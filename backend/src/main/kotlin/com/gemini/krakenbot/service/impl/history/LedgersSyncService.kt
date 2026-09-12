package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.InceptionRecoveryStatus
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.util.PrecisionConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pulls Kraken's raw ledger entries into the local database with `types = null` (unprojected
 * evidence). The repository is raw evidence; the classifier/replay layer
 * ([LedgerFlowClassifier], comparison, reconstruction) is the semantic projection — never the
 * query layer. Unknown top-level types and `trade` checkpoint rows are retained; downstream
 * replay fails closed on unsupported/ambiguous rows instead of the sync layer dropping them.
 *
 * Ledger entries are insert-only: identity is the unique (ledger id, timestamp, asset, type) tuple,
 * so re-fetched pages (including the Kraken newest-first offset overlap) are deduplicated by the
 * database instead of reconciled like trades.
 */
class LedgersSyncService(
    private val repository: LedgerRepository,
    private val krakenService: KrakenService,
    private val configService: ConfigService,
    private val tradeRepository: TradeRepository? = null,
    private val nowProvider: () -> Instant = Instant::now,
    private val accountHistoryScopeGuard: AccountHistoryScopeGuard? = null,
) {
    private val log = LoggerFactory.getLogger(LedgersSyncService::class.java)
    private val syncMutex = Mutex()
    private var lastSyncTime: Instant = Instant.EPOCH

    companion object {
        const val CURRENT_LEDGER_COVERAGE_VERSION = "9"

        /**
         * Coverage-certification vs incremental distinction (mirrors TradeHistorySyncService).
         * Certification promotes coverage version/start/horizon and requires authoritative
         * `count` proof on every page; a count-less or malformed page fails the migration.
         */
        enum class LedgerCoverageSyncMode {
            INCREMENTAL,
            COVERAGE_CERTIFICATION,
        }
        val SUPPORTED_LEDGER_TYPES = listOf(
            KrakenApiConstants.LEDGER_TYPE_TRADE,
            KrakenApiConstants.LEDGER_TYPE_STAKING,
            KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
            KrakenApiConstants.LEDGER_TYPE_EARN,
            KrakenApiConstants.LEDGER_TYPE_REWARD,
            KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
            KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
            KrakenApiConstants.LEDGER_TYPE_TRANSFER,
            KrakenApiConstants.LEDGER_TYPE_ADJUSTMENT,
            KrakenApiConstants.LEDGER_TYPE_CONVERSION,
            KrakenApiConstants.LEDGER_TYPE_SPEND,
            KrakenApiConstants.LEDGER_TYPE_RECEIVE,
            KrakenApiConstants.LEDGER_TYPE_MARGIN,
            KrakenApiConstants.LEDGER_TYPE_ROLLOVER,
            KrakenApiConstants.LEDGER_TYPE_SETTLED,
            KrakenApiConstants.LEDGER_TYPE_CREDIT,
            KrakenApiConstants.LEDGER_TYPE_SALE,
        )
    }

    suspend fun syncLedgersFromKraken() = syncMutex.withLock {
        syncLedgersFromKrakenLocked()
    }

    private suspend fun syncLedgersFromKrakenLocked() {
        val now = nowProvider()
        val elapsedSeconds = Duration.between(lastSyncTime, now).seconds
        // Throttle Kraken ledger pulls to at most once per 5 minutes.
        if (elapsedSeconds in 0 until 300) {
            log.info("Skipping ledger synchronization; last run was only {} seconds ago.", elapsedSeconds)
            return
        }

        val preflightConfig = configService.getConfig()
        if (!preflightConfig.canPullLedgers()) {
            log.warn("Kraken API key is blank or placeholder. Skipping ledger synchronization.")
            return
        }

        configService.beginExecutionSession()
        try {
            val pinnedConfig = configService.getConfig()
            if (!pinnedConfig.canPullLedgers()) {
                log.warn(
                    "Kraken API key became unavailable before ledger synchronization started. Skipping synchronization.",
                )
                return
            }
            krakenService.withStableBackend {
                val scopeResult = accountHistoryScopeGuard?.validateAccountScope()
                if (scopeResult != null && !scopeResult.isValid) {
                    log.warn(
                        "Account scope validation failed: {}. Skipping ledger synchronization.",
                        scopeResult.reason,
                    )
                    return@withStableBackend
                }
                syncLedgersFromKrakenPinned(pinnedConfig, scopeResult?.currentScopeDigest)
            }
        } finally {
            configService.endExecutionSession()
        }
    }

    private suspend fun syncLedgersFromKrakenPinned(config: AppConfig, verifiedAccountScopeDigest: String?) {
        val isSeeded = repository.isLedgersSeeded()
        val coverageVersion = repository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION)
        val seedBound = nowProvider().minus(PrecisionConstants.SEED_HISTORY_LOOKBACK_DAYS, ChronoUnit.DAYS)
        val configuredInception = config.settings.inceptionDate
            ?.let(InceptionDiscoveryService::parseInceptionDate)
        val coverageBackfillBound = configuredInception?.takeIf { it.isBefore(seedBound) } ?: seedBound
        val storedCoverageStartSec = repository
            .getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC)
            ?.toLongOrNull()
        val coverageStartMatches = !coverageBackfillBound.isBefore(seedBound) ||
            (storedCoverageStartSec != null && storedCoverageStartSec <= coverageBackfillBound.epochSecond)
        val storedScopeDigest = repository
            .getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
        val scopeMatches = verifiedAccountScopeDigest.isNullOrBlank() || storedScopeDigest == verifiedAccountScopeDigest
        val isCoverageCurrent =
            coverageVersion == CURRENT_LEDGER_COVERAGE_VERSION && coverageStartMatches && scopeMatches
        val needsCoverageBackfill = isSeeded && !isCoverageCurrent
        val queryNow = nowProvider()

        if (needsCoverageBackfill) {
            log.info(
                "Ledger store is seeded but coverage version is {} (expected {}). Running coverage backfill from {}...",
                coverageVersion,
                CURRENT_LEDGER_COVERAGE_VERSION,
                coverageBackfillBound,
            )
            val recoveredCoverageThrough = recoverableCompletedRecoveryThrough(
                requiredStart = coverageBackfillBound,
                queryNow = queryNow,
                verifiedAccountScopeDigest = verifiedAccountScopeDigest,
            )
            val totalAdded = if (recoveredCoverageThrough == null) {
                processLedgerPages(
                    // Kraken's start bound is exclusive; step back one second so an event exactly
                    // at the configured inception is included in the migration backfill.
                    startSec = coverageBackfillBound.minusSeconds(1).epochSecond,
                    endSec = queryNow.epochSecond,
                    isSeeded = true,
                    mode = LedgerCoverageSyncMode.COVERAGE_CERTIFICATION,
                )
            } else if (recoveredCoverageThrough.isBefore(queryNow)) {
                // Completed recovery proves the historical prefix. Only refresh the unproven tail;
                // never repaginate the already recovered inception-to-horizon range. The tail is
                // still certifying and requires authoritative count proof.
                processLedgerPages(
                    startSec = recoveredCoverageThrough.minusSeconds(300).epochSecond,
                    endSec = queryNow.epochSecond,
                    isSeeded = true,
                    mode = LedgerCoverageSyncMode.COVERAGE_CERTIFICATION,
                )
            } else {
                0
            }
            val currentWatermark = readSyncWatermark()
            if (currentWatermark == null || queryNow.isAfter(currentWatermark)) {
                writeSyncWatermark(queryNow)
            }
            // Persist the watermark before promoting coverage. If a later metadata write fails,
            // the old coverage version remains authoritative and the next run retries the range.
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC,
                coverageBackfillBound.epochSecond.toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                queryNow.epochSecond.toString(),
            )
            if (verifiedAccountScopeDigest != null) {
                repository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST,
                    verifiedAccountScopeDigest,
                )
            }
            repository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, CURRENT_LEDGER_COVERAGE_VERSION)
            pruneOldEntries(queryNow)
            lastSyncTime = nowProvider()
            log.info(
                "Ledger coverage backfill completed. Added: {} entries. Coverage version is now {}.",
                totalAdded,
                CURRENT_LEDGER_COVERAGE_VERSION,
            )
            return
        }

        val effectiveLatest = calculateEffectiveLatestTime()
        // Incremental sync overlaps by 5 minutes so entries near the previous watermark are
        // re-fetched and deduplicated rather than missed. Unseeded initial sync and recovery use
        // the configured inception when it predates the default 96-day bound. Ledger entries are
        // retained indefinitely (lifetime retention contract), so no prune follows the fetch.
        val startSec = effectiveLatest?.minusSeconds(300)?.epochSecond
        val isRecoveringInitialSync = !isSeeded && readInitialPaginationOffset() != null
        val paginationStartSec = if (!isSeeded) {
            // Kraken's start bound is exclusive; step back one second so an event exactly at the
            // configured inception or default seed bound is included.
            coverageBackfillBound.minusSeconds(1).epochSecond
        } else {
            (
                startSec
                    ?: seedBound.epochSecond
                )
        }

        log.info(
            "Starting ledger synchronization (isSeeded={}, startSec={}, recovering={})...",
            isSeeded,
            paginationStartSec,
            isRecoveringInitialSync,
        )

        val totalAdded = processLedgerPages(
            startSec = paginationStartSec,
            endSec = queryNow.epochSecond,
            isSeeded = isSeeded,
            // An unseeded first pass promotes coverage version/start/horizon, so it must
            // certify against the authoritative `count`. Ordinary incremental refresh of an
            // already-certified store stays retryable and does not re-promote coverage.
            mode = if (isSeeded) LedgerCoverageSyncMode.INCREMENTAL else LedgerCoverageSyncMode.COVERAGE_CERTIFICATION,
        )

        // A simulation run that found no ledger rows must not mark the store seeded: the emulator
        // has no ledger data, and a bogus "seeded + watermark" state would make a later live sync
        // skip the full history fetch. Live runs (even with an empty account) always finalize.
        val isSimulation = config.settings.simulation
        if (!isSimulation || isSeeded || totalAdded > 0) {
            finalizeSync(
                isSeeded = isSeeded,
                successfulQueryHorizon = queryNow,
                coverageStart = coverageBackfillBound,
                verifiedAccountScopeDigest = verifiedAccountScopeDigest,
            )
        } else {
            log.info("Simulation ledger sync produced no entries; leaving ledger store unseeded.")
            // Keep the 5-minute throttle engaged even when a simulation sync finds nothing: only
            // the seed/watermark state is deferred, never the next-sync timing.
            lastSyncTime = nowProvider()
        }
        log.info("Ledger synchronization completed. Added: {} entries.", totalAdded)
    }

    /**
     * Returns the latest locally proven ledger horizon when the completed inception recovery is
     * bound to the active account and reaches [requiredStart]. A completed stream plus its durable
     * oldest-row/total evidence is required; an old row by itself is never enough to promote
     * ordinary coverage.
     */
    private suspend fun recoverableCompletedRecoveryThrough(
        requiredStart: Instant,
        queryNow: Instant,
        verifiedAccountScopeDigest: String?,
    ): Instant? {
        val tradeRepository = tradeRepository ?: return null
        if (verifiedAccountScopeDigest.isNullOrBlank()) return null

        val storedScopeDigest = tradeRepository
            .getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST)
            ?.trim()
        val storedBindingVersion = tradeRepository
            .getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION)
            ?.trim()
        if (storedScopeDigest != verifiedAccountScopeDigest ||
            storedBindingVersion != AccountHistoryScopeGuard.CURRENT_BINDING_VERSION
        ) {
            return null
        }

        if (tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION) !=
            InceptionRecoveryService.CURRENT_RECOVERY_VERSION ||
            tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS) !=
            InceptionRecoveryStatus.COMPLETE ||
            repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS) !=
            InceptionRecoveryStatus.COMPLETE ||
            tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET) != "completed" ||
            repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET) != "completed"
        ) {
            return null
        }

        val recoveryHorizon = tradeRepository
            .getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_HORIZON_EPOCH_SEC)
            ?.toLongOrNull()
            ?.let(Instant::ofEpochSecond)
            ?: return null
        if (recoveryHorizon.isBefore(requiredStart) || recoveryHorizon.isAfter(queryNow)) return null

        val ledgerTotal = repository
            .getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL)
            ?.toIntOrNull()
            ?.takeIf { it >= 0 }
            ?: return null
        val ledgerOldest = repository
            .getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OLDEST_EPOCH_MS)
            ?.toLongOrNull()
            ?.let(Instant::ofEpochMilli)
        val ledgerRangeEvidence = ledgerTotal == 0 || ledgerOldest?.let { !it.isAfter(requiredStart) } == true
        if (!ledgerRangeEvidence) return null

        val tradeTotal = tradeRepository
            .getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_TOTAL)
            ?.toIntOrNull()
            ?.takeIf { it >= 0 }
            ?: return null
        val tradeOldest = tradeRepository
            .getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OLDEST_EPOCH_MS)
            ?.toLongOrNull()
            ?.let(Instant::ofEpochMilli)
        val tradeRangeEvidence = tradeTotal == 0 || tradeOldest?.let { !it.isAfter(requiredStart) } == true
        if (!tradeRangeEvidence) return null

        // Do not combine this prefix proof with an unrelated ordinary watermark: a prior v7
        // store may begin at the default seed bound, leaving a gap between that bound and this
        // recovery horizon. The migration must fetch from the recovery horizon to queryNow and
        // establish continuity itself.
        return recoveryHorizon
    }

    private suspend fun calculateEffectiveLatestTime(): Instant? {
        val latestLedgerTime = repository.getLatestLedgerTime()
        val watermarkInstant = readSyncWatermark()
        // Prefer the successful request horizon once present. The latest row can be older than a
        // prior empty scan and would otherwise make every incremental pull revisit that old time.
        return watermarkInstant ?: latestLedgerTime
    }

    private suspend fun processLedgerPages(
        startSec: Long?,
        endSec: Long,
        isSeeded: Boolean,
        mode: LedgerCoverageSyncMode = LedgerCoverageSyncMode.INCREMENTAL,
    ): Int {
        var totalAdded = 0
        // Cross-page duplicates are dropped by the unique (ledger id, timestamp, asset, type)
        // index; saveLedgers returns the number of rows actually inserted.
        getLedgersPaginated(startSec = startSec, endSec = endSec, isSeeded = isSeeded, mode = mode)
            .collect { apiLedgers ->
                invalidateReconstructionIfStale(apiLedgers)
                totalAdded += repository.saveLedgers(apiLedgers)
            }
        return totalAdded
    }

    private suspend fun invalidateReconstructionIfStale(apiLedgers: List<LedgerEvent>) {
        val tradeRepo = tradeRepository ?: return
        val reconstructionVersion = tradeRepo
            .getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION)
        if (reconstructionVersion.isNullOrBlank()) return

        val throughSec = tradeRepo
            .getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC)
            ?.toLongOrNull()
        val startSec = tradeRepo
            .getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_START_EPOCH_SEC)
            ?.toLongOrNull()
        val continuousStartMs = tradeRepo
            .getSyncMetadata(SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS)
            ?.toLongOrNull()
        val reconstructedThrough = throughSec?.let(Instant::ofEpochSecond)
            ?: continuousStartMs?.let(Instant::ofEpochMilli)
            ?: return
        val reconstructedStart = startSec?.let(Instant::ofEpochSecond) ?: Instant.EPOCH

        fun isInReconstructionInterval(time: Instant): Boolean =
            !time.isBefore(reconstructedStart) && !time.isAfter(reconstructedThrough)

        val historicalLedgers = apiLedgers.filter { isInReconstructionInterval(it.time) }
        if (historicalLedgers.isEmpty()) return

        val minTime = historicalLedgers.minOf { it.time }
        val maxTime = historicalLedgers.maxOf { it.time }
        val existing = repository.getLedgersInRange(minTime, maxTime)
        val hasNewHistoricalLedger = historicalLedgers.any { candidate ->
            existing.none {
                it.ledgerId == candidate.ledgerId &&
                    it.type == candidate.type &&
                    it.asset == candidate.asset
            }
        }

        if (hasNewHistoricalLedger) {
            log.info(
                "New ledger rows arrived before reconstructed through ({}); invalidating snapshot reconstruction.",
                reconstructedThrough,
            )
            tradeRepo.setSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION, "")
            tradeRepo.setSyncMetadata(SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS, "")
        }
    }

    private suspend fun finalizeSync(
        isSeeded: Boolean,
        successfulQueryHorizon: Instant,
        coverageStart: Instant,
        verifiedAccountScopeDigest: String? = null,
    ) {
        if (!isSeeded) {
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_VERSION,
                CURRENT_LEDGER_COVERAGE_VERSION,
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC,
                coverageStart.epochSecond.toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                successfulQueryHorizon.epochSecond.toString(),
            )
            if (verifiedAccountScopeDigest != null) {
                repository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST,
                    verifiedAccountScopeDigest,
                )
            }
            repository.setSyncMetadata(SyncMetadataKeys.LEDGER_OFFSET, SyncMetadataKeys.COMPLETED)
            repository.setSyncMetadata(SyncMetadataKeys.LEDGER_TOTAL, SyncMetadataKeys.COMPLETED)
        } else if (readInitialPaginationOffset() != null) {
            // Self-heal: an orphaned numeric offset (crash after seeding, before COMPLETED) must
            // not mark any future sync as an interrupted seed.
            repository.setSyncMetadata(SyncMetadataKeys.LEDGER_OFFSET, SyncMetadataKeys.COMPLETED)
            if (repository.getSyncMetadata(SyncMetadataKeys.LEDGER_TOTAL)
                    ?.toIntOrNull() != null
            ) {
                repository.setSyncMetadata(SyncMetadataKeys.LEDGER_TOTAL, SyncMetadataKeys.COMPLETED)
            }
        }
        // Persist watermark even when no real entries exist so the next sync is incremental.
        writeSyncWatermark(successfulQueryHorizon)
        repository.setSyncMetadata(
            SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
            successfulQueryHorizon.epochSecond.toString(),
        )
        if (verifiedAccountScopeDigest != null) {
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST,
                verifiedAccountScopeDigest,
            )
        }
        pruneOldEntries(successfulQueryHorizon)
        lastSyncTime = nowProvider()
    }

    /**
     * Ledger entries are retained indefinitely. Lifetime reconstruction
     * (ATH owner-capital netting and Buy & Hold benchmark replay) needs the
     * full ledger history from inception onward; pruning by
     * HISTORICAL_DAYS_BACK would silently corrupt both. Storage cost is
     * negligible (small rows, one exchange).
     */
    @Suppress("UnusedPrivateMember", "UnusedParameter")
    private suspend fun pruneOldEntries(reference: Instant) {
        // Intentional no-op: see retention contract above.
    }

    private suspend fun readSyncWatermark(): Instant? =
        repository.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
            ?.toLongOrNull()
            ?.let { Instant.ofEpochSecond(it) }

    private suspend fun writeSyncWatermark(instant: Instant) {
        repository.setSyncMetadata(
            SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
            instant.epochSecond.toString(),
        )
    }

    private suspend fun readInitialPaginationOffset(): Int? = repository
        .getSyncMetadata(SyncMetadataKeys.LEDGER_OFFSET)
        ?.toIntOrNull()
        ?.takeIf { it >= 0 }

    /** Cold paginated Kraken ledger history — unified raw coverage stream. */
    private fun getLedgersPaginated(
        startSec: Long?,
        endSec: Long,
        isSeeded: Boolean,
        mode: LedgerCoverageSyncMode = LedgerCoverageSyncMode.INCREMENTAL,
    ): Flow<List<LedgerEvent>> = flow {
        var offset = 0
        var priorTotal = repository
            .getSyncMetadata(SyncMetadataKeys.LEDGER_TOTAL)
            ?.toIntOrNull()
            ?.coerceAtLeast(0)
            ?: 0

        while (true) {
            log.info("Fetching coverage-grade ledger batch offset={}", offset)
            val page = krakenService.getLedgers(
                startSec = startSec,
                offset = offset,
                endSec = endSec,
                types = null,
            )
            val totalCount = krakenService.getLastLedgerTotalCount().coerceAtLeast(0)
            val hasAuthoritativeTotal = krakenService.hasLastLedgerTotalCount()
            if (!krakenService.hasLastLedgerPageShape()) {
                throw IllegalStateException("Kraken returned a malformed ledger page envelope")
            }
            val rawPageSize = krakenService.getLastLedgerRawPageSize().coerceAtLeast(page.size)
            if (!isSeeded && !hasAuthoritativeTotal && rawPageSize == 0 && page.isEmpty()) {
                throw IllegalStateException(
                    "Cannot finalize an unseeded ledger sync from an unknown empty page " +
                        "(offset=$offset)",
                )
            }
            if (mode == LedgerCoverageSyncMode.COVERAGE_CERTIFICATION && !hasAuthoritativeTotal) {
                throw IllegalStateException(
                    "Cannot certify ledger coverage from a count-less page " +
                        "(offset=$offset): authoritative count is required for coverage promotion",
                )
            }
            val expectedPageSize = (totalCount - offset)
                .takeIf { it > 0 }
                ?.coerceAtMost(KrakenApiConstants.LEDGER_PAGE_SIZE)
            val pageMatchesReportedTotal = when {
                !hasAuthoritativeTotal -> true

                totalCount == 0 -> page.isEmpty() && rawPageSize == 0

                else ->
                    expectedPageSize != null &&
                        rawPageSize == expectedPageSize &&
                        page.size <= expectedPageSize
            }
            if (hasAuthoritativeTotal && !pageMatchesReportedTotal) {
                throw IllegalStateException(
                    "Kraken ledger page occupancy disagreed with count " +
                        "(offset=$offset, count=$totalCount, rawPageSize=$rawPageSize)",
                )
            }
            val paginationShifted = hasAuthoritativeTotal && (
                (priorTotal > 0 && totalCount != priorTotal) ||
                    (priorTotal == 0 && offset > 0)
                )
            priorTotal = if (hasAuthoritativeTotal) totalCount else priorTotal

            if (!isSeeded) {
                repository.setSyncMetadata(SyncMetadataKeys.LEDGER_OFFSET, offset.toString())
                repository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_TOTAL,
                    if (hasAuthoritativeTotal) totalCount.toString() else (offset + page.size).toString(),
                )
            }
            if (page.isNotEmpty()) emit(page)

            val nextOffset = if (paginationShifted && offset > 0) {
                0
            } else {
                offset + KrakenApiConstants.LEDGER_PAGE_SIZE
            }
            val complete = !paginationShifted && if (hasAuthoritativeTotal) {
                nextOffset >= totalCount
            } else {
                rawPageSize < KrakenApiConstants.LEDGER_PAGE_SIZE
            }
            if (complete) break
            offset = nextOffset
        }
    }

    suspend fun getSyncMetadata(key: String): String? = repository.getSyncMetadata(key)

    suspend fun setSyncMetadata(key: String, value: String) = repository.setSyncMetadata(key, value)

    suspend fun isLedgersSeeded(): Boolean = repository.isLedgersSeeded()

    suspend fun isLedgerCoverageCurrent(): Boolean =
        repository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) == CURRENT_LEDGER_COVERAGE_VERSION
}

private fun AppConfig.canPullLedgers(): Boolean = settings.simulation || kraken.hasValidCredentials()
