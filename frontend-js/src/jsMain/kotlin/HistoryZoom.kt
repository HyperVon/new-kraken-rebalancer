package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlEvents
import com.gemini.krakenbot.view.util.ZoomActions
import kotlinx.browser.document
import org.w3c.dom.*
import kotlin.js.json
import com.gemini.krakenbot.view.util.HtmlQueries.CHART_SCRUBBERS as CHART_SCRUBBERS_QUERY
import com.gemini.krakenbot.view.util.HtmlQueries.ZOOM_BTNS as ZOOM_BTNS_QUERY

internal fun setupZoomButtons() {
    val buttons = document.querySelectorAll(ZOOM_BTNS_QUERY)
    repeat(buttons.length) { i ->
        val btn = buttons.item(i) as? HTMLElement ?: return@repeat
        btn.addEventListener(HtmlEvents.CLICK, {
            val canvasId = btn.getAttribute(HtmlAttrs.DATA_CHART_ID) ?: return@addEventListener
            val action = btn.getAttribute(HtmlAttrs.DATA_ZOOM_ACTION) ?: return@addEventListener
            val chart = charts[canvasId] ?: return@addEventListener
            when (action) {
                ZoomActions.IN -> chart.zoom(ChartProps.ZOOM_FACTOR_IN)
                ZoomActions.OUT -> chart.zoom(ChartProps.ZOOM_FACTOR_OUT)
                ZoomActions.RESET -> chart.resetZoom()
            }
            syncChartScrubber(canvasId)
            updateTimeUnitForChart(chart)
        })
    }
}

internal data class ChartRange(val min: Double, val max: Double) {
    val span: Double
        get() = max - min
}

internal data class ChartScrubberState(val enabled: Boolean, val position: Double)

internal fun setupChartScrubbers() {
    val scrubbers = document.querySelectorAll(CHART_SCRUBBERS_QUERY)
    repeat(scrubbers.length) { i ->
        val scrubber = scrubbers.item(i) as? HTMLInputElement ?: return@repeat
        scrubber.addEventListener(HtmlEvents.INPUT, {
            val canvasId = scrubber.getAttribute(HtmlAttrs.DATA_CHART_ID) ?: return@addEventListener
            panChartToScrubberPosition(canvasId, dynamicNumber(scrubber.value) ?: 0.0)
        })
    }
}

internal fun chartScrubberState(chart: dynamic, fallbackRange: ChartRange?): ChartScrubberState? {
    val fullRange = chartInitialRange(chart) ?: fallbackRange ?: return null
    val currentRange = chartCurrentRange(chart) ?: return null
    if (fullRange.span <= 0.0 || currentRange.span <= 0.0 || currentRange.span >= fullRange.span) {
        return ChartScrubberState(enabled = false, position = 0.0)
    }

    val movableSpan = fullRange.span - currentRange.span
    if (movableSpan <= 0.0) {
        return ChartScrubberState(enabled = false, position = 0.0)
    }
    val position = ((currentRange.min - fullRange.min) / movableSpan * PrecisionConstants.HUNDRED_INT)
        .coerceIn(0.0, PrecisionConstants.HUNDRED_INT.toDouble())
    return ChartScrubberState(enabled = true, position = position)
}

internal fun syncChartScrubber(canvasId: String) {
    val scrubber = queryChartScrubber(canvasId) ?: return
    val state = chartScrubberState(charts[canvasId], originalChartRanges[canvasId])
    scrubber.disabled = state?.enabled != true
    scrubber.value = if (state?.enabled == true) state.position.toString() else "0"
}

internal fun panChartToScrubberPosition(canvasId: String, position: Double) {
    val chart = charts[canvasId] ?: return
    val fullRange = chartInitialRange(chart) ?: originalChartRanges[canvasId] ?: return
    val currentRange = chartCurrentRange(chart) ?: return
    if (fullRange.span <= currentRange.span) return

    val start =
        fullRange.min +
            (fullRange.span - currentRange.span) *
            (position / PrecisionConstants.HUNDRED_INT).coerceIn(0.0, 1.0)
    setChartXRange(chart, ChartRange(start, start + currentRange.span))
    // Keep the user's slider position; do not re-read the chart mid-drag (that can
    // snap the thumb if scale reads lag one frame behind zoomScale).
}

private fun chartInitialRange(chart: dynamic): ChartRange? {
    val getInitialScaleBounds = chart?.getInitialScaleBounds
    if (getInitialScaleBounds != null && getInitialScaleBounds != undefined) {
        val bounds = getInitialScaleBounds()
        val min = dynamicNumber(bounds?.x?.min)
        val max = dynamicNumber(bounds?.x?.max)
        if (min != null && max != null) return ChartRange(min, max)
    }
    return null
}

private fun chartCurrentRange(chart: dynamic): ChartRange? {
    val scale = chart?.scales?.x ?: return null
    val min = dynamicNumber(scale.min) ?: return null
    val max = dynamicNumber(scale.max) ?: return null
    return ChartRange(min, max)
}

internal fun configDataRange(config: dynamic): ChartRange? {
    val datasets = config.data?.datasets ?: return null
    val points = mutableListOf<Double>()
    val datasetCount: Int = datasets.length.unsafeCast<Int>()
    repeat(datasetCount) { i ->
        val data = datasets[i].data ?: return@repeat
        val pointCount: Int = data.length.unsafeCast<Int>()
        for (j in 0 until pointCount) {
            dynamicNumber(data[j].x)?.let(points::add)
        }
    }
    return if (points.isEmpty()) null else ChartRange(points.min(), points.max())
}

private fun setChartXRange(chart: dynamic, range: ChartRange) {
    // chartjs-plugin-zoom owns scale limits after any zoom; writing options.scales.x
    // and calling update() is ignored. Prefer zoomScale so the plugin's state matches.
    val zoomScale = chart.zoomScale
    if (zoomScale != null && zoomScale != undefined) {
        zoomScale(
            ChartProps.MODE_X,
            json(ChartProps.MIN to range.min, ChartProps.MAX to range.max),
            ChartProps.TRANSITION_NONE,
        )
        return
    }
    if (chart.options == null || chart.options == undefined) chart.options = json()
    if (chart.options.scales == null || chart.options.scales == undefined) chart.options.scales = json()
    if (chart.options.scales.x == null || chart.options.scales.x == undefined) chart.options.scales.x = json()
    chart.options.scales.x.min = range.min
    chart.options.scales.x.max = range.max
    val update = chart.update
    if (update != null && update != undefined) update.call(chart)
}

internal fun updateTimeUnitForChart(chart: dynamic) {
    val currentRange = chartCurrentRange(chart) ?: return
    val newUnit = when {
        currentRange.span < PrecisionConstants.ONE_HOUR_MS -> ChartProps.TIME_UNIT_MINUTE
        currentRange.span < PrecisionConstants.ONE_DAY_MS -> ChartProps.TIME_UNIT_HOUR
        else -> ChartProps.TIME_UNIT_DAY
    }
    val time = chart.options?.scales?.x?.time ?: return
    val currentUnit = time.unit
    if (currentUnit != newUnit) {
        time.unit = newUnit
        val update = chart.update
        if (update != null && update != undefined) update.call(chart)
    }
}

internal fun syncScrubberFromZoomContext(ctx: dynamic) {
    val chart = ctx?.chart
    val id = chart?.canvas?.id
    if (id == null || id == undefined) return
    val canvasId = id.toString()
    val existingChart = charts[canvasId] ?: return

    syncChartScrubber(canvasId)
    updateTimeUnitForChart(existingChart)
}
