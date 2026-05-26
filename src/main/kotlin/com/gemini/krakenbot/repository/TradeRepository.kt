package com.gemini.krakenbot.repository

import com.gemini.krakenbot.model.PortfolioSnapshot

interface TradeRepository {
    fun save(history: List<PortfolioSnapshot>)
    fun load(): List<PortfolioSnapshot>
}
