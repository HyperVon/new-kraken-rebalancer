package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.service.ConfigService
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
 * Pulls Kraken's strategy-neutral ledger entries into the local database: staking, dividend, earn,
 * deposit, withdrawal, transfer, adjustment, consumer-transaction spend/receive rows, and the
 * margin-family balance rows (margin, rollover, settled, and credit).
 * The live adapter maps the latter two response types to Kraken's documented `sale` query filter.
 *
 * Ledger entries are insert-only: identity is the unique (ledger id, timestamp, asset, type) tuple,
 * so re-fetched pages (including the Kraken newest-first offset overlap) are deduplicated by the
 * database instead of reconciled like trades.
 */
class LedgersSyncService(
    private val repository: LedgerRepository,
    private val krakenService: KrakenService,
    private val configService: ConfigService,
    private val nowProvider: () -> Instant = Instant::now,
    private val accountHistoryScopeGuard: AccountHistoryScopeGuard? = null,
) {
    private val log = LoggerFactory.getLogger(LedgersSyncService::class.java)
    private val syncMutex = Mutex()
    private var lastSyncTime: Instant = Instant.EPOCH

    companion object {
        const val CURRENT_LEDGER_COVERAGE_VERSION = "5"
        val SUPPORTED_LEDGER_TYPES = listOf(
            KrakenApiConstants.LEDGER_TYPE_STAKING,
            KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
            KrakenApiConstants.LEDGER_TYPE_EARN,
            KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
            KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
            KrakenApiConstants.LEDGER_TYPE_TRANSFER,
            KrakenApiConstants.LEDGER_TYPE_ADJUSTMENT,
            KrakenApiConstants.LEDGER_TYPE_SPEND,
            KrakenApiConstants.LEDGER_TYPE_RECEIVE,
            KrakenApiConstants.LEDGER_TYPE_MARGIN,
            KrakenApiConstants.LEDGER_TYPE_ROLLOVER,
            KrakenApiConstants.LEDGER_TYPE_SETTLED,
            KrakenApiConstants.LEDGER_TYPE_CREDIT,
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
                syncLedgersFromKrakenPinned(pinnedConfig)
            }
        } finally {
            configService.endExecutionSession()
        }
    }

    private suspend fun syncLedgersFromKrakenPinned(config: AppConfig) {
        val isSeeded = repository.isLedgersSeeded()
        val coverageVersion = repository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION)
        val isCoverageCurrent = coverageVersion == CURRENT_LEDGER_COVERAGE_VERSION
        val needsCoverageBackfill = isSeeded && !isCoverageCurrent

        val seedBound = nowProvider().minus(PrecisionConstants.SEED_HISTORY_LOOKBACK_DAYS, ChronoUnit.DAYS)
        val queryNow = nowProvider()

        if (needsCoverageBackfill) {
            log.info(
                "Ledger store is seeded but coverage version is {} (expected {}). Running bounded backfill for newly supported types...",
                coverageVersion,
                CURRENT_LEDGER_COVERAGE_VERSION,
            )
            val totalAdded = processLedgerPages(
                startSec = seedBound.epochSecond,
                endSec = queryNow.epochSecond,
                isSeeded = true,
                typesToFetch = SUPPORTED_LEDGER_TYPES,
            )
            repository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, CURRENT_LEDGER_COVERAGE_VERSION)
            val currentWatermark = readSyncWatermark()
            if (currentWatermark == null || queryNow.isAfter(currentWatermark)) {
                writeSyncWatermark(queryNow)
            }
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
        // re-fetched and deduplicated rather than missed. Unseeded initial sync and recovery both
        // bound to SEED_HISTORY_LOOKBACK_DAYS like TradeHistorySyncService. Ledger entries are
        // retained indefinitely (lifetime retention contract), so no prune follows the fetch.
        val startSec = effectiveLatest?.minusSeconds(300)?.epochSecond
        val isRecoveringInitialSync = !isSeeded && readInitialPaginationOffset() != null
        val paginationStartSec = if (isRecoveringInitialSync) {
            seedBound.epochSecond
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
            typesToFetch = SUPPORTED_LEDGER_TYPES,
        )

        // A simulation run that found no ledger rows must not mark the store seeded: the emulator
        // has no ledger data, and a bogus "seeded + watermark" state would make a later live sync
        // skip the full history fetch. Live runs (even with an empty account) always finalize.
        val isSimulation = config.settings.simulation
        if (!isSimulation || isSeeded || totalAdded > 0) {
            finalizeSync(isSeeded, queryNow)
        } else {
            log.info("Simulation ledger sync produced no entries; leaving ledger store unseeded.")
            // Keep the 5-minute throttle engaged even when a simulation sync finds nothing: only
            // the seed/watermark state is deferred, never the next-sync timing.
            lastSyncTime = nowProvider()
        }
        log.info("Ledger synchronization completed. Added: {} entries.", totalAdded)
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
        typesToFetch: List<String> = SUPPORTED_LEDGER_TYPES,
    ): Int {
        var totalAdded = 0
        // Cross-page duplicates are dropped by the unique (ledger id, timestamp, asset, type)
        // index; saveLedgers returns the number of rows actually inserted.
        getLedgersPaginated(startSec = startSec, endSec = endSec, isSeeded = isSeeded, types = typesToFetch)
            .collect { apiLedgers ->
                totalAdded += repository.saveLedgers(apiLedgers)
            }
        return totalAdded
    }

    private suspend fun finalizeSync(isSeeded: Boolean, successfulQueryHorizon: Instant) {
        if (!isSeeded) {
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_VERSION,
                CURRENT_LEDGER_COVERAGE_VERSION,
            )
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

    /** Cold paginated Kraken ledger history — per-type cursors; progress is durable until the first seed completes. */
    private fun getLedgersPaginated(
        startSec: Long?,
        endSec: Long,
        isSeeded: Boolean,
        types: List<String> = SUPPORTED_LEDGER_TYPES,
    ): Flow<List<LedgerEvent>> = flow {
        val perTypeOffset = mutableMapOf<String, Int>().apply { types.forEach { this[it] = 0 } }
        val perTypeTotal = mutableMapOf<String, Int>().apply { types.forEach { this[it] = 0 } }
        val perTypeDone = mutableMapOf<String, Boolean>().apply { types.forEach { this[it] = false } }

        while (perTypeDone.values.any { !it }) {
            val batches = mutableListOf<List<LedgerEvent>>()
            var combinedBatchSize = 0
            for (type in types) {
                if (perTypeDone[type] == true) continue
                val offset = perTypeOffset[type] ?: 0
                log.info("Fetching ledger batch type={} offset={}", type, offset)
                val page = krakenService.getLedgers(
                    startSec = startSec,
                    offset = offset,
                    endSec = endSec,
                    types = setOf(type),
                )
                val totalCount = krakenService.getLastLedgerTotalCount()
                val rawPageSize = krakenService.getLastLedgerRawPageSize()
                perTypeTotal[type] = totalCount
                batches.add(page)
                combinedBatchSize += page.size
                val nextOffset = offset + KrakenApiConstants.LEDGER_PAGE_SIZE
                val hasMoreForType = if (totalCount > 0) {
                    nextOffset < totalCount
                } else {
                    val pageSizeToCheck = if (rawPageSize > 0) rawPageSize else page.size
                    pageSizeToCheck >= KrakenApiConstants.LEDGER_PAGE_SIZE
                }
                if (!hasMoreForType) perTypeDone[type] = true else perTypeOffset[type] = nextOffset
            }
            if (!isSeeded) {
                val effectiveOffset = types.sumOf { type ->
                    if (perTypeDone[type] ==
                        true
                    ) {
                        perTypeTotal[type] ?: 0
                    } else {
                        perTypeOffset[type] ?: 0
                    }
                }
                val effectiveTotal = perTypeTotal.values.sum().let {
                    if (it ==
                        0
                    ) {
                        effectiveOffset + combinedBatchSize
                    } else {
                        it
                    }
                }
                repository.setSyncMetadata(SyncMetadataKeys.LEDGER_OFFSET, effectiveOffset.toString())
                repository.setSyncMetadata(SyncMetadataKeys.LEDGER_TOTAL, effectiveTotal.toString())
            }
            val merged = batches.flatten()
            if (merged.isNotEmpty()) emit(merged)
        }
    }

    suspend fun getSyncMetadata(key: String): String? = repository.getSyncMetadata(key)

    suspend fun setSyncMetadata(key: String, value: String) = repository.setSyncMetadata(key, value)

    suspend fun isLedgersSeeded(): Boolean = repository.isLedgersSeeded()

    suspend fun isLedgerCoverageCurrent(): Boolean =
        repository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) == CURRENT_LEDGER_COVERAGE_VERSION
}

private fun AppConfig.canPullLedgers(): Boolean = settings.simulation || kraken.hasValidCredentials()
