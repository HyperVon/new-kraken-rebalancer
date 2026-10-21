package com.gemini.krakenbot.frontend

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
