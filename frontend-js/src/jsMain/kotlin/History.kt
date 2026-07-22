package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.CssClass.Query.TIME_RANGE_BTNS as TIME_RANGE_BTNS_QUERY
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlEvents
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import kotlin.collections.mutableMapOf
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.js.json

@JsName("Chart")
private external class Chart(ctx: dynamic, config: dynamic)

@JsName("Object")
private external object JSObject {
    fun keys(obj: dynamic): Array<String>
    fun assign(target: dynamic, vararg sources: dynamic): dynamic
}

private val CHART_COLORS = ChartProps.PALETTE_BORDER_COLORS
private val CHART_BG = ChartProps.PALETTE_BG_COLORS

private fun buildLegendConfig(): dynamic = json(
    ChartProps.LABELS to json(
        ChartProps.COLOR to ChartProps.COLOR_LEGEND_LABEL,
        ChartProps.FONT to json(
            ChartProps.FAMILY to ChartProps.FONT_INTER,
            ChartProps.SIZE to 12
        )
    )
)

private fun buildTooltipConfig(): dynamic = json(
    ChartProps.BACKGROUND_COLOR to ChartProps.COLOR_TOOLTIP_BG,
    ChartProps.BORDER_COLOR to ChartProps.COLOR_TOOLTIP_BORDER,
    ChartProps.BORDER_WIDTH to 1,
    ChartProps.TITLE_COLOR to ChartProps.COLOR_TOOLTIP_TITLE,
    ChartProps.BODY_COLOR to ChartProps.COLOR_TOOLTIP_BODY,
    ChartProps.BODY_FONT to json(
        ChartProps.FAMILY to ChartProps.FONT_MONO
    ),
    ChartProps.PADDING to 12,
    ChartProps.CORNER_RADIUS to 8
)

private fun buildScalesConfig(): dynamic = json(
    ChartProps.X to json(
        ChartProps.TYPE to ChartProps.TIME_TYPE,
        ChartProps.TIME to json(
            ChartProps.TOOLTIP_FORMAT to ChartProps.TIME_FORMAT_DEFAULT
        ),
        ChartProps.GRID to json(
            ChartProps.COLOR to ChartProps.COLOR_GRID_LINE
        ),
        ChartProps.TICKS to json(
            ChartProps.COLOR to ChartProps.COLOR_TICK,
            ChartProps.MAX_TICKS_LIMIT to 8
        )
    ),
    ChartProps.Y to json(
        ChartProps.GRID to json(
            ChartProps.COLOR to ChartProps.COLOR_GRID_LINE
        ),
        ChartProps.TICKS to json(
            ChartProps.COLOR to ChartProps.COLOR_TICK
        )
    )
)

private fun buildDefaultChartOptions(): dynamic = json(
    ChartProps.RESPONSIVE to true,
    ChartProps.MAINTAIN_ASPECT_RATIO to false,
    ChartProps.PLUGINS to json(
        ChartProps.LEGEND to buildLegendConfig(),
        ChartProps.TOOLTIP to buildTooltipConfig()
    ),
    ChartProps.SCALES to buildScalesConfig()
)

private val chartDefaults: dynamic = buildDefaultChartOptions()

private val charts = mutableMapOf<String, dynamic>()
private var currentRange = TimeRange.THIRTY_DAYS.key
private var allTrades: Array<dynamic> = emptyArray()
private val visibilityStates = mutableMapOf<String, MutableMap<String, Boolean>>()

fun registerHistoryGlobals() {
    window.asDynamic().chartDefaults = chartDefaults
}

fun initHistory() {
    setupSyncProgressAndLoad()
}

private var syncIntervalId: Int? = null

