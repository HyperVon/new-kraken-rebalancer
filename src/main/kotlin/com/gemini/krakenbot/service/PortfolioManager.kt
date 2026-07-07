package com.gemini.krakenbot.service

import kotlinx.coroutines.flow.Flow

interface PortfolioManager {
    fun stopRebalancingLoop()
    fun startRebalancingLoop()
    suspend fun runLoop()
    
    /**
     * Returns a Flow of rebalancing cycle events for event-driven monitoring.
     * Use this to subscribe to rebalancing lifecycle events.
     */
    fun getRebalanceCycleFlow(): Flow<RebalanceEvent>
}
