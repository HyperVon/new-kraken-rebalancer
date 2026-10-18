package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HistoryChartActions
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlEvents
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.borderColorForSymbol
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Date
import kotlin.js.json

internal data class HistoryChartPoint(val time: Double, val value: Double?)

/** Read only direct server-rendered evidence; incomplete samples remain explicit null breaks. */
internal fun historyChartPoints(container: Element): List<HistoryChartPoint> {
    val markers = container.querySelectorAll("circle[${HtmlAttrs.DATA_TIME}]")
    return (0 until markers.length).mapNotNull { index ->
        val marker = markers.item(index) as? Element ?: return@mapNotNull null
        val time = marker.getAttribute(HtmlAttrs.DATA_TIME)?.toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: return@mapNotNull null
        val value = marker.getAttribute(HtmlAttrs.DATA_VALUE)?.toDoubleOrNull()?.takeIf { it.isFinite() }
        if (marker.getAttribute(HtmlAttrs.DATA_GAP) == "true") {
            HistoryChartPoint(time, null)
        } else {
            value?.let { HistoryChartPoint(time, it) }
        }
    }.sortedBy { it.time }.distinctBy { it.time }
}

/** Per-series samples (single-series charts group under their series attribute). */
internal fun historySeriesPoints(container: Element): List<Pair<String, List<HistoryChartPoint>>> {
    val markers = container.querySelectorAll("circle[${HtmlAttrs.DATA_TIME}]")
    val grouped = linkedMapOf<String, MutableList<HistoryChartPoint>>()
    repeat(markers.length) { index ->
        val marker = markers.item(index) as? Element ?: return@repeat
        val time = marker.getAttribute(HtmlAttrs.DATA_TIME)?.toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: return@repeat
        val value = marker.getAttribute(HtmlAttrs.DATA_VALUE)?.toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: return@repeat
        val series = marker.getAttribute(HtmlAttrs.DATA_SERIES) ?: ""
        grouped.getOrPut(series) { mutableListOf() }.add(HistoryChartPoint(time, value))
    }
    return grouped.map { (series, points) ->
        series to points.sortedBy { it.time }.distinctBy { it.time }
    }
}

private fun historyPad(value: Int, size: Int): String = value.toString().padStart(size, '0')

/** Wall-clock label in the browser's local time. */
internal fun historyDateLabel(time: Double): String {
    val date = Date(time)
    return "${date.getFullYear()}-${historyPad(date.getMonth() + 1, 2)}-${historyPad(date.getDate(), 2)} " +
        "${historyPad(date.getHours(), 2)}:${historyPad(date.getMinutes(), 2)}:" +
        "${historyPad(date.getSeconds(), 2)}.${historyPad(date.getMilliseconds(), 3)}"
}

/** Axis tick label in the browser's local time: clock time for short windows, date plus time otherwise. */
internal fun historyAxisTimeLabel(time: Double, shortWindow: Boolean): String {
    val date = Date(time)
    return if (shortWindow) {
        "${historyPad(date.getHours(), 2)}:${historyPad(date.getMinutes(), 2)}:${historyPad(date.getSeconds(), 2)}"
    } else {
        "${historyPad(date.getMonth() + 1, 2)}-${historyPad(date.getDate(), 2)} " +
            "${historyPad(date.getHours(), 2)}:${historyPad(date.getMinutes(), 2)}"
    }
}

private fun historyNumber(value: Double, money: Boolean): String = if (money) {
    "$" + value.asDynamic().toLocaleString("en-US", json("maximumFractionDigits" to 2)).toString()
} else {
    value.asDynamic().toLocaleString("en-US", json("maximumFractionDigits" to 8)).toString()
}

