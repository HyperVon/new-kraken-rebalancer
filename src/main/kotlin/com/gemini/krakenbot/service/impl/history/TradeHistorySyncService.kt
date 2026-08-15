package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.model.OrderSide
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
import com.gemini.krakenbot.service.getTradeHistoryUntil
import com.gemini.krakenbot.service.impl.KrakenApiConstants
import com.gemini.krakenbot.service.withExecutionSession
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

    // Seed/initial history pulls are bounded to this lookback: filled trades older than
    // HISTORICAL_DAYS_BACK (90d) are pruned and reconstruction only reaches ~95 days, so pulling
    // more than this would fetch data that is immediately discarded.
    private val SEED_HISTORY_LOOKBACK: Duration = Duration.ofDays(96)

    suspend fun syncTradesFromKraken() = syncMutex.withLock {
        syncTradesFromKrakenLocked()
    }

    suspend fun rebuildHistoricalSnapshotsIfNeeded() {
        val config = configService.getConfig()
        if (config.settings.simulation ||
            repository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION) ==
            TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION
        ) {
            return
        }

        if (!reconstructionService.canRebuildSnapshots()) return

        log.info("Snapshot reconstruction version is stale or missing; rebuilding historical snapshots.")
        configService.withExecutionSession {
            val pinnedConfig = configService.getConfig()
            if (pinnedConfig.settings.simulation) return@withExecutionSession
            krakenService.withStableBackend { backend ->
                reconstructionService.rebuildHistoricalSnapshots(pinnedConfig, backend)
            }
        }
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
        if (!preflightConfig.canPullTradeHistory()) {
            log.warn("Kraken API key is blank or placeholder. Skipping trade history synchronization.")
            return
        }

        configService.beginExecutionSession()
        try {
            val pinnedConfig = configService.getConfig()
            if (!pinnedConfig.canPullTradeHistory()) {
                log.warn("Kraken API key became unavailable before synchronization started. Skipping synchronization.")
                return
            }
            krakenService.withStableBackend { backend ->
                syncTradesFromKrakenPinned(pinnedConfig, backend)
            }
        } finally {
            configService.endExecutionSession()
        }
    }

    private suspend fun syncTradesFromKrakenPinned(config: AppConfig, backend: KrakenService) {
        val isSeeded = repository.isHistorySeeded()
        val effectiveLatest = calculateEffectiveLatestTime()
        // Bound every history pull to the seed lookback window instead of fetching full history
        // since the account was created: filled trades older than HISTORICAL_DAYS_BACK are pruned
        // and reconstruction only reaches ~95 days, so anything older would be immediately
        // discarded. Incremental syncs still overlap the previous watermark by 5 minutes so fills
        // near it are re-fetched and reconciled rather than double-inserted.
        // [isHistorySeeded] only gates progress metadata / first-sync completion, not this window.
        val seedBound = nowProvider().minus(SEED_HISTORY_LOOKBACK)
        val startSec = effectiveLatest?.minusSeconds(300)?.epochSecond ?: seedBound.epochSecond
        // A numeric progress cursor marks an interrupted seed. Recovery only applies while the
        // database is unseeded: once seeding completed, an orphaned numeric offset (e.g. the process
        // died after setHistorySeeded but before the COMPLETED marker) is stale and must not force
        // a full-history query on every future sync.
        val isRecoveringInitialSync = !isSeeded && readInitialPaginationOffset() != null
        // A resumed seed restarts from page zero (new fills can shift Kraken offsets) but is still
        // bounded to the seed lookback; the persisted page offset is only a progress marker.
        val paginationStartSec = if (isRecoveringInitialSync) seedBound.epochSecond else startSec

        log.info(
            "Starting trade history synchronization (isSeeded={}, startSec={}, recovering={})...",
            isSeeded,
            paginationStartSec,
            isRecoveringInitialSync,
        )

        // queryStart mirrors the bounded seed window so local reconcile candidates cover the same
        // horizon as the Kraken pull (previously this was a full-history EPOCH query on a resumed
        // seed, pulling far more than the retained/ reconstructable window).
        val queryStart = Instant.ofEpochSecond(paginationStartSec)
        val queryNow = nowProvider()
        val queryEnd = queryNow.plusSeconds(300)
        val originalLocalTrades = repository.getTradesInRange(queryStart, queryEnd).toMutableList()
        val allocations = config.allocations.map { it.symbol.value }

        val (totalAdded, totalReconciled) = processApiTrades(
            startSec = paginationStartSec,
            endSec = queryNow.epochSecond,
            isSeeded = isSeeded,
            originalLocalTrades = originalLocalTrades,
            allocations = allocations,
        )

        triggerReconstructionIfNeeded(config, backend)

        finalizeSync(isSeeded)
        log.info("Trade history synchronization completed. Added: {} new, Reconciled: {}.", totalAdded, totalReconciled)
    }

    private suspend fun calculateEffectiveLatestTime(): Instant? {
        val latestTradeTime = repository.getLatestTradeTime()
        val watermarkInstant = readSyncWatermark()
        return listOfNotNull(latestTradeTime, watermarkInstant).maxOrNull()
    }

    private suspend fun processApiTrades(
        startSec: Long?,
        endSec: Long,
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

        getTradeHistoryPaginated(startSec = startSec, endSec = endSec, isSeeded = isSeeded)
            .collect { apiTrades ->
                for (apiTrade: TradeRecord in apiTrades) {
                    if (!seenApiFillKeys.add(apiFillIdentityKey(apiTrade))) continue

                    val result = reconcileOrInsertApiTrade(apiTrade, originalLocalTrades, allocations)
                    when (result) {
                        TradeReconciliationResult.INSERTED -> totalAdded++
                        TradeReconciliationResult.RECONCILED -> totalReconciled++
                        TradeReconciliationResult.ALREADY_PERSISTED -> { /* no-op */ }
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
            return TradeReconciliationResult.ALREADY_PERSISTED
        }

        val matchingLocalTrade = findMatchingLocalTrade(apiTrade, originalLocalTrades, allocations)

        return if (matchingLocalTrade != null) {
            reconcileWithLocalTrade(apiTrade, matchingLocalTrade, originalLocalTrades)
        } else {
            repository.saveTrade(apiTrade)
            TradeReconciliationResult.INSERTED
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
                local.submissionState == null && local.success && !local.dryRun && local.isLocalEstimate()
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
        // Drop from the pending set after persistence so a DB failure leaves the
        // in-memory view unchanged (pre-refactor update-then-remove order).
        originalLocalTrades.remove(matchingLocalTrade)
        return TradeReconciliationResult.RECONCILED
    }

    private enum class TradeReconciliationResult { INSERTED, RECONCILED, ALREADY_PERSISTED }

    private suspend fun triggerReconstructionIfNeeded(config: AppConfig, backend: KrakenService) {
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
                reconstructionService.reconstructHistoricalSnapshots(config, backend)
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
        } else if (readInitialPaginationOffset() != null) {
            // Self-heal: an orphaned numeric offset (crash after seeding, before COMPLETED) would
            // otherwise linger forever; it must not mark any future sync as an interrupted seed.
            // Also normalize SYNC_TOTAL so a crash between the two COMPLETED writes does not leave
            // a lone numeric total behind.
            repository.setSyncMetadata(SyncMetadataKeys.SYNC_OFFSET, SyncMetadataKeys.COMPLETED)
            if (repository.getSyncMetadata(SyncMetadataKeys.SYNC_TOTAL)
                    ?.toIntOrNull() != null
            ) {
                repository.setSyncMetadata(SyncMetadataKeys.SYNC_TOTAL, SyncMetadataKeys.COMPLETED)
            }
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
        ?.takeIf { it >= 0 && it % KrakenApiConstants.TRADE_HISTORY_PAGE_SIZE == 0 }

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
        OrderSide.normalize(trade.side),
        trade.volume.toPlainString(),
        trade.usdAmount.toPlainString(),
        trade.price.toPlainString(),
        trade.fee.toPlainString(),
        trade.orderTxid.orEmpty(),
    ).joinToString("|")

    /** Cold paginated Kraken history; progress is durable until the first seed completes. */
    private fun getTradeHistoryPaginated(startSec: Long?, endSec: Long, isSeeded: Boolean): Flow<List<TradeRecord>> =
        flow {
            var offset = 0

            while (true) {
                log.info("Fetching trade history batch with offset={}", offset)
                val apiTrades = krakenService.getTradeHistoryUntil(
                    startSec = startSec,
                    offset = offset,
                    endSec = endSec,
                )
                val totalCount = krakenService.getLastTradeHistoryTotalCount()

                if (!isSeeded) {
                    repository.setSyncMetadata(SyncMetadataKeys.SYNC_OFFSET, offset.toString())
                    repository.setSyncMetadata(SyncMetadataKeys.SYNC_TOTAL, totalCount.toString())
                }

                if (apiTrades.isNotEmpty()) emit(apiTrades)

                val nextOffset = offset + KrakenApiConstants.TRADE_HISTORY_PAGE_SIZE
                val hasMorePages = if (totalCount > 0) {
                    nextOffset < totalCount
                } else {
                    apiTrades.size >= KrakenApiConstants.TRADE_HISTORY_PAGE_SIZE
                }
                if (!hasMorePages) break
                offset = nextOffset
            }
        }

    suspend fun getSyncMetadata(key: String): String? = repository.getSyncMetadata(key)

    suspend fun setSyncMetadata(key: String, value: String) = repository.setSyncMetadata(key, value)

    suspend fun isHistorySeeded(): Boolean = repository.isHistorySeeded()
}

private fun AppConfig.canPullTradeHistory(): Boolean = settings.simulation || kraken.hasValidCredentials()
