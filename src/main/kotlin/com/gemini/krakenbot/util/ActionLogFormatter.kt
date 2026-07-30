package com.gemini.krakenbot.util

import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.view.util.ViewText
import java.math.BigDecimal

object ActionLogFormatter {
    private const val DEVIATION_PREFIX = "Deviation: "
    private const val FIAT_CORRECTION_ENFORCED = "USD Deviation Triggered. Enforcing fiat correction."
    private const val DISTRIBUTING_FIAT_PREFIX = "Distributing Fiat Correction ($"
    private const val DISTRIBUTING_FIAT_MIDDLE = ") among "
    private const val CANDIDATES_SUFFIX = " candidates."
    private const val VALUE_LABEL = "Value"
    private const val COST_LABEL = "Cost"
    private const val FAILED_PREFIX = "FAILED "
    private const val SKIPPING_DUST_PREFIX = "Skipping dust "
    private const val FOR_SUFFIX = " for "

    fun formatDeviationTrigger(symbol: String, deviationPercent: BigDecimal): String {
        val formatted = deviationPercent.toPercentScale().stripTrailingZeros().toPlainString()
        return "$DEVIATION_PREFIX$symbol $formatted%"
    }

    fun formatFiatCorrectionEnforced(): String = FIAT_CORRECTION_ENFORCED

    fun formatFiatCorrectionDistribution(deviationAbs: BigDecimal, candidateCount: Int): String {
        val formattedAmount = deviationAbs.toUsdScale().toPlainString()
        return "$DISTRIBUTING_FIAT_PREFIX$formattedAmount$DISTRIBUTING_FIAT_MIDDLE$candidateCount$CANDIDATES_SUFFIX"
    }

    fun formatOrderExecution(
        side: OrderSide,
        symbol: String,
        volume: BigDecimal,
        usdAmount: BigDecimal,
        isDryRun: Boolean,
    ): String {
        val prefix = if (isDryRun) ViewText.DRY_RUN_PREFIX else ""
        val valueLabel = if (side == OrderSide.SELL) VALUE_LABEL else COST_LABEL
        val formattedVolume = volume.toCryptoScale().stripTrailingZeros().toPlainString()
        val formattedUsd = usdAmount.toUsdScale().toPlainString()
        return "${prefix}${side.uppercaseName} $symbol Volume: $formattedVolume $valueLabel: $$formattedUsd"
    }

    fun formatOrderFailure(side: OrderSide, symbol: String, errorMessage: String?): String =
        "$FAILED_PREFIX${side.uppercaseName} $symbol: $errorMessage"

    fun formatSkippedDust(side: OrderSide, symbol: String, usdCost: BigDecimal): String {
        val formattedUsd = usdCost.toUsdScale().toPlainString()
        return "$SKIPPING_DUST_PREFIX${side.apiValue}$FOR_SUFFIX$symbol ($$formattedUsd)"
    }
}
