package com.gemini.krakenbot.service;

import java.math.BigDecimal;
import com.gemini.krakenbot.service.impl.*;
import com.gemini.krakenbot.config.*;
import com.gemini.krakenbot.model.*;
import com.gemini.krakenbot.repository.PortfolioStatsRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PortfolioManagerEdgeCasesTest {

    @Mock
    private KrakenService krakenService;
    @Mock
    private ConfigService configService;
    @Mock
    private TradeHistoryService tradeHistoryService;
    @Mock
    private PortfolioStatsRepository portfolioStatsRepository;

    private PortfolioManagerImpl portfolioManager;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(portfolioStatsRepository.load()).thenReturn(new PortfolioStats(BigDecimal.ZERO));
        portfolioManager = new PortfolioManagerImpl(krakenService, configService, tradeHistoryService, portfolioStatsRepository);
    }

    @Test
    void checkAndRunCycle_DelayNotMet() {
        Settings settings = new Settings(60L, 2.0, 1.0, true, 0.0, 1.0);
        AppConfig config = new AppConfig(new KrakenCredentials("k", "s"), settings, Collections.emptyList());
        when(configService.getConfig()).thenReturn(config);
        
        portfolioManager.startRebalancingLoop();
        
        // First execution -> should run
        portfolioManager.checkAndRunCycle();
        verify(krakenService, times(1)).getBalances();
        
        // Second execution immediately -> should NOT run again
        portfolioManager.checkAndRunCycle();
        verify(krakenService, times(1)).getBalances(); // still 1
    }

    @Test
    void performRebalanceCycle_NullBalances() {
        when(krakenService.getBalances()).thenReturn(null);
        
        List<Allocation> allocs = List.of(new Allocation("USD", 100.0));
        Settings settings = new Settings(0L, 2.0, 1.0, true, 0.0, 1.0);
        AppConfig config = new AppConfig(new KrakenCredentials("k", "s"), settings, allocs);
        when(configService.getConfig()).thenReturn(config);
        
        portfolioManager.startRebalancingLoop();
        portfolioManager.checkAndRunCycle();
        
        verify(krakenService).getBalances();
    }

    @Test
    void performRebalanceCycle_PriceNotFoundAbort() {
        when(krakenService.getBalances()).thenReturn(Map.of("BTC", 1.0));
        when(krakenService.getTickerPrices(anyString())).thenReturn(Collections.emptyMap()); // Price missing
        
        List<Allocation> allocs = List.of(new Allocation("BTC", 100.0));
        Settings settings = new Settings(0L, 2.0, 1.0, true, 0.0, 1.0);
        AppConfig config = new AppConfig(new KrakenCredentials("k", "s"), settings, allocs);
        when(configService.getConfig()).thenReturn(config);
        
        portfolioManager.startRebalancingLoop();
        portfolioManager.checkAndRunCycle();
        
        // Cycle should be aborted, so no snapshot should be added to trade history
        verify(tradeHistoryService, never()).addSnapshot(any());
    }

    @Test
    void testDistributeFiatCorrection_NoCounterbalancingAssets() {
        Map<String, BigDecimal> allDevs = Map.of(
            "USD", new BigDecimal("100.0"),
            "A", new BigDecimal("10.0") // both positive
        );
        Map<String, BigDecimal> buyOrders = new HashMap<>();
        Map<String, BigDecimal> sellOrders = new HashMap<>();
        
        ReflectionTestUtils.invokeMethod(portfolioManager, "distributeFiatCorrection",
            new BigDecimal("100.0"), allDevs, buyOrders, sellOrders, new java.util.ArrayList<String>());
            
        assertTrue(buyOrders.isEmpty());
        assertTrue(sellOrders.isEmpty());
    }
    
    @Test
    void testFiatDeploymentRatioExceedsOne() {
        // ATH = $2000
        // Current = $500 (75% Drawdown)
        // maxDrawdown = 50%
        // drawdownPct (75) / maxDrawdown (50) = 1.5 ratio -> Should cap ratio at 1.0 -> 100% deployment
        when(portfolioStatsRepository.load()).thenReturn(new PortfolioStats(new BigDecimal("2000.0")));
        
        List<Allocation> allocs = List.of(
            new Allocation("A", 50.0),
            new Allocation("USD", 50.0)
        );
        when(configService.getConfig()).thenReturn(new AppConfig(
            new KrakenCredentials("k", "s"),
            new Settings(0L, 2.0, 1.0, true, 50.0, 1.0),
            allocs
        ));
        
        when(krakenService.getBalances()).thenReturn(Map.of("A", 2.5, "USD", 250.0));
        when(krakenService.getTickerPrices(anyString())).thenReturn(Map.of("AUSD", 100.0));
        
        portfolioManager.startRebalancingLoop();
        portfolioManager.checkAndRunCycle();
        
        // Capture snapshot and check deployment percent is 100%
        org.mockito.ArgumentCaptor<PortfolioSnapshot> captor = org.mockito.ArgumentCaptor.forClass(PortfolioSnapshot.class);
        verify(tradeHistoryService).addSnapshot(captor.capture());
        assertEquals(100.0, captor.getValue().getFiatDeploymentPercent().doubleValue(), 0.001);
    }

    @Test
    void testGetCurrentPrice_ShortCircuitAndFallback() {
        Map<String, Double> prices = Map.of(
            "ETHEUR", 3000.0,
            "ETHUSD", 3100.0
        );
        
        BigDecimal priceEth = ReflectionTestUtils.invokeMethod(portfolioManager, "getCurrentPrice", "ETH", prices);
        assertEquals(new BigDecimal("3100.0"), priceEth);
        
        BigDecimal priceMissing = ReflectionTestUtils.invokeMethod(portfolioManager, "getCurrentPrice", "LTC", prices);
        assertEquals(BigDecimal.ZERO, priceMissing);
    }

    @Test
    void testExecuteOrders_ZeroPriceContinues() {
        Map<String, BigDecimal> buyOrders = Map.of("ETH", BigDecimal.TEN);
        Map<String, BigDecimal> sellOrders = Map.of("BTC", BigDecimal.TEN);
        Map<String, BigDecimal> currentValuesUSD = Map.of("USD", BigDecimal.valueOf(1000.0));
        Map<String, Double> prices = Collections.emptyMap();
        Settings settings = new Settings(0L, 2.0, 1.0, false, 0.0, 1.0);
        List<String> actionLog = new java.util.ArrayList<>();
        
        ReflectionTestUtils.invokeMethod(portfolioManager, "executeOrders",
            buyOrders, sellOrders, currentValuesUSD, prices, settings, actionLog);
            
        verify(krakenService, never()).executeOrder(anyString(), anyString(), anyString(), anyDouble());
    }

    @Test
    void testExecuteOrders_UpdateCashException() {
        Map<String, BigDecimal> buyOrders = Map.of("ETH", BigDecimal.TEN);
        Map<String, BigDecimal> sellOrders = Map.of("BTC", BigDecimal.valueOf(100.0));
        Map<String, BigDecimal> currentValuesUSD = Map.of("USD", BigDecimal.valueOf(1000.0));
        Map<String, Double> prices = Map.of("XBTUSD", 10.0, "ETHUSD", 5.0);
        Settings settings = new Settings(0L, 2.0, 1.0, false, 0.0, 1.0);
        List<String> actionLog = new java.util.ArrayList<>();
        
        when(krakenService.getBalances()).thenThrow(new RuntimeException("balances api error"));
        
        ReflectionTestUtils.invokeMethod(portfolioManager, "executeOrders",
            buyOrders, sellOrders, currentValuesUSD, prices, settings, actionLog);
            
        verify(krakenService).executeOrder(eq("BTCUSD"), eq("market"), eq("sell"), eq(10.0));
        verify(krakenService).executeOrder(eq("ETHUSD"), eq("market"), eq("buy"), eq(2.0));
    }

    @Test
    void testExecuteOrders_UpdateBalancesNullOrEmpty() {
        Map<String, BigDecimal> buyOrders = Map.of("ETH", BigDecimal.TEN);
        Map<String, BigDecimal> sellOrders = Map.of("BTC", BigDecimal.valueOf(100.0));
        Map<String, BigDecimal> currentValuesUSD = Map.of("USD", BigDecimal.valueOf(1000.0));
        Map<String, Double> prices = Map.of("XBTUSD", 10.0, "ETHUSD", 5.0);
        Settings settings = new Settings(0L, 2.0, 1.0, false, 0.0, 1.0);
        List<String> actionLog = new java.util.ArrayList<>();
        
        when(krakenService.getBalances()).thenReturn(null);
        
        ReflectionTestUtils.invokeMethod(portfolioManager, "executeOrders",
            buyOrders, sellOrders, currentValuesUSD, prices, settings, actionLog);
            
        verify(krakenService).executeOrder(eq("BTCUSD"), eq("market"), eq("sell"), eq(10.0));
        verify(krakenService).executeOrder(eq("ETHUSD"), eq("market"), eq("buy"), eq(2.0));
    }
}
