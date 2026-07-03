package com.gemini.krakenbot.service

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
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
import io.mockk.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.math.BigDecimal
import java.time.Instant

@Suppress("unused")
class TradeHistoryServiceTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val repository = mockk<TradeRepository>(relaxed = true)
    private val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
    private val krakenService = mockk<KrakenService>(relaxed = true)
    private val configService = mockk<ConfigService>(relaxed = true)

    private fun createService(): TradeHistoryServiceImpl {
        val appConfig = AppConfig(
            kraken = KrakenCredentials("test-api-key", "test-private-key"),
            settings = Settings(
                loopDelaySeconds = 60,
                deviationTriggerPercent = 5.0,
                dustThresholdUSD = 5.0,
                dryRun = false,
                fiatMaxDrawdown = 30.0,
                fiatDeploymentExponent = 1.0
            ),
            allocations = emptyList()
        )
        every { configService.getConfig() } returns appConfig
        
        val savedSnapshots = mutableListOf<PortfolioSnapshot>()
        every { repository.saveSnapshot(any()) } answers {
            savedSnapshots.add(0, firstArg())
        }
        every { repository.load() } answers { savedSnapshots.take(50) }
        
        return TradeHistoryServiceImpl(repository, statsRepository, krakenService, configService)
    }

    init {
        "init_LoadsHistoryFromRepository" {
            val tradeHistoryService = createService()
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
            val tradeHistoryService = createService()
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
            val tradeHistoryService = createService()
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
            val tradeHistoryService = createService()
            every { repository.load() } returns emptyList()
            tradeHistoryService.init()
            tradeHistoryService.getHistory().isEmpty().shouldBeTrue()
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
            val tradeHistoryService = createService()
            tradeHistoryService.getLatestSnapshot().shouldBeNull()
        }

        "saveTrade_DelegatesToRepository" {
            val tradeHistoryService = createService()
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
            val tradeHistoryService = createService()
            val from = Instant.now()
            val to = Instant.now()
            tradeHistoryService.getSnapshotsInRange(from, to)
            verify(exactly = 1) { repository.getSnapshotsInRange(from, to) }
        }

        "getTradesInRange_DelegatesToRepository" {
            val tradeHistoryService = createService()
            val from = Instant.now()
            val to = Instant.now()
            tradeHistoryService.getTradesInRange(from, to)
            verify(exactly = 1) { repository.getTradesInRange(from, to) }
        }

        "getHistoryStats_AggregatesCorrectly" {
            val tradeHistoryService = createService()

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

        "syncTradesFromKraken_AlreadySeeded_IncrementalSync" {
            runTest {
                every { repository.isHistorySeeded() } returns true
                val latestTime = Instant.ofEpochSecond(1700000000)
                every { repository.getLatestTradeTime() } returns latestTime
                every { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getTradeHistory(1700000000 - 300, 0) } returns emptyList()

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                coVerify(exactly = 1) { krakenService.getTradeHistory(1700000000 - 300, 0) }
                verify(exactly = 0) { repository.setHistorySeeded(any()) }
            }
        }

        "syncTradesFromKraken_NoApiKey" {
            runTest {
                every { repository.isHistorySeeded() } returns false
                val emptyConfig = AppConfig(
                    kraken = KrakenCredentials("", ""),
                    settings = Settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
                        dryRun = false,
                        fiatMaxDrawdown = 30.0,
                        fiatDeploymentExponent = 1.0
                    ),
                    allocations = emptyList()
                )
                every { configService.getConfig() } returns emptyConfig
                val tradeHistoryService = TradeHistoryServiceImpl(repository, statsRepository, krakenService, configService)

                tradeHistoryService.syncTradesFromKraken()

                coVerify(exactly = 0) { krakenService.getTradeHistory(any(), any()) }
                verify(exactly = 0) { repository.setHistorySeeded(any()) }
            }
        }

        "syncTradesFromKraken_SuccessSeeding_NoDuplicates" {
            runTest {
                every { repository.isHistorySeeded() } returns false
                every { repository.getLatestTradeTime() } returns null
                every { repository.getTradesInRange(any(), any()) } returns emptyList()

                val now = Instant.now()
                val apiTrades = listOf(
                    TradeRecord(
                        timestamp = now,
                        pair = "XBTUSD",
                        side = "BUY",
                        symbol = "BTC",
                        volume = BigDecimal.ONE,
                        usdAmount = BigDecimal.TEN,
                        success = true,
                        dryRun = false
                    )
                )
                coEvery { krakenService.getTradeHistory(any(), any()) } returns apiTrades andThen emptyList()

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                coVerify(atLeast = 1) { krakenService.getTradeHistory(any(), any()) }
                verify(exactly = 1) { repository.saveTrade(any()) }
                verify(exactly = 1) { repository.setHistorySeeded(true) }
            }
        }

        "syncTradesFromKraken_SuccessSeeding_WithDuplicates" {
            runTest {
                every { repository.isHistorySeeded() } returns false
                val latestTime = Instant.ofEpochSecond(1700000000)
                every { repository.getLatestTradeTime() } returns latestTime

                val duplicateTrade = TradeRecord(
                    timestamp = latestTime,
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    success = true,
                    dryRun = false
                )
                every { repository.getTradesInRange(any(), any()) } returns listOf(duplicateTrade)

                val newTrade = TradeRecord(
                    timestamp = latestTime.plusSeconds(60),
                    pair = "XBTUSD",
                    side = "SELL",
                    symbol = "BTC",
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    success = true,
                    dryRun = false
                )

                coEvery { krakenService.getTradeHistory(any(), 0) } returns listOf(duplicateTrade, newTrade)
                coEvery { krakenService.getTradeHistory(any(), 50) } returns emptyList()

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                coVerify(atLeast = 1) { krakenService.getTradeHistory(any(), any()) }
                verify(exactly = 1) { repository.saveTrade(newTrade) }
                verify(exactly = 0) { repository.saveTrade(duplicateTrade) }
                verify(exactly = 1) { repository.setHistorySeeded(true) }
            }
        }

        "syncTradesFromKraken_Reconciliation" {
            runTest {
                every { repository.isHistorySeeded() } returns true
                val latestTime = Instant.ofEpochSecond(1700000000)
                every { repository.getLatestTradeTime() } returns latestTime

                val localTrade = TradeRecord(
                    timestamp = latestTime,
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    success = true,
                    dryRun = false
                )
                every { repository.getTradesInRange(any(), any()) } returns listOf(localTrade)

                val apiTrade = TradeRecord(
                    timestamp = latestTime.plusSeconds(5),
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.valueOf(9.95),
                    success = true,
                    dryRun = false
                )

                coEvery { krakenService.getTradeHistory(1700000000 - 300, 0) } returns listOf(apiTrade)
                coEvery { krakenService.getTradeHistory(1700000000 - 300, 50) } returns emptyList()

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                verify(exactly = 1) { repository.updateTrade(localTrade, apiTrade) }
                verify(exactly = 0) { repository.saveTrade(any()) }
            }
        }

        "syncTradesFromKraken_FirstBatchEmpty" {
            runTest {
                every { repository.isHistorySeeded() } returns false
                every { repository.getLatestTradeTime() } returns null
                every { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getTradeHistory(any(), any()) } returns emptyList()

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                verify(exactly = 0) { repository.saveTrade(any()) }
                verify(exactly = 1) { repository.setHistorySeeded(true) }
            }
        }

        "syncTradesFromKraken_PaginationOffset" {
            runTest {
                every { repository.isHistorySeeded() } returns false
                every { repository.getLatestTradeTime() } returns null
                every { repository.getTradesInRange(any(), any()) } returns emptyList()

                val batch1 = List(50) { i ->
                    TradeRecord(
                        timestamp = Instant.ofEpochSecond(1700000000 + i.toLong()),
                        pair = "XBTUSD",
                        side = "BUY",
                        symbol = "BTC",
                        volume = BigDecimal.ONE,
                        usdAmount = BigDecimal.TEN,
                        success = true,
                        dryRun = false
                    )
                }
                val batch2 = listOf(
                    TradeRecord(
                        timestamp = Instant.ofEpochSecond(1700000600),
                        pair = "XBTUSD",
                        side = "SELL",
                        symbol = "BTC",
                        volume = BigDecimal.ONE,
                        usdAmount = BigDecimal.TEN,
                        success = true,
                        dryRun = false
                    )
                )

                coEvery { krakenService.getTradeHistory(null, 0) } returns batch1
                coEvery { krakenService.getTradeHistory(null, 50) } returns batch2

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                coVerify(exactly = 1) { krakenService.getTradeHistory(null, 0) }
                coVerify(exactly = 1) { krakenService.getTradeHistory(null, 50) }
                verify(exactly = 51) { repository.saveTrade(any()) }
                verify(exactly = 1) { repository.setHistorySeeded(true) }
            }
        }

        "getHistoryStats_NullAllTimeHigh_DefaultsToZero" {
            val tradeHistoryService = createService()

            every { statsRepository.load() } returns PortfolioStats(null)
            every { repository.getTotalTradeCount() } returns 0L
            every { repository.getTotalVolumeTraded() } returns BigDecimal.ZERO
            every { repository.getFirstSnapshotTime() } returns null
            every { repository.getLatestSnapshotTime() } returns null

            val stats = tradeHistoryService.getHistoryStats()
            stats.allTimeHigh.compareTo(BigDecimal.ZERO) shouldBe 0
            stats.totalTradesExecuted shouldBe 0L
            stats.totalVolumeTraded.compareTo(BigDecimal.ZERO) shouldBe 0
            stats.firstSnapshotTime shouldBe null
            stats.latestSnapshotTime shouldBe null
        }

        "syncTradesFromKraken_PlaceholderApiKey" {
            runTest {
                every { repository.isHistorySeeded() } returns false
                val placeholderConfig = AppConfig(
                    kraken = KrakenCredentials("YOUR_KRAKEN_API_KEY", "YOUR_KRAKEN_PRIVATE_KEY"),
                    settings = Settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
                        dryRun = false,
                        fiatMaxDrawdown = 30.0,
                        fiatDeploymentExponent = 1.0
                    ),
                    allocations = emptyList()
                )
                every { configService.getConfig() } returns placeholderConfig
                val tradeHistoryService = TradeHistoryServiceImpl(repository, statsRepository, krakenService, configService)

                tradeHistoryService.syncTradesFromKraken()

                // Should skip synchronization — no trade history calls, no seeding
                coVerify(exactly = 0) { krakenService.getTradeHistory(any(), any()) }
                verify(exactly = 0) { repository.setHistorySeeded(any()) }
            }
        }

        "init_InSimulationMode_SeedsHistoricalSnapshots" {
            val appConfig = AppConfig(
                kraken = KrakenCredentials("test-api-key", "test-private-key"),
                settings = Settings(
                    loopDelaySeconds = 60,
                    deviationTriggerPercent = 5.0,
                    dustThresholdUSD = 5.0,
                    dryRun = false,
                    simulation = true, // Enable simulation mode!
                    fiatMaxDrawdown = 30.0,
                    fiatDeploymentExponent = 1.0
                ),
                allocations = listOf(
                    com.gemini.krakenbot.config.Allocation(com.gemini.krakenbot.model.Asset("UNKNOWN"), 50.0),
                    com.gemini.krakenbot.config.Allocation(com.gemini.krakenbot.model.Asset("USD"), 50.0)
                )
            )
            every { configService.getConfig() } returns appConfig
            every { repository.load() } returns emptyList() // DB is empty!

            val tradeHistoryService = TradeHistoryServiceImpl(repository, statsRepository, krakenService, configService)
            tradeHistoryService.init()

            // It should call saveSnapshot multiple times to seed 15 days of 6-hour interval snapshots (60 snapshots)
            verify(atLeast = 1) { repository.saveSnapshot(any()) }
        }

        "init_ThrowsExceptionDuringSeeding_HandledGracefully" {
            val appConfig = AppConfig(
                kraken = KrakenCredentials("test-api-key", "test-private-key"),
                settings = Settings(
                    loopDelaySeconds = 60,
                    deviationTriggerPercent = 5.0,
                    dustThresholdUSD = 5.0,
                    dryRun = false,
                    simulation = true,
                    fiatMaxDrawdown = 30.0,
                    fiatDeploymentExponent = 1.0
                ),
                allocations = listOf(
                    com.gemini.krakenbot.config.Allocation(com.gemini.krakenbot.model.Asset("BTC"), 50.0),
                    com.gemini.krakenbot.config.Allocation(com.gemini.krakenbot.model.Asset("USD"), 50.0)
                )
            )
            every { configService.getConfig() } returns appConfig
            every { repository.load() } returns emptyList()
            every { repository.saveSnapshot(any()) } throws RuntimeException("Seeding failed")

            val tradeHistoryService = TradeHistoryServiceImpl(repository, statsRepository, krakenService, configService)
            
            // Should catch exception and not propagate it
            tradeHistoryService.init()
        }

        "addSnapshot_HandlesPruneException" {
            val tradeHistoryService = createService()
            every { repository.pruneSnapshotsOlderThan(any()) } throws RuntimeException("Prune failed")
            
            val snapshot = PortfolioSnapshot(
                timestamp = Instant.now(),
                totalValueUSD = BigDecimal.ZERO,
                assets = emptyMap(),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO
            )
            
            // Should catch the exception and complete successfully
            tradeHistoryService.addSnapshot(snapshot)
            verify(exactly = 1) { repository.saveSnapshot(snapshot) }
        }

        "addSnapshot_SuccessfullyPrunes" {
            val tradeHistoryService = createService()
            every { repository.pruneSnapshotsOlderThan(any()) } returns 5
            
            val snapshot = PortfolioSnapshot(
                timestamp = Instant.now(),
                totalValueUSD = BigDecimal.ZERO,
                assets = emptyMap(),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO
            )
            
            tradeHistoryService.addSnapshot(snapshot)
            verify(exactly = 1) { repository.saveSnapshot(snapshot) }
            verify(exactly = 1) { repository.pruneSnapshotsOlderThan(any()) }
        }

        "syncTradesFromKraken_ThrottlingWithin300Seconds" {
            runTest {
                val service = createService()
                service.syncTradesFromKraken() // First run sets lastSyncTime
                
                // Second run should be skipped due to throttle
                service.syncTradesFromKraken()
                coVerify(exactly = 1) { krakenService.getTradeHistory(any(), any()) }
            }
        }

        "syncTradesFromKraken_MatchingFailuresSavedAsNew" {
            runTest {
                every { repository.isHistorySeeded() } returns true
                val latestTime = Instant.ofEpochSecond(1700000000)
                every { repository.getLatestTradeTime() } returns latestTime

                val baseLocal = TradeRecord(
                    timestamp = latestTime,
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    success = true,
                    dryRun = false
                )

                // Define local trades that fail matching on exactly one attribute
                val diffPair = baseLocal.copy(pair = "ETHUSD")
                val diffSide = baseLocal.copy(side = "SELL")
                val diffVol = baseLocal.copy(volume = BigDecimal.TEN)
                val diffTime = baseLocal.copy(timestamp = latestTime.minusSeconds(600)) // 10 mins diff

                every { repository.getTradesInRange(any(), any()) } returns listOf(diffPair, diffSide, diffVol, diffTime)

                val apiTrade = baseLocal.copy()
                coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(apiTrade) andThen emptyList()

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                // Since it matched none of the local trades, it should be saved as new
                verify(exactly = 1) { repository.saveTrade(apiTrade) }
            }
        }

        "syncTradesFromKraken_ReconcilesDryRunDifference" {
            runTest {
                every { repository.isHistorySeeded() } returns true
                val latestTime = Instant.ofEpochSecond(1700000000)
                every { repository.getLatestTradeTime() } returns latestTime

                val localTrade = TradeRecord(
                    timestamp = latestTime,
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    success = true,
                    dryRun = true
                )
                every { repository.getTradesInRange(any(), any()) } returns listOf(localTrade)

                val apiTrade = localTrade.copy(dryRun = false)

                coEvery { krakenService.getTradeHistory(any(), 0) } returns listOf(apiTrade)
                coEvery { krakenService.getTradeHistory(any(), 50) } returns emptyList()

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                verify(exactly = 1) { repository.updateTrade(localTrade, apiTrade) }
            }
        }

        "syncTradesFromKraken_SeededButNoTrades" {
            runTest {
                val service = createService()
                every { repository.isHistorySeeded() } returns true
                every { repository.getLatestTradeTime() } returns null
                coEvery { krakenService.getTradeHistory(startSec = null, offset = 0) } returns emptyList()

                service.syncTradesFromKraken()
                coVerify(exactly = 1) { krakenService.getTradeHistory(startSec = null, offset = 0) }
            }
         }

        "syncTradesFromKraken_MultipleBatches" {
            runTest {
                val service = createService()
                every { repository.isHistorySeeded() } returns true
                every { repository.getLatestTradeTime() } returns null
                
                val batch1 = List(50) {
                    TradeRecord(
                        timestamp = Instant.now(),
                        pair = "XBTUSD",
                        side = "BUY",
                        symbol = "BTC",
                        volume = BigDecimal.ONE,
                        usdAmount = BigDecimal.TEN,
                        success = true,
                        dryRun = false
                    )
                }
                
                coEvery { krakenService.getTradeHistory(startSec = null, offset = 0) } returns batch1
                coEvery { krakenService.getTradeHistory(startSec = null, offset = 50) } returns emptyList()

                service.syncTradesFromKraken()
                coVerify(exactly = 1) { krakenService.getTradeHistory(startSec = null, offset = 0) }
                coVerify(exactly = 1) { krakenService.getTradeHistory(startSec = null, offset = 50) }
            }
        }

        "syncTradesFromKraken_MatchingExactTradeSkipsReconciliation" {
            runTest {
                every { repository.isHistorySeeded() } returns true
                val latestTime = Instant.ofEpochSecond(1700000000)
                every { repository.getLatestTradeTime() } returns latestTime

                val localTrade = TradeRecord(
                    timestamp = latestTime,
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    success = true,
                    dryRun = false
                )
                every { repository.getTradesInRange(any(), any()) } returns listOf(localTrade)

                val apiTrade = localTrade.copy()

                coEvery { krakenService.getTradeHistory(any(), 0) } returns listOf(apiTrade)
                coEvery { krakenService.getTradeHistory(any(), 50) } returns emptyList()

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                verify(exactly = 0) { repository.updateTrade(any(), any()) }
                verify(exactly = 0) { repository.saveTrade(any()) }
            }
        }

        "syncTradesFromKraken_ReconcilesUsdAmountDifference" {
            runTest {
                every { repository.isHistorySeeded() } returns true
                val latestTime = Instant.ofEpochSecond(1700000000)
                every { repository.getLatestTradeTime() } returns latestTime

                val localTrade = TradeRecord(
                    timestamp = latestTime,
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    success = true,
                    dryRun = false
                )
                every { repository.getTradesInRange(any(), any()) } returns listOf(localTrade)

                val apiTrade = localTrade.copy(usdAmount = BigDecimal.valueOf(11))

                coEvery { krakenService.getTradeHistory(any(), 0) } returns listOf(apiTrade)
                coEvery { krakenService.getTradeHistory(any(), 50) } returns emptyList()

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                verify(exactly = 1) { repository.updateTrade(localTrade, apiTrade) }
            }
        }

        "syncTradesFromKraken_ReconcilesTimestampDifference" {
            runTest {
                every { repository.isHistorySeeded() } returns true
                val latestTime = Instant.ofEpochSecond(1700000000)
                every { repository.getLatestTradeTime() } returns latestTime

                val localTrade = TradeRecord(
                    timestamp = latestTime,
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    success = true,
                    dryRun = false
                )
                every { repository.getTradesInRange(any(), any()) } returns listOf(localTrade)

                val apiTrade = localTrade.copy(timestamp = latestTime.minusSeconds(120))

                coEvery { krakenService.getTradeHistory(any(), 0) } returns listOf(apiTrade)
                coEvery { krakenService.getTradeHistory(any(), 50) } returns emptyList()

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                verify(exactly = 1) { repository.updateTrade(localTrade, apiTrade) }
            }
        }

        "syncTradesFromKraken_BlankApiKey" {
            runTest {
                val appConfig = AppConfig(
                    kraken = KrakenCredentials("", "test-private-key"),
                    settings = Settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
                        dryRun = false
                    ),
                    allocations = emptyList()
                )
                val service = createService()
                every { configService.getConfig() } returns appConfig
                service.syncTradesFromKraken()

                coVerify(exactly = 0) { krakenService.getTradeHistory(any(), any()) }
            }
        }

        "syncTradesFromKraken_Cancelled" {
            runTest {
                val service = createService()
                
                coEvery { krakenService.getTradeHistory(any(), any()) } coAnswers {
                    kotlinx.coroutines.delay(10000)
                    emptyList()
                }

                val job = launch {
                    service.syncTradesFromKraken()
                }
                yield()
                job.cancel()
                job.join()
            }
        }
    }
}
