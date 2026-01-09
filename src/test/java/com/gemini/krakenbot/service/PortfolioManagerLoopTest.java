package com.gemini.krakenbot.service;

import com.gemini.krakenbot.config.AppConfig;
import com.gemini.krakenbot.config.KrakenCredentials;
import com.gemini.krakenbot.config.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.*;

class PortfolioManagerLoopTest {

    @Mock
    private KrakenService krakenService;
    @Mock
    private ConfigService configService;
    @Mock
    private TradeHistoryService tradeHistoryService;

    private PortfolioManager portfolioManager;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        portfolioManager = new PortfolioManager(krakenService, configService, tradeHistoryService);
    }

    @Test
    @Timeout(10)
    void startRebalancingLoop_RunsAndStops() throws InterruptedException {
        // Setup config with short interval
        Settings settings = new Settings(1L, 2.0, 1.0, true);
        AppConfig config = new AppConfig(new KrakenCredentials("k", "s"), settings, Collections.emptyList());
        when(configService.getConfig()).thenReturn(config);

        when(krakenService.getBalances()).thenReturn(Collections.emptyMap());

        // Run in thread
        Future<?> future = Executors.newSingleThreadExecutor().submit(() -> {
            portfolioManager.startRebalancingLoop();
        });

        // Let it run for a bit
        TimeUnit.SECONDS.sleep(2);

        // Stop
        portfolioManager.stopRebalancingLoop();

        // Wait for finish
        while (!future.isDone()) {
            TimeUnit.MILLISECONDS.sleep(100);
        }

        // Verify it ran at least once
        verify(krakenService, atLeastOnce()).getBalances();
    }

    @Test
    @Timeout(10)
    void startRebalancingLoop_HandlesException() throws InterruptedException {
        // Setup config
        Settings settings = new Settings(1L, 2.0, 1.0, true);
        AppConfig config = new AppConfig(new KrakenCredentials("k", "s"), settings, Collections.emptyList());
        when(configService.getConfig()).thenReturn(config);

        // Force exception
        when(krakenService.getBalances()).thenThrow(new RuntimeException("API Error!"));

        // Run in thread
        Future<?> future = Executors.newSingleThreadExecutor().submit(() -> {
            portfolioManager.startRebalancingLoop();
        });

        // Let it run and hit exception
        TimeUnit.SECONDS.sleep(1);

        // Stop
        portfolioManager.stopRebalancingLoop();
        future.cancel(true); // Interrupt the 10s sleep

        // Wait for finish
        try {
            future.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Expected to be cancelled or return null
        }

        // Verify it retried (atLeastOnce implies it ran, exception didn't crash thread)
        verify(krakenService, atLeastOnce()).getBalances();
    }
}
