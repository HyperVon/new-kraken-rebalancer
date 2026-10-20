package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HistoryChartActions
import com.gemini.krakenbot.view.util.HistoryRangeCss
import com.gemini.krakenbot.view.util.HistoryRangeEvents
import com.gemini.krakenbot.view.util.HistoryRangeIds
import com.gemini.krakenbot.view.util.HistoryRangeKeys
import com.gemini.krakenbot.view.util.HistoryRangeProps
import com.gemini.krakenbot.view.util.HistoryRangeQuery
import com.gemini.krakenbot.view.util.HistoryRangeText
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlEvents
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.borderColorForSymbol
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLFormElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import kotlin.js.Date
import kotlin.js.json

internal data class HistoryChartPoint(val time: Double, val value: Double?)

internal data class HistoryRangeState(
    val preset: String,
    val start: String?,
    val end: String?,
    val timezone: String,
    val scope: String?,
)

internal data class HistoryPageTabState(val tab: String, val activity: String)

private data class HistoryScopeOption(
    val id: String,
    val symbols: List<String>,
    val earliestIso: String?,
    val latestIso: String?,
    val current: Boolean,
)

private val refreshedHistoryEvidenceSignatures = mutableMapOf<String, String>()
internal var historyLatestDataHandler: (() -> Unit)? = null
private var historyStatusPoller: HistoryDataStatusPoller? = null
private var historyRefreshReinitializer: (() -> Unit)? = null
private var historyAfterSwapListenerInstalled = false

/** Read only direct server-rendered evidence; incomplete samples remain explicit null breaks. */
internal fun historyChartPoints(container: Element): List<HistoryChartPoint> {
    val markers = container.querySelectorAll("circle[${HtmlAttrs.DATA_TIME}]")
    return (0 until markers.length).mapNotNull { index ->
        val marker = markers.item(index) as? Element ?: return@mapNotNull null
        val time = marker.getAttribute(HtmlAttrs.DATA_TIME)?.toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: return@mapNotNull null
        val value = marker.getAttribute(HtmlAttrs.DATA_VALUE)?.toDoubleOrNull()?.takeIf { it.isFinite() }
        if (marker.getAttribute(HtmlAttrs.DATA_GAP) == "true") {
            HistoryChartPoint(time, null)
        } else {
            value?.let { HistoryChartPoint(time, it) }
        }
    }.sortedBy { it.time }.distinctBy { it.time }
}

/** Per-series samples (single-series charts group under their series attribute). */
internal fun historySeriesPoints(container: Element): List<Pair<String, List<HistoryChartPoint>>> {
    val markers = container.querySelectorAll("circle[${HtmlAttrs.DATA_TIME}]")
    val grouped = linkedMapOf<String, MutableList<HistoryChartPoint>>()
    repeat(markers.length) { index ->
        val marker = markers.item(index) as? Element ?: return@repeat
        val time = marker.getAttribute(HtmlAttrs.DATA_TIME)?.toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: return@repeat
        val value = if (marker.getAttribute(HtmlAttrs.DATA_GAP) == "true") {
            null
        } else {
            marker.getAttribute(HtmlAttrs.DATA_VALUE)?.toDoubleOrNull()?.takeIf { it.isFinite() }
                ?: return@repeat
        }
        val series = marker.getAttribute(HtmlAttrs.DATA_SERIES) ?: ""
        grouped.getOrPut(series) { mutableListOf() }.add(HistoryChartPoint(time, value))
    }
    return grouped.map { (series, points) ->
        series to points.sortedBy { it.time }.distinctBy { it.time }
    }
}

/** Stable identity of the server-rendered evidence; a changed signature invalidates a live chart. */
internal fun historyEvidenceSignature(container: Element): String {
    val markers = container.querySelectorAll("circle[${HtmlAttrs.DATA_TIME}]")
    return (0 until markers.length).joinToString("\n") { index ->
        val marker = markers.item(index) as? Element
        listOf(
            marker?.getAttribute(HtmlAttrs.DATA_TIME),
            marker?.getAttribute(HtmlAttrs.DATA_VALUE),
            marker?.getAttribute(HtmlAttrs.DATA_GAP),
            marker?.getAttribute(HtmlAttrs.DATA_SERIES),
        ).joinToString("|")
    }
}

private fun historyPad(value: Int, size: Int): String = value.toString().padStart(size, '0')

/** Wall-clock label in the browser's local time. */
internal fun historyDateLabel(time: Double): String {
    val date = Date(time)
    return "${date.getFullYear()}-${historyPad(date.getMonth() + 1, 2)}-${historyPad(date.getDate(), 2)} " +
        "${historyPad(date.getHours(), 2)}:${historyPad(date.getMinutes(), 2)}:" +
        "${historyPad(date.getSeconds(), 2)}.${historyPad(date.getMilliseconds(), 3)}"
}

internal fun historyCustomRangeError(start: String?, end: String?): String? = when {
    start.isNullOrBlank() || end.isNullOrBlank() -> "Choose both a start and an end date."
    !isHistoryLocalDate(start) || !isHistoryLocalDate(end) -> "Enter valid calendar dates."
    start > end -> "The start date must not follow the end date."
    else -> null
}

private fun isHistoryLocalDate(value: String): Boolean {
    if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) return false
    val (year, month, day) = value.split('-').map(String::toInt)
    val date: dynamic = js("new Date(0)")
    date.setUTCFullYear(year, month - 1, day)
    return (date.getUTCFullYear() as Number).toInt() == year &&
        (date.getUTCMonth() as Number).toInt() == month - 1 &&
        (date.getUTCDate() as Number).toInt() == day
}

private fun historyBrowserTimezone(): String {
    val formatter: dynamic = js("new Intl.DateTimeFormat()")
    return formatter.resolvedOptions().timeZone as? String ?: "UTC"
}

/** Axis tick label in the browser's local time: clock time for short windows, date plus time otherwise. */
internal fun historyAxisTimeLabel(time: Double, shortWindow: Boolean): String {
    val date = Date(time)
    return if (shortWindow) {
        "${historyPad(date.getHours(), 2)}:${historyPad(date.getMinutes(), 2)}:${historyPad(date.getSeconds(), 2)}"
    } else {
        "${historyPad(date.getMonth() + 1, 2)}-${historyPad(date.getDate(), 2)} " +
            "${historyPad(date.getHours(), 2)}:${historyPad(date.getMinutes(), 2)}"
    }
}

internal fun historyAxisTimeLabel(time: Double, range: ChartRange): String =
    historyAxisTimeLabel(time, range.span < 3_600_000.0)

private fun historyNumber(value: Double, money: Boolean): String = if (money) {
    "$" + value.asDynamic().toLocaleString("en-US", json("maximumFractionDigits" to 2)).toString()
} else {
    value.asDynamic().toLocaleString("en-US", json("maximumFractionDigits" to 8)).toString()
}

internal fun initHistorySeriesSelectors() {
    val selectors = document.querySelectorAll("[${HtmlAttrs.DATA_HISTORY_SERIES_SELECT}]")
    for (index in 0 until selectors.length) {
        val select = selectors.item(index) as? HTMLSelectElement ?: continue
        if (select.asDynamic().historySeriesSelectorInitialized == true) continue
        val chartId = select.getAttribute(HtmlAttrs.DATA_HISTORY_SERIES_SELECT) ?: continue
        val chart = document.getElementById(chartId) as? HTMLElement ?: continue
        select.asDynamic().historySeriesSelectorInitialized = true

        fun updateSelection() {
            val series = chart.querySelectorAll("[${HtmlAttrs.DATA_HISTORY_SERIES_ASSET}]")
            for (seriesIndex in 0 until series.length) {
                val panel = series.item(seriesIndex) as? HTMLElement ?: continue
                panel.hidden = panel.getAttribute(HtmlAttrs.DATA_HISTORY_SERIES_ASSET) != select.value
            }
        }

        select.addEventListener("change", { updateSelection() })
        updateSelection()
    }
}

private fun historyParams(query: String): dynamic = js("new URLSearchParams(query)")

private fun historyParam(query: String, key: String): String? = historyParams(query).get(key) as? String

internal fun historyPageTabState(query: String): HistoryPageTabState {
    val tab = historyParam(query, HistoryRangeQuery.PARAM_TAB)
        ?.takeIf {
            it in setOf(
                HistoryRangeQuery.TAB_PORTFOLIO,
                HistoryRangeQuery.TAB_ACTIVITY,
                HistoryRangeQuery.TAB_DATA_STATUS,
            )
        }
        ?: HistoryRangeQuery.TAB_PORTFOLIO
    val activity = historyParam(query, HistoryRangeQuery.PARAM_ACTIVITY)
        ?.takeIf {
            it in setOf(
                HistoryRangeQuery.ACTIVITY_BOT,
                HistoryRangeQuery.ACTIVITY_TRADES,
                HistoryRangeQuery.ACTIVITY_TRANSACTIONS,
            )
        }
        ?: HistoryRangeQuery.ACTIVITY_BOT
    return HistoryPageTabState(tab, activity)
}

internal fun historyTabsQuery(query: String, tab: String, activity: String? = null): String {
    val params = historyParams(query)
    val state = historyPageTabState(query)
    val selectedTab = tab.takeIf {
        it in setOf(
            HistoryRangeQuery.TAB_PORTFOLIO,
            HistoryRangeQuery.TAB_ACTIVITY,
            HistoryRangeQuery.TAB_DATA_STATUS,
        )
    } ?: HistoryRangeQuery.TAB_PORTFOLIO
    params.set(HistoryRangeQuery.PARAM_TAB, selectedTab)
    if (selectedTab == HistoryRangeQuery.TAB_ACTIVITY || activity != null) {
        params.set(
            HistoryRangeQuery.PARAM_ACTIVITY,
            activity?.takeIf {
                it in setOf(
                    HistoryRangeQuery.ACTIVITY_BOT,
                    HistoryRangeQuery.ACTIVITY_TRADES,
                    HistoryRangeQuery.ACTIVITY_TRANSACTIONS,
                )
            } ?: state.activity,
        )
    }
    return params.toString()
}

internal fun historyActivityCursorStack(query: String): List<String> =
    historyParam(query, HistoryRangeQuery.PARAM_ACTIVITY_CURSORS)
        ?.split(',')
        ?.filter(String::isNotBlank)
        .orEmpty()

internal fun historyActivityRefreshKeepsPage(query: String): Boolean = historyActivityCursorStack(query).isNotEmpty()

internal fun historyActivityNextPageQuery(query: String, cursor: String?): String? {
    if (cursor.isNullOrBlank()) return null
    val params = historyParams(historyTabsQuery(query, HistoryRangeQuery.TAB_ACTIVITY, HistoryRangeQuery.ACTIVITY_BOT))
    params.set(
        HistoryRangeQuery.PARAM_ACTIVITY_CURSORS,
        (historyActivityCursorStack(query) + cursor).joinToString(","),
    )
    return params.toString()
}

internal fun historyActivityPreviousPageQuery(query: String): String? {
    val stack = historyActivityCursorStack(query)
    if (stack.isEmpty()) return null
    val previous = stack.dropLast(1)
    val params = historyParams(historyTabsQuery(query, HistoryRangeQuery.TAB_ACTIVITY, HistoryRangeQuery.ACTIVITY_BOT))
    if (previous.isEmpty()) {
        params.delete(HistoryRangeQuery.PARAM_ACTIVITY_CURSORS)
    } else {
        params.set(HistoryRangeQuery.PARAM_ACTIVITY_CURSORS, previous.joinToString(","))
    }
    return params.toString()
}

internal fun historyActivityApiUrl(query: String): String {
    val params = historyParams(
        historyApiQuery(historyRangeState(query, null, historyBrowserTimezone()), chart = false),
    )
    historyActivityCursorStack(query).lastOrNull()?.let { params.set(HistoryRangeQuery.PARAM_CURSOR, it) }
    params.set(HistoryRangeQuery.PARAM_LIMIT, HistoryRangeProps.RECORD_PAGE_SIZE.toString())
    return "${Routes.API_HISTORY_ACTIVITIES}?$params"
}

internal fun historyExportUrl(
    kind: String,
    query: String,
    from: String?,
    to: String?,
    preset: String? = null,
    fallbackTimezone: String = historyBrowserTimezone(),
): String {
    val route = when (kind) {
        "activity" -> Routes.API_HISTORY_EXPORT_ACTIVITY
        "trades" -> Routes.API_HISTORY_EXPORT_TRADES
        "ledgers" -> Routes.API_HISTORY_EXPORT_LEDGERS
        else -> return ""
    }
    val params: dynamic = js("new URLSearchParams()")
    params.set(
        HistoryRangeQuery.PARAM_PRESET,
        preset ?: historyParam(query, HistoryRangeQuery.PARAM_RANGE) ?: HistoryRangeKeys.DEFAULT,
    )
    listOf(
        HistoryRangeQuery.PARAM_START,
        HistoryRangeQuery.PARAM_END,
        HistoryRangeQuery.PARAM_SCOPE,
        HistoryRangeQuery.PARAM_MODE,
    ).forEach { key -> historyParam(query, key)?.let { params.set(key, it) } }
    params.set(
        HistoryRangeQuery.PARAM_TZ,
        historyParam(query, HistoryRangeQuery.PARAM_TZ)?.takeIf(String::isNotBlank) ?: fallbackTimezone,
    )
    val eventFilterKeys = when (kind) {
        "trades" -> listOf(
            HistoryRangeQuery.PARAM_ASSET,
            HistoryRangeQuery.PARAM_PAIR,
            HistoryRangeQuery.PARAM_TYPE,
        )

        "ledgers" -> listOf(HistoryRangeQuery.PARAM_ASSET, HistoryRangeQuery.PARAM_TYPE)

        else -> emptyList()
    }
    eventFilterKeys.forEach { key -> historyParam(query, key)?.let { params.set(key, it) } }
    (from ?: historyParam(query, HistoryRangeQuery.PARAM_FROM))
        ?.let { params.set(HistoryRangeQuery.PARAM_FROM, it) }
    (to ?: historyParam(query, HistoryRangeQuery.PARAM_TO))
        ?.let { params.set(HistoryRangeQuery.PARAM_TO, it) }
    return "$route?$params"
}

