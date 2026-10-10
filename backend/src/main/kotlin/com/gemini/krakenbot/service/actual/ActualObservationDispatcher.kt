package com.gemini.krakenbot.service.actual

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.repository.ExecutionAccountBindingRepository
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.ObservedBalances
import com.gemini.krakenbot.service.ObservedPrices
import com.gemini.krakenbot.service.impl.ExecutionAccountBindingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** Best-effort bounded writer; no observation database is opened on application startup. */
class ActualObservationDispatcher(
    private val store: ActualObservationStore,
    private val accountBindingService: ExecutionAccountBindingService,
    private val bindingRepository: ExecutionAccountBindingRepository,
    private val krakenService: KrakenService,
    private val nowProvider: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(ActualObservationDispatcher::class.java)
    private val queue = Channel<PendingObservation>(capacity = QUEUE_CAPACITY)
    private val lastCaptureIssue = AtomicReference<String?>(null)
    private val lifecycleLock = Any()

    @Volatile
    private var worker: Job? = null

    fun start(scope: CoroutineScope) {
        synchronized(lifecycleLock) {
            if (worker?.isActive == true) return
            worker = scope.launch {
                for (pending in queue) {
                    persistCandidate(pending)
                }
            }
        }
    }

    /** Captures no database or identity write; account verification and persistence run off-cycle. */
    suspend fun captureAfterCycle(
        cycleId: String,
        config: AppConfig,
        balances: ObservedBalances,
        prices: ObservedPrices,
    ): Boolean {
        if (config.settings.simulation || balances.directCapture == null || prices.directCapture == null) return false
        val credentialScope = try {
            krakenService.withStableBackend { backend -> backend.getFundingEvidenceScope() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Skipping Actual observation because credential scope could not be captured: {}", e.message)
            return false
        }
        if (credentialScope.isBlank() || credentialScope == "scope-unavailable") return false
        val result = queue.trySend(
            PendingObservation(
                cycleId = cycleId,
                scopeSymbols = ActualObservationValuator.scopeSymbols(config),
                balances = balances,
                prices = prices,
                credentialScope = credentialScope,
            ),
        )
        if (result.isFailure) {
            lastCaptureIssue.set("Observation sample skipped because the bounded writer queue is full.")
            log.warn("Skipping Actual observation because the bounded writer queue is full")
            return false
        }
        return true
    }

    suspend fun readCurrent(config: AppConfig, limit: Int = DEFAULT_QUERY_LIMIT): ActualObservationPage {
        val symbols = ActualObservationValuator.scopeSymbols(config)
        val scopeFingerprint = ActualObservationValuator.scopeFingerprint(symbols)
        if (config.settings.simulation) {
            return ActualObservationPage(
                state = ActualPageState.SIMULATION,
                scopeSymbols = symbols,
                scopeFingerprint = scopeFingerprint,
                accountReference = null,
                observations = emptyList(),
                stale = false,
                lastCaptureIssue = lastCaptureIssue.get(),
            )
        }
        return try {
            val binding = bindingRepository.load()
            if (binding == null || !binding.auditVerified || !binding.accountIdentityDigest.matches(ACCOUNT_DIGEST)) {
                return ActualObservationPage(
                    state = ActualPageState.ACCOUNT_UNVERIFIED,
                    scopeSymbols = symbols,
                    scopeFingerprint = scopeFingerprint,
                    accountReference = null,
                    observations = emptyList(),
                    stale = false,
                    lastCaptureIssue = lastCaptureIssue.get(),
                )
            }
            val currentCredentialScope = krakenService.withStableBackend { it.getFundingEvidenceScope().trim() }
            if (currentCredentialScope.isBlank() || currentCredentialScope == "scope-unavailable" ||
                digest(currentCredentialScope) != binding.credentialGenerationDigest
            ) {
                return ActualObservationPage(
                    state = ActualPageState.ACCOUNT_UNVERIFIED,
                    scopeSymbols = symbols,
                    scopeFingerprint = scopeFingerprint,
                    accountReference = null,
                    observations = emptyList(),
                    stale = false,
                    lastCaptureIssue = lastCaptureIssue.get(),
                )
            }
            val observations = store.querySegment(scopeFingerprint, binding.accountIdentityDigest, limit)
            val latest = observations.lastOrNull()
            val latestComplete = observations.lastOrNull { it.status == ActualObservationStatus.COMPLETE }
            val maxAgeSeconds = maxOf(MIN_STALE_AFTER_SECONDS, config.settings.loopDelaySeconds * 3L)
            ActualObservationPage(
                state = if (latest == null) ActualPageState.NO_OBSERVATIONS else ActualPageState.READY,
                scopeSymbols = symbols,
                scopeFingerprint = scopeFingerprint,
                accountReference = binding.accountIdentityDigest.take(8),
                observations = observations,
                stale = latestComplete?.let {
                    Duration.between(it.observedAt, nowProvider()).seconds > maxAgeSeconds
                } ?: false,
                lastCaptureIssue = lastCaptureIssue.get(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Actual observation history is unavailable: {}", e.message)
            ActualObservationPage(
                state = ActualPageState.STORAGE_UNAVAILABLE,
                scopeSymbols = symbols,
                scopeFingerprint = scopeFingerprint,
                accountReference = null,
                observations = emptyList(),
                stale = false,
                lastCaptureIssue = "Observation history is temporarily unavailable.",
            )
        }
    }

    private suspend fun persistCandidate(pending: PendingObservation) {
        try {
            val accountDigest = accountBindingService.verifiedAccountIdentityDigestForObservation(
                pending.credentialScope,
            )
            if (accountDigest == null) {
                lastCaptureIssue.set("Observation skipped because a matching verified account binding is unavailable.")
                return
            }
            val directBalances = checkNotNull(pending.balances.directCapture)
            val directPrices = checkNotNull(pending.prices.directCapture)
            val observation = ActualObservationValuator.build(
                observationId = pending.cycleId,
                scopeSymbols = pending.scopeSymbols,
                accountIdentityDigest = accountDigest,
                balances = directBalances,
                prices = directPrices,
                now = nowProvider(),
            )
            store.append(observation)
            lastCaptureIssue.set(null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastCaptureIssue.set("Actual observation could not be saved.")
            log.warn("Actual observation failed outside the live-order path: {}", e.message)
        }
    }

    private data class PendingObservation(
        val cycleId: String,
        val scopeSymbols: List<String>,
        val balances: ObservedBalances,
        val prices: ObservedPrices,
        val credentialScope: String,
    )

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val QUEUE_CAPACITY = 2
        const val DEFAULT_QUERY_LIMIT = 250
        const val MIN_STALE_AFTER_SECONDS = 300L
        val ACCOUNT_DIGEST = Regex("[0-9a-f]{64}")
    }
}

enum class ActualPageState { SIMULATION, ACCOUNT_UNVERIFIED, NO_OBSERVATIONS, READY, STORAGE_UNAVAILABLE }

data class ActualObservationPage(
    val state: ActualPageState,
    val scopeSymbols: List<String>,
    val scopeFingerprint: String,
    val accountReference: String?,
    val observations: List<ActualObservation>,
    val stale: Boolean,
    val lastCaptureIssue: String?,
)
