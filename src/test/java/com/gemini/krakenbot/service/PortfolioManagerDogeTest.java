package com.gemini.krakenbot.service;

import com.gemini.krakenbot.config.Allocation;
import com.gemini.krakenbot.config.AppConfig;
import com.gemini.krakenbot.config.KrakenCredentials;
import com.gemini.krakenbot.config.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;

class PortfolioManagerDogeTest {

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
    void testDogeMapping() {
        // Setup config with DOGE
        Settings settings = new Settings(60L, 2.0, 1.0, true); // dryRun
        AppConfig config = new AppConfig(new KrakenCredentials("k", "s"), settings,
                List.of(new Allocation("DOGE", 50.0), new Allocation("USD", 50.0)));

        when(configService.getConfig()).thenReturn(config);

        // Mock balances
        when(krakenService.getBalances()).thenReturn(Map.of("XDG", 1000.0, "ZUSD", 500.0));

        // Mock ticker prices for mapped symbol XDGUSD
        // The service calls getTickerPrices with the constructed string
        when(krakenService.getTickerPrices(argThat(s -> s.contains("XDGUSD"))))
                .thenReturn(Map.of("XDGUSD", 0.10));

        // Start cycle (we can call performRebalanceCycle if it was public, or use
        // startRebalancingLoop logic.
        // But performRebalanceCycle is private.
        // We can use startRebalancingLoop in a thread or...
        // Wait, performRebalanceCycle IS private.
        // But I made startRebalancingLoop testable with stop().
        // So I can run it for one cycle.

        // Or I can reflectively call `performRebalanceCycle` or better, rely on
        // startRebalancingLoop with 0 delay or logic.
        // Actually, if I call startRebalancingLoop, it runs once then sleeps.
        // If I make loopDelay 0?
        // sleep(0) might still yield.

        // Let's use the thread approach but ensure we verify the call.

        new Thread(() -> {
            try {
                // slight delay to let it start? No needed if we stop after
                Thread.sleep(100);
                portfolioManager.stopRebalancingLoop();
            } catch (Exception e) {
            }
        }).start();

        portfolioManager.startRebalancingLoop();

        // Verify getTickerPrices was called with XDGUSD
        verify(krakenService, atLeastOnce()).getTickerPrices(argThat(s -> s.contains("XDGUSD")));
    }

    @Test
    void testBtcMapping() {
        // Setup config with BTC
        Settings settings = new Settings(60L, 2.0, 1.0, true);
        AppConfig config = new AppConfig(new KrakenCredentials("k", "s"), settings,
                List.of(new Allocation("BTC", 50.0), new Allocation("USD", 50.0)));

        when(configService.getConfig()).thenReturn(config);
        when(krakenService.getBalances()).thenReturn(Map.of("XXBT", 1.0, "ZUSD", 50000.0));
        when(krakenService.getTickerPrices(argThat(s -> s.contains("XXBTZUSD") || s.contains("XBTUSD"))))
                .thenReturn(Map.of("XXBTZUSD", 50000.0));

        new Thread(() -> {
            try {
                Thread.sleep(100);
                portfolioManager.stopRebalancingLoop();
            } catch (Exception e) {
            }
        }).start();

        portfolioManager.startRebalancingLoop();

        // Verify
        verify(krakenService, atLeastOnce()).getTickerPrices(argThat(s -> s.contains("XBT") || s.contains("XXBT")));
    }
}