internal fun updateHistoryTabPanels(query: String) {
    val state = historyPageTabState(query)
    val pagePanels = document.querySelectorAll("[${HtmlAttrs.DATA_HISTORY_PANEL}]")
    repeat(pagePanels.length) { index ->
        val panel = pagePanels.item(index) as? HTMLElement ?: return@repeat
        panel.hidden = panel.getAttribute(HtmlAttrs.DATA_HISTORY_PANEL) != state.tab
    }
    val activityPanels = document.querySelectorAll("[${HtmlAttrs.DATA_HISTORY_ACTIVITY_PANEL}]")
    repeat(activityPanels.length) { index ->
        val panel = activityPanels.item(index) as? HTMLElement ?: return@repeat
        panel.hidden = state.tab != HistoryRangeQuery.TAB_ACTIVITY ||
            panel.getAttribute(HtmlAttrs.DATA_HISTORY_ACTIVITY_PANEL) != state.activity
    }
    val tabNav = document.getElementById(HistoryRangeIds.TAB_NAV)
    val tabs = tabNav?.querySelectorAll("[${HtmlAttrs.DATA_HISTORY_TAB}]")
    if (tabs != null) {
        repeat(tabs.length) { index ->
            val link = tabs.item(index) as? Element ?: return@repeat
            val active = link.getAttribute(HtmlAttrs.DATA_HISTORY_TAB) == state.tab
            link.setAttribute(
                "class",
                if (active) CssClass.History.TimeRangeBtnActive.value else CssClass.History.TimeRangeBtn.value,
            )
        }
    }
    val activityNav = document.getElementById(HistoryRangeIds.ACTIVITY_NAV)
    val activityTabs = activityNav?.querySelectorAll("[${HtmlAttrs.DATA_HISTORY_ACTIVITY_TAB}]")
    if (activityTabs != null) {
        repeat(activityTabs.length) { index ->
            val link = activityTabs.item(index) as? Element ?: return@repeat
            val active = link.getAttribute(HtmlAttrs.DATA_HISTORY_ACTIVITY_TAB) == state.activity
            link.setAttribute(
                "class",
                if (active) CssClass.History.TimeRangeBtnActive.value else CssClass.History.TimeRangeBtn.value,
            )
        }
    }
    (document.getElementById(HistoryRangeIds.FILTER_TAB) as? HTMLInputElement)?.value = state.tab
    (document.getElementById(HistoryRangeIds.FILTER_ACTIVITY) as? HTMLInputElement)?.value = state.activity
    updateHistoryPaginationLinks(query)
    if (state.tab == HistoryRangeQuery.TAB_PORTFOLIO) {
        val portfolio = document.getElementById(HistoryRangeIds.PANEL_PORTFOLIO)
        historyChartSessions.values
            .filter { portfolio?.contains(it.container) == true }
            .forEach { session ->
                session.chart.resize()
                updateChart(session.chart)
            }
    }
}

private fun updateHistoryPaginationLinks(query: String) {
    val paginations = document.querySelectorAll("[data-history-pagination]")
    repeat(paginations.length) { index ->
        val pagination = paginations.item(index) as? Element ?: return@repeat
        val table = pagination.getAttribute("data-history-pagination") ?: return@repeat
        val offsetKey = if (table == "trades") {
            HistoryRangeQuery.PARAM_TRADES_OFFSET
        } else {
            HistoryRangeQuery.PARAM_LEDGERS_OFFSET
        }
        val offset = historyParam(query, offsetKey)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val limit = pagination.getAttribute("data-history-limit")?.toIntOrNull()
            ?.coerceAtLeast(1) ?: HistoryRangeProps.RECORD_PAGE_SIZE
        pagination.setAttribute("data-history-offset", offset.toString())
        val previousId = if (table == "trades") HtmlIds.HISTORY_TRADES_PREV else HtmlIds.HISTORY_LEDGERS_PREV
        val nextId = if (table == "trades") HtmlIds.HISTORY_TRADES_NEXT else HtmlIds.HISTORY_LEDGERS_NEXT
        (document.getElementById(previousId) as? HTMLElement)?.let { previous ->
            previous.hidden = offset == 0
            if (offset > 0) {
                previous.setAttribute("href", historyPaginationHref(query, table, (offset - limit).coerceAtLeast(0)))
            }
        }
        document.getElementById(nextId)?.setAttribute("href", historyPaginationHref(query, table, offset + limit))
    }
}

private fun updateHistoryExportLinks(query: String, range: dynamic) {
    val from = historyStatusString(range, "fromIso")
    val to = historyStatusString(range, "toIso")
    val links = document.querySelectorAll("[${HtmlAttrs.DATA_HISTORY_EXPORT}]")
    repeat(links.length) { index ->
        val link = links.item(index) as? Element ?: return@repeat
        val kind = link.getAttribute(HtmlAttrs.DATA_HISTORY_EXPORT) ?: return@repeat
        historyExportUrl(kind, query, from, to, historyStatusString(range, "preset"))
            .takeIf(String::isNotBlank)
            ?.let {
                link.setAttribute("href", it)
            }
    }
}

internal fun initHistoryDataActions() {
    fun bindForm(formId: String, messageId: String?, successMessage: String?) {
        val target = document.getElementById(formId) ?: return
        val form = if (target is HTMLFormElement) {
            target
        } else {
            target.asDynamic().closest("form") as? HTMLFormElement
        } ?: return
        if (form.asDynamic().historyActionInitialized == true) return
        form.asDynamic().historyActionInitialized = true
        val message = messageId?.let { document.getElementById(it) } as? HTMLElement
        form.addEventListener("submit", { event: Event ->
            event.preventDefault()
            val submit = form.querySelector("button[type='submit']") as? HTMLButtonElement
            submit?.disabled = true
            message?.let {
                it.hidden = true
                it.textContent = ""
            }
            val body: String = js("new URLSearchParams(new FormData(form)).toString()")
            val options = json(
                "method" to "POST",
                "body" to body,
                "credentials" to "same-origin",
                "headers" to json("Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8"),
            )
            window.asDynamic().fetch(form.action, options)
                .then({ response: dynamic ->
                    if (response.ok == true) {
                        if (successMessage != null && message != null) {
                            message.hidden = false
                            message.textContent = successMessage
                        }
                        submit?.disabled = false
                        historyStatusPoller?.refreshContext()
                    } else {
                        response.json().then({ payload: dynamic ->
                            message?.let {
                                it.hidden = false
                                it.textContent = historyStatusString(payload, "error") ?: HistoryRangeText.ACTION_FAILED
                            }
                            submit?.disabled = false
                        }).catch({ _: dynamic ->
                            message?.let {
                                it.hidden = false
                                it.textContent = HistoryRangeText.ACTION_FAILED
                            }
                            submit?.disabled = false
                        })
                    }
                })
                .catch({ _: dynamic ->
                    message?.let {
                        it.hidden = false
                        it.textContent = HistoryRangeText.ACTION_FAILED
                    }
                    submit?.disabled = false
                })
        })
    }

    bindForm(HtmlIds.HISTORY_SYNC_BUTTON, HistoryRangeIds.SYNC_ERROR, null)
    bindForm(
        HistoryRangeIds.REPAIR_FORM,
        HistoryRangeIds.REPAIR_ERROR,
        HistoryRangeText.REPAIR_ACCEPTED,
    )
}

internal fun historyRangeState(query: String, savedDefaultRange: String?, timezone: String): HistoryRangeState {
    val range = historyRangeForQuery(query, savedDefaultRange)
    return HistoryRangeState(
        preset = range,
        start = historyParam(query, "start"),
        end = historyParam(query, "end"),
        timezone = historyParam(query, "tz")?.takeIf(String::isNotBlank) ?: timezone,
        scope = historyParam(query, "scope")?.takeIf(String::isNotBlank),
    )
}

internal fun historyApiQuery(state: HistoryRangeState, chart: Boolean, pinnedRange: dynamic = null): String {
    val params: dynamic = js("new URLSearchParams()")
    params.set(HistoryRangeQuery.PARAM_PRESET, state.preset)
    params.set(HistoryRangeQuery.PARAM_TZ, state.timezone)
    if (state.preset == "custom") {
        params.set(HistoryRangeQuery.PARAM_START, state.start)
        params.set(HistoryRangeQuery.PARAM_END, state.end)
    }
    state.scope?.let { params.set(HistoryRangeQuery.PARAM_SCOPE, it) }
    if (chart) {
        params.set(HistoryRangeQuery.PARAM_SELECTION, "PORTFOLIO")
        params.set(HistoryRangeQuery.PARAM_MAX_POINTS, "2000")
    }
    if (state.preset != "custom" && pinnedRange != null && pinnedRange != undefined) {
        val from = pinnedRange.fromIso as? String
        val to = pinnedRange.toIso as? String
        if (!from.isNullOrBlank() && !to.isNullOrBlank()) {
            params.set(HistoryRangeQuery.PARAM_FROM, from)
            params.set(HistoryRangeQuery.PARAM_TO, to)
        }
    }
    return params.toString()
}

internal fun historyStatusUrl(query: String, savedDefaultRange: String?, timezone: String): String {
    val state = historyRangeState(query, savedDefaultRange, timezone)
    val params: dynamic = js("new URLSearchParams()")
    params.set(HistoryRangeQuery.PARAM_PRESET, state.preset)
    state.start?.let { params.set(HistoryRangeQuery.PARAM_START, it) }
    state.end?.let { params.set(HistoryRangeQuery.PARAM_END, it) }
    params.set(HistoryRangeQuery.PARAM_TZ, state.timezone)
    state.scope?.let { params.set(HistoryRangeQuery.PARAM_SCOPE, it) }
    return "${HistoryRangeQuery.API_DATA_STATUS}?$params"
}

private fun historyPageUrl(
    query: String,
    preset: String,
    start: String? = null,
    end: String? = null,
    timezone: String? = null,
): String {
    val params = historyParams(query)
    params.set("range", preset)
    listOf(
        HistoryRangeQuery.PARAM_TRADES_OFFSET,
        HistoryRangeQuery.PARAM_LEDGERS_OFFSET,
        HistoryRangeQuery.PARAM_ACTIVITY_CURSORS,
        HistoryRangeQuery.PARAM_FROM,
        HistoryRangeQuery.PARAM_TO,
    ).forEach { params.delete(it) }
    if (preset == "custom") {
        params.set("start", start)
        params.set("end", end)
        params.set("tz", timezone)
    } else {
        params.delete("start")
        params.delete("end")
    }
    val encoded = params.toString()
    return Routes.HISTORY + if (encoded.isEmpty()) "" else "?$encoded"
}

private fun fetchHistoryJson(url: String, onSuccess: (dynamic) -> Unit, onFailure: () -> Unit) {
    window.asDynamic().fetch(url)
        .then({ response: dynamic ->
            if (response.ok != true) throw IllegalStateException("History request failed")
            response.json()
        })
        .then({ response: dynamic -> onSuccess(response) })
        .catch({ _: dynamic -> onFailure() })
}

private fun fetchHistoryRequest(url: String, onSuccess: (dynamic) -> Unit, onFailure: () -> Unit): () -> Unit {
    val abortConstructor = window.asDynamic().AbortController
    val controller: dynamic = if (abortConstructor == null || abortConstructor == undefined) {
        null
    } else {
        js("Reflect.construct(abortConstructor, [])")
    }
    var active = true
    val options: dynamic = json("method" to "GET", "cache" to "no-store")
    if (controller != null) options.signal = controller.signal
    window.asDynamic().fetch(url, options)
        .then({ response: dynamic ->
            if (response.ok != true) throw IllegalStateException("History request failed")
            response.json()
        })
        .then({ response: dynamic -> if (active) onSuccess(response) })
        .catch({ _: dynamic -> if (active) onFailure() })
    return {
        active = false
        if (controller != null) controller.abort()
    }
}

internal data class HistoryRefreshBadge(val label: String, val className: String)

internal class HistoryDataStatusPoller(
    private val statusUrl: () -> String,
    private val request: (String, (dynamic) -> Unit, () -> Unit) -> (() -> Unit),
    private val onPending: () -> Unit,
    private val onResponse: (dynamic, dynamic, Boolean) -> Unit,
    private val onFailure: () -> Unit,
    private val onPaused: () -> Unit = {},
    private val abortOtherRequests: () -> Unit = {},
    private val scheduleInterval: ((() -> Unit, Int) -> dynamic) = { callback, milliseconds ->
        window.setInterval(callback, milliseconds)
    },
    private val clearScheduledInterval: (dynamic) -> Unit = { handle -> window.clearInterval(handle) },
    private val hidden: () -> Boolean = { document.asDynamic().hidden == true },
) {
    private var interval: dynamic = null
    private var cancelRequest: (() -> Unit)? = null
    private var requestVersion = 0
    private var disposed = false
    private var previousResponse: dynamic = null
    private var previousRevision: Double? = null
    private val disposers = mutableListOf<() -> Unit>()

    fun start() {
        if (!disposed && !hidden()) resume()
    }

    fun visibilityChanged() {
        if (disposed) return
        if (hidden()) pause() else resume()
    }

    fun refreshContext() {
        if (disposed || hidden()) return
        if (interval != null) {
            clearScheduledInterval(interval)
            interval = null
        }
        cancelActiveRequest()
        abortOtherRequests()
        resume()
    }

    fun addDisposer(disposer: () -> Unit) {
        if (disposed) disposer() else disposers += disposer
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        pause()
        disposers.toList().forEach { it() }
        disposers.clear()
    }

    private fun resume() {
        if (disposed || hidden() || interval != null) return
        pollNow()
        if (!disposed && !hidden()) {
            interval = scheduleInterval({
                if (hidden()) pause() else pollNow()
            }, HistoryRangeProps.POLL_INTERVAL_MS)
        }
    }

    private fun pause() {
        if (interval != null) {
            clearScheduledInterval(interval)
            interval = null
        }
        cancelActiveRequest()
        abortOtherRequests()
        onPaused()
    }

    private fun pollNow() {
        if (disposed || hidden() || cancelRequest != null) return
        onPending()
        val version = ++requestVersion
        val requestedUrl = statusUrl()
        var completed = false
        val cancel = request(
            requestedUrl,
            { response ->
                completed = true
                if (!isCurrent(version, requestedUrl)) return@request
                cancelRequest = null
                val revision = (response.dataRevision as? Number)?.toDouble()
                val changed = revision != null && previousRevision != null && revision > previousRevision!!
                if (revision != null && (previousRevision == null || revision > previousRevision!!)) {
                    previousRevision = revision
                }
                val previous = previousResponse
                previousResponse = response
                onResponse(response, previous, changed)
            },
            {
                completed = true
                if (!isCurrent(version, requestedUrl)) return@request
                cancelRequest = null
                onFailure()
            },
        )
        if (!completed && isCurrent(version, requestedUrl)) cancelRequest = cancel
    }

    private fun isCurrent(version: Int, requestedUrl: String): Boolean =
        !disposed && version == requestVersion && !hidden() && statusUrl() == requestedUrl

    private fun cancelActiveRequest() {
        requestVersion++
        cancelRequest?.invoke()
        cancelRequest = null
    }
}

