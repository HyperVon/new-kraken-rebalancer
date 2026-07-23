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

    // Time & Interval Constants
    const val MILLIS_PER_SECOND = 1000
    const val ONE_HOUR_MS = 3600000.0
    const val SYNC_POLL_INTERVAL_MS = 3000
    const val HOURS_PER_HALF_DAY = 12

    // UI & Table Layout Constants
    const val TRADE_TABLE_COLSPAN = 6
    const val DEFAULT_SORT_COL_INDEX = 5
    const val TOTAL_ALLOCATION_PERCENTAGE = 100.0
    const val ALLOCATION_TOLERANCE_DELTA = 0.01

    // Historical Reconstruction Constants
    const val HISTORICAL_DAYS_BACK = 90
    const val LAST_HOUR_OF_DAY = 23
    const val LAST_MINUTE_OF_HOUR = 59
    const val LAST_SECOND_OF_MINUTE = 59
    const val DEFAULT_USD_TARGET_PERCENT = 5.0
    const val MIN_CRYPTO_DECIMAL_PLACES = 4
}
