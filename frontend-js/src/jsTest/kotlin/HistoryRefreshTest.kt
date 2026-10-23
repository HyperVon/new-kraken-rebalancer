package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.HtmlIds
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.js.js
import kotlin.js.json

private data class PendingHistoryStatus(val url: String, val success: (dynamic) -> Unit, val failure: () -> Unit)

class HistoryRefreshTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "status polling is five-second, non-overlapping, visibility-aware, and disposable" {
            var hidden = true
            var intervalCallback: (() -> Unit)? = null
            var intervalDelay = 0
            var intervalClears = 0
            var cancels = 0
            var otherAborts = 0
            var paused = 0
            var disposedListener = 0
            var currentUrl = "/api/history/data-status?preset=30d"
            var statuses = 0
            val revisions = mutableListOf<Boolean>()
            val requests = mutableListOf<PendingHistoryStatus>()
            val poller = HistoryDataStatusPoller(
                statusUrl = { currentUrl },
                request = { url, success, failure ->
                    requests += PendingHistoryStatus(url, success, failure)
                    val cancel: () -> Unit = { cancels++ }
                    cancel
                },
                onPending = { statuses++ },
                onResponse = { _, _, changed -> revisions += changed },
                onFailure = { error("Unexpected status failure") },
                onPaused = { paused++ },
                abortOtherRequests = { otherAborts++ },
                scheduleInterval = { callback, delay ->
                    intervalCallback = callback
                    intervalDelay = delay
                    1
                },
                clearScheduledInterval = { intervalClears++ },
                hidden = { hidden },
            )
            poller.addDisposer { disposedListener++ }

            poller.start()
            requests.size shouldBe 0
            hidden = false
            poller.visibilityChanged()
            intervalDelay shouldBe 5_000
            requests.size shouldBe 1
            fire(intervalCallback)
            requests.size shouldBe 1
            requests[0].success(historyRefreshStatus(revision = 1.0))
            revisions shouldBe listOf(false)

            currentUrl = "/api/history/data-status?preset=30d&scope=older"
            poller.refreshContext()
            intervalClears shouldBe 1
            requests.size shouldBe 2
            requests[1].url shouldBe currentUrl
            requests[1].success(historyRefreshStatus(revision = 1.0))
            revisions shouldBe listOf(false, false)

            fire(intervalCallback)
            requests.size shouldBe 3
            hidden = true
            poller.visibilityChanged()
            intervalClears shouldBe 2
            cancels shouldBe 1
            requests[2].success(historyRefreshStatus(revision = 9.0))
            revisions shouldBe listOf(false, false)

            hidden = false
            poller.visibilityChanged()
            requests.size shouldBe 4
            requests[3].url shouldBe currentUrl
            requests[3].success(historyRefreshStatus(revision = 2.0))
            revisions shouldBe listOf(false, false, true)
            statuses shouldBe 4

