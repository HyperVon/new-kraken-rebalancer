package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.PortfolioSnapshot
import kotlinx.coroutines.flow.Flow

interface TradeHistoryService {
    fun init()
    fun addSnapshot(snapshot: PortfolioSnapshot)
    fun getHistory(): List<PortfolioSnapshot>
    fun getLatestSnapshot(): PortfolioSnapshot?
    fun getHistoryFlow(): Flow<PortfolioSnapshot>
}
