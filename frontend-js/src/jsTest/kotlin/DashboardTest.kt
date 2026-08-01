package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.DataSort
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlTags
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import kotlin.js.Date

class DashboardTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "sortTable orders strings and amounts in both directions" {
            val table = document.createElement(HtmlTags.TABLE) as HTMLTableElement
            val tbody = document.createElement(HtmlTags.TBODY) as HTMLTableSectionElement
            table.appendChild(tbody)

            val headerRow = document.createElement(HtmlTags.TR) as HTMLTableRowElement
            val th0 = document.createElement(HtmlTags.TH) as HTMLTableCellElement
            th0.className = CssClass.Table.Sortable.toString()
            th0.textContent = "Asset"
            headerRow.appendChild(th0)

            val th1 = document.createElement(HtmlTags.TH) as HTMLTableCellElement
            th1.className = CssClass.Table.Sortable.toString()
            th1.textContent = "Price"
            headerRow.appendChild(th1)

            val thead = document.createElement(HtmlTags.THEAD) as HTMLTableSectionElement
            thead.appendChild(headerRow)
            table.appendChild(thead)

            val row1 = document.createElement(HtmlTags.TR) as HTMLTableRowElement
            row1.className = CssClass.Table.Hoverable.toString()
            val td1a = document.createElement(HtmlTags.TD)
            td1a.textContent = Asset.ETH
            val td1b = document.createElement(HtmlTags.TD)
            td1b.textContent = "$3,000.00"
            row1.appendChild(td1a)
            row1.appendChild(td1b)
            tbody.appendChild(row1)

            val row2 = document.createElement(HtmlTags.TR) as HTMLTableRowElement
            row2.className = CssClass.Table.Hoverable.toString()
            val td2a = document.createElement(HtmlTags.TD)
            td2a.textContent = Asset.BTC
            val td2b = document.createElement(HtmlTags.TD)
            td2b.textContent = "$60,000.00"
            row2.appendChild(td2a)
            row2.appendChild(td2b)
            tbody.appendChild(row2)

            sortTable(th0, 0)

