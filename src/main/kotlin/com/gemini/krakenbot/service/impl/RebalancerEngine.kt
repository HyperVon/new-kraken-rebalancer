package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.Result
import com.gemini.krakenbot.service.AnalysisResult
import com.gemini.krakenbot.service.AssetDeviations
import com.gemini.krakenbot.service.AssetPrices
import com.gemini.krakenbot.service.AssetValues
import com.gemini.krakenbot.service.MutableRebalanceOrders
import com.gemini.krakenbot.service.PortfolioValues
import com.gemini.krakenbot.service.RawBalances
import com.gemini.krakenbot.service.RawPrices
import com.gemini.krakenbot.service.impl.PortfolioCalculations.HUNDRED
import com.gemini.krakenbot.service.impl.PortfolioCalculations.SCALE_PERCENT
import com.gemini.krakenbot.service.impl.PortfolioCalculations.SCALE_PRICE
import com.gemini.krakenbot.service.impl.PortfolioCalculations.SCALE_USD
import com.gemini.krakenbot.util.ActionLogFormatter
import com.gemini.krakenbot.util.resolveBalance
import com.gemini.krakenbot.util.toUsdScale
import com.gemini.krakenbot.view.util.ViewText
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.pow

/**
 * Pure, side-effect-free domain engine for portfolio rebalancing math.
 * Contains zero network, I/O, or database dependencies.
 */
object RebalancerEngine {
    private val log = LoggerFactory.getLogger(RebalancerEngine::class.java)

    fun resolvePriceFromTicker(symbol: String, rawPrices: RawPrices): BigDecimal {
        val expectedPair = Asset.tradingPair(symbol)
        rawPrices[expectedPair]?.let { return it }

        for ((key, value) in rawPrices) {
            if (Asset.matchesUsdQuotedPair(key, symbol)) {
                return value
            }
        }
        return BigDecimal.ZERO
    }

    fun calculatePortfolioValues(
        balances: RawBalances,
        prices: AssetPrices,
        allocations: List<Allocation>,
    ): Result<PortfolioValues> {
        val currentValuesUSD = mutableMapOf<String, BigDecimal>()
        var totalPortfolioValueUSD = BigDecimal.ZERO

        for ((asset, _) in allocations) {
            val symbol = asset.value
            val balance = resolveBalance(symbol, balances)
            var price = BigDecimal.ONE

            if (!asset.isUsd) {
                val p = prices[symbol] ?: BigDecimal.ZERO
                if (p.signum() == 0) {
                    log.error(
                        "Price not found for {}. Aborting rebalance cycle to prevent erroneous trades.",
                        symbol,
                    )
                    return Result.Failure(
                        IllegalStateException("${ViewText.PRICE_NOT_FOUND_PREFIX}$symbol"),
                    )
                }
                price = p
            }

            val rawValUSD = balance.multiply(price)
            currentValuesUSD[symbol] = rawValUSD.setScale(SCALE_USD, RoundingMode.HALF_UP)
            totalPortfolioValueUSD = totalPortfolioValueUSD.add(rawValUSD)
        }

        return Result.Success(
            PortfolioValues(
                totalPortfolioValueUSD.setScale(SCALE_USD, RoundingMode.HALF_UP),
                currentValuesUSD,
            ),
        )
    }

    fun calculateDrawdown(totalPortfolioValueUSD: BigDecimal, ath: BigDecimal): BigDecimal =
        if (ath > BigDecimal.ZERO && totalPortfolioValueUSD < ath) {
            val diff = ath.subtract(totalPortfolioValueUSD)
            diff
                .divide(
                    ath,
                    SCALE_PERCENT,
                    RoundingMode.HALF_UP,
                ).multiply(HUNDRED)
        } else {
            BigDecimal.ZERO
        }