internal fun disposeHistoryAutoRefresh() {
    historyStatusPoller?.dispose()
    historyStatusPoller = null
    historyLatestDataHandler = null
    historyRefreshReinitializer = null
}

private fun observeHistoryRefreshLifecycle(poller: HistoryDataStatusPoller) {
    val visibilityChange: (Event) -> Unit = { poller.visibilityChanged() }
    val pageHide: (Event) -> Unit = {
        if (historyStatusPoller === poller) {
            historyStatusPoller = null
            historyLatestDataHandler = null
        }
        poller.dispose()
    }
    val beforeSwap: (Event) -> Unit = { event ->
        val root = document.getElementById(HistoryRangeIds.REFRESH_STATUS)
        val target = event.asDynamic().detail?.target
        if (root != null && target != null && (target == root || target.contains(root) == true)) {
            if (historyStatusPoller === poller) {
                historyStatusPoller = null
                historyLatestDataHandler = null
            }
            poller.dispose()
        }
    }
    document.addEventListener(HistoryRangeEvents.VISIBILITY_CHANGE, visibilityChange)
    window.addEventListener(HistoryRangeEvents.PAGE_HIDE, pageHide)
    document.addEventListener(HistoryRangeEvents.BEFORE_SWAP, beforeSwap)
    poller.addDisposer { document.removeEventListener(HistoryRangeEvents.VISIBILITY_CHANGE, visibilityChange) }
    poller.addDisposer { window.removeEventListener(HistoryRangeEvents.PAGE_HIDE, pageHide) }
    poller.addDisposer { document.removeEventListener(HistoryRangeEvents.BEFORE_SWAP, beforeSwap) }
    if (!historyAfterSwapListenerInstalled) {
        historyAfterSwapListenerInstalled = true
        document.addEventListener(HistoryRangeEvents.AFTER_SWAP, {
            if (historyStatusPoller == null && document.getElementById(HistoryRangeIds.REFRESH_STATUS) != null) {
                initHistoryViews()
                historyRefreshReinitializer?.invoke()
            }
        })
    }
}

private fun historyArrayItems(value: dynamic): List<dynamic> {
    val length = (value?.length as? Number)?.toInt() ?: return emptyList()
    return (0 until length).map { value[it] }
}

private fun historyStatusString(value: dynamic, key: String): String? =
    if (value == null || value == undefined) null else (value[key] as? String)?.takeIf(String::isNotBlank)

private fun historyStatusMatchesFeed(value: String?, feed: String): Boolean {
    val normalized = value?.uppercase().orEmpty()
    return if (feed == "TRADES") "TRADE" in normalized else "LEDGER" in normalized
}

private fun historyStatusActive(state: String?): Boolean =
    state?.uppercase() in setOf("RUNNING", "PENDING", "QUEUED", "IN_PROGRESS")

internal fun historyRefreshBadge(
    response: dynamic,
    pending: Boolean = false,
    failed: Boolean = false,
): HistoryRefreshBadge {
    val blocked = historyStatusString(response, "blockedReason")
    val jobs = historyArrayItems(response?.jobs)
    val progress = historyArrayItems(response?.progress)
    val retryScheduled = progress.any { historyStatusString(it, "nextRetryAtIso") != null } ||
        jobs.any { historyStatusString(it, "nextRetryAtIso") != null }
    val errors = progress.any { historyStatusString(it, "lastError") != null } ||
        jobs.any { historyStatusString(it, "lastError") != null }
    val backfillActive = progress.any {
        historyStatusActive(historyStatusString(it, "backfillState"))
    } || jobs.any {
        historyStatusString(it, "purpose")?.contains("BACKFILL", ignoreCase = true) == true &&
            historyStatusActive(historyStatusString(it, "state"))
    }
    val active = jobs.any { historyStatusActive(historyStatusString(it, "state")) }
    val recentComplete = listOf("TRADES", "LEDGERS").all { feed ->
        progress.any {
            historyStatusMatchesFeed(historyStatusString(it, "feed"), feed) &&
                historyStatusString(it, "recentCompletedThroughIso") != null &&
                historyStatusString(it, "recentLastSuccessAtIso") != null
        }
    }
    return when {
        blocked != null -> HistoryRefreshBadge(HistoryRangeText.STATE_ACTION_NEEDED, HistoryRangeCss.CHIP_ERROR)
        failed || retryScheduled -> HistoryRefreshBadge(HistoryRangeText.STATE_DELAYED, HistoryRangeCss.CHIP_WARN)
        errors -> HistoryRefreshBadge(HistoryRangeText.STATE_ACTION_NEEDED, HistoryRangeCss.CHIP_ERROR)
        backfillActive -> HistoryRefreshBadge(HistoryRangeText.STATE_BACKFILLING, HistoryRangeCss.CHIP_BUSY)
        pending || active -> HistoryRefreshBadge(HistoryRangeText.STATE_UPDATING, HistoryRangeCss.CHIP_BUSY)
        recentComplete -> HistoryRefreshBadge(HistoryRangeText.STATE_UP_TO_DATE, HistoryRangeCss.CHIP_OK)
        else -> HistoryRefreshBadge(HistoryRangeText.STATE_UPDATING, HistoryRangeCss.CHIP_BUSY)
    }
}

internal data class HistoryRefreshTargets(
    val charts: Boolean,
    val summary: Boolean,
    val trades: Boolean,
    val ledgers: Boolean,
    val activity: Boolean,
)

private fun historyStatusScalar(value: dynamic, key: String): String {
    if (value == null || value == undefined) return ""
    val item = value[key]
    return if (item == null || item == undefined) "" else item.toString()
}

private fun historyStatusFeedSignature(response: dynamic, feed: String): String {
    val progressFields = listOf(
        "recentCompletedThroughIso",
        "recentLastSuccessAtIso",
        "backfillState",
        "backfillCompletedThroughIso",
        "backfillCursor",
        "repairLastRunAtIso",
        "lastError",
        "nextRetryAtIso",
    )
    val jobFields = listOf(
        "purpose",
        "state",
        "windowStartIso",
        "windowEndIso",
        "cursor",
        "completedThroughIso",
        "lastError",
        "nextRetryAtIso",
        "lastSuccessAtIso",
        "updatedAtIso",
    )
    val progress = historyArrayItems(response?.progress)
        .filter { historyStatusMatchesFeed(historyStatusString(it, "feed"), feed) }
        .map { item -> progressFields.joinToString("|") { historyStatusScalar(item, it) } }
        .sorted()
    val jobs = historyArrayItems(response?.jobs)
        .filter { historyStatusMatchesFeed(historyStatusString(it, "feed"), feed) }
        .map { item -> jobFields.joinToString("|") { historyStatusScalar(item, it) } }
        .sorted()
    return (progress + jobs).joinToString("\n")
}

private fun historyStatusCoverageSignature(response: dynamic, feed: String): String {
    val fields = when (feed) {
        "VALUES" -> listOf("valuesFirstIso", "valuesLastIso", "gapCount", "incomplete")
        "TRADES" -> listOf("tradesFirstIso", "tradesLastIso")
        else -> listOf("ledgersFirstIso", "ledgersLastIso")
    }
    return fields.joinToString("|") { historyStatusScalar(response?.coverage, it) }
}

internal fun historyRefreshTargets(previous: dynamic, current: dynamic): HistoryRefreshTargets {
    val tradesCoverageChanged =
        historyStatusCoverageSignature(previous, "TRADES") != historyStatusCoverageSignature(current, "TRADES")
    val ledgersCoverageChanged =
        historyStatusCoverageSignature(previous, "LEDGERS") != historyStatusCoverageSignature(current, "LEDGERS")
    val valuesChanged = historyStatusCoverageSignature(previous, "VALUES") !=
        historyStatusCoverageSignature(current, "VALUES")
    val tradesChanged = tradesCoverageChanged ||
        historyStatusFeedSignature(previous, "TRADES") != historyStatusFeedSignature(current, "TRADES")
    val ledgersChanged = ledgersCoverageChanged ||
        historyStatusFeedSignature(previous, "LEDGERS") != historyStatusFeedSignature(current, "LEDGERS")
    val noFeedEvidence = !valuesChanged && !tradesChanged && !ledgersChanged
    return HistoryRefreshTargets(
        charts = valuesChanged || ledgersChanged || noFeedEvidence,
        summary = valuesChanged || tradesChanged || ledgersChanged || noFeedEvidence,
        trades = tradesChanged,
        ledgers = ledgersChanged,
        activity = historyStatusScalar(previous, "dataRevision") !=
            historyStatusScalar(current, "dataRevision"),
    )
}

private fun historyStatusTime(raw: dynamic): String {
    val text = raw as? String ?: return "-"
    return historyIsoTime(raw)?.let { Date(it).toLocaleString() } ?: text
}

private fun historyStatusFirstValue(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }

private fun historyStatusFeedText(response: dynamic, feed: String): List<String> {
    val progress = historyArrayItems(response?.progress)
        .firstOrNull { historyStatusMatchesFeed(historyStatusString(it, "feed"), feed) }
    val jobs = historyArrayItems(response?.jobs)
        .filter { historyStatusMatchesFeed(historyStatusString(it, "feed"), feed) }
    val latestJob = jobs.maxByOrNull { historyStatusString(it, "updatedAtIso").orEmpty() }
    val recentJob = jobs.firstOrNull {
        historyStatusString(it, "purpose")?.contains("RECENT", ignoreCase = true) == true ||
            historyStatusString(it, "purpose")?.contains("SYNC", ignoreCase = true) == true
    } ?: latestJob
    val coverage = response?.coverage
    val range = response?.range
    val timezone = historyStatusString(range, "tz") ?: historyBrowserTimezone()
    val prefix = feed.lowercase()
    val first = historyStatusString(coverage, "${prefix}FirstIso")
    val last = historyStatusString(coverage, "${prefix}LastIso")
    val recentThrough = historyStatusFirstValue(
        historyStatusString(progress, "recentCompletedThroughIso"),
        historyStatusString(recentJob, "completedThroughIso"),
    )
    val recentSuccess = historyStatusFirstValue(
        historyStatusString(progress, "recentLastSuccessAtIso"),
        historyStatusString(recentJob, "lastSuccessAtIso"),
    )
    val recentState = historyStatusString(recentJob, "state")
    val recentWindow = listOfNotNull(
        historyStatusString(recentJob, "windowStartIso")?.let(::historyStatusTime),
        historyStatusString(recentJob, "windowEndIso")?.let(::historyStatusTime),
    ).takeIf { it.isNotEmpty() }?.joinToString(" – ")
    val recentCursor = historyStatusString(recentJob, "cursor")
    val backfillState = historyStatusString(progress, "backfillState") ?: "not started"
    val backfillThrough = historyStatusString(progress, "backfillCompletedThroughIso")
    val cursor = historyStatusString(progress, "backfillCursor")
    val error = historyStatusFirstValue(
        historyStatusString(progress, "lastError"),
        jobs.firstNotNullOfOrNull { historyStatusString(it, "lastError") },
    )
    val retry = historyStatusFirstValue(
        historyStatusString(progress, "nextRetryAtIso"),
        jobs.firstNotNullOfOrNull { historyStatusString(it, "nextRetryAtIso") },
    )
    val repair = historyStatusString(progress, "repairLastRunAtIso")
    return listOf(
        listOfNotNull(
            if (first != null && last != null) historyCoverageWindow(first, last, timezone) else null,
            recentState,
            recentWindow,
            recentThrough?.let { "through ${historyStatusTime(it)}" },
            recentSuccess?.let { "success ${historyStatusTime(it)}" },
            recentCursor?.let { "cursor $it" },
        ).joinToString(" · ").ifEmpty { "No recent completion recorded" },
        listOfNotNull(
            backfillState,
            backfillThrough?.let { "through ${historyStatusTime(it)}" },
            cursor?.let { "cursor $it" },
            repair?.let { "repair ${historyStatusTime(it)}" },
        ).joinToString(" · "),
        error.orEmpty(),
        retry?.let(::historyStatusTime).orEmpty(),
    )
}

internal fun updateHistoryRefreshStatus(
    response: dynamic,
    updatedAt: Double?,
    nextRefreshAt: Double?,
    pending: Boolean = false,
    failed: Boolean = false,
) {
    val badge = historyRefreshBadge(response, pending, failed)
    document.getElementById(HistoryRangeIds.REFRESH_CHIP)?.let { chip ->
        chip.textContent = badge.label
        chip.setAttribute("class", "${HistoryRangeCss.CHIP} ${badge.className}")
    }
    document.getElementById(HistoryRangeIds.REFRESH_UPDATED)?.textContent =
        "${HistoryRangeText.UPDATED} ${updatedAt?.let { Date(it).toLocaleString() } ?: "-"}"
    document.getElementById(HistoryRangeIds.REFRESH_NEXT)?.textContent =
        "${HistoryRangeText.NEXT_REFRESH} ${nextRefreshAt?.let { Date(it).toLocaleString() } ?: "-"}"
    val error = historyStatusString(response, "blockedReason") ?: if (failed) {
        HistoryRangeText.STATUS_REQUEST_FAILED
    } else {
        historyArrayItems(response?.progress).firstNotNullOfOrNull { historyStatusString(it, "lastError") }
            ?: historyArrayItems(response?.jobs).firstNotNullOfOrNull { historyStatusString(it, "lastError") }
    }
    document.getElementById(HistoryRangeIds.REFRESH_ERROR)?.let { message ->
        (message as HTMLElement).hidden = error == null
        message.textContent = error.orEmpty()
    }
    val details = document.getElementById(HistoryRangeIds.REFRESH_FEED_PROGRESS) ?: return
    listOf("TRADES", "LEDGERS").forEach { feed ->
        val row = details.querySelector("[data-history-feed='$feed']") ?: return@forEach
        val values = historyStatusFeedText(response, feed)
        row.querySelector("[data-history-refresh-recent]")?.textContent = values[0]
        row.querySelector("[data-history-refresh-backfill]")?.textContent = values[1]
        row.querySelector("[data-history-refresh-failure]")?.textContent = values[2].ifEmpty { "-" }
        row.querySelector("[data-history-refresh-retry]")?.textContent = values[3].ifEmpty { "-" }
    }
}

