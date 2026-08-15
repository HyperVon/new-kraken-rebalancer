package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.impl.KrakenApiConstants
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
 * Pulls Kraken ledger entries (staking rewards and dividend payouts) into the local database.
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
) {
    private val log = LoggerFactory.getLogger(LedgersSyncService::class.java)
    private val syncMutex = Mutex()
    private var lastSyncTime: Instant = Instant.EPOCH

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
            krakenService.withStableBackend { syncLedgersFromKrakenPinned(pinnedConfig) }
        } finally {
            configService.endExecutionSession()
        }
    }

    private suspend fun syncLedgersFromKrakenPinned(config: AppConfig) {
        val isSeeded = repository.isLedgersSeeded()
        val effectiveLatest = calculateEffectiveLatestTime()
        // Null effective → full history (startSec null). Otherwise overlap by 5 minutes so entries
        // near the previous watermark are re-fetched and deduplicated rather than missed.
        val startSec = effectiveLatest?.minusSeconds(300)?.epochSecond
        // A numeric progress cursor marks an interrupted seed; it only gates recovery while the
        // ledger store is unseeded (mirrors the trade sync watermark logic).
        val isRecoveringInitialSync = !isSeeded && readInitialPaginationOffset() != null
        // CQ-19-06: recovery must not fetch full history (startSec=null) — bound to 96d like
        // TradeHistorySyncService to avoid wasting rate-limit on pruned data.
        val seedBound = nowProvider().minus(96, ChronoUnit.DAYS)
        val paginationStartSec = if (isRecoveringInitialSync) seedBound.epochSecond else startSec

        log.info(
            "Starting ledger synchronization (isSeeded={}, startSec={}, recovering={})...",
            isSeeded,
            paginationStartSec,
            isRecoveringInitialSync,
        )

        val queryNow = nowProvider()

        val totalAdded = processLedgerPages(
            startSec = paginationStartSec,
            endSec = queryNow.epochSecond,
            isSeeded = isSeeded,
        )

        // A simulation run that found no ledger rows must not mark the store seeded: the emulator
        // has no ledger data, and a bogus "seeded + watermark" state would make a later live sync
        // skip the full history fetch. Live runs (even with an empty account) always finalize.
        val isSimulation = config.settings.simulation
        if (!isSimulation || isSeeded || totalAdded > 0) {
            finalizeSync(isSeeded)
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
        return listOfNotNull(latestLedgerTime, watermarkInstant).maxOrNull()
    }

    private suspend fun processLedgerPages(startSec: Long?, endSec: Long, isSeeded: Boolean): Int {
        var totalAdded = 0
        // Cross-page duplicates are dropped by the unique (ledger id, timestamp, asset, type)
        // index; saveLedgers returns the number of rows actually inserted.
        getLedgersPaginated(startSec = startSec, endSec = endSec, isSeeded = isSeeded)
            .collect { apiLedgers ->
                totalAdded += repository.saveLedgers(apiLedgers)
            }
        return totalAdded
    }

    private suspend fun finalizeSync(isSeeded: Boolean) {
        if (!isSeeded) {
            repository.setLedgersSeeded(true)
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
        val completedAt = nowProvider()
        writeSyncWatermark(completedAt)
        pruneOldEntries(completedAt)
        lastSyncTime = completedAt
    }

    /** Mirrors the snapshot/trade retention window (HISTORICAL_DAYS_BACK) for ledger entries. */
    private suspend fun pruneOldEntries(reference: Instant) {
        try {
            val cutoff = reference.minus(PrecisionConstants.HISTORICAL_DAYS_BACK.toLong(), ChronoUnit.DAYS)
            val pruned = repository.pruneLedgersOlderThan(cutoff)
            if (pruned > 0) {
                log.info(
                    "Pruned {} ledger entries older than {} days.",
                    pruned,
                    PrecisionConstants.HISTORICAL_DAYS_BACK,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to prune old ledger entries", e)
        }
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
        ?.takeIf { it >= 0 && it % KrakenApiConstants.LEDGER_PAGE_SIZE == 0 }

    /** Cold paginated Kraken ledger history — per-type cursors; progress is durable until the first seed completes. */
    private fun getLedgersPaginated(startSec: Long?, endSec: Long, isSeeded: Boolean): Flow<List<LedgerEvent>> = flow {
        val ledgerTypes = listOf(LedgerEvent.TYPE_STAKING, LedgerEvent.TYPE_DIVIDEND)
        val perTypeOffset = mutableMapOf<String, Int>().apply { ledgerTypes.forEach { this[it] = 0 } }
        val perTypeTotal = mutableMapOf<String, Int>().apply { ledgerTypes.forEach { this[it] = 0 } }
        val perTypeDone = mutableMapOf<String, Boolean>().apply { ledgerTypes.forEach { this[it] = false } }

        while (perTypeDone.values.any { !it }) {
            val batches = mutableListOf<List<LedgerEvent>>()
            var combinedBatchSize = 0
            for (type in ledgerTypes) {
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
                perTypeTotal[type] = totalCount
                batches.add(page)
                combinedBatchSize += page.size
                val nextOffset = offset + KrakenApiConstants.LEDGER_PAGE_SIZE
                val hasMoreForType = if (totalCount >
                    0
                ) {
                    nextOffset < totalCount
                } else {
                    page.size >= KrakenApiConstants.LEDGER_PAGE_SIZE
                }
                if (!hasMoreForType) perTypeDone[type] = true else perTypeOffset[type] = nextOffset
            }
            if (!isSeeded) {
                val effectiveOffset = ledgerTypes.sumOf { type ->
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
}

private fun AppConfig.canPullLedgers(): Boolean = settings.simulation || kraken.hasValidCredentials()
