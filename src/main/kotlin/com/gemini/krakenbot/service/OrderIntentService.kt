package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.OrderResult

interface OrderIntentService {
    suspend fun savePending(intent: OrderIntent): Int

    suspend fun recordOutcome(id: Int, result: OrderResult)

    suspend fun hasUnresolvedIntents(): Boolean

    suspend fun countUnresolvedIntents(): Long

    suspend fun getUnresolvedIntents(): List<OrderIntent>

    suspend fun resolve(id: Int, state: OrderIntentState, evidence: String)
}
