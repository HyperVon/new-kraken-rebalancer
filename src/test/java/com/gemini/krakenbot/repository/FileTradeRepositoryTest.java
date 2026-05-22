package com.gemini.krakenbot.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gemini.krakenbot.model.PortfolioSnapshot;
import com.gemini.krakenbot.repository.impl.FileTradeRepositoryImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class FileTradeRepositoryTest {

    private FileTradeRepositoryImpl repository;
    private static final String TEST_FILE = "trade-history.json";

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules(); // Support for JavaTimeModule/Instant
        repository = new FileTradeRepositoryImpl(objectMapper);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @AfterEach
    void tearDown() {
        File file = new File(TEST_FILE);
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    @Test
    void testSaveAndLoad() {
        PortfolioSnapshot snapshot = new PortfolioSnapshot(
                Instant.now(),
                BigDecimal.valueOf(1000.0),
                Collections.emptyMap(),
                List.of("Action 1"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        List<PortfolioSnapshot> history = new ArrayList<>();
        history.add(snapshot);

        repository.save(history);

        List<PortfolioSnapshot> loaded = repository.load();
        assertNotNull(loaded);
        assertEquals(1, loaded.size());
        assertEquals(0, snapshot.getTotalValueUSD().compareTo(loaded.getFirst().getTotalValueUSD()));
        assertEquals(snapshot.getActions(), loaded.getFirst().getActions());
    }

    @Test
    void testLoadEmpty() {
        // Ensure file is gone
        //noinspection ResultOfMethodCallIgnored
        new File(TEST_FILE).delete();

        List<PortfolioSnapshot> loaded = repository.load();
        assertNotNull(loaded);
        assertTrue(loaded.isEmpty());
    }

    @Test
    void testLoadCorruptedFile() throws IOException {
        // Create a corrupted file
        File file = new File(TEST_FILE);
        try (FileWriter writer = new FileWriter(file)) {
            writer.write("{ incomplete json ");
        }

        List<PortfolioSnapshot> loaded = repository.load();
        assertNotNull(loaded);
        assertTrue(loaded.isEmpty(), "Should return empty list on corrupted file");
    }

    @Test
    void testSaveError() throws IOException {
        ObjectMapper mockMapper = Mockito.mock(ObjectMapper.class);
        Mockito.doThrow(new IOException("Write failed")).when(mockMapper)
                .writeValue(ArgumentMatchers.any(File.class), ArgumentMatchers.any());

        FileTradeRepositoryImpl repo = new FileTradeRepositoryImpl(mockMapper);

        // Should log error but not throw
        assertDoesNotThrow(() -> repo.save(Collections.emptyList()));
    }
}
