package com.gemini.krakenbot.frontend

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import kotlin.js.json

class HistoryChartRefreshTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "server-rendered full range pins the initial chart axis" {
            val wrapper = document.createElement("div") as HTMLElement
            wrapper.innerHTML =
                """<svg data-full-from="2026-09-20T00:00:00Z" data-full-to="2026-10-20T00:00:00Z"></svg>"""
            document.body!!.appendChild(wrapper)
            try {
                val from = js("Date.parse('2026-09-20T00:00:00Z')") as Double
                val to = js("Date.parse('2026-10-20T00:00:00Z')") as Double

                historyDomRange(wrapper) shouldBe ChartRange(from, to - 1.0)
            } finally {
                wrapper.remove()
            }
        }

        "initial chart creation pins the server-rendered full range" {
            val root = chartRefreshRoot("history-value-chart")
            root.className = "chart-container"
            root.innerHTML =
                """<svg data-full-from="1970-01-01T00:00:01Z" data-full-to="1970-01-01T00:00:03Z">""" +
                """<circle data-time="1000" data-value="10.00"></circle></svg>"""
            var created: dynamic = null
            try {
                initHistoryCharts { _, config ->
                    json(
                        "data" to config.data,
                        "options" to config.options,
                        "update" to { _: dynamic -> Unit },
                    ).also { created = it }
                }

                (created.options.scales.x.min as Double) shouldBe 1000.0
                (created.options.scales.x.max as Double) shouldBe 2999.0
            } finally {
                root.remove()
                historyChartSessions.remove("history-value-chart")
            }
        }

        "key reconciliation updates renamed series removes absent series and adds newly recorded series" {
            val root = chartRefreshRoot("history-holdings-chart")
            val session = chartRefreshSession(root, "history-holdings-chart")
            try {
                applyHistoryChartSeries(
                    root.id,
                    chartRefreshResponse(
                        chartRefreshSeries("BTC", "BTC", 1.0),
                        chartRefreshSeries("ETH", "ETH", 2.0),
                    ),
                )
                session.chart.data.datasets[0].hidden = true
                session.chart.data.datasets[0].showLine = false
                val pinned = ChartViewport(session.viewport.full, ChartRange(1200.0, 1600.0))
                session.viewport = pinned

                applyHistoryChartSeries(
                    root.id,
                    chartRefreshResponse(
                        chartRefreshSeries("BTC", "Bitcoin", 3.0),
                        chartRefreshSeries("USD", "USD", 4.0),
                        to = "1970-01-01T00:00:04Z",
                    ),
                )

                session.chart.data.datasets.length as Int shouldBe 2
                session.chart.data.datasets[0].historySeriesKey as String shouldBe "BTC"
                session.chart.data.datasets[0].label as String shouldBe "Bitcoin"
                session.chart.data.datasets[0].hidden as Boolean shouldBe true
                session.chart.data.datasets[0].showLine as Boolean shouldBe false
                session.chart.data.datasets[0].data[0].y as Double shouldBe 3.0
                session.chart.data.datasets[1].historySeriesKey as String shouldBe "USD"
                session.viewport shouldBe pinned
                root.querySelector("circle[data-series='ETH']") shouldBe null
                historyChartSessions[root.id] shouldBe session

                applyHistoryChartSeries(root.id, chartRefreshResponse())
                session.chart.data.datasets.length as Int shouldBe 0
                root.querySelectorAll("circle").length shouldBe 0
            } finally {
                root.remove()
                disposeDetachedHistoryCharts()
                historyLegendHidden.remove(root.id)
            }
        }

        "native and checkpoint updates filter raw asset identity and preserve selection with new choices" {
            listOf("history-rewards-chart", "history-cash-flow-chart", "history-asset-chart").forEach { id ->
                val wrapper = document.createElement("div") as HTMLElement
                wrapper.innerHTML = """
                    <select data-history-series-select="$id"><option value="XXBT">BTC</option></select>
                    <div id="$id"></div>
                """.trimIndent()
                document.body!!.appendChild(wrapper)
                val root = document.getElementById(id) as HTMLElement
                val session = chartRefreshSession(root, id)
                val selector = wrapper.querySelector("select") as HTMLSelectElement
                try {
                    initHistorySeriesSelectors()
                    val response = chartRefreshResponse(
                        chartRefreshSeries("XXBT", "BTC", 0.5),
                        chartRefreshSeries("XBT.F", "BTC", 9.0),
                        chartRefreshSeries("ZUSD", "USD", 100.0),
                    )
                    applyHistoryChartSeries(id, response)
                    selector.value shouldBe "XXBT"
                    selector.options.length shouldBe 3
                    session.chart.data.datasets.length as Int shouldBe 1
                    session.chart.data.datasets[0].data[0].y as Double shouldBe 0.5
                    session.chart.options.scales.y.ticks.callback(1.0) as String shouldBe "1 BTC"

                    selector.value = "ZUSD"
                    selector.dispatchEvent(Event("change"))
                    session.chart.data.datasets.length as Int shouldBe 1
                    session.chart.data.datasets[0].historySeriesKey as String shouldBe "ZUSD"
                    session.chart.data.datasets[0].data[0].y as Double shouldBe 100.0
                    session.chart.options.scales.y.ticks.callback(1.0) as String shouldBe "1 USD"
                    historyChartSessions[id] shouldBe session
                    if (id == "history-asset-chart") {
                        session.chart.data.datasets[0].showLine as Boolean shouldBe false
                        root.querySelector("polyline") shouldBe null
                    }
                    applyHistoryChartSeries(id, chartRefreshResponse(chartRefreshSeries("XXBT", "BTC", 0.75)))
                    selector.value shouldBe "XXBT"
                    selector.options.length shouldBe 1
                    session.chart.data.datasets.length as Int shouldBe 1
                    session.chart.data.datasets[0].historySeriesKey as String shouldBe "XXBT"
                } finally {
                    wrapper.remove()
                    disposeDetachedHistoryCharts()
                }
            }
        }

        "a refreshed chart keeps measuring its cumulative series from the reported baseline" {
            val wrapper: HTMLElement = document.createElement("div") as HTMLElement
            val ids = listOf("history-cash-flow-chart")
            wrapper.innerHTML = """
                <a data-history-range="30d"></a><a data-history-range="7d" href="/history?range=7d"></a>
                <div id="history-range-custom-panel"></div><input id="history-range-custom-start">
                <input id="history-range-custom-end"><span id="history-range-custom-tz"></span>
                <p id="history-range-custom-error"></p><button id="history-range-custom-apply"></button>
                <select id="history-scope-select"></select><div id="history-refresh-status"></div>
            """.trimIndent() + ids.joinToString("") {
                "<div id='$it'></div>"
            }
            document.body!!.appendChild(wrapper)
            val sessions = ids.associateWith { chartRefreshSession(document.getElementById(it) as HTMLElement, it) }
            val oldSetInterval = window.asDynamic().setInterval
            val oldClearInterval = window.asDynamic().clearInterval
            var tick: (() -> Unit)? = null
            window.asDynamic().setInterval = { callback: () -> Unit, _: Int ->
                tick = callback
                1
            }
            window.asDynamic().clearInterval = { _: dynamic -> Unit }
            val statuses = mutableListOf<(dynamic) -> Unit>()
            val initial = mutableListOf<Pair<String, (dynamic) -> Unit>>()
            val updates = mutableListOf<Pair<String, (dynamic) -> Unit>>()
            try {
                initHistoryRangeControls(
                    currentQuery = "?range=30d",
                    fetchJson = { url, success, _ -> initial += url to success },
                    timezone = { "UTC" },
                    pushState = {},
                    navigate = {},
                    statusRequest = { _, success, _ ->
                        statuses += success;
                        {}
                    },
                    panelRequest = { url, success, _ ->
                        updates += url to success;
                        {}
                    },
                )
                val chartRequest = initial.first {
                    it.first.startsWith("/api/history/charts?") && chartRefreshSelection(it.first) == "DEPOSITS"
                }
                chartRequest.second(
                    json(
                        "series" to arrayOf(chartRefreshSeries("ZUSD", "USD", 100.0)),
                        "range" to
                            json(
                                "preset" to "30d",
                                "fromIso" to "2026-03-01T00:00:00Z",
                                "toIso" to "2026-03-30T00:00:00Z",
                            ),
                        "fullRange" to json(
                            "fromIso" to "2026-03-01T00:00:00Z",
                            "toIso" to "2026-03-30T00:00:00Z",
                            "pointCount" to 1,
                            "baselineFromIso" to "2026-03-01T00:00:00Z",
                        ),
                    ),
                )

                val runTick = tick ?: error("the refresh ticker was not registered")
                statuses[0](json("dataRevision" to 1))
                runTick()
                statuses[1](json("dataRevision" to 2))

                val refreshed = updates.first {
                    it.first.startsWith("/api/history/charts?") && chartRefreshSelection(it.first) == "DEPOSITS"
                }.first
                refreshed.contains("baselineFrom=2026-03-01T00%3A00%3A00Z") shouldBe true
            } finally {
                disposeHistoryAutoRefresh()
                wrapper.remove()
                disposeDetachedHistoryCharts()
                window.asDynamic().setInterval = oldSetInterval
                window.asDynamic().clearInterval = oldClearInterval
            }
        }

        "automatic portfolio scopes remain separate with null conflicts and latest bounds advance only unpinned" {
            val root = chartRefreshRoot("history-value-chart")
            val session = chartRefreshSession(root, root.id)
            val first = chartRefreshSeries("ACCOUNT:old", "BTC + USD | older", 10.0)
            first.points = arrayOf(
                json("t" to 1000.0, "v" to 10.0, "complete" to true),
                json("t" to 1500.0, "v" to null, "complete" to false),
                json("t" to 2000.0, "v" to 12.0, "complete" to true),
            )
            try {
                applyHistoryChartSeries(
                    root.id,
                    chartRefreshResponse(
                        first,
                        chartRefreshSeries("ACCOUNT:new", "BTC + ETH + USD | newer", 20.0),
                        to = "1970-01-01T00:00:04Z",
                    ),
                )
                session.chart.data.datasets.length as Int shouldBe 2
                (session.chart.data.datasets[0].data[1].y as Double?) shouldBe null
                session.chart.data.datasets[0].spanGaps as Boolean shouldBe false
                session.chart.data.datasets[1].data[0].y as Double shouldBe 20.0
                root.querySelectorAll("svg").length shouldBe 2
                root.querySelector("circle[data-series='ACCOUNT:old'][data-gap='true']") shouldBe
                    root.querySelector("circle[data-gap='true']")
                session.viewport.full.max shouldBe 4000.0
                session.viewport.visible.max shouldBe 4000.0

                session.viewport = ChartViewport(session.viewport.full, ChartRange(1200.0, 1800.0))
                applyHistoryChartSeries(root.id, chartRefreshResponse(first, to = "1970-01-01T00:00:05Z"))
                session.viewport.full.max shouldBe 4000.0
                session.viewport.visible shouldBe ChartRange(1200.0, 1800.0)
                applyHistoryChartSeries(root.id, chartRefreshResponse(first, to = "1970-01-01T00:00:05Z"), false)
                session.viewport.full.max shouldBe 5000.0
                session.viewport.isZoomed shouldBe false
            } finally {
                root.remove()
                disposeDetachedHistoryCharts()
            }
        }

        "revisions refresh every rendered chart and obsolete same-range responses cannot overwrite new evidence" {
            val wrapper = document.createElement("div") as HTMLElement
            val ids = listOf(
                "history-value-chart",
                "history-holdings-chart",
                "history-allocation-chart",
                "history-cash-flow-chart",
                "history-rewards-chart",
                "history-asset-chart",
            )
            wrapper.innerHTML = """
                <a data-history-range="30d"></a><a data-history-range="7d" href="/history?range=7d"></a>
                <div id="history-range-custom-panel"></div><input id="history-range-custom-start">
                <input id="history-range-custom-end"><span id="history-range-custom-tz"></span>
                <p id="history-range-custom-error"></p><button id="history-range-custom-apply"></button>
                <select id="history-scope-select"></select><div id="history-refresh-status"></div>
            """.trimIndent() + ids.joinToString("") {
                "<div id='$it'></div>"
            }
            document.body!!.appendChild(wrapper)
            val sessions = ids.associateWith { chartRefreshSession(document.getElementById(it) as HTMLElement, it) }
            val oldSetInterval = window.asDynamic().setInterval
            val oldClearInterval = window.asDynamic().clearInterval
            var tick: (() -> Unit)? = null
            window.asDynamic().setInterval = { callback: () -> Unit, _: Int ->
                tick = callback
                1
            }
            window.asDynamic().clearInterval = { _: dynamic -> Unit }
            val statuses = mutableListOf<(dynamic) -> Unit>()
            val initial = mutableListOf<Pair<String, (dynamic) -> Unit>>()
            val updates = mutableListOf<Pair<String, (dynamic) -> Unit>>()
            try {
                initHistoryRangeControls(
                    currentQuery = "?range=30d",
                    fetchJson = { url, success, _ -> initial += url to success },
                    timezone = { "UTC" },
                    pushState = {},
                    navigate = {},
                    statusRequest = { _, success, _ ->
                        statuses += success;
                        {}
                    },
                    panelRequest = { url, success, _ ->
                        updates += url to success;
                        {}
                    },
                )
                initial.filter { it.first.startsWith("/api/history/charts?") }.size shouldBe 6
                val runTick = tick ?: error("the refresh ticker was not registered")
                statuses[0](json("dataRevision" to 1))
                runTick()
                statuses[1](json("dataRevision" to 2))
                val firstRevision = updates.filter { it.first.startsWith("/api/history/charts?") }
                firstRevision.size shouldBe 6
                firstRevision.map { chartRefreshSelection(it.first) }.toSet() shouldBe
                    setOf("PORTFOLIO", "HOLDINGS", "ALLOCATION", "DEPOSITS", "REWARDS", "CHECKPOINTS")
                runTick()
                statuses[2](json("dataRevision" to 3))
                val newest = updates.filter { it.first.startsWith("/api/history/charts?") }.takeLast(6)
                newest.forEach { it.second(chartRefreshResponse(chartRefreshSeries("XXBT", "BTC", 3.0))) }
                firstRevision.forEach { it.second(chartRefreshResponse(chartRefreshSeries("XXBT", "BTC", 2.0))) }
                initial.filter { it.first.startsWith("/api/history/charts?") }
                    .forEach { it.second(chartRefreshResponse(chartRefreshSeries("XXBT", "BTC", 1.0))) }
                sessions.values.forEach { it.chart.data.datasets[0].data[0].y as Double shouldBe 3.0 }

                val scope = wrapper.querySelector("#history-scope-select") as HTMLSelectElement
                val option = document.createElement("option") as HTMLOptionElement
                option.value = "ACCOUNT:older"
                option.textContent = "older"
                scope.appendChild(option)
                scope.value = "ACCOUNT:older"
                scope.dispatchEvent(Event("change"))
                val transitioned = initial.filter { it.first.startsWith("/api/history/charts?") }.takeLast(6)
                transitioned.size shouldBe 6
                transitioned.all {
                    "scope=ACCOUNT%3Aolder" in it.first || "scope=ACCOUNT:older" in it.first
                } shouldBe true
                transitioned.forEach { it.second(chartRefreshResponse(chartRefreshSeries("XXBT", "BTC", 4.0))) }
                sessions.values.forEach { it.chart.data.datasets[0].data[0].y as Double shouldBe 4.0 }
            } finally {
                disposeHistoryAutoRefresh()
                wrapper.remove()
                disposeDetachedHistoryCharts()
                window.asDynamic().setInterval = oldSetInterval
                window.asDynamic().clearInterval = oldClearInterval
            }
        }
    }
}

