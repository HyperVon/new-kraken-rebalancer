package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.RawBalances
import com.gemini.krakenbot.service.RawPrices
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadLocalRandom

class SimulatedKrakenService(
    private val configService: ConfigService
) : KrakenService {

    private val log = LoggerFactory.getLogger(SimulatedKrakenService::class.java)

    private val balances = ConcurrentHashMap<String, Double>()
    private val simulatedPrices = ConcurrentHashMap<String, Double>()
    private val simulatedTrades = CopyOnWriteArrayList<TradeRecord>()

    private val initialPrices = mapOf(
        "BTC" to 60000.0,
        "ETH" to 3000.0,
        "USD" to 1.0,
        "USDT" to 1.0,
        "USDC" to 1.0,
        "DOGE" to 0.15,
        "SOL" to 140.0,
        "ADA" to 0.50,
        "XRP" to 0.60,
        "DOT" to 6.0,
        "LINK" to 15.0,
        "LTC" to 80.0
    )

    init {
        log.info("Initialized SimulatedKrakenService")
    }

    private fun initializeBalancesAndPricesIfEmpty() {
        if (balances.isNotEmpty()) return

        log.info("SimulatedKrakenService: initializing starting portfolio...")
        val allocations = configService.getConfig().allocations

        // 1. Initialize prices
        for (alloc in allocations) {
            val symbol = alloc.symbol.value.uppercase()
            val basePrice = initialPrices[symbol] ?: 10.0
            simulatedPrices[symbol] = basePrice
        }
        simulatedPrices["USD"] = 1.0

        // 2. Initialize balances with some random drift (+/- 25%) so they need rebalancing
        val totalSimulatedValueUSD = 100000.0
        val random = ThreadLocalRandom.current()

        for (alloc in allocations) {
            val symbol = alloc.symbol.value.uppercase()
            val targetPercent = alloc.targetPercent
            val targetUSDValue = (targetPercent / 100.0) * totalSimulatedValueUSD

            // Apply a drift factor between 0.75 and 1.25
            val driftFactor = 0.75 + random.nextDouble() * 0.50
            val driftedUSDValue = targetUSDValue * driftFactor

            if (symbol == "USD") {
                balances["USD"] = driftedUSDValue
            } else {
                val price = simulatedPrices.getValue(symbol)
                balances[symbol] = driftedUSDValue / price
            }
        }

        // 3. Seed some historical trades
        seedSimulatedTrades()
    }

    private fun seedSimulatedTrades() {
        val now = Instant.now()
        val allocations = configService.getConfig().allocations
        val nonUsd = allocations.filter { !it.symbol.isUsd }
        if (nonUsd.isEmpty()) return

        val random = ThreadLocalRandom.current()
        // Create 15 fake trades spanning the last 5 days
        for (i in 1..15) {
            val hoursAgo = random.nextLong(1, 120)
            val timestamp = now.minus(hoursAgo, ChronoUnit.HOURS)
            val alloc = nonUsd[random.nextInt(nonUsd.size)]
            val symbol = alloc.symbol.value.uppercase()
            val pair = Asset.tradingPair(symbol)
            val side = if (random.nextBoolean()) "BUY" else "SELL"
            val price = simulatedPrices.getValue(symbol)
            // Slight noise on the trade price compared to current price
            val tradePrice = price * (0.95 + random.nextDouble() * 0.10)
            val usdValue = 500.0 + random.nextDouble() * 2500.0
            val volume = usdValue / tradePrice

            simulatedTrades.add(
                TradeRecord(
                    timestamp = timestamp,
                    pair = pair,
                    side = side,
                    symbol = symbol,
                    volume = BigDecimal.valueOf(volume).setScale(8, RoundingMode.HALF_UP),
                    usdAmount = BigDecimal.valueOf(usdValue).setScale(2, RoundingMode.HALF_UP),
                    success = true,
                    dryRun = false
                )
            )
        }
        // Sort by timestamp ascending
        simulatedTrades.sortBy { it.timestamp }
    }

    private fun fluctuatePrices() {
        val random = ThreadLocalRandom.current()
        for ((symbol, currentPrice) in simulatedPrices) {
            if (symbol == "USD") continue
            // Random walk between -0.6% and +0.6%
            val changePercent = (random.nextDouble() - 0.5) * 0.012
            simulatedPrices[symbol] = currentPrice * (1.0 + changePercent)
        }
    }

    override suspend fun getBalances(): RawBalances {
        initializeBalancesAndPricesIfEmpty()
        return balances.mapValues { BigDecimal.valueOf(it.value) }
    }

    override suspend fun getTickerPrices(pairs: String): RawPrices {
        initializeBalancesAndPricesIfEmpty()
        fluctuatePrices()

        val results = mutableMapOf<String, BigDecimal>()
        val pairList = pairs.split(",")
        val allocations = configService.getConfig().allocations.map { it.symbol.value }
        for (pair in pairList) {
            val symbol = Asset.fromTradingPair(pair, allocations) ?: pair
            val price = simulatedPrices[symbol] ?: 10.0
            results[pair] = BigDecimal.valueOf(price)
        }
        return results
    }

    override suspend fun executeOrder(
        pair: String,
        type: String,
        side: String,
        volume: BigDecimal
    ): OrderResult {
        initializeBalancesAndPricesIfEmpty()

        val allocations = configService.getConfig().allocations.map { it.symbol.value }
        val symbol = Asset.fromTradingPair(pair, allocations) ?: pair
        val price = simulatedPrices[symbol] ?: 10.0
        val volDouble = volume.toDouble()
        val usdAmountDouble = volDouble * price

        log.info("[EMULATOR] Executing $side order on $pair, volume: $volume, calculated price: $price ($$usdAmountDouble)")

        if (configService.getConfig().settings.dryRun) {
            log.info("[EMULATOR DRY RUN] Order would execute successfully")
            return OrderResult(
                success = true,
                pair = pair,
                side = side,
                volume = volume,
                dryRun = true
            )
        }

        val usdBalance = balances.getValue("USD")
        val tokenBalance = balances[symbol] ?: 0.0

        if (side.equals("buy", ignoreCase = true)) {
            if (usdBalance < usdAmountDouble) {
                val error = "Insufficient USD funds in emulator balance: needed $usdAmountDouble, had $usdBalance"
                log.warn("[EMULATOR] $error")
                return OrderResult(
                    success = false,
                    pair = pair,
                    side = side,
                    volume = volume,
                    errorMessage = error
                )
            }
            balances["USD"] = usdBalance - usdAmountDouble
            balances[symbol] = tokenBalance + volDouble
        } else if (side.equals("sell", ignoreCase = true)) {
            if (tokenBalance < volDouble) {
                val error = "Insufficient $symbol funds in emulator balance: needed $volDouble, had $tokenBalance"
                log.warn("[EMULATOR] $error")
                return OrderResult(
                    success = false,
                    pair = pair,
                    side = side,
                    volume = volume,
                    errorMessage = error
                )
            }
            balances[symbol] = tokenBalance - volDouble
            balances["USD"] = usdBalance + usdAmountDouble
        }

        val trade = TradeRecord(
            timestamp = Instant.now(),
            pair = pair,
            side = side.uppercase(),
            symbol = symbol,
            volume = volume,
            usdAmount = BigDecimal.valueOf(usdAmountDouble).setScale(2, RoundingMode.HALF_UP),
            success = true,
            dryRun = false,
            price = BigDecimal.valueOf(price).setScale(8, RoundingMode.HALF_UP),
            fee = BigDecimal.ZERO
        )
        simulatedTrades.add(trade)

        return OrderResult(
            success = true,
            pair = pair,
            side = side,
            volume = volume
        )
    }

    override suspend fun getTradeHistory(startSec: Long?, offset: Int?): List<TradeRecord> {
        initializeBalancesAndPricesIfEmpty()

        var filtered = if (startSec != null) {
            val startInstant = Instant.ofEpochSecond(startSec)
            simulatedTrades.filter { it.timestamp.isAfter(startInstant) }
        } else {
            simulatedTrades
        }

        filtered = filtered.sortedBy { it.timestamp }

        if (offset != null && offset < filtered.size) {
            return filtered.drop(offset)
        }
        return filtered
    }
}
