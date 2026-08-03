@file:OptIn(ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.OrderSubmissionState
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.repository.TradeSummaryStats
import com.gemini.krakenbot.service.impl.DynamicKrakenService
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import com.gemini.krakenbot.service.impl.SimulatedKrakenService
import com.gemini.krakenbot.service.impl.history.TradeHistoryReconstructionService
import com.gemini.krakenbot.service.impl.history.TradeHistoryServiceImpl
import com.gemini.krakenbot.util.TradeCalculator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.milliseconds

class TradeHistoryServiceTest : TradeHistoryServiceTestBase() {

    init {
        "init_LoadsHistoryFromRepository" {
            runTest {
                val tradeHistoryService = createService()
                val snapshot = TestFixtures.emptySnapshot(Instant.now(), BigDecimal.ZERO)
                coEvery { repository.load() } returns listOf(snapshot)
                tradeHistoryService.init()
                tradeHistoryService.getHistory().size shouldBe 1
                tradeHistoryService.getLatestSnapshot() shouldBe snapshot
            }
        }

        "CQ-11-4: init propagates cancellation from duplicate cleanup" {
            runTest {
                val tradeHistoryService = createService()
                coEvery { repository.cleanupDuplicateTrades() } throws CancellationException("stop startup")

                shouldThrow<CancellationException> { tradeHistoryService.init() }

                coVerify(exactly = 0) { repository.load() }
            }
        }

        "CQ-11-4: init recovers from ordinary duplicate cleanup failure" {
            runTest {
                val tradeHistoryService = createService()
                coEvery { repository.cleanupDuplicateTrades() } throws RuntimeException("cleanup failed")

                tradeHistoryService.init()

                coVerify(exactly = 1) { repository.load() }
            }
        }

        "addSnapshot_AddsToFrontAndSaves" {
            runTest {
                val tradeHistoryService = createService()
                val s1 = TestFixtures.emptySnapshot(Instant.now().minusMillis(10), BigDecimal.ZERO)
                val s2 = TestFixtures.emptySnapshot(Instant.now(), BigDecimal.ZERO)
                tradeHistoryService.addSnapshot(s1)
                tradeHistoryService.addSnapshot(s2)
                tradeHistoryService.getHistory().size shouldBe 2
                tradeHistoryService.getLatestSnapshot() shouldBe s2
                coVerify(exactly = 1) { repository.saveSnapshot(s1) }
                coVerify(exactly = 1) { repository.saveSnapshot(s2) }
            }
        }

        "addSnapshot_LimitsHistorySize" {
            runTest {
                val tradeHistoryService = createService()
                repeat(60) {
                    tradeHistoryService.addSnapshot(TestFixtures.emptySnapshot(Instant.now(), BigDecimal.ZERO))
                }
                tradeHistoryService.getHistory().size shouldBe 50
                coVerify(atLeast = 1) { repository.saveSnapshot(any()) }
            }
        }

        "init_HandlesNullLoaded" {
            runTest {
                val tradeHistoryService = createService()
                coEvery { repository.load() } returns emptyList()
                tradeHistoryService.init()
                tradeHistoryService.getHistory().isEmpty().shouldBeTrue()
            }
        }

        "getHistoryFlow_EmitsSnapshotsOnAdd" {
            runTest {
                val tradeHistoryService = createService()
                val snapshots = mutableListOf<PortfolioSnapshot>()

                val job = launch {
                    tradeHistoryService.getHistoryFlow().collect {
                        snapshots.add(it)
                    }
                }

                yield()

                val s1 = TestFixtures.emptySnapshot(Instant.now(), BigDecimal.ZERO)
                tradeHistoryService.addSnapshot(s1)

                yield()

                snapshots.size shouldBe 1
                snapshots.first() shouldBe s1

                job.cancel()
            }
        }

        "getLatestSnapshot_ReturnsNullWhenEmpty" {
            runTest {
                val tradeHistoryService = createService()
                tradeHistoryService.getLatestSnapshot().shouldBeNull()
            }
        }

        "saveTrade_DelegatesToRepository" {
            runTest {
                val tradeHistoryService = createService()
                val trade = TestFixtures.tradeRecord(
                    timestamp = Instant.now(),
                    pair = Asset.BTC_USD_PAIR,
                    side = OrderSide.BUY.name,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                )
                tradeHistoryService.saveTrade(trade)
                coVerify(exactly = 1) { repository.saveTrade(trade) }
            }
        }

        "getSnapshotsInRange_DelegatesToRepository" {
            runTest {
                val tradeHistoryService = createService()
                val from = Instant.now()
                val to = Instant.now()
                tradeHistoryService.getSnapshotsInRange(from, to)
                coVerify(exactly = 1) { repository.getSnapshotsInRange(from, to) }
            }
        }

        "getTradesInRange_DelegatesToRepository" {
            runTest {
                val tradeHistoryService = createService()
                val from = Instant.now()
                val to = Instant.now()
                tradeHistoryService.getTradesInRange(from, to)
                coVerify(exactly = 1) { repository.getTradesInRange(from, to) }
            }
        }

        "getRebalancerComparison_DoesNotQueryTradesForInsufficientSnapshots" {
            runTest {
                val tradeHistoryService = createService()
                val from = Instant.parse("2026-07-01T00:00:00Z")
                val to = Instant.parse("2026-07-02T00:00:00Z")
                coEvery { repository.getSnapshotsInRange(from, to) } returns
                    listOf(TestFixtures.emptySnapshot(timestamp = from, totalValueUSD = BigDecimal.ONE))

                tradeHistoryService.getRebalancerComparison(from, to)

                coVerify(exactly = 1) { repository.getSnapshotsInRange(from, to) }
                coVerify(exactly = 0) { repository.getTradesInRange(any(), any()) }
            }
        }

        "getRebalancerComparison_QueriesTradesBetweenSelectedSnapshotEndpoints" {
            runTest {
                val tradeHistoryService = createService()
                val from = Instant.parse("2026-07-01T00:00:00Z")
                val baseline = from.plusSeconds(60)
                val last = from.plusSeconds(120)
                val to = from.plusSeconds(180)
                coEvery { repository.getSnapshotsInRange(from, to) } returns
                    listOf(
                        TestFixtures.emptySnapshot(timestamp = baseline, totalValueUSD = BigDecimal.ONE),
                        TestFixtures.emptySnapshot(timestamp = last, totalValueUSD = BigDecimal.ONE),
                    )

                tradeHistoryService.getRebalancerComparison(from, to)

                coVerify(exactly = 1) { repository.getTradesInRange(baseline, last) }
            }
        }

        "getHistoryStats_AggregatesCorrectly" {
            runTest {
                val tradeHistoryService = createService()

                coEvery { statsRepository.load() } returns PortfolioStats(BigDecimal("12345.67"))
                val latestTime = Instant.now()
                coEvery { repository.getTradeSummaryStats() } returns TradeSummaryStats(
                    totalTradesExecuted = 42L,
                    totalVolumeTraded = BigDecimal("98765.43"),
                    totalFeesPaid = BigDecimal("12.34"),
                    latestSnapshotTime = latestTime,
                )

                val stats = tradeHistoryService.getHistoryStats()
                stats.allTimeHigh.shouldBeEqualComparingTo(BigDecimal("12345.67"))
                stats.totalTradesExecuted shouldBe 42L
                stats.totalVolumeTraded.shouldBeEqualComparingTo(BigDecimal("98765.43"))
                stats.totalFeesPaid.shouldBeEqualComparingTo(BigDecimal("12.34"))
                stats.latestSnapshotTime shouldBe latestTime
            }
        }

        "getHistoryStats_NoArg_PrefersPeriodHighAboveStoredAth" {
            runTest {
                val tradeHistoryService = createService()

                coEvery { statsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery { repository.getTradeSummaryStats() } returns TradeSummaryStats(
                    totalTradesExecuted = 5L,
                    totalVolumeTraded = BigDecimal("600.00"),
                    totalFeesPaid = BigDecimal("0.60"),
                    latestSnapshotTime = Instant.now(),
                    periodHigh = BigDecimal("18000.00"),
                )

                val stats = tradeHistoryService.getHistoryStats()
                stats.allTimeHigh.shouldBeEqualComparingTo(BigDecimal("18000.00"))
                coVerify(exactly = 1) { repository.getTradeSummaryStats() }
                coVerify(exactly = 0) { repository.getTradeSummaryStats(any(), any()) }
            }
        }

        "getHistoryStats_WithRange_AggregatesCorrectly" {
            runTest {
                val tradeHistoryService = createService()

                val from = Instant.now().minus(7, ChronoUnit.DAYS)
                val to = Instant.now()
                val latestTime = Instant.now()
                coEvery { repository.getTradeSummaryStats(from, to) } returns TradeSummaryStats(
                    totalTradesExecuted = 10L,
                    totalVolumeTraded = BigDecimal("5000.00"),
                    totalFeesPaid = BigDecimal("5.00"),
                    latestSnapshotTime = latestTime,
                    periodHigh = BigDecimal("14000.00"),
                )

                val stats = tradeHistoryService.getHistoryStats(from, to)
                stats.allTimeHigh.shouldBeEqualComparingTo(BigDecimal("14000.00"))
                stats.totalTradesExecuted shouldBe 10L
                stats.totalVolumeTraded.shouldBeEqualComparingTo(BigDecimal("5000.00"))
                stats.totalFeesPaid.shouldBeEqualComparingTo(BigDecimal("5.00"))
                stats.latestSnapshotTime shouldBe latestTime
            }
        }
    }
}
