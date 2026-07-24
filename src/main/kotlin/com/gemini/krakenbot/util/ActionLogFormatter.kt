package com.gemini.krakenbot.util

import com.gemini.krakenbot.model.OrderSide
import java.math.BigDecimal

/**
 * Standardized human-readable audit log message generator for portfolio rebalancing events.
 */
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
        side: String,
        symbol: String,
        volume: BigDecimal,
        usdAmount: BigDecimal,
        isDryRun: Boolean,
    ): String {
        val prefix = if (isDryRun) "[DRY RUN] " else ""
        val isSell = side == OrderSide.SELL.uppercaseName
        val actionVerb = if (isSell) OrderSide.SELL.uppercaseName else OrderSide.BUY.uppercaseName
        val valueLabel = if (isSell) "Value" else "Cost"
        val formattedVolume = volume.toCryptoScale().stripTrailingZeros().toPlainString()
        val formattedUsd = usdAmount.toUsdScale().toPlainString()
        return "${prefix}$actionVerb $symbol Volume: $formattedVolume $valueLabel: $$formattedUsd"
    }

    fun formatOrderFailure(side: String, symbol: String, errorMessage: String?): String =
        "FAILED $side $symbol: $errorMessage"

    fun formatSkippedDust(side: String, symbol: String, usdCost: BigDecimal): String {
        val formattedUsd = usdCost.toUsdScale().toPlainString()
        return "Skipping dust $side for $symbol ($$formattedUsd)"
    }
}
