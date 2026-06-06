package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.model.Asset
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Duration.Companion.milliseconds

class OrderExecutor(
    private val krakenService: KrakenService,
    private val portfolioAnalyzer: PortfolioAnalyzer
) {
    private val log = LoggerFactory.getLogger(OrderExecutor::class.java)

    suspend fun executeOrders(
        buyOrders: Map<String, BigDecimal>,
        sellOrders: Map<String, BigDecimal>,
        currentValuesUSD: Map<String, BigDecimal>,
        prices: Map<String, BigDecimal>,
        s: Settings,
        actionLog: MutableList<String>
    ) {
        var projectedCash =
            currentValuesUSD[Asset.USD] ?: BigDecimal.ZERO
        var executedSells = false

        for ((symbol, usdToSell) in sellOrders) {
            if (usdToSell < BigDecimal.valueOf(s.dustThresholdUSD)) {
                log.info("Skipping dust sell for {} ($ {})", symbol, usdToSell)
                actionLog.add("Skipping dust sell for $symbol ($$usdToSell)")
                continue
            }

            val price = prices[symbol] ?: BigDecimal.ZERO
            if (price.signum() == 0) continue

            val volume = usdToSell.divide(
                price,
                8,
                RoundingMode.HALF_UP
            )
            val pair = Asset.tradingPair(symbol)
            val result =
                krakenService.executeOrder(
                    pair,
                    "market",
                    "sell",
                    volume
                )
            logOrderResult(
                result,
                actionLog,
                symbol,
                volume,
                usdToSell,
                "SELL"
            )
            if (result.success) {
                projectedCash = projectedCash.add(usdToSell)
                executedSells = true
            }
        }

        var actualCash = projectedCash
        if (executedSells && !s.dryRun) {
            actualCash = refreshUsdBalanceAfterSells(projectedCash)
        }

        for ((symbol, originalCost) in buyOrders) {
            var cost = originalCost
            if (cost > actualCash) {
                log.warn(
                    "Not enough cash to buy {}. Cost: {}, Cash: {}. Reducing.",
                    symbol,
                    cost,
                    actualCash
                )
                cost = actualCash.multiply(BigDecimal.valueOf(0.99))
            }

            if (cost < BigDecimal.valueOf(s.dustThresholdUSD)) {
                log.info("Skipping dust buy for {} ($ {})", symbol, cost)
                actionLog.add("Skipping dust buy for $symbol ($$cost)")
                continue
            }

            val price = prices[symbol] ?: BigDecimal.ZERO
            if (price.signum() == 0) continue

            val volume = cost.divide(price, 8, RoundingMode.HALF_UP)
            val pair = Asset.tradingPair(symbol)
            val result =
                krakenService.executeOrder(
                    pair,
                    "market",
                    "buy",
                    volume
                )
            logOrderResult(
                result,
                actionLog,
                symbol,
                volume,
                cost,
                "BUY"
            )
            if (result.success) {
                actualCash = actualCash.subtract(cost)
            }
        }
    }

    private suspend fun refreshUsdBalanceAfterSells(projectedCash: BigDecimal): BigDecimal {
        val maxAttempts = 3
        val delayMs = 250L
        var bestCash = projectedCash

        repeat(maxAttempts) { attempt ->
            delay(delayMs.milliseconds)
            try {
                val updatedBalances = krakenService.getBalances()
                if (updatedBalances.isNotEmpty()) {
                    val usdBalance = portfolioAnalyzer.resolveBalance(
                        Asset.USD,
                        updatedBalances
                    )
                    if (usdBalance > 0) {
                        bestCash = BigDecimal.valueOf(usdBalance)
                        log.info(
                            "Updated USD balance after sells (attempt {}): $${bestCash}",
                            attempt + 1
                        )
                        if (bestCash >= projectedCash.multiply(BigDecimal("0.95"))) {
                            return bestCash
                        }
                    }
                }
            } catch (e: Exception) {
                log.warn(
                    "Failed to fetch updated USD balance (attempt {})",
                    attempt + 1,
                    e
                )
            }
        }

        log.warn("Using best observed USD balance after sell refresh: $${bestCash}")
        return bestCash
    }

    internal fun logOrderResult(
        result: OrderResult,
        actionLog: MutableList<String>,
        symbol: String,
        volume: BigDecimal,
        usdAmount: BigDecimal,
        side: String
    ) {
        if (result.success) {
            val prefix = if (result.dryRun) "[DRY RUN] " else ""
            if (side == "SELL") {
                actionLog.add("${prefix}SELL $symbol Volume: $volume Value: $$usdAmount")
            } else {
                actionLog.add("${prefix}BUY $symbol Volume: $volume Cost: $$usdAmount")
            }
        } else {
            actionLog.add("FAILED $side $symbol: ${result.errorMessage}")
        }
    }

}
