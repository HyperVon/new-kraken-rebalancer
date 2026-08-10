package com.gemini.krakenbot.repository

import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentState
import java.time.Instant

interface OrderIntentRepository {
    suspend fun savePending(intent: OrderIntent): Int

    suspend fun recordOutcome(
        id: Int,
        state: OrderIntentState,
        orderTxid: String?,
        errorMessage: String?,
        resolvedAt: Instant?,
    )

    suspend fun hasUnresolvedIntents(): Boolean

    suspend fun countUnresolvedIntents(): Long

    suspend fun loadUnresolvedIntents(): List<OrderIntent>

    suspend fun resolve(id: Int, state: OrderIntentState, evidence: String, resolvedAt: Instant): Boolean
}
