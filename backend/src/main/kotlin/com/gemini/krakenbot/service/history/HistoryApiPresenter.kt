package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.service.reporting.HistoryReportingPeriod
import com.gemini.krakenbot.service.reporting.HistoryReportingService
import com.gemini.krakenbot.service.reporting.targetPercentsBySymbol
import com.gemini.krakenbot.view.util.ViewText
import java.math.BigDecimal
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Base64

const val HISTORY_API_MAX_ACTIVITY_LIMIT = 120
const val HISTORY_API_DEFAULT_ACTIVITY_LIMIT = 50

private const val DEFAULT_PRESET_KEY = "30d"
private const val EPOCH_SECONDS_PATTERN = "-?\\d{1,18}"
private const val CURSOR_SEPARATOR = "\n"

/** Ledger families that carry owner capital in and out of the account. */
private val FUNDING_LEDGER_TYPES = listOf(
    KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
    KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
)

/** Ledger families that can pay a reward; the row filter still decides which rows count. */
private val REWARD_LEDGER_TYPES = listOf(
    KrakenApiConstants.LEDGER_TYPE_STAKING,
    KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
    KrakenApiConstants.LEDGER_TYPE_EARN,
    KrakenApiConstants.LEDGER_TYPE_REWARD,
    KrakenApiConstants.LEDGER_TYPE_TRANSFER,
)

private val OBSERVATION_CHART_SELECTIONS = setOf(
    HistoryChartSelection.PORTFOLIO,
    HistoryChartSelection.HOLDINGS,
    HistoryChartSelection.ALLOCATION,
)

private data class ActivityCursor(val occurredAt: Instant, val cycleId: String)

