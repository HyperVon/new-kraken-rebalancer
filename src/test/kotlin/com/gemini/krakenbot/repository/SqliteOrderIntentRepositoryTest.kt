package com.gemini.krakenbot.repository

import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.repository.impl.SqliteOrderIntentRepositoryImpl
import com.gemini.krakenbot.service.impl.OrderIntentServiceImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class SqliteOrderIntentRepositoryTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val database = DatabaseConfig.init(
        "jdbc:sqlite:file:order-intent-${UUID.randomUUID()}?mode=memory&cache=shared&foreign_keys=true",
    )
    private val repository = SqliteOrderIntentRepositoryImpl(database)
    private val service = OrderIntentServiceImpl(repository)

    init {
        "persists pending intent and keeps uncertain outcome unresolved" {
            runTest {
                val intentId = service.savePending(newIntent())

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
                val intentId = service.savePending(
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

        "maps a stored resolution timestamp on an unresolved intent" {
            runTest {
                val intentId = service.savePending(newIntent())
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
                val intentId = service.savePending(newIntent())
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

                service.resolve(intentId, OrderIntentState.CONFIRMED, "Kraken query returned txid=O-123")

                service.countUnresolvedIntents() shouldBe 0L
                service.getUnresolvedIntents() shouldBe emptyList()
                shouldThrow<IllegalStateException> {
                    service.resolve(intentId, OrderIntentState.REJECTED, "duplicate resolution")
                }
            }
        }

        "records successful outcome as resolved without manual intervention" {
            runTest {
                val intentId = service.savePending(newIntent())
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
            }
        }

        "records a definite exchange failure as rejected" {
            runTest {
                val intentId = service.savePending(newIntent())
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
}
