package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.HistoryStats
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.impl.TradeHistoryServiceImpl
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.math.BigDecimal
import java.time.Instant

@Suppress("unused")
class TradeHistoryServiceTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "init_LoadsHistoryFromRepository" {
            val repository = mockk<TradeRepository>(relaxed = true)
            val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
            val tradeHistoryService = TradeHistoryServiceImpl(repository, statsRepository)
            val snapshot = PortfolioSnapshot(
                timestamp = Instant.now(),
                totalValueUSD = BigDecimal.ZERO,
                assets = emptyMap(),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO
            )
            every { repository.load() } returns listOf(snapshot)
            tradeHistoryService.init()
            tradeHistoryService.getHistory().size shouldBe 1
            tradeHistoryService.getLatestSnapshot() shouldBe snapshot
        }

        "addSnapshot_AddsToFrontAndSaves" {
            val repository = mockk<TradeRepository>(relaxed = true)
            val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
            val tradeHistoryService = TradeHistoryServiceImpl(repository, statsRepository)
            val s1 = PortfolioSnapshot(
                timestamp = Instant.now(),
                totalValueUSD = BigDecimal.ZERO,
                assets = emptyMap(),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO
            )
            val s2 = PortfolioSnapshot(
                timestamp = Instant.now(),
                totalValueUSD = BigDecimal.ZERO,
                assets = emptyMap(),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO
            )
            tradeHistoryService.addSnapshot(s1)
            tradeHistoryService.addSnapshot(s2)
            tradeHistoryService.getHistory().size shouldBe 2
            tradeHistoryService.getLatestSnapshot() shouldBe s2
            verify(exactly = 1) { repository.saveSnapshot(s1) }
            verify(exactly = 1) { repository.saveSnapshot(s2) }
        }

        "addSnapshot_LimitsHistorySize" {
            val repository = mockk<TradeRepository>(relaxed = true)
            val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
            val tradeHistoryService = TradeHistoryServiceImpl(repository, statsRepository)
            repeat(60) {
                tradeHistoryService.addSnapshot(
                    PortfolioSnapshot(
                        timestamp = Instant.now(),
                        totalValueUSD = BigDecimal.ZERO,
                        assets = emptyMap(),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO
                    )
                )
            }
            tradeHistoryService.getHistory().size shouldBe 50
            verify(atLeast = 1) { repository.saveSnapshot(any()) }
        }

        "init_HandlesNullLoaded" {
            val repository = mockk<TradeRepository>(relaxed = true)
            val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
            val tradeHistoryService = TradeHistoryServiceImpl(repository, statsRepository)
            every { repository.load() } returns emptyList()
            tradeHistoryService.init()
            tradeHistoryService.getHistory().isEmpty().shouldBeTrue()
        }

        "getHistoryFlow_EmitsSnapshotsOnAdd" {
            runTest {
                val repository = mockk<TradeRepository>(relaxed = true)
                val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
                val tradeHistoryService = TradeHistoryServiceImpl(repository, statsRepository)
                val snapshots = mutableListOf<PortfolioSnapshot>()

                val job = launch {
                    tradeHistoryService.getHistoryFlow().collect {
                        snapshots.add(it)
                    }
                }

                yield()

                val s1 = PortfolioSnapshot(
                    timestamp = Instant.now(),
                    totalValueUSD = BigDecimal.ZERO,
                    assets = emptyMap(),
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO
                )
                tradeHistoryService.addSnapshot(s1)

                yield()

                snapshots.size shouldBe 1
                snapshots.first() shouldBe s1

                job.cancel()
            }
        }

        "getLatestSnapshot_ReturnsNullWhenEmpty" {
            val repository = mockk<TradeRepository>(relaxed = true)
            val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
            val tradeHistoryService = TradeHistoryServiceImpl(repository, statsRepository)
            tradeHistoryService.getLatestSnapshot().shouldBeNull()
        }

        "saveTrade_DelegatesToRepository" {
            val repository = mockk<TradeRepository>(relaxed = true)
            val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
            val tradeHistoryService = TradeHistoryServiceImpl(repository, statsRepository)
            val trade = TradeRecord(
                timestamp = Instant.now(),
                pair = "XBTUSD",
                side = "BUY",
                symbol = "BTC",
                volume = BigDecimal.ONE,
                usdAmount = BigDecimal.TEN,
                success = true,
                dryRun = false
            )
            tradeHistoryService.saveTrade(trade)
            verify(exactly = 1) { repository.saveTrade(trade) }
        }

        "getSnapshotsInRange_DelegatesToRepository" {
            val repository = mockk<TradeRepository>(relaxed = true)
            val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
            val tradeHistoryService = TradeHistoryServiceImpl(repository, statsRepository)
            val from = Instant.now()
            val to = Instant.now()
            tradeHistoryService.getSnapshotsInRange(from, to)
            verify(exactly = 1) { repository.getSnapshotsInRange(from, to) }
        }

        "getTradesInRange_DelegatesToRepository" {
            val repository = mockk<TradeRepository>(relaxed = true)
            val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
            val tradeHistoryService = TradeHistoryServiceImpl(repository, statsRepository)
            val from = Instant.now()
            val to = Instant.now()
            tradeHistoryService.getTradesInRange(from, to)
            verify(exactly = 1) { repository.getTradesInRange(from, to) }
        }

        "getHistoryStats_AggregatesCorrectly" {
            val repository = mockk<TradeRepository>(relaxed = true)
            val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
            val tradeHistoryService = TradeHistoryServiceImpl(repository, statsRepository)

            every { statsRepository.load() } returns PortfolioStats(BigDecimal("12345.67"))
            every { repository.getTotalTradeCount() } returns 42L
            every { repository.getTotalVolumeTraded() } returns BigDecimal("98765.43")
            val firstTime = Instant.now().minusSeconds(86400)
            val latestTime = Instant.now()
            every { repository.getFirstSnapshotTime() } returns firstTime
            every { repository.getLatestSnapshotTime() } returns latestTime

            val stats = tradeHistoryService.getHistoryStats()
            stats.allTimeHigh shouldBe BigDecimal("12345.67")
            stats.totalTradesExecuted shouldBe 42L
            stats.totalVolumeTraded shouldBe BigDecimal("98765.43")
            stats.firstSnapshotTime shouldBe firstTime
            stats.latestSnapshotTime shouldBe latestTime
        }
    }
}