/** Read-side presenter for the History JSON APIs: one validated range contract and scope path. */
class HistoryApiPresenter(
    private val historyService: HistoryService,
    private val historyStore: HistoryStore,
    private val reportingService: HistoryReportingService,
    private val syncCoordinator: HistorySyncCoordinator,
    private val nowProvider: () -> Instant = Instant::now,
) {
    suspend fun charts(
        params: HistoryApiRangeParams,
        currentScope: HistorySourceScope,
        selectionParam: String?,
        scopeId: String?,
        maxPoints: Int?,
        targetAllocations: List<Allocation> = emptyList(),
    ): HistoryApiChartsResponse {
        val startedNanos = System.nanoTime()
        val window = resolveWindow(params, nowProvider())
        val selection = HistoryChartSelection.fromParam(selectionParam)
            ?: throw HistoryApiRequestException(ViewText.HISTORY_API_SELECTION_UNKNOWN)
        val pointCap = (maxPoints ?: HISTORY_MAX_CHART_POINTS).coerceIn(2, HISTORY_MAX_CHART_POINTS)
        if (scopeId.isNullOrBlank() && currentScope.mode == HistorySourceMode.ACCOUNT &&
            selection == HistoryChartSelection.PORTFOLIO
        ) {
            return recordedPortfolioCharts(currentScope, window, pointCap, startedNanos)
        }
        val scope = resolveScope(scopeId, currentScope)
        val observations = historyService.queryChartObservations(scope, window.from, window.until, pointCap)
        val intervalTotal = historyService.queryValues(
            HistoryRangeQuery(from = window.from, until = window.until, sourceScope = scope, limit = 1),
        ).total ?: observations.size.toLong()
        val series = buildSeries(selection, scope, window, observations, pointCap, targetAllocations)
        val pointCount = series.maxOfOrNull { it.points.size } ?: 0
        return HistoryApiChartsResponse(
            range = window.rangeDto(),
            scope = scopeDto(scope, currentScope),
            coverage = buildCoverage(scope),
            series = series,
            fullRange = HistoryApiFullRange(
                fromIso = window.selectedFrom?.toString(),
                toIso = window.selectedUntil.toString(),
                pointCount = pointCount,
                totalObservations = intervalTotal,
                representative = selection in OBSERVATION_CHART_SELECTIONS &&
                    observations.size.toLong() < intervalTotal,
                baselineFromIso = window.baselineFrom?.toString(),
            ),
            generationMs = elapsedMs(startedNanos),
            scopeSelection = if (scopeId.isNullOrBlank()) {
                HistoryApiScopeSelection.CURRENT_SCOPE
            } else {
                HistoryApiScopeSelection.EXPLICIT_SCOPE
            },
        )
    }

    private suspend fun recordedPortfolioCharts(
        currentScope: HistorySourceScope,
        window: HistoryApiWindow,
        pointCap: Int,
        startedNanos: Long,
    ): HistoryApiChartsResponse {
        val digest = checkNotNull(currentScope.accountIdentityDigest)
        val scopes = (
            historyStore.listRecordedScopes(digest).filter {
                it.mode == HistorySourceMode.ACCOUNT && it.accountIdentityDigest == digest
            }.map { it.toSourceScope() } + currentScope
            ).distinctBy { it.sourceKey }
        val coverages = scopes.associateWith { historyStore.valuesCoverage(it) }
        val scopeDtos = scopes.map { scope ->
            val coverage = coverages.getValue(scope)
            HistoryApiScope(
                id = scope.sourceKey,
                symbols = scope.managedScopeSymbols.sorted(),
                earliestIso = coverage.firstRecordedAt?.toString(),
                latestIso = coverage.lastRecordedAt?.toString(),
                current = scope.sourceKey == currentScope.sourceKey,
            )
        }
        val observations = scopes.associate { scope ->
            scope.sourceKey to historyService.queryChartObservations(scope, window.from, window.until, pointCap)
        }
        val totals = scopes.map { scope ->
            historyService.queryValues(
                HistoryRangeQuery(from = window.from, until = window.until, sourceScope = scope, limit = 1),
            ).total ?: observations.getValue(scope.sourceKey).size.toLong()
        }
        val episodes = recordedEpisodes(observations)
        val conflicts = recordedConflictInstants(observations)
        val boundaries = recordedScopeBoundaries(episodes, conflicts, window)
        val series = scopeDtos.map { scope ->
            recordedPortfolioSeries(scope, episodes, conflicts)
        }
        val incompleteCount = coverages.values.sumOf { it.incompleteCount }
        val firstRecordedAt = coverages.values.mapNotNull { it.firstRecordedAt }.minOrNull()
        val lastRecordedAt = coverages.values.mapNotNull { it.lastRecordedAt }.maxOrNull()
        return HistoryApiChartsResponse(
            range = window.rangeDto(),
            scope = scopeDto(currentScope, currentScope),
            coverage = buildCoverage(currentScope).copy(
                valuesFirstIso = firstRecordedAt?.toString(),
                valuesLastIso = lastRecordedAt?.toString(),
                incomplete = incompleteCount > 0 || firstRecordedAt == null || boundaries.isNotEmpty(),
                gapCount = maxOf(incompleteCount, boundaries.size.toLong())
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt(),
            ),
            series = series,
            fullRange = HistoryApiFullRange(
                fromIso = window.selectedFrom?.toString(),
                toIso = window.selectedUntil.toString(),
                pointCount = series.maxOfOrNull { it.points.size } ?: 0,
                totalObservations = totals.sum(),
                representative = observations.values.sumOf { it.size.toLong() } < totals.sum(),
            ),
            generationMs = elapsedMs(startedNanos),
            scopeSelection = HistoryApiScopeSelection.ACCOUNT_RECORDED,
            recordedScopes = scopeDtos,
            boundaries = boundaries,
        )
    }

    suspend fun summary(
        params: HistoryApiRangeParams,
        currentScope: HistorySourceScope,
        scopeId: String?,
        targetAllocations: List<Allocation>,
    ): HistoryApiSummaryResponse {
        val startedNanos = System.nanoTime()
        val window = resolveWindow(params, nowProvider())
        val scope = resolveScope(scopeId, currentScope)
        val period = HistoryReportingPeriod(sourceScope = scope, from = window.from, until = window.until)
        return HistoryApiSummaryResponse(
            range = window.rangeDto(),
            scope = scopeDto(scope, currentScope),
            coverage = buildCoverage(scope),
            statistics = reportingService.computeStatistics(period, targetAllocations),
            generationMs = elapsedMs(startedNanos),
            scopeSelection = if (scopeId.isNullOrBlank()) {
                HistoryApiScopeSelection.CURRENT_SCOPE
            } else {
                HistoryApiScopeSelection.EXPLICIT_SCOPE
            },
        )
    }

    suspend fun scopes(currentScope: HistorySourceScope): HistoryApiScopesResponse {
        val digest = currentScope.accountIdentityDigest
        val records = if (digest == null) emptyList() else historyStore.listRecordedScopes(digest)
        val scopes = records.map { record -> scopeDto(record.toSourceScope(), currentScope) }
        return HistoryApiScopesResponse(scopes = scopes)
    }

    suspend fun activities(
        params: HistoryApiRangeParams,
        currentScope: HistorySourceScope,
        scopeId: String?,
        modeParam: String?,
        cursor: String?,
        limit: Int?,
    ): HistoryApiActivitiesResponse {
        val window = resolveWindowAtCutoff(params)
        val scope = resolveActivityScope(scopeId, modeParam, currentScope)
        val pageSize = (limit ?: HISTORY_API_DEFAULT_ACTIVITY_LIMIT).coerceIn(1, HISTORY_API_MAX_ACTIVITY_LIMIT)
        val after = cursor?.trim()?.takeIf(String::isNotEmpty)?.let(::decodeCursor)
        val (rows, hasMore) = fetchActivities(scope, window, pageSize, after)
        return HistoryApiActivitiesResponse(
            range = window.rangeDto(),
            items = rows.map(::activityRow),
            nextCursor = if (hasMore) rows.lastOrNull()?.let(::encodeCursor) else null,
            hasMore = hasMore,
        )
    }

    suspend fun eventExportQuery(
        params: HistoryApiRangeParams,
        currentScope: HistorySourceScope,
        scopeId: String?,
        modeParam: String?,
        assetKey: String?,
        pair: String?,
        type: String?,
    ): HistoryApiExportQuery<HistoryEventQuery> {
        val window = resolveWindowAtCutoff(params)
        val scope = resolveActivityScope(scopeId, modeParam, currentScope)
        val digest = scope.accountIdentityDigest
            ?.takeIf { scope.mode == HistorySourceMode.ACCOUNT }
            ?: throw HistoryApiRequestException(ViewText.HISTORY_API_SCOPE_MODE_MISMATCH)
        return HistoryApiExportQuery(
            query = HistoryEventQuery(
                from = window.from,
                until = window.until,
                accountIdentityDigest = digest,
                assetKey = assetKey?.trim()?.takeIf(String::isNotEmpty),
                pair = pair?.trim()?.takeIf(String::isNotEmpty),
                type = type?.trim()?.takeIf(String::isNotEmpty),
            ),
            zone = window.zone,
        )
    }

    suspend fun activityExportQuery(
        params: HistoryApiRangeParams,
        currentScope: HistorySourceScope,
        scopeId: String?,
        modeParam: String?,
    ): HistoryApiExportQuery<HistoryActivityQuery> {
        val window = resolveWindowAtCutoff(params)
        val scope = resolveActivityScope(scopeId, modeParam, currentScope)
        return HistoryApiExportQuery(
            query = HistoryActivityQuery(
                sourceScope = scope,
                from = window.from,
                until = window.until,
            ),
            zone = window.zone,
        )
    }

    suspend fun dataStatus(
        params: HistoryApiRangeParams,
        currentScope: HistorySourceScope,
    ): HistoryApiDataStatusResponse {
        val window = resolveWindow(params, nowProvider())
        val digest = currentScope.accountIdentityDigest
        val jobs = if (digest == null) emptyList() else historyStore.loadSyncJobs(digest)
        val progress = if (digest == null) emptyList() else historyStore.syncProgress(digest)
        return HistoryApiDataStatusResponse(
            range = window.rangeDto(),
            dataRevision = historyStore.currentDataRevision(),
            blockedReason = syncCoordinator.blockedReason(),
            syncFailure = syncCoordinator.lastFailure(),
            nextRetryAtIso = syncCoordinator.nextRetryAt()?.toString(),
            coverage = buildCoverage(currentScope),
            jobs = jobs.map(::syncJobDto),
            progress = progress.map(::syncProgressDto),
        )
    }

    fun resolveRepairWindow(params: HistoryApiRepairParams): HistoryApiRepairWindow {
        val zone = resolveZone(params.tz)
        val date = params.date?.trim()?.takeIf(String::isNotEmpty)
        if (date != null) {
            val day = parseLocalDateOrThrow(date, ViewText.HISTORY_API_RANGE_INVALID_DATE)
            return HistoryApiRepairWindow(
                start = day.atStartOfDay(zone).toInstant(),
                end = day.plusDays(1).atStartOfDay(zone).toInstant(),
            )
        }
        val startRaw = params.start?.trim()?.takeIf(String::isNotEmpty)
        val endRaw = params.end?.trim()?.takeIf(String::isNotEmpty)
        if (startRaw == null || endRaw == null) {
            throw HistoryApiRequestException(ViewText.HISTORY_API_REPAIR_WINDOW_REQUIRED)
        }
        val start = parseLocalDateOrThrow(startRaw, ViewText.HISTORY_API_RANGE_INVALID_DATE)
        val end = parseLocalDateOrThrow(endRaw, ViewText.HISTORY_API_RANGE_INVALID_DATE)
        if (start.isAfter(end)) throw HistoryApiRequestException(ViewText.HISTORY_API_RANGE_INVERTED)
        return HistoryApiRepairWindow(
            start = start.atStartOfDay(zone).toInstant(),
            end = end.plusDays(1).atStartOfDay(zone).toInstant(),
        )
    }

    private fun resolveWindow(params: HistoryApiRangeParams, now: Instant): HistoryApiWindow {
        val zone = resolveZone(params.tz)
        val presetKey = params.preset?.trim()?.takeIf(String::isNotEmpty) ?: DEFAULT_PRESET_KEY
        val preset = HistoryRangePreset.fromKey(presetKey)
            ?: throw HistoryApiRequestException(ViewText.HISTORY_API_RANGE_UNKNOWN_PRESET)
        val selection = when (preset) {
            HistoryRangePreset.CUSTOM -> {
                val startRaw = params.start?.trim()?.takeIf(String::isNotEmpty)
                val endRaw = params.end?.trim()?.takeIf(String::isNotEmpty)
                if (startRaw == null || endRaw == null) {
                    throw HistoryApiRequestException(ViewText.HISTORY_API_RANGE_CUSTOM_DATES_REQUIRED)
                }
                val start = parseLocalDateOrThrow(startRaw, ViewText.HISTORY_API_RANGE_INVALID_DATE)
                val end = parseLocalDateOrThrow(endRaw, ViewText.HISTORY_API_RANGE_INVALID_DATE)
                if (start.isAfter(end)) throw HistoryApiRequestException(ViewText.HISTORY_API_RANGE_INVERTED)
                HistoryRangeSelection.custom(start, end)
            }

            else -> HistoryRangeSelection.rolling(preset)
        }
        val resolved = HistoryRanges.resolve(selection, now, zone)
        val requestedFrom = params.from?.trim()?.takeIf(String::isNotEmpty)?.let { parseInstant(it, zone) }
        val requestedTo = params.to?.trim()?.takeIf(String::isNotEmpty)?.let {
            parseInstant(it, zone, inclusiveDate = true)
        }
        if (requestedFrom != null && requestedTo != null && !requestedFrom.isBefore(requestedTo)) {
            throw HistoryApiRequestException(ViewText.HISTORY_API_RANGE_INVERTED)
        }
        val from = maxOfNullable(requestedFrom, resolved.from)
        val until = minOf(requestedTo ?: resolved.until, resolved.until)
        if (from != null && !from.isBefore(until)) {
            throw HistoryApiRequestException(ViewText.HISTORY_API_RANGE_INVERTED)
        }
        return HistoryApiWindow(
            preset = presetKey,
            zone = zone,
            selectedFrom = resolved.from,
            selectedUntil = resolved.until,
            from = from,
            until = until,
            now = now,
            baselineFrom = resolveBaselineFrom(params, resolved.from, until),
        )
    }

    /**
     * Cumulative series are measured from the start of the selected period. A viewport request may
     * pin that baseline explicitly, so zooming keeps the selected period's running total instead
     * of restarting the accumulation inside the zoom.
     */
    private fun resolveBaselineFrom(params: HistoryApiRangeParams, selectedFrom: Instant?, until: Instant): Instant? {
        val requested = params.baselineFrom?.trim()?.takeIf(String::isNotEmpty)
            ?.let { parseInstant(it, UTC_ZONE) }
        val baseline = requested ?: selectedFrom
        if (baseline == null) return null
        if (!baseline.isBefore(until)) return selectedFrom
        // A baseline after the period start would restart the accumulation inside the period and
        // drop the capital that arrived before it.
        if (selectedFrom != null && baseline.isAfter(selectedFrom)) return selectedFrom
        return baseline
    }

    private fun resolveWindowAtCutoff(params: HistoryApiRangeParams): HistoryApiWindow {
        val window = resolveWindow(params, nowProvider())
        val cutoff = minOf(window.until, window.now)
        if (window.from != null && !window.from.isBefore(cutoff)) {
            throw HistoryApiRequestException(ViewText.HISTORY_API_RANGE_INVERTED)
        }
        return window.copy(
            selectedUntil = minOf(window.selectedUntil, window.now),
            until = cutoff,
        )
    }

    private suspend fun resolveScope(scopeId: String?, currentScope: HistorySourceScope): HistorySourceScope {
        val requested = scopeId?.trim()?.takeIf(String::isNotEmpty) ?: return currentScope
        if (requested == currentScope.sourceKey) return currentScope
        val digest = currentScope.accountIdentityDigest
            ?: throw HistoryApiRequestException(ViewText.HISTORY_API_SCOPE_UNKNOWN)
        val record = historyStore.listRecordedScopes(digest).firstOrNull { it.sourceKey == requested }
            ?: throw HistoryApiRequestException(ViewText.HISTORY_API_SCOPE_UNKNOWN)
        return record.toSourceScope()
    }

    private suspend fun resolveActivityScope(
        scopeId: String?,
        modeParam: String?,
        currentScope: HistorySourceScope,
    ): HistorySourceScope {
        val scope = resolveScope(scopeId, currentScope)
        val requestedMode = modeParam?.trim()?.takeIf(String::isNotEmpty)?.let { raw ->
            HistorySourceMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw HistoryApiRequestException(ViewText.HISTORY_API_SCOPE_MODE_MISMATCH)
        }
        if (requestedMode != null && requestedMode != scope.mode) {
            throw HistoryApiRequestException(ViewText.HISTORY_API_SCOPE_MODE_MISMATCH)
        }
        return scope
    }

    private suspend fun scopeDto(scope: HistorySourceScope, currentScope: HistorySourceScope): HistoryApiScope {
        val coverage = historyStore.valuesCoverage(scope)
        return HistoryApiScope(
            id = scope.sourceKey,
            symbols = scope.managedScopeSymbols,
            earliestIso = coverage.firstRecordedAt?.toString(),
            latestIso = coverage.lastRecordedAt?.toString(),
            current = scope.sourceKey == currentScope.sourceKey,
        )
    }

    private suspend fun buildCoverage(scope: HistorySourceScope): HistoryApiCoverage {
        val values = historyStore.valuesCoverage(scope)
        val digest = scope.accountIdentityDigest
        val trades = if (digest == null) null to null else tradeBounds(digest)
        val ledgers = if (digest == null) null to null else ledgerBounds(digest)
        val gapCount = values.incompleteCount.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        return HistoryApiCoverage(
            valuesFirstIso = values.firstRecordedAt?.toString(),
            valuesLastIso = values.lastRecordedAt?.toString(),
            tradesFirstIso = trades.first?.toString(),
            tradesLastIso = trades.second?.toString(),
            ledgersFirstIso = ledgers.first?.toString(),
            ledgersLastIso = ledgers.second?.toString(),
            incomplete = gapCount > 0 || values.firstRecordedAt == null,
            gapCount = gapCount,
            apiKeyHistoryFromIso = syncCoordinator.apiKeyHistoryFrom()?.toString(),
        )
    }

    private suspend fun tradeBounds(digest: String): Pair<Instant?, Instant?> {
        val newest = historyService.queryTrades(HistoryEventQuery(accountIdentityDigest = digest, limit = 1))
        val oldest = historyService.queryTrades(
            HistoryEventQuery(
                accountIdentityDigest = digest,
                limit = 1,
                offset = ((newest.total ?: 0L) - 1L).coerceAtLeast(0L).toInt(),
            ),
        )
        return newest.items.firstOrNull()?.time to oldest.items.firstOrNull()?.time
    }

    private suspend fun ledgerBounds(digest: String): Pair<Instant?, Instant?> {
        val newest = historyService.queryLedgers(HistoryEventQuery(accountIdentityDigest = digest, limit = 1))
        val oldest = historyService.queryLedgers(
            HistoryEventQuery(
                accountIdentityDigest = digest,
                limit = 1,
                offset = ((newest.total ?: 0L) - 1L).coerceAtLeast(0L).toInt(),
            ),
        )
        return newest.items.firstOrNull()?.time to oldest.items.firstOrNull()?.time
    }

    private suspend fun buildSeries(
        selection: HistoryChartSelection,
        scope: HistorySourceScope,
        window: HistoryApiWindow,
        observations: List<HistoryObservation>,
        maxPoints: Int,
        targetAllocations: List<Allocation>,
    ): List<HistoryApiSeries> = when (selection) {
        HistoryChartSelection.PORTFOLIO -> portfolioSeries(observations)

        HistoryChartSelection.HOLDINGS -> holdingsSeries(observations)

        HistoryChartSelection.ALLOCATION -> allocationSeries(
            observations,
            targetPercentsBySymbol(targetAllocations),
        )

        HistoryChartSelection.DEPOSITS -> ledgerSeries(scope, window, rewardsOnly = false)

        HistoryChartSelection.REWARDS -> ledgerSeries(scope, window, rewardsOnly = true)

        HistoryChartSelection.CHECKPOINTS -> checkpointSeries(scope, window, maxPoints)
    }

    /**
     * Cumulative native quantities for one analysis. The whole selected period is read so the
     * running total starts at the selected period's baseline, and withdrawals carry a negative
     * amount so net funding falls when capital leaves the account.
     */
    private suspend fun ledgerSeries(
        scope: HistorySourceScope,
        window: HistoryApiWindow,
        rewardsOnly: Boolean,
    ): List<HistoryApiSeries> {
        val digest = scope.accountIdentityDigest ?: return emptyList()
        val types = if (rewardsOnly) REWARD_LEDGER_TYPES else FUNDING_LEDGER_TYPES
        val rows = fetchLedgerRows(digest, window, types)
        val relevant = if (rewardsOnly) rows.filter { it.isRewardRow() } else rows.filter { it.isFundingRow() }
        return ledgerQuantitySeries(
            rows = relevant,
            signedAmountOf = if (rewardsOnly) ::rewardAmountOf else ::fundingAmountOf,
            from = window.from,
            until = window.until,
        )
    }

    private suspend fun checkpointSeries(
        scope: HistorySourceScope,
        window: HistoryApiWindow,
        maxPoints: Int,
    ): List<HistoryApiSeries> {
        val digest = scope.accountIdentityDigest ?: return emptyList()
        val rows = fetchCheckpointRows(digest, window, maxPoints)
        return checkpointQuantitySeries(rows)
    }

    /**
     * Every ledger row of the requested types inside the selected period. The whole period is read
     * because a cumulative curve needs every event, not only the newest page.
     */
    private suspend fun fetchLedgerRows(
        digest: String,
        window: HistoryApiWindow,
        types: List<String>,
    ): List<HistoryLedger> {
        val rows = linkedMapOf<String, HistoryLedger>()
        val from = window.baselineFrom ?: window.from
        types.forEach { type ->
            var offset = 0
            while (true) {
                val page = historyService.queryLedgers(
                    HistoryEventQuery(
                        from = from,
                        until = window.until,
                        accountIdentityDigest = digest,
                        type = type,
                        limit = HISTORY_MAX_PAGE_LIMIT,
                        offset = offset,
                    ),
                )
                page.items.forEach { row -> rows.putIfAbsent(row.ledgerId, row) }
                if (!page.hasMore || page.items.isEmpty()) break
                offset += page.items.size
            }
        }
        return rows.values.toList()
    }

    private suspend fun fetchCheckpointRows(
        digest: String,
        window: HistoryApiWindow,
        maxPoints: Int,
    ): List<HistoryBalanceCheckpoint> {
        val rows = mutableListOf<HistoryBalanceCheckpoint>()
        var offset = 0
        while (rows.size < maxPoints) {
            val page = historyService.queryBalanceCheckpoints(
                HistoryEventQuery(
                    from = window.from,
                    until = window.until,
                    accountIdentityDigest = digest,
                    limit = HISTORY_MAX_PAGE_LIMIT,
                    offset = offset,
                ),
            )
            rows += page.items
            if (!page.hasMore || page.items.isEmpty()) break
            offset += page.items.size
        }
        return rows.take(maxPoints)
    }

    private suspend fun fetchActivities(
        scope: HistorySourceScope,
        window: HistoryApiWindow,
        pageSize: Int,
        after: ActivityCursor?,
    ): Pair<List<HistoryCycleActivity>, Boolean> {
        val fetchSize = pageSize + 1
        val collected = mutableListOf<HistoryCycleActivity>()
        var offset = 0
        while (true) {
            val page = historyStore.queryActivityPage(
                HistoryActivityQuery(
                    sourceScope = scope,
                    from = window.from,
                    until = after?.occurredAt?.plusNanos(1) ?: window.until,
                    limit = fetchSize,
                    offset = offset,
                ),
            )
            collected += page.items.filter { row -> after == null || row.sortsAfter(after) }
            if (collected.size >= fetchSize) return collected.take(pageSize) to true
            if (!page.hasMore || page.items.isEmpty()) return collected.take(pageSize) to false
            offset += page.items.size
        }
    }

    private fun activityRow(activity: HistoryCycleActivity): HistoryApiActivityRow = HistoryApiActivityRow(
        cycleId = activity.cycleId,
        occurredAt = activity.occurredAt.toString(),
        mode = activity.sourceScope.mode.name,
        outcome = activity.outcome.toOutcomeText(),
        actions = activity.actions,
    )

    private fun HistoryCycleActivity.sortsAfter(cursor: ActivityCursor): Boolean = occurredAt < cursor.occurredAt ||
        (occurredAt == cursor.occurredAt && cycleId < cursor.cycleId)

    private fun syncJobDto(job: HistorySyncJob): HistoryApiSyncJob = HistoryApiSyncJob(
        feed = job.feed.name,
        purpose = job.purpose.name,
        state = job.state.name,
        windowStartIso = job.windowStartSec?.let(::instantFromSec),
        windowEndIso = job.windowEndSec?.let(::instantFromSec),
        cursor = job.cursor,
        completedThroughIso = job.completedThroughSec?.let(::instantFromSec),
        lastError = job.lastError,
        nextRetryAtIso = job.nextRetryAt?.toString(),
        lastSuccessAtIso = job.lastSuccessAt?.toString(),
        updatedAtIso = job.updatedAt.toString(),
    )

    private fun syncProgressDto(progress: HistorySyncProgressSummary): HistoryApiSyncProgress = HistoryApiSyncProgress(
        feed = progress.feed.name,
        recentWindowStartIso = progress.recentWindowStartSec?.let(::instantFromSec),
        recentCompletedThroughIso = progress.recentCompletedThroughSec?.let(::instantFromSec),
        recentLastSuccessAtIso = progress.recentLastSuccessAt?.toString(),
        backfillState = progress.backfillState.name,
        backfillCompletedThroughIso = progress.backfillCompletedThroughSec?.let(::instantFromSec),
        backfillCursor = progress.backfillCursor,
        repairLastRunAtIso = progress.repairLastRunAt?.toString(),
        lastError = progress.lastError,
        nextRetryAtIso = progress.nextRetryAt?.toString(),
        coveredFromIso = progress.coveredWindows.minOfOrNull { it.startSec }?.let(::instantFromSec),
        coveredToIso = progress.coveredWindows.maxOfOrNull { it.endSec }?.let(::instantFromSec),
    )
}

