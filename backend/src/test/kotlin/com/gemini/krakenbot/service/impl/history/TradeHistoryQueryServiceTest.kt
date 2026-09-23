@file:OptIn(ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service.impl.history

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonConfidence
import com.gemini.krakenbot.model.ComparisonProposalStatus
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.DepositStatusRecord
import com.gemini.krakenbot.model.FundingEvidence
import com.gemini.krakenbot.model.FundingProvenanceFailure
import com.gemini.krakenbot.model.FundingProvenanceFailureReason
import com.gemini.krakenbot.model.FundingProvenanceResolver
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.RebalancerOrderIdentities
import com.gemini.krakenbot.model.SimpleFundingProvenanceResolver
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.ConsumedOhlcDependency
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.OhlcReachabilityDependency
import com.gemini.krakenbot.repository.OrderIntentRepository
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.RebalancerComparisonCacheEntry
import com.gemini.krakenbot.repository.RebalancerComparisonCacheRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.repository.impl.SqliteFundingEvidenceIdentityStoreImpl
import com.gemini.krakenbot.repository.impl.SqliteHistoricalOhlcRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteRebalancerComparisonCacheRepositoryImpl
import com.gemini.krakenbot.repository.table.RebalancerComparisonCacheTable
import com.gemini.krakenbot.service.AutomaticBaselineStatus
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.SettingsComparisonStatus
import com.gemini.krakenbot.service.impl.KrakenFundingProvenanceResolver
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.Comparator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// Map-stubbed tests answer every sync-metadata read from a local map; these keys fall back
// to far-future certified coverage so evaluation tests exercise the stable-history path.
// Defer-specific tests stub the coverage keys to null explicitly.
private val CERTIFIED_COVERAGE_DEFAULTS = mapOf(
    SyncMetadataKeys.TRADE_COVERAGE_VERSION to TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION,
    SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC to "0",
    SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC to "4102444800",
    SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST to "test-scope",
    SyncMetadataKeys.LEDGER_COVERAGE_VERSION to LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
    SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC to "0",
    SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC to "4102444800",
    SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST to "test-scope",
    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST to "test-scope",
)

// Captured with COMPARISON_CACHE_VERSION=3 at reviewed head 7bf7d533; the migration fixture below
// uses the same snapshots, evidence metadata, and 1440m fallback inputs.
private const val PRE_V4_COMPARISON_FINGERPRINT =
    "913a36b1371ac3089a2954b98bcb2bdb92f1c9721d47667b0c453d26592d1535"

private suspend fun <T> withTemporarySqliteDatabase(block: suspend (String) -> T): T {
    val directory = Files.createTempDirectory("comparison-cache-test-")
    return try {
        block(directory.resolve("history.db").toString())
    } finally {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder<Path>()).forEach(Files::deleteIfExists)
        }
    }
}

