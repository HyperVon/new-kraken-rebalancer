package com.gemini.krakenbot.util

/** Centralized financial math precision scales and constants. */
object PrecisionConstants {
    const val SCALE_CRYPTO = 8
    const val SCALE_USD = 2
    const val SCALE_PERCENT = 4
    const val SCALE_FEE = 4
    const val HUNDRED_INT = 100
    const val CASH_RESERVE_FACTOR_DOUBLE = 0.99
    const val FEE_RATE_ESTIMATE_DOUBLE = 0.0026
    const val STALE_THRESHOLD_SECONDS = 90L
}
