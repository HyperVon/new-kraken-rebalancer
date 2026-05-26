package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.PortfolioSnapshot

interface TradeHistoryService {
    fun init()
    fun addSnapshot(snapshot: PortfolioSnapshot)
    fun getHistory(): List<PortfolioSnapshot>
    fun getLatestSnapshot(): PortfolioSnapshot?
}