            var sortedRows = tbody.querySelectorAll(CssClass.Query.HOVERABLE_TR)
            (sortedRows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe Asset.BTC
            (sortedRows.item(1) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe Asset.ETH
            th0.classList.contains(CssClass.Utility.Asc).shouldBeTrue()
            th0.getAttribute(HtmlAttrs.DATA_SORT) shouldBe DataSort.ASCENDING
            th1.getAttribute(HtmlAttrs.DATA_SORT) shouldBe DataSort.NONE

            sortTable(th0, 0)
            sortedRows = tbody.querySelectorAll(CssClass.Query.HOVERABLE_TR)
            (sortedRows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe Asset.ETH
            th0.classList.contains(CssClass.Utility.Desc).shouldBeTrue()
            th0.getAttribute(HtmlAttrs.DATA_SORT) shouldBe DataSort.DESCENDING

            sortTable(th1, 1)
            sortedRows = tbody.querySelectorAll(CssClass.Query.HOVERABLE_TR)
            (sortedRows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe Asset.ETH
            th1.classList.contains(CssClass.Utility.Asc).shouldBeTrue()
            th0.getAttribute(HtmlAttrs.DATA_SORT) shouldBe DataSort.NONE
            th1.getAttribute(HtmlAttrs.DATA_SORT) shouldBe DataSort.ASCENDING
        }

        "sortTable parses comma-formatted Price and Value cells numerically" {
            val table = document.createElement(HtmlTags.TABLE) as HTMLTableElement
            val thead = document.createElement(HtmlTags.THEAD) as HTMLTableSectionElement
            val headerRow = document.createElement(HtmlTags.TR) as HTMLTableRowElement
            val headers = listOf("Asset", "Price", "Value").map { label ->
                (document.createElement(HtmlTags.TH) as HTMLTableCellElement).apply {
                    className = CssClass.Table.Sortable.toString()
                    textContent = label
                }
            }
            headers.forEach { headerRow.appendChild(it) }
            thead.appendChild(headerRow)
            table.appendChild(thead)

            val tbody = document.createElement(HtmlTags.TBODY) as HTMLTableSectionElement
            fun addRow(asset: String, price: String, value: String) {
                val row = document.createElement(HtmlTags.TR) as HTMLTableRowElement
                row.className = CssClass.Table.Hoverable.toString()
                listOf(asset, price, value).forEach { text ->
                    row.appendChild(document.createElement(HtmlTags.TD).apply { textContent = text })
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
                sortTable(headers[1], 1, CssClass.Utility.Asc.toString())
                (tbody.rows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe Asset.ETH
                sortTable(headers[1], 1, CssClass.Utility.Desc.toString())
                (tbody.rows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe Asset.BTC

                sortTable(headers[2], 2, CssClass.Utility.Asc.toString())
                (tbody.rows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe Asset.BTC
                sortTable(headers[2], 2, CssClass.Utility.Desc.toString())
                (tbody.rows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe Asset.SOL
            } finally {
                document.body!!.removeChild(table)
            }
        }

        "updateAge displays fresh and stale data" {
            val offsetTime = Date.now() - 10000.0
            val container = document.createElement(HtmlTags.DIV) as HTMLDivElement
            container.innerHTML = TestDomBuilders.dataAgeDom(offsetTime.toString())
            document.body!!.appendChild(container)

            try {
                val ageVal =
                    document.getElementsByClassName(CssClass.DataAge.Value.toString())[0] as HTMLSpanElement
                val ageTime =
                    document.getElementsByClassName(CssClass.DataAge.Time.toString())[0] as HTMLSpanElement
                val badge =
                    document.getElementsByClassName(CssClass.StatusCard.Badge.toString())[0] as HTMLElement

                updateAge()
                ageVal.textContent shouldBe "10s ago"
                // Fresh (< STALE_THRESHOLD_SECONDS): Utility.Live CSS — chip text is STREAM, not trading mode.
                badge.classList.contains(CssClass.Utility.Live).shouldBeTrue()

                val staleTime = Date.now() - 100000.0
                ageTime.setAttribute(HtmlAttrs.DATA_EPOCH, staleTime.toString())
                updateAge()
                ageVal.textContent shouldBe "100s ago"
                badge.classList.contains(CssClass.Utility.Delayed).shouldBeTrue()
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "registerDashboardGlobals exposes table sorting" {
            registerDashboardGlobals()
            (window.asDynamic().sortTable != null) shouldBe true
        }

        "reapplySort preserves the active sort direction" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML =
                """
                <table>
                    <thead>
                        <tr>
                            <th>C0</th><th>C1</th><th>C2</th><th>C3</th><th>C4</th>
                            <th class="${CssClass.Table.Sortable}">Target</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr class="${CssClass.Table.Hoverable}">
                            <td>A</td><td>1</td><td>2</td><td>3</td><td>4</td><td data-sort-value="70">70%</td>
                        </tr>
                        <tr class="${CssClass.Table.Hoverable}">
                            <td>B</td><td>1</td><td>2</td><td>3</td><td>4</td><td data-sort-value="30">30%</td>
                        </tr>
                    </tbody>
                </table>
                """.trimIndent()
            document.body!!.appendChild(container)
            try {
                val headers = container.querySelectorAll(CssClass.Query.SORTABLE_TH)
                val targetHeader = headers.item(0) as HTMLElement

                sortTable(targetHeader, 5, CssClass.Utility.Asc.toString())

                var rows = container.querySelectorAll("${HtmlTags.TBODY} ${HtmlTags.TR}")
                rows.item(0)!!.textContent!!.shouldContain("B")

                sortTable(targetHeader, 5, CssClass.Utility.Desc.toString())
                rows = container.querySelectorAll("${HtmlTags.TBODY} ${HtmlTags.TR}")
                rows.item(0)!!.textContent!!.shouldContain("A")

                reapplySort()
                rows = container.querySelectorAll("${HtmlTags.TBODY} ${HtmlTags.TR}")
                rows.item(0)!!.textContent!!.shouldContain("A")
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "dashboard helpers tolerate missing and invalid elements" {
            updateAge()
            reapplySort()

            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML =
                """
                <span class="${CssClass.DataAge.Value}"></span>
                <span class="${CssClass.DataAge.Time}" ${HtmlAttrs.DATA_EPOCH}="invalid"></span>
                <div id="orphan-header"></div>
                """.trimIndent()
            document.body!!.appendChild(container)
            try {
                updateAge()
                sortTable(document.getElementById("orphan-header") as HTMLElement, 0)
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "updateAge handles missing elements and stale/fresh states" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.dataAgeDom()
            document.body!!.appendChild(container)
            try {
                updateAge()
                val ageVal = document.getElementsByClassName(CssClass.DataAge.Value.toString())[0] as HTMLSpanElement
                ageVal.textContent shouldBe ""

                val recentTime = Date.now() - 5000
                val timeEl = document.getElementsByClassName(CssClass.DataAge.Time.toString())[0] as HTMLSpanElement
                timeEl.setAttribute(HtmlAttrs.DATA_EPOCH, recentTime.toString())
                updateAge()
                ageVal.textContent shouldBe "5s ago"
                val badge = document.getElementsByClassName(CssClass.StatusCard.Badge.toString())[0] as HTMLElement
                badge.classList.contains(CssClass.Utility.Live).shouldBeTrue()
                badge.classList.contains(CssClass.Utility.Delayed).shouldBeFalse()

                // Past STALE_THRESHOLD_SECONDS (90): Utility.Live/Delayed CSS only — chip text is STREAM/STALE.
                val staleTime = Date.now() - 95000
                timeEl.setAttribute(HtmlAttrs.DATA_EPOCH, staleTime.toString())
                updateAge()
                ageVal.textContent shouldBe "95s ago"
                badge.classList.contains(CssClass.Utility.Delayed).shouldBeTrue()
                badge.classList.contains(CssClass.Utility.Live).shouldBeFalse()

                val amTime = Date(2023, 0, 1, 9, 30, 0).getTime()
                timeEl.setAttribute(HtmlAttrs.DATA_EPOCH, amTime.toString())
                updateAge()
                timeEl.textContent shouldBe "09:30:00 AM"

                val pmTime = Date(2023, 0, 1, 15, 30, 0).getTime()
                timeEl.setAttribute(HtmlAttrs.DATA_EPOCH, pmTime.toString())
                updateAge()
                timeEl.textContent shouldBe "03:30:00 PM"

                val noonTime = Date(2023, 0, 1, 12, 30, 0).getTime()
                timeEl.setAttribute(HtmlAttrs.DATA_EPOCH, noonTime.toString())
                updateAge()
                timeEl.textContent shouldBe "12:30:00 PM"

                val badgeContainer = document.createElement(HtmlTags.DIV)
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
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML = TestDomBuilders.sortableTableDom()
            document.body!!.appendChild(container)
            try {
                val noHeadersContainer = document.createElement(HtmlTags.DIV)
                noHeadersContainer.innerHTML = TestDomBuilders.emptyTableDom()
                document.body!!.appendChild(noHeadersContainer)
                try {
                    reapplySort()
                } finally {
                    document.body!!.removeChild(noHeadersContainer)
                }

                val fakeHeader = document.createElement(HtmlTags.TH) as HTMLElement
                fakeHeader.className = CssClass.Table.Sortable.toString()
                sortTable(fakeHeader, 0)

                val sortableClass = CssClass.Table.Sortable.toString()
                val header0 =
                    document.getElementsByClassName(sortableClass)[0] as HTMLTableCellElement
                val header1 =
                    document.getElementsByClassName(sortableClass)[1] as HTMLTableCellElement

                sortTable(header0, 0)
                var rows = container.querySelectorAll("${HtmlTags.TBODY} ${HtmlTags.TR}")
                // "10" < "5" lexicographically
                (rows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe "A"

                sortTable(header0, 0, CssClass.Utility.Desc.toString())
                rows = container.querySelectorAll("${HtmlTags.TBODY} ${HtmlTags.TR}")
                // "5" > "10" lexicographically
                (rows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe "C"

                sortTable(header1, 1)
                rows = container.querySelectorAll("${HtmlTags.TBODY} ${HtmlTags.TR}")
                (rows.item(0) as HTMLTableRowElement).cells.item(1)?.textContent shouldBe "D"

                sortTable(header1, 1, CssClass.Utility.Desc.toString())
                rows = container.querySelectorAll("${HtmlTags.TBODY} ${HtmlTags.TR}")
                (rows.item(0) as HTMLTableRowElement).cells.item(1)?.textContent shouldBe "B"

                val row2 = document.createElement(HtmlTags.TR)
                row2.className = CssClass.Table.Hoverable.toString()
                val td2a = document.createElement(HtmlTags.TD)
                td2a.textContent = "Apple"
                val td2b = document.createElement(HtmlTags.TD)
                td2b.textContent = "Banana"
                row2.appendChild(td2a)
                row2.appendChild(td2b)
                container.querySelector(HtmlTags.TBODY)!!.appendChild(row2)

                sortTable(header0, 0)
                rows = container.querySelectorAll("${HtmlTags.TBODY} ${HtmlTags.TR}")
            } finally {
                document.body!!.removeChild(container)
            }
        }

        "sortTable tolerates out-of-range columns and empty cells" {
            val container = document.createElement(HtmlTags.DIV)
            container.innerHTML =
                """
                <table>
                    <thead>
                        <tr><th class="${CssClass.Table.Sortable}">C0</th></tr>
                    </thead>
                    <tbody>
                        <tr class="${CssClass.Table.Hoverable}"><td></td></tr>
                        <tr class="${CssClass.Table.Hoverable}"><td></td></tr>
                    </tbody>
                </table>
                """.trimIndent()
            document.body!!.appendChild(container)
            try {
                val header = container.querySelector(CssClass.Query.SORTABLE_TH) as HTMLElement
                sortTable(header, 0)
                sortTable(header, 5)
            } finally {
                document.body!!.removeChild(container)
            }
        }
    }
}
