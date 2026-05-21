package com.gemini.krakenbot.repository.impl;

import com.gemini.krakenbot.repository.PortfolioStatsRepository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gemini.krakenbot.model.PortfolioStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;

@Repository
public class PortfolioStatsRepositoryImpl implements PortfolioStatsRepository {

    private static final Logger log = LoggerFactory.getLogger(PortfolioStatsRepositoryImpl.class);
    private static final String FILE_PATH = "portfolio-stats.json";
    private final ObjectMapper objectMapper;

    public PortfolioStatsRepositoryImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public PortfolioStats load() {
        File file = new File(FILE_PATH);
        if (file.exists()) {
            try {
                return objectMapper.readValue(file, PortfolioStats.class);
            } catch (IOException e) {
                log.error("Failed to load portfolio stats", e);
            }
        }
        // Return default with 0 ATH if new
        return new PortfolioStats(BigDecimal.ZERO);
    }

    @Override
    public void save(PortfolioStats stats) {
        try {
            objectMapper.writeValue(new File(FILE_PATH), stats);
        } catch (IOException e) {
            log.error("Failed to save portfolio stats", e);
        }
    }
}
