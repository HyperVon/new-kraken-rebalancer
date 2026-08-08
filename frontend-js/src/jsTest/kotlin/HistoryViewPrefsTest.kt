package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.view.util.ChartProps
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.dom.*
import kotlin.js.json

class HistoryViewPrefsTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "HistoryViewPrefs serializes and round-trips store JSON" {
            localStorage.removeItem("kraken.history.views")
            val store =
                HistoryViewsStore(
                    defaultId = "day-total",
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
                                "portfolio-value-chart" to
                                    mapOf("Total Portfolio" to true),
                            ),
                        ),
                )
            HistoryViewPrefs.saveStore(store)
            val loaded = HistoryViewPrefs.loadStore()
            loaded.defaultId shouldBe "day-total"
            loaded.views.any { it.id == "user-1" && it.name == "Custom" && !it.showDryRun } shouldBe true
            loaded.views.count { it.builtIn } shouldBe 4
            localStorage.removeItem("kraken.history.views")
        }

        "HistoryViewPrefs migrates legacy month-pnl defaultId" {
            localStorage.removeItem("kraken.history.views")
            HistoryViewPrefs.mergeBuiltIns(
                HistoryViewsStore(defaultId = "month-pnl", views = emptyList()),
            ).defaultId shouldBe "month-net-cash-flow"
            HistoryViewPrefs.mergeBuiltIns(
                HistoryViewsStore(
                    defaultId = "month-pnl",
                    views =
                    listOf(
                        HistoryViewDef(
                            id = "user-1",
                            name = "Custom",
                            builtIn = false,
                            range = TimeRange.SEVEN_DAYS.key,
                            showDryRun = true,
                            visibility = emptyMap(),
                        ),
                    ),
                ),
            ).defaultId shouldBe "month-net-cash-flow"
            localStorage.setItem(
                "kraken.history.views",
                """{"defaultId":"month-pnl","views":[]}""",
            )
            HistoryViewPrefs.loadStore().defaultId shouldBe "month-net-cash-flow"
            localStorage.removeItem("kraken.history.views")
        }

        "HistoryViewPrefs migrates legacy cumulative-pl-chart visibility key" {
            localStorage.removeItem("kraken.history.views")
            val parsed = HistoryViewPrefs.parseStore(
                json(
                    "defaultId" to "overview",
                    "views" to
                        arrayOf(
                            json(
                                "id" to "user-legacy",
                                "name" to "Legacy chart",
                                "builtIn" to false,
                                "range" to TimeRange.THIRTY_DAYS.key,
                                "showDryRun" to true,
                                "visibility" to
                                    json(
                                        "cumulative-pl-chart" to
                                            json("Total Portfolio" to true),
                                    ),
                            ),
                        ),
                ),
            )
            val parsedView = parsed.views.first { it.id == "user-legacy" }
            parsedView.visibility.containsKey("cumulative-net-cash-flow-chart") shouldBe true
            parsedView.visibility["cumulative-net-cash-flow-chart"]?.get("Total Portfolio") shouldBe true
            parsedView.visibility.containsKey("cumulative-pl-chart") shouldBe false

            localStorage.setItem(
                "kraken.history.views",
                """{"defaultId":"overview","views":[{"id":"user-legacy","name":"Legacy chart","builtIn":false,"range":"30d","showDryRun":true,"visibility":{"cumulative-pl-chart":{"Total Portfolio":true}}}]}""",
            )
            val loadedView = HistoryViewPrefs.loadStore().views.first { it.id == "user-legacy" }
            loadedView.visibility.containsKey("cumulative-net-cash-flow-chart") shouldBe true
            loadedView.visibility["cumulative-net-cash-flow-chart"]?.get("Total Portfolio") shouldBe true
            loadedView.visibility.containsKey("cumulative-pl-chart") shouldBe false
            localStorage.removeItem("kraken.history.views")
        }

        "HistoryViewPrefs preserves valid views around malformed saved entries" {
            localStorage.removeItem("kraken.history.views")
            localStorage.setItem(
                "kraken.history.views",
                """{"defaultId":"user-valid-2","views":[{"id":"user-valid-1","name":"First","builtIn":false,"range":"7d","showDryRun":true,"visibility":{}},null,{}, {"id":"user-valid-2","name":"Second","builtIn":false,"range":"30d","showDryRun":false,"visibility":{}}]}""",
            )

            try {
                val store = HistoryViewPrefs.loadStore()
                store.defaultId shouldBe "user-valid-2"
                store.views.map { it.id }.contains("user-valid-1") shouldBe true
                store.views.map { it.id }.contains("user-valid-2") shouldBe true
            } finally {
                localStorage.removeItem("kraken.history.views")
            }
        }

        "applyView seeds visibilityStates before loadAll" {
            localStorage.removeItem("kraken.history.views")
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.historyViewsDom()
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            window.asDynamic().fetch = mockFetch(mockHistoryFetchHandler(syncProgress = json("seeded" to true)))
            registerHistoryGlobals()
            try {
                val dayTotal = HistoryViewPrefs.builtInViews().first { it.id == "day-total" }
                historyApplyVisibility(dayTotal.visibility)
                visibilityStates["portfolio-value-chart"]?.get(ChartProps.DATASET_VISIBILITY_DEFAULT) shouldBe
                    false
                visibilityStates["portfolio-value-chart"]?.get("Total Portfolio") shouldBe true

                HistoryViewPrefs.applyView("day-total").await()
                currentRange shouldBe TimeRange.TWENTY_FOUR_HOURS.key
                (document.getElementById("show-dry-run-checkbox") as HTMLInputElement).checked shouldBe true

                HistoryViewPrefs.applyView("month-net-cash-flow").await()
                currentRange shouldBe TimeRange.THIRTY_DAYS.key
                (document.getElementById("show-dry-run-checkbox") as HTMLInputElement).checked shouldBe false

                HistoryViewPrefs.markCurrentViewModified()
                syncTimeRangeButtons(TimeRange.SEVEN_DAYS.key)
                loadHistoryAfterSync().await()
                currentRange shouldBe TimeRange.SEVEN_DAYS.key
                val select = document.getElementById("history-views-select") as HTMLSelectElement
                select.value shouldBe ""
                select.selectedOptions.item(0)?.textContent shouldBe "Custom (unsaved)"
                (document.getElementById("history-set-default-btn") as HTMLButtonElement).disabled shouldBe true
                (document.getElementById("history-delete-view-btn") as HTMLButtonElement).disabled shouldBe true

                // Clear session so the next load tests the default-view path without session restore
                HistorySessionState.clear()
                HistoryViewPrefs.resetInteractionState()
                loadHistoryAfterSync().await()
                currentRange shouldBe TimeRange.THIRTY_DAYS.key
            } finally {
                document.body!!.removeChild(container)
                localStorage.removeItem("kraken.history.views")
                resetHistoryUiState()
            }
        }

        "HistoryViewPrefs toolbar save set-default and delete user views" {
            localStorage.removeItem("kraken.history.views")
            resetHistoryUiState()
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.historyViewsDom()
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            val btcAsset = mockSnapshotRecord().assets.getValue(Asset.BTC)
            window.asDynamic().fetch =
                mockFetch(
                    mockHistoryFetchHandler(
                        snapshots =
                        listOf(
                            mockSnapshotRecord(
                                assets =
                                mapOf(
                                    Asset.BTC to btcAsset.copy(valueUSD = "60", balance = "1", currentPercent = "60"),
                                    Asset.USD to btcAsset.copy(
                                        symbol = Asset.USD,
                                        valueUSD = "40",
                                        balance = "40",
                                        currentPercent = "40",
                                    ),
                                ),
                            ),
                            mockSnapshotRecord(
                                assets =
                                mapOf(
                                    Asset.BTC to btcAsset.copy(valueUSD = "70", balance = "1.1", currentPercent = "70"),
                                    Asset.USD to btcAsset.copy(
                                        symbol = Asset.USD,
                                        valueUSD = "30",
                                        balance = "30",
                                        currentPercent = "30",
                                    ),
                                ),
                            ),
                        ),
                        trades = listOf(mockTradeRecord(symbol = Asset.BTC, volume = "1", usdAmount = "100")),
                        stats =
                        mockPortfolioStatsRecord(
                            allTimeHigh = "100",
                            totalTradesExecuted = 1L,
                            totalVolumeTraded = "100",
                            totalFeesPaid = "1",
                        ),
                    ),
                )
            registerHistoryGlobals()
            try {
                HistoryViewPrefs.initToolbar()
                val select = document.getElementById("history-views-select") as HTMLSelectElement
                select.options.length shouldBe 4

                loadAll(TimeRange.THIRTY_DAYS.key).await()

                window.asDynamic().prompt = { _: String -> "My View" }
                (document.getElementById("history-save-view-btn") as HTMLButtonElement).click()
                select.options.length shouldBe 5
                val userId = select.value
                userId.startsWith("user-") shouldBe true

                (document.getElementById("history-set-default-btn") as HTMLButtonElement).click()
                HistoryViewPrefs.loadStore().defaultId shouldBe userId

                (document.getElementById("history-delete-view-btn") as HTMLButtonElement).disabled shouldBe false
                (document.getElementById("history-delete-view-btn") as HTMLButtonElement).click()
                HistoryViewPrefs.applyDefaultView().await()
                val afterDelete = document.getElementById("history-views-select") as HTMLSelectElement
                afterDelete.options.length shouldBe 4
                HistoryViewPrefs.loadStore().defaultId shouldBe "overview"

                HistoryViewPrefs.applyView("overview").await()
                (document.getElementById("history-delete-view-btn") as HTMLButtonElement).disabled shouldBe true

                HistoryViewPrefs.mergeBuiltIns(
                    HistoryViewsStore(defaultId = "missing", views = emptyList()),
                ).defaultId shouldBe "overview"

                HistoryViewPrefs.parseStore(
                    json("defaultId" to "week-allocation", "views" to emptyArray<dynamic>()),
                ).defaultId shouldBe "week-allocation"

                localStorage.setItem("kraken.history.views", "{not-json")
                HistoryViewPrefs.loadStore().defaultId shouldBe "overview"

                // Whitespace-only prompt is treated as cancel (no new view option added).
                window.asDynamic().prompt = { _: String -> "  " }
                val beforeCancel = (
                    document.getElementById("history-views-select") as HTMLSelectElement
                    ).options.length
                (document.getElementById("history-save-view-btn") as HTMLButtonElement).click()
                (document.getElementById("history-views-select") as HTMLSelectElement).options.length shouldBe
                    beforeCancel
            } finally {
                document.body!!.removeChild(container)
                localStorage.removeItem("kraken.history.views")
                resetHistoryUiState()
            }
        }
    }
}
