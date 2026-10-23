package com.gemini.krakenbot.config

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
    const val LEGACY_REPORTING_DB_FILE = "history.db"
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

    fun initializeDataDirectory(dataDir: Path = resolveDataDirectory()) {
        Files.createDirectories(dataDir)
        withInitializationLock(dataDir) { initializeUnlocked(it) }
    }

    /**
     * Normal startup entry: creates the execution journal automatically on a fresh data
     * directory, resumes an interrupted initialization, and leaves an initialized directory
     * untouched.
     */
    fun ensureDataDirectory(dataDir: Path = resolveDataDirectory()) {
        Files.createDirectories(dataDir)
        withInitializationLock(dataDir) { dir ->
            val executionDb = executionDatabasePath(dir)
            val initializationMarker = dir.resolve(INITIALIZATION_MARKER_FILE)
            if (Files.exists(initializationMarker)) {
                initializeUnlocked(dir)
                return@withInitializationLock
            }
            val executionPresent = Files.exists(executionDb)
            if (!executionPresent) {
                log.info("No execution database found in {}; creating it now.", dir)
                initializeUnlocked(dir)
            }
        }
    }

    private fun initializeUnlocked(dataDir: Path) {
        val executionDb = executionDatabasePath(dataDir)
        val initializationMarker = dataDir.resolve(INITIALIZATION_MARKER_FILE)
        val executionMarker = executionDb.resolveSibling(
            "${executionDb.fileName}${ExecutionDatabase.JOURNAL_MARKER_SUFFIX}",
        )
        val resuming = Files.exists(initializationMarker)
        if (!Files.exists(executionDb)) {
            check(!Files.exists(dataDir.resolve(LEGACY_REPORTING_DB_FILE))) {
                "Legacy reporting data exists without an execution database; refusing to initialize a new journal. " +
                    "Restore execution.db or start from a fresh data directory."
            }
        }
        if (!resuming) {
            check(!Files.exists(executionDb) && !Files.exists(executionMarker)) {
                "Execution database or its identity marker already exists; refusing to replace it."
            }
            Files.createFile(initializationMarker)
        } else {
            // A stray marker must not authorize a copied-in execution database:
            // an execution journal that predates the marker was not created by this initialization.
            // A foreign database copied in after the marker is indistinguishable from a
            // genuine interrupted init without nonce-attesting both stores' identity
            // surfaces; account-identity verification and live-order binding remain
            // the downstream guards (see SECURITY.md).
            val markerTime = Files.getLastModifiedTime(initializationMarker)
            val foreign = listOf(executionDb, executionMarker)
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
        Files.deleteIfExists(initializationMarker)
        log.info("Initialized fresh execution database in {}", dataDir)
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
