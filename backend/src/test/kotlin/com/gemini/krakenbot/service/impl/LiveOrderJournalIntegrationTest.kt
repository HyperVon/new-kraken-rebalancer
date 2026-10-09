package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.config.ExecutionDatabase
import com.gemini.krakenbot.config.ExecutionJournalBootstrap
import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.repository.impl.SqliteExecutionOrderIntentRepositoryImpl
import com.gemini.krakenbot.service.ExecutionAccountBindingVerifier
import com.gemini.krakenbot.service.FakeKrakenService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException

class LiveOrderJournalIntegrationTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "live fake exchange observes committed pending intents and durable outcomes" {
            val directory = Files.createTempDirectory("live-order-journal-success-")
            try {
                val journal = openJournal(directory)
                val fakeExchange = FakeKrakenService()
                val observedPendingPairs = mutableListOf<String>()
                fakeExchange.executeOrderAction = { pair, _, _, _ ->
                    readIntentState(journal.executionPath, pair) shouldBe OrderIntentState.PENDING.name
                    observedPendingPairs += pair
                }
                fakeExchange.orderResultFactory = { pair, _, side, volume ->
                    if (pair == Asset.BTC_USD_PAIR) {
                        OrderResult(
                            success = false,
                            pair = pair,
                            side = side,
                            volume = volume,
                            errorMessage = "definite rejection",
                        )
                    } else {
                        OrderResult(
                            success = true,
                            pair = pair,
                            side = side,
                            volume = volume,
                            orderTxid = "FAKE-ETH-1",
                        )
                    }
                }

                executeLiveOrders(
                    fakeExchange = fakeExchange,
                    intentService = journal.intentService,
                    buys = mapOf(
                        Asset.BTC to BigDecimal("25.00"),
                        Asset.ETH to BigDecimal("25.00"),
                    ),
                    cycleId = "live-success-cycle",
                )

                observedPendingPairs shouldBe listOf(Asset.BTC_USD_PAIR, Asset.ETH_USD_PAIR)
                fakeExchange.executedOrders.map { it.dryRun }.shouldBe(listOf(false, false))
                readIntent(journal.executionPath, Asset.BTC_USD_PAIR).state shouldBe OrderIntentState.REJECTED.name
                readIntent(journal.executionPath, Asset.BTC_USD_PAIR).errorMessage shouldBe "definite rejection"
                val confirmed = readIntent(journal.executionPath, Asset.ETH_USD_PAIR)
                confirmed.state shouldBe OrderIntentState.CONFIRMED.name
                confirmed.orderTxid shouldBe "FAKE-ETH-1"
                confirmed.resolvedAt shouldBe true
                confirmed.outcomeVolume shouldBe "0.02500000"
                projectionEventCount(journal.executionPath) shouldBe 2
                unresolvedCount(journal.executionPath) shouldBe 0
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

        "ambiguous live result stays uncertain and blocks the next submission" {
            val directory = Files.createTempDirectory("live-order-journal-uncertain-")
            try {
                val journal = openJournal(directory)
                val fakeExchange = FakeKrakenService().apply {
                    orderResultFactory = { pair, _, side, volume ->
                        OrderResult(
                            success = false,
                            pair = pair,
                            side = side,
                            volume = volume,
                            errorMessage = "response lost after submission",
                            submissionUncertain = true,
                        )
                    }
                }
                val executor = OrderExecutorImpl(fakeExchange, tradeHistoryService = null, journal.intentService)

                executor.executeOrders(
                    buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                    settings = liveSettings(),
                    actionLog = mutableListOf(),
                    cycleId = "live-uncertain-cycle",
                )
                executor.executeOrders(
                    buyOrders = mapOf(Asset.ETH to BigDecimal("25.00")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices = mapOf(Asset.ETH to BigDecimal("1000.00")),
                    settings = liveSettings(),
                    actionLog = mutableListOf(),
                    cycleId = "live-after-uncertain-cycle",
                )

                fakeExchange.executedOrders.size shouldBe 1
                val unresolved = readIntent(journal.executionPath, Asset.BTC_USD_PAIR)
                unresolved.state shouldBe OrderIntentState.UNCERTAIN.name
                unresolved.errorMessage shouldBe "response lost after submission"
                unresolved.resolvedAt shouldBe false
                unresolvedCount(journal.executionPath) shouldBe 1
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

        "ambiguous live sell prevents plan buys and later attempts until reconciliation" {
            val directory = Files.createTempDirectory("live-order-journal-uncertain-sell-")
            try {
                val journal = openJournal(directory)
                val fakeExchange = FakeKrakenService().apply {
                    executeOrderAction = { pair, _, _, _ ->
                        readIntentState(journal.executionPath, pair) shouldBe OrderIntentState.PENDING.name
                    }
                    orderResultFactory = { pair, _, side, volume ->
                        if (pair == Asset.BTC_USD_PAIR) {
                            OrderResult(
                                success = false,
                                pair = pair,
                                side = side,
                                volume = volume,
                                dryRun = false,
                                errorMessage = "sell response lost after submission",
                                submissionUncertain = true,
                            )
                        } else {
                            OrderResult(
                                success = true,
                                pair = pair,
                                side = side,
                                volume = volume,
                                dryRun = false,
                                orderTxid = "FAKE-ETH-AFTER-RECONCILIATION",
                            )
                        }
                    }
                }
                val executor = OrderExecutorImpl(fakeExchange, tradeHistoryService = null, journal.intentService)

                executor.executeOrders(
                    buyOrders = mapOf(Asset.ETH to BigDecimal("25.00")),
                    sellOrders = mapOf(Asset.BTC to BigDecimal("50.00")),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices = mapOf(
                        Asset.BTC to BigDecimal("2000.00"),
                        Asset.ETH to BigDecimal("1000.00"),
                    ),
                    settings = liveSettings(),
                    actionLog = mutableListOf(),
                    cycleId = "live-uncertain-sell-cycle",
                )
                executor.executeOrders(
                    buyOrders = mapOf(Asset.ETH to BigDecimal("25.00")),
                    sellOrders = emptyMap(),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices = mapOf(Asset.ETH to BigDecimal("1000.00")),
                    settings = liveSettings(),
                    actionLog = mutableListOf(),
                    cycleId = "live-buy-after-uncertain-sell-cycle",
                )

                fakeExchange.executedOrders.map { it.pair } shouldBe listOf(Asset.BTC_USD_PAIR)
                fakeExchange.executedOrders.map { it.side } shouldBe listOf("sell")
                intentCount(journal.executionPath) shouldBe 1
                val unresolvedSell = readIntent(journal.executionPath, Asset.BTC_USD_PAIR)
                unresolvedSell.state shouldBe OrderIntentState.UNCERTAIN.name
                unresolvedSell.errorMessage shouldBe "sell response lost after submission"
                unresolvedSell.resolvedAt shouldBe false
                unresolvedCount(journal.executionPath) shouldBe 1

                journal.intentService.resolve(
                    id = readIntentId(journal.executionPath, Asset.BTC_USD_PAIR),
                    state = OrderIntentState.CONFIRMED,
                    evidence = "Kraken trade history confirmed the sell",
                    orderTxid = "RECONCILED-BTC-SELL",
                )
                unresolvedCount(journal.executionPath) shouldBe 0
                executeLiveOrders(
                    fakeExchange = fakeExchange,
                    intentService = journal.intentService,
                    buys = mapOf(Asset.ETH to BigDecimal("25.00")),
                    cycleId = "live-buy-after-sell-reconciliation",
                )

                fakeExchange.executedOrders.map { it.pair } shouldBe listOf(
                    Asset.BTC_USD_PAIR,
                    Asset.ETH_USD_PAIR,
                )
                fakeExchange.executedOrders.count { it.pair == Asset.BTC_USD_PAIR } shouldBe 1
                unresolvedCount(journal.executionPath) shouldBe 0
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

        "concurrent terminal outcome survives an exchange exception without reopening the intent" {
            val directory = Files.createTempDirectory("live-order-journal-terminal-race-")
            try {
                val journal = openJournal(directory)
                val resolver = OrderIntentServiceImpl(SqliteExecutionOrderIntentRepositoryImpl(journal.database))
                val fakeExchange = FakeKrakenService().apply {
                    executeOrderAction = { pair, _, side, volume ->
                        if (pair == Asset.BTC_USD_PAIR) {
                            val intentId = readIntentId(journal.executionPath, pair)
                            runBlocking {
                                resolver.recordOutcome(
                                    intentId,
                                    OrderResult(
                                        success = true,
                                        pair = pair,
                                        side = side,
                                        volume = volume,
                                        dryRun = false,
                                        orderTxid = "RECONCILED-BTC-1",
                                    ),
                                ) shouldBe true
                            }
                            throw IOException("AddOrder response timed out after reconciliation confirmed it")
                        }
                    }
                }
                val executor = OrderExecutorImpl(fakeExchange, tradeHistoryService = null, journal.intentService)

                shouldThrow<IOException> {
                    runTest {
                        executor.executeOrders(
                            buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
                            sellOrders = emptyMap(),
                            currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                            prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
                            settings = liveSettings(),
                            actionLog = mutableListOf(),
                            cycleId = "live-terminal-race-cycle",
                        )
                    }
                }

                val reconciled = readIntent(journal.executionPath, Asset.BTC_USD_PAIR)
                reconciled.state shouldBe OrderIntentState.CONFIRMED.name
                reconciled.orderTxid shouldBe "RECONCILED-BTC-1"
                reconciled.errorMessage shouldBe null
                reconciled.resolvedAt shouldBe true
                fakeExchange.executedOrders.count { it.pair == Asset.BTC_USD_PAIR } shouldBe 1
                projectionEventCount(journal.executionPath) shouldBe 1
                unresolvedCount(journal.executionPath) shouldBe 0

                // A terminally confirmed intent no longer blocks an independent later plan.
                executeLiveOrders(
                    fakeExchange = fakeExchange,
                    intentService = journal.intentService,
                    buys = mapOf(Asset.ETH to BigDecimal("25.00")),
                    cycleId = "live-after-terminal-resolution-cycle",
                )

                fakeExchange.executedOrders.map { it.pair } shouldBe listOf(
                    Asset.BTC_USD_PAIR,
                    Asset.ETH_USD_PAIR,
                )
                readIntent(journal.executionPath, Asset.BTC_USD_PAIR).state shouldBe OrderIntentState.CONFIRMED.name
                readIntent(journal.executionPath, Asset.BTC_USD_PAIR).orderTxid shouldBe "RECONCILED-BTC-1"
                intentCount(journal.executionPath) shouldBe 2
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

        "failure to persist the initial live intent prevents the fake exchange call" {
            val directory = Files.createTempDirectory("live-order-journal-pending-failure-")
            try {
                val journal = openJournal(directory)
                DriverManager.getConnection("jdbc:sqlite:${journal.executionPath}").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            """
                            CREATE TRIGGER reject_pending_intent
                            BEFORE INSERT ON execution_order_intents
                            BEGIN SELECT RAISE(ABORT, 'injected pending-intent failure'); END
                            """.trimIndent(),
                        )
                    }
                }
                val fakeExchange = FakeKrakenService()

                shouldThrow<SQLException> {
                    runTest {
                        executeLiveOrders(
                            fakeExchange = fakeExchange,
                            intentService = journal.intentService,
                            buys = mapOf(Asset.BTC to BigDecimal("25.00")),
                            cycleId = "live-pending-failure-cycle",
                        )
                    }
                }

                fakeExchange.executedOrders.shouldBeEmpty()
                intentCount(journal.executionPath) shouldBe 0
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

        "failed outcome commit recovers to uncertainty and blocks duplicate submission after restart" {
            val directory = Files.createTempDirectory("live-order-journal-outcome-failure-")
            try {
                val journal = openJournal(directory)
                val fakeExchange = FakeKrakenService()
                fakeExchange.executeOrderAction = { pair, _, _, _ ->
                    readIntentState(journal.executionPath, pair) shouldBe OrderIntentState.PENDING.name
                    DriverManager.getConnection("jdbc:sqlite:${journal.executionPath}").use { connection ->
                        connection.createStatement().use { statement ->
                            statement.execute(
                                """
                                CREATE TRIGGER reject_outcome_projection
                                BEFORE INSERT ON execution_projection_outbox
                                BEGIN SELECT RAISE(ABORT, 'injected outcome-projection failure'); END
                                """.trimIndent(),
                            )
                        }
                    }
                }

                shouldThrow<SQLException> {
                    runTest {
                        executeLiveOrders(
                            fakeExchange = fakeExchange,
                            intentService = journal.intentService,
                            buys = mapOf(Asset.BTC to BigDecimal("25.00")),
                            cycleId = "live-outcome-failure-cycle",
                        )
                    }
                }

                readIntent(journal.executionPath, Asset.BTC_USD_PAIR).state shouldBe OrderIntentState.PENDING.name
                fakeExchange.executedOrders.size shouldBe 1
                DriverManager.getConnection("jdbc:sqlite:${journal.executionPath}").use { connection ->
                    connection.createStatement().use { it.execute("DROP TRIGGER reject_outcome_projection") }
                }

                val restarted = openJournal(directory)
                readIntent(restarted.executionPath, Asset.BTC_USD_PAIR).state shouldBe OrderIntentState.UNCERTAIN.name
                val afterRestartExchange = FakeKrakenService()
                executeLiveOrders(
                    fakeExchange = afterRestartExchange,
                    intentService = restarted.intentService,
                    buys = mapOf(Asset.ETH to BigDecimal("25.00")),
                    cycleId = "live-after-restart-cycle",
                )

                afterRestartExchange.executedOrders.shouldBeEmpty()
                unresolvedCount(restarted.executionPath) shouldBe 1
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    }

    private suspend fun openJournal(directory: Path): LiveJournal {
        val reportingPath = directory.resolve("reporting.db")
        val executionPath = directory.resolve("execution.db")
        DatabaseConfig.init(reportingPath.toString())
        val database = ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
        val repository = SqliteExecutionOrderIntentRepositoryImpl(database)
        ExecutionJournalBootstrap(database, repository, reportingPath.toString()).initialize()
        return LiveJournal(
            reportingPath = reportingPath,
            executionPath = executionPath,
            database = database,
            intentService = OrderIntentServiceImpl(repository, ExecutionAccountBindingVerifier { }),
        )
    }

    private suspend fun executeLiveOrders(
        fakeExchange: FakeKrakenService,
        intentService: OrderIntentServiceImpl,
        buys: Map<String, BigDecimal>,
        cycleId: String,
    ) {
        OrderExecutorImpl(fakeExchange, tradeHistoryService = null, intentService).executeOrders(
            buyOrders = buys,
            sellOrders = emptyMap(),
            currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
            prices = buys.keys.associateWith { BigDecimal("1000.00") },
            settings = liveSettings(),
            actionLog = mutableListOf(),
            cycleId = cycleId,
        )
    }

    private fun liveSettings() = TestFixtures.settings(dryRun = false, simulation = false)

    private fun readIntentState(databasePath: Path, pair: String): String? = readIntent(databasePath, pair).state

    private fun readIntentId(databasePath: Path, pair: String): Int =
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.prepareStatement(
                "SELECT id FROM execution_order_intents WHERE pair = ? ORDER BY id DESC LIMIT 1",
            ).use { statement ->
                statement.setString(1, pair)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next()) { "Expected a committed execution intent for $pair." }
                    resultSet.getInt("id")
                }
            }
        }

    private fun readIntent(databasePath: Path, pair: String): PersistedIntent =
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.prepareStatement(
                """
                SELECT state, order_txid, error_message, resolved_at, outcome_volume
                FROM execution_order_intents WHERE pair = ? ORDER BY id DESC LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, pair)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next()) { "Expected a committed execution intent for $pair." }
                    PersistedIntent(
                        state = resultSet.getString("state"),
                        orderTxid = resultSet.getString("order_txid"),
                        errorMessage = resultSet.getString("error_message"),
                        resolvedAt = resultSet.getObject("resolved_at") != null,
                        outcomeVolume = resultSet.getString("outcome_volume"),
                    )
                }
            }
        }

    private fun intentCount(databasePath: Path): Int =
        count(databasePath, "SELECT COUNT(*) FROM execution_order_intents")

    private fun unresolvedCount(databasePath: Path): Int =
        count(databasePath, "SELECT COUNT(*) FROM execution_order_intents WHERE state IN ('PENDING', 'UNCERTAIN')")

    private fun projectionEventCount(databasePath: Path): Int =
        count(databasePath, "SELECT COUNT(*) FROM execution_projection_outbox")

    private fun count(databasePath: Path, query: String): Int =
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(query).use { resultSet ->
                    check(resultSet.next()) { "Expected a SQLite count result." }
                    resultSet.getInt(1)
                }
            }
        }

    private data class LiveJournal(
        val reportingPath: Path,
        val executionPath: Path,
        val database: ExecutionDatabase,
        val intentService: OrderIntentServiceImpl,
    )

    private data class PersistedIntent(
        val state: String,
        val orderTxid: String?,
        val errorMessage: String?,
        val resolvedAt: Boolean,
        val outcomeVolume: String?,
    )
}
