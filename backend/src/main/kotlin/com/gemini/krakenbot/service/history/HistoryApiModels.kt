package com.gemini.krakenbot.service.history

import com.fasterxml.jackson.annotation.JsonProperty
import com.gemini.krakenbot.service.reporting.HistoryPeriodStatistics
import com.gemini.krakenbot.view.util.HistoryChartSelections
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Structured, UI-displayable rejection of History read API request parameters. */
class HistoryApiRequestException(message: String) : IllegalArgumentException(message)

enum class HistoryChartSelection(val param: String) {
    PORTFOLIO(HistoryChartSelections.PORTFOLIO),
    HOLDINGS(HistoryChartSelections.HOLDINGS),
    ALLOCATION(HistoryChartSelections.ALLOCATION),
    DEPOSITS(HistoryChartSelections.DEPOSITS),
    REWARDS(HistoryChartSelections.REWARDS),
    CHECKPOINTS(HistoryChartSelections.CHECKPOINTS),
    ;

    companion object {
        fun fromParam(raw: String?): HistoryChartSelection? =
            if (raw == null) PORTFOLIO else entries.firstOrNull { it.param.equals(raw.trim(), ignoreCase = true) }
    }
}

/** Raw shared range query parameters before validation; see HistoryRanges for the resolved contract. */
data class HistoryApiRangeParams(
    val preset: String? = null,
    val start: String? = null,
    val end: String? = null,
    val from: String? = null,
    val to: String? = null,
    val tz: String? = null,
    /**
     * Start of the selected period a cumulative series is measured from. A zoomed viewport keeps
     * the selected period's baseline, so a cumulative curve does not restart inside the zoom.
     */
    val baselineFrom: String? = null,
    /** Optional account-value component filter; activity's `asset` filter remains separate. */
    val valueAsset: String? = null,
)

/** One captured-now range resolution: the selected interval plus the effective (viewport) interval. */
data class HistoryApiWindow(
    val preset: String,
    val zone: ZoneId,
    val selectedFrom: Instant?,
    val selectedUntil: Instant,
    val from: Instant?,
    val until: Instant,
    val now: Instant,
    val baselineFrom: Instant?,
)

data class HistoryApiExportQuery<T>(val query: T, val zone: ZoneId)

data class HistoryApiRepairParams(
    val date: String? = null,
    val start: String? = null,
    val end: String? = null,
    val tz: String? = null,
)

data class HistoryApiRepairWindow(val start: Instant, val end: Instant)

data class HistoryApiRange(val fromIso: String?, val toIso: String?, val tz: String, val preset: String)

data class HistoryApiScope(
    val id: String,
    val symbols: List<String>,
    val earliestIso: String?,
    val latestIso: String?,
    val current: Boolean,
)

data class HistoryApiCoverage(
    val valuesFirstIso: String?,
    val valuesLastIso: String?,
    val tradesFirstIso: String?,
    val tradesLastIso: String?,
    val ledgersFirstIso: String?,
    val ledgersLastIso: String?,
    val incomplete: Boolean,
    val gapCount: Int,
    /** Earliest second the account's API key permits; earlier exchange history is unreachable. */
    val apiKeyHistoryFromIso: String? = null,
)

data class HistoryApiPoint(
    val t: Long,
    @get:JsonProperty("tIso") val isoTime: String,
    val v: BigDecimal?,
    val complete: Boolean,
    val status: String? = null,
    val reasons: List<String> = emptyList(),
    val dayLabel: String? = null,
)

data class HistoryApiDailyHolding(
    val asset: String,
    val quantity: String?,
    val priceUsd: String?,
    val valueUsd: String?,
    val availability: String,
    val reason: String?,
)

data class HistoryApiHoldingsAtDate(
    val day: String,
    val available: Boolean,
    val totalUsd: String?,
    val totalStatus: String,
    val generationId: String?,
    val holdings: List<HistoryApiDailyHolding>,
)

data class HistoryDailyValueExportRange(val accountIdentityDigest: String, val from: Instant?, val until: Instant)

data class HistoryDailyHoldingsExportQuery(val accountIdentityDigest: String, val day: LocalDate)

enum class HistoryApiScopeSelection { CURRENT_SCOPE, EXPLICIT_SCOPE, ACCOUNT_RECORDED }

enum class HistoryApiBoundaryKind { ASSET_SET_CHANGE, OVERLAPPING_SCOPES }

data class HistoryApiBoundary(
    val kind: HistoryApiBoundaryKind,
    val fromIso: String,
    val toIso: String,
    val scopeIds: List<String>,
)