internal fun HistoryApiWindow.rangeDto(): HistoryApiRange = HistoryApiRange(
    fromIso = from?.toString(),
    toIso = until.toString(),
    tz = zone.id,
    preset = preset,
)

internal fun HistoryRecordedScope.toSourceScope(): HistorySourceScope = HistorySourceScope(
    mode = mode,
    managedScopeFingerprint = managedScopeFingerprint,
    managedScopeSymbols = managedScopeSymbols,
    accountIdentityDigest = accountIdentityDigest,
    simulationSessionId = simulationSessionId,
)

internal fun HistoryCycleOutcome?.toOutcomeText(): String = when (this) {
    HistoryCycleOutcome.NO_TRADE -> ViewText.HISTORY_API_OUTCOME_NO_TRADE
    HistoryCycleOutcome.DRY_RUN_PLAN -> ViewText.HISTORY_API_OUTCOME_DRY_RUN_PLAN
    HistoryCycleOutcome.ORDERS_ATTEMPTED -> ViewText.HISTORY_API_OUTCOME_ORDERS_ATTEMPTED
    HistoryCycleOutcome.PROBLEM -> ViewText.HISTORY_API_OUTCOME_PROBLEM
    HistoryCycleOutcome.RECORDED, null -> ViewText.HISTORY_API_OUTCOME_RECORDED
}

