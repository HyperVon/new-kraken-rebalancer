package com.gemini.krakenbot.service.impl.history

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ConfigService
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
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ThreadLocalRandom

class TradeHistorySnapshotStore(
    private val repository: TradeRepository,
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

    fun getHistoryFlow(): Flow<PortfolioSnapshot> = snapshotFlow.asSharedFlow()
}
