package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.OrderSubmissionState
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.service.TradeHistoryServiceTestAdapter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * End-to-end coverage of the SQLite-backed durable live-order submission journal.
 *
 * Unlike [OrderExecutorSubmissionSafetyTest], which mocks [TradeHistoryService] and only verifies
 * the calls the executor makes, this drives [OrderExecutorImpl] against a real in-memory SQLite
 * [SqliteTradeRepositoryImpl] (via [TradeHistoryServiceTestAdapter]) and asserts the persisted row
 * transitions directly. Closes CQ-14-M1: cover the `PENDING` -> resolved/`UNCERTAIN` journal
 * lifecycle end to end through the production wiring.
 */
class OrderSubmissionJournalE2ETest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "live success with order txid transitions PENDING row to a resolved state and clears the gate" {
            runTest {
                val db = DatabaseConfig.init(TestFixtures.MEMORY_)
                val repository = SqliteTradeRepositoryImpl(db)
                val krakenService = FakeKrakenService().apply {
                    orderResultFactory = { pair, _, side, volume ->
                        OrderResult(
                            success = true,
                            pair = pair,
                            side = side,
                            volume = volume,
                            orderTxid = "OK-$side",
                        )
                    }
                }
                val tradeHistoryService = TradeHistoryServiceTestAdapter(repository)
                val orderExecutor = OrderExecutorImpl(krakenService, tradeHistoryService)
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)

                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                    settings = TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                    cycleId = "success-cycle",
                )

                repository.hasPendingSubmissions() shouldBe false
                val persisted = repository.getTradesInRange(now.minusSeconds(5), now.plusSeconds(5))
                persisted shouldHaveSize 1
                val row = persisted.single()
                row.success shouldBe true
                row.dryRun shouldBe false
                row.submissionState shouldBe null
                row.orderTxid shouldBe "OK-buy"
                row.cycleId shouldBe "success-cycle"
                row.clientOrderId shouldNotBe null
            }
        }

        "live success without an order txid transitions PENDING row to blocking UNCERTAIN in the database" {
            runTest {
                val db = DatabaseConfig.init(TestFixtures.MEMORY_)
                val repository = SqliteTradeRepositoryImpl(db)
                val krakenService = FakeKrakenService().apply {
                    orderResultFactory = { pair, _, side, volume ->
                        OrderResult(
                            success = true,
                            pair = pair,
                            side = side,
                            volume = volume,
                            // No orderTxid: ambiguous live success must remain blocked.
                        )
                    }
                }
                val tradeHistoryService = TradeHistoryServiceTestAdapter(repository)
                val orderExecutor = OrderExecutorImpl(krakenService, tradeHistoryService)
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)

                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                    settings = TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                    cycleId = "uncertain-cycle",
                )

                repository.hasPendingSubmissions() shouldBe true
                val persisted = repository.getTradesInRange(now.minusSeconds(5), now.plusSeconds(5))
                persisted shouldHaveSize 1
                val row = persisted.single()
                row.success shouldBe false
                row.dryRun shouldBe false
                row.submissionState shouldBe OrderSubmissionState.UNCERTAIN
                row.errorMessage shouldBe "Order submission outcome is uncertain"
                row.cycleId shouldBe "uncertain-cycle"
                row.clientOrderId shouldNotBe null
            }
        }

        "live ambiguous failure transitions PENDING row to UNCERTAIN and blocks a subsequent cycle" {
            runTest {
                val db = DatabaseConfig.init(TestFixtures.MEMORY_)
                val repository = SqliteTradeRepositoryImpl(db)
                val krakenService = FakeKrakenService().apply {
                    orderResultFactory = { pair, _, side, volume ->
                        OrderResult(
                            success = false,
                            pair = pair,
                            side = side,
                            volume = volume,
                            errorMessage = "response lost",
                            submissionUncertain = true,
                        )
                    }
                }
                val tradeHistoryService = TradeHistoryServiceTestAdapter(repository)
                val orderExecutor = OrderExecutorImpl(krakenService, tradeHistoryService)
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)

                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                    settings = TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                    cycleId = "ambiguous-failure-cycle",
                )

                repository.hasPendingSubmissions() shouldBe true
                val row = repository.getTradesInRange(now.minusSeconds(5), now.plusSeconds(5)).single()
                row.success shouldBe false
                row.submissionState shouldBe OrderSubmissionState.UNCERTAIN
                row.errorMessage shouldBe "response lost"

                // A subsequent live cycle must be refused while the UNCERTAIN row is still on disk.
                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.ETH to BigDecimal("25.00")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices = mapOf(Asset.ETH to BigDecimal("1000.00")),
                    settings = TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                    cycleId = "blocked-cycle",
                )
                krakenService.executedOrders shouldHaveSize 1
                repository.getTradesInRange(now.minusSeconds(5), now.plusSeconds(5)) shouldHaveSize 1
            }
        }

        "live IOException during AddOrder transitions PENDING row to UNCERTAIN and rethrows" {
            runTest {
                val db = DatabaseConfig.init(TestFixtures.MEMORY_)
                val repository = SqliteTradeRepositoryImpl(db)
                val krakenService = FakeKrakenService().apply {
                    executeOrderAction = { _, _, _, _ -> throw IOException("connection reset after submission") }
                }
                val tradeHistoryService = TradeHistoryServiceTestAdapter(repository)
                val orderExecutor = OrderExecutorImpl(krakenService, tradeHistoryService)
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)

                val thrown = shouldThrow<IOException> {
                    orderExecutor.executeOrders(
                        buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                        sellOrders = emptyMap(),
                        currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                        prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                        settings = TestFixtures.settings(dryRun = false),
                        actionLog = mutableListOf(),
                        cycleId = "io-cycle",
                    )
                }
                thrown.message shouldBe "connection reset after submission"

                repository.hasPendingSubmissions() shouldBe true
                val row = repository.getTradesInRange(now.minusSeconds(5), now.plusSeconds(5)).single()
                row.success shouldBe false
                row.submissionState shouldBe OrderSubmissionState.UNCERTAIN
                row.errorMessage shouldBe "connection reset after submission"
            }
        }

        "dry-run and simulation placement never enters the live submission journal" {
            runTest {
                val db = DatabaseConfig.init(TestFixtures.MEMORY_)
                val repository = SqliteTradeRepositoryImpl(db)
                val tradeHistoryService = TradeHistoryServiceTestAdapter(repository)
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)

                // Dry-run: success=false / submissionState=null persisted, no live gate.
                val dryRunKraken = FakeKrakenService()
                OrderExecutorImpl(dryRunKraken, tradeHistoryService).executeOrders(
                    buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                    settings = TestFixtures.settings(dryRun = true),
                    actionLog = mutableListOf(),
                    cycleId = "dry-run-cycle",
                )
                repository.hasPendingSubmissions() shouldBe false
                val dryRow = repository.getTradesInRange(now.minusSeconds(5), now.plusSeconds(5)).single()
                dryRow.dryRun shouldBe true
                dryRow.submissionState shouldBe null

                // Simulation (non-dryRun): success persisted, no live gate either.
                val simKraken = FakeKrakenService().apply {
                    orderResultFactory = { pair, _, side, volume ->
                        OrderResult(
                            success = true,
                            pair = pair,
                            side = side,
                            volume = volume,
                            dryRun = false,
                            orderTxid = "SIM-$side",
                        )
                    }
                }
                OrderExecutorImpl(simKraken, tradeHistoryService).executeOrders(
                    buyOrders = mapOf(Asset.ETH to BigDecimal("25.00")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices = mapOf(Asset.ETH to BigDecimal("1000.00")),
                    settings = TestFixtures.settings(dryRun = false, simulation = true),
                    actionLog = mutableListOf(),
                    cycleId = "simulation-cycle",
                )
                repository.hasPendingSubmissions() shouldBe false
                val allRows = repository.getTradesInRange(now.minusSeconds(5), now.plusSeconds(5))
                allRows shouldHaveSize 2
                allRows.all { it.submissionState == null } shouldBe true
            }
        }

        "resolved live rows are eligible for retention pruning but UNCERTAIN rows survive" {
            runTest {
                val db = DatabaseConfig.init(TestFixtures.MEMORY_)
                val repository = SqliteTradeRepositoryImpl(db)
                val krakenService = FakeKrakenService().apply {
                    orderResultFactory = { pair, _, side, volume ->
                        OrderResult(
                            success = true,
                            pair = pair,
                            side = side,
                            volume = volume,
                            orderTxid = "OK-$side",
                        )
                    }
                }
                val tradeHistoryService = TradeHistoryServiceTestAdapter(repository)
                val orderExecutor = OrderExecutorImpl(krakenService, tradeHistoryService)
                val wayBack = Instant.now().minus(200, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS)

                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                    settings = TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                    cycleId = "stale-resolved-cycle",
                )

                // Mark the persisted row as way-old to exercise retention pruning directly.
                val resolved = repository.getTradesInRange(Instant.now().minusSeconds(5), Instant.now().plusSeconds(5))
                    .single()
                repository.updateTrade(
                    resolved,
                    resolved.copy(timestamp = wayBack),
                )

                val uncertainKraken = FakeKrakenService().apply {
                    orderResultFactory = { pair, _, side, volume ->
                        OrderResult(
                            success = true,
                            pair = pair,
                            side = side,
                            volume = volume,
                        )
                    }
                }
                OrderExecutorImpl(uncertainKraken, TradeHistoryServiceTestAdapter(repository)).executeOrders(
                    buyOrders = mapOf(Asset.ETH to BigDecimal("25.00")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices = mapOf(Asset.ETH to BigDecimal("1000.00")),
                    settings = TestFixtures.settings(dryRun = false),
                    actionLog = mutableListOf(),
                    cycleId = "uncertain-cycle",
                )
                val uncertainRow = repository.getTradesInRange(
                    Instant.now().minusSeconds(5),
                    Instant.now().plusSeconds(5),
                )
                    .single()
                repository.updateTrade(
                    uncertainRow,
                    uncertainRow.copy(timestamp = wayBack, submissionState = OrderSubmissionState.UNCERTAIN),
                )

                repository.hasPendingSubmissions() shouldBe true
                val pruned = repository.pruneTradesOlderThan(Instant.now().minus(90, ChronoUnit.DAYS))
                pruned shouldBe 1
                repository.hasPendingSubmissions() shouldBe true
                val remaining = repository.getTradesInRange(wayBack.minusSeconds(5), wayBack.plusSeconds(5))
                remaining shouldHaveSize 1
                remaining.single().submissionState shouldBe OrderSubmissionState.UNCERTAIN
            }
        }
    }
}