private fun resolveZone(tz: String?): ZoneId {
    val raw = tz?.trim().orEmpty()
    if (raw.isEmpty()) return ZoneId.of("UTC")
    return try {
        ZoneId.of(raw)
    } catch (error: DateTimeException) {
        throw HistoryApiRequestException(ViewText.HISTORY_API_RANGE_INVALID_TIMEZONE)
    }
}

private fun parseLocalDateOrThrow(raw: String, message: String): LocalDate = try {
    LocalDate.parse(raw)
} catch (error: DateTimeParseException) {
    throw HistoryApiRequestException(message)
}

private fun parseInstant(raw: String, zone: ZoneId, inclusiveDate: Boolean = false): Instant {
    if (raw.matches(Regex(EPOCH_SECONDS_PATTERN))) {
        return try {
            Instant.ofEpochSecond(raw.toLong())
        } catch (error: DateTimeException) {
            throw HistoryApiRequestException(ViewText.HISTORY_API_RANGE_INVALID_INSTANT)
        }
    }
    if (DATE_ONLY_PATTERN.matches(raw)) {
        val date = parseLocalDateOrThrow(raw, ViewText.HISTORY_API_RANGE_INVALID_DATE)
        val bounded = if (inclusiveDate) date.plusDays(1) else date
        return bounded.atStartOfDay(zone).toInstant()
    }
    return try {
        Instant.parse(raw)
    } catch (error: DateTimeParseException) {
        throw HistoryApiRequestException(ViewText.HISTORY_API_RANGE_INVALID_INSTANT)
    }
}

