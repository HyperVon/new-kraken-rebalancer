package com.gemini.krakenbot.frontend

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import kotlin.collections.mutableMapOf
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.js.json

@JsName("Chart")
private external class Chart(ctx: dynamic, config: dynamic) {
    fun destroy()
    fun isDatasetVisible(index: Int): Boolean
    val data: dynamic
}

@JsName("Object")
private external object JSObject {
    fun keys(obj: dynamic): Array<String>
    fun assign(target: dynamic, vararg sources: dynamic): dynamic
}

private val CHART_COLORS = arrayOf(
    "rgba(96, 165, 250, 1)",   /* blue-400 */
    "rgba(52, 211, 153, 1)",   /* emerald-400 */
    "rgba(251, 191, 36, 1)",   /* amber-400 */
    "rgba(167, 139, 250, 1)",  /* violet-400 */
    "rgba(248, 113, 113, 1)",  /* red-400 */
    "rgba(45, 212, 191, 1)",   /* teal-400 */
    "rgba(251, 146, 60, 1)",   /* orange-400 */
    "rgba(232, 121, 249, 1)"   /* fuchsia-400 */
)

private val CHART_BG = arrayOf(
    "rgba(96, 165, 250, 0.1)",
    "rgba(52, 211, 153, 0.1)",
    "rgba(251, 191, 36, 0.1)",
    "rgba(167, 139, 250, 0.1)",
    "rgba(248, 113, 113, 0.1)",
    "rgba(45, 212, 191, 0.1)",
    "rgba(251, 146, 60, 0.1)",
    "rgba(232, 121, 249, 0.1)"
)

private val chartDefaults: dynamic = js("""
    ({
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
            legend: { labels: { color: '#94a3b8', font: { family: "'Inter', sans-serif", size: 12 } } },
            tooltip: {
                backgroundColor: 'rgba(15, 23, 42, 0.9)',
                borderColor: 'rgba(255,255,255,0.1)',
                borderWidth: 1,
                titleColor: '#f8fafc',
                bodyColor: '#cbd5e1',
                bodyFont: { family: "'Roboto Mono', monospace" },
                padding: 12,
                cornerRadius: 8
            }
        },
        scales: {
            x: {
                type: 'time',
                time: { tooltipFormat: 'MMM d, yyyy HH:mm' },
                grid: { color: 'rgba(51, 65, 85, 0.3)' },
                ticks: { color: '#64748b', maxTicksLimit: 8 }
            },
            y: {
                grid: { color: 'rgba(51, 65, 85, 0.3)' },
                ticks: { color: '#64748b' }
            }
        }
    })
""")

