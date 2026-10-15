package com.gemini.krakenbot.config

import com.gemini.krakenbot.service.history.HistoryDatabase
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

object DataDirectoryConfig {
    const val DATA_DIR_PROPERTY = "kraken.data.dir"
    const val DEFAULT_DATA_DIR = "rebalancer-data-v1"
    const val EXECUTION_DB_FILE = "execution.db"
    const val HISTORY_DB_FILE = "history.db"

    private val log = LoggerFactory.getLogger(DataDirectoryConfig::class.java)

    fun resolveDataDirectory(): Path {
        val raw = System.getProperty(DATA_DIR_PROPERTY)?.trim()?.takeIf(String::isNotEmpty)
            ?: System.getenv("KRAKEN_DATA_DIR")?.trim()?.takeIf(String::isNotEmpty)
            ?: DEFAULT_DATA_DIR
        return Path.of(raw).toAbsolutePath().normalize()
    }

    fun executionDatabasePath(dataDir: Path = resolveDataDirectory()): Path = dataDir.resolve(EXECUTION_DB_FILE)

    fun historyDatabasePath(dataDir: Path = resolveDataDirectory()): Path = dataDir.resolve(HISTORY_DB_FILE)

    fun initializeDataDirectory(dataDir: Path = resolveDataDirectory()) {
        Files.createDirectories(dataDir)
        val executionDb = executionDatabasePath(dataDir)
        val historyDb = historyDatabasePath(dataDir)
        ExecutionDatabase.initialize(executionDb.toString())
        val historyDatabase = HistoryDatabase(historyDb)
        historyDatabase.initializeFreshSchema()
        log.info("Initialized fresh execution and history databases in {}", dataDir)
    }
}
