package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.domain.TradeCalculator
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.OrderSubmissionState
import com.gemini.krakenbot.repository.ExecutionOrderIntentRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.TradeProjectionService

class TradeProjectionServiceImpl(
    private val executionRepository: ExecutionOrderIntentRepository,
    private val tradeRepository: TradeRepository,
) : TradeProjectionService {
    override suspend fun projectPending(limit: Int): Int {
        var projected = 0
        val cursor = tradeRepository.getExecutionProjectionCursor(executionRepository.journalId())
        for (projection in executionRepository.loadProjectionEvents(cursor, limit)) {
            val intent = projection.intent
            val intentId = requireNotNull(intent.id) { "Projection outbox row has no execution intent ID." }
            val volume = intent.outcomeVolume ?: intent.volume
            val result = when (intent.state) {
                OrderIntentState.CONFIRMED -> OrderResult.Success(
                    pair = intent.pair,
                    side = intent.side,
                    volume = volume,
                    dryRun = false,
                    orderTxid = intent.orderTxid,
                )

                OrderIntentState.REJECTED -> OrderResult.Failure(
                    pair = intent.pair,
                    side = intent.side,
                    volume = volume,
                    dryRun = false,
                    errorMessage = intent.errorMessage ?: "Kraken rejected the order.",
                    orderTxid = intent.orderTxid,
                )

                OrderIntentState.UNCERTAIN -> OrderResult.Failure(
                    pair = intent.pair,
                    side = intent.side,
                    volume = volume,
                    dryRun = false,
                    errorMessage = intent.errorMessage ?: "Order submission outcome is uncertain.",
                    orderTxid = intent.orderTxid,
                    submissionUncertain = true,
                )

                OrderIntentState.PENDING -> continue
            }
            val record = TradeCalculator.createTradeRecord(
                result = result,
                symbol = intent.symbol,
                pair = intent.pair,
                side = intent.side,
                volume = volume,
                usdAmount = intent.usdAmount,
                prices = intent.expectedPrice?.let { mapOf(intent.symbol to it) } ?: emptyMap(),
                timestamp = intent.createdAt,
                cycleId = intent.cycleId,
            ).copy(
                clientOrderId = intent.clientOrderId,
                submissionState = if (intent.state == OrderIntentState.UNCERTAIN) {
                    OrderSubmissionState.UNCERTAIN
                } else {
                    null
                },
            )
            if (tradeRepository.upsertExecutionProjection(
                    journalId = projection.journalId,
                    eventId = projection.eventId,
                    intentId = intentId,
                    legacyTradeId = intent.legacyTradeId,
                    trade = record,
                )
            ) {
                projected += 1
            }
        }
        return projected
    }
}