private val charts = mutableMapOf<String, dynamic>()
private var currentRange = "30d"
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
            loadAll("30d")
        } else {
            syncIntervalId?.let { window.clearInterval(it) }
            syncIntervalId = window.setInterval({
                if (document.getElementById("sync-progress-banner") == null) {
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

    val buttons = document.querySelectorAll(".time-range-btn")
    for (i in 0 until buttons.length) {
        val btn = buttons.item(i) as? HTMLElement
        btn?.addEventListener("click", {
            val list = document.querySelectorAll(".time-range-btn")
            for (j in 0 until list.length) {
                (list.item(j) as? HTMLElement)?.classList?.remove("active")
            }
            btn.classList.add("active")
            val range = btn.getAttribute("data-range") ?: "30d"
            loadAll(range)
        })
    }

    val checkbox = document.getElementById("show-dry-run-checkbox") as? HTMLInputElement
    checkbox?.addEventListener("change", {
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
        symbolsSet.filter { it != "USD" }.sorted()
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
        "24h" -> chartDefaults.scales.x.time.unit = "hour"
        "all" -> js("delete chartDefaults.scales.x.time.unit")
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
        } catch (e: Throwable) {}
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
        "label" to "Total Portfolio",
        "data" to totalPortfolioData,
        "borderColor" to "rgba(96, 165, 250, 1)",
        "backgroundColor" to "rgba(96, 165, 250, 0.08)",
        "fill" to true,
        "tension" to 0.3,
        "borderWidth" to 2,
        "pointRadius" to 4,
        "pointHoverRadius" to 6,
        "pointHitRadius" to 10
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
            "label" to sym,
            "data" to symbolData,
            "borderColor" to color,
            "backgroundColor" to "transparent",
            "tension" to 0.3,
            "borderWidth" to 1.5,
            "pointRadius" to 3,
            "pointHoverRadius" to 5,
            "pointHitRadius" to 10
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

    createOrUpdate("portfolio-value-chart", createLineChartConfig(datasets.toTypedArray(), options))
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
            "label" to sym,
            "data" to symbolData,
            "borderColor" to color,
            "backgroundColor" to "transparent",
            "tension" to 0.3,
            "borderWidth" to 2,
            "pointRadius" to 3,
            "pointHoverRadius" to 5,
            "pointHitRadius" to 10
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

    createOrUpdate("asset-holdings-chart", createLineChartConfig(datasets, options))
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
            "label" to sym,
            "data" to symbolData,
            "borderColor" to color,
            "backgroundColor" to bg,
            "fill" to true,
            "tension" to 0.3,
            "borderWidth" to 1.5,
            "pointRadius" to 3,
            "pointHoverRadius" to 5,
            "pointHitRadius" to 10
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

    createOrUpdate("allocation-drift-chart", createLineChartConfig(datasets, options))
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
        cumulative += if (side == "SELL") amt else -amt
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

    val labelText = if (includeDryRun) "Net Cash Flow (Realized & Dry Run Trades)" else "Net Cash Flow (Realized Trades)"

    val datasets = arrayOf(json(
        "label" to labelText,
        "data" to chartData,
        "borderColor" to "rgba(52, 211, 153, 1)",
        "backgroundColor" to "rgba(52, 211, 153, 0.08)",
        "fill" to true,
        "tension" to 0.3,
        "borderWidth" to 2,
        "pointRadius" to 4,
        "pointHoverRadius" to 6,
        "pointHitRadius" to 10
    ))

    val options = getClonedChartOptions()
    options.scales.y.ticks.callback = { v: Double, _: dynamic, _: dynamic ->
        formatUSD(v)
    }

    createOrUpdate("cumulative-pl-chart", createLineChartConfig(datasets, options))
}

fun formatPair(trade: dynamic): String {
    if (trade?.symbol == null) return ""
    return trade.symbol.toString() + "/USD"
}

internal fun renderTradeTable(trades: Array<dynamic>) {
    val tbody = document.getElementById("trade-table-body") ?: return

    val showDryRun = (document.getElementById("show-dry-run-checkbox") as? HTMLInputElement)?.checked ?: true
    val filteredTrades = if (showDryRun) trades else trades.filter { t: dynamic -> !(t.dryRun as? Boolean ?: false) }.toTypedArray()

    if (filteredTrades.asDynamic().length == 0) {
        tbody.innerHTML = "<tr><td colspan=\"6\" style=\"text-align:center;color:var(--color-text-muted);padding:2rem;\">No trades found for this period.</td></tr>"
        return
    }

    val rowsHtml = filteredTrades.joinToString("") { t: dynamic ->
        val time = Date(t.timestamp.toString()).asDynamic().toLocaleString()
        val side = t.side.toString()
        val sideClass = if (side == "BUY") "badge badge-buy" else "badge badge-sell"
        val success = t.success as? Boolean ?: false
        val dryRun = t.dryRun as? Boolean ?: false
        val statusText = if (success) (if (dryRun) "DRY RUN" else "SUCCESS") else "FAILED"
        val statusClass = if (success) (if (dryRun) "badge badge-info" else "badge badge-buy") else "badge badge-sell"
        val vol = t.volume.toString().toDoubleOrNull() ?: 0.0
        val amt = t.usdAmount.toString().toDoubleOrNull() ?: 0.0

        """
        <tr class="hoverable">
            <td class="mono-col">$time</td>
            <td class="symbol-col">${formatPair(t)}</td>
            <td><span class="$sideClass">$side</span></td>
            <td class="mono-col">${vol.toFixed(8)}</td>
            <td class="mono-col">${formatUSD(amt)}</td>
            <td><span class="$statusClass">$statusText</span></td>
        </tr>
        """.trimIndent()
    }

    tbody.innerHTML = rowsHtml
}

internal fun updateStats(stats: dynamic) {
    val athTitle = document.getElementById("stat-ath-title")
    val ath = document.getElementById("stat-ath")
    val totalTrades = document.getElementById("stat-total-trades")
    val totalVolume = document.getElementById("stat-total-volume")
    val totalFees = document.getElementById("stat-total-fees")

    if (athTitle != null) {
        athTitle.textContent = if (currentRange == "all") "All-Time High" else "Period High"
    }
    if (ath != null) ath.textContent = formatUSD(stats.allTimeHigh.toString().toDoubleOrNull() ?: 0.0)
    if (totalTrades != null) {
        val count = stats.totalTradesExecuted.toString().toDoubleOrNull() ?: 0.0
        totalTrades.textContent = count.asDynamic().toLocaleString()
    }
    if (totalVolume != null) totalVolume.textContent = formatUSD(stats.totalVolumeTraded.toString().toDoubleOrNull() ?: 0.0)
    if (totalFees != null) totalFees.textContent = formatUSD(stats.totalFeesPaid.toString().toDoubleOrNull() ?: 0.0)
}

internal fun loadAll(range: String): Promise<Unit> {
    currentRange = range

    val p1 = fetchJSON("/api/history/snapshots?range=$currentRange")
    val p2 = fetchJSON("/api/history/trades?range=$currentRange")
    val p3 = fetchJSON("/api/history/stats?range=$currentRange")

    return Promise.all(arrayOf(p1, p2, p3)).then { results ->
        val snapshots = results[0] as Array<dynamic>
        val trades = results[1] as Array<dynamic>
        val stats = results[2]
        allTrades = trades
        buildPortfolioValueChart(snapshots)
        buildAssetHoldingsChart(snapshots)
        buildAllocationDriftChart(snapshots)
        val showDryRun = (document.getElementById("show-dry-run-checkbox") as? HTMLInputElement)?.checked ?: true
        buildCumulativePLChart(trades, showDryRun)
        renderTradeTable(trades)
        updateStats(stats)
    }
}


internal fun checkSyncProgress(): Promise<Boolean> {
    return fetchJSON("/api/history/sync-progress").then { status: dynamic ->
        val banner = document.getElementById("sync-progress-banner") as? HTMLElement
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

                val bar = document.getElementById("sync-progress-bar") as? HTMLElement
                val text = document.getElementById("sync-progress-text") as? HTMLElement

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
