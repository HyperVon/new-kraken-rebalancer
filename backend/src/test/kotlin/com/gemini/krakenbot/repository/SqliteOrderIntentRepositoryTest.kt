package com.gemini.krakenbot.repository

import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentReconciliationException
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.OrderSubmissionState
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.impl.SqliteOrderIntentRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.impl.OrderIntentServiceImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

@Suppress("unused")
class SqliteOrderIntentRepositoryTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val databaseUrl =
        "jdbc:sqlite:file:order-intent-${UUID.randomUUID()}?mode=memory&cache=shared&foreign_keys=true"
    private val database = DatabaseConfig.init(databaseUrl)
    private val repository = SqliteOrderIntentRepositoryImpl(database)
    private val service = OrderIntentServiceImpl(repository)

    init {
        "reports no unresolved intents for a fresh database" {
            service.countUnresolvedIntents() shouldBe 0L
            service.hasUnresolvedIntents() shouldBe false
        }

        "persists pending intent and keeps uncertain outcome unresolved" {
            runTest {
                val intentId = savePendingWithTrade(newIntent())

                service.countUnresolvedIntents() shouldBe 1L
                val pending = service.getUnresolvedIntents().single()
                pending.id shouldBe intentId
                pending.state shouldBe OrderIntentState.PENDING
                pending.volume.shouldBeEqualComparingTo(BigDecimal("0.01000000"))

                service.recordOutcome(
                    intentId,
                    OrderResult.Failure(
                        pair = "XBTUSD",
                        side = "BUY",
                        volume = BigDecimal("0.01000000"),
                        errorMessage = "timeout",
                        submissionUncertain = true,
                    ),
                )

                service.hasUnresolvedIntents() shouldBe true
                service.getUnresolvedIntents().single().state shouldBe OrderIntentState.UNCERTAIN
            }
        }

        "loads nullable intent fields and rejects updates for missing rows" {
            runTest {
                val intentId = savePendingWithTrade(
                    newIntent().copy(
                        cycleId = null,
                        clientOrderId = null,
                        expectedPrice = null,
                    ),
                )
                service.recordOutcome(
                    intentId,
                    OrderResult.Failure(
                        pair = "XBTUSD",
                        side = "BUY",
                        volume = BigDecimal("0.01000000"),
                        errorMessage = "response lost",
                        submissionUncertain = true,
                    ),
                )

                val persisted = service.getUnresolvedIntents().single()
                persisted.cycleId shouldBe null
                persisted.clientOrderId shouldBe null
                persisted.expectedPrice shouldBe null
                persisted.orderTxid shouldBe null
                persisted.errorMessage shouldBe "response lost"
                persisted.resolvedAt shouldBe null
                persisted.resolutionEvidence shouldBe null

                shouldThrow<IllegalStateException> {
                    repository.recordOutcome(
                        id = 999,
                        state = OrderIntentState.REJECTED,
                        orderTxid = null,
                        errorMessage = "missing",
                        resolvedAt = Instant.now(),
                    )
                }
            }
        }

        "fails closed when a legacy intent has multiple local trade candidates" {
            runTest {
                val tradeRepository = SqliteTradeRepositoryImpl(database)
                val legacyIntent = newIntent().copy(cycleId = null, clientOrderId = null)
                tradeRepository.saveTrade(legacyIntent.toPendingTrade())
                tradeRepository.saveTrade(legacyIntent.toPendingTrade())
                val intentId = service.savePending(legacyIntent)

                shouldThrow<OrderIntentReconciliationException> {
                    service.recordOutcome(
                        intentId,
                        OrderResult.Failure(
                            pair = legacyIntent.pair,
                            side = legacyIntent.side,
                            volume = legacyIntent.volume,
                            errorMessage = "response lost",
                            submissionUncertain = true,
                        ),
                    )
                }

                service.getUnresolvedIntents().single().state shouldBe OrderIntentState.PENDING
            }
        }

        "reconciles an explicitly ambiguous client ID through its linked trade" {
            runTest {
                val tradeRepository = SqliteTradeRepositoryImpl(database)
                val legacyIntent = newIntent().copy(clientOrderId = null)
                val tradeId = tradeRepository.saveTrade(
                    legacyIntent.toPendingTrade().copy(clientOrderId = "duplicate-client"),
                )
                val intentId = service.savePending(
                    legacyIntent.copy(
                        clientOrderIdAmbiguous = true,
                        localTradeId = tradeId,
                    ),
                )

                service.recordOutcome(
                    intentId,
                    OrderResult.Success(
                        pair = legacyIntent.pair,
                        side = legacyIntent.side,
                        volume = legacyIntent.volume,
                        orderTxid = "O-AMBIGUOUS",
                    ),
                ) shouldBe true

                tradeRepository
                    .getTradesInRange(Instant.EPOCH, Instant.now().plusSeconds(1))
                    .single()
                    .also { trade ->
                        trade.success shouldBe true
                        trade.orderTxid shouldBe "O-AMBIGUOUS"
                        trade.submissionState shouldBe null
                    }
            }
        }

        "maps a stored resolution timestamp on an unresolved intent" {
            runTest {
                val intentId = savePendingWithTrade(newIntent())
                val resolvedAt = Instant.parse("2026-08-09T12:34:56Z")

                repository.recordOutcome(
                    id = intentId,
                    state = OrderIntentState.UNCERTAIN,
                    orderTxid = null,
                    errorMessage = "ambiguous",
                    resolvedAt = resolvedAt,
                )

                repository.loadUnresolvedIntents().single().resolvedAt shouldBe resolvedAt
            }
        }

        "resolves an uncertain intent only with an explicit terminal outcome and evidence" {
            runTest {
                val intentId = savePendingWithTrade(newIntent())
                service.recordOutcome(
                    intentId,
                    OrderResult.Failure(
                        pair = "XBTUSD",
                        side = "BUY",
                        volume = BigDecimal("0.01000000"),
                        errorMessage = "transport closed",
                        submissionUncertain = true,
                    ),
                )

                shouldThrow<IllegalArgumentException> {
                    service.resolve(intentId, OrderIntentState.PENDING, "not terminal")
                }
                shouldThrow<IllegalArgumentException> {
                    service.resolve(intentId, OrderIntentState.CONFIRMED, "")
                }

                service.resolve(
                    intentId,
                    OrderIntentState.CONFIRMED,
                    "Kraken query returned txid=O-123",
                    orderTxid = "O-123",
                )

                service.countUnresolvedIntents() shouldBe 0L
                service.getUnresolvedIntents() shouldBe emptyList()
                DriverManager.getConnection(databaseUrl).use { connection ->
                    connection.prepareStatement("SELECT order_txid FROM order_intents WHERE id = ?").use { statement ->
                        statement.setInt(1, intentId)
                        statement.executeQuery().use { resultSet ->
                            resultSet.next() shouldBe true
                            resultSet.getString(1) shouldBe "O-123"
                        }
                    }
                }
                shouldThrow<IllegalStateException> {
                    service.resolve(intentId, OrderIntentState.REJECTED, "duplicate resolution")
                }
            }
        }

        "manual confirmation keeps exactly one authoritative API fill" {
            runTest {
                val tradeRepository = SqliteTradeRepositoryImpl(database)
                val intent = newIntent().copy(
                    pair = "LINKUSD",
                    symbol = "LINK",
                    side = "SELL",
                    volume = BigDecimal("6.50000000"),
                    usdAmount = BigDecimal("56.44"),
                    expectedPrice = BigDecimal("8.68307692"),
                )
                val localTradeId = tradeRepository.saveTrade(intent.toPendingTrade())
                val intentId = service.savePending(intent.copy(localTradeId = localTradeId))
                service.recordOutcome(
                    intentId,
                    OrderResult.Failure(
                        pair = intent.pair,
                        side = intent.side,
                        volume = intent.volume,
                        errorMessage = "response lost",
                        submissionUncertain = true,
                    ),
                ) shouldBe true

                tradeRepository.saveTrade(
                    intent.toPendingTrade().copy(
                        success = true,
                        errorMessage = null,
                        usdAmount = BigDecimal("56.45"),
                        price = BigDecimal("8.68461538"),
                        fee = BigDecimal("0.3387"),
                        source = TradeSource.API_FILL,
                        orderTxid = "O-SETTLED-ALREADY",
                        tradeId = "T-SETTLED-ALREADY",
                        submissionState = null,
                    ),
                )

                service.resolve(
                    intentId,
                    OrderIntentState.CONFIRMED,
                    "Verified settled Kraken order O-SETTLED-ALREADY",
                    orderTxid = "O-SETTLED-ALREADY",
                )

                service.countUnresolvedIntents() shouldBe 0L
                val trades = tradeRepository.getTradesInRange(Instant.EPOCH, Instant.now().plusSeconds(1))
                trades.size shouldBe 1
                trades.single()
                    .also { trade ->
                        trade.source shouldBe TradeSource.API_FILL
                        trade.success shouldBe true
                        trade.orderTxid shouldBe "O-SETTLED-ALREADY"
                        trade.tradeId shouldBe "T-SETTLED-ALREADY"
                        trade.usdAmount.shouldBeEqualComparingTo(BigDecimal("56.45"))
                        trade.price.shouldBeEqualComparingTo(BigDecimal("8.68461538"))
                        trade.fee.shouldBeEqualComparingTo(BigDecimal("0.3387"))
                        trade.submissionState shouldBe null
                    }
                DriverManager.getConnection(databaseUrl).use { connection ->
                    connection.prepareStatement(
                        "SELECT state, local_trade_id FROM order_intents WHERE id = ?",
                    ).use { statement ->
                        statement.setInt(1, intentId)
                        statement.executeQuery().use { resultSet ->
                            resultSet.next() shouldBe true
                            resultSet.getString("state") shouldBe OrderIntentState.CONFIRMED.name
                            resultSet.getObject("local_trade_id") shouldBe null
                        }
                    }
                }
            }
        }

        "manual confirmation leaves a same-order partial-fill set unresolved" {
            runTest {
                val tradeRepository = SqliteTradeRepositoryImpl(database)
                val intent = newIntent().copy(
                    pair = "LINKUSD",
                    symbol = "LINK",
                    side = "SELL",
                    volume = BigDecimal("6.50000000"),
                    usdAmount = BigDecimal("56.44"),
                    expectedPrice = BigDecimal("8.68307692"),
                )
                val localTradeId = tradeRepository.saveTrade(intent.toPendingTrade())
                val intentId = service.savePending(intent.copy(localTradeId = localTradeId))
                service.recordOutcome(
                    intentId,
                    OrderResult.Failure(
                        pair = intent.pair,
                        side = intent.side,
                        volume = intent.volume,
                        errorMessage = "response lost",
                        submissionUncertain = true,
                    ),
                ) shouldBe true

                repeat(2) { index ->
                    tradeRepository.saveTrade(
                        intent.toPendingTrade().copy(
                            timestamp = intent.createdAt.plusMillis(500L + index),
                            volume = BigDecimal("3.25000000"),
                            usdAmount = BigDecimal("28.22"),
                            success = true,
                            errorMessage = null,
                            source = TradeSource.API_FILL,
                            orderTxid = "O-PARTIAL-FILLS",
                            tradeId = "T-PARTIAL-$index",
                            submissionState = null,
                        ),
                    )
                }

                shouldThrow<OrderIntentReconciliationException> {
                    service.resolve(
                        intentId,
                        OrderIntentState.CONFIRMED,
                        "Verified order O-PARTIAL-FILLS",
                        orderTxid = "O-PARTIAL-FILLS",
                    )
                }

                service.getUnresolvedIntents().single().state shouldBe OrderIntentState.UNCERTAIN
                val trades = tradeRepository.getTradesInRange(Instant.EPOCH, Instant.now().plusSeconds(1))
                trades.count { it.source == TradeSource.API_FILL } shouldBe 2
                trades.single { it.id == localTradeId }.success shouldBe false
                DriverManager.getConnection(databaseUrl).use { connection ->
                    connection.prepareStatement(
                        "SELECT local_trade_id FROM order_intents WHERE id = ?",
                    ).use { statement ->
                        statement.setInt(1, intentId)
                        statement.executeQuery().use { resultSet ->
                            resultSet.next() shouldBe true
                            resultSet.getInt(1) shouldBe localTradeId
                        }
                    }
                }
            }
        }

        "manual confirmation without an order id removes a uniquely matching keyed API fill" {
            runTest {
                val tradeRepository = SqliteTradeRepositoryImpl(database)
                val intent = newIntent().copy(
                    pair = "LINKUSD",
                    symbol = "LINK",
                    side = "SELL",
                    volume = BigDecimal("6.54229657"),
                    usdAmount = BigDecimal("56.44"),
                    expectedPrice = BigDecimal("8.62694000"),
                )
                val localTradeId = tradeRepository.saveTrade(intent.toPendingTrade())
                val intentId = service.savePending(intent.copy(localTradeId = localTradeId))
                service.recordOutcome(
                    intentId,
                    OrderResult.Failure(
                        pair = intent.pair,
                        side = intent.side,
                        volume = intent.volume,
                        errorMessage = "response lost",
                        submissionUncertain = true,
                    ),
                ) shouldBe true
                tradeRepository.saveTrade(
                    intent.toPendingTrade().copy(
                        timestamp = intent.createdAt.plusMillis(536),
                        success = true,
                        errorMessage = null,
                        usdAmount = BigDecimal("56.45"),
                        price = BigDecimal("8.62919000"),
                        fee = BigDecimal("0.3387"),
                        source = TradeSource.API_FILL,
                        orderTxid = "O-KEYED-WITHOUT-MANUAL-ID",
                        tradeId = "T-KEYED-WITHOUT-MANUAL-ID",
                        clientOrderId = null,
                        submissionState = null,
                    ),
                )

                service.resolve(
                    intentId,
                    OrderIntentState.CONFIRMED,
                    "Verified the uniquely matching settled Kraken fill",
                )

                val trades = tradeRepository.getTradesInRange(Instant.EPOCH, Instant.now().plusSeconds(1))
                trades.size shouldBe 1
                trades.single().also { trade ->
                    trade.source shouldBe TradeSource.API_FILL
                    trade.orderTxid shouldBe "O-KEYED-WITHOUT-MANUAL-ID"
                    trade.tradeId shouldBe "T-KEYED-WITHOUT-MANUAL-ID"
                }
                DriverManager.getConnection(databaseUrl).use { connection ->
                    connection.prepareStatement("SELECT state, local_trade_id FROM order_intents WHERE id = ?")
                        .use { statement ->
                            statement.setInt(1, intentId)
                            statement.executeQuery().use { resultSet ->
                                resultSet.next() shouldBe true
                                resultSet.getString("state") shouldBe OrderIntentState.CONFIRMED.name
                                resultSet.getObject("local_trade_id") shouldBe null
                            }
                        }
                }
            }
        }

        "unresolved linked trades survive pruning and remain protected by the foreign key" {
            runTest {
                val tradeRepository = SqliteTradeRepositoryImpl(database)
                val reference = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                val intent = newIntent().copy(createdAt = reference.minusSeconds(100L * 24 * 60 * 60))
                val tradeId = tradeRepository.saveTrade(intent.toPendingTrade().copy(submissionState = null))
                service.savePending(intent.copy(localTradeId = tradeId))

                tradeRepository.pruneTradesOlderThan(reference.minusSeconds(90L * 24 * 60 * 60)) shouldBe 0
                tradeRepository.getTradesInRange(Instant.EPOCH, reference).single().id shouldBe tradeId

                DriverManager.getConnection(databaseUrl).use { connection ->
                    connection.prepareStatement("DELETE FROM trades WHERE id = ?").use { statement ->
                        statement.setInt(1, tradeId)
                        shouldThrow<java.sql.SQLException> { statement.executeUpdate() }
                    }
                }
            }
        }

        "terminal resolution detaches its trade so retention can remove obsolete evidence" {
            runTest {
                val tradeRepository = SqliteTradeRepositoryImpl(database)
                val reference = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                val intent = newIntent().copy(createdAt = reference.minusSeconds(100L * 24 * 60 * 60))
                val tradeId = tradeRepository.saveTrade(intent.toPendingTrade())
                val intentId = service.savePending(intent.copy(localTradeId = tradeId))
                service.recordOutcome(
                    intentId,
                    OrderResult.Failure(
                        pair = intent.pair,
                        side = intent.side,
                        volume = intent.volume,
                        errorMessage = "response lost",
                        submissionUncertain = true,
                    ),
                ) shouldBe true

                service.resolve(intentId, OrderIntentState.REJECTED, "No matching Kraken fill")

                tradeRepository.pruneTradesOlderThan(reference.minusSeconds(90L * 24 * 60 * 60)) shouldBe 1
                tradeRepository.getTradesInRange(Instant.EPOCH, reference).shouldBe(emptyList())
                DriverManager.getConnection(databaseUrl).use { connection ->
                    connection.prepareStatement(
                        "SELECT local_trade_id FROM order_intents WHERE id = ?",
                    ).use { statement ->
                        statement.setInt(1, intentId)
                        statement.executeQuery().use { resultSet ->
                            resultSet.next() shouldBe true
                            resultSet.getObject(1) shouldBe null
                        }
                    }
                }
            }
        }

        "manual confirmation reconciles a linked estimate with SQLite decimal drift" {
            runTest {
                val tradeRepository = SqliteTradeRepositoryImpl(database)
                val storedUsdAmount = BigDecimal("56.439999971595803")
                val intent = newIntent().copy(
                    pair = "LINKUSD",
                    symbol = "LINK",
                    side = "SELL",
                    volume = BigDecimal("6.54229657"),
                    usdAmount = storedUsdAmount,
                    expectedPrice = BigDecimal("8.62694000"),
                )
                val localTradeId = tradeRepository.saveTrade(intent.toPendingTrade())
                val intentId = service.savePending(intent.copy(localTradeId = localTradeId))
                service.recordOutcome(
                    intentId,
                    OrderResult.Failure(
                        pair = intent.pair,
                        side = intent.side,
                        volume = intent.volume,
                        errorMessage = "response lost",
                        submissionUncertain = true,
                    ),
                ) shouldBe true
                tradeRepository.saveTrade(
                    intent.toPendingTrade().copy(
                        timestamp = intent.createdAt.plusMillis(536),
                        success = true,
                        errorMessage = null,
                        usdAmount = BigDecimal("56.45"),
                        price = BigDecimal("8.62919000"),
                        fee = BigDecimal("0.3387"),
                        source = TradeSource.API_FILL,
                        orderTxid = "O-DECIMAL-DRIFT",
                        tradeId = "T-DECIMAL-DRIFT",
                        submissionState = null,
                    ),
                )

                service.resolve(
                    intentId,
                    OrderIntentState.CONFIRMED,
                    "Verified settled Kraken order O-DECIMAL-DRIFT",
                    orderTxid = "O-DECIMAL-DRIFT",
                )

                service.countUnresolvedIntents() shouldBe 0L
                val trades = tradeRepository.getTradesInRange(Instant.EPOCH, Instant.now().plusSeconds(1))
                trades.size shouldBe 1
                trades.single()
                    .also { trade ->
                        trade.source shouldBe TradeSource.API_FILL
                        trade.orderTxid shouldBe "O-DECIMAL-DRIFT"
                        trade.tradeId shouldBe "T-DECIMAL-DRIFT"
                    }
            }
        }

        "manual confirmation reconciles an ID-linked migrated local estimate" {
            runTest {
                val tradeRepository = SqliteTradeRepositoryImpl(database)
                val intent = newIntent().copy(
                    pair = "LINKUSD",
                    symbol = "LINK",
                    side = "SELL",
                    volume = BigDecimal("6.50000000"),
                    usdAmount = BigDecimal("56.44"),
                    expectedPrice = BigDecimal("8.68307692"),
                )
                val localTradeId = tradeRepository.saveTrade(intent.toPendingTrade())
                val intentId = service.savePending(intent.copy(localTradeId = localTradeId))
                service.recordOutcome(
                    intentId,
                    OrderResult.Failure(
                        pair = intent.pair,
                        side = intent.side,
                        volume = intent.volume,
                        errorMessage = "response lost",
                        submissionUncertain = true,
                    ),
                ) shouldBe true

                DriverManager.getConnection(databaseUrl).use { connection ->
                    connection.prepareStatement(
                        "UPDATE trades SET client_order_id = NULL, submission_state = NULL WHERE id = ?",
                    ).use { statement ->
                        statement.setInt(1, localTradeId)
                        statement.executeUpdate() shouldBe 1
                    }
                }
                tradeRepository.saveTrade(
                    intent.toPendingTrade().copy(
                        success = true,
                        errorMessage = null,
                        usdAmount = BigDecimal("56.45"),
                        price = BigDecimal("8.68461538"),
                        fee = BigDecimal("0.3387"),
                        source = TradeSource.API_FILL,
                        orderTxid = "O-SETTLED-MIGRATED",
                        tradeId = "T-SETTLED-MIGRATED",
                        submissionState = null,
                    ),
                )

                service.resolve(
                    intentId,
                    OrderIntentState.CONFIRMED,
                    "Verified settled Kraken order O-SETTLED-MIGRATED",
                    orderTxid = "O-SETTLED-MIGRATED",
                )

                service.countUnresolvedIntents() shouldBe 0L
                val trades = tradeRepository.getTradesInRange(Instant.EPOCH, Instant.now().plusSeconds(1))
                trades.size shouldBe 1
                trades.single()
                    .also { trade ->
                        trade.source shouldBe TradeSource.API_FILL
                        trade.orderTxid shouldBe "O-SETTLED-MIGRATED"
                        trade.tradeId shouldBe "T-SETTLED-MIGRATED"
                    }
            }
        }

        "manual confirmation retains a single matching legacy API fill without identifiers" {
            runTest {
                val tradeRepository = SqliteTradeRepositoryImpl(database)
                val intent = newIntent().copy(
                    pair = "LINKUSD",
                    symbol = "LINK",
                    side = "SELL",
                    volume = BigDecimal("6.54229657"),
                    usdAmount = BigDecimal("56.44"),
                    expectedPrice = BigDecimal("8.62694000"),
                )
                val localTradeId = tradeRepository.saveTrade(intent.toPendingTrade())
                val intentId = service.savePending(intent.copy(localTradeId = localTradeId))
                service.recordOutcome(
                    intentId,
                    OrderResult.Failure(
                        pair = intent.pair,
                        side = intent.side,
                        volume = intent.volume,
                        errorMessage = "response lost",
                        submissionUncertain = true,
                    ),
                ) shouldBe true

                DriverManager.getConnection(databaseUrl).use { connection ->
                    connection.prepareStatement(
                        "UPDATE trades SET client_order_id = NULL, submission_state = NULL WHERE id = ?",
                    ).use { statement ->
                        statement.setInt(1, localTradeId)
                        statement.executeUpdate() shouldBe 1
                    }
                }
                tradeRepository.saveTrade(
                    intent.toPendingTrade().copy(
                        timestamp = intent.createdAt.plusMillis(536),
                        success = true,
                        errorMessage = null,
                        usdAmount = BigDecimal("56.45"),
                        price = BigDecimal("8.62919000"),
                        fee = BigDecimal("0.3387"),
                        source = TradeSource.API_FILL,
                        orderTxid = null,
                        tradeId = null,
                        clientOrderId = null,
                        submissionState = null,
                    ),
                )

                service.resolve(
                    intentId,
                    OrderIntentState.CONFIRMED,
                    "Verified settled Kraken order O-LEGACY-UNKEYED",
                    orderTxid = "O-LEGACY-UNKEYED",
                )

                service.countUnresolvedIntents() shouldBe 0L
                val trades = tradeRepository.getTradesInRange(Instant.EPOCH, Instant.now().plusSeconds(1))
                trades.size shouldBe 1
                trades.single()
                    .also { trade ->
                        trade.source shouldBe TradeSource.API_FILL
                        trade.orderTxid shouldBe null
                        trade.tradeId shouldBe null
                        trade.usdAmount.shouldBeEqualComparingTo(BigDecimal("56.45"))
                        trade.price.shouldBeEqualComparingTo(BigDecimal("8.62919000"))
                        trade.fee.shouldBeEqualComparingTo(BigDecimal("0.3387"))
                    }
            }
        }

        "manual confirmation does not match legacy API fills outside economics tolerances" {
            runTest {
                val tradeRepository = SqliteTradeRepositoryImpl(database)
                val intent = newIntent().copy(
                    pair = "LINKUSD",
                    symbol = "LINK",
                    side = "SELL",
                    volume = BigDecimal("6.54229657"),
                    usdAmount = BigDecimal("56.44"),
                    expectedPrice = BigDecimal("8.62694000"),
                )
                val localTradeId = tradeRepository.saveTrade(intent.toPendingTrade())
                val intentId = service.savePending(intent.copy(localTradeId = localTradeId))
                service.recordOutcome(
                    intentId,
                    OrderResult.Failure(
                        pair = intent.pair,
                        side = intent.side,
                        volume = intent.volume,
                        errorMessage = "response lost",
                        submissionUncertain = true,
                    ),
                ) shouldBe true

                val outOfToleranceFills = listOf(
                    intent.toPendingTrade().copy(
                        timestamp = intent.createdAt.plusMillis(500),
                        success = true,
                        errorMessage = null,
                        usdAmount = BigDecimal("58.00"),
                        price = BigDecimal("8.62919000"),
                        source = TradeSource.API_FILL,
                        orderTxid = null,
                        tradeId = null,
                        clientOrderId = null,
                        submissionState = null,
                    ),
                    intent.toPendingTrade().copy(
                        timestamp = intent.createdAt.plusMillis(501),
                        success = true,
                        errorMessage = null,
                        usdAmount = BigDecimal("56.45"),
                        price = BigDecimal("8.80000000"),
                        source = TradeSource.API_FILL,
                        orderTxid = null,
                        tradeId = null,
                        clientOrderId = null,
                        submissionState = null,
                    ),
                )
                for (trade in outOfToleranceFills) {
                    tradeRepository.saveTrade(trade)
                }

                service.resolve(
                    intentId,
                    OrderIntentState.CONFIRMED,
                    "Verified settled Kraken order O-OUTSIDE-TOLERANCE",
                    orderTxid = "O-OUTSIDE-TOLERANCE",
                )

                service.countUnresolvedIntents() shouldBe 0L
                val trades = tradeRepository.getTradesInRange(Instant.EPOCH, Instant.now().plusSeconds(1))
                trades.count { it.source == TradeSource.API_FILL } shouldBe 2
                trades.single { it.id == localTradeId }.also { trade ->
                    trade.source shouldBe TradeSource.LOCAL_ESTIMATE
                    trade.success shouldBe true
                    trade.orderTxid shouldBe "O-OUTSIDE-TOLERANCE"
                }
            }
        }

        "manual confirmation fails closed for multiple matching legacy API fills without identifiers" {
            runTest {
                val tradeRepository = SqliteTradeRepositoryImpl(database)
                val intent = newIntent().copy(
                    pair = "LINKUSD",
                    symbol = "LINK",
                    side = "SELL",
                    volume = BigDecimal("6.54229657"),
                    usdAmount = BigDecimal("56.44"),
                    expectedPrice = null,
                )
                val localTradeId = tradeRepository.saveTrade(intent.toPendingTrade())
                val intentId = service.savePending(intent.copy(localTradeId = localTradeId))
                service.recordOutcome(
                    intentId,
                    OrderResult.Failure(
                        pair = intent.pair,
                        side = intent.side,
                        volume = intent.volume,
                        errorMessage = "response lost",
                        submissionUncertain = true,
                    ),
                ) shouldBe true

                repeat(2) { index ->
                    tradeRepository.saveTrade(
                        intent.toPendingTrade().copy(
                            timestamp = intent.createdAt.plusMillis(500L + index),
                            success = true,
                            errorMessage = null,
                            usdAmount = BigDecimal("56.45"),
                            price = BigDecimal("8.62919000"),
                            fee = BigDecimal("0.3387"),
                            source = TradeSource.API_FILL,
                            orderTxid = null,
                            tradeId = null,
                            clientOrderId = null,
                            submissionState = null,
                        ),
                    )
                }

                shouldThrow<IllegalStateException> {
                    service.resolve(
                        intentId,
                        OrderIntentState.CONFIRMED,
                        "Verified settled Kraken order O-AMBIGUOUS-LEGACY",
                        orderTxid = "O-AMBIGUOUS-LEGACY",
                    )
                }

                service.getUnresolvedIntents().single().state shouldBe OrderIntentState.UNCERTAIN
                tradeRepository
                    .getTradesInRange(Instant.EPOCH, Instant.now().plusSeconds(1))
                    .single { it.id == localTradeId }
                    .success shouldBe false
            }
        }

        "manual confirmation keeps its local estimate when an API fill belongs to another order" {
            runTest {
                val tradeRepository = SqliteTradeRepositoryImpl(database)
                val intent = newIntent().copy(
                    pair = "LINKUSD",
                    symbol = "LINK",
                    side = "SELL",
                    volume = BigDecimal("6.50000000"),
                    usdAmount = BigDecimal("56.44"),
                    expectedPrice = BigDecimal("8.68307692"),
                )
                val localTradeId = tradeRepository.saveTrade(intent.toPendingTrade())
                val intentId = service.savePending(intent.copy(localTradeId = localTradeId))
                service.recordOutcome(
                    intentId,
                    OrderResult.Failure(
                        pair = intent.pair,
                        side = intent.side,
                        volume = intent.volume,
                        errorMessage = "response lost",
                        submissionUncertain = true,
                    ),
                ) shouldBe true
                tradeRepository.saveTrade(
                    intent.toPendingTrade().copy(
                        success = true,
                        errorMessage = null,
                        source = TradeSource.API_FILL,
                        orderTxid = "O-OTHER-ORDER",
                        tradeId = "T-OTHER-ORDER",
                        submissionState = null,
                    ),
                )

                service.resolve(
                    intentId,
                    OrderIntentState.CONFIRMED,
                    "Verified settled Kraken order O-CONFIRMED-ORDER",
                    orderTxid = "O-CONFIRMED-ORDER",
                )

                val trades = tradeRepository.getTradesInRange(Instant.EPOCH, Instant.now().plusSeconds(1))
                trades.single { it.source == TradeSource.API_FILL }.orderTxid shouldBe "O-OTHER-ORDER"
                trades.single { it.source == TradeSource.LOCAL_ESTIMATE }.also { trade ->
                    trade.success shouldBe true
                    trade.orderTxid shouldBe "O-CONFIRMED-ORDER"
                    trade.submissionState shouldBe null
                }
            }
        }

        "manual confirmation keeps its local estimate for same-txid rows that are not settled API fills" {
            runTest {
                val tradeRepository = SqliteTradeRepositoryImpl(database)
                val intent = newIntent().copy(
                    pair = "LINKUSD",
                    symbol = "LINK",
                    side = "SELL",
                    volume = BigDecimal("6.50000000"),
                    usdAmount = BigDecimal("56.44"),
                    expectedPrice = BigDecimal("8.68307692"),
                )
                val localTradeId = tradeRepository.saveTrade(intent.toPendingTrade())
                val intentId = service.savePending(intent.copy(localTradeId = localTradeId))
                service.recordOutcome(
                    intentId,
                    OrderResult.Failure(
                        pair = intent.pair,
                        side = intent.side,
                        volume = intent.volume,
                        errorMessage = "response lost",
                        submissionUncertain = true,
                    ),
                ) shouldBe true
                val apiFill = intent.toPendingTrade().copy(
                    success = true,
                    errorMessage = null,
                    source = TradeSource.API_FILL,
                    orderTxid = "O-CONFIRMED-ORDER",
                    submissionState = null,
                )
                listOf(
                    apiFill.copy(pair = "ETHUSD", tradeId = "T-WRONG-PAIR"),
                    apiFill.copy(symbol = "ETH", tradeId = "T-WRONG-SYMBOL"),
                    apiFill.copy(side = "BUY", tradeId = "T-WRONG-SIDE"),
                    apiFill.copy(success = false, tradeId = "T-UNSUCCESSFUL"),
                    apiFill.copy(dryRun = true, tradeId = "T-DRY-RUN"),
                    apiFill.copy(source = TradeSource.LOCAL_ESTIMATE, tradeId = "T-LOCAL-ESTIMATE"),
                ).forEach { tradeRepository.saveTrade(it) }

                service.resolve(
                    intentId,
                    OrderIntentState.CONFIRMED,
                    "Verified settled Kraken order O-CONFIRMED-ORDER",
                    orderTxid = "O-CONFIRMED-ORDER",
                )

                val trades = tradeRepository.getTradesInRange(Instant.EPOCH, Instant.now().plusSeconds(1))
                trades.size shouldBe 7
                trades.single { it.id == localTradeId }.also { trade ->
                    trade.source shouldBe TradeSource.LOCAL_ESTIMATE
                    trade.success shouldBe true
                    trade.orderTxid shouldBe "O-CONFIRMED-ORDER"
                    trade.submissionState shouldBe null
                }
            }
        }

        "normalizes a blank manual order txid to null" {
            runTest {
                val intentId = savePendingWithTrade(newIntent())
                service.recordOutcome(
                    intentId,
                    OrderResult.Failure(
                        pair = "XBTUSD",
                        side = "BUY",
                        volume = BigDecimal("0.01000000"),
                        errorMessage = "transport closed",
                        submissionUncertain = true,
                    ),
                )

                service.resolve(
                    intentId,
                    OrderIntentState.CONFIRMED,
                    "Verified exchange outcome without a recorded Kraken order ID",
                    orderTxid = "   ",
                )

                DriverManager.getConnection(databaseUrl).use { connection ->
                    connection.prepareStatement("SELECT order_txid FROM order_intents WHERE id = ?").use { statement ->
                        statement.setInt(1, intentId)
                        statement.executeQuery().use { resultSet ->
                            resultSet.next() shouldBe true
                            resultSet.getString(1) shouldBe null
                        }
                    }
                }
            }
        }

        "preserves a previously recorded order txid during manual resolution" {
            runTest {
                val intentId = savePendingWithTrade(newIntent())
                service.recordOutcome(
                    intentId,
                    OrderResult.Failure(
                        pair = "XBTUSD",
                        side = "BUY",
                        volume = BigDecimal("0.01000000"),
                        errorMessage = "response uncertain",
                        orderTxid = "O-PREVIOUSLY-KNOWN",
                        submissionUncertain = true,
                    ),
                )

                service.resolve(intentId, OrderIntentState.CONFIRMED, "Verified in Kraken")

                DriverManager.getConnection(databaseUrl).use { connection ->
                    connection.prepareStatement("SELECT order_txid FROM order_intents WHERE id = ?").use { statement ->
                        statement.setInt(1, intentId)
                        statement.executeQuery().use { resultSet ->
                            resultSet.next() shouldBe true
                            resultSet.getString(1) shouldBe "O-PREVIOUSLY-KNOWN"
                        }
                    }
                }
            }
        }

        "accepts a repository-level pending outcome without terminalizing the trade" {
            runTest {
                val intent = newIntent()
                val intentId = savePendingWithTrade(intent)

                repository.recordOutcome(
                    id = intentId,
                    state = OrderIntentState.PENDING,
                    orderTxid = null,
                    errorMessage = "still in flight",
                    resolvedAt = null,
                ) shouldBe true

                service.getUnresolvedIntents().single().state shouldBe OrderIntentState.PENDING
            }
        }

        "a late exchange outcome cannot overwrite an operator resolution" {
            runTest {
                val intentId = savePendingWithTrade(newIntent())
                service.recordOutcome(
                    intentId,
                    OrderResult.Failure(
                        pair = "XBTUSD",
                        side = "BUY",
                        volume = BigDecimal("0.01000000"),
                        errorMessage = "transport closed",
                        submissionUncertain = true,
                    ),
                )
                service.resolve(intentId, OrderIntentState.REJECTED, "No matching Kraken order or fill")

                service.recordOutcome(
                    intentId,
                    OrderResult.Success(
                        pair = "XBTUSD",
                        side = "BUY",
                        volume = BigDecimal("0.01000000"),
                        orderTxid = "O-LATE",
                    ),
                )

                DriverManager.getConnection(databaseUrl).use { connection ->
                    connection.prepareStatement(
                        "SELECT state, order_txid, resolution_evidence FROM order_intents WHERE id = ?",
                    ).use { statement ->
                        statement.setInt(1, intentId)
                        statement.executeQuery().use { resultSet ->
                            resultSet.next() shouldBe true
                            resultSet.getString("state") shouldBe OrderIntentState.REJECTED.name
                            resultSet.getString("order_txid") shouldBe null
                            resultSet.getString("resolution_evidence") shouldBe "No matching Kraken order or fill"
                        }
                    }
                }
            }
        }

        "a late outcome cannot overwrite a confirmed journal entry" {
            runTest {
                val intentId = savePendingWithTrade(newIntent())
                service.recordOutcome(
                    intentId,
                    OrderResult.Success(
                        pair = "XBTUSD",
                        side = "BUY",
                        volume = BigDecimal("0.01000000"),
                        orderTxid = "O-CONFIRMED-FIRST",
                    ),
                )

                service.recordOutcome(
                    intentId,
                    OrderResult.Failure(
                        pair = "XBTUSD",
                        side = "BUY",
                        volume = BigDecimal("0.01000000"),
                        errorMessage = "late failure",
                    ),
                )

                service.getUnresolvedIntents() shouldBe emptyList()
            }
        }

        "terminal journal transitions reconcile the matching local trade row" {
            runTest {
                val tradeRepository = SqliteTradeRepositoryImpl(database)
                val confirmedIntent = newIntent()
                tradeRepository.saveTrade(confirmedIntent.toPendingTrade())
                val confirmedId = service.savePending(confirmedIntent)
                service.recordOutcome(
                    confirmedId,
                    OrderResult(
                        success = true,
                        pair = confirmedIntent.pair,
                        side = confirmedIntent.side,
                        volume = confirmedIntent.volume,
                        orderTxid = "O-LOCAL-CONFIRMED",
                    ),
                ) shouldBe true

                val confirmedTrade = tradeRepository
                    .getTradesInRange(Instant.EPOCH, Instant.now().plusSeconds(1))
                    .single { it.clientOrderId == confirmedIntent.clientOrderId }
                confirmedTrade.success shouldBe true
                confirmedTrade.submissionState shouldBe null
                confirmedTrade.orderTxid shouldBe "O-LOCAL-CONFIRMED"

                val rejectedIntent = newIntent()
                tradeRepository.saveTrade(rejectedIntent.toPendingTrade())
                val rejectedId = service.savePending(rejectedIntent)
                service.recordOutcome(
                    rejectedId,
                    OrderResult.Failure(
                        pair = rejectedIntent.pair,
                        side = rejectedIntent.side,
                        volume = rejectedIntent.volume,
                        errorMessage = "transport closed",
                        submissionUncertain = true,
                    ),
                ) shouldBe true
                service.resolve(rejectedId, OrderIntentState.REJECTED, "No matching Kraken fill")

                val rejectedTrade = tradeRepository
                    .getTradesInRange(Instant.EPOCH, Instant.now().plusSeconds(1))
                    .single { it.clientOrderId == rejectedIntent.clientOrderId }
                rejectedTrade.success shouldBe false
                rejectedTrade.submissionState shouldBe null
                rejectedTrade.errorMessage shouldBe "No matching Kraken fill"

                val legacyIntent = newIntent().copy(cycleId = null, clientOrderId = null)
                tradeRepository.saveTrade(legacyIntent.toPendingTrade())
                val legacyId = service.savePending(legacyIntent)
                service.recordOutcome(
                    legacyId,
                    OrderResult.Failure(
                        pair = legacyIntent.pair,
                        side = legacyIntent.side,
                        volume = legacyIntent.volume,
                        errorMessage = "response lost",
                        submissionUncertain = true,
                    ),
                )
                service.resolve(legacyId, OrderIntentState.REJECTED, "No matching legacy fill")

                val legacyTrade = tradeRepository
                    .getTradesInRange(Instant.EPOCH, Instant.now().plusSeconds(1))
                    .single { it.clientOrderId == null }
                legacyTrade.submissionState shouldBe null
                legacyTrade.errorMessage shouldBe "No matching legacy fill"
            }
        }

        "uses the persisted local trade id when reconciling a live intent" {
            runTest {
                val tradeRepository = SqliteTradeRepositoryImpl(database)
                val intent = newIntent()
                val tradeId = tradeRepository.saveTrade(intent.toPendingTrade())
                val duplicateTradeId = tradeRepository.saveTrade(intent.toPendingTrade())
                val intentId = service.savePending(intent.copy(localTradeId = tradeId))

                service.recordOutcome(
                    intentId,
                    OrderResult.Success(
                        pair = intent.pair,
                        side = intent.side,
                        volume = intent.volume,
                        orderTxid = "O-LINKED-ID",
                    ),
                )

                val trades = tradeRepository.getTradesInRange(Instant.EPOCH, Instant.now().plusSeconds(1))
                trades.single { it.id == tradeId }.also { trade ->
                    trade.success shouldBe true
                    trade.orderTxid shouldBe "O-LINKED-ID"
                    trade.submissionState shouldBe null
                }
                trades.single { it.id == duplicateTradeId }.also { trade ->
                    trade.success shouldBe false
                    trade.orderTxid shouldBe null
                    trade.submissionState shouldBe OrderSubmissionState.PENDING
                }
            }
        }

        "rejects reconciliation when a linked trade identity has changed" {
            runTest {
                val tradeRepository = SqliteTradeRepositoryImpl(database)
                val intent = newIntent()
                val tradeId = tradeRepository.saveTrade(
                    intent.toPendingTrade().copy(volume = BigDecimal("0.02000000")),
                )
                val intentId = service.savePending(intent.copy(localTradeId = tradeId))

                shouldThrow<OrderIntentReconciliationException> {
                    service.recordOutcome(
                        intentId,
                        OrderResult.Success(
                            pair = intent.pair,
                            side = intent.side,
                            volume = intent.volume,
                            orderTxid = "O-IDENTITY-MISMATCH",
                        ),
                    )
                }

                service.getUnresolvedIntents().single().state shouldBe OrderIntentState.PENDING
            }
        }

        "rejects resolution when a linked trade is no longer pending" {
            runTest {
                val tradeRepository = SqliteTradeRepositoryImpl(database)
                val intent = newIntent()
                val tradeId = tradeRepository.saveTrade(intent.toPendingTrade())
                val intentId = service.savePending(intent.copy(localTradeId = tradeId))
                service.recordOutcome(
                    intentId,
                    OrderResult.Failure(
                        pair = intent.pair,
                        side = intent.side,
                        volume = intent.volume,
                        errorMessage = "response lost",
                        submissionUncertain = true,
                    ),
                ) shouldBe true
                DriverManager.getConnection(databaseUrl).use { connection ->
                    connection.prepareStatement("UPDATE trades SET success = 1 WHERE id = ?").use { statement ->
                        statement.setInt(1, tradeId)
                        statement.executeUpdate() shouldBe 1
                    }
                }

                shouldThrow<IllegalStateException> {
                    service.resolve(intentId, OrderIntentState.CONFIRMED, "Verified exchange outcome")
                }

                service.getUnresolvedIntents().single().state shouldBe OrderIntentState.UNCERTAIN
            }
        }

        "rejects resolution when a linked trade's immutable fields no longer match" {
            runTest {
                val assignments = listOf(
                    "timestamp = timestamp + 1",
                    "pair = 'ETHUSD'",
                    "symbol = 'ETH'",
                    "side = 'SELL'",
                    "usd_amount = usd_amount + 1",
                    "dry_run = 1",
                    "source = 'API_FILL'",
                )
                for (assignment in assignments) {
                    val tradeRepository = SqliteTradeRepositoryImpl(database)
                    val intent = newIntent()
                    val tradeId = tradeRepository.saveTrade(intent.toPendingTrade())
                    val intentId = service.savePending(intent.copy(localTradeId = tradeId))
                    service.recordOutcome(
                        intentId,
                        OrderResult.Failure(
                            pair = intent.pair,
                            side = intent.side,
                            volume = intent.volume,
                            errorMessage = "response lost",
                            submissionUncertain = true,
                        ),
                    ) shouldBe true
                    DriverManager.getConnection(databaseUrl).use { connection ->
                        connection.prepareStatement("UPDATE trades SET $assignment WHERE id = ?").use { statement ->
                            statement.setInt(1, tradeId)
                            statement.executeUpdate() shouldBe 1
                        }
                    }

                    shouldThrow<IllegalStateException> {
                        service.resolve(intentId, OrderIntentState.CONFIRMED, "Verified exchange outcome")
                    }

                    service.getUnresolvedIntents().single { it.id == intentId }.state shouldBe
                        OrderIntentState.UNCERTAIN
                }
            }
        }

        "rejects reconciliation when a legacy intent has no matching trade" {
            runTest {
                val intentId = service.savePending(newIntent())

                shouldThrow<OrderIntentReconciliationException> {
                    service.recordOutcome(
                        intentId,
                        OrderResult.Failure(
                            pair = "XBTUSD",
                            side = "BUY",
                            volume = BigDecimal("0.01000000"),
                            errorMessage = "response lost",
                            submissionUncertain = true,
                        ),
                    )
                }

                service.getUnresolvedIntents().single().state shouldBe OrderIntentState.PENDING
            }
        }

        "does not manually resolve a PENDING intent while submission is in flight" {
            runTest {
                val intentId = service.savePending(newIntent())

                shouldThrow<IllegalStateException> {
                    service.resolve(intentId, OrderIntentState.REJECTED, "No matching Kraken fill")
                }
                service.countUnresolvedIntents() shouldBe 1L
            }
        }

        "records successful outcome as resolved without manual intervention" {
            runTest {
                val intentId = savePendingWithTrade(newIntent())
                service.recordOutcome(
                    intentId,
                    OrderResult.Success(
                        pair = "XBTUSD",
                        side = "BUY",
                        volume = BigDecimal("0.01000000"),
                        orderTxid = "O-456",
                    ),
                )

                service.countUnresolvedIntents() shouldBe 0L
                val persisted = repository.loadUnresolvedIntents()
                persisted shouldBe emptyList()

                DriverManager.getConnection(databaseUrl).use { connection ->
                    connection.prepareStatement(
                        "SELECT state, order_txid, resolved_at FROM order_intents WHERE id = ?",
                    ).use { statement ->
                        statement.setInt(1, intentId)
                        statement.executeQuery().use { resultSet ->
                            resultSet.next() shouldBe true
                            resultSet.getString("state") shouldBe OrderIntentState.CONFIRMED.name
                            resultSet.getString("order_txid") shouldBe "O-456"
                            (resultSet.getLong("resolved_at") > 0L) shouldBe true
                        }
                    }
                }
            }
        }

        "records a definite exchange failure as rejected" {
            runTest {
                val intentId = savePendingWithTrade(newIntent())
                service.recordOutcome(
                    intentId,
                    OrderResult.Failure(
                        pair = "XBTUSD",
                        side = "BUY",
                        volume = BigDecimal("0.01000000"),
                        errorMessage = "order rejected",
                    ),
                )

                service.countUnresolvedIntents() shouldBe 0L
            }
        }
    }

    private suspend fun savePendingWithTrade(intent: OrderIntent): Int {
        SqliteTradeRepositoryImpl(database).saveTrade(intent.toPendingTrade())
        return service.savePending(intent)
    }

    private fun newIntent() = OrderIntent(
        cycleId = UUID.randomUUID().toString(),
        clientOrderId = UUID.randomUUID().toString(),
        pair = "XBTUSD",
        symbol = "BTC",
        side = "BUY",
        volume = BigDecimal("0.01000000"),
        usdAmount = BigDecimal("500.00"),
        expectedPrice = BigDecimal("50000.00000000"),
        createdAt = Instant.now(),
        state = OrderIntentState.PENDING,
    )

    private fun OrderIntent.toPendingTrade() = TradeRecord(
        timestamp = createdAt,
        pair = pair,
        side = side,
        symbol = symbol,
        volume = volume,
        usdAmount = usdAmount,
        success = false,
        dryRun = false,
        errorMessage = "Order submission pending",
        price = expectedPrice ?: BigDecimal.ZERO,
        expectedPrice = expectedPrice,
        source = TradeSource.LOCAL_ESTIMATE,
        cycleId = cycleId,
        clientOrderId = clientOrderId,
        submissionState = OrderSubmissionState.PENDING,
    )
}