@Suppress("unused")
class TradeHistoryQueryServiceTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val openEndedRangeEnd = Instant.ofEpochMilli(Long.MAX_VALUE)
    private val repository = mockk<TradeRepository>(relaxed = true)
    private val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
    private val ledgerRepository = mockk<LedgerRepository>(relaxed = true)
    private val orderIntentRepository = mockk<OrderIntentRepository>(relaxed = true)
    private val service = TradeHistoryQueryService(repository, statsRepository, ledgerRepository, orderIntentRepository)

    private val now = Instant.parse("2026-07-01T12:00:00Z")

    init {
        coEvery { repository.getAllSnapshotsInRange(any(), any()) } coAnswers {
            repository.getSnapshotsInRange(firstArg(), secondArg())
        }
        // Relaxed mocks answer getSyncMetadata with "" → null coverage, which defers the
        // Settings evaluation; default to far-future certified coverage so tests exercise
        // the stable-history path. Defer-specific tests stub these keys to null.
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
        coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
            "4102444800"
        coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST) } returns
            "test-scope"

        "getRewardsOverTime_CumulativePerSnapshotTime" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(now.plusSeconds(86400), "100000.00", btc = "1.0" to "50000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                val e1 = ledgerEvent("L1", now.minusSeconds(3600), "BTC", "0.1")
                val e2 = ledgerEvent("L2", now.plusSeconds(3600), "BTC", "0.2")
                val e3 = ledgerEvent("L3", now.plusSeconds(90000), "BTC", "0.5")
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns listOf(e1, e2, e3)

                val rewards = service.getRewardsOverTime(Instant.EPOCH, now.plusSeconds(100000))

                rewards.points.size shouldBe 2
                rewards.points[0].cumulativeUSD.shouldBeEqualComparingTo(BigDecimal("5000.00"))
                rewards.points[0].perAssetUSD.getValue("BTC").shouldBeEqualComparingTo(BigDecimal("5000.00"))
                rewards.points[1].cumulativeUSD.shouldBeEqualComparingTo(BigDecimal("15000.00"))
                rewards.totalRewardsUSD.shouldBeEqualComparingTo(BigDecimal("15000.00"))
            }
        }

        "getRewardsOverTime_AccumulatesNetBalanceDeltaAccountingForFees" {
            runTest {
                val snap = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap)
                val e1 = ledgerEvent("L1", now.minusSeconds(3600), "BTC", "0.1", fee = "0.01")
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns listOf(e1)

                val rewards = service.getRewardsOverTime(Instant.EPOCH, now)

                rewards.points.size shouldBe 1
                // 0.1 - 0.01 = 0.09 BTC * 50,000 = 4500.00 USD
                rewards.points[0].perAssetUSD.getValue("BTC").shouldBeEqualComparingTo(BigDecimal("4500.00"))
                rewards.totalRewardsUSD.shouldBeEqualComparingTo(BigDecimal("4500.00"))
            }
        }

        "getRewardsOverTime_EmptyRange_ReturnsZeroTotal" {
            runTest {
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()

                val rewards = service.getRewardsOverTime(Instant.EPOCH, now)

                rewards.points.isEmpty() shouldBe true
                rewards.totalRewardsUSD.shouldBeEqualComparingTo(BigDecimal.ZERO)
            }
        }

        "getRewardsOverTime_SkipsAssetsMissingFromSnapshot" {
            runTest {
                val snap = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap)
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns
                    listOf(ledgerEvent("L1", now.minusSeconds(3600), "SOL", "0.5"))

                val rewards = service.getRewardsOverTime(Instant.EPOCH, now)

                rewards.points.size shouldBe 1
                rewards.points[0].perAssetUSD.isEmpty() shouldBe true
                rewards.points[0].cumulativeUSD.shouldBeEqualComparingTo(BigDecimal("0.00"))
            }
        }

        "getSnapshotsInRange retains the final snapshot of duplicate instants" {
            runTest {
                val t = now
                val snap1 = snapshot(t, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(t, "100100.00", btc = "1.0" to "50000.00")
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSyncMetadata(any()) } returns null

                val result = service.getSnapshotsInRange(t.minusSeconds(60), t.plusSeconds(60))
                result.size shouldBe 1
                result.single().totalValueUSD.shouldBeEqualComparingTo(BigDecimal("100100.00"))
            }
        }

        "getRewardsOverTime_NormalizesEarnStakedAssetSymbols" {
            runTest {
                val snap = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap)
                val e1 = ledgerEvent("L1", now.minusSeconds(3600), "XXBT", "0.1")
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns listOf(e1)

                val rewards = service.getRewardsOverTime(Instant.EPOCH, now)

                rewards.points.size shouldBe 1
                rewards.points[0].perAssetUSD.getValue(Asset.BTC).shouldBeEqualComparingTo(BigDecimal("5000.00"))
                rewards.totalRewardsUSD.shouldBeEqualComparingTo(BigDecimal("5000.00"))
            }
        }

        "getRewardsOverTime_IncludesEarnAndAirdropRewardsButExcludesAllocationMechanics" {
            runTest {
                val snap = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap)
                val earnReward = ledgerEvent("EARN-REWARD", now.minusSeconds(3600), "BTC", "0.1")
                    .copy(type = KrakenApiConstants.LEDGER_TYPE_EARN, subtype = "reward")
                val earnAllocation = ledgerEvent("EARN-ALLOCATION", now.minusSeconds(1800), "BTC", "-1.0")
                    .copy(type = KrakenApiConstants.LEDGER_TYPE_EARN, subtype = "allocation")
                val airdrop = ledgerEvent("AIRDROP", now.minusSeconds(900), "BTC", "0.05")
                    .copy(type = KrakenApiConstants.LEDGER_TYPE_TRANSFER, subtype = "airdrop")
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns
                    listOf(earnReward, earnAllocation, airdrop)

                val rewards = service.getRewardsOverTime(Instant.EPOCH, now)

                rewards.points[0].perAssetUSD.getValue(Asset.BTC)
                    .shouldBeEqualComparingTo(BigDecimal("7500.00"))
                rewards.totalRewardsUSD.shouldBeEqualComparingTo(BigDecimal("7500.00"))
            }
        }

        "getLedgersInRange_DelegatesToLedgerRepository" {
            runTest {
                val expected = listOf(ledgerEvent("L1", now.minusSeconds(3600), "BTC", "0.1"))
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns expected

                val result = service.getLedgersInRange(Instant.EPOCH, now)

                result shouldBe expected
            }
        }

        "getRebalancerComparison_StakingRewardExplainsDelta_Reconciled" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(now.plusSeconds(1800), "105000.00", btc = "1.1" to "50000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                val staking = ledgerEvent("L1", now.plusSeconds(600), "BTC", "0.1")
                val dividend =
                    ledgerEvent("L2", now.plusSeconds(1200), "STRC", "1.25", KrakenApiConstants.LEDGER_TYPE_DIVIDEND)
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns listOf(staking, dividend)

                val comparison = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(1800))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.confidence shouldBe ComparisonConfidence.RECONCILED
            }
        }

        "getRebalancerComparison reuses durable result on revision churn and replays on consumed evidence change" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(now.plusSeconds(3600), "100000.00", btc = "1.0" to "50000.00")
                val cache = InMemoryComparisonCache()
                val configService = mockk<ConfigService>(relaxed = true)
                every { configService.getConfig() } returns TestFixtures.config(
                    allocations = listOf(Allocation(Asset.BTC, 50.0), Allocation(Asset.USD, 50.0)),
                )
                val cachedService = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    comparisonCacheRepository = cache,
                    configService = configService,
                )
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                // Counted: one read per consumed-evidence digest pass plus two per
                // authoritative calculation, so hits and misses are directly observable.
                var ledgerRangeReads = 0
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } coAnswers {
                    ledgerRangeReads++
                    emptyList()
                }

                cachedService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cachedService.getRebalancerComparison(
                    Instant.EPOCH,
                    snap2.timestamp.plusSeconds(30),
                ).availability shouldBe
                    ComparisonAvailability.AVAILABLE

                cache.loadCount shouldBe 2
                cache.saveCount shouldBe 1
                ledgerRangeReads shouldBe 3

                // A revision bump with no consumed-row change (live-tail write) rehashes the
                // digest but must NOT invalidate the cached stable prefix.
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.COMPARISON_EVIDENCE_REVISION)
                } returns "changed"
                cachedService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.loadCount shouldBe 3
                cache.saveCount shouldBe 1
                ledgerRangeReads shouldBe 4

                // A consumed evidence row changing (a trade appears at or before the horizon,
                // committed with its revision bump like every real evidence write) replays the
                // authoritative calculation exactly once. The bot-owned trade is not reflected
                // in the unchanged balances, so the replay itself fails closed — and an
                // unavailable outcome is never cached.
                val trade = TradeRecord(
                    id = 1,
                    pair = "BTCUSD",
                    symbol = "BTC",
                    side = "SELL",
                    timestamp = now.plusSeconds(1800),
                    volume = BigDecimal("0.1"),
                    usdAmount = BigDecimal("5000.00"),
                    success = true,
                    dryRun = false,
                    price = BigDecimal("50000.00"),
                    fee = BigDecimal.ZERO,
                    source = TradeSource.API_FILL,
                    tradeId = "T1",
                    orderTxid = "BOT-ORDER-1",
                    cycleId = null,
                    clientOrderId = null,
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(trade)
                coEvery { orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any()) } returns
                    RebalancerOrderIdentities(orderTxids = setOf("BOT-ORDER-1"))
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.COMPARISON_EVIDENCE_REVISION)
                } returns "4"
                cachedService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.UNAVAILABLE
                cache.loadCount shouldBe 4
                cache.saveCount shouldBe 1
                ledgerRangeReads shouldBe 7

                // Reverting the consumed evidence reproduces the original digest, so the
                // original cached entry is authoritative again — no new save is needed.
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.COMPARISON_EVIDENCE_REVISION)
                } returns "5"
                cachedService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.loadCount shouldBe 5
                cache.saveCount shouldBe 1
                ledgerRangeReads shouldBe 8

                cachedService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.loadCount shouldBe 6
                cache.saveCount shouldBe 1
                ledgerRangeReads shouldBe 8
            }
        }

        "comparison cache records the evidence revision after calculation completes" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(now.plusSeconds(3600), "100000.00", btc = "1.0" to "50000.00")
                val cache = InMemoryComparisonCache()
                val cachedService = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    comparisonCacheRepository = cache,
                )
                var revisionReads = 0
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.COMPARISON_EVIDENCE_REVISION)
                } coAnswers {
                    if (revisionReads++ == 0) "before-calculation" else "after-calculation"
                }
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                cachedService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cachedService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE

                cache.loadCount shouldBe 2
                cache.saveCount shouldBe 1
            }
        }

        "comparison cache survives live-tail snapshot appends and replays once per certified horizon advance" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(
                    now.plusSeconds(3600),
                    "100000.00",
                    btc = "1.0" to "50000.00",
                    usdBalance = "50000.00",
                    balancesObservedAt = now.plusSeconds(3600),
                )
                val liveTailSnap = snapshot(
                    now.plusSeconds(7200),
                    "100000.00",
                    btc = "1.0" to "50000.00",
                    usdBalance = "50000.00",
                    balancesObservedAt = now.plusSeconds(7200),
                )
                val snapshotRows = mutableListOf(snap1, snap2)
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } coAnswers {
                    snapshotRows.filter { !it.timestamp.isBefore(firstArg()) && !it.timestamp.isAfter(secondArg()) }
                }
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    (now.epochSecond + 3600).toString()
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    (now.epochSecond + 3600).toString()
                val cache = InMemoryComparisonCache()
                val cachedService = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    comparisonCacheRepository = cache,
                )

                cachedService.getRebalancerComparison(Instant.EPOCH, liveTailSnap.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cachedService.getRebalancerComparison(Instant.EPOCH, liveTailSnap.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.saveCount shouldBe 1
                cache.loadCount shouldBe 2

                // A live-tail snapshot beyond the certified horizon plus its evidence-write
                // revision bump must not replay the stable prefix: the consumed evidence is
                // unchanged, so the durable result is still authoritative for it.
                snapshotRows += liveTailSnap
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.COMPARISON_EVIDENCE_REVISION)
                } returns "2"
                cachedService.getRebalancerComparison(Instant.EPOCH, liveTailSnap.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.saveCount shouldBe 1
                cache.loadCount shouldBe 3

                // The certified horizon advancing makes the tail snapshot consumed evidence:
                // exactly one authoritative replay, then cache hits again.
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    (now.epochSecond + 7200).toString()
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    (now.epochSecond + 7200).toString()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.COMPARISON_EVIDENCE_REVISION)
                } returns "3"
                cachedService.getRebalancerComparison(Instant.EPOCH, liveTailSnap.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.saveCount shouldBe 2
                cache.loadCount shouldBe 4
                cachedService.getRebalancerComparison(Instant.EPOCH, liveTailSnap.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.saveCount shouldBe 2
                cache.loadCount shouldBe 5
            }
        }

        "regression: unrelated OHLC content changes do not invalidate the cached comparison" {
            runTest {
                val database = DatabaseConfig.init(":memory:")
                val ohlcRepository = SqliteHistoricalOhlcRepositoryImpl(database)
                val mapper = jacksonObjectMapper().apply {
                    registerModule(JavaTimeModule())
                    disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                }
                val durableCache = SqliteRebalancerComparisonCacheRepositoryImpl(database, mapper)
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00", usdBalance = "50000.00")
                val snap2 = snapshot(now.plusSeconds(3600), "50000.00", btc = "1.0" to "0.00", usdBalance = "50000.00")
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                val metadata = CERTIFIED_COVERAGE_DEFAULTS.toMutableMap()
                coEvery { repository.getSyncMetadata(any()) } coAnswers { metadata[firstArg()] }
                coEvery { ledgerRepository.getSyncMetadata(any()) } coAnswers
                    { CERTIFIED_COVERAGE_DEFAULTS[firstArg()] }
                coEvery { orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any()) } returns
                    RebalancerOrderIdentities()

                var clock = now.plusSeconds(7200)
                var btcClose = "50000"
                var ohlcCalls = 0
                val kraken = FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        ohlcCalls++
                        listOf((snap2.timestamp.epochSecond - 900L) to BigDecimal(btcClose))
                    }
                }
                val countingCache = CountingComparisonCache(durableCache)
                val queryService = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    krakenService = kraken,
                    historicalOhlcCache = HistoricalOhlcCache(
                        kraken,
                        persistentRepository = ohlcRepository,
                        nowProvider = { clock },
                    ),
                    comparisonCacheRepository = countingCache,
                    nowProvider = { clock },
                )

                // Cold calculation consumes BTC OHLC evidence and caches the comparison.
                queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                countingCache.saveCount shouldBe 1
                val coldOhlcCalls = ohlcCalls
                coldOhlcCalls shouldBeGreaterThan 0

                // Unrelated ETH content persists (advancing the global OHLC revision in
                // production, mirrored here by the metadata stub): the BTC comparison stays
                // a hit with zero replays and zero OHLC calls.
                ohlcRepository.saveFetch(
                    pair = "XETHZUSD",
                    intervalMinutes = 15,
                    sinceEpochSecond = snap2.timestamp.epochSecond - 86_400L,
                    fetchedAtEpochSecond = clock.epochSecond,
                    candles = listOf((snap2.timestamp.epochSecond - 900L) to BigDecimal("3000")),
                    mayBeTruncated = false,
                ) shouldBe true
                metadata[SyncMetadataKeys.OHLC_CANDLE_CONTENT_REVISION] = "1"
                queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                countingCache.saveCount shouldBe 1
                ohlcCalls shouldBe coldOhlcCalls

                // Mutating the CONSUMED BTC candle past expiry replays exactly once.
                clock = clock.plusSeconds(3601)
                btcClose = "55000"
                queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                countingCache.saveCount shouldBe 2
                queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                countingCache.saveCount shouldBe 2
            }
        }

        "comparison cache expires resolver selections when a fine-tier frontier expires" {
            runTest {
                val database = DatabaseConfig.init(":memory:")
                val ohlcRepository = SqliteHistoricalOhlcRepositoryImpl(database)
                val mapper = jacksonObjectMapper().apply {
                    registerModule(JavaTimeModule())
                    disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                }
                val durableCache = SqliteRebalancerComparisonCacheRepositoryImpl(database, mapper)
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00", usdBalance = "50000.00")
                val snap2 = snapshot(now.plusSeconds(3600), "50000.00", btc = "1.0" to "0.00", usdBalance = "50000.00")
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSyncMetadata(any()) } coAnswers { CERTIFIED_COVERAGE_DEFAULTS[firstArg()] }
                coEvery { ledgerRepository.getSyncMetadata(any()) } coAnswers
                    { CERTIFIED_COVERAGE_DEFAULTS[firstArg()] }
                coEvery { orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any()) } returns
                    RebalancerOrderIdentities()

                // Make the valuation old enough to exercise a genuine 240m page-limit frontier.
                var clock = snap2.timestamp.plusSeconds(130 * 24 * 60 * 60L)
                var ohlcCalls = 0
                val requestedIntervals = mutableListOf<Int>()
                var fifteenMinuteReachable = false
                val kraken = FakeKrakenService().apply {
                    ohlcSupplier = { _, requestedInterval, _ ->
                        ohlcCalls++
                        requestedIntervals += requestedInterval
                        if (requestedInterval == 15 && fifteenMinuteReachable) {
                            listOf((snap2.timestamp.epochSecond - 900L) to BigDecimal("51000"))
                        } else if (requestedInterval == 1440) {
                            listOf(
                                (snap2.timestamp.epochSecond - 24 * 60 * 60L - 1800L) to BigDecimal("50000"),
                            )
                        } else {
                            val candleSeconds = requestedInterval * 60L
                            val firstFutureStart = snap2.timestamp.epochSecond + 2 * candleSeconds
                            (0 until KrakenApiConstants.OHLC_PAGE_SIZE).map { index ->
                                (firstFutureStart + index * candleSeconds) to BigDecimal("50000")
                            }
                        }
                    }
                }
                val countingCache = CountingComparisonCache(durableCache)
                val queryService = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    krakenService = kraken,
                    historicalOhlcCache = HistoricalOhlcCache(
                        kraken,
                        persistentRepository = ohlcRepository,
                        nowProvider = { clock },
                    ),
                    comparisonCacheRepository = countingCache,
                    nowProvider = { clock },
                )

                queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                val fromMs = snap1.timestamp.toEpochMilli()
                val toMs = snap2.timestamp.toEpochMilli()
                val saved = checkNotNull(durableCache.load(fromMs, toMs))
                saved.ohlcReachabilityDependencies.map { it.intervalMinutes }.toSet() shouldBe setOf(15, 60, 240)
                saved.ohlcDependencies.map { it.intervalMinutes }.toSet() shouldBe setOf(1440)
                ohlcCalls shouldBe 4

                // Fresh selection evidence permits a true cache hit with no provider work.
                queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                ohlcCalls shouldBe 4
                countingCache.saveCount shouldBe 1

                // Moving one still-fresh boundary invalidates the cached resolver choice. The
                // shifted frontier continues to block that interval, so recalculation persists
                // the new selection without needing another provider request.
                val movedDependency = saved.ohlcReachabilityDependencies.first()
                val storedFrontier = checkNotNull(
                    ohlcRepository.loadReachabilityFrontier(
                        movedDependency.pair,
                        movedDependency.intervalMinutes,
                    ),
                )
                val movedBoundary = storedFrontier.copy(
                    earliestReachableEpochSecond = storedFrontier.earliestReachableEpochSecond + 1,
                    observedAtEpochSecond = storedFrontier.observedAtEpochSecond + 1,
                )
                ohlcRepository.saveReachabilityFrontier(movedBoundary)
                queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                countingCache.deleteCount shouldBe 1
                countingCache.saveCount shouldBe 2
                val refreshedEntry = checkNotNull(durableCache.load(fromMs, toMs))
                refreshedEntry.ohlcReachabilityDependencies.any {
                    it.pair == movedDependency.pair &&
                        it.intervalMinutes == movedDependency.intervalMinutes &&
                        it.earliestReachableEpochSecond == movedBoundary.earliestReachableEpochSecond
                } shouldBe true

                // The frontier TTL is policy-derived from the truncated completed candles.
                // At the first-tier deadline, 15m becomes reachable and must replace the old
                // daily fallback selection after the expired negative evidence is replayed.
                val retryTimes = mutableListOf<Long>()
                for (dependency in refreshedEntry.ohlcReachabilityDependencies) {
                    retryTimes += checkNotNull(
                        ohlcRepository.loadReachabilityFrontier(dependency.pair, dependency.intervalMinutes),
                    ).retryAfterEpochSecond
                }
                val callsBeforeExpiry = ohlcCalls
                val earliestRetry = retryTimes.min()
                fifteenMinuteReachable = true
                clock = Instant.ofEpochSecond(earliestRetry)
                queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                ohlcCalls shouldBe callsBeforeExpiry + 1
                requestedIntervals.drop(requestedIntervals.size - 1) shouldBe listOf(15)
                countingCache.saveCount shouldBe 3

                // The recalculation now consumes the newly reachable 15m candle and records no
                // negative selection dependencies. Its empty manifest remains a valid cache hit.
                val finerEntry = checkNotNull(durableCache.load(fromMs, toMs))
                finerEntry.ohlcDependencies.map { it.intervalMinutes }.toSet() shouldBe setOf(15)
                finerEntry.ohlcReachabilityDependencies.shouldBeEmpty()
                val callsAfterFinerSelection = ohlcCalls
                queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                ohlcCalls shouldBe callsAfterFinerSelection
                countingCache.saveCount shouldBe 3
            }
        }

        "regression: legacy comparison entry without dependency manifest misses and repopulates" {
            runTest {
                withTemporarySqliteDatabase { databasePath ->
                    val databaseUrl = "jdbc:sqlite:$databasePath"
                    val database = DatabaseConfig.init(databaseUrl)
                    val ohlcRepository = SqliteHistoricalOhlcRepositoryImpl(database)
                    val mapper = jacksonObjectMapper().apply {
                        registerModule(JavaTimeModule())
                        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    }
                    val durableCache = SqliteRebalancerComparisonCacheRepositoryImpl(database, mapper)
                    val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00", usdBalance = "50000.00")
                    val snap2 = snapshot(
                        now.plusSeconds(3600),
                        "50000.00",
                        btc = "1.0" to "0.00",
                        usdBalance = "50000.00",
                    )
                    coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns
                        listOf(snap1, snap2)
                    coEvery { repository.getSnapshotBefore(any()) } returns null
                    coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                    coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                    coEvery { repository.getSyncMetadata(any()) } coAnswers { CERTIFIED_COVERAGE_DEFAULTS[firstArg()] }
                    coEvery { ledgerRepository.getSyncMetadata(any()) } coAnswers
                        { CERTIFIED_COVERAGE_DEFAULTS[firstArg()] }
                    coEvery { orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any()) } returns
                        RebalancerOrderIdentities()

                    var clock = snap2.timestamp.plusSeconds(130 * 24 * 60 * 60L)
                    var ohlcCalls = 0
                    val requestedIntervals = mutableListOf<Int>()
                    var fifteenMinuteReachable = false
                    val kraken = FakeKrakenService().apply {
                        ohlcSupplier = { _, requestedInterval, _ ->
                            ohlcCalls++
                            requestedIntervals += requestedInterval
                            if (requestedInterval == 15 && fifteenMinuteReachable) {
                                listOf((snap2.timestamp.epochSecond - 900L) to BigDecimal("51000"))
                            } else if (requestedInterval == 1440) {
                                listOf(
                                    (snap2.timestamp.epochSecond - 24 * 60 * 60L - 1800L) to BigDecimal("50000"),
                                )
                            } else {
                                val candleSeconds = requestedInterval * 60L
                                val firstFutureStart = snap2.timestamp.epochSecond + 2 * candleSeconds
                                (0 until KrakenApiConstants.OHLC_PAGE_SIZE).map { index ->
                                    (firstFutureStart + index * candleSeconds) to BigDecimal("50000")
                                }
                            }
                        }
                    }
                    val countingCache = CountingComparisonCache(durableCache)
                    val queryService = TradeHistoryQueryService(
                        repository = repository,
                        portfolioStatsRepository = statsRepository,
                        ledgerRepository = ledgerRepository,
                        orderIntentRepository = orderIntentRepository,
                        krakenService = kraken,
                        historicalOhlcCache = HistoricalOhlcCache(
                            kraken,
                            persistentRepository = ohlcRepository,
                            nowProvider = { clock },
                        ),
                        comparisonCacheRepository = countingCache,
                        nowProvider = { clock },
                    )

                    // Cold calculation writes the current-contract entry with exact dependencies.
                    val cold = queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp)
                    cold.availability shouldBe ComparisonAvailability.AVAILABLE
                    countingCache.saveCount shouldBe 1
                    val fromMs = snap1.timestamp.toEpochMilli()
                    val toMs = snap2.timestamp.toEpochMilli()
                    val preMigrationEntry = checkNotNull(durableCache.load(fromMs, toMs))
                    preMigrationEntry.inputFingerprint shouldNotBe PRE_V4_COMPARISON_FINGERPRINT
                    preMigrationEntry.ohlcDependencies.map { it.intervalMinutes }.toSet() shouldBe setOf(1440)
                    preMigrationEntry.ohlcReachabilityDependencies.map { it.intervalMinutes }.toSet() shouldBe
                        setOf(15, 60, 240)
                    ohlcCalls shouldBe 4
                    val firstTierRetry = preMigrationEntry.ohlcReachabilityDependencies.minOf { dependency ->
                        checkNotNull(
                            ohlcRepository.loadReachabilityFrontier(dependency.pair, dependency.intervalMinutes),
                        ).retryAfterEpochSecond
                    }

                    // Preserve the v3 fingerprint and consumed candle proof while removing the v15
                    // manifest column/table, then let the real schema migration restore its [] default.
                    durableCache.save(
                        fromMs,
                        toMs,
                        PRE_V4_COMPARISON_FINGERPRINT,
                        cold,
                        preMigrationEntry.ohlcDependencies,
                        emptyList(),
                    )
                    DriverManager.getConnection(databaseUrl).use { connection ->
                        connection.createStatement().use { statement ->
                            statement.executeUpdate("DROP TABLE historical_ohlc_reachability_frontiers")
                            statement.executeUpdate(
                                "ALTER TABLE rebalancer_comparison_cache " +
                                    "DROP COLUMN ohlc_reachability_dependencies_json",
                            )
                            statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 15")
                        }
                    }

                    val migratedDatabase = DatabaseConfig.init(databaseUrl)
                    val migratedCache = SqliteRebalancerComparisonCacheRepositoryImpl(migratedDatabase, mapper)
                    val migratedEntry = checkNotNull(migratedCache.load(fromMs, toMs))
                    migratedEntry.inputFingerprint shouldBe PRE_V4_COMPARISON_FINGERPRINT
                    migratedEntry.ohlcDependencies.map { it.intervalMinutes }.toSet() shouldBe setOf(1440)
                    migratedEntry.ohlcReachabilityDependencies.shouldBeEmpty()
                    val migratedCountingCache = CountingComparisonCache(migratedCache)
                    val migratedQueryService = TradeHistoryQueryService(
                        repository = repository,
                        portfolioStatsRepository = statsRepository,
                        ledgerRepository = ledgerRepository,
                        orderIntentRepository = orderIntentRepository,
                        krakenService = kraken,
                        historicalOhlcCache = HistoricalOhlcCache(
                            kraken,
                            persistentRepository = SqliteHistoricalOhlcRepositoryImpl(migratedDatabase),
                            nowProvider = { clock },
                        ),
                        comparisonCacheRepository = migratedCountingCache,
                        nowProvider = { clock },
                    )

                    // The migrated v3 row remains readable but its fingerprint cannot validate under
                    // v4. When 15m history becomes reachable, replay replaces its old 1440m selection.
                    val callsBeforeReplay = requestedIntervals.size
                    fifteenMinuteReachable = true
                    clock = Instant.ofEpochSecond(firstTierRetry)
                    migratedQueryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                        ComparisonAvailability.AVAILABLE
                    requestedIntervals.drop(callsBeforeReplay) shouldBe listOf(15)
                    migratedCountingCache.saveCount shouldBe 1
                    val repopulated = checkNotNull(migratedCache.load(fromMs, toMs))
                    repopulated.inputFingerprint shouldNotBe PRE_V4_COMPARISON_FINGERPRINT
                    repopulated.ohlcDependencies.map { it.intervalMinutes }.toSet() shouldBe setOf(15)
                    repopulated.ohlcReachabilityDependencies.shouldBeEmpty()
                    repopulated.ohlcDependencies.shouldNotBeEmpty()
                    repopulated.ohlcDependencies.forEach { dep ->
                        (dep.upToEpochSecond > dep.sinceEpochSecond) shouldBe true
                        dep.candleContentHash.shouldNotBeBlank()
                    }

                    // Reopen the file-backed database and use new repositories/services to verify
                    // the persisted v4 entry survives restart.
                    val restartedDatabase = DatabaseConfig.init(databaseUrl)
                    val restartedCache = SqliteRebalancerComparisonCacheRepositoryImpl(restartedDatabase, mapper)
                    val restartedCountingCache = CountingComparisonCache(restartedCache)
                    val restartedQueryService = TradeHistoryQueryService(
                        repository = repository,
                        portfolioStatsRepository = statsRepository,
                        ledgerRepository = ledgerRepository,
                        orderIntentRepository = orderIntentRepository,
                        krakenService = kraken,
                        historicalOhlcCache = HistoricalOhlcCache(
                            kraken,
                            persistentRepository = SqliteHistoricalOhlcRepositoryImpl(restartedDatabase),
                            nowProvider = { clock },
                        ),
                        comparisonCacheRepository = restartedCountingCache,
                        nowProvider = { clock },
                    )
                    val callsBeforeRestartHit = ohlcCalls
                    restartedQueryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                        ComparisonAvailability.AVAILABLE
                    ohlcCalls shouldBe callsBeforeRestartHit
                    restartedCountingCache.saveCount shouldBe 0
                }
            }
        }

        "loadCachedComparison degrades gracefully when cache throws an exception" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00", usdBalance = "50000.00")
                val snap2 =
                    snapshot(now.plusSeconds(3600), "100000.00", btc = "1.0" to "50000.00", usdBalance = "50000.00")
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                val brokenCache = mockk<RebalancerComparisonCacheRepository>()
                coEvery { brokenCache.load(any(), any()) } throws RuntimeException("sqlite disk failure")
                val cachedService = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    comparisonCacheRepository = brokenCache,
                )
                cachedService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
            }
        }

        "persisted comparison is reused across restart via the durable funding identity" {
            runTest {
                val database = DatabaseConfig.init(":memory:")
                val identityStore = SqliteFundingEvidenceIdentityStoreImpl(database)
                val ohlcRepository = SqliteHistoricalOhlcRepositoryImpl(database)
                val mapper = jacksonObjectMapper().apply {
                    registerModule(JavaTimeModule())
                    disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                }
                val durableCache = SqliteRebalancerComparisonCacheRepositoryImpl(database, mapper)
                val snap1 = snapshot(now, "1000.00", btc = "0.0" to "50000.00", usdBalance = "1000.00")
                val snap2 = snapshot(
                    now.plusSeconds(3600),
                    "1100.00",
                    btc = "0.0" to "50000.00",
                    usdBalance = "1100.00",
                )
                val deposit = ledgerEvent(
                    ledgerId = "LIVE-DEPOSIT",
                    timestamp = now.plusSeconds(1800),
                    asset = Asset.USD,
                    amount = "100.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    refid = "LIVE-DEPOSIT-REF",
                )
                var depositMethod = "Wire"
                fun krakenWithDeposits() = FakeKrakenService().apply {
                    depositStatusSupplier = { _, _ ->
                        listOf(
                            DepositStatusRecord(
                                refid = "LIVE-DEPOSIT-REF",
                                asset = Asset.USD,
                                amount = BigDecimal("100.00"),
                                time = deposit.time,
                                status = "Success",
                                method = depositMethod,
                            ),
                        )
                    }
                }
                val krakenA = krakenWithDeposits()
                val resolverA = KrakenFundingProvenanceResolver(krakenA, durableIdentityStore = identityStore)
                val serviceA = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    fundingProvenanceResolver = resolverA,
                    krakenService = krakenA,
                    historicalOhlcCache = HistoricalOhlcCache(krakenA, ohlcRepository),
                    comparisonCacheRepository = durableCache,
                )
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns listOf(deposit)

                serviceA.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                krakenA.getDepositStatusCallCount shouldBe 1
                val originalFingerprint = persistedCacheFingerprint(database)

                // Restart: a brand-new resolver (never prepared) over the same durable stores
                // must recognize the persisted comparison without any funding status call.
                val krakenB = krakenWithDeposits()
                val resolverB = KrakenFundingProvenanceResolver(krakenB, durableIdentityStore = identityStore)
                val serviceB = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    fundingProvenanceResolver = resolverB,
                    krakenService = krakenB,
                    historicalOhlcCache = HistoricalOhlcCache(krakenB, ohlcRepository),
                    comparisonCacheRepository = durableCache,
                )
                serviceB.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                krakenB.getDepositStatusCallCount shouldBe 0
                persistedCacheFingerprint(database) shouldBe originalFingerprint

                // Mutating the durable funding evidence (a provider correction of the deposit
                // record) changes the durable identity and forces one authoritative replay.
                // The replay reuses the freshly prepared in-memory batch, so no additional
                // funding status call is spent merely to re-persist the corrected result.
                depositMethod = "Swift"
                resolverB.prepare(listOf(deposit))
                krakenB.getDepositStatusCallCount shouldBe 1
                serviceB.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                persistedCacheFingerprint(database) shouldNotBe originalFingerprint
            }
        }

        "acceptance: durable comparison cache survives live tail, TTL, and restart with bounded replays" {
            runTest {
                val database = DatabaseConfig.init(":memory:")
                val identityStore = SqliteFundingEvidenceIdentityStoreImpl(database)
                val ohlcRepository = SqliteHistoricalOhlcRepositoryImpl(database)
                val mapper = jacksonObjectMapper().apply {
                    registerModule(JavaTimeModule())
                    disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                }
                val durableCache = SqliteRebalancerComparisonCacheRepositoryImpl(database, mapper)
                val metadata = mutableMapOf<String, String>(
                    SyncMetadataKeys.TRADE_COVERAGE_VERSION to
                        TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION,
                    SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC to "0",
                    SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC to (now.epochSecond + 28_860L).toString(),
                    SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST to "test-scope",
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST to "test-scope",
                )
                val ledgerMetadata = mutableMapOf<String, String>(
                    SyncMetadataKeys.LEDGER_COVERAGE_VERSION to
                        LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
                    SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC to "0",
                    SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC to (now.epochSecond + 28_860L).toString(),
                    SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST to "test-scope",
                )
                coEvery { repository.getSyncMetadata(any()) } coAnswers { metadata[firstArg()] }
                coEvery { ledgerRepository.getSyncMetadata(any()) } coAnswers { ledgerMetadata[firstArg()] }
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any()) } returns
                    RebalancerOrderIdentities()

                // Snapshots eight hours apart with an unrecorded BTC price on the later one, so
                // the comparison must resolve the valuation through historical OHLC evidence.
                // The +100 USD deposit explains the USD balance change between them.
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00", usdBalance = "50000.00")
                val snap2 = snapshot(
                    now.plusSeconds(28_800),
                    "50100.00",
                    btc = "1.0" to "0.00",
                    usdBalance = "50100.00",
                )
                val liveTailSnap = snapshot(
                    now.plusSeconds(36_000),
                    "50100.00",
                    btc = "1.0" to "0.00",
                    usdBalance = "50100.00",
                    balancesObservedAt = now.plusSeconds(36_000),
                )
                val snapshotRows = mutableListOf(snap1, snap2)
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } coAnswers {
                    snapshotRows.filter { !it.timestamp.isBefore(firstArg()) && !it.timestamp.isAfter(secondArg()) }
                }

                val deposit = ledgerEvent(
                    ledgerId = "ACC-DEPOSIT",
                    timestamp = now.plusSeconds(14_400),
                    asset = Asset.USD,
                    amount = "100.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    refid = "ACC-DEPOSIT-REF",
                )
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns listOf(deposit)

                var ohlcClose = "50000"
                var ohlcCalls = 0
                var extraOhlc: List<Pair<Long, BigDecimal>> = emptyList()
                var dropSnap2Candle = false
                var emptyFifteenMinute = false
                fun fundingKraken() = FakeKrakenService().apply {
                    depositStatusSupplier = { _, _ ->
                        listOf(
                            DepositStatusRecord(
                                refid = "ACC-DEPOSIT-REF",
                                asset = Asset.USD,
                                amount = BigDecimal("100.00"),
                                time = deposit.time,
                                status = "Success",
                                method = "Wire",
                            ),
                        )
                    }
                    ohlcSupplier = { _, interval, _ ->
                        ohlcCalls++
                        // Completed candles closing exactly at the deposit-deployment and
                        // snapshot valuation instants (plus a one-bucket-older fallback for
                        // the snapshot instant), two grid-aligned coarser-tier fallbacks
                        // (the tier matcher scores close as start + tier duration, so only
                        // grid-aligned candles feed the 60m tier), plus any newer provider
                        // growth appended by later steps (beyond every consumed instant).
                        if (emptyFifteenMinute && interval == 15) {
                            emptyList()
                        } else {
                            buildList {
                                add((deposit.time.epochSecond - 900L) to BigDecimal(ohlcClose))
                                add((snap2.timestamp.epochSecond - 1800L) to BigDecimal(ohlcClose))
                                add((deposit.time.epochSecond - 7200L) to BigDecimal(ohlcClose))
                                add((snap2.timestamp.epochSecond - 7200L) to BigDecimal(ohlcClose))
                                if (!dropSnap2Candle) {
                                    add((snap2.timestamp.epochSecond - 900L) to BigDecimal(ohlcClose))
                                }
                            } + extraOhlc
                        }
                    }
                }
                var clock = now.plusSeconds(36_000)
                val cache = CountingComparisonCache(durableCache)
                fun buildService(kraken: FakeKrakenService) = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    fundingProvenanceResolver = KrakenFundingProvenanceResolver(
                        kraken,
                        nowProvider = { clock },
                        durableIdentityStore = identityStore,
                    ),
                    krakenService = kraken,
                    historicalOhlcCache = HistoricalOhlcCache(
                        kraken,
                        persistentRepository = ohlcRepository,
                        nowProvider = { clock },
                    ),
                    comparisonCacheRepository = cache,
                    nowProvider = { clock },
                )

                // 1. Initial cold load: authoritative replay, funding prepared, OHLC fetched, persisted.
                val krakenA = fundingKraken()
                val serviceA = buildService(krakenA)
                val coldResult = serviceA.getRebalancerComparison(Instant.EPOCH, snap2.timestamp)
                coldResult.availability shouldBe ComparisonAvailability.AVAILABLE
                cache.saveCount shouldBe 1
                krakenA.getDepositStatusCallCount shouldBe 1
                // Historical OHLC evidence was fetched to price the deposit deployment and the
                // zero-priced snapshot valuation.
                val coldOhlcCalls = ohlcCalls
                coldOhlcCalls shouldBeGreaterThan 0

                // 2. Immediate repeat: cache hit (0 replays, 0 OHLC, 0 funding).
                serviceA.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.loadCount shouldBe 2
                cache.saveCount shouldBe 1
                ohlcCalls shouldBe coldOhlcCalls
                krakenA.getDepositStatusCallCount shouldBe 1

                // 3. Append unstable live-tail snapshot: still a hit (0 replays).
                snapshotRows += liveTailSnap
                metadata[SyncMetadataKeys.COMPARISON_EVIDENCE_REVISION] = "2"
                serviceA.getRebalancerComparison(Instant.EPOCH, liveTailSnap.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.loadCount shouldBe 3
                cache.saveCount shouldBe 1
                ohlcCalls shouldBe coldOhlcCalls

                // 4. Five minutes later with unchanged stable evidence: funding TTL expired, the
                // durable identity still certifies the cached result. Still a hit (0 replays).
                clock = clock.plusSeconds(300)
                serviceA.getRebalancerComparison(Instant.EPOCH, liveTailSnap.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.loadCount shouldBe 4
                cache.saveCount shouldBe 1
                krakenA.getDepositStatusCallCount shouldBe 1
                ohlcCalls shouldBe coldOhlcCalls

                // 5. Process restart: brand-new resolver and OHLC cache over the same durable
                // stores, no funding or OHLC API calls merely to validate reuse (0 replays, 0 OHLC, 0 funding).
                val krakenB = fundingKraken()
                val serviceB = buildService(krakenB)
                serviceB.getRebalancerComparison(Instant.EPOCH, liveTailSnap.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.loadCount shouldBe 5
                cache.saveCount shouldBe 1
                krakenB.getDepositStatusCallCount shouldBe 0
                ohlcCalls shouldBe coldOhlcCalls

                // 6. Advance time past OHLC freshness deadline, provider returns IDENTICAL candles:
                // Revalidation checks expired OHLC evidence. Since candle content is unchanged,
                // the cache entry's deadlines are refreshed in the DB and the cached comparison is served.
                // 0 calculation replays, 1 bounded OHLC refresh.
                clock = clock.plusSeconds(3601)
                val ohlcCallsBeforeStep6 = ohlcCalls
                val updateOhlcBeforeStep6 = cache.updateOhlcCount
                serviceB.getRebalancerComparison(Instant.EPOCH, liveTailSnap.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.loadCount shouldBe 6
                cache.saveCount shouldBe 1
                ohlcCalls shouldBeGreaterThan ohlcCallsBeforeStep6
                cache.updateOhlcCount shouldBeGreaterThan updateOhlcBeforeStep6
                val ohlcCallsAfterStep6 = ohlcCalls

                // 7. Immediate repeat after OHLC revalidation:
                // Fast path: all dependencies are now fresh in DB/memory.
                // 0 calculation replays, 0 OHLC calls.
                serviceB.getRebalancerComparison(Instant.EPOCH, liveTailSnap.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.loadCount shouldBe 7
                cache.saveCount shouldBe 1
                ohlcCalls shouldBe ohlcCallsAfterStep6

                // 8. Advance time past OHLC freshness deadline, provider returns CORRECTED candles:
                // Revalidation detects content hash difference, invalidates/deletes the cached comparison,
                // and executes exactly 1 authoritative calculation replay.
                clock = clock.plusSeconds(3601)
                ohlcClose = "51000"
                serviceB.getRebalancerComparison(Instant.EPOCH, liveTailSnap.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.loadCount shouldBe 8
                cache.saveCount shouldBe 2
                ohlcCalls shouldBeGreaterThan ohlcCallsAfterStep6
                val ohlcCallsAfterStep8 = ohlcCalls

                // 9. Immediate repeat after correction:
                // Cache hit with newly persisted result and candle hashes.
                // 0 calculation replays, 0 OHLC calls.
                serviceB.getRebalancerComparison(Instant.EPOCH, liveTailSnap.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.loadCount shouldBe 9
                cache.saveCount shouldBe 2
                ohlcCalls shouldBe ohlcCallsAfterStep8

                // 9b. Advance time past OHLC freshness deadline, provider appends NEWER candles
                // beyond every consumed valuation instant: dependency revalidation ignores the
                // future growth, refreshes deadlines, and serves the cache with 0 replays.
                clock = clock.plusSeconds(3601)
                extraOhlc = listOf(
                    (snap2.timestamp.epochSecond + 3600L) to BigDecimal("52000"),
                    (snap2.timestamp.epochSecond + 7200L) to BigDecimal("53000"),
                )
                val updateOhlcBeforeStep9b = cache.updateOhlcCount
                serviceB.getRebalancerComparison(Instant.EPOCH, liveTailSnap.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.loadCount shouldBe 10
                cache.saveCount shouldBe 2
                ohlcCalls shouldBeGreaterThan ohlcCallsAfterStep8
                cache.updateOhlcCount shouldBeGreaterThan updateOhlcBeforeStep9b
                val ohlcCallsAfterStep9b = ohlcCalls

                // 9c. Unrelated pair OHLC content persists (advancing the global OHLC revision
                // in production, mirrored here by the metadata stub): the target comparison
                // stays a hit with 0 replays and 0 OHLC calls.
                ohlcRepository.saveFetch(
                    pair = "XETHZUSD",
                    intervalMinutes = 15,
                    sinceEpochSecond = snap2.timestamp.epochSecond - 86_400L,
                    fetchedAtEpochSecond = clock.epochSecond,
                    candles = listOf((snap2.timestamp.epochSecond - 900L) to BigDecimal("3000")),
                    mayBeTruncated = false,
                ) shouldBe true
                metadata[SyncMetadataKeys.OHLC_CANDLE_CONTENT_REVISION] = "1"
                serviceB.getRebalancerComparison(Instant.EPOCH, liveTailSnap.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.loadCount shouldBe 11
                cache.saveCount shouldBe 2
                ohlcCalls shouldBe ohlcCallsAfterStep9b

                // 10. Advance certified horizon:
                // The certified horizon advances to cover the live tail: exactly one replay.
                metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] =
                    (now.epochSecond + 36_060L).toString()
                ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    (now.epochSecond + 36_060L).toString()
                metadata[SyncMetadataKeys.COMPARISON_EVIDENCE_REVISION] = "3"
                serviceB.getRebalancerComparison(Instant.EPOCH, liveTailSnap.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.loadCount shouldBe 12
                cache.saveCount shouldBe 3

                // 11. Eight days later every historical dependency expires; the provider
                // returns identical consumed candles plus many newer candles beyond every
                // consumed instant (the step-9b growth stays, so nothing consumed moves):
                // one bounded revalidation round, consumed hashes unchanged, refreshed
                // deadlines on the 7-day historical cadence, 0 replays.
                clock = clock.plusSeconds(8 * 86_400L)
                extraOhlc = extraOhlc + (1..24).map { i ->
                    (snap2.timestamp.epochSecond + 7_200L + i * 900L) to BigDecimal("54000")
                } + (1..4).map { i ->
                    // Wall-hugging growth: recent enough to keep every stored series on the
                    // 1h record cadence, yet closing after every consumed instant so the
                    // dependencies below still ride the 7-day historical cadence.
                    (clock.epochSecond - (5 - i) * 1_800L) to BigDecimal("54500")
                }
                val ohlcBefore11 = ohlcCalls
                val updateBefore11 = cache.updateOhlcCount
                serviceB.getRebalancerComparison(Instant.EPOCH, liveTailSnap.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.loadCount shouldBe 13
                cache.saveCount shouldBe 3
                (ohlcCalls - ohlcBefore11) shouldBe 4
                cache.updateOhlcCount shouldBeGreaterThan updateBefore11
                val deps11 = checkNotNull(
                    durableCache.load(
                        snap1.timestamp.toEpochMilli(),
                        liveTailSnap.timestamp.toEpochMilli(),
                    ),
                ).ohlcDependencies
                deps11.size shouldBe 4
                deps11.forEach {
                    (it.freshnessDeadlineEpochSecond - it.fetchedAtEpochSecond) shouldBe 604_800L
                }
                val ohlcAfter11 = ohlcCalls

                // 12. Immediate repeat: cache hit with 0 OHLC calls.
                serviceB.getRebalancerComparison(Instant.EPOCH, liveTailSnap.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.loadCount shouldBe 14
                cache.saveCount shouldBe 3
                ohlcCalls shouldBe ohlcAfter11

                // 12b. Two hours later every dependency is still fresh on its 7-day
                // historical cadence even though the stored series (carrying wall-near
                // future candles) expired on the 1h record cadence: a hit with 0 calls.
                // Union-driven TTLs would expire all four ranges here instead.
                clock = clock.plusSeconds(7_200L)
                serviceB.getRebalancerComparison(Instant.EPOCH, liveTailSnap.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.loadCount shouldBe 15
                cache.saveCount shouldBe 3
                ohlcCalls shouldBe ohlcAfter11

                // 14. Eight more days; the provider retracts one out-of-window future
                // candle: revalidation finds consumed content unchanged (0 replays) while
                // authoritative replacement deletes the retracted row.
                clock = clock.plusSeconds(8 * 86_400L)
                val retractedStart = snap2.timestamp.epochSecond + 7_200L + 24 * 900L
                extraOhlc = extraOhlc.filter { it.first != retractedStart }
                val ohlcBefore14 = ohlcCalls
                serviceB.getRebalancerComparison(Instant.EPOCH, liveTailSnap.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.loadCount shouldBe 16
                cache.saveCount shouldBe 3
                (ohlcCalls - ohlcBefore14) shouldBe 4
                val ohlcAfter14 = ohlcCalls
                val series13 = ohlcRepository.loadCovered(
                    Asset.BTC_USD_PAIR,
                    15,
                    snap2.timestamp.epochSecond - 86_400L,
                    clock.epochSecond,
                )?.candles.orEmpty()
                series13.none { it.first == retractedStart } shouldBe true
                series13.any { it.first == snap2.timestamp.epochSecond - 900L } shouldBe true

                // 15. The provider retracts the consumed snapshot candle: the sorted
                // round revalidates the deposit range (unchanged, 1 call), then the
                // snapshot range reports ContentChanged and aborts the round before the
                // tail ranges are attempted; exactly one replay re-prices from the
                // one-bucket-older fallback candle plus one 60m tail refresh. The repeat
                // then hits.
                dropSnap2Candle = true
                clock = clock.plusSeconds(8 * 86_400L)
                val ohlcBefore15 = ohlcCalls
                serviceB.getRebalancerComparison(Instant.EPOCH, liveTailSnap.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.loadCount shouldBe 17
                cache.saveCount shouldBe 4
                (ohlcCalls - ohlcBefore15) shouldBe 3
                val series14 = ohlcRepository.loadCovered(
                    Asset.BTC_USD_PAIR,
                    15,
                    snap2.timestamp.epochSecond - 86_400L,
                    clock.epochSecond,
                )?.candles.orEmpty()
                series14.none { it.first == snap2.timestamp.epochSecond - 900L } shouldBe true
                series14.any { it.first == snap2.timestamp.epochSecond - 1800L } shouldBe true
                val ohlcAfter15 = ohlcCalls
                serviceB.getRebalancerComparison(Instant.EPOCH, liveTailSnap.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.loadCount shouldBe 18
                cache.saveCount shouldBe 4
                ohlcCalls shouldBe ohlcAfter15

                // 16. The provider returns empty for every 15m window: the first round
                // revalidation reports ContentChanged and aborts the round, then exactly
                // one replay re-prices through the 60m tier (1 round call; the replay
                // fetches 60m for the first two valuations while every 15m lookup hits
                // the fresh-empty proofs and the tail 60m lookup hits the fresh
                // cross-range proof), and the stale 15m rows are removed from the store.
                emptyFifteenMinute = true
                clock = clock.plusSeconds(8 * 86_400L)
                val ohlcBefore16 = ohlcCalls
                serviceB.getRebalancerComparison(Instant.EPOCH, liveTailSnap.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.loadCount shouldBe 19
                cache.saveCount shouldBe 5
                (ohlcCalls - ohlcBefore16) shouldBe 3
                ohlcRepository.loadCovered(
                    Asset.BTC_USD_PAIR,
                    15,
                    snap2.timestamp.epochSecond - 86_400L,
                    clock.epochSecond,
                )?.candles shouldBe emptyList()
                val series60 = ohlcRepository.loadCovered(
                    Asset.BTC_USD_PAIR,
                    60,
                    snap2.timestamp.epochSecond - 86_400L,
                    clock.epochSecond,
                )?.candles.orEmpty()
                series60.any { it.first == snap2.timestamp.epochSecond - 1800L } shouldBe true
                val ohlcAfter16 = ohlcCalls
                serviceB.getRebalancerComparison(Instant.EPOCH, liveTailSnap.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.loadCount shouldBe 20
                cache.saveCount shouldBe 5
                ohlcCalls shouldBe ohlcAfter16
                // Pinned totals for the §4 report: 22 live OHLC calls, 20 cache reads,
                // 5 replays (cold + correction + horizon + removal + empty-range), 4
                // dependency-refresh writes, 3 invalidating deletes.
                ohlcCalls shouldBe 22
                cache.updateOhlcCount shouldBe 4
                cache.deleteCount shouldBe 3
            }
        }

        "regression: external OHLC correction without local row change revalidates and replays once" {
            runTest {
                val database = DatabaseConfig.init(":memory:")
                val ohlcRepository = SqliteHistoricalOhlcRepositoryImpl(database)
                val mapper = jacksonObjectMapper().apply {
                    registerModule(JavaTimeModule())
                    disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                }
                val durableCache = SqliteRebalancerComparisonCacheRepositoryImpl(database, mapper)
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00", usdBalance = "50000.00")
                val snap2 = snapshot(now.plusSeconds(3600), "50000.00", btc = "1.0" to "0.00", usdBalance = "50000.00")
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSyncMetadata(any()) } coAnswers { CERTIFIED_COVERAGE_DEFAULTS[firstArg()] }
                coEvery { ledgerRepository.getSyncMetadata(any()) } coAnswers
                    { CERTIFIED_COVERAGE_DEFAULTS[firstArg()] }
                coEvery { orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any()) } returns
                    RebalancerOrderIdentities()

                var clock = now.plusSeconds(7200)
                var ohlcClose = "50000"
                var ohlcCalls = 0
                val kraken = FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        ohlcCalls++
                        listOf((snap2.timestamp.epochSecond - 900L) to BigDecimal(ohlcClose))
                    }
                }
                val countingCache = CountingComparisonCache(durableCache)
                val queryService = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    krakenService = kraken,
                    historicalOhlcCache = HistoricalOhlcCache(
                        kraken,
                        persistentRepository = ohlcRepository,
                        nowProvider = { clock },
                    ),
                    comparisonCacheRepository = countingCache,
                    nowProvider = { clock },
                )

                // Initial calculation
                val initial = queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp)
                initial.availability shouldBe ComparisonAvailability.AVAILABLE
                countingCache.saveCount shouldBe 1
                val initialOhlcCalls = ohlcCalls

                // Provider corrects candle without any local row change
                clock = clock.plusSeconds(3601)
                ohlcClose = "55000"

                val corrected = queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp)
                corrected.availability shouldBe ComparisonAvailability.AVAILABLE
                countingCache.saveCount shouldBe 2 // exactly 1 replay
                ohlcCalls shouldBeGreaterThan initialOhlcCalls

                // Subsequent call hits cache
                val cached = queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp)
                cached.availability shouldBe ComparisonAvailability.AVAILABLE
                countingCache.saveCount shouldBe 2 // 0 additional replays
            }
        }

        "regression: partial OHLC failure serves available without persisting the result" {
            runTest {
                val database = DatabaseConfig.init(":memory:")
                val ohlcRepository = SqliteHistoricalOhlcRepositoryImpl(database)
                val mapper = jacksonObjectMapper().apply {
                    registerModule(JavaTimeModule())
                    disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                }
                val durableCache = SqliteRebalancerComparisonCacheRepositoryImpl(database, mapper)
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00", usdBalance = "50000.00")
                val snap2 = snapshot(now.plusSeconds(3600), "50000.00", btc = "1.0" to "0.00", usdBalance = "50000.00")
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSyncMetadata(any()) } coAnswers { CERTIFIED_COVERAGE_DEFAULTS[firstArg()] }
                coEvery { ledgerRepository.getSyncMetadata(any()) } coAnswers
                    { CERTIFIED_COVERAGE_DEFAULTS[firstArg()] }
                coEvery { orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any()) } returns
                    RebalancerOrderIdentities()

                var clock = now.plusSeconds(7200)
                val fifteenCalls = AtomicInteger(0)
                val intervalsSeen = ConcurrentHashMap.newKeySet<Int>()
                val kraken = FakeKrakenService().apply {
                    ohlcSupplier = { _, interval, _ ->
                        intervalsSeen += interval
                        if (interval == 15 && fifteenCalls.getAndIncrement() == 0) error("transient 15m failure")
                        // One tier-shaped completed candle closing at the valuation instant, so
                        // a coarser fallback tier still resolves when the fine tier fails.
                        val step = interval * 60L
                        listOf((snap2.timestamp.epochSecond - step) to BigDecimal("50000"))
                    }
                }
                val countingCache = CountingComparisonCache(durableCache)
                val queryService = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    krakenService = kraken,
                    historicalOhlcCache = HistoricalOhlcCache(
                        kraken,
                        persistentRepository = ohlcRepository,
                        nowProvider = { clock },
                    ),
                    comparisonCacheRepository = countingCache,
                    nowProvider = { clock },
                )

                // One valuation falls back past its failed fine tier, so the manifest cannot
                // prove the resolver would choose the same price again: serve the
                // best-available result, but never persist it.
                val degraded = queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp)
                degraded.availability shouldBe ComparisonAvailability.AVAILABLE
                // The failed fine tier fell back to a coarser one for at least one valuation.
                (60 in intervalsSeen) shouldBe true
                countingCache.saveCount shouldBe 0

                // The next request recomputes (nothing was cached), resolves every tier, and
                // persists normally; the request after that hits.
                val recovered = queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp)
                recovered.availability shouldBe ComparisonAvailability.AVAILABLE
                countingCache.saveCount shouldBe 1
                queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                countingCache.saveCount shouldBe 1
            }
        }

        "regression: legacy dependency manifest without upTo misses and replays" {
            runTest {
                val database = DatabaseConfig.init(":memory:")
                val ohlcRepository = SqliteHistoricalOhlcRepositoryImpl(database)
                val mapper = jacksonObjectMapper().apply {
                    registerModule(JavaTimeModule())
                    disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                }
                val durableCache = SqliteRebalancerComparisonCacheRepositoryImpl(database, mapper)
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00", usdBalance = "50000.00")
                val snap2 = snapshot(now.plusSeconds(3600), "50000.00", btc = "1.0" to "0.00", usdBalance = "50000.00")
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSyncMetadata(any()) } coAnswers { CERTIFIED_COVERAGE_DEFAULTS[firstArg()] }
                coEvery { ledgerRepository.getSyncMetadata(any()) } coAnswers
                    { CERTIFIED_COVERAGE_DEFAULTS[firstArg()] }
                coEvery { orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any()) } returns
                    RebalancerOrderIdentities()

                var clock = now.plusSeconds(7200)
                val kraken = FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        listOf((snap2.timestamp.epochSecond - 900L) to BigDecimal("50000"))
                    }
                }
                val countingCache = CountingComparisonCache(durableCache)
                val queryService = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    krakenService = kraken,
                    historicalOhlcCache = HistoricalOhlcCache(
                        kraken,
                        persistentRepository = ohlcRepository,
                        nowProvider = { clock },
                    ),
                    comparisonCacheRepository = countingCache,
                    nowProvider = { clock },
                )

                queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                countingCache.saveCount shouldBe 1

                // Rewrite the persisted row the way a pre-window writer left it: a manifest
                // without upToEpochSecond under the current fingerprint. The row must miss
                // (never validate as an empty manifest) and replay exactly once.
                val fromMs = snap1.timestamp.toEpochMilli()
                val toMs = snap2.timestamp.toEpochMilli()
                transaction(database) {
                    RebalancerComparisonCacheTable.update({
                        (RebalancerComparisonCacheTable.fromEpochMillis eq fromMs) and
                            (RebalancerComparisonCacheTable.toEpochMillis eq toMs)
                    }) {
                        it[RebalancerComparisonCacheTable.ohlcDependenciesJson] =
                            """[{"pair":"XXBTZUSD","intervalMinutes":15,"sinceEpochSecond":1,"fetchedAtEpochSecond":2,"freshnessDeadlineEpochSecond":3,"candleContentHash":"abc"}]"""
                    }
                }
                val replayed = queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp)
                replayed.availability shouldBe ComparisonAvailability.AVAILABLE
                countingCache.saveCount shouldBe 2
                queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                countingCache.saveCount shouldBe 2
            }
        }

        "regression: empty OHLC backfill promotes unavailable comparison to available without local row edits" {
            runTest {
                val database = DatabaseConfig.init(":memory:")
                val ohlcRepository = SqliteHistoricalOhlcRepositoryImpl(database)
                val mapper = jacksonObjectMapper().apply {
                    registerModule(JavaTimeModule())
                    disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                }
                val durableCache = SqliteRebalancerComparisonCacheRepositoryImpl(database, mapper)
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00", usdBalance = "50000.00")
                val snap2 = snapshot(now.plusSeconds(3600), "50000.00", btc = "1.0" to "0.00", usdBalance = "50000.00")
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSyncMetadata(any()) } coAnswers { CERTIFIED_COVERAGE_DEFAULTS[firstArg()] }
                coEvery { ledgerRepository.getSyncMetadata(any()) } coAnswers
                    { CERTIFIED_COVERAGE_DEFAULTS[firstArg()] }
                coEvery { orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any()) } returns
                    RebalancerOrderIdentities()

                var clock = now.plusSeconds(7200)
                var returnEmpty = true
                var ohlcCalls = 0
                val kraken = FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        ohlcCalls++
                        if (returnEmpty) {
                            emptyList()
                        } else {
                            listOf((snap2.timestamp.epochSecond - 900L) to BigDecimal("50000"))
                        }
                    }
                }
                val countingCache = CountingComparisonCache(durableCache)
                val queryService = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    krakenService = kraken,
                    historicalOhlcCache = HistoricalOhlcCache(
                        kraken,
                        persistentRepository = ohlcRepository,
                        nowProvider = { clock },
                    ),
                    comparisonCacheRepository = countingCache,
                    nowProvider = { clock },
                )

                // 1. Initially missing price: comparison is UNAVAILABLE (and NOT cached)
                val initial = queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp)
                initial.availability shouldBe ComparisonAvailability.UNAVAILABLE
                countingCache.saveCount shouldBe 0

                // 2. Provider backfills candles; clock advances past empty-result freshness (600s)
                clock = clock.plusSeconds(601)
                returnEmpty = false

                val backfilled = queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp)
                backfilled.availability shouldBe ComparisonAvailability.AVAILABLE
                countingCache.saveCount shouldBe 1

                // 3. Repeat is a cache hit
                val cached = queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp)
                cached.availability shouldBe ComparisonAvailability.AVAILABLE
                countingCache.saveCount shouldBe 1
            }
        }

        "regression: concurrent expired comparison requests share single OHLC flight with zero replays" {
            runTest {
                val database = DatabaseConfig.init(":memory:")
                val ohlcRepository = SqliteHistoricalOhlcRepositoryImpl(database)
                val mapper = jacksonObjectMapper().apply {
                    registerModule(JavaTimeModule())
                    disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                }
                val durableCache = SqliteRebalancerComparisonCacheRepositoryImpl(database, mapper)
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00", usdBalance = "50000.00")
                val snap2 = snapshot(now.plusSeconds(3600), "50000.00", btc = "1.0" to "0.00", usdBalance = "50000.00")
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSyncMetadata(any()) } coAnswers { CERTIFIED_COVERAGE_DEFAULTS[firstArg()] }
                coEvery { ledgerRepository.getSyncMetadata(any()) } coAnswers
                    { CERTIFIED_COVERAGE_DEFAULTS[firstArg()] }
                coEvery { orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any()) } returns
                    RebalancerOrderIdentities()

                var clock = now.plusSeconds(7200)
                var ohlcCalls = 0
                val kraken = FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        ohlcCalls++
                        Thread.sleep(100)
                        listOf((snap2.timestamp.epochSecond - 900L) to BigDecimal("50000"))
                    }
                }
                val countingCache = CountingComparisonCache(durableCache)
                val queryService = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    krakenService = kraken,
                    historicalOhlcCache = HistoricalOhlcCache(
                        kraken,
                        persistentRepository = ohlcRepository,
                        nowProvider = { clock },
                    ),
                    comparisonCacheRepository = countingCache,
                    nowProvider = { clock },
                )

                // Initial load
                queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                countingCache.saveCount shouldBe 1
                val initialOhlcCalls = ohlcCalls

                // Advance clock past freshness deadline
                clock = clock.plusSeconds(3601)

                // Concurrent queries hit the expired cache
                val results = withContext(Dispatchers.IO) {
                    (1..4).map {
                        async { queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp) }
                    }.awaitAll()
                }

                results.forEach { it.availability shouldBe ComparisonAvailability.AVAILABLE }
                countingCache.saveCount shouldBe 1 // 0 calculation replays
                ohlcCalls shouldBe initialOhlcCalls + 1 // exactly 1 shared OHLC flight
            }
        }

        "regression: expired comparison revalidation on provider error serves stale cache without db update" {
            runTest {
                val database = DatabaseConfig.init(":memory:")
                val ohlcRepository = SqliteHistoricalOhlcRepositoryImpl(database)
                val mapper = jacksonObjectMapper().apply {
                    registerModule(JavaTimeModule())
                    disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                }
                val durableCache = SqliteRebalancerComparisonCacheRepositoryImpl(database, mapper)
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00", usdBalance = "50000.00")
                val snap2 = snapshot(now.plusSeconds(3600), "50000.00", btc = "1.0" to "0.00", usdBalance = "50000.00")
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSyncMetadata(any()) } coAnswers { CERTIFIED_COVERAGE_DEFAULTS[firstArg()] }
                coEvery { ledgerRepository.getSyncMetadata(any()) } coAnswers
                    { CERTIFIED_COVERAGE_DEFAULTS[firstArg()] }
                coEvery { orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any()) } returns
                    RebalancerOrderIdentities()

                var clock = now.plusSeconds(7200)
                var ohlcCalls = 0
                var ohlcShouldFail = false
                val kraken = FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        ohlcCalls++
                        if (ohlcShouldFail) error("Kraken OHLC outage")
                        listOf((snap2.timestamp.epochSecond - 900L) to BigDecimal("50000"))
                    }
                }
                val countingCache = CountingComparisonCache(durableCache)
                val queryService = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    krakenService = kraken,
                    historicalOhlcCache = HistoricalOhlcCache(
                        kraken,
                        persistentRepository = ohlcRepository,
                        nowProvider = { clock },
                    ),
                    comparisonCacheRepository = countingCache,
                    nowProvider = { clock },
                )

                // 1. Initial calculation populates cache
                val initial = queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp)
                initial.availability shouldBe ComparisonAvailability.AVAILABLE
                countingCache.saveCount shouldBe 1
                countingCache.updateOhlcCount shouldBe 0
                ohlcCalls shouldBe 1

                // 2. Advance clock past freshness deadline so dependency expires
                clock = clock.plusSeconds(100_000L)
                ohlcShouldFail = true

                // 3. Request revalidation when provider fails -> serves stale cached comparison without db update
                val cached = queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp)
                cached.availability shouldBe ComparisonAvailability.AVAILABLE
                cached.points shouldBe initial.points
                countingCache.saveCount shouldBe 1 // 0 replays
                countingCache.updateOhlcCount shouldBe 0 // no db write on provider failure
                ohlcCalls shouldBe 2 // attempted revalidation
            }
        }

        class ClockBox(var value: Instant)

        class PriceBox(var value: String)

        class FlagBox(var value: Boolean)

        data class OverBudgetHarness(
            val snapshots: List<PortfolioSnapshot>,
            val clock: ClockBox,
            val btcClose: PriceBox,
            val mutatedRanges: MutableSet<Long>,
            val failAll: FlagBox,
            val metadata: MutableMap<String, String?>,
            val ohlcCalls: AtomicInteger,
            val comparisonCache: InMemoryComparisonCache,
            val service: TradeHistoryQueryService,
            val distinctKeys: Int,
            val coldOhlcCalls: Int,
        ) {
            fun sourceWindow(): Pair<Long, Long> =
                snapshots.first().timestamp.toEpochMilli() to snapshots.last().timestamp.toEpochMilli()
        }

        /**
         * Cold-calculates a comparison over sixty hourly snapshots whose unpriced points each
         * resolve through their own OHLC window, leaving an over-budget dependency manifest cached.
         * Memory-only caches keep the refresh protocol single-threaded and deterministic.
         */
        suspend fun prepareOverBudgetHarness(
            backgroundScope: CoroutineScope? = null,
            comparisonCache: InMemoryComparisonCache = InMemoryComparisonCache(),
            serviceCache: RebalancerComparisonCacheRepository = comparisonCache,
            snapshotCount: Int = 60,
        ): OverBudgetHarness {
            val snapshots = (0 until snapshotCount).map { i ->
                if (i == 0) {
                    snapshot(now, "100000.00", btc = "1.0" to "50000.00", usdBalance = "50000.00")
                } else {
                    snapshot(
                        now.plusSeconds(i * 3600L),
                        "50000.00",
                        btc = "1.0" to "0.00",
                        usdBalance = "50000.00",
                    )
                }
            }
            coEvery { repository.getAllSnapshotsInRange(any(), any()) } coAnswers {
                snapshots.filter { !it.timestamp.isBefore(firstArg()) && !it.timestamp.isAfter(secondArg()) }
            }
            coEvery { repository.getSnapshotBefore(any()) } returns null
            coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
            coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
            val metadata: MutableMap<String, String?> = CERTIFIED_COVERAGE_DEFAULTS.toMutableMap()
            coEvery { repository.getSyncMetadata(any()) } coAnswers { metadata[firstArg()] }
            coEvery { ledgerRepository.getSyncMetadata(any()) } coAnswers { metadata[firstArg()] }
            coEvery { orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any()) } returns
                RebalancerOrderIdentities()

            val clock = ClockBox(now.plusSeconds((snapshotCount + 1) * 3600L))
            val btcClose = PriceBox("50000")
            val mutatedRanges = mutableSetOf<Long>()
            val failAll = FlagBox(false)
            val ohlcCalls = AtomicInteger(0)
            val kraken = FakeKrakenService().apply {
                ohlcSupplier = { _, interval, since ->
                    ohlcCalls.incrementAndGet()
                    if (failAll.value) error("simulated provider outage")
                    // Dense completed-candle chain like the live endpoint returns, so every later
                    // valuation instant matches from coverage without another fetch. Ranges listed
                    // in [mutatedRanges] simulate a provider correction of consumed content.
                    val close = if (since != null && since in mutatedRanges) "55000" else btcClose.value
                    val step = interval * 60L
                    val wall = clock.value.epochSecond
                    generateSequence(since ?: (wall - 86_400L)) { it + step }
                        .takeWhile { it + step < wall }
                        .map { it to BigDecimal(close) }
                        .toList()
                }
            }
            val service = TradeHistoryQueryService(
                repository = repository,
                portfolioStatsRepository = statsRepository,
                ledgerRepository = ledgerRepository,
                orderIntentRepository = orderIntentRepository,
                krakenService = kraken,
                historicalOhlcCache = HistoricalOhlcCache(kraken, nowProvider = { clock.value }),
                comparisonCacheRepository = serviceCache,
                applicationScope = backgroundScope,
                nowProvider = { clock.value },
            )

            val cold = service.getRebalancerComparison(Instant.EPOCH, snapshots.last().timestamp)
            cold.availability shouldBe ComparisonAvailability.AVAILABLE
            comparisonCache.saveCount shouldBe 1
            val fromMs = snapshots.first().timestamp.toEpochMilli()
            val toMs = snapshots.last().timestamp.toEpochMilli()
            val recorded = checkNotNull(comparisonCache.load(fromMs, toMs)).ohlcDependencies
            val distinctKeys = recorded.map { Triple(it.pair, it.intervalMinutes, it.sinceEpochSecond) }.distinct().size
            (distinctKeys > TradeHistoryQueryService.MAX_COMPARISON_OHLC_REVALIDATIONS_PER_REQUEST) shouldBe true
            return OverBudgetHarness(
                snapshots = snapshots,
                clock = clock,
                btcClose = btcClose,
                mutatedRanges = mutatedRanges,
                failAll = failAll,
                metadata = metadata,
                ohlcCalls = ohlcCalls,
                comparisonCache = comparisonCache,
                service = service,
                distinctKeys = distinctKeys,
                coldOhlcCalls = ohlcCalls.get(),
            )
        }

        suspend fun OverBudgetHarness.requestLatest() =
            service.getRebalancerComparison(Instant.EPOCH, snapshots.last().timestamp)

        suspend fun OverBudgetHarness.sortedDistinctSinces(): List<Long> {
            val (fromMs, toMs) = sourceWindow()
            return checkNotNull(comparisonCache.load(fromMs, toMs)).ohlcDependencies
                .map { it.sinceEpochSecond }
                .distinct()
                .sorted()
        }

        "stress: many expired dependencies obey the synchronous refresh budget without scope" {
            runTest {
                val database = DatabaseConfig.init(":memory:")
                val ohlcRepository = SqliteHistoricalOhlcRepositoryImpl(database)
                val mapper = jacksonObjectMapper().apply {
                    registerModule(JavaTimeModule())
                    disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                }
                val durableCache = SqliteRebalancerComparisonCacheRepositoryImpl(database, mapper)
                // Sixty hourly snapshots, the first with a recorded baseline price and the rest
                // unpriced: every later point resolves its valuation through its own OHLC window.
                val snapshots = (0 until 60).map { i ->
                    if (i == 0) {
                        snapshot(now, "100000.00", btc = "1.0" to "50000.00", usdBalance = "50000.00")
                    } else {
                        snapshot(
                            now.plusSeconds(i * 3600L),
                            "50000.00",
                            btc = "1.0" to "0.00",
                            usdBalance = "50000.00",
                        )
                    }
                }
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } coAnswers {
                    snapshots.filter { !it.timestamp.isBefore(firstArg()) && !it.timestamp.isAfter(secondArg()) }
                }
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSyncMetadata(any()) } coAnswers { CERTIFIED_COVERAGE_DEFAULTS[firstArg()] }
                coEvery { ledgerRepository.getSyncMetadata(any()) } coAnswers
                    { CERTIFIED_COVERAGE_DEFAULTS[firstArg()] }
                coEvery { orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any()) } returns
                    RebalancerOrderIdentities()

                var clock = now.plusSeconds(61 * 3600L)
                var ohlcCalls = 0
                val kraken = FakeKrakenService().apply {
                    ohlcSupplier = { _, interval, since ->
                        ohlcCalls++
                        // Dense completed-candle chain like the live endpoint returns, so every
                        // later valuation instant matches from coverage without another fetch.
                        val step = interval * 60L
                        val wall = clock.epochSecond
                        generateSequence(since ?: (wall - 86_400L)) { it + step }
                            .takeWhile { it + step < wall }
                            .map { it to BigDecimal("50000") }
                            .toList()
                    }
                }
                val countingCache = CountingComparisonCache(durableCache)
                val queryService = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    krakenService = kraken,
                    historicalOhlcCache = HistoricalOhlcCache(
                        kraken,
                        persistentRepository = ohlcRepository,
                        nowProvider = { clock },
                    ),
                    comparisonCacheRepository = countingCache,
                    nowProvider = { clock },
                )
                val budget = TradeHistoryQueryService.MAX_COMPARISON_OHLC_REVALIDATIONS_PER_REQUEST

                // Cold calculation records one dependency per valuation window.
                val cold = queryService.getRebalancerComparison(Instant.EPOCH, snapshots.last().timestamp)
                cold.availability shouldBe ComparisonAvailability.AVAILABLE
                countingCache.saveCount shouldBe 1
                val coldOhlcCalls = ohlcCalls
                val fromMs = snapshots.first().timestamp.toEpochMilli()
                val toMs = snapshots.last().timestamp.toEpochMilli()
                val recorded = checkNotNull(durableCache.load(fromMs, toMs)).ohlcDependencies
                val distinctKeys = recorded
                    .map { Triple(it.pair, it.intervalMinutes, it.sinceEpochSecond) }
                    .distinct()
                (distinctKeys.size > budget) shouldBe true

                // Every dependency expires at once: eight days clears even the 7-day
                // historical consumed-window TTL. Each request refreshes at most one budget
                // of ranges, serves the explicit transient, and persists progress; zero replays.
                clock = clock.plusSeconds(8 * 86_400L)
                var hit = false
                repeat(12) {
                    if (hit) return@repeat
                    val before = ohlcCalls
                    val result = queryService.getRebalancerComparison(Instant.EPOCH, snapshots.last().timestamp)
                    (ohlcCalls - before) shouldBeLessThanOrEqual budget
                    countingCache.saveCount shouldBe 1
                    if (result.availability == ComparisonAvailability.AVAILABLE) {
                        hit = true
                    } else {
                        result.unavailableReason shouldBe ComparisonUnavailableReason.EXTERNAL_EVIDENCE_REFRESHING
                    }
                }
                hit shouldBe true
                // Every range refreshed exactly once overall, including the final short batch.
                (ohlcCalls - coldOhlcCalls) shouldBe distinctKeys.size
                countingCache.updateOhlcCount shouldBeGreaterThan 0
            }
        }

        "stress: concurrent over-budget requests share one refresh without replay fan-out" {
            runTest {
                val cache = InMemoryComparisonCache()
                // Sixty hourly snapshots, the first with a recorded baseline price and the rest
                // unpriced: every later point resolves its valuation through its own OHLC window.
                val snapshots = (0 until 60).map { i ->
                    if (i == 0) {
                        snapshot(now, "100000.00", btc = "1.0" to "50000.00", usdBalance = "50000.00")
                    } else {
                        snapshot(
                            now.plusSeconds(i * 3600L),
                            "50000.00",
                            btc = "1.0" to "0.00",
                            usdBalance = "50000.00",
                        )
                    }
                }
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } coAnswers {
                    snapshots.filter { !it.timestamp.isBefore(firstArg()) && !it.timestamp.isAfter(secondArg()) }
                }
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSyncMetadata(any()) } coAnswers { CERTIFIED_COVERAGE_DEFAULTS[firstArg()] }
                coEvery { ledgerRepository.getSyncMetadata(any()) } coAnswers
                    { CERTIFIED_COVERAGE_DEFAULTS[firstArg()] }
                coEvery { orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any()) } returns
                    RebalancerOrderIdentities()

                var clock = now.plusSeconds(61 * 3600L)
                var ohlcCalls = 0
                val kraken = FakeKrakenService().apply {
                    ohlcSupplier = { _, interval, since ->
                        ohlcCalls++
                        // Dense completed-candle chain like the live endpoint returns, so every
                        // later valuation instant matches from coverage without another fetch.
                        val step = interval * 60L
                        val wall = clock.epochSecond
                        generateSequence(since ?: (wall - 86_400L)) { it + step }
                            .takeWhile { it + step < wall }
                            .map { it to BigDecimal("50000") }
                            .toList()
                    }
                }
                val queryService = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    krakenService = kraken,
                    historicalOhlcCache = HistoricalOhlcCache(kraken, nowProvider = { clock }),
                    comparisonCacheRepository = cache,
                    applicationScope = this,
                    nowProvider = { clock },
                )
                val budget = TradeHistoryQueryService.MAX_COMPARISON_OHLC_REVALIDATIONS_PER_REQUEST

                val cold = queryService.getRebalancerComparison(Instant.EPOCH, snapshots.last().timestamp)
                cold.availability shouldBe ComparisonAvailability.AVAILABLE
                cache.saveCount shouldBe 1
                val coldOhlcCalls = ohlcCalls
                val recorded = checkNotNull(
                    cache.load(
                        snapshots.first().timestamp.toEpochMilli(),
                        snapshots.last().timestamp.toEpochMilli(),
                    ),
                ).ohlcDependencies
                val distinctKeys = recorded
                    .map { Triple(it.pair, it.intervalMinutes, it.sinceEpochSecond) }
                    .distinct()
                (distinctKeys.size > budget) shouldBe true

                // Every dependency expires at once (eight days clears even the 7-day
                // historical consumed-window TTL); four identical requests race. Exactly one
                // owns the bounded synchronous batch while joiners spend zero calls, and the
                // single background refresh completes the remainder with zero replays.
                clock = clock.plusSeconds(8 * 86_400L)
                val results = (1..4).map {
                    async { queryService.getRebalancerComparison(Instant.EPOCH, snapshots.last().timestamp) }
                }.awaitAll()
                // The first request always owns or joins before any validation completes, so it
                // deterministically serves the transient; later ones may already observe the hit.
                results.first().availability shouldBe ComparisonAvailability.UNAVAILABLE
                results.first().unavailableReason shouldBe ComparisonUnavailableReason.EXTERNAL_EVIDENCE_REFRESHING
                results.drop(1).forEach {
                    if (it.availability == ComparisonAvailability.UNAVAILABLE) {
                        it.unavailableReason shouldBe ComparisonUnavailableReason.EXTERNAL_EVIDENCE_REFRESHING
                    }
                }
                cache.saveCount shouldBe 1
                advanceUntilIdle()
                // One synchronous batch plus one background remainder: every range refreshed
                // exactly once, then a later request hits with zero further calls.
                (ohlcCalls - coldOhlcCalls) shouldBe distinctKeys.size
                cache.updateOhlcCount shouldBe 2
                val hit = queryService.getRebalancerComparison(Instant.EPOCH, snapshots.last().timestamp)
                hit.availability shouldBe ComparisonAvailability.AVAILABLE
                (ohlcCalls - coldOhlcCalls) shouldBe distinctKeys.size
                cache.saveCount shouldBe 1
            }
        }

        "regression: over-budget sync batch change replays exactly once" {
            runTest {
                val h = prepareOverBudgetHarness()
                h.clock.value = h.clock.value.plusSeconds(3601)
                h.btcClose.value = "55000"
                val result = h.requestLatest()
                result.availability shouldBe ComparisonAvailability.AVAILABLE
                h.comparisonCache.saveCount shouldBe 2
                h.requestLatest().availability shouldBe ComparisonAvailability.AVAILABLE
                h.comparisonCache.saveCount shouldBe 2
            }
        }

        "regression: background refresh change deletes the entry and replays once" {
            runTest {
                val h = prepareOverBudgetHarness(backgroundScope = this)
                // Mutate a deferred range (past the first synchronous batch) only.
                val targetSince = h.sortedDistinctSinces()[8]
                h.mutatedRanges.add(targetSince)
                // Eight days clears even the 7-day historical consumed-window TTL, so the
                // mutated range lands in the expired set the background remainder validates.
                h.clock.value = h.clock.value.plusSeconds(8 * 86_400L)

                val first = h.requestLatest()
                first.availability shouldBe ComparisonAvailability.UNAVAILABLE
                first.unavailableReason shouldBe ComparisonUnavailableReason.EXTERNAL_EVIDENCE_REFRESHING
                h.comparisonCache.saveCount shouldBe 1

                // The background remainder finds the correction and deletes the entry.
                advanceUntilIdle()
                val (fromMs, toMs) = h.sourceWindow()
                h.comparisonCache.load(fromMs, toMs) shouldBe null

                // The next request replays exactly once, then hits.
                h.requestLatest().availability shouldBe ComparisonAvailability.AVAILABLE
                h.comparisonCache.saveCount shouldBe 2
                h.requestLatest().availability shouldBe ComparisonAvailability.AVAILABLE
                h.comparisonCache.saveCount shouldBe 2
            }
        }

        "regression: background refresh never deletes an entry it did not validate" {
            runTest {
                val h = prepareOverBudgetHarness(backgroundScope = this)
                val targetSince = h.sortedDistinctSinces()[8]
                h.mutatedRanges.add(targetSince)
                h.clock.value = h.clock.value.plusSeconds(3601)

                val first = h.requestLatest()
                first.availability shouldBe ComparisonAvailability.UNAVAILABLE
                h.comparisonCache.saveCount shouldBe 1

                // Fresh evidence arrives before the background refresh runs: the certified
                // horizon advances, so the replay replaces the entry under a new fingerprint.
                h.metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] = "4102444801"
                h.metadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] = "4102444801"
                h.metadata[SyncMetadataKeys.COMPARISON_EVIDENCE_REVISION] = "1"
                h.requestLatest().availability shouldBe ComparisonAvailability.AVAILABLE
                h.comparisonCache.saveCount shouldBe 2

                // The background refresh finds the correction but must not delete the
                // replaced entry: the next request still hits with zero further replays.
                advanceUntilIdle()
                h.requestLatest().availability shouldBe ComparisonAvailability.AVAILABLE
                h.comparisonCache.saveCount shouldBe 2
            }
        }

        "regression: background refresh never overwrites an entry it did not validate" {
            runTest {
                val h = prepareOverBudgetHarness(backgroundScope = this)
                h.clock.value = h.clock.value.plusSeconds(3601)

                val first = h.requestLatest()
                first.availability shouldBe ComparisonAvailability.UNAVAILABLE
                h.comparisonCache.saveCount shouldBe 1

                // Fresh evidence arrives before the background refresh runs: the certified
                // horizon advances, so the replay replaces the entry under a new fingerprint.
                h.metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] = "4102444801"
                h.metadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] = "4102444801"
                h.metadata[SyncMetadataKeys.COMPARISON_EVIDENCE_REVISION] = "1"
                h.requestLatest().availability shouldBe ComparisonAvailability.AVAILABLE
                h.comparisonCache.saveCount shouldBe 2
                val (replacedFrom, replacedTo) = h.sourceWindow()
                val replacedDeps = checkNotNull(h.comparisonCache.load(replacedFrom, replacedTo)).ohlcDependencies
                val callsBeforeIdle = h.ohlcCalls.get()

                // The background remainder aborts before validating (the fingerprint changed),
                // so it spends zero live calls and leaves the replaced entry byte-identical:
                // the next request hits with zero more replays.
                advanceUntilIdle()
                h.ohlcCalls.get() shouldBe callsBeforeIdle
                checkNotNull(h.comparisonCache.load(replacedFrom, replacedTo)).ohlcDependencies shouldBe replacedDeps
                h.requestLatest().availability shouldBe ComparisonAvailability.AVAILABLE
                h.comparisonCache.saveCount shouldBe 2
            }
        }

        "regression: background refresh never deletes a same-fingerprint replayed entry" {
            runTest {
                val h = prepareOverBudgetHarness(backgroundScope = this, snapshotCount = 12)
                // The race needs a small deferred remainder: the owner defers a few ranges,
                // and the next request revalidates them synchronously (within budget) while
                // the background remainder is still pending.
                (h.distinctKeys > TradeHistoryQueryService.MAX_COMPARISON_OHLC_REVALIDATIONS_PER_REQUEST) shouldBe
                    true
                (h.distinctKeys <= 2 * TradeHistoryQueryService.MAX_COMPARISON_OHLC_REVALIDATIONS_PER_REQUEST) shouldBe
                    true
                val targetSince = h.sortedDistinctSinces()[8]
                h.mutatedRanges.add(targetSince)
                h.clock.value = h.clock.value.plusSeconds(3601)

                val first = h.requestLatest()
                first.availability shouldBe ComparisonAvailability.UNAVAILABLE
                first.unavailableReason shouldBe ComparisonUnavailableReason.EXTERNAL_EVIDENCE_REFRESHING
                h.comparisonCache.saveCount shouldBe 1
                val (fromMs, toMs) = h.sourceWindow()
                val fingerprintBefore = checkNotNull(h.comparisonCache.load(fromMs, toMs)).inputFingerprint

                // The next request takes the within-budget path (it never consults the refresh
                // marker), finds the correction in the deferred set, and replays under the
                // same fingerprint — before the background remainder runs.
                val second = h.requestLatest()
                second.availability shouldBe ComparisonAvailability.AVAILABLE
                h.comparisonCache.saveCount shouldBe 2
                // The premise this test exists for: the replay really did reuse the
                // fingerprint, so only the dependency-identity guard can save the entry.
                checkNotNull(h.comparisonCache.load(fromMs, toMs)).inputFingerprint shouldBe fingerprintBefore

                // The background remainder validates its stale snapshot, finds the same
                // correction, but must not delete the replayed entry: the fingerprint alone
                // cannot tell the validated entry from its replacement.
                advanceUntilIdle()
                h.comparisonCache.load(fromMs, toMs) shouldNotBe null
                h.requestLatest().availability shouldBe ComparisonAvailability.AVAILABLE
                h.comparisonCache.saveCount shouldBe 2
            }
        }

        "regression: background refresh never overwrites a same-fingerprint refreshed entry" {
            runTest {
                val h = prepareOverBudgetHarness(backgroundScope = this, snapshotCount = 12)
                (h.distinctKeys > TradeHistoryQueryService.MAX_COMPARISON_OHLC_REVALIDATIONS_PER_REQUEST) shouldBe
                    true
                (h.distinctKeys <= 2 * TradeHistoryQueryService.MAX_COMPARISON_OHLC_REVALIDATIONS_PER_REQUEST) shouldBe
                    true
                h.clock.value = h.clock.value.plusSeconds(3601)

                val first = h.requestLatest()
                first.availability shouldBe ComparisonAvailability.UNAVAILABLE
                h.comparisonCache.saveCount shouldBe 1
                val updatesAfterFirst = h.comparisonCache.updateOhlcCount

                // The next request validates the small deferred remainder synchronously and
                // persists it, hitting — before the background remainder runs.
                val second = h.requestLatest()
                second.availability shouldBe ComparisonAvailability.AVAILABLE
                h.comparisonCache.updateOhlcCount shouldBe updatesAfterFirst + 1

                // The background remainder validates the same content, but the stored entry
                // is no longer the list it validated: it must abort without writing, so the
                // next request still hits with zero more replays.
                advanceUntilIdle()
                h.comparisonCache.updateOhlcCount shouldBe updatesAfterFirst + 1
                h.requestLatest().availability shouldBe ComparisonAvailability.AVAILABLE
                h.comparisonCache.saveCount shouldBe 1
            }
        }

        "regression: cancelled application scope releases the refresh marker for retry" {
            runTest {
                val cancelledScope = CoroutineScope(SupervisorJob().also { it.cancel() })
                val h = prepareOverBudgetHarness(backgroundScope = cancelledScope)
                h.clock.value = h.clock.value.plusSeconds(3601)

                // Launching on a cancelled scope never runs the block: the owner must not
                // hand it the marker, so the next request owns the window again instead of
                // joining a refresh that will never finish.
                val first = h.requestLatest()
                first.availability shouldBe ComparisonAvailability.UNAVAILABLE
                first.unavailableReason shouldBe ComparisonUnavailableReason.EXTERNAL_EVIDENCE_REFRESHING
                val afterFirst = h.ohlcCalls.get()

                val second = h.requestLatest()
                second.availability shouldBe ComparisonAvailability.UNAVAILABLE
                second.unavailableReason shouldBe ComparisonUnavailableReason.EXTERNAL_EVIDENCE_REFRESHING
                (h.ohlcCalls.get() - afterFirst) shouldBeGreaterThan 0
            }
        }

        "regression: scope cancelled before background dispatch releases the refresh marker" {
            runTest {
                // A paused scheduler of our own: cancelling it before dispatch runs is the
                // deterministic twin of cancellation racing the launch.
                val backgroundScope = TestScope()
                val h = prepareOverBudgetHarness(backgroundScope = backgroundScope)
                h.clock.value = h.clock.value.plusSeconds(3601)

                val first = h.requestLatest()
                first.availability shouldBe ComparisonAvailability.UNAVAILABLE
                h.comparisonCache.saveCount shouldBe 1
                val afterFirst = h.ohlcCalls.get()

                // The background block never runs, but once the scheduler processes the
                // cancellation the completion hook still releases the marker: the next
                // request owns the window instead of joining forever.
                backgroundScope.cancel()
                backgroundScope.advanceUntilIdle()
                val second = h.requestLatest()
                second.availability shouldBe ComparisonAvailability.UNAVAILABLE
                second.unavailableReason shouldBe ComparisonUnavailableReason.EXTERNAL_EVIDENCE_REFRESHING
                (h.ohlcCalls.get() - afterFirst) shouldBeGreaterThan 0
            }
        }

        "regression: failed background refresh retries on later requests without replays" {
            runTest {
                val backing = InMemoryComparisonCache()
                var updates = 0
                var conditionalUpdates = 0
                val throwingCache = object : RebalancerComparisonCacheRepository by backing {
                    override suspend fun updateOhlcDependencies(
                        fromEpochMillis: Long,
                        toEpochMillis: Long,
                        ohlcDependencies: List<ConsumedOhlcDependency>,
                    ) {
                        updates++
                        backing.updateOhlcDependencies(fromEpochMillis, toEpochMillis, ohlcDependencies)
                    }

                    override suspend fun updateOhlcDependenciesIfExpected(
                        fromEpochMillis: Long,
                        toEpochMillis: Long,
                        expectedOhlcDependencies: List<ConsumedOhlcDependency>,
                        ohlcDependencies: List<ConsumedOhlcDependency>,
                    ): Boolean {
                        conditionalUpdates++
                        if (conditionalUpdates == 1) error("simulated persistence failure")
                        return backing.updateOhlcDependenciesIfExpected(
                            fromEpochMillis,
                            toEpochMillis,
                            expectedOhlcDependencies,
                            ohlcDependencies,
                        )
                    }
                }
                val h = prepareOverBudgetHarness(
                    backgroundScope = this,
                    comparisonCache = backing,
                    serviceCache = throwingCache,
                )
                // Eight days clears even the 7-day historical consumed-window TTL, so every
                // dependency expires at once and the refresh stays over budget.
                h.clock.value = h.clock.value.plusSeconds(8 * 86_400L)

                // First request: bounded batch persists (update 1), background remainder
                // validates but its final conditional write fails (throws and is logged).
                val first = h.requestLatest()
                first.availability shouldBe ComparisonAvailability.UNAVAILABLE
                first.unavailableReason shouldBe ComparisonUnavailableReason.EXTERNAL_EVIDENCE_REFRESHING
                advanceUntilIdle()
                updates shouldBe 1
                conditionalUpdates shouldBe 1
                (h.ohlcCalls.get() - h.coldOhlcCalls) shouldBeLessThanOrEqual h.distinctKeys

                // Next request re-batches (still over budget) with zero new live calls
                // (every range is already exact-fresh in memory) and queues the
                // background write again.
                val before = h.ohlcCalls.get()
                val second = h.requestLatest()
                second.availability shouldBe ComparisonAvailability.UNAVAILABLE
                h.ohlcCalls.get() shouldBe before
                advanceUntilIdle()
                updates shouldBe 2
                conditionalUpdates shouldBe 2

                // A later request hits: zero replays throughout the failure and retry.
                h.requestLatest().availability shouldBe ComparisonAvailability.AVAILABLE
                h.ohlcCalls.get() shouldBe before
                backing.saveCount shouldBe 1
                (h.ohlcCalls.get() - h.coldOhlcCalls) shouldBe h.distinctKeys
            }
        }

        "regression: provider outage during over-budget refresh retries without replays" {
            runTest {
                val h = prepareOverBudgetHarness(backgroundScope = this)
                // Eight days clears even the 7-day historical consumed-window TTL, so every
                // dependency expires at once and the refresh stays over budget.
                h.clock.value = h.clock.value.plusSeconds(8 * 86_400L)
                h.failAll.value = true

                // Every live call fails: the batch serves stale without persisting progress
                // (nothing changed), and the background remainder skips its final write for
                // the same reason instead of persisting a content-identical reorder.
                val first = h.requestLatest()
                first.availability shouldBe ComparisonAvailability.UNAVAILABLE
                first.unavailableReason shouldBe ComparisonUnavailableReason.EXTERNAL_EVIDENCE_REFRESHING
                advanceUntilIdle()
                h.comparisonCache.updateOhlcCount shouldBe 0
                h.comparisonCache.saveCount shouldBe 1

                // The provider recovers past the failure pacing window: the next requests
                // validate for real within budget, then a later request hits with no replays.
                h.failAll.value = false
                h.clock.value = h.clock.value.plusSeconds(3601)
                val second = h.requestLatest()
                second.availability shouldBe ComparisonAvailability.UNAVAILABLE
                advanceUntilIdle()
                h.requestLatest().availability shouldBe ComparisonAvailability.AVAILABLE
                h.comparisonCache.saveCount shouldBe 1
                // One failed attempt during the outage (later ranges skip via failure pacing)
                // plus one successful refresh per range after recovery, nothing more.
                (h.ohlcCalls.get() - h.coldOhlcCalls) shouldBe 1 + h.distinctKeys
            }
        }

        "regression: concurrent requests on changed OHLC evidence replay exactly once" {
            runTest {
                val database = DatabaseConfig.init(":memory:")
                val ohlcRepository = SqliteHistoricalOhlcRepositoryImpl(database)
                val mapper = jacksonObjectMapper().apply {
                    registerModule(JavaTimeModule())
                    disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                }
                val durableCache = SqliteRebalancerComparisonCacheRepositoryImpl(database, mapper)
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00", usdBalance = "50000.00")
                val snap2 = snapshot(now.plusSeconds(3600), "50000.00", btc = "1.0" to "0.00", usdBalance = "50000.00")
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSyncMetadata(any()) } coAnswers { CERTIFIED_COVERAGE_DEFAULTS[firstArg()] }
                coEvery { ledgerRepository.getSyncMetadata(any()) } coAnswers
                    { CERTIFIED_COVERAGE_DEFAULTS[firstArg()] }
                coEvery { orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any()) } returns
                    RebalancerOrderIdentities()

                var clock = now.plusSeconds(7200)
                var btcClose = "50000"
                val kraken = FakeKrakenService().apply {
                    ohlcSupplier = { _, _, _ ->
                        listOf((snap2.timestamp.epochSecond - 900L) to BigDecimal(btcClose))
                    }
                }
                val countingCache = CountingComparisonCache(durableCache)
                val queryService = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    krakenService = kraken,
                    historicalOhlcCache = HistoricalOhlcCache(
                        kraken,
                        persistentRepository = ohlcRepository,
                        nowProvider = { clock },
                    ),
                    comparisonCacheRepository = countingCache,
                    nowProvider = { clock },
                )

                queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                countingCache.saveCount shouldBe 1

                // The consumed candle corrects past expiry; concurrent requests share the single
                // invalidation and the single authoritative replay.
                clock = clock.plusSeconds(3601)
                btcClose = "55000"
                val results = withContext(Dispatchers.IO) {
                    (1..4).map {
                        async { queryService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp) }
                    }.awaitAll()
                }
                results.forEach { it.availability shouldBe ComparisonAvailability.AVAILABLE }
                countingCache.saveCount shouldBe 2
            }
        }

        "consumed evidence digest binds predecessor and row content changes" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(now.plusSeconds(3600), "105000.00", btc = "1.1" to "50000.00")
                val predecessor = snapshot(now.minusSeconds(86_400), "99000.00", btc = "0.99" to "50000.00")
                val staking = ledgerEvent("STAKE-1", now.plusSeconds(1800), "BTC", "0.05")
                val stakingTie = ledgerEvent("STAKE-2", now.plusSeconds(1800), "BTC", "0.05")
                var tradeFee = "0"
                fun dryRunTrade(id: Int?) = TradeRecord(
                    id = id,
                    pair = "BTCUSD",
                    symbol = "BTC",
                    side = "SELL",
                    timestamp = now.plusSeconds(600),
                    volume = BigDecimal("0.01"),
                    usdAmount = BigDecimal("500.00"),
                    success = true,
                    dryRun = true,
                    price = BigDecimal("50000.00"),
                    fee = BigDecimal(tradeFee),
                    source = TradeSource.MANUAL,
                    tradeId = "T$id",
                    orderTxid = null,
                    cycleId = null,
                    clientOrderId = null,
                )
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSnapshotBefore(any()) } returns predecessor
                // Two same-instant trades exercise the digest's deterministic tie-break.
                coEvery { repository.getTradesInRange(any(), any()) } coAnswers {
                    listOf(dryRunTrade(1), dryRunTrade(null))
                }
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns listOf(staking, stakingTie)
                val cache = InMemoryComparisonCache()
                val cachedService = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    comparisonCacheRepository = cache,
                )

                cachedService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cachedService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.saveCount shouldBe 1
                cache.loadCount shouldBe 2

                // Editing a consumed row's content (the dry-run trade's fee), committed with
                // its revision bump, replays exactly once and re-caches.
                tradeFee = "1"
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.COMPARISON_EVIDENCE_REVISION)
                } returns "7"
                cachedService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.saveCount shouldBe 2
                cache.loadCount shouldBe 3
                cachedService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
                cache.saveCount shouldBe 2
                cache.loadCount shouldBe 4
            }
        }

        "degraded funding provenance results are never cached" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(now.plusSeconds(3600), "100000.00", btc = "1.0" to "50000.00")
                val cache = InMemoryComparisonCache()
                val degradedResolver = mockk<FundingProvenanceResolver>()
                coEvery { degradedResolver.resolve(any()) } returns FundingEvidence.UNRESOLVED
                coEvery { degradedResolver.isCardFunding(any()) } returns false
                coEvery { degradedResolver.explain(any()) } returns null
                coEvery { degradedResolver.diagnose(any()) } returns null
                coEvery { degradedResolver.prepare(any()) } coAnswers { degradedResolver }
                coEvery { degradedResolver.evidenceFingerprint } returns "prepared-token"
                coEvery { degradedResolver.preparationFailure } returns FundingProvenanceFailure(
                    reason = FundingProvenanceFailureReason.REQUEST_FAILED,
                    message = "funding status request failed",
                )
                val cachedService = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    fundingProvenanceResolver = degradedResolver,
                    comparisonCacheRepository = cache,
                )
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                // Degraded provenance fails the comparison closed, and an unavailable outcome
                // is never cached: repeated requests keep replaying, never rehydrating.
                cachedService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.UNAVAILABLE
                cachedService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.UNAVAILABLE
                cache.loadCount shouldBe 2
                cache.saveCount shouldBe 0
            }
        }

        "comparison cache failures leave the authoritative calculation available" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(now.plusSeconds(3600), "100000.00", btc = "1.0" to "50000.00")
                val cachedService = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    comparisonCacheRepository = ThrowingComparisonCache(),
                )
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                cachedService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp).availability shouldBe
                    ComparisonAvailability.AVAILABLE
            }
        }

        "getRebalancerComparison_UsesProductionFundingResolver" {
            runTest {
                val snap1 = snapshot(
                    now,
                    "1000.00",
                    btc = "0.0" to "50000.00",
                    usdBalance = "1000.00",
                )
                val snap2 = snapshot(
                    now.plusSeconds(3600),
                    "1100.00",
                    btc = "0.0" to "50000.00",
                    usdBalance = "1100.00",
                )
                val deposit = ledgerEvent(
                    ledgerId = "LIVE-DEPOSIT",
                    timestamp = now.plusSeconds(1800),
                    asset = Asset.USD,
                    amount = "100.00",
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    refid = "LIVE-DEPOSIT-REF",
                )
                val kraken = FakeKrakenService().apply {
                    depositStatusSupplier = { _, _ ->
                        listOf(
                            DepositStatusRecord(
                                refid = "LIVE-DEPOSIT-REF",
                                asset = Asset.USD,
                                amount = BigDecimal("100.00"),
                                time = deposit.time,
                                status = "Success",
                                method = "Wire",
                            ),
                        )
                    }
                }
                val productionBoundService = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    fundingProvenanceResolver = KrakenFundingProvenanceResolver(kraken),
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns listOf(deposit)

                val comparison = productionBoundService.getRebalancerComparison(Instant.EPOCH, snap2.timestamp)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.confidence shouldBe ComparisonConfidence.RECONCILED
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1100.00"))
                kraken.getDepositStatusCallCount shouldBe 1
                kraken.getWithdrawStatusCallCount shouldBe 0
                kraken.getInternalTransfersCallCount shouldBe 1
            }
        }

        "getRebalancerComparison_DurableOrderTxidIntentProvesBotOwnership" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 =
                    snapshot(now.plusSeconds(3600), "112000.00", btc = "1.2" to "60000.00", usdBalance = "40000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                val trade = TradeRecord(
                    id = 1,
                    pair = "BTCUSD",
                    symbol = "BTC",
                    side = "BUY",
                    timestamp = now.plusSeconds(1800),
                    volume = BigDecimal("0.2"),
                    usdAmount = BigDecimal("10000.00"),
                    success = true,
                    dryRun = false,
                    price = BigDecimal("50000.00"),
                    fee = BigDecimal.ZERO,
                    source = TradeSource.API_FILL,
                    tradeId = "T1",
                    orderTxid = "BOT-ORDER-1",
                    cycleId = null,
                    clientOrderId = null,
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(trade)
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any()) } returns
                    RebalancerOrderIdentities(orderTxids = setOf("BOT-ORDER-1"))

                val comparison = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.confidence shouldBe ComparisonConfidence.RECONCILED
                // Because trade is bot-owned (REBALANCER), it is NOT replayed into Buy & Hold.
                // Buy & Hold stays 1.0 BTC + 50k USD valued at 60k = 110,000. Actual = 1.2 BTC @ 60k + 40k USD = 112,000.
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("110000.00"))
            }
        }

        "getRebalancerComparison_FetchesTerminalLateFillWithinClockSkew" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val last = now.plusSeconds(3600)
                val snap2 = snapshot(last, "100000.00", btc = "1.2" to "50000.00", usdBalance = "40000.00")
                val trade = TradeRecord(
                    id = 1,
                    pair = "BTCUSD",
                    symbol = "BTC",
                    side = "BUY",
                    timestamp = last.plusMillis(500),
                    volume = BigDecimal("0.2"),
                    usdAmount = BigDecimal("10000.00"),
                    success = true,
                    dryRun = false,
                    price = BigDecimal("50000.00"),
                    fee = BigDecimal.ZERO,
                    source = TradeSource.API_FILL,
                    tradeId = "TERMINAL-LATE-FILL",
                    orderTxid = "TERMINAL-LATE-ORDER",
                    cycleId = null,
                    clientOrderId = null,
                )
                val queriedTradesTo = mutableListOf<Instant>()
                val queriedLedgersTo = mutableListOf<Instant>()
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } answers {
                    queriedTradesTo += secondArg<Instant>()
                    listOf(trade)
                }
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } answers {
                    queriedLedgersTo += secondArg<Instant>()
                    emptyList()
                }
                coEvery { orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any()) } returns
                    RebalancerOrderIdentities(orderTxids = setOf("TERMINAL-LATE-ORDER"))

                val comparison = service.getRebalancerComparison(Instant.EPOCH, last.plusSeconds(1))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.confidence shouldBe ComparisonConfidence.RECONCILED
                queriedTradesTo.contains(last.plusMillis(1_000)) shouldBe true
                queriedLedgersTo.contains(last.plusMillis(1_000)) shouldBe true
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("100000.00"))
            }
        }

        "getRebalancerComparison_QueriesFromAnchorSnapshotWhenAvailable" {
            runTest {
                val anchorTime = now.minusSeconds(3600)
                val anchor = snapshot(anchorTime, "100000.00", btc = "1.0" to "50000.00", usdBalance = "50000.00")
                val snap1 = snapshot(now, "100000.00", btc = "1.2" to "50000.00", usdBalance = "40000.00")
                val snap2 =
                    snapshot(now.plusSeconds(3600), "100000.00", btc = "1.2" to "50000.00", usdBalance = "40000.00")

                // Trade executed just after baseline observation time due to clock skew, but was in snap1 balances
                val trade = TradeRecord(
                    id = 1,
                    pair = "BTCUSD",
                    symbol = "BTC",
                    side = "BUY",
                    timestamp = now.plusMillis(250),
                    volume = BigDecimal("0.2"),
                    usdAmount = BigDecimal("10000.00"),
                    success = true,
                    dryRun = false,
                    price = BigDecimal("50000.00"),
                    fee = BigDecimal.ZERO,
                    source = TradeSource.API_FILL,
                    tradeId = "T-SKEW",
                    orderTxid = "O-SKEW",
                    cycleId = null,
                    clientOrderId = null,
                )

                val queriedTradesFrom = mutableListOf<Instant>()
                val queriedLedgersFrom = mutableListOf<Instant>()
                coEvery { repository.getSnapshotBefore(now) } returns anchor
                coEvery { repository.getSnapshotsInRange(now, now.plusSeconds(3600)) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } answers {
                    queriedTradesFrom += firstArg<Instant>()
                    listOf(trade)
                }
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } answers {
                    queriedLedgersFrom += firstArg<Instant>()
                    emptyList()
                }
                coEvery { orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any()) } returns
                    RebalancerOrderIdentities(orderTxids = setOf("O-SKEW"))

                val comparison = service.getRebalancerComparison(now, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.confidence shouldBe ComparisonConfidence.RECONCILED
                queriedTradesFrom.contains(anchorTime) shouldBe true
                queriedLedgersFrom.contains(anchorTime) shouldBe true
                comparison.points.size shouldBe 2
                comparison.baselineTimestamp shouldBe now
                comparison.points[0].timestamp shouldBe now
                // Assert trade is not replayed into Buy & Hold after baseline
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("100000.00"))
            }
        }

        "getRebalancerComparison_ExpandsEventQueryForLegacyObservationRows" {
            runTest {
                val snap1 = snapshot(
                    now,
                    "100000.00",
                    btc = "1.0" to "50000.00",
                    balancesObservedAt = null,
                )
                val snap2 = snapshot(
                    now.plusSeconds(3600),
                    "105000.00",
                    btc = "1.1" to "50000.00",
                    usdBalance = "50000.00",
                    balancesObservedAt = null,
                )
                val boundaryLedger = ledgerEvent("LEGACY-BOUNDARY", now.minusMillis(500), "BTC", "0.1")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSnapshotBefore(now) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery {
                    ledgerRepository.getLedgersInRange(
                        now.minusMillis(1_000),
                        now.plusSeconds(3600).plusMillis(1_000),
                    )
                } returns listOf(boundaryLedger)

                val comparison = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                // The legacy boundary row predates the recorded baseline and must not be
                // injected into the passive basket. The recorded BTC holding is still repriced
                // from 50,000 to 50,000 here, so the later 1.1 BTC snapshot is 105,000.
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("105000.00"))
                coVerify(exactly = 1) {
                    repository.getTradesInRange(now.minusMillis(1_000), now.plusSeconds(3600).plusMillis(1_000))
                }
                coVerify(exactly = 1) {
                    ledgerRepository.getLedgersInRange(now.minusMillis(1_000), now.plusSeconds(3600).plusMillis(1_000))
                }
            }
        }

        "getRebalancerComparison_TradeIdentityDoesNotAffectPureBuyAndHold" {
            runTest {
                // Baseline snapshot at T+0 (now)
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                // Subsequent snapshot at T+3600 with a higher BTC price, so a replayed trade
                // would visibly drift the synthetic basket.
                val snap2 =
                    snapshot(now.plusSeconds(3600), "111000.00", btc = "1.1" to "60000.00", usdBalance = "45000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                // Trade at T+1800 with an order identity that would previously have been resolved
                // against a local order intent. Pure Buy & Hold must not need that classification.
                val trade = TradeRecord(
                    id = 1,
                    pair = "BTCUSD",
                    symbol = "BTC",
                    side = "BUY",
                    timestamp = now.plusSeconds(1800),
                    volume = BigDecimal("0.1"),
                    usdAmount = BigDecimal("5000.00"),
                    success = true,
                    dryRun = false,
                    price = BigDecimal("50000.00"),
                    fee = BigDecimal.ZERO,
                    source = TradeSource.API_FILL,
                    tradeId = "FILL-1",
                    orderTxid = "ORDER-TXID-1",
                    cycleId = "cycle-1",
                    clientOrderId = null,
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(trade)
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                val comparison = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.confidence shouldBe ComparisonConfidence.RECONCILED
                // The order identity is reconciliation evidence only; it creates no synthetic
                // trade, so the basket keeps 1.0 BTC repriced to 60,000 plus its cash.
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("110000.00"))
            }
        }

        "getRebalancerComparison_ManualTradeDoesNotAffectBuyAndHold" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 =
                    snapshot(now.plusSeconds(3600), "112000.00", btc = "1.2" to "60000.00", usdBalance = "40000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                val trade = TradeRecord(
                    id = 1,
                    pair = "BTCUSD",
                    symbol = "BTC",
                    side = "BUY",
                    timestamp = now.plusSeconds(1800),
                    volume = BigDecimal("0.2"),
                    usdAmount = BigDecimal("10000.00"),
                    success = true,
                    dryRun = false,
                    price = BigDecimal("50000.00"),
                    fee = BigDecimal.ZERO,
                    source = TradeSource.MANUAL,
                    tradeId = "MANUAL-T1",
                    orderTxid = "MANUAL-O1",
                    cycleId = null,
                    clientOrderId = null,
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(trade)
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any()) } returns
                    RebalancerOrderIdentities(orderTxids = emptySet())

                val comparison = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.confidence shouldBe ComparisonConfidence.RECONCILED
                // Pure Buy & Hold retains initial portfolio holdings
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("110000.00"))
                comparison.points.last().differenceUSD.shouldBeEqualComparingTo(BigDecimal("2000.00"))
            }
        }

        "getRebalancerComparison_UnknownTradeDoesNotFailOrAffectBuyAndHold" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 =
                    snapshot(now.plusSeconds(3600), "112000.00", btc = "1.2" to "60000.00", usdBalance = "40000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                val trade = TradeRecord(
                    id = 1,
                    pair = "BTCUSD",
                    symbol = "BTC",
                    side = "BUY",
                    timestamp = now.plusSeconds(1800),
                    volume = BigDecimal("0.2"),
                    usdAmount = BigDecimal("10000.00"),
                    success = true,
                    dryRun = false,
                    price = BigDecimal("50000.00"),
                    fee = BigDecimal.ZERO,
                    source = TradeSource.LEGACY_UNKNOWN,
                    tradeId = null,
                    orderTxid = null,
                    cycleId = null,
                    clientOrderId = null,
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(trade)
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.confidence shouldBe ComparisonConfidence.RECONCILED
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("110000.00"))
                comparison.points.last().differenceUSD.shouldBeEqualComparingTo(BigDecimal("2000.00"))
            }
        }

        "getRebalancerComparison_ReturnsUnavailableWhenLessThanTwoSnapshots" {
            runTest {
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(
                    snapshot(now, "100000.00", btc = "1.0" to "50000.00"),
                )
                val comparison = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(3600))
                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe
                    ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS
            }
        }

        "getRebalancerComparison_HandlesNullOrderIntentRepositoryAndBlankIdentifiers" {
            runTest {
                val serviceNoIntent = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = null,
                )
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(now.plusSeconds(3600), "100000.00", btc = "1.0" to "50000.00")
                val tradeWithBlankIds = TradeRecord(
                    id = 1,
                    pair = "BTCUSD",
                    symbol = "BTC",
                    side = "BUY",
                    timestamp = now.plusSeconds(1800),
                    volume = BigDecimal.ZERO,
                    usdAmount = BigDecimal.ZERO,
                    success = false,
                    dryRun = false,
                    price = BigDecimal.ZERO,
                    fee = BigDecimal.ZERO,
                    tradeId = "   ",
                    orderTxid = "   ",
                    cycleId = null,
                    clientOrderId = "   ",
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(tradeWithBlankIds)
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = serviceNoIntent.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(3600))
                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.confidence shouldBe ComparisonConfidence.RECONCILED
            }
        }

        "getRebalancerComparison withholds comparison when all snapshots lie beyond certified coverage" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(
                    now.plusSeconds(3600),
                    "100000.00",
                    btc = "1.0" to "50000.00",
                    usdBalance = "50000.00",
                    balancesObservedAt = now.plusSeconds(7200),
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    (now.epochSecond - 1).toString()
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    (now.epochSecond - 1).toString()

                val comparison = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(7200))

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS
            }
        }

        "getRebalancerComparison requires coverage from an explicit inception before the retention floor" {
            runTest {
                val inceptionTime = now.minusSeconds(86400)
                val retentionFloor = now
                val inceptionSnapshot = snapshot(inceptionTime, "100000.00", btc = "0" to "0", usdBalance = "100000.00")
                val retained = listOf(
                    snapshot(retentionFloor, "100000.00", btc = "0" to "0", usdBalance = "100000.00"),
                    snapshot(
                        retentionFloor.plusSeconds(3600),
                        "100000.00",
                        btc = "0" to "0",
                        usdBalance = "100000.00",
                    ),
                )
                val inceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { inceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = inceptionTime,
                    inceptionSnapshot = inceptionSnapshot,
                    isAutoDetected = false,
                )
                coEvery { repository.getAllSnapshotsInRange(any(), any()) } returns retained
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns retained
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) } returns
                    retentionFloor.epochSecond.toString()
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) } returns
                    retentionFloor.epochSecond.toString()
                val serviceWithFloor = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = inceptionService,
                    benchmarkHistoryFloor = retentionFloor,
                )

                val comparison = serviceWithFloor.getRebalancerComparison(
                    retentionFloor,
                    retentionFloor.plusSeconds(3600),
                )

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS
            }
        }

        "getRebalancerComparison evaluates stable prefix and skips live tail until coverage catches up" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(
                    now.plusSeconds(3600),
                    "100000.00",
                    btc = "1.0" to "50000.00",
                    usdBalance = "50000.00",
                    balancesObservedAt = now.plusSeconds(3600),
                )
                // Uncertified live tail snapshot: balance changed but trade/ledger sync hasn't arrived
                val liveTailSnap = snapshot(
                    now.plusSeconds(7200),
                    "105000.00",
                    btc = "1.1" to "50000.00",
                    usdBalance = "50000.00",
                    balancesObservedAt = now.plusSeconds(7200),
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2, liveTailSnap)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                // Certified coverage only extends to snap2
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    (now.epochSecond + 3600).toString()
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    (now.epochSecond + 3600).toString()

                val comparisonStable = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(7200))

                // Stable prefix reconciles cleanly without failing on live tail
                comparisonStable.availability shouldBe ComparisonAvailability.AVAILABLE
                comparisonStable.baselineTimestamp shouldBe now
                comparisonStable.points.size shouldBe 2

                // Coverage catches up to liveTailSnap (without trades this balance change will now be evaluated)
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    (now.epochSecond + 7200).toString()
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    (now.epochSecond + 7200).toString()

                val comparisonCaughtUp = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(7200))
                // Now evaluated: because no trade was added for the BTC balance jump, it fails with UNEXPLAINED_BALANCE_CHANGE
                comparisonCaughtUp.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparisonCaughtUp.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            }
        }

        "getRebalancerComparison defers when snapshot observations are non-monotonic relative to coverage" {
            runTest {
                val snap1 = snapshot(
                    now,
                    "100000.00",
                    btc = "1.0" to "50000.00",
                    balancesObservedAt = now,
                )
                val snap2 = snapshot(
                    now.plusSeconds(3600),
                    "100000.00",
                    btc = "1.0" to "50000.00",
                    balancesObservedAt = now.plusSeconds(7200),
                )
                val snap3 = snapshot(
                    now.plusSeconds(7200),
                    "100000.00",
                    btc = "1.0" to "50000.00",
                    balancesObservedAt = now.plusSeconds(1800),
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2, snap3)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    (now.epochSecond + 3600).toString()
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    (now.epochSecond + 3600).toString()

                val comparison = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(7200))
                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS
                comparison.unavailableAt shouldBe snap2.timestamp
            }
        }

        "getRebalancerComparison fails closed when trade coverage horizon is missing" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(
                    now.plusSeconds(3600),
                    "100000.00",
                    btc = "1.0" to "50000.00",
                    usdBalance = "50000.00",
                    balancesObservedAt = now.plusSeconds(3600),
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    null

                val comparison = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS
                comparison.points.shouldBeEmpty()
            }
        }

        "getRebalancerComparison fails closed when ledger coverage horizon is missing" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(
                    now.plusSeconds(3600),
                    "100000.00",
                    btc = "1.0" to "50000.00",
                    usdBalance = "50000.00",
                    balancesObservedAt = now.plusSeconds(3600),
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    null

                val comparison = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS
                comparison.points.shouldBeEmpty()
            }
        }

        "getRebalancerComparison fails closed when trade coverage horizon is malformed" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(
                    now.plusSeconds(3600),
                    "100000.00",
                    btc = "1.0" to "50000.00",
                    usdBalance = "50000.00",
                    balancesObservedAt = now.plusSeconds(3600),
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "not-a-number"

                val comparison = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS
                comparison.points.shouldBeEmpty()
            }
        }

        "getRebalancerComparison fails closed when ledger coverage horizon is malformed" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(
                    now.plusSeconds(3600),
                    "100000.00",
                    btc = "1.0" to "50000.00",
                    usdBalance = "50000.00",
                    balancesObservedAt = now.plusSeconds(3600),
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    "not-a-number"

                val comparison = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS
                comparison.points.shouldBeEmpty()
            }
        }

        "getRebalancerComparison never evaluates uncertified live tail when coverage is missing" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val liveTailSnap = snapshot(
                    now.plusSeconds(3600),
                    "105000.00",
                    btc = "1.1" to "50000.00",
                    usdBalance = "50000.00",
                    balancesObservedAt = now.plusSeconds(3600),
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, liveTailSnap)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) } returns
                    null

                val comparison = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(3600))

                // Uncertified evidence must not produce UNEXPLAINED_BALANCE_CHANGE or an AVAILABLE verdict
                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS
                comparison.points.shouldBeEmpty()
            }
        }

        "getRebalancerComparison_IdentifiesBotTradeViaClientOrderId" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 =
                    snapshot(now.plusSeconds(3600), "112000.00", btc = "1.2" to "60000.00", usdBalance = "40000.00")
                val trade = TradeRecord(
                    id = 1,
                    pair = "BTCUSD",
                    symbol = "BTC",
                    side = "BUY",
                    timestamp = now.plusSeconds(1800),
                    volume = BigDecimal("0.2"),
                    usdAmount = BigDecimal("10000.00"),
                    success = true,
                    dryRun = false,
                    price = BigDecimal("50000.00"),
                    fee = BigDecimal.ZERO,
                    source = TradeSource.API_FILL,
                    tradeId = null,
                    orderTxid = null,
                    cycleId = null,
                    clientOrderId = "CLIENT-ORDER-99",
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(trade)
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery {
                    orderIntentRepository.getKnownRebalancerOrderIdentities(any(), setOf("CLIENT-ORDER-99"))
                } returns RebalancerOrderIdentities(
                    orderTxids = setOf("BOT-ORDER-1"),
                )

                val comparison = service.getRebalancerComparison(Instant.EPOCH, now.plusSeconds(3600))
                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.confidence shouldBe ComparisonConfidence.RECONCILED
            }
        }

        "getRebalancerComparison_UsesInceptionDiscoveryServiceWhenProvided" {
            runTest {
                val inceptionTime = now.minusSeconds(86400 * 30)
                val snapInception =
                    snapshot(inceptionTime, "80000.00", btc = "1.0" to "40000.00", usdBalance = "40000.00")
                // Inception-resolution path applies only when no provably recorded anchor exists:
                // unobserved rows never qualify as the passive re-anchor baseline.
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00", balancesObservedAt = null)
                val snap2 =
                    snapshot(now.plusSeconds(3600), "110000.00", btc = "1.0" to "60000.00", balancesObservedAt = null)

                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = inceptionTime,
                    inceptionSnapshot = snapInception,
                    isAutoDetected = true,
                )

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = serviceWithInception.getRebalancerComparison(now, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe inceptionTime
                comparison.points.size shouldBe 2
            }
        }

        "getRebalancerComparison_ResolvesSnapshotBeforeWhenInceptionSnapshotIsNull" {
            runTest {
                val inceptionTime = now.minusSeconds(86400 * 10)
                val snapInception = snapshot(
                    inceptionTime,
                    "85000.00",
                    btc = "1.0" to "45000.00",
                    usdBalance = "40000.00",
                    balancesObservedAt = null,
                )
                // Inception-resolution path applies only when no provably recorded anchor exists:
                // unobserved rows never qualify as the passive re-anchor baseline.
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00", balancesObservedAt = null)
                val snap2 =
                    snapshot(now.plusSeconds(3600), "110000.00", btc = "1.0" to "60000.00", balancesObservedAt = null)

                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = inceptionTime,
                    inceptionSnapshot = null,
                    isAutoDetected = true,
                )
                coEvery { repository.getSnapshotBefore(any()) } returns snapInception
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                // Bounded inception fallback queries [inception-300s, inception+30s];
                // answer with the true inception snapshot, not the window snapshots.
                coEvery {
                    repository.getSnapshotsInRange(
                        inceptionTime.minusSeconds(300),
                        inceptionTime.plusSeconds(30),
                    )
                } returns listOf(snapInception)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                val comparison = serviceWithInception.getRebalancerComparison(now, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe inceptionTime
                comparison.points.size shouldBe 2
            }
        }

        "getRebalancerComparison_OwnerDeposit PricedFromRecordedSnapshots" {
            runTest {
                val t0 = now.minusSeconds(86400 * 30)
                val tMid = now.plusSeconds(1800)
                val snap0 = snapshot(
                    t0,
                    "100000.00",
                    btc = "1.0" to "50000.00",
                    usdBalance = "50000.00",
                    balancesObservedAt = t0,
                )
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00", usdBalance = "50000.00")
                val snap2 = snapshot(
                    now.plusSeconds(3600),
                    "110000.00",
                    btc = "1.0" to "50000.00",
                    usdBalance = "60000.00",
                )

                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = t0,
                    inceptionSnapshot = snap0,
                    isAutoDetected = true,
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns
                    listOf(
                        ledgerEvent(
                            ledgerId = "qs-deposit-1",
                            timestamp = tMid,
                            asset = "USD",
                            amount = "10000.00",
                            type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        ),
                    )

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    fundingProvenanceResolver = SimpleFundingProvenanceResolver(
                        deposits = listOf(
                            DepositStatusRecord(
                                refid = "FT-qs-deposit-1",
                                asset = "USD",
                                amount = BigDecimal("10000.00"),
                                time = tMid,
                                status = "Success",
                                method = "Wire",
                            ),
                        ),
                    ),
                )

                val comparison = serviceWithInception.getRebalancerComparison(now, now.plusSeconds(3600))

                // $10k allocated by 50/50 inception weights at recorded 50k
                // BTC: no artificial alpha at flat prices.
                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("110000.00")
            }
        }

        "getRebalancerComparison_OwnerCryptoDeposit_PricedFromRecordedSnapshots" {
            runTest {
                val t0 = now.minusSeconds(86400 * 30)
                val tMid = now.plusSeconds(1800)
                val snap0 = snapshot(
                    t0,
                    "100000.00",
                    btc = "1.0" to "50000.00",
                    usdBalance = "50000.00",
                    balancesObservedAt = t0,
                )
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00", usdBalance = "50000.00")
                val snap2 = snapshot(
                    now.plusSeconds(3600),
                    "125000.00",
                    btc = "1.5" to "50000.00",
                    usdBalance = "50000.00",
                )

                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = t0,
                    inceptionSnapshot = snap0,
                    isAutoDetected = true,
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns
                    listOf(
                        ledgerEvent(
                            ledgerId = "qs-deposit-btc",
                            timestamp = tMid,
                            asset = "BTC",
                            amount = "0.50000000",
                            type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        ),
                    )

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    fundingProvenanceResolver = SimpleFundingProvenanceResolver(
                        deposits = listOf(
                            DepositStatusRecord(
                                refid = "tx-qs-deposit-btc",
                                txid = "0xbtc123",
                                asset = "BTC",
                                amount = BigDecimal("0.50000000"),
                                time = tMid,
                                status = "Success",
                                method = "Bitcoin",
                            ),
                        ),
                    ),
                )

                val comparison = serviceWithInception.getRebalancerComparison(now, now.plusSeconds(3600))
                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("125000.00")
            }
        }

        "getRebalancerComparison_OwnerCryptoDeposit_UsesSharedHistoricalLadderWhenGatewayAvailable" {
            runTest {
                val t0 = now.minusSeconds(86400 * 30)
                val tMid = now.plusSeconds(1800)
                val snap0 = snapshot(
                    t0,
                    "100000.00",
                    btc = "1.0" to "50000.00",
                    usdBalance = "50000.00",
                    balancesObservedAt = t0,
                )
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00", usdBalance = "50000.00")
                val snap2 = snapshot(
                    now.plusSeconds(3600),
                    "125000.00",
                    btc = "1.5" to "50000.00",
                    usdBalance = "50000.00",
                )
                val nearContributionSnapshot = snap1.copy(
                    timestamp = tMid.minusSeconds(60),
                    balancesObservedAt = tMid.minusSeconds(60),
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = t0,
                    inceptionSnapshot = snap0,
                    isAutoDetected = true,
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } answers {
                    if (secondArg<Instant>() == tMid) {
                        listOf(nearContributionSnapshot)
                    } else {
                        listOf(snap1, snap2)
                    }
                }
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns
                    listOf(
                        ledgerEvent(
                            ledgerId = "qs-ladder-btc",
                            timestamp = tMid,
                            asset = "BTC",
                            amount = "0.50000000",
                            type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        ),
                    )

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    fundingProvenanceResolver = SimpleFundingProvenanceResolver(
                        deposits = listOf(
                            DepositStatusRecord(
                                refid = "tx-qs-ladder-btc",
                                txid = "0xladder123",
                                asset = "BTC",
                                amount = BigDecimal("0.50000000"),
                                time = tMid,
                                status = "Success",
                                method = "Bitcoin",
                            ),
                        ),
                    ),
                    krakenService = mockk<KrakenService>(relaxed = true),
                )

                val comparison = serviceWithInception.getRebalancerComparison(now, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("125000.00")
            }
        }

        "getRebalancerComparison_KrakenGatewayOutageFallsBackToRecordedSnapshots" {
            runTest {
                val t0 = now.minusSeconds(86400 * 30)
                val tMid = now.plusSeconds(1800)
                val snap0 = snapshot(
                    t0,
                    "100000.00",
                    btc = "1.0" to "50000.00",
                    usdBalance = "50000.00",
                    balancesObservedAt = t0,
                )
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00", usdBalance = "50000.00")
                val snap2 = snapshot(
                    now.plusSeconds(3600),
                    "125000.00",
                    btc = "1.5" to "50000.00",
                    usdBalance = "50000.00",
                )
                val outageGateway = mockk<KrakenService>(relaxed = true)
                coEvery { outageGateway.getOHLC(any(), any(), any()) } throws
                    IllegalStateException("gateway outage")
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = t0,
                    inceptionSnapshot = snap0,
                    isAutoDetected = true,
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns
                    listOf(
                        ledgerEvent(
                            ledgerId = "qs-outage-btc",
                            timestamp = tMid,
                            asset = "BTC",
                            amount = "0.50000000",
                            type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        ),
                    )

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    fundingProvenanceResolver = SimpleFundingProvenanceResolver(
                        deposits = listOf(
                            DepositStatusRecord(
                                refid = "tx-qs-outage-btc",
                                txid = "0xoutage123",
                                asset = "BTC",
                                amount = BigDecimal("0.50000000"),
                                time = tMid,
                                status = "Success",
                                method = "Bitcoin",
                            ),
                        ),
                    ),
                    krakenService = outageGateway,
                )

                val comparison = serviceWithInception.getRebalancerComparison(now, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("125000.00")
            }
        }

        "getRebalancerComparison_UnpriceableOwnerCryptoDepositFailsClosed" {
            runTest {
                val t0 = now.minusSeconds(86400 * 30)
                val tMid = now.plusSeconds(1800)
                val snap0 = snapshot(
                    t0,
                    "100000.00",
                    btc = "1.0" to "50000.00",
                    usdBalance = "50000.00",
                    balancesObservedAt = t0,
                )
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00", usdBalance = "50000.00")
                val snap2 = snapshot(
                    now.plusSeconds(3600),
                    "100000.00",
                    btc = "1.0" to "50000.00",
                    usdBalance = "50000.00",
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = t0,
                    inceptionSnapshot = snap0,
                    isAutoDetected = true,
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns
                    listOf(
                        ledgerEvent(
                            ledgerId = "qs-xlm-deposit",
                            timestamp = tMid,
                            asset = "XLM",
                            amount = "1.00000000",
                            type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                        ),
                    )

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    fundingProvenanceResolver = SimpleFundingProvenanceResolver(
                        deposits = listOf(
                            DepositStatusRecord(
                                refid = "tx-qs-xlm-deposit",
                                txid = "0xxlm123",
                                asset = "XLM",
                                amount = BigDecimal("1.00000000"),
                                time = tMid,
                                status = "Success",
                                method = "Bitcoin",
                            ),
                        ),
                    ),
                )

                val comparison = serviceWithInception.getRebalancerComparison(now, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.MISSING_PRICE

                val outageGateway = mockk<KrakenService>(relaxed = true)
                coEvery { outageGateway.getOHLC(any(), any(), any()) } throws
                    IllegalStateException("historical source outage")
                val outageService = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    fundingProvenanceResolver = SimpleFundingProvenanceResolver(
                        deposits = listOf(
                            DepositStatusRecord(
                                refid = "tx-qs-xlm-deposit",
                                txid = "0xxlm123",
                                asset = "XLM",
                                amount = BigDecimal("1.00000000"),
                                time = tMid,
                                status = "Success",
                                method = "Bitcoin",
                            ),
                        ),
                    ),
                    krakenService = outageGateway,
                )

                val outageComparison = outageService.getRebalancerComparison(now, now.plusSeconds(3600))

                outageComparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                outageComparison.unavailableReason shouldBe ComparisonUnavailableReason.HISTORICAL_PRICE_SOURCE_ERROR
            }
        }

        "getRebalancerComparison_RejectsFutureObservedPriceCandidates" {
            runTest {
                val t0 = now.minusSeconds(86400 * 30)
                val tMid = now.plusSeconds(1800)
                val snap0 = snapshot(
                    t0,
                    "100000.00",
                    btc = "1.0" to "50000.00",
                    usdBalance = "50000.00",
                    balancesObservedAt = t0,
                )
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00", usdBalance = "50000.00")
                val snap2 = snapshot(
                    now.plusSeconds(3600),
                    "125000.00",
                    btc = "1.5" to "50000.00",
                    usdBalance = "50000.00",
                )
                val futureObserved = snap1.copy(
                    timestamp = tMid.minusSeconds(60),
                    balancesObservedAt = tMid.plusSeconds(1),
                    assets = snap1.assets.mapValues { (symbol, asset) ->
                        if (symbol == Asset.BTC) asset.copy(price = BigDecimal("99999.00")) else asset
                    },
                )
                val missingAsset = snap1.copy(
                    timestamp = tMid.minusSeconds(90),
                    assets = snap1.assets - Asset.BTC,
                )
                val validPrice = snap1.copy(
                    timestamp = tMid.minusSeconds(120),
                    balancesObservedAt = tMid.minusSeconds(120),
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = t0,
                    inceptionSnapshot = snap0,
                    isAutoDetected = true,
                )
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns listOf(
                    ledgerEvent(
                        ledgerId = "future-observation-deposit",
                        timestamp = tMid,
                        asset = Asset.BTC,
                        amount = "0.50000000",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    ),
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } answers {
                    if (secondArg<Instant>() == tMid) {
                        listOf(futureObserved, missingAsset, validPrice)
                    } else {
                        listOf(snap1, snap2)
                    }
                }

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    fundingProvenanceResolver = SimpleFundingProvenanceResolver(
                        deposits = listOf(
                            DepositStatusRecord(
                                refid = "tx-future-observation-deposit",
                                txid = "0xfuture-observation",
                                asset = Asset.BTC,
                                amount = BigDecimal("0.50000000"),
                                time = tMid,
                                status = "Success",
                                method = "Bitcoin",
                            ),
                        ),
                    ),
                )

                val comparison = serviceWithInception.getRebalancerComparison(now, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.points[1].buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("125000.00")
            }
        }

        "getRebalancerComparison_TruncatedHistoryReturnsInformativeUnavailable" {
            runTest {
                val snap1 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(now.plusSeconds(3600), "100000.00", btc = "1.0" to "50000.00")

                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400 * 100),
                    inceptionSnapshot = null,
                    isAutoDetected = true,
                    confidence = InceptionConfidence.TRUNCATED,
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                val comparison = serviceWithInception.getRebalancerComparison(now, now.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.INCEPTION_HISTORY_TRUNCATED
            }
        }

        "getRebalancerComparison_ReanchorsAtEarliestRecordedInvestedSnapshot" {
            runTest {
                val anchorTime = Instant.EPOCH.plusSeconds(300)
                val laterTime = anchorTime.plusSeconds(3600)
                val reconstructedAtFloor = snapshot(
                    Instant.EPOCH,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = null,
                )
                val recordedAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns
                    listOf(recordedAnchor, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(reconstructedAtFloor, recordedAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                val comparison = serviceWithInception.getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe anchorTime
                comparison.points.first().timestamp shouldBe anchorTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1100.00"))
                coVerify(exactly = 1) {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                }
            }
        }

        "getRebalancerComparison_SkipsCashInvalidAndPricelessCandidatesForFirstInvestedAnchor" {
            runTest {
                val cashTime = Instant.EPOCH.plusSeconds(1000)
                val invalidTime = Instant.EPOCH.plusSeconds(2000)
                val pricelessTime = Instant.EPOCH.plusSeconds(3000)
                val anchorTime = Instant.EPOCH.plusSeconds(4000)
                val laterTime = anchorTime.plusSeconds(3600)
                val allCash = snapshot(
                    cashTime,
                    "900.00",
                    btc = "0.0" to "450.00",
                    usdBalance = "900.00",
                )
                val invalidBalances = snapshot(
                    invalidTime,
                    "1000.00",
                    btc = "-1.0" to "500.00",
                    usdBalance = "1500.00",
                )
                val pricelessCrypto = snapshot(
                    pricelessTime,
                    "500.00",
                    btc = "1.0" to "0.00",
                    usdBalance = "500.00",
                )
                val investedAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "400.00",
                )
                val later = snapshot(
                    laterTime,
                    "1000.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "400.00",
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = cashTime,
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns
                    listOf(investedAnchor, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(allCash, invalidBalances, pricelessCrypto, investedAnchor, later)
                coEvery {
                    repository.getAllSnapshotsInRange(cashTime, laterTime)
                } returns listOf(allCash, invalidBalances, pricelessCrypto, investedAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                val comparison = serviceWithInception.getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe anchorTime
                comparison.points.first().timestamp shouldBe anchorTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
            }
        }

        "getRebalancerComparison_WindowStartingAfterGlobalAnchorRediscoversInWindowAnchor" {
            runTest {
                val preWindowAnchorTime = Instant.EPOCH.plusSeconds(100)
                val windowFrom = Instant.EPOCH.plusSeconds(1000)
                val windowAnchorTime = Instant.EPOCH.plusSeconds(2000)
                val laterTime = windowAnchorTime.plusSeconds(3600)
                val preWindowAnchor = snapshot(
                    preWindowAnchorTime,
                    "1000.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "400.00",
                )
                val windowAnchor = snapshot(
                    windowAnchorTime,
                    "2000.00",
                    btc = "1.0" to "1200.00",
                    usdBalance = "800.00",
                )
                val later = snapshot(
                    laterTime,
                    "2200.00",
                    btc = "1.0" to "1400.00",
                    usdBalance = "800.00",
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(windowAnchor, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(preWindowAnchor, windowAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(windowFrom, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe windowAnchorTime
                comparison.points.first().timestamp shouldBe windowAnchorTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("2000.00"))
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("2200.00"))
            }
        }

        "getRebalancerComparison_AnchorsFirstMaterialPositionEvenWhenCashDominated" {
            runTest {
                val dustTime = Instant.EPOCH.plusSeconds(100)
                val materialTime = Instant.EPOCH.plusSeconds(200)
                val laterTime = materialTime.plusSeconds(3600)
                val dust = snapshot(
                    dustTime,
                    "1000.00",
                    btc = "0.000002" to "89000.00",
                    usdBalance = "999.822",
                )
                val material = snapshot(
                    materialTime,
                    "1000.00",
                    btc = "0.001" to "100000.00",
                    usdBalance = "900.00",
                )
                val later = snapshot(
                    laterTime,
                    "1010.00",
                    btc = "0.001" to "110000.00",
                    usdBalance = "900.00",
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(dust, material, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(dust, material, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(dustTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe materialTime
                comparison.points.first().timestamp shouldBe materialTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1010.00"))
            }
        }

        "getRebalancerComparison_MalformedSupportedTradeFailsClosed" {
            runTest {
                val materialTime = Instant.EPOCH.plusSeconds(200)
                val tradeTime = materialTime.plusSeconds(600)
                val laterTime = materialTime.plusSeconds(3600)
                val material = snapshot(
                    materialTime,
                    "2000.00",
                    btc = "1.0" to "1200.00",
                    usdBalance = "800.00",
                )
                val later = snapshot(
                    laterTime,
                    "2200.00",
                    btc = "1.0" to "1400.00",
                    usdBalance = "800.00",
                )
                // Replayable BTCUSD pair but malformed economics: must fail closed, never a zero fill.
                val malformed = TradeRecord(
                    id = 1,
                    pair = "BTCUSD",
                    symbol = "BTC",
                    side = "BUY",
                    timestamp = tradeTime,
                    volume = BigDecimal.ZERO,
                    usdAmount = BigDecimal.ZERO,
                    success = true,
                    dryRun = false,
                    price = BigDecimal.ZERO,
                    fee = BigDecimal.ZERO,
                    source = TradeSource.API_FILL,
                    tradeId = "MALFORMED-1",
                    orderTxid = "O-MALFORMED-1",
                    cycleId = null,
                    clientOrderId = null,
                    hasValidVolume = false,
                )
                // Dry-run, failed, and out-of-window rows are not economic evidence: the scan
                // must skip them (repositories return recorded rows; the scan owns the window).
                val dryRunTrade = malformed.copy(
                    id = 2,
                    dryRun = true,
                    tradeId = "MALFORMED-2",
                    orderTxid = "O-MALFORMED-2",
                )
                val failedTrade = malformed.copy(
                    id = 3,
                    success = false,
                    tradeId = "MALFORMED-3",
                    orderTxid = "O-MALFORMED-3",
                )
                val preWindowTrade = malformed.copy(
                    id = 4,
                    timestamp = materialTime.minusSeconds(60),
                    tradeId = "MALFORMED-4",
                    orderTxid = "O-MALFORMED-4",
                )
                val postWindowTrade = malformed.copy(
                    id = 5,
                    timestamp = laterTime.plusSeconds(60),
                    tradeId = "MALFORMED-5",
                    orderTxid = "O-MALFORMED-5",
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(material, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(material, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns
                    listOf(dryRunTrade, failedTrade, preWindowTrade, malformed, postWindowTrade)
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(materialTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_TRADE
                comparison.unavailableAt shouldBe tradeTime
            }
        }

        "getRebalancerComparison_SkipsRecordedPricelessCryptoWithNoPricedExposure" {
            runTest {
                val materialTime = Instant.EPOCH.plusSeconds(200)
                val pricelessTime = materialTime.plusSeconds(1)
                val laterTime = materialTime.plusSeconds(3600)
                val priceless = snapshot(
                    pricelessTime,
                    "1000.00",
                    btc = "1.0" to "0",
                    usdBalance = "1000.00",
                )
                val material = snapshot(
                    materialTime,
                    "1000.00",
                    btc = "0.001" to "100000.00",
                    usdBalance = "900.00",
                )
                val later = snapshot(
                    laterTime,
                    "1010.00",
                    btc = "0.001" to "110000.00",
                    usdBalance = "900.00",
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(material, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(material, priceless, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(materialTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe materialTime
                comparison.points.first().timestamp shouldBe materialTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1010.00"))
            }
        }

        "getRebalancerComparison_AnchorsSnapshotWithZeroBalanceRowFirst" {
            runTest {
                val materialTime = Instant.EPOCH.plusSeconds(200)
                val laterTime = materialTime.plusSeconds(3600)
                // Row order must not affect anchor eligibility: a zero-balance row listed before
                // the invested rows still anchors when the state as a whole is invested.
                val base = snapshot(
                    materialTime,
                    "910.00",
                    btc = "0.0" to "100000.00",
                    usdBalance = "900.00",
                )
                val btcKey = base.assets.keys.single { Asset.normalizeLedgerAsset(it).uppercase() == "BTC" }
                val usdKey = base.assets.keys.single { Asset.normalizeLedgerAsset(it).uppercase() == Asset.USD }
                val ethRow = TestFixtures.assetSnapshot(
                    symbol = "ETH",
                    balance = BigDecimal("0.1"),
                    price = BigDecimal("100"),
                    valueUSD = BigDecimal("10.00"),
                    targetPercent = BigDecimal.ZERO,
                )
                val orderedAssets = linkedMapOf(
                    btcKey to base.assets.getValue(btcKey),
                    "ETH" to ethRow,
                    usdKey to base.assets.getValue(usdKey),
                )
                val zeroFirst = base.copy(assets = orderedAssets)
                val laterBase = snapshot(
                    laterTime,
                    "910.00",
                    btc = "0.0" to "100000.00",
                    usdBalance = "900.00",
                )
                val later = laterBase.copy(assets = orderedAssets)
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(zeroFirst, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(zeroFirst, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(materialTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe materialTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("910.00"))
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("910.00"))
            }
        }

        "getRebalancerComparison_SkipsZeroStateSnapshotWithNoHoldings" {
            runTest {
                val materialTime = Instant.EPOCH.plusSeconds(200)
                val zeroTime = materialTime.plusSeconds(1)
                val laterTime = materialTime.plusSeconds(3600)
                val zeroState = snapshot(
                    zeroTime,
                    "0.00",
                    btc = "0" to "100000.00",
                    usdBalance = "0.00",
                )
                val material = snapshot(
                    materialTime,
                    "1000.00",
                    btc = "0.001" to "100000.00",
                    usdBalance = "900.00",
                )
                val later = snapshot(
                    laterTime,
                    "1010.00",
                    btc = "0.001" to "110000.00",
                    usdBalance = "900.00",
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(material, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(material, zeroState, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(materialTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe materialTime
                comparison.points.first().timestamp shouldBe materialTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1010.00"))
            }
        }

        "getRebalancerComparison_SkipsSnapshotWhoseTotalContradictsItsHoldings" {
            runTest {
                val materialTime = Instant.EPOCH.plusSeconds(200)
                val corruptTime = materialTime.plusSeconds(1)
                val laterTime = materialTime.plusSeconds(3600)
                val corruptTotal = snapshot(
                    corruptTime,
                    "0.00",
                    btc = "0.001" to "100000.00",
                    usdBalance = "900.00",
                )
                val material = snapshot(
                    materialTime,
                    "1000.00",
                    btc = "0.001" to "100000.00",
                    usdBalance = "900.00",
                )
                val later = snapshot(
                    laterTime,
                    "1010.00",
                    btc = "0.001" to "110000.00",
                    usdBalance = "900.00",
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(material, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(material, corruptTotal, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(materialTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe materialTime
                comparison.points.first().timestamp shouldBe materialTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1010.00"))
            }
        }

        "getRebalancerComparison_SkipsSnapshotWithNegativeBalanceHolding" {
            runTest {
                val materialTime = Instant.EPOCH.plusSeconds(200)
                val corruptTime = materialTime.plusSeconds(1)
                val laterTime = materialTime.plusSeconds(3600)
                val negativeHolding = corruptHoldingSnapshot(
                    timestamp = corruptTime,
                    symbol = "ATOM",
                    balance = "-5",
                    price = "1",
                    valueUSD = "-5",
                )
                val material = snapshot(
                    materialTime,
                    "1000.00",
                    btc = "0.001" to "100000.00",
                    usdBalance = "900.00",
                )
                val later = snapshot(
                    laterTime,
                    "1010.00",
                    btc = "0.001" to "110000.00",
                    usdBalance = "900.00",
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(material, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(material, negativeHolding, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(materialTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe materialTime
                comparison.points.first().timestamp shouldBe materialTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1010.00"))
            }
        }

        "getRebalancerComparison_SkipsSnapshotWithNegativeValueHolding" {
            runTest {
                val materialTime = Instant.EPOCH.plusSeconds(200)
                val corruptTime = materialTime.plusSeconds(1)
                val laterTime = materialTime.plusSeconds(3600)
                val negativeValue = corruptHoldingSnapshot(
                    timestamp = corruptTime,
                    symbol = "ATOM",
                    balance = "5",
                    price = "1",
                    valueUSD = "-5",
                )
                val material = snapshot(
                    materialTime,
                    "1000.00",
                    btc = "0.001" to "100000.00",
                    usdBalance = "900.00",
                )
                val later = snapshot(
                    laterTime,
                    "1010.00",
                    btc = "0.001" to "110000.00",
                    usdBalance = "900.00",
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(material, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(material, negativeValue, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(materialTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe materialTime
                comparison.points.first().timestamp shouldBe materialTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1010.00"))
            }
        }

        "getRebalancerComparison_SkipsSnapshotWithPhantomValueAndZeroBalances" {
            runTest {
                val materialTime = Instant.EPOCH.plusSeconds(200)
                val corruptTime = materialTime.plusSeconds(1)
                val laterTime = materialTime.plusSeconds(3600)
                val phantomValue = PortfolioSnapshot(
                    timestamp = corruptTime,
                    totalValueUSD = BigDecimal("100.00"),
                    assets = mapOf(
                        Asset.BTC to TestFixtures.assetSnapshot(
                            symbol = Asset.BTC,
                            balance = BigDecimal.ZERO,
                            price = BigDecimal("100000"),
                            valueUSD = BigDecimal("100.00"),
                            targetPercent = BigDecimal.ZERO,
                        ),
                    ),
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                    balancesObservedAt = corruptTime,
                )
                val material = snapshot(
                    materialTime,
                    "1000.00",
                    btc = "0.001" to "100000.00",
                    usdBalance = "900.00",
                )
                val later = snapshot(
                    laterTime,
                    "1010.00",
                    btc = "0.001" to "110000.00",
                    usdBalance = "900.00",
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(material, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(material, phantomValue, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(materialTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe materialTime
                comparison.points.first().timestamp shouldBe materialTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1010.00"))
            }
        }

        "getRebalancerComparison_SkipsProvablyRecordedAllZeroSnapshot" {
            runTest {
                val materialTime = Instant.EPOCH.plusSeconds(200)
                val zeroTime = materialTime.plusSeconds(1)
                val laterTime = materialTime.plusSeconds(3600)
                // Provably recorded (observation strictly before the row instant) yet
                // holding nothing: reaches the balance gate and must be skipped, so a
                // recorded-but-empty row can never become the benchmark anchor.
                val allZero = PortfolioSnapshot(
                    timestamp = zeroTime,
                    totalValueUSD = BigDecimal.ZERO,
                    assets = mapOf(
                        Asset.BTC to TestFixtures.assetSnapshot(
                            symbol = Asset.BTC,
                            balance = BigDecimal.ZERO,
                            price = BigDecimal("100000"),
                            valueUSD = BigDecimal.ZERO,
                            targetPercent = BigDecimal.ZERO,
                        ),
                    ),
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                    balancesObservedAt = zeroTime.minusMillis(1),
                )
                val material = snapshot(
                    materialTime,
                    "1000.00",
                    btc = "0.001" to "100000.00",
                    usdBalance = "900.00",
                )
                val later = snapshot(
                    laterTime,
                    "1010.00",
                    btc = "0.001" to "110000.00",
                    usdBalance = "900.00",
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(material, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(material, allZero, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(materialTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe materialTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1010.00"))
            }
        }

        "getRebalancerComparison_AnchorsSnapshotWithZeroRowBeforePositiveRows" {
            runTest {
                val materialTime = Instant.EPOCH.plusSeconds(200)
                val laterTime = materialTime.plusSeconds(3600)
                val base = snapshot(
                    materialTime,
                    "900.00",
                    btc = "0.0" to "100000.00",
                    usdBalance = "900.00",
                )
                // A zero-balance row listed before invested rows must not affect discovery:
                // row order is a serialization accident, not portfolio meaning.
                val zeroFirst = base.copy(
                    totalValueUSD = BigDecimal("910.00"),
                    assets = linkedMapOf(
                        Asset.BTC to TestFixtures.assetSnapshot(
                            symbol = Asset.BTC,
                            balance = BigDecimal.ZERO,
                            price = BigDecimal("100000"),
                            valueUSD = BigDecimal.ZERO,
                            targetPercent = BigDecimal.ZERO,
                        ),
                        "ETH" to TestFixtures.assetSnapshot(
                            symbol = "ETH",
                            balance = BigDecimal("0.1"),
                            price = BigDecimal("100.00"),
                            valueUSD = BigDecimal("10.00"),
                            targetPercent = BigDecimal.ZERO,
                        ),
                        Asset.USD to TestFixtures.assetSnapshot(
                            symbol = Asset.USD,
                            balance = BigDecimal("900.00"),
                            price = BigDecimal.ONE,
                            valueUSD = BigDecimal("900.00"),
                            targetPercent = BigDecimal.ZERO,
                        ),
                    ),
                )
                val later = base.copy(
                    timestamp = laterTime,
                    totalValueUSD = BigDecimal("910.00"),
                    assets = zeroFirst.assets,
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(zeroFirst, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(zeroFirst, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(materialTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe materialTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("910.00"))
            }
        }

        "getRebalancerComparison_SkipsSnapshotWithPriceValueInconsistency" {
            runTest {
                val materialTime = Instant.EPOCH.plusSeconds(200)
                val corruptTime = materialTime.plusSeconds(1)
                val laterTime = materialTime.plusSeconds(3600)
                val inconsistentPrice = corruptHoldingSnapshot(
                    timestamp = corruptTime,
                    symbol = "ATOM",
                    balance = "600",
                    price = "0",
                    valueUSD = "600",
                )
                val material = snapshot(
                    materialTime,
                    "1000.00",
                    btc = "0.001" to "100000.00",
                    usdBalance = "900.00",
                )
                val later = snapshot(
                    laterTime,
                    "1010.00",
                    btc = "0.001" to "110000.00",
                    usdBalance = "900.00",
                )
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(material, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        Instant.EPOCH,
                        openEndedRangeEnd,
                    )
                } returns listOf(material, inconsistentPrice, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = service.getRebalancerComparison(materialTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe materialTime
                comparison.points.first().timestamp shouldBe materialTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1010.00"))
            }
        }

        "getRebalancerComparison_DoesNotAnchorBeforeHistoryBoundEvenWhenEarlierHistoryIsClean" {
            runTest {
                val floor = Instant.EPOCH
                val cleanEarly = snapshot(
                    floor.minusSeconds(86400),
                    "900.00",
                    btc = "1.0" to "450.00",
                    usdBalance = "450.00",
                )
                val anchorTime = floor.plusSeconds(300)
                val laterTime = anchorTime.plusSeconds(3600)
                val recordedAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = cleanEarly.timestamp,
                    inceptionSnapshot = cleanEarly,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.CONFIDENT,
                )
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns
                    listOf(recordedAnchor, later)
                coEvery {
                    repository.getAllSnapshotsInRange(
                        floor,
                        openEndedRangeEnd,
                    )
                } returns listOf(recordedAnchor, later)
                coEvery {
                    repository.getAllSnapshotsInRange(floor, laterTime)
                } returns listOf(recordedAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                val comparison = serviceWithInception.getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe anchorTime
                comparison.points.first().timestamp shouldBe anchorTime
                comparison.points.first().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                comparison.points.last().buyAndHoldValueUSD.shouldBeEqualComparingTo(BigDecimal("1100.00"))
                coVerify(exactly = 2) {
                    repository.getAllSnapshotsInRange(
                        floor,
                        openEndedRangeEnd,
                    )
                }
            }
        }

        "getRebalancerComparison_ExcludesLegacyDerivedRowsThroughTheirMetadataSecond" {
            runTest {
                // A non-zero reconstruction base: epoch-second metadata uses 0/blank as the
                // never-reconstructed sentinel, so the legacy window needs a real base instant.
                val base = Instant.parse("2026-01-01T00:00:00Z")
                val derivedTime = base.plusMillis(500)
                val anchorTime = base.plusSeconds(2)
                val laterTime = anchorTime.plusSeconds(3600)
                val legacyDerived = snapshot(
                    derivedTime,
                    "999.00",
                    btc = "1.0" to "499.00",
                    usdBalance = "500.00",
                )
                val recordedAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                )
                val metadata = mapOf(
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION to "legacy-reconstruction",
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_START_EPOCH_SEC to base.epochSecond.toString(),
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC to base.epochSecond.toString(),
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSyncMetadata(any()) } answers {
                    metadata[firstArg()] ?: CERTIFIED_COVERAGE_DEFAULTS[firstArg()]
                }
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns
                    listOf(recordedAnchor, later)
                coEvery {
                    repository.getAllSnapshotsInRange(Instant.EPOCH, openEndedRangeEnd)
                } returns listOf(legacyDerived, recordedAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                )

                val comparison = serviceWithInception.getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe anchorTime
                comparison.points.first().buyAndHoldValueUSD
                    .shouldBeEqualComparingTo(BigDecimal("1000.00"))
            }
        }

        "getRebalancerComparison_restart_reproduces_the_same_reanchor_result" {
            runTest {
                val floor = Instant.EPOCH
                val anchorTime = floor.plusSeconds(300)
                val laterTime = anchorTime.plusSeconds(3600)
                val liveAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = anchorTime.minusMillis(800),
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                    balancesObservedAt = laterTime.minusMillis(700),
                )
                val metadata = mapOf(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION to "7")
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSyncMetadata(any()) } answers {
                    metadata[firstArg()] ?: CERTIFIED_COVERAGE_DEFAULTS[firstArg()]
                }
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns
                    listOf(liveAnchor, later)
                coEvery { repository.getAllSnapshotsInRange(floor, openEndedRangeEnd) } returns
                    listOf(liveAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                fun newService() = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                )

                val beforeRestart = newService().getRebalancerComparison(anchorTime, laterTime)
                val afterRestart = newService().getRebalancerComparison(anchorTime, laterTime)

                afterRestart shouldBe beforeRestart
                afterRestart.availability shouldBe ComparisonAvailability.AVAILABLE
                afterRestart.baselineTimestamp shouldBe anchorTime
            }
        }

        "getRebalancerComparison_reconstructedRowsAnchorWhenTheReconstructionIsCurrent" {
            runTest {
                val floor = Instant.EPOCH
                val anchorTime = floor.plusSeconds(300)
                val laterTime = anchorTime.plusSeconds(3600)
                val reconstructedAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = null,
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                    balancesObservedAt = null,
                )
                val metadata = mapOf(
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION to
                        TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION,
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_LEDGER_COVERAGE_VERSION to
                        LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_TRADE_COVERAGE_VERSION to
                        TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION,
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_START_EPOCH_SEC to floor.epochSecond.toString(),
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC to laterTime.epochSecond.toString(),
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSyncMetadata(any()) } answers {
                    metadata[firstArg()] ?: CERTIFIED_COVERAGE_DEFAULTS[firstArg()]
                }
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns
                    listOf(reconstructedAnchor, later)
                coEvery { repository.getAllSnapshotsInRange(floor, openEndedRangeEnd) } returns
                    listOf(reconstructedAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                ).getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe anchorTime
                comparison.points.first().buyAndHoldValueUSD
                    .shouldBeEqualComparingTo(BigDecimal("1000.00"))
            }
        }

        "getRebalancerComparison_reconstructedRowsStayExcludedWhenTheReconstructionIsOutdated" {
            runTest {
                val floor = Instant.EPOCH
                val anchorTime = floor.plusSeconds(600)
                val laterTime = anchorTime.plusSeconds(3600)
                val reconstructedRow = snapshot(
                    floor.plusSeconds(300),
                    "900.00",
                    btc = "1.0" to "400.00",
                    usdBalance = "500.00",
                    balancesObservedAt = null,
                )
                val recordedAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = anchorTime.minusMillis(800),
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                    balancesObservedAt = laterTime.minusMillis(700),
                )
                val metadata = mapOf(
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION to "17",
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_LEDGER_COVERAGE_VERSION to
                        LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_TRADE_COVERAGE_VERSION to
                        TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION,
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_START_EPOCH_SEC to floor.epochSecond.toString(),
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC to
                        floor.plusSeconds(100).epochSecond.toString(),
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSyncMetadata(any()) } answers {
                    metadata[firstArg()] ?: CERTIFIED_COVERAGE_DEFAULTS[firstArg()]
                }
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns
                    listOf(recordedAnchor, later)
                coEvery { repository.getAllSnapshotsInRange(floor, openEndedRangeEnd) } returns
                    listOf(reconstructedRow, recordedAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                ).getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe anchorTime
            }
        }

        "getRebalancerComparison_reconstructedRowsOutsideTheRecordedWindowStayExcluded" {
            runTest {
                val floor = Instant.EPOCH
                val anchorTime = floor.plusSeconds(600)
                val laterTime = anchorTime.plusSeconds(3600)
                val legacyDerivedRow = snapshot(
                    floor.plusSeconds(50),
                    "800.00",
                    btc = "1.0" to "300.00",
                    usdBalance = "500.00",
                )
                val reconstructedRow = snapshot(
                    floor.plusSeconds(300),
                    "900.00",
                    btc = "1.0" to "400.00",
                    usdBalance = "500.00",
                    balancesObservedAt = null,
                )
                val recordedAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = anchorTime.minusMillis(800),
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                    balancesObservedAt = laterTime.minusMillis(700),
                )
                val metadata = mapOf(
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION to
                        TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION,
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_LEDGER_COVERAGE_VERSION to
                        LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_TRADE_COVERAGE_VERSION to
                        TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION,
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_START_EPOCH_SEC to floor.epochSecond.toString(),
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC to
                        floor.plusSeconds(100).epochSecond.toString(),
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSyncMetadata(any()) } answers {
                    metadata[firstArg()] ?: CERTIFIED_COVERAGE_DEFAULTS[firstArg()]
                }
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns
                    listOf(recordedAnchor, later)
                coEvery { repository.getAllSnapshotsInRange(floor, openEndedRangeEnd) } returns
                    listOf(legacyDerivedRow, reconstructedRow, recordedAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val comparison = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                ).getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe anchorTime
            }
        }

        "getRebalancerComparison_anchorSearchRejectsUnqualifiedRows" {
            runTest {
                val floor = Instant.EPOCH
                val anchorTime = floor.plusSeconds(300)
                val laterTime = anchorTime.plusSeconds(3600)

                fun custom(
                    timestamp: Instant,
                    totalValueUSD: String,
                    rows: Map<String, Triple<String, String, String>>,
                    observedAt: Instant?,
                ): PortfolioSnapshot = PortfolioSnapshot(
                    timestamp = timestamp,
                    totalValueUSD = BigDecimal(totalValueUSD),
                    assets = rows.mapValues { (symbol, row) ->
                        TestFixtures.assetSnapshot(
                            symbol = symbol,
                            balance = BigDecimal(row.first),
                            price = BigDecimal(row.second),
                            valueUSD = BigDecimal(row.third),
                            targetPercent = BigDecimal.ZERO,
                        )
                    },
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                    balancesObservedAt = observedAt,
                )

                val btcRow = Triple("1.00000000", "500.00", "500.00")
                val usdRow = Triple("500.00", "1.00", "500.00")
                val rejected = listOf(
                    custom(
                        floor.minusSeconds(1),
                        "1000.00",
                        mapOf("BTC" to btcRow, "USD" to usdRow),
                        floor.minusSeconds(2),
                    ),
                    custom(
                        now.plusSeconds(3600),
                        "1000.00",
                        mapOf("BTC" to btcRow, "USD" to usdRow),
                        now.plusSeconds(3500),
                    ),
                    custom(floor.plusSeconds(10), "0", mapOf("BTC" to btcRow, "USD" to usdRow), floor.plusSeconds(9)),
                    custom(
                        floor.plusSeconds(20),
                        "0",
                        mapOf("BTC" to Triple("0", "500.00", "0"), "USD" to Triple("0", "1.00", "0")),
                        floor.plusSeconds(19),
                    ),
                    custom(
                        floor.plusSeconds(30),
                        "1000.00",
                        mapOf("BTC" to btcRow, "USD" to Triple("-1.00", "1.00", "-1.00")),
                        floor.plusSeconds(29),
                    ),
                    custom(
                        floor.plusSeconds(40),
                        "1000.00",
                        mapOf("BTC" to btcRow, "USD" to Triple("1.00", "1.00", "-1.00")),
                        floor.plusSeconds(39),
                    ),
                    custom(
                        floor.plusSeconds(50),
                        "1500.00",
                        mapOf("BTC" to btcRow, "XRP" to Triple("2.00", "0", "0")),
                        floor.plusSeconds(49),
                    ),
                    custom(floor.plusSeconds(80), "1500.00", mapOf("BTC" to btcRow, "USD" to usdRow), null),
                )
                // Rows that pass every validity filter but sit after the anchor must not displace it.
                val zeroBalanceRow = custom(
                    anchorTime.plusSeconds(120),
                    "1000.00",
                    mapOf("BTC" to btcRow, "USD" to usdRow, "XRP" to Triple("0", "0", "0")),
                    anchorTime.plusSeconds(119),
                )
                val zeroPricedUsdRow = custom(
                    anchorTime.plusSeconds(180),
                    "1000.00",
                    mapOf("BTC" to btcRow, "USD" to Triple("500.00", "0", "500.00")),
                    anchorTime.plusSeconds(179),
                )
                val liveAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = anchorTime.minusMillis(800),
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                    balancesObservedAt = laterTime.minusMillis(700),
                )
                val metadata = mapOf(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION to "7")
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSyncMetadata(any()) } answers {
                    metadata[firstArg()] ?: CERTIFIED_COVERAGE_DEFAULTS[firstArg()]
                }
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns
                    listOf(liveAnchor, zeroBalanceRow, zeroPricedUsdRow, later)
                coEvery { repository.getAllSnapshotsInRange(floor, openEndedRangeEnd) } returns
                    rejected + listOf(liveAnchor, zeroBalanceRow, zeroPricedUsdRow, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                )

                val comparison = service.getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe anchorTime
                comparison.points.first().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1000.00")
            }
        }

        "getRebalancerComparison_reconstructionMetadataVariantsStayConsistent" {
            runTest {
                val floor = Instant.EPOCH
                val anchorTime = floor.plusSeconds(300)
                val laterTime = anchorTime.plusSeconds(3600)
                val liveAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = anchorTime.minusMillis(800),
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                    balancesObservedAt = laterTime.minusMillis(700),
                )
                var metadata: Map<String, String> = emptyMap()
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSyncMetadata(any()) } answers {
                    metadata[firstArg()] ?: CERTIFIED_COVERAGE_DEFAULTS[firstArg()]
                }
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns listOf(liveAnchor, later)
                coEvery { repository.getAllSnapshotsInRange(floor, openEndedRangeEnd) } returns
                    listOf(liveAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                )

                val versionKey = SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION
                val throughKey = SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC
                val startKey = SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_START_EPOCH_SEC
                val variants = listOf(
                    emptyMap(),
                    mapOf(throughKey to "abc"),
                    mapOf(versionKey to "", throughKey to "0"),
                    mapOf(versionKey to "7"),
                    mapOf(versionKey to "7", throughKey to "abc"),
                    mapOf(versionKey to "7", throughKey to "0"),
                    mapOf(versionKey to "7", throughKey to "100", startKey to "-5"),
                    mapOf(versionKey to "7", throughKey to "100", startKey to "200"),
                    mapOf(versionKey to "7", throughKey to "9223372036854775807", startKey to "100"),
                    mapOf(
                        versionKey to TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION,
                        SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_LEDGER_COVERAGE_VERSION to
                            LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
                        SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_TRADE_COVERAGE_VERSION to
                            TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION,
                        throughKey to "100",
                        startKey to "50",
                    ),
                )

                variants.forEach { variant ->
                    metadata = variant
                    val comparison = service.getRebalancerComparison(anchorTime, laterTime)
                    comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                    comparison.baselineTimestamp shouldBe anchorTime
                }
            }
        }

        "getRebalancerComparison_truncatedInceptionWithoutRecordedAnchorStaysUnavailable" {
            runTest {
                val floor = Instant.EPOCH
                val anchorTime = floor.plusSeconds(300)
                val laterTime = anchorTime.plusSeconds(3600)
                val liveAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = anchorTime.minusMillis(800),
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                    balancesObservedAt = laterTime.minusMillis(700),
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.TRUNCATED,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_HISTORY_TRUNCATED,
                )
                val metadata = emptyMap<String, String>()
                coEvery { repository.getSyncMetadata(any()) } answers {
                    metadata[firstArg()] ?: CERTIFIED_COVERAGE_DEFAULTS[firstArg()]
                }
                coEvery { repository.getAllSnapshotsInRange(floor, openEndedRangeEnd) } returns emptyList()
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns listOf(liveAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                )
                val comparison = service.getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.INCEPTION_HISTORY_TRUNCATED
            }
        }

        "getRebalancerComparison_anchorSearchSkipsCandidatesWithoutPositiveHoldings" {
            runTest {
                val floor = Instant.EPOCH
                val anchorTime = floor.plusSeconds(300)
                val laterTime = anchorTime.plusSeconds(3600)
                val emptyCandidate = snapshot(
                    floor.plusSeconds(10),
                    "1000.00",
                    btc = "0" to "500.00",
                    usdBalance = "0",
                    balancesObservedAt = floor.plusSeconds(9),
                )
                val liveAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = anchorTime.minusMillis(800),
                )
                val later = snapshot(
                    laterTime,
                    "1000.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                    balancesObservedAt = laterTime.minusMillis(700),
                )
                val metadata = mapOf(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION to "7")
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSyncMetadata(any()) } answers {
                    metadata[firstArg()] ?: CERTIFIED_COVERAGE_DEFAULTS[firstArg()]
                }
                coEvery { repository.getAllSnapshotsInRange(floor, openEndedRangeEnd) } returns
                    listOf(emptyCandidate, liveAnchor, later)
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns listOf(liveAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                )
                val comparison = service.getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe anchorTime
                comparison.points.last().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1100.00")
            }
        }

        "getRebalancerComparison_staleRowInsideTheDisplayedWindowFailsClosed" {
            runTest {
                val floor = Instant.EPOCH
                val anchorTime = floor.plusSeconds(300)
                val laterTime = anchorTime.plusSeconds(3600)
                val liveAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = anchorTime.minusMillis(800),
                )
                val unprovableRow = snapshot(
                    anchorTime.plusSeconds(120),
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                    balancesObservedAt = laterTime.minusMillis(700),
                )
                val metadata = mapOf(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION to "7")
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSyncMetadata(any()) } answers {
                    metadata[firstArg()] ?: CERTIFIED_COVERAGE_DEFAULTS[firstArg()]
                }
                coEvery { repository.getAllSnapshotsInRange(floor, openEndedRangeEnd) } returns
                    listOf(liveAnchor, unprovableRow, later)
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns
                    listOf(liveAnchor, unprovableRow, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                )
                val comparison = service.getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.HISTORICAL_COVERAGE_GAP
            }
        }

        "getRebalancerComparison does not rewrite unavailable reason when baseline is verified" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"

                val unprovableRow = snapshot(
                    fixture.anchorTime.plusSeconds(1800),
                    "200000.00",
                    btc = "1.0" to "50000.00",
                    usdBalance = "150000.00",
                )
                fixture.snapshotRows.add(1, unprovableRow)

                val comparison = service.getRebalancerComparison(fixture.anchorTime, fixture.laterTime)
                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
            }
        }

        "getRebalancerComparison_windowMetadataShapesStayConsistent" {
            runTest {
                val floor = Instant.EPOCH
                val anchorTime = floor.plusSeconds(300)
                val laterTime = anchorTime.plusSeconds(3600)
                val liveAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = anchorTime.minusMillis(800),
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                    balancesObservedAt = laterTime.minusMillis(700),
                )
                var metadata: Map<String, String> = emptyMap()
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSyncMetadata(any()) } answers {
                    metadata[firstArg()] ?: CERTIFIED_COVERAGE_DEFAULTS[firstArg()]
                }
                coEvery { repository.getAllSnapshotsInRange(floor, openEndedRangeEnd) } returns
                    listOf(liveAnchor, later)
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns listOf(liveAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                )
                val shapes = listOf(
                    mapOf(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC to "abc"),
                    mapOf(
                        SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION to "7",
                        SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC to "100",
                    ),
                    mapOf(
                        SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION to "",
                        SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC to "0",
                    ),
                )
                shapes.forEach { shape ->
                    metadata = shape
                    val comparison = service.getRebalancerComparison(anchorTime, laterTime)
                    comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                    comparison.baselineTimestamp shouldBe anchorTime
                }
            }
        }

        "getRebalancerComparison_truncatedInceptionStillUsesTheRecordedAnchor" {
            runTest {
                val floor = Instant.EPOCH
                val anchorTime = floor.plusSeconds(300)
                val laterTime = anchorTime.plusSeconds(3600)
                val liveAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = anchorTime.minusMillis(800),
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                    balancesObservedAt = laterTime.minusMillis(700),
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.TRUNCATED,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_HISTORY_TRUNCATED,
                )
                val metadata = emptyMap<String, String>()
                coEvery { repository.getSyncMetadata(any()) } answers {
                    metadata[firstArg()] ?: CERTIFIED_COVERAGE_DEFAULTS[firstArg()]
                }
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns listOf(liveAnchor, later)
                coEvery { repository.getAllSnapshotsInRange(floor, openEndedRangeEnd) } returns
                    listOf(liveAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                )
                val comparison = service.getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe anchorTime
                comparison.points.first().buyAndHoldValueUSD shouldBeEqualComparingTo BigDecimal("1000.00")
            }
        }

        "getRebalancerComparison_UnclassifiableLegacyWindowStillFindsLiveRecordedAnchor" {
            runTest {
                val floor = Instant.EPOCH
                val derivedTime = floor.plusSeconds(1)
                val anchorTime = floor.plusSeconds(300)
                val laterTime = anchorTime.plusSeconds(3600)
                val legacyDerived = snapshot(
                    derivedTime,
                    "999.00",
                    btc = "1.0" to "499.00",
                    usdBalance = "500.00",
                )
                val liveAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = anchorTime.minusMillis(800),
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                    balancesObservedAt = laterTime.minusMillis(700),
                )
                // Legacy v7 writers persisted a reconstruction marker without the window keys, so
                // derived rows can only be excluded through the live-observation signature.
                val metadata = mapOf(
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION to "7",
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSyncMetadata(any()) } answers {
                    metadata[firstArg()] ?: CERTIFIED_COVERAGE_DEFAULTS[firstArg()]
                }
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns
                    listOf(liveAnchor, later)
                coEvery { repository.getAllSnapshotsInRange(floor, openEndedRangeEnd) } returns
                    listOf(legacyDerived, liveAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                )

                val comparison = serviceWithInception.getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe anchorTime
                comparison.points.first().buyAndHoldValueUSD
                    .shouldBeEqualComparingTo(BigDecimal("1000.00"))
            }
        }

        "getRebalancerComparison_LiveSignatureRowInsideReconstructionWindowIsRecorded" {
            runTest {
                val floor = Instant.EPOCH
                val windowEnd = floor.plusSeconds(10)
                val anchorTime = floor.plusSeconds(5)
                val laterTime = floor.plusSeconds(3600)
                val derivedInsideWindow = snapshot(
                    floor.plusSeconds(1),
                    "999.00",
                    btc = "1.0" to "499.00",
                    usdBalance = "500.00",
                )
                val liveAnchor = snapshot(
                    anchorTime,
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = anchorTime.minusMillis(500),
                )
                val later = snapshot(
                    laterTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                )
                val metadata = mapOf(
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION to "7",
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_START_EPOCH_SEC to floor.epochSecond.toString(),
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC to windowEnd.epochSecond.toString(),
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = now.minusSeconds(86400),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSyncMetadata(any()) } answers {
                    metadata[firstArg()] ?: CERTIFIED_COVERAGE_DEFAULTS[firstArg()]
                }
                coEvery { repository.getSnapshotsInRange(anchorTime, laterTime) } returns
                    listOf(liveAnchor, later)
                coEvery { repository.getAllSnapshotsInRange(floor, openEndedRangeEnd) } returns
                    listOf(derivedInsideWindow, liveAnchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    nowProvider = { now },
                )

                val comparison = serviceWithInception.getRebalancerComparison(anchorTime, laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.baselineTimestamp shouldBe anchorTime
                comparison.points.first().buyAndHoldValueUSD
                    .shouldBeEqualComparingTo(BigDecimal("1000.00"))
            }
        }

        "getRebalancerComparison_PreservesPostAnchorFailureReason" {
            runTest {
                val oldInception = now.minusSeconds(90 * 86_400L)
                val anchorTime = Instant.EPOCH.plusSeconds(300)
                val anchor = snapshot(anchorTime, "1000.00", btc = "1.0" to "500.00", usdBalance = "500.00")
                val later = snapshot(
                    anchorTime.plusSeconds(3600),
                    "1500.00",
                    btc = "2.0" to "500.00",
                    usdBalance = "500.00",
                )
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = oldInception,
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )
                coEvery { repository.getSnapshotsInRange(anchorTime, anchorTime.plusSeconds(3600)) } returns
                    listOf(anchor, later)
                coEvery { repository.getAllSnapshotsInRange(any(), openEndedRangeEnd) } returns
                    listOf(anchor, later)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                val comparison = serviceWithInception.getRebalancerComparison(anchorTime, later.timestamp)

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
                comparison.baselineTimestamp shouldBe anchorTime
            }
        }

        "getRebalancerComparison_UnavailableBaseline_ProposesEarliestVerifiedLaterStart" {
            runTest {
                val t0 = now
                val t1 = now.plusSeconds(3600)
                val t2 = now.plusSeconds(7200)
                val snap0 = snapshot(t0, "90000.00", btc = "1.0" to "40000.00")
                val snap1 = snapshot(t1, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(t2, "110000.00", btc = "1.0" to "60000.00")

                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = t0,
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_BASELINE_UNAVAILABLE,
                )
                coEvery { repository.getSnapshotsInRange(t0, t2) } returns listOf(snap0, snap1, snap2)
                coEvery { repository.getSnapshotsInRange(t0, openEndedRangeEnd) } returns listOf(snap0, snap1, snap2)
                coEvery { repository.getSnapshotBefore(any()) } returns snap0
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                val comparison = serviceWithInception.getRebalancerComparison(t0, t2)

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.INCEPTION_BASELINE_UNAVAILABLE
                comparison.proposedBaselineTimestamp shouldBe t1
                comparison.proposalSearchStatus shouldBe ComparisonProposalStatus.VERIFIED
            }
        }

        "getRebalancerComparison_Proposal_IsIndependentOfDisplayWindow" {
            runTest {
                val t0 = now
                val t1 = now.plusSeconds(3600)
                val t2 = now.plusSeconds(7200)
                val snap0 = snapshot(t0, "90000.00", btc = "1.0" to "40000.00")
                val snap1 = snapshot(t1, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(t2, "110000.00", btc = "1.0" to "60000.00")
                val snap3 = snapshot(t2.plusSeconds(3600), "110000.00", btc = "1.0" to "60000.00")

                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = t0,
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_BASELINE_UNAVAILABLE,
                )
                coEvery {
                    repository.getSnapshotsInRange(t2.minusSeconds(60), t2.plusSeconds(3600))
                } returns listOf(snap2, snap3)
                coEvery { repository.getSnapshotsInRange(t0, openEndedRangeEnd) } returns
                    listOf(snap0, snap1, snap2, snap3)
                coEvery {
                    repository.getAllSnapshotsInRange(t0, t2.plusSeconds(3600))
                } returns listOf(snap0, snap1, snap2, snap3)
                coEvery { repository.getSnapshotBefore(any()) } returns snap0
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                // Display window excludes snap1 entirely, yet the verified
                // proposal is still its timestamp: the scan reads the full
                // retained snapshot range, not the displayed zoom range.
                val comparison =
                    serviceWithInception.getRebalancerComparison(t2.minusSeconds(60), t2.plusSeconds(3600))

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.proposedBaselineTimestamp shouldBe t1
                comparison.proposalSearchStatus shouldBe ComparisonProposalStatus.VERIFIED
            }
        }

        "getRebalancerComparison_PendingRecovery_ProposesNothing" {
            runTest {
                val t0 = now
                val t1 = now.plusSeconds(3600)
                val snap0 = snapshot(t0, "90000.00", btc = "1.0" to "40000.00")
                val snap1 = snapshot(t1, "100000.00", btc = "1.0" to "50000.00")

                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = t0,
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_RECOVERY_INCOMPLETE,
                )
                coEvery { repository.getSnapshotsInRange(t0, t1) } returns listOf(snap0, snap1)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                val comparison = serviceWithInception.getRebalancerComparison(t0, t1)

                comparison.availability shouldBe ComparisonAvailability.UNAVAILABLE
                comparison.unavailableReason shouldBe ComparisonUnavailableReason.INCEPTION_RECOVERY_INCOMPLETE
                comparison.proposedBaselineTimestamp.shouldBeNull()
                comparison.proposalSearchStatus.shouldBeNull()
            }
        }

        "findVerifiedLaterComparisonStart_ReturnsEarliestPassingAnchorOverFullRange" {
            runTest {
                val t0 = now
                val t1 = now.plusSeconds(3600)
                val t2 = now.plusSeconds(7200)
                val snap0 = snapshot(t0, "90000.00", btc = "1.0" to "40000.00")
                val snap1 = snapshot(t1, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(t2, "110000.00", btc = "1.0" to "60000.00")

                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = t0,
                    inceptionSnapshot = snap0,
                    isAutoDetected = false,
                )
                coEvery { repository.getAllSnapshotsInRange(t0, openEndedRangeEnd) } returns
                    listOf(snap0, snap1, snap2)
                // Keep the sampled chart query empty so this regression proves that the proposal
                // scanner uses the full-retention repository path.
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotBefore(any()) } returns snap0
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                serviceWithInception.findVerifiedLaterComparisonStart(t0) shouldBe t1
                coVerify(exactly = 1) { repository.getAllSnapshotsInRange(t0, openEndedRangeEnd) }
            }
        }

        "findVerifiedLaterComparisonStart_DoesNotTreatFutureStartAsCoverageGap" {
            runTest {
                val futureStart = now.plusSeconds(3600)
                val serviceWithClock = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    nowProvider = { now },
                )
                coEvery { repository.getAllSnapshotsInRange(futureStart, openEndedRangeEnd) } returns emptyList()

                serviceWithClock.findVerifiedLaterComparisonStart(futureStart).shouldBeNull()
            }
        }

        "findVerifiedLaterComparisonStart_DoesNotFlagMissingSnapshotsWhenStartIsWithinRetention" {
            runTest {
                val recentStart = now.minusSeconds(3600)
                val recentSnapshot = snapshot(recentStart, "90000.00", btc = "1.0" to "40000.00")
                val latestSnapshot = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val serviceWithClock = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    nowProvider = { now },
                )
                coEvery { repository.getAllSnapshotsInRange(recentStart, openEndedRangeEnd) } returns
                    listOf(recentSnapshot, latestSnapshot)
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotBefore(any()) } returns recentSnapshot
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                serviceWithClock.findVerifiedLaterComparisonStart(recentStart).shouldBeNull()
            }
        }

        "findVerifiedLaterComparisonStart_DoesNotFlagAnEmptyHistoricalRangeAsCoverageGap" {
            runTest {
                val historicalStart = now.minusSeconds(91 * 86_400L)
                val serviceWithClock = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    nowProvider = { now },
                )
                coEvery { repository.getAllSnapshotsInRange(historicalStart, openEndedRangeEnd) } returns emptyList()

                serviceWithClock.findVerifiedLaterComparisonStart(historicalStart).shouldBeNull()
            }
        }

        "findVerifiedLaterComparisonStart_FailsClosedForAGapCrossingRetentionBoundary" {
            runTest {
                val historicalStart = now.minusSeconds(100 * 86_400L)
                val retentionCutoff = now.minusSeconds(90 * 86_400L)
                val beforeStrategyStart = snapshot(
                    historicalStart.minusSeconds(3600),
                    "80000.00",
                    btc = "1.0" to "30000.00",
                )
                val oldSnapshot = snapshot(
                    retentionCutoff.minusSeconds(3600),
                    "90000.00",
                    btc = "1.0" to "40000.00",
                )
                val retainedSnapshot = snapshot(
                    retentionCutoff.plusSeconds(25 * 3600L),
                    "100000.00",
                    btc = "1.0" to "50000.00",
                )
                val afterNow = snapshot(
                    now.plusSeconds(3600),
                    "110000.00",
                    btc = "1.0" to "60000.00",
                )
                val serviceWithClock = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    nowProvider = { now },
                )
                coEvery { repository.getAllSnapshotsInRange(historicalStart, openEndedRangeEnd) } returns listOf(
                    beforeStrategyStart,
                    historicalStart.let {
                        snapshot(it, "85000.00", btc = "1.0" to "35000.00")
                    },
                    oldSnapshot,
                    retainedSnapshot,
                    afterNow,
                )

                serviceWithClock.findVerifiedLaterComparisonStart(historicalStart).shouldBeNull()
            }
        }

        "findVerifiedLaterComparisonStart_FailsClosedWhenFirstRetainedSnapshotStartsAfterTheBoundary" {
            runTest {
                val historicalStart = now.minusSeconds(100 * 86_400L)
                val retentionCutoff = now.minusSeconds(90 * 86_400L)
                val firstRetainedSnapshot = snapshot(
                    retentionCutoff.plusSeconds(25 * 3600L),
                    "100000.00",
                    btc = "1.0" to "50000.00",
                )
                val latestSnapshot = snapshot(
                    now,
                    "110000.00",
                    btc = "1.0" to "60000.00",
                )
                val serviceWithClock = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    nowProvider = { now },
                )
                coEvery { repository.getAllSnapshotsInRange(historicalStart, openEndedRangeEnd) } returns listOf(
                    firstRetainedSnapshot,
                    latestSnapshot,
                )

                serviceWithClock.findVerifiedLaterComparisonStart(historicalStart).shouldBeNull()
            }
        }

        "findVerifiedLaterComparisonStart advances past duplicate-millisecond candidates" {
            runTest {
                val duplicateTime = now.plusSeconds(3600)
                val candidates = (1..9).map {
                    snapshot(duplicateTime, "100000.00", btc = "1.0" to "50000.00")
                }
                val metadata = mutableMapOf<String, String>()
                coEvery { repository.getSyncMetadata(any()) } coAnswers {
                    metadata[firstArg()] ?: CERTIFIED_COVERAGE_DEFAULTS[firstArg()]
                }
                coEvery { repository.setSyncMetadataAtomically(any()) } coAnswers {
                    metadata.putAll(firstArg())
                }
                coEvery { repository.getAllSnapshotsInRange(now, openEndedRangeEnd) } returns candidates
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns listOf(
                    ledgerEvent(
                        ledgerId = "blocking-deposit",
                        timestamp = duplicateTime,
                        asset = Asset.BTC,
                        amount = "0.1",
                        type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    ),
                )

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = null,
                )

                serviceWithInception.findVerifiedLaterComparisonStart(now).shouldBeNull()
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] shouldBe
                    ComparisonProposalStatus.INCOMPLETE.name
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] shouldBe
                    "${duplicateTime.toEpochMilli()}:8"

                serviceWithInception.findVerifiedLaterComparisonStart(now).shouldBeNull()
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] shouldBe
                    ComparisonProposalStatus.EXHAUSTED.name
            }
        }

        "findVerifiedLaterComparisonStart retries after transient provenance preparation failure" {
            runTest {
                val t0 = now
                val t1 = now.plusSeconds(3600)
                val t2 = now.plusSeconds(7200)
                val baseline = snapshot(t0, "90000.00", btc = "1.0" to "40000.00")
                val later = snapshot(t1, "100000.00", btc = "1.0" to "50000.00")
                val final = snapshot(t2, "110000.00", btc = "1.0" to "60000.00")
                val metadata = mutableMapOf<String, String>()
                var provenanceAvailable = false
                val provenanceResolver = object : FundingProvenanceResolver {
                    override fun resolve(event: LedgerEvent): FundingEvidence = FundingEvidence.UNRESOLVED

                    override suspend fun prepare(events: Collection<LedgerEvent>): FundingProvenanceResolver =
                        if (provenanceAvailable) {
                            SimpleFundingProvenanceResolver()
                        } else {
                            FundingProvenanceResolver.unavailable(
                                FundingProvenanceFailure(
                                    reason = FundingProvenanceFailureReason.REQUEST_FAILED,
                                    message = "temporary funding evidence failure",
                                ),
                            )
                        }
                }
                coEvery { repository.getSyncMetadata(any()) } coAnswers {
                    metadata[firstArg()] ?: CERTIFIED_COVERAGE_DEFAULTS[firstArg()]
                }
                coEvery { repository.setSyncMetadataAtomically(any()) } coAnswers {
                    metadata.putAll(firstArg())
                }
                coEvery { repository.getAllSnapshotsInRange(t0, openEndedRangeEnd) } returns listOf(later, final)
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotBefore(any()) } returns baseline
                coEvery { repository.getSnapshotId(t1, 0) } returns 77
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = null,
                    fundingProvenanceResolver = provenanceResolver,
                )

                serviceWithInception.findVerifiedLaterComparisonStart(t0).shouldBeNull()
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] shouldBe
                    ComparisonProposalStatus.INCOMPLETE.name

                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] =
                    ComparisonProposalStatus.VERIFIED.name
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_SNAPSHOT_ID] = "77"
                serviceWithInception.findVerifiedLaterComparisonStart(t0).shouldBeNull()
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] shouldBe
                    ComparisonProposalStatus.INCOMPLETE.name

                val verifiedCursor = requireNotNull(
                    metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS],
                )
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] = "not-a-cursor"
                serviceWithInception.findVerifiedLaterComparisonStart(t0).shouldBeNull()
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] shouldBe
                    ComparisonProposalStatus.INCOMPLETE.name

                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] =
                    ComparisonProposalStatus.VERIFIED.name
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] = verifiedCursor
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_SNAPSHOT_ID] = "not-a-snapshot-id"
                serviceWithInception.findVerifiedLaterComparisonStart(t0).shouldBeNull()
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] shouldBe
                    ComparisonProposalStatus.INCOMPLETE.name

                provenanceAvailable = true
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] =
                    ComparisonProposalStatus.VERIFIED.name
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] = verifiedCursor
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_SNAPSHOT_ID] = "77"
                serviceWithInception.findVerifiedLaterComparisonStart(t0) shouldBe t1
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] shouldBe
                    ComparisonProposalStatus.VERIFIED.name
            }
        }

        "getRebalancerComparison_ValuesStakingContributionThroughSnapshotPrices" {
            runTest {
                val t0 = now
                val t1 = now.plusSeconds(3600)
                val baseline = snapshot(t0, "100000.00", btc = "1.0" to "50000.00")
                val later = snapshot(t1, "105000.00", btc = "1.1" to "50000.00")
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns
                    InceptionResolution(t0, baseline, isAutoDetected = false)
                coEvery { repository.getSnapshotsInRange(t0, t1) } returns listOf(baseline, later)
                coEvery { repository.getSnapshotBefore(any()) } returns baseline
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns
                    listOf(ledgerEvent("L1", t1, "BTC", "0.1"))

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                val comparison = serviceWithInception.getRebalancerComparison(t0, t1)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.points.size shouldBe 2
            }
        }

        "findVerifiedLaterComparisonStart_DoesNotSkipPotentiallyValidEarlierAnchor" {
            runTest {
                val t0 = now
                val t1 = now.plusSeconds(3600)
                val t2 = now.plusSeconds(7200)
                val t3 = now.plusSeconds(10800)
                val t4 = now.plusSeconds(14400)
                val snap0 = snapshot(t0, "90000.00", btc = "1.0" to "40000.00")
                val snap1 = snapshot(t1, "130000.00", btc = "2.0" to "40000.00")
                val snap2 = snapshot(t2, "170000.00", btc = "3.0" to "40000.00")
                val snap3 = snapshot(t3, "170000.00", btc = "3.0" to "40000.00")
                val snap4 = snapshot(t4, "170000.00", btc = "3.0" to "40000.00")

                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = t0,
                    inceptionSnapshot = snap0,
                    isAutoDetected = false,
                )
                coEvery { repository.getSnapshotsInRange(t0, t4) } returns
                    listOf(snap0, snap1, snap2, snap3, snap4)
                coEvery { repository.getSnapshotsInRange(t0, openEndedRangeEnd) } returns
                    listOf(snap0, snap1, snap2, snap3, snap4)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                // The first trial may report an unavailableAt after more than one candidate, but
                // that does not prove the intermediate anchor is invalid. Check every candidate
                // in order so the earliest verified start remains trustworthy.
                serviceWithInception.findVerifiedLaterComparisonStart(t0) shouldBe t2
            }
        }

        "findVerifiedLaterComparisonStart_StopsAfterBoundedTrialBudget" {
            runTest {
                val balances = (1..10).map { index -> "1.$index" }
                val snaps = balances.mapIndexed { index, balance ->
                    val stamp = now.plusSeconds(3600L * (index + 1))
                    snapshot(stamp, "90000.00", btc = balance to "40000.00")
                }

                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery {
                    repository.getSnapshotsInRange(now, now.plusSeconds(36000))
                } returns snaps
                coEvery { repository.getSnapshotsInRange(now, openEndedRangeEnd) } returns snaps
                coEvery { repository.getSnapshotBefore(any()) } returns snaps.first()
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                serviceWithInception.findVerifiedLaterComparisonStart(now).shouldBeNull()
            }
        }

        "findVerifiedLaterComparisonStart_ResumesAfterPerRunBudget" {
            runTest {
                val firstEight = (1..8).map { index ->
                    snapshot(
                        now.plusSeconds(3600L * index),
                        "90000.00",
                        btc = "1.$index" to "40000.00",
                    )
                }
                val verifiedStart = now.plusSeconds(3600L * 9)
                val verifiedContinuation = now.plusSeconds(3600L * 10)
                val snaps = firstEight + listOf(
                    snapshot(verifiedStart, "130000.00", btc = "2.0" to "40000.00"),
                    snapshot(verifiedContinuation, "130000.00", btc = "2.0" to "40000.00"),
                )
                val metadata = mutableMapOf<String, String>()
                coEvery { repository.getSyncMetadata(any()) } coAnswers {
                    metadata[firstArg()] ?: CERTIFIED_COVERAGE_DEFAULTS[firstArg()]
                }
                coEvery { repository.setSyncMetadataAtomically(any()) } coAnswers {
                    metadata.putAll(firstArg())
                }
                coEvery { repository.getSnapshotsInRange(now, openEndedRangeEnd) } returns snaps
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotBefore(any()) } returns null

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = null,
                )

                serviceWithInception.findVerifiedLaterComparisonStart(now).shouldBeNull()
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] shouldBe
                    ComparisonProposalStatus.INCOMPLETE.name
                serviceWithInception.findVerifiedLaterComparisonStart(now) shouldBe verifiedStart
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] shouldBe
                    ComparisonProposalStatus.VERIFIED.name
            }
        }

        "proposal search invalidates its cursor when configuration or account evidence changes" {
            runTest {
                val firstEight = (1..8).map { index ->
                    snapshot(
                        now.plusSeconds(3600L * index),
                        "90000.00",
                        btc = "1.$index" to "40000.00",
                    )
                }
                val verifiedStart = now.plusSeconds(3600L * 9)
                val verifiedContinuation = now.plusSeconds(3600L * 10)
                var snapshots = firstEight + listOf(
                    snapshot(verifiedStart, "130000.00", btc = "2.0" to "40000.00"),
                    snapshot(verifiedContinuation, "130000.00", btc = "2.0" to "40000.00"),
                )
                val metadata = mutableMapOf<String, String>(
                    SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT to "config-a",
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST to "account-a",
                    SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST to "account-a",
                )
                val ledgerMetadata = mutableMapOf<String, String>(
                    SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST to "account-a",
                )
                coEvery { repository.getSyncMetadata(any()) } coAnswers {
                    metadata[firstArg()] ?: CERTIFIED_COVERAGE_DEFAULTS[firstArg()]
                }
                coEvery { ledgerRepository.getSyncMetadata(any()) } coAnswers {
                    ledgerMetadata[firstArg()] ?: CERTIFIED_COVERAGE_DEFAULTS[firstArg()]
                }
                coEvery { repository.setSyncMetadataAtomically(any()) } coAnswers {
                    metadata.putAll(firstArg())
                }
                coEvery { repository.getSnapshotsInRange(now, openEndedRangeEnd) } coAnswers { snapshots }
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotBefore(any()) } returns null

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = null,
                )

                serviceWithInception.findVerifiedLaterComparisonStart(now).shouldBeNull()
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] shouldBe
                    ComparisonProposalStatus.INCOMPLETE.name

                metadata[SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT] = "config-b"
                metadata[SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST] = "account-b"
                metadata[SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST] = "account-b"
                ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST] = "account-b"
                serviceWithInception.findVerifiedLaterComparisonStart(now).shouldBeNull()
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] shouldBe
                    ComparisonProposalStatus.INCOMPLETE.name

                serviceWithInception.findVerifiedLaterComparisonStart(now) shouldBe verifiedStart

                snapshots = listOf(
                    snapshots.first().copy(totalValueUSD = BigDecimal("90001.00")),
                ) + snapshots.drop(1)
                serviceWithInception.findVerifiedLaterComparisonStart(now).shouldBeNull()
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] shouldBe
                    ComparisonProposalStatus.INCOMPLETE.name
            }
        }

        "getComparisonStartProposal_reportsExhaustedAfterCheckingEveryCandidate" {
            runTest {
                val t0 = now
                val exhaustedCandidates = listOf(
                    snapshot(now.plusSeconds(3600), "130000.00", btc = "2.0" to "40000.00"),
                    snapshot(now.plusSeconds(7200), "170000.00", btc = "3.0" to "40000.00"),
                    snapshot(now.plusSeconds(10800), "210000.00", btc = "4.0" to "40000.00"),
                )
                val metadata = mutableMapOf<String, String>()
                metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] = "4102444800"
                coEvery { repository.getSyncMetadata(any()) } coAnswers {
                    metadata[firstArg()] ?: CERTIFIED_COVERAGE_DEFAULTS[firstArg()]
                }
                coEvery { repository.setSyncMetadataAtomically(any()) } coAnswers {
                    metadata.putAll(firstArg())
                }
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = t0,
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_BASELINE_UNAVAILABLE,
                )
                coEvery { repository.getSnapshotsInRange(t0, openEndedRangeEnd) } returns exhaustedCandidates
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                val exhausted = serviceWithInception.getComparisonStartProposal(t0)

                exhausted?.status shouldBe ComparisonProposalStatus.EXHAUSTED
                exhausted?.timestamp.shouldBeNull()
                serviceWithInception.getComparisonStartProposal(t0)?.status shouldBe
                    ComparisonProposalStatus.EXHAUSTED
            }
        }

        "terminal proposal state is rechecked when prepared funding evidence changes" {
            runTest {
                val t0 = now
                val candidates = listOf(
                    snapshot(now.plusSeconds(3600), "130000.00", btc = "2.0" to "40000.00"),
                    snapshot(now.plusSeconds(7200), "170000.00", btc = "3.0" to "40000.00"),
                    snapshot(now.plusSeconds(10800), "210000.00", btc = "4.0" to "40000.00"),
                )
                val metadata = mutableMapOf<String, String>()
                metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] = "4102444800"
                var evidenceRevision = "revision-a"
                var prepareCalls = 0
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = t0,
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_BASELINE_UNAVAILABLE,
                )
                val provenanceResolver = object : FundingProvenanceResolver {
                    override fun resolve(event: LedgerEvent): FundingEvidence = FundingEvidence.UNRESOLVED

                    override val evidenceFingerprint: String
                        get() = evidenceRevision

                    override suspend fun prepare(events: Collection<LedgerEvent>): FundingProvenanceResolver {
                        prepareCalls++
                        return this
                    }
                }
                coEvery { repository.getSyncMetadata(any()) } coAnswers {
                    metadata[firstArg()] ?: CERTIFIED_COVERAGE_DEFAULTS[firstArg()]
                }
                coEvery { repository.setSyncMetadataAtomically(any()) } coAnswers {
                    metadata.putAll(firstArg())
                }
                coEvery { repository.getAllSnapshotsInRange(t0, openEndedRangeEnd) } returns candidates
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                    fundingProvenanceResolver = provenanceResolver,
                )

                serviceWithInception.getComparisonStartProposal(t0)?.status shouldBe
                    ComparisonProposalStatus.EXHAUSTED
                serviceWithInception.getComparisonStartProposal(t0)?.status shouldBe
                    ComparisonProposalStatus.EXHAUSTED
                val callsBeforeRevisionChange = prepareCalls

                evidenceRevision = "revision-b"
                serviceWithInception.getComparisonStartProposal(t0)?.status shouldBe
                    ComparisonProposalStatus.EXHAUSTED

                prepareCalls shouldBeGreaterThan callsBeforeRevisionChange
            }
        }

        "getComparisonStartProposal_returnsNullWhenThereIsNoEligibleUnavailableComparison" {
            runTest {
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns emptyList()

                service.getComparisonStartProposal(now).shouldBeNull()
            }
        }

        "getComparisonStartProposal_doesNotSearchWhenTheCurrentComparisonIsAvailable" {
            runTest {
                val first = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val second = snapshot(now.plusSeconds(3600), "100000.00", btc = "1.0" to "50000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(first, second)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()

                service.getComparisonStartProposal(now).shouldBeNull()
            }
        }

        "getComparisonStartProposal_doesNotSearchAnIneligibleRecoveryFailure" {
            runTest {
                val first = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val second = snapshot(now.plusSeconds(3600), "100000.00", btc = "1.0" to "50000.00")
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = now,
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = null,
                )
                coEvery { repository.getSnapshotsInRange(now, openEndedRangeEnd) } returns listOf(first, second)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotBefore(any()) } returns null

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                serviceWithInception.getComparisonStartProposal(now).shouldBeNull()
            }
        }

        "getComparisonStartProposal_doesNotExposeAutoDetectedInception" {
            runTest {
                val first = snapshot(now, "90000.00", btc = "1.0" to "40000.00")
                val second = snapshot(now.plusSeconds(3600), "100000.00", btc = "1.0" to "50000.00")
                val mockInceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                coEvery { mockInceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = now,
                    inceptionSnapshot = first,
                    isAutoDetected = true,
                )
                coEvery { repository.getAllSnapshotsInRange(now, openEndedRangeEnd) } returns listOf(first, second)

                val serviceWithInception = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = mockInceptionService,
                )

                serviceWithInception.getComparisonStartProposal(now).shouldBeNull()
                coVerify(exactly = 0) { repository.getAllSnapshotsInRange(any(), any()) }
            }
        }

        "verified proposal state is reused and an invalid stored cursor restarts safely" {
            runTest {
                val first = snapshot(now, "90000.00", btc = "1.0" to "40000.00")
                val verified = snapshot(now.plusSeconds(3600), "100000.00", btc = "1.0" to "50000.00")
                val last = snapshot(now.plusSeconds(7200), "110000.00", btc = "1.0" to "60000.00")
                val metadata = mutableMapOf<String, String>()
                coEvery { repository.getSyncMetadata(any()) } coAnswers {
                    metadata[firstArg()] ?: CERTIFIED_COVERAGE_DEFAULTS[firstArg()]
                }
                coEvery { repository.setSyncMetadataAtomically(any()) } coAnswers {
                    metadata.putAll(firstArg())
                }
                coEvery { repository.getSnapshotsInRange(now, openEndedRangeEnd) } returns
                    listOf(first, verified, last)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSnapshotBefore(any()) } returns null

                service.findVerifiedLaterComparisonStart(now) shouldBe verified.timestamp
                service.findVerifiedLaterComparisonStart(now) shouldBe verified.timestamp

                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] = "invalid-cursor"
                service.findVerifiedLaterComparisonStart(now) shouldBe verified.timestamp

                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] = "${now.toEpochMilli()}:-1"
                service.findVerifiedLaterComparisonStart(now) shouldBe verified.timestamp

                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] =
                    "${now.toEpochMilli()}:not-an-ordinal"
                service.findVerifiedLaterComparisonStart(now) shouldBe verified.timestamp

                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] = "not-an-epoch:0"
                service.findVerifiedLaterComparisonStart(now) shouldBe verified.timestamp

                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] = "invalid:cursor:shape"
                service.findVerifiedLaterComparisonStart(now) shouldBe verified.timestamp

                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] =
                    ComparisonProposalStatus.INCOMPLETE.name
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] =
                    now.plusSeconds(9999).toEpochMilli().toString()
                service.findVerifiedLaterComparisonStart(now) shouldBe verified.timestamp

                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_STATUS] =
                    ComparisonProposalStatus.VERIFIED.name
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] =
                    now.plusSeconds(9999).toEpochMilli().toString()
                service.findVerifiedLaterComparisonStart(now) shouldBe verified.timestamp

                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_CURSOR_EPOCH_MS] =
                    "${verified.timestamp.toEpochMilli()}:0"
                metadata[SyncMetadataKeys.INCEPTION_COMPARISON_PROPOSAL_SNAPSHOT_ID] = "42"
                service.findVerifiedLaterComparisonStart(now) shouldBe verified.timestamp
            }
        }

        "proposal evidence fingerprint includes retained trade ledger and order identity data" {
            runTest {
                val first = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                val verified = snapshot(
                    now.plusSeconds(3600),
                    "100000.00",
                    btc = "1.0" to "50000.00",
                )
                val last = snapshot(now.plusSeconds(7200), "100000.00", btc = "1.0" to "50000.00")
                val retainedTrade = TestFixtures.tradeRecord(
                    timestamp = now.plusSeconds(5400),
                    pair = Asset.BTC_USD_PAIR,
                    side = OrderSide.BUY.apiValue,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.01"),
                    usdAmount = BigDecimal("500.00"),
                    price = BigDecimal("50000.00"),
                    fee = BigDecimal("0.50"),
                    slippagePercent = BigDecimal("0.10"),
                    expectedPrice = BigDecimal("49950.00"),
                    source = TradeSource.API_FILL,
                    id = 1,
                    orderTxid = "proposal-order",
                    tradeId = "proposal-trade",
                    clientOrderId = "proposal-client",
                )
                val failedTrade = retainedTrade.copy(id = 2, success = false)
                val dryRunTrade = retainedTrade.copy(id = 3, dryRun = true)
                val unidentifiedFailedTrade = retainedTrade.copy(
                    id = null,
                    success = false,
                    orderTxid = null,
                    tradeId = null,
                    clientOrderId = null,
                )
                val retainedLedger = ledgerEvent(
                    ledgerId = "proposal-ledger",
                    timestamp = now.plusSeconds(5401),
                    asset = Asset.BTC,
                    amount = "0",
                )
                coEvery { repository.getSnapshotsInRange(now, openEndedRangeEnd) } returns
                    listOf(first, verified, last)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { repository.getTradesInRange(Instant.EPOCH, openEndedRangeEnd) } returns
                    listOf(failedTrade, dryRunTrade, unidentifiedFailedTrade, retainedTrade)
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
                coEvery { ledgerRepository.getLedgersInRange(Instant.EPOCH, openEndedRangeEnd) } returns
                    listOf(retainedLedger)
                coEvery { repository.getSnapshotBefore(any()) } returns null
                coEvery { orderIntentRepository.getKnownRebalancerOrderIdentities(any(), any()) } returns
                    RebalancerOrderIdentities(orderTxids = setOf("proposal-order"))

                service.findVerifiedLaterComparisonStart(now) shouldBe verified.timestamp
            }
        }

        "resolveContinuousHistoryStart_ReturnsStoredMetadataWhenPresent" {
            runTest {
                val storedTime = now.minusSeconds(86400 * 10)
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS)
                } returns storedTime.toEpochMilli().toString()

                val snap1 = snapshot(now.minusSeconds(86400 * 20), "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)

                val proposal = service.getComparisonStartProposal(now.minusSeconds(86400 * 20))
                proposal.shouldBeNull()
            }
        }

        "resolveContinuousHistoryStart_ReturnsStoredWhenEarliestSnapshotNotBeforeStored" {
            runTest {
                val storedTime = now.minusSeconds(86400 * 20)
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS)
                } returns storedTime.toEpochMilli().toString()

                val snap1 = snapshot(now.minusSeconds(86400 * 10), "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)

                val proposal = service.getComparisonStartProposal(now.minusSeconds(86400 * 5))
                proposal.shouldBeNull()
            }
        }

        "resolveContinuousHistoryStart_SetsEpochForFreshInstall" {
            runTest {
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS)
                } returns null
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_INSTALL_TYPE)
                } returns InceptionDiscoveryService.INSTALL_TYPE_FRESH
                coEvery { repository.isHistorySeeded() } returns false

                val snap1 = snapshot(now.minusSeconds(3600), "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2)

                val storedSlot = slot<String>()
                coEvery {
                    repository.setSyncMetadata(
                        SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS,
                        capture(storedSlot),
                    )
                } returns Unit

                service.findVerifiedLaterComparisonStart(now.minusSeconds(3600))
                storedSlot.captured shouldBe "0"
            }
        }

        "resolveContinuousHistoryStart_FindsContinuousBoundaryAcrossGap" {
            runTest {
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS)
                } returns null
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_INSTALL_TYPE)
                } returns InceptionDiscoveryService.INSTALL_TYPE_UPGRADED
                coEvery { repository.isHistorySeeded() } returns false

                val oldSnap = snapshot(now.minusSeconds(86400 * 10), "100000.00", btc = "1.0" to "50000.00")
                val continuousBoundary = now.minusSeconds(86400 * 2)
                val snap1 = snapshot(continuousBoundary, "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(now.minusSeconds(86400), "100000.00", btc = "1.0" to "50000.00")
                val snap3 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(oldSnap, snap1, snap2, snap3)

                val storedSlot = slot<String>()
                coEvery {
                    repository.setSyncMetadata(
                        SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS,
                        capture(storedSlot),
                    )
                } returns Unit

                val result = service.findVerifiedLaterComparisonStart(oldSnap.timestamp)
                result.shouldBeNull()
                storedSlot.captured shouldBe continuousBoundary.toEpochMilli().toString()
            }
        }

        "historicalCoverageGapExists_ConsecutiveGapInRetainedHistoryBlocksProposal" {
            runTest {
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS)
                } returns "0"
                val snap1 = snapshot(now.minusSeconds(86400 * 10), "100000.00", btc = "1.0" to "50000.00")
                val snap2 = snapshot(now.minusSeconds(86400 * 5), "100000.00", btc = "1.0" to "50000.00")
                val snap3 = snapshot(now, "100000.00", btc = "1.0" to "50000.00")
                coEvery { repository.getSnapshotsInRange(any(), any()) } returns listOf(snap1, snap2, snap3)

                val proposal = service.findVerifiedLaterComparisonStart(snap1.timestamp)
                proposal.shouldBeNull()
            }
        }

        "getSnapshotsInRange drops an identity anchor when a reconstructed snapshot shares the instant" {
            runTest {
                val anchor = snapshot(now, "1235.68", btc = "0.0" to "0.00")
                val reconstructed = snapshot(now, "1490.81", btc = "0.5" to "40000.00")
                val later = snapshot(now.plusSeconds(1800), "1500.00", btc = "0.6" to "40000.00")
                coEvery { repository.getSnapshotsInRange(now, now) } returns listOf(anchor, reconstructed)
                coEvery { repository.getSnapshotsInRange(now, now.plusSeconds(3600)) } returns
                    listOf(anchor, reconstructed, later)
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID) } returns
                    "7"
                coEvery { repository.getSnapshotById(7) } returns anchor

                service.getSnapshotsInRange(now, now.plusSeconds(3600)) shouldBe listOf(reconstructed, later)
            }
        }

        "getSnapshotsInRange keeps an identity anchor when no snapshot shares the instant" {
            runTest {
                val anchor = snapshot(now, "1235.68", btc = "0.0" to "0.00")
                val later = snapshot(now.plusSeconds(1800), "1500.00", btc = "0.6" to "40000.00")
                coEvery { repository.getSnapshotsInRange(now, now) } returns listOf(anchor)
                coEvery { repository.getSnapshotsInRange(now, now.plusSeconds(3600)) } returns listOf(anchor, later)
                coEvery { repository.getSyncMetadata(SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID) } returns
                    "7"
                coEvery { repository.getSnapshotById(7) } returns anchor

                service.getSnapshotsInRange(now, now.plusSeconds(3600)) shouldBe listOf(anchor, later)
            }
        }

        "settings comparison status persists the automatic baseline proof on first successful verification" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(fixture.anchorTime)

                status.comparisonAvailability shouldBe ComparisonAvailability.AVAILABLE
                status.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                status.baselineTimestamp shouldBe fixture.anchorTime.toString()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_VERIFICATION_VERSION] shouldBe "1"
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_TIMESTAMP_EPOCH_MS] shouldBe
                    fixture.anchorTime.toEpochMilli().toString()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_INCEPTION_EPOCH_MS] shouldBe
                    fixture.anchorTime.toEpochMilli().toString()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_SNAPSHOT_CURSOR] shouldBe
                    "${fixture.anchorTime.toEpochMilli()}:0"
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_SNAPSHOT_ID].shouldNotBeNull()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_FINGERPRINT]
                    .shouldNotBeNull()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS] shouldBe
                    fixture.laterTime.toEpochMilli().toString()
            }
        }

        "settings comparison reuses a durable result when the full path is requested again" {
            runTest {
                val fixture = automaticBaselineFixture()
                coEvery { fixture.fundingProvenanceResolver.evidenceFingerprint } returns "fixture-funding"
                val cache = InMemoryComparisonCache()
                val cachedService = automaticBaselineService(fixture, cache)

                cachedService.getSettingsComparisonStatus(
                    fixture.anchorTime,
                    allowPersistedBaselineFastPath = false,
                ).comparisonAvailability shouldBe ComparisonAvailability.AVAILABLE
                cachedService.getSettingsComparisonStatus(
                    fixture.anchorTime,
                    allowPersistedBaselineFastPath = false,
                ).comparisonAvailability shouldBe ComparisonAvailability.AVAILABLE

                cache.loadCount shouldBe 2
                cache.saveCount shouldBe 1
            }
        }

        "history does not persist automatic baseline proof for auto-detected inception" {
            runTest {
                val fixture = automaticBaselineFixture()
                coEvery { fixture.inceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = fixture.anchorTime,
                    inceptionSnapshot = fixture.snapshotRows.first(),
                    isAutoDetected = true,
                    confidence = InceptionConfidence.CONFIDENT,
                )
                val service = automaticBaselineService(fixture)

                val comparison = service.getRebalancerComparison(fixture.anchorTime, fixture.laterTime)

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS].shouldBeNull()
            }
        }

        "verified automatic baseline proof exempts snapshot gaps inside its verified horizon" {
            runTest {
                val fixture = automaticBaselineFixture(anchorOffsetSeconds = 8 * 86400L)
                val service = automaticBaselineService(fixture)
                val gapTime = fixture.laterTime.plusSeconds(29 * 3600)
                fixture.snapshotRows += snapshot(
                    gapTime,
                    "1200.00",
                    btc = "1.0" to "700.00",
                    usdBalance = "500.00",
                    balancesObservedAt = gapTime.minusMillis(600),
                )

                service.getRebalancerComparison(fixture.anchorTime, now)
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS] shouldBe
                    gapTime.toEpochMilli().toString()

                val liveTailTime = gapTime.plusSeconds(3600)
                fixture.snapshotRows += snapshot(
                    liveTailTime,
                    "1270.00",
                    btc = "1.1" to "700.00",
                    usdBalance = "500.00",
                    balancesObservedAt = liveTailTime.minusMillis(500),
                )

                val status = service.getSettingsComparisonStatus(
                    fixture.anchorTime,
                    allowPersistedBaselineFastPath = false,
                )
                status.comparisonAvailability shouldBe ComparisonAvailability.UNAVAILABLE
                status.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
                status.proposal.shouldNotBeNull()
                fixture.metadata[SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS].shouldBeNull()
            }
        }

        "verified baseline proof does not exempt gaps when the evidence horizon is malformed" {
            runTest {
                val fixture = automaticBaselineFixture(anchorOffsetSeconds = 8 * 86400L)
                val service = automaticBaselineService(fixture)
                val gapTime = fixture.laterTime.plusSeconds(29 * 3600)
                fixture.snapshotRows += snapshot(
                    gapTime,
                    "1200.00",
                    btc = "1.0" to "700.00",
                    usdBalance = "500.00",
                    balancesObservedAt = gapTime.minusMillis(600),
                )

                service.getRebalancerComparison(fixture.anchorTime, now)
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS] =
                    "not-a-number"

                val liveTailTime = gapTime.plusSeconds(3600)
                fixture.snapshotRows += snapshot(
                    liveTailTime,
                    "1270.00",
                    btc = "1.1" to "700.00",
                    usdBalance = "500.00",
                    balancesObservedAt = liveTailTime.minusMillis(500),
                )

                val status = service.getSettingsComparisonStatus(
                    fixture.anchorTime,
                    allowPersistedBaselineFastPath = false,
                )
                status.comparisonAvailability shouldBe ComparisonAvailability.UNAVAILABLE
                status.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
                status.proposal.shouldBeNull()
            }
        }

        "without a verified baseline proof the retained-history gap blocks the proposal search" {
            runTest {
                val fixture = automaticBaselineFixture(anchorOffsetSeconds = 8 * 86400L)
                val service = automaticBaselineService(fixture)
                val gapTime = fixture.laterTime.plusSeconds(29 * 3600)
                fixture.snapshotRows += snapshot(
                    gapTime,
                    "1200.00",
                    btc = "1.0" to "700.00",
                    usdBalance = "500.00",
                    balancesObservedAt = gapTime.minusMillis(600),
                )
                val liveTailTime = gapTime.plusSeconds(3600)
                fixture.snapshotRows += snapshot(
                    liveTailTime,
                    "1270.00",
                    btc = "1.1" to "700.00",
                    usdBalance = "500.00",
                    balancesObservedAt = liveTailTime.minusMillis(500),
                )

                val status = service.getSettingsComparisonStatus(
                    fixture.anchorTime,
                    allowPersistedBaselineFastPath = false,
                )
                status.comparisonAvailability shouldBe ComparisonAvailability.UNAVAILABLE
                status.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
                status.proposal.shouldBeNull()
            }
        }

        "settings comparison status takes the persisted fast path on unchanged evidence" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)

                val reloaded = service.getSettingsComparisonStatus(fixture.anchorTime)

                reloaded.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                reloaded.baselineTimestamp shouldBe fixture.anchorTime.toString()
                // The fast path proves baseline identity only; it must not claim that the
                // current comparison was evaluated.
                reloaded.comparisonAvailability.shouldBeNull()
                // The full evaluation ran once: the recompute side effects (anchor lookup and the
                // baseline id write) are not repeated by the fast path.
                coVerify(exactly = 3) { repository.getSnapshotBefore(any()) }
                coVerify(exactly = 2) { repository.getSnapshotId(any(), any()) }
                // The full verification prepared funding evidence once; the fast path must not
                // repeat the preparation.
                coVerify(exactly = 1) { fixture.fundingProvenanceResolver.prepare(any()) }
            }
        }

        "settings comparison status takes the persisted fast path in a fresh service instance" {
            runTest {
                val fixture = automaticBaselineFixture()
                automaticBaselineService(fixture).getSettingsComparisonStatus(fixture.anchorTime)

                val restarted = automaticBaselineService(fixture).getSettingsComparisonStatus(fixture.anchorTime)

                restarted.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                restarted.baselineTimestamp shouldBe fixture.anchorTime.toString()
                restarted.comparisonAvailability.shouldBeNull()
                coVerify(exactly = 3) { repository.getSnapshotBefore(any()) }
                coVerify(exactly = 1) { fixture.fundingProvenanceResolver.prepare(any()) }
            }
        }

        "concurrent settings comparison status calls share one evaluation flight" {
            runTest {
                val inceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                val resolutions = AtomicInteger(0)
                coEvery { inceptionService.resolveInceptionUnderEvidenceLock() } coAnswers {
                    resolutions.incrementAndGet()
                    delay(200)
                    InceptionResolution(
                        inceptionTime = now,
                        inceptionSnapshot = null,
                        isAutoDetected = true,
                    )
                }
                val flightService = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = inceptionService,
                )

                val results = withContext(Dispatchers.IO) {
                    (1..10).map { async { flightService.getSettingsComparisonStatus(now) } }.awaitAll()
                }

                resolutions.get() shouldBe 1
                results.forEach { it shouldBe results.first() }
            }
        }

        "a joiner takes over when the flight initiator is cancelled" {
            runTest {
                val inceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
                val resolutions = AtomicInteger(0)
                val parked = CompletableDeferred<Unit>()
                var first = true
                coEvery { inceptionService.resolveInceptionUnderEvidenceLock() } coAnswers {
                    resolutions.incrementAndGet()
                    if (first) {
                        first = false
                        parked.complete(Unit)
                        // Initiator parks here until the test cancels it.
                        awaitCancellation()
                    }
                    InceptionResolution(
                        inceptionTime = now,
                        inceptionSnapshot = null,
                        isAutoDetected = true,
                    )
                }
                val flightService = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = inceptionService,
                )

                val initiator = async { flightService.getSettingsComparisonStatus(now) }
                parked.await()
                val joiner = async { flightService.getSettingsComparisonStatus(now) }
                advanceUntilIdle()

                initiator.cancel()
                val result = joiner.await()

                initiator.isCancelled shouldBe true
                result shouldBe SettingsComparisonStatus()
                resolutions.get() shouldBe 2
            }
        }

        "a new live snapshot after verification does not invalidate the persisted proof" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                val tailTime = fixture.laterTime.plusSeconds(3600)
                fixture.snapshotRows += snapshot(tailTime, "1200.00", btc = "1.0" to "700.00")

                val reloaded = service.getSettingsComparisonStatus(fixture.anchorTime)

                reloaded.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                reloaded.baselineTimestamp shouldBe fixture.anchorTime.toString()
                reloaded.comparisonAvailability.shouldBeNull()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
                coVerify(exactly = 3) { repository.getSnapshotBefore(any()) }
            }
        }

        "a post-horizon tail event breaks the current comparison " +
            "while the fast path keeps serving the proven baseline" {
                runTest {
                    val fixture = automaticBaselineFixture()
                    val service = automaticBaselineService(fixture)
                    service.getSettingsComparisonStatus(fixture.anchorTime)
                    val tailTime = fixture.laterTime.plusMillis(500)
                    val tailEvent = ledgerEvent("TAIL-1", tailTime, "USD", "1.00", type = "MARGIN_ROLLFORWARD")
                    coEvery { ledgerRepository.getLedgersInRange(any(), any()) } coAnswers {
                        if (secondArg<Instant>() >= tailTime) listOf(tailEvent) else emptyList()
                    }

                    val fastPath = service.getSettingsComparisonStatus(fixture.anchorTime)
                    val fullPath = service.getSettingsComparisonStatus(
                        fixture.anchorTime,
                        allowPersistedBaselineFastPath = false,
                    )

                    // The persisted proof vouches for baseline identity only: the fast path keeps
                    // reporting it while refusing to claim the current comparison was evaluated.
                    fastPath.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                    fastPath.baselineTimestamp shouldBe fixture.anchorTime.toString()
                    fastPath.comparisonAvailability.shouldBeNull()
                    // The full evaluation honestly reports the tail event and marks no baseline
                    // status: an unavailable comparison carries no proof it did not just make.
                    fullPath.comparisonAvailability shouldBe ComparisonAvailability.UNAVAILABLE
                    fullPath.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_LEDGER_TYPE
                    fullPath.baselineStatus.shouldBeNull()
                    fullPath.baselineTimestamp.shouldBeNull()
                    fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
                }
            }

        "a degraded inception resolution fails closed without destroying the persisted proof" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                coEvery { fixture.inceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = fixture.anchorTime.minusSeconds(3600),
                    inceptionSnapshot = null,
                    isAutoDetected = false,
                    confidence = InceptionConfidence.RECOVERY_INCOMPLETE,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )

                val degraded = service.getSettingsComparisonStatus(fixture.anchorTime)

                // A degraded resolution withholds trust from the current evidence, so the
                // full evaluation runs (the passive-anchor path does not consult the
                // snapshot-before lookup) instead of serving the record.
                degraded.comparisonAvailability shouldBe ComparisonAvailability.AVAILABLE
                // The degraded resolution re-anchors passively: the comparison is available,
                // but no automatic-inception proof exists behind that anchor, so the status
                // marks no baseline certification.
                degraded.baselineStatus.shouldBeNull()
                coVerify(exactly = 1) { repository.getSnapshotId(any(), any()) }
                // The proof survives a transient degraded resolution: the next confident
                // resolution can reuse it without a re-verification.
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
            }
        }

        "automatic baseline verification defers when snapshot observations are non-monotonic" {
            runTest {
                val fixture = automaticBaselineFixture()
                val intermediateSnap = snapshot(
                    fixture.anchorTime.plusSeconds(1800),
                    "1050.00",
                    btc = "1.0" to "550.00",
                    usdBalance = "500.00",
                    balancesObservedAt = fixture.laterTime.plusSeconds(3600),
                )
                fixture.snapshotRows.add(1, intermediateSnap)
                fixture.metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.anchorTime.epochSecond + 3600).toString()
                fixture.ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.anchorTime.epochSecond + 3600).toString()

                val service = automaticBaselineService(fixture)
                val status = service.getSettingsComparisonStatus(
                    fixture.anchorTime,
                    allowPersistedBaselineFastPath = false,
                )
                status.baselineStatus.shouldBeNull()
            }
        }

        "the proposal evaluation path bypasses the persisted fast path" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)

                val proposal = service.getComparisonStartProposal(fixture.anchorTime)

                // Proposal semantics are preserved exactly: the persisted record answers the
                // Settings display only, never the proposal chain.
                coVerify(exactly = 4) { repository.getSnapshotBefore(any()) }
                proposal.shouldBeNull()
            }
        }

        "a changed strategy inception invalidates the persisted proof" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                coEvery { fixture.inceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = fixture.laterTime,
                    inceptionSnapshot = fixture.snapshotRows[1],
                    isAutoDetected = false,
                    confidence = InceptionConfidence.CONFIDENT,
                )

                service.getSettingsComparisonStatus(fixture.anchorTime)

                // The fast path rejects the record before resolving the baseline id, so the
                // only id resolution left is the first verification's persist write.
                coVerify(exactly = 1) { repository.getSnapshotId(any(), any()) }
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldNotBe "VERIFIED"
            }
        }

        "an unresolvable baseline snapshot id invalidates the persisted proof" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                coEvery { repository.getSnapshotId(any(), any()) } returns null

                service.getSettingsComparisonStatus(fixture.anchorTime)

                // The recompute's re-persist aborts at the unresolvable id before consuming
                // evidence, so the count is first verification (calc + digest) plus one
                // re-evaluation calculation.
                coVerify(exactly = 3) { repository.getSnapshotBefore(any()) }
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldNotBe "VERIFIED"
            }
        }

        "a changed config universe invalidates and re-persists the proof" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                fixture.metadata[SyncMetadataKeys.INCEPTION_CONFIG_FINGERPRINT] = "universe-2"

                service.getSettingsComparisonStatus(fixture.anchorTime)

                coVerify(exactly = 4) { repository.getSnapshotBefore(any()) }
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_CONFIG_FINGERPRINT] shouldBe
                    "universe-2"
            }
        }

        "a changed account scope invalidates and re-persists the proof" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                fixture.metadata[SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST] = "scope-2"

                val blocked = service.getSettingsComparisonStatus(fixture.anchorTime)

                blocked.comparisonAvailability.shouldBeNull()
                blocked.baselineStatus.shouldBeNull()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "INVALIDATED"

                // A changed account binding cannot be certified until both economic-history
                // coverage certificates are rebound to the same scope.
                fixture.metadata[SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST] = "scope-2"
                fixture.ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST] = "scope-2"
                fixture.metadata[SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST] = "scope-2"
                val rebound = service.getSettingsComparisonStatus(fixture.anchorTime)

                rebound.comparisonAvailability shouldBe ComparisonAvailability.AVAILABLE
                rebound.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_ACCOUNT_SCOPE_DIGEST] shouldBe
                    "scope-2"
            }
        }

        "a changed reconstruction contract invalidates the persisted proof" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                fixture.metadata[SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION] = "legacy"

                service.getSettingsComparisonStatus(fixture.anchorTime)

                coVerify(exactly = 4) { repository.getSnapshotBefore(any()) }
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
            }
        }

        "a rewritten evidence row inside the verified horizon invalidates the persisted proof" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                val firstFingerprint =
                    fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_FINGERPRINT]
                fixture.snapshotRows[1] =
                    fixture.snapshotRows[1].copy(totalValueUSD = BigDecimal("1200.00"))

                service.getSettingsComparisonStatus(fixture.anchorTime)

                // First verification (calc + digest), the failing validation digest, and the
                // re-verification (calc + digest).
                coVerify(exactly = 5) { repository.getSnapshotBefore(any()) }
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_FINGERPRINT]
                    .shouldNotBeNull()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_FINGERPRINT] shouldNotBe
                    firstFingerprint
            }
        }

        "malformed persisted state fails closed and recomputes" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] = "CORRUPT"

                val reloaded = service.getSettingsComparisonStatus(fixture.anchorTime)

                reloaded.comparisonAvailability shouldBe ComparisonAvailability.AVAILABLE
                reloaded.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                reloaded.baselineTimestamp shouldBe fixture.anchorTime.toString()
                coVerify(exactly = 4) { repository.getSnapshotBefore(any()) }
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
            }
        }

        "a partially persisted record fails closed and recomputes" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                fixture.metadata.remove(SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_FINGERPRINT)

                val reloaded = service.getSettingsComparisonStatus(fixture.anchorTime)

                reloaded.comparisonAvailability shouldBe ComparisonAvailability.AVAILABLE
                reloaded.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                coVerify(exactly = 4) { repository.getSnapshotBefore(any()) }
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
            }
        }

        "a record written by another contract version is rejected and re-persisted" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_VERIFICATION_VERSION] = "999"

                service.getSettingsComparisonStatus(fixture.anchorTime)

                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_VERIFICATION_VERSION] shouldBe "1"
            }
        }

        "a confident resolution carrying an unavailable reason does not serve the record" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                coEvery { fixture.inceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
                    inceptionTime = fixture.anchorTime,
                    inceptionSnapshot = fixture.snapshotRows.first(),
                    isAutoDetected = false,
                    confidence = InceptionConfidence.CONFIDENT,
                    unavailableReason = ComparisonUnavailableReason.INCEPTION_AMBIGUOUS,
                )

                service.getSettingsComparisonStatus(fixture.anchorTime)

                coVerify(exactly = 4) { repository.getSnapshotBefore(any()) }
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
            }
        }

        "a stale reconstruction interval outside the verified interval does not invalidate" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                fixture.metadata[SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION] = "legacy"
                fixture.metadata[SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC] =
                    (fixture.anchorTime.epochSecond - 100).toString()

                val reloaded = service.getSettingsComparisonStatus(fixture.anchorTime)

                reloaded.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                reloaded.comparisonAvailability.shouldBeNull()
                coVerify(exactly = 3) { repository.getSnapshotBefore(any()) }
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
            }
        }

        "each missing record field fails closed and is re-persisted" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                val mutations = listOf<Pair<String, String?>>(
                    SyncMetadataKeys.INCEPTION_AUTO_BASELINE_TIMESTAMP_EPOCH_MS to null,
                    SyncMetadataKeys.INCEPTION_AUTO_BASELINE_INCEPTION_EPOCH_MS to null,
                    SyncMetadataKeys.INCEPTION_AUTO_BASELINE_SNAPSHOT_ID to null,
                    SyncMetadataKeys.INCEPTION_AUTO_BASELINE_SNAPSHOT_CURSOR to null,
                    SyncMetadataKeys.INCEPTION_AUTO_BASELINE_CONFIG_FINGERPRINT to null,
                    SyncMetadataKeys.INCEPTION_AUTO_BASELINE_ACCOUNT_SCOPE_DIGEST to null,
                    SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_FINGERPRINT to null,
                    SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS to null,
                    SyncMetadataKeys.INCEPTION_AUTO_BASELINE_TIMESTAMP_EPOCH_MS to
                        (fixture.anchorTime.toEpochMilli() + 1).toString(),
                )
                for ((key, value) in mutations) {
                    if (value == null) fixture.metadata.remove(key) else fixture.metadata[key] = value

                    service.getSettingsComparisonStatus(fixture.anchorTime)

                    fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
                }
                // First verification plus one full re-verification per mutation.
                coVerify(exactly = 2 + mutations.size * 2) { repository.getSnapshotBefore(any()) }
            }
        }

        "an already-invalidated record is skipped silently until re-verification succeeds" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] = "INVALIDATED"
                coEvery { repository.getSnapshotId(any(), any()) } returns null

                service.getSettingsComparisonStatus(fixture.anchorTime)

                // The full evaluation ran (digest consults the predecessor anchor again) and
                // its own re-persist aborted at the unresolvable id, so the record stays dead
                // instead of re-logging the same invalidation on every load.
                coVerify(exactly = 3) { repository.getSnapshotBefore(any()) }
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "INVALIDATED"
            }
        }

        "the predecessor anchor is part of the verified evidence" {
            runTest {
                val fixture = automaticBaselineFixture()
                val predecessor = snapshot(
                    fixture.anchorTime.minusSeconds(3600),
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                )
                coEvery { repository.getSnapshotBefore(any()) } returns predecessor
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                val firstFingerprint =
                    fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_FINGERPRINT]
                coEvery { repository.getSnapshotBefore(any()) } returns predecessor.copy(
                    totalValueUSD = BigDecimal("1050.00"),
                )

                service.getSettingsComparisonStatus(fixture.anchorTime)

                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "VERIFIED"
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_FINGERPRINT] shouldNotBe
                    firstFingerprint
                coVerify(exactly = 5) { repository.getSnapshotBefore(any()) }
            }
        }

        "a different proposal search window does not invalidate the persisted proof" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)

                val overridden =
                    service.getSettingsComparisonStatus(fixture.laterTime.plusSeconds(3600))

                overridden.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                overridden.baselineTimestamp shouldBe fixture.anchorTime.toString()
                overridden.comparisonAvailability.shouldBeNull()
                coVerify(exactly = 3) { repository.getSnapshotBefore(any()) }
            }
        }

        "newest live snapshot beyond ledger coverage is excluded from automatic baseline verification" {
            runTest {
                val fixture = automaticBaselineFixture()
                val midTime = fixture.anchorTime.plusSeconds(1800)
                fixture.snapshotRows += snapshot(
                    midTime,
                    "1050.00",
                    btc = "1.0" to "550.00",
                    usdBalance = "500.00",
                    balancesObservedAt = midTime.minusMillis(800),
                )
                fixture.ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 2).toString()
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(fixture.anchorTime)

                status.comparisonAvailability shouldBe ComparisonAvailability.AVAILABLE
                status.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                status.baselineTimestamp shouldBe fixture.anchorTime.toString()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS] shouldBe
                    midTime.toEpochMilli().toString()
            }
        }

        "newest live snapshot beyond trade coverage is excluded from automatic baseline verification" {
            runTest {
                val fixture = automaticBaselineFixture()
                val midTime = fixture.anchorTime.plusSeconds(1800)
                fixture.snapshotRows += snapshot(
                    midTime,
                    "1050.00",
                    btc = "1.0" to "550.00",
                    usdBalance = "500.00",
                    balancesObservedAt = midTime.minusMillis(800),
                )
                fixture.metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 2).toString()
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(fixture.anchorTime)

                status.comparisonAvailability shouldBe ComparisonAvailability.AVAILABLE
                status.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS] shouldBe
                    midTime.toEpochMilli().toString()
            }
        }

        "verification horizon is the minimum of ledger and trade coverage" {
            runTest {
                val fixture = automaticBaselineFixture()
                val midTime = fixture.anchorTime.plusSeconds(1800)
                fixture.snapshotRows += snapshot(
                    midTime,
                    "1050.00",
                    btc = "1.0" to "550.00",
                    usdBalance = "500.00",
                    balancesObservedAt = midTime.minusMillis(800),
                )
                fixture.metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 1).toString()
                fixture.ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 2).toString()
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(fixture.anchorTime)

                status.comparisonAvailability shouldBe ComparisonAvailability.AVAILABLE
                status.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS] shouldBe
                    midTime.toEpochMilli().toString()
            }
        }

        "balance observation time rather than snapshot write time decides live-tail eligibility" {
            runTest {
                val fixture = automaticBaselineFixture()
                fixture.metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 1).toString()
                fixture.ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 1).toString()
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(fixture.anchorTime)

                status.comparisonAvailability shouldBe ComparisonAvailability.AVAILABLE
                status.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS] shouldBe
                    ((fixture.laterTime.epochSecond - 1) * 1000 + 999).toString()
            }
        }

        "coverage catch-up makes a previously unstable snapshot eligible again" {
            runTest {
                val fixture = automaticBaselineFixture()
                val midTime = fixture.anchorTime.plusSeconds(1800)
                fixture.snapshotRows += snapshot(
                    midTime,
                    "1050.00",
                    btc = "1.0" to "550.00",
                    usdBalance = "500.00",
                    balancesObservedAt = midTime.minusMillis(800),
                )
                fixture.ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 2).toString()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS] shouldBe
                    midTime.toEpochMilli().toString()

                fixture.ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond + 60).toString()

                val caughtUp = service.getSettingsComparisonStatus(
                    fixture.anchorTime,
                    allowPersistedBaselineFastPath = false,
                )

                caughtUp.comparisonAvailability shouldBe ComparisonAvailability.AVAILABLE
                caughtUp.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS] shouldBe
                    fixture.laterTime.toEpochMilli().toString()
            }
        }

        "a covered snapshot reappearing after an uncovered one defers verification" {
            runTest {
                val fixture = automaticBaselineFixture()
                val uncoveredInterior = snapshot(
                    fixture.laterTime,
                    "1150.00",
                    btc = "1.1" to "600.00",
                    usdBalance = "550.00",
                    balancesObservedAt = fixture.laterTime.plusSeconds(60),
                )
                fixture.snapshotRows += uncoveredInterior
                fixture.snapshotRows += snapshot(
                    fixture.laterTime.plusSeconds(5),
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = fixture.laterTime.minusSeconds(100),
                )
                fixture.metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 2).toString()
                fixture.ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 2).toString()
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(
                    fixture.anchorTime,
                    allowPersistedBaselineFastPath = false,
                )

                status.comparisonAvailability.shouldBeNull()
                status.baselineStatus.shouldBeNull()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS].shouldBeNull()
            }
        }

        "a second uncovered snapshot after a covered re-entry also defers verification" {
            runTest {
                val fixture = automaticBaselineFixture()
                fixture.snapshotRows += snapshot(
                    fixture.laterTime,
                    "1150.00",
                    btc = "1.1" to "600.00",
                    usdBalance = "550.00",
                    balancesObservedAt = fixture.laterTime.plusSeconds(60),
                )
                fixture.snapshotRows += snapshot(
                    fixture.laterTime.plusSeconds(5),
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = fixture.laterTime.minusSeconds(100),
                )
                fixture.snapshotRows += snapshot(
                    fixture.laterTime.plusSeconds(10),
                    "1000.00",
                    btc = "1.0" to "500.00",
                    usdBalance = "500.00",
                    balancesObservedAt = fixture.laterTime.plusSeconds(120),
                )
                fixture.metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 2).toString()
                fixture.ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 2).toString()
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(
                    fixture.anchorTime,
                    allowPersistedBaselineFastPath = false,
                )

                status.comparisonAvailability.shouldBeNull()
                status.baselineStatus.shouldBeNull()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS].shouldBeNull()
            }
        }

        "multiple unstable tail snapshots are trimmed together" {
            runTest {
                val fixture = automaticBaselineFixture()
                val midTime = fixture.anchorTime.plusSeconds(1800)
                val tailTime = fixture.laterTime.plusSeconds(1800)
                fixture.snapshotRows += snapshot(
                    midTime,
                    "1050.00",
                    btc = "1.0" to "550.00",
                    usdBalance = "500.00",
                    balancesObservedAt = midTime.minusMillis(800),
                )
                fixture.snapshotRows += snapshot(
                    tailTime,
                    "1200.00",
                    btc = "1.0" to "650.00",
                    usdBalance = "500.00",
                    balancesObservedAt = tailTime.minusMillis(800),
                )
                fixture.snapshotRows += snapshot(
                    fixture.laterTime.plusSeconds(3600),
                    "1250.00",
                    btc = "1.0" to "700.00",
                    usdBalance = "500.00",
                    balancesObservedAt = fixture.laterTime.plusSeconds(3600).minusMillis(800),
                )
                fixture.metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 2).toString()
                fixture.ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 2).toString()
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(fixture.anchorTime)

                status.comparisonAvailability shouldBe ComparisonAvailability.AVAILABLE
                status.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS] shouldBe
                    midTime.toEpochMilli().toString()
            }
        }

        "real reconciliation failure inside the stable horizon still fails closed" {
            runTest {
                val fixture = automaticBaselineFixture()
                val midTime = fixture.anchorTime.plusSeconds(1800)
                fixture.snapshotRows += snapshot(
                    midTime,
                    "1000.00",
                    btc = "0.9" to "500.00",
                    usdBalance = "550.00",
                    balancesObservedAt = midTime.minusMillis(800),
                )
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(
                    fixture.anchorTime,
                    allowPersistedBaselineFastPath = false,
                )

                status.comparisonAvailability shouldBe ComparisonAvailability.UNAVAILABLE
                status.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
                status.unavailableAt shouldBe midTime.toString()
                status.baselineStatus.shouldBeNull()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS].shouldBeNull()
            }
        }

        "fewer than two stable snapshots defers baseline verification" {
            runTest {
                val fixture = automaticBaselineFixture()
                fixture.metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.anchorTime.epochSecond - 2).toString()
                fixture.ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.anchorTime.epochSecond - 2).toString()
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(fixture.anchorTime)

                status.comparisonAvailability.shouldBeNull()
                status.baselineStatus.shouldBeNull()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS].shouldBeNull()
            }
        }

        "missing certified coverage metadata defers baseline verification" {
            runTest {
                val fixture = automaticBaselineFixture()
                fixture.metadata.remove(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC)
                fixture.ledgerMetadata.remove(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC)
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(fixture.anchorTime)

                status.comparisonAvailability.shouldBeNull()
                status.baselineStatus.shouldBeNull()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS].shouldBeNull()
            }
        }

        "blank coverage scopes defer baseline verification outside simulation" {
            runTest {
                val fixture = automaticBaselineFixture()
                fixture.metadata.remove(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                fixture.metadata.remove(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST)
                fixture.ledgerMetadata.remove(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST)
                val productionConfigService = mockk<ConfigService>()
                every { productionConfigService.getConfig() } returns TestFixtures.config(
                    settings = TestFixtures.settings(simulation = false),
                )
                val service = TradeHistoryQueryService(
                    repository = repository,
                    portfolioStatsRepository = statsRepository,
                    ledgerRepository = ledgerRepository,
                    orderIntentRepository = orderIntentRepository,
                    inceptionDiscoveryService = fixture.inceptionService,
                    fundingProvenanceResolver = fixture.fundingProvenanceResolver,
                    nowProvider = { now },
                    configService = productionConfigService,
                )

                val status = service.getSettingsComparisonStatus(fixture.anchorTime)

                status.comparisonAvailability.shouldBeNull()
                status.baselineStatus.shouldBeNull()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS].shouldBeNull()
            }
        }

        "persisted fast path is invalidated when certified coverage regresses" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                val persistedDigest = fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_FINGERPRINT]

                val tailTime = fixture.laterTime.plusSeconds(3600)
                fixture.snapshotRows += snapshot(
                    tailTime,
                    "1200.00",
                    btc = "1.0" to "700.00",
                    usdBalance = "500.00",
                    balancesObservedAt = tailTime.minusMillis(800),
                )
                fixture.ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond - 2).toString()
                val reloaded = automaticBaselineService(fixture)

                val status = reloaded.getSettingsComparisonStatus(fixture.anchorTime)

                status.baselineStatus.shouldBeNull()
                status.baselineTimestamp.shouldBeNull()
                status.comparisonAvailability.shouldBeNull()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "INVALIDATED"
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_FINGERPRINT] shouldBe
                    persistedDigest
            }
        }

        "persisted fast path defers when a valid epoch-second horizon overflows epoch milliseconds" {
            runTest {
                val fixture = automaticBaselineFixture()
                val service = automaticBaselineService(fixture)
                service.getSettingsComparisonStatus(fixture.anchorTime)
                val validButUnrepresentableEpochSecond = "9223372036854776"
                fixture.metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] =
                    validButUnrepresentableEpochSecond
                fixture.ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    validButUnrepresentableEpochSecond

                val status = automaticBaselineService(fixture).getSettingsComparisonStatus(fixture.anchorTime)

                status.baselineStatus.shouldBeNull()
                status.baselineTimestamp.shouldBeNull()
                status.comparisonAvailability.shouldBeNull()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS] shouldBe "INVALIDATED"
            }
        }

        "post-sell live snapshot beyond confirmed coverage fails the evaluation without coverage gating" {
            runTest {
                val fixture = automaticBaselineFixture()
                val postSellTime = fixture.laterTime.plusSeconds(7).plusMillis(819)
                fixture.snapshotRows += snapshot(
                    postSellTime,
                    "1005.00",
                    btc = "0.5" to "520.00",
                    usdBalance = "745.00",
                    balancesObservedAt = postSellTime.minusMillis(800),
                )
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(
                    fixture.anchorTime,
                    allowPersistedBaselineFastPath = false,
                )

                status.comparisonAvailability shouldBe ComparisonAvailability.UNAVAILABLE
                status.unavailableReason shouldBe ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE
                status.unavailableAt shouldBe postSellTime.toString()
                status.baselineStatus.shouldBeNull()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_STATUS].shouldBeNull()
            }
        }

        "post-sell live snapshot beyond confirmed coverage verifies through the stable horizon" {
            runTest {
                val fixture = automaticBaselineFixture()
                val postSellTime = fixture.laterTime.plusSeconds(7).plusMillis(819)
                fixture.snapshotRows += snapshot(
                    postSellTime,
                    "1005.00",
                    btc = "0.5" to "520.00",
                    usdBalance = "745.00",
                    balancesObservedAt = postSellTime.minusMillis(800),
                )
                fixture.metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond + 1).toString()
                fixture.ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond + 1).toString()
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(fixture.anchorTime)

                status.comparisonAvailability shouldBe ComparisonAvailability.AVAILABLE
                status.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                status.baselineTimestamp shouldBe fixture.anchorTime.toString()
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS] shouldBe
                    fixture.laterTime.toEpochMilli().toString()
            }
        }

        "evidence horizon is capped at the stable verification horizon when write time runs ahead" {
            runTest {
                val fixture = automaticBaselineFixture()
                val lateWriteTime = fixture.laterTime.plusSeconds(10)
                fixture.snapshotRows += snapshot(
                    lateWriteTime,
                    "1100.00",
                    btc = "1.0" to "600.00",
                    usdBalance = "500.00",
                    balancesObservedAt = fixture.laterTime.minusMillis(700),
                )
                fixture.metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond + 1).toString()
                fixture.ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] =
                    (fixture.laterTime.epochSecond + 1).toString()
                val service = automaticBaselineService(fixture)

                val status = service.getSettingsComparisonStatus(fixture.anchorTime)

                status.comparisonAvailability shouldBe ComparisonAvailability.AVAILABLE
                status.baselineStatus shouldBe AutomaticBaselineStatus.VERIFIED
                fixture.metadata[SyncMetadataKeys.INCEPTION_AUTO_BASELINE_EVIDENCE_HORIZON_MS] shouldBe
                    ((fixture.laterTime.epochSecond + 1) * 1000 + 999).toString()
            }
        }
    }

    private fun persistedCacheFingerprint(database: Database): String? = transaction(database) {
        RebalancerComparisonCacheTable.selectAll()
            .map { it[RebalancerComparisonCacheTable.inputFingerprint] }
            .singleOrNull()
    }

    private fun snapshot(
        timestamp: Instant,
        totalValueUSD: String,
        btc: Pair<String, String>,
        usdBalance: String = "50000.00",
        balancesObservedAt: Instant? = timestamp,
    ): PortfolioSnapshot {
        val (btcBalance, btcPrice) = btc
        val btcValue = BigDecimal(btcBalance).multiply(BigDecimal(btcPrice))
        val usdVal = BigDecimal(usdBalance)
        return PortfolioSnapshot(
            timestamp = timestamp,
            totalValueUSD = BigDecimal(totalValueUSD),
            assets = mapOf(
                Asset.BTC to TestFixtures.assetSnapshot(
                    symbol = Asset.BTC,
                    balance = BigDecimal(btcBalance),
                    price = BigDecimal(btcPrice),
                    valueUSD = btcValue,
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
            balancesObservedAt = balancesObservedAt,
        )
    }

    private fun corruptHoldingSnapshot(
        timestamp: Instant,
        symbol: String,
        balance: String,
        price: String,
        valueUSD: String,
    ): PortfolioSnapshot {
        val base = snapshot(
            timestamp,
            "1000.00",
            btc = "0.001" to "100000.00",
            usdBalance = "900.00",
        )
        return base.copy(
            assets = base.assets + (
                symbol to TestFixtures.assetSnapshot(
                    symbol = symbol,
                    balance = BigDecimal(balance),
                    price = BigDecimal(price),
                    valueUSD = BigDecimal(valueUSD),
                    targetPercent = BigDecimal.ZERO,
                )
                ),
        )
    }

    private fun ledgerEvent(
        ledgerId: String,
        timestamp: Instant,
        asset: String,
        amount: String,
        type: String = KrakenApiConstants.LEDGER_TYPE_STAKING,
        fee: String = "0",
        refid: String? = null,
    ): LedgerEvent {
        val resolvedRefid = refid ?: when (type) {
            KrakenApiConstants.LEDGER_TYPE_DEPOSIT -> {
                val norm = Asset.normalizeLedgerAsset(asset).uppercase()
                if (norm == Asset.USD) "FT-$ledgerId" else "tx-$ledgerId"
            }

            KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL -> "WIRE-$ledgerId"

            else -> null
        }
        return LedgerEvent(
            ledgerId = ledgerId,
            refid = resolvedRefid,
            time = timestamp,
            type = type,
            asset = asset,
            amount = BigDecimal(amount),
            fee = BigDecimal(fee),
        )
    }

    /** Shared state for the durable automatic baseline verification tests. */
    private data class AutomaticBaselineFixture(
        val anchorTime: Instant,
        val laterTime: Instant,
        val metadata: MutableMap<String, String>,
        val ledgerMetadata: MutableMap<String, String>,
        val snapshotRows: MutableList<PortfolioSnapshot>,
        val inceptionService: InceptionDiscoveryService,
        val fundingProvenanceResolver: FundingProvenanceResolver,
    )

    private fun automaticBaselineFixture(anchorOffsetSeconds: Long = 86400L): AutomaticBaselineFixture {
        val anchorTime = now.minusSeconds(anchorOffsetSeconds)
        val laterTime = anchorTime.plusSeconds(3600)
        val metadata = mutableMapOf<String, String>()
        val ledgerMetadata = mutableMapOf<String, String>()
        // Certified coverage defaults; tests that shape the live tail override these.
        metadata[SyncMetadataKeys.TRADE_COVERAGE_VERSION] = TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
        metadata[SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC] = "0"
        metadata[SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC] = "4102444800"
        metadata[SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST] = "test-scope"
        metadata[SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST] = "test-scope"
        ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_VERSION] = LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
        ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC] = "0"
        ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC] = "4102444800"
        ledgerMetadata[SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST] = "test-scope"
        val anchorSnapshot = snapshot(
            anchorTime,
            "1000.00",
            btc = "1.0" to "500.00",
            usdBalance = "500.00",
            balancesObservedAt = anchorTime.minusMillis(800),
        )
        val laterSnapshot = snapshot(
            laterTime,
            "1100.00",
            btc = "1.0" to "600.00",
            usdBalance = "500.00",
            balancesObservedAt = laterTime.minusMillis(700),
        )
        val snapshotRows = mutableListOf(anchorSnapshot, laterSnapshot)
        val inceptionService = mockk<InceptionDiscoveryService>(relaxed = true)
        coEvery { inceptionService.resolveInceptionUnderEvidenceLock() } returns InceptionResolution(
            inceptionTime = anchorTime,
            inceptionSnapshot = anchorSnapshot,
            isAutoDetected = false,
            confidence = InceptionConfidence.CONFIDENT,
        )
        val fundingProvenanceResolver = mockk<FundingProvenanceResolver>()
        coEvery { fundingProvenanceResolver.resolve(any()) } returns FundingEvidence.UNRESOLVED
        coEvery { fundingProvenanceResolver.isCardFunding(any()) } returns false
        coEvery { fundingProvenanceResolver.preparationFailure } returns null
        coEvery { fundingProvenanceResolver.evidenceFingerprint } returns null
        coEvery { fundingProvenanceResolver.prepare(any()) } coAnswers { fundingProvenanceResolver }
        coEvery { repository.getSyncMetadata(any()) } coAnswers { metadata[firstArg()] }
        coEvery { repository.setSyncMetadata(any(), any()) } coAnswers { metadata[firstArg()] = secondArg() }
        coEvery { repository.setSyncMetadataAtomically(any()) } coAnswers { metadata.putAll(firstArg()) }
        coEvery { ledgerRepository.getSyncMetadata(any()) } coAnswers { ledgerMetadata[firstArg()] }
        coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns emptyList()
        coEvery { repository.getAllSnapshotsInRange(any(), any()) } coAnswers {
            snapshotRows.filter { !it.timestamp.isBefore(firstArg()) && !it.timestamp.isAfter(secondArg()) }
        }
        coEvery { repository.getSnapshotBefore(any()) } returns null
        coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
        coEvery { repository.getSnapshotId(any(), any()) } coAnswers {
            snapshotRows.filter { it.timestamp == firstArg<Instant>() }.getOrNull(secondArg<Int>())?.hashCode()
        }
        return AutomaticBaselineFixture(
            anchorTime,
            laterTime,
            metadata,
            ledgerMetadata,
            snapshotRows,
            inceptionService,
            fundingProvenanceResolver,
        )
    }

    private fun automaticBaselineService(
        fixture: AutomaticBaselineFixture,
        comparisonCacheRepository: RebalancerComparisonCacheRepository? = null,
    ) = TradeHistoryQueryService(
        repository = repository,
        portfolioStatsRepository = statsRepository,
        ledgerRepository = ledgerRepository,
        orderIntentRepository = orderIntentRepository,
        inceptionDiscoveryService = fixture.inceptionService,
        fundingProvenanceResolver = fixture.fundingProvenanceResolver,
        comparisonCacheRepository = comparisonCacheRepository,
        nowProvider = { now },
    )
}

