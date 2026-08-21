package com.gemini.krakenbot.view.util

import com.gemini.krakenbot.domain.toCryptoScale
import com.gemini.krakenbot.domain.toPercentScale
import com.gemini.krakenbot.domain.toUsdScale
import com.gemini.krakenbot.model.OrderSide
import java.math.BigDecimal

object ActionLogFormatter {
    fun formatDeviationTrigger(symbol: String, deviationPercent: BigDecimal): String {
        val formatted = deviationPercent.toPercentScale().stripTrailingZeros().toPlainString()
        return "${ViewText.ACTION_DEVIATION_PREFIX}$symbol $formatted%"
    }

    fun formatFiatCorrectionEnforced(): String = ViewText.ACTION_FIAT_CORRECTION_ENFORCED

    fun formatFiatCorrectionDistribution(deviationAbs: BigDecimal, candidateCount: Int): String {
        val formattedAmount = deviationAbs.toUsdScale().toPlainString()
        return "${ViewText.ACTION_DISTRIBUTING_FIAT_PREFIX}$formattedAmount" +
            "${ViewText.ACTION_DISTRIBUTING_FIAT_MIDDLE}$candidateCount${ViewText.ACTION_CANDIDATES_SUFFIX}"
    }

    fun formatNoCounterBalancingAssets(): String = ViewText.ACTION_NO_COUNTERBALANCING_ASSETS

    fun formatOrderExecution(
        side: OrderSide,
        symbol: String,
        volume: BigDecimal,
        usdAmount: BigDecimal,
        isDryRun: Boolean,
    ): String {
        val prefix = if (isDryRun) ViewText.DRY_RUN_PREFIX else ""
        val valueLabel = if (side == OrderSide.SELL) ViewText.ACTION_VALUE_LABEL else ViewText.ACTION_COST_LABEL
        val formattedVolume = volume.toCryptoScale().stripTrailingZeros().toPlainString()
        val formattedUsd = usdAmount.toUsdScale().toPlainString()
        val volumeLabel = "${ViewText.ACTION_VOLUME_LABEL}:"
        val valueSuffix = "$valueLabel: $$formattedUsd"
        return "${prefix}${side.uppercaseName} $symbol $volumeLabel $formattedVolume $valueSuffix"
    }

    fun formatOrderFailure(side: OrderSide, symbol: String, errorMessage: String?): String =
        "${ViewText.ACTION_FAILED_PREFIX}${side.uppercaseName} $symbol: $errorMessage"

    fun formatSkippedDust(side: OrderSide, symbol: String, usdCost: BigDecimal): String {
        val formattedUsd = usdCost.toUsdScale().toPlainString()
        return "${ViewText.ACTION_SKIPPING_DUST_PREFIX}${side.apiValue}" +
            "${ViewText.ACTION_FOR_SUFFIX}$symbol ($$formattedUsd)"
    }

    fun formatSkippedMissingPrice(side: OrderSide, symbol: String): String =
        "${ViewText.ACTION_SKIPPED_NO_PRICE_PREFIX}${side.apiValue} $symbol"
}
