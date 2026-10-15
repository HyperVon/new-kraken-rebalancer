package com.gemini.krakenbot.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.time.Instant

data class RebalanceOperationalStatus(
    val lastCycleStartedAt: Instant? = null,
    val lastCycleCompletedAt: Instant? = null,
    val lastCycleError: String? = null,
)

interface PortfolioManager {
    fun stopRebalancingLoop(): Job?

    fun startRebalancingLoop()

    fun startRebalancingLoop(scope: CoroutineScope): Job

    suspend fun runLoop()

    fun isLoopPaused(): Boolean

    fun isLoopRunning(): Boolean = false

    fun isCycleActive(): Boolean = false

    /** Runs one bounded History fetch/commit only when no rebalance cycle owns admission. */
    suspend fun runHistoryPageIfAdmitted(block: suspend () -> Unit): Boolean {
        if (isCycleActive()) return false
        block()
        return true
    }

    fun getOperationalStatus(): RebalanceOperationalStatus = RebalanceOperationalStatus()

    fun pauseLoop()

    fun resumeLoop()
}
