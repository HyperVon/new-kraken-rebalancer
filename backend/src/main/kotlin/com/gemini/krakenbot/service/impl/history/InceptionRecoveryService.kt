package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.domain.PortfolioCalculations
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.FlowCategory
import com.gemini.krakenbot.model.FundingProvenanceResolver
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.LedgerFlowClassifier
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeOwnership
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.OrderIntentRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.InceptionRecoveryStatus
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.getRecoveryTradeHistoryUntil
import com.gemini.krakenbot.service.withExecutionSession
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.util.TradeDeduplicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Recovers account history needed to prove a strategy inception independently of the ordinary
 * retained-history sync. Kraken's private history is strategy-neutral, so this service never
 * treats an exchange fill, a multi-symbol burst, or an old snapshot as bot ownership by itself.
 *
 * Each invocation is deliberately bounded. The durable offsets are advanced only after the page
 * has been imported, and the next invocation overlaps one page so an interrupted or shifted
 * newest-first response is harmless when identities are re-deduplicated.
 */
class InceptionRecoveryService(
    private val repository: TradeRepository,
    private val ledgerRepository: LedgerRepository,
    private val krakenService: KrakenService,
    private val configService: ConfigService,
    private val tradeHistorySyncService: TradeHistorySyncService,
    private val orderIntentRepository: OrderIntentRepository? = null,
    private val fundingProvenanceResolver: FundingProvenanceResolver = FundingProvenanceResolver.NONE,
    private val accountHistoryScopeGuard: AccountHistoryScopeGuard = AccountHistoryScopeGuard(
        krakenService = krakenService,
        tradeRepository = repository,
        ledgerRepository = ledgerRepository,
        configService = configService,
    ),
    private val nowProvider: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(InceptionRecoveryService::class.java)
    private val recoveryMutex = Mutex()

    /** Reads durable progress without waiting for an in-flight bounded network run. */
    suspend fun getStatus(): InceptionRecoveryStatus = readStatus()

    /**
     * Clears automatic evidence whenever the explicit inception setting changes. This is a
     * metadata-only operation and is also used by the discovery path before it reads a cache.
     * Returns true if evidence was cleared or updated due to configuration change; false otherwise.
     */
    suspend fun prepareForCurrentConfiguration(inceptionDate: String?): Boolean =
        prepareForCurrentConfigurationResult(inceptionDate).configurationChanged

    suspend fun prepareForCurrentConfigurationResult(inceptionDate: String?): InceptionPreparationResult {
        // A History request must not wait behind the network-bound recovery run, and
        // must not start network-bound continuity proof itself: the trust verdict
        // here is a local fingerprint-vs-binding read. Background validation owns
        // the proof and the binding write; the next recovery invocation performs
        // the same check under the mutex before it fetches another page, and config
        // publication is staged while an execution session is active.
        if (!recoveryMutex.tryLock()) return InceptionPreparationResult.busy()
        return try {
            val config = configService.getConfig()
            if (config.settings.simulation) {
                return InceptionPreparationResult.blocked(AccountScopeValidationStatus.SIMULATION)
            }
            val scopeResult = accountHistoryScopeGuard.readLocalTrustState()
            if (!scopeResult.isValid) {
                return InceptionPreparationResult.blocked(scopeResult.status)
            }
            InceptionPreparationResult.valid(
                changed = prepareForCurrentConfigurationLocked(
                    config = config,
                    inceptionDate = inceptionDate?.trim().orEmpty(),
                    accountScope = scopeResult.currentScopeDigest.orEmpty(),
                ),
            )
        } finally {
            recoveryMutex.unlock()
        }
    }

    /** Runs at most [MAX_PAGES_PER_RUN] private-history pages and returns durable state. */
    suspend fun recoverOneBoundedRun(): InceptionRecoveryStatus = recoveryMutex.withLock {
        val preflightConfig = configService.getConfig()

        // The scope gate runs before the manual-override short-circuit: a configured
        // date is authoritative for *when* inception was, but it must not bless
        // history the active credentials cannot be shown to own.
        val scopeResult = accountHistoryScopeGuard.validateAccountScope()
        when (scopeResult.status) {
            AccountScopeValidationStatus.SIMULATION -> {
                setOverallStatus(InceptionRecoveryStatus.UNAVAILABLE, "simulation backend")
                return@withLock readStatus()
            }

            AccountScopeValidationStatus.SCOPE_UNAVAILABLE -> {
                setOverallStatus(InceptionRecoveryStatus.UNAVAILABLE, scopeResult.reason ?: "account scope unavailable")
                return@withLock readStatus()
            }

            AccountScopeValidationStatus.SCOPE_MISMATCH -> {
                setOverallStatus(
                    InceptionRecoveryStatus.UNAVAILABLE,
                    scopeResult.reason ?: "account scope changed; use correct DB or perform reset",
                )
                return@withLock readStatus()
            }

            AccountScopeValidationStatus.UNBOUND_EXISTING_HISTORY -> {
                setOverallStatus(
                    InceptionRecoveryStatus.UNAVAILABLE,
                    scopeResult.reason ?: "existing history cannot be verified for active credentials",
                )
                return@withLock readStatus()
            }

            AccountScopeValidationStatus.VALIDATION_PENDING -> {
                setOverallStatus(
                    InceptionRecoveryStatus.UNAVAILABLE,
                    scopeResult.reason ?: "account validation pending",
                )
                return@withLock readStatus()
            }

            AccountScopeValidationStatus.VALID -> {
                // Verified valid scope
            }
        }

        if (!preflightConfig.settings.inceptionDate.isNullOrBlank()) {
            setOverallStatus(InceptionRecoveryStatus.MANUAL_OVERRIDE, "explicit inception date")
            return@withLock readStatus()
        }

        prepareForCurrentConfigurationLocked(
            config = preflightConfig,
            inceptionDate = preflightConfig.settings.inceptionDate?.trim().orEmpty(),
            accountScope = scopeResult.currentScopeDigest.orEmpty(),
        )

        val currentStatus = readStatus()
        if (currentStatus.status == InceptionRecoveryStatus.CONFIRMED) return@withLock currentStatus

        val now = nowProvider()
        val lastAttempt = repository
            .getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LAST_ATTEMPT_EPOCH_SEC)
            ?.toLongOrNull()
        if (lastAttempt != null && now.epochSecond - lastAttempt in 0 until RETRY_INTERVAL_SECONDS) {
            return@withLock currentStatus
        }

        val horizon = readHorizon() ?: now.also {
            repository.setSyncMetadata(
                SyncMetadataKeys.INCEPTION_RECOVERY_HORIZON_EPOCH_SEC,
                it.epochSecond.toString(),
            )
        }
        repository.setSyncMetadata(
            SyncMetadataKeys.INCEPTION_RECOVERY_LAST_ATTEMPT_EPOCH_SEC,
            now.epochSecond.toString(),
        )
        setOverallStatus(InceptionRecoveryStatus.IN_PROGRESS, "")

        try {
            configService.withExecutionSession {
                val pinnedConfig = configService.getConfig()
                if (!pinnedConfig.settings.inceptionDate.isNullOrBlank()) {
                    setOverallStatus(InceptionRecoveryStatus.MANUAL_OVERRIDE, "explicit inception date")
                    return@withExecutionSession
                }
                if (pinnedConfig.settings.simulation) {
                    setOverallStatus(InceptionRecoveryStatus.UNAVAILABLE, "simulation backend")
                    return@withExecutionSession
                }
                if (!pinnedConfig.kraken.hasValidCredentials()) {
                    setOverallStatus(InceptionRecoveryStatus.UNAVAILABLE, "credentials unavailable")
                    return@withExecutionSession
                }
                krakenService.withStableBackend { backend ->
                    val pinnedScope = accountHistoryScopeGuard.validateAccountScope()
                    if (!pinnedScope.isValid) {
                        setOverallStatus(
                            InceptionRecoveryStatus.UNAVAILABLE,
                            pinnedScope.reason ?: "account scope unavailable",
                        )
                        return@withStableBackend
                    }
                    prepareForCurrentConfigurationLocked(
                        config = pinnedConfig,
                        inceptionDate = pinnedConfig.settings.inceptionDate?.trim().orEmpty(),
                        accountScope = pinnedScope.currentScopeDigest.orEmpty(),
                    )
                    recoverPagesAndEvaluate(pinnedConfig, backend, horizon)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Strategy inception recovery failed; retaining resumable progress", e)
            setOverallStatus(InceptionRecoveryStatus.FAILED, "history request failed")
        }
        readStatus()
    }

    private suspend fun recoverPagesAndEvaluate(config: AppConfig, backend: KrakenService, horizon: Instant) {
        var pagesUsed = 0
        var tradeOffset = initialOffset(
            SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS,
            SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET,
            KrakenApiConstants.TRADE_HISTORY_PAGE_SIZE,
            repository,
        )
        var ledgerOffset = initialOffset(
            SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS,
            SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET,
            KrakenApiConstants.LEDGER_PAGE_SIZE,
            ledgerRepository,
        )

        var failed = false
        while (pagesUsed < MAX_PAGES_PER_RUN) {
            val tradeStatus = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS)
            if (tradeStatus != STREAM_COMPLETE) {
                try {
                    repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, STREAM_IN_PROGRESS)
                    val result = recoverTradePage(backend, horizon, tradeOffset)
                    tradeOffset = result.nextOffset
                    pagesUsed++
                    if (result.complete) {
                        repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, STREAM_COMPLETE)
                    }
                    continue
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Strategy inception trade-history page failed", e)
                    repository.setSyncMetadata(
                        SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS,
                        STREAM_FAILED,
                    )
                    failed = true
                    break
                }
            }

            val ledgerStatus = ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS)
            if (ledgerStatus != STREAM_COMPLETE) {
                try {
                    ledgerRepository.setSyncMetadata(
                        SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS,
                        STREAM_IN_PROGRESS,
                    )
                    val result = recoverLedgerPage(backend, horizon, ledgerOffset)
                    ledgerOffset = result.nextOffset
                    pagesUsed++
                    if (result.complete) {
                        ledgerRepository.setSyncMetadata(
                            SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS,
                            STREAM_COMPLETE,
                        )
                    }
                    continue
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Strategy inception ledger-history page failed", e)
                    ledgerRepository.setSyncMetadata(
                        SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS,
                        STREAM_FAILED,
                    )
                    failed = true
                    break
                }
            }
            break
        }

        if (failed) {
            setOverallStatus(InceptionRecoveryStatus.FAILED, "history request failed")
            return
        }

        val tradesComplete =
            repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS) == STREAM_COMPLETE
        val ledgersComplete =
            ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS) == STREAM_COMPLETE
        if (!tradesComplete || !ledgersComplete) {
            setOverallStatus(InceptionRecoveryStatus.IN_PROGRESS, "bounded recovery continues")
            return
        }

        evaluateRecoveredEvidence(config, backend, horizon)
    }

    private suspend fun recoverTradePage(backend: KrakenService, horizon: Instant, offset: Int): PageResult {
        log.info("Fetching strategy inception trade-history page with offset={}", offset)
        val page = backend.getRecoveryTradeHistoryUntil(
            startSec = null,
            offset = offset,
            endSec = horizon.epochSecond,
        )
        val reportedTotal = backend.getLastTradeHistoryTotalCount().coerceAtLeast(0)
        val priorTotal = repository
            .getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_TOTAL)
            .orEmpty()
            .toIntOrNull()
            ?.coerceAtLeast(0)
            ?: 0
        val total = if (reportedTotal > 0) {
            maxOf(priorTotal, reportedTotal)
        } else {
            priorTotal
        }
        if (total > 0) {
            repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_TOTAL, total.toString())
        }
        val paginationShifted = priorTotal > 0 && reportedTotal > 0 && reportedTotal != priorTotal

        // The reconciler writes only API_FILL economics and never changes the ordinary cursor.
        tradeHistorySyncService.importRecoveredApiTrades(page)

        // A count change means newest-first offsets may have shifted while the bounded run was
        // paused. Rewind after importing the current overlap page so the next bounded slice
        // re-establishes coverage from the stable page-zero boundary.
        val nextOffset = if (paginationShifted && offset > 0) {
            0
        } else {
            offset + KrakenApiConstants.TRADE_HISTORY_PAGE_SIZE
        }
        val complete = !paginationShifted && if (reportedTotal > 0) {
            nextOffset >= reportedTotal
        } else {
            page.size < KrakenApiConstants.TRADE_HISTORY_PAGE_SIZE
        }
        if (complete) {
            repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, COMPLETED)
            repository.setSyncMetadata(
                SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OLDEST_EPOCH_MS,
                repository
                    .getTradesInRange(Instant.EPOCH, horizon.plusSeconds(1))
                    .minOfOrNull { it.timestamp }
                    ?.toEpochMilli()
                    ?.toString()
                    .orEmpty(),
            )
        } else {
            repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, nextOffset.toString())
        }
        return PageResult(nextOffset, complete)
    }

    private suspend fun recoverLedgerPage(backend: KrakenService, horizon: Instant, offset: Int): PageResult {
        log.info("Fetching strategy inception ledger-history page with offset={}", offset)
        // No type filter is intentional: recovery must see every account ledger family, including
        // newly introduced or currently unclassified types, so unsupported balance changes do not
        // disappear behind the ordinary sync's allow-list.
        val page = backend.getLedgers(
            startSec = null,
            offset = offset,
            endSec = horizon.epochSecond,
            types = null,
        )
        val rawPageSize = backend.getLastLedgerRawPageSize().coerceAtLeast(page.size)
        val authoritativeTotal = backend.getLastLedgerTotalCount().coerceAtLeast(0)
        val priorTotal = ledgerRepository
            .getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL)
            .orEmpty()
            .toIntOrNull()
            ?.coerceAtLeast(0)
            ?: 0
        val total = if (authoritativeTotal > 0) {
            maxOf(priorTotal, authoritativeTotal)
        } else {
            priorTotal
        }
        if (total > 0) {
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL, total.toString())
        }
        val paginationShifted = priorTotal > 0 && authoritativeTotal > 0 && authoritativeTotal != priorTotal
        ledgerRepository.saveLedgers(page)

        val nextOffset = if (paginationShifted && offset > 0) {
            0
        } else {
            offset + KrakenApiConstants.LEDGER_PAGE_SIZE
        }
        val complete = !paginationShifted && (
            rawPageSize < KrakenApiConstants.LEDGER_PAGE_SIZE ||
                (authoritativeTotal > 0 && nextOffset >= authoritativeTotal)
            )
        if (complete) {
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, COMPLETED)
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OLDEST_EPOCH_MS,
                ledgerRepository
                    .getLedgersInRange(Instant.EPOCH, horizon.plusSeconds(1))
                    .minOfOrNull { it.time }
                    ?.toEpochMilli()
                    ?.toString()
                    .orEmpty(),
            )
        } else {
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, nextOffset.toString())
        }
        return PageResult(nextOffset, complete)
    }

    private suspend fun evaluateRecoveredEvidence(config: AppConfig, backend: KrakenService, horizon: Instant) {
        val upperBound = horizon.plusSeconds(1)
        val allTrades = repository.getTradesInRange(Instant.EPOCH, upperBound)
            .filter { it.success && !it.dryRun }
            .sortedBy(TradeRecord::timestamp)

        val expectedUniverse = config.allocations.map {
            Asset.normalizeLedgerAsset(it.symbol.value).uppercase()
        }.toSet()
        val outsideUniverseTrade = allTrades.firstOrNull {
            it.symbol.isNotBlank() &&
                it.volume.signum() != 0 &&
                Asset.normalizeLedgerAsset(it.symbol).uppercase() !in expectedUniverse
        }
        if (outsideUniverseTrade != null) {
            clearCandidateEvidence()
            setOverallStatus(
                InceptionRecoveryStatus.AMBIGUOUS,
                "trade outside configured universe",
            )
            return
        }

        val candidateTrades = allTrades.filter { it.symbol.isNotBlank() && !Asset(it.symbol).isUsd }
        val orderTxids = candidateTrades.mapNotNull { it.orderTxid?.trim()?.takeIf(String::isNotBlank) }.toSet()
        val clientOrderIds = candidateTrades.mapNotNull { it.clientOrderId?.trim()?.takeIf(String::isNotBlank) }.toSet()
        val knownOrderTxids = orderIntentRepository
            ?.getKnownRebalancerOrderIdentities(orderTxids, clientOrderIds)
            ?.orderTxids
            .orEmpty()
        val ownedTrades = candidateTrades.filter {
            classifyTradeForRecovery(it, knownOrderTxids) == TradeOwnership.REBALANCER
        }
        val candidate = ownedTrades.minWithOrNull(
            compareBy<TradeRecord> { it.timestamp }.thenBy {
                it.id
                    ?: Int.MAX_VALUE
            },
        )

        if (candidate == null) {
            clearCandidateEvidence()
            val unknown = candidateTrades.firstOrNull {
                it.source == TradeSource.LEGACY_UNKNOWN ||
                    classifyTradeForRecovery(it, knownOrderTxids) == TradeOwnership.UNKNOWN
            }
            val status = if (unknown == null) {
                InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE
            } else {
                InceptionRecoveryStatus.AMBIGUOUS
            }
            setOverallStatus(
                status,
                if (unknown == null) "no positively owned bot fill" else "trade ownership is ambiguous",
            )
            return
        }

        // This list came from the persistence repository, so every candidate has a durable row
        // identity. Losing that identity would make the evidence non-replayable; fail closed if a
        // non-persisted record ever crosses this repository boundary.
        val candidateDbId = candidate.id!!.toString()

        repository.setSyncMetadata(
            SyncMetadataKeys.INCEPTION_RECOVERY_CANDIDATE_EPOCH_MS,
            candidate.timestamp.toEpochMilli().toString(),
        )
        repository.setSyncMetadata(
            SyncMetadataKeys.INCEPTION_RECOVERY_CANDIDATE_TRADE_ID,
            candidate.tradeId.orEmpty(),
        )
        repository.setSyncMetadata(
            SyncMetadataKeys.INCEPTION_RECOVERY_CANDIDATE_ORDER_TXID,
            candidate.orderTxid.orEmpty(),
        )
        repository.setSyncMetadata(
            SyncMetadataKeys.INCEPTION_RECOVERY_CANDIDATE_DB_ID,
            candidateDbId,
        )
        repository.setSyncMetadata(
            SyncMetadataKeys.INCEPTION_RECOVERY_OWNERSHIP_EVIDENCE,
            ownershipEvidence(candidate),
        )
        val unknownBeforeCandidate = candidateTrades.firstOrNull {
            it.timestamp <= candidate.timestamp &&
                classifyTradeForRecovery(it, knownOrderTxids) == TradeOwnership.UNKNOWN
        }
        if (unknownBeforeCandidate != null) {
            setOverallStatus(InceptionRecoveryStatus.AMBIGUOUS, "ownership before candidate is unresolved")
            return
        }

        val baselineId = repository
            .getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_BASELINE_SNAPSHOT_ID)
            ?.toIntOrNull()
        if (baselineId != null) {
            val existing = repository.getSnapshotById(baselineId)
            if (existing != null && existing.timestamp == candidate.timestamp.minusMillis(1)) {
                confirmRecoveredInception(candidate, baselineId)
                return
            }
        }

        when (val baseline = reconstructBaseline(config, backend, candidate, horizon, allTrades)) {
            is BaselineResult.Failure -> {
                setOverallStatus(baseline.status, baseline.reason)
            }

            is BaselineResult.Success -> {
                val metadata = mapOf(
                    SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS to candidate.timestamp.toEpochMilli().toString(),
                    SyncMetadataKeys.DETECTED_INCEPTION_SOURCE to INCEPTION_SOURCE_AUTO_RECOVERED,
                    SyncMetadataKeys.INCEPTION_RECOVERY_CANDIDATE_EPOCH_MS to
                        candidate.timestamp.toEpochMilli().toString(),
                    SyncMetadataKeys.INCEPTION_RECOVERY_CANDIDATE_TRADE_ID to candidate.tradeId.orEmpty(),
                    SyncMetadataKeys.INCEPTION_RECOVERY_CANDIDATE_ORDER_TXID to candidate.orderTxid.orEmpty(),
                    SyncMetadataKeys.INCEPTION_RECOVERY_CANDIDATE_DB_ID to candidateDbId,
                    SyncMetadataKeys.INCEPTION_RECOVERY_OWNERSHIP_EVIDENCE to ownershipEvidence(
                        candidate,
                    ),
                    SyncMetadataKeys.INCEPTION_RECOVERY_REASON to "coverage and ownership confirmed",
                    SyncMetadataKeys.INCEPTION_RECOVERY_STATUS to InceptionRecoveryStatus.CONFIRMED,
                )
                repository.saveSnapshotWithMetadata(
                    snapshot = baseline.snapshot,
                    metadata = metadata,
                    snapshotIdMetadataKeys = setOf(
                        SyncMetadataKeys.INCEPTION_SNAPSHOT_ID,
                        SyncMetadataKeys.INCEPTION_RECOVERY_BASELINE_SNAPSHOT_ID,
                    ),
                )
            }
        }
    }

    private suspend fun confirmRecoveredInception(candidate: TradeRecord, baselineId: Int) {
        repository.setSyncMetadata(
            SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS,
            candidate.timestamp.toEpochMilli().toString(),
        )
        repository.setSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE, INCEPTION_SOURCE_AUTO_RECOVERED)
        repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_SNAPSHOT_ID, baselineId.toString())
        repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_REASON, "coverage and ownership confirmed")
        repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS, InceptionRecoveryStatus.CONFIRMED)
    }

    private suspend fun reconstructBaseline(
        config: AppConfig,
        backend: KrakenService,
        candidate: TradeRecord,
        horizon: Instant,
        allTrades: List<TradeRecord>,
    ): BaselineResult {
        var anchor = repository.getSnapshotBefore(horizon.plusMillis(1))
        while (anchor != null && (anchor.balancesObservedAt ?: anchor.timestamp).isAfter(horizon)) {
            anchor = repository.getSnapshotBefore(anchor.timestamp)
        }
        if (anchor == null) {
            return BaselineResult.Failure(
                InceptionRecoveryStatus.BASELINE_UNAVAILABLE,
                "no retained balance anchor",
            )
        }

        val baselineTime = candidate.timestamp.minusMillis(1)
        val anchorObservation = anchor.balancesObservedAt ?: anchor.timestamp
        if (candidate.timestamp.isAfter(anchorObservation)) {
            return BaselineResult.Failure(
                InceptionRecoveryStatus.BASELINE_UNAVAILABLE,
                "candidate is newer than retained anchor",
            )
        }

        val allocations = config.allocations
        val expectedUniverse = allocations.map { Asset.normalizeLedgerAsset(it.symbol.value).uppercase() }.toSet()
        val anchorUniverse = anchor.assets.keys.map { Asset.normalizeLedgerAsset(it).uppercase() }.toSet()
        if (anchorUniverse != expectedUniverse) {
            return BaselineResult.Failure(
                InceptionRecoveryStatus.AMBIGUOUS,
                "configured asset universe changed",
            )
        }

        val historicalTrades = repository
            .getTradesInRange(baselineTime.plusMillis(1), anchorObservation)
            .filter { it.success && !it.dryRun }
        val historicalLedgers = ledgerRepository
            .getLedgersInRange(baselineTime.plusMillis(1), anchorObservation)
        val ledgerContext = ledgerRepository.getLedgersInRange(
            baselineTime.minusSeconds(CardFundingNormalizer.MAX_CARD_TRANSACTION_SPAN_SECONDS),
            anchorObservation.plusSeconds(CardFundingNormalizer.MAX_CARD_TRANSACTION_SPAN_SECONDS),
        )

        val preparedProvenance = try {
            fundingProvenanceResolver.prepare(ledgerContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Unable to prepare funding provenance for inception baseline", e)
            FundingProvenanceResolver.NONE
        }

        val unsupportedLedger = historicalLedgers.firstOrNull { event ->
            val type = event.type.trim().lowercase()
            type !in SUPPORTED_LEDGER_TYPES
        }
        if (unsupportedLedger != null) {
            return BaselineResult.Failure(
                InceptionRecoveryStatus.AMBIGUOUS,
                "unsupported ledger type ${unsupportedLedger.type}".take(MAX_REASON_LENGTH),
            )
        }
        if (historicalLedgers.any { !it.hasValidFee }) {
            return BaselineResult.Failure(InceptionRecoveryStatus.AMBIGUOUS, "invalid ledger fee")
        }
        if (!hasConsistentAuthoritativeLedgerBalances(historicalLedgers)) {
            return BaselineResult.Failure(InceptionRecoveryStatus.AMBIGUOUS, "inconsistent ledger balances")
        }

        val cardGroups = CardFundingNormalizer.identifyCandidateGroups(ledgerContext)
        for ((refid, group) in cardGroups) {
            if (group.none { it.time > baselineTime && !it.time.isAfter(anchorObservation) }) continue
            when (val parsed = CardFundingNormalizer.parseCardFundingGroup(refid, group, preparedProvenance)) {
                is CardFundingNormalizer.ParsedGroup.Ambiguous -> {
                    return BaselineResult.Failure(
                        InceptionRecoveryStatus.AMBIGUOUS,
                        parsed.reason.take(MAX_REASON_LENGTH),
                    )
                }

                else -> Unit
            }
        }

        val flowCategories = LedgerFlowClassifier.classifyAll(historicalLedgers, preparedProvenance)
        val ambiguousLedger = historicalLedgers.firstOrNull { event ->
            flowCategories[event.ledgerId] == FlowCategory.AMBIGUOUS
        }
        if (ambiguousLedger != null) {
            return BaselineResult.Failure(
                InceptionRecoveryStatus.AMBIGUOUS,
                "ledger provenance unresolved: ${ambiguousLedger.type}".take(MAX_REASON_LENGTH),
            )
        }

        val runningBalances = anchor.assets.mapKeys { (symbol, _) ->
            Asset.normalizeLedgerAsset(symbol).uppercase()
        }.mapValuesTo(mutableMapOf()) { (_, row) -> row.balance }

        val duplicateTradeIds = TradeDeduplicator.findDuplicateTradeIds(historicalTrades)
        // getTradesInRange returns persisted rows, whose database identity is required for
        // duplicate selection and durable recovery evidence.
        val accountingTrades = historicalTrades.filterNot { it.id!! in duplicateTradeIds }
        val unknownTrade = accountingTrades.firstOrNull {
            Asset.normalizeLedgerAsset(it.symbol).uppercase() !in expectedUniverse &&
                it.volume.signum() != 0
        }
        if (unknownTrade != null) {
            return BaselineResult.Failure(InceptionRecoveryStatus.AMBIGUOUS, "trade outside configured universe")
        }
        for (trade in accountingTrades.sortedWith(
            compareByDescending<TradeRecord> { it.timestamp }.thenByDescending {
                it.id
                    ?: 0
            },
        )) {
            if (!reverseApplyTrade(trade, runningBalances, expectedUniverse)) {
                return BaselineResult.Failure(InceptionRecoveryStatus.AMBIGUOUS, "unsupported trade economics")
            }
        }
        for (event in historicalLedgers.sortedByDescending { it.time }) {
            if (!reverseApplyLedger(event, runningBalances, expectedUniverse)) {
                return BaselineResult.Failure(InceptionRecoveryStatus.AMBIGUOUS, "ledger changed tracked universe")
            }
        }

        if (runningBalances.values.any { it < NEGATIVE_BALANCE_TOLERANCE.negate() }) {
            return BaselineResult.Failure(
                InceptionRecoveryStatus.BASELINE_UNAVAILABLE,
                "negative reconstructed balance",
            )
        }
        runningBalances.replaceAll { _, balance -> balance.max(BigDecimal.ZERO) }

        val prices = resolveHistoricalPrices(
            allocations = allocations,
            baselineTime = baselineTime,
            candidate = candidate,
            runningBalances = runningBalances,
            backend = backend,
        ) ?: return BaselineResult.Failure(
            InceptionRecoveryStatus.BASELINE_UNAVAILABLE,
            "historical price unavailable",
        )
        val total = allocations.sumOf { allocation ->
            val symbol = Asset.normalizeLedgerAsset(allocation.symbol.value).uppercase()
            runningBalances.getValue(symbol).multiply(prices.getValue(symbol))
        }
        if (total <= BigDecimal.ZERO) {
            return BaselineResult.Failure(
                InceptionRecoveryStatus.BASELINE_UNAVAILABLE,
                "non-positive reconstructed baseline",
            )
        }

        val assetSnapshots = allocations.associate { allocation ->
            val symbol = Asset.normalizeLedgerAsset(allocation.symbol.value).uppercase()
            val balance = runningBalances.getValue(symbol).max(BigDecimal.ZERO)
            val price = prices.getValue(symbol)
            val value = balance.multiply(price)
            symbol to PortfolioCalculations.createAssetSnapshot(
                symbol = symbol,
                balance = balance,
                price = price,
                valueUSD = value,
                targetPercent = BigDecimal.valueOf(allocation.targetPercent),
                totalPortfolioValueUSD = total,
            )
        }
        return BaselineResult.Success(
            PortfolioSnapshot(
                timestamp = baselineTime,
                totalValueUSD = total.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP),
                assets = assetSnapshots,
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = allocations
                    .first { it.symbol.isUsd }
                    .targetPercent
                    .let(BigDecimal::valueOf),
                // This is a reconstructed pre-fill state, not a claim that Kraken returned a
                // balance observation at this exact millisecond.
                balancesObservedAt = null,
            ),
        )
    }

    private fun reverseApplyTrade(
        trade: TradeRecord,
        balances: MutableMap<String, BigDecimal>,
        expectedUniverse: Set<String>,
    ): Boolean {
        val symbol = Asset.normalizeLedgerAsset(trade.symbol).uppercase()
        if (symbol !in expectedUniverse || (!OrderSide.isBuy(trade.side) && !OrderSide.isSell(trade.side))) return false
        val usd = Asset.USD
        val assetBalance = balances.getValue(symbol)
        val usdBalance = balances.getValue(usd)
        if (trade.volume.signum() < 0 || trade.usdAmount.signum() < 0 || trade.fee.signum() < 0) return false
        if (OrderSide.isBuy(trade.side)) {
            balances[symbol] = assetBalance.subtract(trade.volume)
            balances[usd] = usdBalance.add(trade.usdAmount).add(trade.fee)
        } else {
            balances[symbol] = assetBalance.add(trade.volume)
            balances[usd] = usdBalance.subtract(trade.usdAmount).add(trade.fee)
        }
        return true
    }

    private fun reverseApplyLedger(
        event: LedgerEvent,
        balances: MutableMap<String, BigDecimal>,
        expectedUniverse: Set<String>,
    ): Boolean {
        if (event.type.equals(TRADE_LEDGER_TYPE, ignoreCase = true)) return true
        val symbol = Asset.normalizeLedgerAsset(event.asset).uppercase()
        val delta = event.netBalanceDelta()
        if (symbol !in expectedUniverse) return delta.signum() == 0
        val balance = balances.getValue(symbol)
        balances[symbol] = balance.subtract(delta)
        return true
    }

    private fun hasConsistentAuthoritativeLedgerBalances(events: List<LedgerEvent>): Boolean {
        val previousByAsset = mutableMapOf<String, LedgerEvent>()
        for (event in events.filterNot { it.type.equals(TRADE_LEDGER_TYPE, ignoreCase = true) }
            .sortedWith(compareBy<LedgerEvent> { it.time }.thenBy { it.ledgerId })) {
            if (!event.hasAuthoritativeBalance) continue
            val asset = Asset.normalizeLedgerAsset(event.asset).uppercase()
            val previous = previousByAsset[asset]
            if (previous != null) {
                val expected = previous.balance.add(event.netBalanceDelta())
                if (expected.subtract(event.balance).abs() > NEGATIVE_BALANCE_TOLERANCE) return false
            }
            previousByAsset[asset] = event
        }
        return true
    }

    private suspend fun resolveHistoricalPrices(
        allocations: List<Allocation>,
        baselineTime: Instant,
        candidate: TradeRecord,
        runningBalances: Map<String, BigDecimal>,
        backend: KrakenService,
    ): Map<String, BigDecimal>? {
        val candidateSymbol = Asset.normalizeLedgerAsset(candidate.symbol).uppercase()
        val prices = mutableMapOf<String, BigDecimal>()
        for (allocation in allocations) {
            val symbol = Asset.normalizeLedgerAsset(allocation.symbol.value).uppercase()
            if (symbol == Asset.USD) {
                prices[symbol] = BigDecimal.ONE
                continue
            }
            val balance = runningBalances[symbol] ?: BigDecimal.ZERO
            val candidateException = if (symbol == candidateSymbol && candidate.price.signum() > 0) {
                candidate.price
            } else {
                null
            }
            val resolvedPrice = HistoricalPriceResolver.resolveHistoricalPrice(
                asset = symbol,
                eventTime = baselineTime,
                tradesRepo = repository,
                krakenService = backend,
                candidatePriceException = candidateException,
            )
            if (resolvedPrice != null && resolvedPrice > BigDecimal.ZERO) {
                prices[symbol] = resolvedPrice
            } else if (balance <= BigDecimal.ZERO) {
                prices[symbol] = BigDecimal.ZERO
            } else {
                return null
            }
        }
        return prices
    }

    private fun classifyTradeForRecovery(trade: TradeRecord, knownRebalancerOrderTxids: Set<String>): TradeOwnership =
        when {
            !trade.cycleId.isNullOrBlank() || !trade.clientOrderId.isNullOrBlank() -> TradeOwnership.REBALANCER
            trade.source == TradeSource.LOCAL_ESTIMATE -> TradeOwnership.REBALANCER
            trade.orderTxid?.takeIf(String::isNotBlank) in knownRebalancerOrderTxids -> TradeOwnership.REBALANCER
            else -> TradeOwnership.UNKNOWN
        }

    private suspend fun prepareForCurrentConfigurationLocked(
        config: AppConfig,
        inceptionDate: String,
        accountScope: String,
    ): Boolean {
        val fingerprint = configurationFingerprint(config, inceptionDate, accountScope)
        val storedFingerprint = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
        val version = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION)
        if (version == CURRENT_RECOVERY_VERSION && storedFingerprint == fingerprint) return false

        listOf(
            SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS,
            SyncMetadataKeys.DETECTED_INCEPTION_SOURCE,
            SyncMetadataKeys.INCEPTION_SNAPSHOT_ID,
            SyncMetadataKeys.INCEPTION_RECOVERY_STATUS,
            SyncMetadataKeys.INCEPTION_RECOVERY_HORIZON_EPOCH_SEC,
            SyncMetadataKeys.INCEPTION_RECOVERY_LAST_ATTEMPT_EPOCH_SEC,
            SyncMetadataKeys.INCEPTION_RECOVERY_CANDIDATE_EPOCH_MS,
            SyncMetadataKeys.INCEPTION_RECOVERY_CANDIDATE_TRADE_ID,
            SyncMetadataKeys.INCEPTION_RECOVERY_CANDIDATE_ORDER_TXID,
            SyncMetadataKeys.INCEPTION_RECOVERY_CANDIDATE_DB_ID,
            SyncMetadataKeys.INCEPTION_RECOVERY_OWNERSHIP_EVIDENCE,
            SyncMetadataKeys.INCEPTION_RECOVERY_BASELINE_SNAPSHOT_ID,
            SyncMetadataKeys.INCEPTION_RECOVERY_REASON,
        ).forEach { repository.setSyncMetadata(it, "") }
        listOf(
            SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS,
            SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET,
            SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_TOTAL,
            SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OLDEST_EPOCH_MS,
        ).forEach { repository.setSyncMetadata(it, "") }
        listOf(
            SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS,
            SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET,
            SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL,
            SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OLDEST_EPOCH_MS,
        ).forEach { ledgerRepository.setSyncMetadata(it, "") }
        repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION, CURRENT_RECOVERY_VERSION)
        repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT, fingerprint)
        return true
    }

    /**
     * The recovery result depends on the inception override, tracked asset universe, and account
     * identity. Store only a digest so configuration details and credential material never enter
     * the metadata table or logs.
     */
    private fun configurationFingerprint(config: AppConfig, inceptionDate: String, accountScope: String): String {
        val allocationShape = config.allocations
            .map { allocation ->
                "${Asset.canonicalSymbol(allocation.symbol.value)}=${allocation.targetPercent}"
            }
            .sorted()
            .joinToString(",")
        val material = listOf(
            inceptionDate,
            config.settings.simulation.toString(),
            allocationShape,
            accountScope,
        ).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private suspend fun setOverallStatus(status: String, reason: String) {
        repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS, status)
        repository.setSyncMetadata(
            SyncMetadataKeys.INCEPTION_RECOVERY_REASON,
            reason.take(MAX_REASON_LENGTH),
        )
    }

    private suspend fun clearCandidateEvidence() {
        listOf(
            SyncMetadataKeys.INCEPTION_RECOVERY_CANDIDATE_EPOCH_MS,
            SyncMetadataKeys.INCEPTION_RECOVERY_CANDIDATE_TRADE_ID,
            SyncMetadataKeys.INCEPTION_RECOVERY_CANDIDATE_ORDER_TXID,
            SyncMetadataKeys.INCEPTION_RECOVERY_CANDIDATE_DB_ID,
            SyncMetadataKeys.INCEPTION_RECOVERY_OWNERSHIP_EVIDENCE,
        ).forEach { repository.setSyncMetadata(it, "") }
    }

    private fun ownershipEvidence(trade: TradeRecord): String = when {
        !trade.cycleId.isNullOrBlank() || !trade.clientOrderId.isNullOrBlank() -> "local cycle/client"
        trade.source == TradeSource.LOCAL_ESTIMATE -> "local estimate"
        else -> "order intent"
    }

    private suspend fun readStatus(): InceptionRecoveryStatus {
        val status = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS)
            ?.takeIf(String::isNotBlank)
            ?: InceptionRecoveryStatus.NOT_STARTED
        val horizon = readHorizon()?.toString()
        return InceptionRecoveryStatus(
            status = status,
            tradeOffset = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET).orEmpty(),
            tradeTotal = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_TOTAL).orEmpty(),
            ledgerOffset = ledgerRepository.getSyncMetadata(
                SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET,
            ).orEmpty(),
            ledgerTotal = ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL).orEmpty(),
            candidateTime = repository
                .getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_CANDIDATE_EPOCH_MS)
                ?.toLongOrNull()
                ?.let { Instant.ofEpochMilli(it).toString() },
            reason = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_REASON)
                ?.takeIf(String::isNotBlank),
            coverageHorizon = horizon,
        )
    }

    private suspend fun readHorizon(): Instant? = repository
        .getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_HORIZON_EPOCH_SEC)
        ?.toLongOrNull()
        ?.let(Instant::ofEpochSecond)

    private suspend fun initialOffset(
        statusKey: String,
        offsetKey: String,
        pageSize: Int,
        metadataRepository: TradeRepository,
    ): Int {
        val status = metadataRepository.getSyncMetadata(statusKey)
        val offset = metadataRepository.getSyncMetadata(offsetKey)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        return if (status == STREAM_COMPLETE) offset else (offset - pageSize).coerceAtLeast(0)
    }

    private suspend fun initialOffset(
        statusKey: String,
        offsetKey: String,
        pageSize: Int,
        metadataRepository: LedgerRepository,
    ): Int {
        val status = metadataRepository.getSyncMetadata(statusKey)
        val offset = metadataRepository.getSyncMetadata(offsetKey)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        return if (status == STREAM_COMPLETE) offset else (offset - pageSize).coerceAtLeast(0)
    }

    private data class PageResult(val nextOffset: Int, val complete: Boolean)

    class InceptionPreparationResult private constructor(
        val scopeStatus: AccountScopeValidationStatus?,
        val configurationChanged: Boolean,
        val canTrustRecoveredInception: Boolean,
        /**
         * True only when a current scope verdict was actually produced. `busy()`
         * carries no verdict (`scopeStatus == null`), so `scopeKnown` — not
         * nullness — separates "no problem" from "unknown because busy". Callers
         * must fail closed whenever this is false in production.
         */
        val scopeKnown: Boolean,
    ) {
        companion object {
            fun valid(changed: Boolean) = InceptionPreparationResult(
                scopeStatus = AccountScopeValidationStatus.VALID,
                configurationChanged = changed,
                canTrustRecoveredInception = true,
                scopeKnown = true,
            )

            fun blocked(status: AccountScopeValidationStatus?) = InceptionPreparationResult(
                scopeStatus = status,
                configurationChanged = false,
                canTrustRecoveredInception = false,
                scopeKnown = status != null,
            )

            fun busy() = blocked(null)
        }
    }

    private sealed interface BaselineResult {
        data class Success(val snapshot: PortfolioSnapshot) : BaselineResult

        data class Failure(val status: String, val reason: String) : BaselineResult
    }

    companion object {
        const val CURRENT_RECOVERY_VERSION = "1"
        const val MAX_PAGES_PER_RUN = 4
        const val RETRY_INTERVAL_SECONDS = 300L
        const val INCEPTION_SOURCE_AUTO_RECOVERED = "auto-recovered"

        private const val STREAM_COMPLETE = "COMPLETE"
        private const val STREAM_IN_PROGRESS = "IN_PROGRESS"
        private const val STREAM_FAILED = "FAILED"
        private const val COMPLETED = "completed"
        private const val TRADE_LEDGER_TYPE = "trade"
        private val SUPPORTED_LEDGER_TYPES =
            setOf(TRADE_LEDGER_TYPE) + LedgerEvent.EXTERNAL_BALANCE_TYPES.map(String::lowercase)
        private const val MAX_REASON_LENGTH = 60
        private val NEGATIVE_BALANCE_TOLERANCE = BigDecimal("0.00000001")
    }
}
