package com.gemini.krakenbot.util

import com.gemini.krakenbot.model.OrderSide
import java.math.BigDecimal

/**
 * Standardized human-readable audit log message generator for portfolio rebalancing events.
 */
object ActionLogFormatter {
    fun formatDeviationTrigger(symbol: String, deviationPercent: BigDecimal): String =
        "Deviation Triggered details: $symbol Dev: $deviationPercent%"

    fun formatFiatCorrectionEnforced(): String = "USD Deviation Triggered. Enforcing fiat correction."

    fun formatFiatCorrectionDistribution(deviationAbs: BigDecimal, candidateCount: Int): String {
        val formattedAmount = deviationAbs.toUsdScale()
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
        val actionVerb = if (isSell) "SELL" else "BUY"
        val valueLabel = if (isSell) "Value" else "Cost"
        return "${prefix}$actionVerb $symbol Volume: $volume $valueLabel: $$usdAmount"
    }

    fun formatOrderFailure(side: String, symbol: String, errorMessage: String?): String =
        "FAILED $side $symbol: $errorMessage"

    fun formatSkippedDust(side: String, symbol: String, usdCost: BigDecimal): String =
        "Skipping dust $side for $symbol ($$usdCost)"
}