private val DATE_ONLY_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")

private val UTC_ZONE: ZoneId = ZoneId.of("UTC")

private fun maxOfNullable(first: Instant?, second: Instant?): Instant? = when {
    first == null -> second
    second == null -> first
    else -> maxOf(first, second)
}

private fun instantFromSec(seconds: Long): String = Instant.ofEpochSecond(seconds).toString()

private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000

private fun encodeCursor(row: HistoryCycleActivity): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString("${row.occurredAt}$CURSOR_SEPARATOR${row.cycleId}".toByteArray())

private fun decodeCursor(raw: String): ActivityCursor {
    val decoded = try {
        String(Base64.getUrlDecoder().decode(raw))
    } catch (error: IllegalArgumentException) {
        throw HistoryApiRequestException(ViewText.HISTORY_API_CURSOR_INVALID)
    }
    val separator = decoded.indexOf(CURSOR_SEPARATOR)
    if (separator <= 0 || separator == decoded.length - 1) {
        throw HistoryApiRequestException(ViewText.HISTORY_API_CURSOR_INVALID)
    }
    val occurredAt = try {
        Instant.parse(decoded.substring(0, separator))
    } catch (error: DateTimeParseException) {
        throw HistoryApiRequestException(ViewText.HISTORY_API_CURSOR_INVALID)
    }
    return ActivityCursor(occurredAt, decoded.substring(separator + 1))
}
