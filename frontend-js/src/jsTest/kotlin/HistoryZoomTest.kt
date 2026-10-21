package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.HistoryChartActions
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import org.w3c.dom.events.WheelEvent
import org.w3c.dom.events.WheelEventInit
import kotlin.js.json
import kotlin.math.abs

class HistoryZoomTest :
    StringSpec({
        "viewport zoom, pan, and selection stay clamped to the full range" {
            val full = ChartRange(1000.0, 5000.0)
            val viewport = ChartViewport(full, full)
            viewport.isZoomed shouldBe false
            viewport.fraction shouldBe 0.0
            val zoomed = viewport.zoom(0.5, 5000.0)
            zoomed.visible shouldBe ChartRange(3000.0, 5000.0)
            zoomed.isZoomed shouldBe true
            zoomed.fraction shouldBe 1.0
            zoomed.panBy(-10_000.0).visible shouldBe ChartRange(1000.0, 3000.0)
            zoomed.panBy(10_000.0).visible shouldBe ChartRange(3000.0, 5000.0)
            viewport.select(4000.0, 2000.0).visible shouldBe ChartRange(2000.0, 4000.0)
            zoomed.reset().visible shouldBe full
            zoomed.zoom(0.0, 3000.0) shouldBe zoomed
            zoomed.zoom(4.0, 5000.0).visible shouldBe full
            zoomed.panToFraction(0.5).visible shouldBe ChartRange(2000.0, 4000.0)
            zoomed.panToFraction(2.0).visible shouldBe ChartRange(3000.0, 5000.0)
            viewport.panToFraction(0.3) shouldBe viewport
            zoomed.zoom(0.0001, 4000.0).visible.span shouldBe 1.0
            val frozen = ChartViewport(full, ChartRange(3000.0, 3000.0))
            frozen.zoom(0.5, 3000.0) shouldBe frozen
        }

        "button reducers share one viewport instead of stale span and end" {
            val full = ChartRange(0.0, 1600.0)
            var viewport = ChartViewport(full, full)
            viewport = zoomedViewport(viewport, HistoryChartActions.ZOOM_IN)
            viewport = zoomedViewport(viewport, HistoryChartActions.ZOOM_IN)
            viewport.visible shouldBe ChartRange(1200.0, 1600.0)
            viewport = pannedViewport(viewport, HistoryChartActions.PAN_EARLIER)
            viewport.visible shouldBe ChartRange(1000.0, 1400.0)
            viewport = pannedViewport(viewport, HistoryChartActions.PAN_LATER)
            viewport.visible shouldBe ChartRange(1200.0, 1600.0)
            viewport = zoomedViewport(viewport, HistoryChartActions.ZOOM_OUT)
            viewport.visible shouldBe ChartRange(800.0, 1600.0)
            viewport = zoomedViewport(viewport, HistoryChartActions.ZOOM_RESET)
            viewport.visible shouldBe full
        }

        "wheel zoom factor clamps and pixel-to-time falls back without a chart scale" {
            wheelZoomFactor(0.0) shouldBe 1.0
            wheelZoomFactor(-5000.0) shouldBe 0.5
            wheelZoomFactor(5000.0) shouldBe 2.0
            val fallback = ChartRange(0.0, 1000.0)
            val scaled = json(
                "scales" to json(
                    "x" to json("getValueForPixel" to { px: dynamic -> (px as Number).toDouble() * 2.0 }),
                ),
            )
            chartPixelToTime(scaled, 150.0, 300.0, fallback) shouldBe 300.0
            chartPixelToTime(null, 150.0, 300.0, fallback) shouldBe 500.0
            chartPixelToTime(null, 150.0, 0.0, fallback) shouldBe 0.0
            chartPixelToTime(null, 150.0, 300.0, ChartRange(10.0, 10.0)) shouldBe 10.0
            val bad = json("scales" to json("x" to json("getValueForPixel" to { _: dynamic -> js("NaN") })))
            chartPixelToTime(bad, 150.0, 300.0, fallback) shouldBe 500.0
            val canvas = document.createElement("canvas") as HTMLCanvasElement
            canvas.width = 320
            stubCanvasBounds(canvas, width = 0.0)
            gestureWidth(canvas) shouldBe 320.0
            stubCanvasBounds(canvas, width = 600.0)
            gestureWidth(canvas) shouldBe 600.0
        }

        "dense series sample to the viewport cap while keeping gaps and endpoints" {
            val valued = (0..10).map { HistoryChartPoint(it * 100.0, it.toDouble()) }
            val gap = HistoryChartPoint(550.0, null)
            val points = (valued + gap).sortedBy { it.time }
            val sampled = representativePoints(points, limit = 5)
            sampled.size shouldBe 5
            sampled.first() shouldBe valued.first()
            sampled.last() shouldBe valued.last()
            sampled.any { it == gap } shouldBe true
            representativePoints(points, limit = 20) shouldBe points
            val gapHeavy = listOf(
                HistoryChartPoint(0.0, 1.0),
                HistoryChartPoint(100.0, null),
                HistoryChartPoint(200.0, null),
                HistoryChartPoint(300.0, null),
                HistoryChartPoint(400.0, 2.0),
            )
            val capSampled = representativePoints(gapHeavy, limit = 3)
            capSampled.size shouldBe 3
            capSampled.first().time shouldBe 0.0
            capSampled.last().time shouldBe 400.0
        }

        "drag selection maps pixels to time and release zooms to the interval bounds" {
            val panel = gestureFixture()
            val fake = FakeChartFactory()
            try {
                initHistoryCharts(fake.factory)
                val canvas = panel.querySelector("canvas") as HTMLCanvasElement
                stubCanvasBounds(canvas)
                val chart = fake.charts.single()
                canvas.dispatchEvent(gestureMouse("mousedown", clientX = 100.0))
                panel.querySelector(".chart-selection-rect") shouldNotBe null
                window.dispatchEvent(gestureMouse("mousemove", clientX = 400.0))
                val rect = panel.querySelector(".chart-selection-rect") as HTMLElement
                assertNear(rect.style.width.removeSuffix("px").toDouble(), 300.0)
                window.dispatchEvent(gestureMouse("mouseup", clientX = 400.0))
                panel.querySelector(".chart-selection-rect") shouldBe null
                assertHistoryValue(chart.options.scales.x.min, 1200.0)
                assertHistoryValue(chart.options.scales.x.max, 1800.0)
                canvas.dispatchEvent(gestureMouse("mousedown", clientX = 500.0))
                window.dispatchEvent(gestureMouse("mouseup", clientX = 300.0))
                assertHistoryValue(chart.options.scales.x.min, 1600.0)
                assertHistoryValue(chart.options.scales.x.max, 2000.0)
                canvas.dispatchEvent(gestureMouse("mousedown", clientX = 100.0))
                window.dispatchEvent(gestureMouse("mousemove", clientX = 101.0))
                window.dispatchEvent(gestureMouse("mouseup", clientX = 101.0))
                assertHistoryValue(chart.options.scales.x.min, 1600.0)
                assertHistoryValue(chart.options.scales.x.max, 2000.0)
                clickHistoryButton(panel, "[data-history-zoom='out']")
                assertHistoryValue(chart.options.scales.x.min, 1200.0)
                assertHistoryValue(chart.options.scales.x.max, 2000.0)
            } finally {
                panel.remove()
                resetChartState()
            }
        }

        "shift+drag pans the visible interval without drawing a selection" {
            val panel = gestureFixture()
            val fake = FakeChartFactory()
            try {
                initHistoryCharts(fake.factory)
                val canvas = panel.querySelector("canvas") as HTMLCanvasElement
                stubCanvasBounds(canvas)
                val chart = fake.charts.single()
                clickHistoryButton(panel, "[data-history-zoom='in']")
                assertHistoryValue(chart.options.scales.x.min, 2000.0)
                canvas.dispatchEvent(gestureMouse("mousedown", clientX = 100.0, shiftKey = true))
                panel.querySelector(".chart-selection-rect") shouldBe null
                window.dispatchEvent(gestureMouse("mousemove", clientX = 250.0, shiftKey = true))
                assertHistoryValue(chart.options.scales.x.min, 1750.0)
                assertHistoryValue(chart.options.scales.x.max, 2750.0)
                window.dispatchEvent(gestureMouse("mouseup", clientX = 250.0))
                window.dispatchEvent(gestureMouse("mousemove", clientX = 500.0))
                assertHistoryValue(chart.options.scales.x.min, 1750.0)
            } finally {
                panel.remove()
                resetChartState()
            }
        }

        "ctrl+wheel zooms around the pointer while plain wheel scrolls the page" {
            val panel = gestureFixture()
            val fake = FakeChartFactory()
            try {
                initHistoryCharts(fake.factory)
                val canvas = panel.querySelector("canvas") as HTMLCanvasElement
                stubCanvasBounds(canvas)
                val chart = fake.charts.single()
                val plain = WheelEvent(
                    "wheel",
                    WheelEventInit(deltaY = -500.0, clientX = 300, bubbles = true, cancelable = true),
                )
                canvas.dispatchEvent(plain)
                plain.defaultPrevented shouldBe false
                val session = historyChartSessions.getValue("history-value-chart")
                session.viewport.isZoomed shouldBe false
                val zooming = WheelEvent(
                    "wheel",
                    WheelEventInit(
                        deltaY = -500.0,
                        ctrlKey = true,
                        clientX = 300,
                        bubbles = true,
                        cancelable = true,
                    ),
                )
                canvas.dispatchEvent(zooming)
                zooming.defaultPrevented shouldBe true
                assertNear(chart.options.scales.x.min, 1300.0)
                assertNear(chart.options.scales.x.max, 2300.0)
            } finally {
                panel.remove()
                resetChartState()
            }
        }

        "scrubber synchronizes after a drag-selection zoom and pans the selected range" {
            val panel = gestureFixture()
            val fake = FakeChartFactory()
            try {
                initHistoryCharts(fake.factory)
                val canvas = panel.querySelector("canvas") as HTMLCanvasElement
                stubCanvasBounds(canvas)
                val scrubber = panel.querySelector(".history-chart-scrubber-input") as HTMLInputElement
                scrubber.disabled shouldBe true
                canvas.dispatchEvent(gestureMouse("mousedown", clientX = 100.0))
                window.dispatchEvent(gestureMouse("mouseup", clientX = 400.0))
                scrubber.disabled shouldBe false
                scrubber.value shouldBe "143"
                scrubber.value = "0"
                scrubber.dispatchEvent(Event("input"))
                val chart = fake.charts.single()
                assertHistoryValue(chart.options.scales.x.min, 1000.0)
                assertHistoryValue(chart.options.scales.x.max, 1600.0)
                clickHistoryButton(panel, "[data-history-zoom='reset']")
                scrubber.disabled shouldBe true
                scrubber.parentElement!!.classList.contains("hidden") shouldBe true
            } finally {
                panel.remove()
                resetChartState()
            }
        }

        "legend toggles hide series and persist across an evidence refresh" {
            val panel = multiFixture()
            val fake = FakeChartFactory()
            try {
                initHistoryCharts(fake.factory)
                val chart = fake.charts.single()
                chart.options.plugins.legend.onClick(null, json("datasetIndex" to 0), json("chart" to chart))
                fake.visibilityWrites shouldBe listOf(0 to false)
                val marker = panel.querySelector("circle[data-time='2000'][data-value='20']") as Element
                marker.setAttribute("data-value", "21")
                initHistoryCharts(fake.factory)
                fake.charts.size shouldBe 2
                val rebuilt = fake.charts[1]
                assertHistoryValue(rebuilt.data.datasets[0].hidden, true)
                (rebuilt.data.datasets[1].hidden != true) shouldBe true
                chart.options.plugins.legend.onClick(null, json("datasetIndex" to 0), json("chart" to chart))
                fake.visibilityWrites shouldBe listOf(0 to false, 0 to true)
                toggleLegendSeries("history-holdings-chart", json(), chart)
                toggleLegendSeries("history-holdings-chart", json("datasetIndex" to 7), chart)
                fake.visibilityWrites.size shouldBe 2
                val plain: dynamic = json(
                    "data" to json("datasets" to arrayOf(json("label" to "BTC", "hidden" to true))),
                    "options" to json(),
                    "update" to { _: dynamic -> },
                )
                toggleLegendSeries("plain", json("datasetIndex" to 0), plain)
                assertHistoryValue(plain.data.datasets[0].hidden, false)
            } finally {
                panel.remove()
                resetChartState()
            }
        }

        "dispose on evidence swap destroys the chart and removes gesture handlers" {
            val panel = gestureFixture()
            val fake = FakeChartFactory()
            val added = mutableListOf<String>()
            val removed = mutableListOf<String>()
            val originalAdd = window.asDynamic().addEventListener
            val originalRemove = window.asDynamic().removeEventListener
            window.asDynamic().addEventListener = { type: dynamic, fn: dynamic, options: dynamic ->
                added.add(type.toString())
                if (options == null || options == undefined) {
                    originalAdd.call(window, type, fn)
                } else {
                    originalAdd.call(window, type, fn, options)
                }
            }
            window.asDynamic().removeEventListener = { type: dynamic, fn: dynamic, options: dynamic ->
                removed.add(type.toString())
                if (options == null || options == undefined) {
                    originalRemove.call(window, type, fn)
                } else {
                    originalRemove.call(window, type, fn, options)
                }
            }
            try {
                initHistoryCharts(fake.factory)
                val canvas = panel.querySelector("canvas") as HTMLCanvasElement
                stubCanvasBounds(canvas)
                canvas.dispatchEvent(gestureMouse("mousedown", clientX = 100.0))
                added.count { it == "mousemove" } shouldBe 1
                added.count { it == "mouseup" } shouldBe 1
                window.dispatchEvent(gestureMouse("mouseup", clientX = 400.0))
                removed.count { it == "mousemove" } shouldBe 1
                removed.count { it == "mouseup" } shouldBe 1
                canvas.dispatchEvent(gestureMouse("mousedown", clientX = 100.0))
                (panel.querySelector("circle[data-time='3000']") as Element).setAttribute("data-value", "99")
                initHistoryCharts(fake.factory)
                fake.destroyCalls shouldBe listOf(0)
                fake.charts.size shouldBe 2
                removed.count { it == "mousemove" } shouldBe 2
                removed.count { it == "mouseup" } shouldBe 2
                panel.querySelectorAll("canvas").length shouldBe 1
            } finally {
                window.asDynamic().addEventListener = originalAdd
                window.asDynamic().removeEventListener = originalRemove
                panel.remove()
                resetChartState()
            }
        }

        "detached chart sessions are disposed on the next enhancement pass" {
            val panel = gestureFixture()
            val fake = FakeChartFactory()
            try {
                initHistoryCharts(fake.factory)
                panel.remove()
                initHistoryCharts(fake.factory)
                fake.destroyCalls shouldBe listOf(0)
                fake.charts.size shouldBe 1
            } finally {
                resetChartState()
            }
        }

        "factory failure keeps the SVG fallback and Retry rebuilds the chart" {
            val panel = gestureFixture()
            val fake = FakeChartFactory()
            fake.fail = true
            try {
                initHistoryCharts(fake.factory)
                panel.querySelector("canvas") shouldBe null
                panel.querySelector("svg")!!.hasAttribute("hidden") shouldBe false
                (panel.querySelector("[data-history-controls]") as HTMLElement).hidden shouldBe true
                val notice = panel.querySelector(".chart-fallback-notice")!!
                notice.textContent!!.contains("Interactive chart unavailable") shouldBe true
                val retry = notice.querySelector("button")!!
                retry.textContent shouldBe "Retry"
                initHistoryCharts(fake.factory)
                panel.querySelectorAll(".chart-fallback-notice").length shouldBe 1
                fake.fail = false
                (retry as HTMLElement).click()
                fake.charts.size shouldBe 1
                panel.querySelector(".chart-fallback-notice") shouldBe null
                panel.querySelector("svg")!!.hasAttribute("hidden") shouldBe true
                (panel.querySelector("canvas") != null) shouldBe true
            } finally {
                panel.remove()
                resetChartState()
            }
        }

        "sample scrubber focuses server-rendered samples without an enhanced chart" {
            val panel = gestureFixture()
            resetChartState()
            try {
                initHistoryScrubbers()
                val scrubber = panel.querySelector(".history-chart-scrubber-input") as HTMLInputElement
                scrubber.disabled shouldBe false
                scrubber.min shouldBe "0"
                scrubber.max shouldBe "2"
                scrubber.value = "1"
                scrubber.dispatchEvent(Event("input"))
                panel.querySelector("circle[data-time='2000']")!!.classList.contains("active") shouldBe true
                panel.querySelector("circle[data-time='1000']")!!.classList.contains("active") shouldBe false
                scrubber.value = "2"
                scrubber.dispatchEvent(Event("input"))
                panel.querySelector("circle[data-time='3000']")!!.classList.contains("active") shouldBe true
                panel.querySelector("circle[data-time='2000']")!!.classList.contains("active") shouldBe false
            } finally {
                panel.remove()
                resetChartState()
            }
        }

        "touch pinch and pan drive the viewport and dispose destroys the hammer instance" {
            val panel = gestureFixture()
            val fake = FakeChartFactory()
            val ctor: dynamic = js(
                "(function FakeHammer(el) { this.handlers = {}; this.on = function(n, f) { this.handlers[n] = f; }; " +
                    "this.get = function(n) { return { set: function(options) { " +
                    "window.__fakeHammer.pinchRecognizer = n; window.__fakeHammer.pinchOptions = options; } }; }; " +
                    "this.destroy = function() { this.destroyed = true; }; window.__fakeHammer = this; })",
            )
            window.asDynamic().Hammer = ctor
            try {
                initHistoryCharts(fake.factory)
                val hammer = window.asDynamic().__fakeHammer
                val chart = fake.charts.single()
                (hammer != null && hammer != undefined) shouldBe true
                assertHistoryValue(hammer.pinchRecognizer, "pinch")
                assertHistoryValue(hammer.pinchOptions.enable, true)
                stubCanvasBounds(panel.querySelector("canvas") as HTMLCanvasElement)
                hammer.handlers["pinchstart"](json("pointerType" to "touch"))
                hammer.handlers["pinchmove"](json("pointerType" to "touch", "scale" to 2.0))
                val session = historyChartSessions.getValue("history-value-chart")
                assertNear(session.viewport.visible.span, 1000.0)
                hammer.handlers["pinchmove"](json("pointerType" to "mouse", "scale" to 4.0))
                assertNear(session.viewport.visible.span, 1000.0)
                assertHistoryValue(chart.options.scales.x.min, session.viewport.visible.min)
                assertHistoryValue(chart.options.scales.x.max, session.viewport.visible.max)
                hammer.handlers["panstart"](json("pointerType" to "touch", "deltaX" to 0.0))
                hammer.handlers["panmove"](json("pointerType" to "touch", "deltaX" to -300.0))
                assertNear(session.viewport.visible.min, 1800.0)
                assertHistoryValue(chart.options.scales.x.min, session.viewport.visible.min)
                assertHistoryValue(chart.options.scales.x.max, session.viewport.visible.max)
                (panel.querySelector("circle[data-time='3000']") as Element).setAttribute("data-value", "99")
                initHistoryCharts(fake.factory)
                assertHistoryValue(hammer.destroyed, true)
            } finally {
                window.asDynamic().Hammer = null
                panel.remove()
                resetChartState()
            }
        }

        "setViewport reports its interval and pins the visible window on the chart options" {
            val detailCalls = mutableListOf<Pair<Double, Double>>()
            historyViewportDetailHandler = { _, from, to -> detailCalls.add(from to to) }
            val updates = mutableListOf<Any?>()
            val zoomCalls = mutableListOf<dynamic>()
            val chart: dynamic = json(
                "zoomScale" to { mode: dynamic, range: dynamic, transition: dynamic ->
                    zoomCalls.add(json("mode" to mode, "min" to range.min, "max" to range.max, "t" to transition))
                },
                "options" to json("scales" to json("x" to json())),
                "update" to { arg: dynamic -> updates.add(arg) },
            )
            val container = document.createElement("div") as HTMLElement
            container.id = "history-value-chart"
            val canvas = document.createElement("canvas") as HTMLCanvasElement
            container.appendChild(canvas)
            document.body!!.appendChild(container)
            try {
                val session = HistoryChartSession(container, canvas, chart, "sig", ChartRange(1000.0, 3000.0))
                historyChartSessions["history-value-chart"] = session
                session.setViewport(ChartViewport(ChartRange(1000.0, 3000.0), ChartRange(2000.0, 3000.0)))
                detailCalls shouldBe listOf(2000.0 to 3000.0)
                assertHistoryValue(chart.options.scales.x.min, 2000.0)
                assertHistoryValue(chart.options.scales.x.max, 3000.0)
                zoomCalls.size shouldBe 1
                assertHistoryValue(zoomCalls[0]["min"], 2000.0)
                assertHistoryValue(zoomCalls[0]["max"], 3000.0)
                updates shouldBe listOf<Any?>("none")
                val plain: dynamic = json("update" to { arg: dynamic -> updates.add(arg) })
                setChartXRange(plain, ChartRange(5.0, 6.0))
                assertHistoryValue(plain.options.scales.x.min, 5.0)
                assertHistoryValue(plain.options.scales.x.max, 6.0)
                updateChart(plain)
                updateChart(json())
                updates shouldBe listOf<Any?>("none", "none")
            } finally {
                container.remove()
                historyViewportDetailHandler = null
                resetChartState()
            }
        }
    })

