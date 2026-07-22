package com.gemini.krakenbot.service.impl

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.model.*
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.PortfolioAnalyzer
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.view.util.SyncMetadataKeys
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
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
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

private const val USD = "USD"
private const val BUY = "BUY"
private const val SELL = "SELL"

class TradeHistoryServiceImpl(
    private val repository: TradeRepository,
    private val portfolioStatsRepository: PortfolioStatsRepository,
    private val krakenService: KrakenService,
    private val configService: ConfigService,
    private val objectMapper: ObjectMapper,
    private val portfolioAnalyzer: PortfolioAnalyzer,
    private val tradeHistoryFilePath: String = "trade-history.json"
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
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    @Volatile
    private var lastSyncTime: Instant = Instant.EPOCH

    override fun init() {
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
                    val snapshots = objectMapper.readValue(
                        file,
                        object : TypeReference<List<PortfolioSnapshot>>() {}
                    )
                    if (!snapshots.isNullOrEmpty()) {
                        log.info("Loaded {} snapshots from trade-history.json. Saving to SQLite...", snapshots.size)
                        repository.save(snapshots)
                        try {
                            val sourcePath = file.toPath()
                            val targetPath = File("$tradeHistoryFilePath.bak").toPath()
                            Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
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

    private fun seedHistoricalSnapshots() {
        log.info("Simulation mode: Seeding historical snapshots in database...")
        val config = configService.getConfig()
        val allocations = config.allocations

        // Start prices
        val currentPrices = mutableMapOf<String, Double>()
        for ((symbol) in allocations) {
            val symbolU = symbol.value.uppercase()
            currentPrices[symbolU] = SimulationDefaults.INITIAL_PRICES[symbolU] ?: 10.0
        }
        currentPrices[USD] = 1.0

        // Start balances
        val currentBalances = mutableMapOf<String, Double>()
        val totalPortfolioValue = 100000.0
        val random = ThreadLocalRandom.current()

        for ((symbol, targetPercent) in allocations) {
            val symbolU = symbol.value.uppercase()
            val targetUSD = targetPercent / 100.0 * totalPortfolioValue
            // Slightly drifted initial balance (+/- 15%)
            val drift = 0.85 + random.nextDouble() * 0.30
            val price = currentPrices.getValue(symbolU)
            currentBalances[symbolU] = (targetUSD * drift) / price
        }

        val now = Instant.now()
        val startInstant = now.minus(15, ChronoUnit.DAYS)
        val stepHours = 6L
        val steps = (15 * 24) / stepHours

        var step = 0
        while (step <= steps) {
            val timestamp = startInstant.plus(step * stepHours, ChronoUnit.HOURS)

            // 1. Fluctuate prices
            for (symbol in currentPrices.keys) {
                if (symbol == USD) continue
                val price = currentPrices.getValue(symbol)
                // random fluctuation +/- 1.5%
                val change = (random.nextDouble() - 0.5) * 0.03
                currentPrices[symbol] = price * (1.0 + change)
            }

            // 2. Compute portfolio value and rebalance
            var portfolioValue = 0.0
            for (symbol in currentBalances.keys) {
                portfolioValue += currentBalances.getValue(symbol) * currentPrices.getValue(symbol)
            }

            // Rebalance balances towards target allocations
            for ((symbol, targetPercent) in allocations) {
                val symbolU = symbol.value.uppercase()
                val targetUSD = targetPercent / 100.0 * portfolioValue
                // Keep it close to target, but let it drift slightly (+/- 3%)
                val drift = 0.97 + random.nextDouble() * 0.06
                val price = currentPrices.getValue(symbolU)
                currentBalances[symbolU] = (targetUSD * drift) / price
            }

            // Recompute exact portfolio value
            var exactPortfolioValue = 0.0
            val assetSnapshots = mutableMapOf<String, PortfolioSnapshot.AssetSnapshot>()

            for ((symbol) in allocations) {
                val symbolU = symbol.value.uppercase()
                val balance = currentBalances.getValue(symbolU)
                val price = currentPrices.getValue(symbolU)
                val valueUSD = balance * price
                exactPortfolioValue += valueUSD
            }

            for ((symbol, targetPercent) in allocations) {
                val symbolU = symbol.value.uppercase()
                val balance = currentBalances.getValue(symbolU)
                val price = currentPrices.getValue(symbolU)
                assetSnapshots[symbolU] = PortfolioCalculations.createAssetSnapshot(
                    symbol = symbolU,
                    balance = BigDecimal.valueOf(balance),
                    price = BigDecimal.valueOf(price),
                    valueUSD = BigDecimal.valueOf(balance * price),
                    targetPercent = BigDecimal.valueOf(targetPercent),
                    totalPortfolioValueUSD = BigDecimal.valueOf(exactPortfolioValue)
                )
            }

            var targetUsdPercent = 5.0
            for ((symbol, targetPercent) in allocations) {
                if (symbol.isUsd) {
                    targetUsdPercent = targetPercent
                }
            }

            val snapshot = PortfolioSnapshot(
                timestamp = timestamp,
                totalValueUSD = BigDecimal.valueOf(exactPortfolioValue).setScale(2, RoundingMode.HALF_UP),
                assets = assetSnapshots,
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.valueOf(targetUsdPercent).setScale(2, RoundingMode.HALF_UP)
            )
            repository.saveSnapshot(snapshot)
            step++
        }
    }

    override fun addSnapshot(snapshot: PortfolioSnapshot) {
        repository.saveSnapshot(snapshot)
        try {
            val cutoff = Instant.now().minus(90, ChronoUnit.DAYS)
            val pruned = repository.pruneSnapshotsOlderThan(cutoff)
            if (pruned > 0) {
                log.info("Pruned {} snapshots older than 90 days", pruned)
            }
        } catch (e: Exception) {
            log.error("Failed to prune old snapshots", e)
        }
        // tryEmit() succeeds instantly and synchronously because of DROP_OLDEST backpressure strategy.
        snapshotFlow.tryEmit(snapshot)
    }

    override fun getHistory(): List<PortfolioSnapshot> = repository.load()

    override fun getLatestSnapshot(): PortfolioSnapshot? = repository.load().firstOrNull()

    /**
     * Exposes the internal mutable shared flow as a read-only Flow for streaming updates.
     */
    override fun getHistoryFlow(): Flow<PortfolioSnapshot> =
        snapshotFlow.asSharedFlow()

    override fun saveTrade(trade: TradeRecord) {
        repository.saveTrade(trade)
    }

    override fun getSnapshotsInRange(
        from: Instant,
        to: Instant
    ): List<PortfolioSnapshot> {
        return repository.getSnapshotsInRange(from, to)
    }

    override fun getTradesInRange(
        from: Instant,
        to: Instant
    ): List<TradeRecord> {
        return repository.getTradesInRange(from, to)
    }

    override fun getHistoryStats(): HistoryStats {
        val stats = portfolioStatsRepository.load()
        val summary = repository.getTradeSummaryStats()
        return HistoryStats(
            allTimeHigh = stats.allTimeHigh,
            totalTradesExecuted = summary.totalTradesExecuted,
            totalVolumeTraded = summary.totalVolumeTraded,
            totalFeesPaid = summary.totalFeesPaid,
            latestSnapshotTime = summary.latestSnapshotTime
        )
    }

    override fun getHistoryStats(from: Instant, to: Instant): HistoryStats {
        val stats = portfolioStatsRepository.load()
        val summary = if (from == Instant.EPOCH) repository.getTradeSummaryStats() else repository.getTradeSummaryStats(from, to)
        val ath = if (from == Instant.EPOCH) {
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
            latestSnapshotTime = summary.latestSnapshotTime
        )
    }

    override suspend fun syncTradesFromKraken() {
        val now = Instant.now()
        val elapsedSeconds = Duration.between(lastSyncTime, now).seconds
        if (elapsedSeconds < 300) {
            log.info("Skipping trade history synchronization; last run was only {} seconds ago.", elapsedSeconds)
            return
        }
        lastSyncTime = now

        val config = configService.getConfig()
        if (!config.settings.simulation && !config.kraken.isConfigured) {
            log.warn("Kraken API key is blank or placeholder. Skipping trade history synchronization.")
            return
        }

        val isSeeded = repository.isHistorySeeded()
        val latestTradeTime = repository.getLatestTradeTime()

        // If history is not yet seeded, do a full sync.
        // If history is seeded, do an incremental sync starting from the latest trade minus a 5-minute safety window.
        val startSec = latestTradeTime?.minusSeconds(300)?.epochSecond

        log.info("Starting trade history synchronization (isSeeded={}, startSec={})...", isSeeded, startSec)

        // Load existing trades in the query window to perform reconciliation and deduplication.
        val queryStart = latestTradeTime?.minusSeconds(300) ?: Instant.EPOCH

        val queryEnd = Instant.now().plusSeconds(300)
        val originalLocalTrades = repository.getTradesInRange(queryStart, queryEnd).toMutableList()

        val allocations = configService.getConfig().allocations.map { it.symbol.value }
        var totalAdded = 0
        var totalReconciled = 0

        getTradeHistoryPaginated(startSec = startSec)
            .collect { apiTrades ->
                for (apiTrade: TradeRecord in apiTrades) {
                    // The local order record has requested values; the API record has actual
                    // fills. Match their small expected variances so the API record reconciles
                    // the local one instead of appearing as a second trade in History.
                    val matchingLocalTrade = originalLocalTrades.find { local ->
                        local.isMatchingApiTrade(apiTrade, allocations)
                    }

                    if (matchingLocalTrade != null) {
                        if (matchingLocalTrade != apiTrade) {

                            log.info("Reconciling trade record: local (timestamp={}, usdAmount={}) with API (timestamp={}, usdAmount={})",
                                matchingLocalTrade.timestamp, matchingLocalTrade.usdAmount, apiTrade.timestamp, apiTrade.usdAmount)

                            repository.updateTrade(matchingLocalTrade, apiTrade)
                            totalReconciled++
                        }
                        // Remove matched trade so it cannot be matched again in this sync run
                        originalLocalTrades.remove(matchingLocalTrade)
                    } else {
                        // No matching local trade found -> Save it as a new trade record
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
                totalTrades
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
        log.info("Trade history synchronization completed. Added: {} new, Reconciled: {}.", totalAdded, totalReconciled)
    }



    /**
     * A cold Flow that fetches trade history from Kraken paginated by 50.
     *
     * Because it is a cold Flow:
     * 1. No network calls are made until the caller collects from it.
     * 2. It fetches page-by-page lazily; emitting each page using emit().
     * 3. The emitting suspends until the collector finishes processing the current batch, providing
     *    automatic backpressure to prevent overloading the system or API rate limits.
     * 4. Once all batches are fetched, the Flow completes and the collector's loop naturally finishes.
     */
    private fun getTradeHistoryPaginated(
        startSec: Long?
    ): Flow<List<TradeRecord>> = flow {
        val pageSize = 50
        var offset = 0
        val isSeeded = repository.isHistorySeeded()

        while (true) {
            log.info("Fetching trade history batch with offset={}", offset)
            val apiTrades = krakenService.getTradeHistory(startSec = startSec, offset = offset)

            val realKrakenService = when (krakenService) {
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

        // 1. Load current snapshots
        val currentSnapshots = repository.load() // DESC
        val oldestSnapshot = currentSnapshots.lastOrNull()

        val cutoffTime = oldestSnapshot?.timestamp ?: Instant.now()

        val currentBalances = try {
            krakenService.getBalances()
        } catch (e: Exception) {
            log.error("Failed to fetch balances for snapshot reconstruction", e)
            emptyMap()
        }

        if (oldestSnapshot == null && currentBalances.isEmpty()) {
            log.warn("Aborting historical snapshot reconstruction: starting balances unavailable.")
            return
        }

        val runningBalances = mutableMapOf<String, BigDecimal>()
        val currentPrices = mutableMapOf<String, BigDecimal>()

        if (oldestSnapshot != null) {
            for (symbol in oldestSnapshot.assets.keys) {
                runningBalances[symbol] = oldestSnapshot.assets[symbol]?.balance ?: BigDecimal.ZERO
                currentPrices[symbol] = oldestSnapshot.assets[symbol]?.price ?: BigDecimal.ZERO
            }
        } else {
            for ((symbol) in allocations) {
                val symbolU = symbol.value.uppercase()
                val bal = portfolioAnalyzer.resolveBalance(symbolU, currentBalances)
                runningBalances[symbolU] = bal
            }
            // Fetch active prices to initialize current prices
            val pairsStr =
                allocations.filter { !it.symbol.isUsd }.joinToString(",") {
                    Asset.tradingPair(it.symbol.value)
                }
            val prices = try {
                krakenService.getTickerPrices(pairsStr)
            } catch (e: Exception) {
                log.error("Failed to fetch starting prices for snapshot reconstruction", e)
                emptyMap()
            }
            for ((symbol) in allocations) {
                val symbolU = symbol.value.uppercase()
                currentPrices[symbolU] = prices[Asset.tradingPair(symbolU)] ?: BigDecimal.ZERO
            }
            currentPrices[USD] = BigDecimal.ONE
        }

        // 3. Fetch OHLC daily close prices for the last 90 days
        val ohlcData = mutableMapOf<String, List<Pair<Long, BigDecimal>>>()
        val sinceSec = Instant.now().minus(95, ChronoUnit.DAYS).epochSecond
        for ((symbol) in allocations) {
            val symbolU = symbol.value.uppercase()
            if (symbolU == USD) continue
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

        // 4. Fetch all trades from database
        val trades = repository.getTradesInRange(Instant.now().minus(95, ChronoUnit.DAYS), Instant.now())
            .filter { it.success && !it.dryRun }


        val tradePrices = trades.groupBy { it.symbol.uppercase() }
            .mapValues { entry ->
                entry.value.map { Pair(it.timestamp, it.price) }
            }

        val historicalTrades = trades.filter { it.timestamp.isBefore(cutoffTime) }

        // 5. Build timeline events
        val events = mutableListOf<TimelineEvent>()
        for (trade in historicalTrades) {
            events.add(TimelineEvent.TradeEvent(trade.timestamp, trade))
        }

        // Add daily close events for the last 90 days
        val now = Instant.now()
        for (day in 0..90) {
            val dailyTime = now.minus(day.toLong(), ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.DAYS)
                .plus(23, ChronoUnit.HOURS)
                .plus(59, ChronoUnit.MINUTES)
                .plus(59, ChronoUnit.SECONDS)
            if (dailyTime.isBefore(cutoffTime)) {
                events.add(TimelineEvent.DailyCloseEvent(dailyTime))
            }
        }

        events.sort() // Sort DESC (newest first)

        val snapshotsToSave = mutableListOf<PortfolioSnapshot>()

        for (ev in events) {
            val snapshotTimestamp = ev.timestamp

            // Compute portfolio snapshot details
            var exactPortfolioValue = BigDecimal.ZERO
            val assetSnapshots = mutableMapOf<String, PortfolioSnapshot.AssetSnapshot>()

            val calculatedAssets = allocations.map { alloc ->
                val symbol = alloc.symbol.value.uppercase()
                val rawBal = runningBalances[symbol] ?: BigDecimal.ZERO
                val balance = if (rawBal < BigDecimal.ZERO) BigDecimal.ZERO else rawBal
                val price = getPriceForTimestamp(symbol, snapshotTimestamp, ohlcData, tradePrices, currentPrices)
                val valueUSD = balance.multiply(price).setScale(2, RoundingMode.HALF_UP)
                exactPortfolioValue = exactPortfolioValue.add(valueUSD)
                CalculatedAsset(symbol, balance, price, valueUSD, alloc.targetPercent)
            }

            for ((symbol, balance, price, valueUSD, targetPercent) in calculatedAssets) {
                assetSnapshots[symbol] = PortfolioCalculations.createAssetSnapshot(
                    symbol = symbol,
                    balance = balance,
                    price = price,
                    valueUSD = valueUSD,
                    targetPercent = BigDecimal(targetPercent),
                    totalPortfolioValueUSD = exactPortfolioValue
                )
            }

            val targetUsdPercent =
                BigDecimal(allocations.firstOrNull { it.symbol.isUsd }?.targetPercent ?: 5.0).setScale(2, RoundingMode.HALF_UP)

            val snapshot = PortfolioSnapshot(
                timestamp = snapshotTimestamp,
                totalValueUSD = exactPortfolioValue.setScale(2, RoundingMode.HALF_UP),
                assets = assetSnapshots,
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = targetUsdPercent
            )

            snapshotsToSave.add(snapshot)

            // If it is a trade event, reverse apply it to runningBalances
            if (ev is TimelineEvent.TradeEvent) {
                val trade = ev.trade
                val volume = trade.volume
                val usdAmount = trade.usdAmount
                val fee = trade.fee
                val symbol = trade.symbol.uppercase()

                if (trade.side == BUY) {
                    runningBalances[symbol] = (runningBalances[symbol] ?: BigDecimal.ZERO).subtract(volume)
                    runningBalances[USD] = (runningBalances[USD] ?: BigDecimal.ZERO).add(usdAmount).add(fee)
                } else if (trade.side == SELL) {
                    runningBalances[symbol] = (runningBalances[symbol] ?: BigDecimal.ZERO).add(volume)
                    runningBalances[USD] = (runningBalances[USD] ?: BigDecimal.ZERO).subtract(usdAmount).add(fee)
                }
            }
        }

        if (snapshotsToSave.isNotEmpty()) {
            log.info("Saving {} reconstructed historical snapshots...", snapshotsToSave.size)
            repository.save(snapshotsToSave)
        }
    }

    private fun <T> findClosest(
        list: List<T>,
        targetTime: Long,
        timeExtractor: (T) -> Long,
        valueExtractor: (T) -> BigDecimal
    ): BigDecimal {
        var closestValue = valueExtractor(list[0])
        var minDiff = abs(timeExtractor(list[0]) - targetTime)
        for (item in list) {
            val diff = abs(timeExtractor(item) - targetTime)
            if (diff < minDiff) {
                minDiff = diff
                closestValue = valueExtractor(item)
            }
        }
        return closestValue
    }

    private fun getPriceForTimestamp(
        symbol: String,
        timestamp: Instant,
        ohlcData: Map<String, List<Pair<Long, BigDecimal>>>,
        tradePrices: Map<String, List<Pair<Instant, BigDecimal>>>,
        currentPrices: Map<String, BigDecimal>
    ): BigDecimal {
        if (symbol.equals(USD, ignoreCase = true)) return BigDecimal.ONE

        val prices = ohlcData[symbol.uppercase()]
        if (!prices.isNullOrEmpty()) {
            return findClosest(
                prices,
                timestamp.epochSecond,
                { it.first },
                { it.second }
            )
        }

        val tPrices = tradePrices[symbol.uppercase()]
        if (!tPrices.isNullOrEmpty()) {
            return findClosest(
                tPrices,
                timestamp.toEpochMilli(),
                { it.first.toEpochMilli() },
                { it.second }
            )
        }

        return currentPrices[symbol.uppercase()] ?: BigDecimal.ZERO
    }

    override fun getSyncMetadata(key: String): String? = repository.getSyncMetadata(key)
    override fun setSyncMetadata(key: String, value: String) = repository.setSyncMetadata(key, value)
    override fun isHistorySeeded(): Boolean = repository.isHistorySeeded()
}

private data class CalculatedAsset(
    val symbol: String,
    val balance: BigDecimal,
    val price: BigDecimal,
    val valueUSD: BigDecimal,
    val targetPercent: Double
)

private sealed class TimelineEvent : Comparable<TimelineEvent> {
    abstract val timestamp: Instant

    data class TradeEvent(override val timestamp: Instant, val trade: TradeRecord) : TimelineEvent()
    data class DailyCloseEvent(override val timestamp: Instant) : TimelineEvent()

    override fun compareTo(other: TimelineEvent): Int {
        return other.timestamp.compareTo(this.timestamp)
    }
}
