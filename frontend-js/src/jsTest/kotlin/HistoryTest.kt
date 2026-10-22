package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.ChartProps
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.js.Date
import kotlin.js.json

class HistoryTest :
    StringSpec({
        "chart evidence stays chronological and incomplete observations remain gaps" {
            val root = document.createElement("div")
            root.innerHTML = """
            <svg>
              <circle data-time="3000" data-value="12.5" data-history-day="2026-03-10"/>
              <circle data-time="2000" data-gap="true" data-value="99"/>
              <circle data-time="1000" data-value="10"/>
              <circle data-time="bad" data-value="1"/>
              <circle data-time="4000" data-value="Infinity"/>
              <circle data-value="1"/>
              <circle data-time="5000"/>
            </svg>
            """.trimIndent()
            historyChartPoints(root) shouldBe listOf(
                HistoryChartPoint(1000.0, 10.0),
                HistoryChartPoint(2000.0, null),
                HistoryChartPoint(3000.0, 12.5, "2026-03-10"),
            )
            val config = historyChartConfig(historyChartPoints(root), true, ChartRange(1000.0, 3000.0))
            assertHistoryValue(config.data.datasets[0].data[1].y, null)
            assertHistoryValue(config.data.datasets[0].spanGaps, false)
            assertHistoryValue(config.options.parsing, false)
            assertHistoryValue(config.data.datasets[0].showLine, true)
            assertHistoryValue(historyChartConfig(historyChartPoints(root), false).data.datasets[0].showLine, false)
            val item = json("parsed" to json("x" to 1000, "y" to 1234.56789))
            config.options.plugins.tooltip.callbacks.title(arrayOf(item)).toString() shouldBe
                localStamp(1000.0)
            assertHistoryValue(config.options.plugins.tooltip.callbacks.label(item).toString(), "$1,234.57")
            assertHistoryValue(config.options.scales.x.ticks.callback(1000).toString(), localAxisShort(1000.0))
            assertHistoryValue(config.options.scales.y.ticks.callback(1000).toString(), "$1,000")
            val quantities = historyChartConfig(emptyList(), false)
            assertHistoryValue(quantities.options.plugins.tooltip.callbacks.label(item).toString(), "1,234.56789")
        }

        "chart controls change rendering and latest time window without discarding gaps" {
            val panel = historyFixture()
            try {
                var chart: dynamic = null
                var updates = 0
                var resizes = 0
                initHistoryCharts { _, config ->
                    chart = json(
                        "data" to config.data,
                        "options" to config.options,
                        "update" to { _: dynamic -> updates++ },
                        "resize" to { resizes++ },
                    )
                    chart
                }
                val controls = panel.querySelector("[data-history-controls]") as HTMLElement
                controls.hidden shouldBe false
                panel.querySelector("svg")!!.hasAttribute("hidden") shouldBe true
                clickHistoryButton(panel, "[data-history-view='points']")
                assertHistoryValue(chart.data.datasets[0].showLine, false)
                panel.querySelector("[data-history-view='points']")!!.classList.contains("active") shouldBe true
                clickHistoryButton(panel, "[data-history-view='line']")
                assertHistoryValue(chart.data.datasets[0].showLine, true)
                clickHistoryButton(panel, "[data-history-zoom='in']")
                assertHistoryValue(chart.options.scales.x.min, 2000.0)
                assertHistoryValue(chart.options.scales.x.max, 3000.0)
                clickHistoryButton(panel, "[data-history-pan='earlier']")
                assertHistoryValue(chart.options.scales.x.min, 1500.0)
                clickHistoryButton(panel, "[data-history-pan='later']")
                assertHistoryValue(chart.options.scales.x.min, 2000.0)
                clickHistoryButton(panel, "[data-history-zoom='out']")
                assertHistoryValue(chart.options.scales.x.min, 1000.0)
                clickHistoryButton(panel, "[data-history-zoom='in']")
                clickHistoryButton(panel, "[data-history-zoom='reset']")
                assertHistoryValue(chart.options.scales.x.min, 1000.0)
                clickHistoryButton(panel, "[data-history-expand]")
                panel.classList.contains("history-chart-expanded") shouldBe true
                clickHistoryButton(panel, "[data-history-expand]")
                panel.classList.contains("history-chart-expanded") shouldBe false
                resizes shouldBe 2
                updates shouldBe 10
                assertHistoryValue(chart.data.datasets[0].data[1].y, null)
                var duplicate = false
                initHistoryCharts { _, _ ->
                    duplicate = true
                    null
                }
                duplicate shouldBe false
            } finally {
                panel.remove()
            }
        }

        "missing or failed chart library preserves visible SVG and hidden controls" {
            val panel = historyFixture()
            try {
                initHistoryCharts { _, _ -> null }
                panel.querySelector("canvas") shouldBe null
                panel.querySelector("svg")!!.hasAttribute("hidden") shouldBe false
                (panel.querySelector("[data-history-controls]") as HTMLElement).hidden shouldBe true
                initHistoryCharts { _, _ -> error("CDN could not initialize") }
                panel.querySelector("canvas") shouldBe null
                panel.querySelector("svg")!!.hasAttribute("hidden") shouldBe false
                val chart = panel.querySelector("#history-value-chart")!!
                chart.innerHTML = "<svg><circle data-time='1000' data-gap='true'/></svg>"
                var called = false
                initHistoryCharts { _, _ ->
                    called = true
                    null
                }
                called shouldBe false
                chart.innerHTML = ""
                initHistoryCharts { _, _ ->
                    called = true
                    null
                }
                called shouldBe false
            } finally {
                panel.remove()
            }
        }

        "default chart factory preserves fallback without CDN and constructs a loaded chart" {
            val panel = historyFixture()
            val originalChart = window.asDynamic().Chart
            try {
                window.asDynamic().Chart = undefined
                initHistoryCharts()
                panel.querySelector("canvas") shouldBe null
                panel.querySelector("svg")!!.hasAttribute("hidden") shouldBe false
                (panel.querySelector("[data-history-controls]") as HTMLElement).hidden shouldBe true
                window.asDynamic().Chart = js(
                    """(function(canvas, config) {
                        this.data = config.data;
                        this.options = config.options;
                        this.update = function() {};
                        this.resize = function() {};
                        canvas.historyTestChart = this;
                    })""",
                )
                panel.querySelector("svg")!!.innerHTML = "<circle data-time='1000' data-value='10'/>"
                initHistoryCharts()
                val canvas = panel.querySelector("canvas")!!
                val chart = canvas.asDynamic().historyTestChart
                assertHistoryValue(chart.data.datasets[0].data[0].x, 1000.0)
                panel.querySelector("svg")!!.hasAttribute("hidden") shouldBe true
                (panel.querySelector("[data-history-controls]") as HTMLElement).hidden shouldBe false
                listOf(
                    "[data-history-zoom='in']",
                    "[data-history-zoom='out']",
                    "[data-history-pan='earlier']",
                    "[data-history-pan='later']",
                    "[data-history-zoom='reset']",
                ).forEach { selector ->
                    clickHistoryButton(panel, selector)
                    assertHistoryValue(chart.options.scales.x.min, 1000.0)
                    assertHistoryValue(chart.options.scales.x.max, 1000.0)
                }
            } finally {
                window.asDynamic().Chart = originalChart
                panel.remove()
            }
        }

        "dense portfolio lines stay legible while points remain inspectable" {
            val points = (0..60).map { HistoryChartPoint(it * 1000.0, it.toDouble()) }
            val line = historyChartConfig(points, true)
            assertHistoryValue(line.data.datasets[0].pointRadius, 1)
            assertHistoryValue(line.data.datasets[0].pointHitRadius, 10)
            assertHistoryValue(line.options.interaction.intersect, false)
            assertHistoryValue(line.options.interaction.axis, "x")
            assertHistoryValue(historyChartConfig(points, false).data.datasets[0].pointRadius, 3)
            assertHistoryValue(historyChartConfig(points.take(2), true).data.datasets[0].pointRadius, 3)
            val isolated = points.flatMap { listOf(it, HistoryChartPoint(it.time + 500, null)) }
            val mixed = historyChartConfig(isolated, true)
            assertHistoryValue(mixed.data.datasets[0].pointRadius, 1)
            assertHistoryValue(mixed.data.datasets[0].data[1].y, null)
            assertHistoryValue(mixed.data.datasets[0].spanGaps, false)
            val panel = historyFixture()
            try {
                panel.querySelector("svg")!!.innerHTML = points.joinToString("") {
                    "<circle data-time='${it.time}' data-value='${it.value}'/>"
                }
                var chart: dynamic = null
                initHistoryCharts { _, config ->
                    chart = json("data" to config.data, "options" to config.options, "update" to { _: dynamic -> })
                    chart
                }
                clickHistoryButton(panel, "[data-history-view='points']")
                assertHistoryValue(chart.data.datasets[0].showLine, false)
                assertHistoryValue(chart.data.datasets[0].pointRadius, 3)
                clickHistoryButton(panel, "[data-history-view='line']")
                assertHistoryValue(chart.data.datasets[0].pointRadius, 1)
                assertHistoryValue(chart.data.datasets[0].data.length, 61)
            } finally {
                panel.remove()
            }
        }

        "long chart windows label both calendar date and time in local time" {
            val config = historyChartConfig(
                listOf(HistoryChartPoint(0.0, 1.0), HistoryChartPoint(86_400_000.0, 2.0)),
                true,
            )
            assertHistoryValue(config.options.scales.x.ticks.callback(0).toString(), localAxisLong(0.0))
            assertHistoryValue(
                config.options.scales.x.ticks.callback(86_400_000).toString(),
                localAxisLong(86_400_000.0),
            )
        }

        "a one-point data series keeps date labels when its selected range is long" {
            val time = 1_700_000_000_000.0
            val point = listOf(HistoryChartPoint(time, 1.0))
            val longRange = ChartRange(time - 30 * 86_400_000.0, time)
            val longWindow = historyChartConfig(point, true, longRange)
            val unknownWindow = historyChartConfig(point, true)

            assertHistoryValue(
                longWindow.options.scales.x.ticks.callback(time).toString(),
                localAxisLong(time),
            )
            assertHistoryValue(
                unknownWindow.options.scales.x.ticks.callback(time).toString(),
                localAxisLong(time),
            )
            historyAxisTimeLabel(time, longRange) shouldBe localAxisLong(time)
            historyAxisTimeLabel(time, ChartRange(time - 30_000.0, time)) shouldBe localAxisShort(time)
        }

        "chart time labels render in the browser's local wall-clock time" {
            val originalDate = window.asDynamic().Date
            try {
                // Keep UTC methods native so a UTC-formatting regression fails even on UTC CI.
                window.asDynamic().Date = js(
                    """(function(NativeDate) {
                        return function(time) {
                            var date = new NativeDate(time);
                            var local = new NativeDate(time + 7200000);
                            date.getFullYear = function() { return local.getUTCFullYear(); };
                            date.getMonth = function() { return local.getUTCMonth(); };
                            date.getDate = function() { return local.getUTCDate(); };
                            date.getHours = function() { return local.getUTCHours(); };
                            date.getMinutes = function() { return local.getUTCMinutes(); };
                            date.getSeconds = function() { return local.getUTCSeconds(); };
                            date.getMilliseconds = function() { return local.getUTCMilliseconds(); };
                            date.getTimezoneOffset = function() { return -120; };
                            return date;
                        };
                    })""",
                )(originalDate)
                val time = 1_700_000_000_000.0
                historyDateLabel(time) shouldBe "2023-11-15 00:13:20.000"
                historyAxisTimeLabel(time, true) shouldBe "00:13:20"
                historyAxisTimeLabel(time, false) shouldBe "11-15 00:13"
            } finally {
                window.asDynamic().Date = originalDate
            }
        }

        "canvas and SVG share the chart palette tokens" {
            val config = historyChartConfig(listOf(HistoryChartPoint(1000.0, 10.0)), true)
            assertHistoryValue(config.data.datasets[0].borderColor, "rgba(96, 165, 250, 1)")
            assertHistoryValue(config.data.datasets[0].backgroundColor, "rgba(96, 165, 250, 1)")
            assertHistoryValue(config.options.scales.x.grid.color, "rgba(51, 65, 85, 0.3)")
            assertHistoryValue(config.options.scales.y.grid.color, "rgba(51, 65, 85, 0.3)")
            assertHistoryValue(config.options.scales.x.ticks.color, "#64748b")
            assertHistoryValue(config.options.scales.y.ticks.color, "#64748b")
        }

        "navigation scrubber pans the zoomed window and synchronizes with chart controls" {
            val panel = historyFixture()
            val track = document.createElement("div") as HTMLElement
            track.className = "history-chart-scrubber"
            val scrubber = document.createElement("input") as HTMLInputElement
            scrubber.type = "range"
            scrubber.className = "history-chart-scrubber-input"
            scrubber.setAttribute("data-chart-id", "history-value-chart")
            scrubber.disabled = true
            track.appendChild(scrubber)
            panel.appendChild(track)
            try {
                var chart: dynamic = null
                var updates = 0
                initHistoryCharts { _, config ->
                    chart = json(
                        "data" to config.data,
                        "options" to config.options,
                        "update" to { _: dynamic -> updates++ },
                        "resize" to { },
                    )
                    chart
                }
                scrubber.disabled shouldBe true
                scrubber.min shouldBe "0"
                scrubber.max shouldBe "1000"
                track.classList.contains("hidden") shouldBe true
                clickHistoryButton(panel, "[data-history-zoom='in']")
                scrubber.disabled shouldBe false
                track.classList.contains("hidden") shouldBe false
                scrubber.value shouldBe "1000"
                clickHistoryButton(panel, "[data-history-pan='earlier']")
                scrubber.value shouldBe "500"
                scrubber.value = "0"
                scrubber.dispatchEvent(Event("input"))
                assertHistoryValue(chart.options.scales.x.min, 1000.0)
                assertHistoryValue(chart.options.scales.x.max, 2000.0)
                scrubber.value = "1000"
                scrubber.dispatchEvent(Event("input"))
                assertHistoryValue(chart.options.scales.x.min, 2000.0)
                assertHistoryValue(chart.options.scales.x.max, 3000.0)
                initHistoryScrubbers()
                scrubber.value = "0"
                scrubber.dispatchEvent(Event("input"))
                assertHistoryValue(chart.options.scales.x.min, 1000.0)
                updates shouldBe 5
                clickHistoryButton(panel, "[data-history-zoom='reset']")
                assertHistoryValue(chart.options.scales.x.min, 1000.0)
                assertHistoryValue(chart.options.scales.x.max, 3000.0)
                scrubber.disabled shouldBe true
                track.classList.contains("hidden") shouldBe true
                updates shouldBe 6
            } finally {
                panel.remove()
            }
        }

        "asset charts stay as points and work without a control toolbar" {
            val panel = historyFixture()
            try {
                panel.querySelector("#history-value-chart")!!.id = "history-asset-chart"
                panel.querySelector("[data-history-controls]")!!.remove()
                var chart: dynamic = null
                initHistoryCharts { _, config ->
                    chart = config
                    config
                }
                assertHistoryValue(chart.data.datasets[0].showLine, false)
                (panel.querySelector("canvas") != null) shouldBe true
            } finally {
                panel.remove()
            }
        }

        "multi-series samples group by series attribute and dedupe repeated markers" {
            val root = document.createElement("div")
            root.innerHTML = """
            <svg>
              <circle data-time="2000" data-value="20" data-series="BTC"/>
              <circle data-time="1000" data-value="10" data-series="BTC"/>
              <circle data-time="1000" data-value="10" data-series="BTC"/>
              <circle data-time="1000" data-value="5" data-series="ETH"/>
            </svg>
            """.trimIndent()
            val series = historySeriesPoints(root)
            series.size shouldBe 2
            series.first { it.first == "BTC" }.second shouldBe listOf(
                HistoryChartPoint(1000.0, 10.0),
                HistoryChartPoint(2000.0, 20.0),
            )
            series.first { it.first == "ETH" }.second shouldBe listOf(HistoryChartPoint(1000.0, 5.0))
        }

        "multi-series parser preserves named gaps without values as null samples" {
            val root = document.createElement("div")
            root.innerHTML = """
            <svg>
              <circle data-time="3000" data-value="20" data-series="BTC"/>
              <circle data-time="2000" data-gap="true" data-series="BTC"/>
              <circle data-time="1000" data-value="10" data-series="BTC"/>
              <circle data-time="2000" data-gap="true" data-value="99" data-series="ETH"/>
            </svg>
            """.trimIndent()

            historySeriesPoints(root) shouldBe listOf(
                "BTC" to listOf(
                    HistoryChartPoint(1000.0, 10.0),
                    HistoryChartPoint(2000.0, null),
                    HistoryChartPoint(3000.0, 20.0),
                ),
                "ETH" to listOf(HistoryChartPoint(2000.0, null)),
            )
        }

        "multi-series config keeps series identity and formats money and percentage axes" {
            val series = listOf(
                "BTC" to listOf(HistoryChartPoint(1000.0, -2.5), HistoryChartPoint(2000.0, 3.0)),
                "ETH" to listOf(HistoryChartPoint(1000.0, 1.0), HistoryChartPoint(2000.0, 2.0)),
            )
            val percent = historyMultiSeriesConfig(series, percent = true, axisRange = ChartRange(1000.0, 2000.0))
            assertHistoryValue(percent.data.datasets.length, 2)
            assertHistoryValue(percent.data.datasets[0].label, "BTC")
            assertHistoryValue(percent.data.datasets[1].label, "ETH")
            assertHistoryValue(percent.data.datasets[0].showLine, true)
            assertHistoryValue(percent.options.plugins.legend.display, true)
            val percentLabel = percent.options.plugins.tooltip.callbacks.label
            assertHistoryValue(
                percentLabel(json("parsed" to json("y" to 3.5), "dataset" to json("label" to "BTC"))),
                "BTC · 3.5 pp",
            )
            val title = percent.options.plugins.tooltip.callbacks.title
            assertHistoryValue(title(arrayOf(json("parsed" to json("x" to 1000.0)))), localStamp(1000.0))
            val yTick = percent.options.scales.y.ticks.callback
            assertHistoryValue(yTick(-2.5), "-2.5 pp")
            val xTick = percent.options.scales.x.ticks.callback
            assertHistoryValue(xTick(1000.0), localAxisShort(1000.0))
            val gridColor = percent.options.scales.y.grid.color
            assertHistoryValue(gridColor(json("tick" to json("value" to 0))), ChartProps.COLOR_TICK)
            assertHistoryValue(gridColor(json("tick" to json("value" to 5))), ChartProps.COLOR_GRID_LINE)

            val money = historyMultiSeriesConfig(
                listOf("BTC" to listOf(HistoryChartPoint(0.0, 3.5), HistoryChartPoint(86_400_000.0, 4.5))),
                percent = false,
            )
            val moneyLabel = money.options.plugins.tooltip.callbacks.label
            assertHistoryValue(
                moneyLabel(json("parsed" to json("y" to 3.5), "dataset" to json("label" to null))),
                "$3.5",
            )
            val moneyYTick = money.options.scales.y.ticks.callback
            assertHistoryValue(moneyYTick(3.5), "$3.5")
            val longWindowX = money.options.scales.x.ticks.callback
            assertHistoryValue(longWindowX(86_400_000.0), localAxisLong(86_400_000.0))
        }

        "multi-series charts enhance through Chart.js and view toggles apply to every dataset" {
            val panel = document.createElement("section") as HTMLElement
            panel.className = "glass-panel"
            panel.innerHTML = """
        <div data-history-controls hidden>
          <button data-history-view="line">Line</button>
          <button data-history-view="points">Points</button>
        </div>
        <div id="history-holdings-chart"><svg class="history-chart-svg">
          <circle data-time="1000" data-value="10" data-series="BTC"/>
          <circle data-time="2000" data-value="20" data-series="BTC"/>
          <circle data-time="1000" data-value="5" data-series="ETH"/>
          <circle data-time="2000" data-value="7" data-series="ETH"/>
        </svg></div>
            """.trimIndent()
            document.body!!.appendChild(panel)
            try {
                var captured: dynamic = null
                initHistoryCharts { _, config ->
                    captured = config
                    json(
                        "data" to config.data,
                        "options" to config.options,
                        "update" to { _: dynamic -> },
                        "resize" to { },
                        "setActiveElements" to { _: dynamic -> },
                        "tooltip" to json("setActiveElements" to { _: dynamic, _: dynamic -> }),
                    )
                }
                assertHistoryValue(captured.data.datasets.length, 2)
                panel.querySelector("#history-holdings-chart svg")!!.getAttribute("hidden") shouldBe ""
                (panel.querySelector("#history-holdings-chart canvas") != null) shouldBe true
                clickHistoryButton(panel, "[data-history-view='points']")
                assertHistoryValue(captured.data.datasets[0].showLine, false)
                assertHistoryValue(captured.data.datasets[1].showLine, false)
            } finally {
                panel.remove()
            }
        }
    })

