package com.gemini.krakenbot.service;

import java.math.BigDecimal;

import com.gemini.krakenbot.model.PortfolioStats;
import com.gemini.krakenbot.service.impl.*;

import com.gemini.krakenbot.config.AppConfig;
import com.gemini.krakenbot.config.KrakenCredentials;
import com.gemini.krakenbot.config.Settings;
import com.gemini.krakenbot.repository.PortfolioStatsRepository;
import com.gemini.krakenbot.repository.impl.PortfolioStatsRepositoryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;

import static org.mockito.Mockito.*;

class PortfolioManagerLoopTest {

    @Mock
    private KrakenService krakenService;
    @Mock
    private ConfigService configService;
    @Mock
    private TradeHistoryService tradeHistoryService;

    private PortfolioManagerImpl portfolioManager;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        PortfolioStatsRepository repo = mock(PortfolioStatsRepositoryImpl.class);
        when(repo.load()).thenReturn(new PortfolioStats(BigDecimal.ZERO));
        portfolioManager = new PortfolioManagerImpl(krakenService, configService, tradeHistoryService, repo);
    }

    @Test
    void startRebalancingLoop_RunsWhenEnabled() {
        // Setup config with 0 interval to bypass delay check
        Settings settings = new Settings(0L, 2.0, 1.0, true, 0.0, 1.0);
        AppConfig config = new AppConfig(new KrakenCredentials("k", "s"), settings, Collections.emptyList());
        when(configService.getConfig()).thenReturn(config);
        when(krakenService.getBalances()).thenReturn(Collections.emptyMap());

        // Start the loop (sets isRunning = true)
        portfolioManager.startRebalancingLoop();

        // Simulate scheduled tick
        portfolioManager.checkAndRunCycle();

        // Verify it ran
        verify(krakenService, times(1)).getBalances();
    }

    @Test
    void stopRebalancingLoop_StopsExecution() {
        // Setup config with 0 interval
        Settings settings = new Settings(0L, 2.0, 1.0, true, 0.0, 1.0);
        AppConfig config = new AppConfig(new KrakenCredentials("k", "s"), settings, Collections.emptyList());
        when(configService.getConfig()).thenReturn(config);

        // Start and then Stop immediately
        portfolioManager.startRebalancingLoop();
        portfolioManager.stopRebalancingLoop();

        // Simulate scheduled tick
        portfolioManager.checkAndRunCycle();

        // Verify it DID NOT run
        verify(krakenService, never()).getBalances();
    }

    @Test
    void checkAndRunCycle_HandlesExceptionGracefully() {
        // Setup config with 0 interval
        Settings settings = new Settings(0L, 2.0, 1.0, true, 0.0, 1.0);
        AppConfig config = new AppConfig(new KrakenCredentials("k", "s"), settings, Collections.emptyList());
        when(configService.getConfig()).thenReturn(config);

        // Force exception
        when(krakenService.getBalances()).thenThrow(new RuntimeException("API Error!"));

        // Start
        portfolioManager.startRebalancingLoop();

        // Simulate tick - Should not throw exception out of method
        portfolioManager.checkAndRunCycle();

        // Verify it hit the method
        verify(krakenService, times(1)).getBalances();
    }
}
