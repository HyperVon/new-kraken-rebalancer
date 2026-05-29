package com.gemini.krakenbot.view.util

import java.math.BigDecimal

object Formatter {
    fun formatCurrency(value: BigDecimal): String {
        return String.format("%,.2f", value)
    }

    fun formatPercent(value: BigDecimal): String {
        return String.format("%.2f", value)
    }

    fun formatPercent(value: Double): String {
        return String.format("%.2f", value)
    }

    fun getDeviationClass(deviation: BigDecimal): String {
        return if (deviation.signum() > 0) "text-danger" else if (deviation.signum() < 0) "text-success" else ""
    }

    fun getDeviationSign(deviation: BigDecimal): String {
        return if (deviation.signum() > 0) "+" else ""
    }
}
