package com.gemini.krakenbot.frontend

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

        "formatPair handles valid and missing symbols" {
            val trade1: dynamic = mockTradeRecord(symbol = Asset.BTC)
            formatPair(trade1.unsafeCast<JsTradeRecord>()) shouldBe "${Asset.BTC}/${Asset.USD}"

            val trade2: dynamic = mockTradeRecord(symbol = null)
            formatPair(trade2.unsafeCast<JsTradeRecord>()) shouldBe ""

            val trade3: dynamic = json()
            formatPair(trade3.unsafeCast<JsTradeRecord>()) shouldBe ""

            formatPair(null) shouldBe ""
        }

        "getUniqueSymbols filters and sorts symbols" {
            val snapshots =
                arrayOf(
                    mockSnapshotRecord(assets = json(Asset.BTC to json(), Asset.ETH to json(), Asset.USD to json())),
                    mockSnapshotRecord(assets = json(Asset.BTC to json(), Asset.SOL to json(), Asset.USD to json())),
                    mockSnapshotRecord(assets = null),
                )

            val symbolsExcludeUsd = getUniqueSymbols(snapshots, excludeUsd = true)
            symbolsExcludeUsd shouldBe listOf(Asset.BTC, Asset.ETH, Asset.SOL)

            val symbolsIncludeUsd = getUniqueSymbols(snapshots, excludeUsd = false)
            symbolsIncludeUsd shouldBe listOf(Asset.BTC, Asset.ETH, Asset.SOL, Asset.USD)
        }

        "mapSnapshotsToPoints retains timestamps and values" {
            val snapshots =
                arrayOf(
                    json("timestamp" to "2023-01-01", "value" to 100.0),
                    json("timestamp" to "2023-01-02", "value" to 110.0),
                )

            val points = mapSnapshotsToPoints(snapshots) { it.value.toString().toDouble() }
            points.size shouldBe 2
            val p0 = points[0]
            val p0x = p0.x
            assertEquals("2023-01-01", p0x.toString())
        }

        "calculateCumulativeNetCashFlow filters and orders completed trades" {
            val trades =
                arrayOf(
                    TestDomBuilders.tradeJson(
                        timestamp = "2023-01-01T10:00:00Z",
                        side = OrderSide.BUY.name,
                        usdAmount = 100.0,
                    ),
                    TestDomBuilders.tradeJson(
                        timestamp = "2023-01-01T08:00:00Z",
                        side = OrderSide.SELL.name,
                        usdAmount = 50.0,
                    ),
                    TestDomBuilders.tradeJson(
                        timestamp = "2023-01-01T09:00:00Z",
                        success = false,
                        side = OrderSide.BUY.name,
                        usdAmount = 200.0,
                    ),
                    TestDomBuilders.tradeJson(
                        timestamp = "2023-01-01T11:00:00Z",
                        dryRun = true,
                        side = OrderSide.BUY.name,
                        usdAmount = 300.0,
                    ),
                    TestDomBuilders.tradeJson(
                        timestamp = "2023-01-01T12:00:00Z",
                        side = OrderSide.SELL.name,
                        usdAmount = 80.0,
                    ),
                )

            val result = calculateCumulativeNetCashFlow(trades.unsafeCast<Array<JsTradeRecord>>())
            result.size shouldBe 3
        }

        "calculateCumulativeNetCashFlow skips unknown order sides" {
            val trades =
                arrayOf(
                    TestDomBuilders.tradeJson(
                        timestamp = "2023-01-01T10:00:00Z",
                        side = OrderSide.SELL.name,
                        usdAmount = 50.0,
                    ),
                    TestDomBuilders.tradeJson(
                        timestamp = "2023-01-01T11:00:00Z",
                        side = "HOLD",
                        usdAmount = 999.0,
                    ),
                    TestDomBuilders.tradeJson(
                        timestamp = "2023-01-01T12:00:00Z",
                        side = OrderSide.BUY.name,
                        usdAmount = 20.0,
                    ),
                )

            val result = calculateCumulativeNetCashFlow(trades.unsafeCast<Array<JsTradeRecord>>())
            result.size shouldBe 2
            result[0].y.toString().toDouble() shouldBe 50.0
            result[1].y.toString().toDouble() shouldBe 30.0
        }

        "calculateCumulativeNetAfterFees subtracts fees from signed cash flow" {
            val trades =
                arrayOf(
                    TestDomBuilders.tradeJson(
                        timestamp = "2023-01-01T08:00:00Z",
                        side = OrderSide.SELL.name,
                        usdAmount = 100.0,
                        fee = 2.6,
                    ),
                    TestDomBuilders.tradeJson(
                        timestamp = "2023-01-01T10:00:00Z",
                        side = OrderSide.BUY.name,
                        usdAmount = 40.0,
                        fee = 1.0,
                    ),
                )

            val result = calculateCumulativeNetAfterFees(trades.unsafeCast<Array<JsTradeRecord>>())
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
                    arrayOf(
                        TestDomBuilders.tradeJson(
                            side = OrderSide.BUY.name,
                            price = 50000.0,
                            fee = 13.0,
                            slippagePercent = 0.5,
                            source = TradeSourceKeys.LOCAL_ESTIMATE,
                        ),
                        TestDomBuilders.tradeJson(
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
                    arrayOf(
                        TestDomBuilders.tradeJson(side = OrderSide.BUY.apiValue),
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
                    arrayOf(
                        TestDomBuilders.tradeJson(
                            side = OrderSide.BUY.name,
                            price = 0.0000753,
                            fee = 0.0033,
                        ),
                    )

                renderTradeTable(trades)
                val tbody = document.getElementById(HtmlIds.TRADE_TABLE_BODY) as HTMLTableSectionElement
                tbody.innerHTML shouldContain "$0.0000753"
                tbody.innerHTML shouldContain "$0.0033"
                tbody.innerHTML shouldContain "aria-label=\"${ViewText.STATUS_SUCCESS}\""
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
                    arrayOf(
                        TestDomBuilders.tradeJson(
                            timestamp = "2023-01-01",
                            symbol = Asset.BTC,
                            side = OrderSide.BUY.name,
                            volume = 0.1,
                            usdAmount = 2000.0,
                            success = true,
                            dryRun = false,
                        ),
                        TestDomBuilders.tradeJson(
                            timestamp = "2023-01-02",
                            symbol = Asset.ETH,
                            side = OrderSide.SELL.name,
                            volume = 1.0,
                            usdAmount = 1800.0,
                            success = true,
                            dryRun = true,
                        ),
                        TestDomBuilders.tradeJson(
                            timestamp = "2023-01-03",
                            symbol = Asset.LTC,
                            side = OrderSide.BUY.name,
                            volume = 5.0,
                            usdAmount = 350.0,
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

                renderTradeTable(emptyArray())
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
                    arrayOf(
                        json(
                            "timestamp" to "2023-01-01",
                            "totalValueUSD" to 100,
                            "assets" to
                                json(
                                    Asset.BTC to
                                        json(
                                            "valueUSD" to 60,
                                            "balance" to 2,
                                            "currentPercent" to 60,
                                            "deviationPercent" to 20,
                                        ),
                                    Asset.USD to
                                        json(
                                            "valueUSD" to 40,
                                            "balance" to 40,
                                            "currentPercent" to 40,
                                            "deviationPercent" to -20,
                                        ),
                                ),
                        ),
                        json(
                            "timestamp" to "2023-01-02",
                            "totalValueUSD" to "invalid",
                            "assets" to
                                json(
                                    Asset.BTC to
                                        json(
                                            "valueUSD" to 80,
                                            "balance" to 3,
                                            "currentPercent" to 80,
                                            "deviationPercent" to 60,
                                        ),
                                ),
                        ),
                    )
                val trades =
                    arrayOf(
                        json(
                            "timestamp" to "2023-01-01",
                            "success" to true,
                            "dryRun" to false,
                            "side" to OrderSide.BUY.name,
                            "usdAmount" to 10,
                        ),
                    )

                buildPortfolioValueChart(emptyArray())
                buildAssetHoldingsChart(emptyArray())
                buildAllocationDriftChart(emptyArray())
                buildCumulativeNetCashFlowChart(emptyArray())
                buildPortfolioValueChart(snapshots)
                buildAssetHoldingsChart(snapshots)
                buildAllocationDriftChart(snapshots)
                buildCumulativeNetCashFlowChart(trades)
                buildPortfolioValueChart(snapshots)

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
                                mockSnapshotRecord(
                                    assets =
                                    json(
                                        Asset.BTC to
                                            json("valueUSD" to 100, "balance" to 1, "currentPercent" to 100),
                                    ),
                                ),
                            )
                        url.contains("trades") ->
                            arrayOf(
                                mockTradeRecord(symbol = Asset.BTC, volume = 1, usdAmount = 100),
                            )
                        url.contains("sync-progress") -> json("seeded" to false, "offset" to 5, "total" to 10)
                        else ->
                            mockPortfolioStatsRecord(
                                allTimeHigh = 100,
                                totalTradesExecuted = 1,
                                totalVolumeTraded = 100,
                                totalFeesPaid = 1,
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

        "HistoryViewPrefs serializes and round-trips store JSON" {
            localStorage.removeItem(ViewText.HISTORY_VIEWS_STORAGE_KEY)
            val store =
                HistoryViewsStore(
                    defaultId = HistoryViewIds.DAY_TOTAL,
                    views =
                    HistoryViewPrefs.builtInViews() +
                        HistoryViewDef(
                            id = "user-1",
                            name = "Custom",
                            builtIn = false,
                            range = TimeRange.SEVEN_DAYS.key,
                            showDryRun = false,
                            visibility =
                            mapOf(
                                HtmlIds.PORTFOLIO_VALUE_CHART to
                                    mapOf(ViewText.TOTAL_PORTFOLIO to true),
                            ),
                        ),
                )
            HistoryViewPrefs.saveStore(store)
            val loaded = HistoryViewPrefs.loadStore()
            loaded.defaultId shouldBe HistoryViewIds.DAY_TOTAL
            loaded.views.any { it.id == "user-1" && it.name == "Custom" && !it.showDryRun } shouldBe true
            loaded.views.count { it.builtIn } shouldBe 4
            localStorage.removeItem(ViewText.HISTORY_VIEWS_STORAGE_KEY)
        }

        "applyView seeds visibilityStates before loadAll" {
            localStorage.removeItem(ViewText.HISTORY_VIEWS_STORAGE_KEY)
            resetHistoryUiState()
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.historyViewsDom()
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            window.asDynamic().fetch =
                mockFetch { url ->
                    when {
                        url.contains("snapshots") ->
                            arrayOf(
                                mockSnapshotRecord(
                                    assets =
                                    json(
                                        Asset.BTC to
                                            json("valueUSD" to 100, "balance" to 1, "currentPercent" to 100),
                                    ),
                                ),
                            )
                        url.contains("trades") -> emptyArray<dynamic>()
                        url.contains("sync-progress") -> json("seeded" to true)
                        else ->
                            mockPortfolioStatsRecord(
                                allTimeHigh = 100,
                                totalTradesExecuted = 0,
                                totalVolumeTraded = 0,
                                totalFeesPaid = 0,
                            )
                    }
                }
            registerHistoryGlobals()
            try {
                val dayTotal = HistoryViewPrefs.builtInViews().first { it.id == HistoryViewIds.DAY_TOTAL }
                historyApplyVisibility(dayTotal.visibility)
                visibilityStates[HtmlIds.PORTFOLIO_VALUE_CHART]?.get(ChartProps.DATASET_VISIBILITY_DEFAULT) shouldBe
                    false
                visibilityStates[HtmlIds.PORTFOLIO_VALUE_CHART]?.get(ViewText.TOTAL_PORTFOLIO) shouldBe true

                HistoryViewPrefs.applyView(HistoryViewIds.DAY_TOTAL).await()
                currentRange shouldBe TimeRange.TWENTY_FOUR_HOURS.key
                (document.getElementById(HtmlIds.SHOW_DRY_RUN_CHECKBOX) as HTMLInputElement).checked shouldBe true

                HistoryViewPrefs.applyView(HistoryViewIds.MONTH_NET_CASH_FLOW).await()
                currentRange shouldBe TimeRange.THIRTY_DAYS.key
                (document.getElementById(HtmlIds.SHOW_DRY_RUN_CHECKBOX) as HTMLInputElement).checked shouldBe false

                HistoryViewPrefs.markCurrentViewModified()
                syncTimeRangeButtons(TimeRange.SEVEN_DAYS.key)
                loadHistoryAfterSync().await()
                currentRange shouldBe TimeRange.SEVEN_DAYS.key
                val select = document.getElementById(HtmlIds.HISTORY_VIEWS_SELECT) as HTMLSelectElement
                select.value shouldBe ""
                select.selectedOptions.item(0)?.textContent shouldBe ViewText.HISTORY_VIEW_UNSAVED
                (document.getElementById(HtmlIds.HISTORY_SET_DEFAULT_BTN) as HTMLButtonElement).disabled shouldBe true
                (document.getElementById(HtmlIds.HISTORY_DELETE_VIEW_BTN) as HTMLButtonElement).disabled shouldBe true

                HistoryViewPrefs.resetInteractionState()
                loadHistoryAfterSync().await()
                currentRange shouldBe TimeRange.THIRTY_DAYS.key
            } finally {
                document.body!!.removeChild(container)
                localStorage.removeItem(ViewText.HISTORY_VIEWS_STORAGE_KEY)
                resetHistoryUiState()
            }
        }

        "HistoryViewPrefs toolbar save set-default and delete user views" {
            localStorage.removeItem(ViewText.HISTORY_VIEWS_STORAGE_KEY)
            resetHistoryUiState()
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.historyViewsDom()
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            window.asDynamic().fetch =
                mockFetch { url ->
                    when {
                        url.contains("snapshots") ->
                            arrayOf(
                                mockSnapshotRecord(
                                    assets =
                                    json(
                                        Asset.BTC to json("valueUSD" to 60, "balance" to 1, "currentPercent" to 60),
                                        Asset.USD to json(
                                            "valueUSD" to 40,
                                            "balance" to 40,
                                            "currentPercent" to 40,
                                        ),
                                    ),
                                ),
                                mockSnapshotRecord(
                                    assets =
                                    json(
                                        Asset.BTC to
                                            json("valueUSD" to 70, "balance" to 1.1, "currentPercent" to 70),
                                        Asset.USD to json(
                                            "valueUSD" to 30,
                                            "balance" to 30,
                                            "currentPercent" to 30,
                                        ),
                                    ),
                                ),
                            )
                        url.contains("trades") ->
                            arrayOf(mockTradeRecord(symbol = Asset.BTC, volume = 1, usdAmount = 100))
                        else ->
                            mockPortfolioStatsRecord(
                                allTimeHigh = 100,
                                totalTradesExecuted = 1,
                                totalVolumeTraded = 100,
                                totalFeesPaid = 1,
                            )
                    }
                }
            registerHistoryGlobals()
            try {
                HistoryViewPrefs.initToolbar()
                val select = document.getElementById(HtmlIds.HISTORY_VIEWS_SELECT) as HTMLSelectElement
                select.options.length shouldBe 4

                loadAll(TimeRange.THIRTY_DAYS.key).await()

                window.asDynamic().prompt = { _: String -> "My View" }
                (document.getElementById(HtmlIds.HISTORY_SAVE_VIEW_BTN) as HTMLButtonElement).click()
                select.options.length shouldBe 5
                val userId = select.value
                userId.startsWith("user-") shouldBe true

                (document.getElementById(HtmlIds.HISTORY_SET_DEFAULT_BTN) as HTMLButtonElement).click()
                HistoryViewPrefs.loadStore().defaultId shouldBe userId

                (document.getElementById(HtmlIds.HISTORY_DELETE_VIEW_BTN) as HTMLButtonElement).disabled shouldBe false
                (document.getElementById(HtmlIds.HISTORY_DELETE_VIEW_BTN) as HTMLButtonElement).click()
                HistoryViewPrefs.applyDefaultView().await()
                val afterDelete = document.getElementById(HtmlIds.HISTORY_VIEWS_SELECT) as HTMLSelectElement
                afterDelete.options.length shouldBe 4
                HistoryViewPrefs.loadStore().defaultId shouldBe HistoryViewIds.OVERVIEW

                HistoryViewPrefs.applyView(HistoryViewIds.OVERVIEW).await()
                (document.getElementById(HtmlIds.HISTORY_DELETE_VIEW_BTN) as HTMLButtonElement).disabled shouldBe true

                HistoryViewPrefs.mergeBuiltIns(
                    HistoryViewsStore(defaultId = "missing", views = emptyList()),
                ).defaultId shouldBe HistoryViewIds.OVERVIEW

                HistoryViewPrefs.parseStore(
                    json("defaultId" to HistoryViewIds.WEEK_ALLOCATION, "views" to emptyArray<dynamic>()),
                ).defaultId shouldBe HistoryViewIds.WEEK_ALLOCATION

                localStorage.setItem(ViewText.HISTORY_VIEWS_STORAGE_KEY, "{not-json")
                HistoryViewPrefs.loadStore().defaultId shouldBe HistoryViewIds.OVERVIEW

                // Empty prompt cancels save
                window.asDynamic().prompt = { _: String -> "  " }
                val beforeCancel = (
                    document.getElementById(
                        HtmlIds.HISTORY_VIEWS_SELECT,
                    ) as HTMLSelectElement
                    ).options.length
                (document.getElementById(HtmlIds.HISTORY_SAVE_VIEW_BTN) as HTMLButtonElement).click()
                (document.getElementById(HtmlIds.HISTORY_VIEWS_SELECT) as HTMLSelectElement).options.length shouldBe
                    beforeCancel
            } finally {
                document.body!!.removeChild(container)
                localStorage.removeItem(ViewText.HISTORY_VIEWS_STORAGE_KEY)
                resetHistoryUiState()
            }
        }

        "setupZoomButtons invoke chart zoom APIs" {
            resetHistoryUiState()
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.zoomControlsDom()
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
                    HtmlIds.PORTFOLIO_VALUE_CHART,
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
                val chartLike =
                    json(
                        "data" to
                            json(
                                "datasets" to lastConfig.data.datasets,
                            ),
                    )
                (legendFilter(json("datasetIndex" to 0), chartLike) as Boolean) shouldBe true
                (legendFilter(json("datasetIndex" to 1), chartLike) as Boolean) shouldBe false
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "legendLabelsFilter keeps legend-toggled series and drops config-hidden ones" {
            val visibleDs = json(ChartProps.LABEL to ViewText.TOTAL_PORTFOLIO)
            val configHiddenDs = json(ChartProps.LABEL to Asset.BTC, DataProps.HIDDEN to true)
            val chartLike =
                json(
                    "data" to
                        json(
                            "datasets" to arrayOf(visibleDs, configHiddenDs),
                        ),
                )
            legendLabelsFilter(json("datasetIndex" to 0), chartLike) shouldBe true
            legendLabelsFilter(json("datasetIndex" to 1), chartLike) shouldBe false
            legendLabelsFilter(json(), chartLike) shouldBe true
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
            window.asDynamic().Chart = { _: dynamic, config: dynamic ->
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
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "getClonedChartOptions attaches onZoomComplete to re-sync scrubber" {
            resetHistoryUiState()
            registerHistoryGlobals()
            val options = getClonedChartOptions()
            val callback = options.plugins.zoom.zoom[ChartProps.ON_ZOOM_COMPLETE]
            (callback != null && callback != undefined) shouldBe true
        }

        "syncScrubberFromZoomContext enables scrubber after zoom context" {
            resetHistoryUiState()
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML =
                """
                <canvas id="${HtmlIds.PORTFOLIO_VALUE_CHART}"></canvas>
                ${TestDomBuilders.scrubberDom(disabled = true)}
                """.trimIndent()
            document.body!!.appendChild(container)
            window.asDynamic().Chart = { _: dynamic, config: dynamic ->
                jsObject {
                    data = config.data
                    options = config.options
                    canvas = document.getElementById(HtmlIds.PORTFOLIO_VALUE_CHART)
                    destroy = {}
                    isDatasetVisible = { _: Int -> true }
                    getInitialScaleBounds = {
                        json("x" to json("min" to 0.0, "max" to 100.0))
                    }
                    scales = json("x" to json("min" to 25.0, "max" to 50.0))
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
                syncScrubberFromZoomContext(json())
                syncScrubberFromZoomContext(null)
                val chart = jsObject {
                    canvas = document.getElementById(HtmlIds.PORTFOLIO_VALUE_CHART)
                }
                syncScrubberFromZoomContext(json("chart" to chart))
                val scrubber =
                    document.querySelector(CssClass.Query.CHART_SCRUBBERS) as HTMLInputElement
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
                zoomScaleCalls shouldBe 0
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }
    }
}
