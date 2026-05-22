package com.gemini.krakenbot.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gemini.krakenbot.model.PortfolioStats;
import com.gemini.krakenbot.repository.impl.PortfolioStatsRepositoryImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class PortfolioStatsRepositoryImplTest {

    private PortfolioStatsRepository repository;
    private static final String TEST_FILE = "portfolio-stats.json";

    @BeforeEach
    void setUp() {
        repository = new PortfolioStatsRepositoryImpl(new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        File file = new File(TEST_FILE);
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Test
    void load_ReturnsZeroWhenFileDoesNotExist() {
        // Ensure file is gone
        //noinspection ResultOfMethodCallIgnored
        new File(TEST_FILE).delete();

        PortfolioStats stats = repository.load();
        assertNotNull(stats);
        assertEquals(BigDecimal.ZERO, stats.getAllTimeHigh());
    }

    @Test
    void saveAndLoad_PersistsData() {
        PortfolioStats stats = new PortfolioStats(new BigDecimal("1000.50"));
        repository.save(stats);

        PortfolioStats loaded = repository.load();
        assertNotNull(loaded);
        assertEquals(0, new BigDecimal("1000.50").compareTo(loaded.getAllTimeHigh()));
    }

    @Test
    void load_HandlesIOException() throws Exception {
        File file = new File(TEST_FILE);
        java.nio.file.Files.writeString(file.toPath(), "{invalid json}");
        
        PortfolioStats stats = repository.load();
        assertNotNull(stats);
        assertEquals(BigDecimal.ZERO, stats.getAllTimeHigh());
    }

    @Test
    void save_HandlesIOException() throws Exception {
        ObjectMapper mockMapper = org.mockito.Mockito.mock(ObjectMapper.class);
        org.mockito.Mockito.doThrow(new java.io.IOException("simulated error"))
            .when(mockMapper).writeValue(org.mockito.ArgumentMatchers.any(File.class), org.mockito.ArgumentMatchers.any());

        PortfolioStatsRepositoryImpl errRepository = new PortfolioStatsRepositoryImpl(mockMapper);
        PortfolioStats stats = new PortfolioStats(BigDecimal.TEN);
        
        assertDoesNotThrow(() -> errRepository.save(stats));
    }
}
