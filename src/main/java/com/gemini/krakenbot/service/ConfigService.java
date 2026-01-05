package com.gemini.krakenbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gemini.krakenbot.config.Allocation;
import com.gemini.krakenbot.config.AppConfig;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import java.io.File;
import java.io.IOException;

@Service
public class ConfigService {

    private AppConfig appConfig;
    private final ObjectMapper objectMapper;

    public ConfigService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void loadConfig() throws IOException {
        File configFile = new File("rebalancer-config.json");
        if (!configFile.exists()) {
            throw new RuntimeException(
                    "Configuration file 'rebalancer-config.json' not found in the application directory.");
        }
        this.appConfig = objectMapper.readValue(configFile, AppConfig.class);
        validateConfig();
    }

    public AppConfig getConfig() {
        return appConfig;
    }

    private void validateConfig() {
        double totalPercent = appConfig.allocations().stream()
                .mapToDouble(Allocation::targetPercent)
                .sum();

        // Allow a tiny epsilon for float arithmetic, though requirements implied exact
        // 100%.
        // Strict 100 check:
        if (Math.abs(totalPercent - 100.0) > 0.001) {
            throw new RuntimeException(
                    "Total allocation percentage must be exactly 100%. Current sum: " + totalPercent);
        }

        boolean hasUsd = appConfig.allocations().stream()
                .anyMatch(a -> "USD".equalsIgnoreCase(a.symbol()));

        if (!hasUsd) {
            throw new RuntimeException("One asset must be USD.");
        }
    }
}
