package com.gemini.krakenbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gemini.krakenbot.config.AppConfig;
import com.gemini.krakenbot.config.Allocation;
import com.gemini.krakenbot.config.KrakenCredentials;
import com.gemini.krakenbot.config.Settings;
import com.gemini.krakenbot.model.PortfolioStats;
import com.gemini.krakenbot.repository.PortfolioStatsRepository;
import com.gemini.krakenbot.service.ConfigService;
import com.gemini.krakenbot.service.TradeHistoryService;
import com.gemini.krakenbot.service.impl.KrakenServiceImpl;
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

public class KrakenE2ETest {

    private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void shouldExecuteFullRebalanceCycleEndToEndWithoutOrder() throws Exception {
        String validSecret = Base64.getEncoder().encodeToString("secret".getBytes());
        AppConfig appConfig = new AppConfig(
                new KrakenCredentials("apiKey", validSecret),
                new Settings(60L, 2.0, 1.0, false, 50.0, 1.0),
                List.of(new Allocation("BTC", 50.0), new Allocation("USD", 50.0))
        );

        ConfigService mockConfigService = mock(ConfigService.class);
        when(mockConfigService.getConfig()).thenReturn(appConfig);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        mockServer.expect(requestTo("https://api.kraken.com/0/private/Balance"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"error\":[],\"result\":{\"XXBT\":0.5,\"ZUSD\":25000.0}}", MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo("https://api.kraken.com/0/public/Ticker?pair=XBTUSD"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"error\":[],\"result\":{\"XXBTZUSD\":{\"c\":[\"50000.0\"]}}}", MediaType.APPLICATION_JSON));

        PortfolioStatsRepository mockStatsRepo = mock(PortfolioStatsRepository.class);
        when(mockStatsRepo.load()).thenReturn(new PortfolioStats(BigDecimal.ZERO));

        KrakenServiceImpl krakenService = new KrakenServiceImpl(mockConfigService, objectMapper, builder);
        PortfolioManagerImpl portfolioManager = new PortfolioManagerImpl(krakenService, mockConfigService, mock(TradeHistoryService.class), mockStatsRepo);

        invokePerformRebalanceCycle(portfolioManager);

        // Verify that no order was placed (MockRestServiceServer will throw an AssertionError if AddOrder was called without being expected)
        mockServer.verify();
    }

    @Test
    void shouldExecuteFullRebalanceCycleEndToEndAndTriggerTrade() throws Exception {
        String validSecret = Base64.getEncoder().encodeToString("secret".getBytes());
        AppConfig appConfig = new AppConfig(
                new KrakenCredentials("apiKey", validSecret),
                new Settings(60L, 2.0, 1.0, false, 50.0, 1.0),
                List.of(new Allocation("BTC", 50.0), new Allocation("USD", 50.0))
        );

        ConfigService mockConfigService = mock(ConfigService.class);
        when(mockConfigService.getConfig()).thenReturn(appConfig);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();

        mockServer.expect(requestTo("https://api.kraken.com/0/private/Balance"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"error\":[],\"result\":{\"XXBT\":0.4,\"ZUSD\":30000.0}}", MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo("https://api.kraken.com/0/public/Ticker?pair=XBTUSD"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"error\":[],\"result\":{\"XXBTZUSD\":{\"c\":[\"50000.0\"]}}}", MediaType.APPLICATION_JSON));

        // We use string matchers or regex to assert the request body contains volume=0.1
        mockServer.expect(requestTo("https://api.kraken.com/0/private/AddOrder"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    String body = request.getBody().toString();
                    assertTrue(body.contains("pair=BTCUSD"), "Missing pair=BTCUSD");
                    assertTrue(body.contains("type=buy"), "Missing type=buy");
                    assertTrue(body.contains("ordertype=market"), "Missing ordertype=market");
                    assertTrue(body.contains("volume=0.1"), "Missing volume=0.1");
                })
                .andRespond(withSuccess("{\"error\":[],\"result\":{\"descr\":{\"order\":\"buy\"},\"txid\":[\"TX-1\"]}}", MediaType.APPLICATION_JSON));

        PortfolioStatsRepository mockStatsRepo = mock(PortfolioStatsRepository.class);
        when(mockStatsRepo.load()).thenReturn(new PortfolioStats(BigDecimal.ZERO));

        KrakenServiceImpl krakenService = new KrakenServiceImpl(mockConfigService, objectMapper, builder);
        PortfolioManagerImpl portfolioManager = new PortfolioManagerImpl(krakenService, mockConfigService, mock(TradeHistoryService.class), mockStatsRepo);

        invokePerformRebalanceCycle(portfolioManager);

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