internal fun historyChartConfig(points: List<HistoryChartPoint>, money: Boolean): dynamic {
    val shortWindow = points.isNotEmpty() && points.last().time - points.first().time < 3_600_000
    val data = points.map { json("x" to it.time, "y" to it.value) }.toTypedArray()
    return json(
        "type" to "line",
        "data" to json(
            "datasets" to arrayOf(
                json(
                    "data" to data,
                    "borderColor" to ChartProps.COLOR_BLUE,
                    "backgroundColor" to ChartProps.COLOR_BLUE,
                    "borderWidth" to 2,
                    "pointRadius" to if (money && points.count { it.value != null } > 60) 1 else 3,
                    "pointHitRadius" to 10,
                    "pointHoverRadius" to 6,
                    "showLine" to money,
                    "spanGaps" to false,
                ),
            ),
        ),
        "options" to json(
            "responsive" to true,
            "maintainAspectRatio" to false,
            "animation" to false,
            "interaction" to json("mode" to "nearest", "axis" to "x", "intersect" to false),
            "parsing" to false,
            "plugins" to json(
                "legend" to json("display" to false),
                "tooltip" to json(
                    "callbacks" to json(
                        "title" to { items: dynamic -> historyDateLabel((items[0].parsed.x as Number).toDouble()) },
                        "label" to { item: dynamic -> historyNumber((item.parsed.y as Number).toDouble(), money) },
                    ),
                ),
            ),
            "scales" to json(
                "x" to json(
                    "type" to "linear",
                    "grid" to json("color" to ChartProps.COLOR_GRID_LINE),
                    "ticks" to json(
                        "color" to ChartProps.COLOR_TICK,
                        "maxTicksLimit" to 5,
                        "maxRotation" to 0,
                        "callback" to { value: dynamic ->
                            historyAxisTimeLabel((value as Number).toDouble(), shortWindow)
                        },
                    ),
                ),
                "y" to json(
                    "grid" to json("color" to ChartProps.COLOR_GRID_LINE),
                    "ticks" to json(
                        "color" to ChartProps.COLOR_TICK,
                        "maxTicksLimit" to 6,
                        "callback" to { value: dynamic -> historyNumber((value as Number).toDouble(), money) },
                    ),
                ),
            ),
        ),
    )
}

private fun createHistoryChart(canvas: HTMLCanvasElement, config: dynamic): dynamic {
    val chartConstructor = window.asDynamic().Chart
    if (chartConstructor == null || chartConstructor == undefined) return null
    return js("Reflect.construct(chartConstructor, [canvas, config])")
}

/** Multi-series companion to [historyChartConfig]: one dataset per series with its palette color. */
internal fun historyMultiSeriesConfig(series: List<Pair<String, List<HistoryChartPoint>>>, percent: Boolean): dynamic {
    val allTimes = series.flatMap { it.second }.map { it.time }
    val shortWindow = allTimes.isNotEmpty() && (allTimes.maxOrNull()!! - allTimes.minOrNull()!!) < 3_600_000
    val dense = series.maxOfOrNull { it.second.size } ?: 0
    val datasets = series.mapIndexed { index, (name, points) ->
        val color = borderColorForSymbol(name, index)
        json(
            "label" to name,
            "data" to points.map { json("x" to it.time, "y" to it.value) }.toTypedArray(),
            "borderColor" to color,
            "backgroundColor" to color,
            "borderWidth" to 2,
            "pointRadius" to if (dense > 60) 1 else 3,
            "pointHitRadius" to 10,
            "pointHoverRadius" to 6,
            "showLine" to true,
            "spanGaps" to false,
        )
    }.toTypedArray()
    return json(
        "type" to "line",
        "data" to json("datasets" to datasets),
        "options" to json(
            "responsive" to true,
            "maintainAspectRatio" to false,
            "animation" to false,
            "interaction" to json("mode" to "nearest", "axis" to "x", "intersect" to false),
            "parsing" to false,
            "plugins" to json(
                "legend" to json(
                    "display" to true,
                    "labels" to json(
                        "color" to ChartProps.COLOR_TICK,
                        "usePointStyle" to true,
                        "boxHeight" to 6,
                    ),
                ),
                "tooltip" to json(
                    "callbacks" to json(
                        "title" to { items: dynamic -> historyDateLabel((items[0].parsed.x as Number).toDouble()) },
                        "label" to { item: dynamic ->
                            val value = (item.parsed.y as Number).toDouble()
                            val label = item.dataset.label
                            val prefix = if (label != null && label != undefined) "$label · " else ""
                            prefix + if (percent) {
                                historyNumber(value, false) + " pp"
                            } else {
                                historyNumber(value, true)
                            }
                        },
                    ),
                ),
            ),
            "scales" to json(
                "x" to json(
                    "type" to "linear",
                    "grid" to json("color" to ChartProps.COLOR_GRID_LINE),
                    "ticks" to json(
                        "color" to ChartProps.COLOR_TICK,
                        "maxTicksLimit" to 5,
                        "maxRotation" to 0,
                        "callback" to { value: dynamic ->
                            historyAxisTimeLabel((value as Number).toDouble(), shortWindow)
                        },
                    ),
                ),
                "y" to json(
                    "grid" to json(
                        "color" to { ctx: dynamic ->
                            val tick = ctx.tick
                            if (percent && tick != null && tick != undefined &&
                                (tick.value as Number).toDouble() == 0.0
                            ) {
                                ChartProps.COLOR_TICK
                            } else {
                                ChartProps.COLOR_GRID_LINE
                            }
                        },
                    ),
                    "ticks" to json(
                        "color" to ChartProps.COLOR_TICK,
                        "maxTicksLimit" to 6,
                        "callback" to { value: dynamic ->
                            val number = (value as Number).toDouble()
                            if (percent) historyNumber(number, false) + " pp" else historyNumber(number, true)
                        },
                    ),
                ),
            ),
        ),
    )
}

