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
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.repository.TradeSummaryStats
import com.gemini.krakenbot.service.impl.TradeHistoryServiceImpl
import com.gemini.krakenbot.util.TradeCalculator
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.*
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

class TradeHistoryServiceTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private val repository = mockk<TradeRepository>(relaxed = true)
    private val statsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
    private val krakenService = mockk<KrakenService>(relaxed = true)
    private val configService = mockk<ConfigService>(relaxed = true)
    private val portfolioAnalyzer = mockk<PortfolioAnalyzer>(relaxed = true)

    private fun createService(): TradeHistoryServiceImpl {
        val appConfig = AppConfig(
            kraken = KrakenCredentials(TestFixtures.TRADE_HISTORY_API_KEY, TestFixtures.TRADE_HISTORY_API_SECRET),
            settings = Settings(
                loopDelaySeconds = 60,
                deviationTriggerPercent = 5.0,
                dustThresholdUSD = 5.0,
                dryRun = false,
                fiatMaxDrawdown = 30.0,
                fiatDeploymentExponent = 1.0,
            ),
            allocations = emptyList(),
        )
        every { configService.getConfig() } returns appConfig

        val savedSnapshots = mutableListOf<PortfolioSnapshot>()
        coEvery { repository.saveSnapshot(any()) } answers {
            savedSnapshots.add(0, firstArg())
        }
        coEvery { repository.load() } answers { savedSnapshots.take(50) }

        return TradeHistoryServiceImpl(
            repository,
            statsRepository,
            krakenService,
            configService,
            objectMapper,
            portfolioAnalyzer,
            TestFixtures.TEST_TRADE_HISTORY_JSON,
        )
    }

    private fun snapshotWorth(totalValueUSD: BigDecimal) = PortfolioSnapshot(
        timestamp = Instant.now(),
        totalValueUSD = totalValueUSD,
        assets = emptyMap(),
        actions = emptyList(),
        drawdownPercent = BigDecimal.ZERO,
        fiatDeploymentPercent = BigDecimal.ZERO,
        effectiveUsdTargetPercent = BigDecimal.ZERO,
    )

    init {
        "init_LoadsHistoryFromRepository" {
            runTest {
                val tradeHistoryService = createService()
                val snapshot = PortfolioSnapshot(
                    timestamp = Instant.now(),
                    totalValueUSD = BigDecimal.ZERO,
                    assets = emptyMap(),
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                )
                coEvery { repository.load() } returns listOf(snapshot)
                tradeHistoryService.init()
                tradeHistoryService.getHistory().size shouldBe 1
                tradeHistoryService.getLatestSnapshot() shouldBe snapshot
            }
        }

        "addSnapshot_AddsToFrontAndSaves" {
            runTest {
                val tradeHistoryService = createService()
                val s1 = PortfolioSnapshot(
                    timestamp = Instant.now().minusMillis(10),
                    totalValueUSD = BigDecimal.ZERO,
                    assets = emptyMap(),
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                )
                val s2 = PortfolioSnapshot(
                    timestamp = Instant.now(),
                    totalValueUSD = BigDecimal.ZERO,
                    assets = emptyMap(),
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                )
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
                    tradeHistoryService.addSnapshot(
                        PortfolioSnapshot(
                            timestamp = Instant.now(),
                            totalValueUSD = BigDecimal.ZERO,
                            assets = emptyMap(),
                            actions = emptyList(),
                            drawdownPercent = BigDecimal.ZERO,
                            fiatDeploymentPercent = BigDecimal.ZERO,
                            effectiveUsdTargetPercent = BigDecimal.ZERO,
                        ),
                    )
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

                val s1 = PortfolioSnapshot(
                    timestamp = Instant.now(),
                    totalValueUSD = BigDecimal.ZERO,
                    assets = emptyMap(),
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                )
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
                val trade = TradeRecord(
                    timestamp = Instant.now(),
                    pair = Asset.BTC_USD_PAIR,
                    side = OrderSide.BUY.name,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    success = true,
                    dryRun = false,
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

        "syncTradesFromKraken_AlreadySeeded_IncrementalSync" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns true
                val latestTime = Instant.ofEpochSecond(1700000000)
                coEvery { repository.getLatestTradeTime() } returns latestTime
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getTradeHistory(1700000000 - 300, 0) } returns emptyList()

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                coVerify(exactly = 1) { krakenService.getTradeHistory(1700000000 - 300, 0) }
                coVerify(exactly = 0) { repository.setHistorySeeded(any()) }
            }
        }

        "syncTradesFromKraken_NoApiKey" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns false
                val emptyConfig = AppConfig(
                    kraken = KrakenCredentials("", ""),
                    settings = Settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
                        dryRun = false,
                        fiatMaxDrawdown = 30.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    allocations = emptyList(),
                )
                every { configService.getConfig() } returns emptyConfig
                val tradeHistoryService = TradeHistoryServiceImpl(
                    repository,
                    statsRepository,
                    krakenService,
                    configService,
                    objectMapper,
                    portfolioAnalyzer,
                    TestFixtures.TEST_TRADE_HISTORY_JSON,
                )

                tradeHistoryService.syncTradesFromKraken()

                coVerify(exactly = 0) { krakenService.getTradeHistory(any(), any()) }
                coVerify(exactly = 0) { repository.setHistorySeeded(any()) }
            }
        }

        "syncTradesFromKraken_SuccessSeeding_NoDuplicates" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns false
                coEvery { repository.getLatestTradeTime() } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()

                val now = Instant.now()
                val apiTrades = listOf(
                    TradeRecord(
                        timestamp = now,
                        pair = Asset.BTC_USD_PAIR,
                        side = OrderSide.BUY.name,
                        symbol = Asset.BTC,
                        volume = BigDecimal.ONE,
                        usdAmount = BigDecimal.TEN,
                        success = true,
                        dryRun = false,
                    ),
                )
                coEvery { krakenService.getTradeHistory(any(), any()) } returns apiTrades andThen emptyList()

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                coVerify(atLeast = 1) { krakenService.getTradeHistory(any(), any()) }
                coVerify(exactly = 1) { repository.saveTrade(any()) }
                coVerify(exactly = 1) { repository.setHistorySeeded(true) }
            }
        }

        "syncTradesFromKraken_SuccessSeeding_WithDuplicates" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns false
                val latestTime = Instant.ofEpochSecond(1700000000)
                coEvery { repository.getLatestTradeTime() } returns latestTime

                val duplicateTrade = TradeRecord(
                    timestamp = latestTime,
                    pair = Asset.BTC_USD_PAIR,
                    side = OrderSide.BUY.name,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    success = true,
                    dryRun = false,
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(duplicateTrade)

                val newTrade = TradeRecord(
                    timestamp = latestTime.plusSeconds(60),
                    pair = Asset.BTC_USD_PAIR,
                    side = OrderSide.SELL.name,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    success = true,
                    dryRun = false,
                )

                coEvery { krakenService.getTradeHistory(any(), 0) } returns listOf(duplicateTrade, newTrade)
                coEvery { krakenService.getTradeHistory(any(), 50) } returns emptyList()

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                coVerify(atLeast = 1) { krakenService.getTradeHistory(any(), any()) }
                coVerify(exactly = 1) { repository.saveTrade(newTrade) }
                coVerify(exactly = 0) { repository.saveTrade(duplicateTrade) }
                coVerify(exactly = 1) { repository.setHistorySeeded(true) }
            }
        }

        "syncTradesFromKraken_Reconciliation" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns true
                val latestTime = Instant.ofEpochSecond(1700000000)
                coEvery { repository.getLatestTradeTime() } returns latestTime

                val localTrade = TradeRecord(
                    timestamp = latestTime,
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    success = true,
                    dryRun = false,
                    price = BigDecimal.TEN,
                    expectedPrice = BigDecimal("10.05"),
                    source = TradeSource.LOCAL_ESTIMATE,
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(localTrade)

                val apiTrade = TradeRecord(
                    timestamp = latestTime.plusSeconds(5),
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.valueOf(9.95),
                    success = true,
                    dryRun = false,
                    price = BigDecimal("9.95"),
                    fee = BigDecimal("0.0259"),
                    source = TradeSource.API_FILL,
                )

                coEvery { krakenService.getTradeHistory(1700000000 - 300, 0) } returns listOf(apiTrade)
                coEvery { krakenService.getTradeHistory(1700000000 - 300, 50) } returns emptyList()

                val reconciledSlot = slot<TradeRecord>()
                coEvery { repository.updateTrade(localTrade, capture(reconciledSlot)) } just Runs

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                coVerify(exactly = 1) { repository.updateTrade(localTrade, any()) }
                reconciledSlot.captured.source shouldBe TradeSource.API_FILL
                reconciledSlot.captured.expectedPrice!!.shouldBeEqualComparingTo(BigDecimal("10.05"))
                reconciledSlot.captured.slippagePercent!!.shouldBeEqualComparingTo(
                    TradeCalculator.calculateSlippage(
                        TestFixtures.BUY,
                        BigDecimal("9.95"),
                        BigDecimal("10.05"),
                    ),
                )
                coVerify(exactly = 0) { repository.saveTrade(any()) }
            }
        }

        "syncTradesFromKraken_ReconcilesSlightlyDifferentMarketFill" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns true
                val latestTime = Instant.ofEpochSecond(1700000000)
                coEvery { repository.getLatestTradeTime() } returns latestTime

                val localEstimate = TradeRecord(
                    timestamp = latestTime,
                    pair = "TAOUSD",
                    side = TestFixtures.SELL,
                    symbol = "TAO",
                    volume = BigDecimal("0.07708000"),
                    usdAmount = BigDecimal("16.63"),
                    success = true,
                    dryRun = false,
                    price = BigDecimal("215.6867"),
                    fee = BigDecimal("0.0998"),
                    expectedPrice = BigDecimal("216.00"),
                    source = TradeSource.LOCAL_ESTIMATE,
                )
                val krakenFill = localEstimate.copy(
                    timestamp = latestTime.minusMillis(500),
                    volume = BigDecimal("0.07708233"),
                    usdAmount = BigDecimal("16.62393026"),
                    price = BigDecimal("215.66460511"),
                    fee = BigDecimal("0.0432"),
                    expectedPrice = null,
                    slippagePercent = null,
                    source = TradeSource.API_FILL,
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(localEstimate)
                coEvery { krakenService.getTradeHistory(any(), 0) } returns listOf(krakenFill)

                val reconciledSlot = slot<TradeRecord>()
                coEvery { repository.updateTrade(localEstimate, capture(reconciledSlot)) } just Runs

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                coVerify(exactly = 1) { repository.updateTrade(localEstimate, any()) }
                reconciledSlot.captured.expectedPrice!!.shouldBeEqualComparingTo(BigDecimal("216.00"))
                reconciledSlot.captured.source shouldBe TradeSource.API_FILL
                reconciledSlot.captured.slippagePercent!!.shouldBeEqualComparingTo(
                    TradeCalculator.calculateSlippage(
                        TestFixtures.SELL,
                        BigDecimal("215.66460511"),
                        BigDecimal("216.00"),
                    ),
                )
                coVerify(exactly = 0) { repository.saveTrade(any()) }
            }
        }

        "syncTradesFromKraken_FirstBatchEmpty" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns false
                coEvery { repository.getLatestTradeTime() } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getTradeHistory(any(), any()) } returns emptyList()

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                coVerify(exactly = 0) { repository.saveTrade(any()) }
                coVerify(exactly = 1) { repository.setHistorySeeded(true) }
            }
        }

        "syncTradesFromKraken_PaginationOffset" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns false
                coEvery { repository.getLatestTradeTime() } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()

                val batch1 = List(50) { i ->
                    TradeRecord(
                        timestamp = Instant.ofEpochSecond(1700000000 + i.toLong()),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal.ONE,
                        usdAmount = BigDecimal.TEN,
                        success = true,
                        dryRun = false,
                    )
                }
                val batch2 = listOf(
                    TradeRecord(
                        timestamp = Instant.ofEpochSecond(1700000600),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.SELL,
                        symbol = Asset.BTC,
                        volume = BigDecimal.ONE,
                        usdAmount = BigDecimal.TEN,
                        success = true,
                        dryRun = false,
                    ),
                )

                coEvery { krakenService.getTradeHistory(null, 0) } returns batch1
                coEvery { krakenService.getTradeHistory(null, 50) } returns batch2

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                coVerify(exactly = 1) { krakenService.getTradeHistory(null, 0) }
                coVerify(exactly = 1) { krakenService.getTradeHistory(null, 50) }
                coVerify(exactly = 51) { repository.saveTrade(any()) }
                coVerify(exactly = 1) { repository.setHistorySeeded(true) }
            }
        }

        "getHistoryStats_NullAllTimeHigh_DefaultsToZero" {
            runTest {
                val tradeHistoryService = createService()

                coEvery { statsRepository.load() } returns PortfolioStats(BigDecimal.ZERO)
                coEvery { repository.getTradeSummaryStats() } returns TradeSummaryStats(
                    totalTradesExecuted = 0L,
                    totalVolumeTraded = BigDecimal.ZERO,
                    totalFeesPaid = BigDecimal.ZERO,
                    latestSnapshotTime = null,
                )

                val stats = tradeHistoryService.getHistoryStats()
                stats.allTimeHigh.compareTo(BigDecimal.ZERO) shouldBe 0
                stats.totalTradesExecuted shouldBe 0L
                stats.totalVolumeTraded.compareTo(BigDecimal.ZERO) shouldBe 0
                stats.totalFeesPaid.compareTo(BigDecimal.ZERO) shouldBe 0
                stats.latestSnapshotTime shouldBe null
            }
        }

        "syncTradesFromKraken_PlaceholderApiKey" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns false
                val placeholderConfig = AppConfig(
                    kraken = KrakenCredentials(
                        KrakenCredentials.PLACEHOLDER_API_KEY,
                        KrakenCredentials.PLACEHOLDER_PRIVATE_KEY,
                    ),
                    settings = Settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
                        dryRun = false,
                        fiatMaxDrawdown = 30.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    allocations = emptyList(),
                )
                every { configService.getConfig() } returns placeholderConfig
                val tradeHistoryService = TradeHistoryServiceImpl(
                    repository,
                    statsRepository,
                    krakenService,
                    configService,
                    objectMapper,
                    portfolioAnalyzer,
                    TestFixtures.TEST_TRADE_HISTORY_JSON,
                )

                tradeHistoryService.syncTradesFromKraken()

                // Should skip synchronization — no trade history calls, no seeding
                coVerify(exactly = 0) { krakenService.getTradeHistory(any(), any()) }
                coVerify(exactly = 0) { repository.setHistorySeeded(any()) }
            }
        }

        "init_InSimulationMode_SeedsHistoricalSnapshots" {
            runTest {
                val appConfig = AppConfig(
                    kraken =
                    KrakenCredentials(
                        TestFixtures.TRADE_HISTORY_API_KEY,
                        TestFixtures.TRADE_HISTORY_API_SECRET,
                    ),
                    settings = Settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
                        dryRun = false,
                        simulation = true, // Enable simulation mode!
                        fiatMaxDrawdown = 30.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    allocations = listOf(
                        Allocation(Asset("UNKNOWN"), 50.0),
                        Allocation(Asset(TestFixtures.USD), 50.0),
                    ),
                )
                every { configService.getConfig() } returns appConfig
                coEvery { repository.load() } returns emptyList() // DB is empty!

                val tradeHistoryService = TradeHistoryServiceImpl(
                    repository,
                    statsRepository,
                    krakenService,
                    configService,
                    objectMapper,
                    portfolioAnalyzer,
                    TestFixtures.TEST_TRADE_HISTORY_JSON,
                )
                tradeHistoryService.init()

                // Seed 15 days of 6-hour interval snapshots in one batch write
                coVerify(exactly = 1) { repository.save(match { it.isNotEmpty() }) }
            }
        }

        "init_ThrowsExceptionDuringSeeding_HandledGracefully" {
            runTest {
                val appConfig = AppConfig(
                    kraken =
                    KrakenCredentials(
                        TestFixtures.TRADE_HISTORY_API_KEY,
                        TestFixtures.TRADE_HISTORY_API_SECRET,
                    ),
                    settings = Settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
                        dryRun = false,
                        simulation = true,
                        fiatMaxDrawdown = 30.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    allocations = listOf(
                        Allocation(Asset(Asset.BTC), 50.0),
                        Allocation(Asset(TestFixtures.USD), 50.0),
                    ),
                )
                every { configService.getConfig() } returns appConfig
                coEvery { repository.load() } returns emptyList()
                coEvery { repository.save(any()) } throws RuntimeException("Seeding failed")

                val tradeHistoryService = TradeHistoryServiceImpl(
                    repository,
                    statsRepository,
                    krakenService,
                    configService,
                    objectMapper,
                    portfolioAnalyzer,
                    TestFixtures.TEST_TRADE_HISTORY_JSON,
                )

                // Should catch exception and not propagate it
                tradeHistoryService.init()
            }
        }

        "init_MigratesTradeHistoryJsonIfEmpty" {
            runTest {
                val file = File(TestFixtures.TEST_TRADE_HISTORY_JSON)
                val bakFile = File("test-trade-history.json.bak")
                try {
                    file.delete()
                    bakFile.delete()

                    val snapshot = PortfolioSnapshot(
                        timestamp = Instant.now(),
                        totalValueUSD = BigDecimal("15000.00"),
                        assets = emptyMap(),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    )

                    file.writeText(objectMapper.writeValueAsString(listOf(snapshot)))

                    val tradeHistoryService = createService()
                    coEvery { repository.load() } returns emptyList()

                    tradeHistoryService.init()

                    coVerify(exactly = 1) { repository.save(any()) }

                    file.exists() shouldBe false
                    bakFile.exists() shouldBe true
                } finally {
                    file.delete()
                    bakFile.delete()
                }
            }
        }

        "addSnapshot_HandlesPruneException" {
            runTest {
                val tradeHistoryService = createService()
                coEvery { repository.pruneSnapshotsOlderThan(any()) } throws RuntimeException("Prune failed")

                val snapshot = PortfolioSnapshot(
                    timestamp = Instant.now(),
                    totalValueUSD = BigDecimal.ZERO,
                    assets = emptyMap(),
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                )

                // Should catch the exception and complete successfully
                tradeHistoryService.addSnapshot(snapshot)
                coVerify(exactly = 1) { repository.saveSnapshot(snapshot) }
            }
        }

        "addSnapshot_SuccessfullyPrunes" {
            runTest {
                val tradeHistoryService = createService()
                coEvery { repository.pruneSnapshotsOlderThan(any()) } returns 5

                val snapshot = PortfolioSnapshot(
                    timestamp = Instant.now(),
                    totalValueUSD = BigDecimal.ZERO,
                    assets = emptyMap(),
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                )

                tradeHistoryService.addSnapshot(snapshot)
                coVerify(exactly = 1) { repository.saveSnapshot(snapshot) }
                coVerify(exactly = 1) { repository.pruneSnapshotsOlderThan(any()) }
            }
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

        "syncTradesFromKraken_MissingCredentialsDoesNotThrottleImmediateRetry" {
            runTest {
                val service = createService()
                val validConfig = configService.getConfig()
                val missingCredentialsConfig = validConfig.copy(kraken = KrakenCredentials("", ""))
                every { configService.getConfig() } returns missingCredentialsConfig andThen validConfig
                coEvery { krakenService.getTradeHistory(any(), any()) } returns emptyList()

                service.syncTradesFromKraken()
                service.syncTradesFromKraken()

                coVerify(exactly = 1) { krakenService.getTradeHistory(any(), any()) }
            }
        }

        "syncTradesFromKraken_FailedAttemptDoesNotThrottleImmediateRetry" {
            runTest {
                val service = createService()
                var attempts = 0
                coEvery { krakenService.getTradeHistory(any(), any()) } coAnswers {
                    if (attempts++ == 0) throw RuntimeException("Kraken API down")
                    emptyList()
                }

                try {
                    service.syncTradesFromKraken()
                } catch (_: RuntimeException) {
                    // A failed sync remains visible to the caller and must not start the throttle window.
                }
                service.syncTradesFromKraken()

                coVerify(exactly = 2) { krakenService.getTradeHistory(any(), any()) }
            }
        }

        "syncTradesFromKraken_MatchingFailuresSavedAsNew" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns true
                val latestTime = Instant.ofEpochSecond(1700000000)
                coEvery { repository.getLatestTradeTime() } returns latestTime

                val baseLocal = TradeRecord(
                    timestamp = latestTime,
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    success = true,
                    dryRun = false,
                )

                // Define local trades that fail matching on exactly one attribute
                val diffPair = baseLocal.copy(pair = "ETHUSD")
                val diffSide = baseLocal.copy(side = TestFixtures.SELL)
                val diffVol = baseLocal.copy(volume = BigDecimal.TEN)
                val diffTime = baseLocal.copy(timestamp = latestTime.minusSeconds(600)) // 10 mins diff

                coEvery { repository.getTradesInRange(any(), any()) } returns
                    listOf(diffPair, diffSide, diffVol, diffTime)

                val apiTrade = baseLocal.copy()
                coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(apiTrade) andThen emptyList()

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                // Since it matched none of the local trades, it should be saved as new
                coVerify(exactly = 1) { repository.saveTrade(apiTrade) }
            }
        }

        "syncTradesFromKraken_ReconcilesDryRunDifference" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns true
                val latestTime = Instant.ofEpochSecond(1700000000)
                coEvery { repository.getLatestTradeTime() } returns latestTime

                val localTrade = TradeRecord(
                    timestamp = latestTime,
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    success = true,
                    dryRun = true,
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(localTrade)

                val apiTrade = localTrade.copy(dryRun = false)

                coEvery { krakenService.getTradeHistory(any(), 0) } returns listOf(apiTrade)
                coEvery { krakenService.getTradeHistory(any(), 50) } returns emptyList()

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                coVerify(exactly = 1) {
                    repository.updateTrade(
                        localTrade,
                        match {
                            it.dryRun == false &&
                                it.source == TradeSource.API_FILL
                        },
                    )
                }
            }
        }

        "syncTradesFromKraken_SeededButNoTrades" {
            runTest {
                val service = createService()
                coEvery { repository.isHistorySeeded() } returns true
                coEvery { repository.getLatestTradeTime() } returns null
                coEvery { krakenService.getTradeHistory(startSec = null, offset = 0) } returns emptyList()

                service.syncTradesFromKraken()
                coVerify(exactly = 1) { krakenService.getTradeHistory(startSec = null, offset = 0) }
            }
        }

        "syncTradesFromKraken_MultipleBatches" {
            runTest {
                val service = createService()
                coEvery { repository.isHistorySeeded() } returns true
                coEvery { repository.getLatestTradeTime() } returns null

                val batch1 = List(50) {
                    TradeRecord(
                        timestamp = Instant.now(),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal.ONE,
                        usdAmount = BigDecimal.TEN,
                        success = true,
                        dryRun = false,
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
                coEvery { repository.isHistorySeeded() } returns true
                val latestTime = Instant.ofEpochSecond(1700000000)
                coEvery { repository.getLatestTradeTime() } returns latestTime

                val localTrade = TradeRecord(
                    timestamp = latestTime,
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    success = true,
                    dryRun = false,
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(localTrade)

                val apiTrade = localTrade.copy()

                coEvery { krakenService.getTradeHistory(any(), 0) } returns listOf(apiTrade)
                coEvery { krakenService.getTradeHistory(any(), 50) } returns emptyList()

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                coVerify(exactly = 0) { repository.updateTrade(any(), any()) }
                coVerify(exactly = 0) { repository.saveTrade(any()) }
            }
        }

        "syncTradesFromKraken_ReconcilesUsdAmountDifference" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns true
                val latestTime = Instant.ofEpochSecond(1700000000)
                coEvery { repository.getLatestTradeTime() } returns latestTime

                val localTrade = TradeRecord(
                    timestamp = latestTime,
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    success = true,
                    dryRun = false,
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(localTrade)

                val apiTrade = localTrade.copy(usdAmount = BigDecimal.valueOf(11))

                coEvery { krakenService.getTradeHistory(any(), 0) } returns listOf(apiTrade)
                coEvery { krakenService.getTradeHistory(any(), 50) } returns emptyList()

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                coVerify(exactly = 1) {
                    repository.updateTrade(
                        localTrade,
                        match {
                            it.dryRun == false &&
                                it.source == TradeSource.API_FILL
                        },
                    )
                }
            }
        }

        "syncTradesFromKraken_ReconcilesTimestampDifference" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns true
                val latestTime = Instant.ofEpochSecond(1700000000)
                coEvery { repository.getLatestTradeTime() } returns latestTime

                val localTrade = TradeRecord(
                    timestamp = latestTime,
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    success = true,
                    dryRun = false,
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(localTrade)

                val apiTrade = localTrade.copy(timestamp = latestTime.minusMillis(500))

                coEvery { krakenService.getTradeHistory(any(), 0) } returns listOf(apiTrade)
                coEvery { krakenService.getTradeHistory(any(), 50) } returns emptyList()

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                coVerify(exactly = 1) {
                    repository.updateTrade(
                        localTrade,
                        match {
                            it.dryRun == false &&
                                it.source == TradeSource.API_FILL
                        },
                    )
                }
            }
        }

        "syncTradesFromKraken_BlankApiKey" {
            runTest {
                val appConfig = AppConfig(
                    kraken = KrakenCredentials("", TestFixtures.TRADE_HISTORY_API_SECRET),
                    settings = Settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
                        dryRun = false,
                    ),
                    allocations = emptyList(),
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
                    delay(10000.milliseconds)
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

        "syncTradesFromKraken_TriggersReconstructionWhenSnapshotsEmpty" {
            runTest {
                val appConfig = AppConfig(
                    kraken =
                    KrakenCredentials(
                        TestFixtures.TRADE_HISTORY_API_KEY,
                        TestFixtures.TRADE_HISTORY_API_SECRET,
                    ),

                    settings = Settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
                        dryRun = true,
                        simulation = false,
                    ),
                    allocations = listOf(
                        Allocation(Asset.BTC, 30.0),
                        Allocation("ETH", 30.0),
                        Allocation("EUR", 20.0),
                        Allocation("DOGE", 10.0),
                        Allocation(TestFixtures.USD, 10.0),
                    ),
                )
                every { configService.getConfig() } returns appConfig

                coEvery { repository.isHistorySeeded() } returns false
                coEvery { repository.getLatestTradeTime() } returns null
                coEvery { repository.getSyncMetadata(TestFixtures.SYNC_OFFSET) } returns null
                coEvery { repository.getSyncMetadata(TestFixtures.SYNC_TOTAL) } returns null

                coEvery { repository.load() } returns emptyList()
                coEvery { repository.getTradeSummaryStats() } returns TradeSummaryStats(
                    totalTradesExecuted = 2L,
                    totalVolumeTraded = BigDecimal.ZERO,
                    totalFeesPaid = BigDecimal.ZERO,
                    latestSnapshotTime = null,
                )

                val apiTrade1 = TradeRecord(
                    timestamp = Instant.now().minus(2, ChronoUnit.DAYS),
                    pair = TestFixtures.BTCUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.5"),
                    usdAmount = BigDecimal("15000.00"),
                    success = true,
                    dryRun = false,
                    price = BigDecimal("30000.00"),
                    fee = BigDecimal("15.00"),
                )
                val apiTrade2 = TradeRecord(
                    timestamp = Instant.now().minus(1, ChronoUnit.DAYS),
                    pair = TestFixtures.BTCUSD,
                    side = TestFixtures.SELL,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.2"),
                    usdAmount = BigDecimal("7000.00"),
                    success = true,
                    dryRun = false,
                    price = BigDecimal("35000.00"),
                    fee = BigDecimal("7.00"),
                )

                coEvery { krakenService.getTradeHistory(any(), 0) } returns listOf(apiTrade1, apiTrade2)
                coEvery { krakenService.getTradeHistory(any(), 50) } returns emptyList()

                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(apiTrade1, apiTrade2)
                coEvery { repository.saveTrade(any()) } just Runs
                coEvery { repository.updateTrade(any(), any()) } just Runs
                coEvery { repository.setHistorySeeded(true) } just Runs
                coEvery { repository.setSyncMetadata(any(), any()) } just Runs

                val mockBalances = mapOf(
                    Asset.BTC to BigDecimal("1.0"),
                    "XETH" to BigDecimal("2.0"),
                    "ZEUR" to BigDecimal("100.0"),
                    TestFixtures.USD to BigDecimal("5000.0"),
                )
                coEvery { krakenService.getBalances() } returns mockBalances
                every { portfolioAnalyzer.resolveBalance(Asset.BTC, mockBalances) } returns BigDecimal("1.0")
                every { portfolioAnalyzer.resolveBalance("ETH", mockBalances) } returns BigDecimal("2.0")
                every { portfolioAnalyzer.resolveBalance("EUR", mockBalances) } returns BigDecimal("100.0")
                every { portfolioAnalyzer.resolveBalance("DOGE", mockBalances) } returns BigDecimal.ZERO
                every { portfolioAnalyzer.resolveBalance(TestFixtures.USD, mockBalances) } returns BigDecimal("5000.0")
                coEvery { krakenService.getTickerPrices(any()) } returns
                    mapOf(TestFixtures.BTCUSD to BigDecimal("30000.0"))
                val dayStart = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS).epochSecond
                coEvery { krakenService.getOHLC(TestFixtures.BTCUSD, 1440, any()) } returns
                    listOf(Pair(dayStart, BigDecimal("30000.0")))
                coEvery { krakenService.getOHLC("ETHUSD", 1440, any()) } returns emptyList()
                coEvery { krakenService.getOHLC("EURUSD", 1440, any()) } returns emptyList()
                coEvery { krakenService.getOHLC("DOGEUSD", 1440, any()) } returns emptyList()

                val reconstructedSnapshots = slot<List<PortfolioSnapshot>>()
                coEvery { repository.save(capture(reconstructedSnapshots)) } just Runs

                val service = createService()
                every { configService.getConfig() } returns appConfig
                service.syncTradesFromKraken()

                reconstructedSnapshots.captured.first().assets.getValue(Asset.BTC).balance
                    .shouldBeEqualComparingTo(BigDecimal("1.0"))
                reconstructedSnapshots.captured.first().assets.getValue(TestFixtures.USD).balance
                    .shouldBeEqualComparingTo(BigDecimal("5000.0"))
            }
        }

        "syncMetadata_delegatesToRepository" {
            runTest {
                val service = createService()
                coEvery { repository.getSyncMetadata(TestFixtures.TEST_KEY) } returns TestFixtures.TEST_VALUE
                service.getSyncMetadata(TestFixtures.TEST_KEY) shouldBe TestFixtures.TEST_VALUE

                service.setSyncMetadata(TestFixtures.TEST_KEY, TestFixtures.TEST_VALUE_2)
                coVerify { repository.setSyncMetadata(TestFixtures.TEST_KEY, TestFixtures.TEST_VALUE_2) }

                coEvery { repository.isHistorySeeded() } returns true
                service.isHistorySeeded() shouldBe true
            }
        }

        "reconstructHistoricalSnapshots_WithExistingOldestSnapshot" {
            runTest {
                val service = createService()

                val appConfig = AppConfig(
                    kraken =
                    KrakenCredentials(
                        TestFixtures.TRADE_HISTORY_API_KEY,
                        TestFixtures.TRADE_HISTORY_API_SECRET,
                    ),
                    settings = Settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
                        dryRun = true,
                        simulation = false,
                    ),
                    allocations = listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(TestFixtures.USD, 50.0),
                    ),
                )
                every { configService.getConfig() } returns appConfig

                coEvery { repository.isHistorySeeded() } returns false
                coEvery { repository.getLatestTradeTime() } returns null
                coEvery { repository.getSyncMetadata(any()) } returns null

                val existingSnapshot = PortfolioSnapshot(
                    timestamp = Instant.now().minus(5, ChronoUnit.DAYS),
                    totalValueUSD = BigDecimal("10000.00"),
                    assets = mapOf(
                        Asset.BTC to PortfolioSnapshot.AssetSnapshot(
                            Asset.BTC,
                            BigDecimal("0.2"),
                            BigDecimal("25000.00"),
                            BigDecimal("5000.00"),
                            BigDecimal("50.00"),
                            BigDecimal("50.00"),
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                        ),
                        TestFixtures.USD to PortfolioSnapshot.AssetSnapshot(
                            TestFixtures.USD,
                            BigDecimal("5000.00"),
                            BigDecimal.ONE,
                            BigDecimal("5000.00"),
                            BigDecimal("50.00"),
                            BigDecimal("50.00"),
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                        ),
                    ),
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                )
                coEvery { repository.load() } returns listOf(existingSnapshot)
                coEvery { repository.getTradeSummaryStats() } returns TradeSummaryStats(
                    totalTradesExecuted = 1L,
                    totalVolumeTraded = BigDecimal.ZERO,
                    totalFeesPaid = BigDecimal.ZERO,
                    latestSnapshotTime = null,
                )

                val apiTrade = TradeRecord(
                    timestamp = Instant.now().minus(6, ChronoUnit.DAYS),
                    pair = TestFixtures.BTCUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.1"),
                    usdAmount = BigDecimal("2500.00"),
                    success = true,
                    dryRun = false,
                    price = BigDecimal("25000.00"),
                    fee = BigDecimal("5.00"),
                )

                coEvery { krakenService.getTradeHistory(any(), 0) } returns listOf(apiTrade)
                coEvery { krakenService.getTradeHistory(any(), 50) } returns emptyList()
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(apiTrade)
                coEvery { repository.saveTrade(any()) } just Runs
                coEvery { repository.updateTrade(any(), any()) } just Runs
                coEvery { repository.setHistorySeeded(true) } just Runs
                coEvery { repository.setSyncMetadata(any(), any()) } just Runs

                coEvery { krakenService.getOHLC(TestFixtures.BTCUSD, 1440, any()) } returns emptyList()
                coEvery { repository.save(any()) } just Runs

                service.syncTradesFromKraken()

                coVerify(atLeast = 1) { repository.save(any()) }
            }
        }

        "reconstructHistoricalSnapshots_FallbackMappingsAndExceptions" {
            runTest {
                val appConfig = AppConfig(
                    kraken =
                    KrakenCredentials(
                        TestFixtures.TRADE_HISTORY_API_KEY,
                        TestFixtures.TRADE_HISTORY_API_SECRET,
                    ),
                    settings = Settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
                        dryRun = true,
                        simulation = false,
                    ),
                    allocations = listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(TestFixtures.USD, 50.0),
                    ),
                )
                every { configService.getConfig() } returns appConfig

                coEvery { repository.isHistorySeeded() } returns false
                coEvery { repository.getLatestTradeTime() } returns null
                coEvery { repository.getSyncMetadata(TestFixtures.SYNC_OFFSET) } returns null
                coEvery { repository.getSyncMetadata(TestFixtures.SYNC_TOTAL) } returns null
                coEvery { repository.load() } returns emptyList()
                coEvery { repository.getTradeSummaryStats() } returns TradeSummaryStats(
                    totalTradesExecuted = 1L,
                    totalVolumeTraded = BigDecimal.ZERO,
                    totalFeesPaid = BigDecimal.ZERO,
                    latestSnapshotTime = null,
                )

                coEvery { krakenService.getBalances() } throws RuntimeException("getBalances error")

                val apiTrade = TradeRecord(
                    timestamp = Instant.now().minus(2, ChronoUnit.DAYS),
                    pair = TestFixtures.BTCUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.5"),
                    usdAmount = BigDecimal("15000.00"),
                    success = true,
                    dryRun = false,
                    price = BigDecimal("30000.00"),
                    fee = BigDecimal("15.00"),
                )
                coEvery { krakenService.getTradeHistory(any(), 0) } returns listOf(apiTrade)
                coEvery { krakenService.getTradeHistory(any(), 50) } returns emptyList()
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(apiTrade)
                coEvery { repository.saveTrade(any()) } just Runs
                coEvery { repository.updateTrade(any(), any()) } just Runs
                coEvery { repository.setHistorySeeded(true) } just Runs
                coEvery { repository.setSyncMetadata(any(), any()) } just Runs

                coEvery { krakenService.getTickerPrices(any()) } throws RuntimeException("getTickerPrices error")
                coEvery { krakenService.getOHLC(any(), any(), any()) } throws RuntimeException("getOHLC error")

                coEvery { repository.save(any()) } just Runs

                val service = createService()
                every { configService.getConfig() } returns appConfig
                service.syncTradesFromKraken()
            }
        }

        "reconstructHistoricalSnapshots_FallbackMappingsAndSimulation" {
            runTest {
                val appConfig = AppConfig(
                    kraken =
                    KrakenCredentials(
                        TestFixtures.TRADE_HISTORY_API_KEY,
                        TestFixtures.TRADE_HISTORY_API_SECRET,
                    ),
                    settings = Settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
                        dryRun = true,
                        simulation = true,
                    ),
                    allocations = listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(TestFixtures.USD, 50.0),
                    ),
                )
                every { configService.getConfig() } returns appConfig

                coEvery { repository.isHistorySeeded() } returns false
                coEvery { repository.getLatestTradeTime() } returns null
                coEvery { repository.getSyncMetadata(TestFixtures.SYNC_OFFSET) } returns null
                coEvery { repository.getSyncMetadata(TestFixtures.SYNC_TOTAL) } returns null
                coEvery { repository.load() } returns emptyList()
                coEvery { repository.getTradeSummaryStats() } returns TradeSummaryStats(
                    totalTradesExecuted = 1L,
                    totalVolumeTraded = BigDecimal.ZERO,
                    totalFeesPaid = BigDecimal.ZERO,
                    latestSnapshotTime = null,
                )

                val apiTrade = TradeRecord(
                    timestamp = Instant.now().minus(2, ChronoUnit.DAYS),
                    pair = TestFixtures.BTCUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.5"),
                    usdAmount = BigDecimal("15000.00"),
                    success = true,
                    dryRun = false,
                    price = BigDecimal("30000.00"),
                    fee = BigDecimal("15.00"),
                )
                coEvery { krakenService.getTradeHistory(any(), 0) } returns listOf(apiTrade)
                coEvery { krakenService.getTradeHistory(any(), 50) } returns emptyList()
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(apiTrade)
                coEvery { repository.saveTrade(any()) } just Runs
                coEvery { repository.updateTrade(any(), any()) } just Runs
                coEvery { repository.setHistorySeeded(true) } just Runs
                coEvery { repository.setSyncMetadata(any(), any()) } just Runs

                val mockBalances = mapOf(
                    "XXBT" to BigDecimal("1.0"),
                    TestFixtures.USD to BigDecimal("5000.0"),
                )
                coEvery { krakenService.getBalances() } returns mockBalances
                coEvery { krakenService.getTickerPrices(any()) } returns
                    mapOf(TestFixtures.BTCUSD to BigDecimal("30000.0"))
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns emptyList()
                coEvery { repository.save(any()) } just Runs

                val service = createService()
                every { configService.getConfig() } returns appConfig
                service.syncTradesFromKraken()
            }
        }

        "syncTradesFromKraken_ApiFailure" {
            runTest {
                val appConfig = AppConfig(
                    kraken =
                    KrakenCredentials(
                        TestFixtures.TRADE_HISTORY_API_KEY,
                        TestFixtures.TRADE_HISTORY_API_SECRET,
                    ),
                    settings = Settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
                        dryRun = true,
                        simulation = false,
                    ),
                    allocations = listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(TestFixtures.USD, 50.0),
                    ),
                )
                every { configService.getConfig() } returns appConfig

                coEvery { repository.isHistorySeeded() } returns false
                coEvery { repository.getLatestTradeTime() } returns null
                coEvery { repository.getSyncMetadata(TestFixtures.SYNC_OFFSET) } returns null
                coEvery { repository.getSyncMetadata(TestFixtures.SYNC_TOTAL) } returns null
                coEvery { repository.load() } returns emptyList()
                coEvery { repository.getTradeSummaryStats() } returns TradeSummaryStats(
                    totalTradesExecuted = 0L,
                    totalVolumeTraded = BigDecimal.ZERO,
                    totalFeesPaid = BigDecimal.ZERO,
                    latestSnapshotTime = null,
                )

                coEvery { krakenService.getTradeHistory(any(), any()) } throws RuntimeException("Kraken API down")

                val service = createService()
                every { configService.getConfig() } returns appConfig
                var threw = false
                try {
                    service.syncTradesFromKraken()
                } catch (e: RuntimeException) {
                    if (e.message == "Kraken API down") {
                        threw = true
                    }
                }
                threw shouldBe true
            }
        }

        "syncTradesFromKraken_ReconstructionFailureStillOpensThrottleWindow" {
            runTest {
                val appConfig = AppConfig(
                    kraken =
                    KrakenCredentials(
                        TestFixtures.TRADE_HISTORY_API_KEY,
                        TestFixtures.TRADE_HISTORY_API_SECRET,
                    ),
                    settings = Settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
                        dryRun = true,
                        simulation = false,
                    ),
                    allocations = listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(TestFixtures.USD, 50.0),
                    ),
                )

                coEvery { repository.isHistorySeeded() } returns true
                coEvery { repository.getLatestTradeTime() } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getTradeHistory(any(), any()) } returns emptyList()
                coEvery { repository.load() } returns emptyList()
                coEvery { repository.getTradeSummaryStats() } returns TradeSummaryStats(
                    totalTradesExecuted = 3L,
                    totalVolumeTraded = BigDecimal.ZERO,
                    totalFeesPaid = BigDecimal.ZERO,
                    latestSnapshotTime = null,
                )
                coEvery { krakenService.getBalances() } returns mapOf(Asset.BTC to BigDecimal.ONE)
                every { portfolioAnalyzer.resolveBalance(any(), any()) } throws
                    RuntimeException("reconstruction blew up")

                val service = createService()
                every { configService.getConfig() } returns appConfig

                service.syncTradesFromKraken()

                // Reconstruction started and failed, but the sync itself succeeded and stays silent about it.
                coVerify(exactly = 1) { krakenService.getBalances() }
                coVerify(exactly = 0) { repository.save(any()) }

                // A best-effort reconstruction failure must not reopen the Kraken tap on the next cycle.
                service.syncTradesFromKraken()

                coVerify(exactly = 1) { krakenService.getTradeHistory(any(), any()) }
                coVerify(exactly = 1) { krakenService.getBalances() }
            }
        }

        "getHistoryFlow_BroadcastsEverySnapshotToAllSubscribers" {
            runTest {
                val service = createService()
                val firstSubscriber = mutableListOf<PortfolioSnapshot>()
                val secondSubscriber = mutableListOf<PortfolioSnapshot>()

                val jobs = listOf(firstSubscriber, secondSubscriber).map { received ->
                    launch { service.getHistoryFlow().collect { received.add(it) } }
                }
                advanceUntilIdle()

                val emitted = List(3) { snapshotWorth(BigDecimal(it)) }
                emitted.forEach { service.addSnapshot(it) }
                advanceUntilIdle()

                firstSubscriber.shouldContainExactly(emitted)
                secondSubscriber.shouldContainExactly(emitted)

                jobs.forEach { it.cancel() }
            }
        }

        "getHistoryFlow_OverflowDropsOldestWithoutBlockingProducer" {
            runTest {
                val service = createService()
                val firstSubscriber = mutableListOf<PortfolioSnapshot>()
                val secondSubscriber = mutableListOf<PortfolioSnapshot>()

                val jobs = listOf(firstSubscriber, secondSubscriber).map { received ->
                    launch { service.getHistoryFlow().collect { received.add(it) } }
                }
                advanceUntilIdle()

                // Both subscribers stay parked while the producer runs past the buffer capacity.
                val emitted = List(SNAPSHOT_FLOW_BUFFER + 4) { snapshotWorth(BigDecimal(it)) }
                emitted.forEach { service.addSnapshot(it) }

                // DROP_OLDEST means the producer never suspends: every snapshot still reached the repository.
                coVerify(exactly = emitted.size) { repository.saveSnapshot(any()) }

                advanceUntilIdle()

                val retained = emitted.takeLast(SNAPSHOT_FLOW_BUFFER)
                firstSubscriber.shouldContainExactly(retained)
                secondSubscriber.shouldContainExactly(retained)

                jobs.forEach { it.cancel() }
            }
        }
    }

    private companion object {
        /** Mirrors `extraBufferCapacity` of `TradeHistoryServiceImpl.snapshotFlow`. */
        const val SNAPSHOT_FLOW_BUFFER = 16
    }
}
