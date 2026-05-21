package com.gemini.krakenbot.service;

import static org.mockito.Mockito.*;
import java.util.ArrayList;

import java.math.BigDecimal;

import com.gemini.krakenbot.model.PortfolioStats;
import com.gemini.krakenbot.service.impl.*;

import com.gemini.krakenbot.config.Allocation;
import com.gemini.krakenbot.config.AppConfig;
import com.gemini.krakenbot.repository.PortfolioStatsRepository;
import com.gemini.krakenbot.repository.impl.PortfolioStatsRepositoryImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortfolioManagerFiatCorrectionTest {

        @Test
        void testDistributeFiatCorrection_Deposit_OnlyBuysUnderweight() {
                // Setup
                KrakenService krakenService = mock(KrakenService.class);
                ConfigService configService = mock(ConfigService.class);
                TradeHistoryService tradeHistoryService = mock(TradeHistoryService.class);

                PortfolioStatsRepository repo = mock(PortfolioStatsRepositoryImpl.class);
                when(repo.load())
                                .thenReturn(new PortfolioStats(BigDecimal.ZERO));
                PortfolioManagerImpl portfolioManager = new PortfolioManagerImpl(krakenService, configService,
                                tradeHistoryService, repo);

                // Config Mock
                Allocation allocA = new Allocation("A", 50.0);
                Allocation allocB = new Allocation("B", 50.0); // Assuming USD is implicit or handled, or strictly these
                                                               // two
                // The method iterates over config allocations.

                List<Allocation> allAllocations = List.of(allocA, allocB);
                AppConfig mockConfig = mock(AppConfig.class);
                when(mockConfig.allocations()).thenReturn(allAllocations);
                when(configService.getConfig()).thenReturn(mockConfig);

                // Inputs
                BigDecimal usdDev = BigDecimal.valueOf(100.0); // Positive = Deposit/Surplus -> Need to BUY
                Map<String, BigDecimal> allDevs = new HashMap<>();
                // Asset A is Overweight (positive dev) -> Should NOT buy
                allDevs.put("A", BigDecimal.valueOf(10.0));
                // Asset B is Underweight (negative dev) -> Should BUY
                allDevs.put("B", BigDecimal.valueOf(-10.0));

                Map<String, BigDecimal> buyOrders = new HashMap<>();
                Map<String, BigDecimal> sellOrders = new HashMap<>();

                // Invoke private method
                ReflectionTestUtils.invokeMethod(portfolioManager, "distributeFiatCorrection",
                                usdDev, allDevs, buyOrders, sellOrders, new ArrayList<String>());

                // Verify
                // Expect Buy for B
                assertTrue(buyOrders.containsKey("B"), "Should buy B because it is underweight");
                // Expect NO Buy for A
                assertEquals(0, buyOrders.getOrDefault("A", BigDecimal.ZERO).compareTo(BigDecimal.ZERO),
                                "Should NOT buy A because it is overweight");

                // Also verify sells are empty
                assertTrue(sellOrders.isEmpty());
        }

        @Test
        void testDistributeFiatCorrection_Withdrawal_OnlySellsOverweight() {
                // Setup similar to above
                KrakenService krakenService = mock(KrakenService.class);
                ConfigService configService = mock(ConfigService.class);
                TradeHistoryService tradeHistoryService = mock(TradeHistoryService.class);

                PortfolioStatsRepository repo = mock(PortfolioStatsRepositoryImpl.class);
                when(repo.load())
                                .thenReturn(new PortfolioStats(BigDecimal.ZERO));
                PortfolioManagerImpl portfolioManager = new PortfolioManagerImpl(krakenService, configService,
                                tradeHistoryService, repo);

                List<Allocation> allAllocations = List.of(new Allocation("A", 50.0), new Allocation("B", 50.0));
                AppConfig mockConfig = mock(AppConfig.class);
                when(mockConfig.allocations()).thenReturn(allAllocations);
                when(configService.getConfig()).thenReturn(mockConfig);

                // Inputs
                BigDecimal usdDev = BigDecimal.valueOf(-100.0); // Negative = Withdrawal/Shortage -> Need to SELL
                Map<String, BigDecimal> allDevs = new HashMap<>();
                // Asset A is Overweight (positive dev) -> Should SELL
                allDevs.put("A", BigDecimal.valueOf(10.0));
                // Asset B is Underweight (negative dev) -> Should NOT SELL
                allDevs.put("B", BigDecimal.valueOf(-10.0));

                Map<String, BigDecimal> buyOrders = new HashMap<>();
                Map<String, BigDecimal> sellOrders = new HashMap<>();

                // Invoke private method
                ReflectionTestUtils.invokeMethod(portfolioManager, "distributeFiatCorrection",
                                usdDev, allDevs, buyOrders, sellOrders, new ArrayList<String>());

                // Verify
                // Expect Sell for A
                assertTrue(sellOrders.containsKey("A"), "Should sell A because it is overweight");
                // Expect NO Sell for B
                assertEquals(0, sellOrders.getOrDefault("B", BigDecimal.ZERO).compareTo(BigDecimal.ZERO),
                                "Should NOT sell B because it is underweight");

                // Also verify buys are empty
                assertTrue(buyOrders.isEmpty());
        }

        @Test
        void testDistributeFiatCorrection_ProportionalDistribution() {
                // Setup
                KrakenService krakenService = mock(KrakenService.class);
                ConfigService configService = mock(ConfigService.class);
                TradeHistoryService tradeHistoryService = mock(TradeHistoryService.class);

                PortfolioStatsRepository repo = mock(PortfolioStatsRepositoryImpl.class);
                when(repo.load())
                                .thenReturn(new PortfolioStats(BigDecimal.ZERO));
                PortfolioManagerImpl portfolioManager = new PortfolioManagerImpl(krakenService, configService,
                                tradeHistoryService, repo);

                // Allocations (targets don't matter for this logic anymore, but needed for
                // config mock)
                List<Allocation> allAllocations = List.of(
                                new Allocation("A", 30.0),
                                new Allocation("B", 30.0),
                                new Allocation("C", 40.0));
                AppConfig mockConfig = mock(AppConfig.class);
                when(mockConfig.allocations()).thenReturn(allAllocations);
                when(configService.getConfig()).thenReturn(mockConfig);

                // Inputs
                BigDecimal usdDev = BigDecimal.valueOf(100.0); // $100 Surplus to spend
                Map<String, BigDecimal> allDevs = new HashMap<>();

                // Asset A: Large Deficit (-$200)
                allDevs.put("A", BigDecimal.valueOf(-200.0));
                // Asset B: Small Deficit (-$50)
                allDevs.put("B", BigDecimal.valueOf(-50.0));
                // Asset C: Surplus (+$50) -> Should be ignored
                allDevs.put("C", BigDecimal.valueOf(50.0));

                Map<String, BigDecimal> buyOrders = new HashMap<>();
                Map<String, BigDecimal> sellOrders = new HashMap<>();

                // Invoke
                ReflectionTestUtils.invokeMethod(portfolioManager, "distributeFiatCorrection",
                                usdDev, allDevs, buyOrders, sellOrders, new ArrayList<String>());

                // Verify Total Weight = 200 + 50 = 250
                // A Share = (200 / 250) * 100 = 80
                // B Share = (50 / 250) * 100 = 20

                BigDecimal boughtA = buyOrders.getOrDefault("A", BigDecimal.ZERO);
                BigDecimal boughtB = buyOrders.getOrDefault("B", BigDecimal.ZERO);

                assertEquals(0, boughtA.compareTo(BigDecimal.valueOf(80.0)),
                                "Asset A should get 80% (80.0) of surplus");
                assertEquals(0, boughtB.compareTo(BigDecimal.valueOf(20.0)),
                                "Asset B should get 20% (20.0) of surplus");

                // C should be 0
                assertEquals(0, buyOrders.getOrDefault("C", BigDecimal.ZERO).compareTo(BigDecimal.ZERO));
        }
}
