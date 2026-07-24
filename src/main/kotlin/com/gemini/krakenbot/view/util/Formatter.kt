package com.gemini.krakenbot.view.util

import java.math.BigDecimal
import java.util.Locale

object Formatter {
    fun formatCurrency(value: BigDecimal?): String =
        value?.let { String.format(Locale.US, "%,.2f", it) } ?: "0.00"

    fun formatPercent(value: BigDecimal?): String =
        value?.let { String.format(Locale.US, "%.2f", it) } ?: "0.00"

    fun formatPercent(value: Double): String =
        String.format(Locale.US, "%.2f", value)

    fun getDeviationClass(deviation: BigDecimal?): CssClass? =
        if (deviation == null) {
            null
        } else {
            when (deviation.signum()) {
                1 -> CssClass.Utility.TextDanger
                -1 -> CssClass.Utility.TextSuccess
                else -> null
            }
        }

    fun getDeviationSign(deviation: BigDecimal?): String = when {
        deviation == null -> ""
        deviation.signum() > 0 -> "+"
        else -> ""
    }
}
