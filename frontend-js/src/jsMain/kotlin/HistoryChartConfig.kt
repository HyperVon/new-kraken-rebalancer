package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.view.util.ChartProps
import kotlinx.browser.window
import kotlin.js.json

@JsName("Chart")
internal external class Chart(ctx: dynamic, config: dynamic)

/**
 * Omit datasets with config-time `hidden: true` from the legend (e.g. Day · Total only).
 * Legend click toggles use `meta.hidden` only, so those series stay listed and can be restored.
 */
internal fun legendLabelsFilter(item: dynamic, chartData: dynamic): Boolean {
    val rawIdx = item?.datasetIndex
    if (rawIdx == null || rawIdx == undefined) return true
    val idx = (rawIdx as Number).toInt()
    val datasets = chartData?.datasets ?: return true
    val ds = datasets[idx] ?: return true
    return ds.hidden != true
}

private fun buildLegendConfig(): dynamic = json(
    ChartProps.LABELS to
        json(
            ChartProps.COLOR to ChartProps.COLOR_LEGEND_LABEL,
            ChartProps.FONT to
                json(
                    ChartProps.FAMILY to ChartProps.FONT_INTER,
                    ChartProps.SIZE to ChartProps.FONT_SIZE_LEGEND,
                ),
            ChartProps.USE_POINT_STYLE to true,
            ChartProps.POINT_STYLE to ChartProps.LEGEND_POINT_STYLE_LINE,
            ChartProps.POINT_STYLE_WIDTH to ChartProps.LEGEND_POINT_STYLE_WIDTH,
            ChartProps.FILTER to { item: dynamic, chartData: dynamic -> legendLabelsFilter(item, chartData) },
        ),
)

private fun buildTooltipConfig(): dynamic = json(
    ChartProps.BACKGROUND_COLOR to ChartProps.COLOR_TOOLTIP_BG,
    ChartProps.BORDER_COLOR to ChartProps.COLOR_TOOLTIP_BORDER,
    ChartProps.BORDER_WIDTH to ChartProps.BORDER_WIDTH_TOOLTIP,
    ChartProps.TITLE_COLOR to ChartProps.COLOR_TOOLTIP_TITLE,
    ChartProps.BODY_COLOR to ChartProps.COLOR_TOOLTIP_BODY,
    ChartProps.BODY_FONT to
        json(
            ChartProps.FAMILY to ChartProps.FONT_MONO,
        ),
    ChartProps.PADDING to ChartProps.PADDING_TOOLTIP,
    ChartProps.CORNER_RADIUS to ChartProps.CORNER_RADIUS_TOOLTIP,
)

private fun buildScalesConfig(): dynamic = json(
    ChartProps.X to
        json(
            ChartProps.TYPE to ChartProps.TIME_TYPE,
            ChartProps.TIME to
                json(
                    ChartProps.TOOLTIP_FORMAT to ChartProps.TIME_FORMAT_DEFAULT,
                ),
            ChartProps.GRID to
                json(
                    ChartProps.COLOR to ChartProps.COLOR_GRID_LINE,
                ),
            ChartProps.TICKS to
                json(
                    ChartProps.COLOR to ChartProps.COLOR_TICK,
                    ChartProps.MAX_TICKS_LIMIT to ChartProps.MAX_TICKS_LIMIT_DEFAULT,
                ),
        ),
    ChartProps.Y to
        json(
            ChartProps.GRID to
                json(
                    ChartProps.COLOR to ChartProps.COLOR_GRID_LINE,
                ),
            ChartProps.TICKS to
                json(
                    ChartProps.COLOR to ChartProps.COLOR_TICK,
                ),
        ),
)

private fun buildZoomPluginConfig(): dynamic = json(
    ChartProps.PAN to
        json(
            ChartProps.ENABLED to false,
            ChartProps.MODE to ChartProps.MODE_X,
        ),
    ChartProps.ZOOM to
        json(
            ChartProps.WHEEL to json(ChartProps.ENABLED to true),
            ChartProps.PINCH to json(ChartProps.ENABLED to true),
            ChartProps.DRAG to json(ChartProps.ENABLED to true),
            ChartProps.MODE to ChartProps.MODE_X,
        ),
    ChartProps.LIMITS to
        json(
            ChartProps.X to
                json(
                    ChartProps.MIN to ChartProps.ORIGINAL,
                    ChartProps.MAX to ChartProps.ORIGINAL,
                    ChartProps.MIN_RANGE to ChartProps.ZOOM_MIN_RANGE_MS,
                ),
        ),
)

