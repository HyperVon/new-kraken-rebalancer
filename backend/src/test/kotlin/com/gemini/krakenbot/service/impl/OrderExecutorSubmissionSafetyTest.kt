package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.OrderIntentService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.math.BigDecimal

@Suppress("unused")
class OrderExecutorSubmissionSafetyTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "dry-run backend failure does not require the live execution journal" {
            runTest {
                val kraken = FakeKrakenService()
                val original = IOException("dry-run backend unavailable")
                kraken.executeOrderAction = { _, _, _, _ -> throw original }

                shouldThrow<IOException> {
                    OrderExecutorImpl(kraken).executeOrders(
                        buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                        sellOrders = emptyMap(),
                        currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                        prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                        settings = TestFixtures.settings(dryRun = true, simulation = false),
                        actionLog = mutableListOf(),
                    )
                } shouldBe original

                kraken.executedOrders.single().dryRun shouldBe true
            }
        }

        "simulation backend failure does not require the live execution journal" {
            runTest {
                val kraken = FakeKrakenService()
                val original = IOException("emulator placement failed")
                kraken.executeOrderAction = { _, _, _, _ -> throw original }

                shouldThrow<IOException> {
                    OrderExecutorImpl(kraken).executeOrders(
                        buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                        sellOrders = emptyMap(),
                        currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                        prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                        settings = TestFixtures.settings(dryRun = false, simulation = true),
                        actionLog = mutableListOf(),
                    )
                } shouldBe original

                kraken.executedOrders.single().dryRun shouldBe false
            }
        }

        "live orders fail closed when no execution journal is wired" {
            runTest {
                val kraken = FakeKrakenService()

                shouldThrow<IllegalStateException> {
                    OrderExecutorImpl(kraken).executeOrders(
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
            }
        }

        "live placement is rejected before persistence when the cycle identity is blank" {
            runTest {
                val kraken = FakeKrakenService()
                val intents = mockk<OrderIntentService>(relaxed = true)

                shouldThrow<IllegalStateException> {
                    OrderExecutorImpl(kraken, intents).executeOrders(
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
            }
        }

        "executeOrders skips orders with zero ticker price" {
            runTest {
                OrderExecutorImpl(FakeKrakenService()).executeOrders(
                    buyOrders = mapOf(Asset.BTC to BigDecimal("50.00")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices = mapOf(Asset.BTC to BigDecimal.ZERO),
                    settings = TestFixtures.settings(),
                    actionLog = mutableListOf(),
                )
            }
        }

        "executeOrders skips a sell whose available volume rounds down to zero" {
            runTest {
                OrderExecutorImpl(FakeKrakenService()).executeOrders(
                    buyOrders = emptyMap(),
                    sellOrders = mapOf(Asset.BTC to BigDecimal("10.00")),
                    currentValuesUSD = mapOf(Asset.BTC to BigDecimal("0.000000001")),
                    prices = mapOf(Asset.BTC to BigDecimal("60000.00")),
                    availableBalances = mapOf(Asset.BTC to BigDecimal("0.000000001")),
                    settings = TestFixtures.settings(),
                    actionLog = mutableListOf(),
                )
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
    }

    private fun liveExecutor(kraken: FakeKrakenService, intents: OrderIntentService) =
        OrderExecutorImpl(kraken, orderIntentService = intents)

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
