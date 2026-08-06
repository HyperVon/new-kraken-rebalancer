package com.gemini.krakenbot.service.impl.history

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.impl.PortfolioCalculations
import com.gemini.krakenbot.service.impl.RebalancerEngine
import com.gemini.krakenbot.service.impl.SimulationDefaults
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.util.toCryptoScale
import com.gemini.krakenbot.util.toUsdScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.sin

class TradeHistorySnapshotStore(
    private val repository: TradeRepository,
    private val krakenService: KrakenService,
    private val configService: ConfigService,
    private val objectMapper: ObjectMapper,
    private val tradeHistoryFilePath: String = "trade-history.json",
) {
    private val log = LoggerFactory.getLogger(TradeHistorySnapshotStore::class.java)

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

    suspend fun init() {
        try {
            repository.cleanupDuplicateTrades()
        } catch (e: CancellationException) {
            throw e
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
                        } catch (ex: CancellationException) {
                            throw ex
                        } catch (ex: Exception) {
                            log.warn("Failed to rename trade history file to backup", ex)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Failed to migrate trade history file", e)
                }
            } else {
                val config = configService.getConfig()
                if (config.settings.simulation) {
                    try {
                        seedHistoricalData()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.error("Failed to seed historical data", e)
                    }
                }
            }
        }
    }

    private suspend fun seedHistoricalData() {
        log.info("Simulation mode: Seeding historical snapshots and trades in database...")
        val config = configService.getConfig()
        val allocations = config.allocations

        val (finalBalances, historicalTrades, provisionalNow) = fetchSimulationData(allocations)

        val (startInstant, steps, stepHours) = calculateSnapshotGridParameters(historicalTrades, provisionalNow)
        val currentBalances = reverseSeedTrades(finalBalances, historicalTrades)
        val snapshotsToSave =
            buildSnapshotGrid(allocations, currentBalances, historicalTrades, startInstant, steps, stepHours)

        repository.save(snapshotsToSave)
        for (trade in historicalTrades) {
            repository.saveTrade(trade)
        }
        repository.setHistorySeeded(true)
        repository.setSyncMetadata(SyncMetadataKeys.SYNC_WATERMARK_EPOCH_SEC, Instant.now().epochSecond.toString())
        log.info(
            "Simulation mode: seeded {} historical snapshots and {} trade records",
            snapshotsToSave.size,
            historicalTrades.size,
        )
    }

    private data class SimulationData(
        val balances: Map<String, BigDecimal>,
        val trades: List<TradeRecord>,
        val provisionalNow: Instant,
    )

    private data class SnapshotGridParams(val startInstant: Instant, val steps: Int, val stepHours: Long)

    private data class ValuedAsset(
        val symbol: String,
        val targetPercent: Double,
        val balance: BigDecimal,
        val price: BigDecimal,
        val valueUSD: BigDecimal,
    )

    private suspend fun fetchSimulationData(allocations: List<Allocation>): SimulationData {
        val provisionalNow = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        // Fetch 15 days back before the real anchor is known; the snapshot grid is later anchored
        // to the simulator's trade timestamps derived from these fetched trades.
        val provisionalStart = provisionalNow.minus(15, ChronoUnit.DAYS)

        return krakenService.withStableBackend { backend ->
            val rawBalances = backend.getBalances()
            val normalizedBalances = allocations.associate { (symbol) ->
                val normalized = symbol.value.uppercase()
                val balance = Asset.possibleBalanceKeys(normalized).firstNotNullOfOrNull(rawBalances::get)
                    ?: BigDecimal.ZERO
                normalized to balance
            }
            val trades = backend
                .getTradeHistory(provisionalStart.epochSecond, 0)
                .filter { it.success && !it.dryRun }
                .sortedBy(TradeRecord::timestamp)
            SimulationData(normalizedBalances, trades, provisionalNow)
        }
    }

    private fun calculateSnapshotGridParameters(
        historicalTrades: List<TradeRecord>,
        provisionalNow: Instant,
    ): SnapshotGridParams {
        val stepHours = 6L
        val steps = (15 * 24) / stepHours.toInt()
        // Anchor the snapshot grid to the simulator's reference time.
        // The simulator seeds trades at fixed offsets from its `now`; the latest trade lands
        // SimulationDefaults.SEED_LATEST_TRADE_HOURS_AGO before that `now`.
        // Derive simulatorNow ≈ latestTrade + SEED_LATEST_TRADE_HOURS_AGO, then build the 15-day grid.
        val simulatorNow = historicalTrades
            .maxByOrNull { it.timestamp }
            ?.let {
                Instant.ofEpochSecond(
                    it.timestamp.epochSecond + SimulationDefaults.SEED_LATEST_TRADE_HOURS_AGO * 3600,
                ).truncatedTo(ChronoUnit.MILLIS)
            }
            ?: provisionalNow
        val startInstant = simulatorNow.minus(15 * 24 * 3600, ChronoUnit.SECONDS)
        return SnapshotGridParams(startInstant, steps, stepHours)
    }

    private fun reverseSeedTrades(
        finalBalances: Map<String, BigDecimal>,
        historicalTrades: List<TradeRecord>,
    ): MutableMap<String, BigDecimal> {
        // The emulator's balances are its present-day state. Reverse its seeded fills to obtain
        // a historical baseline, then replay those exact fills while producing snapshots. This
        // keeps the demo data realistic and exercises the production reconciliation path.
        val currentBalances = finalBalances.toMutableMap()
        for (trade in historicalTrades.asReversed()) {
            applySeedTrade(currentBalances, trade, reverse = true)
        }
        return currentBalances
    }

    private fun buildSnapshotGrid(
        allocations: List<Allocation>,
        currentBalances: MutableMap<String, BigDecimal>,
        historicalTrades: List<TradeRecord>,
        startInstant: Instant,
        steps: Int,
        stepHours: Long,
    ): List<PortfolioSnapshot> {
        val snapshotsToSave = mutableListOf<PortfolioSnapshot>()
        var step = 0
        var nextTradeIndex = 0
        var runningAth = BigDecimal.ZERO

        while (step <= steps) {
            val timestamp = startInstant.plus(step * stepHours, ChronoUnit.HOURS)

            while (
                nextTradeIndex < historicalTrades.size &&
                historicalTrades[nextTradeIndex].timestamp <= timestamp
            ) {
                applySeedTrade(currentBalances, historicalTrades[nextTradeIndex], reverse = false)
                nextTradeIndex++
            }

            val progress = step.toDouble() / steps.toDouble()
            val snapshot = buildSingleSnapshot(allocations, currentBalances, timestamp, progress, runningAth)
            if (snapshot.totalValueUSD > runningAth) {
                runningAth = snapshot.totalValueUSD
            }
            snapshotsToSave.add(snapshot)
            step++
        }

        return snapshotsToSave
    }

    private fun buildSingleSnapshot(
        allocations: List<Allocation>,
        currentBalances: Map<String, BigDecimal>,
        timestamp: Instant,
        progress: Double,
        currentAth: BigDecimal = BigDecimal.ZERO,
    ): PortfolioSnapshot {
        val valuedAssets =
            allocations.mapIndexed { index, (symbol, targetPercent) ->
                val symbolU = symbol.value.uppercase()
                val balance = currentBalances.getValue(symbolU)
                val price = historicalSeedPrice(symbolU, progress, index)
                ValuedAsset(
                    symbol = symbolU,
                    targetPercent = targetPercent,
                    balance = balance,
                    price = price,
                    valueUSD = balance.multiply(price),
                )
            }
        val exactPortfolioValue =
            valuedAssets
                .fold(BigDecimal.ZERO) { acc, asset -> acc.add(asset.valueUSD) }
                .toUsdScale()

        val settings = configService.getConfig().settings
        val ath = if (currentAth > exactPortfolioValue) currentAth else exactPortfolioValue
        val drawdownPct = RebalancerEngine.calculateDrawdown(exactPortfolioValue, ath)
        val fiatDeploymentPct = RebalancerEngine.calculateFiatDeployment(drawdownPct, settings)
        val effectiveUsdTarget = RebalancerEngine.calculateEffectiveUsdTarget(fiatDeploymentPct, allocations)
        val cryptoScaleFactor = RebalancerEngine.calculateCryptoScaleFactor(effectiveUsdTarget, allocations)

        val assetSnapshots =
            valuedAssets.associate { asset ->
                val symbolAsset = Asset(asset.symbol)
                val metrics =
                    PortfolioCalculations.calculateAssetMetrics(
                        symbol = symbolAsset,
                        baseTargetPercent = BigDecimal.valueOf(asset.targetPercent),
                        currentValueUSD = asset.valueUSD,
                        totalPortfolioValueUSD = exactPortfolioValue,
                        effectiveUsdTarget = effectiveUsdTarget,
                        cryptoScaleFactor = cryptoScaleFactor,
                        dustThresholdUSD = settings.dustThresholdUSD,
                    )
                asset.symbol to
                    PortfolioCalculations.createAssetSnapshot(
                        symbol = asset.symbol,
                        balance = asset.balance,
                        price = asset.price,
                        valueUSD = asset.valueUSD,
                        targetPercent = metrics.calcTargetPercent,
                        totalPortfolioValueUSD = exactPortfolioValue,
                    )
            }

        return PortfolioSnapshot(
            timestamp = timestamp,
            totalValueUSD = exactPortfolioValue,
            assets = assetSnapshots,
            actions = emptyList(),
            drawdownPercent = drawdownPct,
            fiatDeploymentPercent = fiatDeploymentPct,
            effectiveUsdTargetPercent = effectiveUsdTarget,
        )
    }

    private fun applySeedTrade(balances: MutableMap<String, BigDecimal>, trade: TradeRecord, reverse: Boolean) {
        val symbol = trade.symbol.uppercase()
        val direction = if (reverse) -1 else 1
        val assetDelta = if (trade.side.equals("BUY", ignoreCase = true)) trade.volume else trade.volume.negate()
        val usdDelta = if (trade.side.equals("BUY", ignoreCase = true)) {
            trade.usdAmount.add(trade.fee).negate()
        } else {
            trade.usdAmount.subtract(trade.fee)
        }
        balances[symbol] = balances.getValue(symbol).add(assetDelta.multiply(BigDecimal(direction)))
        balances[Asset.USD] = balances.getValue(Asset.USD).add(usdDelta.multiply(BigDecimal(direction)))
    }

    private fun historicalSeedPrice(symbol: String, progress: Double, index: Int): BigDecimal {
        if (symbol == Asset.USD) return BigDecimal.ONE
        val currentPrice = SimulationDefaults.INITIAL_PRICES[symbol] ?: SimulationDefaults.DEFAULT_PRICE
        val startFactor = if (index % 2 == 0) 0.86 else 0.93
        val trend = startFactor + (1.0 - startFactor) * progress
        val broadWave = sin(3.0 * PI * progress) * if (index % 2 == 0) 0.045 else 0.06
        val shortWave = sin((9.0 + index) * PI * progress) * 0.012
        return currentPrice.multiply(BigDecimal.valueOf(trend + broadWave + shortWave)).toCryptoScale()
    }

    suspend fun addSnapshot(snapshot: PortfolioSnapshot) {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to prune old snapshots/trades", e)
        }
        snapshotFlow.tryEmit(snapshot)
    }

    suspend fun saveTrade(trade: TradeRecord): Int = repository.saveTrade(trade)

    suspend fun updateTrade(oldTrade: TradeRecord, newTrade: TradeRecord) = repository.updateTrade(oldTrade, newTrade)

    suspend fun hasPendingSubmissions(): Boolean = repository.hasPendingSubmissions()

    fun getHistoryFlow(): Flow<PortfolioSnapshot> = snapshotFlow.asSharedFlow()
}
