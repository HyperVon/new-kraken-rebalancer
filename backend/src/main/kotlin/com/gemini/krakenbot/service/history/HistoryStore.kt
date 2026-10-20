package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.model.ExchangeHistoryPage
import java.time.Instant

/** Persistence boundary for the one active History database. */
interface HistoryStore {
    suspend fun appendObservation(observation: HistoryObservation): Boolean

    suspend fun appendCycleActivity(activity: HistoryCycleActivity): Boolean

    suspend fun appendCycle(observation: HistoryObservation?, activity: HistoryCycleActivity): Boolean

    suspend fun queryObservations(query: HistoryRangeQuery): HistoryPage<HistoryObservation>

    /** Bounded representative samples from the complete selected time range, oldest to newest. */
    suspend fun queryChartObservations(
        sourceScope: HistorySourceScope,
        from: Instant? = null,
        until: Instant? = null,
        maxPoints: Int = HISTORY_MAX_CHART_POINTS,
    ): List<HistoryObservation>

    suspend fun queryAssetValues(query: HistoryRangeQuery): HistoryPage<HistoryAssetValue>

    suspend fun latestObservation(sourceScope: HistorySourceScope): HistoryObservation?

    suspend fun observationAtOrBefore(
        sourceScope: HistorySourceScope,
        targetTime: Instant,
        maxAge: java.time.Duration,
    ): HistoryObservation?

    suspend fun queryCycleActivities(sourceScope: HistorySourceScope, limit: Int = 6): List<HistoryCycleActivity>

    suspend fun queryTrades(query: HistoryEventQuery): HistoryPage<HistoryTrade>

    suspend fun queryLedgers(query: HistoryEventQuery): HistoryPage<HistoryLedger>

    suspend fun queryBalanceCheckpoints(query: HistoryEventQuery): HistoryPage<HistoryBalanceCheckpoint>

    suspend fun queryFilterOptions(query: HistoryEventQuery): HistoryEventFilterOptions

    suspend fun beginSyncWindow(window: HistorySyncWindow): HistorySyncStatus

    suspend fun syncStatus(accountIdentityDigest: String): List<HistorySyncStatus>

    /** Monotonic revision advanced by committed observations, cycle activities, and imported records. */
    suspend fun currentDataRevision(): Long

    /** Durable per-account/feed/purpose coordinator job state, including frozen windows and cursors. */
    suspend fun loadSyncJobs(accountIdentityDigest: String): List<HistorySyncJob>

    suspend fun upsertSyncJob(job: HistorySyncJob)

    /** Account/feed recent coverage and older import progress for Data status. */
    suspend fun syncProgress(accountIdentityDigest: String): List<HistorySyncProgressSummary>

    /** Stable paged activity read for the recent-activity table. */
    suspend fun queryActivityPage(query: HistoryActivityQuery): HistoryPage<HistoryCycleActivity>

    /** Stored asset-set scopes with recorded value dates for the scope selector. */
    suspend fun listRecordedScopes(accountIdentityDigest: String): List<HistoryRecordedScope>

    /** Honest value-record coverage for one scope; exchange feeds are reported separately. */
    suspend fun valuesCoverage(sourceScope: HistorySourceScope): HistoryValuesCoverage

    suspend fun commitTradePage(
        request: HistoryPageRequest,
        page: ExchangeHistoryPage<com.gemini.krakenbot.model.ExchangeTradeHistoryEntry>,
    ): HistorySyncStatus

    suspend fun commitLedgerPage(
        request: HistoryPageRequest,
        page: ExchangeHistoryPage<com.gemini.krakenbot.model.ExchangeLedgerHistoryEntry>,
    ): HistorySyncStatus

    suspend fun pauseSync(accountIdentityDigest: String, feed: HistoryFeed, scanId: String)

    suspend fun failSync(accountIdentityDigest: String, feed: HistoryFeed, scanId: String, reason: String)

    suspend fun hasLedgerCoverage(accountIdentityDigest: String, startSec: Long, endSec: Long): Boolean

    suspend fun queryLedgerCoverageWindows(
        accountIdentityDigest: String,
        overlappingStartSec: Long,
        overlappingEndSec: Long,
    ): List<HistoryLedgerCoverageWindow>

    suspend fun queryDailyCheckpointInputs(
        accountIdentityDigest: String,
        fromSec: Long,
        throughSec: Long,
        limit: Int = MAX_DAILY_SOURCE_CHECKPOINTS,
    ): List<HistoryBalanceCheckpoint>

    suspend fun saveDailyPrices(prices: List<HistoryDailyPrice>)

    suspend fun queryDailyPrices(
        accountIdentityDigest: String,
        rawAssetKey: String,
        fromSec: Long,
        throughSec: Long,
    ): List<HistoryDailyPrice>

    suspend fun saveDailyValues(values: List<HistoryDailyValue>)

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
}

const val MAX_DAILY_SOURCE_CHECKPOINTS = 100_000
