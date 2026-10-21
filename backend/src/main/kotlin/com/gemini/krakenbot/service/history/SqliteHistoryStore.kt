package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.CANONICAL_BY_KRAKEN_ALIAS
import com.gemini.krakenbot.model.EARN_ASSET_SUFFIXES
import com.gemini.krakenbot.model.ExchangeHistoryPage
import com.gemini.krakenbot.model.ExchangeLedgerHistoryEntry
import com.gemini.krakenbot.model.ExchangeTradeHistoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.Locale

class SqliteHistoryStore(
    private val database: HistoryDatabase,
    private val nowProvider: () -> Instant = Instant::now,
    private val maxPagesPerScan: Int = HISTORY_MAX_SYNC_PAGES,
) : HistoryStore {
    init {
        require(maxPagesPerScan > 0)
    }
    override suspend fun appendObservation(observation: HistoryObservation): Boolean = withContext(Dispatchers.IO) {
        database.connect().use { connection ->
            connection.inTransaction {
                val inserted = insertObservation(connection, observation)
                if (inserted) bumpDataRevision(connection)
                inserted
            }
        }
    }

    override suspend fun appendCycleActivity(activity: HistoryCycleActivity): Boolean = withContext(Dispatchers.IO) {
        database.connect().use { connection ->
            connection.inTransaction {
                val inserted = insertCycleActivity(connection, activity)
                if (inserted) bumpDataRevision(connection)
                inserted
            }
        }
    }

    override suspend fun appendCycle(observation: HistoryObservation?, activity: HistoryCycleActivity): Boolean =
        withContext(Dispatchers.IO) {
            require(observation == null || observation.cycleId == activity.cycleId)
            require(observation == null || observation.sourceScope == activity.sourceScope)
            database.connect().use { connection ->
                connection.inTransaction {
                    val observationInserted = observation?.let { insertObservation(connection, it) } ?: false
                    val activityInserted = insertCycleActivity(connection, activity)
                    if (observationInserted || activityInserted) bumpDataRevision(connection)
                    observationInserted || activityInserted
                }
            }
        }

    override suspend fun queryObservations(query: HistoryRangeQuery): HistoryPage<HistoryObservation> =
        withContext(Dispatchers.IO) {
            database.connect().use { connection -> queryObservations(connection, query) }
        }

    override suspend fun queryChartObservations(
        sourceScope: HistorySourceScope,
        from: Instant?,
        until: Instant?,
        maxPoints: Int,
    ): List<HistoryObservation> = withContext(Dispatchers.IO) {
        require(from == null || until == null || from <= until)
        require(maxPoints in 2..HISTORY_MAX_CHART_POINTS)
        val perBucketSamples = if (maxPoints >= 6) 6 else 5
        val bucketCount = (maxPoints / perBucketSamples).coerceAtLeast(1)
        val includeIncompleteSamples = maxPoints >= 3
        val includeLastIncompleteSample = maxPoints >= 6
        val includeExtrema = maxPoints >= 5
        val incompleteClause = if (includeIncompleteSamples) {
            "OR (status = 'INCOMPLETE' AND first_incomplete_in_bucket = 1) " +
                if (includeLastIncompleteSample) {
                    "OR (status = 'INCOMPLETE' AND last_incomplete_in_bucket = 1) "
                } else {
                    ""
                }
        } else {
            ""
        }
        val extremaClause = if (includeExtrema) {
            "OR min_value_in_bucket = 1 OR max_value_in_bucket = 1 "
        } else {
            ""
        }
        val filter = observationFilter(
            HistoryRangeQuery(from = from, until = until, sourceScope = sourceScope, limit = HISTORY_MAX_PAGE_LIMIT),
        )
        database.connect().use { connection ->
            connection.prepareStatement(
                "WITH filtered AS (" +
                    "SELECT history_observations.*, ROW_NUMBER() OVER " +
                    "(ORDER BY observed_at_ns, observation_id) AS sample_order, COUNT(*) OVER () AS row_count, " +
                    "MIN(observed_at_ns) OVER () AS first_time_ns, MAX(observed_at_ns) OVER () AS last_time_ns " +
                    "FROM history_observations ${filter.where}" +
                    "), bucketed AS (" +
                    "SELECT filtered.*, CASE WHEN row_count <= ? THEN sample_order " +
                    "WHEN last_time_ns = first_time_ns THEN " +
                    "CAST((sample_order - 1) * ? / row_count AS INTEGER) ELSE " +
                    "CAST((observed_at_ns - first_time_ns) * 1.0 * ? / " +
                    "(last_time_ns - first_time_ns + 1) AS INTEGER) END AS bucket_id " +
                    "FROM filtered WHERE row_count > 0" +
                    "), ranked AS (" +
                    "SELECT bucketed.*, ROW_NUMBER() OVER (PARTITION BY bucket_id " +
                    "ORDER BY sample_order) AS first_in_bucket, " +
                    "ROW_NUMBER() OVER (PARTITION BY bucket_id ORDER BY sample_order DESC) AS last_in_bucket, " +
                    "ROW_NUMBER() OVER (PARTITION BY bucket_id ORDER BY " +
                    "CASE WHEN status = 'INCOMPLETE' THEN 0 ELSE 1 END, sample_order) AS first_incomplete_in_bucket, " +
                    "ROW_NUMBER() OVER (PARTITION BY bucket_id ORDER BY " +
                    "CASE WHEN status = 'INCOMPLETE' THEN 0 ELSE 1 END, " +
                    "sample_order DESC) AS last_incomplete_in_bucket, " +
                    "ROW_NUMBER() OVER (PARTITION BY bucket_id ORDER BY " +
                    "CASE WHEN total_usd IS NULL THEN 1 ELSE 0 END, " +
                    "CAST(total_usd AS NUMERIC), sample_order) AS min_value_in_bucket, " +
                    "ROW_NUMBER() OVER (PARTITION BY bucket_id ORDER BY " +
                    "CASE WHEN total_usd IS NULL THEN 1 ELSE 0 END, " +
                    "CAST(total_usd AS NUMERIC) DESC, sample_order) AS max_value_in_bucket " +
                    "FROM bucketed" +
                    ") SELECT * FROM ranked WHERE first_in_bucket = 1 OR last_in_bucket = 1 " +
                    incompleteClause + extremaClause +
                    "ORDER BY observed_at_ns, observation_id",
            ).use { statement ->
                bindValues(statement, filter.values)
                statement.setInt(filter.values.size + 1, maxPoints)
                statement.setInt(filter.values.size + 2, bucketCount)
                statement.setInt(filter.values.size + 3, bucketCount)
                val headers = statement.executeQuery().use { resultSet ->
                    buildList { while (resultSet.next()) add(resultSet.toObservationHeader()) }
                }
                headers.loadObservationAssets(connection)
            }
        }
    }

    override suspend fun queryAssetValues(query: HistoryRangeQuery): HistoryPage<HistoryAssetValue> =
        withContext(Dispatchers.IO) {
            database.connect().use { connection -> queryAssetValues(connection, query) }
        }

    override suspend fun latestObservation(sourceScope: HistorySourceScope): HistoryObservation? =
        withContext(Dispatchers.IO) {
            database.connect().use { connection ->
                val rows = queryObservations(connection, HistoryRangeQuery(sourceScope = sourceScope, limit = 1))
                rows.items.firstOrNull()
            }
        }

    override suspend fun observationAtOrBefore(
        sourceScope: HistorySourceScope,
        targetTime: Instant,
        maxAge: Duration,
    ): HistoryObservation? = withContext(Dispatchers.IO) {
        require(!maxAge.isNegative) { "Baseline maximum age cannot be negative." }
        database.connect().use { connection ->
            val header = connection.prepareStatement(
                "SELECT * FROM history_observations WHERE source_key = ? AND observed_at_ns <= ? " +
                    "ORDER BY observed_at_ns DESC LIMIT 1",
            ).use { statement ->
                statement.setString(1, sourceScope.sourceKey)
                statement.setLong(2, targetTime.toEpochNanos())
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.toObservationHeader() else null
                }
            } ?: return@use null
            if (Duration.between(header.observedAt, targetTime) > maxAge) return@use null
            listOf(header).loadObservationAssets(connection).single()
        }
    }

    override suspend fun queryCycleActivities(sourceScope: HistorySourceScope, limit: Int): List<HistoryCycleActivity> =
        withContext(Dispatchers.IO) {
            require(limit in 1..MAX_CYCLE_ACTIVITY_LIMIT)
            database.connect().use { connection ->
                connection.prepareStatement(
                    "SELECT * FROM history_cycle_activity WHERE source_key = ? " +
                        "ORDER BY occurred_at_ns DESC LIMIT ?",
                ).use { statement ->
                    statement.setString(1, sourceScope.sourceKey)
                    statement.setInt(2, limit)
                    statement.executeQuery().use { resultSet ->
                        buildList {
                            while (resultSet.next()) add(resultSet.toCycleActivity())
                        }
                    }
                }
            }
        }

    override suspend fun queryTrades(query: HistoryEventQuery): HistoryPage<HistoryTrade> =
        withContext(Dispatchers.IO) {
            database.connect().use { connection -> connection.inTransaction { queryTrades(connection, query) } }
        }

    override suspend fun queryLedgers(query: HistoryEventQuery): HistoryPage<HistoryLedger> =
        withContext(Dispatchers.IO) {
            database.connect().use { connection -> connection.inTransaction { queryLedgers(connection, query) } }
        }

    override suspend fun queryBalanceCheckpoints(query: HistoryEventQuery): HistoryPage<HistoryBalanceCheckpoint> =
        withContext(Dispatchers.IO) {
            database.connect().use { connection -> queryBalanceCheckpoints(connection, query) }
        }

    override suspend fun queryFilterOptions(query: HistoryEventQuery): HistoryEventFilterOptions {
        if (query.accountIdentityDigest == null) return HistoryEventFilterOptions()
        return withContext(Dispatchers.IO) {
            database.connect().use { connection ->
                val assets = listOf(
                    queryDistinctOptionValues(connection, "history_trades", "base_asset", "time_ns", query),
                    queryDistinctOptionValues(connection, "history_trades", "quote_currency", "time_ns", query),
                    queryDistinctOptionValues(connection, "history_ledgers", "asset", "time_ns", query),
                    queryDistinctOptionValues(
                        connection,
                        "history_balance_checkpoints",
                        "raw_asset_key",
                        "observed_at_ns",
                        query,
                    ),
                ).flatten().distinct().sorted()
                val pairs = queryDistinctOptionValues(connection, "history_trades", "pair", "time_ns", query)
                val types = listOf(
                    queryDistinctOptionValues(connection, "history_trades", "type", "time_ns", query),
                    queryDistinctOptionValues(connection, "history_ledgers", "type", "time_ns", query),
                ).flatten().distinct().sorted()
                HistoryEventFilterOptions(assets = assets, pairs = pairs, types = types)
            }
        }
    }

    override suspend fun beginSyncWindow(window: HistorySyncWindow): HistorySyncStatus = withContext(Dispatchers.IO) {
        requireDigest(window.accountIdentityDigest)
        require(window.startSec <= window.endSec)
        database.connect().use { connection ->
            connection.inTransaction {
                val active = readSyncStatus(connection, window.accountIdentityDigest, window.feed)
                if (active != null && active.state in setOf(HistorySyncState.RUNNING, HistorySyncState.PAUSED) &&
                    active.windowStart?.epochSecond == window.startSec && active.windowEnd?.epochSecond == window.endSec
                ) {
                    if (active.state == HistorySyncState.PAUSED) {
                        updateSyncState(
                            connection,
                            window.accountIdentityDigest,
                            window.feed,
                            active.scanId,
                            HistorySyncState.RUNNING,
                            null,
                        )
                    }
                    return@inTransaction active.copy(state = HistorySyncState.RUNNING, lastError = null)
                }
                val startedAt = nowProvider()
                connection.prepareStatement(
                    "INSERT INTO history_sync_windows(scan_id, account_identity_digest, feed, start_sec, end_sec, " +
                        "state, started_at_ns) VALUES (?, ?, ?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, window.scanId)
                    statement.setString(2, window.accountIdentityDigest)
                    statement.setString(3, window.feed.name)
                    statement.setLong(4, window.startSec)
                    statement.setLong(5, window.endSec)
                    statement.setString(6, HistorySyncState.RUNNING.name)
                    statement.setLong(7, startedAt.toEpochNanos())
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO history_sync_status(account_identity_digest, feed, scan_id, state, " +
                        "window_start_sec, " +
                        "window_end_sec, next_cursor, fetched_count, stored_count, known_total, " +
                        "last_page_at_ns, last_error) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, NULL, NULL, NULL) " +
                        "ON CONFLICT(account_identity_digest, feed) DO UPDATE SET scan_id = excluded.scan_id, " +
                        "state = excluded.state, window_start_sec = excluded.window_start_sec, " +
                        "window_end_sec = excluded.window_end_sec, next_cursor = excluded.next_cursor, " +
                        "fetched_count = 0, " +
                        "stored_count = 0, known_total = NULL, " +
                        "last_page_at_ns = NULL, last_error = NULL",
                ).use { statement ->
                    statement.setString(1, window.accountIdentityDigest)
                    statement.setString(2, window.feed.name)
                    statement.setString(3, window.scanId)
                    statement.setString(4, HistorySyncState.RUNNING.name)
                    statement.setLong(5, window.startSec)
                    statement.setLong(6, window.endSec)
                    statement.setString(7, window.cursor)
                    statement.executeUpdate()
                }
                readSyncStatus(connection, window.accountIdentityDigest, window.feed)
                    ?: error("History sync status disappeared during initialization.")
            }
        }
    }

    override suspend fun syncStatus(accountIdentityDigest: String): List<HistorySyncStatus> =
        withContext(Dispatchers.IO) {
            requireDigest(accountIdentityDigest)
            database.connect().use { connection ->
                HistoryFeed.entries.mapNotNull { feed -> readSyncStatus(connection, accountIdentityDigest, feed) }
            }
        }

    override suspend fun commitTradePage(
        request: HistoryPageRequest,
        page: ExchangeHistoryPage<ExchangeTradeHistoryEntry>,
    ): HistorySyncStatus = withContext(Dispatchers.IO) {
        require(request.feed == HistoryFeed.TRADES)
        database.connect().use { connection ->
            connection.inTransaction {
                commitPageStart(connection, request, page.startSec, page.endSec, page.requestedCursor)
                    ?.let { return@inTransaction it }
                if (!page.valid) {
                    return@inTransaction markPageFailed(
                        connection,
                        request,
                        "Exchange trade page was invalid.",
                    )
                }
                cursorProgressFailure(connection, request, page.nextCursor, "trade")?.let { reason ->
                    return@inTransaction markPageFailed(connection, request, reason)
                }
                var inserted = 0L
                page.entries.forEach { entry ->
                    if (insertTrade(connection, entry.toHistoryTrade(request.accountIdentityDigest))) inserted++
                }
                if (inserted > 0) bumpDataRevision(connection)
                updatePageStatus(connection, request, page.nextCursor, page.rawSize, inserted, page.count)
            }
        }
    }

    override suspend fun commitLedgerPage(
        request: HistoryPageRequest,
        page: ExchangeHistoryPage<ExchangeLedgerHistoryEntry>,
    ): HistorySyncStatus = withContext(Dispatchers.IO) {
        require(request.feed == HistoryFeed.LEDGERS)
        database.connect().use { connection ->
            connection.inTransaction {
                commitPageStart(connection, request, page.startSec, page.endSec, page.requestedCursor)
                    ?.let { return@inTransaction it }
                if (!page.valid) {
                    return@inTransaction markPageFailed(
                        connection,
                        request,
                        "Exchange ledger page was invalid.",
                    )
                }
                cursorProgressFailure(connection, request, page.nextCursor, "ledger")?.let { reason ->
                    return@inTransaction markPageFailed(connection, request, reason)
                }
                var inserted = 0L
                page.entries.forEach { entry ->
                    if (insertLedger(connection, entry.toHistoryLedger(request.accountIdentityDigest))) inserted++
                }
                if (inserted > 0) bumpDataRevision(connection)
                updatePageStatus(connection, request, page.nextCursor, page.rawSize, inserted, page.count)
            }
        }
    }

    override suspend fun pauseSync(accountIdentityDigest: String, feed: HistoryFeed, scanId: String) =
        withContext(Dispatchers.IO) {
            requireDigest(accountIdentityDigest)
            database.connect().use { connection ->
                connection.inTransaction {
                    updateSyncState(connection, accountIdentityDigest, feed, scanId, HistorySyncState.PAUSED, null)
                }
            }
        }

    override suspend fun failSync(accountIdentityDigest: String, feed: HistoryFeed, scanId: String, reason: String) =
        withContext(Dispatchers.IO) {
            requireDigest(accountIdentityDigest)
            database.connect().use { connection ->
                connection.inTransaction {
                    updateSyncState(connection, accountIdentityDigest, feed, scanId, HistorySyncState.FAILED, reason)
                }
            }
        }

    override suspend fun currentDataRevision(): Long = withContext(Dispatchers.IO) {
        database.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT revision FROM history_data_revision WHERE id = 1").use { resultSet ->
                    check(resultSet.next()) { "History data revision row is missing." }
                    resultSet.getLong(1)
                }
            }
        }
    }

    override suspend fun loadSyncJobs(accountIdentityDigest: String): List<HistorySyncJob> =
        withContext(Dispatchers.IO) {
            requireDigest(accountIdentityDigest)
            database.connect().use { connection ->
                connection.prepareStatement(
                    "SELECT * FROM history_sync_jobs WHERE account_identity_digest = ? " +
                        "ORDER BY feed, purpose",
                ).use { statement ->
                    statement.setString(1, accountIdentityDigest)
                    statement.executeQuery().use { resultSet ->
                        buildList {
                            while (resultSet.next()) add(resultSet.toSyncJob())
                        }
                    }
                }
            }
        }

    override suspend fun upsertSyncJob(job: HistorySyncJob) = withContext(Dispatchers.IO) {
        requireDigest(job.accountIdentityDigest)
        database.connect().use { connection ->
            connection.inTransaction {
                connection.prepareStatement(
                    "INSERT INTO history_sync_jobs(" +
                        "account_identity_digest, feed, purpose, state, window_start_sec, window_end_sec, cursor, " +
                        "completed_through_sec, last_error, next_retry_at_ns, last_success_at_ns, updated_at_ns) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT(account_identity_digest, feed, purpose) DO UPDATE SET " +
                        "state = excluded.state, window_start_sec = excluded.window_start_sec, " +
                        "window_end_sec = excluded.window_end_sec, cursor = excluded.cursor, " +
                        "completed_through_sec = excluded.completed_through_sec, last_error = excluded.last_error, " +
                        "next_retry_at_ns = excluded.next_retry_at_ns, " +
                        "last_success_at_ns = excluded.last_success_at_ns, updated_at_ns = excluded.updated_at_ns",
                ).use { statement ->
                    statement.setString(1, job.accountIdentityDigest)
                    statement.setString(2, job.feed.name)
                    statement.setString(3, job.purpose.name)
                    statement.setString(4, job.state.name)
                    statement.setNullableLong(5, job.windowStartSec)
                    statement.setNullableLong(6, job.windowEndSec)
                    statement.setString(7, job.cursor)
                    statement.setNullableLong(8, job.completedThroughSec)
                    statement.setString(9, job.lastError)
                    setInstant(statement, 10, job.nextRetryAt)
                    setInstant(statement, 11, job.lastSuccessAt)
                    statement.setLong(12, job.updatedAt.toEpochNanos())
                    statement.executeUpdate()
                }
                Unit
            }
        }
    }

    override suspend fun syncProgress(accountIdentityDigest: String): List<HistorySyncProgressSummary> =
        withContext(Dispatchers.IO) {
            requireDigest(accountIdentityDigest)
            database.connect().use { connection ->
                val jobs = connection.prepareStatement(
                    "SELECT * FROM history_sync_jobs WHERE account_identity_digest = ?",
                ).use { statement ->
                    statement.setString(1, accountIdentityDigest)
                    statement.executeQuery().use { resultSet ->
                        buildList {
                            while (resultSet.next()) add(resultSet.toSyncJob())
                        }
                    }
                }
                HistoryFeed.entries.mapNotNull { feed ->
                    val feedJobs = jobs.filter { it.feed == feed }
                    if (feedJobs.isEmpty()) return@mapNotNull null
                    val recent = feedJobs.firstOrNull { it.purpose == HistorySyncPurpose.RECENT }
                    val backfill = feedJobs.firstOrNull { it.purpose == HistorySyncPurpose.BACKFILL }
                    val repair = feedJobs.firstOrNull { it.purpose == HistorySyncPurpose.REPAIR }
                    HistorySyncProgressSummary(
                        accountIdentityDigest = accountIdentityDigest,
                        feed = feed,
                        recentWindowStartSec = recent?.windowStartSec,
                        recentCompletedThroughSec = recent?.completedThroughSec,
                        recentLastSuccessAt = recent?.lastSuccessAt,
                        backfillState = backfill?.state ?: HistorySyncJobState.PENDING,
                        backfillCompletedThroughSec = backfill?.completedThroughSec,
                        backfillCursor = backfill?.cursor,
                        repairLastRunAt = repair?.lastSuccessAt,
                        lastError = feedJobs.mapNotNull { it.lastError }.lastOrNull(),
                        nextRetryAt = feedJobs.mapNotNull { it.nextRetryAt }.minOrNull(),
                        coveredWindows = readCoverageWindows(connection, accountIdentityDigest, feed),
                    )
                }
            }
        }

    override suspend fun querySyncCoverageWindows(
        accountIdentityDigest: String,
        feed: HistoryFeed,
    ): List<HistorySyncCoverageWindow> = withContext(Dispatchers.IO) {
        requireDigest(accountIdentityDigest)
        database.connect().use { connection ->
            readCoverageWindows(connection, accountIdentityDigest, feed)
        }
    }

    private fun readCoverageWindows(
        connection: Connection,
        accountIdentityDigest: String,
        feed: HistoryFeed,
    ): List<HistorySyncCoverageWindow> = connection.prepareStatement(
        "SELECT start_sec, end_sec FROM history_sync_coverage WHERE account_identity_digest = ? AND feed = ? " +
            "ORDER BY start_sec, end_sec",
    ).use { statement ->
        statement.setString(1, accountIdentityDigest)
        statement.setString(2, feed.name)
        statement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(
                        HistorySyncCoverageWindow(
                            startSec = resultSet.getLong("start_sec"),
                            endSec = resultSet.getLong("end_sec"),
                        ),
                    )
                }
            }
        }
    }

    override suspend fun queryActivityPage(query: HistoryActivityQuery): HistoryPage<HistoryCycleActivity> =
        withContext(Dispatchers.IO) {
            database.connect().use { connection ->
                connection.inTransaction {
                    val conditions = mutableListOf("source_key = ?")
                    val values = mutableListOf<Any?>(query.sourceScope.sourceKey)
                    query.from?.let {
                        conditions.add("occurred_at_ns >= ?")
                        values.add(it.toEpochNanos())
                    }
                    query.until?.let {
                        conditions.add("occurred_at_ns < ?")
                        values.add(it.toEpochNanos())
                    }
                    val rangeWhere = "WHERE ${conditions.joinToString(" AND ")}"
                    val total = connection.prepareStatement(
                        "SELECT COUNT(*) FROM history_cycle_activity $rangeWhere",
                    ).use { statement ->
                        bindValues(statement, values)
                        statement.executeQuery().use { resultSet ->
                            check(resultSet.next())
                            resultSet.getLong(1)
                        }
                    }
                    query.sortCursor?.let { cursor ->
                        addSortCursor("occurred_at_ns", "cycle_id", cursor, conditions, values)
                    }
                    val where = "WHERE ${conditions.joinToString(" AND ")}"
                    val pageTotal = if (query.sortCursor == null) {
                        total
                    } else {
                        connection.prepareStatement(
                            "SELECT COUNT(*) FROM history_cycle_activity $where",
                        ).use { statement ->
                            bindValues(statement, values)
                            statement.executeQuery().use { resultSet ->
                                check(resultSet.next())
                                resultSet.getLong(1)
                            }
                        }
                    }
                    val items = connection.prepareStatement(
                        "SELECT * FROM history_cycle_activity $where " +
                            "ORDER BY occurred_at_ns DESC, cycle_id DESC LIMIT ? OFFSET ?",
                    ).use { statement ->
                        bindValues(statement, values)
                        statement.setInt(values.size + 1, query.limit)
                        statement.setInt(values.size + 2, query.offset)
                        statement.executeQuery().use { resultSet ->
                            buildList {
                                while (resultSet.next()) add(resultSet.toCycleActivity())
                            }
                        }
                    }
                    HistoryPage(
                        items = items,
                        total = total,
                        limit = query.limit,
                        offset = query.offset,
                        hasMore = query.offset + items.size < pageTotal,
                    )
                }
            }
        }

    override suspend fun listRecordedScopes(accountIdentityDigest: String): List<HistoryRecordedScope> =
        withContext(Dispatchers.IO) {
            requireDigest(accountIdentityDigest)
            database.connect().use { connection ->
                val rows = connection.prepareStatement(
                    "SELECT source_key, source_mode, account_identity_digest, simulation_session_id, " +
                        "managed_scope_fingerprint, managed_scope_symbols, observed_at_ns AS at_ns " +
                        "FROM history_observations WHERE account_identity_digest = ? " +
                        "UNION ALL " +
                        "SELECT source_key, source_mode, account_identity_digest, simulation_session_id, " +
                        "managed_scope_fingerprint, managed_scope_symbols, occurred_at_ns AS at_ns " +
                        "FROM history_cycle_activity WHERE account_identity_digest = ?",
                ).use { statement ->
                    statement.setString(1, accountIdentityDigest)
                    statement.setString(2, accountIdentityDigest)
                    statement.executeQuery().use { resultSet ->
                        buildList {
                            while (resultSet.next()) {
                                add(
                                    Triple(
                                        HistoryRecordedScope(
                                            sourceKey = resultSet.getString("source_key"),
                                            mode = HistorySourceMode.valueOf(resultSet.getString("source_mode")),
                                            managedScopeFingerprint = resultSet.getString("managed_scope_fingerprint"),
                                            managedScopeSymbols = decodeHistoryStrings(
                                                resultSet.getString("managed_scope_symbols"),
                                            ),
                                            accountIdentityDigest = resultSet.getString("account_identity_digest"),
                                            simulationSessionId = resultSet.getString("simulation_session_id"),
                                            firstObservedAt = null,
                                            lastObservedAt = null,
                                            observationCount = 0,
                                        ),
                                        resultSet.getLong("at_ns"),
                                        1L,
                                    ),
                                )
                            }
                        }
                    }
                }
                rows.groupBy { (scope, _, _) -> scope.sourceKey }
                    .map { (_, entries) ->
                        val scope = entries.first().first
                        val times = entries.map { (_, atNs, _) -> atNs }
                        scope.copy(
                            firstObservedAt = times.minOrNull()?.toInstantNanos(),
                            lastObservedAt = times.maxOrNull()?.toInstantNanos(),
                            observationCount = entries.sumOf { (_, _, count) -> count },
                        )
                    }
                    .sortedWith(compareByDescending { it.lastObservedAt ?: Instant.EPOCH })
            }
        }

    override suspend fun valuesCoverage(sourceScope: HistorySourceScope): HistoryValuesCoverage =
        withContext(Dispatchers.IO) {
            database.connect().use { connection ->
                connection.prepareStatement(
                    "SELECT MIN(observed_at_ns) AS first_ns, MAX(observed_at_ns) AS last_ns, " +
                        "SUM(CASE WHEN status = 'COMPLETE' THEN 1 ELSE 0 END) AS complete_count, " +
                        "SUM(CASE WHEN status = 'INCOMPLETE' THEN 1 ELSE 0 END) AS incomplete_count " +
                        "FROM history_observations WHERE source_key = ?",
                ).use { statement ->
                    statement.setString(1, sourceScope.sourceKey)
                    statement.executeQuery().use { resultSet ->
                        check(resultSet.next())
                        HistoryValuesCoverage(
                            sourceKey = sourceScope.sourceKey,
                            firstRecordedAt = resultSet.getNullableLong("first_ns")?.toInstantNanos(),
                            lastRecordedAt = resultSet.getNullableLong("last_ns")?.toInstantNanos(),
                            completeCount = resultSet.getLong("complete_count"),
                            incompleteCount = resultSet.getLong("incomplete_count"),
                        )
                    }
                }
            }
        }

    override suspend fun hasLedgerCoverage(accountIdentityDigest: String, startSec: Long, endSec: Long): Boolean =
        withContext(Dispatchers.IO) {
            requireDigest(accountIdentityDigest)
            require(startSec <= endSec)
            database.connect().use { connection ->
                connection.prepareStatement(
                    "SELECT 1 FROM history_sync_windows WHERE account_identity_digest = ? AND feed = 'LEDGERS' " +
                        "AND state = 'COMPLETE' AND start_sec <= ? AND end_sec >= ? LIMIT 1",
                ).use { statement ->
                    statement.setString(1, accountIdentityDigest)
                    statement.setLong(2, startSec)
                    statement.setLong(3, endSec)
                    statement.executeQuery().use(ResultSet::next)
                }
            }
        }

    override suspend fun queryLedgerCoverageWindows(
        accountIdentityDigest: String,
        overlappingStartSec: Long,
        overlappingEndSec: Long,
    ): List<HistoryLedgerCoverageWindow> = withContext(Dispatchers.IO) {
        requireDigest(accountIdentityDigest)
        require(overlappingStartSec <= overlappingEndSec)
        database.connect().use { connection ->
            connection.prepareStatement(
                "SELECT start_sec, end_sec FROM history_sync_windows WHERE account_identity_digest = ? " +
                    "AND feed = 'LEDGERS' AND state = 'COMPLETE' AND start_sec <= ? AND end_sec >= ? " +
                    "ORDER BY start_sec, end_sec",
            ).use { statement ->
                statement.setString(1, accountIdentityDigest)
                statement.setLong(2, overlappingEndSec)
                statement.setLong(3, overlappingStartSec)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(
                                HistoryLedgerCoverageWindow(
                                    resultSet.getLong("start_sec"),
                                    resultSet.getLong("end_sec"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    override suspend fun queryDailyCheckpointInputs(
        accountIdentityDigest: String,
        fromSec: Long,
        throughSec: Long,
        limit: Int,
    ): List<HistoryBalanceCheckpoint> = withContext(Dispatchers.IO) {
        requireDigest(accountIdentityDigest)
        require(fromSec <= throughSec)
        require(limit in 1..MAX_DAILY_SOURCE_CHECKPOINTS)
        database.connect().use { connection ->
            val rows = connection.prepareStatement(
                "SELECT * FROM history_balance_checkpoints WHERE account_identity_digest = ? " +
                    "AND observed_at_ns <= ? ORDER BY observed_at_ns ASC LIMIT ?",
            ).use { statement ->
                statement.setString(1, accountIdentityDigest)
                statement.setLong(2, epochSecondEndNanos(throughSec))
                statement.setInt(3, limit + 1)
                statement.executeQuery().use { resultSet ->
                    buildList { while (resultSet.next()) add(resultSet.toBalanceCheckpoint()) }
                }
            }
            check(rows.size <= limit) { "Daily valuation source exceeds $limit ledger balance checkpoints." }
            val beforeStartByPartition = rows.filter {
                it.observedAt?.epochSecond?.let { second -> second < fromSec } ==
                    true
            }
                .groupBy(::balancePartitionKey)
                .values
                .flatMap { checkpoints ->
                    val latestTime =
                        checkpoints.maxOfOrNull { it.observedAt ?: Instant.MIN } ?: return@flatMap emptyList()
                    checkpoints.filter { it.observedAt == latestTime }
                }
            val inRange = rows.filter { checkpoint ->
                checkpoint.observedAt?.epochSecond?.let { it >= fromSec } == true
            }
            (beforeStartByPartition + inRange).sortedBy { it.observedAt }
        }
    }

    override suspend fun saveDailyPrices(prices: List<HistoryDailyPrice>): Unit = withContext(Dispatchers.IO) {
        if (prices.isEmpty()) return@withContext
        prices.forEach { price ->
            requireDigest(price.accountIdentityDigest)
            require(price.closeUsd.signum() > 0)
            require(price.candleOpen < price.candleEnd)
        }
        database.connect().use { connection ->
            connection.inTransaction {
                connection.prepareStatement(
                    "INSERT INTO history_daily_prices(account_identity_digest, raw_asset_key, candle_open_sec, " +
                        "candle_end_sec, pair, close_usd, source) VALUES (?, ?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT(account_identity_digest, raw_asset_key, candle_open_sec) DO UPDATE SET " +
                        "candle_end_sec = excluded.candle_end_sec, pair = excluded.pair, " +
                        "close_usd = excluded.close_usd, source = excluded.source",
                ).use { statement ->
                    prices.forEach { price ->
                        statement.setString(1, price.accountIdentityDigest)
                        statement.setString(2, price.rawAssetKey)
                        statement.setLong(3, price.candleOpen.epochSecond)
                        statement.setLong(4, price.candleEnd.epochSecond)
                        statement.setString(5, price.pair)
                        statement.setString(6, price.closeUsd.toPlainString())
                        statement.setString(7, price.source)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }
        }
    }

    override suspend fun queryDailyPrices(
        accountIdentityDigest: String,
        rawAssetKey: String,
        fromSec: Long,
        throughSec: Long,
    ): List<HistoryDailyPrice> = withContext(Dispatchers.IO) {
        requireDigest(accountIdentityDigest)
        require(fromSec <= throughSec)
        database.connect().use { connection ->
            connection.prepareStatement(
                "SELECT * FROM history_daily_prices WHERE account_identity_digest = ? AND raw_asset_key = ? " +
                    "AND candle_end_sec >= ? AND candle_end_sec <= ? ORDER BY candle_end_sec ASC",
            ).use { statement ->
                statement.setString(1, accountIdentityDigest)
                statement.setString(2, rawAssetKey)
                statement.setLong(3, fromSec)
                statement.setLong(4, throughSec)
                statement.executeQuery().use { resultSet ->
                    buildList { while (resultSet.next()) add(resultSet.toDailyPrice()) }
                }
            }
        }
    }

    override suspend fun saveDailyValues(values: List<HistoryDailyValue>) = withContext(Dispatchers.IO) {
        if (values.isEmpty()) return@withContext
        values.forEach { value ->
            requireDigest(value.accountIdentityDigest)
            require(value.sourceAssetScope.isNotBlank())
            require(
                value.assets.map {
                    encodeHistoryAssetPartition(it.rawAssetClass, it.rawAssetKey)
                }.distinct().size ==
                    value.assets.size,
            )
        }
        database.connect().use { connection ->
            connection.inTransaction {
                values.forEach { value -> insertDailyValue(connection, value) }
            }
        }
    }

    override suspend fun queryDailyValues(
        accountIdentityDigest: String,
        sourceAssetScope: String,
        from: Instant?,
        until: Instant?,
        limit: Int,
        offset: Int,
    ): HistoryPage<HistoryDailyValue> = withContext(Dispatchers.IO) {
        validateDailyQuery(accountIdentityDigest, from, until, limit, offset)
        database.connect().use { connection ->
            queryDailyValues(connection, accountIdentityDigest, sourceAssetScope, from, until, limit, offset)
        }
    }

    override suspend fun queryDailyAssetValues(
        accountIdentityDigest: String,
        sourceAssetScope: String,
        from: Instant?,
        until: Instant?,
        limit: Int,
        offset: Int,
    ): HistoryPage<HistoryDailyAssetValue> = withContext(Dispatchers.IO) {
        validateDailyQuery(accountIdentityDigest, from, until, limit, offset)
        database.connect().use { connection ->
            queryDailyAssetValues(connection, accountIdentityDigest, sourceAssetScope, from, until, limit, offset)
        }
    }

    private fun insertObservation(connection: Connection, observation: HistoryObservation): Boolean {
        require(observation.sourceScope.managedScopeFingerprint.matches(HEX_DIGEST))
        if (observation.sourceScope.mode == HistorySourceMode.ACCOUNT) {
            requireDigest(checkNotNull(observation.sourceScope.accountIdentityDigest))
        }
        val payloadHash = observationPayloadHash(observation)
        val inserted = connection.prepareStatement(
            "INSERT OR IGNORE INTO history_observations(" +
                "observation_id, cycle_id, source_key, source_mode, account_identity_digest, simulation_session_id, " +
                "managed_scope_fingerprint, managed_scope_symbols, wallet_scope, balance_source, price_source, " +
                "valuation_currency, sample_selection, observed_at_ns, balance_request_started_at_ns, " +
                "balance_response_ended_at_ns, price_request_started_at_ns, price_response_ended_at_ns, " +
                "persisted_at_ns, status, total_usd, incomplete_reasons, payload_hash) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, observation.observationId)
            statement.setString(2, observation.cycleId)
            statement.setString(3, observation.sourceScope.sourceKey)
            statement.setString(4, observation.sourceScope.mode.name)
            statement.setString(5, observation.sourceScope.accountIdentityDigest)
            statement.setString(6, observation.sourceScope.simulationSessionId)
            statement.setString(7, observation.sourceScope.managedScopeFingerprint)
            statement.setString(8, encodeHistoryStrings(observation.sourceScope.managedScopeSymbols))
            statement.setString(9, observation.walletScope)
            statement.setString(10, observation.balanceSource)
            statement.setString(11, observation.priceSource)
            statement.setString(12, observation.valuationCurrency)
            statement.setString(13, observation.sampleSelection.name)
            statement.setLong(14, observation.observedAt.toEpochNanos())
            statement.setLong(15, observation.balanceRequestStartedAt.toEpochNanos())
            statement.setLong(16, observation.balanceResponseEndedAt.toEpochNanos())
            statement.setLong(17, observation.priceRequestStartedAt.toEpochNanos())
            statement.setLong(18, observation.priceResponseEndedAt.toEpochNanos())
            statement.setLong(19, (observation.persistedAt ?: nowProvider()).toEpochNanos())
            statement.setString(20, observation.status.name)
            statement.setString(21, observation.totalUsd?.toPlainString())
            statement.setString(22, encodeHistoryStrings(observation.incompleteReasons))
            statement.setString(23, payloadHash)
            statement.executeUpdate() == 1
        }
        if (!inserted) {
            val existingHash = connection.prepareStatement(
                "SELECT payload_hash FROM history_observations WHERE observation_id = ? OR " +
                    "(source_key = ? AND cycle_id = ?) LIMIT 1",
            ).use { statement ->
                statement.setString(1, observation.observationId)
                statement.setString(2, observation.sourceScope.sourceKey)
                statement.setString(3, observation.cycleId)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next()) { "History observation idempotency key disappeared during append." }
                    resultSet.getString(1)
                }
            }
            check(existingHash == payloadHash) { "History observation id was reused for different evidence." }
            return false
        }
        connection.prepareStatement(
            "INSERT INTO history_observation_assets(" +
                "observation_id, asset_symbol, status, raw_balance_keys, raw_balance_values, quantity, " +
                "requested_pair, response_pair, candidate_response_pairs, candidate_raw_prices, raw_price, " +
                "price_usd, value_usd, reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            observation.assets.forEach { asset ->
                statement.setString(1, observation.observationId)
                statement.setString(2, asset.symbol)
                statement.setString(3, asset.status.name)
                statement.setString(4, encodeHistoryStrings(asset.rawBalanceKeys.sorted()))
                statement.setString(5, encodeHistoryPairs(asset.rawBalanceValues))
                statement.setString(6, asset.quantity?.toPlainString())
                statement.setString(7, asset.requestedPair)
                statement.setString(8, asset.responsePair)
                statement.setString(9, encodeHistoryStrings(asset.candidateResponsePairs.sorted()))
                statement.setString(10, encodeHistoryPairs(asset.candidateRawPrices))
                statement.setString(11, asset.rawPrice)
                statement.setString(12, asset.priceUsd?.toPlainString())
                statement.setString(13, asset.valueUsd?.toPlainString())
                statement.setString(14, asset.reason)
                statement.addBatch()
            }
            statement.executeBatch()
        }
        return true
    }

    private fun insertCycleActivity(connection: Connection, activity: HistoryCycleActivity): Boolean {
        val payloadHash = activityPayloadHash(activity)
        val inserted = connection.prepareStatement(
            "INSERT OR IGNORE INTO history_cycle_activity(" +
                "source_key, cycle_id, source_mode, account_identity_digest, simulation_session_id, " +
                "managed_scope_fingerprint, managed_scope_symbols, occurred_at_ns, dry_run, actions, outcome, " +
                "payload_hash) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, activity.sourceScope.sourceKey)
            statement.setString(2, activity.cycleId)
            statement.setString(3, activity.sourceScope.mode.name)
            statement.setString(4, activity.sourceScope.accountIdentityDigest)
            statement.setString(5, activity.sourceScope.simulationSessionId)
            statement.setString(6, activity.sourceScope.managedScopeFingerprint)
            statement.setString(7, encodeHistoryStrings(activity.sourceScope.managedScopeSymbols))
            statement.setLong(8, activity.occurredAt.toEpochNanos())
            statement.setInt(9, if (activity.dryRun) 1 else 0)
            statement.setString(10, encodeHistoryStrings(activity.actions))
            statement.setString(11, activity.outcome?.name)
            statement.setString(12, payloadHash)
            statement.executeUpdate() == 1
        }
        if (!inserted) {
            val existingHash = connection.prepareStatement(
                "SELECT payload_hash FROM history_cycle_activity WHERE source_key = ? AND cycle_id = ?",
            ).use { statement ->
                statement.setString(1, activity.sourceScope.sourceKey)
                statement.setString(2, activity.cycleId)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next()) { "History cycle activity idempotency key disappeared." }
                    resultSet.getString(1)
                }
            }
            check(existingHash == payloadHash) { "History cycle id was reused for different activity." }
        }
        return inserted
    }

    private fun queryObservations(connection: Connection, query: HistoryRangeQuery): HistoryPage<HistoryObservation> {
        val filter = observationFilter(query)
        val total = countRows(connection, "history_observations", filter)
        val headers = connection.prepareStatement(
            "SELECT * FROM history_observations ${filter.where} ORDER BY observed_at_ns DESC, observation_id " +
                "DESC LIMIT ? OFFSET ?",
        ).use { statement ->
            bindValues(statement, filter.values)
            statement.setInt(filter.values.size + 1, query.limit)
            statement.setInt(filter.values.size + 2, query.offset)
            statement.executeQuery().use { resultSet ->
                buildList { while (resultSet.next()) add(resultSet.toObservationHeader()) }
            }
        }
        val observations = headers.loadObservationAssets(connection)
        return HistoryPage(observations, total, query.limit, query.offset, query.offset + observations.size < total)
    }

    private fun queryAssetValues(connection: Connection, query: HistoryRangeQuery): HistoryPage<HistoryAssetValue> {
        val filter = observationFilter(query, headerAlias = "o")
        val joinWhere = "FROM history_observation_assets a JOIN history_observations o " +
            "ON o.observation_id = a.observation_id ${filter.where}"
        val total = connection.prepareStatement("SELECT COUNT(*) $joinWhere").use { statement ->
            bindValues(statement, filter.values)
            statement.executeQuery().use { resultSet ->
                resultSet.next()
                resultSet.getLong(1)
            }
        }
        val assets = connection.prepareStatement(
            "SELECT a.* $joinWhere ORDER BY o.observed_at_ns DESC, a.asset_symbol LIMIT ? OFFSET ?",
        ).use { statement ->
            bindValues(statement, filter.values)
            statement.setInt(filter.values.size + 1, query.limit)
            statement.setInt(filter.values.size + 2, query.offset)
            statement.executeQuery().use { resultSet ->
                buildList { while (resultSet.next()) add(resultSet.toHistoryAssetValue()) }
            }
        }
        return HistoryPage(assets, total, query.limit, query.offset, query.offset + assets.size < total)
    }

    private fun queryTrades(connection: Connection, query: HistoryEventQuery): HistoryPage<HistoryTrade> {
        val filter = tradeFilter(query)
        val total = countRows(connection, "history_trades", tradeFilter(query, includeSortCursor = false))
        val pageTotal = if (query.sortCursor == null) total else countRows(connection, "history_trades", filter)
        val rows = connection.prepareStatement(
            "SELECT * FROM history_trades ${filter.where} ORDER BY time_ns DESC, trade_id DESC LIMIT ? OFFSET ?",
        ).use { statement ->
            bindValues(statement, filter.values)
            statement.setInt(filter.values.size + 1, query.limit)
            statement.setInt(filter.values.size + 2, query.offset)
            statement.executeQuery().use { resultSet ->
                buildList { while (resultSet.next()) add(resultSet.toHistoryTrade()) }
            }
        }
        return HistoryPage(rows, total, query.limit, query.offset, query.offset + rows.size < pageTotal)
    }

    private fun queryLedgers(connection: Connection, query: HistoryEventQuery): HistoryPage<HistoryLedger> {
        val filter = ledgerFilter(query)
        val total = countRows(connection, "history_ledgers", ledgerFilter(query, includeSortCursor = false))
        val pageTotal = if (query.sortCursor == null) total else countRows(connection, "history_ledgers", filter)
        val rows = connection.prepareStatement(
            "SELECT * FROM history_ledgers ${filter.where} ORDER BY time_ns DESC, ledger_id DESC LIMIT ? OFFSET ?",
        ).use { statement ->
            bindValues(statement, filter.values)
            statement.setInt(filter.values.size + 1, query.limit)
            statement.setInt(filter.values.size + 2, query.offset)
            statement.executeQuery().use { resultSet ->
                buildList { while (resultSet.next()) add(resultSet.toHistoryLedger()) }
            }
        }
        return HistoryPage(rows, total, query.limit, query.offset, query.offset + rows.size < pageTotal)
    }

    private fun queryBalanceCheckpoints(
        connection: Connection,
        query: HistoryEventQuery,
    ): HistoryPage<HistoryBalanceCheckpoint> {
        val filter = checkpointFilter(query)
        val total = countRows(connection, "history_balance_checkpoints", filter)
        val rows = connection.prepareStatement(
            "SELECT * FROM history_balance_checkpoints ${filter.where} " +
                "ORDER BY observed_at_ns DESC, ledger_id DESC LIMIT ? OFFSET ?",
        ).use { statement ->
            bindValues(statement, filter.values)
            statement.setInt(filter.values.size + 1, query.limit)
            statement.setInt(filter.values.size + 2, query.offset)
            statement.executeQuery().use { resultSet ->
                buildList { while (resultSet.next()) add(resultSet.toBalanceCheckpoint()) }
            }
        }
        return HistoryPage(rows, total, query.limit, query.offset, query.offset + rows.size < total)
    }

    private fun queryDistinctOptionValues(
        connection: Connection,
        table: String,
        column: String,
        timeColumn: String,
        query: HistoryEventQuery,
    ): List<String> {
        val digest = query.accountIdentityDigest ?: return emptyList()
        val conditions = mutableListOf("account_identity_digest = ?", "$column IS NOT NULL", "TRIM($column) != ''")
        val values = mutableListOf<Any?>(digest)
        query.from?.let {
            conditions += "$timeColumn >= ?"
            values += it.toEpochNanos()
        }
        query.until?.let {
            conditions += "$timeColumn < ?"
            values += it.toEpochNanos()
        }
        val where = "WHERE ${conditions.joinToString(" AND ")}"
        return connection.prepareStatement(
            "SELECT DISTINCT $column AS option_value FROM $table $where ORDER BY option_value COLLATE NOCASE",
        ).use { statement ->
            bindValues(statement, values)
            statement.executeQuery().use { resultSet ->
                buildList { while (resultSet.next()) add(resultSet.getString("option_value")) }
            }
        }
    }

    private fun observationFilter(query: HistoryRangeQuery, headerAlias: String = "history_observations"): SqlFilter {
        val conditions = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        query.sourceScope?.let {
            conditions += "$headerAlias.source_key = ?"
            values += it.sourceKey
        }
        query.from?.let {
            conditions += "$headerAlias.observed_at_ns >= ?"
            values += it.toEpochNanos()
        }
        query.until?.let {
            conditions += "$headerAlias.observed_at_ns < ?"
            values += it.toEpochNanos()
        }
        return SqlFilter(conditions, values)
    }

    private fun tradeFilter(query: HistoryEventQuery, includeSortCursor: Boolean = true): SqlFilter {
        val conditions = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        query.accountIdentityDigest?.let {
            conditions += "account_identity_digest = ?"
            values += it
        }
        query.from?.let {
            conditions += "time_ns >= ?"
            values += it.toEpochNanos()
        }
        query.until?.let {
            conditions += "time_ns < ?"
            values += it.toEpochNanos()
        }
        query.assetKey?.let { rawAsset ->
            val asset = Asset.normalizeLedgerAsset(rawAsset).uppercase()
            conditions += "(UPPER(base_asset) = ? OR UPPER(quote_currency) = ?)"
            values += asset
            values += asset
        }
        query.pair?.let {
            conditions += "pair = ?"
            values += it
        }
        query.type?.let {
            conditions += "type = ?"
            values += it
        }
        if (includeSortCursor) {
            addSortCursor("time_ns", "trade_id", query.sortCursor, conditions, values)
        }
        return SqlFilter(conditions, values)
    }

    private fun ledgerFilter(query: HistoryEventQuery, includeSortCursor: Boolean = true): SqlFilter {
        val conditions = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        query.accountIdentityDigest?.let {
            conditions += "account_identity_digest = ?"
            values += it
        }
        query.from?.let {
            conditions += "time_ns >= ?"
            values += it.toEpochNanos()
        }
        query.until?.let {
            conditions += "time_ns < ?"
            values += it.toEpochNanos()
        }
        query.assetKey?.let { assetKey ->
            addLedgerAssetCondition("asset", assetKey, conditions, values)
        }
        query.type?.let {
            conditions += "type = ?"
            values += it
        }
        if (includeSortCursor) {
            addSortCursor("time_ns", "ledger_id", query.sortCursor, conditions, values)
        }
        return SqlFilter(conditions, values)
    }

    private fun addSortCursor(
        timeColumn: String,
        idColumn: String,
        cursor: HistorySortCursor?,
        conditions: MutableList<String>,
        values: MutableList<Any?>,
    ) {
        cursor ?: return
        val time = cursor.time
        if (time == null) {
            conditions += "($timeColumn IS NULL AND $idColumn < ?)"
            values += cursor.id
        } else {
            val nanos = time.toEpochNanos()
            conditions += "($timeColumn < ? OR $timeColumn IS NULL OR ($timeColumn = ? AND $idColumn < ?))"
            values += nanos
            values += nanos
            values += cursor.id
        }
    }

    private fun checkpointFilter(query: HistoryEventQuery): SqlFilter {
        val conditions = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        query.accountIdentityDigest?.let {
            conditions += "account_identity_digest = ?"
            values += it
        }
        query.from?.let {
            conditions += "observed_at_ns >= ?"
            values += it.toEpochNanos()
        }
        query.until?.let {
            conditions += "observed_at_ns < ?"
            values += it.toEpochNanos()
        }
        query.assetKey?.let { assetKey ->
            addLedgerAssetCondition("raw_asset_key", assetKey, conditions, values)
        }
        return SqlFilter(conditions, values)
    }

    private fun addLedgerAssetCondition(
        column: String,
        assetKey: String,
        conditions: MutableList<String>,
        values: MutableList<Any?>,
    ) {
        val rawAssetKeys = ledgerAssetAliases(assetKey)
        conditions += "UPPER($column) IN (${rawAssetKeys.joinToString(", ") { "?" }})"
        values.addAll(rawAssetKeys)
    }

    private fun ledgerAssetAliases(assetKey: String): List<String> {
        val canonical = Asset.normalizeLedgerAsset(assetKey).uppercase(Locale.ROOT)
        val aliases = buildSet {
            add(canonical)
            CANONICAL_BY_KRAKEN_ALIAS.forEach { (alias, canonicalSymbol) ->
                if (canonicalSymbol.equals(canonical, ignoreCase = true)) add(alias.uppercase(Locale.ROOT))
            }
        }
        return (aliases + aliases.flatMap { alias -> EARN_ASSET_SUFFIXES.map { suffix -> alias + suffix } }).sorted()
    }

    private fun countRows(connection: Connection, table: String, filter: SqlFilter): Long =
        connection.prepareStatement("SELECT COUNT(*) FROM $table ${filter.where}").use { statement ->
            bindValues(statement, filter.values)
            statement.executeQuery().use { resultSet ->
                resultSet.next()
                resultSet.getLong(1)
            }
        }

    private fun bindValues(statement: PreparedStatement, values: List<Any?>) {
        values.forEachIndexed { index, value ->
            when (value) {
                null -> statement.setObject(index + 1, null)
                is Long -> statement.setLong(index + 1, value)
                is Int -> statement.setInt(index + 1, value)
                is String -> statement.setString(index + 1, value)
                else -> error("Unsupported History SQL parameter ${value::class.simpleName}.")
            }
        }
    }

    private fun observationPayloadHash(observation: HistoryObservation): String = historyPayloadHash(
        listOf(
            observation.observationId,
            observation.cycleId,
            observation.sourceScope.sourceKey,
            observation.walletScope,
            observation.balanceSource,
            observation.priceSource,
            observation.valuationCurrency,
            observation.sampleSelection.name,
            observation.observedAt.toString(),
            observation.balanceRequestStartedAt.toString(),
            observation.balanceResponseEndedAt.toString(),
            observation.priceRequestStartedAt.toString(),
            observation.priceResponseEndedAt.toString(),
            observation.status.name,
            observation.totalUsd?.toPlainString(),
            encodeHistoryStrings(observation.incompleteReasons),
        ) + observation.assets.sortedBy { it.symbol }.flatMap { asset ->
            listOf(
                asset.symbol,
                asset.status.name,
                encodeHistoryStrings(asset.rawBalanceKeys.sorted()),
                encodeHistoryPairs(asset.rawBalanceValues),
                asset.quantity?.toPlainString(),
                asset.requestedPair,
                asset.responsePair,
                encodeHistoryStrings(asset.candidateResponsePairs.sorted()),
                encodeHistoryPairs(asset.candidateRawPrices),
                asset.rawPrice,
                asset.priceUsd?.toPlainString(),
                asset.valueUsd?.toPlainString(),
                asset.reason,
            )
        },
    )

    private fun activityPayloadHash(activity: HistoryCycleActivity): String = historyPayloadHash(
        listOf(
            activity.cycleId,
            activity.sourceScope.sourceKey,
            activity.occurredAt.toString(),
            activity.dryRun.toString(),
            encodeHistoryStrings(activity.actions),
            activity.outcome?.name,
        ),
    )

    /** Returns a persisted status for stale/duplicate replies, or null when this page may commit. */
    private fun commitPageStart(
        connection: Connection,
        request: HistoryPageRequest,
        pageStart: Long?,
        pageEnd: Long?,
        pageCursor: String?,
    ): HistorySyncStatus? {
        requireDigest(request.accountIdentityDigest)
        require(request.startSec <= request.endSec)
        if (request.startSec != pageStart || request.endSec != pageEnd || request.requestedCursor != pageCursor) {
            return markPageFailed(connection, request, "History page metadata did not match its request.")
        }
        val current = readSyncStatus(connection, request.accountIdentityDigest, request.feed)
            ?: error("No History sync window exists for this page.")
        if (current.scanId != request.scanId || current.state != HistorySyncState.RUNNING ||
            current.windowStart?.epochSecond != request.startSec || current.windowEnd?.epochSecond != request.endSec ||
            current.nextCursor != request.requestedCursor
        ) {
            return current
        }
        return null
    }

    /** Persists the current cursor before page rows and status advance in the same transaction. */
    private fun cursorProgressFailure(
        connection: Connection,
        request: HistoryPageRequest,
        nextCursor: String?,
        feedLabel: String,
    ): String? {
        if (nextCursor != null && nextCursor == request.requestedCursor) {
            return "Exchange $feedLabel cursor did not advance."
        }
        val pageCount = connection.prepareStatement(
            "SELECT COUNT(*) FROM history_sync_seen_cursors WHERE account_identity_digest = ? AND feed = ? " +
                "AND scan_id = ?",
        ).use { statement ->
            statement.setString(1, request.accountIdentityDigest)
            statement.setString(2, request.feed.name)
            statement.setString(3, request.scanId)
            statement.executeQuery().use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        }
        if (pageCount >= maxPagesPerScan) {
            return "History $feedLabel scan exceeded the $maxPagesPerScan page limit."
        }
        if (nextCursor != null && cursorWasSeen(connection, request, cursorKey(nextCursor))) {
            return "Exchange $feedLabel cursor repeated within the current scan."
        }
        val requestedCursorKey = cursorKey(request.requestedCursor)
        if (cursorWasSeen(connection, request, requestedCursorKey)) {
            return "Exchange $feedLabel cursor was already processed within the current scan."
        }
        connection.prepareStatement(
            "INSERT INTO history_sync_seen_cursors(account_identity_digest, feed, scan_id, cursor) " +
                "VALUES (?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, request.accountIdentityDigest)
            statement.setString(2, request.feed.name)
            statement.setString(3, request.scanId)
            statement.setString(4, requestedCursorKey)
            statement.executeUpdate()
        }
        return null
    }

    private fun cursorWasSeen(connection: Connection, request: HistoryPageRequest, cursor: String): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM history_sync_seen_cursors WHERE account_identity_digest = ? AND feed = ? " +
                "AND scan_id = ? AND cursor = ? LIMIT 1",
        ).use { statement ->
            statement.setString(1, request.accountIdentityDigest)
            statement.setString(2, request.feed.name)
            statement.setString(3, request.scanId)
            statement.setString(4, cursor)
            statement.executeQuery().use(ResultSet::next)
        }

    private fun cursorKey(cursor: String?): String = if (cursor == null) "N" else "V$cursor"

    private fun updatePageStatus(
        connection: Connection,
        request: HistoryPageRequest,
        nextCursor: String?,
        rawSize: Int,
        insertedCount: Long,
        knownTotal: Long?,
    ): HistorySyncStatus {
        require(rawSize >= 0)
        val state = if (nextCursor == null) HistorySyncState.COMPLETE else HistorySyncState.RUNNING
        val completedAt = request.committedAt.toEpochNanos().takeIf { state == HistorySyncState.COMPLETE }
        connection.prepareStatement(
            "UPDATE history_sync_status SET state = ?, next_cursor = ?, fetched_count = fetched_count + ?, " +
                "stored_count = stored_count + ?, known_total = ?, last_page_at_ns = ?, last_error = NULL " +
                "WHERE account_identity_digest = ? AND feed = ? AND scan_id = ?",
        ).use { statement ->
            statement.setString(1, state.name)
            statement.setString(2, nextCursor)
            statement.setInt(3, rawSize)
            statement.setLong(4, insertedCount)
            statement.setObject(5, knownTotal)
            statement.setLong(6, request.committedAt.toEpochNanos())
            statement.setString(7, request.accountIdentityDigest)
            statement.setString(8, request.feed.name)
            statement.setString(9, request.scanId)
            check(statement.executeUpdate() == 1) { "History sync checkpoint changed before page commit." }
        }
        connection.prepareStatement(
            "UPDATE history_sync_windows SET state = ?, completed_at_ns = ? WHERE scan_id = ? " +
                "AND account_identity_digest = ? AND feed = ?",
        ).use { statement ->
            statement.setString(1, state.name)
            if (completedAt == null) statement.setObject(2, null) else statement.setLong(2, completedAt)
            statement.setString(3, request.scanId)
            statement.setString(4, request.accountIdentityDigest)
            statement.setString(5, request.feed.name)
            check(statement.executeUpdate() == 1) { "History sync window disappeared before page commit." }
        }
        if (state == HistorySyncState.COMPLETE) {
            // A finished window is verified coverage; the union with earlier windows is kept durable
            // so a rolling refresh never makes an already covered period look unsynchronized.
            mergeCoverageSegment(
                connection = connection,
                accountIdentityDigest = request.accountIdentityDigest,
                feed = request.feed,
                startSec = request.startSec,
                endSec = request.endSec,
                updatedAtNs = request.committedAt.toEpochNanos(),
            )
        }
        persistJobProgress(
            connection = connection,
            accountIdentityDigest = request.accountIdentityDigest,
            feed = request.feed,
            purpose = request.purpose,
            state = if (state ==
                HistorySyncState.COMPLETE
            ) {
                HistorySyncJobState.COMPLETE
            } else {
                HistorySyncJobState.RUNNING
            },
            windowStartSec = request.startSec,
            windowEndSec = request.endSec,
            cursor = nextCursor,
            completedThroughSec = if (state == HistorySyncState.COMPLETE) request.endSec else null,
            lastError = null,
            lastSuccessAt = request.committedAt.takeIf { state == HistorySyncState.COMPLETE },
            updatedAt = request.committedAt,
        )
        return readSyncStatus(connection, request.accountIdentityDigest, request.feed)
            ?: error("History sync status disappeared after page commit.")
    }

    private fun markPageFailed(
        connection: Connection,
        request: HistoryPageRequest,
        reason: String,
    ): HistorySyncStatus {
        updateSyncState(
            connection,
            request.accountIdentityDigest,
            request.feed,
            request.scanId,
            HistorySyncState.FAILED,
            reason,
        )
        return readSyncStatus(connection, request.accountIdentityDigest, request.feed)
            ?: error("History sync status disappeared after page failure.")
    }

    private fun updateSyncState(
        connection: Connection,
        accountIdentityDigest: String,
        feed: HistoryFeed,
        scanId: String,
        state: HistorySyncState,
        reason: String?,
    ) {
        connection.prepareStatement(
            "UPDATE history_sync_status SET state = ?, last_error = ? WHERE account_identity_digest = ? " +
                "AND feed = ? AND scan_id = ?",
        ).use { statement ->
            statement.setString(1, state.name)
            statement.setString(2, reason)
            statement.setString(3, accountIdentityDigest)
            statement.setString(4, feed.name)
            statement.setString(5, scanId)
            if (statement.executeUpdate() == 1) {
                connection.prepareStatement(
                    "UPDATE history_sync_windows SET state = ? WHERE scan_id = ? AND account_identity_digest = ? " +
                        "AND feed = ?",
                ).use { windowStatement ->
                    windowStatement.setString(1, state.name)
                    windowStatement.setString(2, scanId)
                    windowStatement.setString(3, accountIdentityDigest)
                    windowStatement.setString(4, feed.name)
                    windowStatement.executeUpdate()
                }
            }
        }
    }

    private fun readSyncStatus(
        connection: Connection,
        accountIdentityDigest: String,
        feed: HistoryFeed,
    ): HistorySyncStatus? = connection.prepareStatement(
        "SELECT * FROM history_sync_status WHERE account_identity_digest = ? AND feed = ?",
    ).use { statement ->
        statement.setString(1, accountIdentityDigest)
        statement.setString(2, feed.name)
        statement.executeQuery().use { resultSet -> if (resultSet.next()) resultSet.toSyncStatus() else null }
    }

    private fun insertTrade(connection: Connection, trade: HistoryTrade): Boolean {
        requireDigest(trade.accountIdentityDigest)
        require(trade.tradeId.isNotBlank())
        val payloadHash = tradePayloadHash(trade)
        val inserted = connection.prepareStatement(
            "INSERT OR IGNORE INTO history_trades(" +
                "account_identity_digest, trade_id, order_txid, post_txid, pair, base_asset, quote_currency, " +
                "fee_currency, time_ns, raw_time, type, order_type, price, cost, fee, volume, margin, misc, " +
                "trade_sequence_id, maker, ledger_ids, raw_record_json, has_valid_time, has_valid_price, " +
                "has_valid_cost, has_valid_fee, has_valid_volume, is_valid_record, payload_hash) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, trade.accountIdentityDigest)
            statement.setString(2, trade.tradeId)
            statement.setString(3, trade.orderTxid)
            statement.setString(4, trade.postTxid)
            statement.setString(5, trade.pair)
            statement.setString(6, trade.baseAsset)
            statement.setString(7, trade.quoteCurrency)
            statement.setString(8, trade.feeCurrency)
            setInstant(statement, 9, trade.time)
            statement.setString(10, trade.rawTime)
            statement.setString(11, trade.type)
            statement.setString(12, trade.orderType)
            statement.setString(13, trade.price)
            statement.setString(14, trade.cost)
            statement.setString(15, trade.fee)
            statement.setString(16, trade.volume)
            statement.setString(17, trade.margin)
            statement.setString(18, trade.misc)
            statement.setString(19, trade.tradeSequenceId)
            if (trade.maker == null) statement.setObject(20, null) else statement.setInt(20, if (trade.maker) 1 else 0)
            statement.setString(21, encodeHistoryStrings(trade.ledgerIds))
            statement.setString(22, trade.rawRecordJson)
            statement.setInt(23, if (trade.hasValidTime) 1 else 0)
            statement.setInt(24, if (trade.hasValidPrice) 1 else 0)
            statement.setInt(25, if (trade.hasValidCost) 1 else 0)
            statement.setInt(26, if (trade.hasValidFee) 1 else 0)
            statement.setInt(27, if (trade.hasValidVolume) 1 else 0)
            statement.setInt(28, if (trade.isValidRecord) 1 else 0)
            statement.setString(29, payloadHash)
            statement.executeUpdate() == 1
        }
        if (!inserted) {
            val existingHash = connection.prepareStatement(
                "SELECT payload_hash FROM history_trades WHERE account_identity_digest = ? AND trade_id = ?",
            ).use { statement ->
                statement.setString(1, trade.accountIdentityDigest)
                statement.setString(2, trade.tradeId)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next()) { "History trade idempotency key disappeared." }
                    resultSet.getString(1)
                }
            }
            check(existingHash == payloadHash) { "Exchange trade ${trade.tradeId} changed across duplicate pages." }
        }
        return inserted
    }

    private fun tradePayloadHash(trade: HistoryTrade): String = historyPayloadHash(
        listOf(
            trade.tradeId,
            trade.orderTxid,
            trade.postTxid,
            trade.pair,
            trade.baseAsset,
            trade.quoteCurrency,
            trade.feeCurrency,
            trade.time?.toString(),
            trade.rawTime,
            trade.type,
            trade.orderType,
            trade.price,
            trade.cost,
            trade.fee,
            trade.volume,
            trade.margin,
            trade.misc,
            trade.tradeSequenceId,
            trade.maker?.toString(),
            encodeHistoryStrings(trade.ledgerIds),
            trade.rawRecordJson,
            trade.hasValidTime.toString(),
            trade.hasValidPrice.toString(),
            trade.hasValidCost.toString(),
            trade.hasValidFee.toString(),
            trade.hasValidVolume.toString(),
            trade.isValidRecord.toString(),
        ),
    )

    private fun insertLedger(connection: Connection, ledger: HistoryLedger): Boolean {
        requireDigest(ledger.accountIdentityDigest)
        require(ledger.ledgerId.isNotBlank())
        val payloadHash = ledgerPayloadHash(ledger)
        val inserted = connection.prepareStatement(
            "INSERT OR IGNORE INTO history_ledgers(" +
                "account_identity_digest, ledger_id, refid, time_ns, raw_time, type, subtype, asset_class, asset, " +
                "amount, fee, balance, raw_record_json, has_valid_time, has_valid_amount, has_valid_fee, " +
                "has_valid_balance, is_valid_record, payload_hash) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, ledger.accountIdentityDigest)
            statement.setString(2, ledger.ledgerId)
            statement.setString(3, ledger.refid)
            setInstant(statement, 4, ledger.time)
            statement.setString(5, ledger.rawTime)
            statement.setString(6, ledger.type)
            statement.setString(7, ledger.subtype)
            statement.setString(8, ledger.assetClass)
            statement.setString(9, ledger.asset)
            statement.setString(10, ledger.amount)
            statement.setString(11, ledger.fee)
            statement.setString(12, ledger.balance)
            statement.setString(13, ledger.rawRecordJson)
            statement.setInt(14, if (ledger.hasValidTime) 1 else 0)
            statement.setInt(15, if (ledger.hasValidAmount) 1 else 0)
            statement.setInt(16, if (ledger.hasValidFee) 1 else 0)
            statement.setInt(17, if (ledger.hasValidBalance) 1 else 0)
            statement.setInt(18, if (ledger.isValidRecord) 1 else 0)
            statement.setString(19, payloadHash)
            statement.executeUpdate() == 1
        }
        if (!inserted) {
            val existingHash = connection.prepareStatement(
                "SELECT payload_hash FROM history_ledgers WHERE account_identity_digest = ? AND ledger_id = ?",
            ).use { statement ->
                statement.setString(1, ledger.accountIdentityDigest)
                statement.setString(2, ledger.ledgerId)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next()) { "History ledger idempotency key disappeared." }
                    resultSet.getString(1)
                }
            }
            check(existingHash == payloadHash) { "Exchange ledger ${ledger.ledgerId} changed across duplicate pages." }
        }
        insertBalanceCheckpoint(connection, ledger)
        return inserted
    }

    private fun ledgerPayloadHash(ledger: HistoryLedger): String = historyPayloadHash(
        listOf(
            ledger.ledgerId,
            ledger.refid,
            ledger.time?.toString(),
            ledger.rawTime,
            ledger.type,
            ledger.subtype,
            ledger.assetClass,
            ledger.asset,
            ledger.amount,
            ledger.fee,
            ledger.balance,
            ledger.rawRecordJson,
            ledger.hasValidTime.toString(),
            ledger.hasValidAmount.toString(),
            ledger.hasValidFee.toString(),
            ledger.hasValidBalance.toString(),
            ledger.isValidRecord.toString(),
        ),
    )

    private fun insertBalanceCheckpoint(connection: Connection, ledger: HistoryLedger) {
        val (status, quantity, reason) = validateLedgerBalance(ledger)
        connection.prepareStatement(
            "INSERT OR IGNORE INTO history_balance_checkpoints(" +
                "account_identity_digest, ledger_id, observed_at_ns, raw_asset_class, raw_asset_key, raw_amount, " +
                "raw_fee, raw_balance, quantity, status, reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, ledger.accountIdentityDigest)
            statement.setString(2, ledger.ledgerId)
            setInstant(statement, 3, ledger.time)
            statement.setString(4, ledger.assetClass)
            statement.setString(5, ledger.asset)
            statement.setString(6, ledger.amount)
            statement.setString(7, ledger.fee)
            statement.setString(8, ledger.balance)
            statement.setString(9, quantity?.toPlainString())
            statement.setString(10, status.name)
            statement.setString(11, reason)
            statement.executeUpdate()
        }
    }

    private fun validateLedgerBalance(ledger: HistoryLedger): Triple<HistoryBalanceStatus, BigDecimal?, String?> {
        if (ledger.balance ==
            null
        ) {
            return Triple(HistoryBalanceStatus.MISSING, null, "Ledger row has no balance field.")
        }
        val quantity = ledger.balance.toBigDecimalOrNull()
            ?: return Triple(HistoryBalanceStatus.INVALID, null, "Ledger balance is not a decimal.")
        if (ledger.time == null || !ledger.hasValidTime || ledger.asset.isNullOrBlank()) {
            return Triple(HistoryBalanceStatus.INVALID, null, "Ledger balance has no valid time or raw asset key.")
        }
        if (quantity.signum() < 0) {
            return Triple(HistoryBalanceStatus.INVALID, quantity, "Ledger balance is negative.")
        }
        if (!ledger.hasValidBalance || !ledger.isValidRecord) {
            return Triple(HistoryBalanceStatus.INVALID, quantity, "Ledger row failed native record validation.")
        }
        return Triple(HistoryBalanceStatus.VALID, quantity, null)
    }

    private fun queryDailyValues(
        connection: Connection,
        accountIdentityDigest: String,
        sourceAssetScope: String,
        from: Instant?,
        until: Instant?,
        limit: Int,
        offset: Int,
    ): HistoryPage<HistoryDailyValue> {
        val filter = dailyFilter(accountIdentityDigest, sourceAssetScope, from, until)
        val total = countRows(connection, "history_daily_values", filter)
        val headers = connection.prepareStatement(
            "SELECT * FROM history_daily_values ${filter.where} ORDER BY day_end_sec ASC LIMIT ? OFFSET ?",
        ).use { statement ->
            bindValues(statement, filter.values)
            statement.setInt(filter.values.size + 1, limit)
            statement.setInt(filter.values.size + 2, offset)
            statement.executeQuery().use { resultSet ->
                buildList { while (resultSet.next()) add(resultSet.toDailyHeader()) }
            }
        }
        val assetsByDay = readDailyAssets(connection, accountIdentityDigest, sourceAssetScope, headers)
        val values = headers.map { header -> header.copy(assets = assetsByDay[header.dayEnd.epochSecond].orEmpty()) }
        return HistoryPage(values, total, limit, offset, offset + values.size < total)
    }

    private fun queryDailyAssetValues(
        connection: Connection,
        accountIdentityDigest: String,
        sourceAssetScope: String,
        from: Instant?,
        until: Instant?,
        limit: Int,
        offset: Int,
    ): HistoryPage<HistoryDailyAssetValue> {
        val filter = dailyFilter(accountIdentityDigest, sourceAssetScope, from, until, "v")
        val joinWhere = "FROM history_daily_assets a JOIN history_daily_values v " +
            "ON v.account_identity_digest = a.account_identity_digest " +
            "AND v.source_asset_scope = a.source_asset_scope AND v.day_end_sec = a.day_end_sec ${filter.where}"
        val total = connection.prepareStatement("SELECT COUNT(*) $joinWhere").use { statement ->
            bindValues(statement, filter.values)
            statement.executeQuery().use { resultSet ->
                resultSet.next()
                resultSet.getLong(1)
            }
        }
        val values = connection.prepareStatement(
            "SELECT a.* $joinWhere ORDER BY v.day_end_sec ASC, a.raw_asset_key ASC LIMIT ? OFFSET ?",
        ).use { statement ->
            bindValues(statement, filter.values)
            statement.setInt(filter.values.size + 1, limit)
            statement.setInt(filter.values.size + 2, offset)
            statement.executeQuery().use { resultSet ->
                buildList { while (resultSet.next()) add(resultSet.toDailyAssetValue()) }
            }
        }
        return HistoryPage(values, total, limit, offset, offset + values.size < total)
    }

    private fun dailyFilter(
        accountIdentityDigest: String,
        sourceAssetScope: String,
        from: Instant?,
        until: Instant?,
        alias: String = "history_daily_values",
    ): SqlFilter {
        val conditions = mutableListOf("$alias.account_identity_digest = ?", "$alias.source_asset_scope = ?")
        val values = mutableListOf<Any?>(accountIdentityDigest, sourceAssetScope)
        from?.let {
            conditions += "$alias.day_end_sec >= ?"
            values += it.epochSecond
        }
        until?.let {
            conditions += "$alias.day_end_sec < ?"
            values += it.epochSecond
        }
        return SqlFilter(conditions, values)
    }

    private fun insertDailyValue(connection: Connection, value: HistoryDailyValue) {
        require((value.status == HistoryDailyValueStatus.COMPLETE) == (value.totalUsd != null)) {
            "Only complete daily values may expose an aggregate total."
        }
        connection.prepareStatement(
            "INSERT INTO history_daily_values(account_identity_digest, source_asset_scope, day_end_sec, total_usd, " +
                "status, source) VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(account_identity_digest, source_asset_scope, day_end_sec) DO UPDATE SET " +
                "total_usd = excluded.total_usd, status = excluded.status, source = excluded.source",
        ).use { statement ->
            statement.setString(1, value.accountIdentityDigest)
            statement.setString(2, value.sourceAssetScope)
            statement.setLong(3, value.dayEnd.epochSecond)
            statement.setString(4, value.totalUsd?.toPlainString())
            statement.setString(5, value.status.name)
            statement.setString(6, value.source)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "DELETE FROM history_daily_assets WHERE account_identity_digest = ? AND source_asset_scope = ? " +
                "AND day_end_sec = ?",
        ).use { statement ->
            statement.setString(1, value.accountIdentityDigest)
            statement.setString(2, value.sourceAssetScope)
            statement.setLong(3, value.dayEnd.epochSecond)
            statement.executeUpdate()
        }
        if (value.assets.isNotEmpty()) {
            connection.prepareStatement(
                "INSERT INTO history_daily_assets(account_identity_digest, source_asset_scope, day_end_sec, " +
                    "partition_key, raw_asset_class, raw_asset_key, quantity, price_usd, value_usd, price_pair, " +
                    "price_candle_open_sec, price_source, status, reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                value.assets.forEach { asset ->
                    statement.setString(1, value.accountIdentityDigest)
                    statement.setString(2, value.sourceAssetScope)
                    statement.setLong(3, value.dayEnd.epochSecond)
                    statement.setString(4, encodeHistoryAssetPartition(asset.rawAssetClass, asset.rawAssetKey))
                    statement.setString(5, asset.rawAssetClass)
                    statement.setString(6, asset.rawAssetKey)
                    statement.setString(7, asset.quantity?.toPlainString())
                    statement.setString(8, asset.priceUsd?.toPlainString())
                    statement.setString(9, asset.valueUsd?.toPlainString())
                    statement.setString(10, asset.pricePair)
                    if (asset.priceCandleOpen == null) {
                        statement.setObject(11, null)
                    } else {
                        statement.setLong(11, asset.priceCandleOpen.epochSecond)
                    }
                    statement.setString(12, asset.priceSource)
                    statement.setString(13, asset.status.name)
                    statement.setString(14, asset.reason)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    private fun readDailyAssets(
        connection: Connection,
        accountIdentityDigest: String,
        sourceAssetScope: String,
        headers: List<HistoryDailyValue>,
    ): Map<Long, List<HistoryDailyAssetValue>> {
        if (headers.isEmpty()) return emptyMap()
        val daySeconds = headers.map { it.dayEnd.epochSecond }.distinct()
        val placeholders = List(daySeconds.size) { "?" }.joinToString(",")
        return connection.prepareStatement(
            "SELECT * FROM history_daily_assets WHERE account_identity_digest = ? AND source_asset_scope = ? " +
                "AND day_end_sec IN ($placeholders) ORDER BY day_end_sec, raw_asset_class, raw_asset_key",
        ).use { statement ->
            statement.setString(1, accountIdentityDigest)
            statement.setString(2, sourceAssetScope)
            daySeconds.forEachIndexed { index, day -> statement.setLong(index + 3, day) }
            statement.executeQuery().use { resultSet ->
                buildMap<Long, MutableList<HistoryDailyAssetValue>> {
                    while (resultSet.next()) {
                        val asset = resultSet.toDailyAssetValue()
                        getOrPut(asset.dayEnd.epochSecond) { mutableListOf() }.add(asset)
                    }
                }
            }
        }
    }

    private fun validateDailyQuery(
        accountIdentityDigest: String,
        from: Instant?,
        until: Instant?,
        limit: Int,
        offset: Int,
    ) {
        requireDigest(accountIdentityDigest)
        require(from == null || until == null || from <= until)
        require(limit in 1..HISTORY_MAX_PAGE_LIMIT)
        require(offset >= 0)
    }

    private fun balancePartitionKey(checkpoint: HistoryBalanceCheckpoint): String =
        encodeHistoryAssetPartition(checkpoint.rawAssetClass, checkpoint.rawAssetKey.orEmpty())

    private fun epochSecondEndNanos(second: Long): Long = (second + 1) * 1_000_000_000L - 1

    private fun requireDigest(value: String) {
        require(value.matches(HEX_DIGEST)) { "History account scope must be a SHA-256 identity digest." }
    }

    private fun setInstant(statement: PreparedStatement, index: Int, value: Instant?) {
        if (value == null) statement.setObject(index, null) else statement.setLong(index, value.toEpochNanos())
    }

    private fun PreparedStatement.setNullableLong(index: Int, value: Long?) {
        if (value == null) setObject(index, null) else setLong(index, value)
    }

    private fun bumpDataRevision(connection: Connection) {
        connection.createStatement().use { statement ->
            check(
                statement.executeUpdate("UPDATE history_data_revision SET revision = revision + 1 WHERE id = 1") == 1,
            ) {
                "History data revision row is missing."
            }
        }
    }

    /**
     * Durable per-purpose job bookkeeping committed with each page: the cursor survives a
     * recent scan and a restart, and the completed-through horizon never regresses.
     */
    private fun persistJobProgress(
        connection: Connection,
        accountIdentityDigest: String,
        feed: HistoryFeed,
        purpose: HistorySyncPurpose,
        state: HistorySyncJobState,
        windowStartSec: Long?,
        windowEndSec: Long?,
        cursor: String?,
        completedThroughSec: Long?,
        lastError: String?,
        lastSuccessAt: Instant?,
        updatedAt: Instant,
    ) {
        val existing = connection.prepareStatement(
            "SELECT window_start_sec, window_end_sec, completed_through_sec FROM history_sync_jobs " +
                "WHERE account_identity_digest = ? AND feed = ? AND purpose = ?",
        ).use { statement ->
            statement.setString(1, accountIdentityDigest)
            statement.setString(2, feed.name)
            statement.setString(3, purpose.name)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    null
                } else {
                    Triple(
                        resultSet.getNullableLong("window_start_sec"),
                        resultSet.getNullableLong("window_end_sec"),
                        resultSet.getNullableLong("completed_through_sec"),
                    )
                }
            }
        }
        // A backfill keeps its frozen window across API-key clamping. A scan that started later than
        // the frozen window imported the permitted part of it, so the job completes for that range
        // instead of replaying an already imported range; a wider key reopens the window.
        val backfill = purpose == HistorySyncPurpose.BACKFILL
        val frozenStart = if (backfill) existing?.first ?: windowStartSec else windowStartSec
        val frozenEnd = if (backfill) existing?.second ?: windowEndSec else windowEndSec
        val coversFrozenStart = windowStartSec == null || frozenStart == null || windowStartSec <= frozenStart
        val coversFrozenEnd = windowEndSec == null || frozenEnd == null || windowEndSec >= frozenEnd
        val effectiveStart = if (backfill && !coversFrozenStart) windowStartSec else frozenStart
        val effectiveEnd = if (backfill && !coversFrozenEnd) windowEndSec else frozenEnd
        val effectiveState = if (state == HistorySyncJobState.COMPLETE && !coversFrozenEnd) {
            HistorySyncJobState.RUNNING
        } else {
            state
        }
        val effectiveCompleted = when {
            !backfill -> completedThroughSec
            effectiveState != HistorySyncJobState.COMPLETE -> existing?.third
            else -> effectiveEnd
        }
        connection.prepareStatement(
            "INSERT INTO history_sync_jobs(" +
                "account_identity_digest, feed, purpose, state, window_start_sec, window_end_sec, cursor, " +
                "completed_through_sec, last_error, next_retry_at_ns, last_success_at_ns, updated_at_ns) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?) " +
                "ON CONFLICT(account_identity_digest, feed, purpose) DO UPDATE SET " +
                "state = excluded.state, window_start_sec = excluded.window_start_sec, " +
                "window_end_sec = excluded.window_end_sec, cursor = excluded.cursor, " +
                "completed_through_sec = CASE " +
                "WHEN excluded.completed_through_sec IS NULL THEN history_sync_jobs.completed_through_sec " +
                "WHEN history_sync_jobs.completed_through_sec IS NULL THEN excluded.completed_through_sec " +
                "WHEN excluded.completed_through_sec > history_sync_jobs.completed_through_sec " +
                "THEN excluded.completed_through_sec ELSE history_sync_jobs.completed_through_sec END, " +
                "last_error = excluded.last_error, " +
                "last_success_at_ns = COALESCE(excluded.last_success_at_ns, history_sync_jobs.last_success_at_ns), " +
                "updated_at_ns = excluded.updated_at_ns",
        ).use { statement ->
            statement.setString(1, accountIdentityDigest)
            statement.setString(2, feed.name)
            statement.setString(3, purpose.name)
            statement.setString(4, effectiveState.name)
            statement.setNullableLong(5, effectiveStart)
            statement.setNullableLong(6, effectiveEnd)
            statement.setString(7, cursor)
            statement.setNullableLong(8, effectiveCompleted)
            statement.setString(9, lastError)
            setInstant(statement, 10, lastSuccessAt)
            statement.setLong(11, updatedAt.toEpochNanos())
            statement.executeUpdate()
        }
    }

    /**
     * Merges one verified interval into the durable coverage of a feed. Overlapping and adjacent
     * intervals collapse into one segment, so the stored union stays small and gap free.
     */
    private fun mergeCoverageSegment(
        connection: Connection,
        accountIdentityDigest: String,
        feed: HistoryFeed,
        startSec: Long,
        endSec: Long,
        updatedAtNs: Long,
    ) {
        val overlapping = mutableListOf<LongRange>()
        connection.prepareStatement(
            "SELECT start_sec, end_sec FROM history_sync_coverage WHERE account_identity_digest = ? " +
                "AND feed = ? AND end_sec >= ? AND start_sec <= ?",
        ).use { statement ->
            statement.setString(1, accountIdentityDigest)
            statement.setString(2, feed.name)
            statement.setLong(3, startSec - 1)
            statement.setLong(4, endSec + 1)
            statement.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    overlapping += resultSet.getLong("start_sec")..resultSet.getLong("end_sec")
                }
            }
        }
        val mergedStart = minOf(startSec, overlapping.minOfOrNull { it.first } ?: startSec)
        val mergedEnd = maxOf(endSec, overlapping.maxOfOrNull { it.last } ?: endSec)
        connection.prepareStatement(
            "DELETE FROM history_sync_coverage WHERE account_identity_digest = ? AND feed = ? " +
                "AND end_sec >= ? AND start_sec <= ?",
        ).use { statement ->
            statement.setString(1, accountIdentityDigest)
            statement.setString(2, feed.name)
            statement.setLong(3, mergedStart - 1)
            statement.setLong(4, mergedEnd + 1)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO history_sync_coverage(account_identity_digest, feed, start_sec, end_sec, updated_at_ns) " +
                "VALUES (?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, accountIdentityDigest)
            statement.setString(2, feed.name)
            statement.setLong(3, mergedStart)
            statement.setLong(4, mergedEnd)
            statement.setLong(5, updatedAtNs)
            statement.executeUpdate()
        }
    }

    private fun ResultSet.toSyncJob() = HistorySyncJob(
        accountIdentityDigest = getString("account_identity_digest"),
        feed = HistoryFeed.valueOf(getString("feed")),
        purpose = HistorySyncPurpose.valueOf(getString("purpose")),
        state = HistorySyncJobState.valueOf(getString("state")),
        windowStartSec = getNullableLong("window_start_sec"),
        windowEndSec = getNullableLong("window_end_sec"),
        cursor = getString("cursor"),
        completedThroughSec = getNullableLong("completed_through_sec"),
        lastError = getString("last_error"),
        nextRetryAt = getNullableLong("next_retry_at_ns")?.toInstantNanos(),
        lastSuccessAt = getNullableLong("last_success_at_ns")?.toInstantNanos(),
        updatedAt = getLong("updated_at_ns").toInstantNanos(),
    )

    private fun <T> Connection.inTransaction(block: () -> T): T = transaction(block)

    private fun <T> Connection.transaction(block: () -> T): T {
        val originalAutoCommit = autoCommit
        autoCommit = false
        return try {
            block().also { commit() }
        } catch (error: Exception) {
            rollback()
            throw error
        } finally {
            autoCommit = originalAutoCommit
        }
    }

    private class SqlFilter(private val conditions: List<String>, val values: List<Any?>) {
        val where: String
            get() = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
    }

    private class ObservationHeader(
        val observationId: String,
        private val cycleId: String,
        private val sourceScope: HistorySourceScope,
        private val walletScope: String,
        private val balanceSource: String,
        private val priceSource: String,
        private val valuationCurrency: String,
        private val sampleSelection: HistorySampleSelection,
        val observedAt: Instant,
        private val balanceRequestStartedAt: Instant,
        private val balanceResponseEndedAt: Instant,
        private val priceRequestStartedAt: Instant,
        private val priceResponseEndedAt: Instant,
        private val persistedAt: Instant?,
        private val status: HistoryObservationStatus,
        private val totalUsd: BigDecimal?,
        private val incompleteReasons: List<String>,
    ) {
        fun toObservation(assets: List<HistoryAssetValue>) = HistoryObservation(
            observationId = observationId,
            cycleId = cycleId,
            sourceScope = sourceScope,
            walletScope = walletScope,
            balanceSource = balanceSource,
            priceSource = priceSource,
            valuationCurrency = valuationCurrency,
            sampleSelection = sampleSelection,
            observedAt = observedAt,
            balanceRequestStartedAt = balanceRequestStartedAt,
            balanceResponseEndedAt = balanceResponseEndedAt,
            priceRequestStartedAt = priceRequestStartedAt,
            priceResponseEndedAt = priceResponseEndedAt,
            persistedAt = persistedAt,
            status = status,
            totalUsd = totalUsd,
            incompleteReasons = incompleteReasons,
            assets = assets,
        )
    }

    private fun List<ObservationHeader>.loadObservationAssets(connection: Connection): List<HistoryObservation> {
        if (isEmpty()) return emptyList()
        val ids = map { it.observationId }
        val placeholders = List(ids.size) { "?" }.joinToString(",")
        val assetsByObservation = connection.prepareStatement(
            "SELECT * FROM history_observation_assets WHERE observation_id IN ($placeholders) " +
                "ORDER BY observation_id, asset_symbol",
        ).use { statement ->
            ids.forEachIndexed { index, id -> statement.setString(index + 1, id) }
            statement.executeQuery().use { resultSet ->
                buildMap<String, MutableList<HistoryAssetValue>> {
                    while (resultSet.next()) {
                        val asset = resultSet.toHistoryAssetValue()
                        getOrPut(resultSet.getString("observation_id")) { mutableListOf() }.add(asset)
                    }
                }
            }
        }
        return map { header -> header.toObservation(assetsByObservation[header.observationId].orEmpty()) }
    }

    private fun ResultSet.toObservationHeader() = ObservationHeader(
        observationId = getString("observation_id"),
        cycleId = getString("cycle_id"),
        sourceScope = toSourceScope(),
        walletScope = getString("wallet_scope"),
        balanceSource = getString("balance_source"),
        priceSource = getString("price_source"),
        valuationCurrency = getString("valuation_currency"),
        sampleSelection = HistorySampleSelection.valueOf(getString("sample_selection")),
        observedAt = getLong("observed_at_ns").toInstantNanos(),
        balanceRequestStartedAt = getLong("balance_request_started_at_ns").toInstantNanos(),
        balanceResponseEndedAt = getLong("balance_response_ended_at_ns").toInstantNanos(),
        priceRequestStartedAt = getLong("price_request_started_at_ns").toInstantNanos(),
        priceResponseEndedAt = getLong("price_response_ended_at_ns").toInstantNanos(),
        persistedAt = getNullableLong("persisted_at_ns")?.toInstantNanos(),
        status = HistoryObservationStatus.valueOf(getString("status")),
        totalUsd = getString("total_usd")?.toBigDecimalOrNull(),
        incompleteReasons = decodeHistoryStrings(getString("incomplete_reasons")),
    )

    private fun ResultSet.toSourceScope(): HistorySourceScope = HistorySourceScope(
        mode = HistorySourceMode.valueOf(getString("source_mode")),
        managedScopeFingerprint = getString("managed_scope_fingerprint"),
        managedScopeSymbols = decodeHistoryStrings(getString("managed_scope_symbols")),
        accountIdentityDigest = getString("account_identity_digest"),
        simulationSessionId = getString("simulation_session_id"),
    )

    private fun ResultSet.toHistoryAssetValue() = HistoryAssetValue(
        symbol = getString("asset_symbol"),
        status = HistoryAssetValueStatus.valueOf(getString("status")),
        rawBalanceKeys = decodeHistoryStrings(getString("raw_balance_keys")),
        rawBalanceValues = decodeHistoryPairs(getString("raw_balance_values")),
        quantity = getString("quantity")?.toBigDecimalOrNull(),
        requestedPair = getString("requested_pair"),
        responsePair = getString("response_pair"),
        candidateResponsePairs = decodeHistoryStrings(getString("candidate_response_pairs")),
        candidateRawPrices = decodeHistoryPairs(getString("candidate_raw_prices")),
        rawPrice = getString("raw_price"),
        priceUsd = getString("price_usd")?.toBigDecimalOrNull(),
        valueUsd = getString("value_usd")?.toBigDecimalOrNull(),
        reason = getString("reason"),
    )

    private fun ResultSet.toCycleActivity() = HistoryCycleActivity(
        cycleId = getString("cycle_id"),
        sourceScope = toSourceScope(),
        occurredAt = getLong("occurred_at_ns").toInstantNanos(),
        dryRun = getInt("dry_run") != 0,
        actions = decodeHistoryStrings(getString("actions")),
        outcome = getString("outcome")?.let { value -> HistoryCycleOutcome.valueOf(value) },
    )

    private fun ResultSet.toHistoryTrade() = HistoryTrade(
        accountIdentityDigest = getString("account_identity_digest"),
        tradeId = getString("trade_id"),
        orderTxid = getString("order_txid"),
        postTxid = getString("post_txid"),
        pair = getString("pair"),
        baseAsset = getString("base_asset"),
        quoteCurrency = getString("quote_currency"),
        feeCurrency = getString("fee_currency"),
        time = getNullableLong("time_ns")?.toInstantNanos(),
        rawTime = getString("raw_time"),
        type = getString("type"),
        orderType = getString("order_type"),
        price = getString("price"),
        cost = getString("cost"),
        fee = getString("fee"),
        volume = getString("volume"),
        margin = getString("margin"),
        misc = getString("misc"),
        tradeSequenceId = getString("trade_sequence_id"),
        maker = getNullableInt("maker")?.let { it != 0 },
        ledgerIds = decodeHistoryStrings(getString("ledger_ids")),
        rawRecordJson = getString("raw_record_json"),
        hasValidTime = getInt("has_valid_time") != 0,
        hasValidPrice = getInt("has_valid_price") != 0,
        hasValidCost = getInt("has_valid_cost") != 0,
        hasValidFee = getInt("has_valid_fee") != 0,
        hasValidVolume = getInt("has_valid_volume") != 0,
        isValidRecord = getInt("is_valid_record") != 0,
    )

    private fun ResultSet.toHistoryLedger() = HistoryLedger(
        accountIdentityDigest = getString("account_identity_digest"),
        ledgerId = getString("ledger_id"),
        refid = getString("refid"),
        time = getNullableLong("time_ns")?.toInstantNanos(),
        rawTime = getString("raw_time"),
        type = getString("type"),
        subtype = getString("subtype"),
        assetClass = getString("asset_class"),
        asset = getString("asset"),
        amount = getString("amount"),
        fee = getString("fee"),
        balance = getString("balance"),
        rawRecordJson = getString("raw_record_json"),
        hasValidTime = getInt("has_valid_time") != 0,
        hasValidAmount = getInt("has_valid_amount") != 0,
        hasValidFee = getInt("has_valid_fee") != 0,
        hasValidBalance = getInt("has_valid_balance") != 0,
        isValidRecord = getInt("is_valid_record") != 0,
    )

    private fun ResultSet.toBalanceCheckpoint() = HistoryBalanceCheckpoint(
        accountIdentityDigest = getString("account_identity_digest"),
        ledgerId = getString("ledger_id"),
        observedAt = getNullableLong("observed_at_ns")?.toInstantNanos(),
        rawAssetClass = getString("raw_asset_class"),
        rawAssetKey = getString("raw_asset_key"),
        rawAmount = getString("raw_amount"),
        rawFee = getString("raw_fee"),
        rawBalance = getString("raw_balance"),
        quantity = getString("quantity")?.toBigDecimalOrNull(),
        status = HistoryBalanceStatus.valueOf(getString("status")),
        reason = getString("reason"),
    )

    private fun ResultSet.toSyncStatus() = HistorySyncStatus(
        accountIdentityDigest = getString("account_identity_digest"),
        feed = HistoryFeed.valueOf(getString("feed")),
        scanId = getString("scan_id"),
        state = HistorySyncState.valueOf(getString("state")),
        windowStart = Instant.ofEpochSecond(getLong("window_start_sec")),
        windowEnd = Instant.ofEpochSecond(getLong("window_end_sec")),
        nextCursor = getString("next_cursor"),
        fetchedCount = getLong("fetched_count"),
        storedCount = getLong("stored_count"),
        knownTotal = getNullableLong("known_total"),
        lastPageAt = getNullableLong("last_page_at_ns")?.toInstantNanos(),
        lastError = getString("last_error"),
    )

    private fun ResultSet.toDailyPrice() = HistoryDailyPrice(
        accountIdentityDigest = getString("account_identity_digest"),
        rawAssetKey = getString("raw_asset_key"),
        pair = getString("pair"),
        candleOpen = Instant.ofEpochSecond(getLong("candle_open_sec")),
        candleEnd = Instant.ofEpochSecond(getLong("candle_end_sec")),
        closeUsd = getString("close_usd").toBigDecimal(),
        source = getString("source"),
    )

    private fun ResultSet.toDailyHeader() = HistoryDailyValue(
        dayEnd = Instant.ofEpochSecond(getLong("day_end_sec")),
        accountIdentityDigest = getString("account_identity_digest"),
        sourceAssetScope = getString("source_asset_scope"),
        totalUsd = getString("total_usd")?.toBigDecimalOrNull(),
        status = HistoryDailyValueStatus.valueOf(getString("status")),
        source = getString("source"),
        assets = emptyList(),
    )

    private fun ResultSet.toDailyAssetValue() = HistoryDailyAssetValue(
        dayEnd = Instant.ofEpochSecond(getLong("day_end_sec")),
        accountIdentityDigest = getString("account_identity_digest"),
        rawAssetClass = getString("raw_asset_class"),
        rawAssetKey = getString("raw_asset_key"),
        quantity = getString("quantity")?.toBigDecimalOrNull(),
        priceUsd = getString("price_usd")?.toBigDecimalOrNull(),
        valueUsd = getString("value_usd")?.toBigDecimalOrNull(),
        pricePair = getString("price_pair"),
        priceCandleOpen = getNullableLong("price_candle_open_sec")?.let(Instant::ofEpochSecond),
        priceSource = getString("price_source"),
        status = HistoryDailyValueStatus.valueOf(getString("status")),
        reason = getString("reason"),
    )

    private fun ResultSet.getNullableLong(column: String): Long? = getLong(column).let { if (wasNull()) null else it }

    private fun ResultSet.getNullableInt(column: String): Int? = getInt(column).let { if (wasNull()) null else it }

    private companion object {
        const val MAX_CYCLE_ACTIVITY_LIMIT = 100
        val HEX_DIGEST = Regex("[0-9a-f]{64}")
    }
}
