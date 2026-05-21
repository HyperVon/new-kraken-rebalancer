package com.gemini.krakenbot.service;

import com.gemini.krakenbot.model.PortfolioSnapshot;
import java.util.List;

public interface TradeHistoryService {

    void init();
    void addSnapshot(PortfolioSnapshot snapshot);
    List<PortfolioSnapshot> getHistory();
    PortfolioSnapshot getLatestSnapshot();

}
