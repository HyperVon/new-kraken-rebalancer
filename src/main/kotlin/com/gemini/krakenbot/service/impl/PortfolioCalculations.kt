package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.AssetValues
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Consolidated portfolio calculation logic shared between PortfolioManagerImpl and PortfolioAnalyzerImpl.
 * Eliminates duplicate calculation code across the codebase.
 */
object PortfolioCalculations {
    private val log = LoggerFactory.getLogger(PortfolioCalculations::class.java)

    private const val SCALE_PERCENT = 4
    private const val SCALE_USD = 2
    private val HUNDRED = BigDecimal.valueOf(100)

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
     * Calculate deviation percentage.
     */
    fun calculateDeviationPercent(
        deviationUSD: BigDecimal,
        targetValueUSD: BigDecimal,
        currentValueUSD: BigDecimal
    ): BigDecimal =
        when {
            targetValueUSD > BigDecimal.ZERO -> {
                deviationUSD
                    .abs()
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
}
