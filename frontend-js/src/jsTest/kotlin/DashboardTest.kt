package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlTags
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
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
            val table = document.createElement("table") as HTMLTableElement
            val tbody = document.createElement("tbody") as HTMLTableSectionElement
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

            val thead = document.createElement("thead") as HTMLTableSectionElement
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

            sortTable(th0, 0)
            sortedRows = tbody.querySelectorAll(CssClass.Query.HOVERABLE_TR)
            (sortedRows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe Asset.ETH
            th0.classList.contains(CssClass.Utility.Desc).shouldBeTrue()

            sortTable(th1, 1)
            sortedRows = tbody.querySelectorAll(CssClass.Query.HOVERABLE_TR)
            (sortedRows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe Asset.ETH
            th1.classList.contains(CssClass.Utility.Asc).shouldBeTrue()
        }

        "updateAge displays fresh and stale data" {
            val container = document.createElement(HtmlTags.DIV) as HTMLDivElement

            val ageVal = document.createElement(HtmlTags.SPAN) as HTMLSpanElement
            ageVal.className = CssClass.DataAge.Value.toString()
            container.appendChild(ageVal)

            val ageTime = document.createElement(HtmlTags.SPAN) as HTMLSpanElement
            ageTime.className = CssClass.DataAge.Time.toString()
            val offsetTime = Date.now() - 10000.0
            ageTime.setAttribute(HtmlAttrs.DATA_EPOCH, offsetTime.toString())
            container.appendChild(ageTime)

            val badge = document.createElement(HtmlTags.SPAN) as HTMLSpanElement
            badge.className = "status-badge"
            container.appendChild(badge)

            document.body!!.appendChild(container)

            try {
                updateAge()
                ageVal.textContent shouldBe "10s ago"
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
                val headers = container.querySelectorAll("th.sortable")
                val targetHeader = headers.item(0) as HTMLElement

                // Set initial state to sort by col 5
                sortTable(targetHeader, 5, CssClass.Utility.Asc.toString())

                // Verify B is first (30%)
                var rows = container.querySelectorAll("${HtmlTags.TBODY} ${HtmlTags.TR}")
                rows.item(0)!!.textContent!!.shouldContain("B")

                // Reverse sort
                sortTable(targetHeader, 5, CssClass.Utility.Desc.toString())
                rows = container.querySelectorAll("${HtmlTags.TBODY} ${HtmlTags.TR}")
                rows.item(0)!!.textContent!!.shouldContain("A")

                // Reapply sort (should still be A first)
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
                <span class="${CssClass.DataAge.Value}"></span><span class="${CssClass.DataAge.Time}" ${HtmlAttrs.DATA_EPOCH}="invalid"></span>
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
    }
}
