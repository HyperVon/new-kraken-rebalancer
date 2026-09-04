package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.api.RebalancerComparison
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonConfidence
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.util.PrecisionConstants
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
        chartArea?.classList?.add(CssClass.Utility.Hidden.value)
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

    chartArea?.classList?.remove(CssClass.Utility.Hidden.value)
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
        json(ChartProps.X to point.timestamp, ChartProps.Y to dynamicNumber(point.rebalancerValueUSD))
    }.toTypedArray()

    val buyAndHoldData = comparison.points.map { point ->
        json(ChartProps.X to point.timestamp, ChartProps.Y to dynamicNumber(point.buyAndHoldValueUSD))
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
            "Difference: $sign${formatUSD(diff)} ($sign${diffPct.toFixed(PrecisionConstants.SCALE_USD)}%)"
        } else {
            null
        }
    }

    val (latestDiff, latestDiffPct) = comparison.latestDifferenceValues() ?: return
    val signStr = if (latestDiff > 0) "+" else ""
    if (deltaEl != null) {
        deltaEl.textContent =
            "$signStr${formatUSD(latestDiff)} ($signStr${latestDiffPct.toFixed(PrecisionConstants.SCALE_USD)}%)"
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

private fun RebalancerComparison.latestDifferenceValues(): Pair<Double, Double>? {
    val latestDiff = dynamicNumber(latestDifferenceUSD) ?: return null
    val latestDiffPct = dynamicNumber(latestDifferencePercent) ?: return null
    return latestDiff to latestDiffPct
}

private fun RebalancerComparison.hasValidDifferenceValues(): Boolean = latestDifferenceValues() != null

private fun RebalancerComparison.hasSortedTimestamps(): Boolean =
    points.map { dynamicNumber(it.timestamp) }.let { timestamps ->
        timestamps.filterNotNull().let { nonNullTimestamps ->
            nonNullTimestamps.size == timestamps.size &&
                nonNullTimestamps.zipWithNext().all { (previous, current) -> current >= previous }
        }
    }

private fun RebalancerComparison.hasValidBaselinePoint(): Boolean = points.firstOrNull()?.let { first ->
    if (first.timestamp == baselineTimestamp) {
        dynamicNumber(first.rebalancerValueUSD) == dynamicNumber(first.buyAndHoldValueUSD) &&
            dynamicNumber(first.differenceUSD) == 0.0 &&
            dynamicNumber(first.differencePercent) == 0.0
    } else {
        val firstTs = dynamicNumber(first.timestamp)
        val baseTs = dynamicNumber(baselineTimestamp)
        firstTs != null && baseTs != null && firstTs >= baseTs
    }
} == true

private fun RebalancerComparison.hasCompletePointData(): Boolean = points.all { point ->
    point.timestamp.isNotBlank() &&
        dynamicNumber(point.rebalancerValueUSD) != null &&
        dynamicNumber(point.buyAndHoldValueUSD) != null &&
        dynamicNumber(point.differenceUSD) != null &&
        dynamicNumber(point.differencePercent) != null
}

internal fun unavailableReasonText(reason: String?): String = ComparisonUnavailableReason.displayTextFor(reason)
