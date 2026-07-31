package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.model.isLegacyUnknown
import com.gemini.krakenbot.model.isLocalEstimate
import com.gemini.krakenbot.model.isMatchingApiTrade
import com.gemini.krakenbot.model.isSettledApiFill
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.util.TradeCalculator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

class TradeHistorySyncService(
    private val repository: TradeRepository,
    private val krakenService: KrakenService,
    private val configService: ConfigService,
    private val reconstructionService: TradeHistoryReconstructionService,
    private val nowProvider: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(TradeHistorySyncService::class.java)
    private val syncMutex = Mutex()
    private var lastSyncTime: Instant = Instant.EPOCH

    suspend fun syncTradesFromKraken() = syncMutex.withLock {
        syncTradesFromKrakenLocked()
    }

    private suspend fun syncTradesFromKrakenLocked() {
        val now = nowProvider()
        val elapsedSeconds = Duration.between(lastSyncTime, now).seconds
        // Throttle Kraken history pulls to at most once per 5 minutes.
        if (elapsedSeconds in 0 until 300) {
            log.info("Skipping trade history synchronization; last run was only {} seconds ago.", elapsedSeconds)
            return
        }

        val preflightConfig = configService.getConfig()
        if (!preflightConfig.settings.simulation && !preflightConfig.kraken.hasValidCredentials()) {
            log.warn("Kraken API key is blank or placeholder. Skipping trade history synchronization.")
            return
        }

        configService.beginExecutionSession()
        try {
            val pinnedConfig = configService.getConfig()
            if (!pinnedConfig.settings.simulation && !pinnedConfig.kraken.hasValidCredentials()) {
                log.warn("Kraken API key became unavailable before synchronization started. Skipping synchronization.")
                return
            }
            krakenService.withStableBackend { syncTradesFromKrakenPinned(pinnedConfig) }
        } finally {
            configService.endExecutionSession()
        }
    }

    private suspend fun syncTradesFromKrakenPinned(config: AppConfig) {
        val isSeeded = repository.isHistorySeeded()
        val effectiveLatest = calculateEffectiveLatestTime()
        // Null effective → full history (startSec null). Otherwise overlap by 5 minutes so fills
        // near the previous watermark are re-fetched and reconciled rather than double-inserted.
        // [isHistorySeeded] only gates progress metadata / first-sync completion, not this window.
        val startSec = effectiveLatest?.minusSeconds(300)?.epochSecond
        // A numeric progress cursor marks an interrupted seed; recovery widens to a full query so
        // a partial watermark cannot filter out older pages.
        val isRecoveringInitialSync = readInitialPaginationOffset() != null
        val paginationStartSec = if (isRecoveringInitialSync) null else startSec

        log.info(
            "Starting trade history synchronization (isSeeded={}, startSec={}, recovering={})...",
            isSeeded,
            paginationStartSec,
            isRecoveringInitialSync,
        )

        // A resumed seed uses a full-history query. The persisted page offset is only
        // a progress marker; restart from page zero because new fills can shift Kraken offsets.
        val queryStart =
            if (isRecoveringInitialSync) {
                Instant.EPOCH
            } else {
                effectiveLatest?.minusSeconds(300) ?: Instant.EPOCH
            }
        val queryEnd = nowProvider().plusSeconds(300)
        val originalLocalTrades = repository.getTradesInRange(queryStart, queryEnd).toMutableList()
        val allocations = config.allocations.map { it.symbol.value }

        val (totalAdded, totalReconciled) = processApiTrades(
            startSec = paginationStartSec,
            isSeeded = isSeeded,
            originalLocalTrades = originalLocalTrades,
            allocations = allocations,
        )

        triggerReconstructionIfNeeded(config)

        finalizeSync(isSeeded)
        log.info("Trade history synchronization completed. Added: {} new, Reconciled: {}.", totalAdded, totalReconciled)
    }

    private suspend fun calculateEffectiveLatestTime(): Instant? {
        val latestTradeTime = repository.getLatestTradeTime()
        val watermarkInstant = readSyncWatermark()
        // Prefer real/sim fill time; only fall back to the last successful sync watermark when
        // there are no non-dry-run fills (CQ-8-M2). Do not max() with wall-clock watermark — that
        // would shrink the reconcile window below latestTradeTime and strand unreconciled locals.
        return latestTradeTime ?: watermarkInstant
    }

    private suspend fun processApiTrades(
        startSec: Long?,
        isSeeded: Boolean,
        originalLocalTrades: MutableList<TradeRecord>,
        allocations: List<String>,
    ): Pair<Int, Int> {
        var totalAdded = 0
        var totalReconciled = 0
        // Fingerprints of API fills already handled in this sync. Kraken newest-first offset
        // pagination can re-emit the last row of page N as the first of page N+1 when a fill
        // lands mid-pagination; without this set that row is double-inserted (CQ-8-M1).
        val seenApiFillKeys = mutableSetOf<String>()

        getTradeHistoryPaginated(startSec = startSec, isSeeded = isSeeded)
            .collect { apiTrades ->
                for (apiTrade: TradeRecord in apiTrades) {
                    if (!seenApiFillKeys.add(apiFillIdentityKey(apiTrade))) continue

                    val result = reconcileOrInsertApiTrade(apiTrade, originalLocalTrades, allocations)
                    when (result) {
                        is TradeReconciliationResult.Inserted -> totalAdded++
                        is TradeReconciliationResult.Reconciled -> totalReconciled++
                        is TradeReconciliationResult.AlreadyPersisted -> { /* no-op */ }
                        is TradeReconciliationResult.MatchedNoOp -> { /* no-op */ }
                    }
                }
            }

        return totalAdded to totalReconciled
    }

    private suspend fun reconcileOrInsertApiTrade(
        apiTrade: TradeRecord,
        originalLocalTrades: MutableList<TradeRecord>,
        allocations: List<String>,
    ): TradeReconciliationResult {
        // Exact persisted fills must win before nearby local-estimate reconciliation;
        // otherwise an overlapping fetch can rewrite a local row even though this API
        // fill has already been stored (CQ-10-L2). Legacy-unknown rows are also kept
        // intact when their conservative fingerprint matches a fetched fill.
        val persistedFill =
            originalLocalTrades.find { persisted ->
                (persisted.isSettledApiFill() || persisted.isLegacyUnknown()) &&
                    hasSamePersistedFillIdentity(persisted, apiTrade)
            }
        if (persistedFill != null) {
            originalLocalTrades.remove(persistedFill)
            return TradeReconciliationResult.AlreadyPersisted
        }

        val matchingLocalTrade = findMatchingLocalTrade(apiTrade, originalLocalTrades, allocations)

        return if (matchingLocalTrade != null) {
            reconcileWithLocalTrade(apiTrade, matchingLocalTrade, originalLocalTrades)
        } else {
            repository.saveTrade(apiTrade)
            TradeReconciliationResult.Inserted
        }
    }

    private fun findMatchingLocalTrade(
        apiTrade: TradeRecord,
        originalLocalTrades: MutableList<TradeRecord>,
        allocations: List<String>,
    ): TradeRecord? {
        // A Kraken order id is authoritative when both sides have one. Otherwise use
        // economics tolerances so legacy/id-less rows can still reconcile.
        val localEstimates =
            originalLocalTrades.filter { local ->
                local.submissionState == null && !local.dryRun && local.isLocalEstimate()
            }
        val apiOrderTxid = apiTrade.orderTxid?.takeIf { it.isNotBlank() }
        return apiOrderTxid?.let { txid ->
            localEstimates.find { local -> local.orderTxid?.takeIf { it.isNotBlank() } == txid }
        } ?: localEstimates
            .asSequence()
            .filter { local ->
                apiOrderTxid == null || local.orderTxid.isNullOrBlank()
            }.find { local ->
                local.isMatchingApiTrade(apiTrade, allocations)
            }
    }

    private suspend fun reconcileWithLocalTrade(
        apiTrade: TradeRecord,
        matchingLocalTrade: TradeRecord,
        originalLocalTrades: MutableList<TradeRecord>,
    ): TradeReconciliationResult {
        val changed = matchingLocalTrade != apiTrade
        if (changed) {
            val expectedPrice = matchingLocalTrade.expectedPrice
            val reconciledSlippage =
                expectedPrice?.let { expected ->
                    TradeCalculator.calculateSlippage(
                        apiTrade.side,
                        apiTrade.price,
                        expected,
                    )
                }
            val reconciledTrade =
                apiTrade.copy(
                    expectedPrice = expectedPrice,
                    slippagePercent = reconciledSlippage,
                    source = TradeSource.API_FILL,
                    // Keep local cycle linkage; prefer API ordertxid when present.
                    cycleId = matchingLocalTrade.cycleId,
                    orderTxid = apiTrade.orderTxid ?: matchingLocalTrade.orderTxid,
                )
            log.info(
                "Reconciling trade record: local (timestamp={}, usdAmount={}) with API (timestamp={}, usdAmount={})",
                matchingLocalTrade.timestamp,
                matchingLocalTrade.usdAmount,
                apiTrade.timestamp,
                apiTrade.usdAmount,
            )

            repository.updateTrade(matchingLocalTrade, reconciledTrade)
        }
        // Drop from the pending set after persistence so a DB failure leaves the
        // in-memory view unchanged (pre-refactor update-then-remove order).
        originalLocalTrades.remove(matchingLocalTrade)
        return if (changed) {
            TradeReconciliationResult.Reconciled
        } else {
            // Data-class-equal local row: removed but not counted as a reconcile.
            TradeReconciliationResult.MatchedNoOp
        }
    }

    private sealed class TradeReconciliationResult {
        data object Inserted : TradeReconciliationResult()
        data object Reconciled : TradeReconciliationResult()
        data object AlreadyPersisted : TradeReconciliationResult()
        data object MatchedNoOp : TradeReconciliationResult()
    }

    private suspend fun triggerReconstructionIfNeeded(config: AppConfig) {
        val snapshots = repository.load()
        val totalTrades = repository.getTradeSummaryStats().totalTradesExecuted
        val isSimulation = config.settings.simulation

        if (!isSimulation && totalTrades > 0 && snapshots.size <= 1) {
            log.info(
                "Historical snapshots are missing or insufficient (found {} snapshots, {} trades). Starting reconstruction...",
                snapshots.size,
                totalTrades,
            )
            try {
                reconstructionService.reconstructHistoricalSnapshots()
                log.info("Historical snapshot reconstruction completed successfully.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to reconstruct historical snapshots", e)
            }
        }
    }

    private suspend fun finalizeSync(isSeeded: Boolean) {
        if (!isSeeded) {
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.SYNC_OFFSET, SyncMetadataKeys.COMPLETED)
            repository.setSyncMetadata(SyncMetadataKeys.SYNC_TOTAL, SyncMetadataKeys.COMPLETED)
        }
        // Persist watermark even when no real fills exist so the next sync is incremental.
        val completedAt = nowProvider()
        writeSyncWatermark(completedAt)
        lastSyncTime = completedAt
    }

    private suspend fun readSyncWatermark(): Instant? =
        repository.getSyncMetadata(SyncMetadataKeys.SYNC_WATERMARK_EPOCH_SEC)
            ?.toLongOrNull()
            ?.let { Instant.ofEpochSecond(it) }

    private suspend fun writeSyncWatermark(instant: Instant) {
        repository.setSyncMetadata(
            SyncMetadataKeys.SYNC_WATERMARK_EPOCH_SEC,
            instant.epochSecond.toString(),
        )
    }

    private suspend fun readInitialPaginationOffset(): Int? = repository
        .getSyncMetadata(SyncMetadataKeys.SYNC_OFFSET)
        ?.toIntOrNull()
        ?.takeIf { it >= 0 && it % PAGE_SIZE == 0 }

    /**
     * Identity for a single API fill within one sync pass. Kraken's trade id is the authoritative
     * per-fill identity; fall back to a full economics fingerprint for historical rows that lack it.
     */
    private fun apiFillIdentityKey(trade: TradeRecord): String = trade.tradeId
        ?.takeIf { it.isNotBlank() }
        ?.let { "trade-id:$it" }
        ?: legacyApiFillFingerprint(trade)

    private fun hasSamePersistedFillIdentity(persisted: TradeRecord, apiTrade: TradeRecord): Boolean {
        val persistedTradeId = persisted.tradeId?.takeIf { it.isNotBlank() }
        val apiTradeId = apiTrade.tradeId?.takeIf { it.isNotBlank() }
        return if (persistedTradeId != null && apiTradeId != null) {
            persistedTradeId == apiTradeId
        } else {
            legacyApiFillFingerprint(persisted) == legacyApiFillFingerprint(apiTrade)
        }
    }

    private fun legacyApiFillFingerprint(trade: TradeRecord): String = listOf(
        trade.timestamp.toEpochMilli().toString(),
        trade.pair,
        trade.side.uppercase(),
        trade.volume.toPlainString(),
        trade.usdAmount.toPlainString(),
        trade.price.toPlainString(),
        trade.fee.toPlainString(),
        trade.orderTxid.orEmpty(),
    ).joinToString("|")

    /** Cold paginated Kraken history; progress is durable until the first seed completes. */
    private fun getTradeHistoryPaginated(startSec: Long?, isSeeded: Boolean): Flow<List<TradeRecord>> = flow {
        var offset = 0

        while (true) {
            log.info("Fetching trade history batch with offset={}", offset)
            val apiTrades = krakenService.getTradeHistory(startSec = startSec, offset = offset)
            val totalCount = krakenService.getLastTradeHistoryTotalCount()

            if (!isSeeded) {
                repository.setSyncMetadata(SyncMetadataKeys.SYNC_OFFSET, offset.toString())
                repository.setSyncMetadata(SyncMetadataKeys.SYNC_TOTAL, totalCount.toString())
            }

            if (apiTrades.isEmpty()) break

            emit(apiTrades)

            if (apiTrades.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
    }

    suspend fun getSyncMetadata(key: String): String? = repository.getSyncMetadata(key)

    suspend fun setSyncMetadata(key: String, value: String) = repository.setSyncMetadata(key, value)

    suspend fun isHistorySeeded(): Boolean = repository.isHistorySeeded()

    private companion object {
        const val PAGE_SIZE = 50
    }
}
