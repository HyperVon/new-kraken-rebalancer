package com.gemini.krakenbot.service.history

import java.time.Duration
import java.time.Instant

interface HistoryService {
    suspend fun queryValues(query: HistoryRangeQuery): HistoryPage<HistoryObservation>

    suspend fun queryChartObservations(
        sourceScope: HistorySourceScope,
        from: Instant? = null,
        until: Instant? = null,
        maxPoints: Int = HISTORY_MAX_CHART_POINTS,
    ): List<HistoryObservation>

    suspend fun queryAssetValues(query: HistoryRangeQuery): HistoryPage<HistoryAssetValue>

    suspend fun queryTrades(query: HistoryEventQuery): HistoryPage<HistoryTrade>

    suspend fun queryTradeSummary(query: HistoryEventQuery): HistoryTradeSummary

    suspend fun queryLedgers(query: HistoryEventQuery): HistoryPage<HistoryLedger>

    suspend fun queryBalanceCheckpoints(query: HistoryEventQuery): HistoryPage<HistoryBalanceCheckpoint>

    suspend fun queryFilterOptions(query: HistoryEventQuery): HistoryEventFilterOptions

    suspend fun queryDailyValues(
        accountIdentityDigest: String,
        sourceAssetScope: String,
        from: Instant? = null,
        until: Instant? = null,
        limit: Int = HISTORY_PAGE_LIMIT,
        offset: Int = 0,
    ): HistoryPage<HistoryDailyValue>

    suspend fun queryDailyAssetValues(
        accountIdentityDigest: String,
        sourceAssetScope: String,
        from: Instant? = null,
        until: Instant? = null,
        limit: Int = HISTORY_PAGE_LIMIT,
        offset: Int = 0,
    ): HistoryPage<HistoryDailyAssetValue>

    suspend fun latestObservation(sourceScope: HistorySourceScope): HistoryObservation?

    suspend fun observationAtOrBefore(
        sourceScope: HistorySourceScope,
        targetTime: Instant,
        maxAge: Duration,
    ): HistoryObservation?

    suspend fun queryCycleActivities(sourceScope: HistorySourceScope, limit: Int = 6): List<HistoryCycleActivity>

    suspend fun getSyncStatus(sourceScope: HistorySourceScope): List<HistorySyncStatus>

    suspend fun getDashboardHistory(
        sourceScope: HistorySourceScope,
        range: HistoryRangeQuery,
        baselineTime: Instant,
        baselineMaxAge: Duration,
    ): HistoryDashboardData
}

class HistoryDashboardData(
    val sourceScope: HistorySourceScope,
    val latestObservation: HistoryObservation?,
    val observations: HistoryPage<HistoryObservation>,
    val baselineObservation: HistoryObservation?,
    val recentCycleActivity: List<HistoryCycleActivity>,
    val syncStatus: List<HistorySyncStatus>,
)

class HistoryServiceImpl(private val store: HistoryStore) : HistoryService {
    override suspend fun queryValues(query: HistoryRangeQuery): HistoryPage<HistoryObservation> =
        store.queryObservations(query)

    override suspend fun queryChartObservations(
        sourceScope: HistorySourceScope,
        from: Instant?,
        until: Instant?,
        maxPoints: Int,
    ): List<HistoryObservation> = store.queryChartObservations(sourceScope, from, until, maxPoints)

    override suspend fun queryAssetValues(query: HistoryRangeQuery): HistoryPage<HistoryAssetValue> =
        store.queryAssetValues(query)

    override suspend fun queryTrades(query: HistoryEventQuery): HistoryPage<HistoryTrade> = store.queryTrades(query)

    override suspend fun queryTradeSummary(query: HistoryEventQuery): HistoryTradeSummary =
        store.queryTradeSummary(query)

    override suspend fun queryLedgers(query: HistoryEventQuery): HistoryPage<HistoryLedger> = store.queryLedgers(query)

    override suspend fun queryBalanceCheckpoints(query: HistoryEventQuery): HistoryPage<HistoryBalanceCheckpoint> =
        store.queryBalanceCheckpoints(query)

    override suspend fun queryFilterOptions(query: HistoryEventQuery): HistoryEventFilterOptions =
        store.queryFilterOptions(query)

    override suspend fun queryDailyValues(
        accountIdentityDigest: String,
        sourceAssetScope: String,
        from: Instant?,
        until: Instant?,
        limit: Int,
        offset: Int,
    ): HistoryPage<HistoryDailyValue> =
        store.queryDailyValues(accountIdentityDigest, sourceAssetScope, from, until, limit, offset)

    override suspend fun queryDailyAssetValues(
        accountIdentityDigest: String,
        sourceAssetScope: String,
        from: Instant?,
        until: Instant?,
        limit: Int,
        offset: Int,
    ): HistoryPage<HistoryDailyAssetValue> =
        store.queryDailyAssetValues(accountIdentityDigest, sourceAssetScope, from, until, limit, offset)

    override suspend fun latestObservation(sourceScope: HistorySourceScope): HistoryObservation? =
        store.latestObservation(sourceScope)

    override suspend fun observationAtOrBefore(
        sourceScope: HistorySourceScope,
        targetTime: Instant,
        maxAge: Duration,
    ): HistoryObservation? = store.observationAtOrBefore(sourceScope, targetTime, maxAge)

    override suspend fun queryCycleActivities(
        sourceScope: HistorySourceScope,
        limit: Int,
    ): List<HistoryCycleActivity> = store.queryCycleActivities(sourceScope, limit)

    override suspend fun getSyncStatus(sourceScope: HistorySourceScope): List<HistorySyncStatus> =
        sourceScope.accountIdentityDigest?.let { store.syncStatus(it) }.orEmpty()

    override suspend fun getDashboardHistory(
        sourceScope: HistorySourceScope,
        range: HistoryRangeQuery,
        baselineTime: Instant,
        baselineMaxAge: Duration,
    ): HistoryDashboardData {
        require(range.sourceScope == null || range.sourceScope == sourceScope) {
            "Dashboard History range must use the selected source scope."
        }
        val selectedRange = range.copy(sourceScope = sourceScope)
        return HistoryDashboardData(
            sourceScope = sourceScope,
            latestObservation = store.latestObservation(sourceScope),
            observations = store.queryObservations(selectedRange),
            baselineObservation = store.observationAtOrBefore(sourceScope, baselineTime, baselineMaxAge),
            recentCycleActivity = store.queryCycleActivities(sourceScope, 6),
            syncStatus = sourceScope.accountIdentityDigest?.let { store.syncStatus(it) }.orEmpty(),
        )
    }
}