private fun chartRefreshRoot(id: String): HTMLElement = (document.createElement("div") as HTMLElement).also {
    it.id = id
    document.body!!.appendChild(it)
}

private fun chartRefreshSession(root: HTMLElement, id: String): HistoryChartSession {
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    root.appendChild(canvas)
    val config = historyMultiSeriesConfig(emptyList(), false)
    val chart = json("data" to config.data, "options" to config.options, "update" to { _: dynamic -> Unit })
    return HistoryChartSession(root, canvas, chart, "", ChartRange(1000.0, 3000.0)).also {
        historyChartSessions[id] = it
    }
}

private fun chartRefreshSeries(key: String, label: String, value: Double): dynamic = json(
    "key" to key,
    "label" to label,
    "points" to arrayOf(json("t" to 1000.0, "v" to value, "complete" to true)),
)

private fun chartRefreshResponse(vararg series: dynamic, to: String = "1970-01-01T00:00:03Z"): dynamic = json(
    "series" to series,
    "range" to json("preset" to "30d", "fromIso" to "1970-01-01T00:00:01Z", "toIso" to to),
    "fullRange" to json("fromIso" to "1970-01-01T00:00:01Z", "toIso" to to, "pointCount" to 1),
)

private fun chartRefreshSelection(url: String): String {
    val query = url.substringAfter('?')
    val params: dynamic = js("new URLSearchParams(query)")
    return params.get("selection") as String
}
