package com.gemini.krakenbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gemini.krakenbot.config.Allocation;
import com.gemini.krakenbot.config.AppConfig;
import com.gemini.krakenbot.config.KrakenCredentials;
import com.gemini.krakenbot.config.Settings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectWriter;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

class ConfigServiceTest {

    private ConfigService configService;
    private ObjectMapper objectMapper;
    private File tempFile;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        objectMapper = new ObjectMapper();
        tempFile = tempDir.resolve("test-config.json").toFile();

        // Write initial valid config
        createValidConfig(tempFile);

        configService = new ConfigService(objectMapper, tempFile.getAbsolutePath());
    }

    private void createValidConfig(File file) throws IOException {
        Settings settings = new Settings(60L, 2.0, 1.0, true, 0.0, 1.0);
        AppConfig config = new AppConfig(new KrakenCredentials("k", "s"), settings,
                List.of(new Allocation("USD", 100.0)));
        objectMapper.writeValue(file, config);
    }

    @Test
    void loadConfig_Success() throws IOException {
        configService.loadConfig();
        assertNotNull(configService.getConfig());
        assertEquals("USD", configService.getConfig().allocations().get(0).symbol());
    }

    @Test
    void loadConfig_FileNotFound() {
        File missingFile = new File(tempFile.getParent(), "missing.json");
        configService = new ConfigService(objectMapper, missingFile.getAbsolutePath());

        RuntimeException ex = assertThrows(RuntimeException.class, () -> configService.loadConfig());
        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    void updateConfig_Success() throws IOException {
        configService.loadConfig();
        AppConfig oldConfig = configService.getConfig();

        AppConfig newConfig = new AppConfig(oldConfig.kraken(), oldConfig.settings(),
                List.of(new Allocation("USD", 50.0), new Allocation("BTC", 50.0)));

        configService.updateConfig(newConfig);

        assertEquals(2, configService.getConfig().allocations().size());

        // Verify persistence
        AppConfig readBack = objectMapper.readValue(tempFile, AppConfig.class);
        assertEquals(2, readBack.allocations().size());
    }

    @Test
    void validateConfig_InvalidTotal() throws IOException {
        configService.loadConfig();
        AppConfig oldConfig = configService.getConfig();
        AppConfig invalidConfig = new AppConfig(oldConfig.kraken(), oldConfig.settings(),
                List.of(new Allocation("USD", 90.0)));

        assertThrows(RuntimeException.class, () -> configService.updateConfig(invalidConfig));
    }

    @Test
    void validateConfig_NoUSD() throws IOException {
        configService.loadConfig();
        AppConfig oldConfig = configService.getConfig();
        AppConfig invalidConfig = new AppConfig(oldConfig.kraken(), oldConfig.settings(),
                List.of(new Allocation("BTC", 100.0)));

        assertThrows(RuntimeException.class, () -> configService.updateConfig(invalidConfig));
    }

    @Test
    void saveConfig_Exception() throws IOException {
        ObjectMapper mockMapper = Mockito.mock(ObjectMapper.class);
        // Mock writerWithDefaultPrettyPrinter
        ObjectWriter mockWriter = Mockito.mock(ObjectWriter.class);
        Mockito.when(mockMapper.writerWithDefaultPrettyPrinter()).thenReturn(mockWriter);

        Mockito.doThrow(new IOException("Write error")).when(mockWriter)
                .writeValue(ArgumentMatchers.any(File.class), ArgumentMatchers.any());

        // We also need readValue to work for initial load if we call loadConfig(), or
        // we can just inject dependency and call updateConfig directly
        // But updateConfig calls validate, so config must be valid

        configService = new ConfigService(mockMapper, tempFile.getAbsolutePath());

        // We don't need to loadConfig first if we just call updateConfig, BUT
        // updateConfig checks existing config?
        // No, updateConfig(newConfig) validates newConfig.

        // Setup valid payload
        AppConfig validConfig = new AppConfig(new KrakenCredentials("k", "s"),
                new Settings(60L, 2.0, 1.0, true, 0.0, 1.0),
                List.of(new Allocation("USD", 100.0)));

        assertThrows(RuntimeException.class, () -> configService.updateConfig(validConfig));
    }
}
