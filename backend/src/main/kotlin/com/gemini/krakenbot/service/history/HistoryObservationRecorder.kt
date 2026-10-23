package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.DataDirectoryIdentity
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.DirectBalanceCapture
import com.gemini.krakenbot.service.DirectEvidenceStatus
import com.gemini.krakenbot.service.DirectTickerCapture
import com.gemini.krakenbot.service.DirectTickerMark
import com.gemini.krakenbot.service.DirectTickerRequest
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.ObservedBalances
import com.gemini.krakenbot.service.ObservedPrices
import com.gemini.krakenbot.service.readTickerPricesWithCapture
import com.gemini.krakenbot.service.withExecutionSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Bounded, off-cycle persistence for the single selected end-of-cycle sample and activity row. */
class HistoryObservationRecorder(
    private val store: HistoryStore,
    private val krakenService: KrakenService,
    private val configService: ConfigService,
    private val dataDirectoryIdentity: DataDirectoryIdentity,
    private val nowProvider: () -> Instant = Instant::now,
    private val identityLookupTimeoutMs: Long = IDENTITY_LOOKUP_TIMEOUT_MS,
    private val captureTimeoutMs: Long = CAPTURE_TIMEOUT_MS,
) {
    private val log = LoggerFactory.getLogger(HistoryObservationRecorder::class.java)
    private val queue = Channel<PendingCycle>(capacity = QUEUE_CAPACITY)
    private val lastCaptureIssue = AtomicReference<String?>(null)
    private val scopeLock = Mutex()
    private val simulationSessionId = UUID.randomUUID().toString()

    @Volatile
    private var worker: Job? = null

    @Volatile
    private var verifiedAccountScope: VerifiedAccountScope? = null

    fun start(scope: CoroutineScope) {
        synchronized(this) {
            if (worker?.isActive == true) return
            worker = scope.launch {
                for (pending in queue) persistCandidate(pending)
            }
        }
    }

    fun stop() {
        synchronized(this) {
            worker?.cancel()
            worker = null
        }
    }

    /** Resolves the current account through the authenticated API-key identity before exposing its scope. */
    suspend fun currentSourceScope(config: AppConfig): HistorySourceScope? {
        if (config.settings.simulation) {
            return HistoryObservationValuator.simulationScope(config, simulationSessionId)
        }
        val managedSymbols = HistoryObservationValuator.scopeSymbols(config)
        val fingerprint = HistoryObservationValuator.scopeFingerprint(managedSymbols)
        val resolved = try {
            withTimeout(identityLookupTimeoutMs) {
                configService.withExecutionSession {
                    krakenService.withStableBackend { backend ->
                        val credentialScope = backend.getFundingEvidenceScope().trim()
                            .takeIf { it.isNotBlank() && it != "scope-unavailable" }
                            ?: return@withStableBackend null
                        val cached = verifiedAccountScope
                        if (cached != null && cached.credentialScope == credentialScope &&
                            cached.managedScopeFingerprint == fingerprint
                        ) {
                            dataDirectoryIdentity.bindOrVerify(cached.accountIdentityDigest)
                            return@withStableBackend HistoryObservationValuator.accountScope(
                                config,
                                cached.accountIdentityDigest,
                            )
                        }
                        val identity = backend.getAuthenticatedAccountIdentity()
                            ?.filterNot(Char::isWhitespace)
                            ?.uppercase(Locale.ROOT)
                            ?.takeIf(String::isNotBlank)
                            ?: return@withStableBackend null
                        val digest = sha256(identity)
                        dataDirectoryIdentity.bindOrVerify(digest)
                        verifiedAccountScope = VerifiedAccountScope(digest, credentialScope, fingerprint)
                        HistoryObservationValuator.accountScope(config, digest)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            verifiedAccountScope = null
            return null
        }
        if (resolved == null) verifiedAccountScope = null
        return resolved
    }

    /** Captures final values and activity in memory; SQLite and account authentication run off-cycle. */
    suspend fun captureFinalCycle(
        cycleId: String,
        config: AppConfig,
        balances: ObservedBalances,
        prices: ObservedPrices,
        snapshot: PortfolioSnapshot,
        sampleSelection: HistorySampleSelection,
        additionalIncompleteReasons: List<String> = emptyList(),
        actions: List<String> = emptyList(),
        dryRun: Boolean,
        outcome: HistoryCycleOutcome? = null,
    ): Boolean = queueCycleCapture(
        cycleId = cycleId,
        config = config,
        balances = balances,
        prices = prices,
        snapshot = snapshot,
        observedAt = snapshot.timestamp,
        sampleSelection = sampleSelection,
        additionalIncompleteReasons = additionalIncompleteReasons,
        actions = actions,
        dryRun = dryRun,
        outcome = outcome,
    )

    /** Queues direct evidence and cycle activity when valuation fails before a snapshot exists. */
    suspend fun captureIncompleteCycle(
        cycleId: String,
        config: AppConfig,
        balances: ObservedBalances,
        prices: ObservedPrices,
        reason: String,
        actions: List<String> = emptyList(),
        dryRun: Boolean,
        outcome: HistoryCycleOutcome? = HistoryCycleOutcome.PROBLEM,
    ): Boolean {
        val observedAt = maxOf(
            balances.directCapture?.responseEndedAt ?: balances.observedAt,
            prices.directCapture?.responseEndedAt ?: prices.observedAt,
        )
        return queueCycleCapture(
            cycleId = cycleId,
            config = config,
            balances = balances,
            prices = prices,
            snapshot = null,
            observedAt = observedAt,
            sampleSelection = HistorySampleSelection.PRE_TRADE,
            additionalIncompleteReasons = listOf(reason),
            actions = actions,
            dryRun = dryRun,
            outcome = outcome,
        )
    }

    /**
     * Records a cycle that failed before any valuation sample existed, so the activity feed
     * retains failed cycles as well as complete ones.
     */
    suspend fun captureFailedCycle(
        cycleId: String,
        config: AppConfig,
        reason: String,
        actions: List<String> = emptyList(),
        dryRun: Boolean,
    ): Boolean = queueCycleCapture(
        cycleId = cycleId,
        config = config,
        balances = null,
        prices = null,
        snapshot = null,
        observedAt = nowProvider(),
        sampleSelection = HistorySampleSelection.PRE_TRADE,
        additionalIncompleteReasons = listOf(reason),
        actions = actions,
        dryRun = dryRun,
        outcome = HistoryCycleOutcome.PROBLEM,
    )

    private suspend fun queueCycleCapture(
        cycleId: String,
        config: AppConfig,
        balances: ObservedBalances?,
        prices: ObservedPrices?,
        snapshot: PortfolioSnapshot?,
        observedAt: Instant,
        sampleSelection: HistorySampleSelection,
        additionalIncompleteReasons: List<String>,
        actions: List<String>,
        dryRun: Boolean,
        outcome: HistoryCycleOutcome?,
    ): Boolean {
        val credentialScope = if (config.settings.simulation) {
            null
        } else {
            currentCredentialScope().also { scope ->
                if (scope == null) {
                    lastCaptureIssue.set(ACCOUNT_SCOPE_UNAVAILABLE)
                    return false
                }
            }
        }
        val result = queue.trySend(
            PendingCycle(
                cycleId = cycleId,
                config = config,
                balances = balances,
                prices = prices,
                snapshot = snapshot,
                observedAt = observedAt,
                sampleSelection = sampleSelection,
                additionalIncompleteReasons = additionalIncompleteReasons,
                actions = actions,
                dryRun = dryRun,
                outcome = outcome,
                credentialScope = credentialScope,
            ),
        )
        if (result.isFailure) {
            lastCaptureIssue.set(QUEUE_FULL)
            log.warn("History cycle sample was skipped because the bounded writer queue is full.")
            return false
        }
        return true
    }

    fun captureIssue(): String? = lastCaptureIssue.get()

    private suspend fun persistCandidate(pending: PendingCycle) {
        try {
            val scope = if (pending.config.settings.simulation) {
                HistoryObservationValuator.simulationScope(pending.config, simulationSessionId)
            } else {
                val candidateScope = configService.withExecutionSession {
                    val identity = authenticatedAccount(pending.credentialScope)
                        ?: run {
                            verifiedAccountScope = null
                            lastCaptureIssue.set(ACCOUNT_SCOPE_UNAVAILABLE)
                            return@withExecutionSession null
                        }
                    try {
                        dataDirectoryIdentity.bindOrVerify(identity.accountIdentityDigest)
                    } catch (error: Exception) {
                        verifiedAccountScope = null
                        lastCaptureIssue.set(ACCOUNT_SCOPE_UNAVAILABLE)
                        log.warn(
                            "History cycle sample was skipped because the active account binding could not be verified.",
                        )
                        return@withExecutionSession null
                    }
                    HistoryObservationValuator.accountScope(pending.config, identity.accountIdentityDigest)
                } ?: return
                candidateScope
            }
            val balances = pending.balances
            val prices = pending.prices
            val observation = when {
                balances == null || prices == null -> null

                pending.config.settings.simulation -> {
                    pending.snapshot?.let { HistoryObservationValuator.buildSimulation(pending.cycleId, scope, it) }
                        ?: HistoryObservationValuator.buildIncompleteSimulation(
                            pending.cycleId,
                            scope,
                            pending.observedAt,
                            pending.additionalIncompleteReasons.joinToString(" ")
                                .ifBlank { "The rebalance cycle could not produce a valuation." },
                        )
                }

                else -> {
                    val balanceCapture = balances.directCapture
                    val tickerCapture = prices.directCapture
                    val missingReason = buildList {
                        if (balanceCapture == null) add("Direct balance evidence is unavailable.")
                        if (tickerCapture == null) add("Direct ticker evidence is unavailable.")
                    }
                    HistoryObservationValuator.buildDirect(
                        observationId = pending.cycleId,
                        cycleId = pending.cycleId,
                        sourceScope = scope,
                        balances = balanceCapture ?: unavailableBalanceCapture(balances.observedAt),
                        prices = tickerCapture ?: unavailableTickerCapture(prices.observedAt),
                        sampleSelection = pending.sampleSelection,
                        additionalIncompleteReasons = pending.additionalIncompleteReasons + missingReason +
                            if (pending.snapshot == null) {
                                listOf("The rebalance cycle could not produce a valuation.")
                            } else {
                                emptyList()
                            },
                        now = nowProvider(),
                    )
                }
            }
            val activity = HistoryCycleActivity(
                cycleId = pending.cycleId,
                sourceScope = scope,
                occurredAt = pending.snapshot?.timestamp ?: pending.observedAt,
                dryRun = pending.dryRun,
                actions = pending.actions,
                outcome = pending.outcome,
            )
            store.appendCycle(observation, activity)
            if (scope.mode == HistorySourceMode.ACCOUNT) {
                balances?.directCapture?.let { balanceCapture ->
                    val fullAccount = fullAccountObservation(pending, scope, balanceCapture)
                    if (fullAccount != null) {
                        store.appendObservation(fullAccount)
                        verifiedAccountScope = VerifiedAccountScope(
                            accountIdentityDigest = checkNotNull(scope.accountIdentityDigest),
                            credentialScope = checkNotNull(pending.credentialScope),
                            managedScopeFingerprint = scope.managedScopeFingerprint,
                        )
                    } else {
                        verifiedAccountScope = null
                    }
                }
            }
            if (lastCaptureIssue.get() in listOf(CAPTURE_TIMED_OUT, CAPTURE_FAILED)) {
                // Keep capture issue for diagnostics
            } else {
                lastCaptureIssue.set(null)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            lastCaptureIssue.set(SAVE_FAILED)
            log.warn("History cycle sample could not be saved.", error)
        }
    }

    private suspend fun fullAccountObservation(
        pending: PendingCycle,
        managedScope: HistorySourceScope,
        balanceCapture: DirectBalanceCapture,
    ): HistoryObservation? {
        val digest = checkNotNull(managedScope.accountIdentityDigest)
        var knownAssetClasses = emptyMap<String, String>()
        return configService.withExecutionSession {
            krakenService.withStableBackend { backend ->
                val before = captureAuthenticatedAccount(backend, pending.credentialScope)
                if (before == null || before.accountIdentityDigest != digest) {
                    lastCaptureIssue.set(CAPTURE_FAILED)
                    log.warn(
                        "History full-account evidence was skipped because account identity could not be verified.",
                    )
                    return@withStableBackend null
                }

                var captureFailed = false
                val result = try {
                    withTimeout(captureTimeoutMs) {
                        knownAssetClasses = store.queryKnownAssetClasses(digest)
                        val metadata = pending.prices?.assetMetadata ?: backend.getAssetMetadata()
                        val existingCapture = pending.prices?.directCapture
                        val missingPriceSymbols = HistoryObservationValuator.fullAccountTickerSymbols(
                            balances = balanceCapture,
                            prices = existingCapture,
                            assetMetadata = metadata,
                            authoritativeAssetClasses = knownAssetClasses,
                        )
                        val extraRequests = missingPriceSymbols.map { symbol ->
                            DirectTickerRequest(symbol = symbol, pair = Asset.tradingPair(symbol))
                        }
                        val extraCapture = if (extraRequests.isEmpty()) {
                            null
                        } else {
                            try {
                                backend.readTickerPricesWithCapture(extraRequests).capture
                                    ?: unavailableTickerCapture(nowProvider(), extraRequests)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                unavailableTickerCapture(nowProvider(), extraRequests)
                            }
                        }

                        val mergedPrices = mergeAccountTickerCaptures(
                            balanceCapture = balanceCapture,
                            existing = existingCapture,
                            extra = extraCapture,
                            requestedSymbols = missingPriceSymbols,
                        )
                        HistoryObservationValuator.buildFullAccountDirect(
                            observationId = "${pending.cycleId}:full-account-intraday-v1",
                            cycleId = pending.cycleId,
                            accountIdentityDigest = digest,
                            balances = balanceCapture,
                            prices = mergedPrices,
                            assetMetadata = metadata,
                            authoritativeAssetClasses = knownAssetClasses,
                            sampleSelection = pending.sampleSelection,
                            now = nowProvider(),
                        )
                    }
                } catch (timeout: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    captureFailed = true
                    lastCaptureIssue.set(CAPTURE_TIMED_OUT)
                    log.warn("History full-account evidence collection timed out; saving incomplete observation.")
                    HistoryObservationValuator.buildIncompleteFullAccountDirect(
                        observationId = "${pending.cycleId}:full-account-intraday-v1",
                        cycleId = pending.cycleId,
                        accountIdentityDigest = digest,
                        balances = balanceCapture,
                        reason = "Full-account evidence collection timed out.",
                        authoritativeAssetClasses = knownAssetClasses,
                        sampleSelection = pending.sampleSelection,
                        now = nowProvider(),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    captureFailed = true
                    lastCaptureIssue.set(CAPTURE_FAILED)
                    log.warn("History full-account evidence collection failed; saving incomplete observation.", error)
                    HistoryObservationValuator.buildIncompleteFullAccountDirect(
                        observationId = "${pending.cycleId}:full-account-intraday-v1",
                        cycleId = pending.cycleId,
                        accountIdentityDigest = digest,
                        balances = balanceCapture,
                        reason = "Full-account evidence collection failed: ${error.message ?: error::class.simpleName}",
                        authoritativeAssetClasses = knownAssetClasses,
                        sampleSelection = pending.sampleSelection,
                        now = nowProvider(),
                    )
                }

                val after = captureAuthenticatedAccount(backend, pending.credentialScope)
                if (after == null || after.accountIdentityDigest != digest ||
                    after.credentialScope != before.credentialScope
                ) {
                    verifiedAccountScope = null
                    lastCaptureIssue.set(CAPTURE_FAILED)
                    log.warn(
                        "History full-account evidence was discarded because account identity changed during capture.",
                    )
                    return@withStableBackend null
                }
                if (!captureFailed && lastCaptureIssue.get() in listOf(CAPTURE_TIMED_OUT, CAPTURE_FAILED)) {
                    lastCaptureIssue.set(null)
                }
                result
            }
        }
    }

    private suspend fun captureAuthenticatedAccount(
        backend: KrakenService,
        expectedCredentialScope: String?,
    ): AuthenticatedAccount? = try {
        withTimeoutOrNull(identityLookupTimeoutMs) {
            authenticatedAccount(backend, expectedCredentialScope)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        log.warn("History account identity could not be verified during full-account capture.", error)
        null
    }

    private fun mergeAccountTickerCaptures(
        balanceCapture: DirectBalanceCapture,
        existing: DirectTickerCapture?,
        extra: DirectTickerCapture?,
        requestedSymbols: List<String>,
    ): DirectTickerCapture {
        val reusable = if (existing?.resultShapeValid == true) {
            existing.marksBySymbol.filterValues { mark ->
                mark.status == DirectEvidenceStatus.VALID && mark.price?.signum() == 1
            }
        } else {
            emptyMap()
        }
        val marks = (reusable + extra?.marksBySymbol.orEmpty()).toMutableMap()
        requestedSymbols.forEach { symbol ->
            if (symbol !in marks) marks[symbol] = missingTickerMark(symbol)
        }
        val usedCaptures = buildList {
            if (reusable.isNotEmpty()) existing?.let(::add)
            if (requestedSymbols.isNotEmpty()) extra?.let(::add)
        }
        val at = balanceCapture.responseEndedAt
        return DirectTickerCapture(
            requestStartedAt = usedCaptures.minOfOrNull { it.requestStartedAt } ?: at,
            responseEndedAt = usedCaptures.maxOfOrNull { it.responseEndedAt } ?: at,
            resultShapeValid = true,
            marksBySymbol = marks,
        )
    }

    private fun unavailableTickerCapture(at: Instant, requests: List<DirectTickerRequest>) = DirectTickerCapture(
        requestStartedAt = at,
        responseEndedAt = at,
        resultShapeValid = true,
        marksBySymbol = requests.associate { request ->
            Asset.canonicalSymbol(request.symbol) to missingTickerMark(request.symbol)
        },
    )

    private fun missingTickerMark(symbol: String) = DirectTickerMark(
        symbol = Asset.canonicalSymbol(symbol),
        requestedPair = Asset.tradingPair(symbol),
        responsePair = null,
        candidateResponsePairs = emptyList(),
        rawPrice = null,
        price = null,
        status = DirectEvidenceStatus.MISSING,
    )

    private suspend fun authenticatedAccount(expectedCredentialScope: String?): AuthenticatedAccount? = try {
        withTimeout(identityLookupTimeoutMs) {
            krakenService.withStableBackend { backend ->
                authenticatedAccount(backend, expectedCredentialScope)
            }
        }
    } catch (timeout: TimeoutCancellationException) {
        null
    }

    private suspend fun authenticatedAccount(
        backend: KrakenService,
        expectedCredentialScope: String?,
    ): AuthenticatedAccount? {
        val credentialScope = backend.getFundingEvidenceScope().trim()
        if (credentialScope.isBlank() || credentialScope == "scope-unavailable" ||
            (expectedCredentialScope != null && credentialScope != expectedCredentialScope)
        ) {
            return null
        }
        val identity = backend.getAuthenticatedAccountIdentity()
            ?.filterNot(Char::isWhitespace)
            ?.uppercase(Locale.ROOT)
            ?.takeIf(String::isNotBlank)
            ?: return null
        return AuthenticatedAccount(sha256(identity), credentialScope)
    }

    private suspend fun currentCredentialScope(): String? = try {
        configService.withExecutionSession {
            krakenService.withStableBackend { backend ->
                backend.getFundingEvidenceScope().trim().takeIf { it.isNotBlank() && it != "scope-unavailable" }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        null
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class PendingCycle(
        val cycleId: String,
        val config: AppConfig,
        val balances: ObservedBalances?,
        val prices: ObservedPrices?,
        val snapshot: PortfolioSnapshot?,
        val observedAt: Instant,
        val sampleSelection: HistorySampleSelection,
        val additionalIncompleteReasons: List<String>,
        val actions: List<String>,
        val dryRun: Boolean,
        val outcome: HistoryCycleOutcome?,
        val credentialScope: String?,
    )

    private data class AuthenticatedAccount(val accountIdentityDigest: String, val credentialScope: String)

    private data class VerifiedAccountScope(
        val accountIdentityDigest: String,
        val credentialScope: String,
        val managedScopeFingerprint: String,
    )

    private companion object {
        const val QUEUE_CAPACITY = 2
        const val IDENTITY_LOOKUP_TIMEOUT_MS = 3_000L
        const val CAPTURE_TIMEOUT_MS = 15_000L
        const val ACCOUNT_SCOPE_UNAVAILABLE = "History account identity is unavailable or changed."
        const val QUEUE_FULL = "History sample skipped because the bounded writer queue is full."
        const val SAVE_FAILED = "History cycle sample could not be saved."
        const val CAPTURE_TIMED_OUT = "History full-account evidence collection timed out."
        const val CAPTURE_FAILED = "History full-account evidence collection failed."
    }
}

private fun unavailableBalanceCapture(at: Instant) = DirectBalanceCapture(
    requestStartedAt = at,
    responseEndedAt = at,
    resultShapeValid = false,
    valuesByAssetId = emptyMap(),
)

private fun unavailableTickerCapture(at: Instant) = DirectTickerCapture(
    requestStartedAt = at,
    responseEndedAt = at,
    resultShapeValid = false,
    marksBySymbol = emptyMap(),
)
