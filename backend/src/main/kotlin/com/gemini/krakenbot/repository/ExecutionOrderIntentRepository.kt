package com.gemini.krakenbot.repository

import com.gemini.krakenbot.model.OrderIntent

data class PendingTradeProjection(
    val journalId: String,
    val eventId: Long,
    val revision: Long,
    val intent: OrderIntent,
)

interface ExecutionOrderIntentRepository : OrderIntentRepository {
    fun journalId(): String

    suspend fun recoverInterruptedSubmissions()

    suspend fun loadProjectionEvents(afterEventId: Long, limit: Int = 100): List<PendingTradeProjection>
}
