package com.gemini.krakenbot.service.reporting

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.service.history.HistoryObservation
import com.gemini.krakenbot.service.history.HistoryObservationStatus
import com.gemini.krakenbot.util.PrecisionConstants
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * One allocation deviation in percentage points: the observed portfolio share minus the configured
 * target. A holding exactly at its target has zero deviation. One definition serves the
 * server-rendered chart, the summary statistic and the refreshed chart API, so the same sample
 * never changes meaning between them.
 *
 * A complete observation lists every holding, so an asset the portfolio does not hold is worth
 * zero and deviates by its full target. A holding whose value is unknown states no deviation: the
 * sample is incomplete for that asset rather than a confident zero.
 */
internal fun allocationDeviationPercent(
    observation: HistoryObservation,
    symbol: String,
    targetPercent: BigDecimal,
): BigDecimal? {
    if (observation.status != HistoryObservationStatus.COMPLETE) return null
    val totalUsd = observation.totalUsd ?: return null
    if (totalUsd.signum() <= 0) return null
    val holding = observation.assets.firstOrNull { it.symbol == symbol }
    val valueUsd = when {
        holding == null -> BigDecimal.ZERO
        holding.valueUsd == null -> return null
        else -> holding.valueUsd
    }
    val actualPercent = valueUsd
        .multiply(BigDecimal.valueOf(PrecisionConstants.HUNDRED_INT.toLong()))
        .divide(totalUsd, PrecisionConstants.SCALE_PERCENT, RoundingMode.HALF_UP)
    return actualPercent.subtract(targetPercent)
}

/** Configured targets keyed by allocation symbol, in configured order. */
internal fun targetPercentsBySymbol(targets: List<Allocation>): Map<String, BigDecimal> {
    val used = LinkedHashMap<String, BigDecimal>()
    for (allocation in targets) {
        used[allocation.symbol.value] = BigDecimal.valueOf(allocation.targetPercent)
    }
    return used
}
