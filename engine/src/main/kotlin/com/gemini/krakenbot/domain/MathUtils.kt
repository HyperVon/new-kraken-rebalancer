package com.gemini.krakenbot.domain

import java.math.BigDecimal
import java.math.RoundingMode

/** Parses a decimal string; returns [default] (ZERO) on null/blank/NumberFormatException — never throws. */
fun safeParseBigDecimal(value: String?, default: BigDecimal = BigDecimal.ZERO): BigDecimal = value?.let {
    try {
        BigDecimal(it)
    } catch (_: NumberFormatException) {
        default
    }
} ?: default

fun safeParseBigDecimal(
    value: String?,
    scale: Int,
    mode: RoundingMode = RoundingMode.HALF_UP,
    default: BigDecimal = BigDecimal.ZERO,
): BigDecimal = safeParseBigDecimal(value, default).setScale(scale, mode)

/** True when |a−b| / max(|a|,|b|) ≤ [tolerance] (default 0.01 = 1% relative); equals always pass. */
fun isWithinRelativeTolerance(
    first: BigDecimal,
    second: BigDecimal,
    tolerance: BigDecimal = BigDecimal("0.01"),
): Boolean {
    if (first.compareTo(second) == 0) return true
    val largerAmount = maxOf(first.abs(), second.abs())
    return largerAmount.signum() > 0 &&
        first
            .subtract(second)
            .abs()
            .divide(largerAmount, 8, RoundingMode.HALF_UP) <= tolerance
}
