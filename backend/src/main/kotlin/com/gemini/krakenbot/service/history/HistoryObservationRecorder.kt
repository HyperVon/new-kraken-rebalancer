package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.DataDirectoryIdentity
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.DirectBalanceCapture
import com.gemini.krakenbot.service.DirectTickerCapture
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.ObservedBalances
import com.gemini.krakenbot.service.ObservedPrices
import com.gemini.krakenbot.service.withExecutionSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
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
        )
    }

    private suspend fun queueCycleCapture(
        cycleId: String,
        config: AppConfig,
        balances: ObservedBalances,
        prices: ObservedPrices,
        snapshot: PortfolioSnapshot?,
        observedAt: Instant,
        sampleSelection: HistorySampleSelection,
        additionalIncompleteReasons: List<String>,
        actions: List<String>,
        dryRun: Boolean,
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
            val observation = if (pending.config.settings.simulation) {
                pending.snapshot?.let { HistoryObservationValuator.buildSimulation(pending.cycleId, scope, it) }
                    ?: HistoryObservationValuator.buildIncompleteSimulation(
                        pending.cycleId,
                        scope,
                        pending.observedAt,
                        pending.additionalIncompleteReasons.joinToString(" ")
                            .ifBlank { "The rebalance cycle could not produce a valuation." },
                    )
            } else {
                val balanceCapture = pending.balances.directCapture
                val tickerCapture = pending.prices.directCapture
                val missingReason = buildList {
                    if (balanceCapture == null) add("Direct balance evidence is unavailable.")
                    if (tickerCapture == null) add("Direct ticker evidence is unavailable.")
                }
                HistoryObservationValuator.buildDirect(
                    observationId = pending.cycleId,
                    cycleId = pending.cycleId,
                    sourceScope = scope,
                    balances = balanceCapture ?: unavailableBalanceCapture(pending.balances.observedAt),
                    prices = tickerCapture ?: unavailableTickerCapture(pending.prices.observedAt),
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
            val activity = HistoryCycleActivity(
                cycleId = pending.cycleId,
                sourceScope = scope,
                occurredAt = pending.snapshot?.timestamp ?: pending.observedAt,
                dryRun = pending.dryRun,
                actions = pending.actions,
            )
            store.appendCycle(observation, activity)
            if (scope.mode == HistorySourceMode.ACCOUNT) {
                verifiedAccountScope = VerifiedAccountScope(
                    accountIdentityDigest = checkNotNull(scope.accountIdentityDigest),
                    credentialScope = checkNotNull(pending.credentialScope),
                    managedScopeFingerprint = scope.managedScopeFingerprint,
                )
            }
            lastCaptureIssue.set(null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            lastCaptureIssue.set(SAVE_FAILED)
            log.warn("History cycle sample could not be saved.", error)
        }
    }

    private suspend fun authenticatedAccount(expectedCredentialScope: String?): AuthenticatedAccount? = try {
        withTimeout(identityLookupTimeoutMs) {
            krakenService.withStableBackend { backend ->
                val credentialScope = backend.getFundingEvidenceScope().trim()
                if (credentialScope.isBlank() || credentialScope == "scope-unavailable" ||
                    (expectedCredentialScope != null && credentialScope != expectedCredentialScope)
                ) {
                    return@withStableBackend null
                }
                val identity = backend.getAuthenticatedAccountIdentity()
                    ?.filterNot(Char::isWhitespace)
                    ?.uppercase(Locale.ROOT)
                    ?.takeIf(String::isNotBlank)
                    ?: return@withStableBackend null
                AuthenticatedAccount(sha256(identity), credentialScope)
            }
        }
    } catch (timeout: TimeoutCancellationException) {
        null
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
        val balances: ObservedBalances,
        val prices: ObservedPrices,
        val snapshot: PortfolioSnapshot?,
        val observedAt: Instant,
        val sampleSelection: HistorySampleSelection,
        val additionalIncompleteReasons: List<String>,
        val actions: List<String>,
        val dryRun: Boolean,
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
        const val ACCOUNT_SCOPE_UNAVAILABLE = "History account identity is unavailable or changed."
        const val QUEUE_FULL = "History sample skipped because the bounded writer queue is full."
        const val SAVE_FAILED = "History cycle sample could not be saved."
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
