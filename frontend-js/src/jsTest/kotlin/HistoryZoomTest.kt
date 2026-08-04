package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.ViewText
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import org.w3c.dom.events.Event
import kotlin.js.jsTypeOf
import kotlin.js.json

class HistoryZoomTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "setupZoomButtons invoke chart zoom APIs" {
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.zoomControlsDom("rebalancer-comparison-chart")
            document.body!!.appendChild(container)
            var zoomCalls = 0
            var resetCalls = 0
            window.asDynamic().Chart = { _: dynamic, _: dynamic ->
                jsObject {
                    data = json("datasets" to emptyArray<dynamic>())
                    destroy = {}
                    isDatasetVisible = { _: Int -> true }
                    zoom = { _: Double -> zoomCalls++ }
                    resetZoom = { resetCalls++ }
                }
            }
            registerHistoryGlobals()
            try {
                createOrUpdate(
                    "rebalancer-comparison-chart",
                    createLineChartConfig(emptyArray(), getClonedChartOptions()),
                )
                setupZoomButtons()
                val buttons = document.querySelectorAll(".history-zoom-btn")
                for (i in 0 until buttons.length) {
                    (buttons.item(i) as HTMLElement).click()
                }
                zoomCalls shouldBe 2
                resetCalls shouldBe 1
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "createOrUpdate keeps pending Day · Total only visibility on rebuild" {
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML = """<canvas id="portfolio-value-chart"></canvas>"""
            document.body!!.appendChild(container)
            var lastConfig: dynamic = null
            window.asDynamic().Chart =
                mockChartConstructor { config ->
                    lastConfig = config
                }
            registerHistoryGlobals()
            try {
                val datasets =
                    arrayOf(
                        json(ChartProps.LABEL to ViewText.TOTAL_PORTFOLIO, ChartProps.DATA to emptyArray<dynamic>()),
                        json(ChartProps.LABEL to Asset.BTC, ChartProps.DATA to emptyArray<dynamic>()),
                    )
                createOrUpdate(
                    "portfolio-value-chart",
                    createLineChartConfig(datasets, getClonedChartOptions()),
                )

                val dayTotal = HistoryViewPrefs.builtInViews().first { it.id == "day-total" }
                // pendingPresetVisibility skips snapshotting on-screen toggles so the preset wins on rebuild.
                historyApplyVisibility(dayTotal.visibility)
                createOrUpdate(
                    "portfolio-value-chart",
                    createLineChartConfig(datasets, getClonedChartOptions()),
                )

                (lastConfig.data.datasets[0].hidden as Boolean) shouldBe false
                (lastConfig.data.datasets[1].hidden as Boolean) shouldBe true
                visibilityStates["portfolio-value-chart"]
                    ?.get(ChartProps.DATASET_VISIBILITY_DEFAULT) shouldBe false

                val legendFilter: dynamic = lastConfig.options.plugins.legend.labels.filter
                (jsTypeOf(legendFilter) == "function") shouldBe true
                val chartDataLike =
                    json(
                        "datasets" to lastConfig.data.datasets,
                    )
                (legendFilter(json("datasetIndex" to 0), chartDataLike) as Boolean) shouldBe true
                (legendFilter(json("datasetIndex" to 1), chartDataLike) as Boolean) shouldBe false
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "legendLabelsFilter keeps legend-toggled series and drops config-hidden ones" {
            val visibleDs = json(ChartProps.LABEL to ViewText.TOTAL_PORTFOLIO)
            val configHiddenDs = json(ChartProps.LABEL to Asset.BTC, "hidden" to true)
            val chartDataLike =
                json(
                    "datasets" to arrayOf(visibleDs, configHiddenDs),
                )
            legendLabelsFilter(json("datasetIndex" to 0), chartDataLike) shouldBe true
            legendLabelsFilter(json("datasetIndex" to 1), chartDataLike) shouldBe false
            legendLabelsFilter(json(), chartDataLike) shouldBe true
        }

        "chartScrubberState enables only when zoomed" {
            val full =
                jsObject {
                    getInitialScaleBounds = {
                        json("x" to json("min" to 0.0, "max" to 100.0))
                    }
                    scales = json("x" to json("min" to 0.0, "max" to 100.0))
                }
            chartScrubberState(full, null)?.enabled shouldBe false

            val zoomed =
                jsObject {
                    getInitialScaleBounds = {
                        json("x" to json("min" to 0.0, "max" to 100.0))
                    }
                    scales = json("x" to json("min" to 20.0, "max" to 40.0))
                }
            val state = chartScrubberState(zoomed, null)
            state?.enabled shouldBe true
            // Zoomed window [20,40] inside full [0,100] → scrubber thumb at 25% of panable range.
            state?.position shouldBe 25.0
        }

        "setupChartScrubbers pans x window from slider input" {
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML =
                """
                 <canvas id="portfolio-value-chart"></canvas>
                ${TestDomBuilders.scrubberDom(disabled = true)}
                """.trimIndent()
            document.body!!.appendChild(container)
            var zoomScaleCalls = 0
            var lastMin = 0.0
            var lastMax = 0.0
            window.asDynamic().Chart = { _: dynamic, config: dynamic ->
                jsObject {
                    data = config.data
                    options = config.options
                    destroy = {}
                    isDatasetVisible = { _: Int -> true }
                    getInitialScaleBounds = {
                        json("x" to json("min" to 0.0, "max" to 100.0))
                    }
                    scales = json("x" to json("min" to 20.0, "max" to 40.0))
                    zoomScale = { _: String, range: dynamic, _: String ->
                        zoomScaleCalls++
                        lastMin = range.min.toString().toDouble()
                        lastMax = range.max.toString().toDouble()
                        scales = json("x" to json("min" to lastMin, "max" to lastMax))
                    }
                }
            }
            registerHistoryGlobals()
            try {
                val points =
                    arrayOf(
                        json("x" to 0.0, "y" to 1.0),
                        json("x" to 100.0, "y" to 2.0),
                    )
                createOrUpdate(
                    "portfolio-value-chart",
                    createLineChartConfig(
                        arrayOf(json(ChartProps.LABEL to ViewText.TOTAL_PORTFOLIO, ChartProps.DATA to points)),
                        getClonedChartOptions(),
                    ),
                )
                setupChartScrubbers()
                syncChartScrubber("portfolio-value-chart")
                val scrubber =
                    document.querySelector(".history-chart-scrubber-input") as HTMLInputElement
                scrubber.disabled shouldBe false
                scrubber.value = "50"
                scrubber.dispatchEvent(Event("input"))
                // Prefer chart.zoomScale; writing options.scales.x + update() is ignored once zoom owns the axis.
                zoomScaleCalls shouldBe 1
                lastMin shouldBe 40.0
                lastMax shouldBe 60.0
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "panChartToScrubberPosition falls back to options.scales when zoomScale missing" {
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML =
                """
                 <canvas id="portfolio-value-chart"></canvas>
                ${TestDomBuilders.scrubberDom(disabled = false)}
                """.trimIndent()
            document.body!!.appendChild(container)
            var updateCalls = 0
            var capturedOptions: dynamic = null
            // No zoomScale on the mock — covers the options.scales.min/max + update() fallback path.
            window.asDynamic().Chart = { _: dynamic, config: dynamic ->
                capturedOptions = config.options
                jsObject {
                    data = config.data
                    options = config.options
                    destroy = {}
                    isDatasetVisible = { _: Int -> true }
                    getInitialScaleBounds = {
                        json("x" to json("min" to 0.0, "max" to 100.0))
                    }
                    scales = json("x" to json("min" to 10.0, "max" to 30.0))
                    update = { updateCalls++ }
                }
            }
            registerHistoryGlobals()
            try {
                val points =
                    arrayOf(
                        json("x" to 0.0, "y" to 1.0),
                        json("x" to 100.0, "y" to 2.0),
                    )
                createOrUpdate(
                    "portfolio-value-chart",
                    createLineChartConfig(
                        arrayOf(json(ChartProps.LABEL to ViewText.TOTAL_PORTFOLIO, ChartProps.DATA to points)),
                        getClonedChartOptions(),
                    ),
                )
                setupChartScrubbers()
                val scrubber =
                    document.querySelector(".history-chart-scrubber-input") as HTMLInputElement
                scrubber.value = "0"
                scrubber.dispatchEvent(Event("input"))
                updateCalls shouldBe 1
                capturedOptions.scales.x.min.toString().toDouble() shouldBe 0.0
                capturedOptions.scales.x.max.toString().toDouble() shouldBe 20.0
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "captured zoom completion callback re-enables the scrubber after drag or wheel zoom" {
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML =
                """
                 <canvas id="portfolio-value-chart"></canvas>
                ${TestDomBuilders.scrubberDom(disabled = true)}
                """.trimIndent()
            document.body!!.appendChild(container)
            var capturedOptions: dynamic = null
            window.asDynamic().Chart = { _: dynamic, config: dynamic ->
                capturedOptions = config.options
                jsObject {
                    data = config.data
                    options = config.options
                    canvas = document.getElementById("portfolio-value-chart")
                    destroy = {}
                    isDatasetVisible = { _: Int -> true }
                    getInitialScaleBounds = { json("x" to json("min" to 0.0, "max" to 100.0)) }
                    scales = json("x" to json("min" to 20.0, "max" to 40.0))
                }
            }
            registerHistoryGlobals()
            try {
                val points = arrayOf(
                    json("x" to 0.0, "y" to 1.0),
                    json("x" to 100.0, "y" to 2.0),
                )
                createOrUpdate(
                    "portfolio-value-chart",
                    createLineChartConfig(
                        arrayOf(json(ChartProps.LABEL to ViewText.TOTAL_PORTFOLIO, ChartProps.DATA to points)),
                        getClonedChartOptions(),
                    ),
                )

                val scrubber = document.querySelector(".history-chart-scrubber-input") as HTMLInputElement
                scrubber.disabled = true
                val callback = capturedOptions.plugins.zoom.zoom[ChartProps.ON_ZOOM_COMPLETE]
                callback(
                    json(
                        "chart" to json("canvas" to document.getElementById("portfolio-value-chart")),
                    ),
                )
                scrubber.disabled shouldBe false
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "zoom completion callback is invoked without error" {
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML =
                """
                 <canvas id="portfolio-value-chart"></canvas>
                ${TestDomBuilders.scrubberDom(disabled = true)}
                """.trimIndent()
            document.body!!.appendChild(container)
            var capturedOptions: dynamic = null
            window.asDynamic().Chart = { _: dynamic, config: dynamic ->
                capturedOptions = config.options
                jsObject {
                    data = config.data
                    options = config.options
                    canvas = document.getElementById("portfolio-value-chart")
                    destroy = {}
                    isDatasetVisible = { _: Int -> true }
                    getInitialScaleBounds = { json("x" to json("min" to 0.0, "max" to 100.0)) }
                    scales = json("x" to json("min" to 20.0, "max" to 40.0))
                }
            }
            registerHistoryGlobals()
            try {
                val points = arrayOf(
                    json("x" to 0.0, "y" to 1.0),
                    json("x" to 100.0, "y" to 2.0),
                )
                createOrUpdate(
                    "portfolio-value-chart",
                    createLineChartConfig(
                        arrayOf(json(ChartProps.LABEL to ViewText.TOTAL_PORTFOLIO, ChartProps.DATA to points)),
                        getClonedChartOptions(),
                    ),
                )

                val callback = capturedOptions.plugins.zoom.zoom[ChartProps.ON_ZOOM_COMPLETE]
                // Verify the callback does not throw
                callback(
                    json(
                        "chart" to json("canvas" to document.getElementById("portfolio-value-chart")),
                    ),
                )
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "panChartToScrubberPosition no-ops when not zoomed" {
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML =
                """
                 <canvas id="portfolio-value-chart"></canvas>
                ${TestDomBuilders.scrubberDom(disabled = false)}
                """.trimIndent()
            document.body!!.appendChild(container)
            var zoomScaleCalls = 0
            window.asDynamic().Chart = { _: dynamic, config: dynamic ->
                jsObject {
                    data = config.data
                    options = config.options
                    destroy = {}
                    isDatasetVisible = { _: Int -> true }
                    getInitialScaleBounds = {
                        json("x" to json("min" to 0.0, "max" to 100.0))
                    }
                    scales = json("x" to json("min" to 0.0, "max" to 100.0))
                    zoomScale = { _: String, _: dynamic, _: String -> zoomScaleCalls++ }
                }
            }
            registerHistoryGlobals()
            try {
                val points =
                    arrayOf(
                        json("x" to 0.0, "y" to 1.0),
                        json("x" to 100.0, "y" to 2.0),
                    )
                createOrUpdate(
                    "portfolio-value-chart",
                    createLineChartConfig(
                        arrayOf(json(ChartProps.LABEL to ViewText.TOTAL_PORTFOLIO, ChartProps.DATA to points)),
                        getClonedChartOptions(),
                    ),
                )
                setupChartScrubbers()
                val scrubber =
                    document.querySelector(".history-chart-scrubber-input") as HTMLInputElement
                scrubber.value = "50"
                scrubber.dispatchEvent(Event("input"))
                // Full-range x window → not zoomed; pan must no-op even though scrubber is enabled in DOM.
                zoomScaleCalls shouldBe 0
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "updateTimeUnitForChart sets hour unit when zoomed in to less than a day" {
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML = """<canvas id="portfolio-value-chart"></canvas>"""
            document.body!!.appendChild(container)
            var capturedOptions: dynamic = null
            window.asDynamic().Chart = { _: dynamic, config: dynamic ->
                capturedOptions = config.options
                jsObject {
                    data = config.data
                    options = config.options
                    destroy = {}
                    isDatasetVisible = { _: Int -> true }
                    getInitialScaleBounds = { json("x" to json("min" to 0.0, "max" to 86_400_000.0)) }
                    // Zoomed to 12 hours (43_200_000 ms) — less than ONE_DAY_MS
                    scales = json("x" to json("min" to 10_000_000.0, "max" to 53_200_000.0))
                }
            }
            registerHistoryGlobals()
            try {
                val points = arrayOf(
                    json("x" to 0.0, "y" to 1.0),
                    json("x" to 86_400_000.0, "y" to 2.0),
                )
                createOrUpdate(
                    "portfolio-value-chart",
                    createLineChartConfig(
                        arrayOf(json(ChartProps.LABEL to ViewText.TOTAL_PORTFOLIO, ChartProps.DATA to points)),
                        getClonedChartOptions(),
                    ),
                )

                val chart = charts["portfolio-value-chart"]!!
                // Initially set to day (based on 24h range)
                chart.options.scales.x.time.unit = ChartProps.TIME_UNIT_DAY
                updateTimeUnitForChart(chart)
                // After update, should be hour since span < ONE_DAY_MS
                chart.options.scales.x.time.unit.toString() shouldBe ChartProps.TIME_UNIT_HOUR
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "updateTimeUnitForChart sets day unit when zoomed out to more than a day" {
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML = """<canvas id="portfolio-value-chart"></canvas>"""
            document.body!!.appendChild(container)
            var capturedOptions: dynamic = null
            window.asDynamic().Chart = { _: dynamic, config: dynamic ->
                capturedOptions = config.options
                jsObject {
                    data = config.data
                    options = config.options
                    destroy = {}
                    isDatasetVisible = { _: Int -> true }
                    getInitialScaleBounds = { json("x" to json("min" to 0.0, "max" to 172_800_000.0)) }
                    // Zoomed to 3 days (259_200_000 ms) — more than ONE_DAY_MS
                    scales = json("x" to json("min" to 10_000_000.0, "max" to 269_200_000.0))
                }
            }
            registerHistoryGlobals()
            try {
                val points = arrayOf(
                    json("x" to 0.0, "y" to 1.0),
                    json("x" to 172_800_000.0, "y" to 2.0),
                )
                createOrUpdate(
                    "portfolio-value-chart",
                    createLineChartConfig(
                        arrayOf(json(ChartProps.LABEL to ViewText.TOTAL_PORTFOLIO, ChartProps.DATA to points)),
                        getClonedChartOptions(),
                    ),
                )

                val chart = charts["portfolio-value-chart"]!!
                // Initially set to hour
                chart.options.scales.x.time.unit = ChartProps.TIME_UNIT_HOUR
                updateTimeUnitForChart(chart)
                // After update, should be day since span >= ONE_DAY_MS
                chart.options.scales.x.time.unit.toString() shouldBe ChartProps.TIME_UNIT_DAY
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "zoom completion callback updates time unit based on zoom level" {
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML =
                """
                 <canvas id="portfolio-value-chart"></canvas>
                ${TestDomBuilders.scrubberDom(disabled = true)}
                """.trimIndent()
            document.body!!.appendChild(container)
            var capturedOptions: dynamic = null
            window.asDynamic().Chart = { _: dynamic, config: dynamic ->
                capturedOptions = config.options
                jsObject {
                    data = config.data
                    options = config.options
                    canvas = document.getElementById("portfolio-value-chart")
                    destroy = {}
                    isDatasetVisible = { _: Int -> true }
                    getInitialScaleBounds = { json("x" to json("min" to 0.0, "max" to 86_400_000.0)) }
                    // Zoomed to 6 hours (21_600_000 ms) — less than ONE_DAY_MS
                    scales = json("x" to json("min" to 10_000_000.0, "max" to 31_600_000.0))
                }
            }
            registerHistoryGlobals()
            try {
                val points = arrayOf(
                    json("x" to 0.0, "y" to 1.0),
                    json("x" to 86_400_000.0, "y" to 2.0),
                )
                createOrUpdate(
                    "portfolio-value-chart",
                    createLineChartConfig(
                        arrayOf(json(ChartProps.LABEL to ViewText.TOTAL_PORTFOLIO, ChartProps.DATA to points)),
                        getClonedChartOptions(),
                    ),
                )

                val chart = charts["portfolio-value-chart"]!!
                chart.options.scales.x.time.unit = ChartProps.TIME_UNIT_DAY

                val callback = capturedOptions.plugins.zoom.zoom[ChartProps.ON_ZOOM_COMPLETE]
                callback(
                    json(
                        "chart" to json("canvas" to document.getElementById("portfolio-value-chart")),
                    ),
                )
                // Callback should update time unit to hour
                chart.options.scales.x.time.unit.toString() shouldBe ChartProps.TIME_UNIT_HOUR
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "setupZoomButtons updates time unit after programmatic zoom" {
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.zoomControlsDom("portfolio-value-chart")
            document.body!!.appendChild(container)
            var capturedOptions: dynamic = null
            window.asDynamic().Chart = { _: dynamic, config: dynamic ->
                capturedOptions = config.options
                jsObject {
                    data = config.data
                    options = config.options
                    destroy = {}
                    isDatasetVisible = { _: Int -> true }
                    getInitialScaleBounds = { json("x" to json("min" to 0.0, "max" to 86_400_000.0)) }
                    scales = json("x" to json("min" to 10_000_000.0, "max" to 31_600_000.0))
                    zoom = { _: Double ->
                        // Simulate zoom in: update scales to 6-hour range
                        this.scales = json("x" to json("min" to 10_000_000.0, "max" to 31_600_000.0))
                    }
                    resetZoom = {
                        // Simulate reset zoom: restore full range
                        this.scales = json("x" to json("min" to 0.0, "max" to 86_400_000.0))
                    }
                }
            }
            registerHistoryGlobals()
            try {
                val points = arrayOf(
                    json("x" to 0.0, "y" to 1.0),
                    json("x" to 86_400_000.0, "y" to 2.0),
                )
                createOrUpdate(
                    "portfolio-value-chart",
                    createLineChartConfig(
                        arrayOf(json(ChartProps.LABEL to ViewText.TOTAL_PORTFOLIO, ChartProps.DATA to points)),
                        getClonedChartOptions(),
                    ),
                )
                setupZoomButtons()

                val chart = charts["portfolio-value-chart"]!!
                chart.options.scales.x.time.unit = ChartProps.TIME_UNIT_DAY

                // Click zoom in button
                val buttons = document.querySelectorAll(".history-zoom-btn")
                for (i in 0 until buttons.length) {
                    val btn = buttons.item(i) as HTMLElement
                    val action = btn.getAttribute("data-zoom-action")
                    if (action == "in") {
                        btn.click()
                        break
                    }
                }
                // After zoom in, time unit should be hour (span < ONE_DAY_MS)
                chart.options.scales.x.time.unit.toString() shouldBe ChartProps.TIME_UNIT_HOUR

                // Click reset button
                for (i in 0 until buttons.length) {
                    val btn = buttons.item(i) as HTMLElement
                    val action = btn.getAttribute("data-zoom-action")
                    if (action == "reset") {
                        btn.click()
                        break
                    }
                }
// After reset, time unit should be day (span >= ONE_DAY_MS)
                chart.options.scales.x.time.unit.toString() shouldBe ChartProps.TIME_UNIT_DAY
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "updateTimeUnitForChart sets minute unit when zoomed in to less than an hour" {
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML = """<canvas id="portfolio-value-chart"></canvas>"""
            document.body!!.appendChild(container)
            var capturedOptions: dynamic = null
            window.asDynamic().Chart = { _: dynamic, config: dynamic ->
                capturedOptions = config.options
                jsObject {
                    data = config.data
                    options = config.options
                    destroy = {}
                    isDatasetVisible = { _: Int -> true }
                    getInitialScaleBounds = { json("x" to json("min" to 0.0, "max" to 86_400_000.0)) }
                    scales = json("x" to json("min" to 42_000_000.0, "max" to 43_800_000.0))
                }
            }
            registerHistoryGlobals()
            try {
                val points = arrayOf(
                    json("x" to 0.0, "y" to 1.0),
                    json("x" to 86_400_000.0, "y" to 2.0),
                )
                createOrUpdate(
                    "portfolio-value-chart",
                    createLineChartConfig(
                        arrayOf(json(ChartProps.LABEL to ViewText.TOTAL_PORTFOLIO, ChartProps.DATA to points)),
                        getClonedChartOptions(),
                    ),
                )

                val chart = charts["portfolio-value-chart"]!!
                chart.options.scales.x.time.unit = ChartProps.TIME_UNIT_DAY
                updateTimeUnitForChart(chart)
                chart.options.scales.x.time.unit.toString() shouldBe ChartProps.TIME_UNIT_MINUTE
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "updateTimeUnitForChart sets hour unit at exactly one-hour threshold" {
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML = """<canvas id="portfolio-value-chart"></canvas>"""
            document.body!!.appendChild(container)
            var capturedOptions: dynamic = null
            window.asDynamic().Chart = { _: dynamic, config: dynamic ->
                capturedOptions = config.options
                jsObject {
                    data = config.data
                    options = config.options
                    destroy = {}
                    isDatasetVisible = { _: Int -> true }
                    getInitialScaleBounds = { json("x" to json("min" to 0.0, "max" to 86_400_000.0)) }
                    scales = json("x" to json("min" to 41_400_000.0, "max" to 45_000_000.0))
                }
            }
            registerHistoryGlobals()
            try {
                val points = arrayOf(
                    json("x" to 0.0, "y" to 1.0),
                    json("x" to 86_400_000.0, "y" to 2.0),
                )
                createOrUpdate(
                    "portfolio-value-chart",
                    createLineChartConfig(
                        arrayOf(json(ChartProps.LABEL to ViewText.TOTAL_PORTFOLIO, ChartProps.DATA to points)),
                        getClonedChartOptions(),
                    ),
                )

                val chart = charts["portfolio-value-chart"]!!
                chart.options.scales.x.time.unit = ChartProps.TIME_UNIT_MINUTE
                updateTimeUnitForChart(chart)
                chart.options.scales.x.time.unit.toString() shouldBe ChartProps.TIME_UNIT_HOUR
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "zoom completion callback updates time unit to minute when zoomed sub-hour" {
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML = """
      <canvas id="portfolio-value-chart"></canvas>
      ${TestDomBuilders.scrubberDom(disabled = true)}
            """.trimIndent()
            document.body!!.appendChild(container)
            var capturedOptions: dynamic = null
            window.asDynamic().Chart = { _: dynamic, config: dynamic ->
                capturedOptions = config.options
                jsObject {
                    data = config.data
                    options = config.options
                    canvas = document.getElementById("portfolio-value-chart")
                    destroy = {}
                    isDatasetVisible = { _: Int -> true }
                    getInitialScaleBounds = { json("x" to json("min" to 0.0, "max" to 86_400_000.0)) }
                    scales = json("x" to json("min" to 42_750_000.0, "max" to 43_650_000.0))
                }
            }
            registerHistoryGlobals()
            try {
                val points = arrayOf(
                    json("x" to 0.0, "y" to 1.0),
                    json("x" to 86_400_000.0, "y" to 2.0),
                )
                createOrUpdate(
                    "portfolio-value-chart",
                    createLineChartConfig(
                        arrayOf(json(ChartProps.LABEL to ViewText.TOTAL_PORTFOLIO, ChartProps.DATA to points)),
                        getClonedChartOptions(),
                    ),
                )

                val chart = charts["portfolio-value-chart"]!!
                chart.options.scales.x.time.unit = ChartProps.TIME_UNIT_DAY

                val callback = capturedOptions.plugins.zoom.zoom[ChartProps.ON_ZOOM_COMPLETE]
                callback(
                    json(
                        "chart" to json("canvas" to document.getElementById("portfolio-value-chart")),
                    ),
                )
                chart.options.scales.x.time.unit.toString() shouldBe ChartProps.TIME_UNIT_MINUTE
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "configDataRange returns null for empty datasets" {
            val config = json("data" to json("datasets" to emptyArray<dynamic>()))
            configDataRange(config) shouldBe null
        }

        "configDataRange returns null when datasets missing" {
            val config = json("data" to json())
            configDataRange(config) shouldBe null
        }

        "configDataRange returns null when data missing" {
            val config = json()
            configDataRange(config) shouldBe null
        }

        "configDataRange computes min/max from multiple datasets with points" {
            val points1 = arrayOf(
                json("x" to 10.0, "y" to 1.0),
                json("x" to 50.0, "y" to 2.0),
                json("x" to 30.0, "y" to 3.0),
            )
            val points2 = arrayOf(
                json("x" to 5.0, "y" to 4.0),
                json("x" to 100.0, "y" to 5.0),
            )
            val config = json(
                "data" to json(
                    "datasets" to arrayOf(
                        json("data" to points1),
                        json("data" to points2),
                    ),
                ),
            )
            val range = configDataRange(config)
            (range != null) shouldBe true
            range!!.min shouldBe 5.0
            range.max shouldBe 100.0
        }

        "configDataRange ignores points without x value" {
            val points = arrayOf(
                json("x" to 10.0, "y" to 1.0),
                json("y" to 2.0),
                json("x" to 50.0, "y" to 3.0),
            )
            val config = json(
                "data" to json(
                    "datasets" to arrayOf(json("data" to points)),
                ),
            )
            val range = configDataRange(config)
            (range != null) shouldBe true
            range!!.min shouldBe 10.0
            range.max shouldBe 50.0
        }

        "configDataRange returns null when all points lack x" {
            val points = arrayOf(
                json("y" to 1.0),
                json("y" to 2.0),
            )
            val config = json(
                "data" to json(
                    "datasets" to arrayOf(json("data" to points)),
                ),
            )
            configDataRange(config) shouldBe null
        }

        "syncScrubberFromZoomContext re-enables scrubber and updates time unit" {
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML = """
                <canvas id="portfolio-value-chart"></canvas>
                ${TestDomBuilders.scrubberDom(disabled = true)}
            """.trimIndent()
            document.body!!.appendChild(container)
            var capturedOptions: dynamic = null
            window.asDynamic().Chart = { _: dynamic, config: dynamic ->
                capturedOptions = config.options
                jsObject {
                    data = config.data
                    options = config.options
                    canvas = document.getElementById("portfolio-value-chart")
                    destroy = {}
                    isDatasetVisible = { _: Int -> true }
                    getInitialScaleBounds = { json("x" to json("min" to 0.0, "max" to 86_400_000.0)) }
                    scales = json("x" to json("min" to 10_000_000.0, "max" to 31_600_000.0))
                }
            }
            registerHistoryGlobals()
            try {
                val points = arrayOf(
                    json("x" to 0.0, "y" to 1.0),
                    json("x" to 86_400_000.0, "y" to 2.0),
                )
                createOrUpdate(
                    "portfolio-value-chart",
                    createLineChartConfig(
                        arrayOf(json(ChartProps.LABEL to ViewText.TOTAL_PORTFOLIO, ChartProps.DATA to points)),
                        getClonedChartOptions(),
                    ),
                )

                val scrubber = document.querySelector(".history-chart-scrubber-input") as HTMLInputElement
                scrubber.disabled = true

                val ctx = json(
                    "chart" to json("canvas" to document.getElementById("portfolio-value-chart")),
                )
                syncScrubberFromZoomContext(ctx)

                scrubber.disabled shouldBe false
                val chart = charts["portfolio-value-chart"]!!
                chart.options.scales.x.time.unit.toString() shouldBe ChartProps.TIME_UNIT_HOUR
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "syncScrubberFromZoomContext no-ops when chart id not found" {
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML = """
                <canvas id="other-chart"></canvas>
                ${TestDomBuilders.scrubberDom(disabled = true)}
            """.trimIndent()
            document.body!!.appendChild(container)
            registerHistoryGlobals()
            try {
                val ctx = json(
                    "chart" to json("canvas" to document.getElementById("other-chart")),
                )
                syncScrubberFromZoomContext(ctx)

                val scrubber = document.querySelector(".history-chart-scrubber-input") as HTMLInputElement
                scrubber.disabled shouldBe true
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "syncScrubberFromZoomContext no-ops when chart not registered" {
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML = """
                <canvas id="portfolio-value-chart"></canvas>
                ${TestDomBuilders.scrubberDom(disabled = true)}
            """.trimIndent()
            document.body!!.appendChild(container)
            registerHistoryGlobals()
            try {
                val ctx = json(
                    "chart" to json("canvas" to document.getElementById("portfolio-value-chart")),
                )
                syncScrubberFromZoomContext(ctx)

                val scrubber = document.querySelector(".history-chart-scrubber-input") as HTMLInputElement
                scrubber.disabled shouldBe true
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "syncScrubberFromZoomContext no-ops when ctx missing chart" {
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML = """
                <canvas id="portfolio-value-chart"></canvas>
                ${TestDomBuilders.scrubberDom(disabled = true)}
            """.trimIndent()
            document.body!!.appendChild(container)
            registerHistoryGlobals()
            try {
                val ctx = json()
                syncScrubberFromZoomContext(ctx)

                val scrubber = document.querySelector(".history-chart-scrubber-input") as HTMLInputElement
                scrubber.disabled shouldBe true
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }
    }
}
