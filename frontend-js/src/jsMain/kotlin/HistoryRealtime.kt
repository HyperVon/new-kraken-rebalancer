package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlEvents
import com.gemini.krakenbot.view.util.HtmlIds
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

internal const val HISTORY_REALTIME_DEBOUNCE_MS: Int = 450

private var historyRealtimeDebounceId: Int? = null
private var historyRealtimeRoot: HTMLElement? = null
private var historyRealtimeEventListener: dynamic = null

internal fun isHistoryPage(): Boolean = document.getElementById(HtmlIds.PORTFOLIO_VALUE_CHART) != null

internal fun isHistorySyncReady(): Boolean {
    val banner = document.getElementById(HtmlIds.SYNC_PROGRESS_BANNER) as? HTMLElement ?: return true
    return banner.classList.contains(CssClass.Utility.Hidden.value)
}

internal fun scheduleHistoryRealtimeReload() {
    if (!isHistoryPage()) return
    if (!isHistorySyncReady()) return
    historyRealtimeDebounceId?.let { window.clearTimeout(it) }
    historyRealtimeDebounceId = window.setTimeout({
        historyRealtimeDebounceId = null
        if (!isHistoryPage()) return@setTimeout
        if (!isHistorySyncReady()) return@setTimeout
        try {
            loadAll(historyCurrentRange()).`catch` { error ->
                console.error("Error refreshing history after realtime update", error)
            }
        } catch (_: Throwable) {
        }
    }, HISTORY_REALTIME_DEBOUNCE_MS)
}

internal fun teardownHistoryRealtimeUpdates() {
    historyRealtimeDebounceId?.let { window.clearTimeout(it) }
    historyRealtimeDebounceId = null
    val root = historyRealtimeRoot
    val listener = historyRealtimeEventListener
    if (root != null && listener != null) {
        try {
            root.asDynamic().removeEventListener(HtmlEvents.SSE_MESSAGE, listener)
        } catch (_: Throwable) {
        }
    }
    historyRealtimeRoot = null
    historyRealtimeEventListener = null
}

internal fun setupHistoryRealtimeUpdates() {
    teardownHistoryRealtimeUpdates()
    if (!isHistoryPage()) return
    val root = document.getElementById(HtmlIds.HISTORY_REALTIME_ROOT) as? HTMLElement ?: return
    try {
        val listener: dynamic = { _: dynamic -> scheduleHistoryRealtimeReload() }
        root.asDynamic().addEventListener(HtmlEvents.SSE_MESSAGE, listener)
        historyRealtimeRoot = root
        historyRealtimeEventListener = listener
    } catch (_: Throwable) {
        // HTMX SSE may be unavailable in a non-browser test environment; degrade silently.
    }
}

internal fun historyRealtimeActiveForTest(): Boolean = historyRealtimeEventListener != null

internal fun historyRealtimeDebouncePendingForTest(): Boolean = historyRealtimeDebounceId != null
