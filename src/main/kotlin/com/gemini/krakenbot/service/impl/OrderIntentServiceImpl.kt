package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.repository.OrderIntentRepository
import com.gemini.krakenbot.service.OrderIntentService
import java.time.Instant

class OrderIntentServiceImpl(private val repository: OrderIntentRepository) : OrderIntentService {
    override suspend fun savePending(intent: OrderIntent): Int = repository.savePending(
        intent.copy(state = OrderIntentState.PENDING),
    )

    override suspend fun recordOutcome(id: Int, result: OrderResult) {
        val state = when {
            result.submissionUncertain -> OrderIntentState.UNCERTAIN
            result.success -> OrderIntentState.CONFIRMED
            else -> OrderIntentState.REJECTED
        }
        repository.recordOutcome(
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

    override suspend fun resolve(id: Int, state: OrderIntentState, evidence: String) {
        require(state == OrderIntentState.CONFIRMED || state == OrderIntentState.REJECTED) {
            "Only CONFIRMED or REJECTED outcomes can resolve an order intent."
        }
        require(evidence.isNotBlank()) { "Resolution evidence is required." }
        check(repository.resolve(id, state, evidence.trim(), Instant.now())) {
            "Order intent $id is missing or already resolved."
        }
    }
}
