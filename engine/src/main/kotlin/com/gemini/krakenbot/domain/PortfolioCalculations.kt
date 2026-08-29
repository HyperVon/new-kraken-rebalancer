package com.gemini.krakenbot.domain

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.util.PrecisionConstants
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Shared portfolio math used by [RebalancerEngine] and portfolio analyzers.
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

    /** Sums USD allocations to obtain the USD target percent, falling back to [PrecisionConstants.DEFAULT_USD_TARGET_PERCENT]. */
    fun calculateUsdTargetPercent(allocations: List<Allocation>): BigDecimal = allocations
        .filter { it.symbol.isUsd }
        .takeIf { it.isNotEmpty() }
        ?.sumOf { it.targetPercent.toBigDecimal() }
        ?.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP)
        ?: BigDecimal.valueOf(PrecisionConstants.DEFAULT_USD_TARGET_PERCENT)
            .setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP)

    /** Current allocation percent: `valueUSD / totalPortfolioValueUSD × 100`, or zero when total is zero. */
    fun calculateCurrentPercent(valueUSD: BigDecimal, totalPortfolioValueUSD: BigDecimal): BigDecimal =
        if (totalPortfolioValueUSD > BigDecimal.ZERO) {
            valueUSD
                .multiply(PrecisionConstants.HUNDRED)
                .divide(totalPortfolioValueUSD, PrecisionConstants.SCALE_PERCENT, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }

    /** Target value in USD: `totalPortfolioValueUSD × targetPct / 100`. */
    fun calculateTargetValue(targetPct: BigDecimal, totalPortfolioValueUSD: BigDecimal): BigDecimal =
        totalPortfolioValueUSD
            .multiply(targetPct)
            .divide(PrecisionConstants.HUNDRED, PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP)

    /** Signed USD deviation: `currentValueUSD − targetValueUSD` (positive = overweight). */
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
        /** Dust gate only (`|deviationUSD| ≥ minimumOrderSizeUSD`); percent trigger is applied by callers. */
        val isSignificant: Boolean,
    )

    /** Builds [AssetMetrics] for one symbol, applying ATH/drawdown scaling and the dust gate. */
    fun calculateAssetMetrics(
        symbol: Asset,
        baseTargetPercent: BigDecimal,
        currentValueUSD: BigDecimal,
        totalPortfolioValueUSD: BigDecimal,
        effectiveUsdTarget: BigDecimal,
        cryptoScaleFactor: BigDecimal,
        minimumOrderSizeUSD: Double,
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

        val isSignificant = deviationUSD.abs() >= BigDecimal.valueOf(minimumOrderSizeUSD)

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

    /**
     * Creates a display-ready [PortfolioSnapshot.AssetSnapshot] from precomputed [AssetMetrics],
     * avoiding redundant percent and deviation recalculations.
     */
    fun createAssetSnapshot(
        symbol: String,
        balance: BigDecimal,
        price: BigDecimal,
        valueUSD: BigDecimal,
        metrics: AssetMetrics,
    ): PortfolioSnapshot.AssetSnapshot = PortfolioSnapshot.AssetSnapshot(
        symbol = Asset(symbol),
        balance = balance.setScale(PrecisionConstants.SCALE_CRYPTO, RoundingMode.HALF_UP),
        price = price.setScale(PrecisionConstants.SCALE_CRYPTO, RoundingMode.HALF_UP),
        valueUSD = valueUSD.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP),
        targetPercent = metrics.calcTargetPercent.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP),
        currentPercent = metrics.currentPercent.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP),
        deviationPercent = metrics.deviationPercent.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP),
        deviationUSD = metrics.deviationUSD.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP),
    )

    /**
     * Creates a display-ready [PortfolioSnapshot.AssetSnapshot] with percents rounded to `SCALE_USD`.
     */
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

    private const val SECONDS_PER_DAY = 86_400L
    private const val DELTA_SCALE = 6

    /**
     * 24h percentage change vs the most recent snapshot at least 24h older.
     * Returns null when fewer than two points exist, no ≥24h baseline is available,
     * or the baseline value is zero — never invents a shorter window labeled "24H".
     */
    fun compute24hDelta(latest: PortfolioSnapshot, history: List<PortfolioSnapshot>): BigDecimal? {
        if (history.size < 2) return null
        val cutoff = latest.timestamp.minusSeconds(SECONDS_PER_DAY)
        val past = history.firstOrNull { it.timestamp <= cutoff } ?: return null
        val base = past.totalValueUSD
        if (base.signum() == 0) return null
        return latest.totalValueUSD
            .subtract(base)
            .divide(base, DELTA_SCALE, RoundingMode.HALF_UP)
            .multiply(PrecisionConstants.HUNDRED)
    }
}