private class FakeChartFactory {
    val charts = mutableListOf<dynamic>()
    val destroyCalls = mutableListOf<Int>()
    val visibilityWrites = mutableListOf<Pair<Int, Boolean>>()
    var fail = false

    val factory: (HTMLCanvasElement, dynamic) -> dynamic = { _, config ->
        if (fail) throw IllegalStateException("chart library unavailable")
        val chartIndex = charts.size
        val updates = mutableListOf<Any?>()
        val chart = json(
            "data" to config.data,
            "options" to config.options,
            "update" to { arg: dynamic -> updates.add(arg) },
            "resize" to { },
            "destroy" to { destroyCalls.add(chartIndex) },
            "scales" to json(
                "x" to json(
                    "getValueForPixel" to { px: dynamic -> (px as Number).toDouble() * 2.0 + 1000.0 },
                ),
            ),
            "setDatasetVisibility" to { index: dynamic, visible: dynamic ->
                val dataset = config.data.datasets[index as Int]
                dataset.hidden = !(visible as Boolean)
                visibilityWrites.add((index as Int) to (visible as Boolean))
            },
            "isDatasetVisible" to { index: dynamic -> config.data.datasets[index as Int].hidden != true },
        )
        chart.asDynamic().recordedUpdates = updates
        charts.add(chart)
        chart
    }
}

