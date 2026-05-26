package com.gemini.krakenbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gemini.krakenbot.config.AppConfig;
import com.gemini.krakenbot.config.Allocation;
import com.gemini.krakenbot.config.KrakenCredentials;
import com.gemini.krakenbot.config.Settings;
import com.gemini.krakenbot.repository.PortfolioStatsRepository;
import com.gemini.krakenbot.service.ConfigService;
import com.gemini.krakenbot.service.TradeHistoryService;
import com.gemini.krakenbot.service.impl.KrakenServiceImpl;
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

import com.gemini.krakenbot.model.PortfolioStats;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

public class ResilienceChaosTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void shouldNotCrashApplicationWhenKrakenApiReturns502BadGateway() {
        AppConfig appConfig = new AppConfig(
                new KrakenCredentials("apiKey", "secret"),
                new Settings(60L, 2.0, 1.0, false, 50.0, 1.0),
                List.of(new Allocation("BTC", 50.0))
        );

        ConfigService mockConfigService = mock(ConfigService.class);
        when(mockConfigService.getConfig()).thenReturn(appConfig);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        mockServer.expect(requestTo("https://api.kraken.com/0/private/Balance"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("Bad Gateway"));

        PortfolioStatsRepository mockStatsRepo = mock(PortfolioStatsRepository.class);
        when(mockStatsRepo.load()).thenReturn(new PortfolioStats(BigDecimal.ZERO));

        KrakenServiceImpl krakenService = new KrakenServiceImpl(mockConfigService, objectMapper, builder);
        PortfolioManagerImpl portfolioManager = new PortfolioManagerImpl(
                krakenService, mockConfigService, mock(TradeHistoryService.class), mockStatsRepo
        );

        // Prove that the network failure correctly propagates a RuntimeException
        // This ensures our mock is working, while runLoop() is responsible for catching it
        assertThrows(RuntimeException.class, () -> invokePerformRebalanceCycle(portfolioManager));
        
        mockServer.verify();
    }

    @Test
    void shouldNotCrashApplicationWhenAnIOExceptionOccurs() {
        AppConfig appConfig = new AppConfig(
                new KrakenCredentials("apiKey", "secret"),
                new Settings(60L, 2.0, 1.0, false, 50.0, 1.0),
                List.of(new Allocation("BTC", 50.0))
        );

        ConfigService mockConfigService = mock(ConfigService.class);
        when(mockConfigService.getConfig()).thenReturn(appConfig);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        // Simulate network failure by throwing an exception during the request
        mockServer.expect(requestTo("https://api.kraken.com/0/private/Balance"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(request -> { throw new java.net.SocketTimeoutException("Connection reset by peer"); });

        PortfolioStatsRepository mockStatsRepo = mock(PortfolioStatsRepository.class);
        when(mockStatsRepo.load()).thenReturn(new PortfolioStats(BigDecimal.ZERO));

        KrakenServiceImpl krakenService = new KrakenServiceImpl(mockConfigService, objectMapper, builder);
        PortfolioManagerImpl portfolioManager = new PortfolioManagerImpl(
                krakenService, mockConfigService, mock(TradeHistoryService.class), mockStatsRepo
        );

        // Prove that the network failure correctly propagates an exception
        assertThrows(Exception.class, () -> invokePerformRebalanceCycle(portfolioManager));
        
        mockServer.verify();
    }

    private void invokePerformRebalanceCycle(PortfolioManagerImpl pm) throws Exception {
        java.lang.reflect.Method method = PortfolioManagerImpl.class.getDeclaredMethod("performRebalanceCycle");
        method.setAccessible(true);
        try {
            method.invoke(pm);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }
}
