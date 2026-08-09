package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.api.TradeRecord
import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlIds
import kotlinx.browser.document
import org.w3c.dom.*
import com.gemini.krakenbot.view.util.HtmlQueries.TIME_RANGE_BTNS as TIME_RANGE_BTNS_QUERY

internal val charts = mutableMapOf<String, dynamic>()
internal var currentRange = TimeRange.THIRTY_DAYS.key
internal var loadedRange = TimeRange.THIRTY_DAYS.key
internal var historyLoadGeneration = 0L
internal var allTrades: List<TradeRecord> = emptyList()
internal val visibilityStates = mutableMapOf<String, MutableMap<String, Boolean>>()
private val pendingPresetVisibility = mutableSetOf<String>()
private var visibilityBackupBeforePreset: Map<String, Map<String, Boolean>>? = null
internal val originalChartRanges = mutableMapOf<String, ChartRange>()
private val historyChartIds =
    listOf(
        HtmlIds.REBALANCER_COMPARISON_CHART,
        HtmlIds.PORTFOLIO_VALUE_CHART,
        HtmlIds.ASSET_HOLDINGS_CHART,
        HtmlIds.ALLOCATION_DRIFT_CHART,
        HtmlIds.CUMULATIVE_NET_CASH_FLOW_CHART,
    )

private val ACTIVE = CssClass.Utility.Active.value

internal fun historyCurrentRange(): String = currentRange

internal fun captureChartVisibility(chart: dynamic): MutableMap<String, Boolean> {
    if (chart == null || chart == undefined) return mutableMapOf()
    val states = mutableMapOf<String, Boolean>()
    val datasets = chart.data.datasets
    if (datasets != null && datasets != undefined) {
        val length: Int = (datasets.length).unsafeCast<Int>()
        repeat(length) { i ->
            val label = datasets[i].label.toString()
            val visible: Boolean = (chart.isDatasetVisible(i)).unsafeCast<Boolean>()
            states[label] = visible
        }
    }
    return states
}

private fun safeDestroy(chart: dynamic) {
    try {
        chart.destroy()
    } catch (_: Throwable) {
    }
}

internal fun historyCaptureVisibility(): Map<String, Map<String, Boolean>> {
    val result = mutableMapOf<String, Map<String, Boolean>>()
    for ((canvasId, chart) in charts) {
        if (chart == null || chart == undefined) continue
        result[canvasId] = captureChartVisibility(chart)
    }
    return result
}

internal fun historyApplyVisibility(visibility: Map<String, Map<String, Boolean>>) {
    visibilityBackupBeforePreset = visibilityStates.mapValues { it.value.toMap() }
    visibilityStates.clear()
    pendingPresetVisibility.clear()
    pendingPresetVisibility.addAll(historyChartIds)
    for ((canvasId, labels) in visibility) {
        visibilityStates[canvasId] = labels.toMutableMap()
    }
    try {
        HistorySessionState.save()
    } catch (_: Throwable) {
    }
}

/** Undo a preset application whose range load failed; restores the pre-preset visibility state. */
internal fun historyRollbackPresetVisibility() {
    val backup = visibilityBackupBeforePreset ?: return
    visibilityStates.clear()
    for ((canvasId, labels) in backup) {
        visibilityStates[canvasId] = labels.toMutableMap()
    }
    pendingPresetVisibility.clear()
    visibilityBackupBeforePreset = null
    try {
        HistorySessionState.save()
    } catch (_: Throwable) {
    }
}

/** Test helper — clears chart instances and visibility between specs. */
internal fun resetHistoryUiState() {
    for ((_, chart) in charts) {
        safeDestroy(chart)
    }
    charts.clear()
    visibilityStates.clear()
    pendingPresetVisibility.clear()
    visibilityBackupBeforePreset = null
    originalChartRanges.clear()
    currentRange = TimeRange.THIRTY_DAYS.key
    loadedRange = TimeRange.THIRTY_DAYS.key
    historyLoadGeneration = 0L
    allTrades = emptyList()
    HistoryViewPrefs.resetInteractionState()
    try {
        HistorySessionState.clear()
    } catch (_: Throwable) {
    }
}

internal fun syncTimeRangeButtons(range: String) {
    currentRange = range
    val buttons = document.querySelectorAll(TIME_RANGE_BTNS_QUERY)
    repeat(buttons.length) { i ->
        val btn = buttons.item(i) as? HTMLElement ?: return@repeat
        val btnRange = btn.getAttribute(HtmlAttrs.DATA_RANGE)
        if (btnRange == range) {
            btn.classList.add(ACTIVE)
        } else {
            btn.classList.remove(ACTIVE)
        }
    }
    try {
        HistorySessionState.save()
    } catch (_: Throwable) {
    }
}

internal fun createOrUpdate(canvasId: String, config: dynamic) {
    val existingChart: dynamic = charts[canvasId]
    val applyingPresetVisibility = canvasId in pendingPresetVisibility
    if (existingChart != null && existingChart != undefined) {
        // When applying a saved preset, skip snapshotting on-screen visibility — otherwise the
        // previous view's series toggles would overwrite the preset we are about to apply.
        if (!applyingPresetVisibility) {
            visibilityStates[canvasId] = captureChartVisibility(existingChart)
        }
        safeDestroy(existingChart)
    }

    val savedStates = visibilityStates[canvasId]
    if (savedStates != null && config.data != null && config.data.datasets != null) {
        val configDatasets = config.data.datasets
        val length: Int = (configDatasets.length).unsafeCast<Int>()
        // DATASET_VISIBILITY_DEFAULT is the fallback for unlisted series: a preset can store
        // default=false plus a few label=true entries to express "hide everything except these".
        val defaultVisible = savedStates[ChartProps.DATASET_VISIBILITY_DEFAULT] ?: true
        repeat(length) { i ->
            val ds = configDatasets[i]
            val label = ds.label.toString()
            val visible = savedStates[label] ?: defaultVisible
            if (savedStates.containsKey(label) || savedStates.containsKey(ChartProps.DATASET_VISIBILITY_DEFAULT)) {
                ds.hidden = !visible
            }
        }
    }

    val ctx = document.getElementById(canvasId) ?: return
    charts[canvasId] = Chart(ctx, config)
    configDataRange(config)?.let { originalChartRanges[canvasId] = it }
    pendingPresetVisibility.remove(canvasId)
    if (pendingPresetVisibility.isEmpty()) visibilityBackupBeforePreset = null
    syncChartScrubber(canvasId)
    try {
        HistorySessionState.save()
    } catch (_: Throwable) {
    }
}

internal fun clearChart(canvasId: String) {
    val chart = charts.remove(canvasId)
    if (chart != null && chart != undefined) {
        safeDestroy(chart)
    }
    originalChartRanges.remove(canvasId)
    pendingPresetVisibility.remove(canvasId)
    if (pendingPresetVisibility.isEmpty()) visibilityBackupBeforePreset = null
    val scrubber = queryChartScrubber(canvasId)
    if (scrubber != null) {
        scrubber.disabled = true
        scrubber.value = "0"
        scrubber.parentElement?.classList?.add(CssClass.Utility.Hidden.value)
    }
    try {
        HistorySessionState.save()
    } catch (_: Throwable) {
    }
}
