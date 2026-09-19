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
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.OrderIntentRepository
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.repository.downsampleSnapshots
import com.gemini.krakenbot.service.AutomaticBaselineStatus
import com.gemini.krakenbot.service.ComparisonStartProposal
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.SettingsComparisonStatus
import com.gemini.krakenbot.util.PrecisionConstants
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.atomic.AtomicBoolean

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
    /** Lower bound of retained history scanned for a passive benchmark anchor. */
    private val benchmarkHistoryFloor: Instant = Instant.EPOCH,
    /** Application-lifetime scope used for the bounded background proposal continuation. */
    private val applicationScope: CoroutineScope? = null,
    /** Current allocation membership; historical wallet-only assets are not live targets. */
    private val configService: ConfigService? = null,
) {
    private val proposalSearchMutex = Mutex()

    private val log = LoggerFactory.getLogger(TradeHistoryQueryService::class.java)

    private val proposalContinuationActive = AtomicBoolean(false)

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
     * Full-fidelity comparison input: identity-anchor collisions excluded, otherwise the
     * complete retained series. Reconciliation correctness must not depend on chart sampling,
     * so duplicate instants and stride downsampling are NOT applied here — intermediate
     * states are reconciliation evidence (a collapsed series provably loses multi-event
     * instants the calculator contracts require). Presentation sampling happens on the
     * resulting comparison points only after the calculator succeeds.
     */
    private suspend fun loadComparisonSnapshots(from: Instant, to: Instant): List<PortfolioSnapshot> =
        excludeIdentitySnapshots(repository.getAllSnapshotsInRange(from, to)).sortedBy { it.timestamp }

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

        /** Background continuation pacing and lifetime budget for an incomplete scan. */
        private const val PROPOSAL_CONTINUATION_MAX_CYCLES = 600
        private const val PROPOSAL_CONTINUATION_PACING_MS = 2_000L
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
         * The normal snapshot loop is substantially more frequent than daily. A gap this large
         * in historical coverage is evidence that retained history lost an interval or era;
         * proposal search must not infer over it.
         */
        private const val MAX_COVERAGE_GAP_SECONDS = 86_400L
        private val OPEN_ENDED_RANGE_END = Instant.ofEpochMilli(Long.MAX_VALUE)
    }

    suspend fun getHistoryStats(): HistoryStats = getHistoryStats(Instant.EPOCH, nowProvider())

    suspend fun getRebalancerComparison(from: Instant, to: Instant): RebalancerComparison {
        val snapshots = loadComparisonSnapshots(from, to)
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
        val inceptionResolution = inceptionDiscoveryService?.resolveInception()
        val reconciled =
            calculateComparison(
                orderedSnapshots,
                inceptionResolution,
                suppressPassiveDiscovery = shouldSuppressPassiveDiscovery(inceptionResolution),
            )
        // Presentation sampling is strictly post-reconciliation: the calculator reconciles the
        // full retained series, and only the resulting comparison points are reduced for chart
        // payload size. Baseline, latest difference, and contribution accounting are computed on
        // the full series and are unaffected by point selection; endpoints are always kept.
        val result = if (reconciled.availability == ComparisonAvailability.AVAILABLE) {
            reconciled.copy(points = reconciled.points.downsampleSnapshots())
        } else {
            reconciled
        }
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
            if (strategyStart != null && historicalCoverageGapExists(strategyStart)) {
                // A later retained snapshot may reconcile locally while an earlier retained era
                // is missing. Without continuity across the retention boundary there is no proof
                // that the proposed candidate is the earliest trustworthy start.
                return result.copy(
                    unavailableReason = ComparisonUnavailableReason.HISTORICAL_COVERAGE_GAP,
                    proposedBaselineTimestamp = null,
                    proposalSearchStatus = null,
                )
            }
            // Window-independent: the proposal must not change when the user
            // zooms the History chart, so the scan reads the full retained
            // snapshot range instead of the display window.
            val proposal = findLaterComparisonStartProposal(
                startAfter = inceptionResolution?.inceptionTime ?: Instant.EPOCH,
                inceptionResolution = inceptionResolution,
            )
            return result.copy(
                proposedBaselineTimestamp = proposal.timestamp,
                proposalSearchStatus = proposal.status,
            )
        }
        return result
    }

    /**
     * Applies the same comparison-availability policy used by History before exposing a
     * Settings proposal. Recovery status alone is not sufficient: a confirmed baseline can still
     * be followed by an ownership or reconciliation failure in the retained history.
     */
    suspend fun getComparisonStartProposal(after: Instant): ComparisonStartProposal? =
        getSettingsComparisonStatus(after, allowPersistedBaselineFastPath = false).proposal

    /**
     * The same gate chain as [getComparisonStartProposal] in the same order, but it also
     * exposes the baseline identity and the passive comparison's availability so the Settings
     * fragment can render the effective Buy & Hold baseline from this single evaluation.
     * The proposal chain always evaluates through the full gates because it bypasses the
     * persisted-baseline fast path. The stable-horizon gate below applies to every
     * Settings-family evaluation that flows through this function — baseline proof,
     * later-start proposal search, and search continuation — so each withholds a verdict
     * while certified coverage is unknown or thin and resumes once history catches up.
     * History's [getRebalancerComparison] keeps evaluating the full snapshot list.
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
    ): SettingsComparisonStatus {
        val inceptionResolution = inceptionDiscoveryService?.resolveInception()
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
        val stableThrough = latestConfirmedEconomicCoverage()
        if (stableThrough == null) {
            log.info("Automatic B&H baseline verification deferred; reason=HISTORY_COVERAGE_STALE")
            return SettingsComparisonStatus()
        }
        val stableSnapshots = snapshots.filter { isSnapshotCoveredByHistory(it, stableThrough) }
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
        val current =
            calculateComparison(
                stableSnapshots,
                inceptionResolution,
                suppressPassiveDiscovery = shouldSuppressPassiveDiscovery(inceptionResolution),
            )
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
            persistAutomaticBaselineVerification(current, inceptionResolution, stableSnapshots, stableThrough)
            return status
        }
        if (historicalCoverageGapExists(
                snapshots = snapshots,
                strategyStart = inceptionResolution?.inceptionTime ?: after,
            )
        ) {
            return status
        }
        val skipCandidatesBefore = current.unavailableAt
            ?.takeIf { current.unavailableReason in INTRINSIC_EVENT_REASONS }
        val proposal = findLaterComparisonStartProposal(after, inceptionResolution, skipCandidatesBefore)
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
                    delay(PROPOSAL_CONTINUATION_PACING_MS)
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
        )?.toLongOrNull()
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
            ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST).orEmpty()
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
     * of the certified ledger and trade coverage horizons. Both certified horizons must
     * exist and parse — the sync watermarks are deliberately not consulted because they
     * record a refreshed query window, not a completeness proof. This mirrors the
     * reconstruction contract, which requires both certified horizons to reach the
     * reconstruction anchor before any rebuilt snapshot is trusted. A null return means
     * coverage is unknown, so verification defers instead of trusting an unproven tail.
     */
    private suspend fun latestConfirmedEconomicCoverage(): Instant? {
        val ledgerHorizonSec = ledgerRepository
            .getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC)
            ?.toLongOrNull()
        val tradeHorizonSec = repository
            .getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC)
            ?.toLongOrNull()
        val ledgerHorizon = ledgerHorizonSec?.let(Instant::ofEpochSecond)
        val tradeHorizon = tradeHorizonSec?.let(Instant::ofEpochSecond)
        return if (ledgerHorizon == null || tradeHorizon == null) null else minOf(ledgerHorizon, tradeHorizon)
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
        val evidenceHorizonEpochMillis =
            minOf(snapshots.maxOf { it.timestamp.toEpochMilli() }, stableThrough.toEpochMilli())
        val evidenceFingerprint = automaticBaselineEvidenceDigest(inceptionTime, evidenceHorizonEpochMillis)
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
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST)
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
        // Operator-facing entries pass true when a usable approved baseline exists (see
        // shouldSuppressPassiveDiscovery). Proposal-search trials always pass false: each
        // trial synthesizes a per-candidate CONFIDENT resolution as scoping scaffolding, and
        // verification pivots on discovery re-anchoring at the candidate — suppressing it
        // there would make every trial reconcile the stale predecessor instead.
        suppressPassiveDiscovery: Boolean = false,
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
                findPureBenchmarkAnchor(maxOf(benchmarkHistoryFloor, firstTimestamp))
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
        val queryTo = maxOf(lastTimestamp, lastObservationTime)
            .plusMillis(RebalancerComparisonCalculator.MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS)

        val trades = getTradesInRange(queryFrom, queryTo)
        // Raw-evidence contract: the ledger repository is raw/unprojected (types = null on
        // ingestion). The classifier/replay layer is the semantic projection — never the query
        // layer. Pass the complete interval so unknown top-level types reach
        // LedgerFlowClassifier and fail closed as UNSUPPORTED/AMBIGUOUS instead of disappearing
        // here. `trade` rows are retained as continuity checkpoints and classify as TRADE_IGNORED.
        val ledgers = ledgerRepository.getLedgersInRange(queryFrom, queryTo)
        val windowLedgerIds = ledgers.associateBy(LedgerEvent::ledgerId)
        // Validation context: strictly-before rows (inclusive-bound query + identity
        // dedupe keeps the exact-queryFrom row economic-window-only), used ONLY for
        // wallet-scope replay of the authoritative validator.
        val contextLedgers = ledgerRepository.getLedgersInRange(Instant.EPOCH, queryFrom)
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
        val retainedMarketTrades = repository.getTradesInRange(Instant.EPOCH, OPEN_ENDED_RANGE_END)
        val priceProvider = historicalPriceProvider(retainedMarketPairsByBase(retainedMarketTrades + trades))
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
    private suspend fun findPureBenchmarkAnchor(floor: Instant): PortfolioSnapshot? {
        val now = nowProvider()
        val window = reconstructionWindow()
        val reconstructedRange = authoritativelyReconstructedRange(window)
        val effectiveFloor = maxOf(floor, benchmarkHistoryFloor)
        return repository
            .getAllSnapshotsInRange(benchmarkHistoryFloor, OPEN_ENDED_RANGE_END)
            .asSequence()
            .filter { snapshot ->
                !snapshot.timestamp.isBefore(effectiveFloor) &&
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
    ): ComparisonStartProposal {
        return proposalSearchMutex.withLock {
            // Reload all economic evidence after acquiring the mutex. A concurrent Settings and
            // History request must not fingerprint a stale snapshot list and then overwrite newer
            // durable progress with an older cursor.
            val orderedSnapshots = loadAllSnapshots(inceptionResolution?.inceptionTime ?: startAfter)
            // A stale reconstruction interval invalidates every candidate that overlaps it, so no
            // candidate can be declared VERIFIED until a successful rebuild. Fail the search
            // incomplete instead of scanning snapshots whose reconstruction contract is stale.
            if (overlapsStaleReconstruction(orderedSnapshots)) {
                return@withLock ComparisonStartProposal(ComparisonProposalStatus.INCOMPLETE)
            }
            if (historicalCoverageGapExists(
                    snapshots = orderedSnapshots,
                    strategyStart = inceptionResolution?.inceptionTime ?: startAfter,
                )
            ) {
                return@withLock ComparisonStartProposal(ComparisonProposalStatus.INCOMPLETE)
            }
            val allCandidates = orderedSnapshots.filter { it.timestamp > startAfter }
            val predecessorSnapshot = allCandidates.firstOrNull()?.let { repository.getSnapshotBefore(it.timestamp) }
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
            val storedFrontierReason = repository.getSyncMetadata(
                SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_REASON,
            )
            val storedFrontierCursorRaw = repository.getSyncMetadata(
                SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_CURSOR_EPOCH_MS,
            )?.toLongOrNull()
            val trades = repository.getTradesInRange(Instant.EPOCH, OPEN_ENDED_RANGE_END)
            val ledgers = ledgerRepository.getLedgersInRange(Instant.EPOCH, OPEN_ENDED_RANGE_END)
            val latestRowEpochMillis = maxOf(
                orderedSnapshots.maxOfOrNull { it.timestamp.toEpochMilli() } ?: 0L,
                predecessorSnapshot?.timestamp?.toEpochMilli() ?: 0L,
                trades.maxOfOrNull { it.timestamp.toEpochMilli() } ?: 0L,
                ledgers.maxOfOrNull { it.time.toEpochMilli() } ?: 0L,
            )
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
                horizonEpochMillis = storedEvidenceHorizon ?: latestRowEpochMillis,
            )
            val canResume = storedFingerprint == prefixFingerprint
            val horizonAdvanced = canResume &&
                storedEvidenceHorizon != null &&
                latestRowEpochMillis > storedEvidenceHorizon
            // A reset (fresh or invalidated stored state) and a horizon advance both derive the
            // bound from the newest row, so every persisted state covers its candidate universe
            // exactly — no tested candidate is ever digested outside its own fingerprint.
            val appliedEvidenceHorizon = if (canResume && !horizonAdvanced) {
                storedEvidenceHorizon ?: latestRowEpochMillis
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
                    ledgerRepository.getLedgersInRange(Instant.EPOCH, OPEN_ENDED_RANGE_END),
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
                // Funding preparation cannot revalidate a stored anchor against a horizon
                // that has advanced, so fail closed to INCOMPLETE; the next healthy call
                // re-evaluates under the enlarged evidence horizon. The cached state stays
                // safe only while the horizon — and thus the tested evidence set — is
                // unchanged, and only past the caller's skip boundary.
                if (canResume && !fundingEvidenceChanged && !horizonAdvanced &&
                    storedStatus == ComparisonProposalStatus.VERIFIED.name
                ) {
                    val verifiedIndex = storedCursor
                        ?.let { cursor -> candidates.indexOfProposalCursor(cursor) }
                    val storedSnapshotId = repository.getSyncMetadata(
                        SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_SNAPSHOT_ID,
                    )?.toIntOrNull()
                    val durableSnapshotId = if (verifiedIndex != null && verifiedIndex >= 0) {
                        repository.getSnapshotId(
                            candidates[verifiedIndex].timestamp,
                            storedCursor.ordinal,
                        )
                    } else {
                        null
                    }
                    if (verifiedIndex != null && verifiedIndex >= 0 &&
                        (skipIndex == null || skipIndex <= verifiedIndex) &&
                        storedSnapshotId != null && durableSnapshotId == storedSnapshotId
                    ) {
                        return@withLock ComparisonStartProposal(
                            status = ComparisonProposalStatus.VERIFIED,
                            timestamp = candidates[verifiedIndex].timestamp,
                            snapshotId = storedSnapshotId,
                        )
                    }
                }
                persistProposalSearchState(
                    fingerprint = fingerprint,
                    status = ComparisonProposalStatus.INCOMPLETE,
                    cursor = candidates.proposalCursorAt(0).encode(),
                    fundingEvidenceFingerprint = null,
                    evidenceHorizonEpochMillis = appliedEvidenceHorizon,
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
                            if (!horizonAdvanced && !reopensPastStoredVerified) {
                                return@withLock ComparisonStartProposal(
                                    status = ComparisonProposalStatus.VERIFIED,
                                    timestamp = candidates[verifiedIndex].timestamp,
                                    snapshotId = storedSnapshotId,
                                )
                            }
                            if (!reopensPastStoredVerified) {
                                // A horizon advance can invalidate the stored anchor with
                                // new tail evidence (new ledger event, reconstruction row),
                                // so the verified candidate is re-evaluated once under the
                                // enlarged horizon before it may be returned.
                                val revalidated = calculateComparison(
                                    candidates.drop(verifiedIndex),
                                    InceptionResolution(
                                        inceptionTime = candidates[verifiedIndex].timestamp,
                                        inceptionSnapshot = candidates[verifiedIndex],
                                        isAutoDetected = false,
                                    ),
                                    preparedFundingProvenance = preparedFundingProvenance,
                                )
                                if (revalidated.availability == ComparisonAvailability.AVAILABLE) {
                                    persistProposalSearchState(
                                        fingerprint = fingerprint,
                                        status = ComparisonProposalStatus.VERIFIED,
                                        cursor = cursor.encode(),
                                        snapshotId = storedSnapshotId,
                                        fundingEvidenceFingerprint = fundingEvidenceFingerprint,
                                        evidenceHorizonEpochMillis = appliedEvidenceHorizon,
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
            // includes them.
            if (canResume && !fundingEvidenceChanged &&
                storedStatus == ComparisonProposalStatus.EXHAUSTED.name &&
                !horizonAdvanced
            ) {
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
                val advancedTailStart = storedEvidenceHorizon
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

    private suspend fun historicalCoverageGapExists(strategyStart: Instant): Boolean =
        historicalCoverageGapExists(loadAllSnapshots(strategyStart), strategyStart)

    private suspend fun historicalCoverageGapExists(
        snapshots: List<PortfolioSnapshot>,
        strategyStart: Instant,
    ): Boolean {
        val now = nowProvider()
        if (strategyStart.isAfter(now)) return false

        val retained = snapshots.filter {
            !it.timestamp.isBefore(strategyStart) && !it.timestamp.isAfter(now)
        }.sortedBy { it.timestamp }
        if (retained.isEmpty()) return false

        val continuousStart = resolveContinuousHistoryStart(snapshots)
        if (Duration.between(strategyStart, continuousStart).seconds > MAX_COVERAGE_GAP_SECONDS) {
            // Strategy started before continuous history was established (e.g. lost under legacy retention).
            return true
        }

        val first = retained.first()
        if (Duration.between(strategyStart, first.timestamp).seconds > MAX_COVERAGE_GAP_SECONDS) {
            return true
        }

        return retained.zipWithNext().any { (previous, current) ->
            Duration.between(previous.timestamp, current.timestamp).seconds > MAX_COVERAGE_GAP_SECONDS
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
        evidenceHorizonEpochMillis: Long? = null,
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
                    evidenceHorizonEpochMillis?.toString().orEmpty(),
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
     * bound: only rows at or before that instant participate. Append-only tail rows after a
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
    ): String {
        val material = buildString {
            append(PROPOSAL_SEARCH_VERSION).append('\u0000')
            append(startAfter).append('\u0000')
            append(inceptionResolution?.inceptionTime).append('\u0000')
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
            // they are freshness counters, not evidence identity, so they must not invalidate
            // resumable progress. Row-level digests below remain the material evidence check.
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

    private fun historicalPriceProvider(marketPairsByBase: Map<String, List<String>>) = HistoricalPriceProvider {
            symbol,
            time,
        ->
        if (Asset.normalizeLedgerAsset(symbol).uppercase() == Asset.USD) {
            BigDecimal.ONE
        } else {
            val normalizedSymbol = Asset.normalizeLedgerAsset(symbol).uppercase()
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
