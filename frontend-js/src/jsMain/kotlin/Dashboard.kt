package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.DataSort
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlTags
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import kotlin.js.Date
import com.gemini.krakenbot.view.util.CssClass.Query.DATA_AGE_TIME as DATA_AGE_TIME_QUERY
import com.gemini.krakenbot.view.util.CssClass.Query.DATA_AGE_VALUE as DATA_AGE_VALUE_QUERY
import com.gemini.krakenbot.view.util.CssClass.Query.HOVERABLE_TR as HOVERABLE_TR_QUERY
import com.gemini.krakenbot.view.util.CssClass.Query.SORTABLE_TH as SORTABLE_TH_QUERY
import com.gemini.krakenbot.view.util.CssClass.Query.STATUS_BADGE as STATUS_BADGE_QUERY

internal var currentSortCol: Int = PrecisionConstants.DEFAULT_SORT_COL_INDEX
internal var currentSortDir: String = CssClass.Utility.Asc.toString()

// Currency/percent decoration plus formatting whitespace (regular, non-breaking, and narrow
// non-breaking spaces) is stripped before numeric parsing; JS Number("3,000.00") would otherwise
// be NaN and sort as 0.0.
private val CURRENCY_CLEANUP_REGEX = Regex("[\\$,%\\s\\u00A0\\u202F]")

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
    val diff = ((now - epoch) / PrecisionConstants.MILLIS_PER_SECOND).toInt().coerceAtLeast(0)

    ageEl.textContent = "${diff}${ViewText.AGO_SECONDS}"
    val isStale = diff > PrecisionConstants.STALE_THRESHOLD_SECONDS
    ageEl.classList.toggle(CssClass.Utility.Stale, isStale)

    val date = Date(epoch)
    val hours = date.getHours()
    val ampm = if (hours >= PrecisionConstants.HOURS_PER_HALF_DAY) ViewText.PM else ViewText.AM
    val displayHours =
        if (hours % PrecisionConstants.HOURS_PER_HALF_DAY ==
            0
        ) {
            PrecisionConstants.HOURS_PER_HALF_DAY
        } else {
            hours % PrecisionConstants.HOURS_PER_HALF_DAY
        }
    val hh = displayHours.toString().padStart(2, '0')
    val mm = date.getMinutes().toString().padStart(2, '0')
    val ss = date.getSeconds().toString().padStart(2, '0')
    val localTimeStr = "$hh:$mm:$ss $ampm"

    if (timeEl.textContent?.trim() != localTimeStr) {
        timeEl.textContent = localTimeStr
    }

    val badgeEl = document.querySelector(STATUS_BADGE_QUERY) as? HTMLElement
    if (badgeEl != null) {
        badgeEl.classList.toggle(CssClass.Utility.Delayed, isStale)
        badgeEl.classList.toggle(CssClass.Utility.Live, !isStale)
        // GLOB-1: this chip reflects SSE stream health, not trading mode.
        val badgeText = if (isStale) ViewText.STREAM_STALE else ViewText.STREAM
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
    val table = header.closest(HtmlTags.TABLE) as? HTMLTableElement ?: return
    val tbody = table.querySelector(HtmlTags.TBODY) as? HTMLTableSectionElement ?: return
    val rows = mutableListOf<HTMLTableRowElement>()
    val list = tbody.querySelectorAll(HOVERABLE_TR_QUERY)
    for (i in 0 until list.length) {
        val row = list.item(i) as? HTMLTableRowElement
        if (row != null) rows.add(row)
    }

    val isAsc = header.classList.contains(CssClass.Utility.Asc)
    val sortAsc = if (forceDir != null) forceDir == CssClass.Utility.Asc.toString() else !isAsc
    // Column 0 is the asset label (string sort); every other column is numeric. Comparisons
    // prefer the server-rendered data-sort-value attribute and fall back to the visible cell
    // text with currency/percent decoration stripped.
    val isNumericColumn = colIdx != 0

    rows.sortWith(
        Comparator { a, b ->
            val aCell = a.cells.item(colIdx) as? HTMLElement
            val bCell = b.cells.item(colIdx) as? HTMLElement
            val aText =
                (aCell?.dataset?.get(HtmlAttrs.DATASET_SORT_VALUE) ?: aCell?.textContent)
                    ?.trim()
                    ?.replace(CURRENCY_CLEANUP_REGEX, "") ?: ""
            val bText =
                (bCell?.dataset?.get(HtmlAttrs.DATASET_SORT_VALUE) ?: bCell?.textContent)
                    ?.trim()
                    ?.replace(CURRENCY_CLEANUP_REGEX, "") ?: ""

            if (isNumericColumn) {
                val aVal = aText.toDoubleOrNull() ?: 0.0
                val bVal = bText.toDoubleOrNull() ?: 0.0
                val cmp = aVal.compareTo(bVal)
                if (sortAsc) cmp else -cmp
            } else {
                val cmp = aText.compareTo(bText)
                if (sortAsc) cmp else -cmp
            }
        },
    )

    val headersList = table.querySelectorAll(SORTABLE_TH_QUERY)
    for (i in 0 until headersList.length) {
        (headersList.item(i) as? HTMLElement)?.apply {
            classList.remove(CssClass.Utility.Asc, CssClass.Utility.Desc)
            setAttribute(HtmlAttrs.DATA_SORT, DataSort.NONE)
        }
    }
    header.classList.add(if (sortAsc) CssClass.Utility.Asc else CssClass.Utility.Desc)
    header.setAttribute(HtmlAttrs.DATA_SORT, if (sortAsc) DataSort.ASCENDING else DataSort.DESCENDING)

    rows.forEach { row -> tbody.appendChild(row) }

    currentSortCol = colIdx
    currentSortDir = (if (sortAsc) CssClass.Utility.Asc else CssClass.Utility.Desc).toString()
}
