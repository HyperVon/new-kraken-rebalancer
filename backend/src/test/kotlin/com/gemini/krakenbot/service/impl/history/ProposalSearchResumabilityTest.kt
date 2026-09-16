package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ComparisonProposalStatus
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SimpleFundingProvenanceResolver
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.OrderIntentRepository
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.math.BigDecimal
import java.time.Instant

/**
 * The bounded proposal scan must make durable, resumable progress: append-only live
 * snapshots after the evidence horizon must not rewrite the incomplete cursor, while any
 * material change to the scanned evidence must still invalidate it.
 */
class ProposalSearchResumabilityTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val now = Instant.parse("2026-07-01T12:00:00Z")

    private fun snapshot(delaySeconds: Long, index: Int): PortfolioSnapshot {
        val balance = BigDecimal(index + 1)
        val price = BigDecimal("50000.00")
        return PortfolioSnapshot(
            timestamp = now.plusSeconds(delaySeconds),
            totalValueUSD = price.multiply(balance).plus(BigDecimal("50000.00")),
            assets = mapOf(
                Asset.BTC to TestFixtures.assetSnapshot(
                    symbol = Asset.BTC,
                    balance = balance,
                    price = price,
                    valueUSD = price.multiply(balance),
                    targetPercent = BigDecimal.ZERO,
                ),
                TestFixtures.USD to TestFixtures.assetSnapshot(
                    symbol = TestFixtures.USD,
                    balance = BigDecimal("50000.00"),
                    price = BigDecimal.ONE,
                    valueUSD = BigDecimal("50000.00"),
                    targetPercent = BigDecimal.ZERO,
                ),
            ),
            actions = emptyList(),
            drawdownPercent = BigDecimal.ZERO,
            fiatDeploymentPercent = BigDecimal.ZERO,
            effectiveUsdTargetPercent = BigDecimal.ZERO,
            balancesObservedAt = now.plusSeconds(delaySeconds),
        )
    }

    private fun harness(
        metadata: MutableMap<String, String>,
        snapshots: List<PortfolioSnapshot>,
        applicationScope: CoroutineScope? = null,
    ): Pair<TradeHistoryQueryService, TradeRepository> {
        val repository = mockk<TradeRepository>(relaxed = true)
        val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
        coEvery { repository.getSyncMetadata(any()) } coAnswers { metadata[firstArg()] }
        coEvery { repository.setSyncMetadataAtomically(any()) } coAnswers { metadata.putAll(firstArg()) }
        val inceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
        coEvery { inceptionService.resolveInception() } returns InceptionResolution(
            inceptionTime = now,
            inceptionSnapshot = null,
            isAutoDetected = false,
            confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
            unavailableReason = ComparisonUnavailableReason.INCEPTION_BASELINE_UNAVAILABLE,
        )
        coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns snapshots
        coEvery { repository.getSnapshotBefore(any()) } returns null
        coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
        coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
        coEvery { ledgerRepository.getSyncMetadata(any()) } coAnswers { metadata[firstArg()] }
        val service = TradeHistoryQueryService(
            repository = repository,
            portfolioStatsRepository = mockk(relaxed = true),
            ledgerRepository = ledgerRepository,
            orderIntentRepository = mockk(relaxed = true),
            inceptionDiscoveryService = inceptionService,
            fundingProvenanceResolver = SimpleFundingProvenanceResolver(),
            nowProvider = { now },
            applicationScope = applicationScope,
        )
        return service to repository
    }

    init {
        "an append-only snapshot tail preserves an incomplete proposal cursor and finishes the scan" {
            runTest {
                val candidates = (1..12).map { index -> snapshot(3600L * index, index) }
                val tail = (13..15).map { index -> snapshot(3600L * index, index) }
                val metadata = mutableMapOf<String, String>()
                val (service, repository) = harness(metadata, candidates)

                val first = service.getComparisonStartProposal(now)
                first?.status shouldBe ComparisonProposalStatus.INCOMPLETE
                val storedCursor = metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS]
                storedCursor shouldBe "${candidates[8].timestamp.toEpochMilli()}:0"

                // A live append lands after the first request: re-stub the repository so the
                // second call sees the tail rows beyond the first scan's evidence horizon.
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns candidates + tail

                val resumed = service.getComparisonStartProposal(now)
                resumed?.status shouldBe ComparisonProposalStatus.EXHAUSTED
            }
        }

        "a material config change invalidates the incomplete proposal progress" {
            runTest {
                val candidates = (1..12).map { index -> snapshot(3600L * index, index) }
                val metadata = mutableMapOf<String, String>()
                val (service, repository) = harness(metadata, candidates)

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.INCOMPLETE
                val storedFingerprint = metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FINGERPRINT]

                metadata[SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT] = "changed"

                val invalidated = service.getComparisonStartProposal(now)
                // A resumed-but-valid scan would finish its four remaining candidates and
                // return EXHAUSTED; an invalidated one restarts at index zero and lands
                // INCOMPLETE again at the first batch boundary.
                invalidated?.status shouldBe ComparisonProposalStatus.INCOMPLETE
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] shouldBe
                    "${candidates[8].timestamp.toEpochMilli()}:0"
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FINGERPRINT]
                    .shouldNotBe(storedFingerprint)
            }
        }

        "a candidate anchored at an intrinsic failure event skips every untestable predecessor" {
            runTest {
                val candidates = (1..12).map { index -> snapshot(3600L * index, index) }
                val metadata = mutableMapOf<String, String>()
                val (service, repository) = harness(metadata, candidates)

                val proposal = service.findLaterComparisonStartProposal(
                    startAfter = now,
                    inceptionResolution = null,
                    skipCandidatesBefore = candidates[4].timestamp,
                )
                proposal.status shouldBe ComparisonProposalStatus.EXHAUSTED
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] shouldBe "EXHAUSTED"
            }
        }

        "a skip boundary past every candidate leaves the scan from the first candidate" {
            runTest {
                val candidates = (1..12).map { index -> snapshot(3600L * index, index) }
                val metadata = mutableMapOf<String, String>()
                val (service, repository) = harness(metadata, candidates)

                val proposal = service.findLaterComparisonStartProposal(
                    startAfter = now,
                    inceptionResolution = null,
                    skipCandidatesBefore = now.plusSeconds(3600L * 20),
                )
                proposal.status shouldBe ComparisonProposalStatus.INCOMPLETE
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] shouldBe
                    "${candidates[8].timestamp.toEpochMilli()}:0"
            }
        }

        "the bounded server-side continuation completes the scan without operator reloads" {
            val metadata = mutableMapOf<String, String>()
            val candidates = (1..12).map { index -> snapshot(3600L * index, index) }
            val continuationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val (service, _) = harness(metadata, candidates, applicationScope = continuationScope)

            runBlocking {
                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.INCOMPLETE
                withTimeout(10_000) {
                    var status: ComparisonProposalStatus? = null
                    while (status == null || status == ComparisonProposalStatus.INCOMPLETE) {
                        delay(50)
                        status = service.getComparisonStartProposal(now)?.status
                    }
                    status shouldBe ComparisonProposalStatus.EXHAUSTED
                }
            }
            continuationScope.cancel()
        }

        "a stale EXHAUSTED reopens through its frontier when evidence appends past the horizon" {
            runTest {
                val candidates = (1..12).map { index -> snapshot(3600L * index, index) }
                val tail = (13..15).map { index -> snapshot(3600L * index, index) }
                val metadata = mutableMapOf<String, String>()
                val (service, repository) = harness(metadata, candidates)

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.INCOMPLETE
                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.EXHAUSTED
                val exhaustedFingerprint = metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FINGERPRINT]
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_EVIDENCE_HORIZON_MS] shouldBe
                    candidates[11].timestamp.toEpochMilli().toString()
                // The final candidate always fails INSUFFICIENT_SNAPSHOTS: its comparison window
                // holds only itself until a future snapshot lands.
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_REASON] shouldBe
                    ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS.name
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_CURSOR_EPOCH_MS] shouldBe
                    candidates[11].timestamp.toEpochMilli().toString()

                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns candidates + tail
                val reopened = service.getComparisonStartProposal(now)

                reopened?.status shouldBe ComparisonProposalStatus.EXHAUSTED
                // Terminal exhaustion must not persist for an open-ended universe: the tail
                // extended it, so the horizon and fingerprint moved and the frontier run was
                // re-evaluated — the new final candidate is now the pending frontier.
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_EVIDENCE_HORIZON_MS] shouldBe
                    tail[2].timestamp.toEpochMilli().toString()
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FINGERPRINT]
                    .shouldNotBe(exhaustedFingerprint)
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_CURSOR_EPOCH_MS] shouldBe
                    tail[2].timestamp.toEpochMilli().toString()
            }
        }

        "a tail row beyond the horizon does not invalidate progress but a historical edit does" {
            runTest {
                val candidates = (1..12).map { index -> snapshot(3600L * index, index) }
                val tailTrade = TestFixtures.tradeRecord(
                    timestamp = now.plusSeconds(3600L * 14),
                    pair = "XXBTZUSD",
                    side = "buy",
                    symbol = "XXBT",
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal("50000.00"),
                )
                val historicalTrade = TestFixtures.tradeRecord(
                    timestamp = now.plusSeconds(3600L * 3),
                    pair = "XXBTZUSD",
                    side = "buy",
                    symbol = "XXBT",
                    volume = BigDecimal("0.5"),
                    usdAmount = BigDecimal("25000.00"),
                    id = 1,
                )
                val metadata = mutableMapOf<String, String>()
                val (service, repository) = harness(metadata, candidates)

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.INCOMPLETE
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] shouldBe
                    "${candidates[8].timestamp.toEpochMilli()}:0"

                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(tailTrade)
                val appended = service.getComparisonStartProposal(now)

                // The tail trade is outside the persisted horizon: the completed prefix stays
                // proven, the scan resumes at the cursor and finishes its remaining segment.
                appended?.status shouldBe ComparisonProposalStatus.EXHAUSTED
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] shouldBe "EXHAUSTED"

                // A historical trade inside the horizon is part of what tested candidates
                // consumed; its appearance invalidates the stored progress fail-closed.
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(historicalTrade)
                val invalidated = service.getComparisonStartProposal(now)

                invalidated?.status shouldBe ComparisonProposalStatus.INCOMPLETE
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] shouldBe
                    "${candidates[8].timestamp.toEpochMilli()}:0"
            }
        }

        "incomplete progress rewinds to a pending append-sensitive frontier after new evidence" {
            runTest {
                val candidates = (1..12).map { index -> snapshot(3600L * index, index) }
                val tail = (13..15).map { index -> snapshot(3600L * index, index) }
                val metadata = mutableMapOf<String, String>()
                val (service, repository) = harness(metadata, candidates)

                // Force a trailing INSUFFICIENT_SNAPSHOTS frontier run before the budget ends:
                // persist it by racing the scan to EXHAUSTED, then re-introduce a fresh
                // incomplete segment via appends and prove the frontier run is rewound to and
                // retried rather than trusted as immutable.
                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.INCOMPLETE
                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.EXHAUSTED
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_CURSOR_EPOCH_MS] shouldBe
                    candidates[11].timestamp.toEpochMilli().toString()

                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns candidates + tail
                val rewound = service.getComparisonStartProposal(now)

                // The rewind re-evaluates the frontier candidate with the newly appended
                // snapshots; the final candidate then carries the new pending frontier.
                rewound?.status shouldBe ComparisonProposalStatus.EXHAUSTED
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_CURSOR_EPOCH_MS] shouldBe
                    tail[2].timestamp.toEpochMilli().toString()
            }
        }
    }
}
