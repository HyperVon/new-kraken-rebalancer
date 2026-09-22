package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonProposalStatus
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.FundingProvenanceResolver
import com.gemini.krakenbot.model.HistoryStats
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.RebalancerComparison
import com.gemini.krakenbot.model.RewardsOverTime
import com.gemini.krakenbot.model.RewardsOverTimePoint
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.hasValidEconomicFields
import com.gemini.krakenbot.model.isHistoricallyReplayable
import com.gemini.krakenbot.repository.ConsumedOhlcDependency
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.OrderIntentRepository
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.RebalancerComparisonCacheRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.repository.downsampleSnapshots
import com.gemini.krakenbot.service.AutomaticBaselineStatus
import com.gemini.krakenbot.service.ComparisonStartProposal
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.SettingsComparisonStatus
import com.gemini.krakenbot.util.PrecisionConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Comparator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private data class HistoricalPriceKey(val symbol: String, val time: Instant)

/**
 * One comparison asks for the same asset/time from validation, event construction, and valuation.
 * Share those exact resolutions, including null results, while allowing a transient source error
 * to be retried by a later comparison.
 */
private class HistoricalPriceMemo {
    private data class ResolvedPrice(val value: BigDecimal?)

    private val values = ConcurrentHashMap<HistoricalPriceKey, ResolvedPrice>()
    private val inFlight = ConcurrentHashMap<HistoricalPriceKey, CompletableDeferred<ResolvedPrice>>()

    suspend fun get(key: HistoricalPriceKey, loader: suspend () -> BigDecimal?): BigDecimal? {
        values[key]?.let { return it.value }
        val candidate = CompletableDeferred<ResolvedPrice>()
        val existing = inFlight.putIfAbsent(key, candidate)
        if (existing != null) return existing.await().value

        try {
            val resolved = ResolvedPrice(loader())
            values[key] = resolved
            candidate.complete(resolved)
            return resolved.value
        } catch (e: CancellationException) {
            candidate.completeExceptionally(e)
            throw e
        } catch (e: Throwable) {
            candidate.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(key, candidate)
        }
    }
}