private fun historyTableApiUrl(table: String, query: String, range: dynamic, offset: Int, limit: Int): String? {
    val from = historyStatusString(range, "fromIso")
    val until = historyStatusString(range, "toIso")
    if (from == null && until == null) return null
    val params: dynamic = js("new URLSearchParams()")
    from?.let { params.set(HistoryRangeQuery.PARAM_FROM, it) }
    until?.let { params.set(HistoryRangeQuery.PARAM_UNTIL, it) }
    listOf(HistoryRangeQuery.PARAM_ASSET, HistoryRangeQuery.PARAM_PAIR, HistoryRangeQuery.PARAM_TYPE)
        .forEach { key -> historyParam(query, key)?.let { params.set(key, it) } }
    params.set(HistoryRangeQuery.PARAM_LIMIT, limit.toString())
    params.set(HistoryRangeQuery.PARAM_OFFSET, offset.toString())
    val route = if (table == "trades") Routes.API_HISTORY_TRADES else Routes.API_HISTORY_LEDGERS
    return "$route?$params"
}

private fun historyRecordDate(item: dynamic): String {
    val raw = historyStatusFirstValue(
        historyStatusString(item, "time"),
        historyStatusString(item, "rawTime"),
    ) ?: return "-"
    return historyIsoTime(raw)?.let { historyDateLabel(it).substringBefore('.') } ?: raw
}

private fun historyRecordCell(row: Element, text: String, className: String? = null, title: String? = null) {
    val cell = document.createElement("td")
    if (!className.isNullOrBlank()) cell.setAttribute("class", className)
    if (!title.isNullOrBlank()) cell.setAttribute("title", title)
    cell.textContent = text
    row.appendChild(cell)
}

private fun historyRecordValue(item: dynamic, key: String): String = historyStatusString(item, key).orEmpty()

private fun historyOriginalValue(item: dynamic, amountKey: String, currency: String?): String =
    "${historyStatusString(item, amountKey)?.takeIf(String::isNotBlank) ?: ViewText.EM_DASH} " +
        (currency?.takeIf(String::isNotBlank) ?: ViewText.HISTORY_UNKNOWN_CURRENCY)

private fun historyAppendDetails(cell: Element, summaryText: String, fields: List<Pair<String, String>>) {
    val details = document.createElement("details")
    val summary = document.createElement("summary")
    summary.textContent = summaryText
    details.appendChild(summary)
    val list = document.createElement("dl")
    fields.forEach { (label, value) ->
        val term = document.createElement("dt")
        term.textContent = label
        list.appendChild(term)
        val description = document.createElement("dd")
        description.textContent = value.ifBlank { ViewText.EM_DASH }
        list.appendChild(description)
    }
    details.appendChild(list)
    cell.appendChild(details)
}

private fun historyRecordStatus(item: dynamic): String =
    if (item?.isValidRecord == true) HistoryRangeText.RECORD_VALID else HistoryRangeText.RECORD_INCOMPLETE

private fun historyStringList(value: dynamic): List<String> = historyArrayItems(value).mapNotNull { it as? String }

private fun historyAppendTradeRow(body: Element, item: dynamic) {
    val row = document.createElement("tr")
    row.setAttribute("class", CssClass.Table.Hoverable.value)
    val time = historyStatusFirstValue(historyStatusString(item, "time"), historyStatusString(item, "rawTime"))
    val timeText = historyRecordDate(item)
    val timeCell = document.createElement("td")
    timeCell.setAttribute("class", CssClass.Table.MonoCol.value)
    time?.let { timeCell.setAttribute("title", it) }
    val quote = historyRecordValue(item, "quoteCurrency")
    val feeCurrency = historyStatusFirstValue(historyRecordValue(item, "feeCurrency"), quote)
    val ledgerIds = historyStringList(item?.ledgerIds).joinToString().ifEmpty { ViewText.EM_DASH }
    historyAppendDetails(
        timeCell,
        timeText,
        listOf(
            HistoryRangeText.DETAILS_TIMESTAMP to timeText,
            HistoryRangeText.DETAILS_UNITS to historyOriginalValue(
                item,
                "volume",
                historyRecordValue(item, "baseAsset"),
            ),
            HistoryRangeText.DETAILS_PRICE to historyOriginalValue(item, "price", quote),
            HistoryRangeText.DETAILS_COST to historyOriginalValue(item, "cost", quote),
            HistoryRangeText.DETAILS_FEE to historyOriginalValue(item, "fee", feeCurrency),
            HistoryRangeText.DETAILS_STATUS to historyRecordStatus(item),
            HistoryRangeText.DETAILS_EXCHANGE_TRADE_ID to historyRecordValue(item, "tradeId"),
            HistoryRangeText.DETAILS_EXCHANGE_ORDER_ID to historyRecordValue(item, "orderTxid"),
            HistoryRangeText.DETAILS_POST_ID to historyRecordValue(item, "postTxid"),
            HistoryRangeText.DETAILS_LEDGER_ID to ledgerIds,
            HistoryRangeText.DETAILS_BOT_LINK to HistoryRangeText.UNKNOWN_LINKAGE,
        ),
    )
    row.appendChild(timeCell)
    historyRecordCell(row, historyRecordValue(item, "pair"), CssClass.Table.SymbolCol.value)
    historyRecordCell(row, historyRecordValue(item, "type").replaceFirstChar { it.uppercase() })
    historyRecordCell(row, historyRecordValue(item, "volume"), CssClass.Table.MonoCol.value)
    historyRecordCell(row, historyRecordValue(item, "price"), CssClass.Table.MonoCol.value)
    historyRecordCell(row, historyRecordValue(item, "cost"), CssClass.Table.MonoCol.value)
    val fee = historyRecordValue(item, "fee")
    val feeDisplay = listOfNotNull(fee.takeIf(String::isNotBlank), feeCurrency?.takeIf(String::isNotBlank))
        .joinToString(" ")
    historyRecordCell(row, feeDisplay)
    body.appendChild(row)
}

private fun historyAppendLedgerRow(body: Element, item: dynamic) {
    val row = document.createElement("tr")
    row.setAttribute("class", CssClass.Table.Hoverable.value)
    val time = historyStatusFirstValue(historyStatusString(item, "time"), historyStatusString(item, "rawTime"))
    val timeText = historyRecordDate(item)
    val timeCell = document.createElement("td")
    timeCell.setAttribute("class", CssClass.Table.MonoCol.value)
    time?.let { timeCell.setAttribute("title", it) }
    val asset = historyRecordValue(item, "asset")
    historyAppendDetails(
        timeCell,
        timeText,
        listOf(
            HistoryRangeText.DETAILS_TIMESTAMP to timeText,
            HistoryRangeText.DETAILS_UNITS to historyOriginalValue(item, "amount", asset),
            HistoryRangeText.DETAILS_FEE to historyOriginalValue(item, "fee", asset),
            HistoryRangeText.DETAILS_BALANCE to historyOriginalValue(item, "balance", asset),
            HistoryRangeText.DETAILS_STATUS to historyRecordStatus(item),
            HistoryRangeText.DETAILS_LEDGER_ID to historyRecordValue(item, "ledgerId"),
            HistoryRangeText.DETAILS_REFERENCE_ID to historyRecordValue(item, "refid"),
            HistoryRangeText.DETAILS_BOT_LINK to HistoryRangeText.UNKNOWN_LINKAGE,
        ),
    )
    row.appendChild(timeCell)
    val type = historyRecordValue(item, "type").replaceFirstChar { it.uppercase() }
    val subtype = historyRecordValue(item, "subtype")
    val showSubtype = subtype.isNotBlank() && !subtype.equals("tradespot", ignoreCase = true) &&
        !subtype.equals(type, ignoreCase = true)
    historyRecordCell(row, if (showSubtype) "$type ($subtype)" else type)
    historyRecordCell(row, historyRecordValue(item, "asset"), CssClass.Table.SymbolCol.value)
    historyRecordCell(row, historyRecordValue(item, "amount"), CssClass.Table.MonoCol.value)
    val fee = historyRecordValue(item, "fee")
    val feeCurrency = historyRecordValue(item, "feeCurrency")
    historyRecordCell(
        row,
        listOf(fee, feeCurrency).filter(String::isNotBlank).joinToString(" "),
        CssClass.Table.MonoCol.value,
    )
    historyRecordCell(row, historyRecordValue(item, "balance"), CssClass.Table.MonoCol.value)
    body.appendChild(row)
}

private fun historyAppendActivityRow(body: Element, item: dynamic) {
    val row = document.createElement("tr")
    row.setAttribute("class", CssClass.Table.Hoverable.value)
    val occurredAt = historyStatusString(item, "occurredAt") ?: ""
    val epoch = historyIsoTime(occurredAt)
    historyRecordCell(
        row,
        epoch?.let { historyDateLabel(it).substringBefore('.') } ?: occurredAt.ifBlank { ViewText.EM_DASH },
        CssClass.Table.MonoCol.value,
        occurredAt,
    )
    historyRecordCell(row, historyRecordValue(item, "outcome"))
    historyRecordCell(row, historyRecordValue(item, "mode"))
    historyRecordCell(row, historyRecordValue(item, "cycleId"), CssClass.Table.MonoCol.value)
    val actions = historyStringList(item?.actions)
    val cell = document.createElement("td")
    val details = document.createElement("details")
    val summary = document.createElement("summary")
    summary.textContent = HistoryRangeText.VIEW_ACTIONS
    details.appendChild(summary)
    val list = document.createElement("ul")
    actions.forEach { action ->
        val itemElement = document.createElement("li")
        itemElement.textContent = action
        list.appendChild(itemElement)
    }
    details.appendChild(list)
    cell.appendChild(details)
    row.appendChild(cell)
    body.appendChild(row)
}

internal fun updateHistoryActivities(response: dynamic, query: String) {
    val body = document.getElementById(HistoryRangeIds.ACTIVITY_BODY) ?: return
    val items = historyArrayItems(response?.items).take(HistoryRangeProps.RECORD_PAGE_SIZE)
    body.innerHTML = ""
    if (items.isEmpty()) {
        val row = document.createElement("tr")
        val cell = document.createElement("td")
        cell.setAttribute("class", CssClass.History.EmptyTableCell.value)
        cell.setAttribute("colspan", "5")
        cell.textContent = HistoryRangeText.ACTIVITY_EMPTY
        row.appendChild(cell)
        body.appendChild(row)
    } else {
        items.forEach { historyAppendActivityRow(body, it) }
    }
    val stack = historyActivityCursorStack(query)
    val first = stack.size * HistoryRangeProps.RECORD_PAGE_SIZE + 1
    document.getElementById(HistoryRangeIds.ACTIVITY_RANGE)?.textContent =
        if (items.isEmpty()) "0–0" else "$first–${first + items.size - 1}"
    val previous = document.getElementById(HistoryRangeIds.ACTIVITY_PREVIOUS) as? HTMLElement
    previous?.let {
        it.hidden = stack.isEmpty()
        val previousQuery = historyActivityPreviousPageQuery(query)
        if (previousQuery != null) {
            it.setAttribute("href", "${Routes.HISTORY}?$previousQuery#${HistoryRangeIds.PANEL_ACTIVITY}")
        }
    }
    val next = document.getElementById(HistoryRangeIds.ACTIVITY_NEXT) as? HTMLElement
    val nextQuery = historyActivityNextPageQuery(query, historyStatusString(response, "nextCursor"))
    next?.let {
        it.hidden = response?.hasMore != true || nextQuery == null
        if (nextQuery != null) it.setAttribute("href", "${Routes.HISTORY}?$nextQuery#${HistoryRangeIds.PANEL_ACTIVITY}")
    }
}

private fun historyPaginationHref(query: String, table: String, offset: Int): String {
    val params = historyParams(query)
    val key = if (table == "trades") {
        HistoryRangeQuery.PARAM_TRADES_OFFSET
    } else {
        HistoryRangeQuery.PARAM_LEDGERS_OFFSET
    }
    if (offset <= 0) params.delete(key) else params.set(key, offset.toString())
    val section = if (table == "trades") HtmlIds.HISTORY_TRADES_SECTION else HtmlIds.HISTORY_LEDGERS_SECTION
    return "${Routes.HISTORY}?$params#$section"
}

internal fun updateHistoryTable(table: String, response: dynamic, query: String, limit: Int) {
    val trades = table == "trades"
    val tableName = if (trades) "trades" else "ledgers"
    val bodyId = if (trades) HtmlIds.HISTORY_TRADES_BODY else HtmlIds.HISTORY_LEDGERS_BODY
    val body = document.getElementById(bodyId) ?: return
    val offsetKey = if (trades) HistoryRangeQuery.PARAM_TRADES_OFFSET else HistoryRangeQuery.PARAM_LEDGERS_OFFSET
    val offset = historyParam(query, offsetKey)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val items = historyArrayItems(response?.items)
    val wrapper = body.closest(".${CssClass.Table.Wrapper.value}") as? HTMLElement
    val wrapperScrollTop = wrapper?.scrollTop ?: 0
    val pageScrollY = (window.asDynamic().scrollY as? Number)?.toDouble()
    body.innerHTML = ""
    if (items.isEmpty()) {
        val row = document.createElement("tr")
        val cell = document.createElement("td")
        cell.setAttribute("class", CssClass.History.EmptyTableCell.value)
        cell.setAttribute("colspan", if (trades) "7" else "6")
        cell.textContent = ViewText.HISTORY_EVENTS_EMPTY
        row.appendChild(cell)
        body.appendChild(row)
    } else {
        items.forEach { if (trades) historyAppendTradeRow(body, it) else historyAppendLedgerRow(body, it) }
    }
    if (wrapper != null) wrapper.scrollTop = wrapperScrollTop.toDouble()
    if (pageScrollY != null) window.scrollTo(0.0, pageScrollY)

    val total = (response?.total as? Number)?.toLong()
    document.querySelector("[data-history-total='$tableName']")?.textContent = total?.toString().orEmpty()
    val shown = if (items.isEmpty()) "0–0" else "${offset + 1}–${offset + items.size}"
    document.querySelector("[data-history-page-range='$tableName']")?.textContent =
        "$shown${total?.let { " of $it" }.orEmpty()}"
    val pagination = document.querySelector("[data-history-pagination='$tableName']") ?: return
    val nextId = if (trades) HtmlIds.HISTORY_TRADES_NEXT else HtmlIds.HISTORY_LEDGERS_NEXT
    val next = document.getElementById(nextId)
    val hasMore = response?.hasMore == true
    if (hasMore) {
        val link = next ?: document.createElement("a").also {
            it.setAttribute("id", nextId)
            it.setAttribute("class", CssClass.History.PaginationBtn.value)
            it.textContent = ViewText.HISTORY_NEXT
            pagination.appendChild(it)
        }
        link.setAttribute("href", historyPaginationHref(query, tableName, offset + limit))
    } else {
        next?.remove()
    }
}

