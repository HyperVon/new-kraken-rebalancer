package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.OrderSubmissionState
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.OrderIntentService
import com.gemini.krakenbot.service.TradeHistoryService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

class OrderExecutorSubmissionSafetyTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val krakenService = FakeKrakenService()
    private val tradeHistoryService = mockk<TradeHistoryService>(relaxed = true)
    private val orderExecutor = OrderExecutorImpl(krakenService, tradeHistoryService)

    init {
        "ambiguous live failure remains unresolved and blocks the next cycle" {
            runTest {
                coEvery { tradeHistoryService.saveTrade(any()) } returns 42
                coEvery { tradeHistoryService.hasPendingSubmissions() } returnsMany listOf(false, true)
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        success = false,
                        pair = pair,
                        side = side,
                        volume = volume,
                        errorMessage = "response lost",
                        submissionUncertain = true,
                    )
                }
                val settings = TestFixtures.settings(dryRun = false)
                val values = mapOf(Asset.USD to BigDecimal("100.00"))
                val prices =
                    mapOf(
                        Asset.BTC to BigDecimal("1000.00"),
                        Asset.ETH to BigDecimal("1000.00"),
                    )

                repeat(2) {
                    orderExecutor.executeOrders(
                        buyOrders =
                        linkedMapOf(
                            Asset.BTC to BigDecimal("25.00"),
                            Asset.ETH to BigDecimal("25.00"),
                        ),
                        sellOrders = emptyMap(),
                        currentValuesUSD = values,
                        prices = prices,
                        settings = settings,
                        actionLog = mutableListOf(),
                        cycleId = "cycle-$it",
                    )
                }

                krakenService.executedOrders.size shouldBe 1
                coVerify {
                    tradeHistoryService.updateTrade(
                        any(),
                        match { it.id == 42 && it.submissionState == OrderSubmissionState.UNCERTAIN },
                    )
                }
            }
        }

        "live success without an order txid becomes blocking UNCERTAIN" {
            runTest {
                coEvery { tradeHistoryService.saveTrade(any()) } returns 63
                coEvery { tradeHistoryService.hasPendingSubmissions() } returnsMany listOf(false, true)
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        success = true,
                        pair = pair,
                        side = side,
                        volume = volume,
                    )
                }
                val settings = TestFixtures.settings(dryRun = false)

                orderExecutor.executeOrders(
                    buyOrders = linkedMapOf(
                        Asset.BTC to BigDecimal("25.00"),
                        Asset.ETH to BigDecimal("25.00"),
                    ),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices = mapOf(
                        Asset.BTC to BigDecimal("1000.00"),
                        Asset.ETH to BigDecimal("1000.00"),
                    ),
                    settings = settings,
                    actionLog = mutableListOf(),
                    cycleId = "missing-txid-cycle",
                )
                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.ETH to BigDecimal("25.00")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices = mapOf(Asset.ETH to BigDecimal("1000.00")),
                    settings = settings,
                    actionLog = mutableListOf(),
                    cycleId = "blocked-after-missing-txid",
                )

                krakenService.executedOrders.size shouldBe 1
                coVerify(exactly = 1) {
                    tradeHistoryService.updateTrade(
                        any(),
                        match {
                            it.id == 63 &&
                                !it.success &&
                                it.submissionState == OrderSubmissionState.UNCERTAIN &&
                                it.errorMessage == "Order submission outcome is uncertain"
                        },
                    )
                }
            }
        }

        "live success with an order txid resolves and permits the remaining batch" {
            runTest {
                coEvery { tradeHistoryService.saveTrade(any()) } returnsMany listOf(64, 65)
                coEvery { tradeHistoryService.hasPendingSubmissions() } returns false
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        success = true,
                        pair = pair,
                        side = side,
                        volume = volume,
                        orderTxid = "OID-$side",
                    )
                }

                orderExecutor.executeOrders(
                    buyOrders = linkedMapOf(
                        Asset.BTC to BigDecimal("25.00"),
                        Asset.ETH to BigDecimal("25.00"),
                    ),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices = mapOf(
                        Asset.BTC to BigDecimal("1000.00"),
                        Asset.ETH to BigDecimal("1000.00"),
                    ),
                    settings = TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                    cycleId = "identified-live-cycle",
                )

                krakenService.executedOrders.size shouldBe 2
                coVerify(exactly = 2) {
                    tradeHistoryService.updateTrade(
                        any(),
                        match { it.success && it.submissionState == null },
                    )
                }
            }
        }

        "CQ-12-2: live IOException marks the intent uncertain, rethrows, and blocks retry" {
            runTest {
                coEvery { tradeHistoryService.saveTrade(any()) } returns 51
                coEvery { tradeHistoryService.hasPendingSubmissions() } returnsMany listOf(false, true)
                val original = IOException("connection reset after submission")
                krakenService.executeOrderAction = { _, _, _, _ -> throw original }
                val settings = TestFixtures.settings(dryRun = false)

                shouldThrow<IOException> {
                    orderExecutor.executeOrders(
                        buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                        sellOrders = emptyMap(),
                        currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                        prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                        settings = settings,
                        actionLog = mutableListOf(),
                        cycleId = "live-io-cycle",
                    )
                } shouldBe original

                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                    settings = settings,
                    actionLog = mutableListOf(),
                    cycleId = "blocked-cycle",
                )

                krakenService.executedOrders.size shouldBe 1
                coVerify {
                    tradeHistoryService.updateTrade(
                        any(),
                        match {
                            it.id == 51 &&
                                it.submissionState == OrderSubmissionState.UNCERTAIN &&
                                it.errorMessage == original.message
                        },
                    )
                }
            }
        }

        "live failure with no message uses the uncertain fallback text" {
            runTest {
                coEvery { tradeHistoryService.saveTrade(any()) } returns 55
                coEvery { tradeHistoryService.hasPendingSubmissions() } returns false
                val original = IOException()
                krakenService.executeOrderAction = { _, _, _, _ -> throw original }

                shouldThrow<IOException> {
                    orderExecutor.executeOrders(
                        buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                        sellOrders = emptyMap(),
                        currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                        prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                        settings = TestFixtures.settings(dryRun = false),
                        actionLog = mutableListOf(),
                        cycleId = "live-null-message-cycle",
                    )
                }

                coVerify {
                    tradeHistoryService.updateTrade(
                        any(),
                        match {
                            it.id == 55 &&
                                it.submissionState == OrderSubmissionState.UNCERTAIN &&
                                it.errorMessage == "Order submission outcome is uncertain"
                        },
                    )
                }
            }
        }

        "CQ-12-2: live cancellation marks the intent uncertain and rethrows the same exception" {
            runTest {
                coEvery { tradeHistoryService.saveTrade(any()) } returns 52
                coEvery { tradeHistoryService.hasPendingSubmissions() } returns false
                val original = CancellationException("placement cancelled")
                krakenService.executeOrderAction = { _, _, _, _ -> throw original }
                val thrown =
                    shouldThrow<CancellationException> {
                        orderExecutor.executeOrders(
                            buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                            sellOrders = emptyMap(),
                            currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                            prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                            settings =
                            TestFixtures.settings(dryRun = false),
                            actionLog = mutableListOf(),
                            cycleId = "live-cancel-cycle",
                        )
                    }

                thrown shouldBe original
                coVerify {
                    tradeHistoryService.updateTrade(
                        any(),
                        match { it.id == 52 && it.submissionState == OrderSubmissionState.UNCERTAIN },
                    )
                }
            }
        }

        "CQ-12-2: journaling failure never masks the original submission exception" {
            runTest {
                coEvery { tradeHistoryService.saveTrade(any()) } returns 53
                coEvery { tradeHistoryService.hasPendingSubmissions() } returns false
                val original = IOException("connection failed after submission")
                val persistenceFailure = IllegalStateException("trade journal unavailable")
                coEvery { tradeHistoryService.updateTrade(any(), any()) } throws persistenceFailure
                krakenService.executeOrderAction = { _, _, _, _ -> throw original }

                val thrown =
                    shouldThrow<IOException> {
                        orderExecutor.executeOrders(
                            buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                            sellOrders = emptyMap(),
                            currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                            prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                            settings = TestFixtures.settings(dryRun = false),
                            actionLog = mutableListOf(),
                            cycleId = "live-journal-failure-cycle",
                        )
                    }

                thrown shouldBe original
                thrown.suppressed.single() shouldBe persistenceFailure
            }
        }

        "CQ-12-2: journaling failure never masks the original cancellation" {
            runTest {
                coEvery { tradeHistoryService.saveTrade(any()) } returns 54
                coEvery { tradeHistoryService.hasPendingSubmissions() } returns false
                val original = CancellationException("placement cancelled")
                val persistenceFailure = IllegalStateException("trade journal unavailable")
                coEvery { tradeHistoryService.updateTrade(any(), any()) } throws persistenceFailure
                krakenService.executeOrderAction = { _, _, _, _ -> throw original }

                val thrown =
                    shouldThrow<CancellationException> {
                        orderExecutor.executeOrders(
                            buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                            sellOrders = emptyMap(),
                            currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                            prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                            settings = TestFixtures.settings(dryRun = false),
                            actionLog = mutableListOf(),
                            cycleId = "live-journal-cancellation-cycle",
                        )
                    }

                thrown shouldBe original
                thrown.suppressed.single() shouldBe persistenceFailure
            }
        }

        "durable order-intent service records pending and confirmed live placement" {
            runTest {
                val orderIntentService = mockk<OrderIntentService>(relaxed = true)
                val journaledExecutor = OrderExecutorImpl(krakenService, tradeHistoryService, orderIntentService)
                val events = mutableListOf<String>()
                var pendingTimestamp: Instant? = null
                coEvery { tradeHistoryService.saveTrade(any()) } coAnswers {
                    pendingTimestamp = firstArg<TradeRecord>().timestamp
                    70
                }
                coEvery { tradeHistoryService.hasPendingSubmissions() } returns false
                coEvery { orderIntentService.hasUnresolvedIntents() } returns false
                coEvery { orderIntentService.savePending(any()) } coAnswers {
                    events += "journal-pending"
                    700
                }
                coEvery { orderIntentService.recordOutcome(any(), any()) } coAnswers {
                    events += "journal-outcome"
                    true
                }
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(
                        success = true,
                        pair = pair,
                        side = side,
                        volume = volume,
                        orderTxid = "O-700",
                    )
                }
                krakenService.executeOrderAction = { _, _, _, _ -> events += "exchange" }

                journaledExecutor.executeOrders(
                    buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                    settings = TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                    cycleId = "journaled-success",
                )

                coVerify {
                    orderIntentService.savePending(
                        match {
                            it.cycleId == "journaled-success" &&
                                it.state == OrderIntentState.PENDING &&
                                it.side == "BUY" &&
                                it.localTradeId == 70 &&
                                it.createdAt == pendingTimestamp
                        },
                    )
                    orderIntentService.recordOutcome(
                        700,
                        match { it.success && it.orderTxid == "O-700" },
                    )
                }
                coVerify(exactly = 0) { tradeHistoryService.updateTrade(any(), any()) }
                events shouldBe listOf("journal-pending", "exchange", "journal-outcome")
            }
        }

        "durable outcome persistence preserves a backend exception without a second local write" {
            runTest {
                val orderIntentService = mockk<OrderIntentService>(relaxed = true)
                val journaledExecutor = OrderExecutorImpl(krakenService, tradeHistoryService, orderIntentService)
                val original = IOException("response lost after durable submission")
                coEvery { tradeHistoryService.saveTrade(any()) } returns 74
                coEvery { tradeHistoryService.hasPendingSubmissions() } returns false
                coEvery { orderIntentService.hasUnresolvedIntents() } returns false
                coEvery { orderIntentService.savePending(any()) } returns 704
                coEvery { orderIntentService.recordOutcome(any(), any()) } returns true
                krakenService.executeOrderAction = { _, _, _, _ -> throw original }

                shouldThrow<IOException> {
                    journaledExecutor.executeOrders(
                        buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                        sellOrders = emptyMap(),
                        currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                        prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                        settings = TestFixtures.settings(dryRun = false),
                        actionLog = mutableListOf(),
                        cycleId = "journaled-exception",
                    )
                } shouldBe original

                coVerify(exactly = 0) { tradeHistoryService.updateTrade(any(), any()) }
            }
        }

        "late terminal resolution aborts the rest of the live batch" {
            runTest {
                val orderIntentService = mockk<OrderIntentService>(relaxed = true)
                val journaledExecutor = OrderExecutorImpl(krakenService, tradeHistoryService, orderIntentService)
                val attemptedSymbols = mutableListOf<String>()
                coEvery { tradeHistoryService.saveTrade(any()) } returns 77
                coEvery { tradeHistoryService.hasPendingSubmissions() } returns false
                coEvery { orderIntentService.hasUnresolvedIntents() } returns false
                coEvery { orderIntentService.savePending(any()) } returns 707
                coEvery { orderIntentService.recordOutcome(any(), any()) } returns false
                krakenService.executeOrderAction = { pair, _, _, _ -> attemptedSymbols += pair }

                journaledExecutor.executeOrders(
                    buyOrders = mapOf(Asset.ETH to BigDecimal("25.00")),
                    sellOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices = mapOf(Asset.BTC to BigDecimal("1000.00"), Asset.ETH to BigDecimal("1000.00")),
                    settings = TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                    cycleId = "journaled-late-resolution",
                )

                attemptedSymbols shouldBe listOf(Asset.BTC_USD_PAIR)
            }
        }

        "durable cancellation preserves an operator resolution without a second local write" {
            runTest {
                val orderIntentService = mockk<OrderIntentService>(relaxed = true)
                val journaledExecutor = OrderExecutorImpl(krakenService, tradeHistoryService, orderIntentService)
                val original = CancellationException("placement cancelled after durable submission")
                coEvery { tradeHistoryService.saveTrade(any()) } returns 75
                coEvery { tradeHistoryService.hasPendingSubmissions() } returns false
                coEvery { orderIntentService.hasUnresolvedIntents() } returns false
                coEvery { orderIntentService.savePending(any()) } returns 705
                coEvery { orderIntentService.recordOutcome(any(), any()) } returns false
                krakenService.executeOrderAction = { _, _, _, _ -> throw original }

                shouldThrow<CancellationException> {
                    journaledExecutor.executeOrders(
                        buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                        sellOrders = emptyMap(),
                        currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                        prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                        settings = TestFixtures.settings(dryRun = false),
                        actionLog = mutableListOf(),
                        cycleId = "journaled-cancellation",
                    )
                } shouldBe original

                coVerify(exactly = 0) { tradeHistoryService.updateTrade(any(), any()) }
            }
        }

        "durable cancellation persists an uncertain outcome without a legacy fallback write" {
            runTest {
                val orderIntentService = mockk<OrderIntentService>(relaxed = true)
                val journaledExecutor = OrderExecutorImpl(krakenService, tradeHistoryService, orderIntentService)
                val original = CancellationException("placement cancelled after durable journal write")
                coEvery { tradeHistoryService.saveTrade(any()) } returns 76
                coEvery { tradeHistoryService.hasPendingSubmissions() } returns false
                coEvery { orderIntentService.hasUnresolvedIntents() } returns false
                coEvery { orderIntentService.savePending(any()) } returns 706
                coEvery { orderIntentService.recordOutcome(any(), any()) } returns true
                krakenService.executeOrderAction = { _, _, _, _ -> throw original }

                shouldThrow<CancellationException> {
                    journaledExecutor.executeOrders(
                        buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                        sellOrders = emptyMap(),
                        currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                        prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                        settings = TestFixtures.settings(dryRun = false),
                        actionLog = mutableListOf(),
                        cycleId = "journaled-cancellation-applied",
                    )
                } shouldBe original

                coVerify(exactly = 0) { tradeHistoryService.updateTrade(any(), any()) }
            }
        }

        "durable cancellation persistence failure keeps a legacy uncertain guard" {
            runTest {
                val orderIntentService = mockk<OrderIntentService>(relaxed = true)
                val journaledExecutor = OrderExecutorImpl(krakenService, tradeHistoryService, orderIntentService)
                val original = CancellationException("placement cancelled before durable outcome")
                val persistenceFailure = IllegalStateException("order intent outcome unavailable")
                coEvery { tradeHistoryService.saveTrade(any()) } returns 77
                coEvery { tradeHistoryService.hasPendingSubmissions() } returns false
                coEvery { orderIntentService.hasUnresolvedIntents() } returns false
                coEvery { orderIntentService.savePending(any()) } returns 707
                coEvery { orderIntentService.recordOutcome(any(), any()) } throws persistenceFailure
                krakenService.executeOrderAction = { _, _, _, _ -> throw original }

                val thrown = shouldThrow<CancellationException> {
                    journaledExecutor.executeOrders(
                        buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                        sellOrders = emptyMap(),
                        currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                        prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                        settings = TestFixtures.settings(dryRun = false),
                        actionLog = mutableListOf(),
                        cycleId = "journaled-cancellation-failed",
                    )
                }

                thrown shouldBe original
                thrown.suppressed.single() shouldBe persistenceFailure
                coVerify {
                    tradeHistoryService.updateTrade(
                        any(),
                        match { it.submissionState == OrderSubmissionState.UNCERTAIN },
                    )
                }
            }
        }

        "durable uncertain intent blocks a subsequent live batch" {
            runTest {
                val orderIntentService = mockk<OrderIntentService>(relaxed = true)
                val journaledExecutor = OrderExecutorImpl(krakenService, tradeHistoryService, orderIntentService)
                var unresolved = false
                coEvery { tradeHistoryService.saveTrade(any()) } returns 71
                coEvery { tradeHistoryService.hasPendingSubmissions() } returns false
                coEvery { orderIntentService.hasUnresolvedIntents() } coAnswers { unresolved }
                coEvery { orderIntentService.savePending(any()) } returns 701
                coEvery { orderIntentService.recordOutcome(any(), any()) } coAnswers {
                    unresolved = true
                    true
                }
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(success = true, pair = pair, side = side, volume = volume)
                }
                val settings = TestFixtures.settings(dryRun = false)
                val values = mapOf(Asset.USD to BigDecimal("100.00"))

                journaledExecutor.executeOrders(
                    buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = values,
                    prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                    settings = settings,
                    actionLog = mutableListOf(),
                    cycleId = "journaled-uncertain",
                )
                journaledExecutor.executeOrders(
                    buyOrders = mapOf(Asset.ETH to BigDecimal("25.00")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = values,
                    prices = mapOf(Asset.ETH to BigDecimal("1000.00")),
                    settings = settings,
                    actionLog = mutableListOf(),
                    cycleId = "journaled-blocked",
                )

                krakenService.executedOrders.size shouldBe 1
                coVerify {
                    orderIntentService.recordOutcome(
                        701,
                        match { it.submissionUncertain },
                    )
                }
            }
        }

        "live journaling refuses placement without a stable cycle id" {
            runTest {
                val orderIntentService = mockk<OrderIntentService>(relaxed = true)
                val journaledExecutor = OrderExecutorImpl(krakenService, tradeHistoryService, orderIntentService)
                coEvery { tradeHistoryService.saveTrade(any()) } returns 73
                coEvery { tradeHistoryService.hasPendingSubmissions() } returns false
                coEvery { orderIntentService.hasUnresolvedIntents() } returns false

                shouldThrow<IllegalStateException> {
                    journaledExecutor.executeOrders(
                        buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                        sellOrders = emptyMap(),
                        currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                        prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                        settings = TestFixtures.settings(dryRun = false),
                        actionLog = mutableListOf(),
                        cycleId = "",
                    )
                }

                krakenService.executedOrders.size shouldBe 0
                coVerify(exactly = 0) { orderIntentService.savePending(any()) }
            }
        }

        "order-intent persistence failure prevents the exchange call" {
            runTest {
                val orderIntentService = mockk<OrderIntentService>(relaxed = true)
                val journaledExecutor = OrderExecutorImpl(krakenService, tradeHistoryService, orderIntentService)
                val persistenceFailure = IllegalStateException("order intent journal unavailable")
                coEvery { tradeHistoryService.saveTrade(any()) } returns 72
                coEvery { tradeHistoryService.hasPendingSubmissions() } returns false
                coEvery { orderIntentService.hasUnresolvedIntents() } returns false
                coEvery { orderIntentService.savePending(any()) } throws persistenceFailure

                shouldThrow<IllegalStateException> {
                    journaledExecutor.executeOrders(
                        buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                        sellOrders = emptyMap(),
                        currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                        prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                        settings = TestFixtures.settings(dryRun = false),
                        actionLog = mutableListOf(),
                        cycleId = "journal-save-failure",
                    )
                }

                krakenService.executedOrders shouldBe emptyList()
                coVerify {
                    tradeHistoryService.updateTrade(
                        any(),
                        match { it.submissionState == OrderSubmissionState.UNCERTAIN },
                    )
                }
            }
        }

        "order-intent outcome persistence failure does not mask an exchange exception" {
            runTest {
                val orderIntentService = mockk<OrderIntentService>(relaxed = true)
                val journaledExecutor = OrderExecutorImpl(krakenService, tradeHistoryService, orderIntentService)
                val original = IOException("response lost after submission")
                val persistenceFailure = IllegalStateException("order intent outcome unavailable")
                coEvery { tradeHistoryService.saveTrade(any()) } returns 73
                coEvery { tradeHistoryService.hasPendingSubmissions() } returns false
                coEvery { orderIntentService.hasUnresolvedIntents() } returns false
                coEvery { orderIntentService.savePending(any()) } returns 703
                coEvery { orderIntentService.recordOutcome(any(), any()) } throws persistenceFailure
                krakenService.executeOrderAction = { _, _, _, _ -> throw original }

                val thrown = shouldThrow<IOException> {
                    journaledExecutor.executeOrders(
                        buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                        sellOrders = emptyMap(),
                        currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                        prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                        settings = TestFixtures.settings(dryRun = false),
                        actionLog = mutableListOf(),
                        cycleId = "journal-outcome-failure",
                    )
                }

                thrown shouldBe original
                thrown.suppressed.single() shouldBe persistenceFailure
                coVerify {
                    tradeHistoryService.updateTrade(
                        any(),
                        match { it.submissionState == OrderSubmissionState.UNCERTAIN },
                    )
                }
            }
        }

        "CQ-12-3: dry-run backend exception replaces pending text with the failure" {
            runTest {
                coEvery { tradeHistoryService.saveTrade(any()) } returns 61
                val original = IOException("dry-run backend unavailable")
                krakenService.executeOrderAction = { _, _, _, _ -> throw original }

                shouldThrow<IOException> {
                    orderExecutor.executeOrders(
                        buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                        sellOrders = emptyMap(),
                        currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                        prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                        settings =
                        TestFixtures.settings(),
                        actionLog = mutableListOf(),
                    )
                } shouldBe original

                coVerify {
                    tradeHistoryService.updateTrade(
                        any(),
                        match {
                            it.id == 61 && !it.success && it.dryRun &&
                                it.submissionState == null && it.errorMessage == original.message
                        },
                    )
                }
            }
        }

        "CQ-12-3: simulation backend exception persists a failed non-live estimate" {
            runTest {
                coEvery { tradeHistoryService.saveTrade(any()) } returns 62
                val original = IOException("emulator placement failed")
                krakenService.executeOrderAction = { _, _, _, _ -> throw original }

                shouldThrow<IOException> {
                    orderExecutor.executeOrders(
                        buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                        sellOrders = emptyMap(),
                        currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                        prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                        settings =
                        TestFixtures.settings(dryRun = false, simulation = true),
                        actionLog = mutableListOf(),
                    )
                } shouldBe original

                coVerify {
                    tradeHistoryService.updateTrade(
                        any(),
                        match {
                            it.id == 62 && !it.success && !it.dryRun &&
                                it.submissionState == null && it.errorMessage == original.message
                        },
                    )
                }
            }
        }

        "failed PENDING persistence prevents any live exchange call" {
            runTest {
                val persistenceFailure = IllegalStateException("trade journal unavailable")
                coEvery { tradeHistoryService.hasPendingSubmissions() } returns false
                coEvery { tradeHistoryService.saveTrade(any()) } throws persistenceFailure

                shouldThrow<IllegalStateException> {
                    orderExecutor.executeOrders(
                        buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                        sellOrders = emptyMap(),
                        currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                        prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                        settings = TestFixtures.settings(dryRun = false),
                        actionLog = mutableListOf(),
                        cycleId = "pending-save-failure",
                    )
                }

                krakenService.executedOrders shouldBe emptyList()
            }
        }

        "simulation records never create a live submission gate" {
            runTest {
                coEvery { tradeHistoryService.saveTrade(any()) } returns 7
                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.BTC to BigDecimal("50.00")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                    settings =
                    TestFixtures.settings(dryRun = false, simulation = true),
                    actionLog = mutableListOf(),
                    cycleId = "simulation-cycle",
                )

                coVerify(exactly = 0) { tradeHistoryService.hasPendingSubmissions() }
                coVerify { tradeHistoryService.saveTrade(match { it.submissionState == null }) }
            }
        }

        "executeOrders silently skips orders with zero ticker price" {
            runTest {
                val actionLog = mutableListOf<String>()
                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.BTC to BigDecimal("50.00")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices = mapOf(Asset.BTC to BigDecimal.ZERO), // Zero price
                    settings = TestFixtures.settings(),
                    actionLog = actionLog,
                )

                coVerify(exactly = 0) { tradeHistoryService.saveTrade(any()) }
            }
        }

        "executeOrders skips sell order when available holdings volume rounds down to zero" {
            runTest {
                val actionLog = mutableListOf<String>()
                orderExecutor.executeOrders(
                    buyOrders = emptyMap(),
                    sellOrders = mapOf(Asset.BTC to BigDecimal("10.00")),
                    currentValuesUSD = mapOf(Asset.BTC to BigDecimal("0.000000001")),
                    prices = mapOf(Asset.BTC to BigDecimal("60000.00")),
                    // 1e-9 rounds down to 0 at scale 8
                    availableBalances = mapOf(Asset.BTC to BigDecimal("0.000000001")),
                    settings = TestFixtures.settings(),
                    actionLog = actionLog,
                )

                coVerify(exactly = 0) { tradeHistoryService.saveTrade(any()) }
            }
        }
    }
}