private fun buildDefaultChartOptions(): dynamic = json(
    ChartProps.RESPONSIVE to true,
    ChartProps.MAINTAIN_ASPECT_RATIO to false,
    ChartProps.PLUGINS to
        json(
            ChartProps.LEGEND to buildLegendConfig(),
            ChartProps.TOOLTIP to buildTooltipConfig(),
            ChartProps.ZOOM to buildZoomPluginConfig(),
        ),
    ChartProps.SCALES to buildScalesConfig(),
)

private val chartDefaults: dynamic = buildDefaultChartOptions()

fun registerHistoryGlobals() {
    window.asDynamic().chartDefaults = chartDefaults
}

/** Density-based radius: full radius up to FULL_MAX points, half until HALF_MAX, then hidden. */
private fun radiusForCount(pointCount: Int, fullRadius: Int): Int = when {
    pointCount <= ChartProps.POINT_DENSITY_FULL_MAX -> fullRadius
    pointCount <= ChartProps.POINT_DENSITY_HALF_MAX -> fullRadius / 2
    else -> ChartProps.POINT_RADIUS_HIDDEN
}

internal fun pointRadiusForCount(pointCount: Int, primary: Boolean): Int {
    val full = if (primary) ChartProps.POINT_RADIUS_PRIMARY else ChartProps.POINT_RADIUS_SECONDARY
    return radiusForCount(pointCount, full)
}

internal fun pointHoverRadiusForCount(pointCount: Int, primary: Boolean): Int {
    val full =
        if (primary) ChartProps.POINT_HOVER_RADIUS_PRIMARY else ChartProps.POINT_HOVER_RADIUS_SECONDARY
    return radiusForCount(pointCount, full)
}

internal fun getClonedChartOptions(): dynamic {
    val options: dynamic = JSON.parse(JSON.stringify(window.asDynamic().chartDefaults))
    when (currentRange) {
        TimeRange.TWENTY_FOUR_HOURS.key -> options.scales.x.time.unit = ChartProps.TIME_UNIT_HOUR
        TimeRange.ALL.key -> js("delete options.scales.x.time.unit")
        else -> options.scales.x.time.unit = ChartProps.TIME_UNIT_DAY
    }
    // JSON clone above strips functions, so re-attach callbacks here.
    // Any zoom gesture (drag/wheel/pinch/buttons) must re-sync the pan scrubber,
    // not just the toolbar Zoom buttons.
    options.plugins.zoom.zoom[ChartProps.ON_ZOOM_COMPLETE] = { ctx: dynamic ->
        syncScrubberFromZoomContext(ctx)
    }
    options.plugins.legend.labels[ChartProps.FILTER] = { item: dynamic, chartData: dynamic ->
        legendLabelsFilter(item, chartData)
    }
    options.plugins.legend.onClick = { _: dynamic, legendItem: dynamic, legend: dynamic ->
        try {
            val chart = legend.chart
            if (chart != null && chart != undefined && legendItem != null && legendItem != undefined) {
                val rawIdx = legendItem.datasetIndex
                if (rawIdx != null && rawIdx != undefined) {
                    val idx = (rawIdx as Number).toInt()
                    val isVisible = try {
                        (chart.isDatasetVisible(idx)).unsafeCast<Boolean>()
                    } catch (_: Throwable) {
                        true
                    }
                    try {
                        val setVis = chart.setDatasetVisibility
                        if (setVis != null && setVis != undefined) {
                            setVis.call(chart, idx, !isVisible)
                        } else {
                            val ds = chart.data.datasets[idx]
                            if (ds != null && ds != undefined) {
                                ds.hidden = isVisible
                            }
                        }
                    } catch (_: Throwable) {
                        try {
                            val ds = chart.data.datasets[idx]
                            if (ds != null && ds != undefined) {
                                ds.hidden = isVisible
                            }
                        } catch (_: Throwable) {
                        }
                    }
                    try {
                        chart.update()
                    } catch (_: Throwable) {
                    }
                    try {
                        val canvasId = chart.canvas?.id?.toString() ?: ""
                        if (canvasId.isNotEmpty()) {
                            val live = captureChartVisibility(chart)
                            if (live.isNotEmpty()) {
                                visibilityStates[canvasId] = live.toMutableMap()
                            }
                            if (!HistoryViewPrefs.hasUserInteracted()) {
                                HistoryViewPrefs.setHasUserInteracted(true)
                            }
                            // Also reflect diverged state in the preset selector
                            HistoryViewPrefs.markCurrentViewModified()
                            HistorySessionState.save()
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }
    return options
}

internal fun createLineChartConfig(datasets: Array<dynamic>, options: dynamic): dynamic {
    val config: dynamic = json()
    config.type = "line"
    config.data = json()
    config.data.datasets = datasets
    config.options = options
    return config
}