    fun calculateFiatDeployment(drawdownPct: BigDecimal, settings: Settings): BigDecimal {
        if (settings.fiatMaxDrawdown <= 0.0) return BigDecimal.ZERO

        val maxDD = BigDecimal.valueOf(settings.fiatMaxDrawdown)
        var ratio =
            drawdownPct.divide(
                maxDD,
                SCALE_PERCENT,
                RoundingMode.HALF_UP,
            )
        ratio = ratio.coerceAtMost(BigDecimal.ONE)

        val deployDouble = ratio.toDouble().pow(settings.fiatDeploymentExponent) * 100.0
        return BigDecimal
            .valueOf(deployDouble)
            .setScale(SCALE_PERCENT, RoundingMode.HALF_UP)
    }

    fun calculateEffectiveUsdTarget(fiatDeploymentPct: BigDecimal, allocations: List<Allocation>): BigDecimal {
        val baseUsdTarget = allocations
            .filter { it.symbol.isUsd }
            .sumOf { it.targetPercent.toBigDecimal() }

        return if (fiatDeploymentPct > BigDecimal.ZERO) {
            val factor =
                BigDecimal.ONE.subtract(
                    fiatDeploymentPct.divide(HUNDRED, SCALE_PERCENT, RoundingMode.HALF_UP),
                )
            baseUsdTarget.multiply(factor)
        } else {
            baseUsdTarget
        }
    }

    fun calculateCryptoScaleFactor(effectiveUsdTarget: BigDecimal, allocations: List<Allocation>): BigDecimal {
        val totalNonUsdTarget = allocations
            .filter { !it.symbol.isUsd }
            .sumOf { it.targetPercent.toBigDecimal() }

        val remainingForCrypto = HUNDRED.subtract(effectiveUsdTarget)
        return if (totalNonUsdTarget > BigDecimal.ZERO) {
            remainingForCrypto.divide(
                totalNonUsdTarget,
                SCALE_PRICE,
                RoundingMode.HALF_UP,
            )
        } else {
            BigDecimal.ONE
        }
    }

    fun analyzeDeviations(
        totalPortfolioValueUSD: BigDecimal,
        currentValuesUSD: AssetValues,
        effectiveUsdTarget: BigDecimal,
        cryptoScaleFactor: BigDecimal,
        allocations: List<Allocation>,
        settings: Settings,
    ): AnalysisResult {
        val buyOrders = mutableMapOf<String, BigDecimal>()
        val sellOrders = mutableMapOf<String, BigDecimal>()
        val actionLog = mutableListOf<String>()
        var usdTriggered = false
        var usdDeviationAmount = BigDecimal.ZERO
        val allDeviations = mutableMapOf<String, BigDecimal>()

        allocations.forEach { (symbol, targetPercent) ->
            val symbolVal = symbol.value
            val currentVal = currentValuesUSD[symbolVal] ?: BigDecimal.ZERO

            val metrics =
                PortfolioCalculations.calculateAssetMetrics(
                    symbol = symbol,
                    baseTargetPercent = BigDecimal.valueOf(targetPercent),
                    currentValueUSD = currentVal,
                    totalPortfolioValueUSD = totalPortfolioValueUSD,
                    effectiveUsdTarget = effectiveUsdTarget,
                    cryptoScaleFactor = cryptoScaleFactor,
                    dustThresholdUSD = settings.dustThresholdUSD,
                )

            allDeviations[symbolVal] = metrics.deviationUSD

            log.info(
                "Analysis [{}]: Dev: {}% ($ {}). Threshold: {}%",
                symbolVal,
                metrics.deviationPercent,
                metrics.deviationUSD.setScale(SCALE_USD, RoundingMode.HALF_UP),
                settings.deviationTriggerPercent,
            )

            val triggerThreshold = BigDecimal.valueOf(settings.deviationTriggerPercent)
            val isTriggered =
                metrics.deviationPercent.abs() >= triggerThreshold && metrics.isSignificant

            if (isTriggered) {
                actionLog.add(
                    ActionLogFormatter.formatDeviationTrigger(symbolVal, metrics.deviationPercent),
                )
            }

            if (symbol.isUsd) {
                if (isTriggered) {
                    log.info(
                        "Asset USD Deviation: {}% (Trigger: {}%). USD Dev: {}",
                        metrics.deviationPercent,
                        settings.deviationTriggerPercent,
                        metrics.deviationUSD,
                    )
                    usdTriggered = true
                    usdDeviationAmount = metrics.deviationUSD
                }
            } else {
                if (isTriggered) {
                    log.info(
                        "Asset {} Deviation: {}% (Trigger: {}%). USD Dev: {}",
                        symbolVal,
                        metrics.deviationPercent,
                        settings.deviationTriggerPercent,
                        metrics.deviationUSD,
                    )

                    if (metrics.deviationUSD > BigDecimal.ZERO) {
                        sellOrders[symbolVal] = metrics.deviationUSD
                    } else {
                        buyOrders[symbolVal] = metrics.deviationUSD.abs()
                    }
                }
            }
        }

        if (buyOrders.isEmpty() && sellOrders.isEmpty() && usdTriggered) {
            log.info(
                "USD Deviation triggered but no individual asset triggers. " +
                    "Enforcing fiat correction.",
            )
            actionLog.add(ActionLogFormatter.formatFiatCorrectionEnforced())
            distributeFiatCorrection(
                usdDev = usdDeviationAmount,
                allDevs = allDeviations,
                buyOrders = buyOrders,
                sellOrders = sellOrders,
                actionLog = actionLog,
            )
        }

        return AnalysisResult(buyOrders, sellOrders, actionLog)
    }

