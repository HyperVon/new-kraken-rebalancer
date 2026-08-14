package com.gemini.krakenbot.repository

import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.OrderResult
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
import java.io.IOException
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

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

                shouldThrow<IOException> {
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

                shouldThrow<IOException> {
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

        "manual confirmation retains a settled API fill instead of duplicating its local estimate" {
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
                tradeRepository
                    .getTradesInRange(Instant.EPOCH, Instant.now().plusSeconds(1))
                    .single()
                    .also { trade ->
                        trade.source shouldBe TradeSource.API_FILL
                        trade.success shouldBe true
                        trade.orderTxid shouldBe "O-SETTLED-ALREADY"
                        trade.tradeId shouldBe "T-SETTLED-ALREADY"
                        trade.submissionState shouldBe null
                    }
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

                tradeRepository.getTradesInRange(Instant.EPOCH, Instant.now().plusSeconds(1))
                    .single { it.id == tradeId }
                    .submissionState shouldBe null
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

                shouldThrow<IOException> {
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

        "rejects reconciliation when a legacy intent has no matching trade" {
            runTest {
                val intentId = service.savePending(newIntent())

                shouldThrow<IOException> {
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