/** Enhance only after Chart.js succeeds, so blocked CDNs leave a complete native SVG fallback. */
internal fun initHistoryCharts(factory: (HTMLCanvasElement, dynamic) -> dynamic = ::createHistoryChart) {
    val singles = mapOf(
        HtmlIds.HISTORY_VALUE_CHART to true,
        HtmlIds.HISTORY_ASSET_CHART to false,
        HtmlIds.HISTORY_CASH_FLOW_CHART to true,
        HtmlIds.HISTORY_REWARDS_CHART to true,
    )
    val multiples = mapOf(
        HtmlIds.HISTORY_HOLDINGS_CHART to false,
        HtmlIds.HISTORY_ALLOCATION_CHART to true,
    )
    (singles + multiples).forEach { (id, _) ->
        val container = document.getElementById(id) ?: return@forEach
        val svg = container.querySelector("svg") ?: return@forEach
        if (container.querySelector("canvas") != null) return@forEach
        val single = singles[id]
        val points = historyChartPoints(container)
        val series = historySeriesPoints(container)
        val canvas = canvasFor(container)
        val chart = if (single != null) {
            if (points.none { it.value != null }) {
                canvas.remove()
                return@forEach
            }
            try {
                factory(canvas, historyChartConfig(points, single))
            } catch (_: Throwable) {
                null
            }
        } else {
            if (series.isEmpty() || series.all { it.second.isEmpty() }) {
                canvas.remove()
                return@forEach
            }
            try {
                factory(canvas, historyMultiSeriesConfig(series, multiples[id] == true))
            } catch (_: Throwable) {
                null
            }
        }
        if (chart == null || chart == undefined) {
            canvas.remove()
            return@forEach
        }
        svg.setAttribute("hidden", "")
        svg.setAttribute("style", "display:none")
        container.asDynamic().historyChart = chart
        setupHistoryChartControls(container, chart, points, single ?: true)
    }
    initHistoryScrubbers()
}

private fun canvasFor(container: Element): HTMLCanvasElement {
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    container.appendChild(canvas)
    return canvas
}

/** A time cursor over the server-rendered samples: the range input focuses each recorded point. */
internal fun initHistoryScrubbers() {
    listOf(
        HtmlIds.HISTORY_VALUE_CHART,
        HtmlIds.HISTORY_ASSET_CHART,
        HtmlIds.HISTORY_HOLDINGS_CHART,
        HtmlIds.HISTORY_ALLOCATION_CHART,
        HtmlIds.HISTORY_CASH_FLOW_CHART,
        HtmlIds.HISTORY_REWARDS_CHART,
    ).forEach { chartId ->
        val scrubber = queryChartScrubber(chartId) ?: return@forEach
        if (scrubber.asDynamic().historyScrubberInitialized == true) return@forEach
        val container = document.getElementById(chartId) ?: return@forEach
        val points = historyChartPoints(container)
        val times = points.map { it.time }.distinct()
        if (times.size < 2) return@forEach
        scrubber.asDynamic().historyScrubberInitialized = true
        scrubber.disabled = false
        scrubber.min = "0"
        scrubber.max = (times.size - 1).toString()
        scrubber.step = "1"
        scrubber.value = "0"
        scrubber.addEventListener(HtmlEvents.INPUT, {
            focusHistorySample(container, times[scrubber.value.toIntOrNull() ?: 0])
        })
    }
}

