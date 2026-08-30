package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.view.util.CssClass
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTableSectionElement
import kotlin.js.Promise
import kotlin.js.json

class HistoryRealtimeTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "isHistoryPage detects canvas presence" {
            resetHistoryUiState()
            teardownHistoryRealtimeUpdates()
            document.body!!.innerHTML = ""
            isHistoryPage() shouldBe false
            val container = document.createElement("div")
            container.innerHTML = """<canvas id="portfolio-value-chart"></canvas>"""
            document.body!!.appendChild(container)
            try {
                isHistoryPage() shouldBe true
            } finally {
                document.body!!.removeChild(container)
                teardownHistoryRealtimeUpdates()
                resetHistoryUiState()
            }
        }

        "isHistorySyncReady reflects banner visibility" {
            resetHistoryUiState()
            teardownHistoryRealtimeUpdates()
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.syncProgressDom()
            document.body!!.appendChild(container)
            try {
                // banner initially without hidden -> not ready
                isHistorySyncReady() shouldBe false
                (document.getElementById("sync-progress-banner") as HTMLElement)
                    .classList.add(CssClass.Utility.Hidden.value)
                isHistorySyncReady() shouldBe true
                document.getElementById("sync-progress-banner")?.remove()
                isHistorySyncReady() shouldBe true
            } finally {
                document.body!!.removeChild(container)
                teardownHistoryRealtimeUpdates()
            }
        }

        "scheduleHistoryRealtimeReload is a no-op off history page" {
            resetHistoryUiState()
            teardownHistoryRealtimeUpdates()
            document.body!!.innerHTML = TestDomBuilders.syncProgressDom()
            window.asDynamic().Chart = mockChartConstructor()
            var fetchCalls = 0
            window.asDynamic().fetch = mockFetch { _: String ->
                fetchCalls++
                emptyArray<dynamic>()
            }
            registerHistoryGlobals()
            try {
                scheduleHistoryRealtimeReload()
                historyRealtimeDebouncePendingForTest() shouldBe false
                fetchCalls shouldBe 0
            } finally {
                teardownHistoryRealtimeUpdates()
                resetHistoryUiState()
                document.body!!.innerHTML = ""
            }
        }

        "scheduleHistoryRealtimeReload skips when sync banner visible" {
            resetHistoryUiState()
            teardownHistoryRealtimeUpdates()
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.historyDom()
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            window.asDynamic().fetch = mockFetch(mockHistoryFetchHandler(syncProgress = json("seeded" to true)))
            registerHistoryGlobals()
            try {
                // Make banner visible (not hidden) -> simulating still seeding.
                val banner = document.getElementById("sync-progress-banner") as HTMLElement
                banner.classList.remove(CssClass.Utility.Hidden.value)
                scheduleHistoryRealtimeReload()
                historyRealtimeDebouncePendingForTest() shouldBe false
            } finally {
                document.body!!.removeChild(container)
                teardownHistoryRealtimeUpdates()
                resetHistoryUiState()
            }
        }

        "scheduleHistoryRealtimeReload debounces and reloads current range preserving state" {
            resetHistoryUiState()
            teardownHistoryRealtimeUpdates()
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.historyViewsDom()
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            // Track fetches
            var snapshotRangeSeen: String? = null
            var fetchCalls = 0
            window.asDynamic().fetch = mockFetch { url ->
                fetchCalls++
                if (url.contains("snapshots")) {
                    snapshotRangeSeen = url.substringAfter("range=")
                }
                when {
                    url.contains("snapshots") ->
                        arrayOf(portfolioSnapshotToDynamic(mockSnapshotRecord()))

                    url.contains("trades") ->
                        arrayOf(
                            tradeRecordToDynamic(mockTradeRecord(symbol = Asset.BTC, dryRun = false)),
                            tradeRecordToDynamic(mockTradeRecord(symbol = Asset.ETH, dryRun = true)),
                        )

                    url.contains("comparison") ->
                        rebalancerComparisonToDynamic(mockAvailableComparison())

                    url.contains("rewards") ->
                        json("totalRewardsUSD" to "0.00", "points" to emptyArray<dynamic>())

                    else ->
                        historyStatsToDynamic(mockPortfolioStatsRecord())
                }
            }
            registerHistoryGlobals()

            // Stub setTimeout to capture callbacks instead of wall-clock waiting.
            val originalSetTimeout = window.asDynamic().setTimeout
            val originalClearTimeout = window.asDynamic().clearTimeout
            val timeoutCallbacks = mutableMapOf<Int, () -> Unit>()
            val canceledTimeoutIds = mutableSetOf<Int>()
            var nextTimeoutId = 0
            val clearCalls = mutableListOf<Int>()
            window.asDynamic().setTimeout = { cb: () -> Unit, _: Int ->
                nextTimeoutId++
                timeoutCallbacks[nextTimeoutId] = cb
                nextTimeoutId
            }
            window.asDynamic().clearTimeout = { id: Int ->
                clearCalls.add(id)
                canceledTimeoutIds.add(id)
            }

            fun fireTimeout(id: Int) {
                if (id !in canceledTimeoutIds) timeoutCallbacks[id]?.invoke()
            }

            try {
                // History page ready: banner hidden
                (document.getElementById("sync-progress-banner") as HTMLElement)
                    .classList.add(CssClass.Utility.Hidden.value)
                currentRange = TimeRange.SEVEN_DAYS.key
                loadedRange = TimeRange.SEVEN_DAYS.key
                visibilityStates["portfolio-value-chart"] = mutableMapOf(Asset.BTC to false)
                val selectedViewId = HistoryViewPrefs.builtInViews()
                    .first { it.range == TimeRange.SEVEN_DAYS.key }
                    .id
                (selectedViewId == HistoryViewPrefs.defaultStore().defaultId) shouldBe false
                HistoryViewPrefs.refreshSelect(
                    HistoryViewsStore(
                        defaultId = HistoryViewPrefs.defaultStore().defaultId,
                        views = HistoryViewPrefs.builtInViews(),
                    ),
                    selectedId = selectedViewId,
                )
                (document.getElementById("show-dry-run-checkbox") as HTMLInputElement).checked = false

                scheduleHistoryRealtimeReload()
                historyRealtimeDebouncePendingForTest() shouldBe true
                val firstTimeoutId = nextTimeoutId
                // Second rapid call should clear previous timeout and keep pending
                scheduleHistoryRealtimeReload()
                val secondTimeoutId = nextTimeoutId
                clearCalls shouldBe listOf(firstTimeoutId)
                canceledTimeoutIds.contains(firstTimeoutId) shouldBe true
                historyRealtimeDebouncePendingForTest() shouldBe true

                // A canceled callback must not cause a duplicate reload.
                fireTimeout(firstTimeoutId)
                fetchCalls shouldBe 0
                historyRealtimeDebouncePendingForTest() shouldBe true

                // Fire the surviving debounced callback.
                fireTimeout(secondTimeoutId)
                fetchCalls shouldBe 5
                // loadAll is async; wait for its Promise queue
                awaitPromiseQueue()
                // give Promises from fetch a chance to resolve
                Promise.resolve(Unit).await()
                awaitPromiseQueue()

                snapshotRangeSeen shouldBe TimeRange.SEVEN_DAYS.key
                historyRealtimeDebouncePendingForTest() shouldBe false
                (document.getElementById("show-dry-run-checkbox") as HTMLInputElement).checked shouldBe false
                (document.getElementById("history-views-select") as HTMLSelectElement).value shouldBe selectedViewId
                visibilityStates["portfolio-value-chart"]?.get(Asset.BTC) shouldBe false
                val tradeTable = document.getElementById("trade-table-body") as HTMLTableSectionElement
                tradeTable.rows.length shouldBe 1
                tradeTable.innerHTML.contains("${Asset.BTC}/${Asset.USD}") shouldBe true
                tradeTable.innerHTML.contains("${Asset.ETH}/${Asset.USD}") shouldBe false
                val portfolioDatasets = charts["portfolio-value-chart"]!!.data.datasets
                val btcIndex = (0 until (portfolioDatasets.length as Int)).first { index ->
                    portfolioDatasets[index].label.toString() == Asset.BTC
                }
                (portfolioDatasets[btcIndex].hidden as? Boolean) shouldBe true
            } finally {
                window.asDynamic().setTimeout = originalSetTimeout
                window.asDynamic().clearTimeout = originalClearTimeout
                document.body!!.removeChild(container)
                teardownHistoryRealtimeUpdates()
                resetHistoryUiState()
            }
        }

        "scheduleHistoryRealtimeReload handles a rejected reload promise" {
            resetHistoryUiState()
            teardownHistoryRealtimeUpdates()
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.historyDom()
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            registerHistoryGlobals()
            val originalSetTimeout = window.asDynamic().setTimeout
            val originalConsoleError = window.asDynamic().console.error
            var reloadCallback: (() -> Unit)? = null
            var errorCalls = 0
            window.asDynamic().setTimeout = { callback: () -> Unit, _: Int ->
                reloadCallback = callback
                1
            }
            window.asDynamic().console.error = { _: dynamic, _: dynamic -> errorCalls++ }
            window.asDynamic().fetch = { _: String ->
                Promise.reject(RuntimeException("history refresh failed"))
            }
            try {
                (document.getElementById("sync-progress-banner") as HTMLElement)
                    .classList.add(CssClass.Utility.Hidden.value)
                scheduleHistoryRealtimeReload()
                reloadCallback!!.invoke()
                awaitPromiseQueue()
                Promise.resolve(Unit).await()
                awaitPromiseQueue()

                errorCalls shouldBe 1
                historyRealtimeDebouncePendingForTest() shouldBe false
            } finally {
                window.asDynamic().setTimeout = originalSetTimeout
                window.asDynamic().console.error = originalConsoleError
                document.body!!.removeChild(container)
                teardownHistoryRealtimeUpdates()
                resetHistoryUiState()
            }
        }

        "HTMX SSE messages refresh History and listener cleanup is idempotent" {
            resetHistoryUiState()
            teardownHistoryRealtimeUpdates()
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.historyRealtimeDom()
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            var snapshotRangeSeen: String? = null
            var fetchCalls = 0
            window.asDynamic().fetch = mockFetch { url ->
                fetchCalls++
                if (url.contains("snapshots")) {
                    snapshotRangeSeen = url.substringAfter("range=")
                }
                mockHistoryFetchHandler(syncProgress = json("seeded" to true))(url)
            }
            registerHistoryGlobals()
            val originalSetTimeout = window.asDynamic().setTimeout
            val originalClearTimeout = window.asDynamic().clearTimeout
            var nextTimeoutId = 0
            val timeoutCallbacks = mutableMapOf<Int, () -> Unit>()
            val canceledTimeoutIds = mutableSetOf<Int>()
            window.asDynamic().setTimeout = { callback: () -> Unit, _: Int ->
                nextTimeoutId++
                timeoutCallbacks[nextTimeoutId] = callback
                nextTimeoutId
            }
            window.asDynamic().clearTimeout = { id: Int ->
                canceledTimeoutIds.add(id)
            }
            try {
                (document.getElementById("sync-progress-banner") as HTMLElement)
                    .classList.add(CssClass.Utility.Hidden.value)
                currentRange = TimeRange.SEVEN_DAYS.key
                loadedRange = TimeRange.SEVEN_DAYS.key
                val root = document.getElementById("history-realtime-root")!!

                setupHistoryRealtimeUpdates()
                historyRealtimeActiveForTest() shouldBe true
                // Reinitialization must replace the old listener rather than accumulate one.
                setupHistoryRealtimeUpdates()
                root.asDynamic().dispatchEvent(js("new Event('sse:message')"))
                nextTimeoutId shouldBe 1
                historyRealtimeDebouncePendingForTest() shouldBe true
                timeoutCallbacks[1]!!.invoke()
                fetchCalls shouldBe 5
                awaitPromiseQueue()
                Promise.resolve(Unit).await()
                awaitPromiseQueue()
                snapshotRangeSeen shouldBe TimeRange.SEVEN_DAYS.key

                val timeoutCountBeforeTeardown = nextTimeoutId
                teardownHistoryRealtimeUpdates()
                historyRealtimeActiveForTest() shouldBe false
                historyRealtimeDebouncePendingForTest() shouldBe false
                root.asDynamic().dispatchEvent(js("new Event('sse:message')"))
                nextTimeoutId shouldBe timeoutCountBeforeTeardown
            } finally {
                teardownHistoryRealtimeUpdates()
                window.asDynamic().setTimeout = originalSetTimeout
                window.asDynamic().clearTimeout = originalClearTimeout
                document.body!!.removeChild(container)
                resetHistoryUiState()
            }
        }

        "setupHistoryRealtimeUpdates is a no-op when not on history page" {
            resetHistoryUiState()
            teardownHistoryRealtimeUpdates()
            document.body!!.innerHTML = "<div>not history</div>"
            try {
                setupHistoryRealtimeUpdates()
                historyRealtimeActiveForTest() shouldBe false
            } finally {
                teardownHistoryRealtimeUpdates()
                document.body!!.innerHTML = ""
                resetHistoryUiState()
            }
        }
    }
}