private class CountingComparisonCache(private val delegate: RebalancerComparisonCacheRepository) :
    RebalancerComparisonCacheRepository {
    var loadCount = 0
        private set
    var saveCount = 0
        private set
    var updateOhlcCount = 0
        private set
    var deleteCount = 0
        private set

    override suspend fun load(fromEpochMillis: Long, toEpochMillis: Long): RebalancerComparisonCacheEntry? {
        loadCount++
        return delegate.load(fromEpochMillis, toEpochMillis)
    }

    override suspend fun save(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        inputFingerprint: String,
        comparison: com.gemini.krakenbot.model.RebalancerComparison,
        ohlcDependencies: List<ConsumedOhlcDependency>,
        ohlcReachabilityDependencies: List<OhlcReachabilityDependency>,
    ) {
        saveCount++
        delegate.save(
            fromEpochMillis,
            toEpochMillis,
            inputFingerprint,
            comparison,
            ohlcDependencies,
            ohlcReachabilityDependencies,
        )
    }

    override suspend fun updateOhlcDependencies(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        ohlcDependencies: List<ConsumedOhlcDependency>,
    ) {
        updateOhlcCount++
        delegate.updateOhlcDependencies(fromEpochMillis, toEpochMillis, ohlcDependencies)
    }

    override suspend fun delete(fromEpochMillis: Long, toEpochMillis: Long) {
        deleteCount++
        delegate.delete(fromEpochMillis, toEpochMillis)
    }

    override suspend fun updateOhlcDependenciesIfExpected(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        expectedOhlcDependencies: List<ConsumedOhlcDependency>,
        ohlcDependencies: List<ConsumedOhlcDependency>,
    ): Boolean {
        updateOhlcCount++
        return delegate.updateOhlcDependenciesIfExpected(
            fromEpochMillis,
            toEpochMillis,
            expectedOhlcDependencies,
            ohlcDependencies,
        )
    }
}

