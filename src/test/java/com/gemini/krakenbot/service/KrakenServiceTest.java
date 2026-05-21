package com.gemini.krakenbot.service;
import com.gemini.krakenbot.service.impl.*;

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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.test.util.ReflectionTestUtils;

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
        krakenService = new KrakenServiceImpl(configService, objectMapper, builder);
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

    @Test
    void testNonceGeneration_Concurrency() throws InterruptedException {
        AtomicLong nonceGen = (AtomicLong) ReflectionTestUtils.getField(krakenService, "nonceGenerator");
        assertNotNull(nonceGen);

        int numThreads = 10;
        int incrementsPerThread = 1000;
        Set<Long> generatedNonces = Collections.synchronizedSet(new HashSet<>());
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < incrementsPerThread; j++) {
                        generatedNonces.add(nonceGen.incrementAndGet());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(numThreads * incrementsPerThread, generatedNonces.size(), "Nonces should be strictly unique across concurrent threads");
    }

    @Test
    void queryPrivate_ApiKeyNull() {
        ConfigService mockConfigService = mock(ConfigService.class);
        AppConfig config = new AppConfig(new KrakenCredentials(null, "secret"), new Settings(60L, 2.0, 1.0, false, 0.0, 1.0), Collections.emptyList());
        when(mockConfigService.getConfig()).thenReturn(config);
        
        KrakenService localService = new KrakenServiceImpl(mockConfigService, new ObjectMapper(), RestClient.builder());
        
        RuntimeException ex = assertThrows(RuntimeException.class, () -> localService.getBalances());
        assertEquals("API Key is null", ex.getMessage());
    }

    @Test
    void queryPublic_NullResponse() {
        RestClient mockClient = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec getSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec uriSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        
        when(mockClient.get()).thenReturn(getSpec);
        when(getSpec.uri(anyString())).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(null);
        
        KrakenServiceImpl localService = new KrakenServiceImpl(configService, new ObjectMapper(), RestClient.builder());
        ReflectionTestUtils.setField(localService, "restClient", mockClient);
        
        Map<String, Double> prices = localService.getTickerPrices("BTCUSD");
        assertTrue(prices.isEmpty());
    }

    @Test
    void queryPrivate_NullResponse() {
        RestClient mockClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec postSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec uriSpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        
        when(mockClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(uriSpec);
        when(uriSpec.header(anyString(), anyString())).thenReturn(uriSpec);
        when(uriSpec.body(anyString())).thenReturn(uriSpec);
        when(uriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(null);
        
        KrakenServiceImpl localService = new KrakenServiceImpl(configService, new ObjectMapper(), RestClient.builder());
        ReflectionTestUtils.setField(localService, "restClient", mockClient);
        
        Map<String, Double> balances = localService.getBalances();
        assertTrue(balances.isEmpty());
    }

    @Test
    void queryPrivate_InvalidPrivateKeyBase64() {
        ConfigService mockConfigService = mock(ConfigService.class);
        AppConfig config = new AppConfig(
            new KrakenCredentials("apiKey", "invalid_base64_!@#$"), 
            new Settings(60L, 2.0, 1.0, false, 0.0, 1.0), 
            Collections.emptyList()
        );
        when(mockConfigService.getConfig()).thenReturn(config);
        
        KrakenService localService = new KrakenServiceImpl(mockConfigService, new ObjectMapper(), RestClient.builder());
        
        assertThrows(RuntimeException.class, () -> localService.getBalances());
    }
}
