package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.HtmlIds
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.w3c.dom.Element
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import kotlin.js.js
import kotlin.js.json

class HistoryRangeTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "asset series selection shows one native-unit chart at a time" {
            val root = document.createElement("div")
            root.innerHTML = """
                <select data-history-series-select="reward-chart">
                    <option value="XXBT" selected>XXBT</option>
                    <option value="XETH">XETH</option>
                </select>
                <div id="reward-chart">
                    <div data-history-series-asset="XXBT"></div>
                    <div data-history-series-asset="XETH" hidden></div>
                </div>
            """.trimIndent()
            document.body!!.appendChild(root)

            try {
                initHistorySeriesSelectors()
                val select = root.querySelector("select") as HTMLSelectElement
                val bitcoin = root.querySelector("[data-history-series-asset='XXBT']") as HTMLElement
                val ethereum = root.querySelector("[data-history-series-asset='XETH']") as HTMLElement
                bitcoin.hidden shouldBe false
                ethereum.hidden shouldBe true

                select.value = "XETH"
                select.dispatchEvent(Event("change"))

                bitcoin.hidden shouldBe true
                ethereum.hidden shouldBe false
            } finally {
                root.remove()
            }
        }

        "account value asset filter follows chart options and refreshes only the selected series" {
            val root = historyRangeFixture()
            val assetFilter = document.createElement("select") as HTMLSelectElement
            assetFilter.id = HtmlIds.HISTORY_VALUE_ASSET_FILTER
            root.appendChild(assetFilter)
            val requests = mutableListOf<String>()
            val destinations = mutableListOf<String>()
            val fetch: (String, (dynamic) -> Unit, () -> Unit) -> Unit = { url, success, _ ->
                requests += url
                when {
                    url == "/api/history/scopes" -> success(historyScopes())

                    url.startsWith("/api/history/charts?") -> {
                        val response = historyApiCharts(
                            preset = "30d",
                            from = "2026-02-08T00:00:00Z",
                            to = "2026-03-10T00:00:00Z",
                            timezone = "UTC",
                            points = listOf("2026-03-09T12:00:00Z" to 100.0),
                        )
                        response.availableValueAssets = arrayOf("BTC", "USD")
                        response.selectedValueAsset = historyQuery(url).get("valueAsset")
                        success(response)
                    }

                    url.startsWith("/api/history/summary?") -> {
                        val chart = historyApiCharts(
                            preset = "30d",
                            from = "2026-02-08T00:00:00Z",
                            to = "2026-03-10T00:00:00Z",
                            timezone = "UTC",
                            points = listOf("2026-03-09T12:00:00Z" to 100.0),
                        )
                        success(historyApiSummary(chart))
                    }
                }
            }

            try {
                initHistoryRangeControls(
                    currentQuery = "?range=30d&valueAsset=BTC",
                    fetchJson = fetch,
                    pushState = destinations::add,
                    timezone = { "UTC" },
                )

                assetFilter.value shouldBe "BTC"
                assetFilter.options.length shouldBe 3
                historyQuery(requests.first { it.startsWith("/api/history/charts?") })
                    .get("valueAsset") as? String shouldBe "BTC"

                assetFilter.value = "USD"
                assetFilter.dispatchEvent(Event("change"))
                historyQuery(destinations.last()).get("valueAsset") as? String shouldBe "USD"
                historyQuery(requests.last { it.startsWith("/api/history/charts?") })
                    .get("valueAsset") as? String shouldBe "USD"
                assetFilter.value shouldBe "USD"

                assetFilter.value = ""
                assetFilter.dispatchEvent(Event("change"))
                historyQuery(destinations.last()).get("valueAsset") as? String shouldBe null
                historyQuery(requests.last { it.startsWith("/api/history/charts?") })
                    .get("valueAsset") as? String shouldBe null
                assetFilter.value shouldBe ""
            } finally {
                root.remove()
            }
        }

        "history reporting refresh keeps currencies separate and unavailable distinct from zero" {
            val root = document.createElement("div")
            root.innerHTML = """
                <span id="history-stat-latest-value"></span>
                <span id="history-stat-value-change"></span>
                <span id="history-stat-range-high"></span>
                <span id="history-stat-range-low"></span>
                <span id="history-stat-latest-meta"></span>
                <span id="history-stat-change-endpoints"></span>
                <span id="history-stat-change-notice"></span>
                <span id="history-stat-range-high-at"></span>
                <span id="history-stat-range-low-at"></span>
                <span id="history-stat-trades"></span>
                <span id="history-stat-bot-cycles"></span>
                <span id="history-stat-volume"></span>
                <span id="history-stat-fees"></span>
                <span id="history-stat-excluded"></span>
                <span id="history-stat-allocation"></span>
                <span id="history-stat-deposits"></span>
                <span id="history-stat-withdrawals"></span>
                <span id="history-stat-rewards"></span>
                <span id="history-stat-ledger-notice"></span>
            """.trimIndent()
            document.body!!.appendChild(root)

            try {
                updateHistoryStatistics(
                    json(
                        "statistics" to json(
                            "latestObservedValue" to json(
                                "valueUsd" to 125.50,
                                "observedAt" to "2026-05-12T10:00:00Z",
                                "outsideSelectedPeriod" to true,
                                "complete" to true,
                            ),
                            "observedValueChange" to json(
                                "changeUsd" to -25.0,
                                "firstValueUsd" to 100.0,
                                "firstObservedAt" to "2026-05-01T10:00:00Z",
                                "lastValueUsd" to 75.0,
                                "lastObservedAt" to "2026-05-10T10:00:00Z",
                                "includesFlowsNotice" to "Includes deposits, withdrawals and market moves",
                                "complete" to true,
                            ),
                            "rangeExtrema" to json(
                                "high" to json("valueUsd" to 150.0, "observedAt" to "2026-05-08T10:00:00Z"),
                                "low" to json("valueUsd" to 90.0, "observedAt" to "2026-05-03T10:00:00Z"),
                                "complete" to true,
                            ),
                            "trades" to json(
                                "tradeCount" to 2,
                                "volumeByCurrency" to json("USD" to 100.0, "BTC" to 0.01),
                                "feesByCurrency" to json("BTC" to 0.0001),
                                "excludedCount" to 1,
                                "complete" to false,
                                "incompleteReason" to "Incomplete",
                            ),
                            "botCycles" to json("cycleCount" to 3, "complete" to true),
                            "allocationDeviation" to json(
                                "targetsUsed" to json("BTC" to 70.0, "USD" to 30.0),
                                "deviationsBySymbol" to json("BTC" to -10.0, "USD" to 10.0),
                                "observedAt" to "2026-05-12T10:00:00Z",
                                "complete" to true,
                            ),
                            "depositsWithdrawals" to json(
                                "notice" to "Ledger classification is not proof of external capital.",
                                "deposits" to json(
                                    "quantitiesByAsset" to json("ZUSD" to 100.0),
                                    "excludedCount" to 0,
                                    "complete" to true,
                                ),
                                "withdrawals" to json(
                                    "quantitiesByAsset" to json(),
                                    "excludedCount" to 0,
                                    "complete" to false,
                                    "incompleteReason" to "Unavailable",
                                ),
                            ),
                            "rewards" to json(
                                "quantitiesByAsset" to json("XXBT" to 0.001),
                                "excludedCount" to 0,
                                "complete" to true,
                            ),
                        ),
                    ),
                )

                root.querySelector("#history-stat-latest-value")?.textContent shouldBe "\$125.50"
                root.querySelector("#history-stat-value-change")?.textContent shouldBe "-\$25.00"
                root.querySelector("#history-stat-range-high")?.textContent shouldBe "\$150.00"
                root.querySelector("#history-stat-range-low")?.textContent shouldBe "\$90.00"
                root.querySelector("#history-stat-latest-meta")?.textContent shouldContain "outside this selected range"
                root.querySelector("#history-stat-change-endpoints")?.textContent shouldContain "to \$75.00"
                root.querySelector("#history-stat-trades")?.textContent shouldBe "2 · Incomplete"
                root.querySelector("#history-stat-bot-cycles")?.textContent shouldBe "3"
                root.querySelector("#history-stat-volume")?.textContent shouldContain "0.01 BTC; 100 USD"
                root.querySelector("#history-stat-fees")?.textContent shouldContain
                    "0.0001 BTC · Incomplete · 1 excluded"
                root.querySelector("#history-stat-excluded")?.textContent shouldBe "1 · Incomplete"
                root.querySelector("#history-stat-allocation")?.textContent shouldContain "BTC: -10 pp"
                root.querySelector("#history-stat-deposits")?.textContent shouldBe "100 ZUSD · Complete"
                root.querySelector("#history-stat-withdrawals")?.textContent shouldBe "Unavailable"
                root.querySelector("#history-stat-rewards")?.textContent shouldBe "0.001 XXBT · Complete"
                root.querySelector("#history-stat-ledger-notice")?.textContent shouldBe
                    "Ledger classification is not proof of external capital."
            } finally {
                root.remove()
            }
        }

        "custom date range sends inclusive dates and the selected IANA timezone" {
            val root = historyRangeFixture()
            val requests = mutableListOf<String>()
            val chart = historyApiCharts(
                preset = "custom",
                from = "2026-03-04T23:00:00Z",
                to = "2026-03-07T23:00:00Z",
                timezone = "Europe/Berlin",
                points = listOf("2026-03-06T12:00:00Z" to 1500.0),
            )
            val fetch: (String, (dynamic) -> Unit, () -> Unit) -> Unit = { url, success, _ ->
                requests += url
                when {
                    url.startsWith("/api/history/charts?") -> success(chart)
                    url.startsWith("/api/history/summary?") -> success(historyApiSummary(chart))
                    url == "/api/history/scopes" -> success(historyScopes())
                }
            }

            try {
                initHistoryRangeControls(
                    currentQuery = "?range=custom&start=2026-03-05&end=2026-03-07&tz=Europe%2FBerlin",
                    fetchJson = fetch,
                    timezone = { "Asia/Tokyo" },
                )

                val chartQuery = historyQuery(requests.first { it.startsWith("/api/history/charts?") })
                chartQuery.get("preset") as? String shouldBe "custom"
                chartQuery.get("start") as? String shouldBe "2026-03-05"
                chartQuery.get("end") as? String shouldBe "2026-03-07"
                chartQuery.get("tz") as? String shouldBe "Europe/Berlin"
                chartQuery.get("selection") as? String shouldBe "PORTFOLIO"
                (root.querySelector("#history-range-custom-tz") as HTMLElement).textContent shouldBe
                    "Timezone: Europe/Berlin"
                (root.querySelector("#history-coverage-selected") as HTMLElement).textContent shouldBe
                    "2026-03-05 – 2026-03-07"
            } finally {
                root.remove()
            }
        }

        "invalid custom bounds show an error and issue no fallback request" {
            val root = historyRangeFixture()
            val requests = mutableListOf<String>()
            val fetch: (String, (dynamic) -> Unit, () -> Unit) -> Unit = { url, _, _ -> requests += url }

            try {
                initHistoryRangeControls(
                    currentQuery = "?range=custom&start=2026-03-08&end=2026-03-05&tz=Europe%2FBerlin",
                    fetchJson = fetch,
                )

                (root.querySelector("#history-range-custom-panel") as HTMLElement).hidden shouldBe false
                val error = root.querySelector("#history-range-custom-error") as HTMLElement
                error.hidden shouldBe false
                error.textContent shouldBe "The start date must not follow the end date."
                requests shouldBe emptyList()
                historyCustomRangeError(null, "2026-03-05") shouldBe "Choose both a start and an end date."
                historyCustomRangeError("2026-02-29", "2026-03-01") shouldBe "Enter valid calendar dates."
            } finally {
                root.remove()
            }
        }

        "applying custom dates writes an inclusive date range without losing filters" {
            val root = historyRangeFixture()
            val fetch: (String, (dynamic) -> Unit, () -> Unit) -> Unit = { _, _, _ -> }
            var destination = ""

            try {
                initHistoryRangeControls(
                    currentQuery = "?range=30d&asset=XBT&tradesOffset=12&ledgersOffset=8",
                    fetchJson = fetch,
                    navigate = { destination = it },
                    timezone = { "Europe/Berlin" },
                )
                root.querySelector("[data-history-range='custom']")!!.dispatchEvent(Event("click"))
                (root.querySelector("#history-range-custom-panel") as HTMLElement).hidden shouldBe false
                (root.querySelector("#history-range-custom-start") as HTMLInputElement).value = "2026-03-05"
                (root.querySelector("#history-range-custom-end") as HTMLInputElement).value = "2026-03-07"
                root.querySelector("#history-range-custom-apply")!!.dispatchEvent(Event("click"))

                val query = historyQuery(destination)
                query.get("range") as? String shouldBe "custom"
                query.get("start") as? String shouldBe "2026-03-05"
                query.get("end") as? String shouldBe "2026-03-07"
                query.get("tz") as? String shouldBe "Europe/Berlin"
                query.get("asset") as? String shouldBe "XBT"
                query.get("tradesOffset") as? String shouldBe null
                query.get("ledgersOffset") as? String shouldBe null
            } finally {
                root.remove()
            }
        }

        "range changes reset viewport and offsets while preserving scope and activity filters" {
            val root = historyRangeFixture()
            val requests = mutableListOf<String>()
            var destination = ""
            val response = historyApiCharts(
                preset = "30d",
                from = "2026-02-08T00:00:00Z",
                to = "2026-03-10T00:00:00Z",
                timezone = "UTC",
                points = listOf("2026-03-09T12:00:00Z" to 10.0, "2026-03-09T12:30:00Z" to 12.0),
            )
            val fetch: (String, (dynamic) -> Unit, () -> Unit) -> Unit = { url, success, _ ->
                requests += url
                when {
                    url == "/api/history/scopes" -> success(historyScopes())
                    url.startsWith("/api/history/charts?") -> success(response)
                    url.startsWith("/api/history/summary?") -> success(historyApiSummary(response))
                }
            }
            val container = root.querySelector("#history-value-chart") as HTMLElement
            val canvas = document.createElement("canvas") as HTMLCanvasElement
            container.appendChild(canvas)
            val chart: dynamic = json(
                "data" to json("datasets" to arrayOf(json("data" to emptyArray<dynamic>()))),
                "options" to json(
                    "scales" to json(
                        "x" to json("min" to 1_500.0, "max" to 2_000.0, "ticks" to json()),
                    ),
                    "plugins" to json("legend" to json("display" to true)),
                ),
                "update" to { _: dynamic -> },
            )
            val session = HistoryChartSession(
                container,
                canvas,
                chart,
                "",
                ChartRange(1_000.0, 3_000.0),
            )
            session.viewport = ChartViewport(session.viewport.full, ChartRange(1_500.0, 2_000.0))
            historyChartSessions[container.id] = session

            try {
                initHistoryRangeControls(
                    currentQuery = "?range=30d&asset=XBT&type=deposit&scope=older&tradesOffset=12&ledgersOffset=8",
                    fetchJson = fetch,
                    navigate = { destination = it },
                )

                root.querySelector("[data-history-range='7d']")!!.dispatchEvent(Event("click"))

                session.viewport.visible shouldBe session.viewport.full
                (chart.options.scales.x.min as Number).toDouble() shouldBe session.viewport.full.min
                (chart.options.scales.x.max as Number).toDouble() shouldBe session.viewport.full.max
                val pageQuery = historyQuery(destination)
                pageQuery.get("range") as? String shouldBe "7d"
                pageQuery.get("asset") as? String shouldBe "XBT"
                pageQuery.get("type") as? String shouldBe "deposit"
                pageQuery.get("scope") as? String shouldBe "older"
                pageQuery.get("tradesOffset") as? String shouldBe null
                pageQuery.get("ledgersOffset") as? String shouldBe null
            } finally {
                session.dispose()
                root.remove()
            }
        }

        "scope selection lists stored portfolios and replaces rather than appends value series" {
            val root = historyRangeFixture()
            val requests = mutableListOf<String>()
            var destination = ""
            val fetch: (String, (dynamic) -> Unit, () -> Unit) -> Unit = { url, success, _ ->
                requests += url
                when {
                    url == "/api/history/scopes" -> success(historyScopes(includeOlder = true))

                    url.startsWith("/api/history/charts?") -> {
                        val scope = historyQuery(url).get("scope") as? String
                        val value = if (scope == "older-scope") 10.0 else 20.0
                        success(
                            historyApiCharts(
                                preset = "30d",
                                from = "2026-02-08T00:00:00Z",
                                to = "2026-03-10T00:00:00Z",
                                timezone = "UTC",
                                points = listOf("2026-03-09T12:00:00Z" to value),
                            ),
                        )
                    }

                    url.startsWith("/api/history/summary?") -> {
                        val chartUrl = requests.last { it.startsWith("/api/history/charts?") }
                        val scope = historyQuery(chartUrl).get("scope") as? String
                        val value = if (scope == "older-scope") 10.0 else 20.0
                        val chart = historyApiCharts(
                            preset = "30d",
                            from = "2026-02-08T00:00:00Z",
                            to = "2026-03-10T00:00:00Z",
                            timezone = "UTC",
                            points = listOf("2026-03-09T12:00:00Z" to value),
                        )
                        success(historyApiSummary(chart))
                    }
                }
            }

            try {
                initHistoryRangeControls(
                    currentQuery = "?range=30d&asset=XBT&type=deposit&tradesOffset=12",
                    fetchJson = fetch,
                    pushState = { destination = it },
                    timezone = { "UTC" },
                )

                val select = root.querySelector("#history-scope-select") as HTMLSelectElement
                select.value shouldBe "current-scope"
                select.options.length shouldBe 2
                val olderOption = select.options.item(1) as HTMLOptionElement
                olderOption.textContent!!.contains("ETH + USD") shouldBe true
                olderOption.textContent!!.contains("2025-01-01") shouldBe true
                (root.querySelector("#history-scope-link") as HTMLElement).hidden shouldBe false

                select.value = "older-scope"
                select.dispatchEvent(Event("change"))

                val updated = historyQuery(destination)
                updated.get("scope") as? String shouldBe "older-scope"
                updated.get("asset") as? String shouldBe "XBT"
                updated.get("type") as? String shouldBe "deposit"
                updated.get("tradesOffset") as? String shouldBe "12"
                val values = root.querySelectorAll("#history-value-chart circle[data-value]")
                values.length shouldBe 1
                (values.item(0) as Element).getAttribute("data-value") shouldBe "10"
                historyQuery(requests.last { it.startsWith("/api/history/charts?") })
                    .get("scope") as? String shouldBe "older-scope"
            } finally {
                root.remove()
            }
        }

        "sparse 30-day values keep the selected axis and explain older activity" {
            val root = historyRangeFixture()
            val chart = historyApiCharts(
                preset = "30d",
                from = "2026-02-08T00:00:00Z",
                to = "2026-03-10T00:00:00Z",
                timezone = "UTC",
                points = listOf("2026-03-09T12:00:00Z" to 1500.0),
                valuesFirst = "2026-03-09T12:00:00Z",
                valuesLast = "2026-03-09T12:00:00Z",
                incomplete = true,
                gaps = 2,
            )
            val fetch: (String, (dynamic) -> Unit, () -> Unit) -> Unit = { url, success, _ ->
                when {
                    url == "/api/history/scopes" -> success(historyScopes(includeOlder = true))
                    url.startsWith("/api/history/charts?") -> success(chart)
                    url.startsWith("/api/history/summary?") -> success(historyApiSummary(chart))
                }
            }

            try {
                initHistoryRangeControls(currentQuery = "?range=30d", fetchJson = fetch, timezone = { "UTC" })

                val sparse = root.querySelector("#history-coverage-sparse") as HTMLElement
                sparse.hidden shouldBe false
                sparse.textContent shouldBe
                    "Portfolio values have been recorded since 2026-03-09. " +
                    "Older trades and transactions are available separately."
                (root.querySelector("#history-coverage-count") as HTMLElement).textContent shouldBe
                    "Recorded observations: 1 · Incomplete · Gaps: 2"
                (root.querySelector("#history-scope-link") as HTMLElement).hidden shouldBe false

                val svg = root.querySelector("#history-value-chart svg")!!
                svg.getAttribute("data-full-from") shouldBe "2026-02-08T00:00:00Z"
                svg.getAttribute("data-full-to") shouldBe "2026-03-10T00:00:00Z"
                val from = epoch(svg.getAttribute("data-full-from")!!)
                val to = epoch(svg.getAttribute("data-full-to")!!)
                (to - from) shouldBe 30 * 86_400_000.0
                val pointX = (svg.querySelector("circle[data-value]")!!.getAttribute("cx") as String).toDouble()
                (pointX > 900.0) shouldBe true
            } finally {
                root.remove()
            }
        }

        "a complete one-day scope explains missing older values in a 30-day range" {
            val root = historyRangeFixture()
            val chart = historyApiCharts(
                preset = "30d",
                from = "2026-02-08T00:00:00Z",
                to = "2026-03-10T00:00:00Z",
                timezone = "UTC",
                points = listOf(
                    "2026-03-09T00:00:00Z" to 1500.0,
                    "2026-03-10T00:00:00Z" to 1501.0,
                ),
                valuesFirst = "2026-03-09T00:00:00Z",
                valuesLast = "2026-03-10T00:00:00Z",
            )
            val fetch: (String, (dynamic) -> Unit, () -> Unit) -> Unit = { url, success, _ ->
                when {
                    url == "/api/history/scopes" -> success(historyScopes(includeOlder = true))
                    url.startsWith("/api/history/charts?") -> success(chart)
                    url.startsWith("/api/history/summary?") -> success(historyApiSummary(chart))
                }
            }

            try {
                initHistoryRangeControls(currentQuery = "?range=30d", fetchJson = fetch, timezone = { "UTC" })

                val sparse = root.querySelector("#history-coverage-sparse") as HTMLElement
                sparse.hidden shouldBe false
                sparse.textContent shouldBe
                    "Portfolio values have been recorded since 2026-03-09. " +
                    "Older trades and transactions are available separately."
                (root.querySelector("#history-coverage-count") as HTMLElement).textContent shouldBe
                    "Recorded observations: 2 · Complete · Gaps: 0"

                val svg = root.querySelector("#history-value-chart svg")!!
                svg.getAttribute("data-full-from") shouldBe "2026-02-08T00:00:00Z"
                svg.getAttribute("data-full-to") shouldBe "2026-03-10T00:00:00Z"
                (epoch(svg.getAttribute("data-full-to")!!) - epoch(svg.getAttribute("data-full-from")!!)) shouldBe
                    30 * 86_400_000.0
            } finally {
                root.remove()
            }
        }

        "all-time range keeps its earliest sample and incomplete breaks without inventing values" {
            val root = historyRangeFixture()
            val points = buildList {
                add("2020-01-01T00:00:00Z" to 5.0)
                (0..7).forEach { index ->
                    add("2026-03-09T12:00:${index.toString().padStart(2, '0')}Z" to (10.0 + index))
                }
                add("2026-03-09T12:00:08Z" to null)
                (9..17).forEach { index ->
                    add("2026-03-09T12:00:${index.toString().padStart(2, '0')}Z" to (10.0 + index))
                }
            }
            val chart = historyApiCharts(
                preset = "all",
                from = null,
                to = "2026-03-10T00:00:00Z",
                timezone = "UTC",
                points = points,
                incomplete = true,
                gaps = 1,
            )
            chart.fullRange.fromIso = null
            chart.range.fromIso = null
            chart.coverage.tradesFirstIso = null
            chart.coverage.tradesLastIso = null
            chart.coverage.ledgersFirstIso = null
            chart.coverage.ledgersLastIso = null
            chart.series[0].points.push(json("tIso" to "not-an-instant", "v" to 42, "complete" to true))
            chart.series[0].points.push(json("v" to 43, "complete" to true))
            val fetch: (String, (dynamic) -> Unit, () -> Unit) -> Unit = { url, success, _ ->
                when {
                    url == "/api/history/scopes" -> success(json("scopes" to emptyArray<dynamic>()))
                    url.startsWith("/api/history/charts?") -> success(chart)
                    url.startsWith("/api/history/summary?") -> success(historyApiSummary(chart))
                }
            }

            try {
                initHistoryRangeControls(currentQuery = "?range=all", fetchJson = fetch, timezone = { "UTC" })

                (root.querySelector("#history-coverage-selected") as HTMLElement).textContent shouldBe
                    "All recorded data – 2026-03-10"
                (root.querySelector("#history-coverage-trades") as HTMLElement).textContent shouldBe "No data recorded"
                (root.querySelector("#history-coverage-ledgers") as HTMLElement).textContent shouldBe "No data recorded"
                (root.querySelector("#history-coverage-count") as HTMLElement).textContent shouldBe
                    "Recorded observations: 19 · Incomplete · Gaps: 1"
                (root.querySelector("#history-scope-link") as HTMLElement).hidden shouldBe true

                val svg = root.querySelector("#history-value-chart svg")!!
                val fullFrom = svg.getAttribute("data-full-from")!!
                epoch(fullFrom) shouldBe epoch("2020-01-01T00:00:00Z")
                svg.querySelectorAll("polyline").length shouldBe 2
                svg.querySelectorAll("circle[data-gap='true']").length shouldBe 1
                svg.querySelectorAll("circle[r='0']").length shouldBe 8
                svg.textContent!!.contains("No portfolio values for this period") shouldBe false
            } finally {
                root.remove()
            }
        }

        "coverage distinguishes stored observations from representative chart points" {
            val root = historyRangeFixture()
            val chart = historyApiCharts(
                preset = "all",
                from = null,
                to = "2026-03-10T00:00:00Z",
                timezone = "UTC",
                points = listOf(
                    "2026-03-08T00:00:00Z" to 10.0,
                    "2026-03-09T00:00:00Z" to 11.0,
                    "2026-03-10T00:00:00Z" to 12.0,
                ),
                incomplete = true,
                gaps = 6,
                totalObservations = 2403,
                representative = true,
            )
            val fetch: (String, (dynamic) -> Unit, () -> Unit) -> Unit = { url, success, _ ->
                when {
                    url == "/api/history/scopes" -> success(json("scopes" to emptyArray<dynamic>()))
                    url.startsWith("/api/history/charts?") -> success(chart)
                    url.startsWith("/api/history/summary?") -> success(historyApiSummary(chart))
                }
            }

            try {
                initHistoryRangeControls(currentQuery = "?range=all", fetchJson = fetch, timezone = { "UTC" })

                (root.querySelector("#history-coverage-count") as HTMLElement).textContent shouldBe
                    "Recorded observations: 2403 · Plotted points: 3 · Incomplete · Gaps: 6"
            } finally {
                root.remove()
            }
        }

        "empty chart data keeps the 30-day selection visible without fabricating samples" {
            val root = historyRangeFixture()
            val chart: dynamic = json(
                "range" to json("fromIso" to null, "toIso" to null, "tz" to null, "preset" to "30d"),
                "scope" to json("id" to "current-scope", "symbols" to emptyArray<String>(), "current" to true),
                "coverage" to json(
                    "valuesFirstIso" to null,
                    "valuesLastIso" to null,
                    "tradesFirstIso" to null,
                    "tradesLastIso" to null,
                    "ledgersFirstIso" to null,
                    "ledgersLastIso" to null,
                    "incomplete" to false,
                ),
                "series" to emptyArray<dynamic>(),
                "fullRange" to json("fromIso" to null, "toIso" to null),
            )
            val fetch: (String, (dynamic) -> Unit, () -> Unit) -> Unit = { url, success, _ ->
                when {
                    url == "/api/history/scopes" -> success(json("scopes" to emptyArray<dynamic>()))
                    url.startsWith("/api/history/charts?") -> success(chart)
                    url.startsWith("/api/history/summary?") -> success(historyApiSummary(chart))
                }
            }

            try {
                initHistoryRangeControls(currentQuery = "?range=30d", fetchJson = fetch)

                (root.querySelector("#history-coverage-selected") as HTMLElement).textContent shouldBe
                    "All recorded data"
                (root.querySelector("#history-coverage-values") as HTMLElement).textContent shouldBe "No data recorded"
                (root.querySelector("#history-coverage-count") as HTMLElement).textContent shouldBe
                    "Recorded observations: 0 · Complete · Gaps: 0"
                (root.querySelector("#history-coverage-sparse") as HTMLElement).hidden shouldBe true
                root.querySelector("#history-value-chart circle[data-value]") shouldBe null
                root.querySelector("#history-value-chart svg")!!.textContent!!.contains(
                    "No portfolio values for this period",
                ) shouldBe true
            } finally {
                root.remove()
            }
        }
    }
}

