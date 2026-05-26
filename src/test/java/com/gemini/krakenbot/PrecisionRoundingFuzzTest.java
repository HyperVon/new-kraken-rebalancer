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
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.gemini.krakenbot.model.PortfolioStats;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

public class PrecisionRoundingFuzzTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private String capturedOrderPayload = null;

    @Test
    void shouldHandleExtremelyHighPrecisionBalancesAndPricesWithoutThrowingExceptions() {
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
                .andRespond(withSuccess("{\"error\":[],\"result\":{\"XXBT\":0.3333333333333333,\"ZUSD\":31415.9265358979323846}}", MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo("https://api.kraken.com/0/public/Ticker?pair=XBTUSD"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"error\":[],\"result\":{\"XXBTZUSD\":{\"c\":[\"68453.123456789\"]}}}", MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo("https://api.kraken.com/0/private/AddOrder"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    capturedOrderPayload = request.getBody().toString();
                })
                .andRespond(withSuccess("{\"error\":[],\"result\":{\"descr\":{\"order\":\"buy\"},\"txid\":[\"TX-1\"]}}", MediaType.APPLICATION_JSON));

        PortfolioStatsRepository mockStatsRepo = mock(PortfolioStatsRepository.class);
        when(mockStatsRepo.load()).thenReturn(new PortfolioStats(BigDecimal.ZERO));

        KrakenServiceImpl krakenService = new KrakenServiceImpl(mockConfigService, objectMapper, builder);
        PortfolioManagerImpl portfolioManager = new PortfolioManagerImpl(
                krakenService, mockConfigService, mock(TradeHistoryService.class), mockStatsRepo
        );

        assertDoesNotThrow(() -> invokePerformRebalanceCycle(portfolioManager));
        
        mockServer.verify();

        assertNotNull(capturedOrderPayload);

        // Verify that an order was executed and the volume was rounded cleanly (max 8 decimal places)
        // It should not contain a huge trailing decimal like 0.3333333333333
        Pattern volumePattern = Pattern.compile("volume=(\\d+\\.\\d{1,8})(&|$)");
        Matcher volumeMatch = volumePattern.matcher(capturedOrderPayload);
        assertTrue(volumeMatch.find(), "Volume string did not match expected precision constraints in payload: " + capturedOrderPayload);
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
