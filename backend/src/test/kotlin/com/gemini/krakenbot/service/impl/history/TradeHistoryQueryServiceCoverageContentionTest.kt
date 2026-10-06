package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.BenchmarkMethod
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.KrakenAssetMetadata
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.RebalancerComparison
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.HistoricalOhlcFetchProof
import com.gemini.krakenbot.repository.HistoricalOhlcRepository
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.OhlcReachabilityDependency
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

    private inner class Fixture(withDurableCache: Boolean = false, private val ohlc: HistoricalOhlcCache? = null) {
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
        var monotonicTimeNanos = 0L
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
                        ohlcDependencies = arg(4),
                        ohlcReachabilityDependencies = arg(5),
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

        fun seedReconstruction() {
            snapshots.replaceAll { it.copy(balancesObservedAt = it.timestamp.minusMillis(1)) }
            tradeMetadata[SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION] =
                TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION
            tradeMetadata[SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_LEDGER_COVERAGE_VERSION] =
                LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
            tradeMetadata[SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_TRADE_COVERAGE_VERSION] =
                TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
            tradeMetadata[SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_START_EPOCH_SEC] =
                now.minusSeconds(120).epochSecond.toString()
            tradeMetadata[SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC] =
                now.plusSeconds(7200).epochSecond.toString()
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
                historicalOhlcCache = ohlc,
                computationDispatcher = mutatingDispatcher,
                monotonicTimeProvider = { monotonicTimeNanos },
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

        /** Replaces the durable entry, as a real repository read of a previously saved row would. */
        fun seedEntry(entry: RebalancerComparisonCacheEntry) {
            cachedEntry = entry
        }
    }

    init {
        "forward reconstruction progress publishes frozen evidence once without moving coverage" {
            runTest {
                for (withDurableCache in listOf(false, true)) {
                    val fixture = Fixture(withDurableCache)
                    fixture.seedReconstruction()
                    val throughKey = SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC
                    val result = fixture.compare(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher) {
                        fixture.tradeMetadata[throughKey] =
                            (fixture.tradeMetadata.getValue(throughKey).toLong() + 10_800).toString()
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

        "forward reconstruction at the exact frozen horizon publishes unchanged evidence once" {
            runTest {
                for (withDurableCache in listOf(false, true)) {
                    val fixture = Fixture(withDurableCache)
                    fixture.seedReconstruction()
                    val throughKey = SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC
                    fixture.tradeMetadata[throughKey] = now.plusSeconds(5400).epochSecond.toString()
                    val result = fixture.compare(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher) {
                        fixture.tradeMetadata[throughKey] = now.plusSeconds(7200).epochSecond.toString()
                    }

                    result.availability shouldBe ComparisonAvailability.AVAILABLE
                    result.points.map { it.timestamp } shouldBe listOf(now, now.plusSeconds(3600))
                    fixture.computations shouldBe 1
                    fixture.digestQueryHorizons.last() shouldBe now.plusSeconds(5400).plusMillis(999)
                    fixture.cache?.let { coVerify(exactly = 1) { it.save(any(), any(), any(), any(), any(), any()) } }
                }
            }
        }

        "forward reconstruction crossing the frozen horizon invalidates unchanged captured rows" {
            runTest {
                for (withDurableCache in listOf(false, true)) {
                    val fixture = Fixture(withDurableCache)
                    fixture.seedReconstruction()
                    val throughKey = SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC
                    fixture.tradeMetadata[throughKey] = now.plusSeconds(5399).epochSecond.toString()
                    val result = fixture.compare(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher) {
                        if (it == 1) fixture.tradeMetadata[throughKey] = now.plusSeconds(7200).epochSecond.toString()
                    }

                    result.availability shouldBe ComparisonAvailability.AVAILABLE
                    result.points.map { it.timestamp } shouldBe listOf(now, now.plusSeconds(3600))
                    fixture.computations shouldBe 2
                    fixture.cache?.let { coVerify(exactly = 1) { it.save(any(), any(), any(), any(), any(), any()) } }
                }
            }
        }

        "forward reconstruction progress cannot conceal a corrected consumed row" {
            runTest {
                for (withDurableCache in listOf(false, true)) {
                    val fixture = Fixture(withDurableCache)
                    fixture.seedReconstruction()
                    val result = fixture.compare(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher) {
                        fixture.tradeMetadata[SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC] =
                            now.plusSeconds(10_800L * it).epochSecond.toString()
                        if (it == 1) fixture.snapshots[1] = snapshot(now.plusSeconds(3600), btcPrice = "51000.00")
                    }

                    result.availability shouldBe ComparisonAvailability.AVAILABLE
                    fixture.computations shouldBe 2
                    result.points.last().rebalancerValueUSD.shouldBeEqualComparingTo(BigDecimal("101000.00"))
                    fixture.cache?.let { coVerify(exactly = 1) { it.save(any(), any(), any(), any(), any(), any()) } }
                }
            }
        }

        "changed reconstruction progress rejects regressions and nonnumeric transitions before publication" {
            runTest {
                val throughKey = SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC
                val initialThrough = now.plusSeconds(7200).epochSecond.toString()
                val forwardThrough = now.plusSeconds(10_800).epochSecond.toString()
                val transitions = listOf(
                    initialThrough to now.plusSeconds(7140).epochSecond.toString(),
                    initialThrough to null,
                    initialThrough to "not-a-timestamp",
                    null to forwardThrough,
                    "not-a-timestamp" to forwardThrough,
                )
                for ((initial, replacement) in transitions) {
                    val fixture = Fixture(withDurableCache = true)
                    fixture.seedReconstruction()
                    if (initial == null) {
                        fixture.tradeMetadata.remove(throughKey)
                    } else {
                        fixture.tradeMetadata[throughKey] = initial
                    }
                    val result = fixture.compare(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher) {
                        if (it == 1) {
                            if (replacement == null) {
                                fixture.tradeMetadata.remove(throughKey)
                            } else {
                                fixture.tradeMetadata[throughKey] = replacement
                            }
                        }
                    }

                    result.availability shouldBe ComparisonAvailability.AVAILABLE
                    fixture.computations shouldBe 2
                    coVerify(exactly = 1) { fixture.cache!!.save(any(), any(), any(), any(), any(), any()) }
                }
            }
        }

        "durable comparison safely replays after forward reconstruction progress before and during lookup" {
            runTest {
                val fixture = Fixture(withDurableCache = true)
                fixture.seedReconstruction()
                fixture.compare(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher) {}
                val warmed = checkNotNull(fixture.cachedEntry)
                val throughKey = SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC
                fixture.tradeMetadata[throughKey] = now.plusSeconds(10_800).epochSecond.toString()
                coEvery { fixture.cache!!.load(any(), any()) } coAnswers {
                    fixture.tradeMetadata[throughKey] = now.plusSeconds(21_600).epochSecond.toString()
                    warmed
                }

                val result = fixture.request()

                result.availability shouldBe ComparisonAvailability.AVAILABLE
                result.points.last().rebalancerValueUSD.shouldBeEqualComparingTo(BigDecimal("100000.00"))
                fixture.computations shouldBe 2
                fixture.digestQueryHorizons.last() shouldBe now.plusSeconds(5400).plusMillis(999)
                (fixture.cachedEntry!!.inputFingerprint == warmed.inputFingerprint) shouldBe false
                coVerify(exactly = 2) { fixture.cache!!.save(any(), any(), any(), any(), any(), any()) }
            }
        }

        "durable cache hit remains valid for forward progress already beyond its frozen horizon" {
            runTest {
                val fixture = Fixture(withDurableCache = true)
                fixture.seedReconstruction()
                val throughKey = SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC
                fixture.tradeMetadata[throughKey] = now.plusSeconds(7200).epochSecond.toString()
                val dispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
                fixture.compare(dispatcher) {}
                val warmed = checkNotNull(fixture.cachedEntry)
                val warmedComputations = fixture.computations

                // Expire the memory result so the follow-up reaches the durable repository.
                fixture.monotonicTimeNanos = 30_000_000_001L
                var loaded = false
                coEvery { fixture.cache!!.load(any(), any()) } coAnswers {
                    // The capture fingerprint matches the warmed entry; progress advances only
                    // after load begins, so this must exercise a genuine durable Hit.
                    fixture.tradeMetadata[throughKey] = now.plusSeconds(10_800).epochSecond.toString()
                    loaded = true
                    warmed
                }

                val result = fixture.request()

                loaded shouldBe true
                result.availability shouldBe ComparisonAvailability.AVAILABLE
                result.points.map { it.timestamp } shouldBe listOf(now, now.plusSeconds(3600))
                fixture.computations shouldBe warmedComputations
                // One miss warms the cache; the second call is the genuine durable Hit.
                coVerify(exactly = 2) { fixture.cache!!.load(any(), any()) }
            }
        }

        "durable cache hit is rejected when progress crosses its frozen horizon during lookup" {
            runTest {
                val fixture = Fixture(withDurableCache = true)
                fixture.seedReconstruction()
                val throughKey = SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC
                fixture.tradeMetadata[throughKey] = now.plusSeconds(5399).epochSecond.toString()
                val dispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
                fixture.compare(dispatcher) {}
                val warmed = checkNotNull(fixture.cachedEntry)
                val warmedComputations = fixture.computations
                // Make the matching entry distinguishable from any recalculated result.
                val staleHit = warmed.copy(
                    comparison = warmed.comparison.copy(
                        points = warmed.comparison.points.map {
                            it.copy(rebalancerValueUSD = BigDecimal("99999.00"))
                        },
                    ),
                )
                // Force the next request past the memory cache so it reaches durable lookup.
                fixture.monotonicTimeNanos = 30_000_000_001L
                coEvery { fixture.cache!!.load(any(), any()) } coAnswers {
                    // Fingerprint matched at capture; only then does progress cross the horizon.
                    fixture.tradeMetadata[throughKey] = now.plusSeconds(7200).epochSecond.toString()
                    staleHit
                }

                val result = fixture.request()

                result.availability shouldBe ComparisonAvailability.AVAILABLE
                result.points.last().rebalancerValueUSD.shouldBeEqualComparingTo(BigDecimal("100000.00"))
                fixture.computations shouldBe warmedComputations + 2
                fixture.cachedEntry!!.inputFingerprint shouldNotBe warmed.inputFingerprint
            }
        }

        "durable fingerprint rejects regressed removed and malformed reconstruction progress before capture" {
            runTest {
                val throughKey = SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC
                for (replacement in listOf(now.plusSeconds(7140).epochSecond.toString(), null, "not-a-timestamp")) {
                    val fixture = Fixture(withDurableCache = true)
                    fixture.seedReconstruction()
                    fixture.compare(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher) {}
                    val warmed = checkNotNull(fixture.cachedEntry)
                    if (replacement == null) {
                        fixture.tradeMetadata.remove(throughKey)
                    } else {
                        fixture.tradeMetadata[throughKey] = replacement
                    }

                    val result = fixture.request()

                    result.availability shouldBe ComparisonAvailability.AVAILABLE
                    result.points.last().rebalancerValueUSD.shouldBeEqualComparingTo(BigDecimal("100000.00"))
                    fixture.computations shouldBe 2
                    (fixture.cachedEntry!!.inputFingerprint == warmed.inputFingerprint) shouldBe false
                    coVerify(exactly = 2) { fixture.cache!!.save(any(), any(), any(), any(), any(), any()) }
                }
            }
        }

        "durable cache hits reject regressed removed and malformed reconstruction progress during lookup" {
            runTest {
                val throughKey = SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC
                for (replacement in listOf(now.plusSeconds(7140).epochSecond.toString(), null, "not-a-timestamp")) {
                    val fixture = Fixture(withDurableCache = true)
                    fixture.seedReconstruction()
                    fixture.compare(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher) {}
                    val warmed = checkNotNull(fixture.cachedEntry)
                    val stale = warmed.copy(
                        comparison = warmed.comparison.copy(
                            points = warmed.comparison.points.map {
                                it.copy(rebalancerValueUSD = BigDecimal("99999.00"))
                            },
                        ),
                    )
                    var mutated = false
                    coEvery { fixture.cache!!.load(any(), any()) } coAnswers {
                        if (!mutated) {
                            if (replacement == null) {
                                fixture.tradeMetadata.remove(throughKey)
                            } else {
                                fixture.tradeMetadata[throughKey] = replacement
                            }
                            mutated = true
                            stale
                        } else {
                            null
                        }
                    }

                    val result = fixture.request()

                    result.availability shouldBe ComparisonAvailability.AVAILABLE
                    result.points.last().rebalancerValueUSD.shouldBeEqualComparingTo(BigDecimal("100000.00"))
                    fixture.computations shouldBe 3
                }
            }
        }

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

        "a cache hit is rejected when a reachability frontier advances during the unlocked lookup" {
            runTest {
                val dispatcher = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
                val dependency = OhlcReachabilityDependency(
                    pair = "BTCUSD",
                    intervalMinutes = 60,
                    earliestReachableEpochSecond = now.plusSeconds(3600).epochSecond,
                )
                val ohlc = mockk<HistoricalOhlcCache>(relaxed = true)
                coEvery { ohlc.withReadBatch<RebalancerComparison>(any()) } coAnswers {
                    firstArg<suspend (HistoricalOhlcReadBatch?) -> RebalancerComparison>().invoke(null)
                }
                // The unlocked lookup check runs first and must still pass; the frontier only moves
                // afterwards, so the rejection has to come from the locked revalidation.
                var frontierChecks = 0
                coEvery { ohlc.isReachabilityDependencyCurrent(any()) } answers { frontierChecks++ == 0 }
                val fixture = Fixture(withDurableCache = true, ohlc = ohlc)
                fixture.compare(dispatcher) {}
                fixture.cachedEntry shouldNotBe null
                val warmed = fixture.cachedEntry!!
                // The stored economics are deliberately distinguishable from a fresh replay.
                val staleComparison = warmed.comparison.copy(
                    points = warmed.comparison.points.map {
                        it.copy(rebalancerValueUSD = BigDecimal("99999.00"))
                    },
                )
                fixture.seedEntry(
                    warmed.copy(
                        comparison = staleComparison,
                        ohlcReachabilityDependencies = listOf(dependency),
                    ),
                )
                val warmedComputations = fixture.computations

                val result = fixture.request()

                // The stale cached economics must not be served; a replay is required.
                result.points.last().rebalancerValueUSD.shouldBeEqualComparingTo(BigDecimal("100000.00"))
                fixture.computations shouldBe warmedComputations + 1
            }
        }

        "a new OHLC covering proof between calculation and publication forces replay" {
            runTest {
                for (withDurableCache in listOf(false, true)) {
                    val proofs = mutableListOf<HistoricalOhlcFetchProof>()
                    val proofRepository = mockk<HistoricalOhlcRepository>()
                    coEvery { proofRepository.loadFetchProofs(any(), any()) } answers { proofs.toList() }
                    val ohlc = mockk<HistoricalOhlcCache>(relaxed = true)
                    var batches = 0
                    coEvery { ohlc.withReadBatch<RebalancerComparison>(any()) } coAnswers {
                        val batch = HistoricalOhlcReadBatch(ohlc, proofRepository)
                        val mayCover = batch.mayCover("BTCUSD", 60, now.epochSecond, now.plusSeconds(3600).epochSecond)
                        mayCover shouldBe (++batches > 1)
                        val calculated = firstArg<suspend (HistoricalOhlcReadBatch?) -> RebalancerComparison>()(batch)
                        if (batches == 1) {
                            // The helper has already validated. Only the locked publication recheck can see this.
                            proofs += HistoricalOhlcFetchProof(
                                now.epochSecond,
                                now.epochSecond,
                                now.plusSeconds(3600).epochSecond,
                                now.plusSeconds(7200).epochSecond,
                            )
                        }
                        calculated
                    }
                    val fixture = Fixture(withDurableCache, ohlc)

                    val result = fixture.compare(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher) {}

                    result.availability shouldBe ComparisonAvailability.AVAILABLE
                    result.points.last().rebalancerValueUSD.shouldBeEqualComparingTo(BigDecimal("100000.00"))
                    batches shouldBe 2
                    fixture.cache?.let { coVerify(exactly = 1) { it.save(any(), any(), any(), any(), any(), any()) } }
                }
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
                fixture.seedReconstruction()
                val result = fixture.compare(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher) {
                    fixture.advanceCoverage()
                    if (it == 1) {
                        fixture.tradeMetadata[SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION] = "corrected-version"
                    }
                }

                result.availability shouldBe ComparisonAvailability.AVAILABLE
                fixture.computations shouldBe 2
            }
        }
    }
}
