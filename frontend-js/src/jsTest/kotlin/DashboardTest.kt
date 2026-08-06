package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.w3c.dom.*
import kotlin.js.Date

class DashboardTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
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
            th0.getAttribute("aria-sort") shouldBe "ascending"
            th1.getAttribute("data-sort") shouldBe "none"
            th1.getAttribute("aria-sort") shouldBe "none"

            sortTable(th0, 0)
            sortedRows = tbody.querySelectorAll("tr.hoverable")
            (sortedRows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe Asset.ETH
            th0.classList.contains("desc").shouldBeTrue()
            th0.getAttribute("data-sort") shouldBe "descending"
            th0.getAttribute("aria-sort") shouldBe "descending"

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
