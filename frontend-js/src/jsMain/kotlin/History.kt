package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.api.PortfolioSnapshot
import com.gemini.krakenbot.api.RebalancerComparison
import com.gemini.krakenbot.api.TradeRecord
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlEvents
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.ZoomActions
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

private val assetColorMap: Map<String, String> by lazy {
    val global = js("window.__ASSET_COLORS__")
    if (global != null && global != undefined) {
        val map = mutableMapOf<String, String>()
        val keys: Array<String> = js("Object.keys(__ASSET_COLORS__)")
        for (key in keys) {
            val v: String = js("__ASSET_COLORS__[key]")
            map[key] = v
        }
        map
    } else {
        emptyMap()
    }
}

private fun colorForSymbol(symbol: String, fallbackIndex: Int): String =
    assetColorMap[symbol.uppercase()] ?: ChartProps.borderColorForSymbol(symbol, fallbackIndex)

private fun bgColorForSymbol(symbol: String, fallbackIndex: Int): String {
    val solid = assetColorMap[symbol.uppercase()]
    if (solid != null) {
        return hexToRgba(solid, 0.1) ?: ChartProps.backgroundColorForSymbol(symbol, fallbackIndex)
    }
    return ChartProps.backgroundColorForSymbol(symbol, fallbackIndex)
}

