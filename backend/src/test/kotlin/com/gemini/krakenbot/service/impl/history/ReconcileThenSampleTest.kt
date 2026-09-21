package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures.assetSnapshot
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.FundingEvidence
import com.gemini.krakenbot.model.FundingProvenanceResolver
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.OrderIntentRepository
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant

/**
 * Reconciliation correctness must not depend on chart sampling: the service reconciles the
 * full retained snapshot series and only downsamples the resulting comparison points for
 * display. These tests pin that pipeline order — collapsing duplicate instants or striding
 * snapshots before the calculator provably loses multi-event instants the calculator
 * contracts require.
 */
class ReconcileThenSampleTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val openEndedRangeEnd = Instant.ofEpochMilli(Long.MAX_VALUE)
    private val repository = mockk<TradeRepository>(relaxed = true)
    private val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
    private val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
    private val orderIntentRepository = mockk<OrderIntentRepository>(relaxed = true)

    private val now = Instant.parse("2026-07-01T12:00:00Z")

    private val provenance = FundingProvenanceResolver { event ->
        if (event.subtype.isNullOrBlank()) {
            when (event.type) {
                KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                -> FundingEvidence.EXTERNAL

                else -> FundingEvidence.UNRESOLVED
            }
        } else {
            FundingEvidence.UNRESOLVED
        }
    }

    init {
        coEvery { repository.getAllSnapshotsInRange(any(), any()) } coAnswers {
            repository.getSnapshotsInRange(firstArg(), secondArg())
        }
        coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) } returns
            TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
        coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns "0"
        coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) } returns
            LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
        coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns "0"
        coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
            "test-scope"
        coEvery { repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) } returns
            "test-scope"
        coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
            "test-scope"

        "collapsing a multi-row instant silently drops reconciled states" {
            runTest {
                val t = now
                val base = snap(t.minusSeconds(3600), "389.28", mapOf("USD" to row("389.2793", "1", "389.28")))
                val pre = snap(t, "389.28", mapOf("USD" to row("389.2793", "1", "389.28")))
                val dep = snap(t, "1389.28", mapOf("USD" to row("1389.2793", "1", "1389.28")))
                val conv = snap(t, "389.28", mapOf("USD" to row("389.2793", "1", "389.28")))
                val context = listOf(deposit(t.minusSeconds(3600), "389.2793", "389.2793", "ctx-dep", "FT-ctx-dep"))
                val ledgers = listOf(
                    deposit(t, "1000", "1389.2793", "dep-1", "FT-dep-1"),
                    conversionSpend(t, "conv-spend"),
                    conversionReceive(t, "conv-receive"),
                )

                val full = RebalancerComparisonCalculator.calculate(
                    snapshots = listOf(base, pre, dep, conv),
                    trades = emptyList(),
                    rewards = ledgers,
                    ledgerContext = context,
                    provenanceResolver = provenance,
                )
                full.availability shouldBe ComparisonAvailability.AVAILABLE
                full.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1389.28")

                // The pre-reconciliation collapse keeps only the first row per instant, so the
                // merged output looks healthy while silently discarding the deposit and
                // conversion evidence the full series reconciles. Sampling can mask missing
                // evidence as well as destroy it, so the service must never reduce input.
                val merged = RebalancerComparisonCalculator.calculate(
                    snapshots = listOf(base, pre),
                    trades = emptyList(),
                    rewards = ledgers,
                    ledgerContext = context,
                    provenanceResolver = provenance,
                )
                merged.points.size shouldBe 2
            }
        }

        "service reconciles intermediate same-instant states instead of collapsing them" {
            runTest {
                val t = now

                // Two rows share the instant t. A pre-reconciliation collapse keeps only the
                // first row per instant and would return 3 points; the full series reconciles
                // all four retained states. Balances are constant and event-free so the only
                // variable under test is whether the pipeline preserves input states.
                fun steady(): Map<String, Triple<String, String, String>> = mapOf(
                    "BTC" to row("1.0", "50000.00", "50000.00"),
                    "USD" to row("50000.00", "1", "50000.00"),
                )
                val first = snap(t.minusSeconds(3600), "100000.00", steady(), t.minusSeconds(3600))
                val pre = snap(t, "100000.00", steady(), t)
                val post = snap(t, "100000.00", steady(), t)
                val last = snap(t.plusSeconds(3600), "100000.00", steady(), t.plusSeconds(3600))
                val series = listOf(first, pre, post, last)
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = t.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns series
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC)
                } returns "4102444800"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC)
                } returns "4102444800"

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                val comparison = service.getRebalancerComparison(t.minusSeconds(3600), t.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe t.minusSeconds(3600)
                comparison.points.size shouldBe 4
                comparison.points.map { it.timestamp } shouldBe series.map { it.timestamp }
                comparison.latestDifferenceUSD!! shouldBeEqualComparingTo BigDecimal.ZERO
            }
        }

        "comparison points stay bounded while economics come from the full series" {
            runTest {
                val count = 301
                val series = (0 until count).map { i ->
                    snap(
                        now.plusSeconds(i * 60L),
                        "100000.00",
                        mapOf(
                            "BTC" to row("1.0", "50000.00", "50000.00"),
                            "USD" to row("50000.00", "1", "50000.00"),
                        ),
                        now.plusSeconds(i * 60L),
                    )
                }
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns series
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC)
                } returns "4102444800"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC)
                } returns "4102444800"

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                val comparison = service.getRebalancerComparison(now, now.plusSeconds(count * 60L))

                val full = RebalancerComparisonCalculator.calculate(
                    snapshots = series,
                    trades = emptyList(),
                    anchorSnapshot = null,
                    inceptionSnapshot = series.first(),
                    knownInceptionTime = series.first().timestamp,
                )

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.points.size shouldBe 300
                comparison.points.first().timestamp shouldBe series.first().timestamp
                comparison.points.last().timestamp shouldBe series.last().timestamp
                comparison.points.first().buyAndHoldValueUSD shouldBeEqualComparingTo
                    full.points.first().buyAndHoldValueUSD
                comparison.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo
                    full.points.last().buyAndHoldValueUSD
                (comparison.latestDifferenceUSD ?: BigDecimal.ZERO) shouldBeEqualComparingTo
                    full.latestDifferenceUSD!!

                // The exact 300-point boundary must still be a display operation: removing the
                // first accounting row leaves 300 returned points, all of which retain the
                // economics of their corresponding full-fidelity calculation.
                val exactDisplay = service.getRebalancerComparison(
                    now.plusSeconds(60),
                    now.plusSeconds(count * 60L),
                )
                exactDisplay.points.size shouldBe 300
                val fullByTimestamp = full.points.associateBy { it.timestamp }
                exactDisplay.points.forEach { point ->
                    val fullPoint = fullByTimestamp.getValue(point.timestamp)
                    point.buyAndHoldValueUSD shouldBeEqualComparingTo fullPoint.buyAndHoldValueUSD
                    point.differenceUSD shouldBeEqualComparingTo fullPoint.differenceUSD
                }
            }
        }

        "short display ranges reconcile the full prefix and preserve overlapping economics" {
            runTest {
                val baselineTime = now.minusSeconds(4 * 3600L)
                val intermediateTime = now.minusSeconds(3 * 3600L)
                val displayStart = now.minusSeconds(2 * 3600L)
                val displayEnd = now
                val baseline = snap(
                    baselineTime,
                    "100000.00",
                    mapOf(
                        "BTC" to row("1.0", "50000.00", "50000.00"),
                        "USD" to row("50000.00", "1", "50000.00"),
                    ),
                    baselineTime,
                )
                val intermediate = snap(
                    intermediateTime,
                    "100000.00",
                    mapOf(
                        "BTC" to row("1.0", "50000.00", "50000.00"),
                        "USD" to row("50000.00", "1", "50000.00"),
                    ),
                    intermediateTime,
                )
                val firstDisplay = snap(
                    displayStart,
                    "110000.00",
                    mapOf(
                        "BTC" to row("1.0", "60000.00", "60000.00"),
                        "USD" to row("50000.00", "1", "50000.00"),
                    ),
                    displayStart,
                )
                val lastDisplay = snap(
                    displayEnd,
                    "111000.00",
                    mapOf(
                        "BTC" to row("1.0", "61000.00", "61000.00"),
                        "USD" to row("50000.00", "1", "50000.00"),
                    ),
                    displayEnd,
                )
                val series = listOf(baseline, intermediate, firstDisplay, lastDisplay)
                val inceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { inceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = baselineTime,
                    inceptionSnapshot = baseline,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.CONFIDENT,
                )
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } coAnswers {
                    val lower: Instant = firstArg()
                    val upper: Instant = secondArg()
                    series.filter { !it.timestamp.isBefore(lower) && !it.timestamp.isAfter(upper) }
                }
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC)
                } returns "4102444800"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC)
                } returns "4102444800"
                coEvery { repository.getSnapshotBefore(any()) } returns null

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = inceptionService,
                )

                val lifetime = service.getRebalancerComparison(baselineTime, displayEnd)
                val shortRange = service.getRebalancerComparison(displayStart, displayEnd)

                lifetime.availability shouldBe ComparisonAvailability.AVAILABLE
                shortRange.availability shouldBe ComparisonAvailability.AVAILABLE
                shortRange.baselineTimestamp shouldBe baselineTime
                shortRange.points.map { it.timestamp } shouldBe listOf(displayStart, displayEnd)
                val lifetimeByTimestamp = lifetime.points.associateBy { it.timestamp }
                shortRange.points.forEach { point ->
                    val lifetimePoint = lifetimeByTimestamp.getValue(point.timestamp)
                    point.rebalancerValueUSD shouldBeEqualComparingTo lifetimePoint.rebalancerValueUSD
                    point.buyAndHoldValueUSD shouldBeEqualComparingTo lifetimePoint.buyAndHoldValueUSD
                    point.differenceUSD shouldBeEqualComparingTo lifetimePoint.differenceUSD
                    point.differencePercent shouldBeEqualComparingTo lifetimePoint.differencePercent
                }
                shortRange.latestDifferenceUSD!! shouldBeEqualComparingTo shortRange.points.last().differenceUSD
                shortRange.latestDifferencePercent!! shouldBeEqualComparingTo
                    shortRange.points.last().differencePercent

                val oneDisplayPoint = service.getRebalancerComparison(displayStart, displayStart)
                oneDisplayPoint.availability shouldBe ComparisonAvailability.UNAVAILABLE
                oneDisplayPoint.unavailableReason shouldBe ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS
                coVerify(atLeast = 1) {
                    repository.getAllSnapshotsInRange(baselineTime, displayEnd)
                }
            }
        }

        "stale coverage versions defer comparison even with far-future horizons" {
            runTest {
                val first = snap(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to row("1.0", "50000.00", "50000.00"),
                        "USD" to row("50000.00", "1", "50000.00"),
                    ),
                    now,
                )
                val last = first.copy(timestamp = now.plusSeconds(3600))
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(first, last)
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) } returns "1"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                )

                val comparison = service.getRebalancerComparison(now, last.timestamp)

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS
            }
        }

        "overflowing coverage horizons defer comparison without throwing" {
            runTest {
                val first = snap(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to row("1.0", "50000.00", "50000.00"),
                        "USD" to row("50000.00", "1", "50000.00"),
                    ),
                    now,
                )
                val last = first.copy(timestamp = now.plusSeconds(3600))
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(first, last)
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC)
                } returns Long.MAX_VALUE.toString()
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC)
                } returns Long.MAX_VALUE.toString()

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                )

                val comparison = service.getRebalancerComparison(now, last.timestamp)

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS
            }
        }

        "epoch-second horizons that overflow epoch milliseconds defer comparison without throwing" {
            runTest {
                val first = snap(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to row("1.0", "50000.00", "50000.00"),
                        "USD" to row("50000.00", "1", "50000.00"),
                    ),
                    now,
                )
                val last = first.copy(timestamp = now.plusSeconds(3600))
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(first, last)
                val validButUnrepresentableEpochSecond = "9223372036854776"
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC)
                } returns validButUnrepresentableEpochSecond
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC)
                } returns validButUnrepresentableEpochSecond

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                )

                val comparison = service.getRebalancerComparison(now, last.timestamp)

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS
            }
        }

        "missing coverage starts defer comparison even with current horizons" {
            runTest {
                val first = snap(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to row("1.0", "50000.00", "50000.00"),
                        "USD" to row("50000.00", "1", "50000.00"),
                    ),
                    now,
                )
                val last = first.copy(timestamp = now.plusSeconds(3600))
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(first, last)
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns null

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                )

                val comparison = service.getRebalancerComparison(now, last.timestamp)

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS
            }
        }

        "mismatched coverage scopes defer comparison" {
            runTest {
                val first = snap(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to row("1.0", "50000.00", "50000.00"),
                        "USD" to row("50000.00", "1", "50000.00"),
                    ),
                    now,
                )
                val last = first.copy(timestamp = now.plusSeconds(3600))
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(first, last)
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                } returns "account-a"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                } returns "account-b"

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                )

                val comparison = service.getRebalancerComparison(now, last.timestamp)

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS
            }
        }

        "coverage certificate rejects negative starts, future starts, and lagging horizons" {
            runTest {
                val first = snap(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to row("1.0", "50000.00", "50000.00"),
                        "USD" to row("50000.00", "1", "50000.00"),
                    ),
                    now,
                )
                val last = first.copy(timestamp = now.plusSeconds(3600))
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(first, last)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC)
                } returns "4102444800"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC)
                } returns "4102444800"
                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                )

                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns "-1"
                service.getRebalancerComparison(now, last.timestamp).availability shouldBe
                    ComparisonAvailability.UNAVAILABLE

                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns "0"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    "-1"
                service.getRebalancerComparison(now, last.timestamp).availability shouldBe
                    ComparisonAvailability.UNAVAILABLE

                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    "4102444800"
                service.getRebalancerComparison(now, last.timestamp).availability shouldBe
                    ComparisonAvailability.UNAVAILABLE

                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns "0"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    "0"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "0"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    "100"
                service.getRebalancerComparison(now, last.timestamp).availability shouldBe
                    ComparisonAvailability.UNAVAILABLE

                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    "0"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns "0"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns "100"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                service.getRebalancerComparison(now, last.timestamp).availability shouldBe
                    ComparisonAvailability.UNAVAILABLE

                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns "0"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    "0"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns "0"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "0"
                service.getRebalancerComparison(now, last.timestamp).availability shouldBe
                    ComparisonAvailability.UNAVAILABLE

                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    null
                service.getRebalancerComparison(now, last.timestamp).availability shouldBe
                    ComparisonAvailability.UNAVAILABLE

                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns null
                service.getRebalancerComparison(now, last.timestamp).availability shouldBe
                    ComparisonAvailability.UNAVAILABLE

                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns "0"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    null
                service.getRebalancerComparison(now, last.timestamp).availability shouldBe
                    ComparisonAvailability.UNAVAILABLE

                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    "0"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
                    "account-a"
                service.getRebalancerComparison(now, last.timestamp).availability shouldBe
                    ComparisonAvailability.UNAVAILABLE

                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
                    null
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                } returns
                    "account-a"
                service.getRebalancerComparison(now, last.timestamp).availability shouldBe
                    ComparisonAvailability.UNAVAILABLE

                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
                    null
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                } returns
                    null
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns "0"
                service.getRebalancerComparison(now, last.timestamp).availability shouldBe
                    ComparisonAvailability.UNAVAILABLE

                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "9223372036854775"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "9223372036854775"
                service.getRebalancerComparison(now, last.timestamp).availability shouldBe
                    ComparisonAvailability.UNAVAILABLE

                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "-1"
                service.getRebalancerComparison(now, last.timestamp).availability shouldBe
                    ComparisonAvailability.UNAVAILABLE

                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns "-1"
                service.getRebalancerComparison(now, last.timestamp).availability shouldBe
                    ComparisonAvailability.UNAVAILABLE

                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "0"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                service.getRebalancerComparison(now, last.timestamp).availability shouldBe
                    ComparisonAvailability.UNAVAILABLE

                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    "0"
                service.getRebalancerComparison(now, last.timestamp).availability shouldBe
                    ComparisonAvailability.UNAVAILABLE

                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns "0"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
                    "account-a"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                } returns
                    "account-a"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) } returns
                    null
                service.getRebalancerComparison(now, last.timestamp).availability shouldBe
                    ComparisonAvailability.UNAVAILABLE
            }
        }

        "account-scoped coverage requires a shared inception binding" {
            runTest {
                val first = snap(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to row("1.0", "50000.00", "50000.00"),
                        "USD" to row("50000.00", "1", "50000.00"),
                    ),
                    now,
                )
                val last = first.copy(timestamp = now.plusSeconds(3600))
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(first, last)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC)
                } returns "4102444800"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC)
                } returns "4102444800"
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                } returns "account-a"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                } returns "account-a"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST)
                } returns "account-b"
                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                )

                val comparison = service.getRebalancerComparison(now, last.timestamp)

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS
            }
        }

        "unbound coverage cannot certify against a bound inception scope" {
            runTest {
                val first = snap(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to row("1.0", "50000.00", "50000.00"),
                        "USD" to row("50000.00", "1", "50000.00"),
                    ),
                    now,
                )
                val last = first.copy(timestamp = now.plusSeconds(3600))
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(first, last)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
                    ""
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                } returns ""
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) } returns
                    "account-a"
                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                )

                val comparison = service.getRebalancerComparison(now, last.timestamp)

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS
            }
        }

        "comparison trims the uncertified live tail and reconciles the stable prefix" {
            runTest {
                val first = snap(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to row("1.0", "50000.00", "50000.00"),
                        "USD" to row("50000.00", "1", "50000.00"),
                    ),
                    now,
                )
                val second = first.copy(timestamp = now.plusSeconds(3600))
                val uncertifiedTail = first.copy(timestamp = now.plusSeconds(7200))
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns
                    listOf(first, second, uncertifiedTail)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    now.plusSeconds(3600).epochSecond.toString()
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC)
                } returns
                    now.plusSeconds(3600).epochSecond.toString()
                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                )

                val comparison = service.getRebalancerComparison(now, uncertifiedTail.timestamp)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
            }
        }

        "matching scoped coverage with a matching inception binding remains usable" {
            runTest {
                val first = snap(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to row("1.0", "50000.00", "50000.00"),
                        "USD" to row("50000.00", "1", "50000.00"),
                    ),
                    now,
                )
                val last = first.copy(timestamp = now.plusSeconds(3600))
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(first, last)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
                    "account-a"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                } returns
                    "account-a"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) } returns
                    "account-a"
                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                )

                val comparison = service.getRebalancerComparison(now, last.timestamp)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
            }
        }

        "stable-history replay does not query events past the certified horizon" {
            runTest {
                val stableThrough = now.plusSeconds(3600)
                val first = snap(
                    now,
                    "100000.00",
                    mapOf(
                        "BTC" to row("1.0", "50000.00", "50000.00"),
                        "USD" to row("50000.00", "1", "50000.00"),
                    ),
                    now,
                )
                val last = snap(
                    stableThrough,
                    "110000.00",
                    mapOf(
                        "BTC" to row("1.0", "60000.00", "60000.00"),
                        "USD" to row("50000.00", "1", "50000.00"),
                    ),
                    stableThrough,
                )
                val inceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { inceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = first.timestamp,
                    inceptionSnapshot = first,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.CONFIDENT,
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(first, last)
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    stableThrough.epochSecond.toString()
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    stableThrough.epochSecond.toString()
                val queriedTradeTos = mutableListOf<Instant>()
                val queriedLedgerTos = mutableListOf<Instant>()
                coEvery { repository.getTradesInRange(any(), any()) } answers {
                    queriedTradeTos += secondArg<Instant>()
                    emptyList()
                }
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } answers {
                    queriedLedgerTos += secondArg<Instant>()
                    emptyList()
                }

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = inceptionService,
                )

                val comparison = service.getRebalancerComparison(first.timestamp, last.timestamp)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                val certifiedEventEnd = stableThrough.plusNanos(999_999_999)
                queriedTradeTos.any { it == certifiedEventEnd } shouldBe true
                queriedTradeTos.filter { it != openEndedRangeEnd }.all { !it.isAfter(certifiedEventEnd) } shouldBe true
                queriedLedgerTos.all { !it.isAfter(certifiedEventEnd) } shouldBe true
            }
        }
    }

    private fun row(balance: String, price: String, valueUSD: String) = Triple(balance, price, valueUSD)

    private fun snap(
        timestamp: Instant,
        totalValueUSD: String,
        assets: Map<String, Triple<String, String, String>>,
        balancesObservedAt: Instant? = null,
    ): PortfolioSnapshot {
        val assetSnapshots = assets.mapValues { (symbol, triple) ->
            assetSnapshot(
                symbol = symbol,
                balance = BigDecimal(triple.first),
                price = BigDecimal(triple.second),
                valueUSD = BigDecimal(triple.third),
                targetPercent = BigDecimal.ZERO,
            )
        }
        return PortfolioSnapshot(
            timestamp = timestamp,
            totalValueUSD = BigDecimal(totalValueUSD),
            assets = assetSnapshots,
            actions = emptyList(),
            drawdownPercent = BigDecimal.ZERO,
            fiatDeploymentPercent = BigDecimal.ZERO,
            effectiveUsdTargetPercent = BigDecimal.ZERO,
            balancesObservedAt = balancesObservedAt,
        )
    }

    private fun deposit(
        timestamp: Instant,
        amount: String,
        balance: String,
        ledgerId: String,
        refid: String,
    ): LedgerEvent = LedgerEvent(
        ledgerId = ledgerId,
        refid = refid,
        aclass = "currency",
        time = timestamp,
        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
        subtype = null,
        asset = "USD",
        amount = BigDecimal(amount),
        fee = BigDecimal.ZERO,
        balance = BigDecimal(balance),
        hasAuthoritativeBalance = true,
        hasAuthoritativeFee = true,
    )

    private fun conversionSpend(timestamp: Instant, ledgerId: String): LedgerEvent = LedgerEvent(
        ledgerId = ledgerId,
        refid = "Unknown",
        aclass = "currency",
        time = timestamp,
        type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
        subtype = null,
        asset = "USD",
        amount = BigDecimal("-1000"),
        fee = BigDecimal.ZERO,
        balance = BigDecimal("389.2793"),
        hasAuthoritativeBalance = true,
        hasAuthoritativeFee = true,
    )

    private fun conversionReceive(timestamp: Instant, ledgerId: String): LedgerEvent = LedgerEvent(
        ledgerId = ledgerId,
        refid = "Unknown",
        aclass = "currency",
        time = timestamp,
        type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
        subtype = null,
        asset = "USDG",
        amount = BigDecimal("1000"),
        fee = BigDecimal.ZERO,
        balance = BigDecimal("1000"),
        hasAuthoritativeBalance = true,
        hasAuthoritativeFee = true,
    )
}
