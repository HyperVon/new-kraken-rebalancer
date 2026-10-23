package com.gemini.krakenbot.view.util

import com.gemini.krakenbot.util.FormatSpec
import com.gemini.krakenbot.util.PrecisionConstants
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

    /** Locale-style grouping with a bounded decimal tier; the sign is preserved for values below zero. */
    fun formatTiered(value: BigDecimal, minDigits: Int, maxDigits: Int): String {
        val rounded = value.abs().setScale(maxDigits, RoundingMode.HALF_UP)
        val stripped = rounded.stripTrailingZeros()
        val scale = stripped.scale().coerceIn(minDigits, maxDigits)
        val body = String.format(Locale.US, "%,.${scale}f", stripped.setScale(scale, RoundingMode.HALF_UP))
        return if (value.signum() < 0) "-$body" else body
    }

    fun formatSignedUsd(value: BigDecimal): String =
        if (value.signum() < 0) "-$${formatCurrency(value.abs())}" else "$${formatCurrency(value)}"

    /** Em-dash for absent/zero values so table cells stay aligned and quiet. */
    fun formatUsdOrDash(value: BigDecimal?, minDigits: Int, maxDigits: Int): String {
        if (value == null || value.signum() == 0) return ViewText.EM_DASH
        return if (value.signum() < 0) {
            "-$" + formatTiered(value.abs(), minDigits, maxDigits)
        } else {
            "$" + formatTiered(value, minDigits, maxDigits)
        }
    }

    fun formatPriceOrDash(price: BigDecimal?): String {
        if (price == null || price.signum() == 0) return ViewText.EM_DASH
        return formatUsdOrDash(price, PrecisionConstants.SCALE_USD, priceDigitsForDisplay(price))
    }

    fun formatFeeOrDash(fee: BigDecimal?): String {
        if (fee == null || fee.signum() == 0) return ViewText.EM_DASH
        return formatUsdOrDash(fee, PrecisionConstants.SCALE_USD, feeDigitsForDisplay(fee))
    }

    fun formatQuantity(value: BigDecimal): String = formatTiered(value, 0, FormatSpec.quantityDigits())

    fun formatCount(value: Long): String = String.format(Locale.US, "%,d", value)

    fun getDeviationClass(deviation: BigDecimal): CssClass? = when (deviation.signum()) {
        1 -> CssClass.Utility.TextOverweight
        -1 -> CssClass.Utility.TextUnderweight
        else -> null
    }

    fun getDeviationSign(deviation: BigDecimal): String = if (deviation.signum() > 0) "+" else ""
}
