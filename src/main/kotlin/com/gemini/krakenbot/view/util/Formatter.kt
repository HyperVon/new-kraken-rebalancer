package com.gemini.krakenbot.view.util

import java.math.BigDecimal

object Formatter {
    fun formatCurrency(value: BigDecimal): String =
        String.format("%,.2f", value)

    fun formatPercent(value: BigDecimal): String =
        String.format("%.2f", value)

    fun formatPercent(value: Double): String =
        String.format("%.2f", value)

    fun getDeviationClass(deviation: BigDecimal): String = when (deviation.signum()) {
        1 -> "text-danger"
        -1 -> "text-success"
        else -> ""
    }

    fun getDeviationSign(deviation: BigDecimal): String = when {
        deviation.signum() > 0 -> "+"
        else -> ""
    }
}
