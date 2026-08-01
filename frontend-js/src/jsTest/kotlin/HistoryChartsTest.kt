package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.api.PortfolioSnapshot
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.HtmlTags
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import kotlin.js.json
import kotlin.test.assertEquals

private const val TEST_CHART = "test-chart"

class HistoryChartsTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "formatUSD renders currency amounts" {
            formatUSD(1234.56) shouldBe "$1,234.56"
            formatUSD(0.0) shouldBe "$0.00"
            formatUSD(-12.3456) shouldBe "-$12.35"
        }

        "getUniqueSymbols filters and sorts symbols" {
            val asset = mockSnapshotRecord().assets.getValue(Asset.BTC)
            val snapshots =
                listOf(
                    mockSnapshotRecord(
                        assets =
                        mapOf(
                            Asset.BTC to asset,
                            Asset.ETH to asset.copy(symbol = Asset.ETH),
                            Asset.USD to asset.copy(symbol = Asset.USD),
                        ),
                    ),
                    mockSnapshotRecord(
                        assets =
                        mapOf(
                            Asset.BTC to asset,
                            Asset.SOL to asset.copy(symbol = Asset.SOL),
                            Asset.USD to asset.copy(symbol = Asset.USD),
                        ),
                    ),
                    mockSnapshotRecord(assets = emptyMap()),
                )

            val symbolsExcludeUsd = getUniqueSymbols(snapshots, excludeUsd = true)
            symbolsExcludeUsd shouldBe listOf(Asset.BTC, Asset.ETH, Asset.SOL)

            val symbolsIncludeUsd = getUniqueSymbols(snapshots, excludeUsd = false)
            symbolsIncludeUsd shouldBe listOf(Asset.BTC, Asset.ETH, Asset.SOL, Asset.USD)
        }

        "mapSnapshotsToPoints retains timestamps and values" {
            val snapshots =
                listOf(
                    mockSnapshotRecord(timestamp = "2023-01-01", totalValueUSD = "100"),
                    mockSnapshotRecord(timestamp = "2023-01-02", totalValueUSD = "110"),
                )

            val points = mapSnapshotsToPoints(snapshots) { snapshot ->
                dynamicNumber(snapshot.totalValueUSD) ?: 0.0
            }
            points.size shouldBe 2
            val p0 = points[0]
            val p0x = p0.x
            assertEquals("2023-01-01", p0x.toString())
        }

        "calculateCumulativeNetCashFlow skips unknown sides, failed, and dry-run trades" {
            val trades =
                listOf(
                    mockTradeRecord(
                        timestamp = "2023-01-01T10:00:00Z",
                        side = OrderSide.SELL.name,
                        usdAmount = "50.0",
                    ),
                    mockTradeRecord(
                        timestamp = "2023-01-01T11:00:00Z",
                        side = "HOLD",
                        usdAmount = "999.0",
                    ),
                    mockTradeRecord(
                        timestamp = "2023-01-01T12:00:00Z",
                        side = OrderSide.BUY.name,
                        usdAmount = "20.0",
                    ),
                    mockTradeRecord(
                        timestamp = "2023-01-01T13:00:00Z",
                        success = false,
                        side = OrderSide.BUY.name,
                        usdAmount = "100.0",
                    ),
                    mockTradeRecord(
                        timestamp = "2023-01-01T14:00:00Z",
                        dryRun = true,
                        side = OrderSide.SELL.name,
                        usdAmount = "200.0",
                    ),
                )

            val result = calculateCumulativeNetCashFlow(trades)
            result.size shouldBe 2
            result[0].y.toString().toDouble() shouldBe 50.0
            result[1].y.toString().toDouble() shouldBe 30.0
        }

        "calculateCumulativeNetAfterFees subtracts fees from signed cash flow" {
            val trades =
                listOf(
                    mockTradeRecord(
                        timestamp = "2023-01-01T08:00:00Z",
                        side = OrderSide.SELL.name,
                        usdAmount = "100.0",
                        fee = "2.6",
                    ),
                    mockTradeRecord(
                        timestamp = "2023-01-01T10:00:00Z",
                        side = OrderSide.BUY.name,
                        usdAmount = "40.0",
                        fee = "1.0",
                    ),
                )

            val result = calculateCumulativeNetAfterFees(trades)
            result.size shouldBe 2
            result[0].y.toString().toDouble().toFixed(1) shouldBe "97.4"
            result[1].y.toString().toDouble().toFixed(1) shouldBe "56.4"
        }

        "registerHistoryGlobals exposes chart defaults" {
            registerHistoryGlobals()
            (window.asDynamic().chartDefaults != null) shouldBe true
        }

        "chart builders create charts and preserve visibility" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.chartsDom()
            document.body!!.appendChild(container)
            val chartConfigs = mutableListOf<dynamic>()
            window.asDynamic().chartConfigs = chartConfigs.toTypedArray()
            window.asDynamic().Chart =
                mockChartConstructor { config ->
                    chartConfigs.add(config)
                    window.asDynamic().chartConfigs = chartConfigs.toTypedArray()
                }

            try {
                registerHistoryGlobals()
                val snapshots =
                    listOf(
                        mockSnapshotRecord(
                            timestamp = "2023-01-01",
                            totalValueUSD = "100",
                            assets =
                            mapOf(
                                Asset.BTC to
                                    PortfolioSnapshot.AssetSnapshot(
                                        symbol = Asset.BTC,
                                        balance = "2",
                                        price = "0",
                                        valueUSD = "60",
                                        targetPercent = "0",
                                        currentPercent = "60",
                                        deviationPercent = "20",
                                        deviationUSD = "0",
                                    ),
                                Asset.USD to
                                    PortfolioSnapshot.AssetSnapshot(
                                        symbol = Asset.USD,
                                        balance = "40",
                                        price = "0",
                                        valueUSD = "40",
                                        targetPercent = "0",
                                        currentPercent = "40",
                                        deviationPercent = "-20",
                                        deviationUSD = "0",
                                    ),
                            ),
                        ),
                        mockSnapshotRecord(
                            timestamp = "2023-01-02",
                            totalValueUSD = "invalid",
                            assets =
                            mapOf(
                                Asset.BTC to
                                    PortfolioSnapshot.AssetSnapshot(
                                        symbol = Asset.BTC,
                                        balance = "3",
                                        price = "0",
                                        valueUSD = "80",
                                        targetPercent = "0",
                                        currentPercent = "80",
                                        deviationPercent = "60",
                                        deviationUSD = "0",
                                    ),
                            ),
                        ),
                    )
                val trades =
                    listOf(
                        mockTradeRecord(
                            timestamp = "2023-01-01",
                            side = OrderSide.BUY.name,
                            usdAmount = "10",
                        ),
                    )

                buildPortfolioValueChart(emptyList())
                buildAssetHoldingsChart(emptyList())
                buildAllocationDriftChart(emptyList())
                buildCumulativeNetCashFlowChart(emptyList())
                buildPortfolioValueChart(snapshots)
                buildAssetHoldingsChart(snapshots)
                buildAllocationDriftChart(snapshots)
                buildCumulativeNetCashFlowChart(trades)
                buildPortfolioValueChart(snapshots)

                // mockChartConstructor defaults isDatasetVisible to index==0 only, so rebuild hides dataset 1.
                (window.asDynamic().chartConfigs.length as Int) shouldBe 5
                val cashFlowConfig = window.asDynamic().chartConfigs[3]
                cashFlowConfig.data.datasets.length as Int shouldBe 2
                val portfolioConfig = window.asDynamic().chartConfigs[0]
                portfolioConfig.data.datasets.length as Int shouldBe 2
                val deviationConfig = window.asDynamic().chartConfigs[2]
                deviationConfig.data.datasets[0].data[0].y.toString().toDouble() shouldBe 20.0
                deviationConfig.data.datasets[1].data[0].y.toString().toDouble() shouldBe -20.0
                (deviationConfig.options.scales.y.beginAtZero as Boolean) shouldBe true
                val updatedPortfolioConfig = window.asDynamic().chartConfigs[4]
                (updatedPortfolioConfig.data.datasets[1].hidden as Boolean) shouldBe true
                createOrUpdate(
                    "missing-chart",
                    createLineChartConfig(emptyArray(), getClonedChartOptions()),
                )
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "empty history data clears charts and disables their scrubbers" {
            resetHistoryUiState()
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML =
                """
                ${TestDomBuilders.chartsDom()}
                ${TestDomBuilders.scrubberDom(HtmlIds.PORTFOLIO_VALUE_CHART, disabled = false, value = "25")}
                ${TestDomBuilders.scrubberDom(HtmlIds.ASSET_HOLDINGS_CHART, disabled = false, value = "25")}
                ${TestDomBuilders.scrubberDom(HtmlIds.ALLOCATION_DRIFT_CHART, disabled = false, value = "25")}
                ${TestDomBuilders.scrubberDom(HtmlIds.CUMULATIVE_NET_CASH_FLOW_CHART, disabled = false, value = "25")}
                """.trimIndent()
            document.body!!.appendChild(container)
            val chartInstances = mutableListOf<dynamic>()
            window.asDynamic().Chart = { _: dynamic, config: dynamic ->
                val instance: dynamic = json()
                instance.data = config.data
                instance.options = config.options
                instance.destroyed = false
                instance.destroy = { instance.destroyed = true }
                instance.isDatasetVisible = { _: Int -> true }
                instance.getInitialScaleBounds = {
                    json("x" to json("min" to 0.0, "max" to 100.0))
                }
                instance.scales = json("x" to json("min" to 25.0, "max" to 75.0))
                chartInstances.add(instance)
                instance
            }
            registerHistoryGlobals()

            try {
                val snapshots = listOf(mockSnapshotRecord())
                val trades = listOf(mockTradeRecord())
                buildPortfolioValueChart(snapshots)
                buildAssetHoldingsChart(snapshots)
                buildAllocationDriftChart(snapshots)
                buildCumulativeNetCashFlowChart(trades)
                chartInstances.size shouldBe 4

                buildPortfolioValueChart(emptyList())
                buildAssetHoldingsChart(emptyList())
                buildAllocationDriftChart(emptyList())
                buildCumulativeNetCashFlowChart(emptyList())

                chartInstances.all { it.destroyed as Boolean } shouldBe true
                val scrubbers = document.querySelectorAll(CssClass.Query.CHART_SCRUBBERS)
                for (index in 0 until scrubbers.length) {
                    val scrubber = scrubbers.item(index) as HTMLInputElement
                    scrubber.disabled shouldBe true
                    scrubber.value shouldBe "0"
                }
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "pointRadiusForCount shrinks and hides dense markers" {
            pointRadiusForCount(1, primary = true) shouldBe 4
            pointRadiusForCount(24, primary = true) shouldBe 4
            pointRadiusForCount(25, primary = true) shouldBe 2
            pointRadiusForCount(48, primary = false) shouldBe 1
            pointRadiusForCount(49, primary = true) shouldBe 0
            pointHoverRadiusForCount(10, primary = true) shouldBe 6
            pointHoverRadiusForCount(30, primary = false) shouldBe 2
            pointHoverRadiusForCount(100, primary = true) shouldBe 0
        }

        "createOrUpdate handles existing chart and visibility states" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML =
                """
                <canvas id="$TEST_CHART"></canvas>
                """.trimIndent()
            document.body!!.appendChild(container)
            TestDomBuilders.setupMockChart(isDatasetVisible = { index -> index == 0 })
            try {
                registerHistoryGlobals()

                createOrUpdate(
                    TEST_CHART,
                    TestDomBuilders.chartConfig(),
                )

                createOrUpdate(
                    TEST_CHART,
                    TestDomBuilders.chartConfig(
                        TestDomBuilders.datasetConfig("A"),
                        TestDomBuilders.datasetConfig("B"),
                    ),
                )

                createOrUpdate(
                    TEST_CHART,
                    TestDomBuilders.chartConfig(
                        TestDomBuilders.datasetConfig(
                            "A",
                            hidden = false,
                        ),
                        TestDomBuilders.datasetConfig("B", hidden = true),
                    ),
                )

                ((window.asDynamic().chartCallCount as Int) >= 2).shouldBeTrue()
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "createOrUpdate does nothing when canvas missing" {
            createOrUpdate("non-existent-canvas", json())
        }

        "chart builders config callbacks cover tooltip and ticks formatting" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.chartsDom()
            document.body!!.appendChild(container)
            TestDomBuilders.setupMockChart()
            try {
                registerHistoryGlobals()
                val snapshots =
                    listOf(
                        mockSnapshotRecord(
                            timestamp = "2023-01-01",
                            totalValueUSD = "100",
                            assets =
                            mapOf(
                                Asset.USD to
                                    PortfolioSnapshot.AssetSnapshot(
                                        symbol = Asset.USD,
                                        balance = "100",
                                        price = "0",
                                        valueUSD = "100",
                                        targetPercent = "0",
                                        currentPercent = "100",
                                        deviationPercent = "5",
                                        deviationUSD = "0",
                                    ),
                            ),
                        ),
                        mockSnapshotRecord(
                            timestamp = "2023-01-02",
                            totalValueUSD = "160",
                            assets =
                            mapOf(
                                Asset.BTC to
                                    PortfolioSnapshot.AssetSnapshot(
                                        symbol = Asset.BTC,
                                        balance = "2",
                                        price = "0",
                                        valueUSD = "60",
                                        targetPercent = "0",
                                        currentPercent = "37.5",
                                        deviationPercent = "-25",
                                        deviationUSD = "0",
                                    ),
                                Asset.USD to
                                    PortfolioSnapshot.AssetSnapshot(
                                        symbol = Asset.USD,
                                        balance = "50",
                                        price = "0",
                                        valueUSD = "100",
                                        targetPercent = "0",
                                        currentPercent = "62.5",
                                        deviationPercent = "25",
                                        deviationUSD = "0",
                                    ),
                            ),
                        ),
                    )
                val trades =
                    listOf(
                        mockTradeRecord(
                            timestamp = "2023-01-01",
                            side = OrderSide.BUY.name,
                            usdAmount = "10",
                        ),
                    )

                buildPortfolioValueChart(snapshots)
                buildAssetHoldingsChart(snapshots)
                buildAllocationDriftChart(snapshots)
                buildCumulativeNetCashFlowChart(trades)

                val portConfig = window.asDynamic().chartConfigs[0]
                val label1 = portConfig.options.plugins.tooltip.callbacks.label
                val mockCtx1 =
                    jsObject {
                        dataset = json("label" to Asset.BTC)
                        parsed = json("y" to 12.3456)
                    }
                label1(mockCtx1).toString() shouldContain Asset.BTC
                val tick1 = portConfig.options.scales.y.ticks.callback
                tick1(12.34, 0, null).toString() shouldContain "$12.34"

                val holdingsConfig = window.asDynamic().chartConfigs[1]
                val label2 = holdingsConfig.options.plugins.tooltip.callbacks.label
                val mockCtx2 =
                    jsObject {
                        dataset = json("label" to Asset.BTC)
                        parsed = json("y" to 12.34)
                        dataIndex = 1
                    }
                label2(mockCtx2).toString() shouldContain "BTC: +12.34%"
                val mockCtx2USD =
                    jsObject {
                        dataset = json("label" to Asset.USD)
                        parsed = json("y" to -50.0)
                        dataIndex = 1
                    }
                label2(mockCtx2USD).toString() shouldContain "USD: -50.00%"
                val tick2 = holdingsConfig.options.scales.y.ticks.callback
                tick2(12.34, 0, null).toString() shouldBe "+12.34%"
                tick2(-5.6, 0, null).toString() shouldBe "-5.6%"

                val driftConfig = window.asDynamic().chartConfigs[2]
                val label3 = driftConfig.options.plugins.tooltip.callbacks.label
                val mockCtx3 =
                    jsObject {
                        dataset = json("label" to Asset.BTC)
                        parsed = json("y" to 12.34)
                    }
                label3(mockCtx3).toString() shouldBe "BTC: +12.34% vs target"
                val tick3 = driftConfig.options.scales.y.ticks.callback
                tick3(12.34, 0, null).toString() shouldBe "+12.34%"
                tick3(-5.6, 0, null).toString() shouldBe "-5.6%"
                (driftConfig.options.scales.y.beginAtZero as Boolean) shouldBe true
                val gridColor3 = driftConfig.options.scales.y.grid.color
                gridColor3(json("tick" to json("value" to 0))).toString() shouldBe ChartProps.COLOR_ZERO_LINE
                gridColor3(json("tick" to json("value" to 1))).toString() shouldBe ChartProps.COLOR_GRID_LINE

                val plConfig = window.asDynamic().chartConfigs[3]
                val tick4 = plConfig.options.scales.y.ticks.callback
                tick4(12.34, 0, null).toString() shouldContain "$12.34"
            } finally {
                document.body!!.removeChild(container)
            }
        }
    }
}
