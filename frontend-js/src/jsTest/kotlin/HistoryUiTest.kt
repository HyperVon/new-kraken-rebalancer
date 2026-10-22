package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.HistoryRangeCss
import com.gemini.krakenbot.view.util.HistoryRangeIds
import com.gemini.krakenbot.view.util.HistoryRangeProps
import com.gemini.krakenbot.view.util.HistoryRangeQuery
import com.gemini.krakenbot.view.util.HtmlIds
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.js.js
import kotlin.js.json

class HistoryUiTest :
    StringSpec({
        "tab history preserves range filters scope and independent account-record pages" {
            val initial =
                "?range=custom&start=2026-10-01&end=2026-10-19&tz=Europe%2FParis&scope=older&asset=XBT&" +
                    "pair=XXBTZUSD&type=buy&tradesOffset=50&ledgersOffset=100"
            val activity = historyTabsQuery(initial, HistoryRangeQuery.TAB_ACTIVITY, HistoryRangeQuery.ACTIVITY_TRADES)
            val dataStatus = historyTabsQuery(activity, HistoryRangeQuery.TAB_DATA_STATUS)

            historyPageTabState(activity) shouldBe
                HistoryPageTabState("activity", "trades")
            historyPageTabState(dataStatus) shouldBe
                HistoryPageTabState("data", "trades")
            historyPageTabState("?range=30d") shouldBe HistoryPageTabState("portfolio", "bot")
            historyPageTabState("?tab=unknown&activity=unknown") shouldBe
                HistoryPageTabState("portfolio", "bot")
            historyPageTabState(historyTabsQuery("?range=30d", "unknown")) shouldBe
                HistoryPageTabState("portfolio", "bot")
            val params = queryParameters(activity)
            params.get("range") as? String shouldBe "custom"
            params.get("start") as? String shouldBe "2026-10-01"
            params.get("end") as? String shouldBe "2026-10-19"
            params.get("tz") as? String shouldBe "Europe/Paris"
            params.get("scope") as? String shouldBe "older"
            params.get("asset") as? String shouldBe "XBT"
            params.get("tradesOffset") as? String shouldBe "50"
            params.get("ledgersOffset") as? String shouldBe "100"
            historyPageTabState(activity).tab shouldBe "activity"
        }

        "account progress keeps older scan gaps separate from the frozen value target" {
            val root = document.createElement("div") as HTMLElement
            root.innerHTML = """
                <p id="history-account-history-summary"></p>
                <p id="history-value-period"></p>
                <p id="history-values-available"></p>
                <p id="history-value-last-success"></p>
                <div id="history-import-stage"><span data-history-stage-state></span><p data-history-stage-detail></p></div>
                <div id="history-balance-stage"><span data-history-stage-state></span><p data-history-stage-detail></p></div>
                <div id="history-value-stage"><span data-history-stage-state></span><p data-history-stage-detail></p></div>
                <div id="history-incomplete-details" hidden><ul id="history-incomplete-reasons"></ul></div>
                <p id="history-value-next-retry" hidden></p>
            """.trimIndent()
            document.body!!.appendChild(root)
            val progress = arrayOf(
                json(
                    "feed" to "TRADES",
                    "backfillState" to "COMPLETE",
                    "coveredFromIso" to "2019-01-01T00:00:00Z",
                    "coveredToIso" to "2026-10-20T00:00:00Z",
                    "coveredGapCount" to 1,
                    "targetCoverageComplete" to true,
                ),
                json(
                    "feed" to "LEDGERS",
                    "backfillState" to "COMPLETE",
                    "coveredFromIso" to "2019-01-01T00:00:00Z",
                    "coveredToIso" to "2026-10-20T00:00:00Z",
                    "coveredGapCount" to 0,
                    "targetCoverageComplete" to true,
                ),
            )
            try {
                updateHistoryAccountProgress(
                    json(
                        "tradeRecordsImported" to 100,
                        "ledgerRecordsImported" to 200,
                        "availableValueFromIso" to "2025-10-21T00:00:00Z",
                        "availableValueToIso" to "2026-10-20T00:00:00Z",
                        "availableValueDays" to 365,
                        "progress" to progress,
                        "jobs" to emptyArray<Any>(),
                        "valuation" to json(
                            "state" to "COMPLETE",
                            "stage" to "COMPLETE",
                            "targetStartDay" to "2025-10-21",
                            "targetEndDay" to "2026-10-20",
                            "targetDays" to 365,
                            "balancesProcessed" to 365,
                            "balancesValid" to 365,
                            "balancesBlocked" to 0,
                            "requiredPriceTasks" to 1,
                            "completedPriceTasks" to 1,
                            "completeValueDays" to 365,
                            "blockedValueDays" to 0,
                            "lastSuccessAtIso" to "2026-10-21T00:00:00Z",
                        ),
                    ),
                )

                root.querySelector("#history-import-stage [data-history-stage-state]")!!.textContent shouldBe
                    "Historical import complete"
                root.querySelector("#history-import-stage [data-history-stage-state]")!!
                    .classList.contains(HistoryRangeCss.CHIP_OK) shouldBe true
                root.querySelector("#history-import-stage [data-history-stage-detail]")!!.textContent!! shouldContain
                    "scanned with 1 interior gaps"
                root.querySelector("#history-balance-stage [data-history-stage-state]")!!.textContent shouldBe
                    "Complete"
                root.querySelector("#history-value-stage [data-history-stage-state]")!!.textContent shouldBe "Complete"
                root.querySelector("#history-account-history-summary")!!.textContent shouldContain
                    "checking for new activity automatically"
                root.querySelector("#history-values-available")!!.textContent shouldContain "365 days"
            } finally {
                root.remove()
            }
        }

        "account progress distinguishes unavailable importing and durable retry states" {
            val root = document.createElement("div") as HTMLElement
            root.innerHTML = """
                <p id="history-account-history-summary"></p>
                <p id="history-value-period"></p>
                <p id="history-values-available"></p>
                <p id="history-value-last-success"></p>
                <p id="history-value-next-retry" hidden></p>
                <div id="history-import-stage"><span data-history-stage-state></span><p data-history-stage-detail></p></div>
                <div id="history-balance-stage"><span data-history-stage-state></span><p data-history-stage-detail></p></div>
                <div id="history-value-stage"><span data-history-stage-state></span><p data-history-stage-detail></p></div>
                <div id="history-incomplete-details" hidden><ul id="history-incomplete-reasons"></ul></div>
            """.trimIndent()
            document.body!!.appendChild(root)
            try {
                updateHistoryAccountProgress(json("progress" to emptyArray<Any>(), "jobs" to emptyArray<Any>()))
                root.querySelector("#history-import-stage [data-history-stage-state]")!!.textContent shouldBe
                    "Unavailable"
                root.querySelector("#history-account-history-summary")!!.textContent shouldContain
                    "identity is not verified"

                val pendingProgress = arrayOf(
                    json("feed" to "TRADES", "backfillState" to "PENDING"),
                    json("feed" to "LEDGERS", "backfillState" to "PENDING"),
                )
                updateHistoryAccountProgress(
                    json("progress" to pendingProgress, "jobs" to emptyArray<Any>()),
                )
                root.querySelector("#history-import-stage [data-history-stage-state]")!!.textContent shouldBe
                    "Waiting to import records"

                val scanningProgress = arrayOf(
                    json("feed" to "TRADES", "backfillState" to "RUNNING"),
                    json("feed" to "LEDGERS", "backfillState" to "PENDING"),
                )
                val scanningJob = arrayOf(
                    json(
                        "purpose" to "BACKFILL",
                        "state" to "RUNNING",
                        "windowStartIso" to "2025-01-01T00:00:00Z",
                        "windowEndIso" to "2025-02-01T00:00:00Z",
                        "updatedAtIso" to "2026-10-21T00:00:00Z",
                        "lastError" to "Import page is retrying.",
                        "nextRetryAtIso" to "2026-10-21T00:45:00Z",
                    ),
                )
                updateHistoryAccountProgress(
                    json("progress" to scanningProgress, "jobs" to scanningJob),
                )
                root.querySelector("#history-import-stage [data-history-stage-state]")!!.textContent shouldBe
                    "Importing records"
                val importDetail = root.querySelector(
                    "#history-import-stage [data-history-stage-detail]",
                )!!.textContent!!
                importDetail shouldContain "Total not provided by Kraken"
                importDetail shouldContain "Current scan window: 2025-01-01 – 2025-02-01 (UTC)"
                importDetail shouldContain "Import page is retrying"
                importDetail shouldContain "Retry scheduled for"
                importDetail shouldNotContain "%"

                val retryAt = "2026-10-21T01:00:00Z"
                val failedImport = arrayOf(
                    json(
                        "feed" to "TRADES",
                        "backfillState" to "FAILED",
                        "lastError" to "Kraken rate limit reached.",
                        "nextRetryAtIso" to retryAt,
                    ),
                    json("feed" to "LEDGERS", "backfillState" to "COMPLETE"),
                )
                updateHistoryAccountProgress(
                    json("progress" to failedImport, "jobs" to emptyArray<Any>()),
                )
                root.querySelector("#history-import-stage [data-history-stage-state]")!!.textContent shouldBe
                    "Needs attention"
                root.querySelector("#history-import-stage [data-history-stage-detail]")!!.textContent shouldContain
                    "Kraken rate limit reached"
                root.querySelector("#history-account-history-summary")!!.textContent shouldContain
                    "exchange record import"
                root.querySelector("#history-value-next-retry")!!.textContent shouldContain "Retry scheduled for"

                val importedCoverage = arrayOf(
                    json(
                        "feed" to "TRADES",
                        "backfillState" to "COMPLETE",
                        "coveredFromIso" to "2019-01-01T00:00:00Z",
                        "coveredToIso" to "2026-10-20T00:00:00Z",
                        "coveredGapCount" to 0,
                    ),
                    json(
                        "feed" to "LEDGERS",
                        "backfillState" to "COMPLETE",
                        "coveredFromIso" to "2019-01-01T00:00:00Z",
                        "coveredToIso" to "2026-10-20T00:00:00Z",
                        "coveredGapCount" to 0,
                    ),
                )
                updateHistoryAccountProgress(
                    json("progress" to importedCoverage, "jobs" to emptyArray<Any>()),
                )
                root.querySelector("#history-import-stage [data-history-stage-state]")!!.textContent shouldBe
                    "Historical import complete"
                root.querySelector("#history-account-history-summary")!!.textContent shouldContain
                    "establishing daily balances"

                updateHistoryAccountProgress(
                    json(
                        "progress" to importedCoverage,
                        "jobs" to emptyArray<Any>(),
                        "valuation" to json(
                            "state" to "PENDING",
                            "stage" to "ESTABLISH_BALANCES",
                            "targetStartDay" to "2025-10-21",
                            "targetEndDay" to "2026-10-20",
                            "targetDays" to 365,
                            "balancesProcessed" to 0,
                            "balancesValid" to 0,
                            "balancesBlocked" to 0,
                            "requiredPriceTasks" to null,
                            "completedPriceTasks" to 0,
                            "completeValueDays" to 0,
                            "blockedValueDays" to 0,
                        ),
                    ),
                )
                root.querySelector("#history-account-history-summary")!!.textContent shouldContain
                    "establishing daily balances"
                root.querySelector("#history-balance-stage [data-history-stage-state]")!!.textContent shouldBe
                    "Establishing balances"
                root.querySelector("#history-value-stage [data-history-stage-state]")!!.textContent shouldBe
                    "Establishing balances"
                root.querySelector("#history-value-stage [data-history-stage-detail]")!!.textContent shouldContain
                    "Determining required prices"

                updateHistoryAccountProgress(
                    json(
                        "progress" to importedCoverage,
                        "jobs" to emptyArray<Any>(),
                        "valuation" to json(
                            "state" to "RUNNING",
                            "stage" to "PRICE_AND_VALUE",
                            "targetStartDay" to "2025-10-21",
                            "targetEndDay" to "2026-10-20",
                            "targetDays" to 365,
                            "balancesProcessed" to 20,
                            "balancesValid" to 20,
                            "balancesBlocked" to 0,
                            "requiredPriceTasks" to 10,
                            "completedPriceTasks" to 4,
                            "completeValueDays" to 5,
                            "blockedValueDays" to 0,
                            "startedAtIso" to "2026-10-21T00:00:00Z",
                            "lastProgressAtIso" to "2026-10-21T00:30:00Z",
                        ),
                    ),
                )
                root.querySelector("#history-account-history-summary")!!.textContent shouldContain
                    "calculating daily values"
                root.querySelector("#history-balance-stage [data-history-stage-state]")!!.textContent shouldBe
                    "Computing balances"
                root.querySelector("#history-value-stage [data-history-stage-state]")!!.textContent shouldBe
                    "Calculating values"
                root.querySelector("#history-value-stage [data-history-stage-detail]")!!.textContent shouldContain
                    "4 / 10 required asset-days priced"

                val completeProgress = arrayOf(
                    json("feed" to "TRADES", "backfillState" to "COMPLETE", "targetCoverageComplete" to true),
                    json("feed" to "LEDGERS", "backfillState" to "COMPLETE", "targetCoverageComplete" to true),
                )
                updateHistoryAccountProgress(
                    json(
                        "progress" to completeProgress,
                        "jobs" to emptyArray<Any>(),
                        "valuation" to json(
                            "state" to "WAITING",
                            "stage" to "PRICE_AND_VALUE",
                            "targetStartDay" to "2025-10-21",
                            "targetEndDay" to "2026-10-20",
                            "targetDays" to 365,
                            "balancesProcessed" to 10,
                            "balancesValid" to 9,
                            "balancesBlocked" to 1,
                            "requiredPriceTasks" to null,
                            "completedPriceTasks" to 0,
                            "completeValueDays" to 0,
                            "blockedValueDays" to 365,
                            "startedAtIso" to "2026-10-21T00:00:00Z",
                            "lastProgressAtIso" to "2026-10-21T00:30:00Z",
                            "nextRetryAtIso" to retryAt,
                            "blockedValueReasons" to arrayOf("BTC · 2025-10-21: price unavailable"),
                        ),
                    ),
                )
                root.querySelector("#history-account-history-summary")!!.textContent shouldContain "History paused"
                root.querySelector("#history-value-stage [data-history-stage-state]")!!.textContent shouldBe
                    "Waiting to retry"
                root.querySelector("#history-value-stage [data-history-stage-detail]")!!.textContent shouldContain
                    "Determining required prices"
                root.querySelector("#history-value-next-retry")!!.textContent shouldContain "Retry scheduled for"
                (root.querySelector("#history-incomplete-details") as HTMLElement).hidden shouldBe false
                root.querySelector("#history-incomplete-reasons")!!.textContent shouldContain "BTC · 2025-10-21"

                val incompleteImport = arrayOf(
                    json("feed" to "TRADES", "backfillState" to "COMPLETE", "targetCoverageComplete" to false),
                    json("feed" to "LEDGERS", "backfillState" to "COMPLETE", "targetCoverageComplete" to true),
                )
                updateHistoryAccountProgress(
                    json(
                        "progress" to incompleteImport,
                        "jobs" to emptyArray<Any>(),
                        "valuation" to json(
                            "state" to "NEEDS_ATTENTION",
                            "stage" to "PRICE_AND_VALUE",
                            "targetStartDay" to "2025-10-21",
                            "targetEndDay" to "2026-10-20",
                            "targetDays" to 365,
                            "balancesProcessed" to 365,
                            "balancesValid" to 364,
                            "balancesBlocked" to 1,
                            "requiredPriceTasks" to 4,
                            "completedPriceTasks" to 3,
                            "completeValueDays" to 364,
                            "blockedValueDays" to 1,
                            "lastError" to "One target date lacks a valid price.",
                        ),
                    ),
                )
                root.querySelector("#history-import-stage [data-history-stage-state]")!!.textContent shouldBe
                    "Needs attention"
                root.querySelector("#history-balance-stage [data-history-stage-state]")!!.textContent shouldBe
                    "Needs attention"
                root.querySelector("#history-value-stage [data-history-stage-state]")!!.textContent shouldBe
                    "Needs attention"
                root.querySelector("#history-account-history-summary")!!.textContent shouldContain
                    "1 date needs attention"
                root.querySelector("#history-incomplete-reasons")!!.textContent shouldContain "One target date"

                updateHistoryAccountProgress(
                    json(
                        "progress" to completeProgress,
                        "jobs" to emptyArray<Any>(),
                        "valuation" to json(
                            "state" to "NEEDS_ATTENTION",
                            "stage" to "PRICE_AND_VALUE",
                            "targetStartDay" to "2025-10-21",
                            "targetEndDay" to "2026-10-20",
                            "targetDays" to 365,
                            "balancesProcessed" to 365,
                            "balancesValid" to 363,
                            "balancesBlocked" to 2,
                            "requiredPriceTasks" to 4,
                            "completedPriceTasks" to 2,
                            "completeValueDays" to 363,
                            "blockedValueDays" to 2,
                            "blockedValueReasons" to arrayOf("BTC · 2025-10-21: price unavailable"),
                        ),
                    ),
                )
                root.querySelector("#history-account-history-summary")!!.textContent shouldContain
                    "2 dates need attention"
                root.querySelector("#history-incomplete-reasons")!!.textContent shouldContain "BTC"

                updateHistoryAccountProgress(
                    json(
                        "progress" to arrayOf(
                            json("feed" to "TRADES", "backfillState" to "BLOCKED", "lastError" to "Import blocked."),
                        ),
                        "jobs" to emptyArray<Any>(),
                    ),
                )
                root.querySelector("#history-import-stage [data-history-stage-state]")!!.textContent shouldBe
                    "Needs attention"
                root.querySelector("#history-account-history-summary")!!.textContent shouldContain
                    "exchange record import needs attention"

                updateHistoryAccountProgress(
                    json(
                        "progress" to completeProgress,
                        "jobs" to emptyArray<Any>(),
                        "valuation" to json(
                            "state" to "WAITING",
                            "stage" to "PRICE_AND_VALUE",
                            "targetStartDay" to "2025-10-21",
                            "targetEndDay" to "2026-10-20",
                            "targetDays" to 365,
                            "balancesProcessed" to 365,
                            "balancesValid" to 365,
                            "balancesBlocked" to 0,
                            "requiredPriceTasks" to 4,
                            "completedPriceTasks" to 2,
                            "completeValueDays" to 363,
                            "blockedValueDays" to 2,
                            "lastError" to "Price request paused.",
                            "nextRetryAtIso" to retryAt,
                        ),
                    ),
                )
                (root.querySelector("#history-incomplete-details") as HTMLElement).hidden shouldBe true
                root.querySelector("#history-account-history-summary")!!.textContent shouldContain "History paused"
            } finally {
                root.remove()
            }
        }

        "activity cursor pages keep a reversible URL stack and request exactly 50 rows" {
            val first = "?range=7d&scope=older&tab=activity&activity=bot&asset=XBT"
            val second = historyActivityNextPageQuery(first, "cursor-one")!!
            val third = historyActivityNextPageQuery(second, "cursor-two")!!

            historyActivityCursorStack(third) shouldBe listOf("cursor-one", "cursor-two")
            historyActivityCursorStack(historyActivityPreviousPageQuery(third)!!) shouldBe listOf("cursor-one")
            historyActivityCursorStack(historyActivityPreviousPageQuery(second)!!) shouldBe emptyList()
            val request = queryParameters(historyActivityApiUrl(third))
            request.get("limit") as? String shouldBe "50"
            request.get("cursor") as? String shouldBe "cursor-two"
            request.get("preset") as? String shouldBe "7d"
            request.get("scope") as? String shouldBe "older"
            request.get("asset") as? String shouldBe null
        }

        "activity table enforces 50-row pages and keeps previous and next cursors" {
            val root = document.createElement("div") as HTMLElement
            root.innerHTML = """
                <table><tbody id="${HistoryRangeIds.ACTIVITY_BODY}"></tbody></table>
                <span id="${HistoryRangeIds.ACTIVITY_RANGE}"></span>
                <a id="${HistoryRangeIds.ACTIVITY_PREVIOUS}" hidden></a>
                <a id="${HistoryRangeIds.ACTIVITY_NEXT}" hidden></a>
            """.trimIndent()
            document.body!!.appendChild(root)
            val items = (1..51).map { index ->
                json(
                    "occurredAt" to "2026-10-19T14:00:00Z",
                    "outcome" to if (index == 1) "Dry-run plan" else "No trade",
                    "mode" to "ACCOUNT",
                    "cycleId" to "cycle-$index",
                    "actions" to arrayOf("checked positions", "recorded outcome"),
                )
            }.toTypedArray()
            val firstQuery = "?range=30d&tab=activity&activity=bot"
            try {
                updateHistoryActivities(
                    json("items" to items, "hasMore" to true, "nextCursor" to "cursor-one"),
                    firstQuery,
                )

                root.querySelectorAll("#${HistoryRangeIds.ACTIVITY_BODY} tr").length shouldBe 50
                root.querySelector("#${HistoryRangeIds.ACTIVITY_RANGE}")!!.textContent shouldBe "1–50"
                (root.querySelector("#${HistoryRangeIds.ACTIVITY_NEXT}") as HTMLElement).hidden shouldBe false
                (root.querySelector("#${HistoryRangeIds.ACTIVITY_PREVIOUS}") as HTMLElement).hidden shouldBe true
                val nextQuery = root.querySelector("#${HistoryRangeIds.ACTIVITY_NEXT}")!!
                    .getAttribute("href")!!.substringAfter('?').substringBefore('#')
                historyActivityCursorStack(nextQuery) shouldBe listOf("cursor-one")

                updateHistoryActivities(
                    json("items" to items.take(3).toTypedArray(), "hasMore" to false),
                    nextQuery,
                )
                root.querySelectorAll("#${HistoryRangeIds.ACTIVITY_BODY} tr").length shouldBe 3
                root.querySelector("#${HistoryRangeIds.ACTIVITY_RANGE}")!!.textContent shouldBe "51–53"
                (root.querySelector("#${HistoryRangeIds.ACTIVITY_PREVIOUS}") as HTMLElement).hidden shouldBe false
                (root.querySelector("#${HistoryRangeIds.ACTIVITY_NEXT}") as HTMLElement).hidden shouldBe true
            } finally {
                root.remove()
            }
        }

        "trade details render source values and authoritative IDs but never raw JSON" {
            val root = document.createElement("div") as HTMLElement
            root.innerHTML = "<table><tbody id=\"${HtmlIds.HISTORY_TRADES_BODY}\"></tbody></table>"
            document.body!!.appendChild(root)
            val rawRecordJson = "{\"private\":\"must-not-render\"}"
            try {
                updateHistoryTable(
                    "trades",
                    json(
                        "items" to arrayOf(
                            json(
                                "tradeId" to "exchange-trade-1",
                                "orderTxid" to "exchange-order-1",
                                "postTxid" to "exchange-post-1",
                                "ledgerIds" to arrayOf("exchange-ledger-1"),
                                "time" to "2026-10-19T14:00:00Z",
                                "pair" to "XXBTZUSD",
                                "type" to "buy",
                                "volume" to "0.25",
                                "baseAsset" to "XBT",
                                "price" to "60000.00",
                                "quoteCurrency" to "USD",
                                "cost" to "15000.00",
                                "fee" to "15.00",
                                "feeCurrency" to "USD",
                                "isValidRecord" to true,
                                "rawRecordJson" to rawRecordJson,
                            ),
                        ),
                        "total" to 1,
                        "hasMore" to false,
                    ),
                    "?range=30d&tab=activity&activity=trades",
                    HistoryRangeProps.RECORD_PAGE_SIZE,
                )

                val row = root.querySelector("#${HtmlIds.HISTORY_TRADES_BODY} tr")!!
                row.textContent shouldContain "0.25 XBT"
                row.textContent shouldContain "60000.00 USD"
                row.textContent shouldContain "exchange-order-1"
                row.textContent shouldContain "Valid record"
                row.textContent shouldContain "Unknown"
                row.innerHTML shouldNotContain rawRecordJson
                row.innerHTML shouldNotContain "must-not-render"
            } finally {
                root.remove()
            }
        }

        "CSV exports retain selected date windows, scopes, and supported filters" {
            val query =
                "?range=custom&start=2026-10-01&end=2026-10-19&tz=Europe%2FParis&scope=older&mode=simulation&" +
                    "asset=XBT&pair=XXBTZUSD&type=buy&tab=activity&activity=trades&tradesOffset=50"
            val url = historyExportUrl(
                "trades",
                query,
                "2026-10-01T00:00:00Z",
                "2026-10-20T00:00:00Z",
            )
            val params = queryParameters(url)
            val activityUrl = historyExportUrl(
                "activity",
                query,
                "2026-10-01T00:00:00Z",
                "2026-10-20T00:00:00Z",
            )
            val activityParams = queryParameters(activityUrl)
            val ledgerUrl = historyExportUrl("ledgers", query, null, null)
            val ledgerParams = queryParameters(ledgerUrl)
            val allUrl = historyExportUrl("trades", "?tab=activity", null, "2026-10-20T00:00:00Z", "all")
            val allParams = queryParameters(allUrl)
            val dailyValuesUrl = historyExportUrl(
                "account-values",
                "?range=custom&start=2025-10-21&end=2026-10-20&tz=UTC",
                "2025-10-21T00:00:00Z",
                "2026-10-21T00:00:00Z",
                "custom",
            )
            val dailyHoldingsUrl = historyExportUrl(
                "account-holdings",
                "?tab=portfolio&range=1y",
                null,
                null,
                date = "2026-03-09",
            )

            url.substringBefore('?') shouldBe "/api/history/export/trades"
            ledgerUrl.substringBefore('?') shouldBe "/api/history/export/ledgers"
            activityUrl.substringBefore('?') shouldBe "/api/history/export/activity"
            params.get("preset") as? String shouldBe "custom"
            params.get("start") as? String shouldBe "2026-10-01"
            params.get("end") as? String shouldBe "2026-10-19"
            params.get("tz") as? String shouldBe "Europe/Paris"
            params.get("scope") as? String shouldBe "older"
            params.get("asset") as? String shouldBe "XBT"
            params.get("pair") as? String shouldBe "XXBTZUSD"
            params.get("type") as? String shouldBe "buy"
            params.get("from") as? String shouldBe "2026-10-01T00:00:00Z"
            params.get("to") as? String shouldBe "2026-10-20T00:00:00Z"
            params.get("tradesOffset") as? String shouldBe null
            listOf(activityParams, ledgerParams).forEach { exportParams ->
                exportParams.get("preset") as? String shouldBe "custom"
                exportParams.get("start") as? String shouldBe "2026-10-01"
                exportParams.get("end") as? String shouldBe "2026-10-19"
                exportParams.get("tz") as? String shouldBe "Europe/Paris"
                exportParams.get("scope") as? String shouldBe "older"
                exportParams.get("mode") as? String shouldBe "simulation"
            }
            activityParams.get("asset") as? String shouldBe null
            activityParams.get("pair") as? String shouldBe null
            activityParams.get("type") as? String shouldBe null
            ledgerParams.get("asset") as? String shouldBe "XBT"
            ledgerParams.get("pair") as? String shouldBe null
            ledgerParams.get("type") as? String shouldBe "buy"
            val noTimezoneParams = queryParameters(
                historyExportUrl(
                    "activity",
                    "?range=30d",
                    null,
                    null,
                    fallbackTimezone = "Pacific/Auckland",
                ),
            )
            noTimezoneParams.get("tz") as? String shouldBe "Pacific/Auckland"
            val blankTimezoneParams = queryParameters(
                historyExportUrl(
                    "activity",
                    "?range=30d&tz=%20%20",
                    null,
                    null,
                    fallbackTimezone = "Pacific/Auckland",
                ),
            )
            blankTimezoneParams.get("tz") as? String shouldBe "Pacific/Auckland"
            allParams.get("preset") as? String shouldBe "all"
            allParams.get("from") as? String shouldBe null
            allParams.get("to") as? String shouldBe "2026-10-20T00:00:00Z"
            dailyValuesUrl.substringBefore('?') shouldBe "/api/history/export/account-values"
            queryParameters(dailyValuesUrl).get("start") as? String shouldBe "2025-10-21"
            dailyHoldingsUrl.substringBefore('?') shouldBe "/api/history/export/account-holdings"
            queryParameters(dailyHoldingsUrl).get("date") as? String shouldBe "2026-03-09"
        }

        "history tab panels follow query state while the cursor page remains selected" {
            val root = document.createElement("div") as HTMLElement
            root.innerHTML = """
                <nav id="${HistoryRangeIds.TAB_NAV}">
                  <a data-history-tab="portfolio"></a><a data-history-tab="activity"></a>
                  <a data-history-tab="data"></a>
                </nav>
                <div data-history-panel="portfolio" id="${HistoryRangeIds.PANEL_PORTFOLIO}"></div>
                <div data-history-panel="activity" id="${HistoryRangeIds.PANEL_ACTIVITY}"></div>
                <div data-history-panel="data" id="${HistoryRangeIds.PANEL_DATA_STATUS}"></div>
                <nav id="${HistoryRangeIds.ACTIVITY_NAV}">
                  <a data-history-activity-tab="bot"></a><a data-history-activity-tab="trades"></a>
                  <a data-history-activity-tab="transactions"></a>
                </nav>
                <div data-history-activity-panel="bot"></div>
                <div data-history-activity-panel="trades"></div>
                <div data-history-activity-panel="transactions"></div>
            """.trimIndent()
            document.body!!.appendChild(root)
            try {
                val pageTwo = "?range=30d&tab=activity&activity=bot&scope=older&activityCursors=cursor-one"
                updateHistoryTabPanels(pageTwo)

                (root.querySelector("#${HistoryRangeIds.PANEL_ACTIVITY}") as HTMLElement).hidden shouldBe false
                (root.querySelector("#${HistoryRangeIds.PANEL_DATA_STATUS}") as HTMLElement).hidden shouldBe true
                (root.querySelector("[data-history-activity-panel='bot']") as HTMLElement).hidden shouldBe false
                historyActivityCursorStack(pageTwo) shouldBe listOf("cursor-one")
                historyPageTabState(pageTwo).tab shouldBe "activity"
                historyActivityRefreshKeepsPage(pageTwo) shouldBe true

                val dataStatus = historyTabsQuery(pageTwo, HistoryRangeQuery.TAB_DATA_STATUS)
                updateHistoryTabPanels(dataStatus)
                (root.querySelector("#${HistoryRangeIds.PANEL_DATA_STATUS}") as HTMLElement).hidden shouldBe false
                (root.querySelector("#${HistoryRangeIds.PANEL_ACTIVITY}") as HTMLElement).hidden shouldBe true

                updateHistoryTabPanels(pageTwo)
                (root.querySelector("#${HistoryRangeIds.PANEL_ACTIVITY}") as HTMLElement).hidden shouldBe false
                (root.querySelector("[data-history-activity-panel='bot']") as HTMLElement).hidden shouldBe false
                historyActivityRefreshKeepsPage(pageTwo) shouldBe true
                historyActivityRefreshKeepsPage(
                    historyTabsQuery(pageTwo, HistoryRangeQuery.TAB_ACTIVITY, HistoryRangeQuery.ACTIVITY_TRADES),
                ) shouldBe true
            } finally {
                root.remove()
            }
        }
    })

private fun queryParameters(value: String): dynamic {
    val query = if ('?' in value) value.substringAfter('?') else value
    return js("new URLSearchParams(query)")
}
