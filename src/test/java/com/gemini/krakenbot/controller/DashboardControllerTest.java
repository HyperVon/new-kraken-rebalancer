package com.gemini.krakenbot.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gemini.krakenbot.config.AppConfig;
import com.gemini.krakenbot.config.KrakenCredentials;
import com.gemini.krakenbot.config.Settings;
import com.gemini.krakenbot.config.Allocation;
import com.gemini.krakenbot.model.PortfolioSnapshot;
import com.gemini.krakenbot.service.ConfigService;
import com.gemini.krakenbot.service.TradeHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DashboardControllerTest {

    private MockMvc mockMvc;

    @Mock
    private TradeHistoryService tradeHistoryService;

    @Mock
    private ConfigService configService;

    @InjectMocks
    private DashboardController dashboardController;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        mockMvc = MockMvcBuilders.standaloneSetup(dashboardController).build();
    }

    @Test
    void getStatus_ReturnsLatestSnapshot() throws Exception {
        PortfolioSnapshot snapshot = new PortfolioSnapshot(Instant.now(), BigDecimal.ZERO, Collections.emptyMap(),
                Collections.emptyList(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        when(tradeHistoryService.getLatestSnapshot()).thenReturn(snapshot);

        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk());
    }

    @Test
    void getHistory_ReturnsHistory() throws Exception {
        PortfolioSnapshot snapshot = new PortfolioSnapshot(Instant.now(), BigDecimal.ZERO, Collections.emptyMap(),
                Collections.emptyList(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        when(tradeHistoryService.getHistory()).thenReturn(List.of(snapshot));

        mockMvc.perform(get("/api/history"))
                .andExpect(status().isOk());
    }

    @Test
    void getConfig_ReturnsConfigWithSanitizedCredentials() throws Exception {
        AppConfig config = new AppConfig(
                new KrakenCredentials("real-api-key", "real-private-key"),
                new Settings(60L, 2.0, 1.0, true, 0.0, 1.0),
                List.of(new Allocation("USD", 100.0)));
        when(configService.getConfig()).thenReturn(config);

        mockMvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kraken.apiKey").isEmpty())
                .andExpect(jsonPath("$.kraken.privateKey").isEmpty())
                .andExpect(jsonPath("$.settings.loopDelaySeconds").value(60));
    }

    @Test
    void updateConfig_PreservesServerCredentials() throws Exception {
        AppConfig serverConfig = new AppConfig(
                new KrakenCredentials("server-key", "server-secret"),
                new Settings(60L, 2.0, 1.0, true, 0.0, 1.0),
                List.of(new Allocation("USD", 100.0)));
        when(configService.getConfig()).thenReturn(serverConfig);

        // Client sends config without real credentials
        AppConfig clientConfig = new AppConfig(null, serverConfig.settings(), serverConfig.allocations());

        mockMvc.perform(post("/api/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(clientConfig)))
                .andExpect(status().isOk());

        verify(configService).updateConfig(any(AppConfig.class));
    }
}
