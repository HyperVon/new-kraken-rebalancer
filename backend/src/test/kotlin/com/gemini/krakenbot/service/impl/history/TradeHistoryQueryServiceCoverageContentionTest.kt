package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.BenchmarkMethod
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.KrakenAssetMetadata
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.RebalancerComparison
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.RebalancerComparisonCacheEntry
import com.gemini.krakenbot.repository.RebalancerComparisonCacheRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.KrakenService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

class TradeHistoryQueryServiceCoverageContentionTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val now = Instant.parse("2026-07-01T12:00:00Z")

    private fun snapshot(timestamp: Instant, btcPrice: String = "50000.00"): PortfolioSnapshot {
        val price = BigDecimal(btcPrice)
        return PortfolioSnapshot(
            timestamp = timestamp,
            totalValueUSD = price + BigDecimal("50000.00"),
            assets = mapOf(
                Asset.BTC to TestFixtures.assetSnapshot(
                    symbol = Asset.BTC,
                    balance = BigDecimal.ONE,
                    price = price,
                    valueUSD = price,
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
            balancesObservedAt = timestamp,
        )
    }

    private inner class Fixture(withDurableCache: Boolean = false) {
        val repository = mockk<TradeRepository>(relaxed = true)
        val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
        val cache = if (withDurableCache) mockk<RebalancerComparisonCacheRepository>(relaxed = true) else null
        val snapshots = mutableListOf(snapshot(now), snapshot(now.plusSeconds(3600)))
        val digestQueryHorizons = mutableListOf<Instant>()
        val tradeMetadata = mutableMapOf(
            SyncMetadataKeys.TRADE_COVERAGE_VERSION to TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION,
            SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC to "0",
            SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC to now.plusSeconds(7200).epochSecond.toString(),
            SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST to "test-scope",
            SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST to "test-scope",
            SyncMetadataKeys.COMPARISON_EVIDENCE_REVISION to "1",
        )
        val ledgerMetadata = mutableMapOf(
            SyncMetadataKeys.LEDGER_COVERAGE_VERSION to LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
            SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC to "0",
            SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC to now.plusSeconds(5400).epochSecond.toString(),
            SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST to "test-scope",
        )
        var computations = 0
        var service: TradeHistoryQueryService? = null
        var cachedEntry: RebalancerComparisonCacheEntry? = null

        init {
            coEvery { repository.getSyncMetadata(any()) } answers { tradeMetadata[firstArg()] }
            coEvery { ledgerRepository.getSyncMetadata(any()) } answers { ledgerMetadata[firstArg()] }
            coEvery { repository.getAllSnapshotsInRange(any(), any()) } coAnswers {
                snapshots.filter { !it.timestamp.isBefore(firstArg()) && !it.timestamp.isAfter(secondArg()) }
            }
            coEvery { repository.getSnapshotsInRange(any(), any()) } coAnswers {
                snapshots.filter { !it.timestamp.isBefore(firstArg()) && !it.timestamp.isAfter(secondArg()) }
            }
            coEvery { repository.getSnapshotBefore(any()) } returns null
            coEvery { repository.getTradesInRange(any(), any()) } coAnswers {
                if (firstArg<Instant>() == Instant.EPOCH) digestQueryHorizons += secondArg<Instant>()
                emptyList()
            }
            coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
            cache?.let { coEvery { it.load(any(), any()) } answers { cachedEntry } }
            cache?.let {
                coEvery { it.save(any(), any(), any(), any(), any(), any()) } answers {
                    cachedEntry = RebalancerComparisonCacheEntry(
                        inputFingerprint = arg(2),
                        comparison = arg(3),
                    )
                }
            }
        }

        fun advanceCoverage() {
            tradeMetadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] =
                (tradeMetadata.getValue(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC).toLong() + 60).toString()
            ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                (ledgerMetadata.getValue(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC).toLong() + 60).toString()
        }

        suspend fun compare(dispatcher: CoroutineDispatcher, beforeCompute: (Int) -> Unit): RebalancerComparison {
            val kraken = mockk<KrakenService>(relaxed = true)
            coEvery { kraken.getAssetMetadata() } returns listOf(
                KrakenAssetMetadata(assetId = "BTC", assetClass = "currency"),
                KrakenAssetMetadata(assetId = "USD", assetClass = "currency"),
            )
            val mutatingDispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    beforeCompute(++computations)
                    dispatcher.dispatch(context, block)
                }
            }
            service = TradeHistoryQueryService(
                repository = repository,
                portfolioStatsRepository = mockk<PortfolioStatsRepository>(relaxed = true),
                ledgerRepository = ledgerRepository,
                krakenService = kraken,
                comparisonCacheRepository = cache,
                historyEvidenceCoordinator = HistoryEvidenceCoordinator(),
                computationDispatcher = mutatingDispatcher,
                nowProvider = { now.plusSeconds(10_800) },
            )
            return request()
        }

        /** Issues another request against the already-built service, as a follow-up poll would. */
        suspend fun request(): RebalancerComparison = checkNotNull(service).getRebalancerComparison(
            now,
            now.plusSeconds(3600),
            BenchmarkMethod.FIXED_INCEPTION_HOLD,
        )
    }

    init {
        "forward coverage growth publishes unchanged evidence once with a pinned event horizon" {
            runTest {
                for (withDurableCache in listOf(false, true)) {
                    val fixture = Fixture(withDurableCache)
                    val result = fixture.compare(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher) {
                        fixture.advanceCoverage()
                        // This append is certified by the new horizon but lies beyond the captured window.
                        fixture.snapshots += snapshot(now.plusSeconds(5430))
                    }

                    result.availability shouldBe ComparisonAvailability.AVAILABLE
                    result.unavailableReason shouldBe null
                    result.points.map { it.timestamp } shouldBe listOf(now, now.plusSeconds(3600))
                    fixture.computations shouldBe 1
                    fixture.digestQueryHorizons.last() shouldBe now.plusSeconds(5400).plusMillis(999)
                    fixture.cache?.let { coVerify(exactly = 1) { it.save(any(), any(), any(), any(), any(), any()) } }
                }
            }
        }

        "unchanged watermarks do not conceal a consumed-row correction without a revision bump" {
            runTest {
                for (withCache in listOf(false, true)) {
                    val fixture = Fixture(withCache)
                    val result = fixture.compare(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher) {
                        if (it == 1) fixture.snapshots[1] = snapshot(now.plusSeconds(3600), btcPrice = "51000.00")
                    }

                    result.availability shouldBe ComparisonAvailability.AVAILABLE
                    fixture.computations shouldBe 2
                    result.points.last().rebalancerValueUSD.shouldBeEqualComparingTo(BigDecimal("101000.00"))
                }
            }
        }

        "cache lookup cannot combine an old snapshot identity with newly captured economics" {
            runTest {
                val fixture = Fixture(withDurableCache = true)
                var corrected = false
                coEvery { fixture.cache!!.load(any(), any()) } coAnswers {
                    if (!corrected) {
                        fixture.snapshots[1] = snapshot(now.plusSeconds(3600), btcPrice = "51000.00")
                        corrected = true
                    }
                    null
                }

                val result = fixture.compare(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher) {}

                result.availability shouldBe ComparisonAvailability.AVAILABLE
                fixture.computations shouldBe 2
                result.points.last().rebalancerValueUSD.shouldBeEqualComparingTo(BigDecimal("101000.00"))
                coVerify(exactly = 1) { fixture.cache!!.save(any(), any(), any(), any(), any(), any()) }
            }
        }

        "a matching cache hit is rejected when evidence changes during the unlocked lookup" {
            runTest {
                val dispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
                val fixture = Fixture(withDurableCache = true)
                // First request populates the durable entry, so the second request sees a Hit.
                fixture.compare(dispatcher) {}
                fixture.cachedEntry shouldNotBe null
                val warmedComputations = fixture.computations
                var mutated = false
                coEvery { fixture.cache!!.load(any(), any()) } coAnswers {
                    if (!mutated) {
                        fixture.snapshots[1] = snapshot(now.plusSeconds(3600), btcPrice = "51000.00")
                        mutated = true
                    }
                    fixture.cachedEntry
                }

                val result = fixture.request()

                // The stale cached economics must never be served; the corrected row must be replayed.
                result.availability shouldBe ComparisonAvailability.AVAILABLE
                result.points.last().rebalancerValueUSD.shouldBeEqualComparingTo(BigDecimal("101000.00"))
                // One rejected-hit attempt plus one replay after recapture.
                fixture.computations shouldBe warmedComputations + 2
                fixture.cachedEntry!!.comparison.points.last().rebalancerValueUSD
                    .shouldBeEqualComparingTo(BigDecimal("101000.00"))
            }
        }

        "a matching cache hit is still served when evidence is unchanged through the lookup" {
            runTest {
                val dispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
                val fixture = Fixture(withDurableCache = true)
                fixture.compare(dispatcher) {}
                fixture.cachedEntry shouldNotBe null

                val result = fixture.request()

                result.availability shouldBe ComparisonAvailability.AVAILABLE
                // Served from the durable entry: the second request must not recompute.
                fixture.computations shouldBe 1
            }
        }

        "forward coverage growth rehashes changed consumed rows even without a revision bump" {
            runTest {
                for (withDurableCache in listOf(false, true)) {
                    val fixture = Fixture(withDurableCache)
                    val result = fixture.compare(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher) {
                        fixture.advanceCoverage()
                        if (it == 1) fixture.snapshots[1] = snapshot(now.plusSeconds(3600), btcPrice = "51000.00")
                    }

                    result.availability shouldBe ComparisonAvailability.AVAILABLE
                    fixture.computations shouldBe 2
                    result.points.last().rebalancerValueUSD.shouldBeEqualComparingTo(BigDecimal("101000.00"))
                    fixture.cache?.let { coVerify(exactly = 1) { it.save(any(), any(), any(), any(), any(), any()) } }
                }
            }
        }

        "an individual coverage regression invalidates even when combined coverage is unchanged" {
            runTest {
                for (regressLedger in listOf(false, true)) {
                    val fixture = Fixture()
                    val regressing = if (regressLedger) fixture.ledgerMetadata else fixture.tradeMetadata
                    val other = if (regressLedger) fixture.tradeMetadata else fixture.ledgerMetadata
                    val regressingKey = if (regressLedger) {
                        SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC
                    } else {
                        SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC
                    }
                    val otherKey = if (regressLedger) {
                        SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC
                    } else {
                        SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC
                    }
                    regressing[regressingKey] = now.plusSeconds(7200).epochSecond.toString()
                    other[otherKey] = now.plusSeconds(5400).epochSecond.toString()
                    val result = fixture.compare(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher) {
                        if (it == 1) regressing[regressingKey] = now.plusSeconds(7140).epochSecond.toString()
                    }

                    result.availability shouldBe ComparisonAvailability.AVAILABLE
                    fixture.computations shouldBe 2
                }
            }
        }

        "publication rejects removed or malformed coverage horizons during calculation" {
            runTest {
                for (ledger in listOf(false, true)) {
                    for (replacement in listOf(null, "not-a-timestamp")) {
                        val fixture = Fixture(withDurableCache = true)
                        val metadata = if (ledger) fixture.ledgerMetadata else fixture.tradeMetadata
                        val key = if (ledger) {
                            SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC
                        } else {
                            SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC
                        }
                        val result = fixture.compare(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher) {
                            if (it == 1) {
                                if (replacement == null) metadata.remove(key) else metadata[key] = replacement
                            }
                        }

                        result.availability shouldBe ComparisonAvailability.UNAVAILABLE
                        result.points shouldBe emptyList()
                        fixture.computations shouldBe 1
                        fixture.cache?.let {
                            coVerify(exactly = 0) { it.save(any(), any(), any(), any(), any(), any()) }
                        }
                    }
                }
            }
        }

        "forward coverage does not excuse a changed reconstruction revision" {
            runTest {
                val fixture = Fixture()
                fixture.tradeMetadata[SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION] =
                    TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION
                fixture.tradeMetadata[SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_LEDGER_COVERAGE_VERSION] =
                    LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
                fixture.tradeMetadata[SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_TRADE_COVERAGE_VERSION] =
                    TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
                fixture.tradeMetadata[SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_START_EPOCH_SEC] =
                    now.minusSeconds(120).epochSecond.toString()
                fixture.tradeMetadata[SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC] =
                    now.minusSeconds(60).epochSecond.toString()
                val result = fixture.compare(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher) {
                    fixture.advanceCoverage()
                    if (it == 1) {
                        fixture.tradeMetadata[SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC] =
                            now.minusSeconds(30).epochSecond.toString()
                    }
                }

                result.availability shouldBe ComparisonAvailability.AVAILABLE
                fixture.computations shouldBe 2
            }
        }
    }
}
