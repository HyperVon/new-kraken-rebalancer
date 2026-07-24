package com.gemini.krakenbot.util

import java.math.BigDecimal

val PrecisionConstants.CASH_RESERVE_FACTOR: BigDecimal
    get() = BigDecimal.valueOf(CASH_RESERVE_FACTOR_DOUBLE)

val PrecisionConstants.FEE_RATE_ESTIMATE: BigDecimal
    get() = BigDecimal.valueOf(FEE_RATE_ESTIMATE_DOUBLE)

val PrecisionConstants.HUNDRED: BigDecimal
    get() = BigDecimal.valueOf(HUNDRED_INT.toLong())
