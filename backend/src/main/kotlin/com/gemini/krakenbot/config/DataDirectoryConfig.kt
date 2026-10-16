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
    const val INITIALIZATION_MARKER_FILE = ".initialization-in-progress"

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
        val initializationMarker = dataDir.resolve(INITIALIZATION_MARKER_FILE)
        val resuming = Files.exists(initializationMarker)
        if (!resuming) {
            val executionMarker = executionDb.resolveSibling(
                "${executionDb.fileName}${ExecutionDatabase.JOURNAL_MARKER_SUFFIX}",
            )
            check(!Files.exists(executionDb) && !Files.exists(executionMarker)) {
                "Execution database or its identity marker already exists; refusing to replace it."
            }
            check(!Files.exists(historyDb)) {
                "History database already exists; refusing to initialize a partial data directory."
            }
            Files.createFile(initializationMarker)
        }

        if (resuming) {
            ExecutionDatabase.resumeFreshInitialization(executionDb.toString())
        } else {
            ExecutionDatabase.initialize(executionDb.toString())
        }
        val historyDatabase = HistoryDatabase(historyDb)
        historyDatabase.initializeFreshSchema()
        Files.delete(initializationMarker)
        log.info("Initialized fresh execution and history databases in {}", dataDir)
    }

    /**
     * Normal startup entry: creates both stores automatically on a fresh data directory,
     * resumes an interrupted initialization, and leaves an initialized directory untouched.
     * A mixed directory (only one store present) still fails closed because it signals a
     * half-copied or tampered data directory rather than a first run.
     */
    fun ensureDataDirectory(dataDir: Path = resolveDataDirectory()) {
        val executionDb = executionDatabasePath(dataDir)
        val historyDb = historyDatabasePath(dataDir)
        val initializationMarker = dataDir.resolve(INITIALIZATION_MARKER_FILE)
        if (Files.exists(initializationMarker)) {
            initializeDataDirectory(dataDir)
            return
        }
        val executionPresent = Files.exists(executionDb)
        val historyPresent = Files.exists(historyDb)
        when {
            executionPresent && historyPresent -> return

            !executionPresent && !historyPresent -> {
                log.info("No data directory stores found in {}; creating them now.", dataDir)
                initializeDataDirectory(dataDir)
            }

            else -> throw IllegalStateException(
                "Data directory $dataDir contains only one of execution.db / history.db; " +
                    "restore the matching file or start from a fresh directory.",
            )
        }
    }
}
