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
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.OrderIntentRepository
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
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
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = t.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns series
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

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
                coEvery { mockInceptionService.resolveInception() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns series
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

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
