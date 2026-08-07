package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.api.PortfolioSnapshot
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
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
            val container = document.createElement("div")
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
                (document.getElementById("sync-progress-bar") as HTMLElement).style.width shouldBe "50%"
                loadAll(TimeRange.TWENTY_FOUR_HOURS.key).await()
                (getClonedChartOptions().scales.x.time.unit as String) shouldBe "hour"
                (window.asDynamic().chartDefaults.scales.x.time.unit == null) shouldBe true
                (getClonedChartOptions().plugins.zoom.limits.x.minRange as Double) shouldBe
                    ChartProps.ZOOM_MIN_RANGE_MS.toDouble()
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

        "loadAll ignores an older range response that completes after the newest request" {
            resetHistoryUiState()
            val container = document.createElement("div")
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
                bodyResolvers.getValue("/api/history/snapshots?range=$range")(
                    arrayOf(portfolioSnapshotToDynamic(snapshot)),
                )
                bodyResolvers.getValue("/api/history/trades?range=$range")(
                    arrayOf(tradeRecordToDynamic(mockTradeRecord(symbol = tradeSymbol))),
                )
                bodyResolvers.getValue("/api/history/stats?range=$range")(
                    historyStatsToDynamic(mockPortfolioStatsRecord(totalTradesExecuted = totalTrades)),
                )
                bodyResolvers.getValue("/api/history/comparison?range=$range")(
                    rebalancerComparisonToDynamic(mockAvailableComparison()),
                )
                bodyResolvers.getValue("/api/history/rewards?range=$range")(
                    json("totalRewardsUSD" to "0.00", "points" to emptyArray<dynamic>()),
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
                document.getElementById("stat-total-trades")?.textContent shouldBe "2"
                val tableHtml = document.getElementById("trade-table-body")?.innerHTML.orEmpty()
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
                bodyResolvers.getValue("/api/history/snapshots?range=$range")(emptyArray<dynamic>())
                bodyResolvers.getValue("/api/history/trades?range=$range")(emptyArray<dynamic>())
                bodyResolvers.getValue("/api/history/stats?range=$range")(
                    historyStatsToDynamic(mockPortfolioStatsRecord()),
                )
                bodyResolvers.getValue("/api/history/comparison?range=$range")(
                    rebalancerComparisonToDynamic(mockAvailableComparison()),
                )
                bodyResolvers.getValue("/api/history/rewards?range=$range")(
                    json("totalRewardsUSD" to "0.00", "points" to emptyArray<dynamic>()),
                )
            }

            try {
                val older = loadAll(TimeRange.TWENTY_FOUR_HOURS.key)
                val newest = loadAll(TimeRange.ALL.key)
                awaitPromiseQueue()

                resolveRange(TimeRange.ALL.key)
                newest.await()

                bodyRejectors.getValue(
                    "/api/history/snapshots?range=${TimeRange.TWENTY_FOUR_HOURS.key}",
                )(RuntimeException("obsolete request failed"))
                older.await()

                currentRange shouldBe TimeRange.ALL.key

                val current = loadAll(TimeRange.SEVEN_DAYS.key)
                awaitPromiseQueue()
                bodyRejectors.getValue(
                    "/api/history/snapshots?range=${TimeRange.SEVEN_DAYS.key}",
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
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.historyDom() +
                "<div id=\"stat-ath-title\"></div>"
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
                document.getElementById("stat-ath-title")?.textContent shouldBe ViewText.HISTORY_ALL_TIME_HIGH

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
                document.getElementById("stat-ath-title")?.textContent shouldBe ViewText.HISTORY_ALL_TIME_HIGH
                document.getElementById("stat-ath")?.textContent shouldBe "$9,000.00"
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "failed view preset load rolls back to the previous visibility" {
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.historyDom()
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            registerHistoryGlobals()

            try {
                visibilityStates["portfolio-value-chart"] = mutableMapOf(
                    ChartProps.DATASET_VISIBILITY_DEFAULT to true,
                    Asset.BTC to false,
                )

                val dayTotal = HistoryViewPrefs.builtInViews().first { it.id == "day-total" }
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

                visibilityStates["portfolio-value-chart"] shouldBe mapOf(
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
                visibilityStates["portfolio-value-chart"] shouldBe mapOf(
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
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.historyDom() +
                "<div id=\"stat-ath-title\"></div>"
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
                document.getElementById("stat-ath")?.textContent shouldBe "$1,001.00"
                document.getElementById("stat-total-trades")?.textContent shouldBe "11"
                document.getElementById("stat-total-volume")?.textContent shouldBe "$2,001.00"
                document.getElementById("stat-total-fees")?.textContent shouldBe "$31.00"
                document.getElementById("stat-avg-fee-rate")?.textContent shouldBe "0.01%"
                document.getElementById("stat-avg-slippage")?.textContent shouldBe "-0.2%"

                loadAll(TimeRange.SEVEN_DAYS.key).await()
                document.getElementById("stat-ath")?.textContent shouldBe "$1,002.00"
                document.getElementById("stat-total-trades")?.textContent shouldBe "22"
                document.getElementById("stat-total-volume")?.textContent shouldBe "$2,002.00"
                document.getElementById("stat-total-fees")?.textContent shouldBe "$32.00"
                document.getElementById("stat-avg-fee-rate")?.textContent shouldBe "0.02%"
                document.getElementById("stat-avg-slippage")?.textContent shouldBe ViewText.PLACEHOLDER_DASHES
                document.getElementById("stat-ath-title")?.textContent shouldBe ViewText.PERIOD_HIGH
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "checkSyncProgress hides the banner when history is seeded" {
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.syncProgressDom()
            document.body!!.appendChild(container)
            window.asDynamic().fetch = mockFetch { json("seeded" to true) }
            try {
                checkSyncProgress().await() shouldBe true
                (document.getElementById("sync-progress-banner") as HTMLElement)
                    .classList.contains(CssClass.Utility.Hidden.value) shouldBe true
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "initHistory sets up click listeners and checkbox listeners" {
            val container = document.createElement("div")
            container.innerHTML =
                """
                <button class="time-range-btn" data-range="24h"></button>
                <button class="time-range-btn active" data-range="30d"></button>
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
                        "button[data-range='${TimeRange.TWENTY_FOUR_HOURS.key}']",
                    ) as HTMLElement
                button24h.click()

                val checkbox = document.getElementById("show-dry-run-checkbox") as HTMLInputElement
                checkbox.checked = false
                val event = document.createEvent("Event")
                event.initEvent(type = "change", bubbles = true, cancelable = true)
                checkbox.dispatchEvent(event)

                awaitPromiseQueue()
            } finally {
                window.asDynamic().setInterval = oldSetInterval
                window.asDynamic().clearInterval = oldClearInterval
                document.body!!.removeChild(container)
            }
        }

        "checkSyncProgress handles banner missing, seeded true/false, and offset/total" {
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.syncProgressDom()
            document.body!!.appendChild(container)

            window.asDynamic().fetch = mockFetch { json("seeded" to true) }
            try {
                document.getElementById("sync-progress-banner")?.remove()
                checkSyncProgress().await() shouldBe true
            } finally {
                container.innerHTML = TestDomBuilders.syncProgressDom()
                document.body!!.appendChild(container)
            }

            window.asDynamic().fetch = mockFetch { json("seeded" to false, "offset" to 0, "total" to 100) }
            try {
                checkSyncProgress().await() shouldBe false
                val banner = document.getElementById("sync-progress-banner") as HTMLElement
                banner.classList.contains(CssClass.Utility.Hidden.value) shouldBe false
                val bar = document.getElementById("sync-progress-bar") as HTMLElement
                bar.style.width shouldBe "0%"
                val text = document.getElementById("sync-progress-text") as HTMLElement
                text.textContent shouldBe "0 / 100 (0%)"
            } finally {
            }

            window.asDynamic().fetch = mockFetch { json("seeded" to false, "offset" to 50, "total" to 100) }
            try {
                checkSyncProgress().await() shouldBe false
                val bar = document.getElementById("sync-progress-bar") as HTMLElement
                bar.style.width shouldBe "50%"
                val text = document.getElementById("sync-progress-text") as HTMLElement
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
