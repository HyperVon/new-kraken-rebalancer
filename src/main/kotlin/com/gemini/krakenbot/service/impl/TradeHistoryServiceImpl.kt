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

class TradeHistoryServiceImpl(
    private val repository: TradeRepository,
    private val portfolioStatsRepository: PortfolioStatsRepository,
    private val krakenService: KrakenService,
    private val configService: ConfigService
) : TradeHistoryService {

    private val log = LoggerFactory.getLogger(TradeHistoryServiceImpl::class.java)
    private val history = CopyOnWriteArrayList<PortfolioSnapshot>()
    private val maxHistorySize = 50
    private val snapshotFlow =
        MutableSharedFlow<PortfolioSnapshot>(extraBufferCapacity = 16)

    override fun init() {
        val loaded = repository.load()
        if (loaded.isNotEmpty()) {
            history.addAll(loaded)
        } else {
            val config = configService.getConfig()
            if (config.settings.simulation) {
                try {
                    seedHistoricalSnapshots()
                    val reloaded = repository.load()
                    history.addAll(reloaded)
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
        val random = java.util.Random()

        for (alloc in allocations) {
            val symbol = alloc.symbol.value.uppercase()
            val targetPercent = alloc.targetPercent
            val targetUSD = targetPercent / 100.0 * totalPortfolioValue
            // Slightly drifted initial balance (+/- 15%)
            val drift = 0.85 + random.nextDouble() * 0.30
            val price = currentPrices[symbol] ?: 10.0
            currentBalances[symbol] = (targetUSD * drift) / price
        }

        val now = Instant.now()
        val startInstant = now.minus(15, java.time.temporal.ChronoUnit.DAYS)
        val stepHours = 6L
        val steps = (15 * 24) / stepHours

        for (step in 0..steps) {
            val timestamp = startInstant.plus(step * stepHours, java.time.temporal.ChronoUnit.HOURS)

            // 1. Fluctuate prices
            for (symbol in currentPrices.keys) {
                if (symbol == "USD") continue
                val price = currentPrices[symbol] ?: 10.0
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
                val price = currentPrices[symbol] ?: 10.0
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

            val snapshot = PortfolioSnapshot(
                timestamp = timestamp,
                totalValueUSD = BigDecimal.valueOf(exactPortfolioValue).setScale(2, RoundingMode.HALF_UP),
                assets = assetSnapshots,
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.valueOf(allocations.firstOrNull { it.symbol.isUsd }?.targetPercent ?: 5.0).setScale(2, RoundingMode.HALF_UP)
            )
            repository.saveSnapshot(snapshot)
        }
    }

    override fun addSnapshot(snapshot: PortfolioSnapshot) {
        history.add(0, snapshot)
        if (history.size > maxHistorySize) {
            history.removeLast()
        }
        repository.saveSnapshot(snapshot)
        snapshotFlow.tryEmit(snapshot)
    }

    override fun getHistory(): List<PortfolioSnapshot> = ArrayList(history)

    override fun getLatestSnapshot(): PortfolioSnapshot? = history.firstOrNull()

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
        if (repository.isHistorySeeded()) {
            log.info("Historical trades already seeded in database. Skipping API historical fetch.")
            return
        }

        val apiKey = configService.getConfig().kraken.apiKey.value
        if (apiKey.isBlank() || apiKey == "YOUR_KRAKEN_API_KEY") {
            log.warn("Kraken API key is blank or placeholder. Skipping trade history synchronization.")
            return
        }

        log.info("Starting historical trades synchronization from Kraken API...")

        val latestTradeTime = repository.getLatestTradeTime()
        val startSec = latestTradeTime?.epochSecond

        // Load existing boundary signatures to prevent duplicates
        val existingSignatures = if (latestTradeTime != null) {
            repository.getTradesInRange(latestTradeTime, Instant.now())
                .map { "${it.timestamp.toEpochMilli()}_${it.pair}_${it.side.uppercase()}_${it.volume}_${it.usdAmount}" }
                .toSet()
        } else {
            emptySet()
        }

        var offset = 0

        while (true) {
            log.info("Fetching trade history batch with offset={}", offset)
            val apiTrades = krakenService.getTradeHistory(startSec = startSec, offset = offset)
            if (apiTrades.isEmpty()) {
                break
            }

            var addedInBatch = 0
            for (trade: TradeRecord in apiTrades) {
                val signature = "${trade.timestamp.toEpochMilli()}_${trade.pair}_${trade.side.uppercase()}_${trade.volume}_${trade.usdAmount}"
                if (!existingSignatures.contains(signature)) {
                    repository.saveTrade(trade)
                    addedInBatch++
                }
            }

            log.info("Processed batch: added {} new trades out of {} fetched", addedInBatch, apiTrades.size)

            if (apiTrades.size < 50) {
                break
            }
            offset += 50
        }

        repository.setHistorySeeded(true)
        log.info("Historical trades synchronization completed. Database marked as seeded.")
    }
}
