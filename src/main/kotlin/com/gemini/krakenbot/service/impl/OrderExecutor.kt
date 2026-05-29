package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.util.KrakenSymbols
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Duration.Companion.milliseconds

class OrderExecutor(
    private val krakenService: KrakenService,
    private val configService: ConfigService,
    private val portfolioAnalyzer: PortfolioAnalyzer
) {
    private val log = LoggerFactory.getLogger(OrderExecutor::class.java)

    fun analyzeDeviations(
        totalPortfolioValueUSD: BigDecimal,
        currentValuesUSD: Map<String, BigDecimal>,
        effectiveUsdTarget: BigDecimal,
        cryptoScaleFactor: BigDecimal,
        buyOrders: MutableMap<String, BigDecimal>,
        sellOrders: MutableMap<String, BigDecimal>,
        actionLog: MutableList<String>
    ) {
        val s = configService.getConfig().settings
        var usdTriggered = false
        var usdDeviationAmount = BigDecimal.ZERO
        val allDeviations = mutableMapOf<String, BigDecimal>()

        configService.getConfig().allocations.forEach { a ->
            var targetPct = BigDecimal.valueOf(a.targetPercent)

            targetPct =
                if (a.symbol.equals(KrakenSymbols.USD, ignoreCase = true)) {
                    effectiveUsdTarget
                } else {
                    targetPct.multiply(cryptoScaleFactor)
                }

            targetPct = targetPct.divide(
                BigDecimal.valueOf(100),
                4,
                RoundingMode.HALF_UP
            )
            val targetValue = totalPortfolioValueUSD.multiply(targetPct)
            val currentVal = currentValuesUSD[a.symbol] ?: BigDecimal.ZERO

            val deviationUSD = currentVal.subtract(targetValue)
            var deviationPct = BigDecimal.ZERO

            if (targetValue > BigDecimal.ZERO) {
                deviationPct = deviationUSD.abs()
                    .divide(targetValue, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
            } else if (currentVal > BigDecimal.ZERO) {
                deviationPct = BigDecimal.valueOf(100.0)
            }

            allDeviations[a.symbol] = deviationUSD

            log.info(
                "Analysis [{}]: Dev: {}% ($ {}). Threshold: {}%",
                a.symbol,
                deviationPct,
                deviationUSD.setScale(2, RoundingMode.HALF_UP),
                s.deviationTriggerPercent
            )

            if (deviationPct.toDouble() >= s.deviationTriggerPercent) {
                actionLog.add("Deviation Triggered details: ${a.symbol} Dev: $deviationPct%")
            }

            if (a.symbol.equals(KrakenSymbols.USD, ignoreCase = true)) {
                if (deviationPct.toDouble() >= s.deviationTriggerPercent) {
                    log.info(
                        "Asset USD Deviation: {}% (Trigger: {}%). USD Dev: {}",
                        deviationPct, s.deviationTriggerPercent, deviationUSD
                    )
                    usdTriggered = true
                    usdDeviationAmount = deviationUSD
                }
            } else {
                if (deviationPct.toDouble() >= s.deviationTriggerPercent) {
                    log.info(
                        "Asset {} Deviation: {}% (Trigger: {}%). USD Dev: {}",
                        a.symbol,
                        deviationPct,
                        s.deviationTriggerPercent,
                        deviationUSD
                    )

                    if (deviationUSD > BigDecimal.ZERO) {
                        sellOrders[a.symbol] = deviationUSD
                    } else {
                        buyOrders[a.symbol] = deviationUSD.abs()
                    }
                }
            }
        }

        if (buyOrders.isEmpty() && sellOrders.isEmpty() && usdTriggered) {
            log.info("USD Deviation triggered but no individual asset triggers. Enforcing fiat correction.")
            actionLog.add("USD Deviation Triggered. Enforcing fiat correction.")
            distributeFiatCorrection(
                usdDeviationAmount,
                allDeviations,
                buyOrders,
                sellOrders,
                actionLog
            )
        }
    }

    suspend fun executeOrders(
        buyOrders: Map<String, BigDecimal>,
        sellOrders: Map<String, BigDecimal>,
        currentValuesUSD: Map<String, BigDecimal>,
        prices: Map<String, BigDecimal>,
        s: Settings,
        actionLog: MutableList<String>
    ) {
        var projectedCash =
            currentValuesUSD[KrakenSymbols.USD] ?: BigDecimal.ZERO
        var executedSells = false

        for ((symbol, usdToSell) in sellOrders) {
            if (usdToSell < BigDecimal.valueOf(s.dustThresholdUSD)) {
                log.info("Skipping dust sell for {} ($ {})", symbol, usdToSell)
                actionLog.add("Skipping dust sell for $symbol ($$usdToSell)")
                continue
            }

            val price = prices[symbol] ?: BigDecimal.ZERO
            if (price.compareTo(BigDecimal.ZERO) == 0) continue

            val volume = usdToSell.divide(price, 8, RoundingMode.HALF_UP)
            val pair = KrakenSymbols.tradingPair(symbol)
            val result =
                krakenService.executeOrder(pair, "market", "sell", volume)
            logOrderResult(result, actionLog, symbol, volume, usdToSell, "SELL")
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
            if (price.compareTo(BigDecimal.ZERO) == 0) continue

            val volume = cost.divide(price, 8, RoundingMode.HALF_UP)
            val pair = KrakenSymbols.tradingPair(symbol)
            val result =
                krakenService.executeOrder(pair, "market", "buy", volume)
            logOrderResult(result, actionLog, symbol, volume, cost, "BUY")
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
                        KrakenSymbols.USD,
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
        result: com.gemini.krakenbot.model.OrderResult,
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

    fun distributeFiatCorrection(
        usdDev: BigDecimal,
        allDevs: Map<String, BigDecimal>,
        buyOrders: MutableMap<String, BigDecimal>,
        sellOrders: MutableMap<String, BigDecimal>,
        actionLog: MutableList<String>
    ) {
        val deviationAbs = usdDev.abs()
        val isDeposit = usdDev > BigDecimal.ZERO
        var totalCounterDev = BigDecimal.ZERO
        val candidates = mutableListOf<String>()

        for ((symbol, d) in allDevs) {
            if (symbol.equals(KrakenSymbols.USD, ignoreCase = true)) continue

            if (isDeposit && d < BigDecimal.ZERO) {
                candidates.add(symbol)
                totalCounterDev = totalCounterDev.add(d.abs())
            } else if (!isDeposit && d > BigDecimal.ZERO) {
                candidates.add(symbol)
                totalCounterDev = totalCounterDev.add(d)
            }
        }

        if (totalCounterDev.compareTo(BigDecimal.ZERO) == 0) {
            log.info("Fiat correction required but no suitable counter-balancing assets found.")
            return
        }

        log.info(
            "Distributing Fiat Correction ($${
                deviationAbs.setScale(
                    2,
                    RoundingMode.HALF_UP
                )
            }) among ${candidates.size} candidates. Total Counter-Dev: $${
                totalCounterDev.setScale(
                    2,
                    RoundingMode.HALF_UP
                )
            }"
        )
        actionLog.add(
            "Distributing Fiat Correction ($${
                deviationAbs.setScale(
                    2,
                    RoundingMode.HALF_UP
                )
            }) among ${candidates.size} candidates."
        )

        for (symbol in candidates) {
            val assetDev = allDevs[symbol]!!.abs()
            val ratio =
                assetDev.divide(totalCounterDev, 8, RoundingMode.HALF_UP)
            val share = deviationAbs.multiply(ratio)

            if (isDeposit) {
                buyOrders[symbol] = share
            } else {
                sellOrders[symbol] = share
            }
        }
    }
}
