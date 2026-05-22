package com.gemini.krakenbot.service;

import com.gemini.krakenbot.service.impl.*;

import com.gemini.krakenbot.config.Allocation;
import com.gemini.krakenbot.config.AppConfig;
import com.gemini.krakenbot.config.Settings;
import com.gemini.krakenbot.model.PortfolioSnapshot;
import com.gemini.krakenbot.model.PortfolioStats;
import com.gemini.krakenbot.repository.PortfolioStatsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class PortfolioManagerDrawdownTest {

    @Mock
    private KrakenService krakenService;
    @Mock
    private ConfigService configService;
    @Mock
    private TradeHistoryService tradeHistoryService;
    @Mock
    private PortfolioStatsRepository portfolioStatsRepository;
    @Mock
    private AppConfig appConfig;
    @Mock
    private Settings settings;

    @InjectMocks
    private PortfolioManagerImpl portfolioManager;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(configService.getConfig()).thenReturn(appConfig);
        when(appConfig.settings()).thenReturn(settings);

        // Default settings
        when(settings.loopDelaySeconds()).thenReturn(1L);
        when(settings.dryRun()).thenReturn(false);
        when(settings.deviationTriggerPercent()).thenReturn(2.0);
        when(settings.dustThresholdUSD()).thenReturn(1.0);
        when(settings.fiatMaxDrawdown()).thenReturn(50.0); // 50% Max Drawdown
        when(settings.fiatDeploymentExponent()).thenReturn(1.0); // Linear
    }

    @Test
    void testDrawdownAndFiatDeployment() {
        // 1. Setup Portfolio State
        // ATH = $2000
        // Current = $1000 (50% Drawdown)
        // With MaxDrawdown = 50%, we expect 100% Deployment.
        // Or let's do 25% Drawdown (Current $1500) -> 50% ratio -> 50% Deployment.

        when(portfolioStatsRepository.load()).thenReturn(new PortfolioStats(new BigDecimal("2000.0")));

        // Allocations: A=50%, USD=50%
        List<Allocation> allocs = List.of(
                new Allocation("A", 50.0),
                new Allocation("USD", 50.0));
        when(appConfig.allocations()).thenReturn(allocs);

        // Prices
        Map<String, Double> prices = new HashMap<>();
        prices.put("AUSD", 100.0);
        when(krakenService.getTickerPrices(anyString())).thenReturn(prices);

        // Balances:
        // A = 7.5 units ($750)
        // USD = $750
        // Total = $1500.
        // Drawdown = (2000 - 1500) / 2000 = 0.25 (25%).
        // MaxDD = 50. Ratio = 25/50 = 0.5.
        // Exponent = 1.0. Deploy = 0.5^1 * 100 = 50%.
        // Effective USD Target:
        // Base USD Target = 50%.
        // Factor = 1 - (50/100) = 0.5.
        // Effective USD Target = 50% * 0.5 = 25%.
        // Effective Crypto Target (A) = 100% - 25% = 75%?
        // No, logic in code only adjusts USD target. It DOES NOT automatically boost
        // others.
        // But target check calculates deviation.
        // USD Target Value = Total($1500) * 25% = $375.
        // Current USD = $750.
        // Deviation = $750 - $375 = +$375 (Overweight).
        // A Target Value = Total($1500) * 50% = $750.
        // Current A = $750.
        // Deviation = 0.
        // Logic: USD is +$375 overweight. It should trigger a BUY of something?
        // Code logic: "USD Triggered but no Crypto Triggered" ->
        // distributeFiatCorrection.
        // Note: A's deviation % is 0. So it won't be in buy/sell orders.
        // distributeFiatCorrection should be called.

        Map<String, Double> balances = new HashMap<>();
        balances.put("A", 7.5);
        balances.put("USD", 750.0);
        when(krakenService.getBalances()).thenReturn(balances);

        // Act
        ReflectionTestUtils.invokeMethod(portfolioManager, "performRebalanceCycle");

        // Assert
        // We expect USD to be considered overweight by $375, and that surplus to be put
        // into A.
        // A is the only candidate.
        // Executing BUY A for $375.
        // Vol = 375 / 100 = 3.75.

        verify(krakenService).executeOrder(eq("AUSD"), eq("market"), eq("buy"),
                doubleThat(v -> Math.abs(v - 3.75) < 0.01));

        // Also verify snapshot recorded values
        ArgumentCaptor<PortfolioSnapshot> captor = ArgumentCaptor.forClass(PortfolioSnapshot.class);
        verify(tradeHistoryService).addSnapshot(captor.capture());
        PortfolioSnapshot s = captor.getValue();

        assertEquals(25.0, s.getDrawdownPercent().doubleValue(), 0.01);
        assertEquals(50.0, s.getFiatDeploymentPercent().doubleValue(), 0.01);
        assertEquals(25.0, s.getEffectiveUsdTargetPercent().doubleValue(), 0.01);
    }

    @Test
    void testNewATH() {
        // Previous ATH = 1000. Current = 1500.
        // Should update ATH.
        PortfolioStats stats = new PortfolioStats(new BigDecimal("1000.0"));
        when(portfolioStatsRepository.load()).thenReturn(stats);

        List<Allocation> allocs = List.of(new Allocation("USD", 100.0));
        when(appConfig.allocations()).thenReturn(allocs);
        when(krakenService.getTickerPrices(anyString())).thenReturn(Collections.emptyMap());

        Map<String, Double> balances = new HashMap<>();
        balances.put("USD", 1500.0);
        when(krakenService.getBalances()).thenReturn(balances);

        ReflectionTestUtils.invokeMethod(portfolioManager, "performRebalanceCycle");

        verify(portfolioStatsRepository).save(stats);
        assertEquals(0, new BigDecimal("1500.0").compareTo(stats.getAllTimeHigh()));
    }
}
