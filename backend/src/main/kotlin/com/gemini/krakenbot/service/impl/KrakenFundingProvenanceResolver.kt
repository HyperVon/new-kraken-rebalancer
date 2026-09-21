package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.model.FundingEvidence
import com.gemini.krakenbot.model.FundingProvenanceFailure
import com.gemini.krakenbot.model.FundingProvenanceFailureReason
import com.gemini.krakenbot.model.FundingProvenanceResolver
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.SimpleFundingProvenanceResolver
import com.gemini.krakenbot.repository.FundingEvidenceIdentityRecord
import com.gemini.krakenbot.repository.FundingEvidenceIdentityStore
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
 * Funding (Beta) List Funding Deposits / List Funding Withdrawals are the primary
 * evidence source; the deprecated DepositStatus/WithdrawStatus endpoints are only
 * consulted to enrich records whose modern funding-method metadata is unavailable.
 */
class KrakenFundingProvenanceResolver(
    private val krakenService: KrakenService,
    private val nowProvider: () -> Instant = Instant::now,
    private val durableIdentityStore: FundingEvidenceIdentityStore? = null,
) : FundingProvenanceResolver {
    private val log = LoggerFactory.getLogger(KrakenFundingProvenanceResolver::class.java)
    private val prepareMutex = Mutex()

    @Volatile
    private var prepared: PreparedEvidence? = null

    @Volatile
    private var lastPreparationFailure: FundingProvenanceFailure? = null

    override fun resolve(event: LedgerEvent): FundingEvidence = FundingEvidence.UNRESOLVED
    override fun isCardFunding(event: LedgerEvent): Boolean = prepared?.resolver?.isCardFunding(event) ?: false
    override fun explain(event: LedgerEvent): String? = prepared?.resolver?.explain(event)

    override val preparationFailure: FundingProvenanceFailure?
        get() = lastPreparationFailure

    /**
     * The prepared batch's content fingerprint while it is fresh; after the short TTL (or a
     * process restart, which loses the in-memory batch) the DURABLE identity of the last
     * successfully prepared batch keeps certifying cache identity so persisted comparisons
     * stay reusable. Provenance classification is never served from the durable record: it
     * only exists so an unchanged evidence content can be recognized, not trusted for
     * resolution — resolution still requires a fresh in-memory prepare.
     */
    override val evidenceFingerprint: String?
        get() {
            val current = prepared
            if (current != null) {
                return current
                    .takeIf { it.preparedAt.plusSeconds(CACHE_TTL_SECONDS).isAfter(nowProvider()) }
                    ?.resolver
                    ?.evidenceFingerprint
                    ?: durableFingerprint()
            }
            return durableFingerprint() ?: UNPREPARED_EVIDENCE_FINGERPRINT
        }

    private fun durableFingerprint(): String? = durableIdentityStore?.load()?.fingerprint

    /**
     * Returns an immutable resolver snapshot for this batch. The production
     * object itself deliberately remains unresolved: retaining a mutable
     * process-wide current resolver would let concurrent ATH/history calls
     * classify one batch with another batch's evidence.
     */
    override suspend fun prepare(events: Collection<LedgerEvent>): FundingProvenanceResolver {
        val fundingEvents = events.filter { it.type.lowercase() in SUPPORTED_TYPES }
        if (fundingEvents.isEmpty()) {
            // No funding rows to prepare: this window needs no provenance evidence, so an
            // earlier unrelated preparation failure must not keep degrading its result.
            lastPreparationFailure = null
            return this
        }

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
                // [prepare] returns this path only for at least one supported funding family.
                val internalTransfers = backend.getInternalTransfers(
                    requestedRange.startSec,
                    requestedRange.endSec,
                )
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
                lastPreparationFailure = null
                persistDurableIdentity(resolver, lockedEvidenceScope, requestedRange, requiredFamilies)
                resolver
            } catch (e: CancellationException) {
                throw e
            } catch (e: KrakenApiPermissionDeniedException) {
                prepared = null
                val message =
                    "Kraken denied ${e.endpoint}; funding provenance requires the Funds: Query permission."
                log.error(message, e)
                FundingProvenanceFailure(
                    reason = FundingProvenanceFailureReason.PERMISSION_DENIED,
                    message = message,
                ).also { failure ->
                    lastPreparationFailure = failure
                }.let { failure ->
                    FundingProvenanceResolver.unavailable(failure)
                }
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
                FundingProvenanceFailure(
                    reason = FundingProvenanceFailureReason.REQUEST_FAILED,
                    message = "Funding provenance request failed: ${e.message ?: e::class.simpleName}",
                ).also { failure ->
                    lastPreparationFailure = failure
                }.let { failure ->
                    FundingProvenanceResolver.unavailable(failure)
                }
            }
        }
    }

    /**
     * Records the prepared batch's durable cache identity: content fingerprint plus bounded
     * scope metadata (evidence scope, funding families, queried epoch range) — never raw
     * payloads, credentials, or signatures. The in-memory TTL and the durable record serve
     * different purposes: TTL freshness gates classification reuse in this process, while
     * the durable record lets later requests and restarted processes recognize that the
     * funding evidence CONTENT backing an already-persisted comparison has not changed.
     */
    private fun persistDurableIdentity(
        resolver: FundingProvenanceResolver,
        evidenceScope: String,
        range: FundingRange,
        families: Set<String>,
    ) {
        val store = durableIdentityStore ?: return
        val fingerprint = resolver.evidenceFingerprint ?: return
        val identity = "$evidenceScope|${families.sorted().joinToString(",")}|${range.startSec}|${range.endSec}"
        val record = FundingEvidenceIdentityRecord(
            fingerprint = fingerprint,
            identity = identity,
            updatedAtEpochSeconds = nowProvider().epochSecond,
        )
        try {
            store.save(record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Unable to persist durable funding evidence identity: {}", e.message)
        }
        // Verify regardless of the save outcome: a save that reported success but did not
        // persist (or threw) must not leave the PREVIOUS batch's fingerprint certifying cache
        // reuse while this process holds a newer one. Clear it so later requests miss the
        // cache (authoritative replay) instead of reusing a comparison under an outdated
        // identity.
        try {
            if (store.load()?.fingerprint != fingerprint) {
                store.clear()
                log.warn("Durable funding evidence identity could not be verified after save; cleared")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Unable to verify durable funding evidence identity: {}", e.message)
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
        const val UNPREPARED_EVIDENCE_FINGERPRINT = "kraken-funding-unprepared"

        @JvmField val SUPPORTED_TYPES = setOf(
            KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
            KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
            KrakenApiConstants.LEDGER_TYPE_TRANSFER,
        )
    }
}