class TradeHistoryQueryService(
    private val repository: TradeRepository,
    private val portfolioStatsRepository: PortfolioStatsRepository,
    private val ledgerRepository: LedgerRepository,
    private val orderIntentRepository: OrderIntentRepository? = null,
    private val inceptionDiscoveryService: InceptionDiscoveryService? = null,
    private val fundingProvenanceResolver: FundingProvenanceResolver = FundingProvenanceResolver.NONE,
    private val nowProvider: () -> Instant = Instant::now,
    private val krakenService: KrakenService? = null,
    private val historicalOhlcCache: HistoricalOhlcCache? = null,
    private val comparisonCacheRepository: RebalancerComparisonCacheRepository? = null,
    /** Lower bound of retained history scanned for a passive benchmark anchor. */
    private val benchmarkHistoryFloor: Instant = Instant.EPOCH,
    /** Application-lifetime scope used for the bounded background proposal continuation. */
    private val applicationScope: CoroutineScope? = null,
    /** Current allocation membership; historical wallet-only assets are not live targets. */
    private val configService: ConfigService? = null,
    private val historyEvidenceCoordinator: HistoryEvidenceCoordinator = HistoryEvidenceCoordinator(),
) {
    private val proposalSearchMutex = Mutex()

    private val log = LoggerFactory.getLogger(TradeHistoryQueryService::class.java)

    private val proposalContinuationActive = AtomicBoolean(false)

    /**
     * Memoized consumed-evidence digest keyed by inception, certified horizon, and the
     * global evidence revision. The revision changes on every evidence write; the digest
     * itself only changes when a row at or before the certified horizon changes, so a
     * live-tail write (revision bump beyond the horizon) costs one rehash and no cache
     * invalidation.
     */
    private val consumedEvidenceMemo = AtomicReference<ConsumedEvidenceMemo?>(null)

    private data class ConsumedEvidenceMemo(
        val inceptionMillis: Long?,
        val horizonMillis: Long,
        val revision: String,
        val digest: String,
    )

    private val comparisonInFlight =
        ConcurrentHashMap<String, CompletableDeferred<RebalancerComparison>>()

    private fun startOrJoinComparisonFlight(
        flightKey: String,
    ): Pair<CompletableDeferred<RebalancerComparison>, Boolean> {
        var created = false
        val deferred = comparisonInFlight.compute(flightKey) { _, existing ->
            if (existing == null) {
                created = true
                CompletableDeferred()
            } else {
                existing
            }
        }!!
        return deferred to created
    }

    private data class ProposalCursor(val epochMillis: Long, val ordinal: Int) {
        fun encode(): String = "$epochMillis:$ordinal"

        companion object {
            fun parse(raw: String): ProposalCursor? {
                val parts = raw.split(':')
                return when (parts.size) {
                    1 -> parts[0].toLongOrNull()?.let { ProposalCursor(it, 0) }

                    2 -> {
                        val epochMillis = parts[0].toLongOrNull()
                        val ordinal = parts[1].toIntOrNull()
                        if (epochMillis != null && ordinal != null && ordinal >= 0) {
                            ProposalCursor(epochMillis, ordinal)
                        } else {
                            null
                        }
                    }

                    else -> null
                }
            }
        }
    }

    suspend fun getHistory(): List<PortfolioSnapshot> = repository.load()

    suspend fun getLatestSnapshot(): PortfolioSnapshot? = repository.getLatestSnapshot()

    suspend fun getSnapshotsInRange(from: Instant, to: Instant): List<PortfolioSnapshot> =
        excludeIdentitySnapshots(repository.getAllSnapshotsInRange(from, to))
            .collapseDuplicateInstants()
            .downsampleSnapshots()

    /**
     * Full-fidelity accounting input: identity-anchor collisions excluded, otherwise the complete
     * retained series from the effective baseline through the requested end. Reconciliation
     * correctness must not depend on the display window, chart sampling, or duplicate-instant
     * collapsing, so intermediate states remain evidence. Presentation filtering and sampling
     * happen only after the calculator succeeds.
     */
    private suspend fun loadComparisonSnapshots(accountingFrom: Instant, to: Instant): List<PortfolioSnapshot> =
        excludeIdentitySnapshots(repository.getAllSnapshotsInRange(accountingFrom, to))
            .sortedBy { it.timestamp }

    /**
     * Reconstruction replays events newest-first and persists a row per replayed event, so an
     * instant that spans several events keeps several cumulative rows. Rows arrive ordered by
     * timestamp ascending and id descending, which places intra-instant states in forward
     * chronological order. The recorded series exposes the final state after all events of each instant.
     */
    private fun List<PortfolioSnapshot>.collapseDuplicateInstants(): List<PortfolioSnapshot> =
        asReversed().distinctBy { it.timestamp }.asReversed()

    /**
     * Identity anchors survive series rewrites, so a rewrite can place a reconstructed snapshot
     * at the anchor's instant. Only that collision is hidden: the reconstructed snapshot is the
     * recorded series, while an anchor without a same-instant counterpart (for example the
     * approved-start baseline before reconstruction) remains part of the series. The collision
     * check reads the stored instant directly because range sampling can drop the twin from the
     * returned page while keeping the anchor as the range endpoint.
     */
    private suspend fun excludeIdentitySnapshots(snapshots: List<PortfolioSnapshot>): List<PortfolioSnapshot> {
        val replacedAnchors = repository.snapshotIdentityAnchors().filter { anchor ->
            repository.getSnapshotsInRange(anchor.timestamp, anchor.timestamp).any { it != anchor }
        }
        return snapshots.filterNot { it in replacedAnchors }
    }

    suspend fun getTradesInRange(from: Instant, to: Instant): List<TradeRecord> = repository.getTradesInRange(from, to)

    suspend fun getLedgersInRange(from: Instant, to: Instant): List<LedgerEvent> =
        ledgerRepository.getLedgersInRange(from, to)

    companion object {
        /**
         * Contribution-time prices may look back across the retained reconstruction grid, but
         * only a small forward execution skew is admitted by [HistoricalPriceResolver].
         */
        const val CONTRIBUTION_PRICE_LOOKUP_SECONDS = 21600L
        const val CONTRIBUTION_PRICE_FUTURE_SKEW_SECONDS =
            HistoricalPriceResolver.MAX_EVENT_TIME_TRADE_OR_SNAPSHOT_AGE_SECONDS

        /**
         * Smallest non-cash holding that can express a benchmark investment thesis, mirroring
         * the shipped `Settings.minimumOrderSizeUSD` template default: anything smaller is
         * execution dust the strategy itself could never place, not an allocation decision.
         */
        val INVESTED_THESIS_MINIMUM_USD = BigDecimal("5.00")

        /**
         * Comparison-start reasons where an accepted later anchor can genuinely
         * fix the failure: the baseline itself is unverifiable or an
         * ownership/reconciliation conflict sits between the requested start
         * and retained history. Pending recovery is deliberately excluded —
         * no proposal while reconstruction is still making progress.
         */
        private val PROPOSAL_ELIGIBLE_REASONS =
            setOf(
                ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                ComparisonUnavailableReason.INCEPTION_BASELINE_UNAVAILABLE,
                ComparisonUnavailableReason.INCEPTION_SNAPSHOT_PRUNED,
                ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE,
            )

        /**
         * Reasons whose unavailable payload pins the exact evidence event a comparison failed
         * at. Any candidate anchored before that instant places the same event inside its own
         * comparison window and must fail identically, so the bounded proposal scan skips
         * straight to the first candidate at or after the event instead of paying a full
         * reconciliation trial for every one of them.
         */
        private val INTRINSIC_EVENT_REASONS =
            setOf(
                ComparisonUnavailableReason.UNSUPPORTED_TRADE,
                ComparisonUnavailableReason.UNSUPPORTED_LEDGER_TYPE,
                ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE,
            )

        /** Bounded proposal scan: at most this many full reconciliation trials per query. */
        private const val PROPOSAL_MAX_TRIALS = 8

        /** Bump when the serialized comparison payload or its cache invalidation contract changes. */
        private const val COMPARISON_CACHE_VERSION = "2"

        /** Background continuation pacing and lifetime budget for an incomplete scan. */
        private const val PROPOSAL_CONTINUATION_MAX_CYCLES = 24
        private const val PROPOSAL_CONTINUATION_PACING_MS = 2_000L
        private const val PROPOSAL_CONTINUATION_MAX_PACING_MS = 30_000L
        private const val PROPOSAL_SEARCH_VERSION = "10"
        private const val PROPOSAL_CURSOR_EXHAUSTED = "EXHAUSTED"

        /**
         * Contract version of the durable automatic Buy & Hold baseline verification record.
         * Increment whenever semantics that determine automatic-baseline validity change; a
         * stored record written under any other version is rejected and recomputed, never
         * interpreted. Independent of [PROPOSAL_SEARCH_VERSION] — the later-start proposal
         * scan and the baseline proof are separate state machines.
         */
        private const val AUTOMATIC_BASELINE_VERIFICATION_VERSION = "1"
        private const val AUTOMATIC_BASELINE_STATUS_VERIFIED = "VERIFIED"
        private const val AUTOMATIC_BASELINE_STATUS_INVALIDATED = "INVALIDATED"

        /**
         * Candidate-local failure reasons whose outcome depends on the volume or availability of
         * evidence after the candidate's own window, so a later append can flip them to AVAILABLE.
         * A scan that exhausted its segment while its frontier failed for one of these reasons must
         * rewind and re-evaluate that run when the evidence horizon advances. Event-origin and
         * baseline/ownership reasons are excluded: they are pinned by evidence inside the
         * candidate's own window (intrinsics) or on its left (baseline), so appends to the tail
         * cannot cure them and rescanning them would only burn trials.
         */
        private val APPEND_SENSITIVE_FRONTIER_REASONS =
            setOf(
                ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS,
                ComparisonUnavailableReason.MISSING_PRICE,
                ComparisonUnavailableReason.HISTORICAL_PRICE_SOURCE_ERROR,
            )

        /**
         * Append-sensitive frontier reasons that external historical price evidence can also
         * cure: an OHLC backfill, correction, or provider recovery supplies the missing price
         * with NO new snapshot/trade/ledger row, so the evidence digest and the horizon never
         * witness the change. A stored EXHAUSTED carrying one of these is re-probed with a
         * single bounded frontier-candidate trial per call. The remaining append-sensitive
         * reason (INSUFFICIENT_SNAPSHOTS) can only be cured by new rows, which advance the
         * horizon and reopen the scan, so it stays strictly terminal.
         */
        private val PRICE_SENSITIVE_FRONTIER_REASONS =
            setOf(
                ComparisonUnavailableReason.MISSING_PRICE,
                ComparisonUnavailableReason.HISTORICAL_PRICE_SOURCE_ERROR,
            )

        /**
         * The normal snapshot loop is substantially more frequent than daily. A gap this large
         * in historical coverage is evidence that retained history lost an interval or era;
         * proposal search must not infer over it.
         */
        private const val MAX_COVERAGE_GAP_SECONDS = 86_400L
        private val OPEN_ENDED_RANGE_END = Instant.ofEpochMilli(Long.MAX_VALUE)
    }

    suspend fun getHistoryStats(): HistoryStats = getHistoryStats(Instant.EPOCH, nowProvider())

    suspend fun getRebalancerComparison(from: Instant, to: Instant): RebalancerComparison =
        historyEvidenceCoordinator.withLock {
            val inceptionResolution = inceptionDiscoveryService?.resolveInceptionUnderEvidenceLock()
            getRebalancerComparisonLocked(from, to, inceptionResolution)
        }

    private suspend fun getRebalancerComparisonLocked(
        from: Instant,
        to: Instant,
        inceptionResolution: InceptionResolution?,
    ): RebalancerComparison {
        // The requested interval is a display range, not an accounting boundary. Load from the
        // known effective baseline when it predates the display start so every intermediate
        // checkpoint needed to prove the B&H state at the first returned point remains available.
        // When no inception service is configured, there is no proven earlier baseline to widen
        // to and the existing range-local behavior remains the safest contract.
        val accountingFrom = maxOf(
            benchmarkHistoryFloor,
            minOf(from, inceptionResolution?.inceptionTime ?: from),
        )
        val snapshots = loadComparisonSnapshots(accountingFrom, to)
        if (snapshots.size < 2) {
            return RebalancerComparisonCalculator.calculate(snapshots, emptyList())
        }
        val orderedSnapshots = snapshots.sortedBy { it.timestamp }
        // Stale reconstructed history must never appear VERIFIED: if the reconstruction contract
        // was invalidated (unknown/new historical event cleared the version marker) the retained
        // reconstructed snapshots in [START, THROUGH] are unavailable until rebuilt. Live snapshots
        // after THROUGH do not depend on reconstruction metadata and remain usable. Rows before the
        // first recorded row can never become the comparison baseline, so an invalidated
        // reconstruction behind them cannot hide the recorded evidence that follows.
        if (overlapsStaleReconstruction(orderedSnapshots.participatingFromFirstRecordedRow())) {
            return RebalancerComparisonCalculator.calculate(
                snapshots = orderedSnapshots,
                trades = emptyList(),
                rewards = emptyList(),
                knownInceptionTime = orderedSnapshots.first().timestamp,
                inceptionUnavailableReason = ComparisonUnavailableReason.HISTORICAL_COVERAGE_GAP,
            )
        }
        // History comparison operates against stable, coverage-confirmed history: a newest
        // live snapshot whose balance observation lies beyond certified trade/ledger coverage
        // is unstable-tail evidence and must not fail the evaluation with an unexplained balance
        // change or mask into a historical-coverage gap.
        val stableThrough = latestConfirmedEconomicCoverage(
            requiredStart = requiredCoverageStart(
                baselineStart = effectiveReplayBaselineStart(
                    inceptionResolution,
                    orderedSnapshots.first(),
                    accountingFrom,
                    to,
                ),
                firstSnapshot = orderedSnapshots.first(),
            ),
        )
        if (stableThrough == null) {
            // Fail closed: without certified trade AND ledger horizons, no live-tail evidence
            // may be evaluated at all (mirrors the Settings defer behavior). Never substitute
            // a watermark, wall clock, or latest snapshot time for certified coverage.
            val ledgerCoverage = ledgerRepository
                .getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC)
            val tradeCoverage = repository
                .getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC)
            log.info(
                "History comparison unavailable; reason=HISTORY_COVERAGE_STALE stableThrough=null " +
                    "ledgerCoverage={} tradeCoverage={}",
                ledgerCoverage ?: "missing",
                tradeCoverage ?: "missing",
            )
            return RebalancerComparison(
                availability = ComparisonAvailability.UNAVAILABLE,
                confidence = null,
                baselineTimestamp = inceptionResolution?.inceptionTime ?: orderedSnapshots.first().timestamp,
                points = emptyList(),
                latestDifferenceUSD = null,
                latestDifferencePercent = null,
                unavailableReason = ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS,
                unavailableAt = orderedSnapshots.firstOrNull()?.timestamp,
            )
        }
        val firstUncoveredIndex =
            orderedSnapshots.indexOfFirst { !isSnapshotCoveredByHistory(it, stableThrough) }
        val evaluationSnapshots = if (firstUncoveredIndex >= 0) {
            val reentry = orderedSnapshots.drop(firstUncoveredIndex + 1).indexOfFirst {
                isSnapshotCoveredByHistory(it, stableThrough)
            }
            if (reentry >= 0) {
                log.warn(
                    "History comparison deferred; reason=HISTORY_COVERAGE_NON_MONOTONIC " +
                        "firstUncoveredSnapshot={} laterCoveredSnapshot={}",
                    orderedSnapshots[firstUncoveredIndex].timestamp,
                    orderedSnapshots[firstUncoveredIndex + 1 + reentry].timestamp,
                )
                return RebalancerComparison(
                    availability = ComparisonAvailability.UNAVAILABLE,
                    confidence = null,
                    baselineTimestamp = inceptionResolution?.inceptionTime ?: orderedSnapshots.first().timestamp,
                    points = emptyList(),
                    latestDifferenceUSD = null,
                    latestDifferencePercent = null,
                    unavailableReason = ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS,
                    unavailableAt = orderedSnapshots[firstUncoveredIndex].timestamp,
                )
            }
            orderedSnapshots.take(firstUncoveredIndex)
        } else {
            orderedSnapshots
        }

        if (evaluationSnapshots.size < 2) {
            log.info(
                "History comparison unavailable; reason=HISTORY_COVERAGE_STALE stableThrough={} snapshotCount={}",
                stableThrough,
                evaluationSnapshots.size,
            )
            return RebalancerComparison(
                availability = ComparisonAvailability.UNAVAILABLE,
                confidence = null,
                baselineTimestamp = inceptionResolution?.inceptionTime ?: orderedSnapshots.first().timestamp,
                points = emptyList(),
                latestDifferenceUSD = null,
                latestDifferencePercent = null,
                unavailableReason = ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS,
                unavailableAt = orderedSnapshots.firstOrNull()?.timestamp,
            )
        }

        if (evaluationSnapshots.size < orderedSnapshots.size) {
            log.info(
                "History comparison using stable history horizon; " +
                    "stableThrough={} latestStableSnapshot={} skippedUnstableTailCount={}",
                stableThrough,
                evaluationSnapshots.last().timestamp,
                orderedSnapshots.size - evaluationSnapshots.size,
            )
        }

        val cacheFrom = evaluationSnapshots.first().timestamp
        val cacheTo = evaluationSnapshots.last().timestamp
        val cacheFingerprint = comparisonCacheFingerprint(
            stableThrough = stableThrough,
            inceptionResolution = inceptionResolution,
            snapshots = evaluationSnapshots,
        )
        if (cacheFingerprint != null) {
            loadCachedComparison(cacheFrom, cacheTo, cacheFingerprint)?.let { cached ->
                log.debug(
                    "Serving cached B&H comparison; sourceFrom={} sourceTo={} fingerprint={}",
                    cacheFrom,
                    cacheTo,
                    cacheFingerprint,
                )
                return presentComparison(cached, from, to)
            }
        }

        val flightKey = "${cacheFrom.toEpochMilli()}:${cacheTo.toEpochMilli()}:$cacheFingerprint"
        val (flight, created) = startOrJoinComparisonFlight(flightKey)
        val reconciled = if (created) {
            try {
                val consumedDependencies = ConcurrentHashMap.newKeySet<ConsumedOhlcDependency>()
                val calculated = calculateComparison(
                    evaluationSnapshots,
                    inceptionResolution,
                    eventUpperBound = certifiedEventUpperBound(stableThrough),
                    suppressPassiveDiscovery = shouldSuppressPassiveDiscovery(inceptionResolution),
                    onOhlcDependencyConsumed = { consumedDependencies.add(it) },
                )
                if (calculated.availability == ComparisonAvailability.AVAILABLE) {
                    persistAutomaticBaselineVerification(
                        calculated,
                        inceptionResolution,
                        evaluationSnapshots,
                        stableThrough,
                    )
                    persistCachedComparison(
                        from = cacheFrom,
                        to = cacheTo,
                        stableThrough = stableThrough,
                        inceptionResolution = inceptionResolution,
                        snapshots = evaluationSnapshots,
                        comparison = calculated,
                        ohlcDependencies = consumedDependencies.toList(),
                    )
                }
                flight.complete(calculated)
                calculated
            } catch (e: CancellationException) {
                flight.completeExceptionally(e)
                throw e
            } catch (e: Throwable) {
                flight.completeExceptionally(e)
                throw e
            } finally {
                comparisonInFlight.remove(flightKey, flight)
            }
        } else {
            flight.await()
        }

        val result = if (reconciled.availability == ComparisonAvailability.AVAILABLE) {
            presentComparison(reconciled, from, to)
        } else {
            reconciled
        }

        var finalResult = result
        // A later comparison start is actionable only alongside an explicit strategy inception.
        // Auto-detected inception is display-only until the operator supplies that anchor.
        if (result.availability == ComparisonAvailability.UNAVAILABLE &&
            result.unavailableReason in PROPOSAL_ELIGIBLE_REASONS &&
            inceptionResolution?.isAutoDetected != true
        ) {
            // The pure benchmark may have deliberately re-anchored after an unresolved
            // lifetime inception. Use the baseline that actually produced the result for the
            // continuity check; applying the obsolete strategy start here would turn a genuine
            // post-anchor reconciliation failure into a misleading historical-coverage gap.
            val strategyStart = result.baselineTimestamp ?: inceptionResolution?.inceptionTime
            val isVerifiedBaseline = strategyStart != null &&
                strategyStart == inceptionResolution?.inceptionTime &&
                readVerifiedAutomaticBaseline(inceptionResolution) != null
            if (strategyStart != null && !isVerifiedBaseline &&
                historicalCoverageGapExists(strategyStart, inceptionResolution)
            ) {
                log.info(
                    "comparison unavailable reason rewritten; from={} to={} because=HISTORICAL_COVERAGE_GAP",
                    result.unavailableReason,
                    ComparisonUnavailableReason.HISTORICAL_COVERAGE_GAP,
                )
                finalResult = result.copy(
                    unavailableReason = ComparisonUnavailableReason.HISTORICAL_COVERAGE_GAP,
                    proposedBaselineTimestamp = null,
                    proposalSearchStatus = null,
                )
            } else {
                // Window-independent: the proposal must not change when the user
                // zooms the History chart, so the scan reads the full retained
                // snapshot range instead of the display window.
                val proposal = findLaterComparisonStartProposalLocked(
                    startAfter = inceptionResolution?.inceptionTime ?: Instant.EPOCH,
                    inceptionResolution = inceptionResolution,
                )
                finalResult = result.copy(
                    proposedBaselineTimestamp = proposal.timestamp,
                    proposalSearchStatus = proposal.status,
                )
            }
        }

        if (finalResult.availability == ComparisonAvailability.UNAVAILABLE) {
            val continuousHistoryStart = repository.getSyncMetadata(
                SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS,
            )?.toLongOrNull()?.let(Instant::ofEpochMilli)
            val autoBaselineVerifiedThrough = repository.getSyncMetadata(
                SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS,
            )?.toLongOrNull()?.let(Instant::ofEpochMilli)
            log.info(
                "comparison unavailable; originalReason={} finalReason={} baseline={} unavailableAt={} " +
                    "stableThrough={} continuousHistoryStart={} automaticBaselineVerifiedThrough={}",
                result.unavailableReason,
                finalResult.unavailableReason,
                finalResult.baselineTimestamp ?: "unknown",
                finalResult.unavailableAt ?: "unknown",
                stableThrough,
                continuousHistoryStart ?: "unknown",
                autoBaselineVerifiedThrough ?: "unknown",
            )
        }

        return finalResult
    }

    /**
     * Reuses only successful calculations. Cache identity is the digest of the evidence the
     * authoritative calculation actually consumed — every snapshot, trade, and ledger row at
     * or before the certified horizon, bound to the inception the series is anchored on —
     * plus the OHLC candle-content revision for consumed valuations. Writes beyond the
     * horizon (a new live snapshot awaiting fills, a deposit not yet synced into coverage)
     * bump the global revision and cost one rehash, but the digest is unchanged, so the
     * cache stays valid. Any change to consumed evidence — an edit, backfill, deletion,
     * reconciliation, or a certified coverage watermark moving — changes the digest and
     * invalidates exactly once. Configuration, recovery, and prepared funding identities
     * participate separately because they are not all row writes.
     */
    private suspend fun comparisonCacheFingerprint(
        stableThrough: Instant,
        inceptionResolution: InceptionResolution?,
        snapshots: List<PortfolioSnapshot>,
    ): String? {
        if (comparisonCacheRepository == null) return null
        val fundingToken = when {
            fundingProvenanceResolver === FundingProvenanceResolver.NONE -> "none"
            else -> fundingProvenanceResolver.evidenceFingerprint ?: return null
        }
        return try {
            val sourceRevision = repository
                .getSyncMetadata(SyncMetadataKeys.COMPARISON_EVIDENCE_REVISION)
                ?: "0"
            val horizonEpochMillis = certifiedEventUpperBound(stableThrough).toEpochMilli()
            val consumedEvidenceDigest = consumedEvidenceDigest(
                inceptionResolution?.inceptionTime,
                horizonEpochMillis,
                sourceRevision,
            )
            val ohlcContentRevision = repository
                .getSyncMetadata(SyncMetadataKeys.OHLC_CANDLE_CONTENT_REVISION)
                ?: "0"
            val configuredUniverse = configService?.getConfig()?.allocations
                ?.sortedBy { it.symbol.value.uppercase() }
                ?.joinToString(separator = ",") { allocation ->
                    "${allocation.symbol.value.uppercase()}:${allocation.targetPercent}"
                }
                .orEmpty()
            val reconstructionRevision = listOf(
                repository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION),
                repository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC),
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT),
                repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST),
                ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION),
                ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC),
                repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION),
                repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC),
            ).joinToString(separator = "\u0000")
            val material = buildString {
                append(COMPARISON_CACHE_VERSION).append('\u0000')
                append(stableThrough).append('\u0000')
                append(consumedEvidenceDigest).append('\u0000')
                append(ohlcContentRevision).append('\u0000')
                append(fundingToken).append('\u0000')
                append(configuredUniverse).append('\u0000')
                append(reconstructionRevision).append('\u0000')
                append(inceptionResolution?.inceptionTime).append('|')
                    .append(inceptionResolution?.isAutoDetected).append('|')
                    .append(inceptionResolution?.confidence).append('|')
                    .append(inceptionResolution?.unavailableReason).append('\u0000')
                // The consumed-evidence digest is authoritative for row writes. These boundary
                // values make an old database with no revision row conservative when its
                // visible range moves.
                append(snapshots.size).append('|')
                    .append(snapshots.firstOrNull()?.timestamp).append('|')
                    .append(snapshots.lastOrNull()?.timestamp)
            }
            sha256Hex(material)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug("Comparison cache fingerprint unavailable: {}", e.message)
            null
        }
    }

    /**
     * Digest of every snapshot, trade, and ledger row the authoritative calculation can
     * consume at or before the certified horizon, bound to the strategy inception and the
     * cache contract version. Rows strictly after the horizon are append-only live-tail
     * evidence the calculation did not consume: appending them changes the global revision
     * (forcing one rehash here) without changing this digest, so the cached comparison for
     * the stable prefix survives. A row at or before the horizon that is later edited,
     * backfilled, or deleted changes the digest and invalidates the cache.
     */
    private suspend fun consumedEvidenceDigest(
        inceptionTime: Instant?,
        horizonEpochMillis: Long,
        revision: String,
    ): String {
        val inceptionMillis = inceptionTime?.toEpochMilli()
        consumedEvidenceMemo.get()
            ?.takeIf {
                it.inceptionMillis == inceptionMillis &&
                    it.horizonMillis == horizonEpochMillis &&
                    it.revision == revision
            }
            ?.let { return it.digest }

        val effectiveInception = inceptionTime ?: Instant.EPOCH
        val snapshots = loadAllSnapshots(effectiveInception)
        val predecessorSnapshot = repository.getSnapshotBefore(effectiveInception)
        val trades = repository.getTradesInRange(Instant.EPOCH, Instant.ofEpochMilli(horizonEpochMillis))
        val ledgers =
            ledgerRepository.getLedgersInRange(Instant.EPOCH, Instant.ofEpochMilli(horizonEpochMillis))
        val material = buildString {
            append(COMPARISON_CACHE_VERSION).append('\u0000')
            append(effectiveInception).append('\u0000')
            // The predecessor anchor participates in the full evaluation's event query
            // window, so its presence and content belong to the consumed evidence.
            if (predecessorSnapshot == null) {
                append("predecessor:none\n")
            } else {
                append("predecessor\n")
                appendSnapshotDigest(predecessorSnapshot)
            }
            for (snapshot in snapshots) {
                if (snapshot.timestamp.toEpochMilli() <= horizonEpochMillis) appendSnapshotDigest(snapshot)
            }
            val sortedTrades = trades.toMutableList()
            sortedTrades.sortWith(TradeDigestOrder)
            for (trade in sortedTrades) appendTradeDigest(trade)
            val sortedLedgers = ledgers.toMutableList()
            sortedLedgers.sortWith(LedgerDigestOrder)
            for (event in sortedLedgers) appendLedgerDigest(event)
        }
        val digest = sha256Hex(material)
        consumedEvidenceMemo.set(ConsumedEvidenceMemo(inceptionMillis, horizonEpochMillis, revision, digest))
        return digest
    }

    private suspend fun loadCachedComparison(from: Instant, to: Instant, fingerprint: String): RebalancerComparison? {
        val cache = comparisonCacheRepository ?: return null
        return try {
            val entry = cache.load(from.toEpochMilli(), to.toEpochMilli()) ?: return null
            if (entry.inputFingerprint != fingerprint) return null

            val ohlc = historicalOhlcCache ?: return entry.comparison
            val nowEpochSecond = nowProvider().epochSecond
            val (fresh, expired) = entry.ohlcDependencies.partition { it.isFresh(nowEpochSecond) }
            if (expired.isEmpty()) {
                return entry.comparison
            }

            val updatedDependencies = fresh.toMutableList()
            var hasUpdates = false
            for (dep in expired) {
                when (val result = ohlc.revalidateDependency(dep)) {
                    is OhlcRevalidationResult.ContentChanged -> {
                        log.info(
                            "Comparison cache invalidated by OHLC content change; pair={} interval={} since={}",
                            dep.pair,
                            dep.intervalMinutes,
                            dep.sinceEpochSecond,
                        )
                        cache.delete(from.toEpochMilli(), to.toEpochMilli())
                        return null
                    }

                    is OhlcRevalidationResult.Unchanged -> {
                        updatedDependencies.add(result.updatedDependency)
                        if (result.updatedDependency != dep) {
                            hasUpdates = true
                        }
                    }
                }
            }

            if (hasUpdates) {
                cache.updateOhlcDependencies(
                    fromEpochMillis = from.toEpochMilli(),
                    toEpochMillis = to.toEpochMilli(),
                    ohlcDependencies = updatedDependencies,
                )
            }
            entry.comparison
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug("Comparison cache read skipped: {}", e.message)
            null
        }
    }

    private suspend fun persistCachedComparison(
        from: Instant,
        to: Instant,
        stableThrough: Instant,
        inceptionResolution: InceptionResolution?,
        snapshots: List<PortfolioSnapshot>,
        comparison: RebalancerComparison,
        ohlcDependencies: List<ConsumedOhlcDependency>,
    ) {
        val cache = comparisonCacheRepository ?: return
        if (fundingProvenanceResolver !== FundingProvenanceResolver.NONE &&
            fundingProvenanceResolver.preparationFailure != null
        ) {
            // A degraded funding batch (permission denied, request failed) classifies funding
            // rows as unresolved and can change the comparison. Never cache that result under
            // a fingerprint a healthy batch would also produce — it would poison the entry
            // until the funding evidence itself changed.
            log.debug(
                "Comparison cache write skipped; funding provenance degraded ({})",
                fundingProvenanceResolver.preparationFailure?.reason,
            )
            return
        }
        // Resolve the fingerprint only after the authoritative calculation has finished. The
        // calculation may fetch and persist historical OHLC evidence, which advances the
        // comparison revision; saving a pre-calculation fingerprint would invalidate this result
        // on the very next request.
        val fingerprint = comparisonCacheFingerprint(
            stableThrough = stableThrough,
            inceptionResolution = inceptionResolution,
            snapshots = snapshots,
        ) ?: return
        try {
            cache.save(
                fromEpochMillis = from.toEpochMilli(),
                toEpochMillis = to.toEpochMilli(),
                inputFingerprint = fingerprint,
                comparison = comparison,
                ohlcDependencies = ohlcDependencies,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The result remains usable when its optimization record cannot be written.
            log.debug("Comparison cache write skipped: {}", e.message)
        }
    }

    private fun presentComparison(reconciled: RebalancerComparison, from: Instant, to: Instant): RebalancerComparison {
        val displayPoints = reconciled.points.filter { point ->
            !point.timestamp.isBefore(from) && !point.timestamp.isAfter(to)
        }
        if (displayPoints.size < 2) {
            return reconciled.copy(
                availability = ComparisonAvailability.UNAVAILABLE,
                confidence = null,
                points = emptyList(),
                latestDifferenceUSD = null,
                latestDifferencePercent = null,
                unavailableReason = ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS,
                unavailableAt = displayPoints.firstOrNull()?.timestamp ?: from,
                proposedBaselineTimestamp = null,
                proposalSearchStatus = null,
            )
        }
        val sampledDisplayPoints = displayPoints.downsampleSnapshots()
        return reconciled.copy(
            points = sampledDisplayPoints,
            latestDifferenceUSD = sampledDisplayPoints.last().differenceUSD,
            latestDifferencePercent = sampledDisplayPoints.last().differencePercent,
        )
    }

    /**
     * Applies the same comparison-availability policy used by History before exposing a
     * Settings proposal. Recovery status alone is not sufficient: a confirmed baseline can still
     * be followed by an ownership or reconciliation failure in the retained history.
     */
    suspend fun getComparisonStartProposal(after: Instant): ComparisonStartProposal? =
        getSettingsComparisonStatus(after, allowPersistedBaselineFastPath = false).proposal

    /** Called by a service operation that already owns [historyEvidenceCoordinator]. */
    internal suspend fun getComparisonStartProposalUnderEvidenceLock(after: Instant): ComparisonStartProposal? {
        val inceptionResolution = inceptionDiscoveryService?.resolveInceptionUnderEvidenceLock()
        return getSettingsComparisonStatusLocked(
            after = after,
            allowPersistedBaselineFastPath = false,
            inceptionResolution = inceptionResolution,
        ).proposal
    }

    /**
     * The same gate chain as [getComparisonStartProposal] in the same order, but it also
     * exposes the baseline identity and the passive comparison's availability so the Settings
     * fragment can render the effective Buy & Hold baseline from this single evaluation.
     * The proposal chain always evaluates through the full gates because it bypasses the
     * persisted-baseline fast path. The stable-horizon gate below applies to every
     * Settings-family evaluation that flows through this function — baseline proof,
     * later-start proposal search, and search continuation — so each withholds a verdict
     * while certified coverage is unknown or thin and resumes once history catches up.
     * History's [getRebalancerComparison] uses the same stable-horizon gate to trim
     * uncertified live-tail snapshots while evaluating the retained historical prefix.
     *
     * Baseline identity and current comparison availability are independent. When
     * [allowPersistedBaselineFastPath] is true and the durable automatic baseline
     * verification (see [readVerifiedAutomaticBaseline]) is present and still valid, this
     * returns the proven baseline with a null [SettingsComparisonStatus.comparisonAvailability]:
     * the proof validation re-hashes local snapshot/trade/ledger evidence up to the stored
     * horizon but performs no reconciliation replay, no historical price resolution, and no
     * funding preparation, and the current tail-inclusive comparison was not evaluated in
     * this request. A successful full evaluation persists that proof once (see
     * [persistAutomaticBaselineVerification]) and reports both concepts; an unavailable full
     * evaluation keeps the proven baseline identity visible alongside the failure.
     */
    suspend fun getSettingsComparisonStatus(
        after: Instant,
        allowPersistedBaselineFastPath: Boolean = true,
    ): SettingsComparisonStatus = historyEvidenceCoordinator.withLock {
        val inceptionResolution = inceptionDiscoveryService?.resolveInceptionUnderEvidenceLock()
        getSettingsComparisonStatusLocked(after, allowPersistedBaselineFastPath, inceptionResolution)
    }

    private suspend fun getSettingsComparisonStatusLocked(
        after: Instant,
        allowPersistedBaselineFastPath: Boolean,
        inceptionResolution: InceptionResolution?,
    ): SettingsComparisonStatus {
        // An auto-detected inception is display-only until the operator supplies an explicit
        // strategy start; Settings must not expose an approval action for it.
        if (inceptionResolution?.isAutoDetected == true) return SettingsComparisonStatus()
        if (allowPersistedBaselineFastPath) {
            readVerifiedAutomaticBaseline(inceptionResolution)?.let { return it }
        }
        val snapshots = loadAllSnapshots(inceptionResolution?.inceptionTime ?: Instant.EPOCH)
        if (snapshots.size < 2) return SettingsComparisonStatus()
        // Invalidated reconstructed history must not yield a proposal: a candidate anchored on
        // stale reconstructed snapshots is not evidence-backed until the rebuild completes.
        if (overlapsStaleReconstruction(snapshots)) return SettingsComparisonStatus()
        // Automatic-baseline verification operates only on stable historical evidence: a
        // snapshot whose balance observation lies beyond the certified ledger/trade coverage
        // horizon belongs to the live tail — e.g. balances already reflecting an executed
        // trade whose fills or ledger rows have not synced yet — and must not fail the
        // reconciliation of confirmed history. The boundary is the balance observation time,
        // not the snapshot write time.
        val stableThrough = latestConfirmedEconomicCoverage(
            requiredStart = requiredCoverageStart(
                baselineStart = effectiveReplayBaselineStart(
                    inceptionResolution,
                    snapshots.first(),
                    benchmarkHistoryFloor,
                    nowProvider(),
                ),
                firstSnapshot = snapshots.first(),
            ),
        )
        if (stableThrough == null) {
            log.info("Automatic B&H baseline verification deferred; reason=HISTORY_COVERAGE_STALE")
            return SettingsComparisonStatus()
        }
        val firstUncoveredIndex = snapshots.indexOfFirst { !isSnapshotCoveredByHistory(it, stableThrough) }
        if (firstUncoveredIndex >= 0) {
            val reentry = snapshots.drop(firstUncoveredIndex + 1).indexOfFirst {
                isSnapshotCoveredByHistory(it, stableThrough)
            }
            if (reentry >= 0) {
                // A covered observation after an uncovered one means the observation
                // sequence is non-monotonic relative to certified coverage — trimming
                // would silently erase an interior reconciliation checkpoint.
                log.warn(
                    "Automatic B&H baseline verification deferred; reason=HISTORY_COVERAGE_NON_MONOTONIC " +
                        "firstUncoveredSnapshot={} laterCoveredSnapshot={}",
                    snapshots[firstUncoveredIndex].timestamp,
                    snapshots[firstUncoveredIndex + 1 + reentry].timestamp,
                )
                return SettingsComparisonStatus()
            }
        }
        val stableSnapshots = if (firstUncoveredIndex < 0) snapshots else snapshots.take(firstUncoveredIndex)
        if (stableSnapshots.size < 2) {
            log.info("Automatic B&H baseline verification deferred; reason=HISTORY_COVERAGE_STALE")
            return SettingsComparisonStatus()
        }
        if (stableSnapshots.size < snapshots.size) {
            val newestSkipped = snapshots.last()
            log.info(
                "Automatic B&H baseline verification using stable history horizon; " +
                    "stableThrough={} latestStableSnapshot={} skippedUnstableTailCount={}",
                stableThrough,
                stableSnapshots.last().timestamp,
                snapshots.size - stableSnapshots.size,
            )
            log.debug(
                "newestSkippedSnapshot={} balancesObservedAt={} ledgerCoverage={} tradeCoverage={}",
                newestSkipped.timestamp,
                newestSkipped.balancesObservedAt ?: newestSkipped.timestamp,
                ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC),
                repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC),
            )
        }
        val settingsCacheFrom = stableSnapshots.first().timestamp
        val settingsCacheTo = stableSnapshots.last().timestamp
        val settingsCacheFingerprint = comparisonCacheFingerprint(
            stableThrough = stableThrough,
            inceptionResolution = inceptionResolution,
            snapshots = stableSnapshots,
        )
        val cachedSettingsComparison = settingsCacheFingerprint?.let { fingerprint ->
            loadCachedComparison(settingsCacheFrom, settingsCacheTo, fingerprint)
        }
        val current = if (cachedSettingsComparison != null) {
            cachedSettingsComparison
        } else {
            val flightKey =
                "${settingsCacheFrom.toEpochMilli()}:${settingsCacheTo.toEpochMilli()}:$settingsCacheFingerprint"
            val (flight, created) = startOrJoinComparisonFlight(flightKey)
            if (created) {
                try {
                    val consumedDependencies = ConcurrentHashMap.newKeySet<ConsumedOhlcDependency>()
                    val calculated = calculateComparison(
                        stableSnapshots,
                        inceptionResolution,
                        eventUpperBound = certifiedEventUpperBound(stableThrough),
                        suppressPassiveDiscovery = shouldSuppressPassiveDiscovery(inceptionResolution),
                        onOhlcDependencyConsumed = consumedDependencies::add,
                    )
                    if (calculated.availability == ComparisonAvailability.AVAILABLE) {
                        persistCachedComparison(
                            from = settingsCacheFrom,
                            to = settingsCacheTo,
                            stableThrough = stableThrough,
                            inceptionResolution = inceptionResolution,
                            snapshots = stableSnapshots,
                            comparison = calculated,
                            ohlcDependencies = consumedDependencies.toList(),
                        )
                    }
                    flight.complete(calculated)
                    calculated
                } catch (e: CancellationException) {
                    flight.completeExceptionally(e)
                    throw e
                } catch (e: Throwable) {
                    flight.completeExceptionally(e)
                    throw e
                } finally {
                    comparisonInFlight.remove(flightKey, flight)
                }
            } else {
                flight.await()
            }
        }
        val status =
            if (current.availability == ComparisonAvailability.AVAILABLE) {
                SettingsComparisonStatus(
                    // Only the inception-anchored baseline certifies the automatic proof; a
                    // passive re-anchor is available but has no durable verification behind it.
                    baselineStatus = AutomaticBaselineStatus.VERIFIED
                        .takeIf { current.baselineTimestamp == inceptionResolution?.inceptionTime },
                    baselineTimestamp = current.baselineTimestamp?.toString(),
                    comparisonAvailability = current.availability,
                )
            } else {
                SettingsComparisonStatus(
                    comparisonAvailability = current.availability,
                    unavailableReason = current.unavailableReason,
                    unavailableAt = current.unavailableAt?.toString(),
                )
            }
        if (current.availability == ComparisonAvailability.UNAVAILABLE) {
            // Operator-visible reconciliation diagnostics: the reason and its evidence
            // timestamp identify the event that made the inception-anchored comparison
            // unavailable. Names, balances, and raw exchange payloads are deliberately omitted.
            log.info(
                "comparison unavailable; reason={} unavailableAt={}",
                current.unavailableReason,
                status.unavailableAt ?: "unknown",
            )
        }
        if (current.availability != ComparisonAvailability.UNAVAILABLE ||
            current.unavailableReason !in PROPOSAL_ELIGIBLE_REASONS
        ) {
            // The proof's evidence horizon is the newest stable snapshot: unstable live-tail
            // rows after the certified coverage horizon are append-only evidence the proof
            // did not consume, so they can neither invalidate it nor extend its horizon.
            if (cachedSettingsComparison == null) {
                persistAutomaticBaselineVerification(current, inceptionResolution, stableSnapshots, stableThrough)
            }
            return status
        }
        if (historicalCoverageGapExists(
                snapshots = snapshots,
                strategyStart = inceptionResolution?.inceptionTime ?: after,
                inceptionResolution = inceptionResolution,
            )
        ) {
            return status
        }
        val skipCandidatesBefore = current.unavailableAt
            ?.takeIf { current.unavailableReason in INTRINSIC_EVENT_REASONS }
        val proposal = findLaterComparisonStartProposalLocked(after, inceptionResolution, skipCandidatesBefore)
        if (proposal.status == ComparisonProposalStatus.INCOMPLETE) {
            ensureProposalSearchContinuation(after)
        }
        return status.copy(proposal = proposal)
    }

    /**
     * Runs the bounded proposal scan faithfully on the application scope when an evaluation
     * left it INCOMPLETE, so later-start discovery completes without asking the operator to
     * reload Settings until the durable cursor advances through every candidate. Every cycle
     * evaluates through the same gate chain and persists progress under [proposalSearchMutex],
     * and the loop stops on VERIFIED, EXHAUSTED, any gate change, or its own bounded budget.
     */
    private fun ensureProposalSearchContinuation(after: Instant) {
        val scope = applicationScope ?: return
        if (!proposalContinuationActive.compareAndSet(false, true)) return
        if (!scope.isActive) {
            proposalContinuationActive.set(false)
            return
        }
        log.info("proposal search continuation started; status=INCOMPLETE")
        scope.launch {
            try {
                var pacingMs = PROPOSAL_CONTINUATION_PACING_MS
                for (cycle in 0 until PROPOSAL_CONTINUATION_MAX_CYCLES) {
                    val proposal =
                        getSettingsComparisonStatus(after, allowPersistedBaselineFastPath = false).proposal
                    if (proposal?.status != ComparisonProposalStatus.INCOMPLETE) {
                        log.info(
                            "proposal search continuation finished at cycle {}; status={}",
                            cycle,
                            proposal?.status,
                        )
                        return@launch
                    }
                    delay(pacingMs)
                    pacingMs = (pacingMs * 2).coerceAtMost(PROPOSAL_CONTINUATION_MAX_PACING_MS)
                }
                log.info("proposal search continuation budget exhausted; status=INCOMPLETE")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.warn("proposal search continuation stopped", error)
            } finally {
                proposalContinuationActive.set(false)
            }
        }
    }

    // An approved, confident baseline governs an operator-facing comparison only when it
    // can ground a reconciled result for this view: a snapshot older than the
    // trustworthy-history bound has no retained event trail behind it, so it cannot anchor
    // here and discovery stays the fallback. The query window itself is not a usability
    // bound — the full retained range is evaluated and presentation is sliced, so a
    // post-bound approved baseline governs windowed views too.
    private fun shouldSuppressPassiveDiscovery(inceptionResolution: InceptionResolution?): Boolean {
        val approvedBaseline =
            if (inceptionResolution?.confidence == InceptionConfidence.CONFIDENT) {
                inceptionResolution.inceptionSnapshot
            } else {
                null
            }
        return approvedBaseline != null && !approvedBaseline.timestamp.isBefore(benchmarkHistoryFloor)
    }

    /** Bounded reason a persisted automatic baseline verification stopped being reusable. */
    private enum class AutomaticBaselineInvalidationReason {
        VERSION_CHANGED,
        INCEPTION_CHANGED,
        BASELINE_ID_CHANGED,
        ACCOUNT_SCOPE_CHANGED,
        CONFIG_UNIVERSE_CHANGED,
        RECONSTRUCTION_CHANGED,
        FUNDING_EVIDENCE_CHANGED,
        COVERAGE_CHANGED,
        MALFORMED_STATE,
    }

    /**
     * Serves the proven automatic Buy & Hold baseline from the durable verification record
     * when that record is still valid, so a Settings reload or app restart does not replay
     * the entire historical comparison merely to rediscover the same baseline.
     *
     * The record answers only "is strategy inception a proven automatic baseline" — never
     * current comparison economics. Null means no record exists yet (first run) or the
     * record was just invalidated; the caller then evaluates normally and the successful
     * evaluation re-persists the proof. An already-invalidated record returns null silently
     * so a failing re-evaluation does not spam invalidation logs.
     *
     * Validation fails closed: every field is re-checked against current evidence identity,
     * evidence rows at or before the persisted horizon are re-digested, and any mismatch,
     * malformed value, or missing field rejects the record with a bounded reason.
     */
    private suspend fun readVerifiedAutomaticBaseline(
        inceptionResolution: InceptionResolution?,
    ): SettingsComparisonStatus? {
        val inceptionTime = inceptionResolution?.inceptionTime ?: return null
        // A degraded resolution (unverified account scope, incomplete recovery, truncated
        // history) withholds trust from the current evidence, so it must also withhold the
        // persisted proof: the record was written under previously validated conditions and
        // a degraded resolution says those conditions can no longer be confirmed. The full
        // evaluation runs instead, and a successful one re-persists the proof.
        if (inceptionResolution.confidence != InceptionConfidence.CONFIDENT ||
            inceptionResolution.unavailableReason != null
        ) {
            return null
        }
        val status = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS)
        if (status.isNullOrBlank() || status == AUTOMATIC_BASELINE_STATUS_INVALIDATED) return null
        if (status != AUTOMATIC_BASELINE_STATUS_VERIFIED) {
            return invalidateAutomaticBaseline(AutomaticBaselineInvalidationReason.MALFORMED_STATE)
        }
        val version =
            repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_AUTO_BASELINE_VERIFICATION_VERSION)
        if (version != AUTOMATIC_BASELINE_VERIFICATION_VERSION) {
            return invalidateAutomaticBaseline(AutomaticBaselineInvalidationReason.VERSION_CHANGED)
        }
        val storedBaselineEpochMillis = repository.getSyncMetadata(
            SyncMetadataKeys.INCEPTION_AUTO_BASELINE_TIMESTAMP_EPOCH_MS,
        )?.toLongOrNull()
        val storedInceptionEpochMillis = repository.getSyncMetadata(
            SyncMetadataKeys.INCEPTION_AUTO_BASELINE_INCEPTION_EPOCH_MS,
        )?.toLongOrNull()
        val storedSnapshotId = repository.getSyncMetadata(
            SyncMetadataKeys.INCEPTION_AUTO_BASELINE_SNAPSHOT_ID,
        )?.toIntOrNull()
        val storedCursor = repository.getSyncMetadata(
            SyncMetadataKeys.INCEPTION_AUTO_BASELINE_SNAPSHOT_CURSOR,
        )?.let { ProposalCursor.parse(it) }
        val storedConfigFingerprint = repository.getSyncMetadata(
            SyncMetadataKeys.INCEPTION_AUTO_BASELINE_CONFIG_FINGERPRINT,
        )
        val storedAccountScopeDigest = repository.getSyncMetadata(
            SyncMetadataKeys.INCEPTION_AUTO_BASELINE_ACCOUNT_SCOPE_DIGEST,
        )
        val storedEvidenceFingerprint = repository.getSyncMetadata(
            SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_FINGERPRINT,
        )
        val storedEvidenceHorizonEpochMillis = repository.getSyncMetadata(
            SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS,
        )?.toLongOrNull()?.takeIf { it >= 0L }
        if (storedBaselineEpochMillis == null ||
            storedInceptionEpochMillis == null ||
            storedSnapshotId == null ||
            storedCursor == null ||
            storedConfigFingerprint == null ||
            storedAccountScopeDigest == null ||
            storedEvidenceFingerprint == null ||
            storedEvidenceHorizonEpochMillis == null ||
            storedBaselineEpochMillis != storedInceptionEpochMillis
        ) {
            return invalidateAutomaticBaseline(AutomaticBaselineInvalidationReason.MALFORMED_STATE)
        }
        val stableThrough = latestConfirmedEconomicCoverage(requiredStart = inceptionTime)
        val certifiedEvidenceHorizonEpochMillis = stableThrough
            ?.let(::certifiedEventUpperBound)
            ?.toEpochMilliOrNull()
        if (certifiedEvidenceHorizonEpochMillis == null ||
            storedEvidenceHorizonEpochMillis > certifiedEvidenceHorizonEpochMillis
        ) {
            return invalidateAutomaticBaseline(AutomaticBaselineInvalidationReason.COVERAGE_CHANGED)
        }
        if (storedInceptionEpochMillis != inceptionTime.toEpochMilli()) {
            return invalidateAutomaticBaseline(AutomaticBaselineInvalidationReason.INCEPTION_CHANGED)
        }
        // Only a stale reconstruction interval overlapping the verified interval can taint
        // the proof's own rows; a stale interval elsewhere fails the full path exactly as it
        // always has, without churning this record through pointless invalidate/re-persist
        // cycles.
        staleReconstructedInterval()?.let { (reconStart, reconThrough) ->
            val verifiedStart = Instant.ofEpochMilli(storedInceptionEpochMillis)
            val verifiedEnd = Instant.ofEpochMilli(storedEvidenceHorizonEpochMillis)
            if (!reconThrough.isBefore(verifiedStart) && !reconStart.isAfter(verifiedEnd)) {
                return invalidateAutomaticBaseline(AutomaticBaselineInvalidationReason.RECONSTRUCTION_CHANGED)
            }
        }
        if (storedConfigFingerprint !=
            repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT).orEmpty()
        ) {
            return invalidateAutomaticBaseline(AutomaticBaselineInvalidationReason.CONFIG_UNIVERSE_CHANGED)
        }
        if (storedAccountScopeDigest !=
            repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST).orEmpty()
        ) {
            return invalidateAutomaticBaseline(AutomaticBaselineInvalidationReason.ACCOUNT_SCOPE_CHANGED)
        }
        val resolvedSnapshotId = repository.getSnapshotId(
            Instant.ofEpochMilli(storedCursor.epochMillis),
            storedCursor.ordinal,
        )
        if (resolvedSnapshotId != storedSnapshotId) {
            return invalidateAutomaticBaseline(AutomaticBaselineInvalidationReason.BASELINE_ID_CHANGED)
        }
        val evidenceFingerprint =
            automaticBaselineEvidenceDigest(inceptionTime, storedEvidenceHorizonEpochMillis)
        if (evidenceFingerprint != storedEvidenceFingerprint) {
            return invalidateAutomaticBaseline(AutomaticBaselineInvalidationReason.FUNDING_EVIDENCE_CHANGED)
        }
        // TOCTOU guard: a concurrent caller may have invalidated the record while this
        // validation ran. Only a record that is still VERIFIED at the end of validation is
        // served; anything else fails closed and the caller recomputes.
        if (repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS) !=
            AUTOMATIC_BASELINE_STATUS_VERIFIED
        ) {
            return null
        }
        val baseline = Instant.ofEpochMilli(storedBaselineEpochMillis)
        log.info("using persisted automatic B&H baseline verification; baseline={}", baseline)
        // The persisted proof covers baseline identity only; current comparison economics were
        // not evaluated in this request, so comparisonAvailability stays null.
        return SettingsComparisonStatus(
            baselineStatus = AutomaticBaselineStatus.VERIFIED,
            baselineTimestamp = baseline.toString(),
        )
    }

    /**
     * Marks the record invalidated so subsequent evaluations skip it silently, and returns
     * null so the caller recomputes. The proof is re-persisted only by a later successful
     * full evaluation; a failing evaluation leaves the record dead instead of re-logging
     * the same invalidation on every Settings load.
     */
    private suspend fun invalidateAutomaticBaseline(
        reason: AutomaticBaselineInvalidationReason,
    ): SettingsComparisonStatus? {
        log.info("persisted automatic B&H baseline verification invalidated; reason={}", reason)
        repository.setSyncMetadataAtomically(
            mapOf(
                SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS to AUTOMATIC_BASELINE_STATUS_INVALIDATED,
            ),
        )
        return null
    }

    /**
     * Certified economic-history coverage for automatic-baseline verification: the earlier
     * of the current-version certified ledger and trade coverage horizons. Both certificates
     * must have current versions, nonnegative starts and horizons, and parse — the sync
     * watermarks are deliberately not consulted because they record a refreshed query window,
     * not a completeness proof. Coverage starts and account-scope digests are part of the
     * certificate contract: a current horizon alone must never make an incomplete or differently
     * scoped history look verified. A null return means coverage is unknown, so verification
     * defers instead of trusting an unproven tail.
     */
    private suspend fun latestConfirmedEconomicCoverage(requiredStart: Instant): Instant? {
        // A horizon is a certificate only when it was written by the current coverage
        // contract. A stale version marker can otherwise leave an old horizon looking
        // trustworthy after the event-classification or account-scope semantics changed.
        val ledgerCoverageVersion = ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION)
        val tradeCoverageVersion = repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION)
        if (ledgerCoverageVersion != LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION ||
            tradeCoverageVersion != TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
        ) {
            return null
        }
        val ledgerStartSec = ledgerRepository
            .getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC)
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }
        val tradeStartSec = repository
            .getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC)
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }
        val ledgerHorizonSec = ledgerRepository
            .getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC)
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }
        val tradeHorizonSec = repository
            .getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC)
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }
        if (ledgerStartSec == null || tradeStartSec == null ||
            ledgerHorizonSec == null || tradeHorizonSec == null ||
            ledgerStartSec > ledgerHorizonSec || tradeStartSec > tradeHorizonSec
        ) {
            return null
        }
        val requiredStartSec = requiredStart.epochSecond
        if (ledgerStartSec > requiredStartSec || tradeStartSec > requiredStartSec ||
            ledgerHorizonSec < requiredStartSec || tradeHorizonSec < requiredStartSec
        ) {
            return null
        }

        // A live account-scoped certificate must agree with the shared inception binding. An
        // entirely blank pair is the unbound simulation contract; a partial pair, mismatched
        // pair, or nonblank pair without a shared binding is not a certificate.
        val ledgerScopeDigest = ledgerRepository
            .getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
            ?.trim()
            .orEmpty()
        val tradeScopeDigest = repository
            .getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST)
            ?.trim()
            .orEmpty()
        val inceptionScopeDigest = repository
            .getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST)
            ?.trim()
            .orEmpty()
        // Blank scope certificates are emitted only by the simulation seed. They are useful for
        // an isolated synthetic account, but are not evidence that can cross into production
        // mode: when simulation is disabled, require an account-bound certificate instead.
        val simulationEnabled = configService?.getConfig()?.settings?.simulation == true
        val scopesUnbound = simulationEnabled &&
            ledgerScopeDigest.isBlank() &&
            tradeScopeDigest.isBlank() &&
            inceptionScopeDigest.isBlank()
        val scopesBound = ledgerScopeDigest.isNotBlank() &&
            tradeScopeDigest.isNotBlank() &&
            ledgerScopeDigest == tradeScopeDigest &&
            inceptionScopeDigest == ledgerScopeDigest
        if (!scopesUnbound && !scopesBound) {
            return null
        }
        return runCatching {
            val ledgerHorizon = Instant.ofEpochSecond(ledgerHorizonSec)
            val tradeHorizon = Instant.ofEpochSecond(tradeHorizonSec)
            val stableThrough = minOf(ledgerHorizon, tradeHorizon)
            val eventUpperBound = certifiedEventUpperBound(stableThrough)
            // Instant supports a wider range than epoch-millisecond metadata. Reject a
            // mathematically valid second that cannot be represented by every downstream
            // digest/cursor query instead of allowing an overflow later in the replay path.
            if (stableThrough.toEpochMilliOrNull() == null || eventUpperBound.toEpochMilliOrNull() == null) {
                null
            } else {
                stableThrough
            }
        }.getOrNull()
    }

    /**
     * A snapshot is economically covered when its balance observation boundary — the moment
     * the balances it records were observed — lies within confirmed trade/ledger coverage.
     * Reconstructed historical snapshots carry no separate observation boundary
     * ([PortfolioSnapshot.balancesObservedAt] is null by the reconstruction contract) and
     * fall back to their snapshot timestamp, which reconstruction certification guarantees
     * is covered. Certified coverage horizons are second-granular: a covered second covers
     * every observation inside it (mirrors the reconstruction anchor tolerance and the ATH
     * watermark gate).
     */
    private fun isSnapshotCoveredByHistory(snapshot: PortfolioSnapshot, stableThrough: Instant): Boolean =
        (snapshot.balancesObservedAt ?: snapshot.timestamp).epochSecond <= stableThrough.epochSecond

    /**
     * Coverage must reach the earliest time the evaluation can replay, not merely the first row
     * returned for the requested display range. An explicit inception/approved baseline may sit
     * before that row (including when an identity-anchor collision removes the row from the
     * loaded series), and events from that baseline onward still participate in reconciliation.
     */
    private fun requiredCoverageStart(baselineStart: Instant?, firstSnapshot: PortfolioSnapshot): Instant {
        val firstObservation = firstSnapshot.balancesObservedAt ?: firstSnapshot.timestamp
        return baselineStart?.let { minOf(it, firstObservation) } ?: firstObservation
    }

    /**
     * Finds the earliest evidence the current comparison path will actually replay. A retained
     * approved snapshot can supersede the configured instant as the economic anchor, while a
     * passive benchmark re-anchor is deliberately preferred when an informational inception lies
     * before the retained-history floor. If no such post-floor anchor exists, retain the earlier
     * inception so the coverage gate fails closed instead of laundering a truncated replay.
     */
    private suspend fun effectiveReplayBaselineStart(
        inceptionResolution: InceptionResolution?,
        firstSnapshot: PortfolioSnapshot,
        fallback: Instant,
        evaluationUpperBound: Instant,
    ): Instant {
        val resolvedBaseline = inceptionResolution?.inceptionSnapshot?.let {
            it.balancesObservedAt ?: it.timestamp
        } ?: inceptionResolution?.inceptionTime
        if (inceptionResolution != null &&
            !shouldSuppressPassiveDiscovery(inceptionResolution) &&
            (resolvedBaseline == null || resolvedBaseline.isBefore(benchmarkHistoryFloor))
        ) {
            findPureBenchmarkAnchor(
                floor = maxOf(benchmarkHistoryFloor, firstSnapshot.timestamp),
                upperBound = evaluationUpperBound,
            )
                ?.let { return it.balancesObservedAt ?: it.timestamp }
        }
        return resolvedBaseline ?: fallback
    }

    /**
     * Coverage horizons are persisted at epoch-second precision. The whole certified second is
     * covered, so event replay may include its fractional-second rows but nothing later.
     */
    private fun certifiedEventUpperBound(stableThrough: Instant): Instant =
        runCatching { stableThrough.plusNanos(999_999_999) }.getOrDefault(stableThrough)

    private fun Instant.toEpochMilliOrNull(): Long? = runCatching { toEpochMilli() }.getOrNull()

    /**
     * Persists the automatic baseline proof when the just-completed evaluation proved the
     * strategy inception is the effective Buy & Hold baseline. The record stores the proof's
     * identity, evidence horizon, and evidence digest — never current NAV or comparison
     * economics, which History must still calculate. A baseline anchored at a different
     * instant than the strategy inception (e.g. a passive recorded benchmark anchor or a
     * degraded-resolution fallback row) is not an automatic-inception proof and is not
     * persisted here; a same-instant anchor is indistinguishable from and equivalent to
     * the inception baseline. The evidence horizon is capped at the stable verification
     * horizon (the certified coverage pair), so the digest never binds to uncertified
     * tail rows.
     */
    private suspend fun persistAutomaticBaselineVerification(
        current: RebalancerComparison,
        inceptionResolution: InceptionResolution?,
        snapshots: List<PortfolioSnapshot>,
        stableThrough: Instant,
    ) {
        // Auto-detected inception is display-only until the operator supplies an explicit
        // strategy anchor. History must not turn a display result into a durable approval proof.
        if (inceptionResolution?.isAutoDetected == true) return
        if (current.availability != ComparisonAvailability.AVAILABLE) return
        val baselineTimestamp = current.baselineTimestamp ?: return
        val inceptionTime = inceptionResolution?.inceptionTime ?: return
        if (baselineTimestamp != inceptionTime) return
        val baselineIndex = snapshots.indexOfFirst { it.timestamp == baselineTimestamp }
        if (baselineIndex < 0) return
        val cursor = snapshots.proposalCursorAt(baselineIndex)
        val snapshotId = repository.getSnapshotId(Instant.ofEpochMilli(cursor.epochMillis), cursor.ordinal)
        snapshotId ?: return
        // Evidence horizon = the stable verification horizon: capped at certified coverage
        // so a snapshot written after its covered observation cannot bind the digest to
        // uncertified tail rows.
        val certifiedHorizonEpochMillis = certifiedEventUpperBound(stableThrough).toEpochMilliOrNull() ?: return
        val snapshotEpochMillis = snapshots
            .map { it.timestamp.toEpochMilliOrNull() ?: return }
            .maxOrNull() ?: return
        val evidenceHorizonEpochMillis = minOf(snapshotEpochMillis, certifiedHorizonEpochMillis)
        val evidenceFingerprint = automaticBaselineEvidenceDigest(inceptionTime, evidenceHorizonEpochMillis)
        // This proof is B&H economic evidence only. It must never rewrite
        // CONTINUOUS_HISTORY_START_EPOCH_MS: that key is reconstruction-owned truth about
        // retained snapshot continuity and gates rebuildHistoricalSnapshotsIfNeeded().
        repository.setSyncMetadataAtomically(
            mapOf(
                SyncMetadataKeys.INCEPTION_AUTO_BASELINE_VERIFICATION_VERSION to
                    AUTOMATIC_BASELINE_VERIFICATION_VERSION,
                SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS to AUTOMATIC_BASELINE_STATUS_VERIFIED,
                SyncMetadataKeys.INCEPTION_AUTO_BASELINE_TIMESTAMP_EPOCH_MS to
                    cursor.epochMillis.toString(),
                SyncMetadataKeys.INCEPTION_AUTO_BASELINE_SNAPSHOT_ID to snapshotId.toString(),
                SyncMetadataKeys.INCEPTION_AUTO_BASELINE_SNAPSHOT_CURSOR to cursor.encode(),
                SyncMetadataKeys.INCEPTION_AUTO_BASELINE_INCEPTION_EPOCH_MS to
                    inceptionTime.toEpochMilli().toString(),
                SyncMetadataKeys.INCEPTION_AUTO_BASELINE_CONFIG_FINGERPRINT to
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT).orEmpty(),
                SyncMetadataKeys.INCEPTION_AUTO_BASELINE_ACCOUNT_SCOPE_DIGEST to
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST)
                        .orEmpty(),
                SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_FINGERPRINT to evidenceFingerprint,
                SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS to
                    evidenceHorizonEpochMillis.toString(),
            ),
        )
        log.info(
            "automatic B&H baseline verified and persisted; baseline={} snapshotId={}",
            baselineTimestamp,
            snapshotId,
        )
    }

    /**
     * Evidence identity of the automatic baseline proof: every snapshot, trade, and ledger
     * row at or before the verified horizon, bound to the strategy inception the proof was
     * anchored on and the verification contract version. Tail rows after the horizon are
     * append-only evidence the proof did not consume — a new live snapshot, deposit, or
     * trade after the verified interval must not reset the baseline discovery. A row at or
     * before the horizon that is later edited, backfilled, or deleted changes the digest
     * and fails the record closed.
     */
    private suspend fun automaticBaselineEvidenceDigest(inceptionTime: Instant, horizonEpochMillis: Long): String {
        val snapshots = loadAllSnapshots(inceptionTime)
        val predecessorSnapshot = repository.getSnapshotBefore(inceptionTime)
        val trades = repository.getTradesInRange(Instant.EPOCH, Instant.ofEpochMilli(horizonEpochMillis))
        val ledgers =
            ledgerRepository.getLedgersInRange(Instant.EPOCH, Instant.ofEpochMilli(horizonEpochMillis))
        val material = buildString {
            append(AUTOMATIC_BASELINE_VERIFICATION_VERSION).append('\u0000')
            append(inceptionTime).append('\u0000')
            // The predecessor anchor participates in the full evaluation's event query
            // window, so its presence and content belong to the proof's consumed evidence.
            if (predecessorSnapshot == null) {
                append("predecessor:none\n")
            } else {
                append("predecessor\n")
                appendSnapshotDigest(predecessorSnapshot)
            }
            snapshots.forEach {
                if (it.timestamp.toEpochMilli() <= horizonEpochMillis) appendSnapshotDigest(it)
            }
            trades.sortedWith(compareBy({ it.timestamp }, { it.id ?: Int.MAX_VALUE }))
                .forEach { appendTradeDigest(it) }
            ledgers.sortedWith(compareBy({ it.time }, { it.ledgerId }))
                .forEach { appendLedgerDigest(it) }
        }
        return sha256Hex(material)
    }

    private suspend fun calculateComparison(
        orderedSnapshots: List<PortfolioSnapshot>,
        inceptionResolution: InceptionResolution?,
        preparedFundingProvenance: FundingProvenanceResolver? = null,
        // Every caller supplies the certified or otherwise explicitly bounded evidence horizon;
        // replaying events beyond that bound could make an uncertified live tail look verified.
        eventUpperBound: Instant,
        // Operator-facing entries pass true when a usable approved baseline exists (see
        // shouldSuppressPassiveDiscovery). Proposal-search trials always pass false: each
        // trial synthesizes a per-candidate CONFIDENT resolution as scoping scaffolding, and
        // verification pivots on discovery re-anchoring at the candidate — suppressing it
        // there would make every trial reconcile the stale predecessor instead.
        suppressPassiveDiscovery: Boolean = false,
        onOhlcDependencyConsumed: ((ConsumedOhlcDependency) -> Unit)? = null,
    ): RebalancerComparison {
        val firstSnapshot = orderedSnapshots.first()
        val lastSnapshot = orderedSnapshots.last()
        val firstTimestamp = firstSnapshot.timestamp
        val lastTimestamp = lastSnapshot.timestamp
        val firstObservationTime = firstSnapshot.balancesObservedAt ?: firstTimestamp
        val lastObservationTime = lastSnapshot.balancesObservedAt ?: lastTimestamp

        // An ambiguous/rebuilt strategy inception blocks a lifetime reconstruction, but it need
        // not block a clearly-labelled passive benchmark. Re-anchor only at an actual recorded
        // post-floor snapshot; pending recovery and other unresolved states remain unavailable.
        // Window-relative discovery: the anchor must sit inside the evaluated series (see
        // findPureBenchmarkAnchor), so the floor is the range start, not retained-history start.
        // Passive discovery is a fallback for ambiguous, truncated, or baselineless
        // recovery — never an override of a usable explicit approval (the operator-facing
        // call sites suppress it via suppressPassiveDiscovery). A permissive materiality
        // rule would otherwise hijack an approved start at the first small-but-material
        // position and break the approved reconciliation.
        val recordedBenchmarkAnchor =
            if (suppressPassiveDiscovery) {
                null
            } else {
                findPureBenchmarkAnchor(
                    floor = maxOf(benchmarkHistoryFloor, firstTimestamp),
                    upperBound = lastTimestamp,
                )
            }

        if (inceptionResolution?.confidence == InceptionConfidence.RECOVERY_INCOMPLETE &&
            recordedBenchmarkAnchor == null
        ) {
            return RebalancerComparisonCalculator.calculate(
                snapshots = orderedSnapshots,
                trades = emptyList(),
                rewards = emptyList(),
                knownInceptionTime = inceptionResolution.inceptionTime,
                inceptionUnavailableReason = inceptionResolution.unavailableReason
                    ?: ComparisonUnavailableReason.INCEPTION_RECOVERY_INCOMPLETE,
            )
        }
        if (inceptionResolution?.confidence == InceptionConfidence.TRUNCATED && recordedBenchmarkAnchor == null) {
            // Migrated install whose early history was removed by a previous
            // retention era: no window-anchored number may stand in for a
            // lifetime baseline. The UI text tells the user to configure
            // the inception date manually.
            return RebalancerComparisonCalculator.calculate(
                snapshots = orderedSnapshots,
                trades = emptyList(),
                rewards = emptyList(),
                anchorSnapshot = null,
                inceptionSnapshot = null,
                knownInceptionTime = inceptionResolution.inceptionTime,
                historyTruncated = true,
            )
        }
        val inceptionSnapshot = recordedBenchmarkAnchor ?: inceptionResolution?.inceptionSnapshot
            ?: inceptionResolution?.inceptionTime?.let { time ->
                // Bounded fallback: only accept a snapshot within the same
                // +/-300s discovery window used by InceptionDiscoveryService.
                // An unbounded getSnapshotBefore() here silently anchored the
                // benchmark to an unrelated months-old snapshot when inception
                // was known but its snapshot had been pruned.
                val candidates =
                    repository.getSnapshotsInRange(
                        time.minusSeconds(300),
                        time.plusSeconds(30),
                    )
                candidates.minByOrNull {
                    kotlin.math.abs(
                        it.timestamp.epochSecond - time.epochSecond,
                    )
                }
            }

        // A pre-anchor predecessor belongs to the unresolved history and must not re-enter
        // reconciliation once the passive report has deliberately started at the recorded row.
        val anchorSnapshot = if (recordedBenchmarkAnchor == null) {
            repository.getSnapshotBefore(firstTimestamp)
        } else {
            null
        }
        val comparisonBaselineTimestamp = recordedBenchmarkAnchor?.timestamp
            ?: inceptionResolution?.inceptionTime
            ?: firstTimestamp
        // Stale-history policy: a displayed window may start after an invalidated reconstruction
        // interval while the lifetime comparison still resolves an inception or predecessor
        // baseline inside it. Any required snapshot dependency that is stale fails the whole
        // comparison closed until the affected history is rebuilt.
        if (isSnapshotStale(inceptionSnapshot) || isSnapshotStale(anchorSnapshot)) {
            return RebalancerComparisonCalculator.calculate(
                snapshots = orderedSnapshots,
                trades = emptyList(),
                rewards = emptyList(),
                knownInceptionTime = orderedSnapshots.first().timestamp,
                inceptionUnavailableReason = ComparisonUnavailableReason.HISTORICAL_COVERAGE_GAP,
            )
        }
        val eventQueryStart = listOfNotNull(
            inceptionSnapshot?.balancesObservedAt ?: inceptionSnapshot?.timestamp,
            anchorSnapshot?.balancesObservedAt ?: anchorSnapshot?.timestamp,
            firstObservationTime,
        ).minOrNull() ?: firstObservationTime

        // A passive re-anchor deliberately discards all pre-anchor history. Do not load earlier
        // trade/ledger rows into classifier or provenance preparation: an unrelated unresolved
        // event before the recorded anchor must not make the bounded passive report unavailable.
        val queryFrom = if (recordedBenchmarkAnchor != null) {
            // The anchor state already embodies every event at its own instant, so comparison
            // events must start strictly after it. Ledger timestamps are millisecond-precision,
            // which makes a +1ms bound exactly the exclusive boundary: no distinct event can
            // exist between the anchor instant and this query start.
            recordedBenchmarkAnchor.timestamp.plusMillis(1)
        } else {
            eventQueryStart.minusMillisIfLegacyObservation(anchorSnapshot, firstSnapshot)
        }
        val naturalQueryTo = maxOf(lastTimestamp, lastObservationTime)
            .plusMillis(RebalancerComparisonCalculator.MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS)
        val queryTo = minOf(naturalQueryTo, eventUpperBound)

        val trades = if (queryTo.isBefore(queryFrom)) {
            emptyList()
        } else {
            getTradesInRange(queryFrom, queryTo)
        }
        // Raw-evidence contract: the ledger repository is raw/unprojected (types = null on
        // ingestion). The classifier/replay layer is the semantic projection — never the query
        // layer. Pass the complete certified interval so unknown top-level types reach
        // LedgerFlowClassifier and fail closed as UNSUPPORTED/AMBIGUOUS instead of disappearing
        // here. `trade` rows are retained as continuity checkpoints and classify as TRADE_IGNORED.
        val ledgers = if (queryTo.isBefore(queryFrom)) {
            emptyList()
        } else {
            ledgerRepository.getLedgersInRange(queryFrom, queryTo)
        }
        val windowLedgerIds = ledgers.associateBy(LedgerEvent::ledgerId)
        // Validation context: strictly-before rows (inclusive-bound query + identity
        // dedupe keeps the exact-queryFrom row economic-window-only), used ONLY for
        // wallet-scope replay of the authoritative validator.
        val contextLedgers = ledgerRepository.getLedgersInRange(
            Instant.EPOCH,
            minOf(queryFrom, eventUpperBound),
        )
            .filterNot { it.ledgerId in windowLedgerIds }
        // Fail closed on trade markets that recorded history cannot interpret. Coverage-grade
        // ingestion preserves e.g. ADAEUR/XBTUSDT/XBTUSDC; their Kraken `cost` must never be
        // assigned to USD nor silently dropped. Economic replayability is decided by the shared
        // historical pair parser, so a delisted or no-longer-configured market (for example
        // STRCZUSD) remains comparable while an unparseable one still fails closed. Raw
        // retrieval may still be COMPLETE while economic comparison is UNAVAILABLE.
        val unsupportedTrade = trades.firstOrNull {
            it.success && !it.dryRun &&
                !it.timestamp.isBefore(queryFrom) && !it.timestamp.isAfter(queryTo) &&
                !it.isHistoricallyReplayable()
        }
        if (unsupportedTrade != null) {
            // Force UNAVAILABLE even if reconciliation would otherwise succeed:
            // an unsupported market has no trustworthy USD valuation.
            return RebalancerComparison(
                availability = ComparisonAvailability.UNAVAILABLE,
                confidence = null,
                baselineTimestamp = comparisonBaselineTimestamp,
                points = emptyList(),
                latestDifferenceUSD = null,
                latestDifferencePercent = null,
                unavailableReason = ComparisonUnavailableReason.UNSUPPORTED_TRADE,
                unavailableAt = unsupportedTrade.timestamp,
            )
        }
        // Malformed supported-market economics must fail closed, never become zero-value fills.
        val invalidTrade = trades.firstOrNull {
            it.success && !it.dryRun &&
                !it.timestamp.isBefore(queryFrom) && !it.timestamp.isAfter(queryTo) &&
                it.isHistoricallyReplayable() && !it.hasValidEconomicFields()
        }
        if (invalidTrade != null) {
            return RebalancerComparison(
                availability = ComparisonAvailability.UNAVAILABLE,
                confidence = null,
                baselineTimestamp = comparisonBaselineTimestamp,
                points = emptyList(),
                latestDifferenceUSD = null,
                latestDifferencePercent = null,
                unavailableReason = ComparisonUnavailableReason.UNSUPPORTED_TRADE,
                unavailableAt = invalidTrade.timestamp,
            )
        }
        // Contribution-time prices use the same retained market identities as inception recovery,
        // including pairs whose only fill predates the displayed comparison window. No pair is
        // guessed for an asset absent from the anchor, and no live ticker participates in an old
        // comparison.
        val retainedMarketTrades = repository.getTradesInRange(Instant.EPOCH, eventUpperBound)
        val priceProvider = historicalPriceProvider(
            marketPairsByBase = retainedMarketPairsByBase(retainedMarketTrades + trades),
            eventUpperBound = eventUpperBound,
            onOhlcDependencyConsumed = onOhlcDependencyConsumed,
        )
        return RebalancerComparisonCalculator.calculate(
            snapshots = orderedSnapshots,
            trades = trades,
            rewards = ledgers,
            ledgerContext = contextLedgers,
            anchorSnapshot = anchorSnapshot,
            inceptionSnapshot = inceptionSnapshot,
            knownInceptionTime = recordedBenchmarkAnchor?.timestamp ?: inceptionResolution?.inceptionTime,
            priceProvider = priceProvider,
            provenanceResolver = preparedFundingProvenance ?: fundingProvenanceResolver,
            configuredAssetUniverse = configService?.getConfig()?.allocations
                ?.map { Asset.normalizeLedgerAsset(it.symbol.value).uppercase() }
                ?.toSet(),
        )
    }

    /**
     * Finds the earliest trustworthy portfolio state expressing an invested benchmark thesis,
     * at or after [floor]. A row qualifies when it was genuinely recorded by a live balance
     * observation, or when it is the output of a complete reconstruction under the current
     * reconstruction and coverage contracts: both prove the same authoritative chain, while a
     * partially reconstructed or stale row stays excluded. The pure B&H anchor is a
     * fallback, not an override: a usable approved baseline (confident resolution with a
     * retained snapshot) governs and suppresses discovery at the call site; otherwise
     * inception stays informational and must not replace or suppress an evidence-proven
     * anchor. See [isRecordedAnchorCandidate] and [isReconstructedAnchorCandidate].
     *
     * The floor is the evaluated range start (never earlier than [benchmarkHistoryFloor]): a
     * zoomed window re-discovers its own anchor instead of inheriting a far-earlier one, so
     * the baseline always sits inside the reconciled series. A baseline far outside the
     * series forces months of pre-window events through boundary assignment and changes
     * attribution (unpriceable contributions, shifted ownership) versus the lifetime run —
     * the windowed chart must be the same benchmark logic applied to the visible range, not
     * a different reconciliation.
     */
    private suspend fun findPureBenchmarkAnchor(floor: Instant, upperBound: Instant): PortfolioSnapshot? {
        val now = nowProvider()
        val window = reconstructionWindow()
        val reconstructedRange = authoritativelyReconstructedRange(window)
        val effectiveFloor = maxOf(floor, benchmarkHistoryFloor)
        return repository
            .getAllSnapshotsInRange(benchmarkHistoryFloor, OPEN_ENDED_RANGE_END)
            .asSequence()
            .filter { snapshot ->
                !snapshot.timestamp.isBefore(effectiveFloor) &&
                    !snapshot.timestamp.isAfter(upperBound) &&
                    !snapshot.timestamp.isAfter(now) &&
                    (
                        isRecordedAnchorCandidate(snapshot, window) ||
                            isReconstructedAnchorCandidate(snapshot, reconstructedRange)
                        ) &&
                    expressesInvestedBenchmarkThesis(snapshot) &&
                    snapshot.totalValueUSD.signum() > 0 &&
                    snapshot.assets.any { (_, asset) -> asset.balance.signum() > 0 } &&
                    snapshot.assets.all { (symbol, asset) ->
                        val normalized = Asset.normalizeLedgerAsset(symbol).uppercase()
                        asset.balance.signum() >= 0 &&
                            asset.valueUSD.signum() >= 0 &&
                            (asset.balance.signum() == 0 || normalized == Asset.USD || asset.price.signum() > 0)
                    }
            }
            .minWithOrNull(compareBy(PortfolioSnapshot::timestamp))
    }

    /**
     * A passive-benchmark anchor must express an invested thesis, not a pre-deployment cash
     * state: the benchmark invests every later owner contribution by the anchor's asset value
     * weights, so a literally uninvested anchor leaves new money in cash and the benchmark
     * degenerates into a deposits ledger. Mirroring calculator weight participation (positive
     * balance and positive value, aliases normalized), the anchor is the earliest trustworthy
     * state whose non-cash value reaches [INVESTED_THESIS_MINIMUM_USD].
     *
     * The boundary is first intentional-scale exposure, not a majority: 49-vs-50 has no
     * portfolio meaning, while a sub-minimum position cannot express strategy intent — the
     * strategy itself cannot place, adjust, or exit anything below
     * `Settings.minimumOrderSizeUSD` (execution dust guards), so such a holding is dust,
     * not a thesis. A mostly-cash anchor is still a coherent benchmark (it truthfully holds
     * its cash weight); only a zero-investment anchor is degenerate.
     */
    private fun expressesInvestedBenchmarkThesis(snapshot: PortfolioSnapshot): Boolean {
        val valuesBySymbol = snapshot.assets.entries
            .filter { (_, asset) -> asset.balance.signum() > 0 && asset.valueUSD.signum() > 0 }
            .groupingBy { (symbol, _) -> Asset.normalizeLedgerAsset(symbol).uppercase() }
            .fold(BigDecimal.ZERO) { total, (_, asset) -> total.add(asset.valueUSD) }
        if (valuesBySymbol.isEmpty()) return false
        val invested = valuesBySymbol.entries
            .filter { it.key != Asset.USD }
            .fold(BigDecimal.ZERO) { total, (_, value) -> total.add(value) }
        return invested >= INVESTED_THESIS_MINIMUM_USD
    }

    /**
     * A retained snapshot is an evidence anchor only when its row was genuinely recorded by a live
     * balance observation instead of derived by a reconstruction pass:
     *
     * - a missing observation marker marks a derived row;
     * - an observation strictly before the row timestamp is the live-write signature
     *   (`PortfolioAnalyzerImpl` persists the row after the balance response) and is accepted even
     *   inside a recorded reconstruction range;
     * - without any reconstruction on record, every marker-carrying row is a live row;
     * - otherwise a marked row is recorded only outside a well-formed reconstruction range. A
     *   legacy reconstruction marker without a usable range cannot classify rows by metadata alone,
     *   so those rows are never promoted.
     */
    private fun isRecordedAnchorCandidate(snapshot: PortfolioSnapshot, window: ReconstructionWindow): Boolean {
        if (snapshot.isProvablyRecorded()) return true
        if (snapshot.balancesObservedAt == null) return false
        return when (window) {
            ReconstructionWindow.None -> true
            is ReconstructionWindow.Known -> !window.range.contains(snapshot.timestamp)
            ReconstructionWindow.Unclassifiable -> false
        }
    }

    /**
     * Reconstruction range whose output is authoritative under the current reconstruction and
     * coverage contracts: the same signal [staleReconstructedInterval] uses to declare a pass
     * current. An outdated or partial pass leaves every row ineligible.
     */
    private suspend fun authoritativelyReconstructedRange(window: ReconstructionWindow): ClosedRange<Instant>? =
        when (window) {
            is ReconstructionWindow.Known -> window.range.takeIf { staleReconstructedInterval() == null }
            ReconstructionWindow.None, ReconstructionWindow.Unclassifiable -> null
        }

    /**
     * A derived row may anchor the benchmark only when it carries no observation marker (the
     * current reconstruction writer's signature) and falls inside the authoritative range; either
     * condition alone cannot tell a current derived row apart from a legacy or recorded one.
     */
    private fun isReconstructedAnchorCandidate(
        snapshot: PortfolioSnapshot,
        authoritativeRange: ClosedRange<Instant>?,
    ): Boolean = authoritativeRange != null &&
        snapshot.balancesObservedAt == null &&
        authoritativeRange.contains(snapshot.timestamp)

    /** True when the row carries the live-write observation signature rather than a legacy default. */
    private fun PortfolioSnapshot.isProvablyRecorded(): Boolean = balancesObservedAt?.isBefore(timestamp) == true

    /**
     * Snapshot rows a comparison can actually use: everything from the first provably recorded row
     * onward. Rows before it cannot become the comparison baseline, so a stale reconstruction
     * behind them must not hide the recorded evidence that follows.
     */
    private fun List<PortfolioSnapshot>.participatingFromFirstRecordedRow(): List<PortfolioSnapshot> {
        val firstRecorded = indexOfFirst { it.isProvablyRecorded() }
        return if (firstRecorded <= 0) this else drop(firstRecorded)
    }

    /** Provenance of retained snapshot rows relative to the recorded snapshot reconstruction. */
    private sealed interface ReconstructionWindow {
        /** No reconstruction was ever recorded, so every marked row is a live observation. */
        data object None : ReconstructionWindow

        /** A reconstruction range was persisted; retained rows inside it are derived. */
        data class Known(val range: ClosedRange<Instant>) : ReconstructionWindow

        /**
         * A legacy reconstruction marker exists without a usable range. Legacy writers defaulted
         * the observation marker to the row timestamp, so derived rows cannot be told apart from
         * recorded rows by metadata alone.
         */
        data object Unclassifiable : ReconstructionWindow
    }

    /**
     * Scans retained snapshots at/after [after] for the earliest anchor that
     * yields a fully verified comparison. It may persist bounded search progress; see the
     * list-based implementation below.
     */
    suspend fun findVerifiedLaterComparisonStart(after: Instant): Instant? = findLaterComparisonStartProposal(
        startAfter = after,
        inceptionResolution = null,
    ).timestamp

    /**
     * Scans retained snapshots after [startAfter] for the earliest anchor that
     * yields a fully verified comparison. Each trial re-runs the complete
     * reconciliation pipeline; one batched funding-provenance preparation may
     * refresh authoritative evidence within its short cache window. A returned
     * timestamp is evidence-backed rather than "the oldest snapshot we still
     * have". Window-independent: trials cover all retained snapshots, never
     * just the requested display range.
     */
    internal suspend fun findLaterComparisonStartProposal(
        startAfter: Instant,
        inceptionResolution: InceptionResolution?,
        /** Skip all candidates before an evidence event that provably fails inside every scan window. */
        skipCandidatesBefore: Instant? = null,
    ): ComparisonStartProposal = historyEvidenceCoordinator.withLock {
        val resolvedInception = inceptionResolution
            ?: inceptionDiscoveryService?.resolveInceptionUnderEvidenceLock()
        findLaterComparisonStartProposalLocked(startAfter, resolvedInception, skipCandidatesBefore)
    }

    private suspend fun findLaterComparisonStartProposalLocked(
        startAfter: Instant,
        inceptionResolution: InceptionResolution?,
        skipCandidatesBefore: Instant? = null,
    ): ComparisonStartProposal {
        return proposalSearchMutex.withLock {
            // Reload all economic evidence after acquiring the mutex. A concurrent Settings and
            // History request must not fingerprint a stale snapshot list and then overwrite newer
            // durable progress with an older cursor.
            val loadedSnapshots = loadAllSnapshots(inceptionResolution?.inceptionTime ?: startAfter)
            if (loadedSnapshots.size < 2) {
                return@withLock ComparisonStartProposal(ComparisonProposalStatus.INCOMPLETE)
            }
            val stableThrough = latestConfirmedEconomicCoverage(
                requiredStart = requiredCoverageStart(
                    baselineStart = effectiveReplayBaselineStart(
                        inceptionResolution,
                        loadedSnapshots.first(),
                        startAfter,
                        nowProvider(),
                    ),
                    firstSnapshot = loadedSnapshots.first(),
                ),
            ) ?: return@withLock ComparisonStartProposal(ComparisonProposalStatus.INCOMPLETE)
            val eventUpperBound = certifiedEventUpperBound(stableThrough)
            val certifiedEventHorizonEpochMillis = eventUpperBound.toEpochMilliOrNull()
                ?: return@withLock ComparisonStartProposal(ComparisonProposalStatus.INCOMPLETE)
            val firstUncoveredIndex = loadedSnapshots.indexOfFirst {
                !isSnapshotCoveredByHistory(it, stableThrough)
            }
            if (firstUncoveredIndex >= 0) {
                val reentry = loadedSnapshots.drop(firstUncoveredIndex + 1).indexOfFirst {
                    isSnapshotCoveredByHistory(it, stableThrough)
                }
                if (reentry >= 0) {
                    return@withLock ComparisonStartProposal(ComparisonProposalStatus.INCOMPLETE)
                }
            }
            val orderedSnapshots = if (firstUncoveredIndex >= 0) {
                loadedSnapshots.take(firstUncoveredIndex)
            } else {
                loadedSnapshots
            }
            if (orderedSnapshots.size < 2) {
                return@withLock ComparisonStartProposal(ComparisonProposalStatus.INCOMPLETE)
            }
            // A stale reconstruction interval invalidates every candidate that overlaps it, so no
            // candidate can be declared VERIFIED until a successful rebuild. Fail the search
            // incomplete instead of scanning snapshots whose reconstruction contract is stale.
            if (overlapsStaleReconstruction(orderedSnapshots)) {
                return@withLock ComparisonStartProposal(ComparisonProposalStatus.INCOMPLETE)
            }
            if (historicalCoverageGapExists(
                    snapshots = orderedSnapshots,
                    strategyStart = inceptionResolution?.inceptionTime ?: startAfter,
                    inceptionResolution = inceptionResolution,
                )
            ) {
                return@withLock ComparisonStartProposal(ComparisonProposalStatus.INCOMPLETE)
            }
            val allCandidates = orderedSnapshots.filter { it.timestamp > startAfter }
            val predecessorSnapshot = allCandidates.firstOrNull()?.let { repository.getSnapshotBefore(it.timestamp) }
            val orderedSnapshotEpochMillis = orderedSnapshots.map {
                it.timestamp.toEpochMilliOrNull()
                    ?: return@withLock ComparisonStartProposal(ComparisonProposalStatus.INCOMPLETE)
            }
            val predecessorEpochMillis = predecessorSnapshot?.timestamp?.toEpochMilliOrNull()
                ?: predecessorSnapshot?.let {
                    return@withLock ComparisonStartProposal(ComparisonProposalStatus.INCOMPLETE)
                }
            // Read durable progress before fingerprinting: the stored evidence horizon bounds
            // which rows participate in the digest. Candidates at or before it were already
            // evaluated; append-only rows after it must not invalidate that progress (see
            // proposalEvidenceDigest).
            val storedFingerprint = repository.getSyncMetadata(
                SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FINGERPRINT,
            )
            val storedStatus = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS)
            val storedCursorRaw = repository.getSyncMetadata(
                SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS,
            )
            val storedCursor = storedCursorRaw?.let(ProposalCursor::parse)
            val storedEvidenceHorizon = repository.getSyncMetadata(
                SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_EVIDENCE_HORIZON_MS,
            )?.toLongOrNull()
            val storedEvidenceHorizonOutOfScope = storedEvidenceHorizon != null &&
                (storedEvidenceHorizon < 0L || storedEvidenceHorizon > certifiedEventHorizonEpochMillis)
            val boundedStoredEvidenceHorizon = storedEvidenceHorizon
                ?.takeUnless { storedEvidenceHorizonOutOfScope }
            val storedCoverageHorizon = repository.getSyncMetadata(
                SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_COVERAGE_HORIZON_MS,
            )?.toLongOrNull()
            val storedFrontierReason = repository.getSyncMetadata(
                SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_REASON,
            )
            val storedFrontierCursorRaw = repository.getSyncMetadata(
                SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_CURSOR_EPOCH_MS,
            )?.toLongOrNull()
            // Raw evidence, funding provenance, fingerprints, and the candidate universe all
            // share the same certified event boundary. Rows beyond it are live-tail evidence,
            // not input that a proposal can consume or cache as VERIFIED.
            val trades = repository.getTradesInRange(Instant.EPOCH, eventUpperBound)
            val ledgers = ledgerRepository.getLedgersInRange(Instant.EPOCH, eventUpperBound)
            val rawLatestRowEpochMillis = maxOf(
                orderedSnapshotEpochMillis.maxOrNull() ?: 0L,
                predecessorEpochMillis ?: 0L,
                trades.maxOfOrNull { it.timestamp.toEpochMilli() } ?: 0L,
                ledgers.maxOfOrNull { it.time.toEpochMilli() } ?: 0L,
            )
            val latestRowEpochMillis = minOf(rawLatestRowEpochMillis, certifiedEventHorizonEpochMillis)
            // Revalidate the stored prefix under its own horizon first. A match proves every
            // evaluated candidate's evidence is unchanged; only then may the horizon advance to
            // the newest row so the growing tail becomes a fresh segment instead of invalidating
            // proven progress.
            val prefixFingerprint = proposalEvidenceDigest(
                orderedSnapshots = orderedSnapshots,
                startAfter = startAfter,
                inceptionResolution = inceptionResolution,
                predecessorSnapshot = predecessorSnapshot,
                trades = trades,
                ledgers = ledgers,
                horizonEpochMillis = boundedStoredEvidenceHorizon ?: latestRowEpochMillis,
                certifiedEventHorizonEpochMillis = certifiedEventHorizonEpochMillis,
            )
            val coverageBoundaryMatches = storedCoverageHorizon == certifiedEventHorizonEpochMillis
            val canResume = !storedEvidenceHorizonOutOfScope && coverageBoundaryMatches &&
                storedFingerprint == prefixFingerprint
            val horizonAdvanced = canResume &&
                boundedStoredEvidenceHorizon != null &&
                latestRowEpochMillis > boundedStoredEvidenceHorizon
            // A reset (fresh or invalidated stored state) and a horizon advance both derive the
            // bound from the newest row, so every persisted state covers its candidate universe
            // exactly — no tested candidate is ever digested outside its own fingerprint.
            val appliedEvidenceHorizon = if (canResume && !horizonAdvanced) {
                boundedStoredEvidenceHorizon ?: latestRowEpochMillis
            } else {
                latestRowEpochMillis
            }
            val fingerprint = proposalEvidenceDigest(
                orderedSnapshots = orderedSnapshots,
                startAfter = startAfter,
                inceptionResolution = inceptionResolution,
                predecessorSnapshot = predecessorSnapshot,
                trades = trades,
                ledgers = ledgers,
                horizonEpochMillis = appliedEvidenceHorizon,
                certifiedEventHorizonEpochMillis = certifiedEventHorizonEpochMillis,
            )
            val candidates = allCandidates.filter {
                it.timestamp.toEpochMilli() <= appliedEvidenceHorizon
            }
            val storedFundingEvidenceFingerprint = repository.getSyncMetadata(
                SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FUNDING_FINGERPRINT,
            ).orEmpty()
            val preparedFundingProvenance = if (candidates.isNotEmpty()) {
                // Pin one immutable provenance snapshot for the complete bounded scan. Passing
                // it into every trial prevents a candidate loop from issuing one network-backed
                // funding request per reconciliation attempt.
                fundingProvenanceResolver.prepare(
                    ledgers,
                )
            } else {
                null
            }
            val fundingEvidenceFingerprint = preparedFundingProvenance?.evidenceFingerprint
            val fundingEvidenceChanged = fundingEvidenceFingerprint != null &&
                fundingEvidenceFingerprint != storedFundingEvidenceFingerprint
            val skipIndex = skipCandidatesBefore?.let { eventTime ->
                candidates.indexOfFirst { it.timestamp >= eventTime }.takeIf { it >= 0 }
            }
            val frontierSensitive = APPEND_SENSITIVE_FRONTIER_REASONS.any { it.name == storedFrontierReason }
            // A pointer to an append-sensitive failure predates the stored VERIFIED anchor: a
            // horizon advance may cure it and move the earliest verified start earlier, so the
            // durable VERIFIED state may not short-circuit the reopened scan.
            val reopensPastStoredVerified = canResume && !fundingEvidenceChanged &&
                horizonAdvanced && frontierSensitive && storedFrontierCursorRaw != null
            if (preparedFundingProvenance?.preparationFailure != null) {
                // A funding preparation failure means the evidence fingerprint is unknown.
                // Even an unchanged local horizon cannot prove that the cached VERIFIED state
                // remains valid, so never reuse it without a fresh immutable provenance snapshot.
                persistProposalSearchState(
                    fingerprint = fingerprint,
                    status = ComparisonProposalStatus.INCOMPLETE,
                    cursor = candidates.proposalCursorAt(0).encode(),
                    fundingEvidenceFingerprint = null,
                    evidenceHorizonEpochMillis = appliedEvidenceHorizon,
                    coverageHorizonEpochMillis = certifiedEventHorizonEpochMillis,
                )
                return@withLock ComparisonStartProposal(ComparisonProposalStatus.INCOMPLETE)
            }
            if (canResume && !fundingEvidenceChanged && storedStatus == ComparisonProposalStatus.VERIFIED.name) {
                storedCursor?.let { cursor ->
                    val verifiedIndex = candidates.indexOfProposalCursor(cursor)
                    if (verifiedIndex >= 0 && (skipIndex == null || skipIndex <= verifiedIndex)) {
                        val storedSnapshotId = repository.getSyncMetadata(
                            SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_SNAPSHOT_ID,
                        )?.toIntOrNull()
                        val durableSnapshotId = repository.getSnapshotId(
                            candidates[verifiedIndex].timestamp,
                            cursor.ordinal,
                        )
                        if (storedSnapshotId != null && durableSnapshotId == storedSnapshotId) {
                            if (!reopensPastStoredVerified) {
                                // Re-evaluate every cached VERIFIED candidate. The local digest
                                // cannot identify changes in bounded external OHLC evidence, and
                                // the provider/cache may have restarted or corrected a historical
                                // price since the state was persisted. A horizon advance is one
                                // reason to do this; it is not the only one.
                                val revalidated = calculateComparison(
                                    candidates.drop(verifiedIndex),
                                    InceptionResolution(
                                        inceptionTime = candidates[verifiedIndex].timestamp,
                                        inceptionSnapshot = candidates[verifiedIndex],
                                        isAutoDetected = false,
                                    ),
                                    preparedFundingProvenance = preparedFundingProvenance,
                                    eventUpperBound = eventUpperBound,
                                )
                                if (revalidated.availability == ComparisonAvailability.AVAILABLE) {
                                    // The pending frontier mark is unresolved earliest-start
                                    // state, not superseded by this anchor's revalidation: a
                                    // later horizon advance must still reopen at it
                                    // (reopensPastStoredVerified). Omitting the keys here
                                    // would atomically blank them and strand the mark. A
                                    // reason that no longer maps to any known class carries
                                    // no reopen semantics, so its cursor is dropped with it.
                                    val carriedFrontierReason = APPEND_SENSITIVE_FRONTIER_REASONS
                                        .firstOrNull { it.name == storedFrontierReason }
                                    persistProposalSearchState(
                                        fingerprint = fingerprint,
                                        status = ComparisonProposalStatus.VERIFIED,
                                        cursor = cursor.encode(),
                                        snapshotId = storedSnapshotId,
                                        fundingEvidenceFingerprint = fundingEvidenceFingerprint,
                                        evidenceHorizonEpochMillis = appliedEvidenceHorizon,
                                        coverageHorizonEpochMillis = certifiedEventHorizonEpochMillis,
                                        frontierReason = carriedFrontierReason,
                                        frontierCursorEpochMillis = carriedFrontierReason?.let {
                                            storedFrontierCursorRaw
                                        },
                                    )
                                    return@withLock ComparisonStartProposal(
                                        status = ComparisonProposalStatus.VERIFIED,
                                        timestamp = candidates[verifiedIndex].timestamp,
                                        snapshotId = storedSnapshotId,
                                    )
                                }
                                // Not AVAILABLE any more: continue proposal discovery under
                                // the enlarged horizon (the full scan below re-evaluates, and
                                // its persist overwrites the stale VERIFIED state).
                            }
                        }
                    }
                }
            }
            // Terminal exhaustion only holds while no evidence arrived after the persisted
            // horizon. A newer tail extends the open-ended candidate universe, so the scan
            // reopens at its frontier and evaluates the appended rows under a horizon that
            // includes them. A pending append-sensitive mark does not force a re-scan on an
            // unchanged horizon: every ROW that could cure it is itself evidence whose
            // arrival advances the horizon, so the mark stays persisted and reopens there —
            // with one exception below for price-sensitive frontiers, whose cure can be
            // digest-invisible external price evidence.
            if (canResume && !fundingEvidenceChanged &&
                storedStatus == ComparisonProposalStatus.EXHAUSTED.name &&
                !horizonAdvanced
            ) {
                val storedPriceFrontierReason = storedFrontierReason?.let { reason ->
                    PRICE_SENSITIVE_FRONTIER_REASONS.firstOrNull { it.name == reason }
                }
                val storedPriceFrontierIndex = if (storedPriceFrontierReason != null &&
                    storedFrontierCursorRaw != null
                ) {
                    candidates.indexOfFirst {
                        it.timestamp.toEpochMilli() >= storedFrontierCursorRaw
                    }.takeIf { it >= 0 }
                } else {
                    null
                }
                // A price-sensitive frontier candidate pinned before an intrinsic failure
                // event must not resurface, matching the scan's skipCandidatesBefore guard.
                if (storedPriceFrontierIndex == null ||
                    (skipIndex != null && storedPriceFrontierIndex < skipIndex)
                ) {
                    return@withLock ComparisonStartProposal(ComparisonProposalStatus.EXHAUSTED)
                }
                // External price evidence (OHLC backfill, historical correction, provider
                // recovery) can cure this candidate with no new row, so the horizon cannot
                // witness the change and the terminal branch above would suppress an earlier
                // verified start indefinitely. Probe exactly the stored frontier candidate
                // once per call — the same bounded per-call revalidation cost the VERIFIED
                // path already pays — never a rescan of the candidate universe.
                val frontierCandidate = candidates[storedPriceFrontierIndex]
                val frontierTrial = calculateComparison(
                    candidates.drop(storedPriceFrontierIndex),
                    InceptionResolution(
                        inceptionTime = frontierCandidate.timestamp,
                        inceptionSnapshot = frontierCandidate,
                        isAutoDetected = false,
                    ),
                    preparedFundingProvenance = preparedFundingProvenance,
                    eventUpperBound = eventUpperBound,
                )
                if (frontierTrial.availability == ComparisonAvailability.AVAILABLE) {
                    val frontierCursor = candidates.proposalCursorAt(storedPriceFrontierIndex)
                    val frontierSnapshotId = repository.getSnapshotId(
                        frontierCandidate.timestamp,
                        frontierCursor.ordinal,
                    )
                    persistProposalSearchState(
                        fingerprint = fingerprint,
                        status = ComparisonProposalStatus.VERIFIED,
                        cursor = frontierCursor.encode(),
                        snapshotId = frontierSnapshotId,
                        fundingEvidenceFingerprint = fundingEvidenceFingerprint,
                        evidenceHorizonEpochMillis = appliedEvidenceHorizon,
                        coverageHorizonEpochMillis = certifiedEventHorizonEpochMillis,
                    )
                    return@withLock ComparisonStartProposal(
                        status = ComparisonProposalStatus.VERIFIED,
                        timestamp = frontierCandidate.timestamp,
                        snapshotId = frontierSnapshotId,
                    )
                }
                return@withLock ComparisonStartProposal(ComparisonProposalStatus.EXHAUSTED)
            }

            // The persisted frontier is the EARLIEST append-sensitive failure carried across
            // calls — later non-sensitive failures do not supersede it, because the earliest
            // verified start contract keeps that candidate's re-evaluation open even when its
            // successor later failed non-sensitively or a verified anchor sits after it.
            // Carry the persisted mark into the loop on EVERY resumable call — not only
            // horizon advances — so a pending mark survives cursored resumes bounded by the
            // phased calls. Each persist re-writes the earliest pending failure.
            val storedFrontierIndex = if (canResume && !fundingEvidenceChanged &&
                frontierSensitive && storedFrontierCursorRaw != null
            ) {
                candidates.indexOfFirst {
                    it.timestamp.toEpochMilli() >= storedFrontierCursorRaw
                }.takeIf { it >= 0 }
            } else {
                null
            }
            val tailResumeIndex = if (horizonAdvanced) {
                val advancedTailStart = boundedStoredEvidenceHorizon
                candidates.indexOfFirst { it.timestamp.toEpochMilli() > advancedTailStart }
                    .takeIf { it >= 0 }
            } else {
                null
            }
            val resumeIndex = if (canResume && !fundingEvidenceChanged &&
                storedStatus == ComparisonProposalStatus.INCOMPLETE.name
            ) {
                val cursorIndex = storedCursor
                    ?.let { cursor -> candidates.indexOfProposalCursor(cursor) }
                    ?.takeIf { it >= 0 } ?: 0
                // An advanced horizon rewinds through the persisted append-sensitive frontier
                // marker: those earlier failures may now be curable, while proven non-sensitive
                // prefix failures stay final.
                if (horizonAdvanced) {
                    minOf(cursorIndex, storedFrontierIndex ?: cursorIndex)
                } else {
                    cursorIndex
                }
            } else if (horizonAdvanced && !fundingEvidenceChanged) {
                // New funding evidence can flip any earlier candidate's outcome, so it bans
                // frontier/tail shortcuts the same way it bans cursor resumption: rescan.
                storedFrontierIndex
                    ?: tailResumeIndex
                    // Horizon advanced without a mappable cursor position: re-scan the whole
                    // enlarged segment rather than trusting stale index arithmetic.
                    ?: 0
            } else {
                0
            }
            var index = maxOf(resumeIndex, skipIndex ?: 0)
            var trials = 0
            var frontierCursorIndex = storedFrontierIndex
            var frontierRunReason: ComparisonUnavailableReason? =
                if (canResume && !fundingEvidenceChanged) {
                    APPEND_SENSITIVE_FRONTIER_REASONS.firstOrNull { it.name == storedFrontierReason }
                } else {
                    null
                }
            while (index < candidates.size && trials < PROPOSAL_MAX_TRIALS) {
                val candidate = candidates[index]
                val trial = calculateComparison(
                    candidates.drop(index),
                    InceptionResolution(
                        inceptionTime = candidate.timestamp,
                        inceptionSnapshot = candidate,
                        isAutoDetected = false,
                    ),
                    preparedFundingProvenance = preparedFundingProvenance,
                    eventUpperBound = eventUpperBound,
                )
                trials++
                if (trial.availability == ComparisonAvailability.AVAILABLE) {
                    val candidateCursor = candidates.proposalCursorAt(index)
                    val candidateSnapshotId = repository.getSnapshotId(
                        candidate.timestamp,
                        candidateCursor.ordinal,
                    )
                    // The marker candidate was re-reached during this scan — an AVAILABLE
                    // anchor cures the pending mark; a mark that was carried but not reached
                    // in this call stays persisted so the next horizon advance reopens there.
                    val frontierCured = frontierCursorIndex == index
                    persistProposalSearchState(
                        fingerprint = fingerprint,
                        status = ComparisonProposalStatus.VERIFIED,
                        cursor = candidateCursor.encode(),
                        snapshotId = candidateSnapshotId,
                        fundingEvidenceFingerprint = fundingEvidenceFingerprint,
                        evidenceHorizonEpochMillis = appliedEvidenceHorizon,
                        coverageHorizonEpochMillis = certifiedEventHorizonEpochMillis,
                        frontierReason = if (frontierCured) null else frontierRunReason,
                        frontierCursorEpochMillis = if (frontierCured) {
                            null
                        } else {
                            frontierCursorIndex?.let { candidates[it].timestamp.toEpochMilli() }
                        },
                    )
                    return@withLock ComparisonStartProposal(
                        status = ComparisonProposalStatus.VERIFIED,
                        timestamp = candidate.timestamp,
                        snapshotId = candidateSnapshotId,
                    )
                }
                // Keep the EARLIEST append-sensitive failure, not the trailing run: candidate
                // validity is independent, and a non-sensitive failure after it must not erase
                // the earlier uncertainty. A non-sensitive failure of the marked candidate
                // itself does supersede it — its own outcome is now final.
                if (trial.unavailableReason in APPEND_SENSITIVE_FRONTIER_REASONS) {
                    if (frontierCursorIndex == null ||
                        candidate.timestamp < candidates[frontierCursorIndex].timestamp
                    ) {
                        frontierCursorIndex = index
                        frontierRunReason = trial.unavailableReason
                    }
                } else if (frontierCursorIndex == index) {
                    frontierCursorIndex = null
                    frontierRunReason = null
                }
                // Advance exactly one candidate. A trial's unavailableAt is a failure point in
                // that trial, not proof that every earlier retained candidate is invalid.
                index++
            }
            val status = if (index < candidates.size) {
                ComparisonProposalStatus.INCOMPLETE
            } else {
                ComparisonProposalStatus.EXHAUSTED
            }
            persistProposalSearchState(
                fingerprint = fingerprint,
                status = status,
                cursor = if (status == ComparisonProposalStatus.INCOMPLETE) {
                    candidates.proposalCursorAt(index).encode()
                } else {
                    PROPOSAL_CURSOR_EXHAUSTED
                },
                fundingEvidenceFingerprint = fundingEvidenceFingerprint,
                evidenceHorizonEpochMillis = appliedEvidenceHorizon,
                coverageHorizonEpochMillis = certifiedEventHorizonEpochMillis,
                frontierReason = frontierRunReason,
                frontierCursorEpochMillis = frontierCursorIndex?.let {
                    candidates[it].timestamp.toEpochMilli()
                },
            )
            ComparisonStartProposal(status)
        }
    }

    private suspend fun loadAllSnapshots(from: Instant): List<PortfolioSnapshot> =
        repository.getAllSnapshotsInRange(from, OPEN_ENDED_RANGE_END).sortedBy { it.timestamp }

    private suspend fun historicalCoverageGapExists(
        strategyStart: Instant,
        inceptionResolution: InceptionResolution?,
    ): Boolean = historicalCoverageGapExists(
        loadAllSnapshots(strategyStart),
        strategyStart,
        inceptionResolution,
    )

    private suspend fun historicalCoverageGapExists(
        snapshots: List<PortfolioSnapshot>,
        strategyStart: Instant,
        inceptionResolution: InceptionResolution?,
    ): Boolean {
        val now = nowProvider()
        if (strategyStart.isAfter(now)) return false

        val retained = snapshots.filter {
            !it.timestamp.isBefore(strategyStart) && !it.timestamp.isAfter(now)
        }.sortedBy { it.timestamp }
        if (retained.isEmpty()) return false

        val verifiedBaseline = inceptionResolution?.let { readVerifiedAutomaticBaseline(it) }
        val isVerifiedInception = verifiedBaseline != null &&
            verifiedBaseline.baselineTimestamp == strategyStart.toString()
        // The proof is scoped to the interval it actually reconciled: [strategyStart, verifiedHorizon].
        // A malformed or pre-inception horizon grants no exemption, so continuity heuristics run.
        val verifiedHorizon = if (isVerifiedInception) {
            repository.getSyncMetadata(
                SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS,
            )?.toLongOrNull()?.let(Instant::ofEpochMilli)?.takeIf { !it.isBefore(strategyStart) }
        } else {
            null
        }

        if (verifiedHorizon == null) {
            val continuousStart = resolveContinuousHistoryStart(snapshots)
            if (Duration.between(strategyStart, continuousStart).seconds > MAX_COVERAGE_GAP_SECONDS) {
                // Strategy started before continuous history was established (e.g. lost under legacy retention).
                return true
            }

            val first = retained.first()
            if (Duration.between(strategyStart, first.timestamp).seconds > MAX_COVERAGE_GAP_SECONDS) {
                return true
            }
        } else {
            log.debug(
                "Historical continuity satisfied by verified automatic baseline proof; " +
                    "baseline={} verifiedThrough={}",
                strategyStart,
                verifiedHorizon,
            )
        }

        return retained.zipWithNext().any { (previous, current) ->
            if (verifiedHorizon != null && !current.timestamp.isAfter(verifiedHorizon)) {
                false
            } else {
                Duration.between(previous.timestamp, current.timestamp).seconds > MAX_COVERAGE_GAP_SECONDS
            }
        }
    }

    private suspend fun resolveContinuousHistoryStart(snapshots: List<PortfolioSnapshot>): Instant {
        val storedEpoch = repository.getSyncMetadata(SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS)
            ?.toLongOrNull()
        if (storedEpoch != null && storedEpoch >= 0L) {
            val stored = Instant.ofEpochMilli(storedEpoch)
            val earliestSnapshot = snapshots.minByOrNull { it.timestamp }?.timestamp
            if (earliestSnapshot == null || !earliestSnapshot.isBefore(stored)) {
                return stored
            }
        }

        val installType = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_INSTALL_TYPE)
        val isFresh = installType == InceptionDiscoveryService.INSTALL_TYPE_FRESH && !repository.isHistorySeeded()

        val determinedStart = if (isFresh) {
            Instant.EPOCH
        } else {
            determineContinuousHistoryStart(snapshots)
        }

        val storedValue = if (isFresh) "0" else determinedStart.toEpochMilli().toString()
        repository.setSyncMetadata(
            SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS,
            storedValue,
        )
        return determinedStart
    }

    private fun determineContinuousHistoryStart(snapshots: List<PortfolioSnapshot>): Instant {
        if (snapshots.isEmpty()) return Instant.EPOCH
        val sorted = snapshots.sortedBy { it.timestamp }
        for (i in sorted.lastIndex downTo 1) {
            val previous = sorted[i - 1]
            val current = sorted[i]
            if (Duration.between(previous.timestamp, current.timestamp).seconds > MAX_COVERAGE_GAP_SECONDS) {
                return current.timestamp
            }
        }
        return sorted.first().timestamp
    }

    /**
     * Returns the stale reconstructed interval [start, through] when the snapshot reconstruction
     * contract is no longer current but a previous reconstruction range is still recorded.
     * Null means either no reconstruction was ever recorded (all snapshots are live) or the
     * contract is current (reconstructed snapshots are trustworthy).
     */
    private suspend fun staleReconstructedInterval(): Pair<Instant, Instant>? {
        val version = repository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION)
        val ledgerCoverage = repository.getSyncMetadata(
            SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_LEDGER_COVERAGE_VERSION,
        )
        val tradeCoverage = repository.getSyncMetadata(
            SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_TRADE_COVERAGE_VERSION,
        )
        val isCurrent =
            version == TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION &&
                ledgerCoverage == LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION &&
                tradeCoverage == TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
        if (isCurrent) return null
        val throughRaw = repository.getSyncMetadata(
            SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC,
        )
        val throughSec = throughRaw?.toLongOrNull()
        if (throughSec == null) {
            return if (version.isNullOrBlank() && throughRaw.isNullOrBlank()) {
                null
            } else {
                Instant.MIN to Instant.MAX
            }
        }
        // Version blank with no through means never reconstructed — live snapshots only.
        if (version.isNullOrBlank() && throughSec == 0L) return null
        val startSec = repository.getSyncMetadata(
            SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_START_EPOCH_SEC,
        )?.toLongOrNull() ?: Instant.EPOCH.epochSecond
        if (throughSec <= 0L || startSec < 0L || startSec > throughSec) {
            return Instant.MIN to Instant.MAX
        }
        return try {
            // Reconstruction metadata is persisted in epoch seconds, while trade and snapshot
            // timestamps retain milliseconds. Include the complete terminal second so a derived
            // row at e.g. 12:00:00.608Z cannot escape the stale-history guard.
            Instant.ofEpochSecond(startSec) to Instant.ofEpochSecond(throughSec, 999_999_999)
        } catch (_: RuntimeException) {
            // Malformed metadata must not make reconstructed history appear trustworthy.
            Instant.MIN to Instant.MAX
        }
    }

    /**
     * Reads the interval written by the last snapshot reconstruction, regardless of whether that
     * reconstruction is still current. Older writers defaulted [PortfolioSnapshot]'s observation
     * marker to the row timestamp, so the interval remains necessary to exclude those legacy
     * derived rows from a recorded-only passive anchor search.
     *
     * A marker without a persisted range is [ReconstructionWindow.Unclassifiable] rather than a
     * blanket exclusion, because rows carrying the live-observation signature are recorded evidence
     * regardless of reconstruction metadata.
     */
    private suspend fun reconstructionWindow(): ReconstructionWindow {
        val version = repository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION)
        val throughRaw = repository.getSyncMetadata(
            SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC,
        )
        val throughSec = throughRaw?.toLongOrNull()
        if (version.isNullOrBlank() && (throughRaw.isNullOrBlank() || throughSec == 0L)) {
            return ReconstructionWindow.None
        }
        if (throughSec == null || throughSec <= 0L) return ReconstructionWindow.Unclassifiable
        val startSec = repository.getSyncMetadata(
            SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_START_EPOCH_SEC,
        )?.toLongOrNull()
        if (startSec == null || startSec < 0L || startSec > throughSec) {
            return ReconstructionWindow.Unclassifiable
        }
        return try {
            // The persisted endpoint has second precision; cover that entire second because
            // snapshot rows retain sub-second precision.
            ReconstructionWindow.Known(
                Instant.ofEpochSecond(startSec)..Instant.ofEpochSecond(throughSec, 999_999_999),
            )
        } catch (_: RuntimeException) {
            // Malformed metadata must not make reconstructed history appear trustworthy.
            ReconstructionWindow.Unclassifiable
        }
    }

    /**
     * True when any provided snapshot is stale reconstruction output. A provably recorded row is
     * live evidence even when it falls inside an invalidated reconstruction interval.
     */
    private suspend fun overlapsStaleReconstruction(snapshots: List<PortfolioSnapshot>): Boolean {
        val stale = staleReconstructedInterval() ?: return false
        val (reconStart, reconThrough) = stale
        return snapshots.any {
            !it.isProvablyRecorded() && !it.timestamp.isBefore(reconStart) && !it.timestamp.isAfter(reconThrough)
        }
    }

    /**
     * True when one snapshot dependency (inception baseline, predecessor anchor, or an explicitly
     * selected candidate baseline) falls inside an invalidated reconstruction interval. Null
     * dependencies are not stale. Every snapshot that participates in a comparison must pass this
     * check, not only the displayed window.
     */
    private suspend fun isSnapshotStale(snapshot: PortfolioSnapshot?): Boolean {
        if (snapshot == null) return false
        // A row with the live-observation signature is recorded evidence, not reconstruction
        // output, so an invalidated reconstruction window cannot make it stale.
        if (snapshot.isProvablyRecorded()) return false
        val stale = staleReconstructedInterval() ?: return false
        val (reconStart, reconThrough) = stale
        return !snapshot.timestamp.isBefore(reconStart) && !snapshot.timestamp.isAfter(reconThrough)
    }

    private fun List<PortfolioSnapshot>.proposalCursorAt(index: Int): ProposalCursor {
        val timestamp = this[index].timestamp
        val ordinal = subList(0, index).count { it.timestamp == timestamp }
        return ProposalCursor(timestamp.toEpochMilli(), ordinal)
    }

    private fun List<PortfolioSnapshot>.indexOfProposalCursor(cursor: ProposalCursor): Int {
        var ordinal = 0
        for (index in indices) {
            if (this[index].timestamp.toEpochMilli() != cursor.epochMillis) continue
            if (ordinal == cursor.ordinal) return index
            ordinal++
        }
        return -1
    }

    private suspend fun persistProposalSearchState(
        fingerprint: String,
        status: ComparisonProposalStatus,
        cursor: String,
        snapshotId: Int? = null,
        fundingEvidenceFingerprint: String?,
        evidenceHorizonEpochMillis: Long,
        coverageHorizonEpochMillis: Long,
        frontierReason: ComparisonUnavailableReason? = null,
        frontierCursorEpochMillis: Long? = null,
    ) {
        repository.setSyncMetadataAtomically(
            mapOf(
                SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FINGERPRINT to fingerprint,
                SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS to status.name,
                SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS to cursor,
                SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_SNAPSHOT_ID to snapshotId?.toString().orEmpty(),
                SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FUNDING_FINGERPRINT to
                    fundingEvidenceFingerprint.orEmpty(),
                SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_EVIDENCE_HORIZON_MS to
                    evidenceHorizonEpochMillis.toString(),
                SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_COVERAGE_HORIZON_MS to
                    coverageHorizonEpochMillis.toString(),
                SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_REASON to
                    frontierReason?.name.orEmpty(),
                SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_CURSOR_EPOCH_MS to
                    frontierCursorEpochMillis?.toString().orEmpty(),
            ),
        )
    }

    /**
     * The cursor is valid only for this configuration, effective start, account scope, and
     * evidence revision. The digest contains the complete retained rows rather than only the
     * display window so zooming cannot skip or reuse a candidate incorrectly.
     *
     * Identifies the economic evidence a proposal scan consumed over an explicit [horizonEpochMillis]
     * bound and certified coverage boundary: only rows at or before those instants participate.
     * Append-only tail rows after a
     * persisted horizon exclude themselves from the stored fingerprint, so the scan can resume
     * and the appended rows instead extend the universe as a new segment once the prefix is
     * revalidated — the caller derives the bound from the persisted horizon or the newest row.
     * Evidence at or before the horizon is still digested row-by-row, so a backfilled, edited,
     * or deleted historical row — anything that could change a tested candidate's outcome —
     * invalidates the stored progress. Acceptance of a verified start re-runs the full
     * reconciliation against current evidence, which keeps this append tolerance fail-closed
     * for the acceptance decision itself.
     */
    private suspend fun proposalEvidenceDigest(
        orderedSnapshots: List<PortfolioSnapshot>,
        startAfter: Instant,
        inceptionResolution: InceptionResolution?,
        predecessorSnapshot: PortfolioSnapshot?,
        trades: List<TradeRecord>,
        ledgers: List<LedgerEvent>,
        horizonEpochMillis: Long,
        certifiedEventHorizonEpochMillis: Long,
    ): String {
        val material = buildString {
            append(PROPOSAL_SEARCH_VERSION).append('\u0000')
            append(startAfter).append('\u0000')
            append(inceptionResolution?.inceptionTime).append('\u0000')
            append(certifiedEventHorizonEpochMillis).append('\u0000')
            append(repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT).orEmpty())
                .append('\u0000')
            append(repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST).orEmpty())
                .append('\u0000')
            append(repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_STATUS).orEmpty())
                .append('\u0000')
            append(repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_REASON).orEmpty())
                .append('\u0000')
            append(ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION).orEmpty())
                .append('\u0000')
            append(repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION).orEmpty())
                .append('\u0000')
            // Coverage watermarks count append-only fresh rows and grow with every live cycle;
            // they are freshness counters, not evidence identity. The certified event boundary
            // above is different: it defines the exact set of authoritative events the scan was
            // allowed to consume, so a boundary change must invalidate cached progress.
            // Reconstruction currentness participates in the fingerprint: invalidating or
            // rebuilding reconstructed history must force proposal re-trials instead of letting a
            // stored VERIFIED cursor resume against a different evidence baseline.
            append(repository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION).orEmpty())
                .append('\u0000')
            append(
                repository.getSyncMetadata(
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_START_EPOCH_SEC,
                ).orEmpty(),
            ).append('\u0000')
            append(
                repository.getSyncMetadata(
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC,
                ).orEmpty(),
            ).append('\u0000')
            append(
                repository.getSyncMetadata(
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_LEDGER_COVERAGE_VERSION,
                ).orEmpty(),
            ).append('\u0000')
            append(
                repository.getSyncMetadata(
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_TRADE_COVERAGE_VERSION,
                ).orEmpty(),
            ).append('\u0000')
            predecessorSnapshot?.let {
                append("predecessor\n")
                appendSnapshotDigest(it)
            }
            val horizon = horizonEpochMillis
            orderedSnapshots.forEach {
                if (it.timestamp.toEpochMilli() <= horizon) {
                    appendSnapshotDigest(it)
                }
            }
            trades.sortedWith(compareBy({ it.timestamp }, { it.id ?: Int.MAX_VALUE }))
                .forEach { if (it.timestamp.toEpochMilli() <= horizon) appendTradeDigest(it) }
            ledgers.sortedWith(compareBy({ it.time }, { it.ledgerId }))
                .forEach { if (it.time.toEpochMilli() <= horizon) appendLedgerDigest(it) }
        }
        return sha256Hex(material)
    }

    private fun StringBuilder.appendSnapshotDigest(snapshot: PortfolioSnapshot) {
        append("snapshot|").append(snapshot.timestamp).append('|')
            .append(snapshot.balancesObservedAt).append('|')
            .append(snapshot.totalValueUSD.digestValue()).append('\n')
        snapshot.assets.toSortedMap().forEach { (key, asset) ->
            append("asset|").append(key).append('|').append(asset.symbol.value).append('|')
                .append(asset.balance.digestValue()).append('|').append(asset.price.digestValue()).append('|')
                .append(asset.valueUSD.digestValue()).append('|').append(asset.targetPercent.digestValue()).append('|')
                .append(asset.currentPercent.digestValue()).append('|')
                .append(asset.deviationPercent.digestValue()).append('|')
                .append(asset.deviationUSD.digestValue()).append('\n')
        }
    }

    private fun StringBuilder.appendTradeDigest(trade: TradeRecord) {
        append("trade|").append(trade.id).append('|').append(trade.timestamp).append('|')
            .append(trade.pair).append('|').append(trade.side).append('|').append(trade.symbol).append('|')
            .append(trade.volume.digestValue()).append('|').append(trade.usdAmount.digestValue()).append('|')
            .append(trade.success).append('|').append(trade.dryRun).append('|')
            .append(trade.errorMessage).append('|').append(trade.price.digestValue()).append('|')
            .append(trade.fee.digestValue()).append('|').append(trade.slippagePercent?.digestValue()).append('|')
            .append(trade.expectedPrice?.digestValue()).append('|').append(trade.source).append('|')
            .append(trade.cycleId).append('|').append(trade.orderTxid).append('|').append(trade.tradeId).append('|')
            .append(trade.clientOrderId).append('|').append(trade.submissionState).append('|')
            .append(trade.hasValidVolume).append('|').append(trade.hasValidCost).append('|')
            .append(trade.hasValidPrice).append('|').append(trade.hasValidFee).append('\n')
    }

    private fun StringBuilder.appendLedgerDigest(event: LedgerEvent) {
        append("ledger|").append(event.ledgerId).append('|').append(event.refid).append('|')
            .append(event.time).append('|').append(event.type).append('|').append(event.subtype).append('|')
            .append(event.aclass).append('|').append(event.asset).append('|').append(event.amount.digestValue())
            .append('|').append(event.fee.digestValue()).append('|').append(event.balance.digestValue()).append('|')
            .append(event.hasAuthoritativeBalance).append('|').append(event.hasAuthoritativeFee).append('|')
            .append(event.hasValidFee).append('|').append(event.hasValidAmount).append('\n')
    }

    /** Deterministic consumed-row ordering; a named comparator keeps the digest lambda-free. */
    private object TradeDigestOrder : Comparator<TradeRecord> {
        override fun compare(a: TradeRecord, b: TradeRecord): Int {
            val byTime = a.timestamp.compareTo(b.timestamp)
            if (byTime != 0) return byTime
            return (a.id ?: Int.MAX_VALUE).compareTo(b.id ?: Int.MAX_VALUE)
        }
    }

    private object LedgerDigestOrder : Comparator<LedgerEvent> {
        override fun compare(a: LedgerEvent, b: LedgerEvent): Int {
            val byTime = a.time.compareTo(b.time)
            if (byTime != 0) return byTime
            return a.ledgerId.compareTo(b.ledgerId)
        }
    }

    private fun BigDecimal.digestValue(): String =
        (if (signum() == 0) BigDecimal.ZERO else this).stripTrailingZeros().toPlainString()

    private fun sha256Hex(material: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun retainedMarketPairsByBase(trades: List<TradeRecord>): Map<String, List<String>> = trades
        .asSequence()
        .filter { it.success && !it.dryRun && it.hasValidEconomicFields() }
        .mapNotNull { trade ->
            Asset.splitTradingPair(trade.pair)?.let { split -> split.base to split.rawPair }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, pairs) -> pairs.distinct() }

    private fun historicalPriceProvider(
        marketPairsByBase: Map<String, List<String>>,
        eventUpperBound: Instant,
        onOhlcDependencyConsumed: ((ConsumedOhlcDependency) -> Unit)?,
    ): HistoricalPriceProvider {
        val memo = HistoricalPriceMemo()
        return HistoricalPriceProvider { symbol, time ->
            val normalizedSymbol = Asset.normalizeLedgerAsset(symbol).uppercase()
            if (normalizedSymbol == Asset.USD) {
                BigDecimal.ONE
            } else {
                memo.get(HistoricalPriceKey(normalizedSymbol, time)) {
                    var sourceFailure: HistoricalPriceSourceException? = null
                    val fromHistory = krakenService?.let { service ->
                        try {
                            HistoricalPriceResolver.resolveHistoricalPrice(
                                asset = normalizedSymbol,
                                eventTime = time,
                                tradesRepo = repository,
                                krakenService = service,
                                marketPairs = marketPairsByBase[normalizedSymbol].orEmpty(),
                                tradeLookbackSeconds = CONTRIBUTION_PRICE_LOOKUP_SECONDS,
                                futureTradeSkewSeconds = CONTRIBUTION_PRICE_FUTURE_SKEW_SECONDS,
                                marketPairsByBase = marketPairsByBase,
                                ohlcCache = historicalOhlcCache,
                                futureTradeUpperBound = eventUpperBound,
                                onOhlcDependencyConsumed = onOhlcDependencyConsumed,
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: HistoricalPriceSourceException) {
                            // A source outage is not evidence that no price exists. A retained snapshot
                            // may still prove the price; if it cannot, preserve the typed outage below.
                            sourceFailure = e
                            null
                        }
                    }
                    fromHistory ?: snapshotContributionPrice(normalizedSymbol, time) ?: sourceFailure?.let { throw it }
                }
            }
        }
    }

    /**
     * Retained-snapshot fallback for contribution-time pricing: the nearest recorded observation at
     * or before the funding instant inside the same bounded lookup window, never a live ticker.
     */
    private suspend fun snapshotContributionPrice(normalizedSymbol: String, time: Instant): BigDecimal? =
        repository.getSnapshotsInRange(
            time.minusSeconds(CONTRIBUTION_PRICE_LOOKUP_SECONDS),
            time,
        ).mapNotNull { snapshot ->
            val price = snapshot.assets.entries.firstOrNull { (asset, _) ->
                Asset.normalizeLedgerAsset(asset).uppercase() == normalizedSymbol
            }?.value?.price
            val observationTime = snapshot.balancesObservedAt ?: snapshot.timestamp
            if (price != null && price.signum() > 0 && !observationTime.isAfter(time)) {
                snapshot.timestamp to price
            } else {
                null
            }
        }.minByOrNull { (timestamp, _) ->
            kotlin.math.abs(timestamp.toEpochMilli() - time.toEpochMilli())
        }?.second

    private fun Instant.minusMillisIfLegacyObservation(
        anchorSnapshot: PortfolioSnapshot?,
        firstSnapshot: PortfolioSnapshot,
    ): Instant = if ((
            anchorSnapshot !=
                null &&
                anchorSnapshot.balancesObservedAt == null
            ) ||
        firstSnapshot.balancesObservedAt == null
    ) {
        minusMillis(RebalancerComparisonCalculator.MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS)
    } else {
        this
    }

    suspend fun getRewardsOverTime(from: Instant, to: Instant): RewardsOverTime {
        val snapshots = getSnapshotsInRange(from, to).sortedBy { it.timestamp }
        val rewardEvents =
            ledgerRepository
                .getLedgersInRange(from, to)
                .filter(LedgerEvent::isRewardEvent)
                .sortedBy { it.time }
        val cumulativeByAsset = mutableMapOf<String, BigDecimal>()
        var eventIndex = 0
        val points = snapshots.map { snapshot ->
            while (eventIndex < rewardEvents.size && rewardEvents[eventIndex].time <= snapshot.timestamp) {
                val event = rewardEvents[eventIndex]
                val symbol = Asset.normalizeLedgerAsset(event.asset).uppercase()
                if (symbol != Asset.USD) {
                    cumulativeByAsset[symbol] =
                        (cumulativeByAsset[symbol] ?: BigDecimal.ZERO).add(event.netBalanceDelta())
                }
                eventIndex++
            }
            var cumulativeUSD = BigDecimal.ZERO
            val perAssetUSD = mutableMapOf<String, BigDecimal>()
            for ((symbol, cumulative) in cumulativeByAsset) {
                val price =
                    if (symbol == Asset.USD) BigDecimal.ONE else snapshot.assets[symbol]?.price ?: continue
                val valueUSD = cumulative.multiply(price).setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP)
                perAssetUSD[symbol] = valueUSD
                cumulativeUSD = cumulativeUSD.add(valueUSD)
            }
            RewardsOverTimePoint(
                timestamp = snapshot.timestamp,
                cumulativeUSD = cumulativeUSD.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP),
                perAssetUSD = perAssetUSD,
            )
        }
        val totalRewardsUSD =
            points.lastOrNull()?.cumulativeUSD
                ?: BigDecimal.ZERO.setScale(PrecisionConstants.SCALE_USD, RoundingMode.HALF_UP)
        return RewardsOverTime(totalRewardsUSD = totalRewardsUSD, points = points)
    }

    suspend fun getHistoryStats(from: Instant, to: Instant): HistoryStats {
        val stats = portfolioStatsRepository.load()
        val summary = if (from ==
            Instant.EPOCH
        ) {
            repository.getTradeSummaryStats()
        } else {
            repository.getTradeSummaryStats(from, to)
        }
        val ath =
            if (from == Instant.EPOCH) {
                val snapshotMax = summary.periodHigh ?: BigDecimal.ZERO
                if (stats.allTimeHigh > snapshotMax) stats.allTimeHigh else snapshotMax
            } else {
                summary.periodHigh ?: BigDecimal.ZERO
            }
        return HistoryStats(
            allTimeHigh = ath,
            totalTradesExecuted = summary.totalTradesExecuted,
            totalVolumeTraded = summary.totalVolumeTraded,
            totalFeesPaid = summary.totalFeesPaid,
            latestSnapshotTime = summary.latestSnapshotTime,
            avgFeeRatePercent = summary.avgFeeRatePercent,
            avgSlippagePercent = summary.avgSlippagePercent,
            failedTradeCount = summary.failedTradeCount,
            dryRunTradeCount = summary.dryRunTradeCount,
        )
    }
}
