package com.gemini.krakenbot.service;

import java.math.BigDecimal;
import com.gemini.krakenbot.service.impl.*;

import com.gemini.krakenbot.model.PortfolioSnapshot;
import com.gemini.krakenbot.repository.TradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.Instant;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TradeHistoryServiceTest {

    private TradeHistoryService tradeHistoryService;

    @Mock
    private TradeRepository repository;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        tradeHistoryService = new TradeHistoryServiceImpl(repository);
    }

    @Test
    void init_LoadsHistory() {
        PortfolioSnapshot snapshot = new PortfolioSnapshot(Instant.now(), BigDecimal.ZERO,
                Collections.emptyMap(), Collections.emptyList(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        when(repository.load()).thenReturn(List.of(snapshot));

        tradeHistoryService.init();

        assertEquals(1, tradeHistoryService.getHistory().size());
        assertEquals(snapshot, tradeHistoryService.getLatestSnapshot());
    }

    @Test
    void addSnapshot_AddsToFrontAndSaves() {
        PortfolioSnapshot s1 = new PortfolioSnapshot(Instant.now(), BigDecimal.ZERO,
                Collections.emptyMap(), Collections.emptyList(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        PortfolioSnapshot s2 = new PortfolioSnapshot(Instant.now(), BigDecimal.ZERO,
                Collections.emptyMap(), Collections.emptyList(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        tradeHistoryService.addSnapshot(s1);
        tradeHistoryService.addSnapshot(s2); // s2 is newer

        assertEquals(2, tradeHistoryService.getHistory().size());
        assertEquals(s2, tradeHistoryService.getLatestSnapshot());
        verify(repository, times(2)).save(anyList());
    }

    @Test
    void addSnapshot_LimitsHistorySize() {
        // Add 60 snapshots
        for (int i = 0; i < 60; i++) {
            tradeHistoryService.addSnapshot(new PortfolioSnapshot(Instant.now(), BigDecimal.ZERO,
                    Collections.emptyMap(), Collections.emptyList(), BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO));
        }

        assertEquals(50, tradeHistoryService.getHistory().size());
        verify(repository, atLeastOnce()).save(anyList());
    }
    @Test
    void init_HandlesNullLoaded() {
        when(repository.load()).thenReturn(null);
        tradeHistoryService.init();
        assertTrue(tradeHistoryService.getHistory().isEmpty());
    }

    @Test
    void getLatestSnapshot_ReturnsNullWhenEmpty() {
        assertNull(tradeHistoryService.getLatestSnapshot());
    }
}
