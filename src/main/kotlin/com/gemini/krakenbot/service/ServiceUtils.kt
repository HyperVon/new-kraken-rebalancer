package com.gemini.krakenbot.service

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Safe BigDecimal parsing that returns ZERO on failure instead of throwing.
 * Centralizes fallback handling for service response parsing.
 */
fun safeParseBigDecimal(value: String?, default: BigDecimal = BigDecimal.ZERO): BigDecimal = value?.let {
    try {
        BigDecimal(it)
    } catch (_: NumberFormatException) {
        default
    }
} ?: default

/**
 * Safe BigDecimal parsing with scale and rounding.
 */
fun safeParseBigDecimal(
    value: String?,
    scale: Int,
    mode: RoundingMode = RoundingMode.HALF_UP,
    default: BigDecimal = BigDecimal.ZERO,
): BigDecimal = safeParseBigDecimal(value, default).setScale(scale, mode)

/**
 * Checks if two BigDecimal values are within a relative tolerance of each other.
 */
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