    fun distributeFiatCorrection(
        usdDev: BigDecimal,
        allDevs: AssetDeviations,
        buyOrders: MutableRebalanceOrders,
        sellOrders: MutableRebalanceOrders,
        actionLog: MutableList<String>,
    ) {
        val deviationAbs = usdDev.abs()
        val isDeposit = usdDev > BigDecimal.ZERO
        var totalCounterDev = BigDecimal.ZERO
        val candidates = mutableListOf<String>()

        for ((symbol, d) in allDevs) {
            if (symbol.equals(Asset.USD, ignoreCase = true)) continue

            if (isDeposit && d < BigDecimal.ZERO) {
                candidates.add(symbol)
                totalCounterDev = totalCounterDev.add(d.abs())
            } else if (!isDeposit && d > BigDecimal.ZERO) {
                candidates.add(symbol)
                totalCounterDev = totalCounterDev.add(d)
            }
        }

        if (totalCounterDev.signum() == 0) {
            log.info(
                "Fiat correction required but no suitable " +
                    "counter-balancing assets found.",
            )
            return
        }

        log.info(
            "Distributing Fiat Correction ($${
                deviationAbs.setScale(
                    SCALE_USD,
                    RoundingMode.HALF_UP,
                )
            }) among ${candidates.size} candidates. Total Counter-Dev: $${
                totalCounterDev.setScale(
                    SCALE_USD,
                    RoundingMode.HALF_UP,
                )
            }",
        )
        actionLog.add(ActionLogFormatter.formatFiatCorrectionDistribution(deviationAbs, candidates.size))

        var remaining = deviationAbs.setScale(SCALE_USD, RoundingMode.DOWN)

        for (symbol in candidates) {
            val assetDev = allDevs.getValue(symbol).abs()
            val ratio =
                assetDev.divide(
                    totalCounterDev,
                    SCALE_PRICE,
                    RoundingMode.HALF_UP,
                )
            val share = deviationAbs.multiply(ratio).toUsdScale().min(remaining)

            if (share.signum() <= 0) continue
            remaining = remaining.subtract(share)

            if (isDeposit) {
                buyOrders[symbol] = share
            } else {
                sellOrders[symbol] = share
            }
        }
    }
}
