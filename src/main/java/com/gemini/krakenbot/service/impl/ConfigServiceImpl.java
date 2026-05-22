package com.gemini.krakenbot.service.impl;

import org.springframework.beans.factory.annotation.Value;


import com.gemini.krakenbot.service.ConfigService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gemini.krakenbot.config.Allocation;
import com.gemini.krakenbot.config.AppConfig;
import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Service;
import java.io.File;
import java.io.IOException;

@Service
public class ConfigServiceImpl implements ConfigService {

    private AppConfig appConfig;
    private final ObjectMapper objectMapper;
    private final String configFilePath;

    public ConfigServiceImpl(ObjectMapper objectMapper,
            @Value("${app.config-file:rebalancer-config.json}") String configFilePath) {
        this.objectMapper = objectMapper;
        this.configFilePath = configFilePath;
    }

    @PostConstruct
    public void loadConfig() throws IOException {
        File configFile = new File(configFilePath);
        if (!configFile.exists()) {
            throw new RuntimeException(
                    "Configuration file 'rebalancer-config.json' not found in the application directory.");
        }
        this.appConfig = objectMapper.readValue(configFile, AppConfig.class);
        validateConfig(this.appConfig);
    }

    public synchronized AppConfig getConfig() {
        return appConfig;
    }

    public synchronized void updateConfig(AppConfig newConfig) {
        validateConfig(newConfig);
        this.appConfig = newConfig;
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(configFilePath), newConfig);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save configuration", e);
        }
    }

    private void validateConfig(AppConfig config) {
        double totalPercent = config.allocations().stream()
                .mapToDouble(Allocation::targetPercent)
                .sum();

        // Allow a tiny epsilon for float arithmetic, though requirements implied exact
        // 100%.
        // Strict 100 check:
        if (Math.abs(totalPercent - 100.0) > 0.001) {
            throw new RuntimeException(
                    "Total allocation percentage must be exactly 100%. Current sum: " + totalPercent);
        }

        boolean hasUsd = config.allocations().stream()
                .anyMatch(a -> "USD".equalsIgnoreCase(a.symbol()));

        if (!hasUsd) {
            throw new RuntimeException("One asset must be USD.");
        }
    }
}