private fun hexToRgba(hex: String, alpha: Double): String? {
    val clean = hex.removePrefix("#")
    if (clean.length != 6) return null
    val r = clean.substring(0, 2).toIntOrNull(16) ?: return null
    val g = clean.substring(2, 4).toIntOrNull(16) ?: return null
    val b = clean.substring(4, 6).toIntOrNull(16) ?: return null
    return "rgba($r, $g, $b, $alpha)"
}

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
internal var historyLoadGeneration = 0L
internal var allTrades: List<TradeRecord> = emptyList()
internal val visibilityStates = mutableMapOf<String, MutableMap<String, Boolean>>()
private val pendingPresetVisibility = mutableSetOf<String>()
private val originalChartRanges = mutableMapOf<String, ChartRange>()
private val historyChartIds =
    listOf(
        HtmlIds.REBALANCER_COMPARISON_CHART,
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

internal var syncIntervalId: Int? = null

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
            repeat(length) { i ->
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
    historyLoadGeneration = 0L
    allTrades = emptyList()
    HistoryViewPrefs.resetInteractionState()
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
    repeat(datasetCount) { i ->
        val data = datasets[i].data ?: return@repeat
        val pointCount: Int = data.length.unsafeCast<Int>()
        for (j in 0 until pointCount) {
            dynamicNumber(data[j].x)?.let(points::add)
        }
    }
    return if (points.isEmpty()) null else ChartRange(points.min(), points.max())
}

// Payload numbers may arrive as JS numbers OR strings (BigDecimal serializes as text).
// Try a numeric parse first; otherwise treat the value as an ISO timestamp → epoch ms.
internal fun dynamicNumber(value: dynamic): Double? {
    if (value == null || value == undefined) return null
    value.toString().toDoubleOrNull()?.let { parsed -> return parsed.takeIf { it.isFinite() } }
    return Date(value.toString()).getTime().takeIf { it.isFinite() }
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

fun formatUSD(valDouble: Double): String {
    val options: dynamic = json()
    options.minimumFractionDigits = PrecisionConstants.SCALE_USD
    options.maximumFractionDigits = PrecisionConstants.SCALE_USD
    // Format the magnitude, then place the sign before the $ ("-$1.23", not "$-1.23").
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

internal fun getUniqueSymbols(snapshots: List<PortfolioSnapshot>, excludeUsd: Boolean = true): List<String> {
    val symbolsSet = mutableSetOf<String>()
    snapshots.forEach { snapshot ->
        snapshot.assets.keys.forEach { symbolsSet.add(it) }
    }
    return if (excludeUsd) {
        symbolsSet.filter { it != Asset.USD }.sorted()
    } else {
        symbolsSet.sorted()
    }
}

internal fun mapSnapshotsToPoints(
    snapshots: List<PortfolioSnapshot>,
    valueSelector: (PortfolioSnapshot) -> Double,
): Array<dynamic> = snapshots
    .map { snapshot ->
        json("x" to snapshot.timestamp, "y" to valueSelector(snapshot))
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
        // When applying a saved preset, skip snapshotting on-screen visibility — otherwise the
        // previous view's series toggles would overwrite the preset we are about to apply.
        if (!applyingPresetVisibility) {
            val states = mutableMapOf<String, Boolean>()
            val datasets = existingChart.data.datasets
            if (datasets != null && datasets != undefined) {
                val length: Int = (datasets.length).unsafeCast<Int>()
                repeat(length) { i ->
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
    syncChartScrubber(canvasId)
}

private fun clearChart(canvasId: String) {
    val chart = charts.remove(canvasId)
    if (chart != null && chart != undefined) {
        try {
            chart.destroy()
        } catch (_: Throwable) {
        }
    }
    originalChartRanges.remove(canvasId)
    pendingPresetVisibility.remove(canvasId)
    val scrubber =
        document.querySelector(
            "$CHART_SCRUBBERS_QUERY[${HtmlAttrs.DATA_CHART_ID}=\"$canvasId\"]",
        ) as? HTMLInputElement
    if (scrubber != null) {
        scrubber.disabled = true
        scrubber.value = "0"
    }
}

internal fun buildPortfolioValueChart(snapshots: List<PortfolioSnapshot>) {
    if (snapshots.isEmpty()) {
        clearChart(HtmlIds.PORTFOLIO_VALUE_CHART)
        return
    }

    val pointCount = snapshots.size
    val symbolList = getUniqueSymbols(snapshots)

    val totalPortfolioData =
        mapSnapshotsToPoints(snapshots) { snapshot ->
            dynamicNumber(snapshot.totalValueUSD) ?: 0.0
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
        val color = colorForSymbol(sym, i)
        val symbolData =
            mapSnapshotsToPoints(snapshots) { snapshot ->
                dynamicNumber(snapshot.assets[sym]?.valueUSD) ?: 0.0
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

internal fun buildAssetHoldingsChart(snapshots: List<PortfolioSnapshot>) {
    if (snapshots.isEmpty()) {
        clearChart(HtmlIds.ASSET_HOLDINGS_CHART)
        return
    }

    val pointCount = snapshots.size
    val symbolList = getUniqueSymbols(snapshots)

    val baseline = snapshots[0]
    val baselines = mutableMapOf<String, Double>()
    symbolList.forEach { sym ->
        baselines[sym] = dynamicNumber(baseline.assets[sym]?.balance) ?: 0.0
    }

    val datasets =
        symbolList
            .mapIndexed { i, sym ->
                val color = colorForSymbol(sym, i)
                val symbolData =
                    mapSnapshotsToPoints(snapshots) { snapshot ->
                        val current = dynamicNumber(snapshot.assets[sym]?.balance) ?: 0.0
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
        val balance = dynamicNumber(snapshot.assets[sym]?.balance) ?: 0.0
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

internal fun buildAllocationDriftChart(snapshots: List<PortfolioSnapshot>) {
    if (snapshots.isEmpty()) {
        clearChart(HtmlIds.ALLOCATION_DRIFT_CHART)
        return
    }

    val pointCount = snapshots.size
    val symbolList = getUniqueSymbols(snapshots, excludeUsd = false)

    val datasets =
        symbolList
            .mapIndexed { i, sym ->
                val color = colorForSymbol(sym, i)
                val bg = bgColorForSymbol(sym, i)
                val symbolData =
                    mapSnapshotsToPoints(snapshots) { snapshot ->
                        dynamicNumber(snapshot.assets[sym]?.deviationPercent) ?: 0.0
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

internal fun calculateCumulativeNetCashFlow(trades: List<TradeRecord>, includeDryRun: Boolean = false): Array<dynamic> =
    calculateSignedCashFlowSeries(trades, includeDryRun) { _, delta ->
        delta
    }

internal fun calculateCumulativeNetAfterFees(
    trades: List<TradeRecord>,
    includeDryRun: Boolean = false,
): Array<dynamic> = calculateSignedCashFlowSeries(trades, includeDryRun) { trade, delta ->
    val fee = dynamicNumber(trade.fee) ?: 0.0
    delta - fee
}

private inline fun calculateSignedCashFlowSeries(
    trades: List<TradeRecord>,
    includeDryRun: Boolean,
    crossinline adjustDelta: (TradeRecord, Double) -> Double,
): Array<dynamic> {
    if (trades.isEmpty()) return emptyArray()

    val sorted =
        trades.sortedWith { a, b ->
            val aTime = Date(a.timestamp).getTime()
            val bTime = Date(b.timestamp).getTime()
            aTime.compareTo(bTime)
        }

    val filtered =
        sorted.filter { trade ->
            trade.success && (includeDryRun || !trade.dryRun)
        }

    if (filtered.isEmpty()) return emptyArray()

    val points = mutableListOf<dynamic>()
    var cumulative = 0.0
    for (trade in filtered) {
        val amt = dynamicNumber(trade.usdAmount) ?: 0.0
        val side = trade.side.uppercase()
        val delta =
            when (side) {
                OrderSide.SELL.name -> amt
                OrderSide.BUY.name -> -amt
                else -> continue
            }
        cumulative += adjustDelta(trade, delta)
        points.add(json("x" to trade.timestamp, "y" to cumulative))
    }

    return points.toTypedArray()
}

internal fun buildCumulativeNetCashFlowChart(trades: List<TradeRecord>, includeDryRun: Boolean = false) {
    val grossData = calculateCumulativeNetCashFlow(trades, includeDryRun)
    val netAfterFeesData = calculateCumulativeNetAfterFees(trades, includeDryRun)
    if (grossData.asDynamic().length == 0) {
        clearChart(HtmlIds.CUMULATIVE_NET_CASH_FLOW_CHART)
        return
    }

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
        if (chartArea != null) chartArea.classList.add("hidden")
        if (confidenceBadge != null) {
            confidenceBadge.textContent = ""
            confidenceBadge.classList.remove("visible")
        }
        val message = unavailableReasonText(comparison.unavailableReason)
        if (unavailableDiv != null) {
            unavailableDiv.textContent = "${ViewText.COMPARISON_UNAVAILABLE_PREFIX}$message"
            unavailableDiv.classList.add("visible")
        }
        return
    }

    if (chartArea != null) chartArea.classList.remove("hidden")
    if (unavailableDiv != null) {
        unavailableDiv.textContent = ""
        unavailableDiv.classList.remove("visible")
    }
    if (confidenceBadge != null) {
        if (comparison.confidence == "ESTIMATED") {
            confidenceBadge.textContent = ViewText.COMPARISON_CONFIDENCE_ESTIMATED
            confidenceBadge.classList.add("visible")
        } else {
            confidenceBadge.textContent = ""
            confidenceBadge.classList.remove("visible")
        }
    }

    val pointCount = comparison.points.size
    val rebalancerData = comparison.points.map { point ->
        json("x" to point.timestamp, "y" to dynamicNumber(point.rebalancerValueUSD))
    }.toTypedArray()

    val buyAndHoldData = comparison.points.map { point ->
        json("x" to point.timestamp, "y" to dynamicNumber(point.buyAndHoldValueUSD))
    }.toTypedArray()

    val datasets = arrayOf(
        json(
            ChartProps.LABEL to ViewText.REBALANCER,
            ChartProps.DATA to rebalancerData,
            ChartProps.BORDER_COLOR to ChartProps.COLOR_BLUE,
            ChartProps.BACKGROUND_COLOR to ChartProps.TRANSPARENT,
            ChartProps.FILL to false,
            ChartProps.TENSION to ChartProps.TENSION_CURVED,
            ChartProps.BORDER_WIDTH to ChartProps.BORDER_WIDTH_PRIMARY,
            ChartProps.POINT_RADIUS to pointRadiusForCount(pointCount, primary = true),
            ChartProps.POINT_HOVER_RADIUS to pointHoverRadiusForCount(pointCount, primary = true),
            ChartProps.POINT_HIT_RADIUS to ChartProps.POINT_HIT_RADIUS_DEFAULT,
        ),
        json(
            ChartProps.LABEL to ViewText.BUY_AND_HOLD,
            ChartProps.DATA to buyAndHoldData,
            ChartProps.BORDER_COLOR to ChartProps.COLOR_AMBER,
            ChartProps.BACKGROUND_COLOR to ChartProps.TRANSPARENT,
            ChartProps.FILL to false,
            ChartProps.TENSION to ChartProps.TENSION_CURVED,
            ChartProps.BORDER_WIDTH to ChartProps.BORDER_WIDTH_SECONDARY,
            ChartProps.BORDER_DASH to arrayOf(ChartProps.BORDER_DASH_SEGMENT, ChartProps.BORDER_DASH_GAP),
            ChartProps.POINT_RADIUS to pointRadiusForCount(pointCount, primary = false),
            ChartProps.POINT_HOVER_RADIUS to pointHoverRadiusForCount(pointCount, primary = false),
            ChartProps.POINT_HIT_RADIUS to ChartProps.POINT_HIT_RADIUS_DEFAULT,
        ),
    )

    val options = getClonedChartOptions()
    options.plugins.tooltip.callbacks = json()
    options.plugins.tooltip.callbacks.label = { ctx: dynamic ->
        val label = ctx.dataset.label.toString()
        val yVal = dynamicNumber(ctx.parsed.y) ?: 0.0
        "$label: ${formatUSD(yVal)}"
    }
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

    options.scales.y.ticks.callback = { v: Double, _: dynamic, _: dynamic ->
        formatUSD(v)
    }

    val latestDiff = dynamicNumber(comparison.latestDifferenceUSD)!!
    val latestDiffPct = dynamicNumber(comparison.latestDifferencePercent)!!
    val signStr = if (latestDiff > 0) "+" else ""
    if (deltaEl != null) {
        deltaEl.textContent = "$signStr${formatUSD(latestDiff)} ($signStr${latestDiffPct.toFixed(2)}%)"
        deltaEl.className = CssClass.History.ComparisonDelta.value
        if (latestDiff > 0) {
            deltaEl.classList.add("positive")
        } else if (latestDiff < 0) {
            deltaEl.classList.add("negative")
        } else {
            deltaEl.classList.add("neutral")
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

private fun RebalancerComparison.hasValidAvailability(): Boolean = availability == "AVAILABLE" &&
    (confidence == "RECONCILED" || confidence == "ESTIMATED") &&
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
    "INSUFFICIENT_SNAPSHOTS" -> ViewText.UNAVAILABLE_INSUFFICIENT_SNAPSHOTS
    "NON_POSITIVE_BASELINE" -> ViewText.UNAVAILABLE_NON_POSITIVE_BASELINE
    "BASELINE_MISMATCH" -> ViewText.UNAVAILABLE_BASELINE_MISMATCH
    "MISSING_PRICE" -> ViewText.UNAVAILABLE_MISSING_PRICE
    "ASSET_UNIVERSE_CHANGED" -> ViewText.UNAVAILABLE_ASSET_UNIVERSE_CHANGED
    "UNSUPPORTED_TRADE" -> ViewText.UNAVAILABLE_UNSUPPORTED_TRADE
    "UNEXPLAINED_BALANCE_CHANGE" -> ViewText.UNAVAILABLE_UNEXPLAINED_BALANCE_CHANGE
    else -> ViewText.UNAVAILABLE_INVALID_RESPONSE
}

// A single point renders as a lone dot; prepend a synthetic zero one hour earlier so the
// cumulative series draws as a line from the baseline.
private fun padSinglePointSeries(rawData: Array<dynamic>): Array<dynamic> = if (rawData.size == 1) {
    val firstTradeTime = Date(rawData[0].x.toString()).getTime()
    val startTime = Date(firstTradeTime - PrecisionConstants.ONE_HOUR_MS).toISOString()
    arrayOf(json(ChartProps.X to startTime, ChartProps.Y to 0.0), rawData[0])
} else {
    rawData
}
