package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.util.StreamStatus
import com.gemini.krakenbot.view.util.ActionLogFormat
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.DataSort
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlEvents
import com.gemini.krakenbot.view.util.HtmlTags
import com.gemini.krakenbot.view.util.RecentActivityText
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import kotlin.js.Date
import com.gemini.krakenbot.view.util.HtmlQueries.DATA_AGE_TIME as DATA_AGE_TIME_QUERY
import com.gemini.krakenbot.view.util.HtmlQueries.DATA_AGE_VALUE as DATA_AGE_VALUE_QUERY
import com.gemini.krakenbot.view.util.HtmlQueries.HOVERABLE_TR as HOVERABLE_TR_QUERY
import com.gemini.krakenbot.view.util.HtmlQueries.SORTABLE_TH as SORTABLE_TH_QUERY
import com.gemini.krakenbot.view.util.HtmlQueries.STATUS_BADGE as STATUS_BADGE_QUERY
import org.w3c.dom.events.Event as DomEvent

internal var currentSortCol: Int = PrecisionConstants.DEFAULT_SORT_COL_INDEX
internal var currentSortDir: String = CssClass.Utility.Asc.toString()
internal var recentActivityUiState = RecentActivityUiState()
private var recentActivityHandlersRegistered = false

// Currency/percent decoration plus formatting whitespace (regular, non-breaking, and narrow
// non-breaking spaces) is stripped before numeric parsing; JS Number("3,000.00") would otherwise
// be NaN and sort as 0.0.
private val CURRENCY_CLEANUP_REGEX = Regex("[$,%\\s\\u00A0\\u202F]")

fun registerDashboardGlobals() {
    window.asDynamic().sortTable = { header: HTMLElement, colIdx: Int ->
        sortTable(header, colIdx)
    }
    if (recentActivityHandlersRegistered) return
    recentActivityHandlersRegistered = true
    document.addEventListener("click", ::handleRecentActivityClick)
    document.addEventListener(HtmlEvents.HTMX_BEFORE_SWAP, { captureRecentActivityState() })
    document.addEventListener(HtmlEvents.HTMX_AFTER_SWAP, { restoreRecentActivityState() })
    restoreRecentActivityState()
}

fun updateAge() {
    val ageEl = document.querySelector(DATA_AGE_VALUE_QUERY) as? HTMLElement ?: return
    val timeEl = document.querySelector(DATA_AGE_TIME_QUERY) as? HTMLElement ?: return

    val epochStr = timeEl.getAttribute(HtmlAttrs.DATA_EPOCH) ?: return
    val epoch = epochStr.toDoubleOrNull() ?: return
    val now = Date.now()
    val diff = ((now - epoch) / PrecisionConstants.MILLIS_PER_SECOND).toInt().coerceAtLeast(0)

    ageEl.textContent = "${diff}${ViewText.AGO_SECONDS}"
    val isStale = StreamStatus.isStale(diff.toLong())
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
    repeat(headersList.length) { i ->
        (headersList.item(i) as? HTMLElement)?.apply {
            classList.remove(CssClass.Utility.Asc, CssClass.Utility.Desc)
            setAttribute(HtmlAttrs.DATA_SORT, DataSort.NONE)
        }
    }
    header.classList.add(if (sortAsc) CssClass.Utility.Asc.value else CssClass.Utility.Desc.value)
    header.setAttribute(HtmlAttrs.DATA_SORT, if (sortAsc) DataSort.ASCENDING else DataSort.DESCENDING)

    rows.forEach { row -> tbody.appendChild(row) }

    currentSortCol = colIdx
    currentSortDir = (if (sortAsc) CssClass.Utility.Asc else CssClass.Utility.Desc).toString()
}

internal data class RecentActivityItem(
    val cycleId: String,
    val occurredAt: Double,
    val mode: String,
    val outcome: String?,
    val actions: List<String>,
    val displayedActions: List<String> = emptyList(),
)