private fun setupSyncProgressAndLoad() {
    checkSyncProgress().then { isDone ->
        if (isDone) {
            loadAll(TimeRange.THIRTY_DAYS.key)
        } else {
            syncIntervalId?.let { window.clearInterval(it) }
            syncIntervalId = window.setInterval({
                if (document.getElementById(HtmlIds.SYNC_PROGRESS_BANNER) == null) {
                    syncIntervalId?.let { window.clearInterval(it) }
                    return@setInterval
                }
                checkSyncProgress().then { done ->
                    if (done) {
                        syncIntervalId?.let { window.clearInterval(it) }
                        loadAll(currentRange)
                    }
                }
            }, 3000)
        }
    }

    val buttons = document.querySelectorAll(TIME_RANGE_BTNS_QUERY)
    for (i in 0 until buttons.length) {
        val btn = buttons.item(i) as? HTMLElement
        btn?.addEventListener(HtmlEvents.CLICK, {
            val list = document.querySelectorAll(TIME_RANGE_BTNS_QUERY)
            for (j in 0 until list.length) {
                (list.item(j) as? HTMLElement)?.classList?.remove("active")
            }
            btn.classList.add("active")
            val range = btn.getAttribute(HtmlAttrs.DATA_RANGE) ?: TimeRange.THIRTY_DAYS.key
            loadAll(range)
        })
    }

    val checkbox = document.getElementById(HtmlIds.SHOW_DRY_RUN_CHECKBOX) as? HTMLInputElement
    checkbox?.addEventListener(HtmlEvents.CHANGE, {
        renderTradeTable(allTrades)
        buildCumulativePLChart(allTrades, checkbox.checked)
    })
}

private fun fetchJSON(url: String): Promise<dynamic> {
    return window.fetch(url)
        .then { res -> res.json() }
}

fun formatUSD(valDouble: Double): String {
    val options: dynamic = json()
    options.minimumFractionDigits = 2
    options.maximumFractionDigits = 2
    return "$" + valDouble.asDynamic().toLocaleString("en-US", options)
}