private fun historyPad(value: Int, size: Int): String = value.toString().padStart(size, '0')

/** Independent local wall-clock oracle for the label format under test. */
private fun localStamp(time: Double): String {
    val date = Date(time)
    return "${date.getFullYear()}-${historyPad(date.getMonth() + 1, 2)}-${historyPad(date.getDate(), 2)} " +
        "${historyPad(date.getHours(), 2)}:${historyPad(date.getMinutes(), 2)}:" +
        "${historyPad(date.getSeconds(), 2)}.${historyPad(date.getMilliseconds(), 3)}"
}

private fun localAxisShort(time: Double): String {
    val date = Date(time)
    return "${historyPad(date.getHours(), 2)}:${historyPad(date.getMinutes(), 2)}:${historyPad(date.getSeconds(), 2)}"
}

private fun localAxisLong(time: Double): String {
    val date = Date(time)
    return "${historyPad(date.getMonth() + 1, 2)}-${historyPad(date.getDate(), 2)} " +
        "${historyPad(date.getHours(), 2)}:${historyPad(date.getMinutes(), 2)}"
}

private fun historyFixture(): HTMLElement {
    val panel = document.createElement("section") as HTMLElement
    panel.className = "glass-panel"
    panel.innerHTML = """
        <div data-history-controls hidden>
          <button data-history-view="line">Line</button>
          <button data-history-view="points">Points</button>
          <button data-history-zoom="in">Zoom in</button>
          <button data-history-zoom="out">Zoom out</button>
          <button data-history-pan="earlier">Earlier</button>
          <button data-history-pan="later">Later</button>
          <button data-history-zoom="reset">Reset</button>
          <button data-history-expand="true">Expand</button>
        </div>
        <div id="history-value-chart"><svg><title>Portfolio samples</title>
          <circle data-time="1000" data-value="10"/>
          <circle data-time="2000" data-gap="true"/>
          <circle data-time="3000" data-value="12"/>
        </svg></div>
    """.trimIndent()
    document.body!!.appendChild(panel)
    return panel
}

private fun clickHistoryButton(panel: HTMLElement, selector: String) {
    (panel.querySelector(selector) as HTMLButtonElement).click()
}

private fun assertHistoryValue(actual: Any?, expected: Any?) {
    actual shouldBe expected
}
