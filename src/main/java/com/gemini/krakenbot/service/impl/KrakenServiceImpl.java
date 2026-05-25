package com.gemini.krakenbot.service.impl;

import com.gemini.krakenbot.service.ConfigService;


import lombok.extern.slf4j.Slf4j;


import com.gemini.krakenbot.service.KrakenService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class KrakenServiceImpl implements KrakenService {
    private static final String API_URL = "https://api.kraken.com";
    private static final String API_VERSION = "0";

    private final RestClient restClient;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;
    private final AtomicLong nonceGenerator = new AtomicLong(System.currentTimeMillis() * 1000);

    public KrakenServiceImpl(ConfigService configService, ObjectMapper objectMapper, RestClient.Builder restClientBuilder) {
        this.configService = configService;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder.baseUrl(API_URL).build();
    }

    public Map<String, Double> getBalances() {
        String path = "/" + API_VERSION + "/private/Balance";
        JsonNode response = queryPrivate(path, Map.of());
        Map<String, Double> balances = new HashMap<>();
        response.properties().forEach(entry -> balances.put(entry.getKey(), entry.getValue().asDouble()));
        return balances;
    }

    public Map<String, Double> getTickerPrices(String pairs) {
        String path = "/" + API_VERSION + "/public/Ticker?pair=" + pairs;
        JsonNode result = queryPublic(path).path("result");
        Map<String, Double> prices = new HashMap<>();
        result.properties().forEach(entry -> {
            // Kraken returns array [price, ...], usually index 0 of 'c' (last trade closed)
            // is good enough,
            // or 'c'[0]. Let's check structure. Typically
            // {"XXBTZUSD":{"a":["..."],"b":["..."],"c":["63000.0","0.1"]}}
            // We want current price, let's use 'c'[0] (last trade price).
            JsonNode c = entry.getValue().path("c");
            if (c.isArray() && !c.isEmpty()) {
                prices.put(entry.getKey(), c.get(0).asDouble());
            }
        });
        return prices;
    }

    public void executeOrder(String pair, String type, String side, double volume) {
        if (configService.getConfig().settings().dryRun()) {
            log.info("[DRY RUN] Would execute order: {} {} {} volume={}", type, side, pair, volume);
            return;
        }

        String path = "/" + API_VERSION + "/private/AddOrder";
        Map<String, String> params = new HashMap<>();
        params.put("pair", pair);
        params.put("type", side); // buy/sell
        params.put("ordertype", type); // market/limit
        params.put("volume", String.valueOf(volume));

        try {
            JsonNode resp = queryPrivate(path, params);
            log.info("Order Executed: {}", resp.toString());
        } catch (Exception e) {
            log.error("Failed to execute order: {} {} {} volume={}", type, side, pair, volume, e);
        }
    }

    @SuppressWarnings("null")
    private JsonNode queryPublic(String path) {
        String response = restClient.get().uri(path).retrieve().body(String.class);
        if (response == null)
            response = "{}";
        try {
            JsonNode root = objectMapper.readTree(response);
            if (root.has("error") && !root.path("error").isEmpty()) {
                log.error("Kraken Public API Error for path {}: {}", path, root.path("error"));
                // Return empty result or throw? Throwing allows catching in main loop.
                throw new RuntimeException("Kraken Public API Error: " + root.path("error").toString());
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse public API response", e);
        }
    }

    @SuppressWarnings("null")
    private JsonNode queryPrivate(String path, Map<String, String> data) {
        int maxRetries = 5;
        int retryCount = 0;

        while (true) {
            String nonce = String.valueOf(nonceGenerator.incrementAndGet());
            Map<String, String> payload = new HashMap<>(data);
            payload.put("nonce", nonce);

            String postData = payload.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("&"));

            String signature = signRequest(path, nonce, postData);

            String apiKey = configService.getConfig().kraken().apiKey();
            if (apiKey == null)
                throw new RuntimeException("API Key is null");

            String response = restClient.post()
                    .uri(path)
                    .header("API-Key", apiKey)
                    .header("API-Sign", signature)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body(postData)
                    .retrieve()
                    .body(String.class);

            if (response == null)
                response = "{}";

            try {
                JsonNode root = objectMapper.readTree(response);
                if (!root.path("error").isEmpty()) {
                    String errorMsg = root.path("error").toString();
                    if (errorMsg.contains("Invalid nonce") && retryCount < maxRetries) {
                        log.warn("Invalid nonce detected. Adjusting nonce generator and retrying (Attempt {}/{})", retryCount + 1, maxRetries);
                        nonceGenerator.addAndGet(5000); // jump ahead to resolve collisions
                        retryCount++;
                        continue;
                    }
                    throw new RuntimeException("Kraken API Error: " + errorMsg);
                }
                return root.path("result");
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to parse private API response", e);
            }
        }
    }

    private String signRequest(String path, String nonce, String postData) {
        try {
            byte[] sha2 = MessageDigest.getInstance("SHA-256")
                    .digest((nonce + postData).getBytes(StandardCharsets.UTF_8));

            byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
            byte[] hmacMessage = new byte[pathBytes.length + sha2.length];
            System.arraycopy(pathBytes, 0, hmacMessage, 0, pathBytes.length);
            System.arraycopy(sha2, 0, hmacMessage, pathBytes.length, sha2.length);

            Mac mac = Mac.getInstance("HmacSHA512");
            byte[] secretDecoded = Base64.getDecoder().decode(configService.getConfig().kraken().privateKey());
            SecretKeySpec secretSpec = new SecretKeySpec(secretDecoded, "HmacSHA512");
            mac.init(secretSpec);

            byte[] sigBytes = mac.doFinal(hmacMessage);
            return Base64.getEncoder().encodeToString(sigBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign request", e);
        }
    }
}
