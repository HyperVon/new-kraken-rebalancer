package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.domain.OrderFillReconciler
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeReconciliationConflictException
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.isLegacyUnknown
import com.gemini.krakenbot.model.isLocalEstimate
import com.gemini.krakenbot.model.isSettledApiFill
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.getTradeHistoryUntil
import com.gemini.krakenbot.service.withExecutionSession
import com.gemini.krakenbot.util.PrecisionConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
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
        val seedBound = nowProvider().minus(PrecisionConstants.SEED_HISTORY_LOOKBACK_DAYS, ChronoUnit.DAYS)
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

        // Persist the successful request horizon, not the later completion timestamp. A slow
        // pagination/reconstruction phase must not move the next query window past unseen fills.
        finalizeSync(isSeeded, queryNow)
        log.info("Trade history synchronization completed. Added: {} new, Reconciled: {}.", totalAdded, totalReconciled)
    }

    private suspend fun calculateEffectiveLatestTime(): Instant? {
        val latestTradeTime = repository.getLatestTradeTime()
        val watermarkInstant = readSyncWatermark()
        return watermarkInstant ?: latestTradeTime
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
        val seenApiFillKeys = mutableSetOf<String>()
        val orderMetadataByTxid = mutableMapOf<String, LocalOrderMetadata>()

        originalLocalTrades
            .filter { it.isSettledApiFill() && !it.orderTxid.isNullOrBlank() && !it.cycleId.isNullOrBlank() }
            .groupBy { it.orderTxid!!.trim() }
            .forEach { (txid, fills) ->
                val first = fills.first()
                if (fills.all { it.symbol == first.symbol && it.side == first.side && it.pair == first.pair }) {
                    orderMetadataByTxid[txid] = LocalOrderMetadata(
                        localTradeId = first.id,
                        orderTxid = txid,
                        pair = first.pair,
                        symbol = first.symbol,
                        side = first.side,
                        expectedPrice = first.expectedPrice,
                        cycleId = first.cycleId,
                        clientOrderId = first.clientOrderId,
                    )
                }
            }

        getTradeHistoryPaginated(startSec = startSec, endSec = endSec, isSeeded = isSeeded)
            .collect { apiTrades ->
                for (apiTrade: TradeRecord in apiTrades) {
                    if (!seenApiFillKeys.add(apiFillIdentityKey(apiTrade))) continue

                    val result = reconcileOrInsertApiTrade(
                        apiTrade = apiTrade,
                        originalLocalTrades = originalLocalTrades,
                        allocations = allocations,
                        orderMetadataByTxid = orderMetadataByTxid,
                    )
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
        orderMetadataByTxid: MutableMap<String, LocalOrderMetadata>,
    ): TradeReconciliationResult {
        val persistedFill =
            originalLocalTrades.find { persisted ->
                (persisted.isSettledApiFill() || persisted.isLegacyUnknown()) &&
                    hasSamePersistedFillIdentity(persisted, apiTrade)
            }

        val localEstimates = originalLocalTrades.filter { local ->
            local.submissionState == null && local.success && !local.dryRun && local.isLocalEstimate()
        }

        if (persistedFill != null) {
            validateExactOrderLocalIntegrityForPersistedFill(
                persistedFill = persistedFill,
                apiTrade = apiTrade,
                localEstimates = localEstimates,
                orderMetadataByTxid = orderMetadataByTxid,
                allocations = allocations,
            )
            originalLocalTrades.remove(persistedFill)
            return TradeReconciliationResult.ALREADY_PERSISTED
        }

        val resolution = resolveLocalOrderContextForApiFill(
            apiTrade = apiTrade,
            localEstimates = localEstimates,
            orderMetadataByTxid = orderMetadataByTxid,
            allocations = allocations,
        )

        return when (resolution) {
            is LocalOrderResolution.Conflict -> {
                throw TradeReconciliationConflictException(resolution.message)
            }

            is LocalOrderResolution.ReconcileLocal -> {
                reconcileWithLocalTrade(
                    apiTrade = apiTrade,
                    matchingLocalTrade = resolution.localTrade,
                    metadata = resolution.metadata,
                    originalLocalTrades = originalLocalTrades,
                    orderMetadataByTxid = orderMetadataByTxid,
                )
                TradeReconciliationResult.RECONCILED
            }

            is LocalOrderResolution.EnrichedFromCache -> {
                val enrichedTrade = OrderFillReconciler.enrichApiFill(
                    apiFill = apiTrade,
                    expectedPrice = resolution.metadata.expectedPrice,
                    cycleId = resolution.metadata.cycleId,
                    clientOrderId = resolution.metadata.clientOrderId,
                    orderTxid = resolution.metadata.orderTxid,
                )
                repository.saveTrade(enrichedTrade)
                TradeReconciliationResult.INSERTED
            }

            is LocalOrderResolution.None -> {
                val matchingLocal = findHeuristicMatchingLocalTrade(
                    apiTrade = apiTrade,
                    localEstimates = localEstimates,
                    allocations = allocations,
                )
                if (matchingLocal != null) {
                    val metadata = LocalOrderMetadata(
                        localTradeId = matchingLocal.id,
                        orderTxid = matchingLocal.orderTxid ?: "",
                        pair = matchingLocal.pair,
                        symbol = matchingLocal.symbol,
                        side = matchingLocal.side,
                        expectedPrice = matchingLocal.expectedPrice,
                        cycleId = matchingLocal.cycleId,
                        clientOrderId = matchingLocal.clientOrderId,
                    )
                    reconcileWithLocalTrade(
                        apiTrade = apiTrade,
                        matchingLocalTrade = matchingLocal,
                        metadata = metadata,
                        originalLocalTrades = originalLocalTrades,
                        orderMetadataByTxid = orderMetadataByTxid,
                    )
                    TradeReconciliationResult.RECONCILED
                } else {
                    repository.saveTrade(apiTrade)
                    TradeReconciliationResult.INSERTED
                }
            }
        }
    }

    private fun validateExactOrderLocalIntegrityForPersistedFill(
        persistedFill: TradeRecord,
        apiTrade: TradeRecord,
        localEstimates: List<TradeRecord>,
        orderMetadataByTxid: Map<String, LocalOrderMetadata>,
        allocations: List<String>,
    ) {
        val apiOrderTxid = apiTrade.orderTxid?.trim()?.takeIf(String::isNotBlank) ?: return

        val keyedLocals = localEstimates.filter { local ->
            local.orderTxid?.trim()?.takeIf(String::isNotBlank) == apiOrderTxid
        }
        if (keyedLocals.size > 1) {
            val localIds = keyedLocals.map { it.id }
            throw TradeReconciliationConflictException(
                "Cannot reconcile Kraken order $apiOrderTxid: authoritative API fill " +
                    "(tradeId=${persistedFill.tradeId}) is already persisted, but multiple local order " +
                    "estimates (IDs: $localIds) claim this order identity.",
            )
        }
        if (keyedLocals.size == 1) {
            val local = keyedLocals.single()
            val isCompatible = OrderFillReconciler.isInstrumentCompatible(
                orderSymbol = local.symbol,
                orderSide = local.side,
                orderPair = local.pair,
                apiFill = apiTrade,
                allocations = allocations,
            )
            if (!isCompatible) {
                throw TradeReconciliationConflictException(
                    "Cannot reconcile Kraken order $apiOrderTxid: authoritative API fill " +
                        "(tradeId=${persistedFill.tradeId}, pair=${apiTrade.pair}, symbol=${apiTrade.symbol}, " +
                        "side=${apiTrade.side}) is already persisted, but incompatible local order " +
                        "estimate (ID: ${local.id}, pair=${local.pair}, symbol=${local.symbol}, " +
                        "side=${local.side}) claims this order identity.",
                )
            }
            throw TradeReconciliationConflictException(
                "Cannot reconcile Kraken order $apiOrderTxid: authoritative API fill " +
                    "(tradeId=${persistedFill.tradeId}) is already persisted, but un-superseded " +
                    "local order estimate (ID: ${local.id}) claims this order identity.",
            )
        }

        val cached = orderMetadataByTxid[apiOrderTxid]
        if (cached != null) {
            val isCachedCompatible = OrderFillReconciler.isInstrumentCompatible(
                orderSymbol = cached.symbol,
                orderSide = cached.side,
                orderPair = cached.pair,
                apiFill = apiTrade,
                allocations = allocations,
            )
            if (!isCachedCompatible) {
                throw TradeReconciliationConflictException(
                    "Cannot reconcile Kraken order $apiOrderTxid: cached local order metadata " +
                        "(tradeId=${cached.localTradeId}, pair=${cached.pair}, symbol=${cached.symbol}, " +
                        "side=${cached.side}) is incompatible with already-persisted API fill " +
                        "(tradeId=${apiTrade.tradeId}, pair=${apiTrade.pair}, symbol=${apiTrade.symbol}, " +
                        "side=${apiTrade.side}).",
                )
            }
        }
    }

    private fun resolveLocalOrderContextForApiFill(
        apiTrade: TradeRecord,
        localEstimates: List<TradeRecord>,
        orderMetadataByTxid: Map<String, LocalOrderMetadata>,
        allocations: List<String>,
    ): LocalOrderResolution {
        val apiOrderTxid = apiTrade.orderTxid?.trim()?.takeIf(String::isNotBlank) ?: return LocalOrderResolution.None

        val keyedLocals = localEstimates.filter { local ->
            local.orderTxid?.trim()?.takeIf(String::isNotBlank) == apiOrderTxid
        }

        if (keyedLocals.size > 1) {
            val localIds = keyedLocals.map { it.id }
            return LocalOrderResolution.Conflict(
                "Cannot reconcile Kraken order $apiOrderTxid: multiple local order estimates (IDs: $localIds) " +
                    "claim this order identity.",
            )
        }

        if (keyedLocals.size == 1) {
            val local = keyedLocals.single()
            val isCompatible = OrderFillReconciler.isInstrumentCompatible(
                orderSymbol = local.symbol,
                orderSide = local.side,
                orderPair = local.pair,
                apiFill = apiTrade,
                allocations = allocations,
            ) && apiTrade.volume <= local.volume.multiply(BigDecimal("1.01"))

            if (!isCompatible) {
                return LocalOrderResolution.Conflict(
                    "Cannot reconcile Kraken order $apiOrderTxid: local order estimate (ID: ${local.id}, " +
                        "pair=${local.pair}, symbol=${local.symbol}, side=${local.side}) is incompatible with " +
                        "API fill (tradeId=${apiTrade.tradeId}, pair=${apiTrade.pair}, symbol=${apiTrade.symbol}, " +
                        "side=${apiTrade.side}).",
                )
            }

            val metadata = LocalOrderMetadata(
                localTradeId = local.id,
                orderTxid = apiOrderTxid,
                pair = local.pair,
                symbol = local.symbol,
                side = local.side,
                expectedPrice = local.expectedPrice,
                cycleId = local.cycleId,
                clientOrderId = local.clientOrderId,
            )
            return LocalOrderResolution.ReconcileLocal(local, metadata)
        }

        val cached = orderMetadataByTxid[apiOrderTxid]
        if (cached != null) {
            val isCachedCompatible = OrderFillReconciler.isInstrumentCompatible(
                orderSymbol = cached.symbol,
                orderSide = cached.side,
                orderPair = cached.pair,
                apiFill = apiTrade,
                allocations = allocations,
            )
            if (!isCachedCompatible) {
                return LocalOrderResolution.Conflict(
                    "Cannot reconcile Kraken order $apiOrderTxid: cached local order metadata " +
                        "(tradeId=${cached.localTradeId}, pair=${cached.pair}, symbol=${cached.symbol}, " +
                        "side=${cached.side}) is incompatible with API fill (tradeId=${apiTrade.tradeId}, " +
                        "pair=${apiTrade.pair}, symbol=${apiTrade.symbol}, side=${apiTrade.side}).",
                )
            }
            return LocalOrderResolution.EnrichedFromCache(cached)
        }

        return LocalOrderResolution.None
    }

    private fun findHeuristicMatchingLocalTrade(
        apiTrade: TradeRecord,
        localEstimates: List<TradeRecord>,
        allocations: List<String>,
    ): TradeRecord? {
        val apiOrderTxid = apiTrade.orderTxid?.trim()?.takeIf(String::isNotBlank)
        val matches = localEstimates.filter { local ->
            val localOrderTxid = local.orderTxid?.trim()?.takeIf(String::isNotBlank)
            if (apiOrderTxid != null && localOrderTxid != null && apiOrderTxid != localOrderTxid) {
                false
            } else {
                OrderFillReconciler.matchesHeuristic(
                    orderSymbol = local.symbol,
                    orderSide = local.side,
                    orderPair = local.pair,
                    orderVolume = local.volume,
                    orderUsdAmount = local.usdAmount,
                    orderExpectedPrice = local.expectedPrice,
                    orderTimestamp = local.timestamp,
                    apiFill = apiTrade,
                    allocations = allocations,
                )
            }
        }
        if (matches.size > 1) {
            val localIds = matches.map { it.id }
            throw TradeReconciliationConflictException(
                "Cannot reconcile API fill ${apiTrade.tradeId}: multiple local order estimates " +
                    "(IDs: $localIds) match heuristic criteria.",
            )
        }
        return matches.singleOrNull()
    }

    private suspend fun reconcileWithLocalTrade(
        apiTrade: TradeRecord,
        matchingLocalTrade: TradeRecord,
        metadata: LocalOrderMetadata,
        originalLocalTrades: MutableList<TradeRecord>,
        orderMetadataByTxid: MutableMap<String, LocalOrderMetadata>,
    ) {
        val effectiveTxid = (apiTrade.orderTxid ?: matchingLocalTrade.orderTxid)?.trim()?.takeIf(String::isNotBlank)
        if (effectiveTxid != null) {
            orderMetadataByTxid.putIfAbsent(effectiveTxid, metadata)
        }

        val reconciledTrade = OrderFillReconciler.enrichApiFill(
            apiFill = apiTrade,
            expectedPrice = metadata.expectedPrice,
            cycleId = metadata.cycleId,
            clientOrderId = metadata.clientOrderId,
            orderTxid = effectiveTxid ?: apiTrade.orderTxid,
        )
        log.info(
            "Reconciling trade record: local (timestamp={}, usdAmount={}) with API (timestamp={}, usdAmount={})",
            matchingLocalTrade.timestamp,
            matchingLocalTrade.usdAmount,
            apiTrade.timestamp,
            apiTrade.usdAmount,
        )

        repository.updateTrade(matchingLocalTrade, reconciledTrade)
        originalLocalTrades.remove(matchingLocalTrade)
    }

    private data class LocalOrderMetadata(
        val localTradeId: Int?,
        val orderTxid: String,
        val pair: String,
        val symbol: String,
        val side: String,
        val expectedPrice: BigDecimal?,
        val cycleId: String?,
        val clientOrderId: String?,
    )

    private sealed class LocalOrderResolution {
        data object None : LocalOrderResolution()

        data class ReconcileLocal(val localTrade: TradeRecord, val metadata: LocalOrderMetadata) :
            LocalOrderResolution()

        data class EnrichedFromCache(val metadata: LocalOrderMetadata) : LocalOrderResolution()

        data class Conflict(val message: String) : LocalOrderResolution()
    }

    private enum class TradeReconciliationResult { INSERTED, RECONCILED, ALREADY_PERSISTED }

    private suspend fun triggerReconstructionIfNeeded(config: AppConfig, backend: KrakenService) {
        val snapshots = repository.load()
        val totalTrades = repository.getTradeSummaryStats().totalTradesExecuted
        val isSimulation = config.settings.simulation

        if (!isSimulation && totalTrades > 0 && snapshots.size <= 1) {
            if (!reconstructionService.canRebuildSnapshots()) {
                log.info(
                    "Skipping historical snapshot reconstruction during trade sync: ledger coverage is not current.",
                )
                return
            }
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

    private suspend fun finalizeSync(isSeeded: Boolean, successfulQueryHorizon: Instant) {
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
        writeSyncWatermark(successfulQueryHorizon)
        // Local throttling is based on completion; the durable cursor is based on the request
        // horizon above and must never be advanced in a finally block after a failed pull.
        lastSyncTime = nowProvider()
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
        if (persistedTradeId != null && apiTradeId != null && persistedTradeId != apiTradeId) return false

        val persistedOrderTxid = persisted.orderTxid?.takeIf { it.isNotBlank() }
        val apiOrderTxid = apiTrade.orderTxid?.takeIf { it.isNotBlank() }
        if (persistedOrderTxid != null && apiOrderTxid != null && persistedOrderTxid != apiOrderTxid) return false

        if (persistedTradeId != null && apiTradeId != null) {
            return true
        }

        // An order can produce multiple fills. If either trade id is absent, a shared order txid
        // alone is not enough to prove that two rows represent the same fill leg.
        return legacyApiFillFingerprint(persisted) == legacyApiFillFingerprint(apiTrade)
    }

    private fun legacyApiFillFingerprint(trade: TradeRecord): String = listOf(
        trade.timestamp.toEpochMilli().toString(),
        trade.pair,
        OrderSide.normalize(trade.side),
        canonicalDecimal(trade.volume),
        canonicalDecimal(trade.usdAmount),
        canonicalDecimal(trade.price),
        canonicalDecimal(trade.fee),
        trade.orderTxid.orEmpty(),
    ).joinToString("|")

    private fun canonicalDecimal(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

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
