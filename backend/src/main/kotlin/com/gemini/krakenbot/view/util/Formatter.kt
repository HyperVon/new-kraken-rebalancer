package com.gemini.krakenbot.view.util

import com.gemini.krakenbot.util.FormatSpec
import com.gemini.krakenbot.util.PrecisionConstants
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

object Formatter {
    fun formatCurrency(value: BigDecimal): String = String.format(Locale.US, "%,.2f", value)

    fun formatPercent(value: BigDecimal): String =
        value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /** JVM mirror of the JS tier — delegates to [FormatSpec] so both stay in sync; rendering stays `BigDecimal`-native. */
    fun priceDigitsForDisplay(price: BigDecimal): Int = FormatSpec.priceDigits(abs(price.toDouble()))
    fun feeDigitsForDisplay(fee: BigDecimal): Int = FormatSpec.feeDigits(abs(fee.toDouble()))

    /**
     * HIST-3: locale-style grouping with a bounded decimal tier (JVM mirror of the old
     * `usdOptionsToLocale`) — keeps ordinary values calm while preserving sub-cent precision.
     */
    fun formatTiered(value: BigDecimal, minDigits: Int, maxDigits: Int): String {
        val rounded = value.abs().setScale(maxDigits, RoundingMode.HALF_UP)
        val stripped = rounded.stripTrailingZeros()
        val scale = stripped.scale().coerceIn(minDigits, maxDigits)
        return String.format(Locale.US, "%,.${scale}f", stripped.setScale(scale, RoundingMode.HALF_UP))
    }

    fun formatSignedUsd(value: BigDecimal): String =
        if (value.signum() < 0) "-$${formatCurrency(value.abs())}" else "$${formatCurrency(value)}"

    /** Em-dash for absent/zero values so table cells stay aligned and quiet. */
    fun formatUsdOrDash(value: BigDecimal?, minDigits: Int, maxDigits: Int): String {
        if (value == null || value.signum() == 0) return ViewText.EM_DASH
        return "$" + formatTiered(value, minDigits, maxDigits)
    }

    fun formatPriceOrDash(price: BigDecimal?): String {
        if (price == null || price.signum() == 0) return ViewText.EM_DASH
        return formatUsdOrDash(price, PrecisionConstants.SCALE_USD, priceDigitsForDisplay(price))
    }

    fun formatFeeOrDash(fee: BigDecimal?): String {
        if (fee == null || fee.signum() == 0) return ViewText.EM_DASH
        return formatUsdOrDash(fee, PrecisionConstants.SCALE_USD, feeDigitsForDisplay(fee))
    }

    /** Fee is denominated in [currency]; USD is money-styled, other assets keep a plain amount plus symbol. */
    fun formatFeeWithCurrency(fee: BigDecimal?, currency: String?): String {
        if (fee == null || fee.signum() == 0) return ViewText.EM_DASH
        if (currency.isNullOrBlank()) return "${formatQuantity(fee)} ${ViewText.HISTORY_UNKNOWN_CURRENCY}"
        if (!currency.equals("USD", true)) return "${formatQuantity(fee)} $currency"
        return formatFeeOrDash(fee)
    }

    fun formatQuantity(value: BigDecimal): String = formatTiered(value, 0, FormatSpec.quantityDigits())

    fun formatCount(value: Long): String = String.format(Locale.US, "%,d", value)

    private val compactTime: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(ZoneId.systemDefault())

    fun formatCompactTradeTime(time: Instant?): String = time?.let(compactTime::format).orEmpty()

    fun formatPair(baseAsset: String?, quoteCurrency: String?, pair: String?): String {
        if (!baseAsset.isNullOrBlank() && !quoteCurrency.isNullOrBlank()) return "$baseAsset/$quoteCurrency"
        return pair.orEmpty()
    }

    fun getDeviationClass(deviation: BigDecimal): CssClass? = when (deviation.signum()) {
        1 -> CssClass.Utility.TextOverweight
        -1 -> CssClass.Utility.TextUnderweight
        else -> null
    }

    fun getDeviationSign(deviation: BigDecimal): String = if (deviation.signum() > 0) "+" else ""
}
