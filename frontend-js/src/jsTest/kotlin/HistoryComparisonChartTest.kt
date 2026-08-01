package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.HtmlTags
import com.gemini.krakenbot.view.util.ViewText
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import kotlin.js.json

class HistoryComparisonChartTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "buildRebalancerComparisonChart tooltip and tick callbacks format values" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.chartsDom()
            document.body!!.appendChild(container)
            var capturedConfig: dynamic = null
            window.asDynamic().Chart = { _: dynamic, config: dynamic ->
                capturedConfig = config
                json()
            }
            registerHistoryGlobals()
            try {
                val comparison = mockAvailableComparison()
                buildRebalancerComparisonChart(comparison)

                val labelFn: dynamic = capturedConfig.options.plugins.tooltip.callbacks.label
                val ctx = json("dataset" to json("label" to "Rebalancer"), "parsed" to json("y" to "110000.00"))
                val label = labelFn(ctx).unsafeCast<String>()
                label shouldContain "Rebalancer"
                label shouldContain "$110,000.00"

                val footerFn: dynamic = capturedConfig.options.plugins.tooltip.callbacks.footer
                val diffItems = arrayOf(json("dataIndex" to 1), json("dataIndex" to 1))
                val footer = footerFn(diffItems).unsafeCast<String>()
                footer shouldContain "+"
                footer shouldContain "$5,000.00"

                val ticksCb: dynamic = capturedConfig.options.scales.y.ticks.callback
                val formatted = ticksCb(1234.5, null, null).unsafeCast<String>()
                formatted shouldBe "$1,234.50"
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "buildRebalancerComparisonChart renders available comparison with datasets and delta" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.chartsDom()
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            registerHistoryGlobals()
            try {
                val comparison = mockAvailableComparison()
                buildRebalancerComparisonChart(comparison)

                val chartArea = document.getElementById(HtmlIds.COMPARISON_CHART_CONTENT)
                chartArea?.classList?.contains("hidden") shouldBe false

                val unavailable = document.getElementById(HtmlIds.COMPARISON_AVAILABILITY_MESSAGE)
                unavailable?.classList?.contains("visible") shouldBe false

                val deltaEl = document.getElementById(HtmlIds.COMPARISON_LATEST_DIFFERENCE)
                deltaEl?.textContent shouldContain "+$5,000.00"
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "buildRebalancerComparisonChart shows unavailable message and hides chart" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML =
                TestDomBuilders.chartsDom() +
                TestDomBuilders.scrubberDom(
                    canvasId = HtmlIds.REBALANCER_COMPARISON_CHART,
                    disabled = false,
                    value = "50",
                )
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            registerHistoryGlobals()
            try {
                val comparison = mockUnavailableComparison()
                buildRebalancerComparisonChart(comparison)

                val chartArea = document.getElementById(HtmlIds.COMPARISON_CHART_CONTENT)
                chartArea?.classList?.contains("hidden") shouldBe true

                val deltaEl = document.getElementById(HtmlIds.COMPARISON_LATEST_DIFFERENCE)
                deltaEl?.textContent shouldBe ViewText.EM_DASH

                val unavailableDiv = document.getElementById(HtmlIds.COMPARISON_AVAILABILITY_MESSAGE)
                unavailableDiv?.classList?.contains("visible") shouldBe true
                unavailableDiv?.textContent shouldContain ViewText.UNAVAILABLE_INSUFFICIENT_SNAPSHOTS

                val scrubber = document.querySelector(".${CssClass.History.ChartScrubberInput}") as HTMLInputElement
                scrubber.disabled shouldBe true
                scrubber.value shouldBe "0"
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "buildRebalancerComparisonChart fails closed for malformed available payload" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.chartsDom()
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            registerHistoryGlobals()
            try {
                val malformed = mockAvailableComparison().copy(latestDifferenceUSD = null)

                buildRebalancerComparisonChart(malformed)

                document.getElementById(HtmlIds.COMPARISON_CHART_CONTENT)
                    ?.classList
                    ?.contains("hidden") shouldBe true
                document.getElementById(HtmlIds.COMPARISON_AVAILABILITY_MESSAGE)
                    ?.classList
                    ?.contains("visible") shouldBe true
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "buildRebalancerComparisonChart rejects malformed branches and shows ESTIMATED confidence" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.chartsDom()
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            registerHistoryGlobals()
            try {
                val available = mockAvailableComparison()
                val baseline = available.points.first()
                val malformed = listOf(
                    available.copy(confidence = "UNKNOWN"),
                    available.copy(points = listOf(baseline)),
                    available.copy(baselineTimestamp = null),
                    available.copy(points = available.points.reversed()),
                    available.copy(
                        points = listOf(baseline.copy(differenceUSD = "1.0"), available.points[1]),
                    ),
                    available.copy(
                        points = listOf(baseline, available.points[1].copy(rebalancerValueUSD = "bad")),
                    ),
                )
                malformed.forEach { comparison ->
                    buildRebalancerComparisonChart(comparison)
                    document.getElementById(HtmlIds.COMPARISON_CHART_CONTENT)
                        ?.classList
                        ?.contains("hidden") shouldBe true
                }

                buildRebalancerComparisonChart(available.copy(confidence = "ESTIMATED"))
                val confidenceBadge = document.getElementById(HtmlIds.COMPARISON_CONFIDENCE_BADGE)
                confidenceBadge?.classList?.contains("visible") shouldBe true
                confidenceBadge?.textContent shouldBe ViewText.COMPARISON_CONFIDENCE_ESTIMATED
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "unavailableReasonText maps all reason strings to text" {
            unavailableReasonText("INSUFFICIENT_SNAPSHOTS") shouldBe ViewText.UNAVAILABLE_INSUFFICIENT_SNAPSHOTS
            unavailableReasonText("NON_POSITIVE_BASELINE") shouldBe ViewText.UNAVAILABLE_NON_POSITIVE_BASELINE
            unavailableReasonText("BASELINE_MISMATCH") shouldBe ViewText.UNAVAILABLE_BASELINE_MISMATCH
            unavailableReasonText("MISSING_PRICE") shouldBe ViewText.UNAVAILABLE_MISSING_PRICE
            unavailableReasonText("ASSET_UNIVERSE_CHANGED") shouldBe ViewText.UNAVAILABLE_ASSET_UNIVERSE_CHANGED
            unavailableReasonText("UNSUPPORTED_TRADE") shouldBe ViewText.UNAVAILABLE_UNSUPPORTED_TRADE
            unavailableReasonText("UNEXPLAINED_BALANCE_CHANGE") shouldBe ViewText.UNAVAILABLE_UNEXPLAINED_BALANCE_CHANGE
            unavailableReasonText("unknown_reason") shouldBe ViewText.UNAVAILABLE_INVALID_RESPONSE
            unavailableReasonText(null) shouldBe ViewText.UNAVAILABLE_INVALID_RESPONSE
        }
    }
}
