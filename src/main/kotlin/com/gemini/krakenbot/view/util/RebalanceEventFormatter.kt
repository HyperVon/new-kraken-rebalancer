package com.gemini.krakenbot.view.util

import com.gemini.krakenbot.domain.RebalanceEvent

/** Presentation adapter for legacy snapshot action-log strings. */
object RebalanceEventFormatter {
    fun format(event: RebalanceEvent): String = when (event) {
        is RebalanceEvent.DeviationTriggered ->
            ActionLogFormatter.formatDeviationTrigger(event.symbol, event.deviationPercent)

        RebalanceEvent.FiatCorrectionEnforced -> ActionLogFormatter.formatFiatCorrectionEnforced()

        is RebalanceEvent.FiatCorrectionDistributed ->
            ActionLogFormatter.formatFiatCorrectionDistribution(event.usdAmount, event.candidateCount)

        RebalanceEvent.NoCounterBalancingAssets -> ActionLogFormatter.formatNoCounterBalancingAssets()
    }
}
