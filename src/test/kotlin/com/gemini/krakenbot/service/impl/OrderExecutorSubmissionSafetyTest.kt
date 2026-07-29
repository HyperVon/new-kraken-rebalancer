package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.OrderSubmissionState
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.TradeHistoryService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
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
