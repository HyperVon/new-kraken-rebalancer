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
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.InceptionRecoveryStatus
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.getRecoveryTradeHistoryUntil
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
    private val accountHistoryScopeGuard: AccountHistoryScopeGuard? = null,
    private val ledgerRepository: LedgerRepository? = null,
) {
    private val log = LoggerFactory.getLogger(TradeHistorySyncService::class.java)
    private val syncMutex = Mutex()
    private var lastSyncTime: Instant = Instant.EPOCH

    companion object {
        const val CURRENT_TRADE_COVERAGE_VERSION = "1"
    }

    suspend fun syncTradesFromKraken() = syncMutex.withLock {
        syncTradesFromKrakenLocked()
    }

    /**
     * Imports one bounded recovery page without touching the ordinary sync cursor or seeded flag.
     * Recovery deliberately reuses the normal fill reconciler so a historical API fill can enrich
     * a retained local estimate/order-intent row instead of creating a second economic event.
     */
    internal suspend fun importRecoveredApiTrades(apiTrades: List<TradeRecord>): Pair<Int, Int> = syncMutex.withLock {
        if (apiTrades.isEmpty()) return@withLock 0 to 0
        val first = apiTrades.minOf { it.timestamp }
        val last = apiTrades.maxOf { it.timestamp }
        val originalLocalTrades = repository
            .getTradesInRange(first.minusSeconds(600), last.plusSeconds(600))
            .toMutableList()
        val allocations = configService.getConfig().allocations.map { it.symbol.value }
        val orderMetadataByTxid = buildOrderMetadata(originalLocalTrades)
        val result = processApiTradeBatch(
            apiTrades = apiTrades,
            originalLocalTrades = originalLocalTrades,
            allocations = allocations,
            orderMetadataByTxid = orderMetadataByTxid,
            seenApiFillKeys = mutableSetOf(),
        )

        result
    }

    suspend fun rebuildHistoricalSnapshotsIfNeeded() {
        val config = configService.getConfig()
        if (config.settings.simulation) return

        val parsedInception = config.settings.inceptionDate
            ?.let(InceptionDiscoveryService::parseInceptionDate)
        val reconstructionAnchor = nowProvider()
        val canRebuild = reconstructionService.canRebuildSnapshots(config, parsedInception, reconstructionAnchor)
        val storedContinuousStart = repository
            .getSyncMetadata(SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS)
            ?.toLongOrNull()
            ?.let(Instant::ofEpochMilli)
        val continuousStartCoversInception = parsedInception == null ||
            (storedContinuousStart != null && !storedContinuousStart.isAfter(parsedInception))

        val reconstructionIsCurrent =
            repository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION) ==
                TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION &&
                repository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_LEDGER_COVERAGE_VERSION) ==
                LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION &&
                repository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_TRADE_COVERAGE_VERSION) ==
                CURRENT_TRADE_COVERAGE_VERSION &&
                continuousStartCoversInception &&
                canRebuild

        if (reconstructionIsCurrent || !canRebuild) return

        log.info(
            "Snapshot reconstruction version is stale, missing, or does not cover requested inception; rebuilding historical snapshots.",
        )
        configService.withExecutionSession {
            val pinnedConfig = configService.getConfig()
            if (pinnedConfig.settings.simulation) return@withExecutionSession
            krakenService.withStableBackend { backend ->
                reconstructionService.rebuildHistoricalSnapshots(pinnedConfig, backend, reconstructionAnchor)
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
                val scopeResult = accountHistoryScopeGuard?.validateAccountScope()
                if (scopeResult != null && !scopeResult.isValid) {
                    log.warn(
                        "Account scope validation failed: {}. Skipping trade history synchronization.",
                        scopeResult.reason,
                    )
                    return@withStableBackend
                }
                syncTradesFromKrakenPinned(pinnedConfig, backend, scopeResult?.currentScopeDigest)
            }
        } finally {
            configService.endExecutionSession()
        }
    }

    private suspend fun syncTradesFromKrakenPinned(
        config: AppConfig,
        backend: KrakenService,
        verifiedAccountScopeDigest: String?,
    ) {
        val isSeeded = repository.isHistorySeeded()
        val coverageVersion = repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION)
        val seedBound = nowProvider().minus(PrecisionConstants.SEED_HISTORY_LOOKBACK_DAYS, ChronoUnit.DAYS)
        val configuredInception = config.settings.inceptionDate
            ?.let(InceptionDiscoveryService::parseInceptionDate)
        val coverageBackfillBound = configuredInception?.takeIf { it.isBefore(seedBound) } ?: seedBound
        val storedCoverageStartSec = repository
            .getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC)
            ?.toLongOrNull()
        val coverageStartMatches = !coverageBackfillBound.isBefore(seedBound) ||
            (storedCoverageStartSec != null && storedCoverageStartSec <= coverageBackfillBound.epochSecond)
        val storedScopeDigest = repository
            .getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST)
        val scopeMatches = verifiedAccountScopeDigest.isNullOrBlank() || storedScopeDigest == verifiedAccountScopeDigest
        val isCoverageCurrent =
            coverageVersion == CURRENT_TRADE_COVERAGE_VERSION && coverageStartMatches && scopeMatches
        val needsCoverageBackfill = isSeeded && !isCoverageCurrent
        val queryNow = nowProvider()

        if (needsCoverageBackfill) {
            log.info(
                "Trade store is seeded but coverage version is {} (expected {}). Running coverage backfill from {}...",
                coverageVersion,
                CURRENT_TRADE_COVERAGE_VERSION,
                coverageBackfillBound,
            )
            val recoveredCoverageThrough = recoverableCompletedTradeRecoveryThrough(
                requiredStart = coverageBackfillBound,
                queryNow = queryNow,
                verifiedAccountScopeDigest = verifiedAccountScopeDigest,
            )
            val queryEnd = queryNow.plusSeconds(300)
            val (totalAdded, totalReconciled) = if (recoveredCoverageThrough == null) {
                val originalLocalTrades = repository
                    .getTradesInRange(coverageBackfillBound.minusSeconds(1), queryEnd)
                    .toMutableList()
                val allocations = config.allocations.map { it.symbol.value }
                processApiTrades(
                    startSec = coverageBackfillBound.minusSeconds(1).epochSecond,
                    endSec = queryNow.epochSecond,
                    isSeeded = true,
                    originalLocalTrades = originalLocalTrades,
                    allocations = allocations,
                )
            } else if (recoveredCoverageThrough.isBefore(queryNow)) {
                // Completed recovery proves the historical prefix. Only refresh the unproven tail;
                // never repaginate the already recovered inception-to-horizon range.
                val originalLocalTrades = repository
                    .getTradesInRange(recoveredCoverageThrough.minusSeconds(300), queryEnd)
                    .toMutableList()
                val allocations = config.allocations.map { it.symbol.value }
                processApiTrades(
                    startSec = recoveredCoverageThrough.minusSeconds(300).epochSecond,
                    endSec = queryNow.epochSecond,
                    isSeeded = true,
                    originalLocalTrades = originalLocalTrades,
                    allocations = allocations,
                )
            } else {
                0 to 0
            }
            finalizeSync(
                isSeeded = true,
                successfulQueryHorizon = queryNow,
                coverageStart = coverageBackfillBound,
                verifiedAccountScopeDigest = verifiedAccountScopeDigest,
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC,
                coverageBackfillBound.epochSecond.toString(),
            )
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, CURRENT_TRADE_COVERAGE_VERSION)
            triggerReconstructionIfNeeded(config, backend)
            log.info(
                "Trade coverage backfill completed. Added: {} new, Reconciled: {}. Coverage version is now {}.",
                totalAdded,
                totalReconciled,
                CURRENT_TRADE_COVERAGE_VERSION,
            )
            return
        }

        val effectiveLatest = calculateEffectiveLatestTime()
        val startSec = effectiveLatest?.minusSeconds(300)?.epochSecond
        val isRecoveringInitialSync = !isSeeded && readInitialPaginationOffset() != null
        val paginationStartSec = if (!isSeeded) {
            coverageBackfillBound.epochSecond
        } else {
            startSec ?: seedBound.epochSecond
        }

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

        // Persist the successful request horizon, not the later completion timestamp. A slow
        // pagination/reconstruction phase must not move the next query window past unseen fills.
        finalizeSync(
            isSeeded = isSeeded,
            successfulQueryHorizon = queryNow,
            coverageStart = coverageBackfillBound,
            verifiedAccountScopeDigest = verifiedAccountScopeDigest,
        )

        triggerReconstructionIfNeeded(config, backend)
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
        val orderMetadataByTxid = buildOrderMetadata(originalLocalTrades)

        getTradeHistoryPaginated(startSec = startSec, endSec = endSec, isSeeded = isSeeded)
            .collect { apiTrades ->
                val result = processApiTradeBatch(
                    apiTrades = apiTrades,
                    originalLocalTrades = originalLocalTrades,
                    allocations = allocations,
                    orderMetadataByTxid = orderMetadataByTxid,
                    seenApiFillKeys = seenApiFillKeys,
                )
                totalAdded += result.first
                totalReconciled += result.second
            }

        return totalAdded to totalReconciled
    }

    private suspend fun processApiTradeBatch(
        apiTrades: List<TradeRecord>,
        originalLocalTrades: MutableList<TradeRecord>,
        allocations: List<String>,
        orderMetadataByTxid: MutableMap<String, LocalOrderMetadata>,
        seenApiFillKeys: MutableSet<String>,
    ): Pair<Int, Int> {
        var totalAdded = 0
        var totalReconciled = 0
        val results = mutableListOf<TradeReconciliationResult>()
        for (apiTrade in apiTrades) {
            if (!seenApiFillKeys.add(apiFillIdentityKey(apiTrade))) continue

            val result = reconcileOrInsertApiTrade(
                apiTrade = apiTrade,
                originalLocalTrades = originalLocalTrades,
                allocations = allocations,
                orderMetadataByTxid = orderMetadataByTxid,
            )
            results.add(result)
            when (result) {
                is TradeReconciliationResult.Inserted -> totalAdded++
                is TradeReconciliationResult.Reconciled -> totalReconciled++
                TradeReconciliationResult.AlreadyPersisted -> { /* no-op */ }
            }
        }
        invalidateReconstructionIfStale(results)
        return totalAdded to totalReconciled
    }

    private suspend fun invalidateReconstructionIfStale(results: List<TradeReconciliationResult>) {
        val reconstructionVersion = repository
            .getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION)
        if (reconstructionVersion.isNullOrBlank()) return

        val throughSec = repository
            .getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC)
            ?.toLongOrNull()
        val startSec = repository
            .getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_START_EPOCH_SEC)
            ?.toLongOrNull()
        val continuousStartMs = repository
            .getSyncMetadata(SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS)
            ?.toLongOrNull()

        val reconstructedThrough = throughSec?.let(Instant::ofEpochSecond)
            ?: continuousStartMs?.let(Instant::ofEpochMilli)
            ?: return
        val reconstructedStart = startSec?.let(Instant::ofEpochSecond) ?: Instant.EPOCH

        fun isInReconstructionInterval(time: Instant): Boolean =
            !time.isBefore(reconstructedStart) && !time.isAfter(reconstructedThrough)

        var shouldInvalidate = false
        for (result in results) {
            when (result) {
                is TradeReconciliationResult.Inserted -> {
                    if (isInReconstructionInterval(result.trade.timestamp)) {
                        log.info(
                            "New fill arrived within reconstruction interval [{}, {}] (timestamp={}); invalidating snapshot reconstruction.",
                            reconstructedStart,
                            reconstructedThrough,
                            result.trade.timestamp,
                        )
                        shouldInvalidate = true
                        break
                    }
                }

                is TradeReconciliationResult.Reconciled -> {
                    if (isInReconstructionInterval(result.newTrade.timestamp) &&
                        hasMaterialEconomicChange(result.oldTrade, result.newTrade)
                    ) {
                        log.info(
                            "Reconciled trade materially changed within reconstruction interval [{}, {}] (timestamp={}); invalidating snapshot reconstruction.",
                            reconstructedStart,
                            reconstructedThrough,
                            result.newTrade.timestamp,
                        )
                        shouldInvalidate = true
                        break
                    }
                }

                TradeReconciliationResult.AlreadyPersisted -> { /* no-op */ }
            }
        }

        if (shouldInvalidate) {
            repository.setSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION, "")
            repository.setSyncMetadata(SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS, "")
        }
    }

    private fun hasMaterialEconomicChange(old: TradeRecord, new: TradeRecord): Boolean =
        old.volume.compareTo(new.volume) != 0 ||
            old.usdAmount.compareTo(new.usdAmount) != 0 ||
            old.price.compareTo(new.price) != 0 ||
            old.fee.compareTo(new.fee) != 0 ||
            old.timestamp != new.timestamp

    private fun buildOrderMetadata(originalLocalTrades: List<TradeRecord>): MutableMap<String, LocalOrderMetadata> =
        mutableMapOf<String, LocalOrderMetadata>().also { result ->
            originalLocalTrades
                .filter { it.isSettledApiFill() && !it.orderTxid.isNullOrBlank() && !it.cycleId.isNullOrBlank() }
                .groupBy { it.orderTxid!!.trim() }
                .forEach { (txid, fills) ->
                    val first = fills.first()
                    if (fills.all { it.symbol == first.symbol && it.side == first.side && it.pair == first.pair }) {
                        result[txid] = LocalOrderMetadata(
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
            return TradeReconciliationResult.AlreadyPersisted
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
                val reconciled = reconcileWithLocalTrade(
                    apiTrade = apiTrade,
                    matchingLocalTrade = resolution.localTrade,
                    metadata = resolution.metadata,
                    originalLocalTrades = originalLocalTrades,
                    orderMetadataByTxid = orderMetadataByTxid,
                )
                TradeReconciliationResult.Reconciled(resolution.localTrade, reconciled)
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
                TradeReconciliationResult.Inserted(enrichedTrade)
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
                    val reconciled = reconcileWithLocalTrade(
                        apiTrade = apiTrade,
                        matchingLocalTrade = matchingLocal,
                        metadata = metadata,
                        originalLocalTrades = originalLocalTrades,
                        orderMetadataByTxid = orderMetadataByTxid,
                    )
                    TradeReconciliationResult.Reconciled(matchingLocal, reconciled)
                } else {
                    repository.saveTrade(apiTrade)
                    TradeReconciliationResult.Inserted(apiTrade)
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
    ): TradeRecord {
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
        return reconciledTrade
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

    private sealed class TradeReconciliationResult {
        data class Inserted(val trade: TradeRecord) : TradeReconciliationResult()
        data class Reconciled(val oldTrade: TradeRecord, val newTrade: TradeRecord) : TradeReconciliationResult()
        data object AlreadyPersisted : TradeReconciliationResult()
    }

    private suspend fun triggerReconstructionIfNeeded(config: AppConfig, backend: KrakenService) {
        val snapshots = repository.load()
        val totalTrades = repository.getTradeSummaryStats().totalTradesExecuted
        val isSimulation = config.settings.simulation

        if (!isSimulation && totalTrades > 0 && snapshots.size <= 1) {
            val reconstructionAnchor = nowProvider()
            if (!reconstructionService.canRebuildSnapshots(config, reconstructionAnchor = reconstructionAnchor)) {
                log.info(
                    "Skipping historical snapshot reconstruction during trade sync: trade or ledger coverage is not current.",
                )
                return
            }
            log.info(
                "Historical snapshots are missing or insufficient (found {} snapshots, {} trades). Starting reconstruction...",
                snapshots.size,
                totalTrades,
            )
            try {
                reconstructionService.reconstructHistoricalSnapshots(config, backend, reconstructionAnchor)
                log.info("Historical snapshot reconstruction completed successfully.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to reconstruct historical snapshots", e)
            }
        }
    }

    private suspend fun finalizeSync(
        isSeeded: Boolean,
        successfulQueryHorizon: Instant,
        coverageStart: Instant,
        verifiedAccountScopeDigest: String? = null,
    ) {
        if (!isSeeded) {
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_VERSION,
                CURRENT_TRADE_COVERAGE_VERSION,
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC,
                coverageStart.epochSecond.toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC,
                successfulQueryHorizon.epochSecond.toString(),
            )
            if (verifiedAccountScopeDigest != null) {
                repository.setSyncMetadata(
                    SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST,
                    verifiedAccountScopeDigest,
                )
            }
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
        repository.setSyncMetadata(
            SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC,
            successfulQueryHorizon.epochSecond.toString(),
        )
        if (verifiedAccountScopeDigest != null) {
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST,
                verifiedAccountScopeDigest,
            )
        }
        // Local throttling is based on completion; the durable cursor is based on the request
        // horizon above and must never be advanced in a finally block after a failed pull.
        lastSyncTime = nowProvider()
    }

    private suspend fun recoverableCompletedTradeRecoveryThrough(
        requiredStart: Instant,
        queryNow: Instant,
        verifiedAccountScopeDigest: String?,
    ): Instant? {
        if (verifiedAccountScopeDigest.isNullOrBlank()) return null

        val storedScopeDigest = getTradeMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST)?.trim()
        val storedBindingVersion = getTradeMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION)?.trim()
        if (storedScopeDigest != verifiedAccountScopeDigest ||
            storedBindingVersion != AccountHistoryScopeGuard.CURRENT_BINDING_VERSION
        ) {
            return null
        }

        if (getTradeMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION) !=
            InceptionRecoveryService.CURRENT_RECOVERY_VERSION ||
            getTradeMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS) !=
            InceptionRecoveryStatus.COMPLETE ||
            getLedgerMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS) !=
            InceptionRecoveryStatus.COMPLETE ||
            getTradeMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET) != "completed" ||
            getLedgerMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET) != "completed"
        ) {
            return null
        }

        val recoveryHorizon = getTradeMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_HORIZON_EPOCH_SEC)
            ?.toLongOrNull()
            ?.let(Instant::ofEpochSecond)
            ?: return null
        if (recoveryHorizon.isBefore(requiredStart) || recoveryHorizon.isAfter(queryNow)) return null

        val tradeTotal = getTradeMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_TOTAL)
            ?.toIntOrNull()
            ?.takeIf { it >= 0 }
            ?: return null
        val tradeOldest = getTradeMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OLDEST_EPOCH_MS)
            ?.toLongOrNull()
            ?.let(Instant::ofEpochMilli)
        val tradeRangeEvidence = tradeTotal == 0 || tradeOldest?.let { !it.isAfter(requiredStart) } == true
        if (!tradeRangeEvidence) return null

        val ledgerTotal = getLedgerMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL)
            ?.toIntOrNull()
            ?.takeIf { it >= 0 }
            ?: return null
        val ledgerOldest = getLedgerMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OLDEST_EPOCH_MS)
            ?.toLongOrNull()
            ?.let(Instant::ofEpochMilli)
        val ledgerRangeEvidence = ledgerTotal == 0 || ledgerOldest?.let { !it.isAfter(requiredStart) } == true
        if (!ledgerRangeEvidence) return null

        return recoveryHorizon
    }

    private suspend fun getTradeMetadata(key: String): String? = repository.getSyncMetadata(key)

    private suspend fun getLedgerMetadata(key: String): String? =
        ledgerRepository?.getSyncMetadata(key) ?: repository.getSyncMetadata(key)

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
            var priorTotal = repository
                .getSyncMetadata(SyncMetadataKeys.SYNC_TOTAL)
                ?.toIntOrNull()
                ?.coerceAtLeast(0)
                ?: 0

            while (true) {
                log.info("Fetching trade history batch with offset={}", offset)
                val apiTrades = krakenService.getRecoveryTradeHistoryUntil(
                    startSec = startSec,
                    offset = offset,
                    endSec = endSec,
                )
                val totalCount = krakenService.getLastTradeHistoryTotalCount().coerceAtLeast(0)
                val hasAuthoritativeTotal = krakenService.hasLastTradeHistoryTotalCount()
                if (!krakenService.hasLastTradeHistoryPageShape()) {
                    throw IllegalStateException("Kraken returned a malformed trade page envelope")
                }
                val rawPageSize = krakenService.getLastTradeHistoryRawPageSize().coerceAtLeast(apiTrades.size)
                val expectedPageSize = (totalCount - offset)
                    .takeIf { it > 0 }
                    ?.coerceAtMost(KrakenApiConstants.TRADE_HISTORY_PAGE_SIZE)
                val pageMatchesReportedTotal = when {
                    !hasAuthoritativeTotal -> true

                    totalCount == 0 -> apiTrades.isEmpty() && rawPageSize == 0

                    else ->
                        expectedPageSize != null &&
                            rawPageSize == expectedPageSize &&
                            apiTrades.size <= expectedPageSize
                }
                if (hasAuthoritativeTotal && !pageMatchesReportedTotal) {
                    throw IllegalStateException(
                        "Kraken trade page occupancy disagreed with count " +
                            "(offset=$offset, count=$totalCount, rawPageSize=$rawPageSize)",
                    )
                }
                if (!isSeeded && !hasAuthoritativeTotal && rawPageSize == 0 && apiTrades.isEmpty()) {
                    throw IllegalStateException(
                        "Cannot finalize an unseeded trade sync from an unknown empty page " +
                            "(offset=$offset)",
                    )
                }

                val paginationShifted = hasAuthoritativeTotal && (
                    (priorTotal > 0 && totalCount != priorTotal) ||
                        (priorTotal == 0 && offset > 0)
                    )
                priorTotal = if (hasAuthoritativeTotal) totalCount else priorTotal

                if (!isSeeded) {
                    repository.setSyncMetadata(SyncMetadataKeys.SYNC_OFFSET, offset.toString())
                    repository.setSyncMetadata(
                        SyncMetadataKeys.SYNC_TOTAL,
                        if (hasAuthoritativeTotal) totalCount.toString() else (offset + apiTrades.size).toString(),
                    )
                }

                if (apiTrades.isNotEmpty()) emit(apiTrades)

                val nextOffset = if (paginationShifted && offset > 0) {
                    0
                } else {
                    offset + KrakenApiConstants.TRADE_HISTORY_PAGE_SIZE
                }
                val hasMorePages = !paginationShifted && if (hasAuthoritativeTotal) {
                    nextOffset < totalCount
                } else {
                    rawPageSize >= KrakenApiConstants.TRADE_HISTORY_PAGE_SIZE
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
