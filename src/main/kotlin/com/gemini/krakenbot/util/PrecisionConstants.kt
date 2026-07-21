package com.gemini.krakenbot.util

import java.math.BigDecimal

/** Centralized financial math precision scales and constants. */
object PrecisionConstants {
    const val SCALE_CRYPTO = 8
    const val SCALE_USD = 2
    const val SCALE_PERCENT = 4
    const val SCALE_FEE = 4

    val HUNDRED: BigDecimal = BigDecimal.valueOf(100)
    val CASH_RESERVE_FACTOR: BigDecimal = BigDecimal("0.99")
    val FEE_RATE_ESTIMATE: BigDecimal = BigDecimal("0.0026")
}
