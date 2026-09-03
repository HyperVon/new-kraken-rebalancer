package com.gemini.krakenbot.domain

import com.gemini.krakenbot.util.PrecisionConstants
import java.math.BigDecimal

private val CACHED_CASH_RESERVE_FACTOR: BigDecimal = BigDecimal.valueOf(PrecisionConstants.CASH_RESERVE_FACTOR_DOUBLE)
private val CACHED_FEE_RATE_ESTIMATE: BigDecimal = BigDecimal.valueOf(PrecisionConstants.FEE_RATE_ESTIMATE_DOUBLE)
private val CACHED_HUNDRED: BigDecimal = BigDecimal.valueOf(PrecisionConstants.HUNDRED_INT.toLong())
private val CACHED_ALLOCATION_TOLERANCE: BigDecimal =
    BigDecimal.valueOf(PrecisionConstants.ALLOCATION_TOLERANCE_DELTA)

@Suppress("UnusedReceiverParameter")
val PrecisionConstants.CASH_RESERVE_FACTOR: BigDecimal
    get() = CACHED_CASH_RESERVE_FACTOR

@Suppress("UnusedReceiverParameter")
val PrecisionConstants.FEE_RATE_ESTIMATE: BigDecimal
    get() = CACHED_FEE_RATE_ESTIMATE

@Suppress("UnusedReceiverParameter")
val PrecisionConstants.HUNDRED: BigDecimal
    get() = CACHED_HUNDRED

@Suppress("UnusedReceiverParameter")
val PrecisionConstants.ALLOCATION_TOLERANCE: BigDecimal
    get() = CACHED_ALLOCATION_TOLERANCE
