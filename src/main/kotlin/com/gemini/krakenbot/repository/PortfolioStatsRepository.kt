package com.gemini.krakenbot.repository

import com.gemini.krakenbot.model.PortfolioStats

interface PortfolioStatsRepository {
    suspend fun load(): PortfolioStats

    suspend fun save(stats: PortfolioStats)
}