internal fun setHistoryNewRecordsNotice(visible: Boolean) {
    (document.getElementById(HistoryRangeIds.NEW_RECORDS_NOTICE) as? HTMLElement)?.hidden = !visible
}

private fun historyIsoTime(raw: dynamic): Double? {
    val iso = raw as? String ?: return null
    val parsed: dynamic = js("Date.parse(iso)")
    return (parsed as? Number)?.toDouble()?.takeIf(Double::isFinite)
}

private fun historyPortfolioPoints(response: dynamic): List<HistoryChartPoint> {
    val rawSeries = response.series
    val count = (rawSeries?.length as? Number)?.toInt() ?: return emptyList()
    val portfolio = (0 until count).map { rawSeries[it] }.firstOrNull { it.key == "portfolio" } ?: return emptyList()
    val rawPoints = portfolio.points
    val pointCount = (rawPoints?.length as? Number)?.toInt() ?: return emptyList()
    return (0 until pointCount).mapNotNull { index ->
        val point = rawPoints[index]
        val time = (point.t as? Number)?.toDouble() ?: historyIsoTime(point.tIso) ?: return@mapNotNull null
        val value = (point.v as? Number)?.toDouble()?.takeIf { point.complete == true && it.isFinite() }
        HistoryChartPoint(time, value)
    }.sortedBy { it.time }
}

private fun historyChartRange(response: dynamic, points: List<HistoryChartPoint>): ChartRange {
    val selectedMax = historyIsoTime(response.fullRange.toIso) ?: points.lastOrNull()?.time ?: Date.now()
    val max = if (response.range.preset == "custom") selectedMax - 1.0 else selectedMax
    val min = historyIsoTime(response.fullRange.fromIso) ?: points.firstOrNull()?.time
        ?: historyIsoTime(response.range.fromIso) ?: max - 86_400_000.0
    return ChartRange(min, if (max > min) max else min + 1.0)
}

private fun historySvgEscape(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

private fun historyPortfolioSvg(
    points: List<HistoryChartPoint>,
    range: ChartRange,
    label: String,
    money: Boolean,
    hidden: Boolean,
): String {
    val left = 120.0
    val right = 928.0
    val top = 18.0
    val bottom = 196.0
    val values = points.mapNotNull { it.value }
    val low = values.minOrNull() ?: 0.0
    val high = values.maxOrNull() ?: 1.0
    val valueSpan = (high - low).takeIf { it > 0.0 } ?: 1.0
    val timeSpan = range.span.takeIf { it > 0.0 } ?: 1.0
    fun x(time: Double) = left + ((time - range.min) / timeSpan).coerceIn(0.0, 1.0) * (right - left)
    fun y(value: Double) = bottom - ((value - low) / valueSpan) * (bottom - top)
    val complete = points.filter { it.value != null }
    val visibleStep = maxOf(1, (complete.size + 15) / 16)
    val grid = (0..4).joinToString("") { index ->
        val value = high - valueSpan * index / 4.0
        val y = top + (bottom - top) * index / 4.0
        "<line class=\"${CssClass.History.GridLine.value}\" x1=\"$left\" x2=\"$right\" y1=\"$y\" y2=\"$y\"/>" +
            "<text class=\"${CssClass.History.AxisLabel.value}\" x=\"112\" y=\"${y + 4}\" text-anchor=\"end\">" +
            historySvgEscape(historyNumber(value, money)) + "</text>"
    }
    val segments = mutableListOf<List<HistoryChartPoint>>()
    var segment = mutableListOf<HistoryChartPoint>()
    points.forEach { point ->
        if (point.value == null) {
            if (segment.size > 1) segments += segment
            segment = mutableListOf()
        } else {
            segment += point
        }
    }
    if (segment.size > 1) segments += segment
    val lines = segments.joinToString("") { values ->
        val linePoints = values.joinToString(" ") { "${x(it.time)},${y(it.value!!)}" }
        "<polyline class=\"${CssClass.History.Line.value}\" points=\"$linePoints\"/>"
    }
    val markers = points.mapIndexedNotNull { index, point ->
        if (point.value == null) {
            "<circle class=\"${CssClass.History.GapMarker.value}\" data-time=\"${point.time}\" data-gap=\"true\" " +
                "data-series=\"portfolio\" cx=\"${x(point.time)}\" cy=\"$bottom\" r=\"3\"/>"
        } else {
            val visible = index % visibleStep == 0 || index == points.lastIndex
            "<circle class=\"${CssClass.History.Point.value}\" data-time=\"${point.time}\" " +
                "data-value=\"${point.value}\" data-series=\"portfolio\" cx=\"${x(point.time)}\" " +
                "cy=\"${y(point.value)}\" r=\"${if (visible) 3 else 0}\"><title>" +
                "${historySvgEscape(
                    historyDateLabel(point.time),
                )} · ${historySvgEscape(historyNumber(point.value, money))}" +
                "</title></circle>"
        }
    }.joinToString("")
    val startLabel = historySvgEscape(historyAxisTimeLabel(range.min, false))
    val endLabel = historySvgEscape(historyAxisTimeLabel(range.max, false))
    val display = if (hidden) " hidden style=\"display:none\"" else ""
    val emptyMessage = if (complete.isEmpty()) {
        "<text class=\"${CssClass.History.AxisLabel.value}\" x=\"524\" y=\"108\" text-anchor=\"middle\">" +
            "No portfolio values for this period</text>"
    } else {
        "$lines$markers"
    }
    return "<svg class=\"${CssClass.History.ChartSvg.value}\" viewBox=\"0 0 960 240\" " +
        "preserveAspectRatio=\"none\"$display " +
        "data-full-from=\"${range.min}\" data-full-to=\"${range.max}\">" +
        "<title>${historySvgEscape(label)}</title>$grid$emptyMessage" +
        "<text class=\"${CssClass.History.AxisLabel.value}\" x=\"$left\" y=\"222\">$startLabel</text>" +
        "<text class=\"${CssClass.History.AxisLabel.value}\" x=\"$right\" y=\"222\" " +
        "text-anchor=\"end\">$endLabel</text></svg>"
}

private fun updatedHistoryViewport(previous: ChartViewport, full: ChartRange): ChartViewport {
    if (!previous.isZoomed || full.span <= 0.0) return ChartViewport(full, full)
    val span = previous.visible.span.coerceAtMost(full.span)
    if (span <= 0.0) return ChartViewport(full, full)
    val min = previous.visible.min.coerceIn(full.min, full.max - span)
    return ChartViewport(full, ChartRange(min, min + span))
}

private fun updateHistoryPortfolioChart(response: dynamic, preserveViewport: Boolean = false) {
    val points = historyPortfolioPoints(response)
    val range = historyChartRange(response, points)
    val rawSeries = response.series
    val count = (rawSeries?.length as? Number)?.toInt() ?: 0
    val portfolio = (0 until count).map { rawSeries[it] }.firstOrNull { it.key == "portfolio" }
    val label = portfolio?.label as? String ?: "Portfolio value"
    val unit = portfolio?.unit as? String ?: "USD"
    val container = document.getElementById(HtmlIds.HISTORY_VALUE_CHART) ?: return
    val session = historyChartSessions[container.id]
    val wasZoomed = preserveViewport && session?.viewport?.isZoomed == true
    val svg = historyPortfolioSvg(
        points,
        range,
        label,
        money = unit.equals("USD", ignoreCase = true),
        hidden =
        session != null,
    )
    val oldSvg = container.querySelector("svg")
    if (oldSvg == null) {
        container.innerHTML = svg
    } else {
        oldSvg.asDynamic().outerHTML = svg
    }
    val fullFrom = response.fullRange.fromIso as? String
    val fullTo = response.fullRange.toIso as? String
    if (session != null) {
        session.viewport = if (preserveViewport) {
            updatedHistoryViewport(session.viewport, range)
        } else {
            ChartViewport(range, range)
        }
        session.chart.options.scales.x.ticks.callback = { value: dynamic ->
            historyAxisTimeLabel((value as Number).toDouble(), range)
        }
        session.chart.data.datasets[0].data = representativePoints(points)
            .map { json("x" to it.time, "y" to it.value) }
            .toTypedArray()
        setChartXRange(session.chart, session.viewport.visible)
        updateChart(session.chart)
        syncHistoryScrubber(session)
    }
    val axis = container.querySelector("svg")
    axis?.setAttribute("data-full-from", fullFrom?.let(::historySvgEscape) ?: range.min.toString())
    axis?.setAttribute("data-full-to", fullTo?.let(::historySvgEscape) ?: range.max.toString())
    refreshedHistoryEvidenceSignatures[container.id] = historyEvidenceSignature(container)
    document.getElementById(HistoryRangeIds.NEW_DATA_BANNER)?.let { (it as HTMLElement).hidden = !wasZoomed }
}

private fun historyDateInZone(raw: dynamic, timezone: String): String? {
    val time = historyIsoTime(raw) ?: return null
    return historyDateAtTime(time, timezone)
}

private fun historyDateAtTime(time: Double, timezone: String): String {
    val formatter: dynamic = js(
        "new Intl.DateTimeFormat('en-CA', { timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit' })",
    )
    return formatter.format(Date(time)) as String
}

private fun historyCoverageWindow(first: dynamic, last: dynamic, timezone: String): String {
    val firstTime = historyIsoTime(first) ?: return "No data recorded"
    val lastTime = historyIsoTime(last) ?: return "No data recorded"
    val from = historyDateAtTime(minOf(firstTime, lastTime), timezone)
    val to = historyDateAtTime(maxOf(firstTime, lastTime), timezone)
    return "$from – $to"
}

private fun updateHistoryCoverage(response: dynamic, chartResponse: dynamic, olderScopesAvailable: Boolean) {
    val range = response.range
    val coverage = response.coverage
    val timezone = range.tz as? String ?: historyBrowserTimezone()
    val from = historyDateInZone(range.fromIso, timezone) ?: "All recorded data"
    val toTime = historyIsoTime(range.toIso)?.let { if (range.preset == "custom") it - 1.0 else it }
    val to = toTime?.let { historyDateAtTime(it, timezone) }.orEmpty()
    document.getElementById("history-coverage-selected")?.textContent =
        if (to.isEmpty()) from else "$from – $to"
    document.getElementById("history-coverage-values")?.textContent =
        historyCoverageWindow(coverage.valuesFirstIso, coverage.valuesLastIso, timezone)
    document.getElementById("history-coverage-trades")?.textContent =
        historyCoverageWindow(coverage.tradesFirstIso, coverage.tradesLastIso, timezone)
    document.getElementById("history-coverage-ledgers")?.textContent =
        historyCoverageWindow(coverage.ledgersFirstIso, coverage.ledgersLastIso, timezone)
    val pointCount = (chartResponse.fullRange?.pointCount as? Number)?.toInt() ?: 0
    val totalObservations =
        (chartResponse.fullRange?.totalObservations as? Number)?.toLong() ?: pointCount.toLong()
    val gapCount = (coverage.gapCount as? Number)?.toInt() ?: 0
    val incomplete = coverage.incomplete == true
    val representative = chartResponse.fullRange?.representative == true && pointCount.toLong() < totalObservations
    val completeness = if (incomplete) ViewText.HISTORY_COVERAGE_INCOMPLETE else ViewText.HISTORY_COVERAGE_COMPLETE
    val plotted = if (representative) " · ${ViewText.HISTORY_COVERAGE_PLOTTED}: $pointCount" else ""
    document.getElementById("history-coverage-count")?.textContent =
        "${ViewText.HISTORY_COVERAGE_RECORDED}: $totalObservations$plotted · $completeness · " +
        "${ViewText.HISTORY_COVERAGE_GAPS}: $gapCount"
    val points = historyPortfolioPoints(chartResponse)
    val first = points.firstOrNull()?.time
    val last = points.lastOrNull()?.time
    val full = historyChartRange(chartResponse, points)
    val sparse = range.preset == "30d" && first != null && last != null && points.isNotEmpty() &&
        full.span >= 29 * 86_400_000.0 && first > full.min && last - first <= 86_400_000.0
    val sparseMessage = document.getElementById("history-coverage-sparse") as? HTMLElement ?: return
    sparseMessage.hidden = !sparse
    if (sparse) {
        sparseMessage.textContent =
            "Portfolio values have been recorded since ${historyDateAtTime(first, timezone)}. " +
            "Older trades and transactions are available separately."
    }
    document.getElementById("history-scope-link")?.let { (it as HTMLElement).hidden = !olderScopesAvailable }
}

private fun historyReportNumber(value: dynamic): Double? = (value as? Number)?.toDouble()

private fun historyReportStatus(report: dynamic): String = if (report?.complete == true) {
    ViewText.HISTORY_REPORT_COMPLETE
} else {
    historyStatusString(report, "incompleteReason") ?: ViewText.HISTORY_REPORT_INCOMPLETE
}

private fun historyReportDate(value: dynamic): String {
    val iso = value as? String ?: return ViewText.HISTORY_REPORT_UNAVAILABLE
    val date: dynamic = js("new Date(value)")
    return if (date.toString() ==
        "Invalid Date"
    ) {
        ViewText.HISTORY_REPORT_UNAVAILABLE
    } else {
        date.toLocaleString().toString()
    }
}

private fun historyReportUsd(value: dynamic, complete: Boolean, report: dynamic): String {
    val number = historyReportNumber(value)
    val magnitude = number?.let { if (it < 0) -it else it }
    val formatted = magnitude?.asDynamic()?.toLocaleString(
        "en-US",
        json("minimumFractionDigits" to 2, "maximumFractionDigits" to 2),
    )?.toString()
    val currencyPrefix = if (number != null && number < 0) "-\$" else "\$"
    return when {
        number == null -> historyStatusString(report, "incompleteReason") ?: ViewText.HISTORY_REPORT_UNAVAILABLE
        complete -> "$currencyPrefix$formatted"
        else -> "$currencyPrefix$formatted · ${historyReportStatus(report)}"
    }
}

