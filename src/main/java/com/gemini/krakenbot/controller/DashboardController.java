package com.gemini.krakenbot.controller;

import com.gemini.krakenbot.config.AppConfig;
import com.gemini.krakenbot.model.PortfolioSnapshot;
import com.gemini.krakenbot.service.ConfigService;
import com.gemini.krakenbot.service.TradeHistoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class DashboardController {

    private final TradeHistoryService tradeHistoryService;
    private final ConfigService configService;

    public DashboardController(TradeHistoryService tradeHistoryService, ConfigService configService) {
        this.tradeHistoryService = tradeHistoryService;
        this.configService = configService;
    }

    @GetMapping("/status")
    public PortfolioSnapshot getStatus() {
        return tradeHistoryService.getLatestSnapshot();
    }

    @GetMapping("/history")
    public List<PortfolioSnapshot> getHistory() {
        return tradeHistoryService.getHistory();
    }

    @GetMapping("/config")
    public AppConfig getConfig() {
        return configService.getConfig();
    }

    @org.springframework.web.bind.annotation.PostMapping("/config")
    public AppConfig updateConfig(@org.springframework.web.bind.annotation.RequestBody AppConfig config) {
        configService.updateConfig(config);
        return configService.getConfig();
    }
}
