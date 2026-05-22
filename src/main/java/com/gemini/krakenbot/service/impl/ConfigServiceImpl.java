package com.gemini.krakenbot.service.impl;

import org.springframework.beans.factory.annotation.Value;


import com.gemini.krakenbot.service.ConfigService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gemini.krakenbot.config.Allocation;
import com.gemini.krakenbot.config.AppConfig;
import com.gemini.krakenbot.config.Settings;
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
        if (config.settings() == null) {
            throw new RuntimeException("Settings cannot be null.");
        }
        Settings settings = config.settings();
        if (settings.loopDelaySeconds() <= 0) {
            throw new RuntimeException("Loop delay must be a positive integer.");
        }
        if (settings.deviationTriggerPercent() < 0) {
            throw new RuntimeException("Deviation trigger percent must be non-negative.");
        }
        if (settings.dustThresholdUSD() != null && settings.dustThresholdUSD() < 0) {
            throw new RuntimeException("Dust threshold USD must be non-negative.");
        }
        if (settings.fiatMaxDrawdown() != null && (settings.fiatMaxDrawdown() < 0 || settings.fiatMaxDrawdown() > 100)) {
            throw new RuntimeException("Fiat max drawdown must be between 0% and 100%.");
        }
        if (settings.fiatDeploymentExponent() != null && settings.fiatDeploymentExponent() <= 0) {
            throw new RuntimeException("Fiat deployment exponent must be positive.");
        }

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