private fun historyReportCurrencyEntries(value: dynamic): List<Pair<String, Double>> {
    if (value == null || value == undefined) return emptyList()
    val keys: dynamic = js("Object.keys(value)")
    val count = (keys.length as? Number)?.toInt() ?: 0
    return (0 until count).mapNotNull { index ->
        val currency = keys[index] as? String ?: return@mapNotNull null
        historyReportNumber(value[currency])?.let { currency to it }
    }.sortedBy { it.first }
}

private fun historyReportQuantities(report: dynamic, field: String): String {
    if (report == null || report == undefined) return ViewText.HISTORY_REPORT_UNAVAILABLE
    val entries = historyReportCurrencyEntries(report[field])
    if (entries.isEmpty() && report.complete != true) {
        return historyStatusString(report, "incompleteReason") ?: ViewText.HISTORY_REPORT_UNAVAILABLE
    }
    val amounts = if (entries.isEmpty()) {
        "0"
    } else {
        entries.joinToString("; ") { (currency, value) ->
            "${historyNumber(value, money = false)} $currency"
        }
    }
    val excluded = (report.excludedCount as? Number)?.toInt() ?: 0
    val excludedText = if (excluded == 0) "" else " · $excluded excluded"
    return "$amounts · ${historyReportStatus(report)}$excludedText"
}

private fun historyReportCount(value: dynamic, key: String): String {
    if (value == null || value == undefined) return ViewText.HISTORY_REPORT_UNAVAILABLE
    val count = (value[key] as? Number)?.toLong()
        ?: return historyStatusString(value, "incompleteReason") ?: ViewText.HISTORY_REPORT_UNAVAILABLE
    val formatted = count.toString()
    return if (value.complete == true) formatted else "$formatted · ${historyReportStatus(value)}"
}

private fun historyReportAllocation(value: dynamic): String {
    if (value == null || value == undefined) return ViewText.HISTORY_REPORT_UNAVAILABLE
    val targets = historyReportCurrencyEntries(value.targetsUsed)
    if (targets.isEmpty()) return historyStatusString(value, "incompleteReason") ?: ViewText.HISTORY_REPORT_UNAVAILABLE
    val deviations = value.deviationsBySymbol
    val details = targets.joinToString("; ") { (symbol, target) ->
        val deviation = if (deviations == null || deviations == undefined) {
            null
        } else {
            historyReportNumber(deviations[symbol])
        }
        if (deviation == null) {
            "$symbol: ${ViewText.HISTORY_REPORT_UNAVAILABLE} (${historyNumber(target, money = false)}% target)"
        } else {
            val sign = if (deviation > 0) "+" else ""
            "$symbol: $sign${historyNumber(
                deviation,
                money = false,
            )} pp (${historyNumber(target, money = false)}% target)"
        }
    }
    val observedAt = historyReportDate(value.observedAt)
    return "$details · $observedAt · ${historyReportStatus(value)}"
}

internal fun updateHistoryStatistics(response: dynamic) {
    val statistics = response?.statistics
    if (statistics == null || statistics == undefined) return
    fun update(id: String, value: String) {
        document.getElementById(id)?.textContent = value
    }

    val latest = statistics.latestObservedValue
    update(
        HtmlIds.HISTORY_STAT_LATEST_VALUE,
        historyReportUsd(latest?.valueUsd, latest?.complete == true, latest),
    )
    val change = statistics.observedValueChange
    update(
        HtmlIds.HISTORY_STAT_VALUE_CHANGE,
        historyReportUsd(change?.changeUsd, change?.complete == true, change),
    )
    val extrema = statistics.rangeExtrema
    update(
        HtmlIds.HISTORY_STAT_RANGE_HIGH,
        historyReportUsd(extrema?.high?.valueUsd, extrema?.complete == true, extrema),
    )
    update(
        HtmlIds.HISTORY_STAT_RANGE_LOW,
        historyReportUsd(extrema?.low?.valueUsd, extrema?.complete == true, extrema),
    )
    val latestDate = historyReportDate(latest?.observedAt)
    update(
        HtmlIds.HISTORY_STAT_LATEST_META,
        if (latest?.outsideSelectedPeriod ==
            true
        ) {
            "$latestDate · ${ViewText.HISTORY_REPORT_OUTSIDE_RANGE}"
        } else {
            latestDate
        },
    )
    val firstValue = historyReportUsd(change?.firstValueUsd, change?.complete == true, change)
    val firstDate = historyReportDate(change?.firstObservedAt)
    val lastValue = historyReportUsd(change?.lastValueUsd, change?.complete == true, change)
    val lastDate = historyReportDate(change?.lastObservedAt)
    val endpoints = if (change?.complete == true) {
        "$firstValue at $firstDate to $lastValue at $lastDate"
    } else {
        historyStatusString(change, "incompleteReason") ?: ViewText.HISTORY_REPORT_UNAVAILABLE
    }
    update(HtmlIds.HISTORY_STAT_CHANGE_ENDPOINTS, endpoints)
    update(
        HtmlIds.HISTORY_STAT_CHANGE_NOTICE,
        historyStatusString(change, "includesFlowsNotice") ?: ViewText.HISTORY_VALUE_CHANGE_NOTICE,
    )
    update(HtmlIds.HISTORY_STAT_RANGE_HIGH_AT, historyReportDate(extrema?.high?.observedAt))
    update(HtmlIds.HISTORY_STAT_RANGE_LOW_AT, historyReportDate(extrema?.low?.observedAt))
    update(HtmlIds.HISTORY_STAT_TRADES, historyReportCount(statistics.trades, "tradeCount"))
    update(HtmlIds.HISTORY_STAT_BOT_CYCLES, historyReportCount(statistics.botCycles, "cycleCount"))
    update(HtmlIds.HISTORY_STAT_VOLUME, historyReportQuantities(statistics.trades, "volumeByCurrency"))
    update(HtmlIds.HISTORY_STAT_FEES, historyReportQuantities(statistics.trades, "feesByCurrency"))
    update(
        HtmlIds.HISTORY_STAT_EXCLUDED,
        historyReportCount(statistics.trades, "excludedCount"),
    )
    update(HtmlIds.HISTORY_STAT_ALLOCATION, historyReportAllocation(statistics.allocationDeviation))
    val depositsWithdrawals = statistics.depositsWithdrawals
    update(HtmlIds.HISTORY_STAT_DEPOSITS, historyReportQuantities(depositsWithdrawals?.deposits, "quantitiesByAsset"))
    update(
        HtmlIds.HISTORY_STAT_WITHDRAWALS,
        historyReportQuantities(depositsWithdrawals?.withdrawals, "quantitiesByAsset"),
    )
    update(HtmlIds.HISTORY_STAT_REWARDS, historyReportQuantities(statistics.rewards, "quantitiesByAsset"))
    update(
        HtmlIds.HISTORY_STAT_LEDGER_NOTICE,
        historyStatusString(depositsWithdrawals, "notice") ?: ViewText.HISTORY_LEDGER_CLASSIFICATION_NOTICE,
    )
}

internal fun resetHistoryChartViewports() {
    historyChartSessions.values.toList().forEach { session ->
        val reset = session.viewport.reset()
        session.viewport = reset
        setChartXRange(session.chart, reset.visible)
        updateChart(session.chart)
        syncHistoryScrubber(session)
    }
}

