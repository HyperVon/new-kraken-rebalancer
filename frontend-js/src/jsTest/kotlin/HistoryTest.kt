package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.api.PortfolioSnapshot
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.model.TradeSourceKeys
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.DataProps
import com.gemini.krakenbot.view.util.HistoryViewIds
import com.gemini.krakenbot.view.util.HtmlEvents
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.HtmlTags
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.dom.*
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.js.jsTypeOf
import kotlin.js.json
import kotlin.test.assertEquals

class HistoryTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "formatUSD renders currency amounts" {
            formatUSD(1234.56) shouldBe "$1,234.56"
            formatUSD(0.0) shouldBe "$0.00"
            formatUSD(-12.3456) shouldBe "-$12.35"
        }

        "dynamicNumber parses ISO timestamps as finite epoch milliseconds" {
            val parsed = dynamicNumber("2023-01-01T00:00:00Z")

            parsed shouldBe 1_672_531_200_000.0
            parsed!!.isFinite() shouldBe true
        }

        "dynamicNumber rejects non-finite numeric values" {
            dynamicNumber("NaN") shouldBe null
            dynamicNumber("Infinity") shouldBe null
            dynamicNumber("-Infinity") shouldBe null
            dynamicNumber(Double.NaN) shouldBe null
            dynamicNumber(Double.POSITIVE_INFINITY) shouldBe null
            dynamicNumber(Double.NEGATIVE_INFINITY) shouldBe null
        }

        "formatPair handles valid and missing symbols" {
            formatPair(mockTradeRecord(symbol = Asset.BTC)) shouldBe "${Asset.BTC}/${Asset.USD}"
            formatPair(mockTradeRecord(symbol = "")) shouldBe ""
            formatPair(null) shouldBe ""
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

        "calculateCumulativeNetCashFlow filters and orders completed trades" {
            val trades =
                listOf(
                    mockTradeRecord(
                        timestamp = "2023-01-01T10:00:00Z",
                        side = OrderSide.BUY.name,
                        usdAmount = "100.0",
                    ),
                    mockTradeRecord(
                        timestamp = "2023-01-01T08:00:00Z",
                        side = OrderSide.SELL.name,
                        usdAmount = "50.0",
                    ),
                    mockTradeRecord(
                        timestamp = "2023-01-01T09:00:00Z",
                        success = false,
                        side = OrderSide.BUY.name,
                        usdAmount = "200.0",
                    ),
                    mockTradeRecord(
                        timestamp = "2023-01-01T11:00:00Z",
                        dryRun = true,
                        side = OrderSide.BUY.name,
                        usdAmount = "300.0",
                    ),
                    mockTradeRecord(
                        timestamp = "2023-01-01T12:00:00Z",
                        side = OrderSide.SELL.name,
                        usdAmount = "80.0",
                    ),
                )

            val result = calculateCumulativeNetCashFlow(trades)
            result.size shouldBe 3
        }

        "calculateCumulativeNetCashFlow skips unknown order sides" {
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

        "renderTradeTable shows nine columns with price fee and slippage" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.tradeTableDom()
            document.body!!.appendChild(container)

            try {
                val trades =
                    listOf(
                        mockTradeRecord(
                            side = OrderSide.BUY.name,
                            price = "50000.0",
                            fee = "13.0",
                            slippagePercent = "0.5",
                            source = TradeSourceKeys.LOCAL_ESTIMATE,
                        ),
                        mockTradeRecord(
                            side = OrderSide.SELL.name,
                            success = false,
                            errorMessage = "Insufficient funds",
                            slippagePercent = null,
                        ),
                    )

                renderTradeTable(trades)
                val tbody = document.getElementById(HtmlIds.TRADE_TABLE_BODY) as HTMLTableSectionElement
                tbody.rows.length shouldBe 2
                val firstRow = tbody.rows.item(0) as HTMLTableRowElement
                firstRow.cells.length shouldBe PrecisionConstants.TRADE_TABLE_COLSPAN
                tbody.innerHTML shouldContain "badge-slippage-adverse"
                tbody.innerHTML shouldContain ViewText.EM_DASH
                tbody.innerHTML shouldContain ViewText.TRADE_FAILED_TITLE_PREFIX + "Insufficient funds"
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "renderTradeTable maps lowercase buy side to buy badge" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.tradeTableDom()
            document.body!!.appendChild(container)

            try {
                renderTradeTable(
                    listOf(
                        mockTradeRecord(side = OrderSide.BUY.apiValue),
                    ),
                )
                val tbody = document.getElementById(HtmlIds.TRADE_TABLE_BODY) as HTMLTableSectionElement
                tbody.innerHTML shouldContain "badge-buy"
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "renderTradeTable keeps sub-cent price and fee precision instead of rounding to zero" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.tradeTableDom()
            document.body!!.appendChild(container)

            try {
                val trades =
                    listOf(
                        mockTradeRecord(
                            side = OrderSide.BUY.name,
                            price = "0.0000753",
                            fee = "0.0033",
                        ),
                    )

                renderTradeTable(trades)
                val tbody = document.getElementById(HtmlIds.TRADE_TABLE_BODY) as HTMLTableSectionElement
                tbody.innerHTML shouldContain "$0.0000753"
                tbody.innerHTML shouldContain "$0.0033"
                tbody.innerHTML shouldContain CssClass.Table.StatusDot.toString()
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "renderTradeTable filters dry runs and displays empty states" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.tradeTableDom()
            document.body!!.appendChild(container)

            try {
                val trades =
                    listOf(
                        mockTradeRecord(
                            timestamp = "2023-01-01",
                            symbol = Asset.BTC,
                            side = OrderSide.BUY.name,
                            volume = "0.1",
                            usdAmount = "2000.0",
                            success = true,
                            dryRun = false,
                        ),
                        mockTradeRecord(
                            timestamp = "2023-01-02",
                            symbol = Asset.ETH,
                            side = OrderSide.SELL.name,
                            volume = "1.0",
                            usdAmount = "1800.0",
                            success = true,
                            dryRun = true,
                        ),
                        mockTradeRecord(
                            timestamp = "2023-01-03",
                            symbol = Asset.LTC,
                            side = OrderSide.BUY.name,
                            volume = "5.0",
                            usdAmount = "350.0",
                            success = false,
                            dryRun = false,
                        ),
                    )

                renderTradeTable(trades)
                val tbody = document.getElementById(HtmlIds.TRADE_TABLE_BODY) as HTMLTableSectionElement
                tbody.rows.length shouldBe 3
                tbody.innerHTML shouldContain "${Asset.BTC}/${Asset.USD}"
                tbody.innerHTML shouldContain "${Asset.ETH}/${Asset.USD}"
                tbody.innerHTML shouldContain "${Asset.LTC}/${Asset.USD}"
                tbody.innerHTML shouldContain "DRY RUN"
                tbody.innerHTML shouldContain "FAILED"

                (document.getElementById(HtmlIds.SHOW_DRY_RUN_CHECKBOX) as HTMLInputElement).checked = false
                renderTradeTable(trades)
                tbody.rows.length shouldBe 2
                tbody.innerHTML shouldContain "${Asset.BTC}/${Asset.USD}"
                tbody.innerHTML shouldContain "${Asset.LTC}/${Asset.USD}"
                tbody.innerHTML shouldNotContain "${Asset.ETH}/${Asset.USD}"

                renderTradeTable(emptyList())
                tbody.rows.length shouldBe 1
                tbody.innerHTML shouldContain "No trades found"
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "updateStats formats each displayed value" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.statsDom()
            document.body!!.appendChild(container)

            try {
                val stats = mockPortfolioStatsRecord()
                updateStats(stats)

                document.getElementById(HtmlIds.STAT_ATH)?.textContent shouldBe "$15,000.50"
                document.getElementById(HtmlIds.STAT_TOTAL_TRADES)?.textContent shouldBe "42"
                document.getElementById(HtmlIds.STAT_TOTAL_VOLUME)?.textContent shouldBe "$1,000,000.00"
                document.getElementById(HtmlIds.STAT_TOTAL_FEES)?.textContent shouldBe "$250.75"
                document.getElementById(HtmlIds.STAT_AVG_FEE_RATE)?.textContent shouldBe "0.26%"
                document.getElementById(HtmlIds.STAT_AVG_SLIPPAGE)?.textContent shouldBe "+0.15%"
            } finally {
                document.body!!.removeChild(container)
            }
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

        "loadAll and checkSyncProgress update history content" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.historyDom()
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            window.asDynamic().fetch =
                mockFetch { url ->
                    when {
                        url.contains("snapshots") ->
                            arrayOf(
                                portfolioSnapshotToDynamic(
                                    mockSnapshotRecord(
                                        assets =
                                        mapOf(
                                            Asset.BTC to
                                                mockSnapshotRecord().assets.getValue(Asset.BTC).copy(
                                                    valueUSD = "100",
                                                    balance = "1",
                                                    currentPercent = "100",
                                                ),
                                        ),
                                    ),
                                ),
                            )
                        url.contains("trades") ->
                            arrayOf(
                                tradeRecordToDynamic(
                                    mockTradeRecord(symbol = Asset.BTC, volume = "1", usdAmount = "100"),
                                ),
                            )
                        url.contains("comparison") -> rebalancerComparisonToDynamic(mockAvailableComparison())
                        url.contains("sync-progress") -> json("seeded" to false, "offset" to "5", "total" to "10")
                        else ->
                            historyStatsToDynamic(
                                mockPortfolioStatsRecord(
                                    allTimeHigh = "100",
                                    totalTradesExecuted = 1L,
                                    totalVolumeTraded = "100",
                                    totalFeesPaid = "1",
                                ),
                            )
                    }
                }
            registerHistoryGlobals()

            try {
                checkSyncProgress().await() shouldBe false
                (document.getElementById(HtmlIds.SYNC_PROGRESS_BAR) as HTMLElement).style.width shouldBe "50%"
                loadAll(TimeRange.TWENTY_FOUR_HOURS.key).await()
                (getClonedChartOptions().scales.x.time.unit as String) shouldBe "hour"
                (window.asDynamic().chartDefaults.scales.x.time.unit == null) shouldBe true
                loadAll(TimeRange.ALL.key).await()
                (getClonedChartOptions().scales.x.time.unit == null) shouldBe true
                (window.asDynamic().chartDefaults.scales.x.time.unit == null) shouldBe true
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "loadAll ignores an older range response that completes after the newest request" {
            resetHistoryUiState()
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.historyDom()
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            val bodyResolvers = mutableMapOf<String, (dynamic) -> Unit>()
            window.asDynamic().fetch = { url: String ->
                val response: dynamic = json()
                response.json = {
                    Promise<dynamic> { resolve: (dynamic) -> Unit, _: (Throwable) -> Unit ->
                        bodyResolvers[url] = resolve
                    }
                }
                Promise.resolve<dynamic>(response)
            }
            registerHistoryGlobals()

            fun resolveRange(range: String, snapshot: PortfolioSnapshot, tradeSymbol: String, totalTrades: Long) {
                bodyResolvers.getValue("${Routes.API_HISTORY_SNAPSHOTS}?range=$range")(
                    arrayOf(portfolioSnapshotToDynamic(snapshot)),
                )
                bodyResolvers.getValue("${Routes.API_HISTORY_TRADES}?range=$range")(
                    arrayOf(tradeRecordToDynamic(mockTradeRecord(symbol = tradeSymbol))),
                )
                bodyResolvers.getValue("${Routes.API_HISTORY_STATS}?range=$range")(
                    historyStatsToDynamic(mockPortfolioStatsRecord(totalTradesExecuted = totalTrades)),
                )
                bodyResolvers.getValue("${Routes.API_HISTORY_COMPARISON}?range=$range")(
                    rebalancerComparisonToDynamic(mockAvailableComparison()),
                )
            }

            try {
                val older = loadAll(TimeRange.TWENTY_FOUR_HOURS.key)
                val newest = loadAll(TimeRange.ALL.key)
                awaitPromiseQueue()

                resolveRange(
                    TimeRange.ALL.key,
                    mockSnapshotRecord(totalValueUSD = "222"),
                    Asset.ETH,
                    totalTrades = 2L,
                )
                newest.await()

                resolveRange(
                    TimeRange.TWENTY_FOUR_HOURS.key,
                    mockSnapshotRecord(totalValueUSD = "111"),
                    Asset.BTC,
                    totalTrades = 1L,
                )
                older.await()

                currentRange shouldBe TimeRange.ALL.key
                document.getElementById(HtmlIds.STAT_TOTAL_TRADES)?.textContent shouldBe "2"
                val tableHtml = document.getElementById(HtmlIds.TRADE_TABLE_BODY)?.innerHTML.orEmpty()
                tableHtml shouldContain "${Asset.ETH}/${Asset.USD}"
                tableHtml shouldNotContain "${Asset.BTC}/${Asset.USD}"
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "loadAll ignores an older range failure after the newest request completes" {
            resetHistoryUiState()
            val bodyResolvers = mutableMapOf<String, (dynamic) -> Unit>()
            val bodyRejectors = mutableMapOf<String, (Throwable) -> Unit>()
            window.asDynamic().fetch = { url: String ->
                val response: dynamic = json()
                response.json = {
                    Promise<dynamic> { resolve: (dynamic) -> Unit, reject: (Throwable) -> Unit ->
                        bodyResolvers[url] = resolve
                        bodyRejectors[url] = reject
                    }
                }
                Promise.resolve<dynamic>(response)
            }

            fun resolveRange(range: String) {
                bodyResolvers.getValue("${Routes.API_HISTORY_SNAPSHOTS}?range=$range")(emptyArray<dynamic>())
                bodyResolvers.getValue("${Routes.API_HISTORY_TRADES}?range=$range")(emptyArray<dynamic>())
                bodyResolvers.getValue("${Routes.API_HISTORY_STATS}?range=$range")(
                    historyStatsToDynamic(mockPortfolioStatsRecord()),
                )
                bodyResolvers.getValue("${Routes.API_HISTORY_COMPARISON}?range=$range")(
                    rebalancerComparisonToDynamic(mockAvailableComparison()),
                )
            }

            try {
                val older = loadAll(TimeRange.TWENTY_FOUR_HOURS.key)
                val newest = loadAll(TimeRange.ALL.key)
                awaitPromiseQueue()

                resolveRange(TimeRange.ALL.key)
                newest.await()

                bodyRejectors.getValue(
                    "${Routes.API_HISTORY_SNAPSHOTS}?range=${TimeRange.TWENTY_FOUR_HOURS.key}",
                )(RuntimeException("obsolete request failed"))
                older.await()

                currentRange shouldBe TimeRange.ALL.key

                val current = loadAll(TimeRange.SEVEN_DAYS.key)
                awaitPromiseQueue()
                bodyRejectors.getValue(
                    "${Routes.API_HISTORY_SNAPSHOTS}?range=${TimeRange.SEVEN_DAYS.key}",
                )(RuntimeException("current request failed"))
                val currentFailure =
                    try {
                        current.await()
                        null
                    } catch (error: Throwable) {
                        error
                    }
                currentFailure?.message shouldBe "current request failed"
            } finally {
                resetHistoryUiState()
            }
        }

        "loadAll keeps the last successful range label when the selected range fails" {
            resetHistoryUiState()
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.historyDom() +
                "<div id=\"${HtmlIds.STAT_ATH_TITLE}\"></div>"
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            window.asDynamic().fetch = mockFetch { url ->
                when {
                    url.contains("snapshots") -> arrayOf(portfolioSnapshotToDynamic(mockSnapshotRecord()))
                    url.contains("trades") -> arrayOf(tradeRecordToDynamic(mockTradeRecord()))
                    url.contains("comparison") -> rebalancerComparisonToDynamic(mockAvailableComparison())
                    else -> historyStatsToDynamic(mockPortfolioStatsRecord(allTimeHigh = "9000"))
                }
            }
            registerHistoryGlobals()

            try {
                loadAll(TimeRange.ALL.key).await()
                document.getElementById(HtmlIds.STAT_ATH_TITLE)?.textContent shouldBe ViewText.HISTORY_ALL_TIME_HIGH

                window.asDynamic().fetch = { url: String ->
                    if (url.contains("snapshots")) {
                        Promise.reject(RuntimeException("range request failed"))
                    } else {
                        val response: dynamic = json()
                        response.json = { Promise.resolve(json()) }
                        Promise.resolve(response)
                    }
                }
                val failed = loadAll(TimeRange.SEVEN_DAYS.key)
                try {
                    failed.await()
                } catch (error: Throwable) {
                    error.message shouldBe "range request failed"
                }

                currentRange shouldBe TimeRange.ALL.key
                document.getElementById(HtmlIds.STAT_ATH_TITLE)?.textContent shouldBe ViewText.HISTORY_ALL_TIME_HIGH
                document.getElementById(HtmlIds.STAT_ATH)?.textContent shouldBe "$9,000.00"
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "loadAll updates all six summary cards for each range, including null slippage" {
            resetHistoryUiState()
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.historyDom() +
                "<div id=\"${HtmlIds.STAT_ATH_TITLE}\"></div>"
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            window.asDynamic().fetch = mockFetch { url ->
                val suffix = if (url.contains("range=${TimeRange.ALL.key}")) "1" else "2"
                when {
                    url.contains("snapshots") -> arrayOf(portfolioSnapshotToDynamic(mockSnapshotRecord()))
                    url.contains("trades") -> arrayOf(tradeRecordToDynamic(mockTradeRecord()))
                    url.contains("comparison") -> rebalancerComparisonToDynamic(mockAvailableComparison())
                    else -> historyStatsToDynamic(
                        mockPortfolioStatsRecord(
                            allTimeHigh = "100$suffix",
                            totalTradesExecuted = if (suffix == "1") 11 else 22,
                            totalVolumeTraded = "200$suffix",
                            totalFeesPaid = "3$suffix",
                            avgFeeRatePercent = "0.0$suffix",
                            avgSlippagePercent = if (suffix == "1") "-0.2" else null,
                        ),
                    )
                }
            }
            registerHistoryGlobals()

            try {
                loadAll(TimeRange.ALL.key).await()
                document.getElementById(HtmlIds.STAT_ATH)?.textContent shouldBe "$1,001.00"
                document.getElementById(HtmlIds.STAT_TOTAL_TRADES)?.textContent shouldBe "11"
                document.getElementById(HtmlIds.STAT_TOTAL_VOLUME)?.textContent shouldBe "$2,001.00"
                document.getElementById(HtmlIds.STAT_TOTAL_FEES)?.textContent shouldBe "$31.00"
                document.getElementById(HtmlIds.STAT_AVG_FEE_RATE)?.textContent shouldBe "0.01%"
                document.getElementById(HtmlIds.STAT_AVG_SLIPPAGE)?.textContent shouldBe "-0.2%"

                loadAll(TimeRange.SEVEN_DAYS.key).await()
                document.getElementById(HtmlIds.STAT_ATH)?.textContent shouldBe "$1,002.00"
                document.getElementById(HtmlIds.STAT_TOTAL_TRADES)?.textContent shouldBe "22"
                document.getElementById(HtmlIds.STAT_TOTAL_VOLUME)?.textContent shouldBe "$2,002.00"
                document.getElementById(HtmlIds.STAT_TOTAL_FEES)?.textContent shouldBe "$32.00"
                document.getElementById(HtmlIds.STAT_AVG_FEE_RATE)?.textContent shouldBe "0.02%"
                document.getElementById(HtmlIds.STAT_AVG_SLIPPAGE)?.textContent shouldBe ViewText.PLACEHOLDER_DASHES
                document.getElementById(HtmlIds.STAT_ATH_TITLE)?.textContent shouldBe ViewText.PERIOD_HIGH
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
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

        "checkSyncProgress hides the banner when history is seeded" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.syncProgressDom()
            document.body!!.appendChild(container)
            window.asDynamic().fetch = mockFetch { json("seeded" to true) }
            try {
                checkSyncProgress().await() shouldBe true
                (document.getElementById(HtmlIds.SYNC_PROGRESS_BANNER) as HTMLElement).style.display shouldBe "none"
            } finally {
                document.body!!.removeChild(container)
            }
        }

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
    }
}
