package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.OrderSubmissionState
import com.gemini.krakenbot.repository.ExecutionOrderIntentRepository
import com.gemini.krakenbot.repository.PendingTradeProjection
import com.gemini.krakenbot.repository.TradeRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant

class TradeProjectionServiceImplTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "projects confirmed, rejected and uncertain outcomes without changing their meaning" {
            runTest {
                val execution = mockk<ExecutionOrderIntentRepository>()
                val reporting = mockk<TradeRepository>()
                val journalId = "journal-1"
                every { execution.journalId() } returns journalId
                coEvery { reporting.getExecutionProjectionCursor(journalId) } returns 0L
                coEvery { execution.loadProjectionEvents(0L, 100) } returns listOf(
                    projection(1, OrderIntentState.CONFIRMED, orderTxid = "OID-1"),
                    projection(2, OrderIntentState.REJECTED, errorMessage = null),
                    projection(3, OrderIntentState.UNCERTAIN, errorMessage = null),
                )
                val saved = mutableListOf<com.gemini.krakenbot.model.TradeRecord>()
                val projectedJournalIds = mutableListOf<String>()
                val projectedIntentIds = mutableListOf<Int>()
                coEvery {
                    reporting.upsertExecutionProjection(
                        journalId = any(),
                        eventId = any(),
                        intentId = any(),
                        legacyTradeId = any(),
                        trade = any(),
                    )
                } answers {
                    saved += arg<com.gemini.krakenbot.model.TradeRecord>(4)
                    projectedJournalIds += firstArg<String>()
                    projectedIntentIds += thirdArg<Int>()
                    true
                }

                val count = TradeProjectionServiceImpl(execution, reporting).projectPending()

                count shouldBe 3
                saved.map { it.success } shouldBe listOf(true, false, false)
                saved.map { it.errorMessage } shouldBe
                    listOf(null, "Kraken rejected the order.", "Order submission outcome is uncertain.")
                saved.map { it.submissionState } shouldBe listOf(
                    null,
                    null,
                    OrderSubmissionState.UNCERTAIN,
                )
                saved.first().orderTxid shouldBe "OID-1"
                saved.last().price.shouldBeEqualComparingTo(BigDecimal("1250.00"))
                saved.last().expectedPrice.shouldNotBeNull().shouldBeEqualComparingTo(BigDecimal("1000.00"))
                saved.map { it.dryRun } shouldBe listOf(false, false, false)
                projectedJournalIds shouldBe listOf(journalId, journalId, journalId)
                projectedIntentIds shouldBe listOf(1, 2, 3)
            }
        }

        "pending events are skipped and an already projected event does not count again" {
            runTest {
                val execution = mockk<ExecutionOrderIntentRepository>()
                val reporting = mockk<TradeRepository>()
                every { execution.journalId() } returns "journal-2"
                coEvery { reporting.getExecutionProjectionCursor("journal-2") } returns 7L
                coEvery { execution.loadProjectionEvents(7L, 100) } returns listOf(
                    projection(8, OrderIntentState.PENDING),
                    projection(9, OrderIntentState.CONFIRMED),
                )
                coEvery { reporting.upsertExecutionProjection(any(), any(), any(), any(), any()) } returns false

                val count = TradeProjectionServiceImpl(execution, reporting).projectPending()

                count shouldBe 0
                coVerify(exactly = 1) { reporting.upsertExecutionProjection(any(), 9L, any(), any(), any()) }
            }
        }

        "projection requires an identified intent and forwards the requested batch limit" {
            runTest {
                val execution = mockk<ExecutionOrderIntentRepository>()
                val reporting = mockk<TradeRepository>()
                every { execution.journalId() } returns "journal-3"
                coEvery { reporting.getExecutionProjectionCursor("journal-3") } returns 0L
                coEvery { execution.loadProjectionEvents(0L, 4) } returns listOf(
                    projection(1, OrderIntentState.CONFIRMED, intentId = null),
                )

                shouldThrow<IllegalArgumentException> {
                    TradeProjectionServiceImpl(execution, reporting).projectPending(limit = 4)
                }
                coVerify(exactly = 0) { reporting.upsertExecutionProjection(any(), any(), any(), any(), any()) }
            }
        }

        "projection uses submitted volume when no exchange outcome volume was recorded" {
            runTest {
                val execution = mockk<ExecutionOrderIntentRepository>()
                val reporting = mockk<TradeRepository>()
                every { execution.journalId() } returns "journal-1"
                coEvery { reporting.getExecutionProjectionCursor("journal-1") } returns 0L
                coEvery { execution.loadProjectionEvents(0L, 100) } returns listOf(
                    projection(
                        eventId = 1,
                        state = OrderIntentState.CONFIRMED,
                        outcomeVolume = null,
                        expectedPrice = null,
                    ),
                )
                val projectedTrades = mutableListOf<com.gemini.krakenbot.model.TradeRecord>()
                coEvery {
                    reporting.upsertExecutionProjection(any(), any(), any(), any(), any())
                } coAnswers {
                    projectedTrades += arg<com.gemini.krakenbot.model.TradeRecord>(4)
                    true
                }

                val count = TradeProjectionServiceImpl(execution, reporting).projectPending()

                count shouldBe 1
                projectedTrades.single().volume.shouldBeEqualComparingTo(BigDecimal("0.025"))
                projectedTrades.single().expectedPrice.shouldNotBeNull()
                    .shouldBeEqualComparingTo(BigDecimal.ZERO)
                projectedTrades.single().price.shouldBeEqualComparingTo(BigDecimal("1000.00"))
            }
        }
    }

    private fun projection(
        eventId: Long,
        state: OrderIntentState,
        intentId: Int? = eventId.toInt(),
        orderTxid: String? = null,
        errorMessage: String? = null,
        outcomeVolume: BigDecimal? = BigDecimal("0.02"),
        expectedPrice: BigDecimal? = BigDecimal("1000.00"),
    ) = PendingTradeProjection(
        journalId = "journal-1",
        eventId = eventId,
        revision = 1,
        intent = OrderIntent(
            id = intentId,
            cycleId = "cycle-$eventId",
            clientOrderId = "client-$eventId",
            pair = "XBTUSD",
            symbol = Asset.BTC,
            side = if (state == OrderIntentState.REJECTED) "SELL" else "BUY",
            volume = BigDecimal("0.025"),
            usdAmount = BigDecimal("25.00"),
            expectedPrice = expectedPrice,
            createdAt = Instant.parse("2026-10-08T12:00:00Z").plusSeconds(eventId),
            state = state,
            orderTxid = orderTxid,
            errorMessage = errorMessage,
            outcomeVolume = outcomeVolume,
        ),
    )
}
