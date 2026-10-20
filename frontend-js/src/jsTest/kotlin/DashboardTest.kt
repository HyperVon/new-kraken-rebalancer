package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlEvents
import com.gemini.krakenbot.view.util.RecentActivityText
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import org.w3c.dom.events.Event
import kotlin.js.Date

class DashboardTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private fun activity(id: String, time: Double, outcome: String = "NO_TRADE", actions: List<String> = emptyList()) =
        RecentActivityItem(id, time, "LIVE", outcome, actions)

    private fun activityDom(source: String, items: List<RecentActivityItem> = emptyList()): HTMLElement {
        document.getElementById(RecentActivityText.FEED_ID)?.let { existing ->
            existing.parentNode?.removeChild(existing)
        }
        val root = document.createElement("div") as HTMLElement
        root.id = RecentActivityText.FEED_ID
        root.setAttribute(RecentActivityText.SOURCE_ID_ATTRIBUTE, source)
        root.setAttribute(RecentActivityText.FILTER_ATTRIBUTE, RecentActivityText.FILTER_VALUE_ALL)
        root.setAttribute(RecentActivityText.VISIBLE_COUNT_ATTRIBUTE, RecentActivityText.PAGE_SIZE)
        listOf(
            RecentActivityText.FILTER_VALUE_ALL,
            RecentActivityText.FILTER_VALUE_ORDERS,
            RecentActivityText.FILTER_VALUE_PROBLEMS,
        ).forEach { filter ->
            val button = document.createElement("button") as HTMLButtonElement
            button.setAttribute(RecentActivityText.FILTER_ATTRIBUTE, filter)
            root.appendChild(button)
        }
        val warning = document.createElement("p") as HTMLElement
        warning.id = RecentActivityText.PERSISTENCE_WARNING_ID
        warning.hidden = true
        root.appendChild(warning)
        val groups = document.createElement("div") as HTMLElement
        groups.id = RecentActivityText.GROUPS_ID
        items.forEach { item ->
            val row = document.createElement("details") as HTMLElement
            row.className = CssClass.Activity.Cycle.value
            row.setAttribute(RecentActivityText.CYCLE_ID_ATTRIBUTE, item.cycleId)
            row.setAttribute(RecentActivityText.OCCURRED_AT_ATTRIBUTE, item.occurredAt.toString())
            row.setAttribute(RecentActivityText.MODE_ATTRIBUTE, item.mode)
            row.setAttribute(RecentActivityText.OUTCOME_ATTRIBUTE, item.outcome ?: "RECORDED")
            item.actions.forEach { action ->
                val actionNode = document.createElement("div") as HTMLElement
                actionNode.setAttribute(RecentActivityText.ACTION_ATTRIBUTE, action)
                val text = document.createElement("span") as HTMLElement
                text.className = CssClass.Activity.ItemText.value
                text.textContent = action
                actionNode.appendChild(text)
                row.appendChild(actionNode)
            }
            groups.appendChild(row)
        }
        root.appendChild(groups)
        val showOlder = document.createElement("button") as HTMLButtonElement
        showOlder.id = RecentActivityText.SHOW_OLDER_ID
        root.appendChild(showOlder)
        document.body!!.appendChild(root)
        return root
    }

    private fun removeActivityDom(root: HTMLElement) {
        root.parentNode?.removeChild(root)
    }

    init {
        "recent activity defaults to 20 newest cycles and shows 20 older per click" {
            resetRecentActivityClientState()
            registerDashboardGlobals()
            val items = (0 until 35).map { index ->
                when {
                    index % 6 == 0 -> activity(
                        "cycle-$index",
                        index.toDouble(),
                        "PROBLEM",
                        listOf("ERROR: failed $index"),
                    )

                    index % 3 == 0 -> activity("cycle-$index", index.toDouble(), "ORDERS_ATTEMPTED", listOf("BUY BTC"))

                    else -> activity("cycle-$index", index.toDouble(), actions = listOf("check $index"))
                }
            }
            val root = activityDom("source-35")
            recentActivityUiState = RecentActivityUiState(
                sourceId = "source-35",
                items = mergeRecentActivityItems(emptyList(), items),
                hydrationSourceId = "source-35",
            )

            restoreRecentActivityState()
            var groups = root.querySelector("#${RecentActivityText.GROUPS_ID}")!!
            groups.querySelectorAll("details").length shouldBe 20
            (groups.querySelector("details[${RecentActivityText.CYCLE_ID_ATTRIBUTE}]") as HTMLElement)
                .getAttribute(RecentActivityText.CYCLE_ID_ATTRIBUTE) shouldBe "cycle-34"

            (root.querySelector("#${RecentActivityText.SHOW_OLDER_ID}") as HTMLButtonElement).click()
            groups = root.querySelector("#${RecentActivityText.GROUPS_ID}")!!
            groups.querySelectorAll("details").length shouldBe 35
            recentActivityUiState.visibleCount shouldBe 40
            removeActivityDom(root)
        }

        "recent activity merging caps 130 cycles at 120 distinct IDs" {
            val items = (0 until 130).map { activity("cycle-$it", it.toDouble()) }

            val merged = mergeRecentActivityItems(emptyList(), items)

            merged.size shouldBe 120
            merged.first().cycleId shouldBe "cycle-129"
            merged.last().cycleId shouldBe "cycle-10"
        }

        "recent activity filters before applying the 20-cycle limit" {
            val newestNoTrades = (0 until 20).map { activity("check-$it", 100.0 - it) }
            val olderOrders = (0 until 15).map {
                activity("order-$it", 80.0 - it, "ORDERS_ATTEMPTED", listOf("BUY BTC"))
            }
            val items = mergeRecentActivityItems(emptyList(), newestNoTrades + olderOrders)

            val filtered = filterRecentActivityItems(items, RecentActivityText.FILTER_VALUE_ORDERS)
            filtered.take(20).map { it.cycleId } shouldBe olderOrders.sortedByDescending { it.occurredAt }
                .map { it.cycleId }
        }

        "problem filter retains older warning and error cycles ahead of newer healthy cycles" {
            val newestHealthy = (0 until 20).map { activity("healthy-$it", 100.0 - it) }
            val olderProblems = listOf(
                activity("warning-cycle", 70.0, "RECORDED", listOf("WARNING: delayed")),
                activity("error-cycle", 69.0, "PROBLEM", listOf("ERROR: rejected")),
            )

            val filtered = filterRecentActivityItems(
                newestHealthy + olderProblems,
                RecentActivityText.FILTER_VALUE_PROBLEMS,
            )

            filtered.map { it.cycleId } shouldBe listOf("warning-cycle", "error-cycle")
        }

        "hydration merges by cycle ID with current-run activity taking precedence" {
            resetRecentActivityClientState()
            recentActivityUiState.sourceId = "account-a"
            val current = activity(
                "duplicate-cycle",
                Date.parse("2026-10-11T13:00:00Z"),
                "PROBLEM",
                listOf("ERROR: current"),
            )
            recentActivityUiState.items = listOf(current)
            val payload = """
                {
                  "range":"all",
                  "items":[
                    {
                      "cycleId":"duplicate-cycle",
                      "occurredAt":"2026-10-11T12:00:00Z",
                      "mode":"LIVE",
                      "outcome":"ORDERS_ATTEMPTED",
                      "actions":["BUY BTC"]
                    },
                    {
                      "cycleId":"older-cycle",
                      "occurredAt":"2026-10-11T11:00:00Z",
                      "mode":"LIVE",
                      "outcome":null,
                      "actions":["legacy"]
                    }
                  ],
                  "nextCursor":null,
                  "hasMore":false
                }
            """.trimIndent()

            hydrateRecentActivityJson(payload, "account-a") shouldBe true
            recentActivityUiState.items.size shouldBe 2
            recentActivityUiState.items.first().actions shouldBe listOf("ERROR: current")
            recentActivityUiState.items.last().outcome shouldBe null
        }

        "hydration skips malformed rows and accepts numeric timestamps" {
            resetRecentActivityClientState()
            val root = activityDom("source-numeric")
            recentActivityUiState.sourceId = "source-numeric"
            val numericTime = Date.parse("2026-10-11T12:00:00Z")
            val payload = """
                {
                  "items":[
                    {
                      "cycleId":"numeric-cycle",
                      "occurredAt":$numericTime,
                      "mode":"SIMULATION",
                      "outcome":"DRY_RUN_PLAN",
                      "actions":["[DRY RUN] SELL ETH"]
                    },
                    {
                      "cycleId":"legacy-cycle",
                      "occurredAt":"2026-10-11T12:00:01Z",
                      "mode":"LIVE",
                      "outcome":null,
                      "actions":[]
                    },
                    {"occurredAt":$numericTime,"mode":"LIVE","actions":[]},
                    {"cycleId":"invalid-time","occurredAt":"not-a-date","mode":"LIVE","actions":[]}
                  ]
                }
            """.trimIndent()

            hydrateRecentActivityJson(payload, "source-numeric") shouldBe true

            recentActivityUiState.items.map { it.cycleId } shouldBe listOf("legacy-cycle", "numeric-cycle")
            recentActivityUiState.items.last().occurredAt shouldBe numericTime
            root.textContent shouldContain "SIMULATION"
            root.textContent shouldContain "Dry-run plan"
            root.textContent shouldContain "SELL"
            root.textContent shouldContain "Recorded activity"

            hydrateRecentActivityJson("{}", "source-numeric") shouldBe true
            hydrateRecentActivityJson(payload, "other-source") shouldBe true
            recentActivityUiState.items.size shouldBe 2
            setRecentActivityPersistenceUnavailable("other-source")
            (root.querySelector("#${RecentActivityText.PERSISTENCE_WARNING_ID}") as HTMLElement).hidden.shouldBeTrue()
            removeActivityDom(root)
        }

        "activity state capture keeps its current filter and page when swap metadata is absent" {
            resetRecentActivityClientState()
            val root = activityDom("source-metadata")
            recentActivityUiState.sourceId = "source-metadata"
            recentActivityUiState.filter = RecentActivityText.FILTER_VALUE_ORDERS
            recentActivityUiState.visibleCount = 40
            root.removeAttribute(RecentActivityText.FILTER_ATTRIBUTE)
            root.removeAttribute(RecentActivityText.VISIBLE_COUNT_ATTRIBUTE)

            captureRecentActivityState()

            recentActivityUiState.filter shouldBe RecentActivityText.FILTER_VALUE_ORDERS
            recentActivityUiState.visibleCount shouldBe 40
            removeActivityDom(root)
        }

        "source changes discard prior account activity before merging new rows" {
            resetRecentActivityClientState()
            recentActivityUiState = RecentActivityUiState(
                sourceId = "account-a",
                items = listOf(activity("private-cycle", 1.0)),
                expandedCycleIds = mutableSetOf("private-cycle"),
            )
            val root = activityDom("account-b", listOf(activity("account-b-cycle", 2.0)))

            captureRecentActivityState()

            recentActivityUiState.items.map { it.cycleId } shouldBe listOf("account-b-cycle")
            recentActivityUiState.expandedCycleIds shouldBe emptySet()
            recentActivityUiState.hydrationSourceId shouldBe null
            removeActivityDom(root)
        }

        "filter page expansion and scroll survive three dashboard swaps" {
            resetRecentActivityClientState()
            registerDashboardGlobals()
            val source = "source-stable"
            val rows = (0 until 35).map { activity("cycle-$it", it.toDouble(), "ORDERS_ATTEMPTED", listOf("BUY BTC")) }
            var root = activityDom(source, rows)
            recentActivityUiState = RecentActivityUiState(
                sourceId = source,
                items = rows,
                filter = RecentActivityText.FILTER_VALUE_ALL,
                hydrationSourceId = source,
            )
            restoreRecentActivityState()
            (root.querySelector("button[${RecentActivityText.FILTER_ATTRIBUTE}='orders']") as HTMLButtonElement).click()
            (root.querySelector("#${RecentActivityText.SHOW_OLDER_ID}") as HTMLButtonElement).click()
            val expanded = root.querySelector(
                "details[${RecentActivityText.CYCLE_ID_ATTRIBUTE}='cycle-10']",
            ) as HTMLElement
            expanded.asDynamic().open = true
            val spacer = document.createElement("div") as HTMLElement
            spacer.style.height = "2000px"
            document.body!!.appendChild(spacer)
            window.scrollTo(0.0, 35.0)
            recentActivityUiState.pageScrollY = window.scrollY

            repeat(3) { index ->
                document.dispatchEvent(Event(HtmlEvents.HTMX_BEFORE_SWAP))
                removeActivityDom(root)
                root = activityDom(source, listOf(activity("cycle-new-$index", 100.0 + index, "NO_TRADE")))
                document.dispatchEvent(Event(HtmlEvents.HTMX_AFTER_SWAP))
                recentActivityUiState.filter shouldBe RecentActivityText.FILTER_VALUE_ORDERS
                recentActivityUiState.visibleCount shouldBe 40
                recentActivityUiState.expandedCycleIds.contains("cycle-10").shouldBeTrue()
                window.scrollY shouldBe 35.0
                val restored = root.querySelector("details[${RecentActivityText.CYCLE_ID_ATTRIBUTE}='cycle-10']")!!
                (restored.asDynamic().open as Boolean).shouldBeTrue()
            }
            removeActivityDom(root)
            spacer.parentNode?.removeChild(spacer)
        }

        "missing persistence warns without removing current-run activity" {
            resetRecentActivityClientState()
            val root = activityDom("source-warning", listOf(activity("current-cycle", 1.0)))
            recentActivityUiState.sourceId = "source-warning"
            recentActivityUiState.items = listOf(activity("current-cycle", 1.0))

            hydrateRecentActivityJson("{", "source-warning") shouldBe false

            val warning = root.querySelector("#${RecentActivityText.PERSISTENCE_WARNING_ID}") as HTMLElement
            warning.hidden.shouldBeFalse()
            root.querySelectorAll("details[${RecentActivityText.CYCLE_ID_ATTRIBUTE}]").length shouldBe 1
            removeActivityDom(root)
        }

        "legacy hydrated outcome is rendered as recorded activity" {
            resetRecentActivityClientState()
            val root = activityDom("source-legacy")
            recentActivityUiState.sourceId = "source-legacy"
            val payload = """
                {"items":[{
                  "cycleId":"legacy-cycle",
                  "occurredAt":"2026-10-11T12:00:00Z",
                  "mode":"LIVE",
                  "outcome":null,
                  "actions":[]
                }]}
            """.trimIndent()

            hydrateRecentActivityJson(payload, "source-legacy") shouldBe true

            root.textContent shouldContain "Recorded activity"
            root.textContent shouldContain "LIVE TRADING"
            removeActivityDom(root)
        }

        "empty filtered activity shows an empty state and hides older paging" {
            resetRecentActivityClientState()
            val root = activityDom("source-empty", listOf(activity("healthy-cycle", 1.0)))
            recentActivityUiState.sourceId = "source-empty"
            recentActivityUiState.filter = RecentActivityText.FILTER_VALUE_PROBLEMS
            recentActivityUiState.hydrationSourceId = "source-empty"
            recentActivityUiState.items = listOf(activity("healthy-cycle", 1.0))

            restoreRecentActivityState()

            root.textContent shouldContain RecentActivityText.EMPTY_FILTER
            (root.querySelector("#${RecentActivityText.SHOW_OLDER_ID}") as HTMLButtonElement).hidden.shouldBeTrue()
            removeActivityDom(root)
        }

        "sortTable orders strings and amounts in both directions" {
            val table = document.createElement("table") as HTMLTableElement
            val tbody = document.createElement("tbody") as HTMLTableSectionElement
            table.appendChild(tbody)

            val headerRow = document.createElement("tr") as HTMLTableRowElement
            val th0 = document.createElement("th") as HTMLTableCellElement
            th0.className = "sortable"
            th0.textContent = "Asset"
            headerRow.appendChild(th0)

            val th1 = document.createElement("th") as HTMLTableCellElement
            th1.className = "sortable"
            th1.textContent = "Price"
            headerRow.appendChild(th1)

            val thead = document.createElement("thead") as HTMLTableSectionElement
            thead.appendChild(headerRow)
            table.appendChild(thead)

            val row1 = document.createElement("tr") as HTMLTableRowElement
            row1.className = "hoverable"
            val td1a = document.createElement("td")
            td1a.textContent = Asset.ETH
            val td1b = document.createElement("td")
            td1b.textContent = "$3,000.00"
            row1.appendChild(td1a)
            row1.appendChild(td1b)
            tbody.appendChild(row1)

            val row2 = document.createElement("tr") as HTMLTableRowElement
            row2.className = "hoverable"
            val td2a = document.createElement("td")
            td2a.textContent = Asset.BTC
            val td2b = document.createElement("td")
            td2b.textContent = "$60,000.00"
            row2.appendChild(td2a)
            row2.appendChild(td2b)
            tbody.appendChild(row2)

            sortTable(th0, 0)

            var sortedRows = tbody.querySelectorAll("tr.hoverable")
            (sortedRows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe Asset.BTC
            (sortedRows.item(1) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe Asset.ETH
            th0.classList.contains("asc").shouldBeTrue()
            th0.getAttribute("data-sort") shouldBe "ascending"
            th1.getAttribute("data-sort") shouldBe "none"

            sortTable(th0, 0)
            sortedRows = tbody.querySelectorAll("tr.hoverable")
            (sortedRows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe Asset.ETH
            th0.classList.contains("desc").shouldBeTrue()
            th0.getAttribute("data-sort") shouldBe "descending"

            sortTable(th1, 1)
            sortedRows = tbody.querySelectorAll("tr.hoverable")
            (sortedRows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe Asset.ETH
            th1.classList.contains("asc").shouldBeTrue()
            th0.getAttribute("data-sort") shouldBe "none"
            th1.getAttribute("data-sort") shouldBe "ascending"
        }

        "sortTable parses comma-formatted Price and Value cells numerically" {
            val table = document.createElement("table") as HTMLTableElement
            val thead = document.createElement("thead") as HTMLTableSectionElement
            val headerRow = document.createElement("tr") as HTMLTableRowElement
            val headers = listOf("Asset", "Price", "Value").map { label ->
                (document.createElement("th") as HTMLTableCellElement).apply {
                    className = "sortable"
                    textContent = label
                }
            }
            headers.forEach { headerRow.appendChild(it) }
            thead.appendChild(headerRow)
            table.appendChild(thead)

            val tbody = document.createElement("tbody") as HTMLTableSectionElement
            fun addRow(asset: String, price: String, value: String) {
                val row = document.createElement("tr") as HTMLTableRowElement
                row.className = "hoverable"
                listOf(asset, price, value).forEach { text ->
                    row.appendChild(document.createElement("td").apply { textContent = text })
                }
                tbody.appendChild(row)
            }
            addRow(Asset.ETH, "$3,000.00", "$12,000.00")
            addRow(Asset.BTC, "$60,000.00", "$8,000.00")
            // Non-breaking-space thousands separator on the Value cell: Number("50\u00A0000.00")
            // is NaN, so the comma-only cleanup leaves SOL sorting as 0.0; the whitespace-aware
            // regex strips the NBSP and recovers 50000.0, which must place SOL first on a value
            // sort. Without the fix, SOL would sort as 0.0 and the assertion below would fail.
            addRow(Asset.SOL, "$3,000.00", "$50\u00A0000.00")
            table.appendChild(tbody)
            document.body!!.appendChild(table)

            try {
                sortTable(headers[1], 1, "asc")
                (tbody.rows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe Asset.ETH
                sortTable(headers[1], 1, "desc")
                (tbody.rows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe Asset.BTC

                sortTable(headers[2], 2, "asc")
                (tbody.rows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe Asset.BTC
                sortTable(headers[2], 2, "desc")
                (tbody.rows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe Asset.SOL
            } finally {
                document.body!!.removeChild(table)
            }
        }

        "updateAge displays fresh and stale data" {
            val offsetTime = Date.now() - 10000.0
            val container = document.createElement("div") as HTMLDivElement
            container.innerHTML = TestDomBuilders.dataAgeDom(offsetTime.toString())
            document.body!!.appendChild(container)

            try {
                val ageVal =
                    document.getElementsByClassName("data-age-value")[0] as HTMLSpanElement
                val ageTime =
                    document.getElementsByClassName("data-age-time")[0] as HTMLSpanElement
                val badge =
                    document.getElementsByClassName("status-badge")[0] as HTMLElement

                updateAge()
                ageVal.textContent shouldBe "10s ago"
                // Fresh (< STALE_THRESHOLD_SECONDS): Utility.Live CSS — chip text is STREAM, not trading mode.
                badge.classList.contains("live").shouldBeTrue()

                val staleTime = Date.now() - 100000.0
                ageTime.setAttribute("data-epoch", staleTime.toString())
                updateAge()
                ageVal.textContent shouldBe "100s ago"
                badge.classList.contains("delayed").shouldBeTrue()
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "reapplySort preserves the active sort direction" {
            val container = document.createElement("div")
            container.innerHTML =
                """
                <table>
                    <thead>
                        <tr>
                            <th>C0</th><th>C1</th><th>C2</th><th>C3</th><th>C4</th>
                            <th class="sortable">Target</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr class="hoverable">
                            <td>A</td><td>1</td><td>2</td><td>3</td><td>4</td><td data-sort-value="70">70%</td>
                        </tr>
                        <tr class="hoverable">
                            <td>B</td><td>1</td><td>2</td><td>3</td><td>4</td><td data-sort-value="30">30%</td>
                        </tr>
                    </tbody>
                </table>
                """.trimIndent()
            document.body!!.appendChild(container)
            try {
                val headers = container.querySelectorAll("th.sortable")
                val targetHeader = headers.item(0) as HTMLElement

                sortTable(targetHeader, 5, "asc")

                var rows = container.querySelectorAll("tbody tr")
                rows.item(0)!!.textContent!!.shouldContain("B")

                sortTable(targetHeader, 5, "desc")
                rows = container.querySelectorAll("tbody tr")
                rows.item(0)!!.textContent!!.shouldContain("A")

                reapplySort()
                rows = container.querySelectorAll("tbody tr")
                rows.item(0)!!.textContent!!.shouldContain("A")
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "updateAge handles missing elements and stale/fresh states" {
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.dataAgeDom()
            document.body!!.appendChild(container)
            try {
                updateAge()
                val ageVal = document.getElementsByClassName("data-age-value")[0] as HTMLSpanElement
                ageVal.textContent shouldBe ""

                val recentTime = Date.now() - 5000
                val timeEl = document.getElementsByClassName("data-age-time")[0] as HTMLSpanElement
                timeEl.setAttribute("data-epoch", recentTime.toString())
                updateAge()
                ageVal.textContent shouldBe "5s ago"
                val badge = document.getElementsByClassName("status-badge")[0] as HTMLElement
                badge.classList.contains("live").shouldBeTrue()
                badge.classList.contains("delayed").shouldBeFalse()

                // Past STALE_THRESHOLD_SECONDS (90): Utility.Live/Delayed CSS only — chip text is STREAM/STALE.
                val staleTime = Date.now() - 95000
                timeEl.setAttribute("data-epoch", staleTime.toString())
                updateAge()
                ageVal.textContent shouldBe "95s ago"
                badge.classList.contains("delayed").shouldBeTrue()
                badge.classList.contains("live").shouldBeFalse()

                val amTime = Date(2023, 0, 1, 9, 30, 0).getTime()
                timeEl.setAttribute("data-epoch", amTime.toString())
                updateAge()
                timeEl.textContent shouldBe "09:30:00 AM"

                val pmTime = Date(2023, 0, 1, 15, 30, 0).getTime()
                timeEl.setAttribute("data-epoch", pmTime.toString())
                updateAge()
                timeEl.textContent shouldBe "03:30:00 PM"

                val noonTime = Date(2023, 0, 1, 12, 30, 0).getTime()
                timeEl.setAttribute("data-epoch", noonTime.toString())
                updateAge()
                timeEl.textContent shouldBe "12:30:00 PM"

                val badgeContainer = document.createElement("div")
                badgeContainer.innerHTML = TestDomBuilders.dataAgeDom("0")
                document.body!!.appendChild(badgeContainer)
                try {
                    updateAge()
                } finally {
                    document.body!!.removeChild(badgeContainer)
                }
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "reapplySort and sortTable handle edge cases" {
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.sortableTableDom()
            document.body!!.appendChild(container)
            try {
                val noHeadersContainer = document.createElement("div")
                noHeadersContainer.innerHTML = TestDomBuilders.emptyTableDom()
                document.body!!.appendChild(noHeadersContainer)
                try {
                    reapplySort()
                } finally {
                    document.body!!.removeChild(noHeadersContainer)
                }

                val fakeHeader = document.createElement("th") as HTMLElement
                fakeHeader.className = "sortable"
                sortTable(fakeHeader, 0)

                val sortableClass = "sortable"
                val header0 =
                    document.getElementsByClassName(sortableClass)[0] as HTMLTableCellElement
                val header1 =
                    document.getElementsByClassName(sortableClass)[1] as HTMLTableCellElement

                sortTable(header0, 0)
                var rows = container.querySelectorAll("tbody tr")
                // "10" < "5" lexicographically
                (rows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe "A"

                sortTable(header0, 0, "desc")
                rows = container.querySelectorAll("tbody tr")
                // "5" > "10" lexicographically
                (rows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe "C"

                sortTable(header1, 1)
                rows = container.querySelectorAll("tbody tr")
                (rows.item(0) as HTMLTableRowElement).cells.item(1)?.textContent shouldBe "D"

                sortTable(header1, 1, "desc")
                rows = container.querySelectorAll("tbody tr")
                (rows.item(0) as HTMLTableRowElement).cells.item(1)?.textContent shouldBe "B"

                val row2 = document.createElement("tr")
                row2.className = "hoverable"
                val td2a = document.createElement("td")
                td2a.textContent = "Apple"
                val td2b = document.createElement("td")
                td2b.textContent = "Banana"
                row2.appendChild(td2a)
                row2.appendChild(td2b)
                container.querySelector("tbody")!!.appendChild(row2)

                sortTable(header0, 0)
                rows = container.querySelectorAll("tbody tr")
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "sortTable tolerates out-of-range columns" {
            val container = document.createElement("div")
            container.innerHTML = TestDomBuilders.sortableTableDom()
            document.body!!.appendChild(container)
            try {
                val header0 =
                    container.querySelector("th.sortable") as HTMLTableCellElement

                // Column index 5 is out of range (only two columns): must not throw and must leave rows in place.
                sortTable(header0, 5)
                var rows = container.querySelectorAll("tbody tr")
                (rows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe "A"
                (rows.item(1) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe "C"

                // A real sort on the populated column still reorders correctly.
                sortTable(header0, 0, "desc")
                rows = container.querySelectorAll("tbody tr")
                (rows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe "C"
                (rows.item(1) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe "A"
            } finally {
                document.body!!.removeChild(container)
            }
        }
    }
}
