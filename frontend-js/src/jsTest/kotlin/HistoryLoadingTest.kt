package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.api.PortfolioSnapshot
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HistoryViewIds
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlEvents
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.HtmlTags
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.dom.*
import kotlin.js.Promise
import kotlin.js.json

class HistoryLoadingTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
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

        "failed view preset load rolls back to the previous visibility" {
            resetHistoryUiState()
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.historyDom()
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            registerHistoryGlobals()

            try {
                visibilityStates[HtmlIds.PORTFOLIO_VALUE_CHART] = mutableMapOf(
                    ChartProps.DATASET_VISIBILITY_DEFAULT to true,
                    Asset.BTC to false,
                )

                val dayTotal = HistoryViewPrefs.builtInViews().first { it.id == HistoryViewIds.DAY_TOTAL }
                historyApplyVisibility(dayTotal.visibility)

                window.asDynamic().fetch = { url: String ->
                    if (url.contains("snapshots")) {
                        Promise.reject(RuntimeException("preset load failed"))
                    } else {
                        val response: dynamic = json()
                        response.json = { Promise.resolve(json()) }
                        Promise.resolve(response)
                    }
                }
                val failed = loadAll(TimeRange.TWENTY_FOUR_HOURS.key)
                try {
                    failed.await()
                } catch (error: Throwable) {
                    error.message shouldBe "preset load failed"
                }

                visibilityStates[HtmlIds.PORTFOLIO_VALUE_CHART] shouldBe mapOf(
                    ChartProps.DATASET_VISIBILITY_DEFAULT to true,
                    Asset.BTC to false,
                )

                window.asDynamic().fetch = mockFetch { url ->
                    when {
                        url.contains("snapshots") -> arrayOf(portfolioSnapshotToDynamic(mockSnapshotRecord()))
                        url.contains("trades") -> arrayOf(tradeRecordToDynamic(mockTradeRecord()))
                        url.contains("comparison") -> rebalancerComparisonToDynamic(mockAvailableComparison())
                        else -> historyStatsToDynamic(mockPortfolioStatsRecord())
                    }
                }
                loadAll(TimeRange.SEVEN_DAYS.key).await()

                // The failed preset's hidden flags (default=false, Day · Total only) were never applied.
                visibilityStates[HtmlIds.PORTFOLIO_VALUE_CHART] shouldBe mapOf(
                    ChartProps.DATASET_VISIBILITY_DEFAULT to true,
                    Asset.BTC to false,
                )
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

        "loadAll sets chartDefaults time unit based on range" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.historyDom()
            document.body!!.appendChild(container)
            val capturedUrls = mutableListOf<String>()
            window.asDynamic().capturedUrls = capturedUrls.toTypedArray()
            window.asDynamic().Chart = mockChartConstructor()
            window.asDynamic().fetch =
                mockFetch(
                    mockHistoryFetchHandler(
                        syncProgress = json("seeded" to false, "offset" to "5", "total" to "10"),
                        stats =
                        mockPortfolioStatsRecord(
                            allTimeHigh = "100",
                            totalTradesExecuted = 1L,
                            totalVolumeTraded = "100",
                            totalFeesPaid = "1",
                        ),
                    ),
                )
            try {
                registerHistoryGlobals()

                // loadAll mutates a deep clone only — shared chartDefaults.time.unit must stay null.
                loadAll(TimeRange.TWENTY_FOUR_HOURS.key).await()
                (getClonedChartOptions().scales.x.time.unit as String) shouldBe "hour"
                (window.asDynamic().chartDefaults.scales.x.time.unit == null) shouldBe true

                loadAll(TimeRange.ALL.key).await()
                (getClonedChartOptions().scales.x.time.unit == null) shouldBe true
                (window.asDynamic().chartDefaults.scales.x.time.unit == null) shouldBe true

                loadAll(TimeRange.THIRTY_DAYS.key).await()
                (getClonedChartOptions().scales.x.time.unit as String) shouldBe "day"
                (window.asDynamic().chartDefaults.scales.x.time.unit == null) shouldBe true
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "initHistory sets up click listeners and checkbox listeners" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML =
                """
                <button class="${CssClass.History.TimeRangeBtn}" ${HtmlAttrs.DATA_RANGE}="24h"></button>
                <button class="${CssClass.History.TimeRangeBtnActive}" ${HtmlAttrs.DATA_RANGE}="30d"></button>
                ${TestDomBuilders.syncProgressDom()}
                ${TestDomBuilders.chartsDom()}
                ${TestDomBuilders.tradeTableDom()}
                ${TestDomBuilders.statsDom()}
                """.trimIndent()
            document.body!!.appendChild(container)

            val oldSetInterval = window.asDynamic().setInterval
            val oldClearInterval = window.asDynamic().clearInterval
            var intervalCb: (() -> Unit)? = null
            window.asDynamic().setInterval = { cb: () -> Unit, _: Int ->
                intervalCb = cb
                42
            }
            var clearIntervalCalled = false
            window.asDynamic().clearInterval = { id: Int ->
                id shouldBe 42
                clearIntervalCalled = true
            }

            var fetchCount = 0
            window.asDynamic().Chart = mockChartConstructor()
            window.asDynamic().fetch =
                mockFetch { url ->
                    when {
                        url.contains("sync-progress") -> {
                            if (fetchCount++ == 0) {
                                json("seeded" to false, "offset" to 5, "total" to 10)
                            } else {
                                json("seeded" to true)
                            }
                        }
                        url.contains("snapshots") -> emptyArray<dynamic>()
                        url.contains("trades") -> emptyArray<dynamic>()
                        else -> json()
                    }
                }

            try {
                initHistory()

                awaitPromiseQueue()

                (intervalCb != null).shouldBeTrue()

                intervalCb?.invoke()

                awaitPromiseQueue()

                clearIntervalCalled.shouldBeTrue()

                val button24h =
                    document.querySelector(
                        "${HtmlTags.BUTTON}[${HtmlAttrs.DATA_RANGE}='${TimeRange.TWENTY_FOUR_HOURS.key}']",
                    ) as HTMLElement
                button24h.click()

                val checkbox = document.getElementById(HtmlIds.SHOW_DRY_RUN_CHECKBOX) as HTMLInputElement
                checkbox.checked = false
                val event = document.createEvent("Event")
                event.initEvent(type = HtmlEvents.CHANGE, bubbles = true, cancelable = true)
                checkbox.dispatchEvent(event)

                awaitPromiseQueue()
            } finally {
                window.asDynamic().setInterval = oldSetInterval
                window.asDynamic().clearInterval = oldClearInterval
                document.body!!.removeChild(container)
            }
        }

        "checkSyncProgress handles banner missing, seeded true/false, and offset/total" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.syncProgressDom()
            document.body!!.appendChild(container)

            window.asDynamic().fetch = mockFetch { json("seeded" to true) }
            try {
                document.getElementById(HtmlIds.SYNC_PROGRESS_BANNER)?.remove()
                checkSyncProgress().await() shouldBe true
            } finally {
                container.innerHTML = TestDomBuilders.syncProgressDom()
                document.body!!.appendChild(container)
            }

            window.asDynamic().fetch = mockFetch { json("seeded" to false, "offset" to 0, "total" to 100) }
            try {
                checkSyncProgress().await() shouldBe false
                val banner = document.getElementById(HtmlIds.SYNC_PROGRESS_BANNER) as HTMLElement
                banner.style.display shouldBe "block"
                val bar = document.getElementById(HtmlIds.SYNC_PROGRESS_BAR) as HTMLElement
                bar.style.width shouldBe "0%"
                val text = document.getElementById(HtmlIds.SYNC_PROGRESS_TEXT) as HTMLElement
                text.textContent shouldBe "0 / 100 (0%)"
            } finally {
            }

            window.asDynamic().fetch = mockFetch { json("seeded" to false, "offset" to 50, "total" to 100) }
            try {
                checkSyncProgress().await() shouldBe false
                val bar = document.getElementById(HtmlIds.SYNC_PROGRESS_BAR) as HTMLElement
                bar.style.width shouldBe "50%"
                val text = document.getElementById(HtmlIds.SYNC_PROGRESS_TEXT) as HTMLElement
                text.textContent shouldBe "50 / 100 (50%)"
            } finally {
            }

            window.asDynamic().fetch = mockFetch { json("seeded" to false, "offset" to 0, "total" to 0) }
            try {
                checkSyncProgress().await() shouldBe false
            } finally {
            }

            window.asDynamic().fetch = { _: String -> Promise.reject(Throwable("Network error")) }
            try {
                checkSyncProgress().await() shouldBe false
            } finally {
                document.body!!.removeChild(container)
            }
        }
    }
}
