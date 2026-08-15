package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentReconciliationException
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.repository.OrderIntentRepository
import com.gemini.krakenbot.service.OrderIntentService
import java.io.IOException
import java.time.Instant

class OrderIntentServiceImpl(private val repository: OrderIntentRepository) : OrderIntentService {
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
        val normalizedOrderTxid = orderTxid?.trim()?.takeIf(String::isNotEmpty)
        try {
            check(repository.resolve(id, state, evidence.trim(), Instant.now(), normalizedOrderTxid)) {
                "Order intent $id is missing or already resolved."
            }
        } catch (e: IOException) {
            val reconciliationFailure = generateSequence(e.cause) { it.cause }
                .filterIsInstance<OrderIntentReconciliationException>()
                .firstOrNull()
            if (reconciliationFailure != null) {
                throw reconciliationFailure
            }
            throw e
        }
    }
}
