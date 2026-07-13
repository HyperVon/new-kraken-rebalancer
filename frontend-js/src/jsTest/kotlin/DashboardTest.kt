package com.gemini.krakenbot.frontend

import kotlinx.browser.document
import org.w3c.dom.*
import kotlin.js.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class DashboardTest {
    @Test
    fun testSortTable() {
        val table = document.createElement("table") as HTMLTableElement
        val tbody = document.createElement("tbody") as HTMLTableSectionElement
        table.appendChild(tbody)

        val headerRow = document.createElement("tr") as HTMLTableRowElement
        val th0 = document.createElement("th") as HTMLTableCellElement
        th0.className = "sortable" // starts without asc, so first click sorts ASC
        th0.textContent = "Asset"
        headerRow.appendChild(th0)
        
        val th1 = document.createElement("th") as HTMLTableCellElement
        th1.className = "sortable" // starts without asc, so first click sorts ASC
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

        // 1. Sort by Asset (col index 0) - Ascending
        sortTable(th0, 0)
        
        val sortedRows1 = tbody.querySelectorAll("tr.hoverable")
        assertEquals("BTC", (sortedRows1.item(0) as HTMLTableRowElement).cells.item(0)?.textContent)
        assertEquals("ETH", (sortedRows1.item(1) as HTMLTableRowElement).cells.item(0)?.textContent)
        assertTrue(th0.classList.contains("asc"))
        assertFalse(th0.classList.contains("desc"))
        
        // 2. Sort by Asset (col index 0) again - Toggles to Descending
        sortTable(th0, 0)
        
        val sortedRowsDesc = tbody.querySelectorAll("tr.hoverable")
        assertEquals("ETH", (sortedRowsDesc.item(0) as HTMLTableRowElement).cells.item(0)?.textContent)
        assertEquals("BTC", (sortedRowsDesc.item(1) as HTMLTableRowElement).cells.item(0)?.textContent)
        assertTrue(th0.classList.contains("desc"))
        assertFalse(th0.classList.contains("asc"))
        
        // 3. Sort by Price (col index 1) - Ascending
        sortTable(th1, 1)
        
        val sortedRows2 = tbody.querySelectorAll("tr.hoverable")
        assertEquals("ETH", (sortedRows2.item(0) as HTMLTableRowElement).cells.item(0)?.textContent)
        assertEquals("BTC", (sortedRows2.item(1) as HTMLTableRowElement).cells.item(0)?.textContent)
        assertTrue(th1.classList.contains("asc"))
        assertFalse(th1.classList.contains("desc"))
    }

    @Test
    fun testUpdateAge() {
        val container = document.createElement("div") as HTMLDivElement
        
        val ageVal = document.createElement("span") as HTMLSpanElement
        ageVal.className = "data-age-value"
        container.appendChild(ageVal)

        val ageTime = document.createElement("span") as HTMLSpanElement
        ageTime.className = "data-age-time"
        val offsetTime = Date.now() - 10000.0 // 10s ago
        ageTime.setAttribute("data-epoch", offsetTime.toString())
        container.appendChild(ageTime)

        val badge = document.createElement("span") as HTMLSpanElement
        badge.className = "status-badge"
        container.appendChild(badge)

        document.body!!.appendChild(container)

        try {
            updateAge()
            assertEquals("10s ago", ageVal.textContent)
            assertFalse(ageVal.classList.contains("stale"))
            assertTrue(badge.classList.contains("live"))
            assertFalse(badge.classList.contains("delayed"))

            // Make it stale (100 seconds ago)
            val staleTime = Date.now() - 100000.0
            ageTime.setAttribute("data-epoch", staleTime.toString())
            updateAge()
            assertEquals("100s ago", ageVal.textContent)
            assertTrue(ageVal.classList.contains("stale"))
            assertTrue(badge.classList.contains("delayed"))
            assertFalse(badge.classList.contains("live"))
        } finally {
            document.body!!.removeChild(container)
        }
    }
}
