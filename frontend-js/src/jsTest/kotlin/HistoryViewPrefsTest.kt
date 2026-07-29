package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.HistoryViewIds
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.HtmlTags
import com.gemini.krakenbot.view.util.ViewText
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

        "HistoryViewPrefs migrates legacy month-pnl defaultId" {
            localStorage.removeItem(ViewText.HISTORY_VIEWS_STORAGE_KEY)
            HistoryViewPrefs.mergeBuiltIns(
                HistoryViewsStore(defaultId = "month-pnl", views = emptyList()),
            ).defaultId shouldBe HistoryViewIds.MONTH_NET_CASH_FLOW
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
            ).defaultId shouldBe HistoryViewIds.MONTH_NET_CASH_FLOW
            localStorage.setItem(
                ViewText.HISTORY_VIEWS_STORAGE_KEY,
                """{"defaultId":"month-pnl","views":[]}""",
            )
            HistoryViewPrefs.loadStore().defaultId shouldBe HistoryViewIds.MONTH_NET_CASH_FLOW
            localStorage.removeItem(ViewText.HISTORY_VIEWS_STORAGE_KEY)
        }

        "HistoryViewPrefs migrates legacy cumulative-pl-chart visibility key" {
            localStorage.removeItem(ViewText.HISTORY_VIEWS_STORAGE_KEY)
            val parsed = HistoryViewPrefs.parseStore(
                json(
                    "defaultId" to HistoryViewIds.OVERVIEW,
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
                                            json(ViewText.TOTAL_PORTFOLIO to true),
                                    ),
                            ),
                        ),
                ),
            )
            val parsedView = parsed.views.first { it.id == "user-legacy" }
            parsedView.visibility.containsKey(HtmlIds.CUMULATIVE_NET_CASH_FLOW_CHART) shouldBe true
            parsedView.visibility[HtmlIds.CUMULATIVE_NET_CASH_FLOW_CHART]?.get(ViewText.TOTAL_PORTFOLIO) shouldBe true
            parsedView.visibility.containsKey("cumulative-pl-chart") shouldBe false

            localStorage.setItem(
                ViewText.HISTORY_VIEWS_STORAGE_KEY,
                """{"defaultId":"overview","views":[{"id":"user-legacy","name":"Legacy chart","builtIn":false,"range":"30d","showDryRun":true,"visibility":{"cumulative-pl-chart":{"Total Portfolio":true}}}]}""",
            )
            val loadedView = HistoryViewPrefs.loadStore().views.first { it.id == "user-legacy" }
            loadedView.visibility.containsKey(HtmlIds.CUMULATIVE_NET_CASH_FLOW_CHART) shouldBe true
            loadedView.visibility[HtmlIds.CUMULATIVE_NET_CASH_FLOW_CHART]?.get(ViewText.TOTAL_PORTFOLIO) shouldBe true
            loadedView.visibility.containsKey("cumulative-pl-chart") shouldBe false
            localStorage.removeItem(ViewText.HISTORY_VIEWS_STORAGE_KEY)
        }

        "HistoryViewPrefs preserves valid views around malformed saved entries" {
            localStorage.removeItem(ViewText.HISTORY_VIEWS_STORAGE_KEY)
            localStorage.setItem(
                ViewText.HISTORY_VIEWS_STORAGE_KEY,
                """{"defaultId":"user-valid-2","views":[{"id":"user-valid-1","name":"First","builtIn":false,"range":"7d","showDryRun":true,"visibility":{}},null,{}, {"id":"user-valid-2","name":"Second","builtIn":false,"range":"30d","showDryRun":false,"visibility":{}}]}""",
            )

            try {
                val store = HistoryViewPrefs.loadStore()
                store.defaultId shouldBe "user-valid-2"
                store.views.map { it.id }.contains("user-valid-1") shouldBe true
                store.views.map { it.id }.contains("user-valid-2") shouldBe true
            } finally {
                localStorage.removeItem(ViewText.HISTORY_VIEWS_STORAGE_KEY)
            }
        }

        "applyView seeds visibilityStates before loadAll" {
            localStorage.removeItem(ViewText.HISTORY_VIEWS_STORAGE_KEY)
            resetHistoryUiState()
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.historyViewsDom()
            document.body!!.appendChild(container)
            window.asDynamic().Chart = mockChartConstructor()
            window.asDynamic().fetch = mockFetch(mockHistoryFetchHandler(syncProgress = json("seeded" to true)))
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

                // Whitespace-only prompt is treated as cancel (no new view option added).
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
    }
}
