package com.gemini.krakenbot.util

/**
 * Shared scale/threshold numbers for JVM BigDecimal math and Kotlin/JS UI checks.
 * Doubles/Ints only in commonMain so JS can share values without java.math.BigDecimal;
 * JVM wraps the monetary ones in PrecisionConstantsJvm.
 */
object PrecisionConstants {
    /** Crypto quantity/price decimal places. */
    const val SCALE_CRYPTO = 8

    /** Fiat USD decimal places. */
    const val SCALE_USD = 2

    const val SCALE_PERCENT = 4
    const val SCALE_FEE = 4

    /** Ledger-asset fee decimal places; ledger fees are not fiat trade fees. */
    const val SCALE_LEDGER_FEE = 8
    const val HUNDRED_INT = 100

    /** Post-sell buy budget: spend at most 99% of settled USD. */
    const val CASH_RESERVE_FACTOR_DOUBLE = 0.99

    /** Per-leg fee estimate for local trade planning before API reconciliation. */
    const val FEE_RATE_ESTIMATE_DOUBLE = 0.006

    /** Seconds after which dashboard/JS treat snapshot age as STALE (not "live trading"). */
    const val STALE_THRESHOLD_SECONDS = 90L

    const val MILLIS_PER_SECOND = 1000
    const val ONE_HOUR_MS = 3600000.0
    const val ONE_DAY_MS = 86_400_000.0
    const val SYNC_POLL_INTERVAL_MS = 3000
    const val HOURS_PER_HALF_DAY = 12

    const val TRADE_TABLE_COLSPAN = 9
    const val DEFAULT_SORT_COL_INDEX = 5
    const val TOTAL_ALLOCATION_PERCENTAGE = 100.0
    const val ALLOCATION_TOLERANCE_DELTA = 0.01

    /** Allocation-percent number-input bounds, shared by the SSR form and the JS editor. */
    const val ALLOCATION_MIN_PERCENT = 0.0
    const val ALLOCATION_STEP_PERCENT = 0.1

    const val HISTORICAL_DAYS_BACK = 90
    const val LAST_HOUR_OF_DAY = 23
    const val LAST_MINUTE_OF_HOUR = 59
    const val LAST_SECOND_OF_MINUTE = 59
    const val DEFAULT_USD_TARGET_PERCENT = 5.0
    const val MIN_CRYPTO_DECIMAL_PLACES = 4

    /**
     * Seed/initial history-pull window (days) for trades and ledgers: filled trades
     * older than HISTORICAL_DAYS_BACK are pruned and reconstruction only reaches
     * ~95 days, so pulling more than this would fetch data that is immediately discarded.
     */
    const val SEED_HISTORY_LOOKBACK_DAYS = 96L
}
