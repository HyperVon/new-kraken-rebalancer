package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.util.PrecisionConstants

fun formatUSD(valDouble: Double): String {
    val absVal = if (valDouble < 0) -valDouble else valDouble
    val formatted = usdOptionsToLocale(absVal, PrecisionConstants.SCALE_USD, PrecisionConstants.SCALE_USD)
    return if (valDouble < 0 && formatted != "0.00") "-$$formatted" else "$$formatted"
}

fun formatPctTick(v: Double, includePlus: Boolean = true): String {
    val d = dynamicNumber(v) ?: 0.0
    val sign = if (includePlus && d >= 0.0) "+" else ""
    val options: dynamic = kotlin.js.json()
    options.minimumFractionDigits = 0
    options.maximumFractionDigits = PrecisionConstants.SCALE_USD
    return sign + d.asDynamic().toLocaleString(EN_US, options) + "%"
}

internal fun formatCompactTradeTime(timestamp: String): String {
    val options: dynamic = kotlin.js.json()
    options.month = "short"
    options.day = "numeric"
    options.hour = "numeric"
    options.minute = "2-digit"
    return kotlin.js.Date(timestamp).asDynamic().toLocaleString(EN_US, options)
}

internal fun usdOptionsToLocale(value: Double, minDigits: Int, maxDigits: Int): String {
    val options: dynamic = kotlin.js.json()
    options.minimumFractionDigits = minDigits
    options.maximumFractionDigits = maxDigits
    return value.asDynamic().toLocaleString(EN_US, options)
}

internal const val EN_US = "en-US"
