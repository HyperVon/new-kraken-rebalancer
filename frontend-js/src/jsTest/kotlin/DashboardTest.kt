package com.gemini.krakenbot.frontend

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import kotlin.js.Date

@Suppress("unused")
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
        td1a.textContent = "ETH"
        val td1b = document.createElement("td")
        td1b.textContent = "$3,000.00"
        row1.appendChild(td1a)
        row1.appendChild(td1b)
        tbody.appendChild(row1)

        val row2 = document.createElement("tr") as HTMLTableRowElement
        row2.className = "hoverable"
        val td2a = document.createElement("td")
        td2a.textContent = "BTC"
        val td2b = document.createElement("td")
        td2b.textContent = "$60,000.00"
        row2.appendChild(td2a)
        row2.appendChild(td2b)
        tbody.appendChild(row2)

        sortTable(th0, 0)
        
        var sortedRows = tbody.querySelectorAll("tr.hoverable")
        (sortedRows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe "BTC"
        (sortedRows.item(1) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe "ETH"
        th0.classList.contains("asc").shouldBeTrue()
        
        sortTable(th0, 0)
        sortedRows = tbody.querySelectorAll("tr.hoverable")
        (sortedRows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe "ETH"
        th0.classList.contains("desc").shouldBeTrue()
        
        sortTable(th1, 1)
        sortedRows = tbody.querySelectorAll("tr.hoverable")
        (sortedRows.item(0) as HTMLTableRowElement).cells.item(0)?.textContent shouldBe "ETH"
        th1.classList.contains("asc").shouldBeTrue()
    }

        "updateAge displays fresh and stale data" {
        val container = document.createElement("div") as HTMLDivElement
        
        val ageVal = document.createElement("span") as HTMLSpanElement
        ageVal.className = "data-age-value"
        container.appendChild(ageVal)

        val ageTime = document.createElement("span") as HTMLSpanElement
        ageTime.className = "data-age-time"
        val offsetTime = Date.now() - 10000.0
        ageTime.setAttribute("data-epoch", offsetTime.toString())
        container.appendChild(ageTime)

        val badge = document.createElement("span") as HTMLSpanElement
        badge.className = "status-badge"
        container.appendChild(badge)

        document.body!!.appendChild(container)

        try {
            updateAge()
            ageVal.textContent shouldBe "10s ago"
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

        "registerDashboardGlobals exposes table sorting" {
        registerDashboardGlobals()
        (window.asDynamic().sortTable != null) shouldBe true
    }

        "reapplySort preserves the active sort direction" {
        val container = document.createElement("div")
        container.innerHTML = """
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
            
            // Set initial state to sort by col 5
            sortTable(targetHeader, 5, "asc")
            
            // Verify B is first (30%)
            var rows = container.querySelectorAll("tbody tr")
            rows.item(0)!!.textContent!!.shouldContain("B")
            
            // Reverse sort
            sortTable(targetHeader, 5, "desc")
            rows = container.querySelectorAll("tbody tr")
            rows.item(0)!!.textContent!!.shouldContain("A")
            
            // Reapply sort (should still be A first)
            reapplySort()
            rows = container.querySelectorAll("tbody tr")
            rows.item(0)!!.textContent!!.shouldContain("A")
        } finally {
            document.body!!.removeChild(container)
        }
    }

        "dashboard helpers tolerate missing and invalid elements" {
        updateAge()
        reapplySort()

        val container = document.createElement("div")
        container.innerHTML = """
            <span class="data-age-value"></span><span class="data-age-time" data-epoch="invalid"></span>
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
