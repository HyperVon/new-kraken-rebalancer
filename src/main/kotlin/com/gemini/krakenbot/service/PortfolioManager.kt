package com.gemini.krakenbot.service

interface PortfolioManager {
    fun stopRebalancingLoop()
    fun startRebalancingLoop()
    suspend fun runLoop()
}