            poller.dispose()
            intervalClears shouldBe 3
            disposedListener shouldBe 1
            otherAborts shouldBe 3
            paused shouldBe 2
            poller.dispose()
            disposedListener shouldBe 1
        }

        "History visibility and swaps cancel requests and remove page listeners" {
            val root = historyRefreshFixture()
            val oldSetInterval = window.asDynamic().setInterval
            val oldClearInterval = window.asDynamic().clearInterval
            var timerCallback: (() -> Unit)? = null
            var intervalClears = 0
            var cancels = 0
            val requests = mutableListOf<PendingHistoryStatus>()
            window.asDynamic().setInterval = { callback: () -> Unit, _: Int ->
                timerCallback = callback
                1
            }
            window.asDynamic().clearInterval = { _: dynamic -> intervalClears++ }
            setDocumentHidden(true)
            val chart = historyRefreshChart()
            val fetch: (String, (dynamic) -> Unit, () -> Unit) -> Unit = { url, success, _ ->
                when {
                    url.startsWith("/api/history/charts?") -> success(chart)
                    url.startsWith("/api/history/summary?") -> success(historyRefreshSummary(chart))
                    url == "/api/history/scopes" -> success(historyRefreshScopes())
                }
            }
            val statusRequest: (String, (dynamic) -> Unit, () -> Unit) -> (() -> Unit) = { url, success, failure ->
                requests += PendingHistoryStatus(url, success, failure)
                val cancel: () -> Unit = { cancels++ }
                cancel
            }
            try {
                initHistoryRangeControls(
                    currentQuery = "?range=30d",
                    fetchJson = fetch,
                    timezone = { "UTC" },
                    statusRequest = statusRequest,
                )
                requests.size shouldBe 0

                setDocumentHidden(false)
                document.dispatchEvent(Event("visibilitychange"))
                requests.size shouldBe 1
                requests[0].success(historyRefreshStatus(revision = 1.0))
                fire(timerCallback)
                requests.size shouldBe 2

                setDocumentHidden(true)
                document.dispatchEvent(Event("visibilitychange"))
                intervalClears shouldBe 1
                cancels shouldBe 1
                requests[1].success(historyRefreshStatus(revision = 2.0))
                setDocumentHidden(false)
                document.dispatchEvent(Event("visibilitychange"))
                requests.size shouldBe 3

                val beforeSwap = Event("htmx:beforeSwap")
                beforeSwap.asDynamic().detail = json("target" to root)
                document.dispatchEvent(beforeSwap)
                cancels shouldBe 2
                intervalClears shouldBe 2
                root.remove()
                document.dispatchEvent(Event("visibilitychange"))
                document.dispatchEvent(Event("htmx:afterSwap"))
                requests.size shouldBe 3
            } finally {
                disposeHistoryAutoRefresh()
                root.remove()
                clearDocumentHidden()
                window.asDynamic().setInterval = oldSetInterval
                window.asDynamic().clearInterval = oldClearInterval
            }
        }

        "status mapping distinguishes current completion from backfill and failures" {
            historyRefreshBadge(historyRefreshStatus(revision = 1.0)).label shouldBe "Up to date"
            historyRefreshBadge(null, pending = true).label shouldBe "Updating"
            historyRefreshBadge(null, failed = true).label shouldBe "Delayed"

            val backfilling = historyRefreshStatus(revision = 1.0, backfillState = "RUNNING")
            historyRefreshBadge(backfilling).label shouldBe "Backfilling"

            val retrying = historyRefreshStatus(revision = 1.0, retryAt = "2026-10-19T16:00:00Z")
            historyRefreshBadge(retrying).label shouldBe "Delayed"

            val blocked = historyRefreshStatus(revision = 1.0)
            blocked.blockedReason = "Account identity is not verified"
            historyRefreshBadge(blocked).label shouldBe "Action needed"

            val failedWithoutRetry = historyRefreshStatus(revision = 1.0)
            failedWithoutRetry.progress[0].lastError = "recent sync failed"
            historyRefreshBadge(failedWithoutRetry).label shouldBe "Action needed"

            val backfillOnly = historyRefreshStatus(
                revision = 1.0,
                recentThrough = null,
                backfillState = "COMPLETE",
            )
            historyRefreshBadge(backfillOnly).label shouldBe "Updating"
        }

        "tab push and pop state preserve filters and the selected account-record page" {
            val root = historyRefreshFixture()
            val originalUrl = window.location.href
            val oldSetInterval = window.asDynamic().setInterval
            val oldClearInterval = window.asDynamic().clearInterval
            window.asDynamic().setInterval = { _: () -> Unit, _: Int -> 1 }
            window.asDynamic().clearInterval = { _: dynamic -> Unit }
            val firstUrl =
                "${window.location.pathname}?range=30d&scope=portfolio-1&asset=XBT&pair=XXBTZUSD&" +
                    "type=buy&tab=activity&activity=trades&tradesOffset=50&ledgersOffset=100"
            val fetch: (String, (dynamic) -> Unit, () -> Unit) -> Unit = { url, success, _ ->
                when {
                    url.startsWith("/api/history/charts?") -> success(historyRefreshChart())
                    url.startsWith("/api/history/summary?") -> success(historyRefreshSummary(historyRefreshChart()))
                    url == "/api/history/scopes" -> success(historyRefreshScopes())
                }
            }
            val statusRequest: (String, (dynamic) -> Unit, () -> Unit) -> (() -> Unit) = { _, _, _ ->
                val cancel: () -> Unit = {}
                cancel
            }

            try {
                window.asDynamic().history.replaceState(null, "", firstUrl)
                initHistoryCharts { _, config ->
                    json(
                        "data" to config.data,
                        "options" to config.options,
                        "update" to { _: dynamic -> Unit },
                        "resize" to { Unit },
                    )
                }
                initHistoryRangeControls(
                    currentQuery = window.location.search,
                    fetchJson = fetch,
                    timezone = { "UTC" },
                    statusRequest = statusRequest,
                )

                val dataStatusTab = root.querySelector("#history-tab-data-status") as HTMLElement
                dataStatusTab.click()

                val dataQuery = historyTestParams(window.location.search)
                dataQuery.get("tab") as? String shouldBe "data"
                dataQuery.get("range") as? String shouldBe "30d"
                dataQuery.get("scope") as? String shouldBe "portfolio-1"
                dataQuery.get("asset") as? String shouldBe "XBT"
                dataQuery.get("pair") as? String shouldBe "XXBTZUSD"
                dataQuery.get("type") as? String shouldBe "buy"
                dataQuery.get("tradesOffset") as? String shouldBe "50"
                dataQuery.get("ledgersOffset") as? String shouldBe "100"
                (root.querySelector("#history-panel-data-status") as HTMLElement).hidden shouldBe false

                window.asDynamic().history.replaceState(null, "", firstUrl)
                window.dispatchEvent(Event("popstate"))

                historyPageTabState(window.location.search) shouldBe HistoryPageTabState("activity", "trades")
                (root.querySelector("#history-panel-activity") as HTMLElement).hidden shouldBe false
                (root.querySelector("#history-trades-section") as HTMLElement).hidden shouldBe false
                (root.querySelector("#history-panel-data-status") as HTMLElement).hidden shouldBe true
                root.querySelector("[data-history-pagination='trades']")!!
                    .getAttribute("data-history-offset") shouldBe "50"
            } finally {
                disposeHistoryAutoRefresh()
                root.remove()
                disposeDetachedHistoryCharts()
                window.asDynamic().history.replaceState(null, "", originalUrl)
                window.asDynamic().setInterval = oldSetInterval
                window.asDynamic().clearInterval = oldClearInterval
            }
        }

        "refresh planning reloads only affected feed panels and falls back to value panels" {
            val previous = historyRefreshStatus(revision = 1.0)
            val tradeProgressOnly = historyRefreshStatus(revision = 2.0)
            tradeProgressOnly.progress[0].recentLastSuccessAtIso = "2026-10-19T15:00:00Z"
            val tradeTargets = historyRefreshTargets(previous, tradeProgressOnly)
            tradeTargets.charts shouldBe false
            tradeTargets.summary shouldBe true
            tradeTargets.holdings shouldBe false
            tradeTargets.trades shouldBe true
            tradeTargets.ledgers shouldBe false
            tradeTargets.activity shouldBe true

            val noFeedEvidence = json(
                "coverage" to json(),
                "jobs" to emptyArray<dynamic>(),
                "progress" to emptyArray<dynamic>(),
            )
            val fallbackTargets = historyRefreshTargets(noFeedEvidence, noFeedEvidence)
            fallbackTargets.charts shouldBe true
            fallbackTargets.summary shouldBe true
            fallbackTargets.holdings shouldBe false
            fallbackTargets.trades shouldBe false
            fallbackTargets.ledgers shouldBe false
            fallbackTargets.activity shouldBe false
        }

        "published value generations refresh charts when date coverage stays the same" {
            val previous = historyRefreshStatus(revision = 1.0)
            previous.publishedValueGenerationId = "generation-old"
            previous.publishedValueSourceRevision = 4
            val published = historyRefreshStatus(revision = 2.0)
            published.publishedValueGenerationId = "generation-new"
            published.publishedValueSourceRevision = 5

            val targets = historyRefreshTargets(previous, published)

            targets.charts shouldBe true
            targets.summary shouldBe true
            targets.holdings shouldBe true
            targets.activity shouldBe true
        }

        "selected holdings refresh retains the selected day and intraday sample" {
            val root = historyRefreshFixture()
            val dayInput = root.querySelector("#history-holdings-date") as HTMLInputElement
            val sampleAtInput = root.querySelector("#history-holdings-at") as HTMLInputElement
            val selections = mutableListOf<Pair<String, String?>>()
            try {
                refreshSelectedHistoryHoldings { day, sampleAt -> selections += day to sampleAt }
                selections shouldBe listOf("2026-10-18" to "2026-10-18T12:00:00Z")

                sampleAtInput.value = ""
                refreshSelectedHistoryHoldings { day, sampleAt -> selections += day to sampleAt }
                selections.last() shouldBe ("2026-10-18" to null)

                dayInput.value = ""
                refreshSelectedHistoryHoldings { day, sampleAt -> selections += day to sampleAt }
                selections.size shouldBe 2

                dayInput.remove()
                refreshSelectedHistoryHoldings { day, sampleAt -> selections += day to sampleAt }
                selections.size shouldBe 2
            } finally {
                root.remove()
            }
        }

        "status and chart queries preserve custom ranges and freeze rolling ranges while zoomed" {
            val customQuery = "?range=custom&start=2026-10-01&end=2026-10-10&tz=Europe%2FParis&scope=older"
            val customState = historyRangeState(customQuery, null, "UTC")
            val selectedRange = json(
                "fromIso" to "2026-10-01T00:00:00Z",
                "toIso" to "2026-10-11T00:00:00Z",
            )
            val customChart = historyTestParams(historyApiQuery(customState, chart = true, pinnedRange = selectedRange))
            customChart.get("preset") as? String shouldBe "custom"
            customChart.get("start") as? String shouldBe "2026-10-01"
            customChart.get("end") as? String shouldBe "2026-10-10"
            (customChart.get("from") as? String) shouldBe null
            (customChart.get("to") as? String) shouldBe null
            customChart.get("scope") as? String shouldBe "older"

            val statusUrl = historyTestParams(
                historyStatusUrl(customQuery, null, "UTC").substringAfter('?'),
            )
            statusUrl.get("preset") as? String shouldBe "custom"
            statusUrl.get("start") as? String shouldBe "2026-10-01"
            statusUrl.get("end") as? String shouldBe "2026-10-10"
            statusUrl.get("tz") as? String shouldBe "Europe/Paris"
            statusUrl.get("scope") as? String shouldBe "older"

            val rolling = historyRangeState("?range=30d&scope=current", null, "UTC")
            val following = historyTestParams(historyApiQuery(rolling, chart = true))
            (following.get("from") as? String) shouldBe null
            (following.get("to") as? String) shouldBe null
            val zoomed = historyTestParams(historyApiQuery(rolling, chart = true, pinnedRange = selectedRange))
            zoomed.get("from") as? String shouldBe "2026-10-01T00:00:00Z"
            zoomed.get("to") as? String shouldBe "2026-10-11T00:00:00Z"
            val latestAgain = historyTestParams(historyApiQuery(rolling, chart = true))
            (latestAgain.get("from") as? String) shouldBe null
            (latestAgain.get("to") as? String) shouldBe null
        }

        "all-time refresh uses the newest status upper bound instead of a stale chart range" {
            val root = historyRefreshFixture()
            val originalUrl = window.location.pathname + window.location.search + window.location.hash
            val oldSetInterval = window.asDynamic().setInterval
            val oldClearInterval = window.asDynamic().clearInterval
            var timerCallback: (() -> Unit)? = null
            window.asDynamic().setInterval = { callback: () -> Unit, _: Int ->
                timerCallback = callback
                1
            }
            window.asDynamic().clearInterval = { _: dynamic -> Unit }
            val statusRequests = mutableListOf<PendingHistoryStatus>()
            val panelRequests = mutableListOf<String>()
            val oldUntil = "2026-10-19T16:00:00Z"
            val newUntil = "2026-10-19T16:05:00Z"
            val staleChart = historyRefreshChart(from = "2026-10-01T00:00:00Z", to = oldUntil)
            val fetch: (String, (dynamic) -> Unit, () -> Unit) -> Unit = { url, success, _ ->
                when {
                    url.startsWith("/api/history/charts?") -> success(staleChart)
                    url.startsWith("/api/history/summary?") -> success(historyRefreshSummary(staleChart))
                    url == "/api/history/scopes" -> success(historyRefreshScopes())
                }
            }
            val statusRequest: (String, (dynamic) -> Unit, () -> Unit) -> (() -> Unit) = { url, success, failure ->
                statusRequests += PendingHistoryStatus(url, success, failure)
                val cancel: () -> Unit = {}
                cancel
            }
            val panelRequest: (String, (dynamic) -> Unit, () -> Unit) -> (() -> Unit) = { url, success, _ ->
                panelRequests += url
                when {
                    url.startsWith("/api/history/trades?") ->
                        success(json("items" to emptyArray<dynamic>(), "total" to 104, "hasMore" to true))

                    url.startsWith("/api/history/summary?") -> success(historyRefreshSummary(staleChart))
                }
                val cancel: () -> Unit = {}
                cancel
            }

            try {
                window.asDynamic().history.replaceState(
                    null,
                    "",
                    "${window.location.pathname}?range=all&tab=activity&activity=trades",
                )
                initHistoryCharts { _, config ->
                    json(
                        "data" to config.data,
                        "options" to config.options,
                        "update" to { _: dynamic -> Unit },
                        "resize" to { Unit },
                    )
                }
                initHistoryRangeControls(
                    currentQuery = window.location.search,
                    fetchJson = fetch,
                    timezone = { "UTC" },
                    statusRequest = statusRequest,
                    panelRequest = panelRequest,
                )
                statusRequests.size shouldBe 1
                statusRequests[0].success(
                    historyRefreshStatus(
                        revision = 1.0,
                        rangePreset = "all",
                        rangeFrom = null,
                        rangeTo = oldUntil,
                    ),
                )

                timerCallback!!()
                statusRequests.size shouldBe 2
                statusRequests[1].success(
                    historyRefreshStatus(
                        revision = 2.0,
                        tradeThrough = newUntil,
                        rangePreset = "all",
                        rangeFrom = null,
                        rangeTo = newUntil,
                    ),
                )

                val tradeRequest = panelRequests.first { it.startsWith("/api/history/trades?") }
                val params = historyTestParams(tradeRequest.substringAfter('?'))
                params.get("from") as? String shouldBe null
                params.get("until") as? String shouldBe newUntil
                params.get("offset") as? String shouldBe "0"
                root.querySelector("[data-history-total='trades']")!!.textContent shouldBe "104"
            } finally {
                disposeHistoryAutoRefresh()
                root.remove()
                disposeDetachedHistoryCharts()
                window.asDynamic().history.replaceState(null, "", originalUrl)
                window.asDynamic().setInterval = oldSetInterval
                window.asDynamic().clearInterval = oldClearInterval
            }
        }

        "unchanged revisions avoid panel reads and changed revisions preserve chart and page state" {
            val root = historyRefreshFixture()
            val oldSetInterval = window.asDynamic().setInterval
            val oldClearInterval = window.asDynamic().clearInterval
            var timerCallback: (() -> Unit)? = null
            window.asDynamic().setInterval = { callback: () -> Unit, _: Int ->
                timerCallback = callback
                1
            }
            window.asDynamic().clearInterval = { _: dynamic -> Unit }
            val statusRequests = mutableListOf<PendingHistoryStatus>()
            val panelRequests = mutableListOf<String>()
            val holdingsRequests = mutableListOf<String>()
            val oldFetch = window.asDynamic().fetch
            window.asDynamic().fetch = { url: String, _: dynamic ->
                holdingsRequests += url
                js(
                    "Promise.resolve({ok: true, json: () => Promise.resolve({totalStatus: 'COMPLETE', totalUsd: '1', holdings: []})})",
                )
            }
            var chartInstances = 0
            val defaultChart = historyRefreshChart()
            val latestChart = historyRefreshChart(
                from = "2026-09-19T00:00:00Z",
                to = "2026-10-20T00:00:00Z",
                lastValue = 25.0,
            )
            val fetch: (String, (dynamic) -> Unit, () -> Unit) -> Unit = { url, success, _ ->
                when {
                    url.startsWith("/api/history/charts?") -> success(defaultChart)
                    url.startsWith("/api/history/summary?") -> success(historyRefreshSummary(defaultChart))
                    url == "/api/history/scopes" -> success(historyRefreshScopes())
                }
            }
            val statusRequest: (String, (dynamic) -> Unit, () -> Unit) -> (() -> Unit) = { url, success, failure ->
                statusRequests += PendingHistoryStatus(url, success, failure)
                val cancel: () -> Unit = {}
                cancel
            }
            val panelRequest: (String, (dynamic) -> Unit, () -> Unit) -> (() -> Unit) = { url, success, _ ->
                panelRequests += url
                val params = historyTestParams(url.substringAfter('?', ""))
                when {
                    url.startsWith("/api/history/charts?") -> {
                        success(if (params.get("from") == null) latestChart else historyRefreshChart(lastValue = 22.0))
                    }

                    url.startsWith("/api/history/summary?") -> success(historyRefreshSummary(latestChart))
                }
                val cancel: () -> Unit = {}
                cancel
            }

            val previousLegendState = historyLegendHidden[HtmlIds.HISTORY_VALUE_CHART]
            val hiddenSeries = mutableSetOf("Portfolio value")
            historyLegendHidden[HtmlIds.HISTORY_VALUE_CHART] = hiddenSeries
            try {
                initHistoryCharts { _, config ->
                    chartInstances++
                    json(
                        "data" to config.data,
                        "options" to config.options,
                        "update" to { _: dynamic -> Unit },
                        "resize" to { Unit },
                    )
                }
                initHistoryRangeControls(
                    currentQuery =
                    "?range=30d&scope=portfolio-1&asset=XBT&pair=XXBTZUSD&type=buy&" +
                        "tab=activity&activity=trades&tradesOffset=24",
                    fetchJson = fetch,
                    timezone = { "UTC" },
                    statusRequest = statusRequest,
                    panelRequest = panelRequest,
                )
                (root.querySelector("#history-holdings-date") as HTMLInputElement).value = "2026-10-19"
                (root.querySelector("#history-holdings-at") as HTMLInputElement).value = "2026-10-19T14:30:00Z"
                val session = historyChartSessions[HtmlIds.HISTORY_VALUE_CHART]!!
                val full = session.viewport.full
                val visible = ChartRange(full.min + full.span * 0.2, full.min + full.span * 0.45)
                session.viewport = ChartViewport(full, visible)
                val stableRows = root.querySelector("#history-trades-body")!!.textContent

                statusRequests.size shouldBe 1
                val statusParams = historyTestParams(statusRequests[0].url.substringAfter('?'))
                statusParams.get("preset") as? String shouldBe "30d"
                statusParams.get("scope") as? String shouldBe "portfolio-1"
                statusParams.get("tz") as? String shouldBe "UTC"
                (statusParams.get("asset") as? String) shouldBe null
                statusRequests[0].success(historyRefreshStatus(revision = 1.0))
                root.querySelector("#history-refresh-chip")!!.textContent shouldBe "Up to date"

                fire(timerCallback)
                statusRequests.size shouldBe 2
                statusRequests[1].success(historyRefreshStatus(revision = 1.0))
                panelRequests shouldBe emptyList()
                holdingsRequests shouldBe emptyList()
                historyChartSessions[HtmlIds.HISTORY_VALUE_CHART] shouldBe session
                session.viewport.visible shouldBe visible
                historyLegendHidden[HtmlIds.HISTORY_VALUE_CHART] shouldBe hiddenSeries
                root.querySelector("#history-trades-body")!!.textContent shouldBe stableRows

                fire(timerCallback)
                statusRequests.size shouldBe 3
                statusRequests[2].success(
                    historyRefreshStatus(
                        revision = 2.0,
                        valuesLast = "2026-10-19T15:00:00Z",
                        tradeThrough = "2026-10-19T15:00:00Z",
                    ),
                )

                panelRequests.size shouldBe 2
                panelRequests.any { it.startsWith("/api/history/charts?") } shouldBe true
                panelRequests.any { it.startsWith("/api/history/summary?") } shouldBe true
                panelRequests.any { it.startsWith("/api/history/trades?") } shouldBe false
                panelRequests.any { it.startsWith("/api/history/ledgers?") } shouldBe false
                holdingsRequests.size shouldBe 1
                val holdingsParams = historyTestParams(holdingsRequests.single().substringAfter('?'))
                holdingsParams.get("date") as? String shouldBe "2026-10-19"
                holdingsParams.get("at") as? String shouldBe "2026-10-19T14:30:00Z"
                val chartParams = historyTestParams(
                    panelRequests.first { it.startsWith("/api/history/charts?") }
                        .substringAfter('?'),
                )
                chartParams.get("scope") as? String shouldBe "portfolio-1"
                chartParams.get("from") as? String shouldBe "2026-09-19T00:00:00Z"
                chartParams.get("to") as? String shouldBe "2026-10-19T00:00:00Z"
                session.viewport.visible shouldBe visible
                historyChartSessions[HtmlIds.HISTORY_VALUE_CHART] shouldBe session
                historyLegendHidden[HtmlIds.HISTORY_VALUE_CHART] shouldBe hiddenSeries
                chartInstances shouldBe 1
                (root.querySelector("#history-new-records-notice") as HTMLElement).hidden shouldBe false
                root.querySelector("#history-trades-body")!!.textContent shouldBe stableRows
                historyPageTabState("?range=30d&tab=activity&activity=trades&tradesOffset=24") shouldBe
                    HistoryPageTabState("activity", "trades")
                (root.querySelector("#history-panel-activity") as HTMLElement).hidden shouldBe false
                (root.querySelector("#history-panel-data-status") as HTMLElement).hidden shouldBe true
                (root.querySelector("#history-trades-section") as HTMLElement).hidden shouldBe false
                root.querySelector("[data-history-pagination='trades']")!!
                    .getAttribute("data-history-offset") shouldBe "24"
                (root.querySelector("#history-new-data-banner") as HTMLElement).hidden shouldBe false

                initHistoryCharts { _, _ ->
                    chartInstances++
                    null
                }
                chartInstances shouldBe 1
                (root.querySelector("#history-new-data-button") as HTMLElement).dispatchEvent(Event("click"))
                chartInstances shouldBe 1
                val latestQuery = historyTestParams(
                    panelRequests.last { it.startsWith("/api/history/charts?") }.substringAfter('?'),
                )
                (latestQuery.get("from") as? String) shouldBe null
                (latestQuery.get("to") as? String) shouldBe null
                latestQuery.get("scope") as? String shouldBe "portfolio-1"
                session.viewport.isZoomed shouldBe false
                (root.querySelector("#history-new-data-banner") as HTMLElement).hidden shouldBe true
            } finally {
                disposeHistoryAutoRefresh()
                root.remove()
                disposeDetachedHistoryCharts()
                window.asDynamic().fetch = oldFetch
                window.asDynamic().setInterval = oldSetInterval
                window.asDynamic().clearInterval = oldClearInterval
                if (previousLegendState == null) {
                    historyLegendHidden.remove(HtmlIds.HISTORY_VALUE_CHART)
                } else {
                    historyLegendHidden[HtmlIds.HISTORY_VALUE_CHART] = previousLegendState
                }
            }
        }

        "first table page prepends matching records while preserving filters, offsets, and scroll" {
            val root = document.createElement("div") as HTMLElement
            root.innerHTML = """
                <div class="table-wrapper" style="height:20px;overflow:auto">
                  <table><tbody id="history-trades-body"><tr><td>older</td></tr></tbody></table>
                </div>
                <span data-history-total="trades">2</span>
                <div data-history-pagination="trades" data-history-offset="0" data-history-limit="2">
                  <span data-history-page-range="trades">1-2 of 2</span>
                  <a id="history-trades-next">Next</a>
                </div>
            """.trimIndent()
            document.body!!.appendChild(root)
            val wrapper = root.querySelector(".table-wrapper") as HTMLElement
            wrapper.scrollTop = 2.0
            val items: dynamic = js("[]")
            items.push(historyTrade("new", "BTC/USD"))
            items.push(historyTrade("older", "ETH/USD"))
            try {
                updateHistoryTable(
                    "trades",
                    json("items" to items, "total" to 5, "hasMore" to true),
                    "?range=custom&start=2026-10-01&end=2026-10-10&tz=Europe%2FParis&scope=older&asset=XBT&pair=XXBTZUSD&type=buy&tradesOffset=0&ledgersOffset=24",
                    limit = 2,
                )

                val rows = root.querySelectorAll("#history-trades-body tr")
                rows.length shouldBe 2
                rows.item(0)!!.textContent!!.contains("BTC/USD") shouldBe true
                rows.item(1)!!.textContent!!.contains("ETH/USD") shouldBe true
                wrapper.scrollTop shouldBe 2.0
                root.querySelector("[data-history-total='trades']")!!.textContent shouldBe "5"
                root.querySelector("[data-history-page-range='trades']")!!.textContent shouldBe "1–2 of 5"

                val next = historyTestParams(
                    root.querySelector("#history-trades-next")!!.getAttribute("href")!!.substringAfter('?')
                        .substringBefore('#'),
                )
                next.get("asset") as? String shouldBe "XBT"
                next.get("pair") as? String shouldBe "XXBTZUSD"
                next.get("type") as? String shouldBe "buy"
                next.get("scope") as? String shouldBe "older"
                next.get("tradesOffset") as? String shouldBe "2"
                next.get("ledgersOffset") as? String shouldBe "24"
                next.get("start") as? String shouldBe "2026-10-01"
                next.get("end") as? String shouldBe "2026-10-10"
            } finally {
                root.remove()
            }
        }

        "first-page polling reads only changed trades with the active filters and stable cutoff" {
            val root = historyRefreshFixture()
            val oldSetInterval = window.asDynamic().setInterval
            val oldClearInterval = window.asDynamic().clearInterval
            var timerCallback: (() -> Unit)? = null
            window.asDynamic().setInterval = { callback: () -> Unit, _: Int ->
                timerCallback = callback
                1
            }
            window.asDynamic().clearInterval = { _: dynamic -> Unit }
            val statusRequests = mutableListOf<PendingHistoryStatus>()
            val panelRequests = mutableListOf<String>()
            val initialChart = historyRefreshChart()
            val fetch: (String, (dynamic) -> Unit, () -> Unit) -> Unit = { url, success, _ ->
                when {
                    url.startsWith("/api/history/charts?") -> success(initialChart)
                    url.startsWith("/api/history/summary?") -> success(historyRefreshSummary(initialChart))
                    url == "/api/history/scopes" -> success(historyRefreshScopes())
                }
            }
            val statusRequest: (String, (dynamic) -> Unit, () -> Unit) -> (() -> Unit) = { url, success, failure ->
                statusRequests += PendingHistoryStatus(url, success, failure)
                val cancel: () -> Unit = {}
                cancel
            }
            val panelRequest: (String, (dynamic) -> Unit, () -> Unit) -> (() -> Unit) = { url, success, _ ->
                panelRequests += url
                when {
                    url.startsWith("/api/history/summary?") -> success(historyRefreshSummary(initialChart))

                    url.startsWith("/api/history/trades?") -> {
                        val items: dynamic = js("[]")
                        items.push(historyTrade("new", "BTC/USD"))
                        items.push(historyTrade("older", "ETH/USD"))
                        success(json("items" to items, "total" to 5, "hasMore" to true))
                    }
                }
                val cancel: () -> Unit = {}
                cancel
            }
            val wrapper = root.querySelector("#history-trades-section .table-wrapper") as HTMLElement
            wrapper.scrollTop = 2.0

            try {
                initHistoryRangeControls(
                    currentQuery = "?range=30d&scope=portfolio-1&asset=XBT&pair=XXBTZUSD&type=buy&tradesOffset=0",
                    fetchJson = fetch,
                    timezone = { "UTC" },
                    statusRequest = statusRequest,
                    panelRequest = panelRequest,
                )
                statusRequests[0].success(historyRefreshStatus(revision = 1.0))
                fire(timerCallback)
                val changedStatus = historyRefreshStatus(revision = 2.0, tradeThrough = "2026-10-19T15:00:00Z")
                changedStatus.range = json("preset" to "30d")
                statusRequests[1].success(changedStatus)

                panelRequests.size shouldBe 2
                panelRequests.any { it.startsWith("/api/history/trades?") } shouldBe true
                panelRequests.any { it.startsWith("/api/history/summary?") } shouldBe true
                panelRequests.any { it.startsWith("/api/history/charts?") } shouldBe false
                panelRequests.any { it.startsWith("/api/history/ledgers?") } shouldBe false
                val tradeParams = historyTestParams(
                    panelRequests.first { it.startsWith("/api/history/trades?") }.substringAfter('?'),
                )
                tradeParams.get("asset") as? String shouldBe "XBT"
                tradeParams.get("pair") as? String shouldBe "XXBTZUSD"
                tradeParams.get("type") as? String shouldBe "buy"
                tradeParams.get("offset") as? String shouldBe "0"
                tradeParams.get("limit") as? String shouldBe "2"
                tradeParams.get("from") as? String shouldBe "2026-09-19T00:00:00Z"
                tradeParams.get("until") as? String shouldBe "2026-10-19T00:00:00Z"
                (tradeParams.get("scope") as? String) shouldBe null
                val rows = root.querySelectorAll("#history-trades-body tr")
                rows.length shouldBe 2
                rows.item(0)!!.textContent!!.contains("BTC/USD") shouldBe true
                rows.item(1)!!.textContent!!.contains("ETH/USD") shouldBe true
            } finally {
                disposeHistoryAutoRefresh()
                root.remove()
                window.asDynamic().setInterval = oldSetInterval
                window.asDynamic().clearInterval = oldClearInterval
            }
        }

        "ledger refresh renders empty pages and status failure detail without false success" {
            val root = historyRefreshFixture()
            val ledgerBody = root.querySelector("#history-ledgers-body")!!
            val next = document.createElement("a")
            next.setAttribute("id", "history-ledgers-next")
            root.querySelector("[data-history-pagination='ledgers']")!!.appendChild(next)
            val ledgerItems: dynamic = js("[]")
            ledgerItems.push(historyLedger("earn", "staking", "DOT"))
            ledgerItems.push(historyLedger("trade", "tradespot", "BTC"))
            try {
                updateHistoryTable(
                    "ledgers",
                    json("items" to ledgerItems, "total" to null, "hasMore" to false),
                    "?range=30d&ledgersOffset=0",
                    limit = 2,
                )
                val rows = root.querySelectorAll("#history-ledgers-body tr")
                rows.length shouldBe 2
                rows.item(0)!!.textContent!!.contains("Earn (staking)") shouldBe true
                rows.item(1)!!.textContent!!.contains("Trade (tradespot)") shouldBe false
                root.querySelector("#history-ledgers-next") shouldBe null
                root.querySelector("[data-history-page-range='ledgers']")!!.textContent shouldBe "1–2"

                updateHistoryTable(
                    "ledgers",
                    json("items" to emptyArray<dynamic>(), "total" to null, "hasMore" to false),
                    "?range=30d&ledgersOffset=0",
                    limit = 2,
                )
                ledgerBody.textContent!!.contains("No matching records") shouldBe true

                val failed = historyRefreshStatus(revision = 1.0, retryAt = "2026-10-19T16:00:00Z")
                failed.blockedReason = "Account scope is blocked"
                updateHistoryRefreshStatus(failed, 1_800_000_000_000.0, null, failed = true)
                root.querySelector("#history-refresh-chip")!!.textContent shouldBe "Action needed"
                (root.querySelector("#history-refresh-error") as HTMLElement).hidden shouldBe false
                root.querySelector("#history-refresh-error")!!.textContent shouldBe "Account scope is blocked"

                updateHistoryRefreshStatus(null, 1_800_000_000_000.0, null, failed = true)
                root.querySelector("#history-refresh-chip")!!.textContent shouldBe "Delayed"
                root.querySelector("#history-refresh-error")!!.textContent shouldBe "Could not refresh History status."
            } finally {
                disposeHistoryAutoRefresh()
                root.remove()
            }
        }
    }
}

