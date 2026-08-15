package com.gemini.krakenbot.util

import kotlin.math.abs

/**
 * Single-sourced tier rules for price/fee display precision. JVM ([Formatter]) and JS
 * ([HistoryFormatting]/[HistoryTradeRendering]) previously duplicated the `>=100→2,
 * >=1→4, >=0.01→6 else 8` cascade — this object is the canonical source so both
 * platforms stay in sync while keeping `BigDecimal` vs `toLocaleString` rendering
 * on its native side.
 */
object FormatSpec {
    fun priceDigits(absValue: Double): Int {
        val a = abs(absValue)
        return when {
            a >= 100.0 -> PrecisionConstants.SCALE_USD
            a >= 1.0 -> 4
            a >= 0.01 -> 6
            else -> PrecisionConstants.SCALE_CRYPTO
        }
    }

    fun feeDigits(absValue: Double): Int =
        if (abs(absValue) >= 1.0) PrecisionConstants.SCALE_USD else PrecisionConstants.SCALE_FEE

    fun quantityDigits(): Int = PrecisionConstants.SCALE_CRYPTO
}
