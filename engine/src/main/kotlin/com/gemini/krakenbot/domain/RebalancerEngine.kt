package com.gemini.krakenbot.domain

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.Result
import com.gemini.krakenbot.util.PrecisionConstants
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.pow

/**
 * Pure domain calculator for portfolio rebalancing math — no network, database, or
 * logging dependencies. Diagnostics travel through [Result.Failure] messages instead
 * of SLF4J so the module stays dependency-free apart from `:common`.
 */
object RebalancerEngine {
    private const val MISSING_PRICE_MESSAGE_PREFIX = "Price not found for "

    fun resolvePriceFromTicker(symbol: String, rawPrices: RawPrices): BigDecimal {
        val expectedPair = Asset.tradingPair(symbol)
        rawPrices[expectedPair]?.let { return it }

        // Exact pair-alias match only (CQ-1-11 / #69) — never substring contains().
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
                    return Result.Failure(
                        IllegalStateException("$MISSING_PRICE_MESSAGE_PREFIX$symbol"),
                    )
                }
                price = p
            }

            val rawValUSD = balance.multiply(price)
            // Per-asset values stay USD-scaled for order sizing; accumulate raw then round once.
            currentValuesUSD[symbol] = rawValUSD.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP)
            totalPortfolioValueUSD = totalPortfolioValueUSD.add(rawValUSD)
        }

        return Result.Success(
            PortfolioValues(
                totalPortfolioValueUSD.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP),
                currentValuesUSD,
            ),
        )
    }

    fun calculateDrawdown(totalPortfolioValueUSD: BigDecimal, ath: BigDecimal): BigDecimal =
        if (ath > BigDecimal.ZERO && totalPortfolioValueUSD < ath) {
            val diff = ath.subtract(totalPortfolioValueUSD)
            diff
                .multiply(PrecisionConstants.HUNDRED)
                .divide(
                    ath,
                    PrecisionConstants.SCALE_PERCENT,
                    RoundingMode.HALF_UP,
                )
        } else {
            BigDecimal.ZERO
        }

    /**
     * Proportionally adjusts the portfolio All-Time High when external capital flows (deposits or withdrawals) occur.
     *
     * In portfolio management (Modified Dietz / Time-Weighted High-Water Mark):
     * A withdrawal must not artificially manufacture a market crash drawdown.
     * A deposit must not artificially inflate the market-achieved high-water mark.
     *
     * ATH_new = ATH_current * (1 + netFlowUSD / preFlowValueUSD)
     *
     * If preFlowValueUSD <= 0 or currentAth <= 0, the ATH is not adjusted.
     */
    fun adjustAthForCashFlow(currentAth: BigDecimal, preFlowValueUSD: BigDecimal, netFlowUSD: BigDecimal): BigDecimal {
        if (currentAth <= BigDecimal.ZERO || preFlowValueUSD <= BigDecimal.ZERO || netFlowUSD.signum() == 0) {
            return currentAth
        }
        val postFlowValueUSD = preFlowValueUSD.add(netFlowUSD)
        if (postFlowValueUSD <= BigDecimal.ZERO) {
            return BigDecimal.ZERO
        }
        val factor = postFlowValueUSD.divide(
            preFlowValueUSD,
            PrecisionConstants.SCALE_PERCENT + 4,
            RoundingMode.HALF_UP,
        )
        return currentAth.multiply(factor).setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP)
    }

    fun calculateFiatDeployment(drawdownPct: BigDecimal, settings: Settings): BigDecimal {
        if (!settings.fiatDeploymentThresholdPercent.isFinite() ||
            drawdownPct <= BigDecimal.ZERO ||
            settings.fiatMaxDrawdown <= 0.0 ||
            settings.fiatDeploymentExponent <= 0.0
        ) {
            return BigDecimal.ZERO
        }

        val threshold = BigDecimal.valueOf(settings.fiatDeploymentThresholdPercent.coerceAtLeast(0.0))
        if (drawdownPct <= threshold) {
            return BigDecimal.ZERO
        }

        val maxDD = BigDecimal.valueOf(settings.fiatMaxDrawdown)
        val effectiveDrawdown = drawdownPct.subtract(threshold)
        val effectiveMaxDD = maxDD.subtract(threshold)
        if (effectiveMaxDD <= BigDecimal.ZERO) {
            return PrecisionConstants.HUNDRED
        }

        var ratio =
            effectiveDrawdown.divide(
                effectiveMaxDD,
                PrecisionConstants.SCALE_PERCENT + 4,
                RoundingMode.HALF_UP,
            )
        ratio = ratio.coerceAtMost(BigDecimal.ONE)

        // Deploy% = (DD / MaxDD)^exponent × 100; ratio already capped at 1 so Deploy% ≤ 100.
        // Fractional exponents require Double.pow (~15-digit mantissa); at current
        // exponent range 1–3 this is well within SCALE_PERCENT precision. Re-enter
        // BigDecimal immediately, guard non-finite, and re-scale — do not widen the
        // double domain.
        val deployDouble = (ratio.toDouble().pow(settings.fiatDeploymentExponent) * 100.0)
            .takeIf { it.isFinite() } ?: 0.0
        return BigDecimal
            .valueOf(deployDouble)
            .setScale(PrecisionConstants.SCALE_PERCENT, RoundingMode.HALF_UP)
            .coerceAtMost(PrecisionConstants.HUNDRED)
    }

    fun calculateEffectiveUsdTarget(fiatDeploymentPct: BigDecimal, allocations: List<Allocation>): BigDecimal {
        val baseUsdTarget = allocations
            .filter { it.symbol.isUsd }
            .sumOf { it.targetPercent.toBigDecimal() }
        val hasUsdTarget = baseUsdTarget > BigDecimal.ZERO
        val hasNonUsdTarget = allocations.any { !it.symbol.isUsd && it.targetPercent > 0.0 }

        // Shrink configured USD target only when there is a non-zero USD allocation
        // and a positive crypto target can receive the freed allocation.
        return if (fiatDeploymentPct > BigDecimal.ZERO && hasUsdTarget && hasNonUsdTarget) {
            val factor =
                BigDecimal.ONE.subtract(
                    fiatDeploymentPct.divide(
                        PrecisionConstants.HUNDRED,
                        PrecisionConstants.SCALE_PERCENT,
                        RoundingMode.HALF_UP,
                    ),
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

        // Scale every crypto base target so (100 − effectiveUsd) is split in the same proportions.
        val remainingForCrypto = PrecisionConstants.HUNDRED.subtract(effectiveUsdTarget)
        return if (totalNonUsdTarget > BigDecimal.ZERO) {
            remainingForCrypto.divide(
                totalNonUsdTarget,
                PrecisionConstants.SCALE_CRYPTO,
                RoundingMode.HALF_UP,
            )
        } else {
            BigDecimal.ONE
        }
    }

    fun analyzeDeviationsPlan(
        totalPortfolioValueUSD: BigDecimal,
        currentValuesUSD: AssetValues,
        effectiveUsdTarget: BigDecimal,
        cryptoScaleFactor: BigDecimal,
        allocations: List<Allocation>,
        settings: Settings,
    ): RebalancePlan {
        val buyOrders = mutableMapOf<String, BigDecimal>()
        val sellOrders = mutableMapOf<String, BigDecimal>()
        val events = mutableListOf<RebalanceEvent>()
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
                    minimumOrderSizeUSD = settings.minimumOrderSizeUSD,
                )

            allDeviations[symbolVal] = metrics.deviationUSD

            // Both gates required: |Dev%| ≥ trigger and |DevUSD| ≥ dust (isSignificant).
            val triggerThreshold = BigDecimal.valueOf(settings.deviationTriggerPercent)
            val isTriggered =
                metrics.deviationPercent.abs() >= triggerThreshold && metrics.isSignificant

            if (isTriggered) {
                events.add(
                    RebalanceEvent.DeviationTriggered(
                        symbol = symbolVal,
                        deviationPercent = metrics.deviationPercent,
                    ),
                )
            }

            if (symbol.isUsd) {
                // USD never becomes a buy/sell row; only flags a fiat-correction candidate.
                if (isTriggered) {
                    usdTriggered = true
                    usdDeviationAmount = metrics.deviationUSD
                }
            } else {
                if (isTriggered) {
                    // Overweight (positive DevUSD) → sell excess; underweight → buy deficit.
                    if (metrics.deviationUSD > BigDecimal.ZERO) {
                        sellOrders[symbolVal] = metrics.deviationUSD
                    } else {
                        buyOrders[symbolVal] = metrics.deviationUSD.abs()
                    }
                }
            }
        }

        // Fiat correction only when USD alone triggered (deposit/withdrawal); skip if crypto
        // already produced orders so we do not double-spend the same cash move.
        if (buyOrders.isEmpty() && sellOrders.isEmpty() && usdTriggered) {
            events.add(RebalanceEvent.FiatCorrectionEnforced)
            distributeFiatCorrectionPlan(
                usdDev = usdDeviationAmount,
                allDevs = allDeviations,
                buyOrders = buyOrders,
                sellOrders = sellOrders,
                events = events,
            )
        }

        return RebalancePlan(buyOrders, sellOrders, events)
    }

    fun distributeFiatCorrectionPlan(
        usdDev: BigDecimal,
        allDevs: AssetDeviations,
        buyOrders: MutableRebalanceOrders,
        sellOrders: MutableRebalanceOrders,
        events: MutableList<RebalanceEvent>,
    ) {
        // Positive USD DevUSD = surplus cash (deposit) → buy underweights; negative = shortage → sell overweights.
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
            events.add(RebalanceEvent.NoCounterBalancingAssets)
            return
        }

        events.add(
            RebalanceEvent.FiatCorrectionDistributed(
                usdAmount = deviationAbs,
                candidateCount = candidates.size,
            ),
        )

        // Truncate rather than round the budget so the shares can never sum above the
        // deviation we are actually correcting (CQ-3-26 / #76).
        var remaining = deviationAbs.setScale(PrecisionConstants.SCALE_USD, RoundingMode.DOWN)

        for (symbol in candidates) {
            val assetDev = allDevs.getValue(symbol).abs()
            val ratio =
                assetDev.divide(
                    totalCounterDev,
                    PrecisionConstants.SCALE_CRYPTO,
                    RoundingMode.HALF_UP,
                )
            val share = deviationAbs.multiply(ratio).toUsdScale().min(remaining)

            // A tiny counter-deviation rounds to $0.00, and HALF_UP shares can collectively
            // exceed the budget; either way there is nothing left to trade for this symbol.
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
