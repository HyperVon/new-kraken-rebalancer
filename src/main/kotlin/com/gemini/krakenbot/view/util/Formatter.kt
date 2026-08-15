package com.gemini.krakenbot.view.util

import com.gemini.krakenbot.util.FormatSpec
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs

object Formatter {
    fun formatCurrency(value: BigDecimal): String = String.format(Locale.US, "%,.2f", value)

    fun formatPercent(value: BigDecimal): String =
        value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /** JVM mirror of the JS tier — delegates to [FormatSpec] so both stay in sync; rendering stays `BigDecimal`-native. */
    fun priceDigitsForDisplay(price: BigDecimal): Int = FormatSpec.priceDigits(abs(price.toDouble()))
    fun feeDigitsForDisplay(fee: BigDecimal): Int = FormatSpec.feeDigits(abs(fee.toDouble()))

    fun getDeviationClass(deviation: BigDecimal): CssClass? = when (deviation.signum()) {
        1 -> CssClass.Utility.TextOverweight
        -1 -> CssClass.Utility.TextUnderweight
        else -> null
    }

    fun getDeviationSign(deviation: BigDecimal): String = if (deviation.signum() > 0) "+" else ""
}
