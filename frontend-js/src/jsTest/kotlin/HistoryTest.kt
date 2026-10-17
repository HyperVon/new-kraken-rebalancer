package com.gemini.krakenbot.frontend

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import kotlin.js.json

class HistoryTest :
    StringSpec({
        "chart evidence stays chronological and incomplete observations remain gaps" {
            val root = document.createElement("div")
            root.innerHTML = """
            <svg>
              <circle data-time="3000" data-value="12.5"/>
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
                HistoryChartPoint(3000.0, 12.5),
            )
            val config = historyChartConfig(historyChartPoints(root), true)
            assertHistoryValue(config.data.datasets[0].data[1].y, null)
            assertHistoryValue(config.data.datasets[0].spanGaps, false)
            assertHistoryValue(config.options.parsing, false)
            assertHistoryValue(config.data.datasets[0].showLine, true)
            assertHistoryValue(historyChartConfig(historyChartPoints(root), false).data.datasets[0].showLine, false)
            val item = json("parsed" to json("x" to 1000, "y" to 1234.56789))
            config.options.plugins.tooltip.callbacks.title(arrayOf(item)).toString() shouldBe
                "1970-01-01 00:00:01.000 UTC"
            assertHistoryValue(config.options.plugins.tooltip.callbacks.label(item).toString(), "$1,234.57")
            assertHistoryValue(config.options.scales.x.ticks.callback(1000).toString(), "00:00:01")
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

        "long chart windows label both calendar date and time in UTC" {
            val config = historyChartConfig(
                listOf(HistoryChartPoint(0.0, 1.0), HistoryChartPoint(86_400_000.0, 2.0)),
                true,
            )
            assertHistoryValue(config.options.scales.x.ticks.callback(0).toString(), "01-01 00:00")
            assertHistoryValue(config.options.scales.x.ticks.callback(86_400_000).toString(), "01-02 00:00")
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
    })

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
