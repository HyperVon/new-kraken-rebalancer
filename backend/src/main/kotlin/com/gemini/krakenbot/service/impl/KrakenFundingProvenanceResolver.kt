package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.model.FundingEvidence
import com.gemini.krakenbot.model.FundingProvenanceFailure
import com.gemini.krakenbot.model.FundingProvenanceFailureReason
import com.gemini.krakenbot.model.FundingProvenanceResolver
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.SimpleFundingProvenanceResolver
import com.gemini.krakenbot.service.KrakenService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Production funding resolver backed by one batched status fetch per funding
 * family. It deliberately does not infer external ownership from a ledger
 * row's shape: when Kraken status or optional internal-transfer evidence is
 * unavailable, the resolver returns [FundingEvidence.UNRESOLVED].
 *
 * Limitation note: Funding provenance currently uses legacy DepositStatus/WithdrawStatus APIs.
 * Migrate to List Funding Deposits / List Funding Withdrawals in a follow-up.
 */
class KrakenFundingProvenanceResolver(
    private val krakenService: KrakenService,
    private val nowProvider: () -> Instant = Instant::now,
) : FundingProvenanceResolver {
    private val log = LoggerFactory.getLogger(KrakenFundingProvenanceResolver::class.java)
    private val prepareMutex = Mutex()

    @Volatile
    private var prepared: PreparedEvidence? = null

    override fun resolve(event: LedgerEvent): FundingEvidence = FundingEvidence.UNRESOLVED
    override fun isCardFunding(event: LedgerEvent): Boolean = prepared?.resolver?.isCardFunding(event) ?: false

    /**
     * Returns an immutable resolver snapshot for this batch. The production
     * object itself deliberately remains unresolved: retaining a mutable
     * process-wide current resolver would let concurrent ATH/history calls
     * classify one batch with another batch's evidence.
     */
    override suspend fun prepare(events: Collection<LedgerEvent>): FundingProvenanceResolver {
        val fundingEvents = events.filter { it.type.lowercase() in SUPPORTED_TYPES }
        if (fundingEvents.isEmpty()) return this

        val requiredFamilies = fundingEvents.mapTo(mutableSetOf()) { it.type.lowercase() }
        val requestedRange = FundingRange.from(fundingEvents)
        val requestedEvents = fundingEvents.toSet()
        return krakenService.withStableBackend { backend ->
            prepareForBackend(backend, requestedRange, requiredFamilies, requestedEvents)
        }
    }

    private suspend fun prepareForBackend(
        backend: KrakenService,
        requestedRange: FundingRange,
        requiredFamilies: Set<String>,
        requestedEvents: Set<LedgerEvent>,
    ): FundingProvenanceResolver {
        val evidenceScope = backend.getFundingEvidenceScope()
        val cached = prepared
        if (cached != null && cached.covers(
                requestedBackend = backend,
                requestedScope = evidenceScope,
                requestedRange = requestedRange,
                requestedFamilies = requiredFamilies,
                requestedEvents = requestedEvents,
                now = nowProvider(),
            )
        ) {
            return cached.resolver
        }

        return prepareMutex.withLock {
            val lockedEvidenceScope = backend.getFundingEvidenceScope()
            val lockedCached = prepared
            if (lockedCached != null && lockedCached.covers(
                    requestedBackend = backend,
                    requestedScope = lockedEvidenceScope,
                    requestedRange = requestedRange,
                    requestedFamilies = requiredFamilies,
                    requestedEvents = requestedEvents,
                    now = nowProvider(),
                )
            ) {
                return@withLock lockedCached.resolver
            }

            try {
                // All three calls use the backend selected by one stable
                // DynamicKrakenService pin. A mode flip cannot mix live and
                // simulated evidence within this batch.
                // TODO: Funding provenance currently uses legacy DepositStatus/WithdrawStatus APIs.
                // Migrate to List Funding Deposits / List Funding Withdrawals in a follow-up.
                val deposits = if (KrakenApiConstants.LEDGER_TYPE_DEPOSIT in requiredFamilies) {
                    backend.getDepositStatus(requestedRange.startSec, requestedRange.endSec)
                } else {
                    emptyList()
                }
                val withdrawals = if (KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL in requiredFamilies) {
                    backend.getWithdrawStatus(requestedRange.startSec, requestedRange.endSec)
                } else {
                    emptyList()
                }
                // Deposit/withdrawal ledger rows can represent Spot/Futures
                // movement too. Fetch optional internal evidence for every
                // funding family, not only coarse `transfer` rows, so a
                // backend that can query Futures history can disambiguate
                // those rows before the external candidate is accepted.
                val internalTransfers = if (requiredFamilies.isNotEmpty()) {
                    backend.getInternalTransfers(requestedRange.startSec, requestedRange.endSec)
                } else {
                    emptyList()
                }
                val resolver = SimpleFundingProvenanceResolver(
                    deposits = deposits,
                    withdrawals = withdrawals,
                    internalTransfers = internalTransfers,
                )
                prepared = PreparedEvidence(
                    backend = backend,
                    evidenceScope = lockedEvidenceScope,
                    range = requestedRange,
                    families = requiredFamilies,
                    events = requestedEvents,
                    preparedAt = nowProvider(),
                    resolver = resolver,
                )
                resolver
            } catch (e: CancellationException) {
                throw e
            } catch (e: KrakenApiPermissionDeniedException) {
                prepared = null
                val message =
                    "Kraken denied ${e.endpoint}; enable Funds: Query for DepositStatus and " +
                        "Funds: Withdraw or Data: Query ledger entries for WithdrawStatus."
                log.error(message, e)
                FundingProvenanceResolver.unavailable(
                    FundingProvenanceFailure(
                        reason = FundingProvenanceFailureReason.PERMISSION_DENIED,
                        message = message,
                    ),
                )
            } catch (e: Exception) {
                // Do not retain an incomplete or stale batch after a fetch
                // failure. The caller receives unresolved evidence with the
                // operational failure attached, and a later operation may
                // retry the authoritative source.
                prepared = null
                log.warn(
                    "Funding provenance fetch failed; funding rows remain unresolved ({})",
                    e::class.simpleName ?: "unknown",
                )
                FundingProvenanceResolver.unavailable(
                    FundingProvenanceFailure(
                        reason = FundingProvenanceFailureReason.REQUEST_FAILED,
                        message = "Funding provenance request failed: ${e.message ?: e::class.simpleName}",
                    ),
                )
            }
        }
    }

    private data class PreparedEvidence(
        @JvmField val backend: KrakenService,
        @JvmField val evidenceScope: String,
        @JvmField val range: FundingRange,
        @JvmField val families: Set<String>,
        @JvmField val events: Set<LedgerEvent>,
        @JvmField val preparedAt: Instant,
        @JvmField val resolver: FundingProvenanceResolver,
    ) {
        fun covers(
            requestedBackend: KrakenService,
            requestedScope: String,
            requestedRange: FundingRange,
            requestedFamilies: Set<String>,
            requestedEvents: Set<LedgerEvent>,
            now: Instant,
        ): Boolean = backend === requestedBackend &&
            evidenceScope == requestedScope &&
            preparedAt.plusSeconds(CACHE_TTL_SECONDS).isAfter(now) &&
            range.startSec <= requestedRange.startSec &&
            range.endSec >= requestedRange.endSec &&
            families.containsAll(requestedFamilies) &&
            events.containsAll(requestedEvents)
    }

    private data class FundingRange(val startSec: Long, val endSec: Long) {
        companion object {
            fun from(events: Collection<LedgerEvent>): FundingRange {
                val earliest = events.minOf { it.time }
                val latest = events.maxOf { it.time }
                return FundingRange(
                    startSec = earliest.minusSeconds(CORRELATION_WINDOW_SECONDS).epochSecond.coerceAtLeast(0L),
                    endSec = inclusiveEpochSecondCeiling(latest.plusSeconds(CORRELATION_WINDOW_SECONDS)),
                )
            }

            private fun inclusiveEpochSecondCeiling(instant: Instant): Long =
                instant.epochSecond + if (instant.nano == 0) 0 else 1
        }
    }

    private companion object {
        const val CORRELATION_WINDOW_SECONDS = 180L
        const val CACHE_TTL_SECONDS = 60L

        @JvmField val SUPPORTED_TYPES = setOf(
            KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
            KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
            KrakenApiConstants.LEDGER_TYPE_TRANSFER,
        )
    }
}
