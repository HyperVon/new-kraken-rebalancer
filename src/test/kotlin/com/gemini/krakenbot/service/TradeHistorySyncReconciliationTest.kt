@file:OptIn(ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.OrderSubmissionState
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.TradeSummaryStats
import com.gemini.krakenbot.service.impl.history.TradeHistoryServiceImpl
import com.gemini.krakenbot.util.TradeCalculator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant

class TradeHistorySyncReconciliationTest : TradeHistoryServiceTestBase() {

    init {
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
                verify(exactly = 1) { configService.beginExecutionSession() }
                verify(exactly = 1) { configService.endExecutionSession() }
            }
        }

        "syncTradesFromKraken_NoApiKey" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns false
                val emptyConfig = AppConfig(
                    kraken = KrakenCredentials("", ""),
                    settings = TestFixtures.settings(
                        dryRun = false,
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
                        fiatMaxDrawdown = 30.0,
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
                verify(exactly = 0) { configService.beginExecutionSession() }
                verify(exactly = 0) { configService.endExecutionSession() }
            }
        }

        "syncTradesFromKraken_SuccessSeeding_NoDuplicates" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns false
                coEvery { repository.getLatestTradeTime() } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()

                val now = Instant.now()
                val apiTrades = listOf(
                    TestFixtures.tradeRecord(
                        timestamp = now,
                        pair = Asset.BTC_USD_PAIR,
                        side = OrderSide.BUY.name,
                        symbol = Asset.BTC,
                        volume = BigDecimal.ONE,
                        usdAmount = BigDecimal.TEN,
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

                val duplicateTrade = TestFixtures.tradeRecord(
                    timestamp = latestTime,
                    pair = Asset.BTC_USD_PAIR,
                    side = OrderSide.BUY.name,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(duplicateTrade)

                val newTrade = TestFixtures.tradeRecord(
                    timestamp = latestTime.plusSeconds(60),
                    pair = Asset.BTC_USD_PAIR,
                    side = OrderSide.SELL.name,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
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

                val localTrade = TestFixtures.tradeRecord(
                    timestamp = latestTime,
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    price = BigDecimal.TEN,
                    expectedPrice = BigDecimal("10.05"),
                    source = TradeSource.LOCAL_ESTIMATE,
                    cycleId = "cycle-keep-me",
                    orderTxid = "API-OID",
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(localTrade)

                val apiTrade = TestFixtures.tradeRecord(
                    timestamp = latestTime.plusSeconds(5),
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.valueOf(9.95),
                    price = BigDecimal("9.95"),
                    fee = BigDecimal("0.0259"),
                    source = TradeSource.API_FILL,
                    orderTxid = "API-OID",
                )

                coEvery { krakenService.getTradeHistory(1700000000 - 300, 0) } returns listOf(apiTrade)
                coEvery { krakenService.getTradeHistory(1700000000 - 300, 50) } returns emptyList()

                val reconciledSlot = slot<TradeRecord>()
                coEvery { repository.updateTrade(localTrade, capture(reconciledSlot)) } just Runs

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                coVerify(exactly = 1) { repository.updateTrade(localTrade, any()) }
                reconciledSlot.captured.source shouldBe TradeSource.API_FILL
                reconciledSlot.captured.cycleId shouldBe "cycle-keep-me"
                reconciledSlot.captured.orderTxid shouldBe "API-OID"
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

        "sync never clears an unresolved submission with an economics heuristic" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns true
                val submittedAt = Instant.ofEpochSecond(1700000000)
                coEvery { repository.getLatestTradeTime() } returns submittedAt
                val unresolved =
                    TestFixtures.tradeRecord(
                        timestamp = submittedAt,
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal.ONE,
                        usdAmount = BigDecimal.TEN,
                        success = false,
                        price = BigDecimal.TEN,
                        source = TradeSource.LOCAL_ESTIMATE,
                        clientOrderId = "74cf3df5-fe0c-4bd7-a884-b630701cfcd8",
                        submissionState = OrderSubmissionState.UNCERTAIN,
                    )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(unresolved)
                val similarFill =
                    unresolved.copy(
                        timestamp = submittedAt.plusSeconds(5),
                        success = true,
                        source = TradeSource.API_FILL,
                        clientOrderId = null,
                        submissionState = null,
                    )
                coEvery { krakenService.getTradeHistory(1700000000 - 300, 0) } returns listOf(similarFill)
                coEvery { krakenService.getTradeHistory(1700000000 - 300, 50) } returns emptyList()

                createService().syncTradesFromKraken()

                coVerify(exactly = 0) { repository.updateTrade(unresolved, any()) }
                coVerify(exactly = 1) { repository.saveTrade(similarFill) }
            }
        }

        "sync does not reconcile a failed local estimate" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns true
                val submittedAt = Instant.ofEpochSecond(1700000000)
                coEvery { repository.getLatestTradeTime() } returns submittedAt
                val failedLocal = TestFixtures.tradeRecord(
                    timestamp = submittedAt,
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    success = false,
                    source = TradeSource.LOCAL_ESTIMATE,
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(failedLocal)
                val apiFill = failedLocal.copy(
                    timestamp = submittedAt.plusSeconds(5),
                    success = true,
                    source = TradeSource.API_FILL,
                )
                coEvery { krakenService.getTradeHistory(1700000000 - 300, 0) } returns listOf(apiFill)

                createService().syncTradesFromKraken()

                coVerify(exactly = 0) { repository.updateTrade(failedLocal, any()) }
                coVerify(exactly = 1) { repository.saveTrade(apiFill) }
            }
        }

        "CQ-10-1: reconciliation retains local Kraken order txid when API fill omits it" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns true
                val latestTime = Instant.ofEpochSecond(1700000000)
                coEvery { repository.getLatestTradeTime() } returns latestTime

                val localTrade = TestFixtures.tradeRecord(
                    timestamp = latestTime,
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    price = BigDecimal.TEN,
                    expectedPrice = BigDecimal("10.05"),
                    source = TradeSource.LOCAL_ESTIMATE,
                    cycleId = "cycle-keep-me",
                    orderTxid = "LOCAL-OID",
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(localTrade)

                val apiTrade = localTrade.copy(
                    timestamp = latestTime.plusSeconds(5),
                    usdAmount = BigDecimal("9.95"),
                    price = BigDecimal("9.95"),
                    expectedPrice = null,
                    source = TradeSource.API_FILL,
                    cycleId = null,
                    orderTxid = null,
                )
                coEvery { krakenService.getTradeHistory(1700000000 - 300, 0) } returns listOf(apiTrade)

                val reconciledSlot = slot<TradeRecord>()
                coEvery { repository.updateTrade(localTrade, capture(reconciledSlot)) } just Runs

                createService().syncTradesFromKraken()

                coVerify(exactly = 1) { repository.updateTrade(localTrade, any()) }
                reconciledSlot.captured.orderTxid shouldBe "LOCAL-OID"
                reconciledSlot.captured.cycleId shouldBe "cycle-keep-me"
            }
        }

        "CQ-11-L5: reconciliation does not cross conflicting order txids" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns true
                val latestTime = Instant.ofEpochSecond(1700000000)
                coEvery { repository.getLatestTradeTime() } returns latestTime

                val olderLocal = TestFixtures.tradeRecord(
                    timestamp = latestTime,
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    price = BigDecimal.TEN,
                    expectedPrice = BigDecimal("10.05"),
                    source = TradeSource.LOCAL_ESTIMATE,
                    cycleId = "cycle-old",
                    orderTxid = "LOCAL-OID-OLD",
                )
                val newerLocal = TestFixtures.tradeRecord(
                    timestamp = latestTime.plusSeconds(1),
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    price = BigDecimal.TEN,
                    expectedPrice = BigDecimal("10.05"),
                    source = TradeSource.LOCAL_ESTIMATE,
                    cycleId = "cycle-new",
                    orderTxid = "LOCAL-OID-NEW",
                )
                // Newest-first as getTradesInRange (DESC) provides in production.
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(newerLocal, olderLocal)

                val apiTrade = TestFixtures.tradeRecord(
                    timestamp = latestTime.plusSeconds(5),
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.valueOf(9.95),
                    price = BigDecimal("9.95"),
                    fee = BigDecimal("0.0259"),
                    source = TradeSource.API_FILL,
                    orderTxid = "API-OID",
                )

                coEvery { krakenService.getTradeHistory(1700000000 - 300, 0) } returns listOf(apiTrade)
                coEvery { krakenService.getTradeHistory(1700000000 - 300, 50) } returns emptyList()

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                coVerify(exactly = 0) { repository.updateTrade(newerLocal, any()) }
                coVerify(exactly = 0) { repository.updateTrade(olderLocal, any()) }
                coVerify(exactly = 1) { repository.saveTrade(apiTrade) }
            }
        }

        "CQ-11-L5: reconciliation prefers the local estimate with the API order txid" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns true
                val latestTime = Instant.ofEpochSecond(1700000000)
                coEvery { repository.getLatestTradeTime() } returns latestTime

                val matchingOlderLocal = TestFixtures.tradeRecord(
                    timestamp = latestTime,
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    price = BigDecimal.TEN,
                    expectedPrice = BigDecimal("10.05"),
                    source = TradeSource.LOCAL_ESTIMATE,
                    cycleId = "cycle-matching",
                    orderTxid = "OID-MATCHING",
                )
                val newerHeuristicLocal = matchingOlderLocal.copy(
                    timestamp = latestTime.plusSeconds(1),
                    cycleId = "cycle-newer",
                    orderTxid = "OID-OTHER",
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns
                    listOf(newerHeuristicLocal, matchingOlderLocal)

                val apiTrade = matchingOlderLocal.copy(
                    timestamp = latestTime.plusSeconds(5),
                    usdAmount = BigDecimal("9.95"),
                    price = BigDecimal("9.95"),
                    fee = BigDecimal("0.0259"),
                    expectedPrice = null,
                    source = TradeSource.API_FILL,
                    cycleId = null,
                    orderTxid = "OID-MATCHING",
                )
                coEvery { krakenService.getTradeHistory(1700000000 - 300, 0) } returns listOf(apiTrade)

                val reconciledSlot = slot<TradeRecord>()
                coEvery { repository.updateTrade(matchingOlderLocal, capture(reconciledSlot)) } just Runs

                createService().syncTradesFromKraken()

                coVerify(exactly = 1) { repository.updateTrade(matchingOlderLocal, any()) }
                coVerify(exactly = 0) { repository.updateTrade(newerHeuristicLocal, any()) }
                reconciledSlot.captured.cycleId shouldBe "cycle-matching"
                reconciledSlot.captured.orderTxid shouldBe "OID-MATCHING"
            }
        }

        "syncTradesFromKraken_ReconcilesSlightlyDifferentMarketFill" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns true
                val latestTime = Instant.ofEpochSecond(1700000000)
                coEvery { repository.getLatestTradeTime() } returns latestTime

                val localEstimate = TestFixtures.tradeRecord(
                    timestamp = latestTime,
                    pair = "TAOUSD",
                    side = TestFixtures.SELL,
                    symbol = "TAO",
                    volume = BigDecimal("0.07708000"),
                    usdAmount = BigDecimal("16.63"),
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
                    TestFixtures.tradeRecord(
                        timestamp = Instant.ofEpochSecond(1700000000 + i.toLong()),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal.ONE,
                        usdAmount = BigDecimal.TEN,
                    )
                }
                val batch2 = listOf(
                    TestFixtures.tradeRecord(
                        timestamp = Instant.ofEpochSecond(1700000600),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.SELL,
                        symbol = Asset.BTC,
                        volume = BigDecimal.ONE,
                        usdAmount = BigDecimal.TEN,
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

        "syncTradesFromKraken_continuesWhenFilteredPageIsShort" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns false
                coEvery { repository.getLatestTradeTime() } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                every { krakenService.getLastTradeHistoryTotalCount() } returns 51

                val firstPageFill = TestFixtures.tradeRecord(
                    timestamp = Instant.ofEpochSecond(1700000000),
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                )
                val secondPageFill = firstPageFill.copy(
                    timestamp = Instant.ofEpochSecond(1700000600),
                    side = TestFixtures.SELL,
                )
                coEvery { krakenService.getTradeHistory(null, 0) } returns listOf(firstPageFill)
                coEvery { krakenService.getTradeHistory(null, 50) } returns listOf(secondPageFill)

                createService().syncTradesFromKraken()

                coVerify(exactly = 1) { krakenService.getTradeHistory(null, 0) }
                coVerify(exactly = 1) { krakenService.getTradeHistory(null, 50) }
                coVerify(exactly = 2) { repository.saveTrade(any()) }
            }
        }

        listOf(
            "cancellation" to { CancellationException("cancel after page progress and persistence") },
            "failure" to { IllegalStateException("failure after page progress and persistence") },
        ).forEach { (interruptionKind, interruptionFactory) ->
            "CQ-14-L5: interrupted initial pagination resumes older fills after $interruptionKind" {
                runTest {
                    val now = Instant.parse("2033-05-01T12:00:00Z")
                    val allTrades = List(150) { index ->
                        val secondsAgo = when {
                            index < 50 -> index.toLong()
                            index < 100 -> 300L + (index - 50)
                            else -> 1_000L + (index - 100) * 600L
                        }
                        TestFixtures.tradeRecord(
                            timestamp = now.minusSeconds(secondsAgo),
                            pair = TestFixtures.XBTUSD,
                            side = TestFixtures.BUY,
                            symbol = Asset.BTC,
                            volume = BigDecimal.ONE,
                            usdAmount = BigDecimal.TEN,
                            source = TradeSource.API_FILL,
                            tradeId = "TRADE-${index.toString().padStart(3, '0')}",
                        )
                    }
                    val persistedTrades = mutableListOf<TradeRecord>()
                    val metadata = mutableMapOf<String, String>()
                    var seeded = false
                    var throwOnPersistedPageOne = true
                    val expectedFailure = interruptionFactory()

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
                        ),
                        allocations = emptyList(),
                    )
                    every { configService.getConfig() } returns appConfig
                    coEvery { repository.isHistorySeeded() } coAnswers { seeded }
                    coEvery { repository.getLatestTradeTime() } coAnswers {
                        persistedTrades.maxByOrNull(TradeRecord::timestamp)?.timestamp
                    }
                    coEvery { repository.getSyncMetadata(any()) } coAnswers {
                        metadata[firstArg<String>()]
                    }
                    coEvery { repository.setSyncMetadata(any(), any()) } coAnswers {
                        metadata[firstArg<String>()] = secondArg<String>()
                    }
                    coEvery { repository.setHistorySeeded(any()) } coAnswers {
                        seeded = firstArg<Boolean>()
                    }
                    coEvery { repository.getTradesInRange(any(), any()) } coAnswers {
                        val from = firstArg<Instant>()
                        val to = secondArg<Instant>()
                        persistedTrades
                            .filter { !it.timestamp.isBefore(from) && !it.timestamp.isAfter(to) }
                            .sortedByDescending(TradeRecord::timestamp)
                    }
                    coEvery { repository.saveTrade(any()) } coAnswers {
                        val trade = firstArg<TradeRecord>()
                        if (persistedTrades.none { it.tradeId == trade.tradeId }) persistedTrades.add(trade)
                        if (trade.tradeId == allTrades[50].tradeId && throwOnPersistedPageOne) {
                            throwOnPersistedPageOne = false
                            throw expectedFailure
                        }
                        persistedTrades.size
                    }
                    coEvery { repository.load() } returns emptyList()
                    coEvery { repository.getTradeSummaryStats() } returns TradeSummaryStats(
                        totalTradesExecuted = 0L,
                        totalVolumeTraded = BigDecimal.ZERO,
                        totalFeesPaid = BigDecimal.ZERO,
                        latestSnapshotTime = null,
                    )
                    every { krakenService.getLastTradeHistoryTotalCount() } returns allTrades.size
                    coEvery { krakenService.getTradeHistory(any(), any()) } coAnswers {
                        val startSec = firstArg<Long?>()
                        val offset = secondArg<Int?>() ?: 0
                        allTrades
                            .filter { startSec == null || it.timestamp.epochSecond >= startSec }
                            .drop(offset)
                            .take(50)
                    }

                    val service = createService(syncNowProvider = { now })

                    val thrown = shouldThrow<Throwable> { service.syncTradesFromKraken() }
                    thrown::class shouldBe expectedFailure::class

                    persistedTrades.size shouldBe 51
                    seeded shouldBe false
                    metadata[SyncMetadataKeys.SYNC_OFFSET] shouldBe "50"
                    metadata[SyncMetadataKeys.SYNC_TOTAL] shouldBe "150"
                    metadata[SyncMetadataKeys.HISTORY_SEEDED] shouldBe null
                    metadata[SyncMetadataKeys.SYNC_WATERMARK_EPOCH_SEC] shouldBe null
                    verify(exactly = 1) { configService.beginExecutionSession() }
                    verify(exactly = 1) { configService.endExecutionSession() }

                    service.syncTradesFromKraken()

                    persistedTrades.mapNotNull(TradeRecord::tradeId).toSet().size shouldBe 150
                    persistedTrades.size shouldBe 150
                    seeded shouldBe true
                    metadata[SyncMetadataKeys.SYNC_OFFSET] shouldBe SyncMetadataKeys.COMPLETED
                    metadata[SyncMetadataKeys.SYNC_TOTAL] shouldBe SyncMetadataKeys.COMPLETED
                    coVerify(exactly = 2) { krakenService.getTradeHistory(null, 0) }
                    coVerify(exactly = 1) { krakenService.getTradeHistory(null, 100) }
                    coVerify(exactly = 0) { krakenService.getTradeHistory(null, 150) }
                    verify(exactly = 2) { configService.beginExecutionSession() }
                    verify(exactly = 2) { configService.endExecutionSession() }
                }
            }
        }

        "CQ-14-L5: a seeded database with an orphaned numeric offset does not resume full-history pagination" {
            runTest {
                val now = Instant.parse("2033-05-01T12:00:00Z")
                // The seed completed but the process died before SYNC_OFFSET was rewritten to
                // COMPLETED, leaving a numeric cursor behind (the interrupted-seed marker).
                val seeded = true
                val metadata = mutableMapOf(
                    SyncMetadataKeys.HISTORY_SEEDED to "true",
                    SyncMetadataKeys.SYNC_OFFSET to "50",
                    SyncMetadataKeys.SYNC_TOTAL to "150",
                )
                val seedTrade = TestFixtures.tradeRecord(
                    timestamp = now.minusSeconds(60),
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    source = TradeSource.API_FILL,
                    tradeId = "TRADE-001",
                )
                val persistedTrades = mutableListOf(seedTrade)
                val queriedStartSecs = mutableListOf<Long?>()

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
                    ),
                    allocations = emptyList(),
                )
                every { configService.getConfig() } returns appConfig
                coEvery { repository.isHistorySeeded() } coAnswers { seeded }
                coEvery { repository.getLatestTradeTime() } coAnswers {
                    persistedTrades.maxByOrNull(TradeRecord::timestamp)?.timestamp
                }
                coEvery { repository.getSyncMetadata(any()) } coAnswers {
                    metadata[firstArg<String>()]
                }
                coEvery { repository.setSyncMetadata(any(), any()) } coAnswers {
                    metadata[firstArg<String>()] = secondArg<String>()
                }
                coEvery { repository.getTradesInRange(any(), any()) } coAnswers {
                    val from = firstArg<Instant>()
                    val to = secondArg<Instant>()
                    persistedTrades
                        .filter { !it.timestamp.isBefore(from) && !it.timestamp.isAfter(to) }
                        .sortedByDescending(TradeRecord::timestamp)
                }
                coEvery { repository.saveTrade(any()) } coAnswers {
                    val trade = firstArg<TradeRecord>()
                    if (persistedTrades.none { it.tradeId == trade.tradeId }) persistedTrades.add(trade)
                    persistedTrades.size
                }
                coEvery { repository.load() } returns emptyList()
                coEvery { repository.getTradeSummaryStats() } returns TradeSummaryStats(
                    totalTradesExecuted = 0L,
                    totalVolumeTraded = BigDecimal.ZERO,
                    totalFeesPaid = BigDecimal.ZERO,
                    latestSnapshotTime = null,
                )
                every { krakenService.getLastTradeHistoryTotalCount() } returns 150
                coEvery { krakenService.getTradeHistory(any(), any()) } coAnswers {
                    val startSec = firstArg<Long?>()
                    val offset = secondArg<Int?>() ?: 0
                    queriedStartSecs.add(startSec)
                    // The orphan offset must NOT widen the window to a full history query: the
                    // sync is incremental, so page zero only returns fills from the overlap window.
                    listOf<TradeRecord>()
                        .drop(offset)
                        .take(50)
                }

                val service = createService(syncNowProvider = { now })
                service.syncTradesFromKraken()

                // Every fetch used the incremental window, never a null startSec full-history query.
                queriedStartSecs.isNotEmpty() shouldBe true
                (queriedStartSecs.none { it == null }) shouldBe true
                // The orphaned numeric cursor is self-healed to COMPLETED.
                metadata[SyncMetadataKeys.SYNC_OFFSET] shouldBe SyncMetadataKeys.COMPLETED
                metadata[SyncMetadataKeys.SYNC_TOTAL] shouldBe SyncMetadataKeys.COMPLETED
                persistedTrades.mapNotNull(TradeRecord::tradeId).toSet().size shouldBe 1
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
                stats.allTimeHigh.shouldBeEqualComparingTo(BigDecimal.ZERO)
                stats.totalTradesExecuted shouldBe 0L
                stats.totalVolumeTraded.shouldBeEqualComparingTo(BigDecimal.ZERO)
                stats.totalFeesPaid.shouldBeEqualComparingTo(BigDecimal.ZERO)
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
                    settings = TestFixtures.settings(
                        dryRun = false,
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
                        fiatMaxDrawdown = 30.0,
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

                // Placeholder credentials + simulation=false: skip Kraken (see CQ-7-3 for simulation=true).
                coVerify(exactly = 0) { krakenService.getTradeHistory(any(), any()) }
                coVerify(exactly = 0) { repository.setHistorySeeded(any()) }
            }
        }

        "CQ-7-3: syncTradesFromKraken_SimulationAllowsPlaceholderCredentials" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns false
                val simulationConfig = AppConfig(
                    kraken = KrakenCredentials(
                        KrakenCredentials.PLACEHOLDER_API_KEY,
                        KrakenCredentials.PLACEHOLDER_PRIVATE_KEY,
                    ),
                    settings = TestFixtures.settings(
                        dryRun = false,
                        simulation = true,
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
                        fiatMaxDrawdown = 30.0,
                    ),
                    allocations = emptyList(),
                )
                every { configService.getConfig() } returns simulationConfig
                coEvery { krakenService.getTradeHistory(any(), any()) } returns emptyList()

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

                coVerify(exactly = 1) { krakenService.getTradeHistory(any(), any()) }
                coVerify(exactly = 1) { repository.setHistorySeeded(true) }
            }
        }
    }
}
