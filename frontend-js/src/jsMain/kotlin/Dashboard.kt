package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import kotlin.js.Date

import com.gemini.krakenbot.util.PrecisionConstants

internal const val SORT_ASC = "asc"
internal const val SORT_DESC = "desc"

internal var currentSortCol: Int = 5
internal var currentSortDir: String = SORT_ASC

fun registerDashboardGlobals() {
    window.asDynamic().sortTable = { header: HTMLElement, colIdx: Int ->
        sortTable(header, colIdx)
    }
}

fun updateAge() {
    val ageEl = document.querySelector(DATA_AGE_VALUE_QUERY) as? HTMLElement ?: return
    val timeEl = document.querySelector(DATA_AGE_TIME_QUERY) as? HTMLElement ?: return

    val epochStr = timeEl.getAttribute(HtmlAttrs.DATA_EPOCH) ?: return
    val epoch = epochStr.toDoubleOrNull() ?: return
    val now = Date.now()
    val diff = ((now - epoch) / 1000).toInt().coerceAtLeast(0)

    ageEl.textContent = "${diff}s ago"
    val isStale = diff > PrecisionConstants.STALE_THRESHOLD_SECONDS
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

    val badgeEl = document.querySelector(STATUS_BADGE_QUERY) as? HTMLElement
    if (badgeEl != null) {
        badgeEl.classList.toggle("delayed", isStale)
        badgeEl.classList.toggle("live", !isStale)
        val badgeText = if (isStale) ViewText.DELAYED else ViewText.LIVE
        if (badgeEl.textContent != badgeText) {
            badgeEl.textContent = badgeText
        }
    }
}

fun reapplySort() {
    val headers = document.querySelectorAll(SORTABLE_TH_QUERY)
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
    val list = tbody.querySelectorAll(HOVERABLE_TR_QUERY)
    for (i in 0 until list.length) {
        val row = list.item(i) as? HTMLTableRowElement
        if (row != null) rows.add(row)
    }

    val isAsc = header.classList.contains(SORT_ASC)
    val sortAsc = if (forceDir != null) forceDir == SORT_ASC else !isAsc
    val key = if (colIdx == 0) "string" else "float"

    rows.sortWith(Comparator { a, b ->
        val aCell = a.cells.item(colIdx) as? HTMLElement
        val bCell = b.cells.item(colIdx) as? HTMLElement
        val aText = (aCell?.dataset?.get(HtmlAttrs.DATASET_SORT_VALUE) ?: aCell?.textContent)?.trim()
            ?.replace(CURRENCY_CLEANUP_REGEX, "") ?: ""
        val bText = (bCell?.dataset?.get(HtmlAttrs.DATASET_SORT_VALUE) ?: bCell?.textContent)?.trim()
            ?.replace(CURRENCY_CLEANUP_REGEX, "") ?: ""

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

    val headersList = table.querySelectorAll(SORTABLE_TH_QUERY)
    for (i in 0 until headersList.length) {
        (headersList.item(i) as? HTMLElement)?.classList?.remove(SORT_ASC, SORT_DESC)
    }
    header.classList.add(if (sortAsc) SORT_ASC else SORT_DESC)

    rows.forEach { row -> tbody.appendChild(row) }

    currentSortCol = colIdx
    currentSortDir = if (sortAsc) SORT_ASC else SORT_DESC
}

private const val DATA_AGE_VALUE_QUERY = ".data-age-value"
private const val DATA_AGE_TIME_QUERY = ".data-age-time"
private const val STATUS_BADGE_QUERY = ".status-badge"
private const val SORTABLE_TH_QUERY = "th.sortable"
private const val HOVERABLE_TR_QUERY = "tr.hoverable"
private val CURRENCY_CLEANUP_REGEX = Regex("[$,%]")
