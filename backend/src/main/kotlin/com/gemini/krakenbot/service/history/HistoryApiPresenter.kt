package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.service.reporting.HistoryReportingPeriod
import com.gemini.krakenbot.service.reporting.HistoryReportingService
import com.gemini.krakenbot.service.reporting.LatestObservedValue
import com.gemini.krakenbot.service.reporting.ObservedValueChange
import com.gemini.krakenbot.service.reporting.RangeExtrema
import com.gemini.krakenbot.service.reporting.RangeExtremum
import com.gemini.krakenbot.service.reporting.targetPercentsBySymbol
import com.gemini.krakenbot.view.util.ViewText
import java.math.BigDecimal
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
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
    KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
)

/** Ledger families that can pay a reward; the row filter still decides which rows count. */
private val REWARD_LEDGER_TYPES = listOf(
    KrakenApiConstants.LEDGER_TYPE_STAKING,
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
            return accountDailyValueCharts(currentScope, window, pointCap, startedNanos, params.valueAsset)
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

    private suspend fun accountDailyValueCharts(
        currentScope: HistorySourceScope,
        window: HistoryApiWindow,
        pointCap: Int,
        startedNanos: Long,
        rawValueAsset: String?,
    ): HistoryApiChartsResponse {
        val digest = checkNotNull(currentScope.accountIdentityDigest)
        val values = mutableListOf<HistoryDailyValue>()
        var offset = 0
        var total: Long? = null
        val (dailyFrom, dailyUntil) = window.dailyValueBounds()
        while (true) {
            val page = historyStore.queryDailyValues(
                accountIdentityDigest = digest,
                sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
                from = dailyFrom,
                until = dailyUntil,
                limit = HISTORY_PAGE_LIMIT,
                offset = offset,
            )
            total = page.total
            values += page.items
            if (!page.hasMore || page.items.isEmpty()) break
            offset += page.items.size
        }
        val from = values.firstOrNull()?.dayLabel?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toString()
        val to = values.lastOrNull()?.dayLabel?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toString()
        val displayValues = sampleDailyValues(values, pointCap)
        val availableValueAssets = values.asSequence()
            .flatMap { value -> value.assets.asSequence() }
            .filterNot { asset -> asset.rawAssetClass.equals("equity", ignoreCase = true) }
            .map { asset -> Asset.normalizeLedgerAsset(asset.rawAssetKey) }
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
            .toList()
        val selectedValueAsset = rawValueAsset?.trim()?.takeIf(String::isNotEmpty)?.let(Asset::normalizeLedgerAsset)
            ?.takeIf { it in availableValueAssets }
        val points = displayValues.map { value ->
            val selectedAssets = selectedValueAsset?.let { symbol ->
                value.assets.filter {
                    !it.rawAssetClass.equals("equity", ignoreCase = true) &&
                        Asset.normalizeLedgerAsset(it.rawAssetKey) == symbol
                }
            }.orEmpty()
            val selectedValue = when {
                selectedValueAsset == null -> value.totalUsd

                selectedAssets.isEmpty() && value.status == HistoryDailyValueStatus.COMPLETE -> BigDecimal.ZERO

                selectedAssets.isNotEmpty() && selectedAssets.all {
                    it.status == HistoryDailyValueStatus.COMPLETE && it.valueUsd != null
                } -> selectedAssets.fold(BigDecimal.ZERO) { totalUsd, asset ->
                    totalUsd.add(asset.valueUsd ?: BigDecimal.ZERO)
                }

                else -> null
            }
            HistoryApiPoint(
                t = value.dayEnd.toEpochMilli(),
                isoTime = value.dayEnd.toString(),
                v = selectedValue,
                complete = selectedValue != null,
                status = if (selectedValue == null) {
                    selectedAssets.firstOrNull { it.status != HistoryDailyValueStatus.COMPLETE }?.status?.name
                        ?: value.status.name
                } else {
                    HistoryDailyValueStatus.COMPLETE.name
                },
                reasons = if (selectedValue != null) {
                    emptyList()
                } else if (selectedValueAsset != null) {
                    selectedAssets.filter { it.status != HistoryDailyValueStatus.COMPLETE }.map { asset ->
                        "$selectedValueAsset: ${asset.reason ?: asset.status.name}"
                    }.ifEmpty { listOf(value.status.name) }
                } else {
                    value.assets.filter { it.status != HistoryDailyValueStatus.COMPLETE }.map { asset ->
                        "${Asset.normalizeLedgerAsset(asset.rawAssetKey)}: ${asset.reason ?: asset.status.name}"
                    }.ifEmpty { listOf(value.status.name) }
                },
                dayLabel = value.dayLabel.toString(),
            )
        }
        val series = HistoryApiSeries(
            key = selectedValueAsset?.let { "asset:$it" } ?: HISTORY_API_PORTFOLIO_KEY,
            label = selectedValueAsset?.let { "$it · USD value" } ?: ViewText.HISTORY_API_SERIES_PORTFOLIO,
            legendLabel = selectedValueAsset?.let { "$it · USD value" } ?: ViewText.HISTORY_API_SERIES_PORTFOLIO,
            unit = "USD",
            points = points,
        )
        val baseCoverage = buildCoverage(currentScope)
        val dailyCoverage = baseCoverage.copy(
            valuesFirstIso = from,
            valuesLastIso = to,
            incomplete = values.isEmpty() || values.any { it.status != HistoryDailyValueStatus.COMPLETE },
            gapCount = values.count { it.status != HistoryDailyValueStatus.COMPLETE },
        )
        val latestGeneration = values.lastOrNull()
        val earliestDay = values.firstOrNull()?.dayLabel
        val latestDay = values.lastOrNull()?.dayLabel
        return HistoryApiChartsResponse(
            range = window.rangeDto(),
            scope = scopeDto(currentScope, currentScope),
            coverage = dailyCoverage,
            series = listOf(series),
            fullRange = HistoryApiFullRange(
                fromIso = window.selectedFrom?.toString(),
                toIso = window.selectedUntil.toString(),
                pointCount = points.size,
                totalObservations = total ?: values.size.toLong(),
                representative = points.size.toLong() < (total ?: values.size.toLong()),
                baselineFromIso = window.baselineFrom?.toString(),
            ),
            generationMs = elapsedMs(startedNanos),
            scopeSelection = HistoryApiScopeSelection.CURRENT_SCOPE,
            resolution = "1d",
            valuationBasis = "Daily value · historical closing prices",
            generationId = latestGeneration?.generationId,
            sourceRevision = latestGeneration?.sourceRevision,
            availableValueFromIso = earliestDay?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toString(),
            availableValueToIso = latestDay?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toString(),
            availableValueAssets = availableValueAssets,
            selectedValueAsset = selectedValueAsset,
        )
    }

    suspend fun holdingsAtDate(currentScope: HistorySourceScope, dayValue: String): HistoryApiHoldingsAtDate {
        val digest = currentScope.accountIdentityDigest
            ?.takeIf { currentScope.mode == HistorySourceMode.ACCOUNT }
            ?: throw HistoryApiRequestException(ViewText.HISTORY_ACCOUNT_UNAVAILABLE)
        val day = try {
            LocalDate.parse(dayValue)
        } catch (_: DateTimeParseException) {
            throw HistoryApiRequestException(ViewText.HISTORY_API_RANGE_INVALID_DATE)
        }
        val dayEnd = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val total = historyStore.queryDailyValues(
            accountIdentityDigest = digest,
            sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
            from = dayEnd,
            until = dayEnd.plusSeconds(1),
            limit = 1,
        ).items.firstOrNull()
        if (total == null) {
            return HistoryApiHoldingsAtDate(
                day = day.toString(),
                available = false,
                totalUsd = null,
                totalStatus = HistoryDailyValueStatus.OUTSIDE_COVERAGE.name,
                generationId = null,
                holdings = emptyList(),
            )
        }
        val assets = historyStore.queryDailyAssetValues(
            accountIdentityDigest = digest,
            sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
            from = dayEnd,
            until = dayEnd.plusSeconds(1),
            limit = HISTORY_MAX_PAGE_LIMIT,
        ).items
        return HistoryApiHoldingsAtDate(
            day = day.toString(),
            available = true,
            totalUsd = total.totalUsd?.toPlainString(),
            totalStatus = total.status.name,
            generationId = total.generationId,
            holdings = assets.filterNot { asset ->
                asset.rawAssetClass.equals("equity", ignoreCase = true)
            }.map { asset ->
                HistoryApiDailyHolding(
                    asset = Asset.normalizeLedgerAsset(asset.rawAssetKey),
                    quantity = asset.quantity?.toPlainString(),
                    priceUsd = asset.priceUsd?.toPlainString(),
                    valueUsd = asset.valueUsd?.toPlainString(),
                    availability = asset.status.name,
                    reason = asset.reason,
                )
            },
        )
    }

    fun dailyValueExportQuery(
        params: HistoryApiRangeParams,
        currentScope: HistorySourceScope,
    ): HistoryApiExportQuery<HistoryDailyValueExportRange> {
        val digest = currentScope.accountIdentityDigest
            ?.takeIf { currentScope.mode == HistorySourceMode.ACCOUNT }
            ?: throw HistoryApiRequestException(ViewText.HISTORY_ACCOUNT_UNAVAILABLE)
        val window = resolveWindow(params, nowProvider())
        val (from, until) = window.dailyValueBounds()
        return HistoryApiExportQuery(HistoryDailyValueExportRange(digest, from, until), window.zone)
    }

    fun dailyHoldingsExportQuery(
        dayValue: String,
        currentScope: HistorySourceScope,
    ): HistoryApiExportQuery<HistoryDailyHoldingsExportQuery> {
        val digest = currentScope.accountIdentityDigest
            ?.takeIf { currentScope.mode == HistorySourceMode.ACCOUNT }
            ?: throw HistoryApiRequestException(ViewText.HISTORY_ACCOUNT_UNAVAILABLE)
        val day = try {
            LocalDate.parse(dayValue)
        } catch (_: DateTimeParseException) {
            throw HistoryApiRequestException(ViewText.HISTORY_API_RANGE_INVALID_DATE)
        }
        return HistoryApiExportQuery(HistoryDailyHoldingsExportQuery(digest, day), ZoneOffset.UTC)
    }

    private suspend fun dailyValueStatistics(
        base: com.gemini.krakenbot.service.reporting.HistoryPeriodStatistics,
        scope: HistorySourceScope,
        window: HistoryApiWindow,
    ): com.gemini.krakenbot.service.reporting.HistoryPeriodStatistics {
        val digest = scope.accountIdentityDigest ?: return base
        val values = mutableListOf<HistoryDailyValue>()
        var offset = 0
        val (dailyFrom, dailyUntil) = window.dailyValueBounds()
        while (true) {
            val page = historyStore.queryDailyValues(
                accountIdentityDigest = digest,
                sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
                from = dailyFrom,
                until = dailyUntil,
                limit = HISTORY_PAGE_LIMIT,
                offset = offset,
            )
            values += page.items
            if (!page.hasMore || page.items.isEmpty()) break
            offset += page.items.size
        }
        val complete = values.filter { it.status == HistoryDailyValueStatus.COMPLETE && it.totalUsd != null }
        val first = values.firstOrNull()
        val last = values.lastOrNull()
        val firstComplete = complete.firstOrNull()
        val lastComplete = complete.lastOrNull()
        val minValue = complete.minByOrNull { it.totalUsd!! }
        val maxValue = complete.maxByOrNull { it.totalUsd!! }
        val allComplete = values.isNotEmpty() && complete.size == values.size
        val incompleteReason = values.firstOrNull { it.status != HistoryDailyValueStatus.COMPLETE }
            ?.let { "Daily value is unavailable on ${it.dayLabel}: ${it.status.name.lowercase()}." }
        return base.copy(
            latestObservedValue = LatestObservedValue(
                valueUsd = last?.totalUsd?.takeIf { last.status == HistoryDailyValueStatus.COMPLETE },
                observedAt = last?.dayEnd,
                outsideSelectedPeriod = false,
                complete = last?.status == HistoryDailyValueStatus.COMPLETE,
                incompleteReason = if (last?.status == HistoryDailyValueStatus.COMPLETE) null else incompleteReason,
            ),
            observedValueChange = ObservedValueChange(
                changeUsd = if (allComplete && firstComplete != null && lastComplete != null) {
                    lastComplete.totalUsd!!.subtract(firstComplete.totalUsd!!)
                } else {
                    null
                },
                firstValueUsd = firstComplete?.totalUsd,
                firstObservedAt = firstComplete?.dayEnd,
                lastValueUsd = lastComplete?.totalUsd,
                lastObservedAt = lastComplete?.dayEnd,
                includesFlowsNotice = "Deposits and withdrawals affect this change in account value.",
                complete = allComplete,
                incompleteReason = if (allComplete) {
                    null
                } else {
                    incompleteReason
                        ?: "No daily account values are available."
                },
            ),
            rangeExtrema = RangeExtrema(
                high = maxValue?.let { RangeExtremum("high", it.totalUsd!!, it.dayEnd) },
                low = minValue?.let { RangeExtremum("low", it.totalUsd!!, it.dayEnd) },
                complete = allComplete,
                incompleteReason = if (allComplete) {
                    null
                } else {
                    incompleteReason
                        ?: "No daily account values are available."
                },
            ),
        )
    }

    private fun sampleDailyValues(values: List<HistoryDailyValue>, pointCap: Int): List<HistoryDailyValue> {
        if (values.size <= pointCap) return values
        val mandatory = values.indices.filter { index -> values[index].status != HistoryDailyValueStatus.COMPLETE }
            .toMutableSet()
        mandatory += 0
        mandatory += values.lastIndex
        val remainingSlots = (pointCap - mandatory.size).coerceAtLeast(0)
        if (remainingSlots > 0) {
            repeat(remainingSlots) { slot ->
                val index = if (remainingSlots == 1) {
                    values.lastIndex / 2
                } else {
                    (slot * values.lastIndex) / (remainingSlots - 1)
                }
                mandatory += index
            }
        }
        return mandatory.sorted().map(values::get)
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
        val baseStatistics = reportingService.computeStatistics(period, targetAllocations)
        val dailyStatistics = if (scopeId.isNullOrBlank() && scope.mode == HistorySourceMode.ACCOUNT) {
            dailyValueStatistics(baseStatistics, scope, window)
        } else {
            baseStatistics
        }
        return HistoryApiSummaryResponse(
            range = window.rangeDto(),
            scope = scopeDto(scope, currentScope),
            coverage = buildCoverage(scope),
            statistics = dailyStatistics,
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
        val valuationJob = digest?.let { historyStore.loadValuationJob(it, HISTORY_ACCOUNT_DAILY_SCOPE) }
        val valuation = if (digest == null || valuationJob == null) {
            null
        } else {
            val targetRows = historyStore.queryDailyValues(
                accountIdentityDigest = digest,
                sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
                from = valuationJob.targetStartDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                until = valuationJob.targetEndDay.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant(),
                limit = valuationJob.targetDays.coerceAtLeast(1),
            ).items
            valuationJob.toApiProgress().copy(
                blockedValueReasons = targetRows.asSequence()
                    .filter { it.status != HistoryDailyValueStatus.COMPLETE }
                    .flatMap { value ->
                        value.assets.asSequence()
                            .filter { it.status != HistoryDailyValueStatus.COMPLETE }
                            .map { asset ->
                                "${value.dayLabel} UTC · ${Asset.normalizeLedgerAsset(asset.rawAssetKey)}: " +
                                    (asset.reason ?: asset.status.name.lowercase())
                            }
                            .ifEmpty { sequenceOf("${value.dayLabel} UTC · ${value.status.name.lowercase()}") }
                    }
                    .take(50)
                    .toList(),
            )
        }
        val tradeCount = digest?.let {
            historyStore.queryTrades(HistoryEventQuery(accountIdentityDigest = it, limit = 1)).total
        }
        val ledgerCount = digest?.let {
            historyStore.queryLedgers(HistoryEventQuery(accountIdentityDigest = it, limit = 1)).total
        }
        val firstDailyValue = digest?.let {
            historyStore.queryDailyValues(it, HISTORY_ACCOUNT_DAILY_SCOPE, limit = 1)
        }?.items?.firstOrNull()
        val availableDays = digest?.let {
            historyStore.queryDailyValues(it, HISTORY_ACCOUNT_DAILY_SCOPE, limit = 1).total
        }
        val storedValueGaps = if (digest != null && valuation == null) {
            historyStore.countIncompleteDailyValues(digest, HISTORY_ACCOUNT_DAILY_SCOPE)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        } else {
            0
        }
        val lastDailyValue = if (digest != null && availableDays != null && availableDays > 0) {
            historyStore.queryDailyValues(
                digest,
                HISTORY_ACCOUNT_DAILY_SCOPE,
                limit = 1,
                offset = (availableDays - 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            ).items.firstOrNull()
        } else {
            null
        }
        val statusCoverage = if (currentScope.mode == HistorySourceMode.ACCOUNT) {
            buildCoverage(currentScope).copy(
                valuesFirstIso = firstDailyValue?.dayLabel?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toString(),
                valuesLastIso = lastDailyValue?.dayLabel?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toString(),
                incomplete =
                valuation?.blockedValueDays?.let { it > 0 }
                    ?: (availableDays == null || availableDays == 0L || storedValueGaps > 0),
                gapCount = valuation?.blockedValueDays ?: storedValueGaps,
            )
        } else {
            buildCoverage(currentScope)
        }
        val progressDtos = progress.map { feedProgress ->
            val targetCoverageComplete = valuationJob?.let { job ->
                val targetStart = job.targetStartDay.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
                val targetThrough = job.targetEndDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() - 1
                coverageCovers(feedProgress.coveredWindows, targetStart, targetThrough)
            }
            syncProgressDto(feedProgress).copy(targetCoverageComplete = targetCoverageComplete)
        }
        return HistoryApiDataStatusResponse(
            range = window.rangeDto(),
            dataRevision = historyStore.currentDataRevision(),
            blockedReason = syncCoordinator.blockedReason(),
            syncFailure = syncCoordinator.lastFailure(),
            nextRetryAtIso = syncCoordinator.nextRetryAt()?.toString(),
            coverage = statusCoverage,
            jobs = jobs.map(::syncJobDto),
            progress = progressDtos,
            valuation = valuation,
            tradeRecordsImported = tradeCount,
            ledgerRecordsImported = ledgerCount,
            availableValueFromIso = firstDailyValue?.dayLabel?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toString(),
            availableValueToIso = lastDailyValue?.dayLabel?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toString(),
            availableValueDays = availableDays,
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
        coveredGapCount = progress.coveredWindows.sortedBy { it.startSec }
            .zipWithNext()
            .count { (earlier, later) -> earlier.endSec < Long.MAX_VALUE && later.startSec > earlier.endSec + 1 },
    )

    private fun coverageCovers(windows: List<HistorySyncCoverageWindow>, fromSec: Long, throughSec: Long): Boolean {
        if (fromSec > throughSec) return true
        var cursor = fromSec
        for (window in windows.sortedBy { it.startSec }) {
            if (window.endSec < cursor) continue
            if (window.startSec > cursor) return false
            if (window.endSec >= throughSec || window.endSec == Long.MAX_VALUE) return true
            cursor = window.endSec + 1
        }
        return false
    }

    private fun HistoryValuationJob.toApiProgress() = HistoryApiValuationProgress(
        state = state.name,
        stage = stage.name,
        targetStartDay = targetStartDay.toString(),
        targetEndDay = targetEndDay.toString(),
        targetDays = targetDays,
        balancesProcessed = balancesProcessed,
        balancesValid = balancesValid,
        balancesBlocked = balancesBlocked,
        requiredPriceTasks = requiredPriceTasks,
        completedPriceTasks = completedPriceTasks,
        completeValueDays = completeValueDays,
        blockedValueDays = blockedValueDays,
        lastProgressAtIso = lastProgressAt?.toString(),
        nextRetryAtIso = nextRetryAt?.toString(),
        lastSuccessAtIso = lastSuccessAt?.toString(),
        lastError = lastError,
        startedAtIso = startedAt.toString(),
    )
}

internal fun HistoryApiWindow.rangeDto(): HistoryApiRange = HistoryApiRange(
    fromIso = from?.toString(),
    toIso = until.toString(),
    tz = zone.id,
    preset = preset,
)

/** Maps selected civil dates to the UTC closing marks labeled with those dates. */
private fun HistoryApiWindow.dailyValueBounds(): Pair<Instant?, Instant> {
    val firstLabel = from?.atZone(zone)?.toLocalDate()
    val lastLabel = until.minusNanos(1).atZone(zone).toLocalDate()
    return firstLabel?.plusDays(1)?.atStartOfDay(ZoneOffset.UTC)?.toInstant() to
        lastLabel.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant()
}

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
