package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.model.HistoryStats
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.TradeHistoryService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

import java.math.RoundingMode
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.Random

class TradeHistoryServiceImpl(
    private val repository: TradeRepository,
    private val portfolioStatsRepository: PortfolioStatsRepository,
    private val krakenService: KrakenService,
    private val configService: ConfigService
) : TradeHistoryService {

    private val log = LoggerFactory.getLogger(TradeHistoryServiceImpl::class.java)
    private val snapshotFlow =
        MutableSharedFlow<PortfolioSnapshot>(extraBufferCapacity = 16)
    @Volatile
    private var lastSyncTime: Instant = Instant.EPOCH

    override fun init() {
        val loaded = repository.load()
        if (loaded.isEmpty()) {
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

    private fun seedHistoricalSnapshots() {
        log.info("Simulation mode: Seeding historical snapshots in database...")
        val config = configService.getConfig()
        val allocations = config.allocations

        val initialPrices = mapOf(
            "BTC" to 60000.0,
            "ETH" to 3000.0,
            "USD" to 1.0,
            "USDT" to 1.0,
            "USDC" to 1.0,
            "XRP" to 0.60,
            "DOGE" to 0.15,
            "SOL" to 140.0,
            "ADA" to 0.50,
            "DOT" to 6.0,
            "LINK" to 15.0,
            "LTC" to 80.0
        )

        // Start prices
        val currentPrices = mutableMapOf<String, Double>()
        for (alloc in allocations) {
            val symbol = alloc.symbol.value.uppercase()
            currentPrices[symbol] = initialPrices[symbol] ?: 10.0
        }
        currentPrices["USD"] = 1.0

        // Start balances
        val currentBalances = mutableMapOf<String, Double>()
        val totalPortfolioValue = 100000.0
        val random = Random()

        for (alloc in allocations) {
            val symbol = alloc.symbol.value.uppercase()
            val targetPercent = alloc.targetPercent
            val targetUSD = targetPercent / 100.0 * totalPortfolioValue
            // Slightly drifted initial balance (+/- 15%)
            val drift = 0.85 + random.nextDouble() * 0.30
            val price = currentPrices.getValue(symbol)
            currentBalances[symbol] = (targetUSD * drift) / price
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
                if (symbol == "USD") continue
                val price = currentPrices.getValue(symbol)
                // random fluctuation +/- 1.5%
                val change = (random.nextDouble() - 0.5) * 0.03
                currentPrices[symbol] = price * (1.0 + change)
            }

            // 2. Compute portfolio value and rebalance
            var portfolioValue = 0.0
            for (symbol in currentBalances.keys) {
                portfolioValue += currentBalances[symbol]!! * currentPrices[symbol]!!
            }

            // Rebalance balances towards target allocations
            for (alloc in allocations) {
                val symbol = alloc.symbol.value.uppercase()
                val targetPercent = alloc.targetPercent
                val targetUSD = targetPercent / 100.0 * portfolioValue
                // Keep it close to target, but let it drift slightly (+/- 3%)
                val drift = 0.97 + random.nextDouble() * 0.06
                val price = currentPrices.getValue(symbol)
                currentBalances[symbol] = (targetUSD * drift) / price
            }

            // Recompute exact portfolio value
            var exactPortfolioValue = 0.0
            val assetSnapshots = mutableMapOf<String, PortfolioSnapshot.AssetSnapshot>()

            for (alloc in allocations) {
                val symbol = alloc.symbol.value.uppercase()
                val balance = currentBalances[symbol]!!
                val price = currentPrices[symbol]!!
                val valueUSD = balance * price
                exactPortfolioValue += valueUSD
            }

            for (alloc in allocations) {
                val symbol = alloc.symbol.value.uppercase()
                val balance = currentBalances[symbol]!!
                val price = currentPrices[symbol]!!
                val valueUSD = balance * price
                val currentPercent = (valueUSD / exactPortfolioValue) * 100.0
                val deviationPercent = currentPercent - alloc.targetPercent
                val deviationUSD = deviationPercent / 100.0 * exactPortfolioValue

                assetSnapshots[symbol] = PortfolioSnapshot.AssetSnapshot(
                    symbol = symbol,
                    balance = BigDecimal.valueOf(balance).setScale(8, RoundingMode.HALF_UP),
                    price = BigDecimal.valueOf(price).setScale(8, RoundingMode.HALF_UP),
                    valueUSD = BigDecimal.valueOf(valueUSD).setScale(2, RoundingMode.HALF_UP),
                    targetPercent = BigDecimal.valueOf(alloc.targetPercent).setScale(2, RoundingMode.HALF_UP),
                    currentPercent = BigDecimal.valueOf(currentPercent).setScale(2, RoundingMode.HALF_UP),
                    deviationPercent = BigDecimal.valueOf(deviationPercent).setScale(2, RoundingMode.HALF_UP),
                    deviationUSD = BigDecimal.valueOf(deviationUSD).setScale(2, RoundingMode.HALF_UP)
                )
            }

            var targetUsdPercent = 5.0
            for (alloc in allocations) {
                if (alloc.symbol.isUsd) {
                    targetUsdPercent = alloc.targetPercent
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
        snapshotFlow.tryEmit(snapshot)
    }

    override fun getHistory(): List<PortfolioSnapshot> = repository.load()

    override fun getLatestSnapshot(): PortfolioSnapshot? = repository.load().firstOrNull()

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
        return HistoryStats(
            allTimeHigh = stats.allTimeHigh ?: BigDecimal.ZERO,
            totalTradesExecuted = repository.getTotalTradeCount(),
            totalVolumeTraded = repository.getTotalVolumeTraded(),
            firstSnapshotTime = repository.getFirstSnapshotTime(),
            latestSnapshotTime = repository.getLatestSnapshotTime()
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

        val apiKey = configService.getConfig().kraken.apiKey.value
        if (apiKey.isBlank() || apiKey == "YOUR_KRAKEN_API_KEY") {
            log.warn("Kraken API key is blank or placeholder. Skipping trade history synchronization.")
            return
        }

        val isSeeded = repository.isHistorySeeded()
        val latestTradeTime = repository.getLatestTradeTime()

        // If history is not yet seeded, do a full sync.
        // If history is seeded, do an incremental sync starting from the latest trade minus a 5-minute safety window.
        val startSec = if (latestTradeTime != null) {
            latestTradeTime.minusSeconds(300).epochSecond
        } else {
            null
        }

        log.info("Starting trade history synchronization (isSeeded={}, startSec={})...", isSeeded, startSec)

        // Load existing trades in the query window to perform reconciliation and deduplication.
        val queryStart = latestTradeTime?.minusSeconds(300) ?: Instant.EPOCH

        val originalLocalTrades = repository.getTradesInRange(queryStart, Instant.now()).toMutableList()

        var offset = 0
        var totalAdded = 0
        var totalReconciled = 0

        while (true) {
            log.info("Fetching trade history batch with offset={}", offset)
            val apiTrades = krakenService.getTradeHistory(startSec = startSec, offset = offset)
            if (apiTrades.isEmpty()) {
                break
            }

            for (apiTrade: TradeRecord in apiTrades) {
                // Look for an existing matching trade in the pre-existing local trades.
                // Match criteria: same pair, side, volume, and timestamp within 5 minutes.
                val matchingLocalTrade = originalLocalTrades.find { local ->
                    val diff = local.timestamp.toEpochMilli() - apiTrade.timestamp.toEpochMilli()
                    val absDiff = if (diff < 0) -diff else diff
                    local.pair == apiTrade.pair &&
                            local.side.uppercase() == apiTrade.side.uppercase() &&
                            local.volume.compareTo(apiTrade.volume) == 0 &&
                            absDiff < 300_000
                }

                if (matchingLocalTrade != null) {
                    // Check if we need to update/reconcile it (if the timestamp or usdAmount differs slightly from the official API ones).
                    if (matchingLocalTrade.timestamp != apiTrade.timestamp ||
                        matchingLocalTrade.usdAmount.compareTo(apiTrade.usdAmount) != 0 ||
                        matchingLocalTrade.dryRun != apiTrade.dryRun) {

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

            if (apiTrades.size < 50) {
                break
            }
            offset += 50
        }

        if (!isSeeded) {
            repository.setHistorySeeded(true)
        }
        log.info("Trade history synchronization completed. Added: {} new, Reconciled: {}.", totalAdded, totalReconciled)
    }
}
