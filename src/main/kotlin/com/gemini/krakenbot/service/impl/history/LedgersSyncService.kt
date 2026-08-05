package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.impl.KrakenApiConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pulls Kraken ledger entries (staking rewards and dividend payouts) into the local database.
 *
 * Ledger entries are insert-only: identity is the unique (refid, timestamp, asset, type) tuple,
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
        val paginationStartSec = if (isRecoveringInitialSync) null else startSec

        log.info(
            "Starting ledger synchronization (isSeeded={}, startSec={}, recovering={})...",
            isSeeded,
            paginationStartSec,
            isRecoveringInitialSync,
        )

        // A resumed seed uses a full-history query; the persisted page offset is only a progress
        // marker, so restart from page zero (new ledger entries can shift Kraken offsets).
        val queryStart =
            if (isRecoveringInitialSync) {
                Instant.EPOCH
            } else {
                effectiveLatest?.minusSeconds(300) ?: Instant.EPOCH
            }
        val queryNow = nowProvider()
        val queryEnd = queryNow.plusSeconds(300)

        val totalAdded = processLedgerPages(
            startSec = paginationStartSec,
            endSec = queryNow.epochSecond,
            isSeeded = isSeeded,
        )

        finalizeSync(isSeeded)
        log.info("Ledger synchronization completed. Added: {} entries.", totalAdded)
    }

    private suspend fun calculateEffectiveLatestTime(): Instant? {
        val latestLedgerTime = repository.getLatestLedgerTime()
        val watermarkInstant = readSyncWatermark()
        // Prefer the newest stored entry; only fall back to the last successful sync watermark
        // when nothing has been stored yet.
        return latestLedgerTime ?: watermarkInstant
    }

    private suspend fun processLedgerPages(startSec: Long?, endSec: Long, isSeeded: Boolean): Int {
        var totalAdded = 0
        // Cross-page duplicates are dropped by the unique (refid, timestamp, asset, type) index;
        // saveLedgers returns the number of rows actually inserted.
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
        lastSyncTime = completedAt
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

    /** Cold paginated Kraken ledger history; progress is durable until the first seed completes. */
    private fun getLedgersPaginated(startSec: Long?, endSec: Long, isSeeded: Boolean): Flow<List<LedgerEvent>> = flow {
        var offset = 0

        while (true) {
            log.info("Fetching ledger batch with offset={}", offset)
            val apiLedgers = krakenService.getLedgers(
                startSec = startSec,
                offset = offset,
                endSec = endSec,
                types = setOf(KrakenApiConstants.LEDGER_TYPE_STAKING, KrakenApiConstants.LEDGER_TYPE_DIVIDEND),
            )
            val totalCount = krakenService.getLastLedgerTotalCount()

            if (!isSeeded) {
                repository.setSyncMetadata(SyncMetadataKeys.LEDGER_OFFSET, offset.toString())
                repository.setSyncMetadata(SyncMetadataKeys.LEDGER_TOTAL, totalCount.toString())
            }

            if (apiLedgers.isNotEmpty()) emit(apiLedgers)

            val nextOffset = offset + KrakenApiConstants.LEDGER_PAGE_SIZE
            val hasMorePages = if (totalCount > 0) {
                nextOffset < totalCount
            } else {
                apiLedgers.size >= KrakenApiConstants.LEDGER_PAGE_SIZE
            }
            if (!hasMorePages) break
            offset = nextOffset
        }
    }

    suspend fun getSyncMetadata(key: String): String? = repository.getSyncMetadata(key)

    suspend fun setSyncMetadata(key: String, value: String) = repository.setSyncMetadata(key, value)

    suspend fun isLedgersSeeded(): Boolean = repository.isLedgersSeeded()
}

private fun AppConfig.canPullLedgers(): Boolean = settings.simulation || kraken.hasValidCredentials()