private fun gestureFixture(): HTMLElement {
    val panel = document.createElement("section") as HTMLElement
    panel.className = "glass-panel"
    panel.innerHTML = """
        <div data-history-controls hidden>
          <button data-history-zoom="in">Zoom in</button>
          <button data-history-zoom="out">Zoom out</button>
          <button data-history-pan="earlier">Earlier</button>
          <button data-history-pan="later">Later</button>
          <button data-history-zoom="reset">Reset</button>
          <button data-history-expand="true">Expand</button>
        </div>
        <div id="history-value-chart"><svg>
          <circle data-time="1000" data-value="10"/>
          <circle data-time="2000" data-gap="true"/>
          <circle data-time="3000" data-value="12"/>
        </svg></div>
        <div class="history-chart-scrubber">
          <input type="range" class="history-chart-scrubber-input" data-chart-id="history-value-chart" disabled>
        </div>
    """.trimIndent()
    document.body!!.appendChild(panel)
    return panel
}

private fun multiFixture(): HTMLElement {
    val panel = document.createElement("section") as HTMLElement
    panel.className = "glass-panel"
    panel.innerHTML = """
        <div data-history-controls hidden>
          <button data-history-view="line">Line</button>
        </div>
        <div id="history-holdings-chart"><svg>
          <circle data-time="1000" data-value="10" data-series="BTC"/>
          <circle data-time="2000" data-value="20" data-series="BTC"/>
          <circle data-time="1000" data-value="5" data-series="ETH"/>
          <circle data-time="2000" data-value="7" data-series="ETH"/>
        </svg></div>
    """.trimIndent()
    document.body!!.appendChild(panel)
    return panel
}

private fun clickHistoryButton(panel: HTMLElement, selector: String) {
    (panel.querySelector(selector) as HTMLButtonElement).click()
}

private fun gestureMouse(type: String, clientX: Double, shiftKey: Boolean = false): MouseEvent = MouseEvent(
    type,
    MouseEventInit(
        clientX = clientX.toInt(),
        clientY = 0,
        button = 0,
        shiftKey = shiftKey,
        bubbles = true,
        cancelable = true,
    ),
)

private fun stubCanvasBounds(canvas: HTMLCanvasElement, width: Double = 600.0, left: Double = 0.0) {
    canvas.asDynamic().getBoundingClientRect = {
        json(
            "left" to left,
            "top" to 0.0,
            "width" to width,
            "height" to 200.0,
            "right" to left + width,
            "bottom" to 200.0,
        )
    }
}

private fun resetChartState() {
    historyChartSessions.clear()
    historyLegendHidden.clear()
    historyViewportDetailHandler = null
}

private fun assertNear(actual: Any?, expected: Double) {
    val value = (actual as Number).toDouble()
    (abs(value - expected) < 1e-6) shouldBe true
}

private fun assertHistoryValue(actual: Any?, expected: Any?) {
    actual shouldBe expected
}