private class InMemoryComparisonCache : RebalancerComparisonCacheRepository {
    private val stored = mutableMapOf<Pair<Long, Long>, RebalancerComparisonCacheEntry>()
    var loadCount = 0
        private set
    var saveCount = 0
        private set
    var updateOhlcCount = 0
        private set
    var deleteCount = 0
        private set
    val size: Int
        get() = stored.size

    override suspend fun load(fromEpochMillis: Long, toEpochMillis: Long): RebalancerComparisonCacheEntry? {
        loadCount++
        return stored[fromEpochMillis to toEpochMillis]
    }

    override suspend fun save(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        inputFingerprint: String,
        comparison: com.gemini.krakenbot.model.RebalancerComparison,
        ohlcDependencies: List<ConsumedOhlcDependency>,
        ohlcReachabilityDependencies: List<OhlcReachabilityDependency>,
    ) {
        saveCount++
        stored[fromEpochMillis to toEpochMillis] = RebalancerComparisonCacheEntry(
            inputFingerprint = inputFingerprint,
            comparison = comparison,
            ohlcDependencies = ohlcDependencies,
            ohlcReachabilityDependencies = ohlcReachabilityDependencies,
        )
    }

    override suspend fun updateOhlcDependencies(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        ohlcDependencies: List<ConsumedOhlcDependency>,
    ) {
        updateOhlcCount++
        stored[fromEpochMillis to toEpochMillis]?.let { existing ->
            stored[fromEpochMillis to toEpochMillis] = existing.copy(ohlcDependencies = ohlcDependencies)
        }
    }

