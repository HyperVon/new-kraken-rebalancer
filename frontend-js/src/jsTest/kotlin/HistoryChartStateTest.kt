package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.HtmlTags
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document

class HistoryChartStateTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "historyCurrentRange defaults to 30d and reflects syncTimeRangeButtons" {
            resetHistoryUiState()
            historyCurrentRange() shouldBe TimeRange.THIRTY_DAYS.key

            val selector = document.createElement(HtmlTags.DIV)
            selector.innerHTML =
                """
                <div class="${CssClass.History.TimeRangeSelector}">
                    <button class="${CssClass.History.TimeRangeBtn}"
                      ${HtmlAttrs.DATA_RANGE}="24h">24h</button>
                    <button class="${CssClass.History.TimeRangeBtn}"
                      ${HtmlAttrs.DATA_RANGE}="7d">7d</button>
                </div>
                """.trimIndent()
            document.body!!.appendChild(selector)

            try {
                syncTimeRangeButtons(TimeRange.SEVEN_DAYS.key)
                historyCurrentRange() shouldBe TimeRange.SEVEN_DAYS.key
            } finally {
                document.body!!.removeChild(selector)
                resetHistoryUiState()
            }
        }

        "historyCaptureVisibility snapshots per-chart label visibility" {
            resetHistoryUiState()
            val container = document.createElement(HtmlTags.DIV)
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
            visibilityStates[HtmlIds.PORTFOLIO_VALUE_CHART] = mutableMapOf(
                ChartProps.DATASET_VISIBILITY_DEFAULT to true,
                Asset.BTC to false,
            )
            historyRollbackPresetVisibility()
            visibilityStates[HtmlIds.PORTFOLIO_VALUE_CHART] shouldBe mapOf(
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
            visibilityStates[HtmlIds.PORTFOLIO_VALUE_CHART] = before.toMutableMap()

            val presetVisibility =
                mapOf(
                    HtmlIds.PORTFOLIO_VALUE_CHART to mapOf(
                        ChartProps.DATASET_VISIBILITY_DEFAULT to false,
                        Asset.BTC to true,
                    ),
                )
            historyApplyVisibility(presetVisibility)
            visibilityStates[HtmlIds.PORTFOLIO_VALUE_CHART] shouldBe
                presetVisibility.getValue(HtmlIds.PORTFOLIO_VALUE_CHART)

            historyRollbackPresetVisibility()
            visibilityStates[HtmlIds.PORTFOLIO_VALUE_CHART] shouldBe before
            resetHistoryUiState()
        }
    }

    private companion object {
        const val TEST_CHART = "test-chart-state"
    }
}
