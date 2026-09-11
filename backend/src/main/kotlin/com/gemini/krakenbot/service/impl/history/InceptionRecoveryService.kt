package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.domain.PortfolioCalculations
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.FlowCategory
import com.gemini.krakenbot.model.FundingProvenanceResolver
import com.gemini.krakenbot.model.InceptionCandidateEvidence
import com.gemini.krakenbot.model.InceptionInferenceEvidence
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
import com.gemini.krakenbot.service.InceptionDisplayInfo
import com.gemini.krakenbot.service.InceptionDisplayStatus
import com.gemini.krakenbot.service.InceptionRecoveryStatus
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.getRecoveryTradeHistoryUntil
import com.gemini.krakenbot.service.withExecutionSession
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.util.TradeDeduplicator
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
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
     * Reads the display-only inception status for the Settings page.
     *
     * This read is strictly local and non-blocking: it checks the local account trust state via
     * [AccountHistoryScopeGuard.readLocalTrustState], validates the recovery configuration
     * fingerprint, and verifies that the durable auto-detected date matches a whitelisted source
     * and valid epoch. It never triggers Kraken network calls, never waits behind ongoing background
     * validation, never alters database state, and never overwrites user configuration.
     */
    suspend fun getLocalInceptionDisplayInfo(): InceptionDisplayInfo {
        val config = configService.getConfig()
        if (!config.settings.inceptionDate.isNullOrBlank()) {
            // An approved strategy start stays authoritative: nothing is copied into or submitted
            // from the approved field, but the historical evidence readout may still render
            // underneath so the operator can compare it with the approved choice.
            val scopeResult = accountHistoryScopeGuard.readLocalTrustState()
            val inferred = if (scopeResult.status == AccountScopeValidationStatus.VALID) {
                readLocalInference(config, scopeResult.currentScopeDigest.orEmpty())
            } else {
                InceptionDisplayInfo()
            }
            val recoveryStatus = readStatus()
            return when (recoveryStatus.status) {
                InceptionRecoveryStatus.CONFIRMED -> InceptionDisplayInfo(
                    status = InceptionDisplayStatus.APPROVED_READY,
                    dateText = config.settings.inceptionDate,
                    message = "${ViewText.INCEPTION_APPROVED_BASELINE_READY_PREFIX} ${config.settings.inceptionDate}",
                )

                InceptionRecoveryStatus.AMBIGUOUS,
                InceptionRecoveryStatus.BASELINE_UNAVAILABLE,
                InceptionRecoveryStatus.UNAVAILABLE,
                -> InceptionDisplayInfo(
                    status = InceptionDisplayStatus.APPROVED_UNAVAILABLE,
                    message = listOf(
                        ViewText.INCEPTION_APPROVED_BASELINE_FAILED_PREFIX,
                        recoveryStatus.reason.orEmpty(),
                    ).filter { it.isNotBlank() }.joinToString(" "),
                )

                else -> InceptionDisplayInfo(
                    status = InceptionDisplayStatus.APPROVED_PENDING,
                    message = ViewText.INCEPTION_APPROVED_BASELINE_PENDING,
                )
            }.withInference(inferred)
        }

        val scopeResult = accountHistoryScopeGuard.readLocalTrustState()
        when (scopeResult.status) {
            AccountScopeValidationStatus.VALIDATION_PENDING -> {
                return InceptionDisplayInfo(
                    status = InceptionDisplayStatus.VALIDATION_PENDING,
                    message = ViewText.INCEPTION_DETECTED_VALIDATION_PENDING,
                )
            }

            AccountScopeValidationStatus.SCOPE_MISMATCH,
            AccountScopeValidationStatus.SCOPE_UNAVAILABLE,
            AccountScopeValidationStatus.UNBOUND_EXISTING_HISTORY,
            AccountScopeValidationStatus.SIMULATION,
            -> {
                return InceptionDisplayInfo(
                    status = InceptionDisplayStatus.UNAVAILABLE,
                    message = ViewText.INCEPTION_DETECTED_UNAVAILABLE,
                )
            }

            AccountScopeValidationStatus.VALID -> {
                // Verified valid scope
            }
        }

        val currentScope = scopeResult.currentScopeDigest.orEmpty()
        val inferred = readLocalInference(config, currentScope)
        val expectedFingerprint = configurationFingerprint(
            config,
            config.settings.copy(inceptionDate = "", comparisonStartDate = null),
            currentScope,
        )
        val storedFingerprint = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
        val storedVersion = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION)

        if (storedVersion != CURRENT_RECOVERY_VERSION || storedFingerprint != expectedFingerprint) {
            val recoveryStatus = readStatus()
            if (recoveryStatus.status == InceptionRecoveryStatus.IN_PROGRESS) {
                return InceptionDisplayInfo(
                    status = InceptionDisplayStatus.IN_PROGRESS,
                    message = ViewText.INCEPTION_DETECTED_IN_PROGRESS,
                ).withInference(inferred)
            }
            if (storedFingerprint.isNullOrBlank()) {
                return InceptionDisplayInfo(
                    status = InceptionDisplayStatus.NOT_DETECTED,
                    message = ViewText.INCEPTION_DETECTED_NOT_STARTED,
                ).withInference(inferred)
            }
            return InceptionDisplayInfo(
                status = InceptionDisplayStatus.UNAVAILABLE,
                message = ViewText.INCEPTION_DETECTED_CONFIG_CHANGED,
            ).withInference(inferred)
        }

        val recoveryStatus = readStatus()
        val epochMs = repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS)?.toLongOrNull()
        val source = repository.getSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE)?.trim()
        val nowMs = nowProvider().toEpochMilli()

        val isAutomaticDetectionValid = epochMs != null && epochMs > 0 && epochMs <= nowMs &&
            (
                source == InceptionDiscoveryService.INCEPTION_SOURCE_AUTO ||
                    source == INCEPTION_SOURCE_AUTO_RECOVERED
                )

        if (recoveryStatus.status == InceptionRecoveryStatus.CONFIRMED) {
            if (isAutomaticDetectionValid) {
                val dateText = Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate().toString()
                return InceptionDisplayInfo(
                    status = InceptionDisplayStatus.CONFIRMED,
                    dateText = dateText,
                    source = source,
                    message = ViewText.INCEPTION_DETECTED_LEAVE_BLANK,
                ).withInference(inferred)
            }
            return InceptionDisplayInfo(
                status = InceptionDisplayStatus.UNAVAILABLE,
                message = ViewText.INCEPTION_DETECTED_UNAVAILABLE,
            ).withInference(inferred)
        }

        return displayFromRecoveryStatus(recoveryStatus).withInference(inferred)
    }

    private suspend fun readLocalInference(config: AppConfig, accountScope: String): InceptionDisplayInfo {
        if (repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_INFERENCE_VERSION) != CURRENT_INFERENCE_VERSION) {
            return InceptionDisplayInfo()
        }
        val fingerprint = inferenceFingerprint(config, accountScope)
        if (repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_INFERENCE_FINGERPRINT) != fingerprint) {
            return InceptionDisplayInfo()
        }
        val record = repository.findInceptionInferenceEvidence(fingerprint) ?: return InceptionDisplayInfo()
        val now = nowProvider()

        val start = validInferenceInstant(record.inferredStart, now)
        val windowStart = validInferenceInstant(record.inferredWindowStart, now)
        val windowEnd = validInferenceInstant(record.inferredWindowEnd, now)
        val candidateWindowValid = start != null && windowStart != null && windowEnd != null &&
            !windowStart.isAfter(start) && !start.isAfter(windowEnd)
        val firstPositive = validInferenceInstant(record.firstPositive, now)
        val inferredStartStrength = record.inferredStartStrength?.takeIf { it in VALID_INFERENCE_STRENGTHS }
        val strongestObserved = validInferenceInstant(record.strongestObservedStart, now)
            ?.takeIf { start == null || it != start }
        val strongestEpisodeStrength = record.strongestEpisodeStrength?.takeIf { it in VALID_INFERENCE_STRENGTHS }

        return InceptionDisplayInfo(
            inferredStartText = if (candidateWindowValid) start.toString() else null,
            inferredWindowStartText = if (candidateWindowValid) windowStart.toString() else null,
            inferredWindowEndText = if (candidateWindowValid) windowEnd.toString() else null,
            firstPositiveText = firstPositive?.toString(),
            inferredStartStrengthText = if (candidateWindowValid) inferredStartStrength else null,
            inferredStartReasonsText = if (candidateWindowValid) {
                record.inferredStartReasons.takeIf { it.isNotEmpty() }?.joinToString(", ") { reasonCodeText(it) }
            } else {
                null
            },
            inferredStartContradictionsText = if (candidateWindowValid) {
                record.inferredStartContradictions.takeIf { it.isNotEmpty() }?.joinToString(", ") { reasonCodeText(it) }
            } else {
                null
            },
            strongestEpisodeText = strongestObserved?.toString(),
            strongestEpisodeStrengthText = if (strongestObserved != null) strongestEpisodeStrength else null,
            strongestEpisodeReasonsText = if (strongestObserved != null) {
                record.strongestEpisodeReasons.takeIf { it.isNotEmpty() }?.joinToString(", ") { reasonCodeText(it) }
            } else {
                null
            },
            earliestAmbiguousText = validInferenceInstant(record.earliestAmbiguousStart, now)?.toString(),
            earlierAmbiguousCountText = record.earlierAmbiguousCandidateCount.takeIf { it > 0 }?.toString(),
            competingCandidatesText = if (candidateWindowValid) {
                record.competingCandidateCount.takeIf { it > 0 }?.toString()
            } else {
                null
            },
            unsupportedMarketsText = record.unsupportedMarketCount
                .takeIf { it > 0 }
                ?.let { count -> "$count (${record.unsupportedMarketSamples.joinToString(", ")})" },
            coverageText = inferenceCoverageText(record, now),
        )
    }

    private fun validInferenceInstant(value: Instant?, now: Instant): Instant? = value?.takeIf {
        it.isAfter(Instant.EPOCH) &&
            !it.isAfter(now)
    }

    private fun reasonCodeText(code: String): String = code.replace('_', ' ').lowercase()

    private fun inferenceCoverageText(record: InceptionInferenceEvidence, now: Instant): String? {
        val coverageStart = validInferenceInstant(record.coverageStart, now) ?: return null
        val coverageEnd = validInferenceInstant(record.coverageEnd, now) ?: return null
        if (coverageStart.isAfter(coverageEnd)) return null
        return "$coverageStart to $coverageEnd"
    }

    private fun InceptionDisplayInfo.withInference(inference: InceptionDisplayInfo): InceptionDisplayInfo = copy(
        inferredStartText = inference.inferredStartText,
        inferredWindowStartText = inference.inferredWindowStartText,
        inferredWindowEndText = inference.inferredWindowEndText,
        firstPositiveText = inference.firstPositiveText,
        inferredStartStrengthText = inference.inferredStartStrengthText,
        inferredStartReasonsText = inference.inferredStartReasonsText,
        inferredStartContradictionsText = inference.inferredStartContradictionsText,
        strongestEpisodeText = inference.strongestEpisodeText,
        strongestEpisodeStrengthText = inference.strongestEpisodeStrengthText,
        strongestEpisodeReasonsText = inference.strongestEpisodeReasonsText,
        earliestAmbiguousText = inference.earliestAmbiguousText,
        earlierAmbiguousCountText = inference.earlierAmbiguousCountText,
        competingCandidatesText = inference.competingCandidatesText,
        unsupportedMarketsText = inference.unsupportedMarketsText,
        coverageText = inference.coverageText,
    )

    private fun displayFromRecoveryStatus(recoveryStatus: InceptionRecoveryStatus): InceptionDisplayInfo =
        when (recoveryStatus.status) {
            InceptionRecoveryStatus.IN_PROGRESS -> InceptionDisplayInfo(
                status = InceptionDisplayStatus.IN_PROGRESS,
                message = ViewText.INCEPTION_DETECTED_IN_PROGRESS,
            )

            InceptionRecoveryStatus.AMBIGUOUS -> InceptionDisplayInfo(
                status = InceptionDisplayStatus.AMBIGUOUS,
                message = ViewText.INCEPTION_DETECTED_AMBIGUOUS,
            )

            InceptionRecoveryStatus.BASELINE_UNAVAILABLE -> InceptionDisplayInfo(
                status = InceptionDisplayStatus.BASELINE_UNAVAILABLE,
                message = ViewText.INCEPTION_DETECTED_BASELINE_UNAVAILABLE,
            )

            InceptionRecoveryStatus.COMPLETE_NO_BOT_EVIDENCE -> InceptionDisplayInfo(
                status = InceptionDisplayStatus.COMPLETE_NO_BOT_EVIDENCE,
                message = ViewText.INCEPTION_DETECTED_COMPLETE_NO_BOT_EVIDENCE,
            )

            InceptionRecoveryStatus.FAILED -> InceptionDisplayInfo(
                status = InceptionDisplayStatus.FAILED,
                message = ViewText.INCEPTION_DETECTED_FAILED,
            )

            InceptionRecoveryStatus.UNAVAILABLE -> InceptionDisplayInfo(
                status = InceptionDisplayStatus.UNAVAILABLE,
                message = ViewText.INCEPTION_DETECTED_UNAVAILABLE,
            )

            else -> InceptionDisplayInfo(
                status = InceptionDisplayStatus.NOT_DETECTED,
                message = ViewText.INCEPTION_DETECTED_NOT_STARTED,
            )
        }

    /**
     * Clears automatic evidence whenever a recovery-scoped setting changes. This is a
     * metadata-only operation and is also used by the discovery path before it reads a cache.
     * Returns true if evidence was cleared or updated due to configuration change; false
     * otherwise. The optional comparison anchor is query state and does not invalidate the
     * approved strategy baseline.
     */
    suspend fun prepareForCurrentConfiguration(settings: Settings?): Boolean =
        prepareForCurrentConfigurationResult(settings).configurationChanged

    suspend fun prepareForCurrentConfigurationResult(settings: Settings?): InceptionPreparationResult {
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
                    settings = settings,
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

        val requestedStart = preflightConfig.settings.inceptionDate
            ?.trim()
            ?.takeIf(String::isNotBlank)

        prepareForCurrentConfigurationLocked(
            config = preflightConfig,
            settings = preflightConfig.settings,
            accountScope = scopeResult.currentScopeDigest.orEmpty(),
        )
        prepareForCurrentBaselineReplayVersionLocked()

        val currentStatus = readStatus()
        if (currentStatus.status == InceptionRecoveryStatus.CONFIRMED) return@withLock currentStatus
        val now = nowProvider()
        val persistedHorizon = readHorizon()
        var retryWithExpandedHorizon = false
        if (requestedStart != null && recoveryStreamsComplete() &&
            (currentStatus.status in FINAL_BASELINE_FAILURES || currentStatus.status == InceptionRecoveryStatus.FAILED)
        ) {
            // A failed approved-start reconstruction is reusable only while its local evidence
            // is unchanged. The configuration fingerprint alone is insufficient: a later sync
            // may add the missing anchor, ledger, trade identity, or price-bearing row without
            // changing the operator's approved date. Keep the retry bounded by the normal
            // interval below and retain all imported history. Include retained rows beyond the
            // original recovery horizon so a later balance observation can become the retry
            // anchor without repaginating already-complete private-history streams.
            if (currentStatus.status in FINAL_BASELINE_FAILURES) {
                val currentEvidence = approvedBaselineEvidenceFingerprint(
                    InceptionDiscoveryService.parseInceptionDate(requestedStart) ?: Instant.EPOCH,
                )
                val failedEvidence = repository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_EVIDENCE_FINGERPRINT,
                )
                val evidenceUnchanged = !failedEvidence.isNullOrBlank() && failedEvidence == currentEvidence
                if (evidenceUnchanged && !isTransientApprovedBaselineFailure(currentStatus.reason)) {
                    return@withLock currentStatus
                }
            }
            retryWithExpandedHorizon = true
        }

        val cadence = classifyRecoveryCadence(currentStatus)
        val lastAttempt = repository
            .getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LAST_ATTEMPT_EPOCH_SEC)
            ?.toLongOrNull()
        if (lastAttempt != null && now.epochSecond - lastAttempt in 0 until cadence.intervalSeconds) {
            return@withLock currentStatus
        }

        val horizon = if (retryWithExpandedHorizon) {
            // This is an evaluation-only expansion. Complete private-history streams were not
            // repaginated, so the durable coverage horizon must remain the original bound.
            maxOf(persistedHorizon ?: now, now)
        } else {
            persistedHorizon ?: now.also {
                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_HORIZON_EPOCH_SEC,
                    it.epochSecond.toString(),
                )
            }
        }
        repository.setSyncMetadata(
            SyncMetadataKeys.INCEPTION_RECOVERY_LAST_ATTEMPT_EPOCH_SEC,
            now.epochSecond.toString(),
        )
        setOverallStatus(InceptionRecoveryStatus.IN_PROGRESS, "")

        try {
            configService.withExecutionSession {
                val pinnedConfig = configService.getConfig()
                val pinnedStart = pinnedConfig.settings.inceptionDate
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
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
                        settings = pinnedConfig.settings,
                        accountScope = pinnedScope.currentScopeDigest.orEmpty(),
                    )
                    prepareForCurrentBaselineReplayVersionLocked()
                    recoverPagesAndEvaluate(
                        config = pinnedConfig,
                        backend = backend,
                        horizon = horizon,
                        approvedStartRequested = pinnedStart != null,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Strategy inception recovery failed; retaining resumable progress", e)
            setOverallStatus(InceptionRecoveryStatus.FAILED, "history request failed")
        }
        val outcomeStatus = readStatus()
        when {
            outcomeStatus.status == InceptionRecoveryStatus.IN_PROGRESS &&
                outcomeStatus.reason == RECOVERY_REASON_BOUNDED_CONTINUATION -> {
                log.info(
                    "Strategy inception recovery incomplete; next continuation eligible in {}s",
                    SUCCESSFUL_CONTINUATION_INTERVAL_SECONDS,
                )
            }

            outcomeStatus.status == InceptionRecoveryStatus.FAILED ||
                outcomeStatus.status == InceptionRecoveryStatus.UNAVAILABLE ||
                (
                    outcomeStatus.status in FINAL_BASELINE_FAILURES &&
                        isTransientApprovedBaselineFailure(outcomeStatus.reason)
                    ) -> {
                log.warn(
                    "Strategy inception recovery failed; retry eligible in {}s",
                    FAILURE_RETRY_INTERVAL_SECONDS,
                )
            }
        }
        outcomeStatus
    }

    private suspend fun recoverPagesAndEvaluate(
        config: AppConfig,
        backend: KrakenService,
        horizon: Instant,
        approvedStartRequested: Boolean,
    ) {
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
            setOverallStatus(InceptionRecoveryStatus.IN_PROGRESS, RECOVERY_REASON_BOUNDED_CONTINUATION)
            return
        }

        if (approvedStartRequested) {
            establishApprovedStartBaseline(config, backend, horizon)
        } else {
            evaluateRecoveredEvidence(config, backend, horizon)
        }
    }

    /**
     * Establishes the baseline for an operator-approved strategy start. The approval expresses
     * intent only: the baseline still has to pass the same reverse-replay reconstruction as the
     * automatic candidate path, backed by complete recovery streams, and it never converts
     * unknown-ownership trades into owned ones.
     */
    private suspend fun establishApprovedStartBaseline(config: AppConfig, backend: KrakenService, horizon: Instant) {
        val requestedStart = config.settings.inceptionDate
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(InceptionDiscoveryService::parseInceptionDate)
        if (requestedStart == null) {
            setOverallStatus(InceptionRecoveryStatus.UNAVAILABLE, "invalid approved inception date")
            return
        }
        if (requestedStart.isAfter(horizon)) {
            setOverallStatus(InceptionRecoveryStatus.UNAVAILABLE, "approved start is in the future")
            return
        }

        val expectedUniverse = config.allocations
            .map { Asset.normalizeLedgerAsset(it.symbol.value).uppercase() }
            .toSet()
        val approvedId = repository
            .getSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID)
            ?.toIntOrNull()
        if (approvedId != null) {
            val existing = repository.getSnapshotById(approvedId)
            if (existing != null && isExactBaselineSnapshot(existing, requestedStart, expectedUniverse)) {
                confirmApprovedBaseline(requestedStart, approvedId)
                return
            }
            repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID, "")
        }

        val nearbySnapshots = repository.getSnapshotsInRange(
            requestedStart.minusSeconds(InceptionDiscoveryService.MAX_ANCHOR_PROXIMITY_SECONDS),
            requestedStart.plusSeconds(InceptionDiscoveryService.MAX_ANCHOR_PROXIMITY_SECONDS),
        )
        // A post-start observation is useful as a reverse-replay anchor, but it is not the
        // requested baseline. Direct adoption is safe only for one exact, universe-matching row;
        // otherwise reconstruct the state at T or fail closed.
        val exactSnapshot = nearbySnapshots
            .singleOrNull { isExactBaselineSnapshot(it, requestedStart, expectedUniverse) }
        if (exactSnapshot != null) {
            val exactOrdinal = nearbySnapshots
                .filter { it.timestamp == requestedStart }
                .indexOf(exactSnapshot)
            val exactId = repository.getSnapshotId(requestedStart, exactOrdinal)
            if (exactId != null) {
                confirmApprovedBaseline(requestedStart, exactId)
                return
            }
        }

        val allTrades = repository.getTradesInRange(Instant.EPOCH, horizon.plusSeconds(1))
            .filter { it.success && !it.dryRun }
            .sortedBy(TradeRecord::timestamp)
        when (
            val baseline = reconstructBaseline(
                config = config,
                backend = backend,
                baselineTime = requestedStart,
                horizon = horizon,
                allTrades = allTrades,
            )
        ) {
            is BaselineResult.Failure -> {
                clearApprovedBaselineEvidence()
                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_EVIDENCE_FINGERPRINT,
                    approvedBaselineEvidenceFingerprint(requestedStart),
                )
                setOverallStatus(baseline.status, baseline.reason)
            }

            is BaselineResult.Success -> {
                val metadata = mapOf(
                    SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS to requestedStart.toEpochMilli().toString(),
                    SyncMetadataKeys.DETECTED_INCEPTION_SOURCE to INCEPTION_SOURCE_APPROVED,
                    SyncMetadataKeys.INCEPTION_RECOVERY_REASON to APPROVED_BASELINE_READY_REASON,
                    SyncMetadataKeys.INCEPTION_RECOVERY_STATUS to InceptionRecoveryStatus.CONFIRMED,
                    SyncMetadataKeys.INCEPTION_RECOVERY_EVIDENCE_FINGERPRINT to "",
                )
                repository.saveSnapshotWithMetadata(
                    snapshot = baseline.snapshot,
                    metadata = metadata,
                    snapshotIdMetadataKeys = setOf(
                        SyncMetadataKeys.INCEPTION_SNAPSHOT_ID,
                        SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID,
                    ),
                )
            }
        }
    }

    private suspend fun confirmApprovedBaseline(requestedStart: Instant, snapshotId: Int) {
        repository.setSyncMetadata(
            SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS,
            requestedStart.toEpochMilli().toString(),
        )
        repository.setSyncMetadata(SyncMetadataKeys.DETECTED_INCEPTION_SOURCE, INCEPTION_SOURCE_APPROVED)
        repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_SNAPSHOT_ID, snapshotId.toString())
        repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID, snapshotId.toString())
        repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_REASON, APPROVED_BASELINE_READY_REASON)
        repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_EVIDENCE_FINGERPRINT, "")
        repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS, InceptionRecoveryStatus.CONFIRMED)
    }

    private suspend fun clearApprovedBaselineEvidence() {
        repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID, "")
    }

    private fun isExactBaselineSnapshot(
        snapshot: PortfolioSnapshot,
        requestedStart: Instant,
        expectedUniverse: Set<String>,
    ): Boolean = snapshot.timestamp == requestedStart &&
        snapshot.balancesObservedAt?.let { it == requestedStart } != false &&
        snapshot.assets.keys.map { Asset.normalizeLedgerAsset(it).uppercase() }.toSet() == expectedUniverse

    private suspend fun recoveryStreamsComplete(): Boolean =
        repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS) == STREAM_COMPLETE &&
            ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS) == STREAM_COMPLETE

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

        val candidateTrades = allTrades.filter { it.symbol.isNotBlank() && !Asset(it.symbol).isUsd }
        val orderTxids = candidateTrades.mapNotNull { it.orderTxid?.trim()?.takeIf(String::isNotBlank) }.toSet()
        val clientOrderIds = candidateTrades.mapNotNull { it.clientOrderId?.trim()?.takeIf(String::isNotBlank) }.toSet()
        val knownOrderTxids = orderIntentRepository
            ?.getKnownRebalancerOrderIdentities(orderTxids, clientOrderIds)
            ?.orderTxids
            .orEmpty()
        persistHistoricalInference(config, allTrades, horizon, knownOrderTxids)
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

        // Candidate-specific price evidence is only justified on the automatic path, where the
        // candidate fill itself observed a real executed price one millisecond after the anchor.
        val candidatePriceEvidence = candidate.symbol
            .takeIf(String::isNotBlank)
            ?.let { Asset.normalizeLedgerAsset(it).uppercase() to candidate.price }
        when (
            val baseline = reconstructBaseline(
                config = config,
                backend = backend,
                baselineTime = candidate.timestamp.minusMillis(1),
                horizon = horizon,
                allTrades = allTrades,
                candidatePriceEvidence = candidatePriceEvidence,
            )
        ) {
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
        baselineTime: Instant,
        horizon: Instant,
        allTrades: List<TradeRecord>,
        candidatePriceEvidence: Pair<String, BigDecimal>? = null,
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

        val anchorObservation = anchor.balancesObservedAt ?: anchor.timestamp
        if (baselineTime.isAfter(anchorObservation)) {
            return BaselineResult.Failure(
                InceptionRecoveryStatus.BASELINE_UNAVAILABLE,
                "baseline is newer than retained anchor",
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
        val balanceValidation = AuthoritativeLedgerBalanceValidator.validate(historicalLedgers)
        if (!balanceValidation.isValid) {
            val failure = requireNotNull(balanceValidation.failure)
            log.warn("Inception ledger balance validation failed: {}", failure.diagnostic)
            return BaselineResult.Failure(
                InceptionRecoveryStatus.AMBIGUOUS,
                failure.reason.take(MAX_REASON_LENGTH),
            )
        }
        log.info(
            "Validated inception ledger balances: checkpoints={}/{}, trades={}, grouped={}, " +
                "sameTimestamp={}, flexible={}, nonAuthoritative={}, scopes={}",
            balanceValidation.validatedCheckpointCount,
            balanceValidation.authoritativeCheckpointCount,
            balanceValidation.tradeCheckpointCount,
            balanceValidation.groupedEventCheckpointCount,
            balanceValidation.sameTimestampCheckpointCount,
            balanceValidation.flexibleCheckpointCount,
            balanceValidation.nonAuthoritativeEventCount,
            balanceValidation.scopeCount,
        )

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
        val unsupportedClassifiedLedger = historicalLedgers.firstOrNull { event ->
            flowCategories[event.ledgerId] == FlowCategory.UNSUPPORTED
        }
        if (unsupportedClassifiedLedger != null) {
            return BaselineResult.Failure(
                InceptionRecoveryStatus.AMBIGUOUS,
                "unsupported ledger type ${unsupportedClassifiedLedger.type}".take(MAX_REASON_LENGTH),
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
            if (!reverseApplyLedger(
                    event = event,
                    balances = runningBalances,
                    expectedUniverse = expectedUniverse,
                    flowCategories = flowCategories,
                    resolvedScopes = balanceValidation.resolvedScopes,
                )
            ) {
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
            candidatePriceEvidence = candidatePriceEvidence,
            runningBalances = runningBalances,
            backend = backend,
        ) ?: return BaselineResult.Failure(
            InceptionRecoveryStatus.BASELINE_UNAVAILABLE,
            HISTORICAL_PRICE_UNAVAILABLE_REASON,
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
        flowCategories: Map<String, FlowCategory>,
        resolvedScopes: Map<String, AuthoritativeLedgerBalanceValidator.LedgerWalletScope>,
    ): Boolean {
        if (event.type.equals(TRADE_LEDGER_TYPE, ignoreCase = true)) return true
        val isConversion = event.type.equals(KrakenApiConstants.LEDGER_TYPE_CONVERSION, ignoreCase = true)
        if (!isConversion) {
            when (resolvedScopes[event.ledgerId]) {
                AuthoritativeLedgerBalanceValidator.LedgerWalletScope.SPOT -> Unit

                AuthoritativeLedgerBalanceValidator.LedgerWalletScope.STAKING,
                AuthoritativeLedgerBalanceValidator.LedgerWalletScope.FUTURES,
                AuthoritativeLedgerBalanceValidator.LedgerWalletScope.OPAQUE_STAKING,
                -> return true

                null -> {
                    // A zero net delta cannot change the reconstructed configured balance, so it
                    // is safe to ignore even when its authoritative checkpoint was deliberately
                    // left without a replay scope.
                    if (event.netBalanceDelta().signum() == 0) return true
                    // Valid authoritative rows are assigned by the validator. A missing scope
                    // would make applying versus skipping a balance-changing event unknowable.
                    if (event.hasAuthoritativeBalance) return false
                    // For an internal transfer, a documented internal scope marker (e.g. Earn allocation)
                    // is known to be non-Spot and safely skipped, while an undocumented internal move
                    // without a resolved scope fails closed.
                    if (flowCategories[event.ledgerId] == FlowCategory.INTERNAL_MOVE) {
                        return LedgerFlowClassifier.isDocumentedInternalScopeMarker(event)
                    }
                    // A staking row without an authoritative balance whose wallet scope could not
                    // be resolved by the validator is ambiguous and must fail closed.
                    if (event.type.equals(KrakenApiConstants.LEDGER_TYPE_STAKING, ignoreCase = true)) {
                        return false
                    }
                }
            }
        }
        val symbol = Asset.normalizeLedgerAsset(event.asset).uppercase()
        val delta = event.netBalanceDelta()
        if (symbol !in expectedUniverse) {
            // A structurally complete conversion can legitimately leave the configured strategy
            // universe (for example USD -> a stablecoin the strategy does not track). The linked
            // opposite leg proves this is an internal transformation; do not reinterpret it as
            // unexplained owner capital, but also do not invent a price for the untracked asset.
            return delta.signum() == 0 ||
                event.type.equals(KrakenApiConstants.LEDGER_TYPE_CONVERSION, ignoreCase = true)
        }
        val balance = balances.getValue(symbol)
        balances[symbol] = balance.subtract(delta)
        return true
    }

    private suspend fun resolveHistoricalPrices(
        allocations: List<Allocation>,
        baselineTime: Instant,
        candidatePriceEvidence: Pair<String, BigDecimal>?,
        runningBalances: Map<String, BigDecimal>,
        backend: KrakenService,
    ): Map<String, BigDecimal>? {
        val evidenceSymbol = candidatePriceEvidence?.first
        val prices = mutableMapOf<String, BigDecimal>()
        for (allocation in allocations) {
            val symbol = Asset.normalizeLedgerAsset(allocation.symbol.value).uppercase()
            if (symbol == Asset.USD) {
                prices[symbol] = BigDecimal.ONE
                continue
            }
            val balance = runningBalances[symbol] ?: BigDecimal.ZERO
            val candidateException = if (symbol == evidenceSymbol && candidatePriceEvidence.second.signum() > 0) {
                candidatePriceEvidence.second
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

    private suspend fun persistHistoricalInference(
        config: AppConfig,
        trades: List<TradeRecord>,
        horizon: Instant,
        knownRebalancerOrderTxids: Set<String>,
    ) {
        val accountScope = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST).orEmpty()
        val fingerprint = inferenceFingerprint(config, accountScope)
        val inferenceTrades = trades.map { trade ->
            HistoricalInferenceTrade(
                id = trade.id?.toString() ?: "${trade.timestamp.toEpochMilli()}:${trade.pair}:${trade.side}",
                timestamp = trade.timestamp,
                pair = trade.pair,
                side = trade.side,
                symbol = trade.symbol,
                volume = trade.volume,
                quoteAmount = trade.usdAmount,
                orderTxid = trade.orderTxid,
                tradeId = trade.tradeId,
                ownership = if (classifyTradeForRecovery(trade, knownRebalancerOrderTxids) ==
                    TradeOwnership.REBALANCER
                ) {
                    InferenceOwnership.POSITIVE
                } else {
                    InferenceOwnership.UNKNOWN
                },
            )
        }
        val policy = HistoricalInferencePolicy()
        val inference = HistoricalStrategyStartDetector.infer(inferenceTrades, policy)
        val evidenceDigest = evidenceFingerprint(
            modelVersion = CURRENT_INFERENCE_VERSION,
            config = config,
            accountScope = accountScope,
            horizon = horizon,
            inferenceTrades = inferenceTrades,
            unsupportedMarkets = inference.unsupportedMarkets,
        )
        val metadata = mapOf(
            SyncMetadataKeys.INCEPTION_INFERENCE_VERSION to CURRENT_INFERENCE_VERSION,
            SyncMetadataKeys.INCEPTION_INFERENCE_FINGERPRINT to fingerprint,
            SyncMetadataKeys.INCEPTION_RECOVERY_HORIZON_EPOCH_SEC to horizon.epochSecond.toString(),
        )
        val existing = repository.findInceptionInferenceEvidence(fingerprint)
        if (existing != null &&
            existing.modelVersion == CURRENT_INFERENCE_VERSION &&
            existing.evidenceDigest == evidenceDigest
        ) {
            repository.setSyncMetadataAtomically(metadata)
            return
        }
        val evidence = InceptionInferenceEvidence(
            fingerprint = fingerprint,
            evidenceDigest = evidenceDigest,
            modelVersion = CURRENT_INFERENCE_VERSION,
            coverageStart = inference.coverageStart,
            coverageEnd = inference.coverageEnd,
            horizon = horizon,
            firstPositive = inference.firstPositivelyOwnedTrade,
            inferredStart = inference.inferredStart,
            inferredWindowStart = inference.inferredWindowStart,
            inferredWindowEnd = inference.inferredWindowEnd,
            inferredStartStrength = inference.inferredStartStrength?.name,
            inferredStartReasons = inference.inferredStartReasons,
            inferredStartContradictions = inference.inferredStartContradictions,
            strongestObservedStart = inference.strongestObservedStart,
            strongestEpisodeStrength = inference.strongestEpisodeStrength?.name,
            strongestEpisodeReasons = inference.strongestEpisodeReasons,
            strongestEpisodeContradictions = inference.strongestEpisodeContradictions,
            earliestAmbiguousStart = inference.earliestAmbiguousStart,
            earlierAmbiguousCandidateCount = inference.earlierAmbiguousCandidateCount,
            unsupportedMarketCount = inference.unsupportedMarkets.size,
            unsupportedMarketSamples = inference.unsupportedMarkets.take(MAX_UNSUPPORTED_MARKET_SAMPLES),
            competingCandidateCount = inference.competingCandidateCount,
            candidates = inference.candidates.map { candidate ->
                InceptionCandidateEvidence(
                    observedStart = candidate.observedStart,
                    observedEnd = candidate.observedEnd,
                    windowStart = candidate.windowStart,
                    windowEnd = candidate.windowEnd,
                    strength = candidate.strength.name,
                    reasons = candidate.reasons,
                    contradictions = candidate.contradictions,
                    assetCount = candidate.distinctAssets.size,
                    assetSymbols = candidate.distinctAssets.sorted(),
                    orderCount = candidate.orderCount,
                    repeatedEvidenceCount = candidate.repeatedEvidenceCount,
                    timescalesSeconds = candidate.timescalesSeconds,
                )
            },
        )
        repository.saveInceptionInferenceEvidence(evidence, metadata)
    }

    internal fun inferenceFingerprint(config: AppConfig, accountScope: String): String =
        sha256Hex(listOf(config.settings.simulation.toString(), accountScope).joinToString("\u0000"))

    private fun evidenceFingerprint(
        modelVersion: String,
        config: AppConfig,
        accountScope: String,
        horizon: Instant,
        inferenceTrades: List<HistoricalInferenceTrade>,
        unsupportedMarkets: List<String>,
    ): String {
        val material = buildString {
            append(modelVersion).append('\u0000')
            append(config.settings.simulation.toString()).append('\u0000')
            append(accountScope).append('\u0000')
            append(horizon.toString()).append('\u0000')
            inferenceTrades
                .sortedWith(
                    compareBy(
                        { it.timestamp },
                        { it.pair },
                        { it.side },
                        { it.orderTxid.orEmpty() },
                        { it.tradeId.orEmpty() },
                        { it.volume.stripTrailingZeros() },
                        { it.quoteAmount.stripTrailingZeros() },
                        { it.ownership.name },
                    ),
                )
                .forEach { trade ->
                    append(trade.timestamp).append('|')
                    append(trade.pair).append('|')
                    append(trade.side).append('|')
                    append(trade.volume.digestScale()).append('|')
                    append(trade.quoteAmount.digestScale()).append('|')
                    append(trade.orderTxid.orEmpty()).append('|')
                    append(trade.tradeId.orEmpty()).append('|')
                    append(trade.ownership.name).append('\n')
                }
            append('\u0000')
            unsupportedMarkets.forEach { append(it).append(',') }
        }
        return sha256Hex(material)
    }

    // Zero has multiple BigDecimal representations (0E-8 vs 0); normalize so the
    // digest is stable across data sources and the idempotent skip stays effective.
    private fun BigDecimal.digestScale(): String = (
        if (signum() ==
            0
        ) {
            BigDecimal.ZERO
        } else {
            this
        }
        ).stripTrailingZeros().toPlainString()

    private fun sha256Hex(material: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private suspend fun prepareForCurrentConfigurationLocked(
        config: AppConfig,
        settings: Settings?,
        accountScope: String,
    ): Boolean {
        val fingerprint = configurationFingerprint(config, settings, accountScope)
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
            SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID,
            SyncMetadataKeys.INCEPTION_RECOVERY_REASON,
            SyncMetadataKeys.INCEPTION_RECOVERY_EVIDENCE_FINGERPRINT,
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
     * Baseline replay semantics can change without changing the recovery stream contract. In that
     * case invalidate only the derived inception result and preserve complete private-history
     * coverage so the next evaluation does not silently reuse a financially stale baseline or
     * repaginate already-complete streams.
     */
    private suspend fun prepareForCurrentBaselineReplayVersionLocked() {
        if (repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_BASELINE_REPLAY_VERSION) ==
            CURRENT_BASELINE_REPLAY_VERSION
        ) {
            return
        }

        repository.setSyncMetadataAtomically(
            mapOf(
                SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS to "",
                SyncMetadataKeys.DETECTED_INCEPTION_SOURCE to "",
                SyncMetadataKeys.INCEPTION_SNAPSHOT_ID to "",
                SyncMetadataKeys.INCEPTION_RECOVERY_STATUS to "",
                SyncMetadataKeys.INCEPTION_RECOVERY_LAST_ATTEMPT_EPOCH_SEC to "",
                SyncMetadataKeys.INCEPTION_RECOVERY_CANDIDATE_EPOCH_MS to "",
                SyncMetadataKeys.INCEPTION_RECOVERY_CANDIDATE_TRADE_ID to "",
                SyncMetadataKeys.INCEPTION_RECOVERY_CANDIDATE_ORDER_TXID to "",
                SyncMetadataKeys.INCEPTION_RECOVERY_CANDIDATE_DB_ID to "",
                SyncMetadataKeys.INCEPTION_RECOVERY_OWNERSHIP_EVIDENCE to "",
                SyncMetadataKeys.INCEPTION_RECOVERY_BASELINE_SNAPSHOT_ID to "",
                SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID to "",
                SyncMetadataKeys.INCEPTION_RECOVERY_REASON to "",
                SyncMetadataKeys.INCEPTION_RECOVERY_EVIDENCE_FINGERPRINT to "",
                SyncMetadataKeys.INCEPTION_BASELINE_REPLAY_VERSION to CURRENT_BASELINE_REPLAY_VERSION,
            ),
        )
    }

    /**
     * The recovery result depends on the approved inception, tracked asset universe, and account
     * identity. The optional comparison anchor is evaluated by the comparison query and does not
     * change the recovered strategy baseline. Store only a digest so configuration details and
     * credential material never enter the metadata table or logs.
     */
    internal fun configurationFingerprint(config: AppConfig, settings: Settings?, accountScope: String): String {
        val allocationShape = config.allocations
            .map { allocation ->
                "${Asset.canonicalSymbol(allocation.symbol.value)}=${allocation.targetPercent}"
            }
            .sorted()
            .joinToString(",")
        val material = listOf(
            settings?.inceptionDate?.trim().orEmpty(),
            config.settings.simulation.toString(),
            allocationShape,
            accountScope,
        ).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /**
     * Digests the local evidence that can change an approved-start reconstruction without a
     * settings change. This is deliberately financial-row data only; credentials and raw account
     * scope details remain represented by their existing digests.
     */
    private suspend fun approvedBaselineEvidenceFingerprint(requestedStart: Instant): String {
        val upperBound = Instant.ofEpochMilli(Long.MAX_VALUE)
        val trades = repository.getTradesInRange(Instant.EPOCH, upperBound)
        val snapshots = repository.getAllSnapshotsInRange(Instant.EPOCH, upperBound)
        val ledgers = ledgerRepository.getLedgersInRange(Instant.EPOCH, upperBound)
        val material = buildString {
            append(CURRENT_RECOVERY_VERSION).append('\u0000')
            append("full-retained-approved-evidence-v$CURRENT_BASELINE_REPLAY_VERSION").append('\u0000')
            append(requestedStart).append('\u0000')
            append(repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT).orEmpty())
                .append('\u0000')
            append(repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST).orEmpty())
                .append('\u0000')
            snapshots.sortedBy { it.timestamp }.forEach { snapshot ->
                append("snapshot|").append(snapshot.timestamp).append('|')
                    .append(snapshot.balancesObservedAt).append('|')
                    .append(snapshot.totalValueUSD.digestScale()).append('\n')
                snapshot.assets.toSortedMap().forEach { (asset, row) ->
                    append("asset|").append(asset).append('|').append(row.symbol.value).append('|')
                        .append(row.balance.digestScale()).append('|').append(row.price.digestScale()).append('|')
                        .append(row.valueUSD.digestScale()).append('|').append(row.targetPercent.digestScale())
                        .append('|').append(row.currentPercent.digestScale()).append('|')
                        .append(row.deviationPercent.digestScale()).append('|')
                        .append(row.deviationUSD.digestScale()).append('\n')
                }
            }
            trades.sortedWith(compareBy({ it.timestamp }, { it.id ?: Int.MAX_VALUE })).forEach { trade ->
                append("trade|").append(trade.id).append('|').append(trade.timestamp).append('|')
                    .append(trade.pair).append('|').append(trade.side).append('|').append(trade.symbol).append('|')
                    .append(trade.volume.digestScale()).append('|').append(trade.usdAmount.digestScale())
                    .append('|').append(trade.success).append('|').append(trade.dryRun).append('|')
                    .append(trade.price.digestScale()).append('|').append(trade.fee.digestScale()).append('|')
                    .append(trade.source).append('|').append(trade.cycleId).append('|').append(trade.orderTxid)
                    .append('|').append(trade.tradeId).append('|').append(trade.clientOrderId).append('\n')
            }
            ledgers.sortedWith(compareBy({ it.time }, { it.ledgerId })).forEach { event ->
                append("ledger|").append(event.ledgerId).append('|').append(event.refid).append('|')
                    .append(event.time).append('|').append(event.type).append('|').append(event.subtype).append('|')
                    .append(event.asset).append('|').append(event.amount.digestScale()).append('|')
                    .append(event.fee.digestScale()).append('|').append(event.balance.digestScale()).append('|')
                    .append(event.hasAuthoritativeBalance).append('|').append(event.hasAuthoritativeFee).append('|')
                    .append(event.hasValidFee).append('|').append(event.hasValidAmount).append('\n')
            }
        }
        return sha256Hex(material)
    }

    private suspend fun setOverallStatus(status: String, reason: String) {
        repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS, status)
        repository.setSyncMetadata(
            SyncMetadataKeys.INCEPTION_RECOVERY_REASON,
            reason.take(MAX_REASON_LENGTH),
        )
    }

    /**
     * These failures depend on evidence outside the local financial rows. Retry them on the
     * normal bounded interval without re-paginating complete history; structural contradictions
     * remain terminal until their local evidence or recovery scope changes.
     */
    private fun isTransientApprovedBaselineFailure(reason: String?): Boolean =
        reason == HISTORICAL_PRICE_UNAVAILABLE_REASON ||
            reason?.startsWith("ledger provenance unresolved:", ignoreCase = true) == true ||
            reason?.contains("unresolved funding provenance", ignoreCase = true) == true ||
            reason?.startsWith("Funding legs in card group cannot be proven", ignoreCase = true) == true

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
        val persistedStatus = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS)
            ?.takeIf(String::isNotBlank)
            ?: InceptionRecoveryStatus.NOT_STARTED
        val status = if (persistedStatus == InceptionRecoveryStatus.CONFIRMED &&
            repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_BASELINE_REPLAY_VERSION) !=
            CURRENT_BASELINE_REPLAY_VERSION
        ) {
            InceptionRecoveryStatus.IN_PROGRESS
        } else {
            persistedStatus
        }
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

    enum class RecoveryCadence(val intervalSeconds: Long) {
        CONTINUATION(SUCCESSFUL_CONTINUATION_INTERVAL_SECONDS),
        RETRY(FAILURE_RETRY_INTERVAL_SECONDS),
    }

    internal suspend fun classifyRecoveryCadence(currentStatus: InceptionRecoveryStatus): RecoveryCadence {
        val isHealthyIncomplete = currentStatus.status == InceptionRecoveryStatus.IN_PROGRESS &&
            currentStatus.reason == RECOVERY_REASON_BOUNDED_CONTINUATION &&
            !recoveryStreamsComplete() &&
            repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS) != STREAM_FAILED &&
            ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS) != STREAM_FAILED

        return if (isHealthyIncomplete) {
            RecoveryCadence.CONTINUATION
        } else {
            RecoveryCadence.RETRY
        }
    }

    companion object {
        const val CURRENT_RECOVERY_VERSION = "1"
        const val CURRENT_BASELINE_REPLAY_VERSION = "7"
        const val CURRENT_INFERENCE_VERSION = "2"
        const val MAX_PAGES_PER_RUN = 4
        const val SUCCESSFUL_CONTINUATION_INTERVAL_SECONDS = 30L
        const val FAILURE_RETRY_INTERVAL_SECONDS = 300L
        const val RETRY_INTERVAL_SECONDS = FAILURE_RETRY_INTERVAL_SECONDS
        const val RECOVERY_REASON_BOUNDED_CONTINUATION = "bounded recovery continues"
        const val INCEPTION_SOURCE_AUTO_RECOVERED = "auto-recovered"
        const val INCEPTION_SOURCE_APPROVED = "approved"
        val FINAL_BASELINE_FAILURES = setOf(
            InceptionRecoveryStatus.AMBIGUOUS,
            InceptionRecoveryStatus.BASELINE_UNAVAILABLE,
        )
        private const val HISTORICAL_PRICE_UNAVAILABLE_REASON = "historical price unavailable"
        private const val APPROVED_BASELINE_READY_REASON = "approved-start baseline ready"

        private const val STREAM_COMPLETE = "COMPLETE"
        private const val STREAM_IN_PROGRESS = "IN_PROGRESS"
        private const val STREAM_FAILED = "FAILED"
        private const val COMPLETED = "completed"
        private const val TRADE_LEDGER_TYPE = "trade"
        private val SUPPORTED_LEDGER_TYPES =
            setOf(TRADE_LEDGER_TYPE) + LedgerEvent.EXTERNAL_BALANCE_TYPES.map(String::lowercase)
        private const val MAX_REASON_LENGTH = 60
        private val VALID_INFERENCE_STRENGTHS = setOf("HIGH", "MEDIUM", "LOW")
        private const val MAX_UNSUPPORTED_MARKET_SAMPLES = 5
        private val NEGATIVE_BALANCE_TOLERANCE = BigDecimal("0.00000001")
    }
}
