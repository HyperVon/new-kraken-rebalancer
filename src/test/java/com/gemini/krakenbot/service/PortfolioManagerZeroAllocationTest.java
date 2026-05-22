package com.gemini.krakenbot.service;

import java.math.BigDecimal;

import com.gemini.krakenbot.model.PortfolioStats;
import com.gemini.krakenbot.service.impl.*;

import com.gemini.krakenbot.config.Allocation;
import com.gemini.krakenbot.config.AppConfig;
import com.gemini.krakenbot.config.Settings;
import com.gemini.krakenbot.repository.PortfolioStatsRepository;
import com.gemini.krakenbot.repository.impl.PortfolioStatsRepositoryImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;

class PortfolioManagerZeroAllocationTest {

    @Test
    void testZeroAllocationToOtherAssetRebalance() {
        // Setup
        KrakenService krakenService = mock(KrakenService.class);
        ConfigService configService = mock(ConfigService.class);
        TradeHistoryService tradeHistoryService = mock(TradeHistoryService.class);

        PortfolioStatsRepository repo = mock(PortfolioStatsRepositoryImpl.class);
        when(repo.load())
                .thenReturn(new PortfolioStats(BigDecimal.ZERO));
        PortfolioManagerImpl portfolioManager = new PortfolioManagerImpl(krakenService, configService, tradeHistoryService,
                repo);

        // Config Mock
        Allocation allocA = new Allocation("A", 0.0); // 0% Target (Has funds)
        Allocation allocB = new Allocation("B", 100.0); // 100% Target (Empty)

        List<Allocation> allAllocations = List.of(allocA, allocB);
        AppConfig mockConfig = mock(AppConfig.class);
        Settings mockSettings = mock(Settings.class);

        when(mockConfig.allocations()).thenReturn(allAllocations);
        when(mockConfig.settings()).thenReturn(mockSettings);
        when(mockSettings.deviationTriggerPercent()).thenReturn(2.0); // 2% trigger
        when(mockSettings.dustThresholdUSD()).thenReturn(1.0);

        when(configService.getConfig()).thenReturn(mockConfig);

        // Balances Mock
        Map<String, Double> balances = new HashMap<>();
        balances.put("A", 10.0); // $1000
        balances.put("B", 0.0); // $0
        balances.put("USD", 100.0); // Small cash buffer
        when(krakenService.getBalances()).thenReturn(balances);

        // Prices Mock
        Map<String, Double> prices = new HashMap<>();
        prices.put("AUSD", 100.0); // Price of A is $100
        prices.put("BUSD", 50.0); // Price of B is $50
        when(krakenService.getTickerPrices(anyString())).thenReturn(prices);

        // Invoke private method
        ReflectionTestUtils.invokeMethod(portfolioManager, "performRebalanceCycle");

        // Verify
        // Expect SELL for Asset A (to free up funds for B)
        // A has $1000 val, Target 0. Should sell ~10 units.
        verify(krakenService, times(1)).executeOrder(eq("AUSD"), eq("market"), eq("sell"), anyDouble());
    }
}
