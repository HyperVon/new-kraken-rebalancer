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
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;

class PortfolioManagerOrderExecutionTest {

        @Test
        void testExecutionOrder_SellsBeforeBuys() {
                // Setup
                KrakenService krakenService = mock(KrakenService.class);
                ConfigService configService = mock(ConfigService.class);
                TradeHistoryService tradeHistoryService = mock(TradeHistoryService.class);

                PortfolioStatsRepository repo = mock(PortfolioStatsRepositoryImpl.class);
                when(repo.load())
                                .thenReturn(new PortfolioStats(BigDecimal.ZERO));
                PortfolioManagerImpl portfolioManager = new PortfolioManagerImpl(krakenService, configService,
                                tradeHistoryService,
                                repo);

                // Config Mock
                // Asset A: Overweight -> Needs Sell
                // Asset B: Underweight -> Needs Buy
                Allocation allocA = new Allocation("A", 10.0);
                Allocation allocB = new Allocation("B", 90.0);
                Allocation allocUSD = new Allocation("USD", 0.0); // No cash target

                List<Allocation> allAllocations = List.of(allocA, allocB, allocUSD);
                AppConfig mockConfig = mock(AppConfig.class);
                Settings mockSettings = mock(Settings.class);

                when(mockConfig.allocations()).thenReturn(allAllocations);
                when(mockConfig.settings()).thenReturn(mockSettings);
                when(mockSettings.deviationTriggerPercent()).thenReturn(1.0); // 1% trigger
                when(mockSettings.dustThresholdUSD()).thenReturn(1.0);

                when(configService.getConfig()).thenReturn(mockConfig);

                // Balances Mock
                // Total Value = $1000
                // A: has $500 (50%) -> Target 10% ($100) -> Sell $400
                // B: has $500 (50%) -> Target 90% ($900) -> Buy $400
                // USD: $0
                Map<String, Double> balances = new HashMap<>();
                balances.put("A", 5.0); // Price 100 -> $500
                balances.put("B", 50.0); // Price 10 -> $500
                balances.put("USD", 0.0);
                when(krakenService.getBalances()).thenReturn(balances);

                // Prices Mock
                Map<String, Double> prices = new HashMap<>();
                prices.put("AUSD", 100.0);
                prices.put("BUSD", 10.0);
                when(krakenService.getTickerPrices(anyString())).thenReturn(prices);

                // Invoke private method
                ReflectionTestUtils.invokeMethod(portfolioManager, "performRebalanceCycle");

                // Verify Order
                InOrder inOrder = inOrder(krakenService);

                // 1. Should call Sell for A
                inOrder.verify(krakenService).executeOrder(eq("AUSD"), eq("market"), eq("sell"), anyDouble());

                // 2. Should call Buy for B
                // Note: It will only buy B if it thinks it has cash.
                // The code adds sold value to projectedCash immediately.
                // A Sell $400 -> Projected Cash $400 -> Buy B $400.
                inOrder.verify(krakenService).executeOrder(eq("BUSD"), eq("market"), eq("buy"), anyDouble());
        }

        @Test
        void testExecution_SkipDustSells() {
                // Setup
                KrakenService krakenService = mock(KrakenService.class);
                ConfigService configService = mock(ConfigService.class);
                TradeHistoryService tradeHistoryService = mock(TradeHistoryService.class);

                PortfolioStatsRepository repo = mock(PortfolioStatsRepositoryImpl.class);
                when(repo.load())
                                .thenReturn(new PortfolioStats(BigDecimal.ZERO));

                PortfolioManagerImpl portfolioManager = new PortfolioManagerImpl(krakenService, configService,
                                tradeHistoryService,
                                repo);

                // Config Mock
                // Asset A: Overweight -> Needs Sell
                Allocation allocA = new Allocation("A", 10.0);
                Allocation allocUSD = new Allocation("USD", 90.0);

                List<Allocation> allAllocations = List.of(allocA, allocUSD);
                AppConfig mockConfig = mock(AppConfig.class);
                Settings mockSettings = mock(Settings.class);

                when(mockConfig.allocations()).thenReturn(allAllocations);
                when(mockConfig.settings()).thenReturn(mockSettings);
                when(mockSettings.deviationTriggerPercent()).thenReturn(0.1);
                when(mockSettings.dustThresholdUSD()).thenReturn(10.0); // Threshold 10

                when(configService.getConfig()).thenReturn(mockConfig);

                // Balances Mock
                // Total Value = $1000
                // A: has $105 (10.5%) -> Target 10% ($100) -> Sell $5. (5 < 10) -> Skip
                // USD: $895
                Map<String, Double> balances = new HashMap<>();
                balances.put("A", 1.05); // Price 100 -> $105
                balances.put("USD", 895.0);
                when(krakenService.getBalances()).thenReturn(balances);

                // Prices Mock
                Map<String, Double> prices = new HashMap<>();
                prices.put("AUSD", 100.0);
                when(krakenService.getTickerPrices(anyString())).thenReturn(prices);

                // Invoke private method
                ReflectionTestUtils.invokeMethod(portfolioManager, "performRebalanceCycle");

                // Verify NO Sell
                verify(krakenService, never()).executeOrder(eq("AUSD"), anyString(), eq("sell"), anyDouble());
        }

