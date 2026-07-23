package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.util.HUNDRED
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Consolidated portfolio calculation logic shared between PortfolioManagerImpl and PortfolioAnalyzerImpl.
 * Eliminates duplicate calculation code across the codebase.
 */
object PortfolioCalculations {

    internal const val SCALE_PERCENT = PrecisionConstants.SCALE_PERCENT
    internal const val SCALE_USD = PrecisionConstants.SCALE_USD
    internal const val SCALE_PRICE = PrecisionConstants.SCALE_CRYPTO
    internal val HUNDRED = PrecisionConstants.HUNDRED

    /**
     * Calculate target percentage for an asset based on allocation type.
     */
    fun calculateTargetPercent(
        symbol: Asset,
        baseTargetPercent: BigDecimal,
        effectiveUsdTarget: BigDecimal,
        cryptoScaleFactor: BigDecimal
    ): BigDecimal =
        if (symbol.isUsd) {
            effectiveUsdTarget
        } else {
            baseTargetPercent.multiply(cryptoScaleFactor)
        }

    /**
     * Calculate current percentage for an asset.
     */
    fun calculateCurrentPercent(
        valueUSD: BigDecimal,
        totalPortfolioValueUSD: BigDecimal
    ): BigDecimal =
        if (totalPortfolioValueUSD > BigDecimal.ZERO) {
            valueUSD
                .divide(totalPortfolioValueUSD, SCALE_PERCENT, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
        } else {
            BigDecimal.ZERO
        }

    /**
     * Calculate target value in USD based on target percentage.
     */
    fun calculateTargetValue(
        targetPct: BigDecimal,
        totalPortfolioValueUSD: BigDecimal
    ): BigDecimal =
        totalPortfolioValueUSD
            .multiply(targetPct)
            .divide(HUNDRED, SCALE_USD, RoundingMode.HALF_UP)

    /**
     * Calculate deviation in USD.
     */
    fun calculateDeviationUSD(
        currentValueUSD: BigDecimal,
        targetValueUSD: BigDecimal
    ): BigDecimal =
        currentValueUSD.subtract(targetValueUSD)

    /**
     * Calculate deviation percentage (signed relative deviation).
     */
    fun calculateDeviationPercent(
        deviationUSD: BigDecimal,
        targetValueUSD: BigDecimal,
        currentValueUSD: BigDecimal
    ): BigDecimal =
        when {
            targetValueUSD > BigDecimal.ZERO -> {
                deviationUSD
                    .divide(targetValueUSD, SCALE_PERCENT, RoundingMode.HALF_UP)
                    .multiply(HUNDRED)
            }
            currentValueUSD > BigDecimal.ZERO -> HUNDRED
            else -> BigDecimal.ZERO
        }

    /**
     * Data class for holding calculated asset metrics.
     */
    data class AssetMetrics(
        val symbol: Asset,
        val baseTargetPercent: BigDecimal,
        val calcTargetPercent: BigDecimal,
        val currentPercent: BigDecimal,
        val deviationUSD: BigDecimal,
        val deviationPercent: BigDecimal,
        val targetValueUSD: BigDecimal,
        val isSignificant: Boolean
    )

    /**
     * Calculate all metrics for a single asset in one operation.
     */
    fun calculateAssetMetrics(
        symbol: Asset,
        baseTargetPercent: BigDecimal,
        currentValueUSD: BigDecimal,
        totalPortfolioValueUSD: BigDecimal,
        effectiveUsdTarget: BigDecimal,
        cryptoScaleFactor: BigDecimal,
        dustThresholdUSD: Double
    ): AssetMetrics {
        val calcTargetPct = calculateTargetPercent(
            symbol,
            baseTargetPercent,
            effectiveUsdTarget,
            cryptoScaleFactor
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
            isSignificant = isSignificant
        )
    }

    /**
     * Create an AssetSnapshot using consolidated calculations.
     */
    fun createAssetSnapshot(
        symbol: String,
        balance: BigDecimal,
        price: BigDecimal,
        valueUSD: BigDecimal,
        targetPercent: BigDecimal,
        totalPortfolioValueUSD: BigDecimal
    ): PortfolioSnapshot.AssetSnapshot {
        val currentPercent = calculateCurrentPercent(valueUSD, totalPortfolioValueUSD)
        val targetValueUSD = calculateTargetValue(targetPercent, totalPortfolioValueUSD)
        val deviationUSD = calculateDeviationUSD(valueUSD, targetValueUSD)
        val deviationPercent = calculateDeviationPercent(deviationUSD, targetValueUSD, valueUSD)

        return PortfolioSnapshot.AssetSnapshot(
            symbol = Asset(symbol),
            balance = balance.setScale(8, RoundingMode.HALF_UP),
            price = price.setScale(8, RoundingMode.HALF_UP),
            valueUSD = valueUSD.setScale(2, RoundingMode.HALF_UP),
            targetPercent = targetPercent.setScale(2, RoundingMode.HALF_UP),
            currentPercent = currentPercent.setScale(2, RoundingMode.HALF_UP),
            deviationPercent = deviationPercent.setScale(2, RoundingMode.HALF_UP),
            deviationUSD = deviationUSD.setScale(2, RoundingMode.HALF_UP)
        )
    }
}
