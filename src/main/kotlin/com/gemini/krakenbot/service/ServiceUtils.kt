package com.gemini.krakenbot.service

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Utility functions for service operations including retry logic, parsing, and common patterns.
 * Promotes consistent error handling and reduces code duplication.
 */

/**
 * Safe BigDecimal parsing that returns ZERO on failure instead of throwing.
 * Replaces try-catch patterns for BigDecimal parsing.
 */
fun safeParseBigDecimal(value: String?, default: BigDecimal = BigDecimal.ZERO): BigDecimal =
    value?.let {
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
    default: BigDecimal = BigDecimal.ZERO
): BigDecimal =
    safeParseBigDecimal(value, default).setScale(scale, mode)

