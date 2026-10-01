package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.api.SyncProgressResponse
import com.gemini.krakenbot.model.TimeRange
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlAttrs
import com.gemini.krakenbot.view.util.HtmlEvents
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.withRange
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import kotlin.js.Promise
import com.gemini.krakenbot.view.util.HtmlQueries.TIME_RANGE_BTNS as TIME_RANGE_BTNS_QUERY

private var syncIntervalId: Int? = null

internal fun setupSyncProgressAndLoad() {
    checkSyncProgress().then { isDone ->
        if (isDone) {
            consumeHistoryLoad(loadHistoryAfterSync())
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
                            consumeHistoryLoad(loadHistoryAfterSync())
                        }
                    }
                }, PrecisionConstants.SYNC_POLL_INTERVAL_MS)
        }
    }

    val buttons = document.querySelectorAll(TIME_RANGE_BTNS_QUERY)
    repeat(buttons.length) { i ->
        val btn = buttons.item(i) as? HTMLElement
        btn?.addEventListener(HtmlEvents.CLICK, {
            val range = btn.getAttribute(HtmlAttrs.DATA_RANGE) ?: TimeRange.THIRTY_DAYS.key
            HistoryViewPrefs.markCurrentViewModified()
            syncTimeRangeButtons(range)
            consumeHistoryLoad(loadAll(range))
        })
    }

    val checkbox = document.getElementById(HtmlIds.SHOW_DRY_RUN_CHECKBOX) as? HTMLInputElement
    checkbox?.addEventListener(HtmlEvents.CHANGE, {
        if (!historyTradesAvailable) return@addEventListener
        HistoryViewPrefs.markCurrentViewModified()
        rerenderHistoryTradesForDryRunFilter(checkbox.checked)
        try {
            HistorySessionState.save()
        } catch (_: Throwable) {
        }
    })

    // Persist session on page hide/unload so legend toggles and other ephemeral UI
    // captured from live charts are not lost when navigating away.
    try {
        window.addEventListener(HtmlEvents.BEFORE_UNLOAD, {
            try {
                HistorySessionState.save()
            } catch (_: Throwable) {
            }
        })
        document.addEventListener(HtmlEvents.VISIBILITY_CHANGE, {
            if (document.asDynamic().visibilityState == HtmlEvents.VISIBILITY_HIDDEN) {
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
    .then { res ->
        if (!res.ok) {
            throw Throwable("${ViewText.HTTP_FETCH_FAILED} ${res.status})")
        }
        res.json()
    }

/** Attach a rejection handler to History loads started by UI callbacks that cannot return a Promise. */
internal fun consumeHistoryLoad(promise: Promise<Unit>) {
    promise.`catch` { error: dynamic ->
        console.error("Error loading History data", error)
    }
}

private fun loadGroup(
    requestGeneration: Long,
    url: String,
    onError: ((Throwable) -> Unit)? = null,
    onSuccess: (raw: dynamic) -> Unit,
): Promise<Unit> = fetchJSON(url)
    .then { raw: dynamic ->
        if (requestGeneration == historyLoadGeneration) onSuccess(raw)
    }
    .`catch` { e: dynamic ->
        val throwable = e as? Throwable ?: Throwable(e?.toString())
        console.error("Error loading history data from $url", e)
        if (requestGeneration == historyLoadGeneration) {
            onError?.invoke(throwable)
        }
        throw throwable
    }

internal fun loadAll(range: String): Promise<Unit> {
    currentRange = range
    val requestGeneration = ++historyLoadGeneration
    clearHistoryCoreLoadError()
    clearHistoryCoreData()

    val showDryRun = (document.getElementById(HtmlIds.SHOW_DRY_RUN_CHECKBOX) as? HTMLInputElement)?.checked ?: true

    // Each dataset renders as soon as its own response resolves. If one core endpoint
    // fails, clear only that group's previous-range data and leave other successful or
    // still-pending groups alone. The selected range remains the range all groups target.
    val corePromises = arrayOf(
        loadGroup(
            requestGeneration,
            Routes.API_HISTORY_SNAPSHOTS.withRange(range),
            onError = {
                clearSnapshotCharts()
                showHistoryCoreLoadError()
            },
        ) { raw ->
            val snapshots = parsePortfolioSnapshots(raw)
            buildPortfolioValueChart(snapshots)
            buildAssetHoldingsChart(snapshots)
            buildAllocationDriftChart(snapshots)
        },
        loadGroup(
            requestGeneration,
            Routes.API_HISTORY_TRADES.withRange(range),
            onError = {
                historyTradesAvailable = false
                allTrades = emptyList()
                document.getElementById(HtmlIds.TRADE_TABLE_BODY)?.innerHTML = ""
                clearChart(HtmlIds.CUMULATIVE_NET_CASH_FLOW_CHART)
                showHistoryCoreLoadError()
            },
        ) { raw ->
            val trades = parseTradeRecords(raw)
            allTrades = trades
            historyTradesAvailable = true
            renderTradeTable(trades)
            buildCumulativeNetCashFlowChart(trades, showDryRun)
        },
        loadGroup(
            requestGeneration,
            Routes.API_HISTORY_STATS.withRange(range),
            onError = {
                clearHistoryStats()
                showHistoryCoreLoadError()
            },
        ) { raw ->
            updateStats(parseHistoryStats(raw))
        },
        loadGroup(
            requestGeneration,
            Routes.API_HISTORY_REWARDS.withRange(range),
            onError = {
                clearChart(HtmlIds.REWARDS_CHART)
                document.getElementById(HtmlIds.REWARDS_TOTAL)?.textContent = ViewText.PLACEHOLDER_DASHES
                showHistoryCoreLoadError()
            },
        ) { raw ->
            buildRewardsChart(parseRewardsOverTime(raw))
        },
    )

    // The comparison chart loads independently: it shows its own error state on
    // failure and resolves regardless, so it cannot reject the core range load.
    showComparisonLoading()
    loadGroup(
        requestGeneration,
        Routes.API_HISTORY_COMPARISON.withRange(range),
        onError = { showComparisonFetchError() },
    ) { raw ->
        buildRebalancerComparisonChart(parseRebalancerComparison(raw))
    }.`catch` { _: dynamic ->
        // loadGroup already logged the failure and showed the visible error state
        // for the current generation; a stale-generation failure must NOT reject
        // (this chain is detached), so swallow it here unconditionally.
    }

    return Promise.all(corePromises)
        .then {
            if (requestGeneration == historyLoadGeneration) loadedRange = range
        }
        .`catch` { error: dynamic ->
            val throwable = error as? Throwable ?: Throwable(error?.toString())
            if (requestGeneration == historyLoadGeneration) {
                throw throwable
            }
        }
}

private fun clearHistoryCoreData() {
    clearSnapshotCharts()
    historyTradesAvailable = false
    allTrades = emptyList()
    document.getElementById(HtmlIds.TRADE_TABLE_BODY)?.innerHTML = ""
    clearChart(HtmlIds.CUMULATIVE_NET_CASH_FLOW_CHART)
    clearHistoryStats()
    clearChart(HtmlIds.REWARDS_CHART)
    document.getElementById(HtmlIds.REWARDS_TOTAL)?.textContent = ViewText.PLACEHOLDER_DASHES
}

private fun clearSnapshotCharts() {
    clearChart(HtmlIds.PORTFOLIO_VALUE_CHART)
    clearChart(HtmlIds.ASSET_HOLDINGS_CHART)
    clearChart(HtmlIds.ALLOCATION_DRIFT_CHART)
}

private fun clearHistoryStats() {
    document.getElementById(HtmlIds.STAT_ATH_TITLE)?.textContent =
        if (currentRange == TimeRange.ALL.key) ViewText.HISTORY_ALL_TIME_HIGH else ViewText.PERIOD_HIGH
    listOf(
        HtmlIds.STAT_ATH,
        HtmlIds.STAT_TOTAL_TRADES,
        HtmlIds.STAT_TOTAL_VOLUME,
        HtmlIds.STAT_TOTAL_FEES,
        HtmlIds.STAT_AVG_FEE_RATE,
        HtmlIds.STAT_AVG_SLIPPAGE,
    ).forEach { id -> document.getElementById(id)?.textContent = ViewText.PLACEHOLDER_DASHES }
}

private fun clearHistoryCoreLoadError() {
    document.getElementById(HtmlIds.HISTORY_CORE_LOAD_ERROR)?.let { error ->
        error.textContent = ""
        error.classList.remove(CssClass.Utility.Visible.value)
        error.classList.add(CssClass.Utility.Hidden.value)
    }
}

private fun showHistoryCoreLoadError() {
    document.getElementById(HtmlIds.HISTORY_CORE_LOAD_ERROR)?.let { error ->
        error.textContent = ViewText.HISTORY_CORE_LOAD_ERROR
        error.classList.remove(CssClass.Utility.Hidden.value)
        error.classList.add(CssClass.Utility.Visible.value)
    }
}

internal fun rerenderHistoryTradesForDryRunFilter(includeDryRun: Boolean) {
    if (!historyTradesAvailable) return
    renderTradeTable(allTrades)
    buildCumulativeNetCashFlowChart(allTrades, includeDryRun)
}

internal fun checkSyncProgress(): Promise<Boolean> = fetchJSON(Routes.API_HISTORY_SYNC_PROGRESS)
    .then { rawStatus: dynamic ->
        val status = parseSyncProgressResponse(rawStatus)
        val banner = document.getElementById(HtmlIds.SYNC_PROGRESS_BANNER) as? HTMLElement
        banner == null || if (!status.seeded) {
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
        } else if (isInceptionRecoveryVisible(status)) {
            banner.classList.remove(CssClass.Utility.Hidden.value)
            val progress = recoveryProgress(status)
            val bar = document.getElementById(HtmlIds.SYNC_PROGRESS_BAR) as? HTMLElement
            val text = document.getElementById(HtmlIds.SYNC_PROGRESS_TEXT) as? HTMLElement
            if (bar != null) bar.style.width = "${progress.second}%"
            if (text != null) text.textContent = progress.first
            status.recoveryStatus != SyncProgressResponse.RECOVERY_IN_PROGRESS
        } else {
            banner.classList.add(CssClass.Utility.Hidden.value)
            true
        }
    }.`catch` { e ->
        console.error("Error checking sync progress", e)
        false
    }

private fun isInceptionRecoveryVisible(status: SyncProgressResponse): Boolean = status.recoveryStatus in setOf(
    SyncProgressResponse.RECOVERY_IN_PROGRESS,
    SyncProgressResponse.RECOVERY_FAILED,
    SyncProgressResponse.RECOVERY_AMBIGUOUS,
    SyncProgressResponse.RECOVERY_NO_BOT_EVIDENCE,
    SyncProgressResponse.RECOVERY_BASELINE_UNAVAILABLE,
    SyncProgressResponse.RECOVERY_UNAVAILABLE,
)

private fun recoveryProgress(status: SyncProgressResponse): Pair<String, Int> {
    val streams = listOf(
        dynamicNumber(status.recoveryTradeOffset) to dynamicNumber(status.recoveryTradeTotal),
        dynamicNumber(status.recoveryLedgerOffset) to dynamicNumber(status.recoveryLedgerTotal),
    )
    val known = streams.mapNotNull { (offset, total) ->
        if (offset != null && total != null && total > 0.0) {
            (offset / total).coerceIn(0.0, 1.0)
        } else {
            null
        }
    }
    val pct = if (known.isEmpty()) 0 else (known.minOrNull()!! * PrecisionConstants.TOTAL_ALLOCATION_PERCENTAGE).toInt()
    val trade = "${status.recoveryTradeOffset.ifBlank { "0" }} / ${status.recoveryTradeTotal.ifBlank { "?" }}"
    val ledger = "${status.recoveryLedgerOffset.ifBlank { "0" }} / ${status.recoveryLedgerTotal.ifBlank { "?" }}"
    val reason = status.recoveryReason?.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
    return "${ViewText.INCEPTION_RECOVERY_PROGRESS_PREFIX}: trades $trade; ledgers $ledger$reason" to pct
}