private fun historyRangeFixture(): HTMLElement {
    val root = document.createElement("div") as HTMLElement
    root.innerHTML = """
        <div>
            <a href="/history?range=24h" data-history-range="24h">24h</a>
            <a href="/history?range=7d" data-history-range="7d">7d</a>
            <a href="/history?range=30d" data-history-range="30d">30d</a>
            <a href="/history?range=90d" data-history-range="90d">90d</a>
            <a href="/history?range=1y" data-history-range="1y">1y</a>
            <a href="/history?range=all" data-history-range="all">All</a>
            <a href="#history-range-custom-panel" data-history-range="custom">Custom</a>
        </div>
        <div id="history-range-custom-panel" hidden>
            <input id="history-range-custom-start" type="date">
            <input id="history-range-custom-end" type="date">
            <span id="history-range-custom-tz"></span>
            <button id="history-range-custom-apply">Apply</button>
            <p id="history-range-custom-error" hidden></p>
        </div>
        <select id="history-scope-select"></select>
        <a href="#history-scope-select" id="history-scope-link" hidden>Earlier recorded portfolios available</a>
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
        <div id="history-value-chart"><svg></svg></div>
    """.trimIndent()
    document.body!!.appendChild(root)
    return root
}

private fun historyApiCharts(
    preset: String,
    from: String?,
    to: String,
    timezone: String,
    points: List<Pair<String, Double?>>,
    valuesFirst: String? = points.firstOrNull()?.first,
    valuesLast: String? = points.lastOrNull()?.first,
    incomplete: Boolean = false,
    gaps: Int = 0,
    totalObservations: Long = points.size.toLong(),
    representative: Boolean = false,
): dynamic {
    val rawPoints: dynamic = js("[]")
    points.forEach { (iso, value) ->
        rawPoints.push(json("t" to epoch(iso), "tIso" to iso, "v" to value, "complete" to (value != null)))
    }
    val range = json("fromIso" to from, "toIso" to to, "tz" to timezone, "preset" to preset)
    val coverage = json(
        "valuesFirstIso" to valuesFirst,
        "valuesLastIso" to valuesLast,
        "tradesFirstIso" to "2025-01-01T00:00:00Z",
        "tradesLastIso" to "2026-03-01T00:00:00Z",
        "ledgersFirstIso" to "2025-06-01T00:00:00Z",
        "ledgersLastIso" to "2026-02-01T00:00:00Z",
        "incomplete" to incomplete,
        "gapCount" to gaps,
    )
    val series =
        arrayOf(json("key" to "portfolio", "label" to "Portfolio value", "unit" to "USD", "points" to rawPoints))
    return json(
        "range" to range,
        "scope" to json(
            "id" to "current-scope",
            "symbols" to arrayOf("BTC", "USD"),
            "earliestIso" to valuesFirst,
            "latestIso" to valuesLast,
            "current" to true,
        ),
        "coverage" to coverage,
        "series" to series,
        "fullRange" to json(
            "fromIso" to from,
            "toIso" to to,
            "pointCount" to points.size,
            "totalObservations" to totalObservations,
            "representative" to representative,
        ),
        "generationMs" to 1,
    )
}

private fun historyApiSummary(charts: dynamic): dynamic = json(
    "range" to charts.range,
    "scope" to charts.scope,
    "coverage" to charts.coverage,
    "statistics" to json(),
    "generationMs" to 1,
)

private fun historyScopes(includeOlder: Boolean = false): dynamic {
    val scopes = mutableListOf<dynamic>()
    scopes += json(
        "id" to "current-scope",
        "symbols" to arrayOf("BTC", "USD"),
        "earliestIso" to "2026-03-09T00:00:00Z",
        "latestIso" to "2026-03-10T00:00:00Z",
        "current" to true,
    )
    if (includeOlder) {
        scopes += json(
            "id" to "older-scope",
            "symbols" to arrayOf("ETH", "USD"),
            "earliestIso" to "2025-01-01T00:00:00Z",
            "latestIso" to "2025-12-31T00:00:00Z",
            "current" to false,
        )
    }
    return json("scopes" to scopes.toTypedArray())
}

private fun historyQuery(url: String): dynamic {
    val query = url.substringAfter('?', "")
    return js("new URLSearchParams(query)")
}

private fun epoch(iso: String): Double = (js("Date.parse(iso)") as Number).toDouble()