internal data class RecentActivityUiState(
    var sourceId: String? = null,
    var items: List<RecentActivityItem> = emptyList(),
    var filter: String = RecentActivityText.FILTER_VALUE_ALL,
    var visibleCount: Int = RecentActivityText.PAGE_SIZE.toInt(),
    val expandedCycleIds: MutableSet<String> = mutableSetOf(),
    var pageScrollY: Double = 0.0,
)

internal fun mergeRecentActivityItems(
    existing: List<RecentActivityItem>,
    incoming: List<RecentActivityItem>,
): List<RecentActivityItem> {
    val byCycleId = linkedMapOf<String, RecentActivityItem>()
    (existing + incoming).forEach { item -> byCycleId[item.cycleId] = item }
    return byCycleId.values
        .sortedWith(compareByDescending<RecentActivityItem> { it.occurredAt }.thenByDescending { it.cycleId })
        .take(RecentActivityText.MAX_ITEMS.toInt())
}

internal fun filterRecentActivityItems(items: List<RecentActivityItem>, filter: String): List<RecentActivityItem> =
    when (filter) {
        RecentActivityText.FILTER_VALUE_ORDERS -> items.filter { item ->
            outcomeCode(item.outcome) in setOf("DRY_RUN_PLAN", "ORDERS_ATTEMPTED") ||
                item.actions.any(::isOrderAction)
        }

        RecentActivityText.FILTER_VALUE_PROBLEMS -> items.filter { item ->
            outcomeCode(item.outcome) == "PROBLEM" || item.actions.any(::isProblemAction)
        }

        else -> items
    }

internal fun captureRecentActivityState() {
    val root = recentActivityRoot() ?: return
    val sourceId = root.getAttribute(RecentActivityText.SOURCE_ID_ATTRIBUTE) ?: return
    val renderedItems = readRenderedRecentActivityItems(root)
    if (recentActivityUiState.sourceId != sourceId) {
        resetRecentActivitySource(sourceId, renderedItems)
    } else {
        recentActivityUiState.items = mergeRecentActivityItems(recentActivityUiState.items, renderedItems)
    }

    recentActivityUiState.filter = root.getAttribute(RecentActivityText.FILTER_ATTRIBUTE)
        ?: recentActivityUiState.filter
    recentActivityUiState.visibleCount = root.getAttribute(RecentActivityText.VISIBLE_COUNT_ATTRIBUTE)
        ?.toIntOrNull()?.coerceIn(1, RecentActivityText.MAX_ITEMS.toInt())
        ?: recentActivityUiState.visibleCount
    recentActivityUiState.pageScrollY = window.scrollY
    val visibleIds = renderedItems.mapTo(mutableSetOf()) { it.cycleId }
    val openIds = readOpenCycleIds(root)
    recentActivityUiState.expandedCycleIds.removeAll(visibleIds)
    recentActivityUiState.expandedCycleIds.addAll(openIds)
    recentActivityUiState.expandedCycleIds.retainAll(recentActivityUiState.items.map { it.cycleId }.toSet())
}

internal fun restoreRecentActivityState() {
    val root = recentActivityRoot() ?: return
    val sourceId = root.getAttribute(RecentActivityText.SOURCE_ID_ATTRIBUTE) ?: return
    val renderedItems = readRenderedRecentActivityItems(root)
    val sourceChanged = recentActivityUiState.sourceId != sourceId
    val newActivityIds = if (sourceChanged) {
        renderedItems.filter(::hasActivity).take(1).mapTo(mutableSetOf()) { it.cycleId }
    } else {
        val knownCycleIds = recentActivityUiState.items.mapTo(mutableSetOf()) { it.cycleId }
        renderedItems.filter { it.cycleId !in knownCycleIds && hasActivity(it) }.mapTo(mutableSetOf()) { it.cycleId }
    }
    if (sourceChanged) {
        resetRecentActivitySource(sourceId, renderedItems)
    } else {
        recentActivityUiState.items = mergeRecentActivityItems(recentActivityUiState.items, renderedItems)
    }
    recentActivityUiState.expandedCycleIds.addAll(newActivityIds)
    recentActivityUiState.expandedCycleIds.retainAll(recentActivityUiState.items.map { it.cycleId }.toSet())
    renderRecentActivity(root)
    if (recentActivityUiState.pageScrollY > 0.0) window.scrollTo(window.scrollX, recentActivityUiState.pageScrollY)
}

