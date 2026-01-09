package com.gemini.krakenbot.service;

import com.gemini.krakenbot.model.PortfolioSnapshot;
import com.gemini.krakenbot.repository.TradeRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class TradeHistoryService {

    // Store last 50 snapshots
    private final List<PortfolioSnapshot> history = new CopyOnWriteArrayList<>();
    private static final int MAX_HISTORY_SIZE = 50;

    private final TradeRepository repository;

    public TradeHistoryService(TradeRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        List<PortfolioSnapshot> loaded = repository.load();
        if (loaded != null) {
            history.addAll(loaded);
        }
    }

    public void addSnapshot(PortfolioSnapshot snapshot) {
        history.add(0, snapshot);
        if (history.size() > MAX_HISTORY_SIZE) {
            history.remove(history.size() - 1);
        }
        repository.save(new ArrayList<>(history));
    }

    public List<PortfolioSnapshot> getHistory() {
        return new ArrayList<>(history);
    }

    public PortfolioSnapshot getLatestSnapshot() {
        if (history.isEmpty())
            return null;
        return history.get(0);
    }
}
