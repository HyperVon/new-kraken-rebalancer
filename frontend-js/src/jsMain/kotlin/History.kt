package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlEvents
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.HtmlTags
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.ZoomActions
import com.gemini.krakenbot.view.util.withRange
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import kotlin.collections.mutableMapOf
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.js.json
import com.gemini.krakenbot.view.util.CssClass.Query.CHART_SCRUBBERS as CHART_SCRUBBERS_QUERY
import com.gemini.krakenbot.view.util.CssClass.Query.TIME_RANGE_BTNS as TIME_RANGE_BTNS_QUERY
import com.gemini.krakenbot.view.util.CssClass.Query.ZOOM_BTNS as ZOOM_BTNS_QUERY

@JsName("Chart")
private external class Chart(ctx: dynamic, config: dynamic)

@JsName("Object")
private external object JSObject {
    fun keys(obj: dynamic): Array<String>

    fun assign(target: dynamic, vararg sources: dynamic): dynamic
}

/**
 * Omit datasets with config-time `hidden: true` from the legend (e.g. Day · Total only).
 * Legend click toggles use `meta.hidden` only, so those series stay listed and can be restored.
 */
internal fun legendLabelsFilter(item: dynamic, chart: dynamic): Boolean {
    val rawIdx = item?.datasetIndex
    if (rawIdx == null || rawIdx == undefined) return true
    val idx = (rawIdx as Number).toInt()
    val datasets = chart?.data?.datasets ?: return true
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
            ChartProps.FILTER to { item: dynamic, chart: dynamic -> legendLabelsFilter(item, chart) },
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

private val charts = mutableMapOf<String, dynamic>()
internal var currentRange = TimeRange.THIRTY_DAYS.key
private var allTrades: Array<dynamic> = emptyArray()
internal val visibilityStates = mutableMapOf<String, MutableMap<String, Boolean>>()
private val pendingPresetVisibility = mutableSetOf<String>()
private val originalChartRanges = mutableMapOf<String, ChartRange>()
private val historyChartIds =
    listOf(
        HtmlIds.PORTFOLIO_VALUE_CHART,
        HtmlIds.ASSET_HOLDINGS_CHART,
        HtmlIds.ALLOCATION_DRIFT_CHART,
        HtmlIds.CUMULATIVE_NET_CASH_FLOW_CHART,
    )

fun registerHistoryGlobals() {
    window.asDynamic().chartDefaults = chartDefaults
}

fun initHistory() {
    HistoryViewPrefs.initToolbar()
    setupZoomButtons()
    setupChartScrubbers()
    setupSyncProgressAndLoad()
}

private var syncIntervalId: Int? = null

private const val ACTIVE = CssClass.ACTIVE

internal fun historyCurrentRange(): String = currentRange

internal fun historyLoadAll(range: String): Promise<Unit> = loadAll(range)

internal fun historyCaptureVisibility(): Map<String, Map<String, Boolean>> {
    val result = mutableMapOf<String, Map<String, Boolean>>()
    for ((canvasId, chart) in charts) {
        if (chart == null || chart == undefined) continue
        val states = mutableMapOf<String, Boolean>()
        val datasets = chart.data.datasets
        if (datasets != null && datasets != undefined) {
            val length: Int = (datasets.length).unsafeCast<Int>()
            for (i in 0 until length) {
                val label = datasets[i].label.toString()
                val visible: Boolean = (chart.isDatasetVisible(i)).unsafeCast<Boolean>()
                states[label] = visible
            }
        }
        result[canvasId] = states
    }
    return result
}

internal fun historyApplyVisibility(visibility: Map<String, Map<String, Boolean>>) {
    visibilityStates.clear()
    pendingPresetVisibility.clear()
    pendingPresetVisibility.addAll(historyChartIds)
    for ((canvasId, labels) in visibility) {
        visibilityStates[canvasId] = labels.toMutableMap()
    }
}

/** Test helper — clears chart instances and visibility between specs. */
internal fun resetHistoryUiState() {
    for ((_, chart) in charts) {
        try {
            chart.destroy()
        } catch (_: Throwable) {
        }
    }
    charts.clear()
    visibilityStates.clear()
    pendingPresetVisibility.clear()
    originalChartRanges.clear()
    currentRange = TimeRange.THIRTY_DAYS.key
    allTrades = emptyArray()
    HistoryViewPrefs.resetInteractionState()
}

internal fun syncTimeRangeButtons(range: String) {
    currentRange = range
    val buttons = document.querySelectorAll(TIME_RANGE_BTNS_QUERY)
    for (i in 0 until buttons.length) {
        val btn = buttons.item(i) as? HTMLElement ?: continue
        val btnRange = btn.getAttribute(HtmlAttrs.DATA_RANGE)
        if (btnRange == range) {
            btn.classList.add(ACTIVE)
        } else {
            btn.classList.remove(ACTIVE)
        }
    }
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

internal fun setupZoomButtons() {
    val buttons = document.querySelectorAll(ZOOM_BTNS_QUERY)
    for (i in 0 until buttons.length) {
        val btn = buttons.item(i) as? HTMLElement ?: continue
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
    for (i in 0 until scrubbers.length) {
        val scrubber = scrubbers.item(i) as? HTMLInputElement ?: continue
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
    val position = ((currentRange.min - fullRange.min) / movableSpan * PrecisionConstants.HUNDRED_INT)
        .coerceIn(0.0, PrecisionConstants.HUNDRED_INT.toDouble())
    return ChartScrubberState(enabled = true, position = position)
}

internal fun syncChartScrubber(canvasId: String) {
    val scrubber =
        document
            .querySelector("$CHART_SCRUBBERS_QUERY[${HtmlAttrs.DATA_CHART_ID}=\"$canvasId\"]")
            as? HTMLInputElement ?: return
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

private fun configDataRange(config: dynamic): ChartRange? {
    val datasets = config.data?.datasets ?: return null
    val points = mutableListOf<Double>()
    val datasetCount: Int = datasets.length.unsafeCast<Int>()
    for (i in 0 until datasetCount) {
        val data = datasets[i].data ?: continue
        val pointCount: Int = data.length.unsafeCast<Int>()
        for (j in 0 until pointCount) {
            dynamicNumber(data[j].x)?.let(points::add)
        }
    }
    return if (points.isEmpty()) null else ChartRange(points.min(), points.max())
}

private fun dynamicNumber(value: dynamic): Double? {
    if (value == null || value == undefined) return null
    value.toString().toDoubleOrNull()?.let { return it }
    return Date(value.toString()).getTime().takeUnless { it.isNaN() }
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

internal fun loadHistoryAfterSync(): Promise<Unit> = if (HistoryViewPrefs.hasUserInteracted()) {
    historyLoadAll(historyCurrentRange())
} else {
    HistoryViewPrefs.applyDefaultView()
}

private fun setupSyncProgressAndLoad() {
    checkSyncProgress().then { isDone ->
        if (isDone) {
            loadHistoryAfterSync()
        } else {
            syncIntervalId?.let { window.clearInterval(it) }
            syncIntervalId =
                window.setInterval({
                    if (document.getElementById(HtmlIds.SYNC_PROGRESS_BANNER) == null) {
                        syncIntervalId?.let { window.clearInterval(it) }
                        return@setInterval
                    }
                    checkSyncProgress().then { done ->
                        if (done) {
                            syncIntervalId?.let { window.clearInterval(it) }
                            loadHistoryAfterSync()
                        }
                    }
                }, PrecisionConstants.SYNC_POLL_INTERVAL_MS)
        }
    }

    val buttons = document.querySelectorAll(TIME_RANGE_BTNS_QUERY)
    for (i in 0 until buttons.length) {
        val btn = buttons.item(i) as? HTMLElement
        btn?.addEventListener(HtmlEvents.CLICK, {
            val range = btn.getAttribute(HtmlAttrs.DATA_RANGE) ?: TimeRange.THIRTY_DAYS.key
            HistoryViewPrefs.markCurrentViewModified()
            syncTimeRangeButtons(range)
            loadAll(range)
        })
    }

    val checkbox = document.getElementById(HtmlIds.SHOW_DRY_RUN_CHECKBOX) as? HTMLInputElement
    checkbox?.addEventListener(HtmlEvents.CHANGE, {
        HistoryViewPrefs.markCurrentViewModified()
        renderTradeTable(allTrades)
        buildCumulativeNetCashFlowChart(allTrades, checkbox.checked)
    })
}

private fun fetchJSON(url: String): Promise<dynamic> = window
    .fetch(url)
    .then { res -> res.json() }

private const val EN_US = "en-US"

fun formatUSD(valDouble: Double): String {
    val options: dynamic = json()
    options.minimumFractionDigits = PrecisionConstants.SCALE_USD
    options.maximumFractionDigits = PrecisionConstants.SCALE_USD
    val absVal = if (valDouble < 0) -valDouble else valDouble
    val formatted = absVal.asDynamic().toLocaleString(EN_US, options) as String
    return if (valDouble < 0) "-$$formatted" else "$$formatted"
}

fun formatPctTick(v: Double, includePlus: Boolean = true): String {
    val d = dynamicNumber(v) ?: 0.0
    val sign = if (includePlus && d >= 0.0) "+" else ""
    val options: dynamic = json()
    options.minimumFractionDigits = 0
    options.maximumFractionDigits = PrecisionConstants.SCALE_USD
    return sign + d.asDynamic().toLocaleString(EN_US, options) + "%"
}

internal fun getUniqueSymbols(snapshots: Array<dynamic>, excludeUsd: Boolean = true): List<String> {
    val symbolsSet = mutableSetOf<String>()
    snapshots.forEach { s: dynamic ->
        val assets = s.assets
        if (assets != null) {
            val keys = JSObject.keys(assets)
            keys.forEach { symbolsSet.add(it) }
        }
    }
    return if (excludeUsd) {
        symbolsSet.filter { it != Asset.USD }.sorted()
    } else {
        symbolsSet.sorted()
    }
}

internal fun mapSnapshotsToPoints(snapshots: Array<dynamic>, valueSelector: (dynamic) -> Double): Array<dynamic> =
    snapshots
        .map { s: dynamic ->
            json("x" to s.timestamp, "y" to valueSelector(s))
        }.toTypedArray()

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
    options.plugins.legend.labels[ChartProps.FILTER] = { item: dynamic, chart: dynamic ->
        legendLabelsFilter(item, chart)
    }
    return options
}

internal fun syncScrubberFromZoomContext(ctx: dynamic) {
    val id = ctx?.chart?.canvas?.id
    if (id != null && id != undefined) syncChartScrubber(id.toString())
}

internal fun createLineChartConfig(datasets: Array<dynamic>, options: dynamic): dynamic {
    val config: dynamic = json()
    config.type = "line"
    config.data = json()
    config.data.datasets = datasets
    config.options = options
    return config
}

internal fun createOrUpdate(canvasId: String, config: dynamic) {
    val existingChart: dynamic = charts[canvasId]
    val applyingPresetVisibility = canvasId in pendingPresetVisibility
    if (existingChart != null && existingChart != undefined) {
        if (!applyingPresetVisibility) {
            val states = mutableMapOf<String, Boolean>()
            val datasets = existingChart.data.datasets
            if (datasets != null && datasets != undefined) {
                val length: Int = (datasets.length).unsafeCast<Int>()
                for (i in 0 until length) {
                    val ds = datasets[i]
                    val label = ds.label.toString()
                    val visible: Boolean = (existingChart.isDatasetVisible(i)).unsafeCast<Boolean>()
                    states[label] = visible
                }
            }
            visibilityStates[canvasId] = states
        }
        try {
            existingChart.destroy()
        } catch (_: Throwable) {
        }
    }

    val savedStates = visibilityStates[canvasId]
    if (savedStates != null && config.data != null && config.data.datasets != null) {
        val configDatasets = config.data.datasets
        val length: Int = (configDatasets.length).unsafeCast<Int>()
        val defaultVisible = savedStates[ChartProps.DATASET_VISIBILITY_DEFAULT] ?: true
        for (i in 0 until length) {
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
    syncChartScrubber(canvasId)
}

internal fun buildPortfolioValueChart(snapshots: Array<dynamic>) {
    if (snapshots.asDynamic().length == 0) return

    val pointCount = (snapshots.asDynamic().length as Int)
    val symbolList = getUniqueSymbols(snapshots)

    val totalPortfolioData =
        mapSnapshotsToPoints(snapshots) { s ->
            dynamicNumber(s.totalValueUSD) ?: 0.0
        }

    val datasets = mutableListOf<dynamic>()
    datasets.add(
        json(
            ChartProps.LABEL to ViewText.TOTAL_PORTFOLIO,
            ChartProps.DATA to totalPortfolioData,
            ChartProps.BORDER_COLOR to ChartProps.COLOR_BLUE,
            ChartProps.BACKGROUND_COLOR to ChartProps.COLOR_BLUE_BG,
            ChartProps.FILL to true,
            ChartProps.TENSION to ChartProps.TENSION_CURVED,
            ChartProps.BORDER_WIDTH to ChartProps.BORDER_WIDTH_PRIMARY,
            ChartProps.POINT_RADIUS to pointRadiusForCount(pointCount, primary = true),
            ChartProps.POINT_HOVER_RADIUS to pointHoverRadiusForCount(pointCount, primary = true),
            ChartProps.POINT_HIT_RADIUS to ChartProps.POINT_HIT_RADIUS_DEFAULT,
        ),
    )

    symbolList.forEachIndexed { i, sym ->
        val color = ChartProps.borderColorForSymbol(sym, i)
        val symbolData =
            mapSnapshotsToPoints(snapshots) { s ->
                if (s.assets != null && s.assets[sym] != null) {
                    dynamicNumber(s.assets[sym].valueUSD) ?: 0.0
                } else {
                    0.0
                }
            }

        datasets.add(
            json(
                ChartProps.LABEL to sym,
                ChartProps.DATA to symbolData,
                ChartProps.BORDER_COLOR to color,
                ChartProps.BACKGROUND_COLOR to ChartProps.TRANSPARENT,
                ChartProps.TENSION to ChartProps.TENSION_CURVED,
                ChartProps.BORDER_WIDTH to ChartProps.BORDER_WIDTH_SECONDARY,
                ChartProps.POINT_RADIUS to pointRadiusForCount(pointCount, primary = false),
                ChartProps.POINT_HOVER_RADIUS to pointHoverRadiusForCount(pointCount, primary = false),
                ChartProps.POINT_HIT_RADIUS to ChartProps.POINT_HIT_RADIUS_DEFAULT,
            ),
        )
    }

    val options = getClonedChartOptions()
    options.plugins.tooltip.callbacks = json()
    options.plugins.tooltip.callbacks.label = { ctx: dynamic ->
        val label = ctx.dataset.label.toString()
        val yVal = dynamicNumber(ctx.parsed.y) ?: 0.0
        "$label: ${formatUSD(yVal)}"
    }

    options.scales.y.ticks.callback = { v: Double, _: dynamic, _: dynamic ->
        formatUSD(v)
    }

    createOrUpdate(
        HtmlIds.PORTFOLIO_VALUE_CHART,
        createLineChartConfig(datasets.toTypedArray(), options),
    )
}

internal fun buildAssetHoldingsChart(snapshots: Array<dynamic>) {
    if (snapshots.asDynamic().length == 0) return

    val pointCount = (snapshots.asDynamic().length as Int)
    val symbolList = getUniqueSymbols(snapshots)

    val baseline = snapshots[0]
    val baselines = mutableMapOf<String, Double>()
    symbolList.forEach { sym ->
        val baseVal =
            if (baseline.assets != null && baseline.assets[sym] != null) {
                dynamicNumber(baseline.assets[sym].balance) ?: 0.0
            } else {
                0.0
            }
        baselines[sym] = baseVal
    }

    val datasets =
        symbolList
            .mapIndexed { i, sym ->
                val color = ChartProps.borderColorForSymbol(sym, i)
                val symbolData =
                    mapSnapshotsToPoints(snapshots) { s ->
                        val current =
                            if (s.assets != null && s.assets[sym] != null) {
                                dynamicNumber(s.assets[sym].balance) ?: 0.0
                            } else {
                                0.0
                            }
                        val base = baselines[sym] ?: 0.0
                        if (base > 0.0) {
                            ((current - base) / base) * PrecisionConstants.TOTAL_ALLOCATION_PERCENTAGE
                        } else {
                            0.0
                        }
                    }

                json(
                    ChartProps.LABEL to sym,
                    ChartProps.DATA to symbolData,
                    ChartProps.BORDER_COLOR to color,
                    ChartProps.BACKGROUND_COLOR to ChartProps.TRANSPARENT,
                    ChartProps.TENSION to ChartProps.TENSION_CURVED,
                    ChartProps.BORDER_WIDTH to ChartProps.BORDER_WIDTH_PRIMARY,
                    ChartProps.POINT_RADIUS to pointRadiusForCount(pointCount, primary = false),
                    ChartProps.POINT_HOVER_RADIUS to pointHoverRadiusForCount(pointCount, primary = false),
                    ChartProps.POINT_HIT_RADIUS to ChartProps.POINT_HIT_RADIUS_DEFAULT,
                )
            }.toTypedArray()

    val options = getClonedChartOptions()
    options.plugins.tooltip.callbacks = json()
    options.plugins.tooltip.callbacks.label = { ctx: dynamic ->
        val sym = ctx.dataset.label.toString()
        val pctChange = dynamicNumber(ctx.parsed.y) ?: 0.0
        val snapshot = snapshots[ctx.dataIndex as Int]
        val balance =
            if (snapshot.assets != null && snapshot.assets[sym] != null) {
                dynamicNumber(snapshot.assets[sym].balance) ?: 0.0
            } else {
                0.0
            }
        val pctSign = if (pctChange >= 0.0) "+" else ""
        val balOpts: dynamic = json()
        balOpts.minimumFractionDigits = PrecisionConstants.MIN_CRYPTO_DECIMAL_PLACES
        balOpts.maximumFractionDigits = PrecisionConstants.SCALE_CRYPTO
        val balFormatted = balance.asDynamic().toLocaleString(EN_US, balOpts)
        "$sym: $pctSign${pctChange.toFixed(PrecisionConstants.SCALE_USD)}% ($balFormatted)"
    }

    options.scales.y.ticks.callback = { v: Double, _: dynamic, _: dynamic ->
        formatPctTick(v, includePlus = true)
    }

    createOrUpdate(HtmlIds.ASSET_HOLDINGS_CHART, createLineChartConfig(datasets, options))
}

internal fun buildAllocationDriftChart(snapshots: Array<dynamic>) {
    if (snapshots.asDynamic().length == 0) return

    val pointCount = (snapshots.asDynamic().length as Int)
    val symbolList = getUniqueSymbols(snapshots, excludeUsd = false)

    val datasets =
        symbolList
            .mapIndexed { i, sym ->
                val color = ChartProps.borderColorForSymbol(sym, i)
                val bg = ChartProps.backgroundColorForSymbol(sym, i)
                val symbolData =
                    mapSnapshotsToPoints(snapshots) { s ->
                        if (s.assets != null && s.assets[sym] != null) {
                            dynamicNumber(s.assets[sym].deviationPercent) ?: 0.0
                        } else {
                            0.0
                        }
                    }

                json(
                    ChartProps.LABEL to sym,
                    ChartProps.DATA to symbolData,
                    ChartProps.BORDER_COLOR to color,
                    ChartProps.BACKGROUND_COLOR to bg,
                    ChartProps.FILL to false,
                    ChartProps.TENSION to ChartProps.TENSION_CURVED,
                    ChartProps.BORDER_WIDTH to ChartProps.BORDER_WIDTH_PRIMARY,
                    ChartProps.POINT_RADIUS to pointRadiusForCount(pointCount, primary = false),
                    ChartProps.POINT_HOVER_RADIUS to pointHoverRadiusForCount(pointCount, primary = false),
                    ChartProps.POINT_HIT_RADIUS to ChartProps.POINT_HIT_RADIUS_DEFAULT,
                )
            }.toTypedArray()

    val options = getClonedChartOptions()
    options.plugins.tooltip.callbacks = json()
    options.plugins.tooltip.callbacks.label = { ctx: dynamic ->
        val label = ctx.dataset.label.toString()
        val yVal = dynamicNumber(ctx.parsed.y) ?: 0.0
        val sign = if (yVal >= 0.0) "+" else ""
        "$label: $sign${yVal.toFixed(PrecisionConstants.SCALE_USD)}% ${ViewText.HISTORY_VS_TARGET}"
    }

    options.scales.y[ChartProps.BEGIN_AT_ZERO] = true
    options.scales.y.grid.color = { ctx: dynamic ->
        val tickValue = dynamicNumber(ctx.tick?.value)
        if (tickValue == 0.0) ChartProps.COLOR_ZERO_LINE else ChartProps.COLOR_GRID_LINE
    }
    options.scales.y.ticks.callback = { v: Double, _: dynamic, _: dynamic ->
        formatPctTick(v, includePlus = true)
    }

    createOrUpdate(HtmlIds.ALLOCATION_DRIFT_CHART, createLineChartConfig(datasets, options))
}

internal fun calculateCumulativeNetCashFlow(trades: Array<dynamic>, includeDryRun: Boolean = false): Array<dynamic> =
    calculateSignedCashFlowSeries(trades, includeDryRun) {
            trade,
            delta,
        ->
        delta
    }

internal fun calculateCumulativeNetAfterFees(trades: Array<dynamic>, includeDryRun: Boolean = false): Array<dynamic> =
    calculateSignedCashFlowSeries(trades, includeDryRun) { trade, delta ->
        val fee = dynamicNumber(trade.fee) ?: 0.0
        delta - fee
    }

private inline fun calculateSignedCashFlowSeries(
    trades: Array<dynamic>,
    includeDryRun: Boolean,
    crossinline adjustDelta: (dynamic, Double) -> Double,
): Array<dynamic> {
    if (trades.asDynamic().length == 0) return emptyArray()

    val sorted =
        trades.sortedWith { a: dynamic, b: dynamic ->
            val aTime = Date(a.timestamp.toString()).getTime()
            val bTime = Date(b.timestamp.toString()).getTime()
            aTime.compareTo(bTime)
        }

    val filtered =
        sorted.filter { t: dynamic ->
            val isSuccess = isTrue(t.success)
            val isDryRun = isTrue(t.dryRun)
            isSuccess && (includeDryRun || !isDryRun)
        }

    if (filtered.isEmpty()) return emptyArray()

    val points = mutableListOf<dynamic>()
    var cumulative = 0.0
    for (t in filtered) {
        val amt = dynamicNumber(t.usdAmount) ?: 0.0
        val side = t.side.toString().uppercase()
        val delta =
            when (side) {
                OrderSide.SELL.name -> amt
                OrderSide.BUY.name -> -amt
                else -> continue
            }
        cumulative += adjustDelta(t, delta)
        points.add(json("x" to t.timestamp, "y" to cumulative))
    }

    return points.toTypedArray()
}

internal fun buildCumulativeNetCashFlowChart(trades: Array<dynamic>, includeDryRun: Boolean = false) {
    val grossData = calculateCumulativeNetCashFlow(trades, includeDryRun)
    val netAfterFeesData = calculateCumulativeNetAfterFees(trades, includeDryRun)
    if (grossData.asDynamic().length == 0) return

    val grossChartData = padSinglePointSeries(grossData)
    val netChartData = padSinglePointSeries(netAfterFeesData)

    val grossLabel = if (includeDryRun) ViewText.NET_CASH_FLOW_ALL else ViewText.NET_CASH_FLOW_REALIZED
    val netLabel =
        if (includeDryRun) {
            ViewText.NET_AFTER_FEES_ESTIMATED
        } else {
            ViewText.NET_AFTER_FEES
        }
    val pointCount = (grossChartData.asDynamic().length as Int)

    val datasets =
        arrayOf(
            json(
                ChartProps.LABEL to grossLabel,
                ChartProps.DATA to grossChartData,
                ChartProps.BORDER_COLOR to ChartProps.COLOR_EMERALD,
                ChartProps.BACKGROUND_COLOR to ChartProps.COLOR_GREEN_BG,
                ChartProps.FILL to true,
                ChartProps.TENSION to ChartProps.TENSION_CURVED,
                ChartProps.BORDER_WIDTH to ChartProps.BORDER_WIDTH_PRIMARY,
                ChartProps.POINT_RADIUS to pointRadiusForCount(pointCount, primary = true),
                ChartProps.POINT_HOVER_RADIUS to pointHoverRadiusForCount(pointCount, primary = true),
                ChartProps.POINT_HIT_RADIUS to ChartProps.POINT_HIT_RADIUS_DEFAULT,
            ),
            json(
                ChartProps.LABEL to netLabel,
                ChartProps.DATA to netChartData,
                ChartProps.BORDER_COLOR to ChartProps.COLOR_AMBER,
                ChartProps.BACKGROUND_COLOR to ChartProps.TRANSPARENT,
                ChartProps.FILL to false,
                ChartProps.TENSION to ChartProps.TENSION_CURVED,
                ChartProps.BORDER_WIDTH to ChartProps.BORDER_WIDTH_SECONDARY,
                ChartProps.BORDER_DASH to
                    arrayOf(
                        ChartProps.BORDER_DASH_SEGMENT,
                        ChartProps.BORDER_DASH_GAP,
                    ),
                ChartProps.POINT_RADIUS to pointRadiusForCount(pointCount, primary = false),
                ChartProps.POINT_HOVER_RADIUS to pointHoverRadiusForCount(pointCount, primary = false),
                ChartProps.POINT_HIT_RADIUS to ChartProps.POINT_HIT_RADIUS_DEFAULT,
            ),
        )

    val options = getClonedChartOptions()
    options.scales.y.ticks.callback = { v: Double, _: dynamic, _: dynamic ->
        formatUSD(v)
    }

    createOrUpdate(HtmlIds.CUMULATIVE_NET_CASH_FLOW_CHART, createLineChartConfig(datasets, options))
}

private fun padSinglePointSeries(rawData: Array<dynamic>): Array<dynamic> = if (rawData.size == 1) {
    val firstTradeTime = Date(rawData[0].x.toString()).getTime()
    val startTime = Date(firstTradeTime - PrecisionConstants.ONE_HOUR_MS).toISOString()
    arrayOf(json(ChartProps.X to startTime, ChartProps.Y to 0.0), rawData[0])
} else {
    rawData
}

fun formatPair(trade: JsTradeRecord?): String {
    if (trade?.symbol == null) return ""
    return "${trade.symbol}/USD"
}

internal fun renderTradeTable(trades: Array<JsTradeRecord>) {
    val tbody = document.getElementById(HtmlIds.TRADE_TABLE_BODY) ?: return
    tbody.innerHTML = ""

    val showDryRun = (document.getElementById(HtmlIds.SHOW_DRY_RUN_CHECKBOX) as? HTMLInputElement)?.checked ?: true
    val filteredTrades = if (showDryRun) trades else trades.filter { t -> !isTrue(t.dryRun) }.toTypedArray()

    if (filteredTrades.isEmpty()) {
        val tr = document.createElement(HtmlTags.TR)
        val td = document.createElement(HtmlTags.TD) as HTMLTableCellElement
        td.colSpan = PrecisionConstants.TRADE_TABLE_COLSPAN
        td.className = CssClass.History.EmptyTableCell.value
        td.textContent = ViewText.NO_TRADES_FOUND_PERIOD
        tr.appendChild(td)
        tbody.appendChild(tr)
        return
    }

    filteredTrades.forEach { t ->
        tbody.appendChild(renderTradeRow(t))
    }
}

private fun renderTradeRow(t: JsTradeRecord): HTMLTableRowElement {
    val tr = document.createElement(HtmlTags.TR) as HTMLTableRowElement
    tr.className = CssClass.Table.Hoverable.toString()

    val time = Date(t.timestamp.toString()).asDynamic().toLocaleString()
    val side = t.side ?: ""
    val sideClass =
        when (side) {
            OrderSide.BUY.name -> CssClass.Badge.Buy
            OrderSide.SELL.name -> CssClass.Badge.Sell
            else -> CssClass.Badge.Info
        }
    val success = isTrue(t.success)
    val dryRun = isTrue(t.dryRun)
    val statusText =
        when {
            !success -> ViewText.STATUS_FAILED
            dryRun -> ViewText.STATUS_DRY_RUN
            else -> ViewText.STATUS_SUCCESS
        }
    val statusClass =
        when {
            !success -> CssClass.Badge.Failed
            dryRun -> CssClass.Badge.Info
            else -> CssClass.Badge.Success
        }
    val vol = dynamicNumber(t.volume) ?: 0.0
    val amt = dynamicNumber(t.usdAmount) ?: 0.0
    val price = dynamicNumber(t.price) ?: 0.0
    val fee = dynamicNumber(t.fee) ?: 0.0
    val slippage = dynamicNumber(t.slippagePercent)
    val isEstimatedEconomics = dryRun || t.source == "LOCAL_ESTIMATE"
    val estimatedTitle =
        if (isEstimatedEconomics) {
            ViewText.SLIPPAGE_ESTIMATED_TITLE
        } else {
            null
        }

    val isPlainSuccess = success && !dryRun

    tr.appendChild(createCell(time, CssClass.Table.MonoCol))
    tr.appendChild(createCell(formatPair(t), CssClass.Table.SymbolCol))
    tr.appendChild(createBadgeCell(side, sideClass))
    tr.appendChild(createCell(vol.toFixed(PrecisionConstants.SCALE_CRYPTO), CssClass.Table.MonoCol))
    tr.appendChild(createCell(formatUSD(amt), CssClass.Table.MonoCol))
    // HIST-3: price keeps crypto precision (4-8dp) and fee keeps up to 4dp; zero/missing
    // economics show a muted em-dash, not 0.00000000.
    tr.appendChild(createCellWithOptionalTitle(formatPriceOrDash(price), CssClass.Table.MonoCol, estimatedTitle))
    tr.appendChild(createCellWithOptionalTitle(formatFeeOrDash(fee), CssClass.Table.MonoCol, estimatedTitle))
    tr.appendChild(createSlippageCell(slippage, estimatedTitle))
    tr.appendChild(createStatusCell(statusText, statusClass, t.errorMessage, isPlainSuccess))

    return tr
}

/** HIST-3: format a trade price at crypto precision, or a muted em-dash when it is zero/absent. */
private fun formatPriceOrDash(value: Double): String {
    if (value == 0.0) return ViewText.EM_DASH
    val options: dynamic = json()
    options.minimumFractionDigits = PrecisionConstants.MIN_CRYPTO_DECIMAL_PLACES
    options.maximumFractionDigits = PrecisionConstants.SCALE_CRYPTO
    return "$" + value.asDynamic().toLocaleString(EN_US, options)
}

/** HIST-3: format a trade fee at up to 4dp, or a muted em-dash when it is zero/absent. */
private fun formatFeeOrDash(value: Double): String {
    if (value == 0.0) return ViewText.EM_DASH
    val options: dynamic = json()
    options.minimumFractionDigits = PrecisionConstants.SCALE_USD
    options.maximumFractionDigits = PrecisionConstants.SCALE_FEE
    return "$" + value.asDynamic().toLocaleString(EN_US, options)
}

private fun slippageBadgeClass(value: Double): CssClass = when {
    value > 0.0 -> CssClass.Badge.SlippageAdverse
    value < 0.0 -> CssClass.Badge.SlippageFavorable
    else -> CssClass.Badge.SlippageNeutral
}

private fun formatSignedSlippage(value: Double): String = formatPctTick(value, includePlus = true)

private fun createCellWithOptionalTitle(text: String, cssClass: CssClass, title: String?): HTMLTableCellElement {
    val td = createCell(text, cssClass)
    if (title != null) td.title = title
    return td
}

private fun createSlippageCell(slippage: Double?, estimatedTitle: String?): HTMLTableCellElement {
    val td = document.createElement(HtmlTags.TD) as HTMLTableCellElement
    td.className = CssClass.Table.MonoCol.toString()
    if (slippage == null) {
        td.textContent = ViewText.EM_DASH
        return td
    }
    val span = document.createElement(HtmlTags.SPAN) as HTMLSpanElement
    span.className = slippageBadgeClass(slippage).toString()
    span.textContent = formatSignedSlippage(slippage)
    if (estimatedTitle != null) span.title = estimatedTitle
    td.appendChild(span)
    return td
}

private fun createStatusCell(
    text: String,
    badgeClass: CssClass,
    errorMessage: String?,
    isPlainSuccess: Boolean,
): HTMLTableCellElement {
    val td = document.createElement(HtmlTags.TD) as HTMLTableCellElement
    // HIST-3: a plain success is a quiet dot (removes the always-"SUCCESS" constant column);
    // only failures and dry-run rows keep a labelled badge.
    if (isPlainSuccess) {
        val dot = document.createElement(HtmlTags.SPAN) as HTMLSpanElement
        dot.className = CssClass.Table.StatusDot.toString()
        dot.title = ViewText.STATUS_SUCCESS
        dot.setAttribute(HtmlAttrs.ARIA_LABEL, ViewText.STATUS_SUCCESS)
        dot.setAttribute(HtmlAttrs.ROLE, "img")
        td.appendChild(dot)
        return td
    }
    val span = document.createElement(HtmlTags.SPAN) as HTMLSpanElement
    span.className = badgeClass.toString()
    span.textContent = text
    if (!errorMessage.isNullOrBlank()) {
        span.title = ViewText.TRADE_FAILED_TITLE_PREFIX + errorMessage
    }
    td.appendChild(span)
    return td
}

private fun createCell(text: String, cssClass: CssClass): HTMLTableCellElement {
    val td = document.createElement(HtmlTags.TD) as HTMLTableCellElement
    td.className = cssClass.toString()
    td.textContent = text
    return td
}

private fun createBadgeCell(text: String, badgeClass: CssClass): HTMLTableCellElement {
    val td = document.createElement(HtmlTags.TD) as HTMLTableCellElement
    val span = document.createElement(HtmlTags.SPAN) as HTMLSpanElement
    span.className = badgeClass.toString()
    span.textContent = text
    td.appendChild(span)
    return td
}

internal fun updateStats(stats: JsHistoryStats) {
    val athTitle = document.getElementById(HtmlIds.STAT_ATH_TITLE)
    val ath = document.getElementById(HtmlIds.STAT_ATH)
    val totalTrades = document.getElementById(HtmlIds.STAT_TOTAL_TRADES)
    val totalVolume = document.getElementById(HtmlIds.STAT_TOTAL_VOLUME)
    val totalFees = document.getElementById(HtmlIds.STAT_TOTAL_FEES)
    val avgFeeRate = document.getElementById(HtmlIds.STAT_AVG_FEE_RATE)
    val avgSlippage = document.getElementById(HtmlIds.STAT_AVG_SLIPPAGE)

    if (athTitle != null) {
        athTitle.textContent =
            if (currentRange == TimeRange.ALL.key) {
                ViewText.HISTORY_ALL_TIME_HIGH
            } else {
                ViewText.PERIOD_HIGH
            }
    }
    if (ath != null) ath.textContent = formatUSD(dynamicNumber(stats.allTimeHigh) ?: 0.0)
    if (totalTrades != null) {
        val count = dynamicNumber(stats.totalTradesExecuted) ?: 0.0
        totalTrades.textContent = count.asDynamic().toLocaleString()
    }
    if (totalVolume != null) {
        totalVolume.textContent =
            formatUSD(dynamicNumber(stats.totalVolumeTraded) ?: 0.0)
    }
    if (totalFees != null) totalFees.textContent = formatUSD(dynamicNumber(stats.totalFeesPaid) ?: 0.0)
    if (avgFeeRate != null) {
        val rate = dynamicNumber(stats.avgFeeRatePercent)
        avgFeeRate.textContent =
            if (rate == null) {
                ViewText.PLACEHOLDER_DASHES
            } else {
                formatPctTick(rate, includePlus = false)
            }
    }
    if (avgSlippage != null) {
        val slip = dynamicNumber(stats.avgSlippagePercent)
        avgSlippage.textContent =
            if (slip == null) {
                ViewText.PLACEHOLDER_DASHES
            } else {
                formatSignedSlippage(slip)
            }
    }
}

private fun fetchRanged(vararg routes: String, range: String): Array<Promise<dynamic>> = routes.map { route ->
    fetchJSON(route.withRange(range))
}.toTypedArray()

internal fun loadAll(range: String): Promise<Unit> {
    currentRange = range

    val promises =
        fetchRanged(
            Routes.API_HISTORY_SNAPSHOTS,
            Routes.API_HISTORY_TRADES,
            Routes.API_HISTORY_STATS,
            range = range,
        )

    return Promise.all(promises).then { results ->
        val snapshots = results[0].unsafeCast<Array<JsPortfolioSnapshot>>()
        val trades = results[1].unsafeCast<Array<JsTradeRecord>>()
        val stats = results[2].unsafeCast<JsHistoryStats>()
        allTrades = trades.asDynamic()
        buildPortfolioValueChart(snapshots.asDynamic())
        buildAssetHoldingsChart(snapshots.asDynamic())
        buildAllocationDriftChart(snapshots.asDynamic())
        val showDryRun = (document.getElementById(HtmlIds.SHOW_DRY_RUN_CHECKBOX) as? HTMLInputElement)?.checked ?: true
        buildCumulativeNetCashFlowChart(trades.asDynamic(), showDryRun)
        renderTradeTable(trades)
        updateStats(stats)
    }
}

internal fun checkSyncProgress(): Promise<Boolean> = fetchJSON(Routes.API_HISTORY_SYNC_PROGRESS)
    .then { rawStatus: dynamic ->
        val status = rawStatus.unsafeCast<JsSyncProgress>()
        val banner = document.getElementById(HtmlIds.SYNC_PROGRESS_BANNER) as? HTMLElement
        if (banner == null) {
            true
        } else {
            val seeded = status.seeded ?: false
            if (seeded) {
                banner.style.display = "none"
                true
            } else {
                banner.style.display = "block"
                val offset = dynamicNumber(status.offset) ?: 0.0
                val total = dynamicNumber(status.total) ?: 0.0
                var pct = 0
                if (total > 0.0) {
                    pct =
                        (offset / total * PrecisionConstants.TOTAL_ALLOCATION_PERCENTAGE).toInt().coerceAtMost(
                            PrecisionConstants.HUNDRED_INT,
                        )
                }

                val bar = document.getElementById(HtmlIds.SYNC_PROGRESS_BAR) as? HTMLElement
                val text = document.getElementById(HtmlIds.SYNC_PROGRESS_TEXT) as? HTMLElement

                if (bar != null) bar.style.width = "$pct%"
                if (text != null) {
                    val offsetLabel = offset.asDynamic().toLocaleString()
                    val totalLabel = total.asDynamic().toLocaleString()
                    text.textContent = "$offsetLabel / $totalLabel ($pct%)"
                }

                false
            }
        }
    }.`catch` { e ->
        console.error("Error checking sync progress", e)
        false
    }
