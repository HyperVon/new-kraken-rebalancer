package com.gemini.krakenbot.util

import java.math.BigDecimal
import java.math.RoundingMode

val BigDecimal.isZero: Boolean
    get() = signum() == 0

val BigDecimal.isPositive: Boolean
    get() = signum() > 0

val BigDecimal.isNegative: Boolean
    get() = signum() < 0

fun BigDecimal.toUsdScale(): BigDecimal = setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP)

fun BigDecimal.toCryptoScale(): BigDecimal = setScale(PrecisionConstants.SCALE_CRYPTO, RoundingMode.HALF_UP)

fun BigDecimal.toPercentScale(): BigDecimal = setScale(PrecisionConstants.SCALE_PERCENT, RoundingMode.HALF_UP)
