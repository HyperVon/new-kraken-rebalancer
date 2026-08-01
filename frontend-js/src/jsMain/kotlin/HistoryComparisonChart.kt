package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.api.RebalancerComparison
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonConfidence
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.browser.document
import kotlin.js.json

internal fun buildRebalancerComparisonChart(comparison: RebalancerComparison) {
    val chartArea = document.getElementById(HtmlIds.COMPARISON_CHART_CONTENT)
    val unavailableDiv = document.getElementById(HtmlIds.COMPARISON_AVAILABILITY_MESSAGE)
    val deltaEl = document.getElementById(HtmlIds.COMPARISON_LATEST_DIFFERENCE)
    val confidenceBadge = document.getElementById(HtmlIds.COMPARISON_CONFIDENCE_BADGE)

    if (!comparison.isRenderable()) {
        clearChart(HtmlIds.REBALANCER_COMPARISON_CHART)
        if (deltaEl != null) {
            deltaEl.textContent = ViewText.EM_DASH
            deltaEl.className = CssClass.History.ComparisonDelta.value
        }
        if (chartArea != null) chartArea.classList.add(CssClass.Utility.Hidden.value)
        if (confidenceBadge != null) {
            confidenceBadge.textContent = ""
            confidenceBadge.classList.remove(CssClass.Utility.Visible.value)
        }
        val message = unavailableReasonText(comparison.unavailableReason)
        if (unavailableDiv != null) {
            unavailableDiv.textContent = "${ViewText.COMPARISON_UNAVAILABLE_PREFIX}$message"
            unavailableDiv.classList.add(CssClass.Utility.Visible.value)
        }
        return
    }

    if (chartArea != null) chartArea.classList.remove(CssClass.Utility.Hidden.value)
    if (unavailableDiv != null) {
        unavailableDiv.textContent = ""
        unavailableDiv.classList.remove(CssClass.Utility.Visible.value)
    }
    if (confidenceBadge != null) {
        if (comparison.confidence == ComparisonConfidence.ESTIMATED.name) {
            confidenceBadge.textContent = ViewText.COMPARISON_CONFIDENCE_ESTIMATED
            confidenceBadge.classList.add(CssClass.Utility.Visible.value)
        } else {
            confidenceBadge.textContent = ""
            confidenceBadge.classList.remove(CssClass.Utility.Visible.value)
        }
    }

    val rebalancerData = comparison.points.map { point ->
        json("x" to point.timestamp, "y" to dynamicNumber(point.rebalancerValueUSD))
    }.toTypedArray()

    val buyAndHoldData = comparison.points.map { point ->
        json("x" to point.timestamp, "y" to dynamicNumber(point.buyAndHoldValueUSD))
    }.toTypedArray()

    val datasets = arrayOf(
        lineDataset(
            label = ViewText.REBALANCER,
            data = rebalancerData,
            borderColor = ChartProps.COLOR_BLUE,
            backgroundColor = ChartProps.TRANSPARENT,
            primary = true,
            fill = false,
        ),
        lineDataset(
            label = ViewText.BUY_AND_HOLD,
            data = buyAndHoldData,
            borderColor = ChartProps.COLOR_AMBER,
            backgroundColor = ChartProps.TRANSPARENT,
            primary = false,
            fill = false,
            borderDash = arrayOf(ChartProps.BORDER_DASH_SEGMENT, ChartProps.BORDER_DASH_GAP),
        ),
    )

    val options = getClonedChartOptions()
    applyUsdLabeling(options)
    options.plugins.tooltip.callbacks.footer = { items: dynamic ->
        val firstItem: dynamic = items[0]
        val dataIndex = dynamicNumber(firstItem?.dataIndex)?.toInt() ?: -1
        val pts = comparison.points
        if (dataIndex >= 0 && dataIndex < pts.size) {
            val pt = pts[dataIndex]
            val diff = dynamicNumber(pt.differenceUSD) ?: 0.0
            val diffPct = dynamicNumber(pt.differencePercent) ?: 0.0
            val sign = if (diff >= 0) "+" else ""
            "Difference: $sign${formatUSD(diff)} ($sign${diffPct.toFixed(2)}%)"
        } else {
            null
        }
    }

    val latestDiff = dynamicNumber(comparison.latestDifferenceUSD)!!
    val latestDiffPct = dynamicNumber(comparison.latestDifferencePercent)!!
    val signStr = if (latestDiff > 0) "+" else ""
    if (deltaEl != null) {
        deltaEl.textContent = "$signStr${formatUSD(latestDiff)} ($signStr${latestDiffPct.toFixed(2)}%)"
        deltaEl.className = CssClass.History.ComparisonDelta.value
        if (latestDiff > 0) {
            deltaEl.classList.add(CssClass.Utility.Positive.value)
        } else if (latestDiff < 0) {
            deltaEl.classList.add(CssClass.Utility.Negative.value)
        } else {
            deltaEl.classList.add(CssClass.Utility.Neutral.value)
        }
    }

    createOrUpdate(HtmlIds.REBALANCER_COMPARISON_CHART, createLineChartConfig(datasets, options))
}

