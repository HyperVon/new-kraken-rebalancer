package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import kotlin.js.Date
import kotlin.js.json

internal data class HistoryChartPoint(val time: Double, val value: Double?)

/** Read only direct server-rendered evidence; incomplete samples remain explicit null breaks. */
internal fun historyChartPoints(container: Element): List<HistoryChartPoint> {
    val markers = container.querySelectorAll("circle[data-time]")
    return (0 until markers.length).mapNotNull { index ->
        val marker = markers.item(index) as? Element ?: return@mapNotNull null
        val time = marker.getAttribute("data-time")?.toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: return@mapNotNull null
        val value = marker.getAttribute("data-value")?.toDoubleOrNull()?.takeIf { it.isFinite() }
        if (marker.getAttribute("data-gap") == "true") {
            HistoryChartPoint(time, null)
        } else {
            value?.let { HistoryChartPoint(time, it) }
        }
    }.sortedBy { it.time }
}

private fun historyDateLabel(time: Double): String = Date(time).toISOString().replace('T', ' ').take(23) + " UTC"

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
                    "borderColor" to "#8b9dff",
                    "backgroundColor" to "#8b9dff",
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
                    "grid" to json("color" to "rgba(148,163,184,0.08)"),
                    "ticks" to json(
                        "color" to "#94a3b8",
                        "maxTicksLimit" to 5,
                        "maxRotation" to 0,
                        "callback" to { value: dynamic ->
                            val date = Date((value as Number).toDouble()).toISOString()
                            if (shortWindow) date.substring(11, 19) else date.substring(5, 16).replace('T', ' ')
                        },
                    ),
                ),
                "y" to json(
                    "grid" to json("color" to "rgba(148,163,184,0.08)"),
                    "ticks" to json(
                        "color" to "#94a3b8",
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

/** Enhance only after Chart.js succeeds, so blocked CDNs leave a complete native SVG fallback. */
internal fun initHistoryCharts(factory: (HTMLCanvasElement, dynamic) -> dynamic = ::createHistoryChart) {
    listOf(HtmlIds.HISTORY_VALUE_CHART, HtmlIds.HISTORY_ASSET_CHART).forEach { id ->
        val container = document.getElementById(id) ?: return@forEach
        val svg = container.querySelector("svg") ?: return@forEach
        if (container.querySelector("canvas") != null) return@forEach
        val points = historyChartPoints(container)
        if (points.none { it.value != null }) return@forEach
        val money = id == HtmlIds.HISTORY_VALUE_CHART
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        container.appendChild(canvas)
        val chart = try {
            factory(canvas, historyChartConfig(points, money))
        } catch (_: Throwable) {
            null
        }
        if (chart == null || chart == undefined) {
            canvas.remove()
            return@forEach
        }
        svg.setAttribute("hidden", "")
        svg.setAttribute("style", "display:none")
        setupHistoryChartControls(container, chart, points, money)
    }
}

private fun setupHistoryChartControls(
    container: Element,
    chart: dynamic,
    points: List<HistoryChartPoint>,
    money: Boolean,
) {
    val panel = container.closest(".glass-panel") ?: return
    val controls = panel.querySelector("[data-history-controls]") as? HTMLElement ?: return
    controls.hidden = false
    val fullMin = points.first().time
    val fullMax = points.last().time
    var span = fullMax - fullMin
    var end = fullMax
    panel.querySelector("[data-history-view='line']")?.classList?.add("active")
    val buttons = controls.querySelectorAll("button")
    repeat(buttons.length) { index ->
        val button = buttons.item(index) as? HTMLElement ?: return@repeat
        button.addEventListener("click", {
            button.getAttribute("data-history-view")?.let { view ->
                chart.data.datasets[0].showLine = money && view == "line"
                chart.data.datasets[0].pointRadius =
                    if (money && view == "line" && points.count { it.value != null } > 60) 1 else 3
                val views = controls.querySelectorAll("[data-history-view]")
                repeat(views.length) { viewIndex ->
                    val viewButton = views.item(viewIndex) as Element
                    viewButton.classList.toggle("active", viewButton == button)
                }
            }
            button.getAttribute("data-history-zoom")?.let { action ->
                span = when (action) {
                    "in" -> (span / 2).coerceAtLeast(minOf(1.0, fullMax - fullMin))
                    "out" -> (span * 2).coerceAtMost(fullMax - fullMin)
                    else -> fullMax - fullMin
                }
                end = if (action == "reset") fullMax else end.coerceIn(fullMin + span, fullMax)
                chart.options.scales.x.min = end - span
                chart.options.scales.x.max = end
            }
            button.getAttribute("data-history-pan")?.let { direction ->
                val step = if (direction == "earlier") -span / 2 else span / 2
                end = (end + step).coerceIn(fullMin + span, fullMax)
                chart.options.scales.x.min = end - span
                chart.options.scales.x.max = end
            }
            if (button.getAttribute("data-history-expand") == "true") {
                val expanded = panel.classList.toggle("history-chart-expanded")
                button.textContent = if (expanded) ViewText.HISTORY_CHART_COLLAPSE else ViewText.HISTORY_CHART_EXPAND
                chart.resize()
            }
            chart.update("none")
        })
    }
}