internal fun resetRecentActivityClientState() {
    recentActivityUiState = RecentActivityUiState()
}

private fun resetRecentActivitySource(sourceId: String, initialItems: List<RecentActivityItem>) {
    recentActivityUiState = RecentActivityUiState(
        sourceId = sourceId,
        items = mergeRecentActivityItems(emptyList(), initialItems),
        filter = recentActivityUiState.filter,
        pageScrollY = recentActivityUiState.pageScrollY,
    )
}

private fun handleRecentActivityClick(event: DomEvent) {
    val clicked = (event.target as? Element)?.closest("button") as? HTMLButtonElement ?: return
    val root = clicked.closest("#${RecentActivityText.FEED_ID}") as? HTMLElement ?: return
    val filter = clicked.getAttribute(RecentActivityText.FILTER_ATTRIBUTE)
    if (filter != null) {
        recentActivityUiState.filter = filter
        renderRecentActivity(root)
    } else if (clicked.id == RecentActivityText.SHOW_OLDER_ID) {
        recentActivityUiState.visibleCount =
            (recentActivityUiState.visibleCount + RecentActivityText.PAGE_INCREMENT.toInt())
                .coerceAtMost(RecentActivityText.MAX_ITEMS.toInt())
        renderRecentActivity(root)
    }
}

private fun renderRecentActivity(root: Element) {
    val rows = filterRecentActivityItems(recentActivityUiState.items, recentActivityUiState.filter)
    val visible = rows.take(recentActivityUiState.visibleCount)
    val groups = root.querySelector("#${RecentActivityText.GROUPS_ID}") ?: return
    while (groups.firstChild != null) groups.removeChild(groups.firstChild!!)
    visible.forEach { groups.appendChild(renderRecentActivityItem(it)) }
    if (visible.isEmpty()) {
        val empty = document.createElement("p") as HTMLElement
        empty.textContent = RecentActivityText.EMPTY_FILTER
        groups.appendChild(empty)
    }
    root.setAttribute(RecentActivityText.FILTER_ATTRIBUTE, recentActivityUiState.filter)
    root.setAttribute(RecentActivityText.VISIBLE_COUNT_ATTRIBUTE, recentActivityUiState.visibleCount.toString())
    updateActivityFilterButtons(root)
    val showOlder = root.querySelector("#${RecentActivityText.SHOW_OLDER_ID}") as? HTMLButtonElement
    showOlder?.hidden = rows.size <= visible.size || visible.size >= RecentActivityText.MAX_ITEMS.toInt()
}