private fun RebalancerComparison.isRenderable(): Boolean = hasValidAvailability() &&
    hasSufficientData() &&
    hasValidDifferenceValues() &&
    hasSortedTimestamps() &&
    hasValidBaselinePoint() &&
    hasCompletePointData()

private fun RebalancerComparison.hasValidAvailability(): Boolean =
    availability == ComparisonAvailability.AVAILABLE.name &&
        (confidence == ComparisonConfidence.RECONCILED.name || confidence == ComparisonConfidence.ESTIMATED.name) &&
        unavailableReason == null &&
        unavailableAt == null

private fun RebalancerComparison.hasSufficientData(): Boolean = points.size >= 2 &&
    baselineTimestamp?.isNotBlank() == true

private fun RebalancerComparison.hasValidDifferenceValues(): Boolean = dynamicNumber(latestDifferenceUSD) != null &&
    dynamicNumber(latestDifferencePercent) != null

private fun RebalancerComparison.hasSortedTimestamps(): Boolean =
    points.map { dynamicNumber(it.timestamp) }.let { timestamps ->
        timestamps.all { it != null } &&
            timestamps.zipWithNext().all { (previous, current) -> current!! >= previous!! }
    }

private fun RebalancerComparison.hasValidBaselinePoint(): Boolean = points.firstOrNull()?.let { first ->
    first.timestamp == baselineTimestamp &&
        dynamicNumber(first.rebalancerValueUSD) == dynamicNumber(first.buyAndHoldValueUSD) &&
        dynamicNumber(first.differenceUSD) == 0.0 &&
        dynamicNumber(first.differencePercent) == 0.0
} == true

private fun RebalancerComparison.hasCompletePointData(): Boolean = points.all { point ->
    point.timestamp.isNotBlank() &&
        dynamicNumber(point.rebalancerValueUSD) != null &&
        dynamicNumber(point.buyAndHoldValueUSD) != null &&
        dynamicNumber(point.differenceUSD) != null &&
        dynamicNumber(point.differencePercent) != null
}

internal fun unavailableReasonText(reason: String?): String = when (reason) {
    ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS.name -> ViewText.UNAVAILABLE_INSUFFICIENT_SNAPSHOTS
    ComparisonUnavailableReason.NON_POSITIVE_BASELINE.name -> ViewText.UNAVAILABLE_NON_POSITIVE_BASELINE
    ComparisonUnavailableReason.BASELINE_MISMATCH.name -> ViewText.UNAVAILABLE_BASELINE_MISMATCH
    ComparisonUnavailableReason.MISSING_PRICE.name -> ViewText.UNAVAILABLE_MISSING_PRICE
    ComparisonUnavailableReason.ASSET_UNIVERSE_CHANGED.name -> ViewText.UNAVAILABLE_ASSET_UNIVERSE_CHANGED
    ComparisonUnavailableReason.UNSUPPORTED_TRADE.name -> ViewText.UNAVAILABLE_UNSUPPORTED_TRADE
    ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE.name -> ViewText.UNAVAILABLE_UNEXPLAINED_BALANCE_CHANGE
    else -> ViewText.UNAVAILABLE_INVALID_RESPONSE
}
