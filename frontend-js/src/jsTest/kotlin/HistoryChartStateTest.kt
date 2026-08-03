package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.view.util.ChartProps
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.w3c.dom.HTMLElement

class HistoryChartStateTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "historyCurrentRange defaults to 30d and reflects syncTimeRangeButtons" {
            resetHistoryUiState()
            historyCurrentRange() shouldBe TimeRange.THIRTY_DAYS.key

            val selector = document.createElement("div")
            selector.innerHTML =
                """
                <div class="time-range-selector">
                    <button class="time-range-btn"
                      data-range="24h">24h</button>
                    <button class="time-range-btn"
                      data-range="7d">7d</button>
                </div>
                """.trimIndent()
            document.body!!.appendChild(selector)

            try {
                syncTimeRangeButtons(TimeRange.SEVEN_DAYS.key)
                historyCurrentRange() shouldBe TimeRange.SEVEN_DAYS.key
                val sevenDayButton =
                    selector.querySelector("[data-range=\"7d\"]") as HTMLElement
                val twentyFourHourButton =
                    selector.querySelector("[data-range=\"24h\"]") as HTMLElement
                sevenDayButton.classList.contains("active") shouldBe true
                twentyFourHourButton.classList.contains("active") shouldBe false
            } finally {
                document.body!!.removeChild(selector)
                resetHistoryUiState()
            }
        }

        "historyCaptureVisibility snapshots per-chart label visibility" {
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML = "<canvas id=\"$TEST_CHART\"></canvas>"
            document.body!!.appendChild(container)

            try {
                // Only dataset index 0 is visible, mirroring a user-hidden series.
                TestDomBuilders.setupMockChart(isDatasetVisible = { index -> index == 0 })
                createOrUpdate(
                    TEST_CHART,
                    TestDomBuilders.chartConfig(
                        TestDomBuilders.datasetConfig("A"),
                        TestDomBuilders.datasetConfig("B"),
                    ),
                )

                val captured = historyCaptureVisibility()
                captured[TEST_CHART] shouldBe mapOf("A" to true, "B" to false)
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "historyCaptureVisibility skips null chart instances" {
            resetHistoryUiState()
            charts[TEST_CHART] = null
            historyCaptureVisibility()[TEST_CHART].shouldBe(null)
            resetHistoryUiState()
        }

        "historyRollbackPresetVisibility is a no-op without a backup" {
            resetHistoryUiState()
            visibilityStates["portfolio-value-chart"] = mutableMapOf(
                ChartProps.DATASET_VISIBILITY_DEFAULT to true,
                Asset.BTC to false,
            )
            historyRollbackPresetVisibility()
            visibilityStates["portfolio-value-chart"] shouldBe mapOf(
                ChartProps.DATASET_VISIBILITY_DEFAULT to true,
                Asset.BTC to false,
            )
            resetHistoryUiState()
        }

        "historyRollbackPresetVisibility restores the pre-preset visibility" {
            resetHistoryUiState()
            val before =
                mapOf(
                    ChartProps.DATASET_VISIBILITY_DEFAULT to true,
                    Asset.BTC to false,
                )
            visibilityStates["portfolio-value-chart"] = before.toMutableMap()

            val presetVisibility =
                mapOf(
                    "portfolio-value-chart" to mapOf(
                        ChartProps.DATASET_VISIBILITY_DEFAULT to false,
                        Asset.BTC to true,
                    ),
                )
            historyApplyVisibility(presetVisibility)
            visibilityStates["portfolio-value-chart"] shouldBe
                presetVisibility.getValue("portfolio-value-chart")

            historyRollbackPresetVisibility()
            visibilityStates["portfolio-value-chart"] shouldBe before
            resetHistoryUiState()
        }
    }

    private companion object {
        const val TEST_CHART = "test-chart-state"
    }
}