internal fun initHistoryRangeControls(
    currentQuery: String = window.location.search,
    savedDefaultRange: String? = null,
    fetchJson: (String, (dynamic) -> Unit, () -> Unit) -> Unit = ::fetchHistoryJson,
    navigate: (String) -> Unit = { window.location.assign(it) },
    pushState: (String) -> Unit = { window.asDynamic().history.pushState(null, "", it) },
    timezone: () -> String = ::historyBrowserTimezone,
    statusRequest: (String, (dynamic) -> Unit, () -> Unit) -> (() -> Unit) = ::fetchHistoryRequest,
    panelRequest: (String, (dynamic) -> Unit, () -> Unit) -> (() -> Unit) = ::fetchHistoryRequest,
    now: () -> Double = { Date.now() },
) {
    initHistorySeriesSelectors()
    val rangeLinks = document.querySelectorAll("[data-history-range]")
    if (rangeLinks.length == 0) return
    val firstLink = rangeLinks.item(0) as? Element ?: return
    if (firstLink.asDynamic().historyRangeControlsInitialized == true) return
    firstLink.asDynamic().historyRangeControlsInitialized = true

    val customPanel = document.getElementById("history-range-custom-panel") as? HTMLElement ?: return
    val startInput = document.getElementById("history-range-custom-start") as? HTMLInputElement ?: return
    val endInput = document.getElementById("history-range-custom-end") as? HTMLInputElement ?: return
    val timezoneLabel = document.getElementById("history-range-custom-tz") as? HTMLElement ?: return
    val errorMessage = document.getElementById("history-range-custom-error") as? HTMLElement ?: return
    val applyButton = document.getElementById("history-range-custom-apply") as? HTMLButtonElement ?: return
    val scopeSelect = document.getElementById("history-scope-select") as? HTMLSelectElement ?: return
    var activeQuery = currentQuery
    var currentScopes = emptyList<HistoryScopeOption>()
    var latestChartResponse: dynamic = null
    var latestSummaryResponse: dynamic = null
    var latestActivityNextCursor: String? = null
    var activityNeedsFirstPageNotice = false
    var latestStatusResponse: dynamic = null
    var lastStatusUpdatedAt: Double? = null
    var nextStatusRefreshAt: Double? = null
    var panelRequestVersion = 0
    var activeQueryVersion = 0
    val pendingPanelCancels = mutableListOf<() -> Unit>()

    fun setRangeError(message: String?) {
        errorMessage.hidden = message == null
        errorMessage.textContent = message.orEmpty()
    }

    fun updateRangeControls(state: HistoryRangeState) {
        customPanel.hidden = state.preset != "custom"
        startInput.value = state.start.orEmpty()
        endInput.value = state.end.orEmpty()
        timezoneLabel.textContent = "Timezone: ${state.timezone}"
        (document.getElementById("history-filter-range") as? HTMLInputElement)?.value = state.preset
        (document.getElementById("history-filter-scope") as? HTMLInputElement)?.value = state.scope.orEmpty()
        (document.getElementById("history-filter-start") as? HTMLInputElement)?.value = state.start.orEmpty()
        (document.getElementById("history-filter-end") as? HTMLInputElement)?.value = state.end.orEmpty()
        (document.getElementById("history-filter-tz") as? HTMLInputElement)?.value = state.timezone
        (document.getElementById(HistoryRangeIds.FILTER_TAB) as? HTMLInputElement)?.value =
            historyPageTabState(activeQuery).tab
        (document.getElementById(HistoryRangeIds.FILTER_ACTIVITY) as? HTMLInputElement)?.value =
            historyPageTabState(activeQuery).activity
        (document.getElementById(HistoryRangeIds.REPAIR_START) as? HTMLInputElement)?.value = state.start.orEmpty()
        (document.getElementById(HistoryRangeIds.REPAIR_END) as? HTMLInputElement)?.value = state.end.orEmpty()
        (document.getElementById(HistoryRangeIds.REPAIR_TIMEZONE) as? HTMLInputElement)?.value = state.timezone
        document.getElementById(HistoryRangeIds.REPAIR_TIMEZONE_LABEL)?.textContent = state.timezone
        updateHistoryTabPanels(activeQuery)
        val statusRange = latestStatusResponse?.range
        val matchingRange =
            if (statusRange != null && statusRange != undefined &&
                historyStatusString(statusRange, "preset") == state.preset
            ) {
                statusRange
            } else {
                null
            }
        updateHistoryExportLinks(activeQuery, matchingRange)
        repeat(rangeLinks.length) { index ->
            val link = rangeLinks.item(index) as? Element ?: return@repeat
            val active = link.getAttribute("data-history-range") == state.preset
            link.setAttribute(
                "class",
                if (active) CssClass.History.TimeRangeBtnActive.value else CssClass.History.TimeRangeBtn.value,
            )
        }
    }

    fun refreshCoverage() {
        val chart = latestChartResponse
        val summary = latestSummaryResponse
        if (chart != null && chart != undefined && summary != null && summary != undefined) {
            updateHistoryCoverage(summary, chart, currentScopes.any { !it.current })
        }
    }

    fun setScopeOptions(response: dynamic) {
        val rawScopes = response.scopes
        val count = (rawScopes?.length as? Number)?.toInt() ?: 0
        currentScopes = (0 until count).mapNotNull { index ->
            val scope = rawScopes[index]
            val id = scope.id as? String ?: return@mapNotNull null
            val rawSymbols = scope.symbols
            val symbolCount = (rawSymbols?.length as? Number)?.toInt() ?: 0
            HistoryScopeOption(
                id = id,
                symbols = (0 until symbolCount).mapNotNull { rawSymbols[it] as? String },
                earliestIso = scope.earliestIso as? String,
                latestIso = scope.latestIso as? String,
                current = scope.current == true,
            )
        }
        val selectedScope = historyParam(activeQuery, "scope")
            ?: currentScopes.firstOrNull { it.current }?.id
        scopeSelect.innerHTML = ""
        currentScopes.forEach { scope ->
            val option = document.createElement("option") as HTMLElement
            option.setAttribute("value", scope.id)
            val symbols = scope.symbols.joinToString(" + ").ifEmpty { "Recorded portfolio" }
            val dates = historyCoverageWindow(scope.earliestIso, scope.latestIso, timezone())
            option.textContent = "$symbols · $dates${if (scope.current) " · Current" else ""}"
            scopeSelect.appendChild(option)
        }
        if (selectedScope != null && currentScopes.any { it.id == selectedScope }) {
            scopeSelect.value = selectedScope
        }
        refreshCoverage()
    }

    fun queryUrl(path: String, query: String): String = if (query.isEmpty()) path else "$path?$query"

    fun cancelPanelRequests() {
        panelRequestVersion++
        pendingPanelCancels.toList().forEach { it() }
        pendingPanelCancels.clear()
    }

    fun requestPanel(url: String, onSuccess: (dynamic) -> Unit, onFailure: () -> Unit = {}) {
        val version = panelRequestVersion
        val queryVersion = activeQueryVersion
        var completed = false
        var cancel: (() -> Unit)? = null
        val requestCancel = panelRequest(
            url,
            { response ->
                completed = true
                cancel?.let(pendingPanelCancels::remove)
                if (version == panelRequestVersion && queryVersion == activeQueryVersion) {
                    onSuccess(response)
                }
            },
            {
                completed = true
                cancel?.let(pendingPanelCancels::remove)
                if (version == panelRequestVersion && queryVersion == activeQueryVersion) onFailure()
            },
        )
        if (!completed) {
            cancel = requestCancel
            pendingPanelCancels += requestCancel
        }
    }

    fun setActivityMessage(message: String) {
        val body = document.getElementById(HistoryRangeIds.ACTIVITY_BODY) ?: return
        body.innerHTML = ""
        val row = document.createElement("tr")
        val cell = document.createElement("td")
        cell.setAttribute("class", CssClass.History.EmptyTableCell.value)
        cell.setAttribute("colspan", "5")
        cell.textContent = message
        row.appendChild(cell)
        body.appendChild(row)
    }

    fun loadActivityPage(query: String = activeQuery) {
        val tabs = historyPageTabState(query)
        if (tabs.tab != HistoryRangeQuery.TAB_ACTIVITY || tabs.activity != HistoryRangeQuery.ACTIVITY_BOT) return
        if (!historyActivityRefreshKeepsPage(query)) activityNeedsFirstPageNotice = false
        (document.getElementById(HistoryRangeIds.ACTIVITY_PREVIOUS) as? HTMLElement)?.hidden = true
        (document.getElementById(HistoryRangeIds.ACTIVITY_NEXT) as? HTMLElement)?.hidden = true
        setActivityMessage(HistoryRangeText.ACTIVITY_LOADING)
        requestPanel(
            historyActivityApiUrl(query),
            { response ->
                if (query == activeQuery) {
                    latestActivityNextCursor = historyStatusString(response, "nextCursor")
                    updateHistoryActivities(response, query)
                    setHistoryNewRecordsNotice(activityNeedsFirstPageNotice)
                }
            },
            { if (query == activeQuery) setActivityMessage(HistoryRangeText.ACTIVITY_LOAD_FAILED) },
        )
    }

    fun refreshActivityForRevision() {
        val tabs = historyPageTabState(activeQuery)
        if (historyActivityRefreshKeepsPage(activeQuery)) {
            activityNeedsFirstPageNotice = true
            setHistoryNewRecordsNotice(true)
        } else if (tabs.tab == HistoryRangeQuery.TAB_ACTIVITY && tabs.activity == HistoryRangeQuery.ACTIVITY_BOT) {
            loadActivityPage()
        }
    }

    fun refreshTable(table: String, statusResponse: dynamic) {
        val offsetKey = if (table == "trades") {
            HistoryRangeQuery.PARAM_TRADES_OFFSET
        } else {
            HistoryRangeQuery.PARAM_LEDGERS_OFFSET
        }
        val offset = historyParam(activeQuery, offsetKey)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        if (offset > 0) {
            setHistoryNewRecordsNotice(true)
            return
        }
        val tableName = if (table == "trades") "trades" else "ledgers"
        val pagination = document.querySelector("[data-history-pagination='$tableName']")
        val limit = pagination?.getAttribute("data-history-limit")?.toIntOrNull() ?: 50
        val statusRange = statusResponse?.range
        val hasStatusRange = historyStatusString(statusRange, "fromIso") != null ||
            historyStatusString(statusRange, "toIso") != null
        val range = if (hasStatusRange) {
            statusResponse.range
        } else {
            latestChartResponse?.range
        }
        val url = historyTableApiUrl(tableName, activeQuery, range, offset, limit) ?: return
        requestPanel(url, onSuccess = { response -> updateHistoryTable(tableName, response, activeQuery, limit) })
    }

    fun refreshChangedPanels(previous: dynamic, current: dynamic) {
        val targets = historyRefreshTargets(previous, current)
        cancelPanelRequests()
        val state = historyRangeState(activeQuery, savedDefaultRange, timezone())
        val pinnedRange = if (historyChartSessions.values.any { it.viewport.isZoomed }) {
            latestChartResponse?.range
        } else {
            null
        }
        if (targets.charts) {
            val chartQuery = historyApiQuery(state, chart = true, pinnedRange = pinnedRange)
            requestPanel(queryUrl(HistoryRangeQuery.API_CHARTS, chartQuery), onSuccess = { response ->
                latestChartResponse = response
                updateHistoryPortfolioChart(response, preserveViewport = true)
                refreshCoverage()
            })
        }
        if (targets.summary) {
            val summaryQuery = historyApiQuery(state, chart = false, pinnedRange = pinnedRange)
            requestPanel(queryUrl(HistoryRangeQuery.API_SUMMARY, summaryQuery), onSuccess = { response ->
                latestSummaryResponse = response
                updateHistoryStatistics(response)
                refreshCoverage()
            })
        }
        if (targets.trades) refreshTable("trades", current)
        if (targets.ledgers) refreshTable("ledgers", current)
        if (targets.activity) refreshActivityForRevision()
    }

    fun refreshLatestSelection() {
        cancelPanelRequests()
        resetHistoryChartViewports()
        val state = historyRangeState(activeQuery, savedDefaultRange, timezone())
        val chartQuery = historyApiQuery(state, chart = true)
        val summaryQuery = historyApiQuery(state, chart = false)
        requestPanel(queryUrl(HistoryRangeQuery.API_CHARTS, chartQuery), onSuccess = { response ->
            latestChartResponse = response
            updateHistoryPortfolioChart(response)
            refreshCoverage()
        })
        requestPanel(queryUrl(HistoryRangeQuery.API_SUMMARY, summaryQuery), onSuccess = { response ->
            latestSummaryResponse = response
            updateHistoryStatistics(response)
            refreshCoverage()
        })
        val tabs = historyPageTabState(activeQuery)
        if (tabs.tab == HistoryRangeQuery.TAB_ACTIVITY && tabs.activity == HistoryRangeQuery.ACTIVITY_BOT) {
            loadActivityPage(activeQuery)
        }
        (document.getElementById(HistoryRangeIds.NEW_DATA_BANNER) as? HTMLElement)?.hidden = true
    }

    if (document.getElementById(HistoryRangeIds.REFRESH_STATUS) != null) {
        historyLatestDataHandler = ::refreshLatestSelection
    }

    fun refresh(query: String) {
        cancelPanelRequests()
        activeQuery = query
        activeQueryVersion++
        val queryVersion = activeQueryVersion
        historyStatusPoller?.refreshContext()
        val state = historyRangeState(query, savedDefaultRange, timezone())
        updateRangeControls(state)
        updateHistoryTabPanels(query)
        updateHistoryExportLinks(query, null)
        val invalid = if (state.preset == "custom") {
            historyCustomRangeError(state.start, state.end)
        } else {
            null
        }
        setRangeError(invalid)
        if (invalid != null) return

        latestChartResponse = null
        latestSummaryResponse = null
        val chartQuery = historyApiQuery(state, chart = true)
        val summaryQuery = historyApiQuery(state, chart = false)
        fetchJson(queryUrl(HistoryRangeQuery.API_CHARTS, chartQuery), { response ->
            if (queryVersion == activeQueryVersion) {
                latestChartResponse = response
                updateHistoryPortfolioChart(response)
                refreshCoverage()
            }
        }, {})
        fetchJson(queryUrl(HistoryRangeQuery.API_SUMMARY, summaryQuery), { response ->
            if (queryVersion == activeQueryVersion) {
                latestSummaryResponse = response
                updateHistoryStatistics(response)
                refreshCoverage()
            }
        }, {})
        loadActivityPage(query)
    }

    fun startHistoryPolling() {
        if (document.getElementById(HistoryRangeIds.REFRESH_STATUS) == null || historyStatusPoller != null) return
        val poller = HistoryDataStatusPoller(
            statusUrl = { historyStatusUrl(activeQuery, savedDefaultRange, timezone()) },
            request = statusRequest,
            onPending = {
                nextStatusRefreshAt = now() + HistoryRangeProps.POLL_INTERVAL_MS
                updateHistoryRefreshStatus(
                    latestStatusResponse,
                    lastStatusUpdatedAt,
                    nextStatusRefreshAt,
                    pending = true,
                )
            },
            onResponse = { response, previous, changed ->
                latestStatusResponse = response
                lastStatusUpdatedAt = now()
                updateHistoryRefreshStatus(response, lastStatusUpdatedAt, nextStatusRefreshAt)
                val state = historyRangeState(activeQuery, savedDefaultRange, timezone())
                val statusRange = response?.range
                val range =
                    if (statusRange != null && statusRange != undefined &&
                        historyStatusString(statusRange, "preset") == state.preset
                    ) {
                        statusRange
                    } else {
                        null
                    }
                updateHistoryExportLinks(activeQuery, range)
                if (changed && previous != null && previous != undefined) {
                    refreshChangedPanels(previous, response)
                }
            },
            onFailure = {
                updateHistoryRefreshStatus(
                    latestStatusResponse,
                    lastStatusUpdatedAt,
                    nextStatusRefreshAt,
                    failed = true,
                )
            },
            onPaused = {
                nextStatusRefreshAt = null
                updateHistoryRefreshStatus(latestStatusResponse, lastStatusUpdatedAt, null)
            },
            abortOtherRequests = ::cancelPanelRequests,
        )
        historyStatusPoller = poller
        historyRefreshReinitializer = ::startHistoryPolling
        observeHistoryRefreshLifecycle(poller)
        poller.start()
    }

    val initialState = historyRangeState(activeQuery, savedDefaultRange, timezone())
    updateRangeControls(initialState)
    updateHistoryTabPanels(activeQuery)

    fun changeTabs(tab: String, activity: String? = null) {
        activeQuery = historyTabsQuery(activeQuery, tab, activity)
        updateHistoryTabPanels(activeQuery)
        val state = historyRangeState(activeQuery, savedDefaultRange, timezone())
        val statusRange = latestStatusResponse?.range
        val range =
            if (statusRange != null && statusRange != undefined &&
                historyStatusString(statusRange, "preset") == state.preset
            ) {
                statusRange
            } else {
                null
            }
        updateHistoryExportLinks(activeQuery, range)
        pushState(queryUrl(Routes.HISTORY, activeQuery))
        loadActivityPage(activeQuery)
    }

    document.getElementById(HistoryRangeIds.TAB_NAV)?.querySelectorAll("[${HtmlAttrs.DATA_HISTORY_TAB}]")?.let { tabs ->
        repeat(tabs.length) { index ->
            val link = tabs.item(index) as? Element ?: return@repeat
            link.addEventListener(HtmlEvents.CLICK, { event: Event ->
                if (event.asDynamic().metaKey == true || event.asDynamic().ctrlKey == true ||
                    event.asDynamic().shiftKey == true || event.asDynamic().altKey == true
                ) {
                    return@addEventListener
                }
                event.preventDefault()
                link.getAttribute(HtmlAttrs.DATA_HISTORY_TAB)?.let { changeTabs(it) }
            })
        }
    }
    document.getElementById(HistoryRangeIds.ACTIVITY_NAV)
        ?.querySelectorAll("[${HtmlAttrs.DATA_HISTORY_ACTIVITY_TAB}]")?.let { tabs ->
            repeat(tabs.length) { index ->
                val link = tabs.item(index) as? Element ?: return@repeat
                link.addEventListener(HtmlEvents.CLICK, { event: Event ->
                    if (event.asDynamic().metaKey == true || event.asDynamic().ctrlKey == true ||
                        event.asDynamic().shiftKey == true || event.asDynamic().altKey == true
                    ) {
                        return@addEventListener
                    }
                    event.preventDefault()
                    link.getAttribute(HtmlAttrs.DATA_HISTORY_ACTIVITY_TAB)
                        ?.let { changeTabs(HistoryRangeQuery.TAB_ACTIVITY, it) }
                })
            }
        }
    listOf(HistoryRangeIds.ACTIVITY_PREVIOUS, HistoryRangeIds.ACTIVITY_NEXT).forEach { id ->
        document.getElementById(id)?.addEventListener(HtmlEvents.CLICK, { event: Event ->
            event.preventDefault()
            val query = if (id == HistoryRangeIds.ACTIVITY_PREVIOUS) {
                historyActivityPreviousPageQuery(activeQuery)
            } else {
                historyActivityNextPageQuery(activeQuery, latestActivityNextCursor)
            }
            if (query != null) {
                activeQuery = query
                updateHistoryTabPanels(activeQuery)
                pushState(queryUrl(Routes.HISTORY, activeQuery) + "#${HistoryRangeIds.PANEL_ACTIVITY}")
                loadActivityPage(activeQuery)
            }
        })
    }
    val initialError = if (initialState.preset == "custom") {
        historyCustomRangeError(initialState.start, initialState.end)
    } else {
        null
    }
    setRangeError(initialError)
    if (initialError != null) customPanel.hidden = false

    repeat(rangeLinks.length) { index ->
        val link = rangeLinks.item(index) as? Element ?: return@repeat
        link.addEventListener("click", { event: Event ->
            event.preventDefault()
            val preset = link.getAttribute("data-history-range") ?: return@addEventListener
            if (preset == "custom") {
                updateRangeControls(
                    historyRangeState(activeQuery, savedDefaultRange, timezone()).copy(preset = "custom"),
                )
                setRangeError(null)
                return@addEventListener
            }
            if (preset == historyRangeForQuery(activeQuery, savedDefaultRange)) return@addEventListener
            resetHistoryChartViewports()
            navigate(historyPageUrl(activeQuery, preset))
        })
    }

    applyButton.addEventListener("click", {
        val start = startInput.value
        val end = endInput.value
        val invalid = historyCustomRangeError(start, end)
        setRangeError(invalid)
        if (invalid != null) return@addEventListener
        val selectedTimezone = historyParam(activeQuery, "tz")?.takeIf(String::isNotBlank) ?: timezone()
        resetHistoryChartViewports()
        navigate(historyPageUrl(activeQuery, "custom", start, end, selectedTimezone))
    })

    scopeSelect.addEventListener("change", {
        val params = historyParams(activeQuery)
        if (scopeSelect.value.isBlank()) {
            params.delete(HistoryRangeQuery.PARAM_SCOPE)
        } else {
            params.set(HistoryRangeQuery.PARAM_SCOPE, scopeSelect.value)
        }
        params.delete(HistoryRangeQuery.PARAM_ACTIVITY_CURSORS)
        activeQuery = params.toString()
        val encoded = activeQuery
        pushState(queryUrl(Routes.HISTORY, encoded))
        refresh(activeQuery)
    })

    document.getElementById("history-scope-link")?.addEventListener("click", { event: Event ->
        event.preventDefault()
        scopeSelect.focus()
    })

    window.addEventListener("popstate", {
        val query = window.location.search
        refresh(query)
        setScopeOptionsFromCurrentQuery(currentScopes, scopeSelect, query)
    })

    document.getElementById(HistoryRangeIds.NEW_RECORDS_BUTTON)?.addEventListener(HtmlEvents.CLICK, {
        val state = historyRangeState(activeQuery, savedDefaultRange, timezone())
        navigate(historyPageUrl(activeQuery, state.preset, state.start, state.end, state.timezone))
    })
    document.getElementById(HistoryRangeIds.NEW_DATA_BUTTON)?.addEventListener(HtmlEvents.CLICK, {
        historyLatestDataHandler?.invoke() ?: resetHistoryChartViewports()
    })

    if (initialError == null) {
        fetchJson(HistoryRangeQuery.API_SCOPES, { response -> setScopeOptions(response) }, {})
        refresh(activeQuery)
    }
    startHistoryPolling()
}

