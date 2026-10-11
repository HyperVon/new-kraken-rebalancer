package com.gemini.krakenbot.util

import com.gemini.krakenbot.domain.toCryptoScale
import com.gemini.krakenbot.domain.toPercentScale
import com.gemini.krakenbot.domain.toUsdScale
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.view.util.ActionLogFormat
import com.gemini.krakenbot.view.util.Formatter
import com.gemini.krakenbot.view.util.ViewText
import java.math.BigDecimal

/**
 * Single owner of the persistent action-log text format: writing entries (executor,
 * plan events) and re-rendering stored entries for the activity feed both live here so
 * the write and parse sides cannot drift apart.
 */
object ActionLogFormatter {
    fun formatDeviationTrigger(symbol: String, deviationPercent: BigDecimal): String {
        val formatted = deviationPercent.toPercentScale().stripTrailingZeros().toPlainString()
        return "${ActionLogFormat.INFO_DEVIATION_PREFIX}$symbol $formatted%"
    }

    fun formatFiatCorrectionEnforced(): String = ActionLogFormat.INFO_FIAT_CORRECTION_ENFORCED

    fun formatFiatCorrectionDistribution(deviationAbs: BigDecimal, candidateCount: Int): String {
        val formattedAmount = deviationAbs.toUsdScale().toPlainString()
        return "${ActionLogFormat.INFO_DISTRIBUTING_FIAT_PREFIX}$formattedAmount" +
            "${ActionLogFormat.INFO_DISTRIBUTING_FIAT_MIDDLE}$candidateCount${ActionLogFormat.INFO_CANDIDATES_SUFFIX}"
    }

    fun formatNoCounterBalancingAssets(): String = ViewText.ACTION_NO_COUNTERBALANCING_ASSETS

    fun formatOrderExecution(
        side: OrderSide,
        symbol: String,
        volume: BigDecimal,
        usdAmount: BigDecimal,
        isDryRun: Boolean,
    ): String {
        val prefix = if (isDryRun) ActionLogFormat.DRY_RUN_PREFIX else ""
        val valueMarker = if (side == OrderSide.SELL) ActionLogFormat.VALUE_MARKER else ActionLogFormat.COST_MARKER
        val formattedVolume = volume.toCryptoScale().stripTrailingZeros().toPlainString()
        val formattedUsd = usdAmount.toUsdScale().toPlainString()
        val volumeMarker = ActionLogFormat.VOLUME_MARKER
        return "${prefix}${side.uppercaseName} $symbol $volumeMarker $formattedVolume $valueMarker $$formattedUsd"
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

    /** Re-renders a stored order-execution line for the dashboard activity feed. */
    fun renderTradeAction(action: String): String {
        val dryRun = action.startsWith(ActionLogFormat.DRY_RUN_PREFIX)
        val normalized = action.removePrefix(ActionLogFormat.DRY_RUN_PREFIX)
        val parts = normalized.split(' ')
        if (parts.size < 6 || parts[0] !in setOf(OrderSide.BUY.uppercaseName, OrderSide.SELL.uppercaseName)) {
            return action
        }
        val amountMarkers = setOf(ActionLogFormat.VALUE_MARKER, ActionLogFormat.COST_MARKER)
        if (parts[2] != ActionLogFormat.VOLUME_MARKER || parts[4] !in amountMarkers) return action

        return try {
            val quantity = BigDecimal(parts[3]).stripTrailingZeros().toPlainString()
            buildString {
                append(parts[0])
                append(' ')
                append(parts[1])
                append(" · ")
                append(quantity)
                append(" · $")
                append(Formatter.formatCurrency(BigDecimal(parts[5].removePrefix("$"))))
                if (dryRun) append(ViewText.ACTIVITY_DRY_RUN_MARKER)
            }
        } catch (_: NumberFormatException) {
            action
        }
    }

    /** Re-renders a stored info line for the dashboard activity feed. */
    fun renderInfoAction(action: String): String {
        val normalized = action.removePrefix(ActionLogFormat.DRY_RUN_PREFIX)
        return when {
            normalized.startsWith(ActionLogFormat.INFO_DEVIATION_PREFIX) ->
                normalized.removePrefix(ActionLogFormat.INFO_DEVIATION_PREFIX) +
                    ViewText.ACTIVITY_DRIFT_DETECTED_SUFFIX

            normalized == ActionLogFormat.INFO_FIAT_CORRECTION_ENFORCED ->
                ViewText.ACTIVITY_CASH_CORRECTION_ENFORCED

            normalized.startsWith(ActionLogFormat.INFO_DISTRIBUTING_FIAT_PREFIX) ->
                ViewText.ACTIVITY_CASH_CORRECTION_DISTRIBUTED

            else -> normalized
        }
    }
}