private fun historyRefreshStatus(
    revision: Double,
    valuesLast: String = "2026-10-19T14:00:00Z",
    tradeThrough: String? = "2026-10-19T14:00:00Z",
    retryAt: String? = null,
    backfillState: String = "COMPLETE",
    recentThrough: String? = "2026-10-19T14:00:00Z",
    rangePreset: String = "30d",
    rangeFrom: String? = "2026-09-19T00:00:00Z",
    rangeTo: String? = "2026-10-19T00:00:00Z",
): dynamic {
    val progress = arrayOf(
        json(
            "feed" to "TRADES",
            "recentCompletedThroughIso" to tradeThrough,
            "recentLastSuccessAtIso" to tradeThrough,
            "backfillState" to backfillState,
            "backfillCompletedThroughIso" to "2026-10-01T00:00:00Z",
            "backfillCursor" to "trade-cursor",
            "repairLastRunAtIso" to "2026-10-18T00:00:00Z",
            "lastError" to null,
            "nextRetryAtIso" to retryAt,
        ),
        json(
            "feed" to "LEDGERS",
            "recentCompletedThroughIso" to recentThrough,
            "recentLastSuccessAtIso" to recentThrough,
            "backfillState" to "COMPLETE",
            "backfillCompletedThroughIso" to "2026-10-02T00:00:00Z",
            "backfillCursor" to "ledger-cursor",
            "repairLastRunAtIso" to null,
            "lastError" to null,
            "nextRetryAtIso" to null,
        ),
    )
    val jobs = arrayOf(
        json(
            "feed" to "TRADES",
            "purpose" to "RECENT_SYNC",
            "state" to "COMPLETE",
            "windowStartIso" to "2026-10-19T13:00:00Z",
            "windowEndIso" to "2026-10-19T14:00:00Z",
            "cursor" to "trade-job-cursor",
            "completedThroughIso" to tradeThrough,
            "lastError" to null,
            "nextRetryAtIso" to retryAt,
            "lastSuccessAtIso" to tradeThrough,
            "updatedAtIso" to tradeThrough,
        ),
        json(
            "feed" to "LEDGERS",
            "purpose" to "RECENT_SYNC",
            "state" to "COMPLETE",
            "windowStartIso" to "2026-10-19T13:00:00Z",
            "windowEndIso" to "2026-10-19T14:00:00Z",
            "cursor" to "ledger-job-cursor",
            "completedThroughIso" to recentThrough,
            "lastError" to null,
            "nextRetryAtIso" to null,
            "lastSuccessAtIso" to recentThrough,
            "updatedAtIso" to recentThrough,
        ),
    )
    return json(
        "range" to json(
            "preset" to rangePreset,
            "fromIso" to rangeFrom,
            "toIso" to rangeTo,
            "tz" to "UTC",
        ),
        "dataRevision" to revision,
        "publishedValueGenerationId" to "generation-1",
        "publishedValueSourceRevision" to 4,
        "blockedReason" to null,
        "coverage" to json(
            "valuesFirstIso" to "2026-09-20T00:00:00Z",
            "valuesLastIso" to valuesLast,
            "tradesFirstIso" to "2026-09-19T00:00:00Z",
            "tradesLastIso" to tradeThrough,
            "ledgersFirstIso" to "2026-09-19T00:00:00Z",
            "ledgersLastIso" to recentThrough,
            "gapCount" to 0,
            "incomplete" to false,
        ),
        "jobs" to jobs,
        "progress" to progress,
    )
}