fun formatPctTick(v: Double, includePlus: Boolean = true): String {
    val d = v.toString().toDoubleOrNull() ?: 0.0
    val sign = if (includePlus && d >= 0.0) "+" else ""
    val options: dynamic = json()
    options.minimumFractionDigits = 0
    options.maximumFractionDigits = 2
    return sign + d.asDynamic().toLocaleString("en-US", options) + "%"
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

internal fun mapSnapshotsToPoints(snapshots: Array<dynamic>, valueSelector: (dynamic) -> Double): Array<dynamic> {
    return snapshots.map { s: dynamic ->
        json("x" to s.timestamp, "y" to valueSelector(s))
    }.toTypedArray()
}

internal fun getClonedChartOptions(): dynamic {
    when (currentRange) {
        TimeRange.TWENTY_FOUR_HOURS.key -> chartDefaults.scales.x.time.unit = "hour"
        TimeRange.ALL.key -> js("delete chartDefaults.scales.x.time.unit")
        else -> chartDefaults.scales.x.time.unit = "day"
    }

    val options: dynamic = JSObject.assign(json(), window.asDynamic().chartDefaults)
    options.plugins = JSObject.assign(json(), window.asDynamic().chartDefaults.plugins)
    options.plugins.tooltip = JSObject.assign(json(), window.asDynamic().chartDefaults.plugins.tooltip)
    options.scales = JSObject.assign(json(), window.asDynamic().chartDefaults.scales)
    options.scales.x = JSObject.assign(json(), window.asDynamic().chartDefaults.scales.x)
    options.scales.x.time = JSObject.assign(json(), window.asDynamic().chartDefaults.scales.x.time)
    options.scales.x.ticks = JSObject.assign(json(), window.asDynamic().chartDefaults.scales.x.ticks)
    options.scales.y = JSObject.assign(json(), window.asDynamic().chartDefaults.scales.y)
    options.scales.y.ticks = JSObject.assign(json(), window.asDynamic().chartDefaults.scales.y.ticks)

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

internal fun createOrUpdate(canvasId: String, config: dynamic) {
    val existingChart: dynamic = charts[canvasId]
    if (existingChart != null && existingChart != undefined) {
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
        try {
            existingChart.destroy()
        } catch (_: Throwable) {}
    }

    val savedStates = visibilityStates[canvasId]
    if (savedStates != null && config.data != null && config.data.datasets != null) {
        val configDatasets = config.data.datasets
        val length: Int = (configDatasets.length).unsafeCast<Int>()
        for (i in 0 until length) {
            val ds = configDatasets[i]
            val label = ds.label.toString()
            val savedVisible = savedStates[label]
            if (savedVisible != null) {
                ds.hidden = (!savedVisible)
            }
        }
    }

    val ctx = document.getElementById(canvasId) ?: return
    charts[canvasId] = Chart(ctx, config)
}

internal fun buildPortfolioValueChart(snapshots: Array<dynamic>) {
    if (snapshots.asDynamic().length == 0) return

    val symbolList = getUniqueSymbols(snapshots)

    val totalPortfolioData = mapSnapshotsToPoints(snapshots) { s ->
        s.totalValueUSD.toString().toDoubleOrNull() ?: 0.0
    }

    val datasets = mutableListOf<dynamic>()
    datasets.add(json(
        ChartProps.LABEL to ViewText.TOTAL_PORTFOLIO,
        ChartProps.DATA to totalPortfolioData,
        ChartProps.BORDER_COLOR to ChartProps.COLOR_BLUE_BORDER,
        ChartProps.BACKGROUND_COLOR to ChartProps.COLOR_BLUE_BG,
        ChartProps.FILL to true,
        ChartProps.TENSION to 0.3,
        ChartProps.BORDER_WIDTH to 2,
        ChartProps.POINT_RADIUS to 4,
        ChartProps.POINT_HOVER_RADIUS to 6,
        ChartProps.POINT_HIT_RADIUS to 10
    ))

    symbolList.forEachIndexed { i, sym ->
        val color = CHART_COLORS[(i + 1) % CHART_COLORS.size]
        val symbolData = mapSnapshotsToPoints(snapshots) { s ->
            if (s.assets != null && s.assets[sym] != null) {
                s.assets[sym].valueUSD.toString().toDoubleOrNull() ?: 0.0
            } else {
                0.0
            }
        }

        datasets.add(json(
            ChartProps.LABEL to sym,
            ChartProps.DATA to symbolData,
            ChartProps.BORDER_COLOR to color,
            ChartProps.BACKGROUND_COLOR to ChartProps.TRANSPARENT,
            ChartProps.TENSION to 0.3,
            ChartProps.BORDER_WIDTH to 1.5,
            ChartProps.POINT_RADIUS to 3,
            ChartProps.POINT_HOVER_RADIUS to 5,
            ChartProps.POINT_HIT_RADIUS to 10
        ))
    }

    val options = getClonedChartOptions()
    options.plugins.tooltip.callbacks = json()
    options.plugins.tooltip.callbacks.label = { ctx: dynamic ->
        val label = ctx.dataset.label.toString()
        val yVal = ctx.parsed.y.toString().toDoubleOrNull() ?: 0.0
        "$label: ${formatUSD(yVal)}"
    }

    options.scales.y.ticks.callback = { v: Double, _: dynamic, _: dynamic ->
        formatUSD(v)
    }

    createOrUpdate(HtmlIds.PORTFOLIO_VALUE_CHART, createLineChartConfig(datasets.toTypedArray(), options))
}

internal fun buildAssetHoldingsChart(snapshots: Array<dynamic>) {
    if (snapshots.asDynamic().length == 0) return

    val symbolList = getUniqueSymbols(snapshots)

    val baseline = snapshots[0]
    val baselines = mutableMapOf<String, Double>()
    symbolList.forEach { sym ->
        val baseVal = if (baseline.assets != null && baseline.assets[sym] != null) {
            baseline.assets[sym].balance.toString().toDoubleOrNull() ?: 0.0
        } else {
            0.0
        }
        baselines[sym] = baseVal
    }

    val datasets = symbolList.mapIndexed { i, sym ->
        val color = CHART_COLORS[i % CHART_COLORS.size]
        val symbolData = mapSnapshotsToPoints(snapshots) { s ->
            val current = if (s.assets != null && s.assets[sym] != null) {
                s.assets[sym].balance.toString().toDoubleOrNull() ?: 0.0
            } else {
                0.0
            }
            val base = baselines[sym] ?: 0.0
            if (base > 0.0) ((current - base) / base) * 100.0 else 0.0
        }

        json(
            ChartProps.LABEL to sym,
            ChartProps.DATA to symbolData,
            ChartProps.BORDER_COLOR to color,
            ChartProps.BACKGROUND_COLOR to ChartProps.TRANSPARENT,
            ChartProps.TENSION to 0.3,
            ChartProps.BORDER_WIDTH to 2,
            ChartProps.POINT_RADIUS to 3,
            ChartProps.POINT_HOVER_RADIUS to 5,
            ChartProps.POINT_HIT_RADIUS to 10
        )
    }.toTypedArray()

    val options = getClonedChartOptions()
    options.plugins.tooltip.callbacks = json()
    options.plugins.tooltip.callbacks.label = { ctx: dynamic ->
        val sym = ctx.dataset.label.toString()
        val pctChange = ctx.parsed.y.toString().toDoubleOrNull() ?: 0.0
        val snapshot = snapshots[ctx.dataIndex as Int]
        val balance = if (snapshot.assets != null && snapshot.assets[sym] != null) {
            snapshot.assets[sym].balance.toString().toDoubleOrNull() ?: 0.0
        } else {
            0.0
        }
        val pctSign = if (pctChange >= 0.0) "+" else ""
        val balOpts: dynamic = json()
        balOpts.minimumFractionDigits = 4
        balOpts.maximumFractionDigits = 8
        "$sym: $pctSign${pctChange.toFixed(2)}% (${balance.asDynamic().toLocaleString("en-US", balOpts)})"
    }

    options.scales.y.ticks.callback = { v: Double, _: dynamic, _: dynamic ->
        formatPctTick(v, includePlus = true)
    }

    createOrUpdate(HtmlIds.ASSET_HOLDINGS_CHART, createLineChartConfig(datasets, options))
}

internal fun buildAllocationDriftChart(snapshots: Array<dynamic>) {
    if (snapshots.asDynamic().length == 0) return

    val symbolList = getUniqueSymbols(snapshots, excludeUsd = false)

    val datasets = symbolList.mapIndexed { i, sym ->
        val color = CHART_COLORS[i % CHART_COLORS.size]
        val bg = CHART_BG[i % CHART_BG.size]
        val symbolData = mapSnapshotsToPoints(snapshots) { s ->
            if (s.assets != null && s.assets[sym] != null) {
                s.assets[sym].currentPercent.toString().toDoubleOrNull() ?: 0.0
            } else {
                0.0
            }
        }

        json(
            ChartProps.LABEL to sym,
            ChartProps.DATA to symbolData,
            ChartProps.BORDER_COLOR to color,
            ChartProps.BACKGROUND_COLOR to bg,
            ChartProps.FILL to true,
            ChartProps.TENSION to 0.3,
            ChartProps.BORDER_WIDTH to 1.5,
            ChartProps.POINT_RADIUS to 3,
            ChartProps.POINT_HOVER_RADIUS to 5,
            ChartProps.POINT_HIT_RADIUS to 10
        )
    }.toTypedArray()

    val options = getClonedChartOptions()
    options.plugins.tooltip.callbacks = json()
    options.plugins.tooltip.callbacks.label = { ctx: dynamic ->
        val label = ctx.dataset.label.toString()
        val yVal = ctx.parsed.y.toString().toDoubleOrNull() ?: 0.0
        "$label: ${yVal.toFixed(2)}%"
    }

    options.scales.y.stacked = true
    options.scales.y.ticks.callback = { v: Double, _: dynamic, _: dynamic ->
        formatPctTick(v, includePlus = false)
    }

    createOrUpdate(HtmlIds.ALLOCATION_DRIFT_CHART, createLineChartConfig(datasets, options))
}

internal fun calculateCumulativePL(trades: Array<dynamic>, includeDryRun: Boolean = false): Array<dynamic> {
    if (trades.asDynamic().length == 0) return emptyArray()

    val sorted = trades.sortedWith { a: dynamic, b: dynamic ->
        val aTime = Date(a.timestamp.toString()).getTime()
        val bTime = Date(b.timestamp.toString()).getTime()
        aTime.compareTo(bTime)
    }

    val filtered = sorted.filter { t: dynamic ->
        val isSuccess = (t.success == true || t.success.toString() == "true")
        val isDryRun = (t.dryRun == true || t.dryRun.toString() == "true")
        isSuccess && (includeDryRun || !isDryRun)
    }

    if (filtered.isEmpty()) return emptyArray()

    val points = mutableListOf<dynamic>()
    var cumulative = 0.0
    for (t in filtered) {
        val amt = t.usdAmount.toString().toDoubleOrNull() ?: 0.0
        val side = t.side.toString().uppercase()
        cumulative += if (side == OrderSide.SELL.name) amt else -amt
        points.add(json("x" to t.timestamp, "y" to cumulative))
    }

    return points.toTypedArray()
}

internal fun buildCumulativePLChart(trades: Array<dynamic>, includeDryRun: Boolean = false) {
    val rawData = calculateCumulativePL(trades, includeDryRun)
    if (rawData.asDynamic().length == 0) return

    val chartData = if (rawData.size == 1) {
        val firstTradeTime = Date(rawData[0].x.toString()).getTime()
        val startTime = Date(firstTradeTime - 3600000.0).toISOString()
        arrayOf(json("x" to startTime, "y" to 0.0), rawData[0])
    } else {
        rawData
    }

    val labelText = if (includeDryRun) ViewText.NET_CASH_FLOW_ALL else ViewText.NET_CASH_FLOW_REALIZED

    val datasets = arrayOf(json(
        ChartProps.LABEL to labelText,
        ChartProps.DATA to chartData,
        ChartProps.BORDER_COLOR to ChartProps.COLOR_GREEN_BORDER,
        ChartProps.BACKGROUND_COLOR to ChartProps.COLOR_GREEN_BG,
        ChartProps.FILL to true,
        ChartProps.TENSION to 0.3,
        ChartProps.BORDER_WIDTH to 2,
        ChartProps.POINT_RADIUS to 4,
        ChartProps.POINT_HOVER_RADIUS to 6,
        ChartProps.POINT_HIT_RADIUS to 10
    ))

    val options = getClonedChartOptions()
    options.scales.y.ticks.callback = { v: Double, _: dynamic, _: dynamic ->
        formatUSD(v)
    }

    createOrUpdate(HtmlIds.CUMULATIVE_PL_CHART, createLineChartConfig(datasets, options))
}

fun formatPair(trade: dynamic): String {
    if (trade?.symbol == null) return ""
    return trade.symbol.toString() + "/USD"
}

internal fun renderTradeTable(trades: Array<dynamic>) {
    val tbody = document.getElementById(HtmlIds.TRADE_TABLE_BODY) ?: return
    tbody.innerHTML = ""

    val showDryRun = (document.getElementById(HtmlIds.SHOW_DRY_RUN_CHECKBOX) as? HTMLInputElement)?.checked ?: true
    val filteredTrades = if (showDryRun) trades else trades.filter { t: dynamic -> !(t.dryRun as? Boolean ?: false) }.toTypedArray()

    if (filteredTrades.asDynamic().length == 0) {
        val tr = document.createElement("tr")
        val td = document.createElement("td") as HTMLTableCellElement
        td.colSpan = 6
        td.setAttribute("style", "text-align:center;color:var(--color-text-muted);padding:2rem;")
        td.textContent = ViewText.NO_TRADES_FOUND_PERIOD
        tr.appendChild(td)
        tbody.appendChild(tr)
        return
    }

    filteredTrades.forEach { t ->
        tbody.appendChild(renderTradeRow(t))
    }
}

private fun renderTradeRow(t: dynamic): HTMLTableRowElement {
    val tr = document.createElement("tr") as HTMLTableRowElement
    tr.className = CssClass.Table.Hoverable.toString()

    val time = Date(t.timestamp.toString()).asDynamic().toLocaleString()
    val side = t.side.toString()
    val sideClass = if (side == OrderSide.BUY.name) CssClass.Badge.Buy else CssClass.Badge.Sell
    val success = t.success as? Boolean ?: false
    val dryRun = t.dryRun as? Boolean ?: false
    val statusText = if (success) (if (dryRun) ViewText.STATUS_DRY_RUN else ViewText.STATUS_SUCCESS) else ViewText.STATUS_FAILED
    val statusClass = if (success) (if (dryRun) CssClass.Badge.Info else CssClass.Badge.Buy) else CssClass.Badge.Sell
    val vol = t.volume.toString().toDoubleOrNull() ?: 0.0
    val amt = t.usdAmount.toString().toDoubleOrNull() ?: 0.0

    tr.appendChild(createCell(time, CssClass.Table.MonoCol))
    tr.appendChild(createCell(formatPair(t), CssClass.Table.SymbolCol))
    tr.appendChild(createBadgeCell(side, sideClass))
    tr.appendChild(createCell(vol.toFixed(8), CssClass.Table.MonoCol))
    tr.appendChild(createCell(formatUSD(amt), CssClass.Table.MonoCol))
    tr.appendChild(createBadgeCell(statusText, statusClass))

    return tr
}

private fun createCell(text: String, cssClass: CssClass): HTMLTableCellElement {
    val td = document.createElement("td") as HTMLTableCellElement
    td.className = cssClass.toString()
    td.textContent = text
    return td
}

private fun createBadgeCell(text: String, badgeClass: CssClass): HTMLTableCellElement {
    val td = document.createElement("td") as HTMLTableCellElement
    val span = document.createElement("span") as HTMLSpanElement
    span.className = badgeClass.toString()
    span.textContent = text
    td.appendChild(span)
    return td
}

internal fun updateStats(stats: dynamic) {
    val athTitle = document.getElementById(HtmlIds.STAT_ATH_TITLE)
    val ath = document.getElementById(HtmlIds.STAT_ATH)
    val totalTrades = document.getElementById(HtmlIds.STAT_TOTAL_TRADES)
    val totalVolume = document.getElementById(HtmlIds.STAT_TOTAL_VOLUME)
    val totalFees = document.getElementById(HtmlIds.STAT_TOTAL_FEES)

    if (athTitle != null) {
        athTitle.textContent = if (currentRange == TimeRange.ALL.key) ViewText.HISTORY_ALL_TIME_HIGH else ViewText.PERIOD_HIGH
    }
    if (ath != null) ath.textContent = formatUSD(stats.allTimeHigh.toString().toDoubleOrNull() ?: 0.0)
    if (totalTrades != null) {
        val count = stats.totalTradesExecuted.toString().toDoubleOrNull() ?: 0.0
        totalTrades.textContent = count.asDynamic().toLocaleString()
    }
    if (totalVolume != null) totalVolume.textContent = formatUSD(stats.totalVolumeTraded.toString().toDoubleOrNull() ?: 0.0)
    if (totalFees != null) totalFees.textContent = formatUSD(stats.totalFeesPaid.toString().toDoubleOrNull() ?: 0.0)
}

private fun fetchRanged(vararg routes: String, range: String): Array<Promise<dynamic>> {
    return routes.map { route -> fetchJSON("$route?range=$range") }.toTypedArray()
}

internal fun loadAll(range: String): Promise<Unit> {
    currentRange = range

    val promises = fetchRanged(
        Routes.API_HISTORY_SNAPSHOTS,
        Routes.API_HISTORY_TRADES,
        Routes.API_HISTORY_STATS,
        range = range
    )

    return Promise.all(promises).then { results ->
        val snapshots = results[0] as Array<dynamic>
        val trades = results[1] as Array<dynamic>
        val stats = results[2]
        allTrades = trades
        buildPortfolioValueChart(snapshots)
        buildAssetHoldingsChart(snapshots)
        buildAllocationDriftChart(snapshots)
        val showDryRun = (document.getElementById(HtmlIds.SHOW_DRY_RUN_CHECKBOX) as? HTMLInputElement)?.checked ?: true
        buildCumulativePLChart(trades, showDryRun)
        renderTradeTable(trades)
        updateStats(stats)
    }
}

internal fun checkSyncProgress(): Promise<Boolean> {
    return fetchJSON(Routes.API_HISTORY_SYNC_PROGRESS).then { status: dynamic ->
        val banner = document.getElementById(HtmlIds.SYNC_PROGRESS_BANNER) as? HTMLElement
        if (banner == null) {
            true
        } else {
            val seeded = status.seeded as? Boolean ?: false
            if (seeded) {
                banner.style.display = "none"
                true
            } else {
                banner.style.display = "block"
                val offset = status.offset.toString().toDoubleOrNull() ?: 0.0
                val total = status.total.toString().toDoubleOrNull() ?: 0.0
                var pct = 0
                if (total > 0.0) {
                    pct = (offset / total * 100.0).toInt().coerceAtMost(100)
                }

                val bar = document.getElementById(HtmlIds.SYNC_PROGRESS_BAR) as? HTMLElement
                val text = document.getElementById(HtmlIds.SYNC_PROGRESS_TEXT) as? HTMLElement

                if (bar != null) bar.style.width = "$pct%"
                if (text != null) text.textContent = "${offset.asDynamic().toLocaleString()} / ${total.asDynamic().toLocaleString()} ($pct%)"

                false
            }
        }
    }.`catch` { e ->
        console.error("Error checking sync progress", e)
        false
    }
}
