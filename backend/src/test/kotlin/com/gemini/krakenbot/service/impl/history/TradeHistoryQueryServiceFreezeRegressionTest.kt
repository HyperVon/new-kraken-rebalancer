@file:OptIn(ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.BenchmarkMethod
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.KrakenAssetMetadata
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.RebalancerComparisonCacheRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigDecimal
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * Regression cover for the History / B&H navigation freeze reported against production:
 *
 *  1. Settings-save resolving an accepted comparison start runs inside
 *     `HistoryEvidenceCoordinator.tryWithLock`. The evaluation must not re-acquire that same
 *     non-reentrant mutex, or the coroutine deadlocks against itself and the lock is never
 *     released — wedging the rebalance loop and every history consumer (the observed freeze).
 *  2. History polls the comparison endpoint every few seconds with `to = Instant.now()`. Each
 *     new `to` used to carry a brand-new cache key, so every poll re-ran the full
 *     reconciliation over the retained series; polls within one flight bucket must share the
 *     completed result instead.
 */
class TradeHistoryQueryServiceFreezeRegressionTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val now = Instant.parse("2026-07-01T12:00:00Z")

    private val testAssetMetadata = listOf(
        KrakenAssetMetadata(assetId = "BTC", assetClass = "currency"),
        KrakenAssetMetadata(assetId = "USD", assetClass = "currency"),
    )

    private fun snapshot(
        timestamp: Instant,
        totalValueUSD: String = "100000.00",
        btcBalance: String = "1.0",
        btcPrice: String = "50000.00",
        usdBalance: String = "50000.00",
    ): PortfolioSnapshot {
        val btcVal = BigDecimal(btcBalance).multiply(BigDecimal(btcPrice))
        val usdVal = BigDecimal(usdBalance)
        return PortfolioSnapshot(
            timestamp = timestamp,
            totalValueUSD = BigDecimal(totalValueUSD),
            assets = mapOf(
                Asset.BTC to TestFixtures.assetSnapshot(
                    symbol = Asset.BTC,
                    balance = BigDecimal(btcBalance),
                    price = BigDecimal(btcPrice),
                    valueUSD = btcVal,
                    targetPercent = BigDecimal.ZERO,
                ),
                TestFixtures.USD to TestFixtures.assetSnapshot(
                    symbol = TestFixtures.USD,
                    balance = usdVal,
                    price = BigDecimal.ONE,
                    valueUSD = usdVal,
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

    private fun stubCoverage(repository: TradeRepository, ledgerRepository: LedgerRepository) {
        coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) } returns
            TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
        coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns "0"
        coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns "4102444800"
        coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
            "test-scope"
        coEvery { repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) } returns "test-scope"
        coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) } returns
            LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
        coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns "0"
        coEvery {
            ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC)
        } returns "4102444800"
        coEvery {
            ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
        } returns "test-scope"
    }

    private fun testConfig() = TestFixtures.config(
        allocations = listOf(
            Allocation(Asset.BTC, 50.0),
            Allocation(TestFixtures.USD, 50.0),
        ),
    )

    init {
        "settings-save comparison-start resolution must not re-enter the evidence lock" {
            val coordinator = HistoryEvidenceCoordinator()
            val repository = mockk<TradeRepository>(relaxed = true)
            val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
            val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
            val krakenService = mockk<KrakenService>(relaxed = true)
            val configService = mockk<ConfigService>(relaxed = true)
            val inceptionService = mockk<InceptionDiscoveryService>(relaxed = true)

            val snap1 = snapshot(now)
            val snap2 = snapshot(now.plusSeconds(3600))
            coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
            coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
            coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
            coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
            stubCoverage(repository, ledgerRepository)

            every { configService.getConfig() } returns testConfig()
            coEvery { krakenService.getAssetMetadata() } returns testAssetMetadata
            coEvery { inceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                inceptionTime = snap1.timestamp,
                inceptionSnapshot = snap1,
                isAutoDetected = false,
                confidence = InceptionConfidence.CONFIDENT,
            )

            val service = TradeHistoryQueryService(
                repository = repository,
                portfolioStatsRepository = statsRepository,
                ledgerRepository = ledgerRepository,
                inceptionDiscoveryService = inceptionService,
                krakenService = krakenService,
                configService = configService,
                historyEvidenceCoordinator = coordinator,
                nowProvider = { now.plusSeconds(7200) },
            )

            // Mirrors DashboardController.handlePostSettings: the settings-save block owns the
            // coordinator, then resolves an accepted comparison start through the same lock.
            val completed = runBlocking {
                withTimeoutOrNull(30.seconds) {
                    coordinator.tryWithLock {
                        service.getComparisonStartProposalUnderEvidenceLock(snap1.timestamp)
                    }
                }
            }

            completed shouldNotBe null

            // Prove the inline-persist branch actually ran. Without this the test could pass
            // while the evaluation short-circuits before persistAutomaticBaselineVerification,
            // leaving the deadlock path silently unexercised.
            coVerify(atLeast = 1) {
                repository.setSyncMetadataAtomically(
                    match { metadata ->
                        metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] == "VERIFIED"
                    },
                )
            }
        }

        "comparison polls within one flight bucket are served without re-capturing evidence" {
            val coordinator = HistoryEvidenceCoordinator()
            val repository = mockk<TradeRepository>(relaxed = true)
            val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
            val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
            val krakenService = mockk<KrakenService>(relaxed = true)
            val configService = mockk<ConfigService>(relaxed = true)
            val comparisonCache = mockk<RebalancerComparisonCacheRepository>(relaxed = true)

            val snap1 = snapshot(now)
            val snap2 = snapshot(now.plusSeconds(3600))
            coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
            coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)

            // getTradesInRange runs more than once per evaluation, so it must only be counted,
            // never used as the completion signal. persistCachedComparison runs last, once the
            // evaluation is finished, and gives a deterministic point to sample the counter.
            val firstEvaluationDone = CompletableDeferred<Unit>()
            var captureRuns = 0
            coEvery { repository.getTradesInRange(any(), any()) } coAnswers {
                captureRuns++
                emptyList()
            }
            coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
            coEvery { comparisonCache.load(any(), any()) } returns null
            coEvery { comparisonCache.save(any(), any(), any(), any(), any(), any()) } coAnswers {
                firstEvaluationDone.complete(Unit)
            }
            stubCoverage(repository, ledgerRepository)

            every { configService.getConfig() } returns testConfig()
            coEvery { krakenService.getAssetMetadata() } returns testAssetMetadata

            val service = TradeHistoryQueryService(
                repository = repository,
                portfolioStatsRepository = statsRepository,
                ledgerRepository = ledgerRepository,
                krakenService = krakenService,
                configService = configService,
                comparisonCacheRepository = comparisonCache,
                historyEvidenceCoordinator = coordinator,
                applicationScope = CoroutineScope(Dispatchers.Default),
                computationDispatcher = Dispatchers.Default,
                nowProvider = { now.plusSeconds(7200) },
            )

            // History polls with `to = Instant.now()`, so every poll carries a new wall clock.
            // The first poll starts the evaluation; wait for it to finish before sampling.
            service.requestRebalancerComparison(
                now,
                now.plusSeconds(3600),
                BenchmarkMethod.FIXED_INCEPTION_HOLD,
            )
            withTimeoutOrNull(30.seconds) { firstEvaluationDone.await() } shouldNotBe null
            captureRuns = 0

            // A later poll inside the same flight bucket must be answered from the completed
            // cache. Before the fix the wall-clock key missed on every poll and each one
            // re-ran the full reconciliation over the retained series, which froze the UI.
            //
            // The evaluation persists its cache row just before the flight is completed, so a
            // poll landing in that tail joins the live flight and gets the transient until it
            // resolves. Retry within a bound rather than racing that tail.
            var second = service.requestRebalancerComparison(
                now,
                now.plusSeconds(3615),
                BenchmarkMethod.FIXED_INCEPTION_HOLD,
            )
            var attempts = 0
            while (
                second.unavailableReason == ComparisonUnavailableReason.COMPARISON_EVALUATING &&
                attempts < 40
            ) {
                delay(50)
                second = service.requestRebalancerComparison(
                    now,
                    now.plusSeconds(3615),
                    BenchmarkMethod.FIXED_INCEPTION_HOLD,
                )
                attempts++
            }

            // unavailableReason is the domain enum, not the wire string: compare like for like
            // so this assertion can actually fail when the transient is served.
            second.unavailableReason shouldNotBe ComparisonUnavailableReason.COMPARISON_EVALUATING
            captureRuns shouldBe 0
        }

        "a cached comparison is presented for the caller window, not the window it was cached under" {
            val coordinator = HistoryEvidenceCoordinator()
            val repository = mockk<TradeRepository>(relaxed = true)
            val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
            val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
            val krakenService = mockk<KrakenService>(relaxed = true)
            val configService = mockk<ConfigService>(relaxed = true)
            val comparisonCache = mockk<RebalancerComparisonCacheRepository>(relaxed = true)

            // Two snapshots 10s apart, so a display window starting between them must drop the
            // first point while a window starting at the first keeps both.
            val snap1 = snapshot(now)
            val snap2 = snapshot(now.plusSeconds(10), totalValueUSD = "110000.00")
            coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
            coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
            coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
            coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
            coEvery { comparisonCache.load(any(), any()) } returns null
            stubCoverage(repository, ledgerRepository)

            every { configService.getConfig() } returns testConfig()
            coEvery { krakenService.getAssetMetadata() } returns testAssetMetadata

            val service = TradeHistoryQueryService(
                repository = repository,
                portfolioStatsRepository = statsRepository,
                ledgerRepository = ledgerRepository,
                krakenService = krakenService,
                configService = configService,
                comparisonCacheRepository = comparisonCache,
                historyEvidenceCoordinator = coordinator,
                applicationScope = CoroutineScope(Dispatchers.Default),
                computationDispatcher = Dispatchers.Default,
                nowProvider = { now.plusSeconds(7200) },
            )

            val firstEvaluationDone = CompletableDeferred<Unit>()
            coEvery { comparisonCache.save(any(), any(), any(), any(), any(), any()) } coAnswers {
                firstEvaluationDone.complete(Unit)
            }

            // Window covers both snapshots. `from = now` and `from = now + 5` fall in the same
            // 30s flight bucket, so the second request must be served from the cache.
            val wide = service.requestRebalancerComparison(
                now,
                now.plusSeconds(600),
                BenchmarkMethod.FIXED_INCEPTION_HOLD,
            )
            withTimeoutOrNull(30.seconds) { firstEvaluationDone.await() } shouldNotBe null
            wide.availability shouldBe ComparisonAvailability.AVAILABLE

            // Window starts after the first snapshot: only one point remains, which is not a
            // valid comparison. The cached value must be trimmed to THIS window rather than
            // served with the earlier window's points.
            val narrow = service.requestRebalancerComparison(
                now.plusSeconds(5),
                now.plusSeconds(600),
                BenchmarkMethod.FIXED_INCEPTION_HOLD,
            )

            narrow.availability shouldBe ComparisonAvailability.UNAVAILABLE
            narrow.unavailableReason shouldBe ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS
        }

        "concurrent polls share one evaluation and the identity varies by accounting floor" {
            val coordinator = HistoryEvidenceCoordinator()
            val repository = mockk<TradeRepository>(relaxed = true)
            val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
            val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
            val krakenService = mockk<KrakenService>(relaxed = true)
            val comparisonCache = mockk<RebalancerComparisonCacheRepository>(relaxed = true)
            val inceptionService = mockk<InceptionDiscoveryService>(relaxed = true)

            val snap1 = snapshot(now)
            val snap2 = snapshot(now.plusSeconds(3600))
            coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
            coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
            coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
            coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
            coEvery { comparisonCache.load(any(), any()) } returns null
            stubCoverage(repository, ledgerRepository)
            coEvery { krakenService.getAssetMetadata() } returns testAssetMetadata
            coEvery { inceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                inceptionTime = snap1.timestamp,
                inceptionSnapshot = snap1,
                isAutoDetected = false,
                confidence = InceptionConfidence.CONFIDENT,
            )

            // One persist per completed evaluation: the assertion below counts evaluations,
            // so concurrent polls must not multiply it.
            val evaluations = CompletableDeferred<Unit>()
            var persists = 0
            coEvery { comparisonCache.save(any(), any(), any(), any(), any(), any()) } coAnswers {
                persists++
                evaluations.complete(Unit)
            }

            val service = TradeHistoryQueryService(
                repository = repository,
                portfolioStatsRepository = statsRepository,
                ledgerRepository = ledgerRepository,
                inceptionDiscoveryService = inceptionService,
                krakenService = krakenService,
                comparisonCacheRepository = comparisonCache,
                historyEvidenceCoordinator = coordinator,
                applicationScope = CoroutineScope(Dispatchers.Default),
                computationDispatcher = Dispatchers.Default,
                nowProvider = { now.plusSeconds(7200) },
            )

            // Concurrent polls with drifting `to` must collapse onto ONE evaluation. This is
            // the herding defect: with a time-bucketed flight key each poll started its own
            // reconciliation and none of them completed.
            coroutineScope {
                repeat(6) { i ->
                    launch {
                        service.requestRebalancerComparison(
                            now,
                            now.plusSeconds(3600L + i),
                            BenchmarkMethod.FIXED_INCEPTION_HOLD,
                        )
                    }
                }
            }
            withTimeoutOrNull(30.seconds) { evaluations.await() } shouldNotBe null
            persists shouldBe 1

            // `range=all` resolves a different accounting floor (`Instant.EPOCH`) than a
            // preset range (the inception time), so it must NOT share that evaluation.
            val all = service.requestRebalancerComparison(
                Instant.EPOCH,
                now.plusSeconds(3600),
                BenchmarkMethod.FIXED_INCEPTION_HOLD,
            )
            all.availability shouldBe ComparisonAvailability.AVAILABLE
        }
    }
}
