package com.gemini.krakenbot.repository;

import com.gemini.krakenbot.model.PortfolioSnapshot;
import java.util.List;

public interface TradeRepository {
    void save(List<PortfolioSnapshot> history);

    List<PortfolioSnapshot> load();
}
