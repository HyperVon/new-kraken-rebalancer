package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.repository.OrderIntentRepository
import com.gemini.krakenbot.service.OrderIntentService
import java.time.Instant

class OrderIntentServiceImpl(private val repository: OrderIntentRepository) : OrderIntentService {
    private companion object {
        const val MAX_RESOLUTION_EVIDENCE_LENGTH = 500
        const val MAX_ORDER_TXID_LENGTH = 64
    }

    override suspend fun savePending(intent: OrderIntent): Int = repository.savePending(
        intent.copy(state = OrderIntentState.PENDING),
    )

    override suspend fun recordOutcome(id: Int, result: OrderResult): Boolean {
        val state = when {
            result.submissionUncertain -> OrderIntentState.UNCERTAIN
            result.success -> OrderIntentState.CONFIRMED
            else -> OrderIntentState.REJECTED
        }
        return repository.recordOutcome(
            id = id,
            state = state,
            orderTxid = result.orderTxid,
            errorMessage = result.errorMessage,
            resolvedAt = if (state == OrderIntentState.UNCERTAIN) null else Instant.now(),
        )
    }

    override suspend fun hasUnresolvedIntents(): Boolean = repository.hasUnresolvedIntents()

    override suspend fun countUnresolvedIntents(): Long = repository.countUnresolvedIntents()

    override suspend fun getUnresolvedIntents(): List<OrderIntent> = repository.loadUnresolvedIntents()

    override suspend fun resolve(id: Int, state: OrderIntentState, evidence: String, orderTxid: String?) {
        require(state == OrderIntentState.CONFIRMED || state == OrderIntentState.REJECTED) {
            "Only CONFIRMED or REJECTED outcomes can resolve an order intent."
        }
        require(evidence.isNotBlank()) { "Resolution evidence is required." }
        require(evidence.length <= MAX_RESOLUTION_EVIDENCE_LENGTH) {
            "Resolution evidence must be at most $MAX_RESOLUTION_EVIDENCE_LENGTH characters."
        }
        val normalizedOrderTxid = orderTxid?.trim()?.takeIf(String::isNotEmpty)
        require(normalizedOrderTxid == null || normalizedOrderTxid.length <= MAX_ORDER_TXID_LENGTH) {
            "Order transaction id must be at most $MAX_ORDER_TXID_LENGTH characters."
        }
        check(repository.resolve(id, state, evidence.trim(), Instant.now(), normalizedOrderTxid)) {
            "Order intent $id is missing or already resolved."
        }
    }
}
