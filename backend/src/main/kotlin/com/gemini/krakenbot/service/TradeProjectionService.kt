package com.gemini.krakenbot.service

interface TradeProjectionService {
    /** Projects a bounded batch of immutable execution events using the reporting-side cursor. */
    suspend fun projectPending(limit: Int = 100): Int
}