private fun historyRefreshChart(
    from: String = "2026-09-19T00:00:00Z",
    to: String = "2026-10-19T00:00:00Z",
    lastValue: Double = 20.0,
): dynamic {
    val pointStart = historyEpoch("2026-10-01T00:00:00Z")
    val pointMiddle = historyEpoch("2026-10-10T00:00:00Z")
    val pointEnd = historyEpoch("2026-10-18T00:00:00Z")
    val points = arrayOf(
        json("t" to pointStart, "tIso" to "2026-10-01T00:00:00Z", "v" to 10.0, "complete" to true),
        json("t" to pointMiddle, "tIso" to "2026-10-10T00:00:00Z", "v" to 15.0, "complete" to true),
        json("t" to pointEnd, "tIso" to "2026-10-18T00:00:00Z", "v" to lastValue, "complete" to true),
    )
    val coverage = json(
        "valuesFirstIso" to "2026-10-01T00:00:00Z",
        "valuesLastIso" to "2026-10-18T00:00:00Z",
        "tradesFirstIso" to "2026-09-19T00:00:00Z",
        "tradesLastIso" to "2026-10-19T14:00:00Z",
        "ledgersFirstIso" to "2026-09-19T00:00:00Z",
        "ledgersLastIso" to "2026-10-19T14:00:00Z",
        "gapCount" to 0,
        "incomplete" to false,
    )
    return json(
        "range" to json("fromIso" to from, "toIso" to to, "tz" to "UTC", "preset" to "30d"),
        "scope" to json("id" to "portfolio-1", "symbols" to arrayOf("BTC", "USD"), "current" to true),
        "coverage" to coverage,
        "series" to
            arrayOf(json("key" to "portfolio", "label" to "Portfolio value", "unit" to "USD", "points" to points)),
        "fullRange" to json("fromIso" to from, "toIso" to to, "pointCount" to points.size),
    )
}

