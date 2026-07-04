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
import kotlinx.coroutines.delay
import com.gemini.krakenbot.model.Asset


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
            val realKrakenService = when (krakenService) {
                is KrakenServiceImpl -> krakenService
                is DynamicKrakenService -> krakenService.realService
                else -> null
            }
            val totalCount = realKrakenService?.lastFetchedCount?.get() ?: 0
            if (!isSeeded) {
                repository.setSyncMetadata("sync_offset", offset.toString())
                repository.setSyncMetadata("sync_total", totalCount.toString())
            }

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

        val snapshots = repository.load()
        val totalTrades = repository.getTotalTradeCount()
        val isSimulation = configService.getConfig().settings.simulation

        if (!isSimulation && totalTrades > 0 && snapshots.size <= 1) {
            log.info("Historical snapshots are missing or insufficient (found {} snapshots, {} trades). Starting reconstruction...", snapshots.size, totalTrades)
            try {
                reconstructHistoricalSnapshots()
                log.info("Historical snapshot reconstruction completed successfully.")
            } catch (e: java.lang.Exception) {
                log.error("Failed to reconstruct historical snapshots", e)
            }
        }

        if (!isSeeded) {
            repository.setHistorySeeded(true)
            repository.setSyncMetadata("sync_offset", "completed")
            repository.setSyncMetadata("sync_total", "completed")
        }
        log.info("Trade history synchronization completed. Added: {} new, Reconciled: {}.", totalAdded, totalReconciled)
    }

    private suspend fun reconstructHistoricalSnapshots() {
        log.info("Starting historical snapshots reconstruction...")
        val allocations = configService.getConfig().allocations

        // 1. Load current snapshots
        val currentSnapshots = repository.load() // DESC
        val oldestSnapshot = currentSnapshots.lastOrNull()

        val cutoffTime = oldestSnapshot?.timestamp ?: Instant.now()

        // 2. Fetch current/starting balances
        val currentBalances = try {
            krakenService.getBalances()
        } catch (e: java.lang.Exception) {
            log.error("Failed to fetch balances for snapshot reconstruction", e)
            emptyMap()
        }

        val runningBalances = mutableMapOf<String, BigDecimal>()
        val currentPrices = mutableMapOf<String, BigDecimal>()

        if (oldestSnapshot != null) {
            for (symbol in oldestSnapshot.assets.keys) {
                runningBalances[symbol] = oldestSnapshot.assets[symbol]?.balance ?: BigDecimal.ZERO
                currentPrices[symbol] = oldestSnapshot.assets[symbol]?.price ?: BigDecimal.ZERO
            }
        } else {
            for (alloc in allocations) {
                val symbol = alloc.symbol.value.uppercase()
                val bal = currentBalances[symbol]
                    ?: currentBalances["X$symbol"]
                    ?: currentBalances["Z$symbol"]
                    ?: currentBalances[Asset.toKrakenTicker(symbol)]
                    ?: currentBalances["X${Asset.toKrakenTicker(symbol)}"]
                    ?: BigDecimal.ZERO
                runningBalances[symbol] = bal
            }
            // Fetch active prices to initialize current prices
            val pairsStr = allocations.filter { !it.symbol.isUsd }.map { Asset.tradingPair(it.symbol.value) }.joinToString(",")
            val prices = try {
                krakenService.getTickerPrices(pairsStr)
            } catch (e: java.lang.Exception) {
                log.error("Failed to fetch starting prices for snapshot reconstruction", e)
                emptyMap()
            }
            for (alloc in allocations) {
                val symbol = alloc.symbol.value.uppercase()
                currentPrices[symbol] = prices[Asset.tradingPair(symbol)] ?: BigDecimal.ZERO
            }
            currentPrices["USD"] = BigDecimal.ONE
        }

        // 3. Fetch OHLC daily close prices for the last 90 days
        val ohlcData = mutableMapOf<String, List<Pair<Long, BigDecimal>>>()
        val sinceSec = Instant.now().minus(95, ChronoUnit.DAYS).epochSecond
        for (alloc in allocations) {
            val symbol = alloc.symbol.value.uppercase()
            if (symbol == "USD") continue
            val pair = Asset.tradingPair(symbol)
            try {
                val prices = krakenService.getOHLC(pair, interval = 1440, since = sinceSec)
                ohlcData[symbol] = prices
                log.info("Fetched {} OHLC close prices for {}", prices.size, symbol)
            } catch (e: java.lang.Exception) {
                log.error("Failed to fetch OHLC prices for $symbol ($pair)", e)
            }
            delay(200)
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

            for (alloc in allocations) {
                val symbol = alloc.symbol.value.uppercase()
                val rawBal = runningBalances[symbol] ?: BigDecimal.ZERO
                val balance = if (rawBal < BigDecimal.ZERO) BigDecimal.ZERO else rawBal
                val price = getPriceForTimestamp(symbol, snapshotTimestamp, ohlcData, tradePrices, currentPrices)
                val valueUSD = balance.multiply(price).setScale(2, RoundingMode.HALF_UP)
                exactPortfolioValue = exactPortfolioValue.add(valueUSD)
            }

            for (alloc in allocations) {
                val symbol = alloc.symbol.value.uppercase()
                val rawBal = runningBalances[symbol] ?: BigDecimal.ZERO
                val balance = if (rawBal < BigDecimal.ZERO) BigDecimal.ZERO else rawBal
                val price = getPriceForTimestamp(symbol, snapshotTimestamp, ohlcData, tradePrices, currentPrices)
                val valueUSD = balance.multiply(price).setScale(2, RoundingMode.HALF_UP)

                val currentPercent = if (exactPortfolioValue > BigDecimal.ZERO) {
                    valueUSD.multiply(BigDecimal(100)).divide(exactPortfolioValue, 2, RoundingMode.HALF_UP)
                } else {
                    BigDecimal.ZERO
                }
                val deviationPercent = currentPercent.subtract(BigDecimal(alloc.targetPercent)).setScale(2, RoundingMode.HALF_UP)
                val deviationUSD = deviationPercent.divide(BigDecimal(100), 4, RoundingMode.HALF_UP).multiply(exactPortfolioValue).setScale(2, RoundingMode.HALF_UP)

                assetSnapshots[symbol] = PortfolioSnapshot.AssetSnapshot(
                    symbol = symbol,
                    balance = balance.setScale(8, RoundingMode.HALF_UP),
                    price = price.setScale(8, RoundingMode.HALF_UP),
                    valueUSD = valueUSD,
                    targetPercent = BigDecimal(alloc.targetPercent).setScale(2, RoundingMode.HALF_UP),
                    currentPercent = currentPercent,
                    deviationPercent = deviationPercent,
                    deviationUSD = deviationUSD
                )
            }

            val targetUsdPercent = BigDecimal(allocations.firstOrNull { it.symbol.isUsd }?.targetPercent ?: 5.0).setScale(2, RoundingMode.HALF_UP)

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

                if (trade.side == "BUY") {
                    runningBalances[symbol] = (runningBalances[symbol] ?: BigDecimal.ZERO).subtract(volume)
                    runningBalances["USD"] = (runningBalances["USD"] ?: BigDecimal.ZERO).add(usdAmount).add(fee)
                } else if (trade.side == "SELL") {
                    runningBalances[symbol] = (runningBalances[symbol] ?: BigDecimal.ZERO).add(volume)
                    runningBalances["USD"] = (runningBalances["USD"] ?: BigDecimal.ZERO).subtract(usdAmount).add(fee)
                }
            }
        }

        if (snapshotsToSave.isNotEmpty()) {
            log.info("Saving {} reconstructed historical snapshots...", snapshotsToSave.size)
            repository.save(snapshotsToSave)
        }
    }

    private fun getPriceForTimestamp(
        symbol: String,
        timestamp: Instant,
        ohlcData: Map<String, List<Pair<Long, BigDecimal>>>,
        tradePrices: Map<String, List<Pair<Instant, BigDecimal>>>,
        currentPrices: Map<String, BigDecimal>
    ): BigDecimal {
        if (symbol.equals("USD", ignoreCase = true)) return BigDecimal.ONE

        val prices = ohlcData[symbol.uppercase()]
        if (prices != null && prices.isNotEmpty()) {
            val targetSec = timestamp.epochSecond
            var closestPrice = prices[0].second
            var minDiff = Math.abs(prices[0].first - targetSec)
            for (p in prices) {
                val diff = Math.abs(p.first - targetSec)
                if (diff < minDiff) {
                    minDiff = diff
                    closestPrice = p.second
                }
            }
            return closestPrice
        }

        val tPrices = tradePrices[symbol.uppercase()]
        if (tPrices != null && tPrices.isNotEmpty()) {
            var closestPrice = tPrices[0].second
            var minDiff = Math.abs(tPrices[0].first.toEpochMilli() - timestamp.toEpochMilli())
            for (p in tPrices) {
                val diff = Math.abs(p.first.toEpochMilli() - timestamp.toEpochMilli())
                if (diff < minDiff) {
                    minDiff = diff
                    closestPrice = p.second
                }
            }
            return closestPrice
        }

        return currentPrices[symbol.uppercase()] ?: BigDecimal.ZERO
    }

    override fun getSyncMetadata(key: String): String? = repository.getSyncMetadata(key)
    override fun setSyncMetadata(key: String, value: String) = repository.setSyncMetadata(key, value)
    override fun isHistorySeeded(): Boolean = repository.isHistorySeeded()
}

private sealed class TimelineEvent : Comparable<TimelineEvent> {
    abstract val timestamp: Instant

    data class TradeEvent(override val timestamp: Instant, val trade: TradeRecord) : TimelineEvent()
    data class DailyCloseEvent(override val timestamp: Instant) : TimelineEvent()

    override fun compareTo(other: TimelineEvent): Int {
        return other.timestamp.compareTo(this.timestamp)
    }
}

