package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.view.util.CssClass
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.dom.HTMLElement
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
            container.innerHTML = TestDomBuilders.historyDom()
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            // Track fetches
            var snapshotRangeSeen: String? = null
            window.asDynamic().fetch = mockFetch { url ->
                if (url.contains("snapshots")) {
                    snapshotRangeSeen = url.substringAfter("range=")
                }
                when {
                    url.contains("snapshots") ->
                        arrayOf(portfolioSnapshotToDynamic(mockSnapshotRecord()))

                    url.contains("trades") ->
                        arrayOf(tradeRecordToDynamic(mockTradeRecord()))

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
            var lastCb: (() -> Unit)? = null
            var lastId = 0
            var clearCalls = mutableListOf<Int>()
            window.asDynamic().setTimeout = { cb: () -> Unit, _: Int ->
                lastCb = cb
                lastId++
                lastId
            }
            window.asDynamic().clearTimeout = { id: Int ->
                clearCalls.add(id)
            }

            try {
                // History page ready: banner hidden
                (document.getElementById("sync-progress-banner") as HTMLElement)
                    .classList.add(CssClass.Utility.Hidden.value)
                currentRange = TimeRange.SEVEN_DAYS.key
                loadedRange = TimeRange.SEVEN_DAYS.key

                scheduleHistoryRealtimeReload()
                historyRealtimeDebouncePendingForTest() shouldBe true
                // Second rapid call should clear previous timeout and keep pending
                scheduleHistoryRealtimeReload()
                clearCalls.size shouldBe 1
                historyRealtimeDebouncePendingForTest() shouldBe true

                // Fire the debounced callback
                lastCb!!.invoke()
                // loadAll is async; wait for its Promise queue
                awaitPromiseQueue()
                // give Promises from fetch a chance to resolve
                Promise.resolve(Unit).await()
                awaitPromiseQueue()

                snapshotRangeSeen shouldBe TimeRange.SEVEN_DAYS.key
                historyRealtimeDebouncePendingForTest() shouldBe false
            } finally {
                window.asDynamic().setTimeout = originalSetTimeout
                window.asDynamic().clearTimeout = originalClearTimeout
                document.body!!.removeChild(container)
                teardownHistoryRealtimeUpdates()
                resetHistoryUiState()
            }
        }

        "teardown clears debounce and closes EventSource" {
            resetHistoryUiState()
            teardownHistoryRealtimeUpdates()
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.historyDom()
            document.body!!.appendChild(container)
            val fakeEs: dynamic = json("close" to {}, "onmessage" to null, "onerror" to null)
            // Expose fake constructor on both window and globalThis for js(\"new EventSource\") lookup.
            window.asDynamic().EventSource = { _: String -> fakeEs }
            js("globalThis.EventSource = window.EventSource")
            window.asDynamic().Chart = mockChartConstructor()
            try {
                (document.getElementById("sync-progress-banner") as HTMLElement)
                    .classList.add(CssClass.Utility.Hidden.value)
                setupHistoryRealtimeUpdates()
                historyRealtimeActiveForTest() shouldBe true
                val origSetTimeout = window.asDynamic().setTimeout
                window.asDynamic().setTimeout = { _: () -> Unit, _: Int ->
                    99
                }
                scheduleHistoryRealtimeReload()
                historyRealtimeDebouncePendingForTest() shouldBe true
                window.asDynamic().setTimeout = origSetTimeout

                teardownHistoryRealtimeUpdates()
                historyRealtimeActiveForTest() shouldBe false
                historyRealtimeDebouncePendingForTest() shouldBe false
            } finally {
                window.asDynamic().EventSource = js("undefined")
                js("globalThis.EventSource = undefined")
                document.body!!.removeChild(container)
                teardownHistoryRealtimeUpdates()
                resetHistoryUiState()
            }
        }

        "setupHistoryRealtimeUpdates is a no-op when not on history page" {
            resetHistoryUiState()
            teardownHistoryRealtimeUpdates()
            document.body!!.innerHTML = "<div>not history</div>"
            window.asDynamic().EventSource = { _: String ->
                // should not be called
                throw RuntimeException("EventSource should not be created off history page")
            }
            js("globalThis.EventSource = window.EventSource")
            try {
                setupHistoryRealtimeUpdates()
                historyRealtimeActiveForTest() shouldBe false
            } finally {
                window.asDynamic().EventSource = js("undefined")
                js("globalThis.EventSource = undefined")
                teardownHistoryRealtimeUpdates()
                document.body!!.innerHTML = ""
                resetHistoryUiState()
            }
        }
    }
}