private fun historyRefreshSummary(chart: dynamic): dynamic = json(
    "range" to chart.range,
    "scope" to chart.scope,
    "coverage" to chart.coverage,
)

private fun historyRefreshScopes(): dynamic = json(
    "scopes" to arrayOf(
        json(
            "id" to "portfolio-1",
            "symbols" to arrayOf("BTC", "USD"),
            "earliestIso" to "2026-09-19T00:00:00Z",
            "latestIso" to "2026-10-19T00:00:00Z",
            "current" to true,
        ),
    ),
)

private fun historyRefreshFixture(): HTMLElement {
    val root = document.createElement("div") as HTMLElement
    root.innerHTML = """
        <nav id="history-tab-nav">
          <a id="history-tab-portfolio" data-history-tab="portfolio"></a>
          <a id="history-tab-activity" data-history-tab="activity"></a>
          <a id="history-tab-data-status" data-history-tab="data"></a>
        </nav>
        <div id="history-panel-activity" data-history-panel="activity" hidden>
          <nav id="history-activity-nav">
            <a data-history-activity-tab="bot"></a><a data-history-activity-tab="trades"></a>
            <a data-history-activity-tab="transactions"></a>
          </nav>
          <div id="history-activity-bot-panel" data-history-activity-panel="bot"></div>
          <div id="history-trades-section" class="history-trade-log" data-history-activity-panel="trades">
            <div class="table-wrapper"><table><tbody id="history-trades-body">
              <tr><td>inspect this row</td></tr>
            </tbody></table></div>
            <span data-history-total="trades">50</span>
            <div data-history-pagination="trades" data-history-offset="24" data-history-limit="2">
              <span data-history-page-range="trades">25-26 of 50</span>
            </div>
          </div>
          <div id="history-ledgers-section" class="history-trade-log" data-history-activity-panel="transactions">
            <table><tbody id="history-ledgers-body"></tbody></table>
            <div data-history-pagination="ledgers" data-history-offset="0" data-history-limit="2">
              <span data-history-page-range="ledgers">0-0</span>
            </div>
          </div>
        </div>
        <div id="history-panel-data-status" data-history-panel="data" hidden>
        <div id="history-refresh-status">
          <span id="history-refresh-chip"></span>
          <span id="history-refresh-updated"></span>
          <span id="history-refresh-next"></span>
          <p id="history-refresh-error" hidden></p>
          <div id="history-refresh-feed-progress">
            <div data-history-feed="TRADES">
              <span data-history-refresh-recent></span><span data-history-refresh-backfill></span>
              <span data-history-refresh-failure></span><span data-history-refresh-retry></span>
            </div>
            <div data-history-feed="LEDGERS">
              <span data-history-refresh-recent></span><span data-history-refresh-backfill></span>
              <span data-history-refresh-failure></span><span data-history-refresh-retry></span>
            </div>
          </div>
        </div>
        </div>
        <a data-history-range="30d" href="/history?range=30d">30d</a>
        <a data-history-range="custom" href="#history-range-custom-panel">Custom</a>
        <div id="history-range-custom-panel" hidden>
          <input id="history-range-custom-start" type="date">
          <input id="history-range-custom-end" type="date">
          <span id="history-range-custom-tz"></span>
          <button id="history-range-custom-apply">Apply</button>
          <p id="history-range-custom-error" hidden></p>
        </div>
        <select id="history-scope-select"></select>
        <a id="history-scope-link" href="#history-scope-select" hidden></a>
        <div id="history-coverage-selected"></div>
        <div id="history-coverage-values"></div>
        <div id="history-coverage-trades"></div>
        <div id="history-coverage-ledgers"></div>
        <div id="history-coverage-count"></div>
        <p id="history-coverage-sparse" hidden></p>
        <input id="history-filter-range" type="hidden">
        <input id="history-filter-scope" type="hidden">
        <input id="history-filter-start" type="hidden">
        <input id="history-filter-end" type="hidden">
        <input id="history-filter-tz" type="hidden">
        <input id="history-filter-tab" type="hidden">
        <input id="history-filter-activity" type="hidden">
        <input id="history-holdings-date" type="date" value="2026-10-18">
        <input id="history-holdings-at" type="hidden" value="2026-10-18T12:00:00Z">
        <div id="history-panel-portfolio" data-history-panel="portfolio">
        <div class="glass-panel">
          <div data-history-controls hidden>
            <button data-history-zoom="reset">Latest</button>
          </div>
          <div id="history-value-chart"><svg>
            <circle data-time="1790812800000" data-value="10"/>
            <circle data-time="1791590400000" data-value="15"/>
            <circle data-time="1792281600000" data-value="20"/>
          </svg></div>
        </div>
        </div>
        <div id="history-new-data-banner" hidden><button id="history-new-data-button">Latest</button></div>
        <div id="history-new-records-notice" hidden><button id="history-new-records-button">First page</button></div>
    """.trimIndent()
    document.body!!.appendChild(root)
    return root
}

