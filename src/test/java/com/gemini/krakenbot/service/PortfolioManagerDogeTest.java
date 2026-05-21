package com.gemini.krakenbot.service;

import java.math.BigDecimal;

import com.gemini.krakenbot.model.PortfolioStats;
import com.gemini.krakenbot.service.impl.*;

import com.gemini.krakenbot.config.Allocation;
import com.gemini.krakenbot.config.AppConfig;
import com.gemini.krakenbot.config.KrakenCredentials;
import com.gemini.krakenbot.config.Settings;
import com.gemini.krakenbot.repository.PortfolioStatsRepository;
import com.gemini.krakenbot.repository.impl.PortfolioStatsRepositoryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.*;

class PortfolioManagerDogeTest {

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
    void testDogeMapping() {
        // Setup config with DOGE
        Settings settings = new Settings(60L, 2.0, 1.0, true, 0.0, 1.0); // dryRun
        AppConfig config = new AppConfig(new KrakenCredentials("k", "s"), settings,
                List.of(new Allocation("DOGE", 50.0), new Allocation("USD", 50.0)));

        when(configService.getConfig()).thenReturn(config);

        // Mock balances
        when(krakenService.getBalances()).thenReturn(Map.of("XDG", 1000.0, "ZUSD", 500.0));

        // Mock ticker prices for mapped symbol XDGUSD
        // The service calls getTickerPrices with the constructed string
        when(krakenService.getTickerPrices(argThat(s -> s.contains("XDGUSD"))))
                .thenReturn(Map.of("XDGUSD", 0.10));

        ReflectionTestUtils.invokeMethod(portfolioManager, "performRebalanceCycle");

        // Verify getTickerPrices was called with XDGUSD
        verify(krakenService, atLeastOnce()).getTickerPrices(argThat(s -> s.contains("XDGUSD")));
    }

    @Test
    void testBtcMapping() {
        // Setup config with BTC
        Settings settings = new Settings(60L, 2.0, 1.0, true, 0.0, 1.0);
        AppConfig config = new AppConfig(new KrakenCredentials("k", "s"), settings,
                List.of(new Allocation("BTC", 50.0), new Allocation("USD", 50.0)));

        when(configService.getConfig()).thenReturn(config);
        when(krakenService.getBalances()).thenReturn(Map.of("XXBT", 1.0, "ZUSD", 50000.0));
        when(krakenService.getTickerPrices(argThat(s -> s.contains("XXBTZUSD") || s.contains("XBTUSD"))))
                .thenReturn(Map.of("XXBTZUSD", 50000.0));

        ReflectionTestUtils.invokeMethod(portfolioManager, "performRebalanceCycle");

        // Verify
        verify(krakenService, atLeastOnce()).getTickerPrices(argThat(s -> s.contains("XBT") || s.contains("XXBT")));
    }
}
