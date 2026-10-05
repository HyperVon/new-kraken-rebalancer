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
import com.gemini.krakenbot.model.RebalancerComparison
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.ConsumedOhlcDependency
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.OhlcReachabilityDependency
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.RebalancerComparisonCacheEntry
import com.gemini.krakenbot.repository.RebalancerComparisonCacheRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

class TradeHistoryQueryServiceContentionTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val now = Instant.parse("2026-07-01T12:00:00Z")

    private fun tradeRepository(): TradeRepository = mockk<TradeRepository>(relaxed = true).also { repository ->
        // A relaxed mock invents a predecessor whose contents vary between digest reads.
        // These fixtures have no predecessor unless the individual test supplies one.
        coEvery { repository.getSnapshotBefore(any()) } returns null
    }

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

    init {
        "comparison calculation does not hold coordinator lock and allows concurrent writers" {
            runTest {
                val coordinator = HistoryEvidenceCoordinator()
                val repository = tradeRepository()
                val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
                val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
                val krakenService = mockk<KrakenService>(relaxed = true)

                coEvery { repository.getAllSnapshotsInRange(any(), any()) } coAnswers {
                    repository.getSnapshotsInRange(firstArg(), secondArg())
                }
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) } returns
                    TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns "0"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) } returns
                    LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    "0"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                } returns "test-scope"

                val snap1 = snapshot(now)
                val snap2 = snapshot(now.plusSeconds(3600))
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val pauseGate = CompletableDeferred<Unit>()
                val calculationPaused = CompletableDeferred<Unit>()

                coEvery { krakenService.getAssetMetadata() } coAnswers {
                    calculationPaused.complete(Unit)
                    pauseGate.await()
                    testAssetMetadata
                }

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    krakenService = krakenService,
                    historyEvidenceCoordinator = coordinator,
                    computationDispatcher =
                    (coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher) ?: Dispatchers.Default,
                    nowProvider = { now.plusSeconds(7200) },
                )

                val comparisonJob = launch {
                    service.getRebalancerComparison(now, now.plusSeconds(3600), BenchmarkMethod.FIXED_INCEPTION_HOLD)
                }

                calculationPaused.await()

                // Verification: lock is NOT held during the paused calculation
                coordinator.tryWithLock { } shouldBe true

                val writerFinished = CompletableDeferred<Unit>()
                val writerJob = launch {
                    coordinator.withLock("account-scope-validation") {
                        writerFinished.complete(Unit)
                    }
                }

                runCurrent()
                writerJob.isCompleted shouldBe true
                writerFinished.isCompleted shouldBe true

                // Unblock calculation
                pauseGate.complete(Unit)

                comparisonJob.join()
                writerJob.join()
                writerFinished.isCompleted shouldBe true
            }
        }

        "simultaneous equivalent comparison requests coalesce into a single execution" {
            runTest {
                val coordinator = HistoryEvidenceCoordinator()
                val repository = tradeRepository()
                val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
                val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
                val krakenService = mockk<KrakenService>(relaxed = true)

                coEvery { repository.getAllSnapshotsInRange(any(), any()) } coAnswers {
                    repository.getSnapshotsInRange(firstArg(), secondArg())
                }
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) } returns
                    TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns "0"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) } returns
                    LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    "0"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                } returns "test-scope"

                val snap1 = snapshot(now)
                val snap2 = snapshot(now.plusSeconds(3600))
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                var assetMetadataCalls = 0
                coEvery { krakenService.getAssetMetadata() } coAnswers {
                    assetMetadataCalls++
                    testAssetMetadata
                }

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    krakenService = krakenService,
                    historyEvidenceCoordinator = coordinator,
                    computationDispatcher =
                    (coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher) ?: Dispatchers.Default,
                    nowProvider = { now.plusSeconds(7200) },
                )

                val call1 = launch {
                    service.getRebalancerComparison(now, now.plusSeconds(3600), BenchmarkMethod.FIXED_INCEPTION_HOLD)
                }
                val call2 = launch {
                    service.getRebalancerComparison(now, now.plusSeconds(3600), BenchmarkMethod.FIXED_INCEPTION_HOLD)
                }

                call1.join()
                call2.join()

                assetMetadataCalls shouldBe 1
            }
        }

        "cancelling one caller leaves the shared flight active for other callers" {
            runTest {
                val coordinator = HistoryEvidenceCoordinator()
                val repository = tradeRepository()
                val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
                val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
                val krakenService = mockk<KrakenService>(relaxed = true)

                coEvery { repository.getAllSnapshotsInRange(any(), any()) } coAnswers {
                    repository.getSnapshotsInRange(firstArg(), secondArg())
                }
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) } returns
                    TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns "0"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) } returns
                    LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    "0"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                } returns "test-scope"

                val snap1 = snapshot(now)
                val snap2 = snapshot(now.plusSeconds(3600))
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val pauseGate = CompletableDeferred<Unit>()
                val firstStarted = CompletableDeferred<Unit>()
                coEvery { krakenService.getAssetMetadata() } coAnswers {
                    firstStarted.complete(Unit)
                    pauseGate.await()
                    testAssetMetadata
                }

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    krakenService = krakenService,
                    historyEvidenceCoordinator = coordinator,
                    computationDispatcher =
                    (coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher) ?: Dispatchers.Default,
                    nowProvider = { now.plusSeconds(7200) },
                )

                val caller1 = launch {
                    service.getRebalancerComparison(now, now.plusSeconds(3600), BenchmarkMethod.FIXED_INCEPTION_HOLD)
                }
                firstStarted.await()

                var caller2Result: RebalancerComparison? = null
                val caller2 = launch {
                    caller2Result = service.getRebalancerComparison(
                        now,
                        now.plusSeconds(3600),
                        BenchmarkMethod.FIXED_INCEPTION_HOLD,
                    )
                }

                runCurrent()
                caller1.cancel()

                pauseGate.complete(Unit)
                caller2.join()

                caller2Result shouldNotBe null
            }
        }

        "requestRebalancerComparison returns COMPARISON_EVALUATING when cold and completes via background flight" {
            runTest {
                val coordinator = HistoryEvidenceCoordinator()
                val repository = tradeRepository()
                val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
                val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
                val krakenService = mockk<KrakenService>(relaxed = true)

                coEvery { repository.getAllSnapshotsInRange(any(), any()) } coAnswers {
                    repository.getSnapshotsInRange(firstArg(), secondArg())
                }
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) } returns
                    TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns "0"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) } returns
                    LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    "0"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                } returns "test-scope"

                val snap1 = snapshot(now)
                val snap2 = snapshot(now.plusSeconds(3600))
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val pauseGate = CompletableDeferred<Unit>()
                coEvery { krakenService.getAssetMetadata() } coAnswers {
                    pauseGate.await()
                    testAssetMetadata
                }

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    krakenService = krakenService,
                    historyEvidenceCoordinator = coordinator,
                    applicationScope = this,
                    computationDispatcher =
                    (coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher) ?: Dispatchers.Default,
                    nowProvider = { now.plusSeconds(7200) },
                )

                // Cold call should return COMPARISON_EVALUATING immediately
                val initial = service.requestRebalancerComparison(
                    now,
                    now.plusSeconds(3600),
                    BenchmarkMethod.FIXED_INCEPTION_HOLD,
                )
                initial.availability shouldBe ComparisonAvailability.UNAVAILABLE
                initial.unavailableReason shouldBe ComparisonUnavailableReason.COMPARISON_EVALUATING

                // Unblock background evaluation
                pauseGate.complete(Unit)
                advanceUntilIdle()

                // Second poll gets the completed result
                val second = service.requestRebalancerComparison(
                    now,
                    now.plusSeconds(3600),
                    BenchmarkMethod.FIXED_INCEPTION_HOLD,
                )
                second.availability shouldBe ComparisonAvailability.AVAILABLE
                second.unavailableReason shouldBe null
            }
        }

        "requestRebalancerComparison caches completed comparisons and cleans up on expiry" {
            runTest {
                val coordinator = HistoryEvidenceCoordinator()
                val repository = tradeRepository()
                val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
                val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
                val krakenService = mockk<KrakenService>(relaxed = true)

                var calcCount = 0
                val snap1 = snapshot(now)
                val snap2 = snapshot(now.plusSeconds(3600))
                coEvery { repository.getAllSnapshotsInRange(match { it != Instant.EPOCH }, any()) } coAnswers {
                    calcCount++
                    listOf(snap1, snap2)
                }
                coEvery { repository.getAllSnapshotsInRange(Instant.EPOCH, any()) } returns emptyList()
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) } returns
                    TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns "0"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) } returns
                    LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    "0"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                } returns "test-scope"

                val pauseGate = CompletableDeferred<Unit>()
                var firstCall = true
                coEvery { krakenService.getAssetMetadata() } coAnswers {
                    if (firstCall) {
                        firstCall = false
                        pauseGate.await()
                    }
                    testAssetMetadata
                }

                var fakeNanos = 1_000_000_000L
                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    krakenService = krakenService,
                    historyEvidenceCoordinator = coordinator,
                    applicationScope = this,
                    computationDispatcher =
                    (coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher) ?: Dispatchers.Default,
                    nowProvider = { now.plusSeconds(7200) },
                    monotonicTimeProvider = { fakeNanos },
                )

                // Initial request initiates background job
                val initial = service.requestRebalancerComparison(
                    now,
                    now.plusSeconds(3600),
                    BenchmarkMethod.FIXED_INCEPTION_HOLD,
                )
                initial.unavailableReason shouldBe ComparisonUnavailableReason.COMPARISON_EVALUATING

                pauseGate.complete(Unit)
                runCurrent()
                calcCount shouldBe 1

                // Subsequent request before expiry hits in-memory cache
                val cached = service.requestRebalancerComparison(
                    now,
                    now.plusSeconds(3600),
                    BenchmarkMethod.FIXED_INCEPTION_HOLD,
                )
                cached.availability shouldBe ComparisonAvailability.AVAILABLE
                calcCount shouldBe 1

                // Advance fake monotonic time past 30s TTL
                fakeNanos += 35_000_000_000L

                // Expired request cleans up and re-computes
                service.requestRebalancerComparison(
                    now,
                    now.plusSeconds(3600),
                    BenchmarkMethod.FIXED_INCEPTION_HOLD,
                )
                runCurrent()
                calcCount shouldBe 2
            }
        }

        "requestRebalancerComparison falls back when scope is null or inactive" {
            runTest {
                val coordinator = HistoryEvidenceCoordinator()
                val repository = tradeRepository()
                val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
                val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
                val krakenService = mockk<KrakenService>(relaxed = true)

                coEvery { repository.getAllSnapshotsInRange(any(), any()) } coAnswers {
                    repository.getSnapshotsInRange(firstArg(), secondArg())
                }
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) } returns
                    TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns "0"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) } returns
                    LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    "0"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                } returns "test-scope"

                val snap1 = snapshot(now)
                val snap2 = snapshot(now.plusSeconds(3600))
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getAssetMetadata() } returns testAssetMetadata

                // Null scope falls back to synchronous getRebalancerComparison
                val serviceNullScope = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    krakenService = krakenService,
                    historyEvidenceCoordinator = coordinator,
                    applicationScope = null,
                    computationDispatcher =
                    (coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher) ?: Dispatchers.Default,
                    nowProvider = { now.plusSeconds(7200) },
                )
                val resultNull = serviceNullScope.requestRebalancerComparison(
                    now,
                    now.plusSeconds(3600),
                    BenchmarkMethod.FIXED_INCEPTION_HOLD,
                )
                resultNull.availability shouldBe ComparisonAvailability.AVAILABLE
                resultNull.unavailableReason shouldBe null

                // Inactive scope returns COMPARISON_EVALUATING immediately
                val deadScope = CoroutineScope(Job().apply { cancel() })
                val serviceDeadScope = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    krakenService = krakenService,
                    historyEvidenceCoordinator = coordinator,
                    applicationScope = deadScope,
                    computationDispatcher =
                    (coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher) ?: Dispatchers.Default,
                    nowProvider = { now.plusSeconds(7200) },
                )
                val resultDead = serviceDeadScope.requestRebalancerComparison(
                    now,
                    now.plusSeconds(3600),
                    BenchmarkMethod.FIXED_INCEPTION_HOLD,
                )
                resultDead.availability shouldBe ComparisonAvailability.UNAVAILABLE
                resultDead.unavailableReason shouldBe ComparisonUnavailableReason.COMPARISON_EVALUATING

                // Active scope returns fastResult, and subsequent call within TTL hits completed cache
                val fastScope = CoroutineScope(Dispatchers.Unconfined)
                val serviceFastScope = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    krakenService = krakenService,
                    historyEvidenceCoordinator = coordinator,
                    applicationScope = fastScope,
                    computationDispatcher = Dispatchers.Unconfined,
                    nowProvider = { now.plusSeconds(7200) },
                )
                val resultFast = serviceFastScope.requestRebalancerComparison(
                    now,
                    now.plusSeconds(3600),
                    BenchmarkMethod.FIXED_INCEPTION_HOLD,
                )
                resultFast.availability shouldBe ComparisonAvailability.AVAILABLE
                resultFast.unavailableReason shouldBe null

                val resultCached = serviceFastScope.requestRebalancerComparison(
                    now,
                    now.plusSeconds(3600),
                    BenchmarkMethod.FIXED_INCEPTION_HOLD,
                )
                resultCached.availability shouldBe ComparisonAvailability.AVAILABLE
                resultCached.unavailableReason shouldBe null
            }
        }

        "validateAndPublishComparison invalidates and retries when allocation universe changes during calculation" {
            runTest {
                val coordinator = HistoryEvidenceCoordinator()
                val repository = tradeRepository()
                val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
                val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
                val krakenService = mockk<KrakenService>(relaxed = true)
                val configService = mockk<ConfigService>(relaxed = true)

                var calcAttempts = 0
                val snap1 = snapshot(now)
                val snap2 = snapshot(now.plusSeconds(3600))
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getAllSnapshotsInRange(now, now.plusSeconds(3600)) } coAnswers {
                    calcAttempts++
                    listOf(snap1, snap2)
                }

                var configCallCount = 0
                val initialConfig = TestFixtures.config(
                    allocations = listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(TestFixtures.USD, 50.0),
                    ),
                )
                val updatedConfig = TestFixtures.config(
                    allocations = listOf(
                        Allocation(Asset.BTC, 60.0),
                        Allocation(TestFixtures.USD, 40.0),
                    ),
                )
                every { configService.getConfig() } answers {
                    configCallCount++
                    if (configCallCount <= 3) initialConfig else updatedConfig
                }

                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) } returns
                    TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns "0"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) } returns
                    LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    "0"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                } returns "test-scope"
                coEvery { krakenService.getAssetMetadata() } returns testAssetMetadata

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    krakenService = krakenService,
                    configService = configService,
                    historyEvidenceCoordinator = coordinator,
                    computationDispatcher =
                    (coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher) ?: Dispatchers.Default,
                    nowProvider = { now.plusSeconds(7200) },
                )

                val result = service.getRebalancerComparison(
                    now,
                    now.plusSeconds(3600),
                    BenchmarkMethod.FIXED_INCEPTION_HOLD,
                )
                result.availability shouldBe ComparisonAvailability.AVAILABLE
                calcAttempts shouldBe 2
            }
        }

        "validateAndPublishComparison invalidates and retries when inception changes during calculation" {
            runTest {
                val coordinator = HistoryEvidenceCoordinator()
                val repository = tradeRepository()
                val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
                val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
                val krakenService = mockk<KrakenService>(relaxed = true)
                val inceptionService = mockk<InceptionDiscoveryService>(relaxed = true)

                var calcAttempts = 0
                val snap1 = snapshot(now)
                val snap2 = snapshot(now.plusSeconds(3600))
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getAllSnapshotsInRange(now, now.plusSeconds(3600)) } coAnswers {
                    calcAttempts++
                    listOf(snap1, snap2)
                }

                var inceptionCallCount = 0
                coEvery { inceptionService.resolveInceptionUnderEvidenceLock() } answers {
                    inceptionCallCount++
                    InceptionResolution(
                        inceptionTime = snap1.timestamp,
                        inceptionSnapshot = snap1,
                        isAutoDetected = false,
                        confidence = if (inceptionCallCount == 1) {
                            InceptionConfidence.RECOVERY_INCOMPLETE
                        } else {
                            InceptionConfidence.CONFIDENT
                        },
                    )
                }

                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) } returns
                    TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns "0"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) } returns
                    LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    "0"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                } returns "test-scope"
                coEvery { krakenService.getAssetMetadata() } returns testAssetMetadata

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    krakenService = krakenService,
                    inceptionDiscoveryService = inceptionService,
                    historyEvidenceCoordinator = coordinator,
                    computationDispatcher =
                    (coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher) ?: Dispatchers.Default,
                    nowProvider = { now.plusSeconds(7200) },
                )

                val result = service.getRebalancerComparison(
                    now,
                    now.plusSeconds(3600),
                    BenchmarkMethod.FIXED_INCEPTION_HOLD,
                )
                result.availability shouldBe ComparisonAvailability.AVAILABLE
                calcAttempts shouldBe 2
            }
        }

        "validateAndPublishComparison invalidates and retries when coverage horizon regresses during calculation" {
            runTest {
                val coordinator = HistoryEvidenceCoordinator()
                val repository = tradeRepository()
                val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
                val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
                val krakenService = mockk<KrakenService>(relaxed = true)

                var calcAttempts = 0
                var tradeHorizon = "4102444800"
                val snap1 = snapshot(now)
                val snap2 = snapshot(now.plusSeconds(3600))
                coEvery { repository.getAllSnapshotsInRange(match { it != Instant.EPOCH }, any()) } coAnswers {
                    calcAttempts++
                    if (calcAttempts == 2) {
                        tradeHorizon = "4102444800"
                    }
                    listOf(snap1, snap2)
                }
                coEvery { repository.getAllSnapshotsInRange(Instant.EPOCH, any()) } returns emptyList()

                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) } returns
                    TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns "0"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } answers {
                    tradeHorizon
                }
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) } returns
                    LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    "0"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                } returns "test-scope"

                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { repository.getTradesInRange(Instant.EPOCH, any()) } coAnswers {
                    if (calcAttempts == 1) {
                        tradeHorizon = "0"
                    }
                    emptyList()
                }
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getAssetMetadata() } returns testAssetMetadata

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    krakenService = krakenService,
                    historyEvidenceCoordinator = coordinator,
                    computationDispatcher =
                    (coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher) ?: Dispatchers.Default,
                    nowProvider = { now.plusSeconds(7200) },
                )

                val result = service.getRebalancerComparison(
                    now,
                    now.plusSeconds(3600),
                    BenchmarkMethod.FIXED_INCEPTION_HOLD,
                )
                result.availability shouldBe ComparisonAvailability.AVAILABLE
                calcAttempts shouldBe 2
            }
        }

        "executeComparisonPipeline returns transient in-progress when invalidated repeatedly exceeding attempt limit" {
            runTest {
                val coordinator = HistoryEvidenceCoordinator()
                val repository = tradeRepository()
                val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
                val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
                val krakenService = mockk<KrakenService>(relaxed = true)

                var calcAttempts = 0
                val snap1 = snapshot(now)
                val snap2 = snapshot(now.plusSeconds(3600))
                coEvery { repository.getAllSnapshotsInRange(now, any()) } coAnswers {
                    calcAttempts++
                    listOf(snap1, snap2)
                }
                coEvery { repository.getAllSnapshotsInRange(match { it != now }, any()) } returns listOf(snap1, snap2)

                var revisionCounter = 0
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT)
                } answers {
                    (++revisionCounter).toString()
                }

                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) } returns
                    TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns "0"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) } returns
                    LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    "0"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                } returns "test-scope"

                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getAssetMetadata() } returns testAssetMetadata

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    krakenService = krakenService,
                    historyEvidenceCoordinator = coordinator,
                    computationDispatcher =
                    (coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher) ?: Dispatchers.Default,
                    nowProvider = { now.plusSeconds(7200) },
                )

                val result = service.getRebalancerComparison(
                    now,
                    now.plusSeconds(3600),
                    BenchmarkMethod.FIXED_INCEPTION_HOLD,
                )
                result.availability shouldBe ComparisonAvailability.UNAVAILABLE
                result.unavailableReason shouldBe ComparisonUnavailableReason.COMPARISON_EVALUATING
                calcAttempts shouldBe 3
            }
        }

        "validateAndPublishComparison invalidates and retries when comparison evidence revision changes" {
            runTest {
                val coordinator = HistoryEvidenceCoordinator()
                val repository = tradeRepository()
                val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
                val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
                val krakenService = mockk<KrakenService>(relaxed = true)
                val comparisonCacheRepository = mockk<RebalancerComparisonCacheRepository>(relaxed = true)

                var calcAttempts = 0
                val snap1 = snapshot(now)
                val snap2 = snapshot(now.plusSeconds(3600))
                coEvery { repository.getAllSnapshotsInRange(now, any()) } coAnswers {
                    calcAttempts++
                    listOf(snap1, snap2)
                }
                coEvery { repository.getAllSnapshotsInRange(match { it != now }, any()) } returns listOf(snap1, snap2)

                var revision = "rev-initial"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.COMPARISON_EVIDENCE_REVISION) } answers {
                    revision
                }

                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) } returns
                    TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns "0"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) } returns
                    LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    "0"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                } returns "test-scope"

                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)

                val predSnap = snapshot(now.minusSeconds(3600))
                var currentPredecessor: PortfolioSnapshot? = null
                coEvery { repository.getSnapshotBefore(any()) } answers { currentPredecessor }
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getAssetMetadata() } returns testAssetMetadata

                val testDispatcher =
                    (coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher) ?: Dispatchers.Default
                val notifyingDispatcher = object : CoroutineDispatcher() {
                    override fun dispatch(context: CoroutineContext, block: Runnable) {
                        if (calcAttempts == 1) {
                            revision = "rev-updated"
                            currentPredecessor = predSnap
                        }
                        testDispatcher.dispatch(context, block)
                    }
                }

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    krakenService = krakenService,
                    comparisonCacheRepository = comparisonCacheRepository,
                    historyEvidenceCoordinator = coordinator,
                    computationDispatcher = notifyingDispatcher,
                    nowProvider = { now.plusSeconds(7200) },
                )

                val result = service.getRebalancerComparison(
                    now,
                    now.plusSeconds(3600),
                    BenchmarkMethod.FIXED_INCEPTION_HOLD,
                )
                result.unavailableReason shouldBe null
                result.availability shouldBe ComparisonAvailability.AVAILABLE
                calcAttempts shouldBe 2
            }
        }

        "requestRebalancerComparison uses configService to incorporate configuration into request key" {
            runTest {
                val coordinator = HistoryEvidenceCoordinator()
                val repository = tradeRepository()
                val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
                val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
                val krakenService = mockk<KrakenService>(relaxed = true)
                val configService = mockk<ConfigService>(relaxed = true)

                val snap1 = snapshot(now)
                val snap2 = snapshot(now.plusSeconds(3600))
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getAssetMetadata() } returns testAssetMetadata

                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) } returns
                    TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns "0"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) } returns
                    LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    "0"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                } returns "test-scope"

                val testConfig = TestFixtures.config(
                    allocations = listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(TestFixtures.USD, 50.0),
                    ),
                )
                every { configService.getConfig() } returns testConfig

                var fakeTimeNanos = 10_000_000_000L
                val activeScope = CoroutineScope(Dispatchers.Unconfined)
                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    krakenService = krakenService,
                    configService = configService,
                    historyEvidenceCoordinator = coordinator,
                    applicationScope = activeScope,
                    computationDispatcher = Dispatchers.Unconfined,
                    nowProvider = { now.plusSeconds(7200) },
                    monotonicTimeProvider = { fakeTimeNanos },
                )

                val result1 = service.requestRebalancerComparison(
                    now,
                    now.plusSeconds(3600),
                    BenchmarkMethod.FIXED_INCEPTION_HOLD,
                )
                result1.availability shouldBe ComparisonAvailability.AVAILABLE

                // Second call before expiry hits completed cache
                val resultCached = service.requestRebalancerComparison(
                    now,
                    now.plusSeconds(3600),
                    BenchmarkMethod.FIXED_INCEPTION_HOLD,
                )
                resultCached.availability shouldBe ComparisonAvailability.AVAILABLE

                // Advance time past TTL so completed entry is evicted and recomputed
                fakeTimeNanos += 100_000_000_000L
                val resultAfterExpiry = service.requestRebalancerComparison(
                    now,
                    now.plusSeconds(3600),
                    BenchmarkMethod.FIXED_INCEPTION_HOLD,
                )
                resultAfterExpiry.availability shouldBe ComparisonAvailability.AVAILABLE
            }
        }

        "cached comparison with expired OHLC dependencies revalidates outside coordinator lock" {
            runTest {
                val coordinator = HistoryEvidenceCoordinator()
                val repository = tradeRepository()
                val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
                val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
                val krakenService = mockk<KrakenService>(relaxed = true)
                val comparisonCache = mockk<RebalancerComparisonCacheRepository>(relaxed = true)
                val ohlcCache = mockk<HistoricalOhlcCache>(relaxed = true)

                coEvery { repository.getAllSnapshotsInRange(any(), any()) } coAnswers {
                    repository.getSnapshotsInRange(firstArg(), secondArg())
                }
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) } returns
                    TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns "0"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) } returns
                    LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    "0"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                } returns "test-scope"

                val snap1 = snapshot(now)
                val snap2 = snapshot(now.plusSeconds(3600))
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getAssetMetadata() } returns testAssetMetadata

                var storedEntry: RebalancerComparisonCacheEntry? = null
                coEvery { comparisonCache.load(any(), any()) } coAnswers { storedEntry }
                coEvery {
                    comparisonCache.save(any(), any(), any(), any(), any(), any())
                } coAnswers {
                    val fp = thirdArg<String>()
                    val comp = arg<RebalancerComparison>(3)
                    val deps = arg<List<ConsumedOhlcDependency>>(4)
                    val reach = arg<List<OhlcReachabilityDependency>>(5)
                    storedEntry = RebalancerComparisonCacheEntry(fp, comp, deps, reach)
                }

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    krakenService = krakenService,
                    comparisonCacheRepository = comparisonCache,
                    historicalOhlcCache = ohlcCache,
                    historyEvidenceCoordinator = coordinator,
                    computationDispatcher = Dispatchers.Unconfined,
                    nowProvider = { now.plusSeconds(7200) },
                )

                // First call: computes and caches entry
                val firstResult = service.getRebalancerComparison(
                    now,
                    now.plusSeconds(3600),
                    BenchmarkMethod.FIXED_INCEPTION_HOLD,
                )
                firstResult.availability shouldBe ComparisonAvailability.AVAILABLE
                storedEntry shouldNotBe null

                // Expire the cached dependency so next query revalidates it
                val expiredDep = ConsumedOhlcDependency(
                    pair = "XXBTZUSD",
                    intervalMinutes = 60,
                    sinceEpochSecond = now.epochSecond - 3600,
                    upToEpochSecond = now.epochSecond,
                    fetchedAtEpochSecond = now.epochSecond - 7200,
                    freshnessDeadlineEpochSecond = now.epochSecond - 3600,
                    candleContentHash = "hash1",
                )
                storedEntry = storedEntry?.copy(ohlcDependencies = listOf(expiredDep))

                val revalidationPaused = CompletableDeferred<Unit>()
                val pauseGate = CompletableDeferred<Unit>()
                coEvery { ohlcCache.revalidateDependency(any()) } coAnswers {
                    revalidationPaused.complete(Unit)
                    pauseGate.await()
                    OhlcRevalidationResult.Unchanged(firstArg())
                }

                val secondCallJob = launch {
                    service.getRebalancerComparison(
                        now,
                        now.plusSeconds(3600),
                        BenchmarkMethod.FIXED_INCEPTION_HOLD,
                    )
                }

                revalidationPaused.await()

                // Verification: coordinator lock is NOT held during OHLC revalidation!
                coordinator.tryWithLock { } shouldBe true

                val writerFinished = CompletableDeferred<Unit>()
                val writerJob = launch {
                    coordinator.withLock("ledger-sync") {
                        writerFinished.complete(Unit)
                    }
                }

                runCurrent()
                writerJob.isCompleted shouldBe true
                writerFinished.isCompleted shouldBe true

                pauseGate.complete(Unit)
                secondCallJob.join()
                writerJob.join()
            }
        }

        "unavailable comparison proposal search does not hold coordinator lock and permits concurrent writers" {
            runTest {
                val coordinator = HistoryEvidenceCoordinator()
                val repository = tradeRepository()
                val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
                val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
                val krakenService = mockk<KrakenService>(relaxed = true)

                coEvery { repository.getAllSnapshotsInRange(any(), any()) } coAnswers {
                    repository.getSnapshotsInRange(firstArg(), secondArg())
                }
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) } returns
                    TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns "0"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) } returns
                    LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    "0"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                } returns "test-scope"

                val snap1 = snapshot(now)
                val snap2 = snapshot(now.plusSeconds(3600))
                val snap3 = snapshot(now.plusSeconds(7200))
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1)
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2, snap3)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val proposalSearchPaused = CompletableDeferred<Unit>()
                val pauseGate = CompletableDeferred<Unit>()

                coEvery { krakenService.getAssetMetadata() } coAnswers {
                    proposalSearchPaused.complete(Unit)
                    pauseGate.await()
                    testAssetMetadata
                }

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    krakenService = krakenService,
                    historyEvidenceCoordinator = coordinator,
                    computationDispatcher =
                    (coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher) ?: Dispatchers.Default,
                    nowProvider = { now.plusSeconds(10800) },
                )

                val comparisonJob = launch {
                    service.getRebalancerComparison(now, now.plusSeconds(3600), BenchmarkMethod.FIXED_INCEPTION_HOLD)
                }

                proposalSearchPaused.await()

                // Verification: coordinator lock is NOT held during proposal search!
                coordinator.tryWithLock { } shouldBe true

                val writerFinished = CompletableDeferred<Unit>()
                val writerJob = launch {
                    coordinator.withLock("account-scope-validation") {
                        writerFinished.complete(Unit)
                    }
                }

                runCurrent()
                writerJob.isCompleted shouldBe true
                writerFinished.isCompleted shouldBe true

                pauseGate.complete(Unit)
                comparisonJob.join()
                writerJob.join()
            }
        }

        "getSettingsComparisonStatus evaluates outside coordinator lock and permits concurrent writers" {
            runTest {
                val coordinator = HistoryEvidenceCoordinator()
                val repository = tradeRepository()
                val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
                val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
                val krakenService = mockk<KrakenService>(relaxed = true)
                val configService = mockk<ConfigService>(relaxed = true)

                coEvery { repository.getAllSnapshotsInRange(any(), any()) } coAnswers {
                    repository.getSnapshotsInRange(firstArg(), secondArg())
                }
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) } returns
                    TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns "0"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) } returns
                    LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    "0"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                } returns "test-scope"

                val snap1 = snapshot(now)
                val snap2 = snapshot(now.plusSeconds(3600))
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val evalPaused = CompletableDeferred<Unit>()
                val pauseGate = CompletableDeferred<Unit>()

                coEvery { krakenService.getAssetMetadata() } coAnswers {
                    evalPaused.complete(Unit)
                    pauseGate.await()
                    testAssetMetadata
                }

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    krakenService = krakenService,
                    configService = configService,
                    historyEvidenceCoordinator = coordinator,
                    computationDispatcher =
                    (coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher) ?: Dispatchers.Default,
                    nowProvider = { now.plusSeconds(7200) },
                )

                val statusJob = launch {
                    service.getSettingsComparisonStatus(now, allowPersistedBaselineFastPath = false)
                }

                evalPaused.await()

                // Verification: coordinator lock is NOT held during Settings comparison evaluation!
                coordinator.tryWithLock { } shouldBe true

                val writerFinished = CompletableDeferred<Unit>()
                val writerJob = launch {
                    coordinator.withLock("ledger-sync") {
                        writerFinished.complete(Unit)
                    }
                }

                runCurrent()
                writerJob.isCompleted shouldBe true
                writerFinished.isCompleted shouldBe true

                pauseGate.complete(Unit)
                statusJob.join()
                writerJob.join()
            }
        }

        "rapid calls to requestRebalancerComparison within bucket window join flight and hit cache" {
            runTest {
                val coordinator = HistoryEvidenceCoordinator()
                val repository = tradeRepository()
                val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
                val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
                val krakenService = mockk<KrakenService>(relaxed = true)
                val configService = mockk<ConfigService>(relaxed = true)

                val snap1 = snapshot(now)
                val snap2 = snapshot(now.plusSeconds(3600))
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getAssetMetadata() } returns testAssetMetadata

                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) } returns
                    TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns "0"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) } returns
                    "test-scope"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) } returns
                    LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    "0"
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "4102444800"
                coEvery {
                    ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                } returns "test-scope"

                val testConfig = TestFixtures.config(
                    allocations = listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(TestFixtures.USD, 50.0),
                    ),
                )
                every { configService.getConfig() } returns testConfig

                val activeScope = CoroutineScope(Dispatchers.Unconfined)
                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    krakenService = krakenService,
                    configService = configService,
                    historyEvidenceCoordinator = coordinator,
                    applicationScope = activeScope,
                    computationDispatcher = Dispatchers.Unconfined,
                    nowProvider = { now.plusSeconds(7200) },
                )

                val to1 = now.plusSeconds(3600)
                val result1 = service.requestRebalancerComparison(
                    now,
                    to1,
                    BenchmarkMethod.FIXED_INCEPTION_HOLD,
                )
                result1.availability shouldBe ComparisonAvailability.AVAILABLE

                val to2 = to1.plusMillis(500)
                val result2 = service.requestRebalancerComparison(
                    now,
                    to2,
                    BenchmarkMethod.FIXED_INCEPTION_HOLD,
                )
                result2.availability shouldBe ComparisonAvailability.AVAILABLE
                result2.points shouldBe result1.points
            }
        }
    }
}
