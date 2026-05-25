package com.gemini.krakenbot.repository

import com.gemini.krakenbot.model.PortfolioStats

interface PortfolioStatsRepository {
    fun load(): PortfolioStats
    fun save(stats: PortfolioStats)
}
