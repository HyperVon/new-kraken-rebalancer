package com.gemini.krakenbot.service

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.TradeSummaryStats
import com.gemini.krakenbot.service.impl.history.LedgersSyncService
import com.gemini.krakenbot.service.impl.history.TradeHistoryReconstructionService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

@Suppress("unused")
class TradeHistoryReconstructionTest : TradeHistoryServiceTestBase() {

    init {
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

        "reconstructHistoricalSnapshots_WithExistingOldestSnapshotAndAthLoadFailure" {
            runTest {
                val service = createService()

                val appConfig = AppConfig(
                    kraken =
                    KrakenCredentials(
                        TestFixtures.TRADE_HISTORY_API_KEY,
                        TestFixtures.TRADE_HISTORY_API_SECRET,
                    ),
                    settings = TestFixtures.settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        minimumOrderSizeUSD = 5.0,
                        fiatMaxDrawdown = 30.0,
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
                coEvery { statsRepository.load() } throws IllegalStateException("stats unavailable")

                val existingSnapshot = PortfolioSnapshot(
                    timestamp = Instant.now().minus(5, ChronoUnit.DAYS),
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
                    totalTradesExecuted = 1L,
                    totalVolumeTraded = BigDecimal.ZERO,
                    totalFeesPaid = BigDecimal.ZERO,
                    latestSnapshotTime = null,
                )

                val apiTrade = TestFixtures.tradeRecord(
                    timestamp = Instant.now().minus(6, ChronoUnit.DAYS),
                    pair = TestFixtures.BTCUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.1"),
                    usdAmount = BigDecimal("2500.00"),
                    price = BigDecimal("25000.00"),
                    fee = BigDecimal("5.00"),
                )

                coEvery { krakenService.getTradeHistory(any(), 0) } returns listOf(apiTrade)
                coEvery { krakenService.getTradeHistory(any(), 50) } returns emptyList()
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(apiTrade)
                coEvery { repository.saveTrade(any()) } returns 1
                coEvery { repository.updateTrade(any(), any()) } just Runs
                coEvery { repository.setHistorySeeded(true) } just Runs
                coEvery { repository.setSyncMetadata(any(), any()) } just Runs

                coEvery { krakenService.getOHLC(TestFixtures.BTCUSD, 1440, any()) } returns emptyList()
                val reconstructed = slot<List<PortfolioSnapshot>>()
                coEvery { repository.save(capture(reconstructed)) } just Runs

                service.syncTradesFromKraken()

                coVerify(atLeast = 1) { repository.save(any()) }
                reconstructed.captured.last().drawdownPercent
                    .shouldBeEqualComparingTo(BigDecimal.ZERO)
                reconstructed.captured.last().effectiveUsdTargetPercent
                    .shouldBeEqualComparingTo(BigDecimal("50.00"))
            }
        }

        "reconstructHistoricalSnapshots_AppliesStakingRewardsFromLedgers" {
            runTest {
                val service = createService()

                val appConfig = AppConfig(
                    kraken =
                    KrakenCredentials(
                        TestFixtures.TRADE_HISTORY_API_KEY,
                        TestFixtures.TRADE_HISTORY_API_SECRET,
                    ),
                    settings = TestFixtures.settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        minimumOrderSizeUSD = 5.0,
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
                    totalTradesExecuted = 1L,
                    totalVolumeTraded = BigDecimal.ZERO,
                    totalFeesPaid = BigDecimal.ZERO,
                    latestSnapshotTime = null,
                )

                val apiTrade = TestFixtures.tradeRecord(
                    timestamp = Instant.now().minus(6, ChronoUnit.DAYS),
                    pair = TestFixtures.BTCUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.1"),
                    usdAmount = BigDecimal("2500.00"),
                    price = BigDecimal("25000.00"),
                    fee = BigDecimal("5.00"),
                )

                coEvery { krakenService.getTradeHistory(any(), 0) } returns listOf(apiTrade)
                coEvery { krakenService.getTradeHistory(any(), 50) } returns emptyList()
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(apiTrade)
                coEvery { repository.saveTrade(any()) } returns 1
                coEvery { repository.updateTrade(any(), any()) } just Runs
                coEvery { repository.setHistorySeeded(true) } just Runs
                coEvery { repository.setSyncMetadata(any(), any()) } just Runs

                coEvery { krakenService.getOHLC(TestFixtures.BTCUSD, 1440, any()) } returns emptyList()
                coEvery { repository.save(any()) } just Runs

                val stakingEvent = LedgerEvent(
                    ledgerId = "L1",
                    time = Instant.now().minus(4, ChronoUnit.DAYS),
                    type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                    asset = "BTC",
                    amount = BigDecimal("0.05"),
                )
                coEvery { ledgerRepository.getLedgersInRange(any(), any()) } returns listOf(stakingEvent)

                service.syncTradesFromKraken()

                coVerify(atLeast = 1) { ledgerRepository.getLedgersInRange(any(), any()) }
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
                    settings = TestFixtures.settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        minimumOrderSizeUSD = 5.0,
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
                coEvery { krakenService.getTradeHistory(any(), 0) } returns listOf(apiTrade)
                coEvery { krakenService.getTradeHistory(any(), 50) } returns emptyList()
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(apiTrade)
                coEvery { repository.saveTrade(any()) } returns 1
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

        "reconstructHistoricalSnapshots_resolvesCanonicalKrakenTickerKeys" {
            runTest {
                val appConfig = AppConfig(
                    kraken =
                    KrakenCredentials(
                        TestFixtures.TRADE_HISTORY_API_KEY,
                        TestFixtures.TRADE_HISTORY_API_SECRET,
                    ),
                    settings = TestFixtures.settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        minimumOrderSizeUSD = 5.0,
                    ),
                    allocations = listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(TestFixtures.USD, 50.0),
                    ),
                )
                every { configService.getConfig() } returns appConfig

                coEvery { repository.load() } returns emptyList()
                val balances = mapOf(
                    Asset.BTC to BigDecimal.ONE,
                    TestFixtures.USD to BigDecimal("30000.00"),
                )
                coEvery { krakenService.getBalances() } returns balances
                every { portfolioAnalyzer.resolveBalance(Asset.BTC, balances) } returns BigDecimal.ONE
                every {
                    portfolioAnalyzer.resolveBalance(TestFixtures.USD, balances)
                } returns BigDecimal("30000.00")

                // Live Kraken keys tickers by canonical pair (XXBTZUSD), not the request alias (XBTUSD).
                val canonicalPrices = mapOf(TestFixtures.XXBTZUSD to BigDecimal("30000.00"))
                coEvery { krakenService.getTickerPrices(any()) } returns canonicalPrices
                every {
                    portfolioAnalyzer.resolvePriceFromTicker(Asset.BTC, canonicalPrices)
                } returns BigDecimal("30000.00")
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns emptyList()
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()

                val reconstructed = slot<List<PortfolioSnapshot>>()
                coEvery { repository.save(capture(reconstructed)) } just Runs

                val reconstructionService = TradeHistoryReconstructionService(
                    repository = repository,
                    ledgerRepository = ledgerRepository,
                    krakenService = krakenService,
                    configService = configService,
                    portfolioAnalyzer = portfolioAnalyzer,
                )
                reconstructionService.reconstructHistoricalSnapshots()

                verify(exactly = 1) { portfolioAnalyzer.resolvePriceFromTicker(Asset.BTC, canonicalPrices) }
                reconstructed.isCaptured.shouldBeTrue()
                reconstructed.captured.any {
                    it.assets[Asset.BTC]?.price?.compareTo(BigDecimal.ZERO) != 0
                }.shouldBeTrue()
                coVerify(exactly = 1) {
                    repository.setSyncMetadata(
                        SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION,
                        TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION,
                    )
                }
                coVerify(exactly = 1) {
                    repository.setSyncMetadata(
                        SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_LEDGER_COVERAGE_VERSION,
                        LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
                    )
                }
            }
        }

        listOf("balances", "ticker", "OHLC").forEach { cancellationPoint ->
            "CQ-10-2: reconstruction propagates $cancellationPoint cancellation without saving" {
                runTest {
                    val allocations = listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(TestFixtures.USD, 50.0),
                    )
                    every { configService.getConfig() } returns AppConfig(
                        kraken = KrakenCredentials(
                            TestFixtures.TRADE_HISTORY_API_KEY,
                            TestFixtures.TRADE_HISTORY_API_SECRET,
                        ),
                        settings = TestFixtures.settings(
                            dryRun = false,
                            loopDelaySeconds = 60,
                            deviationTriggerPercent = 5.0,
                            minimumOrderSizeUSD = 5.0,
                        ),
                        allocations = allocations,
                    )
                    coEvery { repository.load() } returns emptyList()
                    coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()

                    val balances = mapOf(
                        Asset.BTC to BigDecimal.ONE,
                        TestFixtures.USD to BigDecimal("1000.00"),
                    )
                    if (cancellationPoint == "balances") {
                        coEvery { krakenService.getBalances() } throws CancellationException("cancel balances")
                    } else {
                        coEvery { krakenService.getBalances() } returns balances
                    }
                    every { portfolioAnalyzer.resolveBalance(Asset.BTC, balances) } returns BigDecimal.ONE
                    every {
                        portfolioAnalyzer.resolveBalance(TestFixtures.USD, balances)
                    } returns BigDecimal("1000.00")

                    if (cancellationPoint == "ticker") {
                        coEvery { krakenService.getTickerPrices(any()) } throws CancellationException("cancel ticker")
                    } else {
                        coEvery { krakenService.getTickerPrices(any()) } returns
                            mapOf(TestFixtures.BTCUSD to BigDecimal("30000.00"))
                    }
                    if (cancellationPoint == "OHLC") {
                        coEvery {
                            krakenService.getOHLC(TestFixtures.XBTUSD, 1440, any())
                        } throws CancellationException("cancel OHLC")
                    } else {
                        coEvery { krakenService.getOHLC(any(), any(), any()) } returns emptyList()
                    }

                    val reconstructionService = TradeHistoryReconstructionService(
                        repository = repository,
                        ledgerRepository = ledgerRepository,
                        krakenService = krakenService,
                        configService = configService,
                        portfolioAnalyzer = portfolioAnalyzer,
                    )

                    shouldThrow<CancellationException> {
                        reconstructionService.reconstructHistoricalSnapshots()
                    }
                    coVerify(exactly = 0) { repository.save(any()) }
                    coVerify(exactly = 0) {
                        repository.setSyncMetadata(
                            SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION,
                            TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION,
                        )
                    }
                }
            }
        }

        listOf(
            "cancellation" to CancellationException("cancel snapshot save"),
            "failure" to IllegalStateException("snapshot save failed"),
        ).forEach { (kind, saveFailure) ->
            "CQ-14-2: reconstruction propagates $kind while saving snapshots" {
                runTest {
                    val allocations = listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(TestFixtures.USD, 50.0),
                    )
                    every { configService.getConfig() } returns AppConfig(
                        kraken = KrakenCredentials(
                            TestFixtures.TRADE_HISTORY_API_KEY,
                            TestFixtures.TRADE_HISTORY_API_SECRET,
                        ),
                        settings = TestFixtures.settings(
                            dryRun = false,
                            loopDelaySeconds = 60,
                            deviationTriggerPercent = 5.0,
                            minimumOrderSizeUSD = 5.0,
                        ),
                        allocations = allocations,
                    )
                    coEvery { repository.load() } returns emptyList()
                    val balances = mapOf(
                        Asset.BTC to BigDecimal.ONE,
                        TestFixtures.USD to BigDecimal("1000.00"),
                    )
                    coEvery { krakenService.getBalances() } returns balances
                    every { portfolioAnalyzer.resolveBalance(Asset.BTC, balances) } returns BigDecimal.ONE
                    every {
                        portfolioAnalyzer.resolveBalance(TestFixtures.USD, balances)
                    } returns BigDecimal("1000.00")
                    coEvery { krakenService.getTickerPrices(any()) } returns
                        mapOf(TestFixtures.BTCUSD to BigDecimal("30000.00"))
                    coEvery { krakenService.getOHLC(any(), any(), any()) } returns emptyList()
                    coEvery { repository.getTradesInRange(any(), any()) } returns listOf(
                        TestFixtures.tradeRecord(
                            timestamp = Instant.now().minus(1, ChronoUnit.DAYS),
                            pair = TestFixtures.BTCUSD,
                            side = TestFixtures.BUY,
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.1"),
                            usdAmount = BigDecimal("3000.00"),
                            price = BigDecimal("30000.00"),
                            fee = BigDecimal("3.00"),
                        ),
                    )
                    coEvery { repository.save(any()) } throws saveFailure

                    val reconstructionService = TradeHistoryReconstructionService(
                        repository = repository,
                        ledgerRepository = ledgerRepository,
                        krakenService = krakenService,
                        configService = configService,
                        portfolioAnalyzer = portfolioAnalyzer,
                    )

                    if (saveFailure is CancellationException) {
                        shouldThrow<CancellationException> {
                            reconstructionService.reconstructHistoricalSnapshots()
                        }
                    } else {
                        shouldThrow<IllegalStateException> {
                            reconstructionService.reconstructHistoricalSnapshots()
                        }
                    }
                    coVerify(exactly = 1) { repository.save(any()) }
                    coVerify(exactly = 0) {
                        repository.setSyncMetadata(
                            SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION,
                            TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION,
                        )
                    }
                }
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
                    settings = TestFixtures.settings(
                        simulation = true,
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        minimumOrderSizeUSD = 5.0,
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
                coEvery { krakenService.getTradeHistory(any(), 0) } returns listOf(apiTrade)
                coEvery { krakenService.getTradeHistory(any(), 50) } returns emptyList()
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(apiTrade)
                coEvery { repository.saveTrade(any()) } returns 1
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
                    settings = TestFixtures.settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        minimumOrderSizeUSD = 5.0,
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
                coVerify(exactly = 1) { configService.beginExecutionSession() }
                coVerify(exactly = 1) { configService.endExecutionSession() }
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
                    settings = TestFixtures.settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        minimumOrderSizeUSD = 5.0,
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

                coVerify(exactly = 1) { krakenService.getBalances() }
                coVerify(exactly = 0) { repository.save(any()) }

                service.syncTradesFromKraken()

                coVerify(exactly = 1) { krakenService.getTradeHistory(any(), any()) }
                coVerify(exactly = 1) { krakenService.getBalances() }
            }
        }

        "CQ-18-6: reconstruction passes nowProvider through to buildTimelineEvents" {
            runTest {
                val fixedNow = Instant.parse("2024-06-01T00:00:00Z")
                val allocations = listOf(
                    Allocation(Asset.BTC, 100.0),
                    Allocation(TestFixtures.USD, 0.0),
                )
                every { configService.getConfig() } returns AppConfig(
                    kraken =
                    KrakenCredentials(
                        TestFixtures.TRADE_HISTORY_API_KEY,
                        TestFixtures.TRADE_HISTORY_API_SECRET,
                    ),
                    settings =
                    TestFixtures.settings(
                        dryRun = false,
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        minimumOrderSizeUSD = 5.0,
                    ),
                    allocations = allocations,
                )
                coEvery { repository.load() } returns emptyList()
                coEvery { krakenService.getBalances() } returns mapOf(Asset.BTC to BigDecimal.ONE)
                every { portfolioAnalyzer.resolveBalance(any(), any()) } returns BigDecimal.ONE
                coEvery { krakenService.getTickerPrices(any()) } returns
                    mapOf(TestFixtures.BTCUSD to BigDecimal("30000.00"))
                every { portfolioAnalyzer.resolvePriceFromTicker(any(), any()) } returns BigDecimal("30000.00")
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns emptyList()
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()

                val captured = slot<List<PortfolioSnapshot>>()
                coEvery { repository.save(capture(captured)) } just Runs

                val reconstructionService = TradeHistoryReconstructionService(
                    repository = repository,
                    ledgerRepository = ledgerRepository,
                    krakenService = krakenService,
                    configService = configService,
                    portfolioAnalyzer = portfolioAnalyzer,
                    nowProvider = { fixedNow },
                )
                reconstructionService.reconstructHistoricalSnapshots()

                val expectedDailyClose =
                    fixedNow.minus(1, ChronoUnit.DAYS)
                        .truncatedTo(ChronoUnit.DAYS)
                        .plus(23, ChronoUnit.HOURS)
                        .plus(59, ChronoUnit.MINUTES)
                        .plus(59, ChronoUnit.SECONDS)
                captured.captured.any { it.timestamp == expectedDailyClose } shouldBe true
            }
        }

        "canRebuildSnapshots returns false when ledger store is seeded but coverage version is stale" {
            runTest {
                val appConfig = AppConfig(
                    kraken = KrakenCredentials(
                        TestFixtures.TRADE_HISTORY_API_KEY,
                        TestFixtures.TRADE_HISTORY_API_SECRET,
                    ),
                    settings = TestFixtures.settings(),
                    allocations = emptyList(),
                )
                val reconstructionService = TradeHistoryReconstructionService(
                    repository = repository,
                    ledgerRepository = ledgerRepository,
                    krakenService = krakenService,
                    configService = configService,
                    portfolioAnalyzer = portfolioAnalyzer,
                )
                coEvery { ledgerRepository.isLedgersSeeded() } returns true
                coEvery {
                    ledgerRepository.getSyncMetadata(
                        com.gemini.krakenbot.model.SyncMetadataKeys.LEDGER_COVERAGE_VERSION,
                    )
                } returns "1"

                reconstructionService.canRebuildSnapshots() shouldBe false

                shouldThrow<IllegalStateException> {
                    reconstructionService.rebuildHistoricalSnapshots(appConfig, krakenService)
                }
            }
        }

        "canRebuildSnapshots returns true when ledger store is seeded and coverage version is current" {
            runTest {
                val reconstructionService = TradeHistoryReconstructionService(
                    repository = repository,
                    ledgerRepository = ledgerRepository,
                    krakenService = krakenService,
                    configService = configService,
                    portfolioAnalyzer = portfolioAnalyzer,
                )
                coEvery { ledgerRepository.isLedgersSeeded() } returns true
                coEvery {
                    ledgerRepository.getSyncMetadata(
                        com.gemini.krakenbot.model.SyncMetadataKeys.LEDGER_COVERAGE_VERSION,
                    )
                } returns com.gemini.krakenbot.service.impl.history.LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION

                reconstructionService.canRebuildSnapshots() shouldBe true
            }
        }

        "reconstructHistoricalSnapshots skips execution and does not set version marker when ledger coverage is stale" {
            runTest {
                val appConfig = AppConfig(
                    kraken = KrakenCredentials(
                        TestFixtures.TRADE_HISTORY_API_KEY,
                        TestFixtures.TRADE_HISTORY_API_SECRET,
                    ),
                    settings = TestFixtures.settings(),
                    allocations = emptyList(),
                )
                val reconstructionService = TradeHistoryReconstructionService(
                    repository = repository,
                    ledgerRepository = ledgerRepository,
                    krakenService = krakenService,
                    configService = configService,
                    portfolioAnalyzer = portfolioAnalyzer,
                )
                coEvery { ledgerRepository.isLedgersSeeded() } returns true
                coEvery {
                    ledgerRepository.getSyncMetadata(
                        com.gemini.krakenbot.model.SyncMetadataKeys.LEDGER_COVERAGE_VERSION,
                    )
                } returns "1" // stale

                reconstructionService.reconstructHistoricalSnapshots(appConfig, krakenService)

                coVerify(exactly = 0) {
                    repository.setSyncMetadata(
                        com.gemini.krakenbot.model.SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION,
                        any(),
                    )
                }
            }
        }
    }
}
