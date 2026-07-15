package com.gemini.krakenbot.frontend

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import kotlin.js.Date

internal var currentSortCol: Int = 5
internal var currentSortDir: String = "asc"

fun registerDashboardGlobals() {
    window.asDynamic().sortTable = { header: HTMLElement, colIdx: Int ->
        sortTable(header, colIdx)
    }
}

fun updateAge() {
    val ageEl = document.querySelector(".data-age-value") as? HTMLElement ?: return
    val timeEl = document.querySelector(".data-age-time") as? HTMLElement ?: return

    val epochStr = timeEl.getAttribute("data-epoch") ?: return
    val epoch = epochStr.toDoubleOrNull() ?: return
    val now = Date.now()
    val diff = ((now - epoch) / 1000).toInt().coerceAtLeast(0)

    ageEl.textContent = "${diff}s ago"
    val isStale = diff > 90
    ageEl.classList.toggle("stale", isStale)

    val date = Date(epoch)
    val hours = date.getHours()
    val ampm = if (hours >= 12) "PM" else "AM"
    val displayHours = if (hours % 12 == 0) 12 else hours % 12
    val hh = displayHours.toString().padStart(2, '0')
    val mm = date.getMinutes().toString().padStart(2, '0')
    val ss = date.getSeconds().toString().padStart(2, '0')
    val localTimeStr = "$hh:$mm:$ss $ampm"

    if (timeEl.textContent?.trim() != localTimeStr) {
        timeEl.textContent = localTimeStr
    }

    val badgeEl = document.querySelector(".status-badge") as? HTMLElement
    if (badgeEl != null) {
        badgeEl.classList.toggle("delayed", isStale)
        badgeEl.classList.toggle("live", !isStale)
        val badgeText = if (isStale) "DELAYED" else "LIVE"
        if (badgeEl.textContent != badgeText) {
            badgeEl.textContent = badgeText
        }
    }
}

fun reapplySort() {
    val headers = document.querySelectorAll("th.sortable")
    if (headers.length > currentSortCol) {
        val header = headers.item(currentSortCol) as? HTMLElement
        if (header != null) {
            sortTable(header, currentSortCol, currentSortDir)
        }
    }
}

fun sortTable(header: HTMLElement, colIdx: Int, forceDir: String? = null) {
    val table = header.closest("table") as? HTMLTableElement ?: return
    val tbody = table.querySelector("tbody") as? HTMLTableSectionElement ?: return
    val rows = mutableListOf<HTMLTableRowElement>()
    val list = tbody.querySelectorAll("tr.hoverable")
    for (i in 0 until list.length) {
        val row = list.item(i) as? HTMLTableRowElement
        if (row != null) rows.add(row)
    }

    val isAsc = header.classList.contains("asc")
    val sortAsc = if (forceDir != null) forceDir == "asc" else !isAsc
    val key = if (colIdx == 0) "string" else "float"

    rows.sortWith(Comparator { a, b ->
        val aCell = a.cells.item(colIdx) as? HTMLElement
        val bCell = b.cells.item(colIdx) as? HTMLElement
        val aText = (aCell?.dataset?.get("sortValue") ?: aCell?.textContent)?.trim()
            ?.replace(Regex("[$,%]"), "") ?: ""
        val bText = (bCell?.dataset?.get("sortValue") ?: bCell?.textContent)?.trim()
            ?.replace(Regex("[$,%]"), "") ?: ""

        if (key == "float") {
            val aVal = aText.toDoubleOrNull() ?: 0.0
            val bVal = bText.toDoubleOrNull() ?: 0.0
            val cmp = aVal.compareTo(bVal)
            if (sortAsc) cmp else -cmp
        } else {
            val cmp = aText.compareTo(bText)
            if (sortAsc) cmp else -cmp
        }
    })

    val headersList = table.querySelectorAll("th.sortable")
    for (i in 0 until headersList.length) {
        (headersList.item(i) as? HTMLElement)?.classList?.remove("asc", "desc")
    }
    header.classList.add(if (sortAsc) "asc" else "desc")

    rows.forEach { row -> tbody.appendChild(row) }

    currentSortCol = colIdx
    currentSortDir = if (sortAsc) "asc" else "desc"
}