        @Test
        void testExecution_CashVerificationFallback() {
                // Setup
                KrakenService krakenService = mock(KrakenService.class);
                ConfigService configService = mock(ConfigService.class);
                TradeHistoryService tradeHistoryService = mock(TradeHistoryService.class);

                PortfolioStatsRepository repo = mock(PortfolioStatsRepositoryImpl.class);
                when(repo.load())
                                .thenReturn(new PortfolioStats(BigDecimal.ZERO));
                PortfolioManagerImpl portfolioManager = new PortfolioManagerImpl(krakenService, configService,
                                tradeHistoryService, repo);

                List<Allocation> allAllocations = List.of(new Allocation("A", 10.0), new Allocation("B", 90.0));
                AppConfig mockConfig = mock(AppConfig.class);
                Settings mockSettings = mock(Settings.class);
                when(mockConfig.allocations()).thenReturn(allAllocations);
                when(mockConfig.settings()).thenReturn(mockSettings);
                when(mockSettings.deviationTriggerPercent()).thenReturn(1.0);
                when(mockSettings.dustThresholdUSD()).thenReturn(1.0);
                when(configService.getConfig()).thenReturn(mockConfig);

                // Initial balances
                Map<String, Double> initialBalances = new HashMap<>();
                initialBalances.put("A", 5.0); // $500
                initialBalances.put("B", 50.0); // $500
                initialBalances.put("USD", 0.0);

                // First call: returns initial balances
                // Second call (verification): throws Exception
                when(krakenService.getBalances())
                    .thenReturn(initialBalances)
                    .thenThrow(new RuntimeException("API Error during verification!"));

                Map<String, Double> prices = new HashMap<>();
                prices.put("AUSD", 100.0);
                prices.put("BUSD", 10.0);
                when(krakenService.getTickerPrices(anyString())).thenReturn(prices);

                // Execute
                ReflectionTestUtils.invokeMethod(portfolioManager, "performRebalanceCycle");

                // It should fall back to projectedCash ($400 from sell A) and buy B
                verify(krakenService).executeOrder(eq("AUSD"), eq("market"), eq("sell"), anyDouble());
                verify(krakenService).executeOrder(eq("BUSD"), eq("market"), eq("buy"), anyDouble());
        }

        @Test
        void testExecution_PartialFillCashUpdate() {
                KrakenService krakenService = mock(KrakenService.class);
                ConfigService configService = mock(ConfigService.class);
                TradeHistoryService tradeHistoryService = mock(TradeHistoryService.class);

                PortfolioStatsRepository repo = mock(PortfolioStatsRepositoryImpl.class);
                when(repo.load())
                                .thenReturn(new PortfolioStats(BigDecimal.ZERO));
                PortfolioManagerImpl portfolioManager = new PortfolioManagerImpl(krakenService, configService,
                                tradeHistoryService, repo);

                List<Allocation> allAllocations = List.of(new Allocation("A", 10.0), new Allocation("B", 90.0));
                AppConfig mockConfig = mock(AppConfig.class);
                Settings mockSettings = mock(Settings.class);
                when(mockConfig.allocations()).thenReturn(allAllocations);
                when(mockConfig.settings()).thenReturn(mockSettings);
                when(mockSettings.deviationTriggerPercent()).thenReturn(1.0);
                when(mockSettings.dustThresholdUSD()).thenReturn(1.0);
                when(configService.getConfig()).thenReturn(mockConfig);

                Map<String, Double> initialBalances = new HashMap<>();
                initialBalances.put("A", 5.0); // $500 -> sell $400
                initialBalances.put("B", 50.0); // $500 -> buy $400
                initialBalances.put("USD", 0.0);

                Map<String, Double> updatedBalances = new HashMap<>();
                updatedBalances.put("A", 2.0); // sold some
                updatedBalances.put("B", 50.0); 
                updatedBalances.put("USD", 200.0); // Only got $200! (Partial fill/slippage)

                when(krakenService.getBalances())
                    .thenReturn(initialBalances)
                    .thenReturn(updatedBalances);

                Map<String, Double> prices = new HashMap<>();
                prices.put("AUSD", 100.0);
                prices.put("BUSD", 10.0);
                when(krakenService.getTickerPrices(anyString())).thenReturn(prices);

                ReflectionTestUtils.invokeMethod(portfolioManager, "performRebalanceCycle");

                verify(krakenService).executeOrder(eq("AUSD"), eq("market"), eq("sell"), anyDouble());
                // It should buy B but only with $200 cash * 0.99 = $198. $198 / 10 = 19.8 units
                verify(krakenService).executeOrder(eq("BUSD"), eq("market"), eq("buy"), doubleThat(v -> Math.abs(v - 19.8) < 0.1));
        }
}
