package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.util.HUNDRED
import com.gemini.krakenbot.util.PrecisionConstants
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Shared portfolio math used by [RebalancerEngine] and [PortfolioAnalyzerImpl].
 */
object PortfolioCalculations {
    /**
     * Effective target % after ATH/drawdown fiat deployment: USD uses [effectiveUsdTarget]
     * directly; crypto is [baseTargetPercent] × [cryptoScaleFactor] so reduced USD redistributes
     * across crypto and totals still sum to 100%.
     */
    fun calculateTargetPercent(
        symbol: Asset,
        baseTargetPercent: BigDecimal,
        effectiveUsdTarget: BigDecimal,
        cryptoScaleFactor: BigDecimal,
    ): BigDecimal = if (symbol.isUsd) {
        effectiveUsdTarget
    } else {
        baseTargetPercent.multiply(cryptoScaleFactor)
    }

    fun calculateUsdTargetPercent(allocations: List<Allocation>): BigDecimal = allocations
        .filter { it.symbol.isUsd }
        .takeIf { it.isNotEmpty() }
        ?.sumOf { it.targetPercent.toBigDecimal() }
        ?.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP)
        ?: BigDecimal.valueOf(PrecisionConstants.DEFAULT_USD_TARGET_PERCENT)
            .setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP)

    fun calculateCurrentPercent(valueUSD: BigDecimal, totalPortfolioValueUSD: BigDecimal): BigDecimal =
        if (totalPortfolioValueUSD >
            BigDecimal.ZERO
        ) {
            valueUSD
                .multiply(PrecisionConstants.HUNDRED)
                .divide(totalPortfolioValueUSD, PrecisionConstants.SCALE_PERCENT, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }

    fun calculateTargetValue(targetPct: BigDecimal, totalPortfolioValueUSD: BigDecimal): BigDecimal =
        totalPortfolioValueUSD
            .multiply(targetPct)
            .divide(PrecisionConstants.HUNDRED, PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP)

    fun calculateDeviationUSD(currentValueUSD: BigDecimal, targetValueUSD: BigDecimal): BigDecimal =
        currentValueUSD.subtract(targetValueUSD)

    /**
     * Signed relative deviation: `(current − target) / target × 100`.
     * When target is $0 but the position still has value, returns 100% so a zero-target holding
     * can still clear the percent trigger (paired with the dust gate in [calculateAssetMetrics]).
     */
    fun calculateDeviationPercent(
        deviationUSD: BigDecimal,
        targetValueUSD: BigDecimal,
        currentValueUSD: BigDecimal,
    ): BigDecimal = when {
        targetValueUSD > BigDecimal.ZERO -> {
            deviationUSD
                .multiply(PrecisionConstants.HUNDRED)
                .divide(targetValueUSD, PrecisionConstants.SCALE_PERCENT, RoundingMode.HALF_UP)
        }
        currentValueUSD > BigDecimal.ZERO -> PrecisionConstants.HUNDRED
        else -> BigDecimal.ZERO
    }

    data class AssetMetrics(
        val symbol: Asset,
        val baseTargetPercent: BigDecimal,
        val calcTargetPercent: BigDecimal,
        val currentPercent: BigDecimal,
        val deviationUSD: BigDecimal,
        val deviationPercent: BigDecimal,
        val targetValueUSD: BigDecimal,
        /** Dust gate only (`|deviationUSD| ≥ dustThresholdUSD`); percent trigger is applied by callers. */
        val isSignificant: Boolean,
    )

    fun calculateAssetMetrics(
        symbol: Asset,
        baseTargetPercent: BigDecimal,
        currentValueUSD: BigDecimal,
        totalPortfolioValueUSD: BigDecimal,
        effectiveUsdTarget: BigDecimal,
        cryptoScaleFactor: BigDecimal,
        dustThresholdUSD: Double,
    ): AssetMetrics {
        val calcTargetPct =
            calculateTargetPercent(
                symbol,
                baseTargetPercent,
                effectiveUsdTarget,
                cryptoScaleFactor,
            )

        val currentPct = calculateCurrentPercent(currentValueUSD, totalPortfolioValueUSD)
        val targetValueUSD = calculateTargetValue(calcTargetPct, totalPortfolioValueUSD)
        val deviationUSD = calculateDeviationUSD(currentValueUSD, targetValueUSD)
        val deviationPct = calculateDeviationPercent(deviationUSD, targetValueUSD, currentValueUSD)

        val isSignificant = deviationUSD.abs() >= BigDecimal.valueOf(dustThresholdUSD)

        return AssetMetrics(
            symbol = symbol,
            baseTargetPercent = baseTargetPercent,
            calcTargetPercent = calcTargetPct,
            currentPercent = currentPct,
            deviationUSD = deviationUSD,
            deviationPercent = deviationPct,
            targetValueUSD = targetValueUSD,
            isSignificant = isSignificant,
        )
    }

    fun createAssetSnapshot(
        symbol: String,
        balance: BigDecimal,
        price: BigDecimal,
        valueUSD: BigDecimal,
        targetPercent: BigDecimal,
        totalPortfolioValueUSD: BigDecimal,
    ): PortfolioSnapshot.AssetSnapshot {
        val currentPercent = calculateCurrentPercent(valueUSD, totalPortfolioValueUSD)
        val targetValueUSD = calculateTargetValue(targetPercent, totalPortfolioValueUSD)
        val deviationUSD = calculateDeviationUSD(valueUSD, targetValueUSD)
        val deviationPercent = calculateDeviationPercent(deviationUSD, targetValueUSD, valueUSD)

        // Snapshot percents use SCALE_USD (2) for display; analysis math keeps SCALE_PERCENT (4).
        return PortfolioSnapshot.AssetSnapshot(
            symbol = Asset(symbol),
            balance = balance.setScale(PrecisionConstants.SCALE_CRYPTO, RoundingMode.HALF_UP),
            price = price.setScale(PrecisionConstants.SCALE_CRYPTO, RoundingMode.HALF_UP),
            valueUSD = valueUSD.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP),
            targetPercent = targetPercent.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP),
            currentPercent = currentPercent.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP),
            deviationPercent = deviationPercent.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP),
            deviationUSD = deviationUSD.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP),
        )
    }
}
