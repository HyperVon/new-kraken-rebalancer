package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.HistoryAnalysis
import com.gemini.krakenbot.view.util.HistoryChartSelections
import com.gemini.krakenbot.view.util.HistoryRangeIds
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.ViewText
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import kotlin.js.json

class HistoryInteractionTest :
    StringSpec({
        "a native quantity chart uses only the selected asset and its display unit" {
            val container: HTMLElement = document.createElement("div") as HTMLElement
            container.innerHTML = """
                <div ${HtmlAttrs.DATA_HISTORY_SERIES_ASSET}="XXBT">
                  <svg>
                    <circle data-time="1000" data-value="0.5" data-series="BTC"></circle>
                    <circle data-time="2000" data-value="0.75" data-series="BTC"></circle>
                  </svg>
                </div>
                <div ${HtmlAttrs.DATA_HISTORY_SERIES_ASSET}="ZUSD" hidden>
                  <svg>
                    <circle data-time="1000" data-value="100" data-series="USD"></circle>
                  </svg>
                </div>
            """.trimIndent()

            val series = historyNativeSeries(container)

            series.size shouldBe 1
            series[0].first shouldBe "BTC"
            series[0].second.map { it.value } shouldBe listOf(0.5, 0.75)
        }

        "a native quantity chart without samples has no series" {
            val container: HTMLElement = document.createElement("div") as HTMLElement
            container.innerHTML = """
                <div ${HtmlAttrs.DATA_HISTORY_SERIES_ASSET}="XXBT"><svg></svg></div>
            """.trimIndent()

            historyNativeSeries(container) shouldBe emptyList()
        }

        "the visible analysis panel decides the chart and its API selection" {
            val root: HTMLElement = document.createElement("div") as HTMLElement
            root.innerHTML = """
                <div ${HtmlAttrs.DATA_HISTORY_ANALYSIS_PANEL}="${HistoryAnalysis.HOLDINGS}" hidden></div>
                <div ${HtmlAttrs.DATA_HISTORY_ANALYSIS_PANEL}="${HistoryAnalysis.CASH_FLOW}"></div>
            """.trimIndent()
            document.body?.appendChild(root)

            historyVisibleAnalysis() shouldBe (HtmlIds.HISTORY_CASH_FLOW_CHART to HistoryChartSelections.DEPOSITS)

            root.innerHTML = """
                <div ${HtmlAttrs.DATA_HISTORY_ANALYSIS_PANEL}="${HistoryAnalysis.ALLOCATION}"></div>
            """.trimIndent()

            historyVisibleAnalysis() shouldBe
                (HtmlIds.HISTORY_ALLOCATION_CHART to HistoryChartSelections.ALLOCATION)
            root.remove()
        }

        "every chart id maps to the selection its API expects" {
            historyChartSelectionFor(HtmlIds.HISTORY_VALUE_CHART) shouldBe HistoryChartSelections.PORTFOLIO
            historyChartSelectionFor(HtmlIds.HISTORY_HOLDINGS_CHART) shouldBe HistoryChartSelections.HOLDINGS
            historyChartSelectionFor(HtmlIds.HISTORY_ALLOCATION_CHART) shouldBe HistoryChartSelections.ALLOCATION
            historyChartSelectionFor(HtmlIds.HISTORY_CASH_FLOW_CHART) shouldBe HistoryChartSelections.DEPOSITS
            historyChartSelectionFor(HtmlIds.HISTORY_REWARDS_CHART) shouldBe HistoryChartSelections.REWARDS
            historyChartSelectionFor(HtmlIds.HISTORY_ASSET_CHART) shouldBe HistoryChartSelections.CHECKPOINTS
        }

        "the single analysis selector changes one panel and does not register duplicate listeners" {
            val root: HTMLElement = document.createElement("div") as HTMLElement
            root.innerHTML = """
                <select ${HtmlAttrs.DATA_HISTORY_ANALYSIS}="true">
                  <option value="${HistoryAnalysis.HOLDINGS}">Holdings</option>
                  <option value="${HistoryAnalysis.REWARDS}">Rewards</option>
                </select>
                <div ${HtmlAttrs.DATA_HISTORY_ANALYSIS_PANEL}="${HistoryAnalysis.HOLDINGS}"></div>
                <div ${HtmlAttrs.DATA_HISTORY_ANALYSIS_PANEL}="${HistoryAnalysis.REWARDS}" hidden></div>
            """.trimIndent()
            document.body?.appendChild(root)

            val selections = mutableListOf<String>()
            initHistoryAnalysisSelector { selections += it }
            initHistoryAnalysisSelector { selections += it }
            val selector = root.querySelector("select") as HTMLSelectElement
            selector.value = HistoryAnalysis.REWARDS
            selector.dispatchEvent(Event("change"))
            val panels = root.querySelectorAll("[${HtmlAttrs.DATA_HISTORY_ANALYSIS_PANEL}]")
            (panels.item(0) as HTMLElement?)?.hidden shouldBe true
            (panels.item(1) as HTMLElement?)?.hidden shouldBe false
            selector.value shouldBe HistoryAnalysis.REWARDS
            selections shouldBe listOf(HistoryAnalysis.REWARDS)
            root.remove()
        }

        "an API response exposes every series with its display name" {
            val response = json(
                "series" to arrayOf(
                    json(
                        "key" to "portfolio",
                        "label" to "Portfolio value",
                        "unit" to "USD",
                        "points" to arrayOf(
                            json("t" to 1000.0, "v" to 10.0, "complete" to true),
                            json("t" to 2000.0, "v" to 20.0, "complete" to true),
                        ),
                    ),
                    json(
                        "key" to "XXBT",
                        "label" to "BTC",
                        "unit" to "XXBT",
                        "points" to arrayOf(json("t" to 1000.0, "v" to 0.5, "complete" to true)),
                    ),
                ),
            )

            val series = historyApiSeries(response)

            series.size shouldBe 2
            series[0].first shouldBe "Portfolio value"
            series[0].second.size shouldBe 2
            series[1].first shouldBe "BTC"
            series[1].second.single().value shouldBe 0.5
        }

        "a response without series has none" {
            historyApiSeries(json()) shouldBe emptyList()
            historyApiSeries(json("series" to arrayOf<dynamic>())) shouldBe emptyList()
        }

        "historical holdings keep missing prices explicit and distinguish an empty portfolio from a missing mark" {
            val root: HTMLElement = document.createElement("div") as HTMLElement
            root.innerHTML = """
                <section id="history-account-holdings">
                  <h2></h2>
                  <p id="history-holdings-total"></p>
                  <table><tbody id="history-holdings-rows"></tbody></table>
                </section>
            """.trimIndent()
            document.body?.appendChild(root)
            try {
                renderHistoryHoldings(
                    json(
                        "totalStatus" to "PARTIAL",
                        "totalUsd" to null,
                        "holdings" to arrayOf(
                            json(
                                "asset" to "BTC",
                                "quantity" to "1.5",
                                "priceUsd" to "100",
                                "valueUsd" to "150",
                                "availability" to "VALUED",
                            ),
                            json(
                                "asset" to "ETH",
                                "quantity" to "2",
                                "priceUsd" to null,
                                "valueUsd" to null,
                                "reason" to "historical price unavailable",
                            ),
                        ),
                    ),
                    "2026-03-09",
                )

                root.querySelector("h2")!!.textContent shouldBe "Holdings at 2026-03-09"
                root.querySelector("#history-holdings-total")!!.textContent shouldBe "PARTIAL: unavailable USD"
                val rows = root.querySelectorAll("#history-holdings-rows tr")
                rows.length shouldBe 2
                rows.item(0)?.textContent shouldBe "BTC1.5100150VALUED"
                rows.item(1)?.textContent shouldBe "ETH2Unavailable: historical price unavailable"

                renderHistoryHoldings(
                    json("available" to true, "holdings" to emptyArray<Any>()),
                    "2026-03-10",
                )
                root.querySelector("#history-holdings-rows")!!.textContent shouldBe "No held assets for this date."

                renderHistoryHoldings(
                    json("available" to false, "holdings" to emptyArray<Any>()),
                    "2026-03-11",
                )
                root.querySelector("#history-holdings-rows")!!.textContent shouldBe
                    "No daily mark is available for this date."
            } finally {
                root.remove()
            }
        }

        "a point without a value stays an explicit break" {
            val response = json(
                "series" to arrayOf(
                    json(
                        "key" to "portfolio",
                        "label" to "Portfolio value",
                        "unit" to "USD",
                        "points" to arrayOf(
                            json("t" to 1000.0, "v" to null, "complete" to false),
                            json("tIso" to "2026-03-01T10:00:00Z", "v" to 5.0, "complete" to true),
                        ),
                    ),
                ),
            )

            val points = historyApiSeries(response).single().second

            points.size shouldBe 2
            points[0].value shouldBe null
            points[1].value shouldBe 5.0
        }

        "an expanded record detail survives a table refresh" {
            val root: HTMLElement = document.createElement("div") as HTMLElement
            root.innerHTML = """
                <table><tbody id="${HtmlIds.HISTORY_TRADES_BODY}"></tbody></table>
            """.trimIndent()
            document.body?.appendChild(root)

            val response = json(
                "items" to arrayOf(
                    json(
                        "time" to "2026-03-01T10:00:00Z",
                        "pair" to "XXBTZUSD",
                        "type" to "buy",
                        "volume" to "0.1",
                        "price" to "50000",
                        "cost" to "5000",
                        "tradeId" to "T-1",
                    ),
                ),
                "total" to 1,
            )
            updateHistoryTable("trades", response, "?range=7d", 50)

            val details = document.querySelector(
                "#${HtmlIds.HISTORY_TRADES_BODY} details[${HtmlAttrs.DATA_HISTORY_DETAIL_KEY}]",
            ) as HTMLElement?
            details?.setAttribute("open", "")

            updateHistoryTable("trades", response, "?range=7d", 50)

            val refreshed = document.querySelector(
                "#${HtmlIds.HISTORY_TRADES_BODY} details[${HtmlAttrs.DATA_HISTORY_DETAIL_KEY}]",
            ) as HTMLElement?
            refreshed?.getAttribute(HtmlAttrs.DATA_HISTORY_DETAIL_KEY) shouldBe "trade:T-1"
            refreshed?.hasAttribute("open") shouldBe true
            root.remove()
        }

        "a stale completed horizon is not reported as up to date" {
            val response = json(
                "range" to json("toIso" to "2026-03-10T12:00:00Z"),
                "jobs" to arrayOf<dynamic>(),
                "progress" to arrayOf(
                    json(
                        "feed" to "TRADES",
                        "recentCompletedThroughIso" to "2026-03-10T11:00:00Z",
                        "recentLastSuccessAtIso" to "2026-03-10T11:00:00Z",
                    ),
                    json(
                        "feed" to "LEDGERS",
                        "recentCompletedThroughIso" to "2026-03-10T12:00:00Z",
                        "recentLastSuccessAtIso" to "2026-03-10T12:00:00Z",
                    ),
                ),
            )

            historyRefreshBadge(response).label shouldBe "Delayed"

            val fresh = json(
                "range" to json("toIso" to "2026-03-10T12:00:00Z"),
                "jobs" to arrayOf<dynamic>(),
                "progress" to arrayOf(
                    json(
                        "feed" to "TRADES",
                        "recentCompletedThroughIso" to "2026-03-10T11:59:30Z",
                        "recentLastSuccessAtIso" to "2026-03-10T11:59:30Z",
                    ),
                    json(
                        "feed" to "LEDGERS",
                        "recentCompletedThroughIso" to "2026-03-10T12:00:00Z",
                        "recentLastSuccessAtIso" to "2026-03-10T12:00:00Z",
                    ),
                ),
            )

            historyRefreshBadge(fresh).label shouldBe "Up to date"
        }

        "a missing recent horizon keeps the status in the updating state" {
            val response = json(
                "range" to json("toIso" to "2026-03-10T12:00:00Z"),
                "jobs" to arrayOf<dynamic>(),
                "progress" to arrayOf<dynamic>(),
            )

            historyRefreshBadge(response).label shouldBe "Updating"
            historyRefreshBadge(response).label shouldNotBe "Up to date"
        }

        "the activity table keeps its own expanded detail rows" {
            val root: HTMLElement = document.createElement("div") as HTMLElement
            root.innerHTML = """
                <table><tbody id="${HistoryRangeIds.ACTIVITY_BODY}"></tbody></table>
            """.trimIndent()
            document.body?.appendChild(root)

            val response = json(
                "items" to arrayOf(
                    json(
                        "occurredAt" to "2026-03-01T10:00:00Z",
                        "outcome" to "Rebalanced",
                        "mode" to "DRY RUN",
                        "cycleId" to "cycle-1",
                        "actions" to arrayOf("Sold BTC"),
                    ),
                ),
            )
            updateHistoryActivities(response, "?range=7d")

            val details = document.querySelector(
                "#${HistoryRangeIds.ACTIVITY_BODY} details[${HtmlAttrs.DATA_HISTORY_DETAIL_KEY}]",
            ) as HTMLElement?
            details?.setAttribute("open", "")

            updateHistoryActivities(response, "?range=7d")

            val refreshed = document.querySelector(
                "#${HistoryRangeIds.ACTIVITY_BODY} details[${HtmlAttrs.DATA_HISTORY_DETAIL_KEY}]",
            ) as HTMLElement?
            refreshed?.getAttribute(HtmlAttrs.DATA_HISTORY_DETAIL_KEY) shouldBe "activity:cycle-1"
            refreshed?.hasAttribute("open") shouldBe true
            root.remove()
        }
        "a ledger row shows the canonical asset name and keeps the raw identifier in the details" {
            val root: HTMLElement = document.createElement("div") as HTMLElement
            root.innerHTML = """
                <table><tbody id="${HtmlIds.HISTORY_LEDGERS_BODY}"></tbody></table>
            """.trimIndent()
            document.body?.appendChild(root)

            val response = json(
                "items" to arrayOf(
                    json(
                        "ledgerId" to "L-1",
                        "time" to "2026-03-01T10:00:00Z",
                        "type" to "deposit",
                        "asset" to "ZUSD",
                        "amount" to "100.00",
                        "fee" to "0.50",
                        "feeCurrency" to "ZUSD",
                        "isValidRecord" to true,
                    ),
                ),
            )
            updateHistoryTable("ledgers", response, "?range=7d", 50)

            val row = document.querySelector("#${HtmlIds.HISTORY_LEDGERS_BODY} tr") as HTMLElement
            val cells = row.querySelectorAll("td")
            (cells.item(2) as HTMLElement).textContent shouldBe "USD"
            (cells.item(4) as HTMLElement).textContent shouldBe "0.50 USD"
            row.innerHTML.contains("ZUSD") shouldBe true
            root.remove()
        }

        "a trade row settles its fee in the quote currency and hides a zero fee" {
            val root: HTMLElement = document.createElement("div") as HTMLElement
            root.innerHTML = """
                <table><tbody id="${HtmlIds.HISTORY_TRADES_BODY}"></tbody></table>
            """.trimIndent()
            document.body?.appendChild(root)

            updateHistoryTable(
                "trades",
                json(
                    "items" to arrayOf(
                        json(
                            "time" to "2026-03-01T10:00:00Z",
                            "pair" to "XXBTZUSD",
                            "type" to "buy",
                            "volume" to "0.1",
                            "price" to "50000",
                            "cost" to "5000",
                            "fee" to "0.25",
                            "quoteCurrency" to "ZUSD",
                            "tradeId" to "T-2",
                        ),
                        json(
                            "time" to "2026-03-02T10:00:00Z",
                            "pair" to "XXBTZUSD",
                            "type" to "buy",
                            "volume" to "0.1",
                            "price" to "50000",
                            "cost" to "5000",
                            "fee" to "0",
                            "quoteCurrency" to "ZUSD",
                            "tradeId" to "T-3",
                        ),
                    ),
                    "total" to 2,
                ),
                "?range=7d",
                50,
            )

            val cells = document.querySelectorAll("#${HtmlIds.HISTORY_TRADES_BODY} tr td")
            // The server renders a trade fee in the currency Kraken reported for it.
            (cells.item(6) as HTMLElement).textContent shouldBe "0.25 ZUSD"
            (cells.item(13) as HTMLElement).textContent shouldBe "—"
            root.remove()
        }

        "a ledger row without a fee shows no fee" {
            val root: HTMLElement = document.createElement("div") as HTMLElement
            root.innerHTML = """
                <table><tbody id="${HtmlIds.HISTORY_LEDGERS_BODY}"></tbody></table>
            """.trimIndent()
            document.body?.appendChild(root)

            val response = json(
                "items" to arrayOf(
                    json(
                        "ledgerId" to "L-2",
                        "time" to "2026-03-01T10:00:00Z",
                        "type" to "deposit",
                        "asset" to "ZUSD",
                        "amount" to "100.00",
                        "fee" to "0",
                        "feeCurrency" to "ZUSD",
                        "isValidRecord" to true,
                    ),
                ),
            )
            updateHistoryTable("ledgers", response, "?range=7d", 50)

            val row = document.querySelector("#${HtmlIds.HISTORY_LEDGERS_BODY} tr") as HTMLElement
            val cells = row.querySelectorAll("td")
            (cells.item(4) as HTMLElement).textContent shouldBe "—"
            root.remove()
        }

        "an expanded activity detail survives the automatic activity reload" {
            val root: HTMLElement = document.createElement("div") as HTMLElement
            root.innerHTML = """
                <div>
                    <a href="/history?range=7d" data-history-range="7d">7d</a>
                </div>
                <div id="history-range-custom-panel" hidden>
                    <input id="history-range-custom-start" type="date">
                    <input id="history-range-custom-end" type="date">
                    <span id="history-range-custom-tz"></span>
                    <button id="history-range-custom-apply">Apply</button>
                    <p id="history-range-custom-error" hidden></p>
                </div>
                <select id="history-scope-select"></select>
                <div id="history-tab-nav">
                    <a href="/history?range=7d&tab=activity" data-history-tab="activity">Activity</a>
                </div>
                <table><tbody id="${HistoryRangeIds.ACTIVITY_BODY}"></tbody></table>
            """.trimIndent()
            document.body?.appendChild(root)

            val activityResponse = json(
                "items" to arrayOf(
                    json(
                        "occurredAt" to "2026-03-01T10:00:00Z",
                        "outcome" to "Rebalanced",
                        "mode" to "DRY RUN",
                        "cycleId" to "cycle-1",
                        "actions" to arrayOf("Sold BTC"),
                    ),
                ),
            )
            val pending = mutableListOf<(dynamic) -> Unit>()
            val panelRequest: (String, (dynamic) -> Unit, () -> Unit) -> (() -> Unit) = { _, success, _ ->
                pending += success
                { }
            }
            val fetch: (String, (dynamic) -> Unit, () -> Unit) -> Unit = { _, _, _ -> }

            try {
                initHistoryRangeControls(
                    currentQuery = "?range=7d&tab=activity&activity=bot",
                    fetchJson = fetch,
                    panelRequest = panelRequest,
                    timezone = { "UTC" },
                )
                pending.size shouldBe 1
                pending.removeAt(0)(activityResponse)

                val details = document.querySelector(
                    "#${HistoryRangeIds.ACTIVITY_BODY} details[${HtmlAttrs.DATA_HISTORY_DETAIL_KEY}]",
                ) as HTMLElement?
                details?.setAttribute("open", "")

                // Reloading the activity panel replaces the body with a loading row first.
                root.querySelector("[data-history-tab='activity']")!!
                    .dispatchEvent(Event("click"))
                pending.size shouldBe 1
                pending.removeAt(0)(activityResponse)

                val refreshed = document.querySelector(
                    "#${HistoryRangeIds.ACTIVITY_BODY} details[${HtmlAttrs.DATA_HISTORY_DETAIL_KEY}]",
                ) as HTMLElement?
                refreshed?.getAttribute(HtmlAttrs.DATA_HISTORY_DETAIL_KEY) shouldBe "activity:cycle-1"
                refreshed?.hasAttribute("open") shouldBe true
            } finally {
                root.remove()
            }
        }

        "an empty chart and an unknown analysis have no series and no panel" {
            val container: HTMLElement = document.createElement("div") as HTMLElement
            historyNativeSeries(container) shouldBe emptyList()

            val root: HTMLElement = document.createElement("div") as HTMLElement
            document.body?.appendChild(root)
            historyVisibleAnalysis() shouldBe null
            historyShowAnalysis(HistoryAnalysis.ALLOCATION) shouldBe false
            root.remove()
        }

        "a native quantity chart falls back to the first asset when no panel is visible" {
            val container: HTMLElement = document.createElement("div") as HTMLElement
            container.innerHTML = """
                <div ${HtmlAttrs.DATA_HISTORY_SERIES_ASSET}="ZUSD" hidden>
                  <svg><circle data-time="1000" data-value="100" data-series="USD"></circle></svg>
                </div>
            """.trimIndent()

            val series = historyNativeSeries(container)

            series.size shouldBe 1
            series[0].first shouldBe "USD"
        }

        "a chart without an analysis button registers no selector" {
            val root: HTMLElement = document.createElement("div") as HTMLElement
            document.body?.appendChild(root)
            val selected = mutableListOf<String>()

            initHistoryAnalysisSelector { selected += it }

            selected shouldBe emptyList()
            root.remove()
        }
    })
