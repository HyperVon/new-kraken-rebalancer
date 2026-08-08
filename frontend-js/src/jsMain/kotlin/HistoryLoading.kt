package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlEvents
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.withRange
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import kotlin.js.Promise
import kotlin.js.json
import com.gemini.krakenbot.view.util.HtmlQueries.TIME_RANGE_BTNS as TIME_RANGE_BTNS_QUERY

private var syncIntervalId: Int? = null

internal fun setupSyncProgressAndLoad() {
    checkSyncProgress().then { isDone ->
        if (isDone) {
            loadHistoryAfterSync()
        } else {
            syncIntervalId?.let { window.clearInterval(it) }
            syncIntervalId =
                window.setInterval({
                    if (document.getElementById(HtmlIds.SYNC_PROGRESS_BANNER) == null) {
                        syncIntervalId?.let { window.clearInterval(it) }
                        return@setInterval
                    }
                    checkSyncProgress().then { done ->
                        if (done) {
                            syncIntervalId?.let { window.clearInterval(it) }
                            loadHistoryAfterSync()
                        }
                    }
                }, PrecisionConstants.SYNC_POLL_INTERVAL_MS)
        }
    }

    val buttons = document.querySelectorAll(TIME_RANGE_BTNS_QUERY)
    for (i in 0 until buttons.length) {
        val btn = buttons.item(i) as? HTMLElement
        btn?.addEventListener(HtmlEvents.CLICK, {
            val range = btn.getAttribute(HtmlAttrs.DATA_RANGE) ?: TimeRange.THIRTY_DAYS.key
            HistoryViewPrefs.markCurrentViewModified()
            syncTimeRangeButtons(range)
            loadAll(range)
        })
    }

    val checkbox = document.getElementById(HtmlIds.SHOW_DRY_RUN_CHECKBOX) as? HTMLInputElement
    checkbox?.addEventListener(HtmlEvents.CHANGE, {
        HistoryViewPrefs.markCurrentViewModified()
        renderTradeTable(allTrades)
        buildCumulativeNetCashFlowChart(allTrades, checkbox.checked)
        try {
            HistorySessionState.save()
        } catch (_: Throwable) {
        }
    })

    // Persist session on page hide/unload so legend toggles and other ephemeral UI
    // captured from live charts are not lost when navigating away.
    try {
        window.addEventListener("beforeunload", {
            try {
                HistorySessionState.save()
            } catch (_: Throwable) {
            }
        })
        document.addEventListener("visibilitychange", {
            if (document.asDynamic().visibilityState == "hidden") {
                try {
                    HistorySessionState.save()
                } catch (_: Throwable) {
                }
            }
        })
    } catch (_: Throwable) {
    }
}

internal fun loadHistoryAfterSync(): Promise<Unit> {
    val session = HistorySessionState.load()
    if (session != null) {
        // Restore session before deciding default vs currentRange
        HistorySessionState.restoreIfNeeded()
        return loadAll(session.range)
    }
    return if (HistoryViewPrefs.hasUserInteracted()) {
        loadAll(historyCurrentRange())
    } else {
        HistoryViewPrefs.applyDefaultView()
    }
}

private fun fetchJSON(url: String): Promise<dynamic> = window
    .fetch(url)
    .then { res -> res.json() }

private fun fetchRanged(vararg routes: String, range: String): Array<Promise<dynamic>> = routes.map { route ->
    fetchJSON(route.withRange(range))
}.toTypedArray()

internal fun loadAll(range: String): Promise<Unit> {
    currentRange = range
    val requestGeneration = ++historyLoadGeneration

    val promises =
        fetchRanged(
            Routes.API_HISTORY_SNAPSHOTS,
            Routes.API_HISTORY_TRADES,
            Routes.API_HISTORY_STATS,
            Routes.API_HISTORY_COMPARISON,
            Routes.API_HISTORY_REWARDS,
            range = range,
        )

    return Promise.all(promises).then { results ->
        if (requestGeneration != historyLoadGeneration) return@then
        val snapshots = parsePortfolioSnapshots(results[0])
        val trades = parseTradeRecords(results[1])
        val stats = parseHistoryStats(results[2])
        val comparison = parseRebalancerComparison(results[3])
        val rewards = parseRewardsOverTime(results[4])
        loadedRange = range
        currentRange = range
        allTrades = trades
        buildPortfolioValueChart(snapshots)
        buildAssetHoldingsChart(snapshots)
        buildAllocationDriftChart(snapshots)
        val showDryRun = (document.getElementById(HtmlIds.SHOW_DRY_RUN_CHECKBOX) as? HTMLInputElement)?.checked ?: true
        buildCumulativeNetCashFlowChart(trades, showDryRun)
        buildRebalancerComparisonChart(comparison)
        buildRewardsChart(rewards)
        renderTradeTable(trades)
        updateStats(stats)
    }.`catch` { error ->
        if (requestGeneration == historyLoadGeneration) {
            currentRange = loadedRange
            syncTimeRangeButtons(currentRange)
            historyRollbackPresetVisibility()
            throw error
        }
    }
}

internal fun checkSyncProgress(): Promise<Boolean> = fetchJSON(Routes.API_HISTORY_SYNC_PROGRESS)
    .then { rawStatus: dynamic ->
        val status = parseSyncProgressResponse(rawStatus)
        val banner = document.getElementById(HtmlIds.SYNC_PROGRESS_BANNER) as? HTMLElement
        banner == null || if (status.seeded) {
            banner.classList.add(CssClass.Utility.Hidden.value)
            true
        } else {
            banner.classList.remove(CssClass.Utility.Hidden.value)
            val offset = dynamicNumber(status.offset) ?: 0.0
            val total = dynamicNumber(status.total) ?: 0.0
            var pct = 0
            if (total > 0.0) {
                pct =
                    (offset / total * PrecisionConstants.TOTAL_ALLOCATION_PERCENTAGE).toInt().coerceAtMost(
                        PrecisionConstants.HUNDRED_INT,
                    )
            }

            val bar = document.getElementById(HtmlIds.SYNC_PROGRESS_BAR) as? HTMLElement
            val text = document.getElementById(HtmlIds.SYNC_PROGRESS_TEXT) as? HTMLElement

            if (bar != null) bar.style.width = "$pct%"
            if (text != null) {
                val offsetLabel = offset.asDynamic().toLocaleString()
                val totalLabel = total.asDynamic().toLocaleString()
                text.textContent = "$offsetLabel / $totalLabel ($pct%)"
            }

            false
        }
    }.`catch` { e ->
        console.error("Error checking sync progress", e)
        false
    }
