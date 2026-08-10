package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.OrderResult

interface OrderIntentService {
    suspend fun savePending(intent: OrderIntent): Int

    /** Returns false when a prior operator resolution already won the race. */
    suspend fun recordOutcome(id: Int, result: OrderResult): Boolean

    suspend fun hasUnresolvedIntents(): Boolean

    suspend fun countUnresolvedIntents(): Long

    suspend fun getUnresolvedIntents(): List<OrderIntent>

    suspend fun resolve(id: Int, state: OrderIntentState, evidence: String, orderTxid: String? = null)
}
