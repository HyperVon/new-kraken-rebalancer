package com.gemini.krakenbot.config

import com.gemini.krakenbot.service.history.HistoryDatabase
import org.slf4j.LoggerFactory
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

object DataDirectoryConfig {
    const val DATA_DIR_PROPERTY = "kraken.data.dir"
    const val DEFAULT_DATA_DIR = "rebalancer-data-v1"
    const val EXECUTION_DB_FILE = "execution.db"
    const val HISTORY_DB_FILE = "history.db"
    const val INITIALIZATION_MARKER_FILE = ".initialization-in-progress"
    const val INITIALIZATION_LOCK_FILE = ".initialization.lock"

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
        withInitializationLock(dataDir) { initializeUnlocked(it) }
    }

    /**
     * Normal startup entry: creates both stores automatically on a fresh data directory,
     * resumes an interrupted initialization, and leaves an initialized directory untouched.
     * A mixed directory (only one store present) still fails closed because it signals a
     * half-copied or tampered data directory rather than a first run.
     */
    fun ensureDataDirectory(dataDir: Path = resolveDataDirectory()) {
        Files.createDirectories(dataDir)
        withInitializationLock(dataDir) { dir ->
            val executionDb = executionDatabasePath(dir)
            val historyDb = historyDatabasePath(dir)
            val initializationMarker = dir.resolve(INITIALIZATION_MARKER_FILE)
            if (Files.exists(initializationMarker)) {
                initializeUnlocked(dir)
                return@withInitializationLock
            }
            val executionPresent = Files.exists(executionDb)
            val historyPresent = Files.exists(historyDb)
            when {
                executionPresent && historyPresent -> Unit

                !executionPresent && !historyPresent -> {
                    log.info("No data directory stores found in {}; creating them now.", dir)
                    initializeUnlocked(dir)
                }

                else -> throw IllegalStateException(
                    "Data directory $dir contains only one of execution.db / history.db; " +
                        "restore the matching file or start from a fresh directory.",
                )
            }
        }
    }

    private fun initializeUnlocked(dataDir: Path) {
        val executionDb = executionDatabasePath(dataDir)
        val historyDb = historyDatabasePath(dataDir)
        val initializationMarker = dataDir.resolve(INITIALIZATION_MARKER_FILE)
        val executionMarker = executionDb.resolveSibling(
            "${executionDb.fileName}${ExecutionDatabase.JOURNAL_MARKER_SUFFIX}",
        )
        val resuming = Files.exists(initializationMarker)
        if (!resuming) {
            check(!Files.exists(executionDb) && !Files.exists(executionMarker)) {
                "Execution database or its identity marker already exists; refusing to replace it."
            }
            check(!Files.exists(historyDb)) {
                "History database already exists; refusing to initialize a partial data directory."
            }
            Files.createFile(initializationMarker)
        } else {
            // A stray marker must not turn a half-copied directory into a mixed pairing:
            // stores that predate the marker were not created by this initialization.
            // A foreign store copied in after the marker is indistinguishable from a
            // genuine interrupted init without nonce-attesting both stores' identity
            // surfaces; account-identity verification and live-order binding remain
            // the downstream guards (see SECURITY.md).
            val markerTime = Files.getLastModifiedTime(initializationMarker)
            val foreign = listOf(executionDb, executionMarker, historyDb)
                .filter { Files.exists(it) && Files.getLastModifiedTime(it) < markerTime }
            check(foreign.isEmpty()) {
                "Data directory $dataDir holds stores that predate its initialization marker " +
                    "(likely half-copied or tampered); restore the matching file or start from a fresh directory."
            }
        }

        if (resuming) {
            ExecutionDatabase.resumeFreshInitialization(executionDb.toString())
        } else {
            ExecutionDatabase.initialize(executionDb.toString())
        }
        val historyDatabase = HistoryDatabase(historyDb)
        historyDatabase.initializeFreshSchema()
        Files.deleteIfExists(initializationMarker)
        log.info("Initialized fresh execution and history databases in {}", dataDir)
    }

    /** Exclusive cross-process guard so overlapping startups cannot race marker/store creation. */
    private fun <T> withInitializationLock(dataDir: Path, block: (Path) -> T): T {
        val lockFile = dataDir.resolve(INITIALIZATION_LOCK_FILE)
        FileChannel.open(
            lockFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            val lock =
                try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
            checkNotNull(lock) {
                "Data directory $dataDir is being initialized by another process; retry after it finishes."
            }
            try {
                return block(dataDir)
            } finally {
                lock.release()
            }
        }
    }
}