data class HistoryApiSeriesProvenance(
    val scope: HistoryApiScope,
    val walletScopes: List<String>,
    val balanceSources: List<String>,
    val priceSources: List<String>,
    val valuationCurrency: String,
)

data class HistoryApiSeries(
    val key: String,
    val label: String,
    val unit: String,
    val points: List<HistoryApiPoint>,
    val provenance: HistoryApiSeriesProvenance? = null,
    /** Short display name for chart legends; the full label keeps the recorded period. */
    val legendLabel: String = label,
)

data class HistoryApiFullRange(
    val fromIso: String?,
    val toIso: String?,
    val pointCount: Int,
    val totalObservations: Long,
    val representative: Boolean,
    /** Start of the selected period a cumulative series is measured from. */
    val baselineFromIso: String? = null,
)

data class HistoryApiChartsResponse(
    val range: HistoryApiRange,
    val scope: HistoryApiScope,
    val coverage: HistoryApiCoverage,
    val series: List<HistoryApiSeries>,
    val fullRange: HistoryApiFullRange,
    val generationMs: Long,
    val scopeSelection: HistoryApiScopeSelection = HistoryApiScopeSelection.CURRENT_SCOPE,
    val recordedScopes: List<HistoryApiScope> = emptyList(),
    val boundaries: List<HistoryApiBoundary> = emptyList(),
    val resolution: String = "observation",
    val valuationBasis: String? = null,
    val generationId: String? = null,
    val sourceRevision: Long? = null,
    val availableValueFromIso: String? = null,
    val availableValueToIso: String? = null,
    val availableValueAssets: List<String> = emptyList(),
    val selectedValueAsset: String? = null,
)

data class HistoryApiSummaryResponse(
    val range: HistoryApiRange,
    val scope: HistoryApiScope,
    val coverage: HistoryApiCoverage,
    val statistics: HistoryPeriodStatistics,
    val generationMs: Long,
    val scopeSelection: HistoryApiScopeSelection = HistoryApiScopeSelection.CURRENT_SCOPE,
)

data class HistoryApiScopesResponse(val scopes: List<HistoryApiScope>)

data class HistoryApiActivityRow(
    val cycleId: String,
    val occurredAt: String,
    val mode: String,
    val outcome: String,
    val actions: List<String>,
)

data class HistoryApiActivitiesResponse(
    val range: HistoryApiRange,
    val items: List<HistoryApiActivityRow>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

data class HistoryApiSyncJob(
    val feed: String,
    val purpose: String,
    val state: String,
    val windowStartIso: String?,
    val windowEndIso: String?,
    val cursor: String?,
    val completedThroughIso: String?,
    val lastError: String?,
    val nextRetryAtIso: String?,
    val lastSuccessAtIso: String?,
    val updatedAtIso: String?,
)

data class HistoryApiSyncProgress(
    val feed: String,
    val recentWindowStartIso: String?,
    val recentCompletedThroughIso: String?,
    val recentLastSuccessAtIso: String?,
    val backfillState: String,
    val backfillCompletedThroughIso: String?,
    val backfillCursor: String?,
    val repairLastRunAtIso: String?,
    val lastError: String?,
    val nextRetryAtIso: String?,
    /** Verified scanned coverage of this feed; the union stays stable across rolling refreshes. */
    val coveredFromIso: String? = null,
    val coveredToIso: String? = null,
    val coveredGapCount: Int = 0,
    val targetCoverageComplete: Boolean? = null,
)

data class HistoryApiDataStatusResponse(
    val range: HistoryApiRange,
    val dataRevision: Long,
    val blockedReason: String?,
    val syncFailure: String?,
    val nextRetryAtIso: String?,
    val coverage: HistoryApiCoverage,
    val jobs: List<HistoryApiSyncJob>,
    val progress: List<HistoryApiSyncProgress>,
    val valuation: HistoryApiValuationProgress? = null,
    val tradeRecordsImported: Long? = null,
    val ledgerRecordsImported: Long? = null,
    val availableValueFromIso: String? = null,
    val availableValueToIso: String? = null,
    val availableValueDays: Long? = null,
)

data class HistoryApiValuationProgress(
    val state: String,
    val stage: String,
    val targetStartDay: String,
    val targetEndDay: String,
    val targetDays: Int,
    val balancesProcessed: Int,
    val balancesValid: Int,
    val balancesBlocked: Int,
    val requiredPriceTasks: Int?,
    val completedPriceTasks: Int,
    val completeValueDays: Int,
    val blockedValueDays: Int,
    val lastProgressAtIso: String?,
    val nextRetryAtIso: String?,
    val lastSuccessAtIso: String?,
    val lastError: String?,
    val startedAtIso: String? = null,
    val blockedValueReasons: List<String> = emptyList(),
)
