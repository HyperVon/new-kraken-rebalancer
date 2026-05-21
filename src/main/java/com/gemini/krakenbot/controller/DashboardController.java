package com.gemini.krakenbot.controller;

import lombok.RequiredArgsConstructor;


import com.gemini.krakenbot.config.AppConfig;
import com.gemini.krakenbot.config.KrakenCredentials;
import com.gemini.krakenbot.model.PortfolioSnapshot;
import com.gemini.krakenbot.service.ConfigService;
import com.gemini.krakenbot.service.TradeHistoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")

@RequiredArgsConstructor
public class DashboardController {

    private final TradeHistoryService tradeHistoryService;
    private final ConfigService configService;

    

    @GetMapping("/status")
    public PortfolioSnapshot getStatus() {
        return tradeHistoryService.getLatestSnapshot();
    }

    @GetMapping("/history")
    public List<PortfolioSnapshot> getHistory() {
        return tradeHistoryService.getHistory();
    }

    @GetMapping("/config")
    public FrontendConfig getConfig() {
        AppConfig config = configService.getConfig();
        return new FrontendConfig(config.settings(), config.allocations());
    }

    @PostMapping("/config")
    public FrontendConfig updateConfig(@RequestBody FrontendConfig config) {
        // Preserve server-side credentials — the client never has them
        KrakenCredentials serverCredentials = configService.getConfig().kraken();
        AppConfig configWithCredentials = new AppConfig(serverCredentials, config.settings(), config.allocations());
        configService.updateConfig(configWithCredentials);
        // Return sanitized config
        AppConfig updated = configService.getConfig();
        return new FrontendConfig(updated.settings(), updated.allocations());
    }
}
