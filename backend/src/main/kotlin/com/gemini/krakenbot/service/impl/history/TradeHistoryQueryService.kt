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
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.OrderIntentRepository
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ComparisonStartProposal
import com.gemini.krakenbot.util.PrecisionConstants
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

class TradeHistoryQueryService(
    private val repository: TradeRepository,
    private val portfolioStatsRepository: PortfolioStatsRepository,
    private val ledgerRepository: LedgerRepository,
    private val orderIntentRepository: OrderIntentRepository? = null,
    private val inceptionDiscoveryService: InceptionDiscoveryService? = null,
    private val fundingProvenanceResolver: FundingProvenanceResolver = FundingProvenanceResolver.NONE,
    private val nowProvider: () -> Instant = Instant::now,
) {
    private val proposalSearchMutex = Mutex()

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
        repository.getSnapshotsInRange(from, to)

    suspend fun getTradesInRange(from: Instant, to: Instant): List<TradeRecord> = repository.getTradesInRange(from, to)

    suspend fun getLedgersInRange(from: Instant, to: Instant): List<LedgerEvent> =
        ledgerRepository.getLedgersInRange(from, to)

    companion object {
        /**
         * Contribution-time prices must come from recorded snapshots near the
         * event. Six hours matches the historical reconstruction grid, so an
         * old contribution still finds its era's prices while a pruned era
         * fails closed instead of borrowing a modern price.
         */
        const val CONTRIBUTION_PRICE_LOOKUP_SECONDS = 21600L

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
                ComparisonUnavailableReason.AMBIGUOUS_TRADE_OWNERSHIP,
                ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE,
            )

        /** Bounded proposal scan: at most this many full reconciliation trials per query. */
        private const val PROPOSAL_MAX_TRIALS = 8
        private const val PROPOSAL_SEARCH_VERSION = "3"
        private const val PROPOSAL_CURSOR_EXHAUSTED = "EXHAUSTED"

        /**
         * The normal snapshot loop is substantially more frequent than daily. A gap this large
         * crossing the rolling-retention boundary is therefore evidence that retained history may
         * have lost an entire earlier era; proposal search must not infer over it.
         */
        private const val MAX_COVERAGE_GAP_SECONDS = 86_400L
        private val OPEN_ENDED_RANGE_END = Instant.ofEpochMilli(Long.MAX_VALUE)
    }

    suspend fun getHistoryStats(): HistoryStats = getHistoryStats(Instant.EPOCH, Instant.now())

    suspend fun getRebalancerComparison(from: Instant, to: Instant): RebalancerComparison {
        val snapshots = getSnapshotsInRange(from, to)
        if (snapshots.size < 2) {
            return RebalancerComparisonCalculator.calculate(snapshots, emptyList())
        }
        val orderedSnapshots = snapshots.sortedBy { it.timestamp }
        val inceptionResolution = inceptionDiscoveryService?.resolveInception()
        val result = calculateComparison(orderedSnapshots, inceptionResolution)
        // A later comparison start is actionable only alongside an explicit strategy inception.
        // Auto-detected inception is display-only until the operator supplies that anchor.
        if (result.availability == ComparisonAvailability.UNAVAILABLE &&
            result.unavailableReason in PROPOSAL_ELIGIBLE_REASONS &&
            inceptionResolution?.isAutoDetected != true
        ) {
            val strategyStart = inceptionResolution?.inceptionTime
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
    suspend fun getComparisonStartProposal(after: Instant): ComparisonStartProposal? {
        val inceptionResolution = inceptionDiscoveryService?.resolveInception()
        // An auto-detected inception is display-only until the operator supplies an explicit
        // strategy start; Settings must not expose an approval action for it.
        if (inceptionResolution?.isAutoDetected == true) return null
        val snapshots = loadAllSnapshots(inceptionResolution?.inceptionTime ?: Instant.EPOCH)
        if (snapshots.size < 2) return null
        val current = calculateComparison(snapshots, inceptionResolution)
        if (current.availability != ComparisonAvailability.UNAVAILABLE ||
            current.unavailableReason !in PROPOSAL_ELIGIBLE_REASONS
        ) {
            return null
        }
        if (historicalCoverageGapExists(
                snapshots = snapshots,
                strategyStart = inceptionResolution?.inceptionTime ?: after,
            )
        ) {
            return null
        }
        return findLaterComparisonStartProposal(after, inceptionResolution)
    }

    private suspend fun calculateComparison(
        orderedSnapshots: List<PortfolioSnapshot>,
        inceptionResolution: InceptionResolution?,
        preparedFundingProvenance: FundingProvenanceResolver? = null,
    ): RebalancerComparison {
        val firstSnapshot = orderedSnapshots.first()
        val lastSnapshot = orderedSnapshots.last()
        val firstTimestamp = firstSnapshot.timestamp
        val lastTimestamp = lastSnapshot.timestamp
        val firstObservationTime = firstSnapshot.balancesObservedAt ?: firstTimestamp
        val lastObservationTime = lastSnapshot.balancesObservedAt ?: lastTimestamp

        if (inceptionResolution?.confidence == InceptionConfidence.RECOVERY_INCOMPLETE) {
            return RebalancerComparisonCalculator.calculate(
                snapshots = orderedSnapshots,
                trades = emptyList(),
                rewards = emptyList(),
                knownInceptionTime = inceptionResolution.inceptionTime,
                inceptionUnavailableReason = inceptionResolution.unavailableReason
                    ?: ComparisonUnavailableReason.INCEPTION_RECOVERY_INCOMPLETE,
            )
        }
        if (inceptionResolution?.confidence == InceptionConfidence.TRUNCATED) {
            // Migrated install whose early history was removed by a previous
            // retention era: no window-anchored number may stand in for a
            // lifetime baseline. The UI text tells the user to configure
            // the inception date manually.
            return RebalancerComparisonCalculator.calculate(
                snapshots = orderedSnapshots,
                trades = emptyList(),
                rewards = emptyList(),
                knownRebalancerOrderTxids = emptySet(),
                anchorSnapshot = null,
                inceptionSnapshot = null,
                knownInceptionTime = inceptionResolution.inceptionTime,
                historyTruncated = true,
            )
        }
        val inceptionSnapshot = inceptionResolution?.inceptionSnapshot
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

        val anchorSnapshot = repository.getSnapshotBefore(firstTimestamp)
        val eventQueryStart = listOfNotNull(
            inceptionSnapshot?.balancesObservedAt ?: inceptionSnapshot?.timestamp,
            anchorSnapshot?.balancesObservedAt ?: anchorSnapshot?.timestamp,
            firstObservationTime,
        ).minOrNull() ?: firstObservationTime

        val queryFrom = eventQueryStart.minusMillisIfLegacyObservation(anchorSnapshot, firstSnapshot)
        val queryTo = maxOf(lastTimestamp, lastObservationTime)
            .plusMillis(RebalancerComparisonCalculator.MAX_EVENT_OBSERVATION_CLOCK_SKEW_MILLIS)

        val trades = getTradesInRange(queryFrom, queryTo)
        // Closed world: LedgersSyncService only fetches EXTERNAL_BALANCE_TYPES,
        // so unknown types cannot arrive here. LedgerFlowClassifier inside
        // RebalancerComparisonCalculator is the second layer: it replays the
        // margin-family in-kind and fails closed on anything unrecognized.
        val ledgers =
            ledgerRepository
                .getLedgersInRange(queryFrom, queryTo)
                .filter { it.type in LedgerEvent.EXTERNAL_BALANCE_TYPES }
        // The calculator prepares one immutable provenance snapshot for this
        // complete history query and uses that same snapshot for classification
        // and card normalization.
        val candidateTrades = trades.filter { it.success && !it.dryRun }
        val candidateOrderTxids = candidateTrades.mapNotNull {
            it.orderTxid?.trim()?.takeIf(String::isNotBlank)
        }.toSet()
        val candidateClientOrderIds = candidateTrades.mapNotNull {
            it.clientOrderId?.trim()?.takeIf(String::isNotBlank)
        }.toSet()
        val knownRebalancerOrderTxids = orderIntentRepository
            ?.getKnownRebalancerOrderIdentities(candidateOrderTxids, candidateClientOrderIds)
            ?.orderTxids
            .orEmpty()
        // Contribution-time prices come only from recorded snapshots near the
        // event (never a live ticker for an old contribution). Absent prices
        // fail the comparison closed inside the calculator.
        val priceProvider = historicalPriceProvider()
        return RebalancerComparisonCalculator.calculate(
            snapshots = orderedSnapshots,
            trades = trades,
            rewards = ledgers,
            knownRebalancerOrderTxids = knownRebalancerOrderTxids,
            anchorSnapshot = anchorSnapshot,
            inceptionSnapshot = inceptionSnapshot,
            knownInceptionTime = inceptionResolution?.inceptionTime,
            priceProvider = priceProvider,
            provenanceResolver = preparedFundingProvenance ?: fundingProvenanceResolver,
        )
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
    private suspend fun findLaterComparisonStartProposal(
        startAfter: Instant,
        inceptionResolution: InceptionResolution?,
    ): ComparisonStartProposal {
        return proposalSearchMutex.withLock {
            // Reload all economic evidence after acquiring the mutex. A concurrent Settings and
            // History request must not fingerprint a stale snapshot list and then overwrite newer
            // durable progress with an older cursor.
            val orderedSnapshots = loadAllSnapshots(inceptionResolution?.inceptionTime ?: startAfter)
            if (historicalCoverageGapExists(
                    snapshots = orderedSnapshots,
                    strategyStart = inceptionResolution?.inceptionTime ?: startAfter,
                )
            ) {
                return@withLock ComparisonStartProposal(ComparisonProposalStatus.INCOMPLETE)
            }
            val candidates = orderedSnapshots.filter { it.timestamp > startAfter }
            val predecessorSnapshot = candidates.firstOrNull()?.let { repository.getSnapshotBefore(it.timestamp) }
            val fingerprint = proposalEvidenceFingerprint(
                orderedSnapshots = orderedSnapshots,
                startAfter = startAfter,
                inceptionResolution = inceptionResolution,
                predecessorSnapshot = predecessorSnapshot,
            )
            val storedFingerprint = repository.getSyncMetadata(
                SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FINGERPRINT,
            )
            val storedStatus = repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS)
            val storedCursor = repository.getSyncMetadata(
                SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS,
            )
            val storedFundingEvidenceFingerprint = repository.getSyncMetadata(
                SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FUNDING_FINGERPRINT,
            ).orEmpty()
            val canResume = storedFingerprint == fingerprint
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
            if (preparedFundingProvenance?.preparationFailure != null) {
                persistProposalSearchState(
                    fingerprint = fingerprint,
                    status = ComparisonProposalStatus.INCOMPLETE,
                    cursor = candidates.proposalCursorAt(0).encode(),
                    fundingEvidenceFingerprint = null,
                )
                return@withLock ComparisonStartProposal(ComparisonProposalStatus.INCOMPLETE)
            }
            if (canResume && !fundingEvidenceChanged && storedStatus == ComparisonProposalStatus.VERIFIED.name) {
                storedCursor?.let(ProposalCursor::parse)?.let { cursor ->
                    val verifiedIndex = candidates.indexOfProposalCursor(cursor)
                    if (verifiedIndex >= 0) {
                        return@withLock ComparisonStartProposal(
                            status = ComparisonProposalStatus.VERIFIED,
                            timestamp = candidates[verifiedIndex].timestamp,
                            snapshotId = repository.getSyncMetadata(
                                SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_SNAPSHOT_ID,
                            )?.toIntOrNull(),
                        )
                    }
                }
            }
            if (canResume && !fundingEvidenceChanged && storedStatus == ComparisonProposalStatus.EXHAUSTED.name) {
                return@withLock ComparisonStartProposal(ComparisonProposalStatus.EXHAUSTED)
            }

            val resumeIndex = if (canResume && !fundingEvidenceChanged &&
                storedStatus == ComparisonProposalStatus.INCOMPLETE.name
            ) {
                storedCursor?.let(ProposalCursor::parse)
                    ?.let { cursor -> candidates.indexOfProposalCursor(cursor) }
                    ?.takeIf { it >= 0 } ?: 0
            } else {
                0
            }
            var index = resumeIndex
            var trials = 0
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
                    persistProposalSearchState(
                        fingerprint = fingerprint,
                        status = ComparisonProposalStatus.VERIFIED,
                        cursor = candidateCursor.encode(),
                        snapshotId = candidateSnapshotId,
                        fundingEvidenceFingerprint = fundingEvidenceFingerprint,
                    )
                    return@withLock ComparisonStartProposal(
                        status = ComparisonProposalStatus.VERIFIED,
                        timestamp = candidate.timestamp,
                        snapshotId = candidateSnapshotId,
                    )
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
            )
            ComparisonStartProposal(status)
        }
    }

    private suspend fun loadAllSnapshots(from: Instant): List<PortfolioSnapshot> =
        repository.getAllSnapshotsInRange(from, OPEN_ENDED_RANGE_END).sortedBy { it.timestamp }

    private suspend fun historicalCoverageGapExists(strategyStart: Instant): Boolean =
        historicalCoverageGapExists(loadAllSnapshots(strategyStart), strategyStart)

    private fun historicalCoverageGapExists(snapshots: List<PortfolioSnapshot>, strategyStart: Instant): Boolean {
        val now = nowProvider()
        if (strategyStart.isAfter(now)) return false
        val retentionCutoff = now.minusSeconds(PrecisionConstants.HISTORICAL_DAYS_BACK.toLong() * 86_400L)
        if (!strategyStart.isBefore(retentionCutoff)) return false

        val retained = snapshots.filter {
            !it.timestamp.isBefore(strategyStart) && !it.timestamp.isAfter(now)
        }
        val first = retained.firstOrNull() ?: return false
        if (!first.timestamp.isBefore(retentionCutoff)) {
            return true
        }
        return retained.zipWithNext().any { (previous, current) ->
            previous.timestamp.isBefore(retentionCutoff) &&
                !current.timestamp.isBefore(retentionCutoff) &&
                Duration.between(previous.timestamp, current.timestamp).seconds > MAX_COVERAGE_GAP_SECONDS
        }
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
    ) {
        repository.setSyncMetadataAtomically(
            mapOf(
                SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FINGERPRINT to fingerprint,
                SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS to status.name,
                SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS to cursor,
                SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_SNAPSHOT_ID to snapshotId?.toString().orEmpty(),
                SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FUNDING_FINGERPRINT to
                    fundingEvidenceFingerprint.orEmpty(),
            ),
        )
    }

    /**
     * The cursor is valid only for this configuration, effective start, account scope, and
     * evidence revision. The digest contains the complete retained rows rather than only the
     * display window so zooming cannot skip or reuse a candidate incorrectly.
     */
    private suspend fun proposalEvidenceFingerprint(
        orderedSnapshots: List<PortfolioSnapshot>,
        startAfter: Instant,
        inceptionResolution: InceptionResolution?,
        predecessorSnapshot: PortfolioSnapshot?,
    ): String {
        val trades = repository.getTradesInRange(Instant.EPOCH, OPEN_ENDED_RANGE_END)
        val ledgers = ledgerRepository.getLedgersInRange(Instant.EPOCH, OPEN_ENDED_RANGE_END)
        val candidateTrades = trades.filter { it.success && !it.dryRun }
        val knownOrderTxids = orderIntentRepository
            ?.getKnownRebalancerOrderIdentities(
                orderTxids = candidateTrades.mapNotNull { it.orderTxid?.takeIf(String::isNotBlank) }.toSet(),
                clientOrderIds = candidateTrades.mapNotNull { it.clientOrderId?.takeIf(String::isNotBlank) }.toSet(),
            )
            ?.orderTxids
            .orEmpty()
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
            append(ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC).orEmpty())
                .append('\u0000')
            append(repository.getSyncMetadata(SyncMetadataKeys.SYNC_WATERMARK_EPOCH_SEC).orEmpty())
                .append('\u0000')
            predecessorSnapshot?.let {
                append("predecessor\n")
                appendSnapshotDigest(it)
            }
            orderedSnapshots.forEach { appendSnapshotDigest(it) }
            trades.sortedWith(compareBy({ it.timestamp }, { it.id ?: Int.MAX_VALUE }))
                .forEach { appendTradeDigest(it) }
            ledgers.sortedWith(compareBy({ it.time }, { it.ledgerId }))
                .forEach { appendLedgerDigest(it) }
            knownOrderTxids.sorted().forEach { append("order=$it\n") }
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
            .append(trade.clientOrderId).append('|').append(trade.submissionState).append('\n')
    }

    private fun StringBuilder.appendLedgerDigest(event: LedgerEvent) {
        append("ledger|").append(event.ledgerId).append('|').append(event.refid).append('|')
            .append(event.time).append('|').append(event.type).append('|').append(event.subtype).append('|')
            .append(event.aclass).append('|').append(event.asset).append('|').append(event.amount.digestValue())
            .append('|').append(event.fee.digestValue()).append('|').append(event.balance.digestValue()).append('|')
            .append(event.hasAuthoritativeBalance).append('|').append(event.hasAuthoritativeFee).append('|')
            .append(event.hasValidFee).append('\n')
    }

    private fun BigDecimal.digestValue(): String =
        (if (signum() == 0) BigDecimal.ZERO else this).stripTrailingZeros().toPlainString()

    private fun sha256Hex(material: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun historicalPriceProvider() = HistoricalPriceProvider { symbol, time ->
        if (Asset.normalizeLedgerAsset(symbol).uppercase() == Asset.USD) {
            BigDecimal.ONE
        } else {
            val normalizedSymbol = Asset.normalizeLedgerAsset(symbol).uppercase()
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
        }
    }

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
