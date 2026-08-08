package com.gemini.krakenbot.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

interface PortfolioManager {
    fun stopRebalancingLoop()

    fun startRebalancingLoop()

    fun startRebalancingLoop(scope: CoroutineScope): Job

    suspend fun runLoop()

    fun isLoopPaused(): Boolean

    fun pauseLoop()

    fun resumeLoop()
}