private fun renderRecentActivityItem(item: RecentActivityItem): HTMLElement {
    val row = document.createElement("details") as HTMLElement
    row.className = CssClass.Activity.Cycle.value
    row.setAttribute(RecentActivityText.CYCLE_ID_ATTRIBUTE, item.cycleId)
    row.setAttribute(RecentActivityText.OCCURRED_AT_ATTRIBUTE, item.occurredAt.toString())
    row.setAttribute(RecentActivityText.MODE_ATTRIBUTE, item.mode)
    row.setAttribute(RecentActivityText.OUTCOME_ATTRIBUTE, item.outcome ?: "RECORDED")
    row.setAttribute(RecentActivityText.PROBLEM_ATTRIBUTE, itemProblem(item).toString())
    row.setAttribute(RecentActivityText.ORDERS_ATTRIBUTE, itemHasOrders(item).toString())
    val summary = document.createElement("summary") as HTMLElement
    summary.className = CssClass.Activity.CycleHeader.value
    appendActivityText(summary, CssClass.Activity.CycleTime.value, activityLocalTime(item.occurredAt))
    appendActivityText(summary, CssClass.Activity.CycleMeta.value, activityOutcomeLabel(item.outcome))
    val actionCount = item.actions.size
    val countLabel = if (actionCount == 1) RecentActivityText.ACTION_COUNT_ONE else RecentActivityText.ACTION_COUNT_MANY
    appendActivityText(summary, CssClass.Activity.CycleMeta.value, "$actionCount $countLabel")
    row.appendChild(summary)
    val body = document.createElement("div") as HTMLElement
    body.className = CssClass.Activity.CycleBody.value
    appendActivityText(body, CssClass.Activity.CycleIdentity.value, "${ViewText.ACTIVITY_CYCLE_PREFIX} ${item.cycleId}")
    appendActivityText(body, CssClass.Activity.CycleIdentity.value, activityModeLabel(item.mode))
    item.actions.forEachIndexed { index, action ->
        body.appendChild(renderRecentActivityAction(action, item.displayedActions.getOrNull(index)))
    }
    row.appendChild(body)
    row.asDynamic().open = item.cycleId in recentActivityUiState.expandedCycleIds
    row.addEventListener("toggle", {
        if (row.asDynamic().open as Boolean) {
            recentActivityUiState.expandedCycleIds.add(item.cycleId)
        } else {
            recentActivityUiState.expandedCycleIds.remove(item.cycleId)
        }
    })
    return row
}

private fun renderRecentActivityAction(action: String, displayText: String?): HTMLElement {
    val side = activityOrderSide(action)
    val item = document.createElement("div") as HTMLElement
    item.className = if (side == null) CssClass.Activity.Item.value else CssClass.Activity.ItemTrade.value
    item.setAttribute(RecentActivityText.ACTION_ATTRIBUTE, action)
    if (side != null) {
        val badgeClass = if (side == "BUY") CssClass.Badge.Buy else CssClass.Badge.Sell
        appendActivityText(item, badgeClass.value, side)
    }
    appendActivityText(item, CssClass.Activity.ItemText.value, displayText ?: action)
    return item
}

private fun updateActivityFilterButtons(root: Element) {
    val buttons = root.querySelectorAll("button[${RecentActivityText.FILTER_ATTRIBUTE}]")
    repeat(buttons.length) { index ->
        val button = buttons.item(index) as? HTMLElement ?: return@repeat
        val selected = button.getAttribute(RecentActivityText.FILTER_ATTRIBUTE) == recentActivityUiState.filter
        button.className = if (selected) CssClass.Button.Primary.value else CssClass.Button.Secondary.value
    }
}

private fun readRenderedRecentActivityItems(root: Element): List<RecentActivityItem> {
    val nodes = root.querySelectorAll("details[${RecentActivityText.CYCLE_ID_ATTRIBUTE}]")
    return (0 until nodes.length).mapNotNull { index ->
        val node = nodes.item(index) as? HTMLElement ?: return@mapNotNull null
        val cycleId = node.getAttribute(RecentActivityText.CYCLE_ID_ATTRIBUTE) ?: return@mapNotNull null
        val occurredAt = node.getAttribute(RecentActivityText.OCCURRED_AT_ATTRIBUTE)?.toDoubleOrNull()
            ?: return@mapNotNull null
        val actionNodes = node.querySelectorAll("[${RecentActivityText.ACTION_ATTRIBUTE}]")
        val actions = (0 until actionNodes.length).mapNotNull { actionIndex ->
            (actionNodes.item(actionIndex) as? Element)?.getAttribute(RecentActivityText.ACTION_ATTRIBUTE)
        }
        val displayNodes = node.querySelectorAll(".${CssClass.Activity.ItemText.value}")
        val displayed = (0 until displayNodes.length).mapNotNull { displayIndex ->
            displayNodes.item(displayIndex)?.textContent
        }
        RecentActivityItem(
            cycleId = cycleId,
            occurredAt = occurredAt,
            mode = node.getAttribute(RecentActivityText.MODE_ATTRIBUTE).orEmpty(),
            outcome = node.getAttribute(RecentActivityText.OUTCOME_ATTRIBUTE),
            actions = actions,
            displayedActions = displayed,
        )
    }
}

