package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.DataProps
import com.gemini.krakenbot.view.util.HistoryViewIds
import com.gemini.krakenbot.view.util.HtmlEvents
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.HtmlTags
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
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.zoomControlsDom(HtmlIds.REBALANCER_COMPARISON_CHART)
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
                    HtmlIds.REBALANCER_COMPARISON_CHART,
                    createLineChartConfig(emptyArray(), getClonedChartOptions()),
                )
                setupZoomButtons()
                val buttons = document.querySelectorAll(CssClass.Query.ZOOM_BTNS)
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
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = """<canvas id="${HtmlIds.PORTFOLIO_VALUE_CHART}"></canvas>"""
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
                    HtmlIds.PORTFOLIO_VALUE_CHART,
                    createLineChartConfig(datasets, getClonedChartOptions()),
                )

                val dayTotal = HistoryViewPrefs.builtInViews().first { it.id == HistoryViewIds.DAY_TOTAL }
                // pendingPresetVisibility skips snapshotting on-screen toggles so the preset wins on rebuild.
                historyApplyVisibility(dayTotal.visibility)
                createOrUpdate(
                    HtmlIds.PORTFOLIO_VALUE_CHART,
                    createLineChartConfig(datasets, getClonedChartOptions()),
                )

                (lastConfig.data.datasets[0].hidden as Boolean) shouldBe false
                (lastConfig.data.datasets[1].hidden as Boolean) shouldBe true
                visibilityStates[HtmlIds.PORTFOLIO_VALUE_CHART]
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
            val configHiddenDs = json(ChartProps.LABEL to Asset.BTC, DataProps.HIDDEN to true)
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
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML =
                """
                <canvas id="${HtmlIds.PORTFOLIO_VALUE_CHART}"></canvas>
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
                    HtmlIds.PORTFOLIO_VALUE_CHART,
                    createLineChartConfig(
                        arrayOf(json(ChartProps.LABEL to ViewText.TOTAL_PORTFOLIO, ChartProps.DATA to points)),
                        getClonedChartOptions(),
                    ),
                )
                setupChartScrubbers()
                syncChartScrubber(HtmlIds.PORTFOLIO_VALUE_CHART)
                val scrubber =
                    document.querySelector(CssClass.Query.CHART_SCRUBBERS) as HTMLInputElement
                scrubber.disabled shouldBe false
                scrubber.value = "50"
                scrubber.dispatchEvent(Event(HtmlEvents.INPUT))
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
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML =
                """
                <canvas id="${HtmlIds.PORTFOLIO_VALUE_CHART}"></canvas>
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
                    HtmlIds.PORTFOLIO_VALUE_CHART,
                    createLineChartConfig(
                        arrayOf(json(ChartProps.LABEL to ViewText.TOTAL_PORTFOLIO, ChartProps.DATA to points)),
                        getClonedChartOptions(),
                    ),
                )
                setupChartScrubbers()
                val scrubber =
                    document.querySelector(CssClass.Query.CHART_SCRUBBERS) as HTMLInputElement
                scrubber.value = "0"
                scrubber.dispatchEvent(Event(HtmlEvents.INPUT))
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
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML =
                """
                <canvas id="${HtmlIds.PORTFOLIO_VALUE_CHART}"></canvas>
                ${TestDomBuilders.scrubberDom(disabled = true)}
                """.trimIndent()
            document.body!!.appendChild(container)
            var capturedOptions: dynamic = null
            window.asDynamic().Chart = { _: dynamic, config: dynamic ->
                capturedOptions = config.options
                jsObject {
                    data = config.data
                    options = config.options
                    canvas = document.getElementById(HtmlIds.PORTFOLIO_VALUE_CHART)
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
                    HtmlIds.PORTFOLIO_VALUE_CHART,
                    createLineChartConfig(
                        arrayOf(json(ChartProps.LABEL to ViewText.TOTAL_PORTFOLIO, ChartProps.DATA to points)),
                        getClonedChartOptions(),
                    ),
                )

                val scrubber = document.querySelector(CssClass.Query.CHART_SCRUBBERS) as HTMLInputElement
                scrubber.disabled = true
                val callback = capturedOptions.plugins.zoom.zoom[ChartProps.ON_ZOOM_COMPLETE]
                callback(
                    json(
                        "chart" to json("canvas" to document.getElementById(HtmlIds.PORTFOLIO_VALUE_CHART)),
                    ),
                )
                scrubber.disabled shouldBe false
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "panChartToScrubberPosition no-ops when not zoomed" {
            resetHistoryUiState()
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML =
                """
                <canvas id="${HtmlIds.PORTFOLIO_VALUE_CHART}"></canvas>
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
                    HtmlIds.PORTFOLIO_VALUE_CHART,
                    createLineChartConfig(
                        arrayOf(json(ChartProps.LABEL to ViewText.TOTAL_PORTFOLIO, ChartProps.DATA to points)),
                        getClonedChartOptions(),
                    ),
                )
                setupChartScrubbers()
                val scrubber =
                    document.querySelector(CssClass.Query.CHART_SCRUBBERS) as HTMLInputElement
                scrubber.value = "50"
                scrubber.dispatchEvent(Event(HtmlEvents.INPUT))
                // Full-range x window → not zoomed; pan must no-op even though scrubber is enabled in DOM.
                zoomScaleCalls shouldBe 0
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }
    }
}
