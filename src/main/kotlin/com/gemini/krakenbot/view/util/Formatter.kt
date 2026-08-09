package com.gemini.krakenbot.view.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

object Formatter {
    fun formatCurrency(value: BigDecimal): String = String.format(Locale.US, "%,.2f", value)

    fun formatPercent(value: BigDecimal): String =
        value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    fun getDeviationClass(deviation: BigDecimal): CssClass? = when (deviation.signum()) {
        1 -> CssClass.Utility.TextOverweight
        -1 -> CssClass.Utility.TextUnderweight
        else -> null
    }

    fun getDeviationSign(deviation: BigDecimal): String = if (deviation.signum() > 0) "+" else ""
}
