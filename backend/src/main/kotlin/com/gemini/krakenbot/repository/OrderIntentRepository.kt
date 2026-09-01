package com.gemini.krakenbot.repository

import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.RebalancerOrderIdentities
import java.time.Instant

interface OrderIntentRepository {
    suspend fun savePending(intent: OrderIntent): Int

    suspend fun recordOutcome(
        id: Int,
        state: OrderIntentState,
        orderTxid: String?,
        errorMessage: String?,
        resolvedAt: Instant?,
    ): Boolean

    suspend fun hasUnresolvedIntents(): Boolean

    suspend fun countUnresolvedIntents(): Long

    suspend fun loadUnresolvedIntents(): List<OrderIntent>

    suspend fun resolve(
        id: Int,
        state: OrderIntentState,
        evidence: String,
        resolvedAt: Instant,
        orderTxid: String? = null,
    ): Boolean

    suspend fun getKnownRebalancerOrderIdentities(
        orderTxids: Set<String>,
        clientOrderIds: Set<String>,
    ): RebalancerOrderIdentities
}