private fun historyTrade(pairName: String, pair: String): dynamic = json(
    "tradeId" to pairName,
    "time" to "2026-10-19T14:00:00Z",
    "rawTime" to null,
    "pair" to pair,
    "type" to "buy",
    "volume" to "0.25",
    "price" to "60000.00",
    "cost" to "15000.00",
    "fee" to "15.00",
    "feeCurrency" to "USD",
)

private fun historyLedger(type: String, subtype: String, asset: String): dynamic = json(
    "ledgerId" to "ledger-$type",
    "time" to "2026-10-19T14:00:00Z",
    "rawTime" to null,
    "type" to type,
    "subtype" to subtype,
    "asset" to asset,
    "amount" to "5.00",
    "fee" to "0.05",
    "feeCurrency" to "USD",
    "balance" to "10.00",
)

private fun historyTestParams(value: String): dynamic = js("new URLSearchParams(value)")

private fun historyEpoch(value: String): Double = (js("Date.parse(value)") as Number).toDouble()

private fun fire(callback: (() -> Unit)?) {
    (callback ?: error("Interval callback was not installed"))()
}

private fun setDocumentHidden(hidden: Boolean) {
    js("Object.defineProperty(document, 'hidden', { configurable: true, value: hidden })")
}

private fun clearDocumentHidden() {
    js("delete document.hidden")
}
