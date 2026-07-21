package com.gemini.krakenbot.util

import java.math.BigDecimal

val PrecisionConstants.CASH_RESERVE_FACTOR: BigDecimal get() = BigDecimal.valueOf(PrecisionConstants.CASH_RESERVE_FACTOR_DOUBLE)
val PrecisionConstants.FEE_RATE_ESTIMATE: BigDecimal get() = BigDecimal.valueOf(PrecisionConstants.FEE_RATE_ESTIMATE_DOUBLE)
val PrecisionConstants.HUNDRED: BigDecimal get() = BigDecimal.valueOf(PrecisionConstants.HUNDRED_INT.toLong())
