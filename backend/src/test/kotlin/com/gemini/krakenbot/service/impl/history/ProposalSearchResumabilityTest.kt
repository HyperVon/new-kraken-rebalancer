package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonProposalStatus
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.DepositStatusRecord
import com.gemini.krakenbot.model.FundingEvidence
import com.gemini.krakenbot.model.FundingProvenanceFailure
import com.gemini.krakenbot.model.FundingProvenanceFailureReason
import com.gemini.krakenbot.model.FundingProvenanceResolver
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SimpleFundingProvenanceResolver
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.OrderIntentRepository
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.KrakenService
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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

    /**
     * Snapshot with constant balances so trials anchored at it reconcile AVAILABLE against
     * sibling stable rows (no trades/ledgers to explain deltas) — the "cured candidate" lever
     * for reopen sequences.
     */
    private fun stableSnapshot(delaySeconds: Long, btcBalance: String = "1"): PortfolioSnapshot {
        val price = BigDecimal("50000.00")
        val balance = BigDecimal(btcBalance)
        return PortfolioSnapshot(
            timestamp = now.plusSeconds(delaySeconds),
            totalValueUSD = price.multiply(balance).plus(BigDecimal("50000.00")),
            assets = mapOf(
                Asset.BTC to TestFixtures.assetSnapshot(
                    symbol = Asset.BTC,
                    balance = balance,
                    price = price,
                    valueUSD = price.multiply(balance),
                    targetPercent = BigDecimal("10"),
                ),
                TestFixtures.USD to TestFixtures.assetSnapshot(
                    symbol = TestFixtures.USD,
                    balance = BigDecimal("50000.00"),
                    price = BigDecimal.ONE,
                    valueUSD = BigDecimal("50000.00"),
                    targetPercent = BigDecimal("90"),
                ),
            ),
            actions = emptyList(),
            drawdownPercent = BigDecimal.ZERO,
            fiatDeploymentPercent = BigDecimal.ZERO,
            effectiveUsdTargetPercent = BigDecimal.ZERO,
            balancesObservedAt = now.plusSeconds(delaySeconds),
        )
    }

    /**
     * Stable snapshot whose BTC position grew by the deposited amount so a BTC deposit
     * ledger row inside the evaluated window is balance-explained without introducing a
     * new asset. The DEPOSIT instant is priced only through the external OHLC provider —
     * the price-sensitive lever for frontier re-probe scenarios.
     */
    private fun fundedSnapshot(delaySeconds: Long): PortfolioSnapshot = stableSnapshot(delaySeconds, btcBalance = "3")

    private fun btcDeposit(at: Instant): LedgerEvent = LedgerEvent(
        ledgerId = "L-BTC-DEPOSIT",
        refid = "REF-BTC-DEPOSIT",
        time = at,
        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
        asset = "XXBT",
        amount = BigDecimal("2"),
    )

    /** Resolves the BTC deposit's external ownership so provenance never masks the price lever. */
    private fun externalFundingResolver(): FundingProvenanceResolver = object : FundingProvenanceResolver {
        override fun resolve(event: LedgerEvent): FundingEvidence = FundingEvidence.EXTERNAL

        override suspend fun prepare(events: Collection<LedgerEvent>): FundingProvenanceResolver = this

        override val evidenceFingerprint: String? = "external-funding-fingerprint"
    }

    private fun harness(
        metadata: MutableMap<String, String>,
        snapshots: List<PortfolioSnapshot>,
        confidentInception: Boolean = false,
        withInceptionService: Boolean = true,
        applicationScope: CoroutineScope? = null,
        fundingResolver: FundingProvenanceResolver = SimpleFundingProvenanceResolver(),
        ledgers: List<LedgerEvent> = emptyList(),
        krakenService: KrakenService? = null,
        historicalOhlcCache: HistoricalOhlcCache? = null,
    ): Triple<TradeHistoryQueryService, TradeRepository, LedgerRepository> {
        val repository = mockk<TradeRepository>(relaxed = true)
        val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
        coEvery { repository.getSyncMetadata(any()) } coAnswers { metadata[firstArg()] }
        metadata[SyncMetadataKeys.TRADE_COVERAGE_VERSION] = TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
        metadata[SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC] = "0"
        coEvery { repository.setSyncMetadataAtomically(any()) } coAnswers { metadata.putAll(firstArg()) }
        metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] = "4102444800"
        metadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] = "4102444800"
        metadata[SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC] = "0"
        metadata[SyncMetadataKeys.LEDGER_COVERAGE_VERSION] = LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
        metadata[SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST] = "test-scope"
        metadata[SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST] = "test-scope"
        metadata[SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST] = "test-scope"
        val inceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
        coEvery { inceptionService.resolveInceptionUnderEvidenceLock() } returns if (confidentInception) {
            InceptionResolution(
                inceptionTime = now,
                inceptionSnapshot = snapshots.firstOrNull()?.takeIf { it.timestamp == now },
                isAutoDetected = false,
            )
        } else {
            InceptionResolution(
                inceptionTime = now,
                inceptionSnapshot = null,
                isAutoDetected = false,
                confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                unavailableReason = ComparisonUnavailableReason.INCEPTION_BASELINE_UNAVAILABLE,
            )
        }
        coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns snapshots
        coEvery { repository.getSnapshotBefore(any()) } returns null
        coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
        coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns ledgers
        coEvery { ledgerRepository.getSyncMetadata(any()) } coAnswers { metadata[firstArg()] }
        val service = TradeHistoryQueryService(
            repository = repository,
            portfolioStatsRepository = mockk(relaxed = true),
            ledgerRepository = ledgerRepository,
            orderIntentRepository = mockk(relaxed = true),
            inceptionDiscoveryService = if (withInceptionService) inceptionService else null,
            fundingProvenanceResolver = fundingResolver,
            nowProvider = { now },
            krakenService = krakenService,
            historicalOhlcCache = historicalOhlcCache,
            applicationScope = applicationScope,
        )
        return Triple(service, repository, ledgerRepository)
    }

    private fun failedFundingResolver(): FundingProvenanceResolver = FundingProvenanceResolver.unavailable(
        FundingProvenanceFailure(
            reason = FundingProvenanceFailureReason.REQUEST_FAILED,
            message = "funding evidence unavailable",
        ),
    )

    private fun healthyFundingResolver(fingerprint: String): FundingProvenanceResolver =
        object : FundingProvenanceResolver {
            override fun resolve(event: LedgerEvent): FundingEvidence = FundingEvidence.UNRESOLVED

            override suspend fun prepare(events: Collection<LedgerEvent>): FundingProvenanceResolver = this

            override val evidenceFingerprint: String? = fingerprint
        }

    private fun scriptedFundingResolver(vararg stages: FundingProvenanceResolver): FundingProvenanceResolver {
        val queue = stages.toMutableList()
        return object : FundingProvenanceResolver {
            override fun resolve(event: LedgerEvent): FundingEvidence = FundingEvidence.UNRESOLVED

            override suspend fun prepare(events: Collection<LedgerEvent>): FundingProvenanceResolver =
                queue.removeFirst()
        }
    }

    init {
        "an append-only snapshot tail preserves an incomplete proposal cursor and finishes the scan" {
            runTest {
                val candidates = (1..12).map { index -> snapshot(3600L * index, index) }
                val tail = (13..15).map { index -> snapshot(3600L * index, index) }
                val metadata = mutableMapOf<String, String>()
                val (service, repository, ledgerRepository) = harness(metadata, candidates)

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

        "proposal search never verifies from an uncertified snapshot tail" {
            runTest {
                val stable = listOf(
                    stableSnapshot(3600),
                    stableSnapshot(7200),
                )
                val uncertifiedTail = stableSnapshot(10800, btcBalance = "2")
                val metadata = mutableMapOf<String, String>()
                val (service, _, _) = harness(metadata, stable + uncertifiedTail)
                val stableThrough = now.plusSeconds(7200)
                metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] = stableThrough.epochSecond.toString()
                metadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] = stableThrough.epochSecond.toString()

                val proposal = service.findVerifiedLaterComparisonStart(now)

                proposal shouldBe stable.first().timestamp
            }
        }

        "coverage catch-up invalidates a cached verified proposal before rescanning the new tail" {
            runTest {
                val stable = listOf(
                    stableSnapshot(3600),
                    stableSnapshot(7200),
                )
                val tail = stableSnapshot(10800, btcBalance = "2")
                val metadata = mutableMapOf<String, String>()
                val (service, _, _) = harness(metadata, stable + tail)
                val stableThrough = now.plusSeconds(7200)
                metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] = stableThrough.epochSecond.toString()
                metadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] = stableThrough.epochSecond.toString()

                val first = service.findLaterComparisonStartProposal(now, null)
                first.status shouldBe ComparisonProposalStatus.VERIFIED
                first.timestamp shouldBe stable.first().timestamp

                val caughtUpThrough = now.plusSeconds(10800)
                metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] =
                    caughtUpThrough.epochSecond.toString()
                metadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    caughtUpThrough.epochSecond.toString()

                val afterCatchUp = service.findLaterComparisonStartProposal(now, null)

                afterCatchUp.status shouldBe ComparisonProposalStatus.EXHAUSTED
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_COVERAGE_HORIZON_MS] shouldBe
                    ((caughtUpThrough.epochSecond + 1) * 1000 - 1).toString()
            }
        }

        "an out-of-scope stored evidence horizon rewinds proposal progress" {
            runTest {
                val candidates = (1..12).map { index -> snapshot(3600L * index, index) }
                val metadata = mutableMapOf<String, String>()
                val (service, _, _) = harness(metadata, candidates)

                service.findLaterComparisonStartProposal(now, null).status shouldBe
                    ComparisonProposalStatus.INCOMPLETE
                val cursor = "${candidates[8].timestamp.toEpochMilli()}:0"
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_EVIDENCE_HORIZON_MS] =
                    Long.MAX_VALUE.toString()

                service.findLaterComparisonStartProposal(now, null).status shouldBe
                    ComparisonProposalStatus.INCOMPLETE
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] shouldBe cursor

                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_EVIDENCE_HORIZON_MS] = "-1"
                service.findLaterComparisonStartProposal(now, null).status shouldBe
                    ComparisonProposalStatus.INCOMPLETE
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] shouldBe cursor
            }
        }

        "a material config change invalidates the incomplete proposal progress" {
            runTest {
                val candidates = (1..12).map { index -> snapshot(3600L * index, index) }
                val metadata = mutableMapOf<String, String>()
                val (service, repository, ledgerRepository) = harness(metadata, candidates)

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
                val (service, repository, ledgerRepository) = harness(metadata, candidates)

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
                val (service, repository, ledgerRepository) = harness(metadata, candidates)

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
            val metadata = ConcurrentHashMap<String, String>()
            val candidates = (1..12).map { index -> snapshot(3600L * index, index) }
            val continuationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val (service, _, _) = harness(metadata, candidates, applicationScope = continuationScope)

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

        "a cancelled continuation scope releases the guard instead of latching it" {
            runTest {
                val candidates = (1..12).map { index -> snapshot(3600L * index, index) }
                val metadata = mutableMapOf<String, String>()
                val cancelledScope = CoroutineScope(SupervisorJob().apply { cancel() })
                val (service, _, _) = harness(metadata, candidates, applicationScope = cancelledScope)

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.INCOMPLETE
                val guard = TradeHistoryQueryService::class.java
                    .getDeclaredField("proposalContinuationActive")
                    .apply { isAccessible = true }
                    .get(service) as AtomicBoolean
                guard.get() shouldBe false
            }
        }

        "a pending append-sensitive marker outranks a stored later VERIFIED on horizon advance" {
            runTest {
                // State 1: G has an unexplained delta; S1 is the final stable candidate and
                // fails INSUFFICIENT_SNAPSHOTS (window holds only itself) — natural pending
                // frontier. Seed a later durable VERIFIED anchor to represent the premise
                // being tested: stored VERIFIED at a candidate AFTER failing mark.
                val g = snapshot(3600, 1)
                val s1 = stableSnapshot(7200)
                val metadata = mutableMapOf<String, String>()
                val (service, repository, ledgerRepository) = harness(metadata, listOf(g, s1))

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.EXHAUSTED
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_CURSOR_EPOCH_MS] shouldBe
                    s1.timestamp.toEpochMilli().toString()

                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] =
                    ComparisonProposalStatus.VERIFIED.name
                val tempTs = now.plusSeconds(10800).toEpochMilli()
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] = "$tempTs:0"
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_SNAPSHOT_ID] = "0"

                val tail = listOf(stableSnapshot(10800), stableSnapshot(14400))
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(g, s1) + tail
                val cured = service.getComparisonStartProposal(now)

                cured?.status shouldBe ComparisonProposalStatus.VERIFIED
                cured?.timestamp shouldBe s1.timestamp
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_REASON].orEmpty() shouldBe ""
            }
        }

        "a stale EXHAUSTED with a pending append-sensitive frontier re-evaluates to a cure" {
            runTest {
                val g = snapshot(3600, 1)
                val a = stableSnapshot(7200)
                val metadata = mutableMapOf<String, String>()
                val (service, repository, ledgerRepository) = harness(metadata, listOf(g, a))

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.EXHAUSTED
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_REASON] shouldBe
                    ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS.name
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_CURSOR_EPOCH_MS] shouldBe
                    a.timestamp.toEpochMilli().toString()

                val tail = listOf(stableSnapshot(10800), stableSnapshot(14400))
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(g, a) + tail
                val cured = service.getComparisonStartProposal(now)

                cured?.status shouldBe ComparisonProposalStatus.VERIFIED
                cured?.timestamp shouldBe a.timestamp
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_REASON].orEmpty() shouldBe ""
            }
        }

        "an OHLC outage leaves a price-sensitive exhausted frontier bounded, and recovery cures it" {
            runTest {
                // S1/S2 hold 1 BTC, a BTC deposit lands at +9000s, and S3 holds 3 BTC. With
                // the OHLC source down, trials that must price the deposit fail
                // HISTORICAL_PRICE_SOURCE_ERROR; the deposit is the only cure lever, and no
                // row ever changes between the failing and cured calls.
                val s1 = stableSnapshot(3600)
                val s2 = stableSnapshot(7200)
                val s3 = fundedSnapshot(10800)
                val metadata = mutableMapOf<String, String>()
                val krakenService = mockk<KrakenService>()
                coEvery { krakenService.getOHLC(any(), any(), any()) } throws
                    RuntimeException("OHLC outage")
                val (service, _) = harness(
                    metadata,
                    listOf(s1, s2, s3),
                    ledgers = listOf(btcDeposit(now.plusSeconds(9000))),
                    fundingResolver = externalFundingResolver(),
                    krakenService = krakenService,
                    historicalOhlcCache = HistoricalOhlcCache(krakenService),
                )

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.EXHAUSTED
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_REASON] shouldBe
                    ComparisonUnavailableReason.HISTORICAL_PRICE_SOURCE_ERROR.name
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_CURSOR_EPOCH_MS] shouldBe
                    s1.timestamp.toEpochMilli().toString()

                // Still exhausted while the source stays down: the bounded per-call probe
                // burns one frontier-candidate trial, never a rescan of the universe.
                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.EXHAUSTED
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_REASON] shouldBe
                    ComparisonUnavailableReason.HISTORICAL_PRICE_SOURCE_ERROR.name

                // The provider recovers (backfill/correction class) with NO evidence change:
                // the stored frontier candidate becomes priceable and must be re-probed.
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns listOf(
                    (now.plusSeconds(9000).epochSecond - 900) to BigDecimal("50000"),
                )
                val cured = service.getComparisonStartProposal(now)

                cured?.status shouldBe ComparisonProposalStatus.VERIFIED
                cured?.timestamp shouldBe s1.timestamp
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_REASON].orEmpty() shouldBe ""
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] shouldBe
                    "${s1.timestamp.toEpochMilli()}:0"
            }
        }

        "a stored VERIFIED preserves its pending price frontier through revalidation and cures it on reopen" {
            runTest {
                // Scan: trials anchored at S1/S2 must price the +9000s deposit and fail; the
                // S3-anchored trial excludes the pre-anchor deposit and verifies. The earliest
                // price-sensitive mark at S1 stays pending under the later VERIFIED anchor.
                val s1 = stableSnapshot(3600)
                val s2 = stableSnapshot(7200)
                val s3 = fundedSnapshot(10800)
                val s4 = fundedSnapshot(14400)
                val metadata = mutableMapOf<String, String>()
                val krakenService = mockk<KrakenService>()
                coEvery { krakenService.getOHLC(any(), any(), any()) } throws
                    RuntimeException("OHLC outage")
                val (service, repository, _) = harness(
                    metadata,
                    listOf(s1, s2, s3, s4),
                    ledgers = listOf(btcDeposit(now.plusSeconds(9000))),
                    fundingResolver = externalFundingResolver(),
                    krakenService = krakenService,
                    historicalOhlcCache = HistoricalOhlcCache(krakenService),
                )

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.VERIFIED
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_REASON] shouldBe
                    ComparisonUnavailableReason.HISTORICAL_PRICE_SOURCE_ERROR.name
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_CURSOR_EPOCH_MS] shouldBe
                    s1.timestamp.toEpochMilli().toString()

                // Unchanged evidence revalidates the stored anchor. The revalidation persist
                // must carry the pending mark forward — blanking it here would strand the
                // earliest unresolved candidate forever.
                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.VERIFIED
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_REASON] shouldBe
                    ComparisonUnavailableReason.HISTORICAL_PRICE_SOURCE_ERROR.name
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_CURSOR_EPOCH_MS] shouldBe
                    s1.timestamp.toEpochMilli().toString()

                // A horizon advance reopens the scan AT the preserved mark; the recovered
                // price source cures it and the earliest start moves back to S1.
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns
                    listOf(s1, s2, s3, s4) + fundedSnapshot(18000)
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns listOf(
                    (now.plusSeconds(9000).epochSecond - 900) to BigDecimal("50000"),
                )
                val cured = service.getComparisonStartProposal(now)

                cured?.status shouldBe ComparisonProposalStatus.VERIFIED
                cured?.timestamp shouldBe s1.timestamp
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_REASON].orEmpty() shouldBe ""
            }
        }

        "an append-sensitive exhausted frontier performs no trials while evidence is unchanged" {
            runTest {
                val g = snapshot(3600, 1)
                val a = stableSnapshot(7200)
                val metadata = mutableMapOf<String, String>()
                val (service, repository, _) = harness(metadata, listOf(g, a))
                var tradeRangeCalls = 0
                coEvery { repository.getTradesInRange(any(), any()) } coAnswers {
                    tradeRangeCalls++
                    emptyList()
                }

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.EXHAUSTED
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_REASON] shouldBe
                    ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS.name
                val callsAfterExhaustion = tradeRangeCalls

                // Repeated polling with unchanged evidence must stay terminal with only the
                // fixed per-call digest read: zero trial reconciliation work, ever.
                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.EXHAUSTED
                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.EXHAUSTED

                tradeRangeCalls - callsAfterExhaustion shouldBe 2
            }
        }

        "a price-sensitive frontier with a lost or pruned cursor degrades to terminal exhaustion" {
            runTest {
                // A durable price mark whose cursor was lost (legacy state) or whose target
                // row was pruned must degrade to the SAME safe terminal state as an
                // append-sensitive mark: no probe, no relaunch, until fresh evidence.
                val g = snapshot(3600, 1)
                val a = stableSnapshot(7200)
                val metadata = mutableMapOf<String, String>()
                val (service, repository, _) = harness(metadata, listOf(g, a))
                var tradeRangeCalls = 0
                coEvery { repository.getTradesInRange(any(), any()) } coAnswers {
                    tradeRangeCalls++
                    emptyList()
                }

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.EXHAUSTED
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_REASON] =
                    ComparisonUnavailableReason.MISSING_PRICE.name
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_CURSOR_EPOCH_MS] = ""
                val callsWithLostCursor = tradeRangeCalls
                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.EXHAUSTED
                tradeRangeCalls - callsWithLostCursor shouldBe 1

                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_CURSOR_EPOCH_MS] =
                    (now.plusSeconds(99999)).toEpochMilli().toString()
                val callsWithPrunedCursor = tradeRangeCalls
                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.EXHAUSTED
                tradeRangeCalls - callsWithPrunedCursor shouldBe 1
            }
        }

        "a price-sensitive frontier pinned before an intrinsic failure stays skipped" {
            runTest {
                // An intrinsic event (e.g. an unexplained balance change) pinned the scan's
                // skip boundary after the marked frontier candidate: the contract forbids
                // that candidate from resurfacing, so the probe must stay suppressed.
                val g = snapshot(3600, 1)
                val a = stableSnapshot(7200)
                val metadata = mutableMapOf<String, String>()
                val (service, repository, _) = harness(metadata, listOf(g, a))
                var tradeRangeCalls = 0
                coEvery { repository.getTradesInRange(any(), any()) } coAnswers {
                    tradeRangeCalls++
                    emptyList()
                }

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.EXHAUSTED
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_REASON] =
                    ComparisonUnavailableReason.MISSING_PRICE.name
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_CURSOR_EPOCH_MS] =
                    g.timestamp.toEpochMilli().toString()
                val callsBefore = tradeRangeCalls
                val proposal = service.findLaterComparisonStartProposal(
                    startAfter = now,
                    inceptionResolution = null,
                    skipCandidatesBefore = a.timestamp,
                )
                proposal.status shouldBe ComparisonProposalStatus.EXHAUSTED
                tradeRangeCalls - callsBefore shouldBe 1
            }
        }

        "an appended trade advances the evidence horizon and reopens a stale exhausted scan" {
            runTest {
                // Trade rows are append-sensitive evidence too: a newly certified trade
                // beyond the stored horizon must reopen the scan exactly like a snapshot
                // append, and the proposal digest must consume the appended trade row.
                val g = snapshot(3600, 1)
                val a = stableSnapshot(7200)
                val metadata = mutableMapOf<String, String>()
                val (service, repository, _) = harness(metadata, listOf(g, a))

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.EXHAUSTED

                val appendedTrade = TestFixtures.tradeRecord(
                    timestamp = now.plusSeconds(9000),
                    pair = "XXBTZUSD",
                    side = "sell",
                    symbol = "XXBT",
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal("50000"),
                    success = false,
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(appendedTrade)
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(g, a)
                val reopened = service.getComparisonStartProposal(now)

                reopened?.status shouldBe ComparisonProposalStatus.EXHAUSTED
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_EVIDENCE_HORIZON_MS] shouldBe
                    now.plusSeconds(9000).toEpochMilli().toString()
            }
        }

        "the evidence-lock proposal entry evaluates the same gates for lock owners" {
            runTest {
                // Sync/recovery operations that already own the evidence coordinator call
                // the internal entry; it must run the identical gate chain and scan.
                val candidates = (1..12).map { index -> snapshot(3600L * index, index) }
                val metadata = mutableMapOf<String, String>()
                val (service, _, _) = harness(metadata, candidates)

                val underLock = service.getComparisonStartProposalUnderEvidenceLock(now)

                underLock?.status shouldBe ComparisonProposalStatus.INCOMPLETE
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] shouldBe
                    "${candidates[8].timestamp.toEpochMilli()}:0"
            }
        }

        "an unknown legacy frontier mark is not carried through a VERIFIED revalidation" {
            runTest {
                // A mark whose reason no longer maps to any known class cannot be honored by
                // any reopen path; carrying it would preserve junk, clearing it falls back to
                // the plain verified-anchor contract.
                val base = listOf(stableSnapshot(3600), stableSnapshot(7200))
                val metadata = mutableMapOf<String, String>()
                val (service, _) = harness(metadata, base)

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.VERIFIED
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_REASON] = "LEGACY_UNKNOWN"
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_CURSOR_EPOCH_MS] =
                    base[0].timestamp.toEpochMilli().toString()

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.VERIFIED

                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_REASON].orEmpty() shouldBe ""
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_CURSOR_EPOCH_MS]
                    .orEmpty() shouldBe ""
            }
        }

        "a stored VERIFIED without a durable snapshot identity is not trusted" {
            runTest {
                // The VERIFIED short-circuit pivots on the durable snapshot identity: state
                // whose id was lost (malformed or legacy write) must fall through to the
                // scan instead of standing in for a verified anchor.
                val base = listOf(stableSnapshot(3600), stableSnapshot(7200))
                val metadata = mutableMapOf<String, String>()
                val (service, _) = harness(metadata, base)

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.VERIFIED
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_SNAPSHOT_ID] = ""

                val reevaluated = service.getComparisonStartProposal(now)

                reevaluated?.status shouldBe ComparisonProposalStatus.VERIFIED
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_SNAPSHOT_ID] shouldBe "0"
            }
        }

        "changed funding evidence forces a stored VERIFIED anchor through re-scan" {
            runTest {
                // Funding evidence sits outside the row digest; a changed fingerprint bans
                // every reuse shortcut, including the cached VERIFIED anchor.
                val base = listOf(stableSnapshot(3600), stableSnapshot(7200))
                val metadata = mutableMapOf<String, String>()
                val (service, _) = harness(
                    metadata,
                    base,
                    fundingResolver = scriptedFundingResolver(
                        healthyFundingResolver("fingerprint-A"),
                        healthyFundingResolver("fingerprint-B"),
                    ),
                )

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.VERIFIED
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FUNDING_FINGERPRINT] shouldBe
                    "fingerprint-A"

                val reevaluated = service.getComparisonStartProposal(now)

                reevaluated?.status shouldBe ComparisonProposalStatus.VERIFIED
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FUNDING_FINGERPRINT] shouldBe
                    "fingerprint-B"
            }
        }

        "a non-sensitive failure of the marked candidate itself supersedes the mark" {
            runTest {
                // The pending mark means "earliest candidate whose outcome may still flip".
                // When the scan actually re-evaluates the marked candidate and its own
                // verdict is non-sensitive, that verdict is final: the stale mark must not
                // survive, and the scan's remaining append-sensitive failure takes over.
                val candidates = (1..12).map { index -> snapshot(3600L * index, index) }
                val metadata = mutableMapOf<String, String>()
                val (service, _, _) = harness(metadata, candidates)

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.INCOMPLETE
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_REASON] =
                    ComparisonUnavailableReason.MISSING_PRICE.name
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_CURSOR_EPOCH_MS] =
                    candidates[2].timestamp.toEpochMilli().toString()
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] =
                    "${candidates[0].timestamp.toEpochMilli()}:0"

                // The rewound scan re-evaluates the marked candidate; its non-sensitive
                // verdict clears the mark, and no sensitive failure replaces it.
                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.INCOMPLETE

                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_REASON].orEmpty() shouldBe ""
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_CURSOR_EPOCH_MS]
                    .orEmpty() shouldBe ""
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] shouldBe
                    "${candidates[8].timestamp.toEpochMilli()}:0"
            }
        }

        "a funding preparation failure fails closed and clears reuse state" {
            runTest {
                // With the funding fingerprint unknown, no stored state can be trusted:
                // the scan fails closed to INCOMPLETE and blanks the cached fingerprint
                // and any pending frontier mark in the same atomic write.
                val base = listOf(stableSnapshot(3600), stableSnapshot(7200))
                val metadata = mutableMapOf<String, String>()
                val (service, _) = harness(metadata, base, fundingResolver = failedFundingResolver())

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.INCOMPLETE
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FUNDING_FINGERPRINT].orEmpty() shouldBe ""
            }
        }

        "a stored VERIFIED with a mismatched snapshot identity is not trusted" {
            runTest {
                // A durable identity that no longer matches the candidate row (rewritten
                // store, dedupe collapse) must void the cached anchor and force re-scan.
                val base = listOf(stableSnapshot(3600), stableSnapshot(7200))
                val metadata = mutableMapOf<String, String>()
                val (service, _) = harness(metadata, base)

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.VERIFIED
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_SNAPSHOT_ID] = "999"

                val reevaluated = service.getComparisonStartProposal(now)

                reevaluated?.status shouldBe ComparisonProposalStatus.VERIFIED
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_SNAPSHOT_ID] shouldBe "0"
            }
        }

        "no pending frontier leaves a stored VERIFIED reusable across an appended tail" {
            runTest {
                val base = (1..12).map { index -> snapshot(3600L * index, index) } + stableSnapshot(3600L * 13)
                val metadata = mutableMapOf<String, String>()
                val (service, repository, ledgerRepository) = harness(metadata, base)

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.INCOMPLETE
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] =
                    ComparisonProposalStatus.VERIFIED.name
                val verifiedTs = base[12].timestamp.toEpochMilli()
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] = "$verifiedTs:0"
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_SNAPSHOT_ID] = "0"
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_REASON] = ""
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_CURSOR_EPOCH_MS] = ""
                val storedHorizonBefore =
                    metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_EVIDENCE_HORIZON_MS]

                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns
                    base + stableSnapshot(3600L * 14) + stableSnapshot(3600L * 15)
                val reused = service.getComparisonStartProposal(now)

                reused?.status shouldBe ComparisonProposalStatus.VERIFIED
                reused?.timestamp shouldBe base[12].timestamp
                storedHorizonBefore?.let {
                    metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_EVIDENCE_HORIZON_MS] shouldNotBe it
                }
            }
        }

        "a horizon advance with no pending mark resumes directly at the appended tail" {
            runTest {
                val base = (1..12).map { index -> snapshot(3600L * index, index) } + stableSnapshot(3600L * 13)
                val metadata = mutableMapOf<String, String>()
                val (service, repository, _) = harness(metadata, base)

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.INCOMPLETE
                // Review premise: with NO pending append-sensitive mark, the proven prefix may
                // stay skipped and the tail resumes directly. Simulate EXHAUSTED with the
                // frontier explicitly dropped.
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] =
                    ComparisonProposalStatus.EXHAUSTED.name
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] = "EXHAUSTED"
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_REASON] = ""
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_CURSOR_EPOCH_MS] = ""
                val storedHorizonBefore =
                    metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_EVIDENCE_HORIZON_MS]

                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns
                    base + stableSnapshot(3600L * 14) + stableSnapshot(3600L * 15)
                val firstTail = stableSnapshot(3600L * 14)
                val resumed = service.getComparisonStartProposal(now)

                resumed?.status shouldBe ComparisonProposalStatus.VERIFIED
                resumed?.timestamp shouldBe firstTail.timestamp
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] =
                    "${firstTail.timestamp.toEpochMilli()}:0"
            }
        }

        "a pending mark survives non-sensitive noise, budget bounds, and later exhaustion" {
            runTest {
                val candidates = (1..12).map { index -> snapshot(3600L * index, index) }
                val metadata = mutableMapOf<String, String>()
                val (service, repository, _) = harness(metadata, candidates)

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.INCOMPLETE
                // Seed the review premise: the earliest pending append-sensitive mark sits at
                // candidate 2, well before the resumed cursor.
                val markedTs = candidates[2].timestamp.toEpochMilli()
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_REASON] =
                    ComparisonUnavailableReason.MISSING_PRICE.name
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_CURSOR_EPOCH_MS] =
                    markedTs.toString()

                // Continued bounded scan without new evidence: resume at the cursor. Later
                // non-sensitive failures (and the final INSUFFICIENT_SNAPSHOTS candidate) must
                // not erase the EARLIEST pending mark.
                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.EXHAUSTED
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_REASON] shouldBe
                    ComparisonUnavailableReason.MISSING_PRICE.name
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_CURSOR_EPOCH_MS] shouldBe
                    markedTs.toString()
            }
        }

        "an invalidating appended tail re-opens past a stored VERIFIED anchor" {
            runTest {
                val base = listOf(stableSnapshot(3600), stableSnapshot(7200))
                val metadata = mutableMapOf<String, String>()
                val (service, repository, _) = harness(metadata, base)

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.VERIFIED

                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns
                    base + snapshot(3600L * 3, 1) + stableSnapshot(3600L * 4) + stableSnapshot(3600L * 5)
                val after = service.getComparisonStartProposal(now)

                // The appended growing snapshot breaks the stored anchor's reconciliation, so
                // the stale VERIFIED at the earliest candidate must not be returned; the scan
                // continues and reaches the later stable run.
                after?.status shouldBe ComparisonProposalStatus.VERIFIED
                after?.timestamp shouldBe stableSnapshot(3600L * 4).timestamp
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FRONTIER_REASON].orEmpty() shouldBe ""
            }
        }

        "a stale VERIFIED never bypasses skipCandidatesBefore" {
            runTest {
                val base = listOf(stableSnapshot(3600), stableSnapshot(7200))
                val metadata = mutableMapOf<String, String>()
                val service = harness(metadata, base).first

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.VERIFIED

                val proposal = service.findLaterComparisonStartProposal(
                    startAfter = now,
                    inceptionResolution = null,
                    skipCandidatesBefore = now.plusSeconds(5000),
                )
                // The verified candidate precedes the intrinsic failure event, so it may not
                // resurface; the remaining reachable anchors exhaust.
                proposal.status shouldBe ComparisonProposalStatus.EXHAUSTED
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] shouldBe "EXHAUSTED"
            }
        }

        "without a horizon advance the stored VERIFIED revalidates current evidence" {
            runTest {
                val base = listOf(stableSnapshot(3600), stableSnapshot(7200))
                val metadata = mutableMapOf<String, String>()
                val (service, repository, _) = harness(metadata, base)
                var tradeRangeCalls = 0
                coEvery { repository.getTradesInRange(any(), any()) } coAnswers {
                    tradeRangeCalls++
                    emptyList()
                }

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.VERIFIED
                val fingerprintBefore =
                    metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FINGERPRINT]

                val reused = service.getComparisonStartProposal(now)

                reused?.status shouldBe ComparisonProposalStatus.VERIFIED
                reused?.timestamp shouldBe base[0].timestamp
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FINGERPRINT] shouldBe fingerprintBefore
                // Each proposal call reads the bounded digest once and replays the cached
                // candidate through the current historical price/evidence path twice.
                tradeRangeCalls shouldBe 6
            }
        }

        "a stale EXHAUSTED reopens through its frontier when evidence appends past the horizon" {
            runTest {
                val candidates = (1..12).map { index -> snapshot(3600L * index, index) }
                val tail = (13..15).map { index -> snapshot(3600L * index, index) }
                val metadata = mutableMapOf<String, String>()
                val (service, repository, ledgerRepository) = harness(metadata, candidates)

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
                val extraHistoricalTrade = TestFixtures.tradeRecord(
                    timestamp = now.plusSeconds(3600L * 4),
                    pair = "XXBTZUSD",
                    side = "sell",
                    symbol = "XXBT",
                    volume = BigDecimal("0.25"),
                    usdAmount = BigDecimal("12500.00"),
                )
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
                val (service, repository, ledgerRepository) = harness(metadata, candidates)

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
                coEvery { repository.getTradesInRange(any(), any()) } returns
                    listOf(extraHistoricalTrade, historicalTrade)
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
                val (service, repository, ledgerRepository) = harness(metadata, candidates)

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

        "funding preparation failure without a horizon advance fails closed" {
            runTest {
                val base = listOf(stableSnapshot(3600), stableSnapshot(7200))
                val metadata = mutableMapOf<String, String>()
                val service = harness(
                    metadata,
                    base,
                    fundingResolver = scriptedFundingResolver(
                        healthyFundingResolver("funding-v1"),
                        failedFundingResolver(),
                    ),
                ).first

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.VERIFIED

                val reused = service.getComparisonStartProposal(now)

                reused?.status shouldBe ComparisonProposalStatus.INCOMPLETE
                reused?.timestamp shouldBe null
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] shouldBe
                    "${base[0].timestamp.toEpochMilli()}:0"
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FUNDING_FINGERPRINT] shouldBe ""
            }
        }

        "a horizon advance during funding preparation failure fails closed to INCOMPLETE" {
            runTest {
                val base = listOf(stableSnapshot(3600), stableSnapshot(7200))
                val tail = listOf(stableSnapshot(3600L * 3), stableSnapshot(3600L * 4))
                val metadata = mutableMapOf<String, String>()
                val (service, repository, _) = harness(
                    metadata,
                    base,
                    fundingResolver = scriptedFundingResolver(
                        healthyFundingResolver("funding-v1"),
                        failedFundingResolver(),
                    ),
                )

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.VERIFIED

                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns base + tail
                val failed = service.getComparisonStartProposal(now)

                failed?.status shouldBe ComparisonProposalStatus.INCOMPLETE
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] shouldBe
                    ComparisonProposalStatus.INCOMPLETE.name
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] shouldBe
                    "${base[0].timestamp.toEpochMilli()}:0"
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FUNDING_FINGERPRINT] shouldBe ""
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_EVIDENCE_HORIZON_MS] shouldBe
                    tail[1].timestamp.toEpochMilli().toString()
            }
        }

        "a cached VERIFIED never bypasses skipCandidatesBefore during preparation failure" {
            runTest {
                val base = listOf(stableSnapshot(3600), stableSnapshot(7200))
                val metadata = mutableMapOf<String, String>()
                val service = harness(
                    metadata,
                    base,
                    fundingResolver = scriptedFundingResolver(
                        healthyFundingResolver("funding-v1"),
                        failedFundingResolver(),
                    ),
                ).first

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.VERIFIED

                val proposal = service.findLaterComparisonStartProposal(
                    startAfter = now,
                    inceptionResolution = InceptionResolution(
                        inceptionTime = now,
                        inceptionSnapshot = null,
                        isAutoDetected = false,
                        confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                        unavailableReason = ComparisonUnavailableReason.INCEPTION_BASELINE_UNAVAILABLE,
                    ),
                    skipCandidatesBefore = base[1].timestamp,
                )

                proposal.status shouldBe ComparisonProposalStatus.INCOMPLETE
            }
        }

        "a recovery after preparation failure rescans under the enlarged horizon before VERIFIED" {
            runTest {
                val base = listOf(stableSnapshot(3600), stableSnapshot(7200))
                val tail = listOf(stableSnapshot(3600L * 3), stableSnapshot(3600L * 4))
                val metadata = mutableMapOf<String, String>()
                val (service, repository, _) = harness(
                    metadata,
                    base,
                    fundingResolver = scriptedFundingResolver(
                        healthyFundingResolver("funding-v1"),
                        failedFundingResolver(),
                        healthyFundingResolver("funding-v1"),
                    ),
                )

                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.VERIFIED

                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns base + tail
                service.getComparisonStartProposal(now)?.status shouldBe ComparisonProposalStatus.INCOMPLETE
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FUNDING_FINGERPRINT] shouldBe ""

                val recovered = service.getComparisonStartProposal(now)

                recovered?.status shouldBe ComparisonProposalStatus.VERIFIED
                recovered?.timestamp shouldBe base[0].timestamp
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_FUNDING_FINGERPRINT] shouldBe "funding-v1"
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_EVIDENCE_HORIZON_MS] shouldBe
                    tail[1].timestamp.toEpochMilli().toString()
            }
        }
    }
}
