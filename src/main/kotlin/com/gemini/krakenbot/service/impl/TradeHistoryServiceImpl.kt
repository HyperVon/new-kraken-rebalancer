package com.gemini.krakenbot.service.impl

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.HistoryStats
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.model.isMatchingApiTrade
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.PortfolioAnalyzer
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.util.TradeCalculator
import com.gemini.krakenbot.util.toCryptoScale
import com.gemini.krakenbot.util.toUsdScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ThreadLocalRandom
import kotlin.time.Duration.Companion.milliseconds

class TradeHistoryServiceImpl(
    private val repository: TradeRepository,
    private val portfolioStatsRepository: PortfolioStatsRepository,
    private val krakenService: KrakenService,
    private val configService: ConfigService,
    private val objectMapper: ObjectMapper,
    private val portfolioAnalyzer: PortfolioAnalyzer,
    private val tradeHistoryFilePath: String = "trade-history.json",
) : TradeHistoryService {
    private val log = LoggerFactory.getLogger(TradeHistoryServiceImpl::class.java)

    /**
     * A hot SharedFlow that broadcasts newly created portfolio snapshots to all active dashboard SSE connections.
     *
     * - extraBufferCapacity = 16: Allocates a small memory buffer for slow collectors.
     * - onBufferOverflow = BufferOverflow.DROP_OLDEST: Dropping the oldest value ensures tryEmit() is guaranteed
     *   to succeed without suspending. This isolates the core rebalancing loop from slow network dashboard clients.
     */
    private val snapshotFlow =
        MutableSharedFlow<PortfolioSnapshot>(
            replay = 1,
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    @Volatile
    private var lastSyncTime: Instant = Instant.EPOCH

    override suspend fun init() {
        try {
            repository.cleanupDuplicateTrades()
        } catch (e: Exception) {
            log.error("Failed to run duplicate trade cleanup on startup", e)
        }
        val loaded = repository.load()
        if (loaded.isEmpty()) {
            val file = File(tradeHistoryFilePath)
            if (file.exists()) {
                log.info("Found trade-history.json. Migrating snapshots to database...")
                try {
                    val snapshots =
                        objectMapper.readValue(
                            file,
                            object : TypeReference<List<PortfolioSnapshot>>() {},
                        )
                    if (!snapshots.isNullOrEmpty()) {
                        log.info("Loaded {} snapshots from trade-history.json. Saving to SQLite...", snapshots.size)
                        repository.save(snapshots)
                        try {
                            val sourcePath = file.toPath()
                            val targetPath = File("$tradeHistoryFilePath.bak").toPath()
                            withContext(Dispatchers.IO) {
                                Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
                            }
                            log.info("Renamed trade history file to backup successfully.")
                        } catch (ex: Exception) {
                            log.warn("Failed to rename trade history file to backup", ex)
                        }
                    }
                } catch (e: Exception) {
                    log.error("Failed to migrate trade history file", e)
                }
            } else {
                val config = configService.getConfig()
                if (config.settings.simulation) {
                    try {
                        seedHistoricalSnapshots()
                    } catch (e: Exception) {
                        log.error("Failed to seed historical snapshots", e)
                    }
                }
            }
        }
    }

    private suspend fun seedHistoricalSnapshots() {
        log.info("Simulation mode: Seeding historical snapshots in database...")
        val config = configService.getConfig()
        val allocations = config.allocations
        val random = ThreadLocalRandom.current()

        val currentPrices = mutableMapOf<String, BigDecimal>()
        for ((symbol) in allocations) {
            val symbolU = symbol.value.uppercase()
            currentPrices[symbolU] =
                (SimulationDefaults.INITIAL_PRICES[symbolU] ?: SimulationDefaults.DEFAULT_PRICE).toCryptoScale()
        }
        currentPrices[Asset.USD] = BigDecimal.ONE

        val currentBalances = mutableMapOf<String, BigDecimal>()
        val totalPortfolioValue = SimulationDefaults.TOTAL_PORTFOLIO_VALUE_USD

        for ((symbol, targetPercent) in allocations) {
            val symbolU = symbol.value.uppercase()
            val targetUSD =
                PortfolioCalculations.calculateTargetValue(
                    BigDecimal.valueOf(targetPercent),
                    totalPortfolioValue,
                )
            val driftFactor = BigDecimal.valueOf(0.85 + random.nextDouble() * 0.30)
            val driftedUSD = targetUSD.multiply(driftFactor).toUsdScale()
            val price = currentPrices.getValue(symbolU)
            currentBalances[symbolU] =
                driftedUSD.divide(price, PrecisionConstants.SCALE_CRYPTO, RoundingMode.HALF_UP)
        }

        val now = Instant.now()
        // Empty-DB simulation seed: ~15 days of snapshots at 6-hour steps.
        val startInstant = now.minus(15, ChronoUnit.DAYS)
        val stepHours = 6L
        val steps = (15 * 24) / stepHours
        val snapshotsToSave = mutableListOf<PortfolioSnapshot>()

        data class ValuedAsset(
            val symbol: String,
            val targetPercent: Double,
            val balance: BigDecimal,
            val price: BigDecimal,
            val valueUSD: BigDecimal,
        )

        var step = 0
        while (step <= steps) {
            val timestamp = startInstant.plus(step * stepHours, ChronoUnit.HOURS)

            for (symbol in currentPrices.keys) {
                if (symbol == Asset.USD) continue
                val price = currentPrices.getValue(symbol)
                val changeFactor = BigDecimal.ONE.add(BigDecimal.valueOf((random.nextDouble() - 0.5) * 0.03))
                currentPrices[symbol] = price.multiply(changeFactor).toCryptoScale()
            }

            var portfolioValue = BigDecimal.ZERO
            for (symbol in currentBalances.keys) {
                portfolioValue =
                    portfolioValue.add(
                        currentBalances.getValue(symbol).multiply(currentPrices.getValue(symbol)),
                    )
            }
            portfolioValue = portfolioValue.toUsdScale()

            for ((symbol, targetPercent) in allocations) {
                val symbolU = symbol.value.uppercase()
                val targetUSD =
                    PortfolioCalculations.calculateTargetValue(
                        BigDecimal.valueOf(targetPercent),
                        portfolioValue,
                    )
                val driftFactor = BigDecimal.valueOf(0.97 + random.nextDouble() * 0.06)
                val driftedUSD = targetUSD.multiply(driftFactor).toUsdScale()
                val price = currentPrices.getValue(symbolU)
                currentBalances[symbolU] =
                    driftedUSD.divide(price, PrecisionConstants.SCALE_CRYPTO, RoundingMode.HALF_UP)
            }

            val valuedAssets =
                allocations.map { (symbol, targetPercent) ->
                    val symbolU = symbol.value.uppercase()
                    val balance = currentBalances.getValue(symbolU)
                    val price = currentPrices.getValue(symbolU)
                    ValuedAsset(
                        symbol = symbolU,
                        targetPercent = targetPercent,
                        balance = balance,
                        price = price,
                        valueUSD = balance.multiply(price).toUsdScale(),
                    )
                }
            val exactPortfolioValue =
                valuedAssets
                    .fold(BigDecimal.ZERO) { acc, asset -> acc.add(asset.valueUSD) }
                    .toUsdScale()

            val assetSnapshots =
                valuedAssets.associate { asset ->
                    asset.symbol to
                        PortfolioCalculations.createAssetSnapshot(
                            symbol = asset.symbol,
                            balance = asset.balance,
                            price = asset.price,
                            valueUSD = asset.valueUSD,
                            targetPercent = BigDecimal.valueOf(asset.targetPercent),
                            totalPortfolioValueUSD = exactPortfolioValue,
                        )
                }

            val targetUsdPercent =
                allocations
                    .firstOrNull { it.symbol.isUsd }
                    ?.let { BigDecimal.valueOf(it.targetPercent) }
                    ?: BigDecimal.valueOf(PrecisionConstants.DEFAULT_USD_TARGET_PERCENT)

            snapshotsToSave.add(
                PortfolioSnapshot(
                    timestamp = timestamp,
                    totalValueUSD = exactPortfolioValue,
                    assets = assetSnapshots,
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = targetUsdPercent.toUsdScale(),
                ),
            )
            step++
        }

        repository.save(snapshotsToSave)
        log.info("Simulation mode: seeded {} historical snapshots", snapshotsToSave.size)
    }

    override suspend fun addSnapshot(snapshot: PortfolioSnapshot) {
        repository.saveSnapshot(snapshot)
        try {
            val cutoff = Instant.now().minus(PrecisionConstants.HISTORICAL_DAYS_BACK.toLong(), ChronoUnit.DAYS)
            val prunedSnapshots = repository.pruneSnapshotsOlderThan(cutoff)
            if (prunedSnapshots > 0) {
                log.info(
                    "Pruned {} snapshots older than {} days",
                    prunedSnapshots,
                    PrecisionConstants.HISTORICAL_DAYS_BACK,
                )
            }
            val prunedTrades = repository.pruneTradesOlderThan(cutoff)
            if (prunedTrades > 0) {
                log.info(
                    "Pruned {} trades older than {} days",
                    prunedTrades,
                    PrecisionConstants.HISTORICAL_DAYS_BACK,
                )
            }
        } catch (e: Exception) {
            log.error("Failed to prune old snapshots/trades", e)
        }
        snapshotFlow.tryEmit(snapshot)
    }

    override suspend fun getHistory(): List<PortfolioSnapshot> = repository.load()

    override suspend fun getLatestSnapshot(): PortfolioSnapshot? = repository.load().firstOrNull()

    override fun getHistoryFlow(): Flow<PortfolioSnapshot> = snapshotFlow.asSharedFlow()

    override suspend fun saveTrade(trade: TradeRecord) {
        repository.saveTrade(trade)
    }

    override suspend fun getSnapshotsInRange(from: Instant, to: Instant): List<PortfolioSnapshot> =
        repository.getSnapshotsInRange(from, to)

    override suspend fun getTradesInRange(from: Instant, to: Instant): List<TradeRecord> =
        repository.getTradesInRange(from, to)

    override suspend fun getHistoryStats(): HistoryStats {
        val stats = portfolioStatsRepository.load()
        val summary = repository.getTradeSummaryStats()
        return HistoryStats(
            allTimeHigh = stats.allTimeHigh,
            totalTradesExecuted = summary.totalTradesExecuted,
            totalVolumeTraded = summary.totalVolumeTraded,
            totalFeesPaid = summary.totalFeesPaid,
            latestSnapshotTime = summary.latestSnapshotTime,
            avgFeeRatePercent = summary.avgFeeRatePercent,
            avgSlippagePercent = summary.avgSlippagePercent,
            failedTradeCount = summary.failedTradeCount,
            dryRunTradeCount = summary.dryRunTradeCount,
        )
    }

    override suspend fun getHistoryStats(from: Instant, to: Instant): HistoryStats {
        val stats = portfolioStatsRepository.load()
        val summary = if (from ==
            Instant.EPOCH
        ) {
            repository.getTradeSummaryStats()
        } else {
            repository.getTradeSummaryStats(from, to)
        }
        val ath =
            if (from == Instant.EPOCH) {
                val snapshotMax = summary.periodHigh ?: BigDecimal.ZERO
                if (stats.allTimeHigh > snapshotMax) stats.allTimeHigh else snapshotMax
            } else {
                summary.periodHigh ?: BigDecimal.ZERO
            }
        return HistoryStats(
            allTimeHigh = ath,
            totalTradesExecuted = summary.totalTradesExecuted,
            totalVolumeTraded = summary.totalVolumeTraded,
            totalFeesPaid = summary.totalFeesPaid,
            latestSnapshotTime = summary.latestSnapshotTime,
            avgFeeRatePercent = summary.avgFeeRatePercent,
            avgSlippagePercent = summary.avgSlippagePercent,
            failedTradeCount = summary.failedTradeCount,
            dryRunTradeCount = summary.dryRunTradeCount,
        )
    }

    override suspend fun syncTradesFromKraken() {
        val now = Instant.now()
        val elapsedSeconds = Duration.between(lastSyncTime, now).seconds
        // Throttle Kraken history pulls to at most once per 5 minutes.
        if (elapsedSeconds < 300) {
            log.info("Skipping trade history synchronization; last run was only {} seconds ago.", elapsedSeconds)
            return
        }

        val config = configService.getConfig()
        if (!config.settings.simulation && !config.kraken.hasValidCredentials()) {
            log.warn("Kraken API key is blank or placeholder. Skipping trade history synchronization.")
            return
        }

        val isSeeded = repository.isHistorySeeded()
        val latestTradeTime = repository.getLatestTradeTime()

        // Null latest → full history (startSec null). Otherwise overlap by 5 minutes so fills
        // near the previous watermark are re-fetched and reconciled rather than double-inserted.
        // [isHistorySeeded] only gates progress metadata / first-sync completion, not this window.
        val startSec = latestTradeTime?.minusSeconds(300)?.epochSecond

        log.info("Starting trade history synchronization (isSeeded={}, startSec={})...", isSeeded, startSec)

        val queryStart = latestTradeTime?.minusSeconds(300) ?: Instant.EPOCH

        val queryEnd = Instant.now().plusSeconds(300)
        val originalLocalTrades = repository.getTradesInRange(queryStart, queryEnd).toMutableList()

        val allocations = configService.getConfig().allocations.map { it.symbol.value }
        var totalAdded = 0
        var totalReconciled = 0

        getTradeHistoryPaginated(startSec = startSec)
            .collect { apiTrades ->
                for (apiTrade: TradeRecord in apiTrades) {
                    // Local row has requested economics; API has the settle. Match within
                    // tolerances so we update the local row instead of inserting a second History trade.
                    val matchingLocalTrade =
                        originalLocalTrades.find { local ->
                            local.isMatchingApiTrade(apiTrade, allocations)
                        }

                    if (matchingLocalTrade != null) {
                        if (matchingLocalTrade != apiTrade) {
                            val expectedPrice = matchingLocalTrade.expectedPrice
                            val reconciledSlippage =
                                expectedPrice?.let { expected ->
                                    TradeCalculator.calculateSlippage(
                                        apiTrade.side,
                                        apiTrade.price,
                                        expected,
                                    )
                                }
                            val reconciledTrade =
                                apiTrade.copy(
                                    expectedPrice = expectedPrice,
                                    slippagePercent = reconciledSlippage,
                                    source = TradeSource.API_FILL,
                                )
                            log.info(
                                "Reconciling trade record: local (timestamp={}, usdAmount={}) with API (timestamp={}, usdAmount={})",
                                matchingLocalTrade.timestamp,
                                matchingLocalTrade.usdAmount,
                                apiTrade.timestamp,
                                apiTrade.usdAmount,
                            )

                            repository.updateTrade(matchingLocalTrade, reconciledTrade)
                            totalReconciled++
                        }
                        // One local row per API fill in this sync pass.
                        originalLocalTrades.remove(matchingLocalTrade)
                    } else {
                        repository.saveTrade(apiTrade)
                        totalAdded++
                    }
                }
            }

        val snapshots = repository.load()
        val totalTrades = repository.getTradeSummaryStats().totalTradesExecuted
        val isSimulation = configService.getConfig().settings.simulation

        if (!isSimulation && totalTrades > 0 && snapshots.size <= 1) {
            log.info(
                "Historical snapshots are missing or insufficient (found {} snapshots, {} trades). Starting reconstruction...",
                snapshots.size,
                totalTrades,
            )
            try {
                reconstructHistoricalSnapshots()
                log.info("Historical snapshot reconstruction completed successfully.")
            } catch (e: Exception) {
                log.error("Failed to reconstruct historical snapshots", e)
            }
        }

        if (!isSeeded) {
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.SYNC_OFFSET, SyncMetadataKeys.COMPLETED)
            repository.setSyncMetadata(SyncMetadataKeys.SYNC_TOTAL, SyncMetadataKeys.COMPLETED)
        }
        lastSyncTime = Instant.now()
        log.info("Trade history synchronization completed. Added: {} new, Reconciled: {}.", totalAdded, totalReconciled)
    }

    /** Cold paginated Kraken history (page size 50); writes sync_offset/total only until first seed completes. */
    private fun getTradeHistoryPaginated(startSec: Long?): Flow<List<TradeRecord>> = flow {
        val pageSize = 50
        var offset = 0
        val isSeeded = repository.isHistorySeeded()

        while (true) {
            log.info("Fetching trade history batch with offset={}", offset)
            val apiTrades = krakenService.getTradeHistory(startSec = startSec, offset = offset)

            val realKrakenService =
                when (krakenService) {
                    is KrakenServiceImpl -> krakenService
                    is DynamicKrakenService -> krakenService.realService
                    else -> null
                }
            val totalCount = realKrakenService?.lastFetchedCount?.get() ?: 0

            if (!isSeeded) {
                repository.setSyncMetadata(SyncMetadataKeys.SYNC_OFFSET, offset.toString())
                repository.setSyncMetadata(SyncMetadataKeys.SYNC_TOTAL, totalCount.toString())
            }

            if (apiTrades.isEmpty()) break

            emit(apiTrades)

            if (apiTrades.size < pageSize) break
            offset += pageSize
        }
    }

    private suspend fun reconstructHistoricalSnapshots() {
        log.info("Starting historical snapshots reconstruction...")
        val allocations = configService.getConfig().allocations

        // load() is newest-first (DESC); lastOrNull() is the oldest retained snapshot.
        val currentSnapshots = repository.load()
        val oldestSnapshot = currentSnapshots.lastOrNull()

        val cutoffTime = oldestSnapshot?.timestamp ?: Instant.now()

        val fetchedLiveBalances =
            try {
                krakenService.getBalances()
            } catch (e: Exception) {
                log.error("Failed to fetch balances for snapshot reconstruction", e)
                emptyMap()
            }

        if (oldestSnapshot == null && fetchedLiveBalances.isEmpty()) {
            log.warn("Aborting historical snapshot reconstruction: starting balances unavailable.")
            return
        }

        val runningBalances = mutableMapOf<String, BigDecimal>()
        val currentPrices = mutableMapOf<String, BigDecimal>()

        if (oldestSnapshot != null) {
            for ((symbol, asset) in oldestSnapshot.assets) {
                runningBalances[symbol] = asset.balance
                currentPrices[symbol] = asset.price
            }
        } else {
            for ((symbol) in allocations) {
                val symbolU = symbol.value.uppercase()
                val bal = portfolioAnalyzer.resolveBalance(symbolU, fetchedLiveBalances)
                runningBalances[symbolU] = bal
            }
            val pairsStr =
                allocations.filter { !it.symbol.isUsd }.joinToString(",") {
                    Asset.tradingPair(it.symbol.value)
                }
            val prices =
                try {
                    krakenService.getTickerPrices(pairsStr)
                } catch (e: Exception) {
                    log.error("Failed to fetch starting prices for snapshot reconstruction", e)
                    emptyMap()
                }
            for ((symbol) in allocations) {
                val symbolU = symbol.value.uppercase()
                currentPrices[symbolU] = prices[Asset.tradingPair(symbolU)] ?: BigDecimal.ZERO
            }
            currentPrices[Asset.USD] = BigDecimal.ONE
        }

        // Slightly wider than HISTORICAL_DAYS_BACK so daily closes cover the full reconstruction window.
        val ohlcData = mutableMapOf<String, List<Pair<Long, BigDecimal>>>()
        val sinceSec = Instant.now().minus(95, ChronoUnit.DAYS).epochSecond
        for ((symbol) in allocations) {
            val symbolU = symbol.value.uppercase()
            if (symbolU == Asset.USD) continue
            val pair = Asset.tradingPair(symbolU)
            try {
                val prices = krakenService.getOHLC(pair, interval = 1440, since = sinceSec)
                ohlcData[symbolU] = prices
                log.info("Fetched {} OHLC close prices for {}", prices.size, symbolU)
            } catch (e: Exception) {
                log.error("Failed to fetch OHLC prices for $symbolU ($pair)", e)
            }
            delay(200.milliseconds)
        }

        val trades =
            repository
                .getTradesInRange(Instant.now().minus(95, ChronoUnit.DAYS), Instant.now())
                .filter { it.success && !it.dryRun }

        val tradePrices =
            trades
                .groupBy { it.symbol.uppercase() }
                .mapValues { entry ->
                    entry.value.map { Pair(it.timestamp, it.price) }
                }

        val historicalTrades = trades.filter { it.timestamp.isBefore(cutoffTime) }

        val events =
            SnapshotHistoryCalculator.buildTimelineEvents(
                historicalTrades = historicalTrades,
                cutoffTime = cutoffTime,
            )

        val snapshotsToSave =
            SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = events,
                allocations = allocations,
                runningBalances = runningBalances,
                currentPrices = currentPrices,
                ohlcData = ohlcData,
                tradePrices = tradePrices,
            )

        if (snapshotsToSave.isNotEmpty()) {
            log.info("Saving {} reconstructed historical snapshots...", snapshotsToSave.size)
            repository.save(snapshotsToSave)
        }
    }

    override suspend fun getSyncMetadata(key: String): String? = repository.getSyncMetadata(key)

    override suspend fun setSyncMetadata(key: String, value: String) = repository.setSyncMetadata(key, value)

    override suspend fun isHistorySeeded(): Boolean = repository.isHistorySeeded()
}