private fun focusHistorySample(container: Element, time: Double) {
    val circles = container.querySelectorAll("circle[${HtmlAttrs.DATA_TIME}]")
    repeat(circles.length) { index ->
        val circle = circles.item(index) as? Element ?: return@repeat
        val focused = circle.getAttribute(HtmlAttrs.DATA_TIME)?.toDoubleOrNull() == time
        circle.classList.toggle(CssClass.Utility.Active, focused)
    }
    val chart = container.asDynamic().historyChart
    if (chart == null || chart == undefined) return
    if (jsTypeOf(chart.setActiveElements) != "function") return
    val datasets = chart.data.datasets
    val active = mutableListOf<dynamic>()
    repeat(datasets.length) { datasetIndex ->
        val data = datasets[datasetIndex].data
        var index = -1
        repeat(data.length) { i ->
            val point = data[i]
            if (index < 0 && point.y != null && (point.x as Number).toDouble() == time) index = i
        }
        if (index >= 0) active.add(json("datasetIndex" to datasetIndex, "index" to index))
    }
    val activeArray = active.toTypedArray()
    chart.setActiveElements(activeArray)
    val tooltip = chart.tooltip
    if (tooltip != null && tooltip != undefined && jsTypeOf(tooltip.setActiveElements) == "function") {
        tooltip.setActiveElements(activeArray, json("x" to 0, "y" to 0))
    }
    chart.update("none")
}

private fun setupHistoryChartControls(
    container: Element,
    chart: dynamic,
    points: List<HistoryChartPoint>,
    money: Boolean,
) {
    val panel = container.closest(".glass-panel") ?: return
    val controls = panel.querySelector("[${HtmlAttrs.DATA_HISTORY_CONTROLS}]") as? HTMLElement ?: return
    controls.hidden = false
    if (points.isEmpty()) return
    val fullMin = points.first().time
    val fullMax = points.last().time
    var span = fullMax - fullMin
    var end = fullMax
    panel.querySelector("[${HtmlAttrs.DATA_HISTORY_VIEW}='${HistoryChartActions.VIEW_LINE}']")
        ?.classList?.add(CssClass.Utility.Active.value)
    val buttons = controls.querySelectorAll("button")
    repeat(buttons.length) { index ->
        val button = buttons.item(index) as? HTMLElement ?: return@repeat
        button.addEventListener("click", {
            button.getAttribute(HtmlAttrs.DATA_HISTORY_VIEW)?.let { view ->
                val datasets = chart.data.datasets
                repeat(datasets.length) { datasetIndex ->
                    val dataset = datasets[datasetIndex]
                    dataset.showLine = money && view == HistoryChartActions.VIEW_LINE
                    dataset.pointRadius =
                        if (money && view == HistoryChartActions.VIEW_LINE && points.count { it.value != null } > 60) {
                            1
                        } else {
                            3
                        }
                }
                val views = controls.querySelectorAll("[${HtmlAttrs.DATA_HISTORY_VIEW}]")
                repeat(views.length) { viewIndex ->
                    val viewButton = views.item(viewIndex) as Element
                    viewButton.classList.toggle(CssClass.Utility.Active, viewButton == button)
                }
            }
            button.getAttribute(HtmlAttrs.DATA_HISTORY_ZOOM)?.let { action ->
                span = when (action) {
                    HistoryChartActions.ZOOM_IN -> (span / 2).coerceAtLeast(minOf(1.0, fullMax - fullMin))
                    HistoryChartActions.ZOOM_OUT -> (span * 2).coerceAtMost(fullMax - fullMin)
                    else -> fullMax - fullMin
                }
                end = if (action == HistoryChartActions.ZOOM_RESET) fullMax else end.coerceIn(fullMin + span, fullMax)
                chart.options.scales.x.min = end - span
                chart.options.scales.x.max = end
            }
            button.getAttribute(HtmlAttrs.DATA_HISTORY_PAN)?.let { direction ->
                val step = if (direction == HistoryChartActions.PAN_EARLIER) -span / 2 else span / 2
                end = (end + step).coerceIn(fullMin + span, fullMax)
                chart.options.scales.x.min = end - span
                chart.options.scales.x.max = end
            }
            if (button.getAttribute(HtmlAttrs.DATA_HISTORY_EXPAND) == HistoryChartActions.EXPAND) {
                val expanded = panel.classList.toggle(CssClass.History.ChartExpanded)
                button.textContent = if (expanded) ViewText.HISTORY_CHART_COLLAPSE else ViewText.HISTORY_CHART_EXPAND
                chart.resize()
            }
            chart.update("none")
        })
    }
}
