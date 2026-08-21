package com.gemini.krakenbot.frontend

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
            val container = document.createElement("div")
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
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.chartsDom()
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            registerHistoryGlobals()
            try {
                val comparison = mockAvailableComparison()
                buildRebalancerComparisonChart(comparison)

                val chartArea = document.getElementById("comparison-chart-content")
                chartArea?.classList?.contains("hidden") shouldBe false

                val unavailable = document.getElementById("comparison-availability-message")
                unavailable?.classList?.contains("visible") shouldBe false

                val deltaEl = document.getElementById("comparison-latest-difference")
                deltaEl?.textContent shouldContain "+$5,000.00"
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "buildRebalancerComparisonChart shows unavailable message and hides chart" {
            val container = document.createElement("div")
            container.innerHTML =
                TestDomBuilders.chartsDom() +
                TestDomBuilders.scrubberDom(
                    canvasId = "rebalancer-comparison-chart",
                    disabled = false,
                    value = "50",
                )
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            registerHistoryGlobals()
            try {
                val comparison = mockUnavailableComparison()
                buildRebalancerComparisonChart(comparison)

                val chartArea = document.getElementById("comparison-chart-content")
                chartArea?.classList?.contains("hidden") shouldBe true

                val deltaEl = document.getElementById("comparison-latest-difference")
                deltaEl?.textContent shouldBe "—"

                val unavailableDiv = document.getElementById("comparison-availability-message")
                unavailableDiv?.classList?.contains("visible") shouldBe true
                unavailableDiv?.textContent shouldContain
                    "Not enough history exists in this range to compare strategies."

                val scrubber = document.querySelector(".history-chart-scrubber-input") as HTMLInputElement
                scrubber.disabled shouldBe true
                scrubber.value shouldBe "0"
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "buildRebalancerComparisonChart fails closed for malformed available payload" {
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.chartsDom()
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            registerHistoryGlobals()
            try {
                val malformed = mockAvailableComparison().copy(latestDifferenceUSD = null)

                buildRebalancerComparisonChart(malformed)

                document.getElementById("comparison-chart-content")
                    ?.classList
                    ?.contains("hidden") shouldBe true
                document.getElementById("comparison-availability-message")
                    ?.classList
                    ?.contains("visible") shouldBe true
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "buildRebalancerComparisonChart rejects malformed branches and shows ESTIMATED confidence" {
            val container = document.createElement("div")
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
                    document.getElementById("comparison-chart-content")
                        ?.classList
                        ?.contains("hidden") shouldBe true
                }

                buildRebalancerComparisonChart(available.copy(confidence = "ESTIMATED"))
                val confidenceBadge = document.getElementById("comparison-confidence-badge")
                confidenceBadge?.classList?.contains("visible") shouldBe true
                confidenceBadge?.textContent shouldBe "Estimated (external balance changes may affect precision)"
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "unavailableReasonText maps all reason strings to text" {
            unavailableReasonText("INSUFFICIENT_SNAPSHOTS") shouldBe
                "Not enough history exists in this range to compare strategies."
            unavailableReasonText("NON_POSITIVE_BASELINE") shouldBe
                "The comparison needs a positive starting portfolio value."
            unavailableReasonText("BASELINE_MISMATCH") shouldBe
                "Starting holdings do not reconcile with the recorded portfolio value."
            unavailableReasonText("MISSING_PRICE") shouldBe
                "A required historical asset price is missing."
            unavailableReasonText("ASSET_UNIVERSE_CHANGED") shouldBe
                "The configured asset set changed during this range."
            unavailableReasonText("UNSUPPORTED_TRADE") shouldBe
                "A recorded trade cannot be reconciled safely."
            unavailableReasonText("UNEXPLAINED_BALANCE_CHANGE") shouldBe
                "A deposit, withdrawal, transfer, or incomplete trade history may exist."
            unavailableReasonText("unknown_reason") shouldBe "Comparison data could not be validated."
            unavailableReasonText(null) shouldBe "Comparison data could not be validated."
        }
    }
}
