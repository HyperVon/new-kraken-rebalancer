package com.gemini.krakenbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gemini.krakenbot.config.AppConfig;
import com.gemini.krakenbot.config.KrakenCredentials;
import com.gemini.krakenbot.config.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KrakenServiceTest {

    private MockRestServiceServer mockServer;
    private KrakenService krakenService;
    private ConfigService configService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        configService = mock(ConfigService.class);

        // Mock Config
        KrakenCredentials credentials = new KrakenCredentials("public-key",
                Base64.getEncoder().encodeToString("secret-key".getBytes()));
        Settings settings = new Settings(60L, 2.0, 1.0, false, 0.0, 1.0);
        AppConfig config = new AppConfig(credentials, settings, Collections.emptyList());
        when(configService.getConfig()).thenReturn(config);

        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        krakenService = new KrakenService(configService, objectMapper, builder);
    }

    @Test
    void getBalances_Success() {
        String responseJson = "{\"error\":[],\"result\":{\"XXBTZUSD\":63000.0,\"XETHZUSD\":3000.0,\"USD\":5000.0}}";

        mockServer.expect(requestTo("https://api.kraken.com/0/private/Balance"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        Map<String, Double> balances = krakenService.getBalances();

        assertEquals(63000.0, balances.get("XXBTZUSD"));
        assertEquals(3000.0, balances.get("XETHZUSD"));
        assertEquals(5000.0, balances.get("USD"));
    }

    @Test
    void getTickerPrices_Success() {
        String responseJson = "{\"error\":[],\"result\":{\"XXBTZUSD\":{\"c\":[\"65000.0\"]},\"XETHZUSD\":{\"c\":[\"3200.0\"]}}}";

        mockServer.expect(requestTo("https://api.kraken.com/0/public/Ticker?pair=XXBTZUSD,XETHZUSD"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        Map<String, Double> prices = krakenService.getTickerPrices("XXBTZUSD,XETHZUSD");

        assertEquals(65000.0, prices.get("XXBTZUSD"));
        assertEquals(3200.0, prices.get("XETHZUSD"));
    }

    @Test
    void executeOrder_Success() {
        String responseJson = "{\"error\":[],\"result\":{\"descr\":{\"order\":\"buy 0.1 XBTUSD @ limit 50000\"},\"txid\":[\"THVR-...-TC\"]}}";

        mockServer.expect(requestTo("https://api.kraken.com/0/private/AddOrder"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        assertDoesNotThrow(() -> krakenService.executeOrder("XBTUSD", "limit", "buy", 0.1));
    }

    @Test
    void executeOrder_DryRun() {
        Settings settings = new Settings(60L, 2.0, 1.0, true, 0.0, 1.0); // dryRun = true
        AppConfig config = new AppConfig(new KrakenCredentials("k", "s"), settings, Collections.emptyList());
        when(configService.getConfig()).thenReturn(config);

        // Should not make any request
        krakenService.executeOrder("XBTUSD", "limit", "buy", 0.1);
        mockServer.verify();
    }

    @Test
    void getTickerPrices_Malformed() {
        String responseJson = "{\"error\":[],\"result\":{\"XXBTZUSD\":{\"c\":[]}, \"XETHZUSD\":{}}}";

        mockServer.expect(requestTo("https://api.kraken.com/0/public/Ticker?pair=XXBTZUSD,XETHZUSD"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        Map<String, Double> prices = krakenService.getTickerPrices("XXBTZUSD,XETHZUSD");

        assertTrue(prices.isEmpty());
    }

    @Test
    void queryPublic_ErrorResponse() {
        String responseJson = "{\"error\":[\"EQuery:Unknown asset pair\"]}";
        mockServer.expect(requestTo("https://api.kraken.com/0/public/Ticker?pair=INVALID"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        assertThrows(RuntimeException.class, () -> krakenService.getTickerPrices("INVALID"));
    }

    @Test
    void queryPublic_JsonProcessingException() {
        mockServer.expect(requestTo("https://api.kraken.com/0/public/Ticker?pair=XBTUSD"))
                .andRespond(withSuccess("{invalid-json", MediaType.APPLICATION_JSON));

        assertThrows(RuntimeException.class, () -> krakenService.getTickerPrices("XBTUSD"));
    }

    @Test
    void executeOrder_ApiError() {
        String responseJson = "{\"error\":[\"EOrder:Insufficient funds\"]}";
        mockServer.expect(requestTo("https://api.kraken.com/0/private/AddOrder"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        // executeOrder logs error, doesn't throw.
        assertDoesNotThrow(() -> krakenService.executeOrder("XBTUSD", "limit", "buy", 1.0));
    }

    @Test
    void queryPrivate_JsonProcessingException() {
        mockServer.expect(requestTo("https://api.kraken.com/0/private/Balance"))
                .andRespond(withSuccess("{broken-json", MediaType.APPLICATION_JSON));

        assertThrows(RuntimeException.class, () -> krakenService.getBalances());
    }
}
