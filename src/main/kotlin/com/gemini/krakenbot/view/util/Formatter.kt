package com.gemini.krakenbot.view.util

import java.math.BigDecimal

object Formatter {
    fun formatCurrency(value: BigDecimal?): String =
        value?.let { String.format("%,.2f", it) } ?: "0.00"

    fun formatPercent(value: BigDecimal?): String =
        value?.let { String.format("%.2f", it) } ?: "0.00"

    fun formatPercent(value: Double): String =
        String.format("%.2f", value)

    fun getDeviationClass(deviation: BigDecimal?): String =
        if (deviation == null) "" else when (deviation.signum()) {
            1 -> "text-danger"
            -1 -> "text-success"
            else -> ""
        }

    fun getDeviationSign(deviation: BigDecimal?): String = when {
        deviation == null -> ""
        deviation.signum() > 0 -> "+"
        else -> ""
    }

}
