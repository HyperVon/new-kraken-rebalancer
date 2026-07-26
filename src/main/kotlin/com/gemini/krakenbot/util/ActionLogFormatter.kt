package com.gemini.krakenbot.util

import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.view.util.ViewText
import java.math.BigDecimal

object ActionLogFormatter {
    fun formatDeviationTrigger(symbol: String, deviationPercent: BigDecimal): String {
        val formatted = deviationPercent.toPercentScale().stripTrailingZeros().toPlainString()
        return "Deviation: $symbol $formatted%"
    }

    fun formatFiatCorrectionEnforced(): String = "USD Deviation Triggered. Enforcing fiat correction."

    fun formatFiatCorrectionDistribution(deviationAbs: BigDecimal, candidateCount: Int): String {
        val formattedAmount = deviationAbs.toUsdScale().toPlainString()
        return "Distributing Fiat Correction ($$formattedAmount) among $candidateCount candidates."
    }

    fun formatOrderExecution(
        side: OrderSide,
        symbol: String,
        volume: BigDecimal,
        usdAmount: BigDecimal,
        isDryRun: Boolean,
    ): String {
        val prefix = if (isDryRun) ViewText.DRY_RUN_PREFIX else ""
        val valueLabel = if (side == OrderSide.SELL) "Value" else "Cost"
        val formattedVolume = volume.toCryptoScale().stripTrailingZeros().toPlainString()
        val formattedUsd = usdAmount.toUsdScale().toPlainString()
        return "${prefix}${side.uppercaseName} $symbol Volume: $formattedVolume $valueLabel: $$formattedUsd"
    }

    fun formatOrderFailure(side: OrderSide, symbol: String, errorMessage: String?): String =
        "FAILED ${side.uppercaseName} $symbol: $errorMessage"

    fun formatSkippedDust(side: OrderSide, symbol: String, usdCost: BigDecimal): String {
        val formattedUsd = usdCost.toUsdScale().toPlainString()
        return "Skipping dust ${side.apiValue} for $symbol ($$formattedUsd)"
    }
}
