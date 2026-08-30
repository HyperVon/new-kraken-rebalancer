package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.Routes
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

internal const val HISTORY_REALTIME_DEBOUNCE_MS: Int = 450
internal const val HISTORY_REALTIME_RECONNECT_MS: Int = 4000

private var historyEventSource: dynamic = null
private var historyRealtimeDebounceId: Int? = null
private var historyRealtimeReconnectId: Int? = null
private var historyRealtimeBeforeUnloadHandler: dynamic = null

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
    historyRealtimeReconnectId?.let { window.clearTimeout(it) }
    historyRealtimeReconnectId = null
    if (historyRealtimeBeforeUnloadHandler != null) {
        val handler = historyRealtimeBeforeUnloadHandler
        try {
            window.asDynamic().removeEventListener("beforeunload", handler)
        } catch (_: Throwable) {
        }
        historyRealtimeBeforeUnloadHandler = null
    }
    try {
        historyEventSource?.close?.call(historyEventSource)
    } catch (_: Throwable) {
    }
    historyEventSource = null
}

internal fun setupHistoryRealtimeUpdates() {
    if (!isHistoryPage()) return
    teardownHistoryRealtimeUpdates()
    try {
        val url = Routes.API_STATUS_STREAM
        val es: dynamic = js("new (window.EventSource || EventSource)(url)")
        historyEventSource = es
        es.onmessage = { _: dynamic ->
            if (historyEventSource === es) {
                scheduleHistoryRealtimeReload()
            }
        }
        es.onerror = { _: dynamic ->
            if (historyEventSource === es) {
                try {
                    es.close()
                } catch (_: Throwable) {
                }
                historyEventSource = null
                historyRealtimeReconnectId?.let { window.clearTimeout(it) }
                historyRealtimeReconnectId = window.setTimeout({
                    historyRealtimeReconnectId = null
                    if (isHistoryPage()) {
                        try {
                            setupHistoryRealtimeUpdates()
                        } catch (_: Throwable) {
                        }
                    }
                }, HISTORY_REALTIME_RECONNECT_MS)
            }
        }
        // Ensure cleanup when navigating away.
        if (historyRealtimeBeforeUnloadHandler == null) {
            val handler: dynamic = { _: dynamic ->
                teardownHistoryRealtimeUpdates()
            }
            try {
                window.asDynamic().addEventListener("beforeunload", handler)
                historyRealtimeBeforeUnloadHandler = handler
            } catch (_: Throwable) {
            }
        }
    } catch (_: Throwable) {
        // EventSource unavailable in this environment (e.g. jsTest); degrade silently.
    }
}

internal fun historyRealtimeActiveForTest(): Boolean = historyEventSource != null

internal fun historyRealtimeDebouncePendingForTest(): Boolean = historyRealtimeDebounceId != null
