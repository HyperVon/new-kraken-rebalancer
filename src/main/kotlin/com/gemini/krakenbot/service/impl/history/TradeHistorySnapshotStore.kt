package com.gemini.krakenbot.service.impl.history

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.impl.PortfolioCalculations
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
                        seedHistoricalSnapshots()
                    } catch (e: CancellationException) {
                        throw e
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

        val now = Instant.ofEpochMilli(Instant.now().toEpochMilli())
        // Empty-DB simulation seed: ~15 days of snapshots at 6-hour steps.
        val startInstant = now.minus(15, ChronoUnit.DAYS)
        val stepHours = 6L
        val steps = (15 * 24) / stepHours
        val snapshotsToSave = mutableListOf<PortfolioSnapshot>()

        val (finalBalances, historicalTrades) = krakenService.withStableBackend { backend ->
            val rawBalances = backend.getBalances()
            val normalizedBalances = allocations.associate { (symbol) ->
                val normalized = symbol.value.uppercase()
                val balance = Asset.possibleBalanceKeys(normalized).firstNotNullOfOrNull(rawBalances::get)
                    ?: BigDecimal.ZERO
                normalized to balance
            }
            val trades = backend
                .getTradeHistory(startInstant.epochSecond, 0)
                .filter { it.success && !it.dryRun }
                .sortedBy(TradeRecord::timestamp)
            normalizedBalances to trades
        }

        // The emulator's balances are its present-day state. Reverse its seeded fills to obtain
        // a historical baseline, then replay those exact fills while producing snapshots. This
        // keeps the demo data realistic and exercises the production reconciliation path.
        val currentBalances = finalBalances.toMutableMap()
        for (trade in historicalTrades.asReversed()) {
            applySeedTrade(currentBalances, trade, reverse = true)
        }

        data class ValuedAsset(
            val symbol: String,
            val targetPercent: Double,
            val balance: BigDecimal,
            val price: BigDecimal,
            val valueUSD: BigDecimal,
        )

        var step = 0
        var nextTradeIndex = 0
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
        for (trade in historicalTrades) {
            repository.saveTrade(trade)
        }
        repository.setHistorySeeded(true)
        historicalTrades.maxOfOrNull { it.timestamp }?.let { latest ->
            repository.setSyncMetadata(SyncMetadataKeys.SYNC_WATERMARK_EPOCH_SEC, latest.epochSecond.toString())
        }
        log.info(
            "Simulation mode: seeded {} historical snapshots and {} trade records",
            snapshotsToSave.size,
            historicalTrades.size,
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
