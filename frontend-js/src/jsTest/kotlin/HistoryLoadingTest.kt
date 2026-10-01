package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.api.PortfolioSnapshot
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlIds
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

    private fun historyDomWithCoreLoadError(): String = TestDomBuilders.historyDom() +
        """
            <div id="stat-ath-title"></div>
            <div id="history-core-load-error" class="error-banner hidden"></div>
        """.trimIndent()

    init {
        "dry-run filter changes during loading persist and apply to the completed chart" {
            resetHistoryUiState()
            HistorySessionState.clear()
            HistoryViewPrefs.resetInteractionState()
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.historyViewsDom()
            document.body!!.appendChild(container)
            val chartConfigs = mutableListOf<dynamic>()
            window.asDynamic().Chart = mockChartConstructor { config -> chartConfigs.add(config) }
            val bodyResolvers = mutableMapOf<String, (dynamic) -> Unit>()
            window.asDynamic().fetch = { url: String ->
                if (url == "/api/history/sync-progress") {
                    Promise.resolve(okFetchResponse(json("seeded" to true, "offset" to "5", "total" to "10")))
                } else {
                    val response: dynamic = json()
                    response.ok = true
                    response.status = 200
                    response.json = {
                        Promise { resolve: (dynamic) -> Unit, _: (Throwable) -> Unit ->
                            bodyResolvers[url] = resolve
                        }
                    }
                    Promise.resolve<dynamic>(response)
                }
            }
            registerHistoryGlobals()

            try {
                setupSyncProgressAndLoad()
                awaitPromiseQueue()
                val range = currentRange
                val checkbox = document.getElementById(HtmlIds.SHOW_DRY_RUN_CHECKBOX) as HTMLInputElement
                historyTradesAvailable shouldBe false
                checkbox.checked = false
                val changeEvent = document.createEvent("Event")
                changeEvent.initEvent("change", bubbles = true, cancelable = true)
                checkbox.dispatchEvent(changeEvent)

                HistorySessionState.load()?.showDryRun shouldBe false
                HistoryViewPrefs.hasUserInteracted() shouldBe true

                bodyResolvers.getValue("/api/history/snapshots?range=$range")(emptyArray<dynamic>())
                bodyResolvers.getValue("/api/history/trades?range=$range")(
                    arrayOf(
                        tradeRecordToDynamic(mockTradeRecord(symbol = Asset.ETH, dryRun = true)),
                        tradeRecordToDynamic(mockTradeRecord(symbol = Asset.BTC, dryRun = false)),
                    ),
                )
                bodyResolvers.getValue("/api/history/stats?range=$range")(
                    historyStatsToDynamic(mockPortfolioStatsRecord()),
                )
                bodyResolvers.getValue("/api/history/rewards?range=$range")(
                    json("totalRewardsUSD" to "0.00", "points" to emptyArray<dynamic>()),
                )
                bodyResolvers.getValue("/api/history/comparison?range=$range")(
                    rebalancerComparisonToDynamic(mockAvailableComparison()),
                )
                awaitPromiseQueue()

                historyTradesAvailable shouldBe true
                chartConfigs.any { config ->
                    (config.data.datasets[0].label as String) == ViewText.NET_CASH_FLOW_REALIZED
                }.shouldBeTrue()

                checkbox.checked = true
                val loadedChangeEvent = document.createEvent("Event")
                loadedChangeEvent.initEvent("change", bubbles = true, cancelable = true)
                checkbox.dispatchEvent(loadedChangeEvent)
                HistorySessionState.load()?.showDryRun shouldBe true
                chartConfigs.any { config ->
                    (config.data.datasets[0].label as String) == ViewText.NET_CASH_FLOW_ALL
                }.shouldBeTrue()
            } finally {
                document.body!!.removeChild(container)
                HistorySessionState.clear()
                HistoryViewPrefs.resetInteractionState()
                resetHistoryUiState()
            }
        }

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
                response.ok = true
                response.status = 200
                response.json = {
                    Promise { resolve: (dynamic) -> Unit, _: (Throwable) -> Unit ->
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

        "a stale core failure cannot clear newer data or show the core error" {
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML = historyDomWithCoreLoadError()
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            val bodyResolvers = mutableMapOf<String, (dynamic) -> Unit>()
            val bodyRejectors = mutableMapOf<String, (Throwable) -> Unit>()
            window.asDynamic().fetch = { url: String ->
                val response: dynamic = json()
                response.ok = true
                response.status = 200
                response.json = {
                    Promise { resolve: (dynamic) -> Unit, reject: (Throwable) -> Unit ->
                        bodyResolvers[url] = resolve
                        bodyRejectors[url] = reject
                    }
                }
                Promise.resolve<dynamic>(response)
            }

            fun resolveRange(range: String) {
                bodyResolvers.getValue("/api/history/snapshots?range=$range")(emptyArray<dynamic>())
                bodyResolvers.getValue("/api/history/trades?range=$range")(
                    arrayOf(tradeRecordToDynamic(mockTradeRecord(symbol = Asset.ETH))),
                )
                bodyResolvers.getValue("/api/history/stats?range=$range")(
                    historyStatsToDynamic(mockPortfolioStatsRecord(totalTradesExecuted = 2L)),
                )
                bodyResolvers.getValue("/api/history/rewards?range=$range")(
                    json("totalRewardsUSD" to "0.00", "points" to emptyArray<dynamic>()),
                )
            }

            try {
                registerHistoryGlobals()
                val older = loadAll(TimeRange.TWENTY_FOUR_HOURS.key)
                val newest = loadAll(TimeRange.ALL.key)
                awaitPromiseQueue()

                resolveRange(TimeRange.ALL.key)
                newest.await()

                bodyRejectors.getValue(
                    "/api/history/snapshots?range=${TimeRange.TWENTY_FOUR_HOURS.key}",
                )(RuntimeException("obsolete snapshots failed"))
                older.await()

                currentRange shouldBe TimeRange.ALL.key
                loadedRange shouldBe TimeRange.ALL.key
                document.getElementById("stat-total-trades")?.textContent shouldBe "2"
                document.getElementById("trade-table-body")?.innerHTML.orEmpty() shouldContain
                    "${Asset.ETH}/${Asset.USD}"
                (document.getElementById("history-core-load-error") as HTMLElement)
                    .classList.contains("visible") shouldBe false
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "loadAll rejects with the HTTP failure surfaced by the res.ok check" {
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML = historyDomWithCoreLoadError()
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            window.asDynamic().fetch = { url: String ->
                if (url.contains("snapshots")) {
                    Promise.resolve(
                        json("ok" to false, "status" to 503, "json" to { Promise.resolve(json()) }),
                    )
                } else {
                    Promise.resolve(okFetchResponse(json()))
                }
            }
            registerHistoryGlobals()

            try {
                val failed = loadAll(TimeRange.SEVEN_DAYS.key)
                try {
                    failed.await()
                } catch (error: Throwable) {
                    (error.message ?: "").startsWith(ViewText.HTTP_FETCH_FAILED) shouldBe true
                }
                currentRange shouldBe TimeRange.SEVEN_DAYS.key
                loadedRange shouldBe TimeRange.THIRTY_DAYS.key
                (document.getElementById("history-core-load-error") as HTMLElement)
                    .classList.contains("visible") shouldBe true
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "loadAll renders other charts when only the comparison endpoint fails" {
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.historyDom() +
                "<div id=\"stat-ath-title\"></div>"
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            window.asDynamic().fetch = { url: String ->
                if (url.contains("comparison")) {
                    Promise.resolve(
                        json("ok" to false, "status" to 503, "json" to { Promise.resolve(json()) }),
                    )
                } else if (url.contains("snapshots")) {
                    Promise.resolve(okFetchResponse(arrayOf(portfolioSnapshotToDynamic(mockSnapshotRecord()))))
                } else if (url.contains("trades")) {
                    Promise.resolve(okFetchResponse(arrayOf(tradeRecordToDynamic(mockTradeRecord()))))
                } else if (url.contains("rewards")) {
                    Promise.resolve(
                        okFetchResponse(json("totalRewardsUSD" to "0.00", "points" to emptyArray<dynamic>())),
                    )
                } else {
                    Promise.resolve(okFetchResponse(historyStatsToDynamic(mockPortfolioStatsRecord())))
                }
            }
            registerHistoryGlobals()

            try {
                // The comparison failure never rejects the range load: loadAll resolves
                // while the comparison slot keeps its own visible error state.
                loadAll(TimeRange.ALL.key).await()

                // Core charts rendered and the requested range stays selected.
                document.getElementById("trade-table-body")?.innerHTML.orEmpty() shouldContain
                    "${Asset.BTC}/${Asset.USD}"
                document.getElementById("stat-ath")?.textContent shouldBe "$15,000.50"
                currentRange shouldBe TimeRange.ALL.key
                loadedRange shouldBe TimeRange.ALL.key
                // Comparison slot shows its own visible error state.
                (document.getElementById(HtmlIds.COMPARISON_AVAILABILITY_MESSAGE) as HTMLElement)
                    .classList.contains(CssClass.Utility.Visible.value) shouldBe true
                document.getElementById(HtmlIds.COMPARISON_CHART_CONTENT)
                    ?.classList?.contains(CssClass.Utility.Hidden.value) shouldBe true
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "a stale comparison failure cannot overwrite the current generation's range or panel" {
            resetHistoryUiState()
            val bodyResolvers = mutableMapOf<String, (dynamic) -> Unit>()
            val bodyRejectors = mutableMapOf<String, (Throwable) -> Unit>()
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.historyDom() +
                "<div id=\"stat-ath-title\"></div>"
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            window.asDynamic().fetch = { url: String ->
                val response: dynamic = json()
                response.ok = true
                response.status = 200
                response.json = {
                    Promise { resolve: (dynamic) -> Unit, reject: (Throwable) -> Unit ->
                        bodyResolvers[url] = resolve
                        bodyRejectors[url] = reject
                    }
                }
                Promise.resolve<dynamic>(response)
            }
            registerHistoryGlobals()

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

            fun resolveCoreOnly(range: String) {
                bodyResolvers.getValue("/api/history/snapshots?range=$range")(emptyArray<dynamic>())
                bodyResolvers.getValue("/api/history/trades?range=$range")(emptyArray<dynamic>())
                bodyResolvers.getValue("/api/history/stats?range=$range")(
                    historyStatsToDynamic(mockPortfolioStatsRecord()),
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

                // The superseded generation's comparison rejection must not touch the
                // current generation's range state or comparison panel. The older core
                // loads resolve normally; only their comparison request is rejected.
                resolveCoreOnly(TimeRange.TWENTY_FOUR_HOURS.key)
                bodyRejectors.getValue(
                    "/api/history/comparison?range=${TimeRange.TWENTY_FOUR_HOURS.key}",
                )(RuntimeException("obsolete comparison failed"))
                older.await()

                currentRange shouldBe TimeRange.ALL.key
                loadedRange shouldBe TimeRange.ALL.key
                (document.getElementById(HtmlIds.COMPARISON_AVAILABILITY_MESSAGE) as HTMLElement)
                    .classList.contains(CssClass.Utility.Visible.value) shouldBe false
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "loadAll keeps the requested range when a failure value is not a Throwable" {
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML = historyDomWithCoreLoadError()
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

                window.asDynamic().fetch = { url: String ->
                    if (url.contains("snapshots")) {
                        js("Promise.reject('raw-js-failure')") as Promise<dynamic>
                    } else {
                        Promise.resolve(okFetchResponse(emptyArray<dynamic>()))
                    }
                }
                val failed = loadAll(TimeRange.SEVEN_DAYS.key)
                try {
                    failed.await()
                } catch (error: dynamic) {
                    "$error".contains("raw-js-failure") shouldBe true
                }

                currentRange shouldBe TimeRange.SEVEN_DAYS.key
                loadedRange shouldBe TimeRange.ALL.key
                (document.getElementById("history-core-load-error") as HTMLElement)
                    .classList.contains("visible") shouldBe true
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "loadAll keeps the requested All range and ATH caption after another core group fails" {
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML = historyDomWithCoreLoadError()
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
                loadAll(TimeRange.SEVEN_DAYS.key).await()
                document.getElementById("stat-ath-title")?.textContent shouldBe "Period High"

                window.asDynamic().fetch = { url: String ->
                    if (url.contains("snapshots")) {
                        Promise.reject(RuntimeException("range request failed"))
                    } else {
                        Promise.resolve(
                            okFetchResponse(historyStatsToDynamic(mockPortfolioStatsRecord(allTimeHigh = "9000"))),
                        )
                    }
                }
                val failed = loadAll(TimeRange.ALL.key)
                try {
                    failed.await()
                } catch (error: Throwable) {
                    error.message shouldBe "range request failed"
                }

                currentRange shouldBe TimeRange.ALL.key
                loadedRange shouldBe TimeRange.SEVEN_DAYS.key
                // The stats endpoint succeeded for All while snapshots failed; the ATH label
                // stays consistent with the still-selected range and the failed charts clear.
                document.getElementById("stat-ath-title")?.textContent shouldBe "All-Time High"
                document.getElementById("stat-ath")?.textContent shouldBe "$9,000.00"
                charts.containsKey(HtmlIds.PORTFOLIO_VALUE_CHART) shouldBe false
                (document.getElementById("history-core-load-error") as HTMLElement)
                    .textContent shouldBe "Some History data could not be loaded for this range. " +
                    "Select the range again to retry."
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "core endpoint failures clear only unavailable data and a retry restores it" {
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML = historyDomWithCoreLoadError()
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            registerHistoryGlobals()

            var failedEndpoint: String? = null
            window.asDynamic().fetch = { url: String ->
                val failedRangeUrl = failedEndpoint?.let { endpoint ->
                    "/api/history/$endpoint?range=${TimeRange.SEVEN_DAYS.key}"
                }
                if (failedRangeUrl != null && url.contains(failedRangeUrl)) {
                    Promise.reject(RuntimeException("$failedEndpoint failed"))
                } else {
                    val response: dynamic = when {
                        url.contains("snapshots") ->
                            arrayOf(portfolioSnapshotToDynamic(mockSnapshotRecord(totalValueUSD = "222")))

                        url.contains("trades") ->
                            arrayOf(
                                tradeRecordToDynamic(
                                    mockTradeRecord(symbol = Asset.ETH, usdAmount = "222", dryRun = true),
                                ),
                            )

                        url.contains("comparison") -> rebalancerComparisonToDynamic(mockAvailableComparison())

                        url.contains("rewards") ->
                            json(
                                "totalRewardsUSD" to "12.34",
                                "points" to
                                    arrayOf(
                                        json(
                                            "timestamp" to "2026-07-01T12:00:00Z",
                                            "cumulativeUSD" to "12.34",
                                            "perAssetUSD" to json(Asset.ETH to "12.34"),
                                        ),
                                    ),
                            )

                        else ->
                            historyStatsToDynamic(
                                mockPortfolioStatsRecord(
                                    allTimeHigh = "9000",
                                    totalTradesExecuted = 77L,
                                ),
                            )
                    }
                    Promise.resolve(okFetchResponse(response))
                }
            }

            try {
                listOf("snapshots", "trades", "stats", "rewards").forEach { endpoint ->
                    failedEndpoint = null
                    loadAll(TimeRange.ALL.key).await()
                    charts.containsKey(HtmlIds.PORTFOLIO_VALUE_CHART) shouldBe true
                    charts.containsKey(HtmlIds.CUMULATIVE_NET_CASH_FLOW_CHART) shouldBe true
                    charts.containsKey(HtmlIds.REWARDS_CHART) shouldBe true
                    document.getElementById("trade-table-body")?.innerHTML.orEmpty() shouldContain
                        "${Asset.ETH}/${Asset.USD}"
                    document.getElementById("stat-total-trades")?.textContent shouldBe "77"
                    document.getElementById("rewards-total")?.textContent shouldBe "$12.34"

                    failedEndpoint = endpoint
                    val failed = loadAll(TimeRange.SEVEN_DAYS.key)
                    var failureMessage: String? = null
                    try {
                        failed.await()
                    } catch (error: Throwable) {
                        failureMessage = error.message
                    }
                    failureMessage shouldBe "$endpoint failed"
                    awaitPromiseQueue()

                    currentRange shouldBe TimeRange.SEVEN_DAYS.key
                    loadedRange shouldBe TimeRange.ALL.key
                    (document.getElementById("history-core-load-error") as HTMLElement)
                        .classList.contains(CssClass.Utility.Visible.value) shouldBe true
                    when (endpoint) {
                        "snapshots" -> {
                            charts.containsKey(HtmlIds.PORTFOLIO_VALUE_CHART) shouldBe false
                            charts.containsKey(HtmlIds.CUMULATIVE_NET_CASH_FLOW_CHART) shouldBe true
                        }

                        "trades" -> {
                            historyTradesAvailable shouldBe false
                            allTrades shouldBe emptyList()
                            document.getElementById("trade-table-body")?.innerHTML shouldBe ""
                            charts.containsKey(HtmlIds.CUMULATIVE_NET_CASH_FLOW_CHART) shouldBe false
                            rerenderHistoryTradesForDryRunFilter(includeDryRun = false)
                            document.getElementById("trade-table-body")?.innerHTML shouldBe ""
                            charts.containsKey(HtmlIds.CUMULATIVE_NET_CASH_FLOW_CHART) shouldBe false
                        }

                        "stats" -> {
                            listOf(
                                "stat-ath",
                                "stat-total-trades",
                                "stat-total-volume",
                                "stat-total-fees",
                                "stat-avg-fee-rate",
                                "stat-avg-slippage",
                            ).forEach { statId ->
                                document.getElementById(statId)?.textContent shouldBe ViewText.PLACEHOLDER_DASHES
                            }
                            document.getElementById("stat-ath-title")?.textContent shouldBe "Period High"
                        }

                        "rewards" -> {
                            charts.containsKey(HtmlIds.REWARDS_CHART) shouldBe false
                            document.getElementById("rewards-total")?.textContent shouldBe ViewText.PLACEHOLDER_DASHES
                        }
                    }

                    failedEndpoint = null
                    val retry = loadAll(TimeRange.SEVEN_DAYS.key)
                    val errorBanner = document.getElementById("history-core-load-error") as HTMLElement
                    errorBanner.classList.contains(CssClass.Utility.Visible.value) shouldBe false
                    errorBanner.classList.contains(CssClass.Utility.Hidden.value) shouldBe true
                    retry.await()

                    loadedRange shouldBe TimeRange.SEVEN_DAYS.key
                    charts.containsKey(HtmlIds.PORTFOLIO_VALUE_CHART) shouldBe true
                    charts.containsKey(HtmlIds.CUMULATIVE_NET_CASH_FLOW_CHART) shouldBe true
                    charts.containsKey(HtmlIds.REWARDS_CHART) shouldBe true
                    historyTradesAvailable shouldBe true
                    document.getElementById("trade-table-body")?.innerHTML.orEmpty() shouldContain
                        "${Asset.ETH}/${Asset.USD}"
                    document.getElementById("stat-total-trades")?.textContent shouldBe "77"
                    document.getElementById("rewards-total")?.textContent shouldBe "$12.34"
                }
            } finally {
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "failed preset load keeps its requested range and visibility while rejecting to callers" {
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML = historyDomWithCoreLoadError() +
                "<select id=\"history-views-select\"></select>"
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            registerHistoryGlobals()

            try {
                visibilityStates["portfolio-value-chart"] = mutableMapOf(
                    ChartProps.DATASET_VISIBILITY_DEFAULT to true,
                    Asset.BTC to false,
                )

                val dayTotal = HistoryViewPrefs.builtInViews().first { it.id == "day-total" }

                window.asDynamic().fetch = { url: String ->
                    if (url.contains("snapshots")) {
                        Promise.reject(RuntimeException("preset load failed"))
                    } else {
                        Promise.resolve(okFetchResponse(json()))
                    }
                }
                val failed = HistoryViewPrefs.applyView(dayTotal.id)
                try {
                    failed.await()
                } catch (error: Throwable) {
                    error.message shouldBe "preset load failed"
                }

                currentRange shouldBe TimeRange.TWENTY_FOUR_HOURS.key
                (document.getElementById("history-views-select") as HTMLSelectElement).value shouldBe dayTotal.id
                visibilityStates shouldBe dayTotal.visibility
                (document.getElementById("history-core-load-error") as HTMLElement)
                    .classList.contains("visible") shouldBe true

                window.asDynamic().fetch = mockFetch { url ->
                    when {
                        url.contains("snapshots") -> arrayOf(portfolioSnapshotToDynamic(mockSnapshotRecord()))
                        url.contains("trades") -> arrayOf(tradeRecordToDynamic(mockTradeRecord()))
                        url.contains("comparison") -> rebalancerComparisonToDynamic(mockAvailableComparison())
                        else -> historyStatsToDynamic(mockPortfolioStatsRecord())
                    }
                }
                loadAll(TimeRange.SEVEN_DAYS.key).await()

                // The selected preset remains coherent when its delayed charts later load.
                visibilityStates shouldBe dayTotal.visibility
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
                document.getElementById("stat-avg-slippage")?.textContent shouldBe "--"
                document.getElementById("stat-ath-title")?.textContent shouldBe "Period High"
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

            window.asDynamic().fetch = mockFetch {
                json(
                    "seeded" to true,
                    "recoveryStatus" to "AMBIGUOUS",
                    "recoveryTradeOffset" to "100",
                    "recoveryTradeTotal" to "200",
                    "recoveryLedgerOffset" to "50",
                    "recoveryLedgerTotal" to "100",
                    "recoveryReason" to "trade ownership is ambiguous",
                )
            }
            try {
                checkSyncProgress().await() shouldBe true
                val banner = document.getElementById("sync-progress-banner") as HTMLElement
                banner.classList.contains(CssClass.Utility.Hidden.value) shouldBe false
                val bar = document.getElementById("sync-progress-bar") as HTMLElement
                bar.style.width shouldBe "50%"
                val text = document.getElementById("sync-progress-text") as HTMLElement
                text.textContent shouldContain "trades 100 / 200; ledgers 50 / 100"
                text.textContent shouldContain "trade ownership is ambiguous"
            } finally {
            }

            window.asDynamic().fetch = mockFetch { json("seeded" to true, "recoveryStatus" to "FAILED") }
            try {
                document.getElementById("sync-progress-bar")?.remove()
                document.getElementById("sync-progress-text")?.remove()
                checkSyncProgress().await() shouldBe true
            } finally {
                container.innerHTML = TestDomBuilders.syncProgressDom()
                document.body!!.appendChild(container)
            }

            window.asDynamic().fetch = mockFetch {
                json(
                    "seeded" to true,
                    "recoveryStatus" to "IN_PROGRESS",
                    "recoveryTradeOffset" to "25",
                    "recoveryTradeTotal" to "100",
                )
            }
            try {
                checkSyncProgress().await() shouldBe false
                val banner = document.getElementById("sync-progress-banner") as HTMLElement
                banner.classList.contains(CssClass.Utility.Hidden.value) shouldBe false
            } finally {
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

            window.asDynamic().fetch = mockFetch { json("seeded" to false, "offset" to null, "total" to null) }
            try {
                checkSyncProgress().await() shouldBe false
                val text = document.getElementById("sync-progress-text") as HTMLElement
                text.textContent shouldBe "0 / 0 (0%)"
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
