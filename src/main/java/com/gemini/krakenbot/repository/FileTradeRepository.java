package com.gemini.krakenbot.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gemini.krakenbot.model.PortfolioSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Repository
public class FileTradeRepository implements TradeRepository {
    private static final Logger log = LoggerFactory.getLogger(FileTradeRepository.class);
    private static final String FILE_PATH = "trade-history.json";
    private final ObjectMapper objectMapper;

    public FileTradeRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void save(List<PortfolioSnapshot> history) {
        try {
            objectMapper.writeValue(new File(FILE_PATH), history);
        } catch (IOException e) {
            log.error("Failed to save trade history to " + FILE_PATH, e);
        }
    }

    public List<PortfolioSnapshot> load() {
        File file = new File(FILE_PATH);
        if (!file.exists()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(file, new TypeReference<List<PortfolioSnapshot>>() {
            });
        } catch (Exception e) {
            log.error("Failed to load trade history from {}. Starting with empty history.", FILE_PATH, e);
            // Optional: Backup the corrupted file? For now just return empty.
            return new ArrayList<>();
        }
    }
}
