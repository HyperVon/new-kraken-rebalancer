package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.ViewText
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import kotlin.js.json

class HistorySessionStateTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "HistorySessionState saves and restores ephemeral history state across navigation" {
            resetHistoryUiState()
            window.sessionStorage.removeItem(ViewText.HISTORY_SESSION_STORAGE_KEY)
            window.localStorage.removeItem(ViewText.HISTORY_VIEWS_STORAGE_KEY)
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.historyViewsDom()
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            window.asDynamic().fetch = mockFetch(mockHistoryFetchHandler(syncProgress = json("seeded" to true)))
            registerHistoryGlobals()

            try {
                // Start on default 30d, showDryRun true, no visibility
                currentRange shouldBe TimeRange.THIRTY_DAYS.key
                (document.getElementById("show-dry-run-checkbox") as HTMLInputElement).checked shouldBe true

                // Simulate user changing range to 7d, toggling dryRun, and marking modified
                HistoryViewPrefs.markCurrentViewModified()
                syncTimeRangeButtons(TimeRange.SEVEN_DAYS.key)
                (document.getElementById("show-dry-run-checkbox") as HTMLInputElement).checked = false
                // Simulate visibility change
                visibilityStates["portfolio-value-chart"] = mutableMapOf(
                    ChartProps.DATASET_VISIBILITY_DEFAULT to false,
                    "Total Portfolio" to true,
                )
                HistorySessionState.save()

                // Verify session was saved
                val saved = HistorySessionState.load()
                saved?.range shouldBe TimeRange.SEVEN_DAYS.key
                saved?.showDryRun shouldBe false
                saved?.visibility?.get("portfolio-value-chart")?.get(ChartProps.DATASET_VISIBILITY_DEFAULT) shouldBe
                    false

                // Simulate navigation away: clear in-memory state but keep sessionStorage (like page reload)
                // Do not call resetHistoryUiState which would clear session; instead manually reset
                currentRange = TimeRange.THIRTY_DAYS.key
                loadedRange = TimeRange.THIRTY_DAYS.key
                visibilityStates.clear()
                HistoryViewPrefs.resetInteractionState()
                (document.getElementById("show-dry-run-checkbox") as HTMLInputElement).checked = true
                // Select should be back to default before restore
                val selectBefore = document.getElementById("history-views-select") as HTMLSelectElement
                // Refresh to default to simulate fresh page load's initToolbar
                HistoryViewPrefs.refreshSelect(
                    HistoryViewPrefs.loadStore(),
                    selectedId = HistoryViewPrefs.loadStore().defaultId,
                )

                // Now simulate returning to History: restore session
                val restored = HistorySessionState.restoreIfNeeded()
                restored shouldBe true
                currentRange shouldBe TimeRange.SEVEN_DAYS.key
                (document.getElementById("show-dry-run-checkbox") as HTMLInputElement).checked shouldBe false
                visibilityStates["portfolio-value-chart"]?.get(ChartProps.DATASET_VISIBILITY_DEFAULT) shouldBe false
                visibilityStates["portfolio-value-chart"]?.get("Total Portfolio") shouldBe true
                HistoryViewPrefs.hasUserInteracted() shouldBe true
                // Selector should show unsaved
                val selectAfter = document.getElementById("history-views-select") as HTMLSelectElement
                selectAfter.value shouldBe ""

                // loadHistoryAfterSync should use session's range
                HistorySessionState.clear()
                HistoryViewPrefs.resetInteractionState()
                // Save again for loadHistoryAfterSync test
                syncTimeRangeButtons(TimeRange.SEVEN_DAYS.key)
                // Need to set visibility again after reset
                visibilityStates["portfolio-value-chart"] = mutableMapOf(
                    ChartProps.DATASET_VISIBILITY_DEFAULT to false,
                    "Total Portfolio" to true,
                )
                HistoryViewPrefs.markCurrentViewModified()
                HistorySessionState.save()
                // Now reset interaction to simulate fresh load with session present
                HistoryViewPrefs.resetInteractionState()
                // loadHistoryAfterSync should restore session and load 7d
                loadHistoryAfterSync().await()
                currentRange shouldBe TimeRange.SEVEN_DAYS.key
            } finally {
                document.body!!.removeChild(container)
                window.sessionStorage.removeItem(ViewText.HISTORY_SESSION_STORAGE_KEY)
                window.localStorage.removeItem(ViewText.HISTORY_VIEWS_STORAGE_KEY)
                resetHistoryUiState()
            }
        }

        "HistorySessionState handles missing and corrupted storage" {
            resetHistoryUiState()
            window.sessionStorage.removeItem(ViewText.HISTORY_SESSION_STORAGE_KEY)
            try {
                HistorySessionState.load() shouldBe null
                HistorySessionState.restoreIfNeeded() shouldBe false

                window.sessionStorage.setItem(ViewText.HISTORY_SESSION_STORAGE_KEY, "{not-json")
                HistorySessionState.load() shouldBe null

                window.sessionStorage.setItem(
                    ViewText.HISTORY_SESSION_STORAGE_KEY,
                    JSON.stringify(json("range" to "invalid-range")),
                )
                HistorySessionState.load() shouldBe null

                window.sessionStorage.setItem(
                    ViewText.HISTORY_SESSION_STORAGE_KEY,
                    JSON.stringify(
                        json(
                            "range" to TimeRange.SEVEN_DAYS.key,
                            "showDryRun" to true,
                            "visibility" to json(),
                            "selectedViewId" to "",
                            "hasUserInteracted" to true,
                        ),
                    ),
                )
                val loaded = HistorySessionState.load()
                loaded?.range shouldBe TimeRange.SEVEN_DAYS.key

                HistorySessionState.clear()
                HistorySessionState.load() shouldBe null
            } finally {
                window.sessionStorage.removeItem(ViewText.HISTORY_SESSION_STORAGE_KEY)
                resetHistoryUiState()
            }
        }

        "HistorySessionState preserves selected preset vs unsaved" {
            resetHistoryUiState()
            window.sessionStorage.removeItem(ViewText.HISTORY_SESSION_STORAGE_KEY)
            window.localStorage.removeItem(ViewText.HISTORY_VIEWS_STORAGE_KEY)
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.historyViewsDom()
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            registerHistoryGlobals()
            try {
                // Save session with a valid preset selection
                val store = HistoryViewPrefs.loadStore()
                val overviewId = store.defaultId
                // Simulate selecting overview preset
                HistoryViewPrefs.refreshSelect(store, selectedId = overviewId)
                HistoryViewPrefs.setHasUserInteracted(true)
                syncTimeRangeButtons(TimeRange.THIRTY_DAYS.key)
                HistorySessionState.save()

                // Clear and restore
                visibilityStates.clear()
                HistoryViewPrefs.resetInteractionState()
                HistorySessionState.restoreIfNeeded() shouldBe true
                val select = document.getElementById("history-views-select") as HTMLSelectElement
                select.value shouldBe overviewId

                // Now test unsaved case
                HistorySessionState.clear()
                HistoryViewPrefs.markCurrentViewModified()
                syncTimeRangeButtons(TimeRange.SEVEN_DAYS.key)
                HistorySessionState.save()
                HistoryViewPrefs.resetInteractionState()
                // Need to repopulate select before restore
                HistoryViewPrefs.refreshSelect(store, selectedId = overviewId)
                HistorySessionState.restoreIfNeeded() shouldBe true
                val select2 = document.getElementById("history-views-select") as HTMLSelectElement
                select2.value shouldBe ""
            } finally {
                document.body!!.removeChild(container)
                window.sessionStorage.removeItem(ViewText.HISTORY_SESSION_STORAGE_KEY)
                window.localStorage.removeItem(ViewText.HISTORY_VIEWS_STORAGE_KEY)
                resetHistoryUiState()
            }
        }

        "legend onClick toggles dataset and persists session" {
            resetHistoryUiState()
            window.sessionStorage.removeItem(ViewText.HISTORY_SESSION_STORAGE_KEY)
            val container = document.createElement("div")
            container.innerHTML = """<canvas id="portfolio-value-chart"></canvas>"""
            document.body!!.appendChild(container)
            var chartInstance: dynamic = null
            window.asDynamic().Chart = { _: dynamic, config: dynamic ->
                val chartData = config.data
                val isVisibleFn: dynamic =
                    js("""(function(idx) { var ds = this.data.datasets[idx]; return !(ds && ds.hidden); })""")
                val setVisibleFn: dynamic =
                    js(
                        """(function(idx, visible) { var ds = this.data.datasets[idx]; if (ds) ds.hidden = !visible; })""",
                    )
                val instance = jsObject {
                    data = chartData
                    destroy = {}
                    isDatasetVisible = isVisibleFn
                    setDatasetVisibility = setVisibleFn
                    update = {}
                    canvas = json("id" to "portfolio-value-chart")
                }
                chartInstance = instance
                instance
            }
            registerHistoryGlobals()
            try {
                val datasets = arrayOf(
                    json("label" to "Total Portfolio", "data" to emptyArray<dynamic>()),
                    json("label" to "BTC", "data" to emptyArray<dynamic>()),
                )
                // Start with BTC hidden via visibilityStates
                visibilityStates["portfolio-value-chart"] = mutableMapOf("BTC" to false)
                createOrUpdate("portfolio-value-chart", createLineChartConfig(datasets, getClonedChartOptions()))
                // Verify initial hidden
                val initialHidden = chartInstance.data.datasets[1].hidden as? Boolean ?: false
                initialHidden shouldBe true
                // Get the onClick handler from options
                val options = getClonedChartOptions()
                val onClick = options.plugins.legend.onClick
                // Simulate legend click on BTC (index 1) to show it
                val legend = json("chart" to chartInstance)
                val legendItem = json("datasetIndex" to 1)
                onClick(null, legendItem, legend)
                // After click, dataset hidden should be false (visible)
                val afterHidden = chartInstance.data.datasets[1].hidden as? Boolean ?: true
                afterHidden shouldBe false
                // visibilityStates should be updated
                visibilityStates["portfolio-value-chart"]?.get("BTC") shouldBe true
                // Session should contain the toggled visibility
                val sess = HistorySessionState.load()
                sess?.visibility?.get("portfolio-value-chart")?.get("BTC") shouldBe true
                // Also should be marked as modified
                HistoryViewPrefs.hasUserInteracted() shouldBe true
            } finally {
                document.body!!.removeChild(container)
                window.sessionStorage.removeItem(ViewText.HISTORY_SESSION_STORAGE_KEY)
                resetHistoryUiState()
            }
        }
    }
}
