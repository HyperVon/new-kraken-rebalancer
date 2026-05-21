package com.gemini.krakenbot.repository;

import com.gemini.krakenbot.model.PortfolioStats;

public interface PortfolioStatsRepository {
    PortfolioStats load();

    void save(PortfolioStats stats);
}