private fun readOpenCycleIds(root: Element): Set<String> {
    val nodes = root.querySelectorAll("details[open][${RecentActivityText.CYCLE_ID_ATTRIBUTE}]")
    return (0 until nodes.length).mapNotNullTo(mutableSetOf()) { index ->
        (nodes.item(index) as? Element)?.getAttribute(RecentActivityText.CYCLE_ID_ATTRIBUTE)
    }
}

private fun recentActivityRoot(): HTMLElement? = document.getElementById(RecentActivityText.FEED_ID) as? HTMLElement

private fun appendActivityText(parent: HTMLElement, classes: String, text: String) {
    val child = document.createElement("span") as HTMLElement
    child.className = classes
    child.textContent = text
    parent.appendChild(child)
}

private fun outcomeCode(outcome: String?): String = when (outcome?.uppercase()) {
    "NO_TRADE" -> "NO_TRADE"
    "DRY_RUN_PLAN" -> "DRY_RUN_PLAN"
    "ORDERS_ATTEMPTED" -> "ORDERS_ATTEMPTED"
    "PROBLEM" -> "PROBLEM"
    else -> "RECORDED"
}

private fun activityOutcomeLabel(outcome: String?): String = when (outcomeCode(outcome)) {
    "NO_TRADE" -> RecentActivityText.OUTCOME_NO_TRADE
    "DRY_RUN_PLAN" -> RecentActivityText.OUTCOME_DRY_RUN_PLAN
    "ORDERS_ATTEMPTED" -> RecentActivityText.OUTCOME_ORDERS_ATTEMPTED
    "PROBLEM" -> RecentActivityText.OUTCOME_PROBLEM
    else -> RecentActivityText.OUTCOME_RECORDED
}

private fun activityModeLabel(mode: String): String = when (mode.uppercase()) {
    "SIMULATION" -> ViewText.MODE_SIMULATION
    "DRY_RUN" -> ViewText.MODE_DRY_RUN
    "LIVE", "LIVE_TRADING" -> ViewText.MODE_LIVE
    else -> mode
}

private fun itemHasOrders(item: RecentActivityItem): Boolean =
    outcomeCode(item.outcome) in setOf("DRY_RUN_PLAN", "ORDERS_ATTEMPTED") || item.actions.any(::isOrderAction)

private fun hasActivity(item: RecentActivityItem): Boolean =
    item.actions.isNotEmpty() || outcomeCode(item.outcome) in setOf("DRY_RUN_PLAN", "ORDERS_ATTEMPTED", "PROBLEM")

private fun itemProblem(item: RecentActivityItem): Boolean =
    outcomeCode(item.outcome) == "PROBLEM" || item.actions.any(::isProblemAction)

private fun isOrderAction(action: String): Boolean = activityOrderSide(action) != null

private fun activityOrderSide(action: String): String? {
    val normalized = action.uppercase().removePrefix(ActionLogFormat.DRY_RUN_PREFIX.uppercase()).trim()
    return when {
        normalized.startsWith("BUY") -> "BUY"
        normalized.startsWith("SELL") -> "SELL"
        else -> null
    }
}

private fun isProblemAction(action: String): Boolean {
    val normalized = action.uppercase().trimStart()
    return normalized.startsWith("ERROR") || normalized.startsWith("FAILED") || normalized.startsWith("WARNING")
}

private fun activityLocalTime(timestamp: Double): String {
    val date = Date(timestamp)
    fun pad(value: Int) = value.toString().padStart(2, '0')
    return "${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} " +
        "${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}"
}
