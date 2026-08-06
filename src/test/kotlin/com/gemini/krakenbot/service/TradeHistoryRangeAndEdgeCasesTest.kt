@file:OptIn(ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.TradeSummaryStats
import com.gemini.krakenbot.service.impl.DynamicKrakenService
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import com.gemini.krakenbot.service.impl.SimulatedKrakenService
import com.gemini.krakenbot.service.impl.history.TradeHistoryServiceImpl
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

class TradeHistoryRangeAndEdgeCasesTest : TradeHistoryServiceTestBase() {

    init {
        "getHistoryStats_EpochRange_PrefersStoredAthWhenHigherThanPeriodHigh" {
            runTest {
                val tradeHistoryService = createService()
                coEvery { statsRepository.load() } returns PortfolioStats(BigDecimal("20000.00"))
                coEvery { repository.getTradeSummaryStats() } returns TradeSummaryStats(
                    totalTradesExecuted = 3L,
                    totalVolumeTraded = BigDecimal("1000.00"),
                    totalFeesPaid = BigDecimal("1.00"),
                    latestSnapshotTime = Instant.now(),
                    periodHigh = BigDecimal("15000.00"),
                )

                val stats = tradeHistoryService.getHistoryStats(Instant.EPOCH, Instant.now())
                stats.allTimeHigh.shouldBeEqualComparingTo(BigDecimal("20000.00"))
                stats.totalTradesExecuted shouldBe 3L
                coVerify(exactly = 1) { repository.getTradeSummaryStats() }
                coVerify(exactly = 0) { repository.getTradeSummaryStats(any(), any()) }
            }
        }

        "getHistoryStats_EpochRange_PrefersPeriodHighWhenHigherThanStoredAth" {
            runTest {
                val tradeHistoryService = createService()
                coEvery { statsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery { repository.getTradeSummaryStats() } returns TradeSummaryStats(
                    totalTradesExecuted = 2L,
                    totalVolumeTraded = BigDecimal("500.00"),
                    totalFeesPaid = BigDecimal("0.50"),
                    latestSnapshotTime = Instant.now(),
                    periodHigh = BigDecimal("18000.00"),
                )

                val stats = tradeHistoryService.getHistoryStats(Instant.EPOCH, Instant.now())
                stats.allTimeHigh.shouldBeEqualComparingTo(BigDecimal("18000.00"))
            }
        }

        "getHistoryStats_EpochRange_NullPeriodHighFallsBackToStoredAth" {
            runTest {
                val tradeHistoryService = createService()
                coEvery { statsRepository.load() } returns PortfolioStats(BigDecimal("12000.00"))
                coEvery { repository.getTradeSummaryStats() } returns TradeSummaryStats(
                    totalTradesExecuted = 1L,
                    totalVolumeTraded = BigDecimal("100.00"),
                    totalFeesPaid = BigDecimal.ZERO,
                    latestSnapshotTime = null,
                    periodHigh = null,
                )

                val stats = tradeHistoryService.getHistoryStats(Instant.EPOCH, Instant.now())
                stats.allTimeHigh.shouldBeEqualComparingTo(BigDecimal("12000.00"))
            }
        }

        "getHistoryStats_NonEpochRange_NullPeriodHighDefaultsToZero" {
            runTest {
                val tradeHistoryService = createService()
                val from = Instant.now().minus(3, ChronoUnit.DAYS)
                val to = Instant.now()
                coEvery { repository.getTradeSummaryStats(from, to) } returns TradeSummaryStats(
                    totalTradesExecuted = 0L,
                    totalVolumeTraded = BigDecimal.ZERO,
                    totalFeesPaid = BigDecimal.ZERO,
                    latestSnapshotTime = null,
                    periodHigh = null,
                )

                val stats = tradeHistoryService.getHistoryStats(from, to)
                stats.allTimeHigh.shouldBeEqualComparingTo(BigDecimal.ZERO)
                coVerify(exactly = 1) { repository.getTradeSummaryStats(from, to) }
                coVerify(exactly = 0) { repository.getTradeSummaryStats() }
            }
        }

        "init_MigratesEmptyTradeHistoryJsonWithoutSaving" {
            runTest {
                val tmpFile = File.createTempFile("edge-empty-", ".json").apply { deleteOnExit() }
                val file = tmpFile
                val bakFile = File("${tmpFile.absolutePath}.bak")
                try {
                    bakFile.delete()
                    file.writeText("[]")

                    val tradeHistoryService = createService(tradeHistoryFilePath = tmpFile.absolutePath)
                    coEvery { repository.load() } returns emptyList()
                    tradeHistoryService.init()

                    coVerify(exactly = 0) { repository.save(any()) }
                    file.exists() shouldBe true
                    bakFile.exists() shouldBe false
                } finally {
                    file.delete()
                    bakFile.delete()
                }
            }
        }

        "init_MigratesNullTradeHistoryJsonWithoutSaving" {
            runTest {
                val tmpFile = File.createTempFile("edge-null-", ".json").apply { deleteOnExit() }
                val file = tmpFile
                val bakFile = File("${tmpFile.absolutePath}.bak")
                try {
                    bakFile.delete()
                    file.writeText("null")

                    val tradeHistoryService = createService(tradeHistoryFilePath = tmpFile.absolutePath)
                    coEvery { repository.load() } returns emptyList()
                    tradeHistoryService.init()

                    coVerify(exactly = 0) { repository.save(any()) }
                    file.exists() shouldBe true
                } finally {
                    file.delete()
                    bakFile.delete()
                }
            }
        }

        "init_InSimulationMode_SeedsWithoutUsdAllocationUsesDefaultTarget" {
            runTest {
                val appConfig = AppConfig(
                    kraken = KrakenCredentials(
                        TestFixtures.TRADE_HISTORY_API_KEY,
                        TestFixtures.TRADE_HISTORY_API_SECRET,
                    ),
                    settings = TestFixtures.settings(
                        dryRun = false,
                        simulation = true,
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
                        fiatMaxDrawdown = 30.0,
                    ),
                    allocations = listOf(
                        Allocation(Asset.BTC, 60.0),
                        Allocation(Asset.ETH, 40.0),
                    ),
                )
                every { configService.getConfig() } returns appConfig
                coEvery { repository.load() } returns emptyList()
                val saved = slot<List<PortfolioSnapshot>>()
                coEvery { repository.save(capture(saved)) } just Runs

                val uniquePath = File.createTempFile("range-seed-", ".json").also { it.delete() }
                TradeHistoryServiceImpl(
                    repository,
                    statsRepository,
                    ledgerRepository,
                    krakenService,
                    configService,
                    objectMapper,
                    portfolioAnalyzer,
                    uniquePath.absolutePath,
                ).init()

                saved.captured.isNotEmpty().shouldBeTrue()
                saved.captured.first().effectiveUsdTargetPercent
                    .shouldBeEqualComparingTo(BigDecimal("0.00"))
            }
        }

        "syncTradesFromKraken_SkipsReconstructionWhenMultipleSnapshotsExist" {
            runTest {
                val appConfig = AppConfig(
                    kraken = KrakenCredentials(
                        TestFixtures.TRADE_HISTORY_API_KEY,
                        TestFixtures.TRADE_HISTORY_API_SECRET,
                    ),
                    settings = TestFixtures.settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
                    ),
                    allocations = listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(TestFixtures.USD, 50.0),
                    ),
                )
                coEvery { krakenService.getTradeHistory(any(), 0) } returns emptyList()

                val service = createService()
                every { configService.getConfig() } returns appConfig
                coEvery { repository.isHistorySeeded() } returns true
                coEvery { repository.getLatestTradeTime() } returns Instant.now().minus(1, ChronoUnit.DAYS)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { repository.load() } returns listOf(
                    snapshotWorth(BigDecimal("1000.00")),
                    snapshotWorth(BigDecimal("1100.00")),
                )
                coEvery { repository.getTradeSummaryStats() } returns TradeSummaryStats(
                    totalTradesExecuted = 5L,
                    totalVolumeTraded = BigDecimal("500.00"),
                    totalFeesPaid = BigDecimal.ONE,
                    latestSnapshotTime = Instant.now(),
                )

                service.syncTradesFromKraken()

                coVerify(exactly = 0) { krakenService.getBalances() }
                coVerify(exactly = 0) { repository.save(any()) }
            }
        }

        "syncTradesFromKraken_ReconstructionSkipsFailedAndDryRunTradesAndSkipsEmptySave" {
            runTest {
                val appConfig = AppConfig(
                    kraken = KrakenCredentials(
                        TestFixtures.TRADE_HISTORY_API_KEY,
                        TestFixtures.TRADE_HISTORY_API_SECRET,
                    ),
                    settings = TestFixtures.settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
                    ),
                    allocations = listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(TestFixtures.USD, 50.0),
                    ),
                )
                every { configService.getConfig() } returns appConfig

                val ancientSnapshot = PortfolioSnapshot(
                    timestamp = Instant.EPOCH,
                    totalValueUSD = BigDecimal("10000.00"),
                    assets = mapOf(
                        Asset.BTC to TestFixtures.assetSnapshot(
                            symbol = Asset.BTC,
                            balance = BigDecimal("0.2"),
                            price = BigDecimal("25000.00"),
                            valueUSD = BigDecimal("5000.00"),
                            targetPercent = BigDecimal("50.00"),
                        ),
                        TestFixtures.USD to TestFixtures.assetSnapshot(
                            symbol = TestFixtures.USD,
                            balance = BigDecimal("5000.00"),
                            price = BigDecimal.ONE,
                            valueUSD = BigDecimal("5000.00"),
                            targetPercent = BigDecimal("50.00"),
                        ),
                    ),
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal("50.00"),
                )

                val service = createService()
                every { configService.getConfig() } returns appConfig
                coEvery { repository.isHistorySeeded() } returns false
                coEvery { repository.getLatestTradeTime() } returns null
                coEvery { repository.getSyncMetadata(any()) } returns null
                coEvery { repository.load() } returns listOf(ancientSnapshot)
                coEvery { repository.getTradeSummaryStats() } returns TradeSummaryStats(
                    totalTradesExecuted = 2L,
                    totalVolumeTraded = BigDecimal.ZERO,
                    totalFeesPaid = BigDecimal.ZERO,
                    latestSnapshotTime = Instant.EPOCH,
                )
                coEvery { krakenService.getTradeHistory(any(), 0) } returns emptyList()
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(
                    TestFixtures.tradeRecord(
                        timestamp = Instant.now().minus(1, ChronoUnit.DAYS),
                        pair = TestFixtures.BTCUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("2500.00"),
                        success = false,
                        price = BigDecimal("25000.00"),
                        fee = BigDecimal.ZERO,
                    ),
                    TestFixtures.tradeRecord(
                        timestamp = Instant.now().minus(2, ChronoUnit.DAYS),
                        pair = TestFixtures.BTCUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("2500.00"),
                        dryRun = true,
                        price = BigDecimal("25000.00"),
                        fee = BigDecimal.ZERO,
                    ),
                )
                coEvery { repository.setHistorySeeded(true) } just Runs
                coEvery { repository.setSyncMetadata(any(), any()) } just Runs
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns emptyList()

                service.syncTradesFromKraken()

                coVerify(exactly = 0) { repository.save(any()) }
                coVerify(exactly = 1) { repository.setHistorySeeded(true) }
            }
        }

        "CQ-7-4: reconstructionExcludesDryRunTradesButIncludesSuccessfulLiveTwin" {
            runTest {
                val appConfig = AppConfig(
                    kraken = KrakenCredentials(
                        TestFixtures.TRADE_HISTORY_API_KEY,
                        TestFixtures.TRADE_HISTORY_API_SECRET,
                    ),
                    settings = TestFixtures.settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
                    ),
                    allocations = listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(TestFixtures.USD, 50.0),
                    ),
                )
                every { configService.getConfig() } returns appConfig

                val service = createService()
                every { configService.getConfig() } returns appConfig

                coEvery { repository.isHistorySeeded() } returns false
                coEvery { repository.getLatestTradeTime() } returns null
                coEvery { repository.getSyncMetadata(any()) } returns null

                val cutoff = Instant.now().minus(5, ChronoUnit.DAYS)
                val existingSnapshot = PortfolioSnapshot(
                    timestamp = cutoff,
                    totalValueUSD = BigDecimal("10000.00"),
                    assets = mapOf(
                        Asset.BTC to TestFixtures.assetSnapshot(
                            symbol = Asset.BTC,
                            balance = BigDecimal("0.2"),
                            price = BigDecimal("25000.00"),
                            valueUSD = BigDecimal("5000.00"),
                            targetPercent = BigDecimal("50.00"),
                        ),
                        TestFixtures.USD to TestFixtures.assetSnapshot(
                            symbol = TestFixtures.USD,
                            balance = BigDecimal("5000.00"),
                            price = BigDecimal.ONE,
                            valueUSD = BigDecimal("5000.00"),
                            targetPercent = BigDecimal("50.00"),
                        ),
                    ),
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                )
                coEvery { repository.load() } returns listOf(existingSnapshot)
                coEvery { repository.getTradeSummaryStats() } returns TradeSummaryStats(
                    totalTradesExecuted = 2L,
                    totalVolumeTraded = BigDecimal("5000.00"),
                    totalFeesPaid = BigDecimal.ZERO,
                    latestSnapshotTime = cutoff,
                )

                // Identical BUY twins before cutoff: live must reverse-apply; dry-run must not.
                // Reverse of one BUY 0.1 @ 2500 → BTC 0.1, USD 7500. If dry-run also applied → BTC 0.0, USD 10000.
                val dryRunTwin = TestFixtures.tradeRecord(
                    timestamp = cutoff.minus(2, ChronoUnit.DAYS),
                    pair = TestFixtures.BTCUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.1"),
                    usdAmount = BigDecimal("2500.00"),
                    dryRun = true,
                    price = BigDecimal("25000.00"),
                    fee = BigDecimal("5.00"),
                )
                val liveTwin = dryRunTwin.copy(
                    dryRun = false,
                    timestamp = cutoff.minus(1, ChronoUnit.DAYS),
                )

                coEvery { krakenService.getTradeHistory(any(), 0) } returns emptyList()
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(dryRunTwin, liveTwin)
                coEvery { repository.saveTrade(any()) } returns 1
                coEvery { repository.updateTrade(any(), any()) } just Runs
                coEvery { repository.setHistorySeeded(true) } just Runs
                coEvery { repository.setSyncMetadata(any(), any()) } just Runs
                coEvery { krakenService.getOHLC(TestFixtures.BTCUSD, 1440, any()) } returns emptyList()

                val reconstructed = slot<List<PortfolioSnapshot>>()
                coEvery { repository.save(capture(reconstructed)) } just Runs

                service.syncTradesFromKraken()

                reconstructed.isCaptured.shouldBeTrue()
                val earliest = reconstructed.captured.minBy { it.timestamp }
                earliest.assets.getValue(Asset.BTC).balance.shouldBeEqualComparingTo(BigDecimal("0.1"))
                // Reverse BUY restores usdAmount + fee onto USD.
                earliest.assets.getValue(TestFixtures.USD).balance.shouldBeEqualComparingTo(BigDecimal("7505.00"))
            }
        }

        "syncTradesFromKraken_UsesKrakenServiceImplLastFetchedCountForSyncMetadata" {
            runTest {
                val realKraken = mockk<KrakenServiceImpl>(relaxed = true)
                every { realKraken.getLastTradeHistoryTotalCount() } returns 42
                coEvery { realKraken.getTradeHistory(any(), 0) } returns emptyList()
                stubWithStableBackend(realKraken)

                val appConfig = AppConfig(
                    kraken = KrakenCredentials(
                        TestFixtures.TRADE_HISTORY_API_KEY,
                        TestFixtures.TRADE_HISTORY_API_SECRET,
                    ),
                    settings = TestFixtures.settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
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
                coEvery { repository.load() } returns emptyList()
                coEvery { repository.getTradeSummaryStats() } returns TradeSummaryStats(
                    totalTradesExecuted = 0L,
                    totalVolumeTraded = BigDecimal.ZERO,
                    totalFeesPaid = BigDecimal.ZERO,
                    latestSnapshotTime = null,
                )
                coEvery { repository.setHistorySeeded(true) } just Runs
                coEvery { repository.setSyncMetadata(any(), any()) } just Runs

                TradeHistoryServiceImpl(
                    repository,
                    statsRepository,
                    ledgerRepository,
                    realKraken,
                    configService,
                    objectMapper,
                    portfolioAnalyzer,
                    TestFixtures.TEST_TRADE_HISTORY_JSON,
                ).syncTradesFromKraken()

                coVerify(exactly = 1) {
                    repository.setSyncMetadata(TestFixtures.SYNC_TOTAL, "42")
                }
                coVerify(exactly = 1) {
                    repository.setSyncMetadata(TestFixtures.SYNC_OFFSET, "0")
                }
            }
        }

        "syncTradesFromKraken_UsesDynamicKrakenServiceRealLastFetchedCount" {
            runTest {
                val realKraken = mockk<KrakenServiceImpl>(relaxed = true)
                every { realKraken.getLastTradeHistoryTotalCount() } returns 99
                coEvery { realKraken.getTradeHistory(any(), any()) } returns emptyList()
                val simulated = mockk<SimulatedKrakenService>(relaxed = true)
                val dynamic = DynamicKrakenService(
                    realKraken,
                    simulated,
                    configService,
                )

                val appConfig = AppConfig(
                    kraken = KrakenCredentials(
                        TestFixtures.TRADE_HISTORY_API_KEY,
                        TestFixtures.TRADE_HISTORY_API_SECRET,
                    ),
                    settings = TestFixtures.settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
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
                coEvery { repository.load() } returns emptyList()
                coEvery { repository.getTradeSummaryStats() } returns TradeSummaryStats(
                    totalTradesExecuted = 0L,
                    totalVolumeTraded = BigDecimal.ZERO,
                    totalFeesPaid = BigDecimal.ZERO,
                    latestSnapshotTime = null,
                )
                coEvery { repository.setHistorySeeded(true) } just Runs
                coEvery { repository.setSyncMetadata(any(), any()) } just Runs

                TradeHistoryServiceImpl(
                    repository,
                    statsRepository,
                    ledgerRepository,
                    dynamic,
                    configService,
                    objectMapper,
                    portfolioAnalyzer,
                    TestFixtures.TEST_TRADE_HISTORY_JSON,
                ).syncTradesFromKraken()

                coVerify(exactly = 2) {
                    repository.setSyncMetadata(TestFixtures.SYNC_TOTAL, "99")
                }
            }
        }

        "syncTradesFromKraken_ReconstructionMissingTickerPricesClampToZero" {
            runTest {
                val appConfig = AppConfig(
                    kraken = KrakenCredentials(
                        TestFixtures.TRADE_HISTORY_API_KEY,
                        TestFixtures.TRADE_HISTORY_API_SECRET,
                    ),
                    settings = TestFixtures.settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
                    ),
                    allocations = listOf(
                        Allocation(Asset.BTC, 40.0),
                        Allocation(Asset.ETH, 40.0),
                        Allocation(TestFixtures.USD, 20.0),
                    ),
                )
                every { configService.getConfig() } returns appConfig
                val apiTrade = TestFixtures.tradeRecord(
                    timestamp = Instant.now().minus(2, ChronoUnit.DAYS),
                    pair = TestFixtures.BTCUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.5"),
                    usdAmount = BigDecimal("15000.00"),
                    price = BigDecimal("30000.00"),
                    fee = BigDecimal("15.00"),
                )
                val balances = mapOf(
                    Asset.BTC to BigDecimal("0.5"),
                    Asset.ETH to BigDecimal("2.0"),
                    TestFixtures.USD to BigDecimal("1000.0"),
                )
                val reconstructed = slot<List<PortfolioSnapshot>>()

                val service = createService()
                every { configService.getConfig() } returns appConfig
                coEvery { repository.isHistorySeeded() } returns false
                coEvery { repository.getLatestTradeTime() } returns null
                coEvery { repository.getSyncMetadata(any()) } returns null
                coEvery { repository.load() } returns emptyList()
                coEvery { repository.getTradeSummaryStats() } returns TradeSummaryStats(
                    totalTradesExecuted = 1L,
                    totalVolumeTraded = BigDecimal.ZERO,
                    totalFeesPaid = BigDecimal.ZERO,
                    latestSnapshotTime = null,
                )
                coEvery { krakenService.getTradeHistory(any(), 0) } returns listOf(apiTrade)
                coEvery { krakenService.getTradeHistory(any(), 50) } returns emptyList()
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(apiTrade)
                coEvery { repository.saveTrade(any()) } returns 1
                coEvery { repository.setHistorySeeded(true) } just Runs
                coEvery { repository.setSyncMetadata(any(), any()) } just Runs
                coEvery { krakenService.getBalances() } returns balances
                every { portfolioAnalyzer.resolveBalance(Asset.BTC, balances) } returns BigDecimal("0.5")
                every { portfolioAnalyzer.resolveBalance(Asset.ETH, balances) } returns BigDecimal("2.0")
                every { portfolioAnalyzer.resolveBalance(TestFixtures.USD, balances) } returns BigDecimal("1000.0")
                // BTC ticker present (Kraken XBTUSD); ETH missing → ETH clamps to zero via currentPrices fallback
                coEvery { krakenService.getTickerPrices(any()) } returns
                    mapOf(Asset.BTC_USD_PAIR to BigDecimal("30000.00"))
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns emptyList()
                coEvery { repository.save(capture(reconstructed)) } just Runs

                service.syncTradesFromKraken()

                reconstructed.isCaptured.shouldBeTrue()
                reconstructed.captured.first().assets.getValue(Asset.ETH).price
                    .shouldBeEqualComparingTo(BigDecimal.ZERO)
                reconstructed.captured.any {
                    it.assets[Asset.BTC]?.price?.compareTo(BigDecimal.ZERO) != 0
                }.shouldBeTrue()
            }
        }
    }
}
