package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.RawBalances
import com.gemini.krakenbot.service.RawPrices
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.util.toCryptoScale
import com.gemini.krakenbot.util.toUsdScale
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadLocalRandom

class SimulatedKrakenService(private val configService: ConfigService) : KrakenService {
    private val log = LoggerFactory.getLogger(SimulatedKrakenService::class.java)

    private val balances = ConcurrentHashMap<String, BigDecimal>()
    private val simulatedPrices = ConcurrentHashMap<String, BigDecimal>()
    private val simulatedTrades = CopyOnWriteArrayList<TradeRecord>()

    init {
        log.info("Initialized SimulatedKrakenService")
    }

    private fun initializeBalancesAndPricesIfEmpty() {
        if (balances.isNotEmpty()) return

        log.info("SimulatedKrakenService: initializing starting portfolio...")
        val allocations = configService.getConfig().allocations

        // 1. Initialize prices
        for ((symbol) in allocations) {
            val symbolU = symbol.value.uppercase()
            val basePrice = SimulationDefaults.INITIAL_PRICES[symbolU] ?: SimulationDefaults.DEFAULT_PRICE
            simulatedPrices[symbolU] = basePrice.toCryptoScale()
        }
        simulatedPrices[Asset.USD] = BigDecimal.ONE

        // 2. Initialize balances with some random drift (+/- 25%) so they need rebalancing
        val totalSimulatedValueUSD = SimulationDefaults.TOTAL_PORTFOLIO_VALUE_USD
        val random = ThreadLocalRandom.current()

        for ((symbol, targetPercent) in allocations) {
            val symbolU = symbol.value.uppercase()
            val targetUSDValue =
                PortfolioCalculations.calculateTargetValue(
                    BigDecimal.valueOf(targetPercent),
                    totalSimulatedValueUSD,
                )

            // Apply a drift factor between 0.75 and 1.25
            val driftFactor = BigDecimal.valueOf(0.75 + random.nextDouble() * 0.50)
            val driftedUSDValue = targetUSDValue.multiply(driftFactor).toUsdScale()

            if (symbolU == Asset.USD) {
                balances[Asset.USD] = driftedUSDValue
            } else {
                val price = simulatedPrices.getValue(symbolU)
                balances[symbolU] =
                    driftedUSDValue.divide(price, PrecisionConstants.SCALE_CRYPTO, RoundingMode.HALF_UP)
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
        (1..15).forEach { _ ->
            val hoursAgo = random.nextLong(1, 120)
            val timestamp = now.minus(hoursAgo, ChronoUnit.HOURS)
            val alloc = nonUsd[random.nextInt(nonUsd.size)]
            val symbol = alloc.symbol.value.uppercase()
            val pair = Asset.tradingPair(symbol)
            val side = if (random.nextBoolean()) OrderSide.BUY.name else OrderSide.SELL.name
            val price = simulatedPrices.getValue(symbol)
            // Slight noise on the trade price compared to current price
            val tradePrice =
                price
                    .multiply(BigDecimal.valueOf(0.95 + random.nextDouble() * 0.10))
                    .toCryptoScale()
            val usdValue = BigDecimal.valueOf(500.0 + random.nextDouble() * 2500.0).toUsdScale()
            val volume = usdValue.divide(tradePrice, PrecisionConstants.SCALE_CRYPTO, RoundingMode.HALF_UP)

            simulatedTrades.add(
                TradeRecord(
                    timestamp = timestamp,
                    pair = pair,
                    side = side,
                    symbol = symbol,
                    volume = volume,
                    usdAmount = usdValue,
                    success = true,
                    dryRun = false,
                ),
            )
        }
        val sorted = simulatedTrades.sortedBy { it.timestamp }
        simulatedTrades.clear()
        simulatedTrades.addAll(sorted)
    }

    private fun fluctuatePrices() {
        val random = ThreadLocalRandom.current()
        for ((symbol, currentPrice) in simulatedPrices) {
            if (symbol == Asset.USD) continue
            // Random walk between -0.6% and +0.6%
            val changeFactor = BigDecimal.ONE.add(BigDecimal.valueOf((random.nextDouble() - 0.5) * 0.012))
            simulatedPrices[symbol] = currentPrice.multiply(changeFactor).toCryptoScale()
        }
    }

    override suspend fun getBalances(): RawBalances {
        initializeBalancesAndPricesIfEmpty()
        return balances.toMap()
    }

    override suspend fun getTickerPrices(pairs: String): RawPrices {
        initializeBalancesAndPricesIfEmpty()
        fluctuatePrices()

        val results = mutableMapOf<String, BigDecimal>()
        val pairList = pairs.split(",")
        val allocations = configService.getConfig().allocations.map { it.symbol.value }
        for (pair in pairList) {
            val symbol = Asset.fromTradingPair(pair, allocations) ?: pair
            val price = simulatedPrices[symbol] ?: BigDecimal.TEN
            results[pair] = price
        }
        return results
    }

    override suspend fun executeOrder(pair: String, type: String, side: String, volume: BigDecimal): OrderResult {
        initializeBalancesAndPricesIfEmpty()

        val allocations = configService.getConfig().allocations.map { it.symbol.value }
        val symbol = Asset.fromTradingPair(pair, allocations) ?: pair
        val price = simulatedPrices[symbol] ?: BigDecimal.TEN
        val normalizedVolume = volume.toCryptoScale()
        val usdAmount = normalizedVolume.multiply(price).toUsdScale()

        log.info(
            "[EMULATOR] Executing $side order on $pair, volume: $normalizedVolume, " +
                "calculated price: $price ($$usdAmount)",
        )

        if (configService.getConfig().settings.dryRun) {
            log.info("[EMULATOR DRY RUN] Order would execute successfully")
            return OrderResult(
                success = true,
                pair = pair,
                side = side,
                volume = normalizedVolume,
                dryRun = true,
            )
        }

        val usdBalance = balances[Asset.USD] ?: BigDecimal.ZERO
        val tokenBalance = balances[symbol] ?: BigDecimal.ZERO

        if (side.equals(OrderSide.BUY.apiValue, ignoreCase = true)) {
            if (usdBalance < usdAmount) {
                val error =
                    "Insufficient USD funds in emulator balance: needed $usdAmount, had $usdBalance"
                log.warn("[EMULATOR] $error")
                return OrderResult(
                    success = false,
                    pair = pair,
                    side = side,
                    volume = normalizedVolume,
                    errorMessage = error,
                )
            }
            balances[Asset.USD] = usdBalance.subtract(usdAmount).toUsdScale()
            balances[symbol] = tokenBalance.add(normalizedVolume).toCryptoScale()
        } else if (side.equals(OrderSide.SELL.apiValue, ignoreCase = true)) {
            if (tokenBalance < normalizedVolume) {
                val error =
                    "Insufficient $symbol funds in emulator balance: needed $normalizedVolume, had $tokenBalance"
                log.warn("[EMULATOR] $error")
                return OrderResult(
                    success = false,
                    pair = pair,
                    side = side,
                    volume = normalizedVolume,
                    errorMessage = error,
                )
            }
            balances[symbol] = tokenBalance.subtract(normalizedVolume).toCryptoScale()
            balances[Asset.USD] = usdBalance.add(usdAmount).toUsdScale()
        }

        val trade =
            TradeRecord(
                timestamp = Instant.now(),
                pair = pair,
                side = side.uppercase(),
                symbol = symbol,
                volume = normalizedVolume,
                usdAmount = usdAmount,
                success = true,
                dryRun = false,
                price = price.toCryptoScale(),
                fee = BigDecimal.ZERO,
            )
        simulatedTrades.add(trade)

        return OrderResult(
            success = true,
            pair = pair,
            side = side,
            volume = normalizedVolume,
        )
    }

    override suspend fun getTradeHistory(startSec: Long?, offset: Int?): List<TradeRecord> {
        initializeBalancesAndPricesIfEmpty()

        var filtered =
            if (startSec != null) {
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

    override suspend fun getOHLC(pair: String, interval: Int, since: Long?): List<Pair<Long, BigDecimal>> = emptyList()
}