    override suspend fun delete(fromEpochMillis: Long, toEpochMillis: Long) {
        deleteCount++
        stored.remove(fromEpochMillis to toEpochMillis)
    }

    override suspend fun updateOhlcDependenciesIfExpected(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        expectedOhlcDependencies: List<ConsumedOhlcDependency>,
        ohlcDependencies: List<ConsumedOhlcDependency>,
    ): Boolean {
        updateOhlcCount++
        val existing = stored[fromEpochMillis to toEpochMillis]
        if (existing == null || existing.ohlcDependencies != expectedOhlcDependencies) return false
        stored[fromEpochMillis to toEpochMillis] = existing.copy(ohlcDependencies = ohlcDependencies)
        return true
    }
}

private class ThrowingComparisonCache : RebalancerComparisonCacheRepository {
    override suspend fun load(fromEpochMillis: Long, toEpochMillis: Long): RebalancerComparisonCacheEntry =
        error("cache read failure")

    override suspend fun save(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        inputFingerprint: String,
        comparison: com.gemini.krakenbot.model.RebalancerComparison,
        ohlcDependencies: List<ConsumedOhlcDependency>,
        ohlcReachabilityDependencies: List<OhlcReachabilityDependency>,
    ) {
        error("cache write failure")
    }

    override suspend fun updateOhlcDependencies(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        ohlcDependencies: List<ConsumedOhlcDependency>,
    ) {
        error("cache update failure")
    }

    override suspend fun delete(fromEpochMillis: Long, toEpochMillis: Long) {
        error("cache delete failure")
    }

    override suspend fun updateOhlcDependenciesIfExpected(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        expectedOhlcDependencies: List<ConsumedOhlcDependency>,
        ohlcDependencies: List<ConsumedOhlcDependency>,
    ): Boolean {
        error("cache update failure")
    }
}
