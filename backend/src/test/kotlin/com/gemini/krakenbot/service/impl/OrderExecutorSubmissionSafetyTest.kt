package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.OrderIntentService
import com.gemini.krakenbot.service.ReportingDispatcher
import com.gemini.krakenbot.service.TradeHistoryService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.math.BigDecimal

class OrderExecutorSubmissionSafetyTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "dry-run backend failure is recorded without using the live execution journal" {
            runTest {
                val kraken = FakeKrakenService()
                val history = mockk<TradeHistoryService>(relaxed = true)
                coEvery { history.saveTrade(any()) } returns 61
                val original = IOException("dry-run backend unavailable")
                kraken.executeOrderAction = { _, _, _, _ -> throw original }

                shouldThrow<IOException> {
                    OrderExecutorImpl(kraken, history).executeOrders(
                        buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                        sellOrders = emptyMap(),
                        currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                        prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                        settings = TestFixtures.settings(dryRun = true, simulation = false),
                        actionLog = mutableListOf(),
                    )
                } shouldBe original

                coVerify {
                    history.updateTrade(
                        any(),
                        match<TradeRecord> {
                            it.id == 61 && !it.success && it.dryRun &&
                                it.submissionState == null && it.errorMessage == original.message
                        },
                    )
                }
                coVerify(exactly = 0) { history.hasPendingSubmissions() }
            }
        }

        "simulation backend failure is recorded as a non-live estimate" {
            runTest {
                val kraken = FakeKrakenService()
                val history = mockk<TradeHistoryService>(relaxed = true)
                coEvery { history.saveTrade(any()) } returns 62
                val original = IOException("emulator placement failed")
                kraken.executeOrderAction = { _, _, _, _ -> throw original }

                shouldThrow<IOException> {
                    OrderExecutorImpl(kraken, history).executeOrders(
                        buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                        sellOrders = emptyMap(),
                        currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                        prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                        settings = TestFixtures.settings(dryRun = false, simulation = true),
                        actionLog = mutableListOf(),
                    )
                } shouldBe original

                coVerify {
                    history.updateTrade(
                        any(),
                        match<TradeRecord> {
                            it.id == 62 && !it.success && !it.dryRun &&
                                it.submissionState == null && it.errorMessage == original.message
                        },
                    )
                }
                coVerify(exactly = 0) { history.hasPendingSubmissions() }
            }
        }

        "live orders fail closed before history writes when no execution journal is wired" {
            runTest {
                val kraken = FakeKrakenService()
                val history = mockk<TradeHistoryService>(relaxed = true)

                shouldThrow<IllegalStateException> {
                    OrderExecutorImpl(kraken, history).executeOrders(
                        buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                        sellOrders = emptyMap(),
                        currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                        prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                        settings = TestFixtures.settings(dryRun = false, simulation = false),
                        actionLog = mutableListOf(),
                        cycleId = "missing-journal",
                    )
                }

                kraken.executedOrders.shouldBeEmpty()
                coVerify(exactly = 0) { history.saveTrade(any()) }
            }
        }

        "live placement is rejected before persistence when the cycle identity is blank" {
            runTest {
                val kraken = FakeKrakenService()
                val history = mockk<TradeHistoryService>(relaxed = true)
                val intents = mockk<OrderIntentService>(relaxed = true)

                shouldThrow<IllegalStateException> {
                    OrderExecutorImpl(kraken, history, intents).executeOrders(
                        buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                        sellOrders = emptyMap(),
                        currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                        prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                        settings = TestFixtures.settings(dryRun = false, simulation = false),
                        actionLog = mutableListOf(),
                        cycleId = "  ",
                    )
                }

                kraken.executedOrders.shouldBeEmpty()
                coVerify(exactly = 0) { intents.savePending(any()) }
                coVerify(exactly = 0) { history.saveTrade(any()) }
            }
        }

        "executeOrders skips orders with zero ticker price" {
            runTest {
                val history = mockk<TradeHistoryService>(relaxed = true)
                OrderExecutorImpl(FakeKrakenService(), history).executeOrders(
                    buyOrders = mapOf(Asset.BTC to BigDecimal("50.00")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices = mapOf(Asset.BTC to BigDecimal.ZERO),
                    settings = TestFixtures.settings(),
                    actionLog = mutableListOf(),
                )

                coVerify(exactly = 0) { history.saveTrade(any()) }
            }
        }

        "executeOrders skips a sell whose available volume rounds down to zero" {
            runTest {
                val history = mockk<TradeHistoryService>(relaxed = true)
                OrderExecutorImpl(FakeKrakenService(), history).executeOrders(
                    buyOrders = emptyMap(),
                    sellOrders = mapOf(Asset.BTC to BigDecimal("10.00")),
                    currentValuesUSD = mapOf(Asset.BTC to BigDecimal("0.000000001")),
                    prices = mapOf(Asset.BTC to BigDecimal("60000.00")),
                    availableBalances = mapOf(Asset.BTC to BigDecimal("0.000000001")),
                    settings = TestFixtures.settings(),
                    actionLog = mutableListOf(),
                )

                coVerify(exactly = 0) { history.saveTrade(any()) }
            }
        }

        "a definite live rejection is journaled and does not abort later buys" {
            runTest {
                val kraken = FakeKrakenService()
                val intents = mockk<OrderIntentService>(relaxed = true)
                coEvery { intents.savePending(any()) } returnsMany listOf(1, 2)
                coEvery { intents.recordOutcome(any(), any()) } returns true
                kraken.orderResultFactory = { pair, _, side, volume ->
                    if (kraken.executedOrders.size == 1) {
                        OrderResult.Failure(pair, side, volume, errorMessage = "insufficient funds")
                    } else {
                        OrderResult.Success(pair, side, volume, orderTxid = "OID-2")
                    }
                }

                liveExecutor(kraken, intents).executeOrders(
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
                    settings = TestFixtures.settings(dryRun = false, simulation = false),
                    actionLog = mutableListOf(),
                    cycleId = "definite-rejection",
                )

                kraken.executedOrders.size shouldBe 2
                coVerify {
                    intents.recordOutcome(1, match { !it.success && !it.submissionUncertain })
                    intents.recordOutcome(2, match { it.success && it.orderTxid == "OID-2" })
                }
            }
        }

        "an uncertain live response blocks the rest of the batch and the next cycle" {
            runTest {
                val kraken = FakeKrakenService().apply {
                    orderResultFactory = { pair, _, side, volume ->
                        OrderResult.Failure(
                            pair,
                            side,
                            volume,
                            errorMessage = "response lost",
                            submissionUncertain = true,
                        )
                    }
                }
                val intents = mockk<OrderIntentService>(relaxed = true)
                coEvery { intents.savePending(any()) } returns 1
                coEvery { intents.hasUnresolvedIntents() } returnsMany listOf(false, true)
                coEvery { intents.recordOutcome(any(), any()) } returns true
                val executor = liveExecutor(kraken, intents)

                repeat(2) {
                    executor.executeOrders(
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
                        settings = TestFixtures.settings(dryRun = false, simulation = false),
                        actionLog = mutableListOf(),
                        cycleId = "uncertain-$it",
                    )
                }

                kraken.executedOrders.size shouldBe 1
                coVerify(exactly = 1) { intents.savePending(any()) }
                coVerify(exactly = 1) {
                    intents.recordOutcome(1, match { !it.success && it.submissionUncertain })
                }
            }
        }

        "a prior terminal resolution aborts the remaining batch and a live cycle needs an id" {
            runTest {
                val kraken = FakeKrakenService()
                val intents = mockk<OrderIntentService>(relaxed = true)
                coEvery { intents.savePending(any()) } returns 9
                coEvery { intents.hasUnresolvedIntents() } returns false
                coEvery { intents.recordOutcome(any(), any()) } returns false
                val actionLog = mutableListOf<String>()

                liveExecutor(kraken, intents).executeOrders(
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
                    settings = TestFixtures.settings(dryRun = false, simulation = false),
                    actionLog = actionLog,
                    cycleId = "already-resolved",
                )

                kraken.executedOrders.size shouldBe 1
                actionLog.any { it.contains("already resolved") } shouldBe true
                coVerify(exactly = 1) { intents.recordOutcome(9, any()) }

                val blankIdKraken = FakeKrakenService()
                val blankIdIntents = mockk<OrderIntentService>(relaxed = true)
                coEvery { blankIdIntents.hasUnresolvedIntents() } returns false
                shouldThrow<IllegalStateException> {
                    liveExecutor(blankIdKraken, blankIdIntents).executeOrders(
                        buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                        sellOrders = emptyMap(),
                        currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                        prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                        settings = TestFixtures.settings(dryRun = false, simulation = false),
                        actionLog = mutableListOf(),
                        cycleId = "",
                    )
                }
                blankIdKraken.executedOrders.shouldBeEmpty()
                coVerify(exactly = 0) { blankIdIntents.savePending(any()) }
            }
        }

        "live exception without a message records the fail-closed fallback description" {
            runTest {
                val kraken = FakeKrakenService().apply {
                    executeOrderAction = { _, _, _, _ -> throw IOException() }
                }
                val intents = mockk<OrderIntentService>(relaxed = true)
                coEvery { intents.savePending(any()) } returns 3
                coEvery { intents.recordOutcome(any(), any()) } returns true

                shouldThrow<IOException> { executeLiveBuy(liveExecutor(kraken, intents)) }

                coVerify {
                    intents.recordOutcome(
                        3,
                        match { !it.success && it.submissionUncertain && !it.errorMessage.isNullOrBlank() },
                    )
                }
            }
        }

        "submission and cancellation persistence failures preserve the exchange failure" {
            runTest {
                val kraken = FakeKrakenService()
                val intents = mockk<OrderIntentService>(relaxed = true)
                coEvery { intents.savePending(any()) } returns 1
                val original = IOException("Kraken response was lost")
                val journalFailure = IOException("execution journal locked")
                kraken.executeOrderAction = { _, _, _, _ -> throw original }
                coEvery { intents.recordOutcome(any(), any()) } throws journalFailure

                shouldThrow<IOException> { executeLiveBuy(liveExecutor(kraken, intents)) } shouldBe original
                original.suppressed.toList() shouldBe listOf(journalFailure)

                val cancellation = CancellationException("cycle stopped")
                val cancellationKraken = FakeKrakenService().apply {
                    executeOrderAction = { _, _, _, _ -> throw cancellation }
                }
                val cancellationIntents = mockk<OrderIntentService>(relaxed = true)
                coEvery { cancellationIntents.savePending(any()) } returns 2
                coEvery { cancellationIntents.recordOutcome(any(), any()) } returns true

                shouldThrow<CancellationException> {
                    executeLiveBuy(liveExecutor(cancellationKraken, cancellationIntents))
                } shouldBe cancellation
                coVerify {
                    cancellationIntents.recordOutcome(2, match { !it.success && it.submissionUncertain })
                }
            }
        }

        "non-live reporting update failure is suppressed onto the original placement error" {
            runTest {
                val kraken = FakeKrakenService()
                val history = mockk<TradeHistoryService>(relaxed = true)
                coEvery { history.saveTrade(any()) } returns 71
                val original = IOException("emulator error")
                val reportFailure = IOException("reporting store unavailable")
                kraken.executeOrderAction = { _, _, _, _ -> throw original }
                coEvery { history.updateTrade(any(), any()) } throws reportFailure

                shouldThrow<IOException> {
                    OrderExecutorImpl(kraken, history).executeOrders(
                        buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                        sellOrders = emptyMap(),
                        currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                        prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                        settings = TestFixtures.settings(dryRun = false, simulation = true),
                        actionLog = mutableListOf(),
                    )
                } shouldBe original

                original.suppressed.toList() shouldBe listOf(reportFailure)
                coVerify(exactly = 0) { history.hasPendingSubmissions() }
            }
        }

        "simulation reporting update failure is suppressed when history is dispatcher owned" {
            runTest {
                val kraken = FakeKrakenService()
                val history = mockk<TradeHistoryService>(relaxed = true)
                coEvery { history.saveTrade(any()) } returns 72
                val original = IOException("emulator placement failed")
                val reportFailure = IOException("simulation trade update failed")
                kraken.executeOrderAction = { _, _, _, _ -> throw original }
                coEvery { history.updateTrade(any(), any()) } throws reportFailure
                val dispatcher = ReportingDispatcher(
                    historyServiceProvider = { history },
                    projectionServiceProvider = { error("projection is not used by simulation orders") },
                )
                dispatcher.initializeBeforeSimulationCycle()

                shouldThrow<IOException> {
                    OrderExecutorImpl(
                        kraken,
                        tradeHistoryService = null,
                        reportingDispatcher = dispatcher,
                    ).executeOrders(
                        buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                        sellOrders = emptyMap(),
                        currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                        prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                        settings = TestFixtures.settings(dryRun = false, simulation = true),
                        actionLog = mutableListOf(),
                    )
                } shouldBe original

                original.suppressed.toList() shouldBe listOf(reportFailure)
                coVerify(exactly = 1) {
                    history.updateTrade(
                        any(),
                        match {
                            !it.success &&
                                it.errorMessage == original.message
                        },
                    )
                }
            }
        }

        "dry-run trade projection is queued without making order execution wait for reporting" {
            runBlocking {
                val history = mockk<TradeHistoryService>(relaxed = true)
                val projectedFailure = CompletableDeferred<TradeRecord>()
                coEvery { history.saveTrade(any()) } coAnswers {
                    projectedFailure.complete(firstArg())
                    1
                }
                val dispatcher = ReportingDispatcher(
                    historyServiceProvider = { history },
                    projectionServiceProvider = { error("execution journal projection is not used in this dry-run") },
                )
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val worker = dispatcher.start(scope)
                val original = IOException("dry-run backend failed")
                val kraken = FakeKrakenService().apply {
                    executeOrderAction = { _, _, _, _ -> throw original }
                }
                try {
                    shouldThrow<IOException> {
                        OrderExecutorImpl(
                            kraken,
                            tradeHistoryService = null,
                            reportingDispatcher = dispatcher,
                        ).executeOrders(
                            buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                            sellOrders = emptyMap(),
                            currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                            prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                            settings = TestFixtures.settings(dryRun = true, simulation = false),
                            actionLog = mutableListOf(),
                        )
                    } shouldBe original
                    val projected = withTimeout(5_000) { projectedFailure.await() }
                    projected.success shouldBe false
                    projected.dryRun shouldBe true
                    projected.errorMessage shouldBe original.message
                } finally {
                    worker.cancelAndJoin()
                    scope.cancel()
                }
            }
        }
    }

    private fun liveExecutor(kraken: FakeKrakenService, intents: OrderIntentService) =
        OrderExecutorImpl(kraken, tradeHistoryService = null, orderIntentService = intents)

    private suspend fun executeLiveBuy(executor: OrderExecutorImpl) = executor.executeOrders(
        buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
        sellOrders = emptyMap(),
        currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
        prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
        settings = TestFixtures.settings(dryRun = false, simulation = false),
        actionLog = mutableListOf(),
        cycleId = "live-safety-test",
    )
}