private fun setScopeOptionsFromCurrentQuery(
    scopes: List<HistoryScopeOption>,
    select: HTMLSelectElement,
    query: String,
) {
    val selected = historyParam(query, "scope") ?: scopes.firstOrNull { it.current }?.id ?: return
    if (scopes.any { it.id == selected }) select.value = selected
}

internal fun historyChartConfig(
    points: List<HistoryChartPoint>,
    money: Boolean,
    axisRange: ChartRange? = null,
): dynamic {
    val shortWindow = axisRange?.span?.let { it < 3_600_000.0 } == true
    val data = representativePoints(points).map { json("x" to it.time, "y" to it.value) }.toTypedArray()
    return json(
        "type" to "line",
        "data" to json(
            "datasets" to arrayOf(
                json(
                    "data" to data,
                    "borderColor" to ChartProps.COLOR_BLUE,
                    "backgroundColor" to ChartProps.COLOR_BLUE,
                    "borderWidth" to 2,
                    "pointRadius" to if (money && points.count { it.value != null } > 60) 1 else 3,
                    "pointHitRadius" to 10,
                    "pointHoverRadius" to 6,
                    "showLine" to money,
                    "spanGaps" to false,
                ),
            ),
        ),
        "options" to json(
            "responsive" to true,
            "maintainAspectRatio" to false,
            "animation" to false,
            "interaction" to json("mode" to "nearest", "axis" to "x", "intersect" to false),
            "parsing" to false,
            "plugins" to json(
                "legend" to json("display" to false),
                "tooltip" to json(
                    "callbacks" to json(
                        "title" to { items: dynamic -> historyDateLabel((items[0].parsed.x as Number).toDouble()) },
                        "label" to { item: dynamic -> historyNumber((item.parsed.y as Number).toDouble(), money) },
                    ),
                ),
            ),
            "scales" to json(
                "x" to json(
                    "type" to "linear",
                    "grid" to json("color" to ChartProps.COLOR_GRID_LINE),
                    "ticks" to json(
                        "color" to ChartProps.COLOR_TICK,
                        "maxTicksLimit" to 5,
                        "maxRotation" to 0,
                        "callback" to { value: dynamic ->
                            historyAxisTimeLabel((value as Number).toDouble(), shortWindow)
                        },
                    ),
                ),
                "y" to json(
                    "grid" to json("color" to ChartProps.COLOR_GRID_LINE),
                    "ticks" to json(
                        "color" to ChartProps.COLOR_TICK,
                        "maxTicksLimit" to 6,
                        "callback" to { value: dynamic -> historyNumber((value as Number).toDouble(), money) },
                    ),
                ),
            ),
        ),
    )
}

private fun createHistoryChart(canvas: HTMLCanvasElement, config: dynamic): dynamic {
    val chartConstructor = window.asDynamic().Chart
    if (chartConstructor == null || chartConstructor == undefined) return null
    return js("Reflect.construct(chartConstructor, [canvas, config])")
}

/** Multi-series companion to [historyChartConfig]: one dataset per series with its palette color. */
internal fun historyMultiSeriesConfig(
    series: List<Pair<String, List<HistoryChartPoint>>>,
    percent: Boolean,
    axisRange: ChartRange? = null,
): dynamic {
    val shortWindow = axisRange?.span?.let { it < 3_600_000.0 } == true
    val dense = series.maxOfOrNull { it.second.size } ?: 0
    val datasets = series.mapIndexed { index, (name, points) ->
        val color = borderColorForSymbol(name, index)
        json(
            "label" to name,
            "data" to representativePoints(points).map { json("x" to it.time, "y" to it.value) }.toTypedArray(),
            "borderColor" to color,
            "backgroundColor" to color,
            "borderWidth" to 2,
            "pointRadius" to if (dense > 60) 1 else 3,
            "pointHitRadius" to 10,
            "pointHoverRadius" to 6,
            "showLine" to true,
            "spanGaps" to false,
        )
    }.toTypedArray()
    return json(
        "type" to "line",
        "data" to json("datasets" to datasets),
        "options" to json(
            "responsive" to true,
            "maintainAspectRatio" to false,
            "animation" to false,
            "interaction" to json("mode" to "nearest", "axis" to "x", "intersect" to false),
            "parsing" to false,
            "plugins" to json(
                "legend" to json(
                    "display" to true,
                    "labels" to json(
                        "color" to ChartProps.COLOR_TICK,
                        "usePointStyle" to true,
                        "boxHeight" to 6,
                    ),
                ),
                "tooltip" to json(
                    "callbacks" to json(
                        "title" to { items: dynamic -> historyDateLabel((items[0].parsed.x as Number).toDouble()) },
                        "label" to { item: dynamic ->
                            val value = (item.parsed.y as Number).toDouble()
                            val label = item.dataset.label
                            val prefix = if (label != null && label != undefined) "$label · " else ""
                            prefix + if (percent) {
                                historyNumber(value, false) + " pp"
                            } else {
                                historyNumber(value, true)
                            }
                        },
                    ),
                ),
            ),
            "scales" to json(
                "x" to json(
                    "type" to "linear",
                    "grid" to json("color" to ChartProps.COLOR_GRID_LINE),
                    "ticks" to json(
                        "color" to ChartProps.COLOR_TICK,
                        "maxTicksLimit" to 5,
                        "maxRotation" to 0,
                        "callback" to { value: dynamic ->
                            historyAxisTimeLabel((value as Number).toDouble(), shortWindow)
                        },
                    ),
                ),
                "y" to json(
                    "grid" to json(
                        "color" to { ctx: dynamic ->
                            val tick = ctx.tick
                            if (percent && tick != null && tick != undefined &&
                                (tick.value as Number).toDouble() == 0.0
                            ) {
                                ChartProps.COLOR_TICK
                            } else {
                                ChartProps.COLOR_GRID_LINE
                            }
                        },
                    ),
                    "ticks" to json(
                        "color" to ChartProps.COLOR_TICK,
                        "maxTicksLimit" to 6,
                        "callback" to { value: dynamic ->
                            val number = (value as Number).toDouble()
                            if (percent) historyNumber(number, false) + " pp" else historyNumber(number, true)
                        },
                    ),
                ),
            ),
        ),
    )
}

/**
 * Enhance only after Chart.js succeeds, so a failed enhancement leaves the native SVG fallback visible
 * with a retry notice. A rebuilt chart first disposes its predecessor so no stale instance or handler
 * survives new evidence.
 */
internal fun initHistoryCharts(factory: (HTMLCanvasElement, dynamic) -> dynamic = ::createHistoryChart) {
    disposeDetachedHistoryCharts()
    val singles = mapOf(
        HtmlIds.HISTORY_VALUE_CHART to true,
        HtmlIds.HISTORY_ASSET_CHART to false,
        HtmlIds.HISTORY_CASH_FLOW_CHART to true,
        HtmlIds.HISTORY_REWARDS_CHART to true,
    )
    val multiples = mapOf(
        HtmlIds.HISTORY_HOLDINGS_CHART to false,
        HtmlIds.HISTORY_ALLOCATION_CHART to true,
    )
    (singles + multiples).forEach { (id, _) ->
        val container = document.getElementById(id) ?: return@forEach
        val svg = container.querySelector("svg") ?: return@forEach
        val signature = historyEvidenceSignature(container)
        val existing = historyChartSessions[id]
        if (existing != null) {
            if ((refreshedHistoryEvidenceSignatures[id] ?: existing.evidenceSignature) == signature) {
                return@forEach
            }
            refreshedHistoryEvidenceSignatures.remove(id)
            existing.dispose()
        }
        val single = singles[id]
        val points = historyChartPoints(container)
        val series = historySeriesPoints(container)
        val empty = if (single != null) {
            points.none { it.value != null }
        } else {
            series.isEmpty()
        }
        if (empty) return@forEach
        val canvas = canvasFor(container)
        val config = if (single != null) {
            historyChartConfig(points, single)
        } else {
            historyMultiSeriesConfig(series, multiples[id] == true)
        }
        applyLegendVisibility(config, id)
        val chart = try {
            factory(canvas, config)
        } catch (_: Throwable) {
            null
        }
        if (chart == null || chart == undefined) {
            canvas.remove()
            showChartFallbackNotice(container) { initHistoryCharts(factory) }
            return@forEach
        }
        removeChartFallbackNotice(container)
        svg.setAttribute("hidden", "")
        svg.setAttribute("style", "display:none")
        val session = HistoryChartSession(
            container = container,
            canvas = canvas,
            chart = chart,
            evidenceSignature = signature,
            fullRange = ChartRange(points.first().time, points.last().time),
        )
        historyChartSessions[id] = session
        refreshedHistoryEvidenceSignatures.remove(id)
        bindLegendPersistence(chart, id)
        attachChartGestures(session)
        setupHistoryChartControls(session, points, single ?: true)
    }
    initHistoryScrubbers()
}

private fun canvasFor(container: Element): HTMLCanvasElement {
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    container.appendChild(canvas)
    return canvas
}

/**
 * The scrubber pans the visible interval once a chart exists; without an enhanced chart it keeps
 * focusing each server-rendered sample on the SVG fallback.
 */
internal fun initHistoryScrubbers() {
    listOf(
        HtmlIds.HISTORY_VALUE_CHART,
        HtmlIds.HISTORY_ASSET_CHART,
        HtmlIds.HISTORY_HOLDINGS_CHART,
        HtmlIds.HISTORY_ALLOCATION_CHART,
        HtmlIds.HISTORY_CASH_FLOW_CHART,
        HtmlIds.HISTORY_REWARDS_CHART,
    ).forEach { chartId ->
        val scrubber = queryChartScrubber(chartId) ?: return@forEach
        val session = historyChartSessions[chartId]
        if (session != null) {
            bindHistoryScrubber(scrubber, session)
        } else {
            bindSampleScrubber(scrubber, chartId)
        }
    }
}

private fun bindSampleScrubber(scrubber: HTMLInputElement, chartId: String) {
    releaseHistoryScrubber(scrubber)
    val container = document.getElementById(chartId) ?: return
    val points = historyChartPoints(container)
    val times = points.map { it.time }.distinct()
    if (times.size < 2) return
    scrubber.asDynamic().historyScrubberMode = "sample"
    scrubber.disabled = false
    scrubber.min = "0"
    scrubber.max = (times.size - 1).toString()
    scrubber.step = "1"
    scrubber.value = "0"
    val dispose = listenDom(scrubber, HtmlEvents.INPUT) {
        focusHistorySample(container, times[scrubber.value.toIntOrNull() ?: 0])
    }
    scrubber.asDynamic().historyScrubberDispose = dispose
}

private fun focusHistorySample(container: Element, time: Double) {
    val circles = container.querySelectorAll("circle[${HtmlAttrs.DATA_TIME}]")
    repeat(circles.length) { index ->
        val circle = circles.item(index) as? Element ?: return@repeat
        val focused = circle.getAttribute(HtmlAttrs.DATA_TIME)?.toDoubleOrNull() == time
        circle.classList.toggle(CssClass.Utility.Active, focused)
    }
}

private fun setupHistoryChartControls(session: HistoryChartSession, points: List<HistoryChartPoint>, money: Boolean) {
    val panel = session.container.closest(".glass-panel") ?: return
    val controls = panel.querySelector("[${HtmlAttrs.DATA_HISTORY_CONTROLS}]") as? HTMLElement ?: return
    controls.hidden = false
    panel.querySelector("[${HtmlAttrs.DATA_HISTORY_VIEW}='${HistoryChartActions.VIEW_LINE}']")
        ?.classList?.add(CssClass.Utility.Active.value)
    val chart = session.chart
    val buttons = controls.querySelectorAll("button")
    repeat(buttons.length) { index ->
        val button = buttons.item(index) as? HTMLElement ?: return@repeat
        button.addEventListener(HtmlEvents.CLICK, {
            button.getAttribute(HtmlAttrs.DATA_HISTORY_VIEW)?.let { view ->
                val datasets = chart.data.datasets
                repeat(datasets.length) { datasetIndex ->
                    val dataset = datasets[datasetIndex]
                    dataset.showLine = money && view == HistoryChartActions.VIEW_LINE
                    dataset.pointRadius =
                        if (money && view == HistoryChartActions.VIEW_LINE && points.count { it.value != null } > 60) {
                            1
                        } else {
                            3
                        }
                }
                val views = controls.querySelectorAll("[${HtmlAttrs.DATA_HISTORY_VIEW}]")
                repeat(views.length) { viewIndex ->
                    val viewButton = views.item(viewIndex) as Element
                    viewButton.classList.toggle(CssClass.Utility.Active, viewButton == button)
                }
                updateChart(chart)
            }
            button.getAttribute(HtmlAttrs.DATA_HISTORY_ZOOM)?.let { action ->
                session.setViewport(zoomedViewport(session.viewport, action))
                if (action == HistoryChartActions.ZOOM_RESET) {
                    (document.getElementById(HistoryRangeIds.NEW_DATA_BANNER) as? HTMLElement)?.hidden = true
                    historyLatestDataHandler?.invoke()
                }
            }
            button.getAttribute(HtmlAttrs.DATA_HISTORY_PAN)?.let { direction ->
                session.setViewport(pannedViewport(session.viewport, direction))
            }
            if (button.getAttribute(HtmlAttrs.DATA_HISTORY_EXPAND) == HistoryChartActions.EXPAND) {
                val expanded = panel.classList.toggle(CssClass.History.ChartExpanded)
                button.textContent = if (expanded) ViewText.HISTORY_CHART_COLLAPSE else ViewText.HISTORY_CHART_EXPAND
                chart.resize()
                updateChart(chart)
            }
        })
    }
}
