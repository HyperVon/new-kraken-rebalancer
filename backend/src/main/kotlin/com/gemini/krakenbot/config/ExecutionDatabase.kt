package com.gemini.krakenbot.config

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Durable order-submission journal for the active runtime. */
class ExecutionDatabase internal constructor(val path: String, private val jdbcUrl: String) {
    private val readinessLock = Any()

    @Volatile
    private var readyForSubmission = false

    fun connect(): Connection {
        val connection = DriverManager.getConnection(jdbcUrl)
        try {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute("PRAGMA busy_timeout = $BUSY_TIMEOUT_MILLIS")
                statement.execute("PRAGMA synchronous = FULL")
                check(statement.executeQuery("PRAGMA foreign_keys").use { it.next() && it.getInt(1) == 1 }) {
                    "Execution database did not enable foreign-key checks on this connection."
                }
                check(
                    statement.executeQuery("PRAGMA busy_timeout").use {
                        it.next() &&
                            it.getInt(1) == BUSY_TIMEOUT_MILLIS
                    },
                ) {
                    "Execution database did not apply its bounded busy timeout on this connection."
                }
                check(
                    statement.executeQuery("PRAGMA synchronous").use {
                        it.next() &&
                            it.getInt(1) == SQLITE_SYNCHRONOUS_FULL
                    },
                ) {
                    "Execution database did not apply synchronous=FULL on this connection."
                }
                if (!isMemoryDatabase(path)) {
                    check(
                        statement.executeQuery("PRAGMA journal_mode").use {
                            it.next() && it.getString(1).equals("wal", ignoreCase = true)
                        },
                    ) {
                        "Execution database is not in WAL mode."
                    }
                }
            }
            return connection
        } catch (e: Exception) {
            connection.close()
            throw e
        }
    }

    fun journalId(): String = connect().use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT journal_id FROM execution_journal_metadata WHERE singleton_id = 1")
                .use { resultSet ->
                    check(resultSet.next()) { "Execution journal identity is missing." }
                    resultSet.getString(1)
                }
        }
    }

    /** Prevents an unmarked populated directory from being rebound to a newly authenticated account. */
    fun hasAccountEvidence(): Boolean = connect().use { connection ->
        val bindingInitialized = connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT account_binding_initialized FROM execution_journal_metadata WHERE singleton_id = 1",
            ).use { resultSet ->
                check(resultSet.next()) { "Execution journal account marker is missing." }
                resultSet.getInt(1) != 0
            }
        }
        bindingInitialized || connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT EXISTS(SELECT 1 FROM execution_account_binding LIMIT 1) OR " +
                    "EXISTS(SELECT 1 FROM execution_account_binding_audit LIMIT 1) OR " +
                    "EXISTS(SELECT 1 FROM execution_order_intents LIMIT 1)",
            ).use { resultSet -> resultSet.next() && resultSet.getInt(1) != 0 }
        }
    }

    fun markReadyForSubmission() {
        synchronized(readinessLock) { readyForSubmission = true }
    }

    fun ensureReadyForSubmission() {
        check(readyForSubmission) {
            "Execution journal restart recovery has not completed."
        }
        check(!isMemoryDatabase(path)) {
            "Live order submission requires a file-backed execution journal."
        }
    }

    private fun verifyInitializedSchema() {
        val actualTables = connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
                ).use { resultSet ->
                    buildSet { while (resultSet.next()) add(resultSet.getString(1)) }
                }
            }
        }
        check(actualTables == REQUIRED_TABLES) {
            "Execution database schema is incomplete or unrecognized; refusing to open it."
        }
        check(journalId().isNotBlank()) { "Execution journal identity is missing." }
    }

    private fun createFreshSchema() {
        connect().use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE execution_journal_metadata (
                            singleton_id INTEGER PRIMARY KEY CHECK(singleton_id = 1),
                            journal_id TEXT NOT NULL,
                            account_binding_initialized INTEGER NOT NULL DEFAULT 0
                                CHECK(account_binding_initialized IN (0, 1))
                        )
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        CREATE TABLE execution_order_intents (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            cycle_id TEXT,
                            client_order_id TEXT,
                            client_order_id_ambiguous INTEGER NOT NULL DEFAULT 0
                                CHECK(client_order_id_ambiguous IN (0, 1)),
                            pair TEXT NOT NULL,
                            symbol TEXT NOT NULL,
                            side TEXT NOT NULL,
                            volume TEXT NOT NULL,
                            usd_amount TEXT NOT NULL,
                            expected_price TEXT,
                            created_at INTEGER NOT NULL,
                            state TEXT NOT NULL CHECK(state IN ('PENDING', 'UNCERTAIN', 'CONFIRMED', 'REJECTED')),
                            order_txid TEXT,
                            error_message TEXT,
                            resolved_at INTEGER,
                            resolution_evidence TEXT,
                            outcome_volume TEXT
                        )
                        """.trimIndent(),
                    )
                    statement.execute(
                        "CREATE UNIQUE INDEX ux_execution_client_order_id " +
                            "ON execution_order_intents(client_order_id) " +
                            "WHERE client_order_id IS NOT NULL AND client_order_id_ambiguous = 0",
                    )
                    statement.execute(
                        "CREATE INDEX idx_execution_intents_state_created " +
                            "ON execution_order_intents(state, created_at)",
                    )
                    statement.execute(
                        """
                        CREATE TABLE execution_account_binding (
                            singleton_id INTEGER PRIMARY KEY CHECK(singleton_id = 1),
                            account_identity_digest TEXT NOT NULL,
                            credential_generation_digest TEXT NOT NULL,
                            binding_version INTEGER NOT NULL,
                            verification_method TEXT NOT NULL,
                            verified_at INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        CREATE TABLE execution_account_binding_audit (
                            event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                            account_identity_digest TEXT NOT NULL,
                            previous_credential_generation_digest TEXT,
                            credential_generation_digest TEXT NOT NULL,
                            verification_method TEXT NOT NULL,
                            verified_at INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    connection.prepareStatement(
                        "INSERT INTO execution_journal_metadata(singleton_id, journal_id) VALUES (1, ?)",
                    ).use { statement ->
                        statement.setString(1, UUID.randomUUID().toString())
                        statement.executeUpdate()
                    }
                }
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }
    }

    private fun verifyOrWriteJournalMarker(markerPath: Path, createIfMissing: Boolean) {
        val id = journalId()
        if (Files.exists(markerPath)) {
            val recordedId = runCatching { Files.readString(markerPath).trim() }.getOrElse {
                throw IOException("Execution journal identity marker cannot be read.", it)
            }
            if (recordedId != id) {
                throw IOException("Execution database identity does not match its initialized journal marker.")
            }
            return
        }
        if (!createIfMissing) {
            throw IOException("Execution journal identity marker is missing; refusing to initialize this database.")
        }
        writeJournalMarker(markerPath, "$id\n")
    }

    companion object {
        private const val BUSY_TIMEOUT_MILLIS = 5_000
        private const val SQLITE_SYNCHRONOUS_FULL = 2
        const val JOURNAL_MARKER_SUFFIX = ".journal-id"
        private val log = LoggerFactory.getLogger(ExecutionDatabase::class.java)
        private val keepAliveConnections = ConcurrentHashMap<String, Connection>()
        private val REQUIRED_TABLES = setOf(
            "execution_journal_metadata",
            "execution_order_intents",
            "execution_account_binding",
            "execution_account_binding_audit",
        )

        /** Explicitly creates the active execution journal at its final data-directory path. */
        fun initialize(executionDbPath: String): ExecutionDatabase {
            if (isMemoryDatabase(executionDbPath)) return initializeMemory(executionDbPath)

            val databasePath = filePath(executionDbPath)
            val markerPath = journalMarkerPath(databasePath)
            check(!Files.exists(databasePath) && !Files.exists(markerPath)) {
                "Execution database or its identity marker already exists; refusing to replace it."
            }
            databasePath.parent?.let { Files.createDirectories(it) }
            try {
                Files.createFile(databasePath)
            } catch (e: FileAlreadyExistsException) {
                throw IOException("Execution database already exists; refusing to replace it.", e)
            }
            val database = ExecutionDatabase(executionDbPath, buildSqliteUrl(executionDbPath))
            try {
                database.enableWalForInitialization()
                database.createFreshSchema()
                database.verifyInitializedSchema()
                database.verifyOrWriteJournalMarker(markerPath, createIfMissing = true)
            } catch (e: Exception) {
                deleteSqliteArtifacts(databasePath)
                throw e
            }
            log.info("Initialized fresh execution journal at {}", executionDbPath)
            return database
        }

        /** Resumes a fresh paired-store initialization whose outer marker proves this path began empty. */
        fun resumeFreshInitialization(executionDbPath: String): ExecutionDatabase {
            val databasePath = filePath(executionDbPath)
            val markerPath = journalMarkerPath(databasePath)
            val database = ExecutionDatabase(executionDbPath, buildSqliteUrl(executionDbPath))
            val schemaComplete = runCatching { database.verifyInitializedSchema() }.isSuccess
            if (schemaComplete) {
                database.verifyOrWriteJournalMarker(markerPath, createIfMissing = !Files.exists(markerPath))
                return database
            }

            deleteSqliteArtifacts(databasePath)
            Files.deleteIfExists(markerPath)
            return initialize(executionDbPath)
        }

        /** Opens an explicitly initialized journal and never creates or upgrades a file-backed store. */
        fun init(executionDbPath: String): ExecutionDatabase {
            if (isMemoryDatabase(executionDbPath)) return initializeMemory(executionDbPath)

            val databasePath = filePath(executionDbPath)
            requirePairedInitializationComplete(databasePath)
            val markerPath = journalMarkerPath(databasePath)
            if (!Files.isRegularFile(databasePath)) {
                throw IOException(
                    "Initialized execution database is missing; refusing to create a replacement journal.",
                )
            }
            if (!Files.isRegularFile(markerPath)) {
                throw IOException("Execution journal identity marker is missing; refusing to open this database.")
            }
            val database = ExecutionDatabase(executionDbPath, buildSqliteUrl(executionDbPath))
            database.verifyInitializedSchema()
            database.verifyOrWriteJournalMarker(markerPath, createIfMissing = false)
            requirePairedInitializationComplete(databasePath)
            log.info("Opened execution journal at {}", executionDbPath)
            return database
        }

        private fun requirePairedInitializationComplete(databasePath: Path) {
            if (Files.exists(databasePath.resolveSibling(DataDirectoryConfig.INITIALIZATION_MARKER_FILE))) {
                throw IOException("Data directory initialization is incomplete; refusing normal startup.")
            }
        }

        private fun initializeMemory(executionDbPath: String): ExecutionDatabase {
            val jdbcUrl = buildSqliteUrl(executionDbPath)
            keepAliveConnections.computeIfAbsent(jdbcUrl) { url ->
                DriverManager.getConnection(url).also(::configureConnectionPragmas)
            }
            val database = ExecutionDatabase(executionDbPath, jdbcUrl)
            database.createFreshSchema()
            database.verifyInitializedSchema()
            return database
        }

        private fun configureConnectionPragmas(connection: Connection) {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute("PRAGMA busy_timeout = $BUSY_TIMEOUT_MILLIS")
                statement.execute("PRAGMA synchronous = FULL")
            }
        }

        private fun configureFileConnection(connection: Connection, enableWal: Boolean) {
            configureConnectionPragmas(connection)
            connection.createStatement().use { statement ->
                if (enableWal) {
                    statement.executeQuery("PRAGMA journal_mode = WAL").use { resultSet ->
                        check(resultSet.next() && resultSet.getString(1).equals("wal", ignoreCase = true)) {
                            "Execution database could not enter WAL mode."
                        }
                    }
                }
            }
        }

        private fun filePath(databasePath: String): Path {
            if (isMemoryDatabase(databasePath)) throw IllegalArgumentException("Memory databases have no file path.")
            val rawPath = databasePath
                .removePrefix("jdbc:sqlite:")
                .substringBefore('?')
                .removePrefix("file:")
            return Path.of(rawPath).toAbsolutePath().normalize()
        }

        private fun journalMarkerPath(databasePath: Path): Path =
            databasePath.resolveSibling("${databasePath.fileName}$JOURNAL_MARKER_SUFFIX")

        private fun buildSqliteUrl(dbPath: String): String = when {
            dbPath.startsWith("jdbc:sqlite:") -> dbPath
            dbPath == ":memory:" -> "jdbc:sqlite:file:execution-${UUID.randomUUID()}?mode=memory&cache=shared"
            else -> "jdbc:sqlite:$dbPath"
        }

        internal fun isMemoryDatabase(dbPath: String): Boolean {
            val sqlitePath = dbPath.removePrefix("jdbc:sqlite:")
            val databaseName = sqlitePath.substringBefore('?')
            if (databaseName == ":memory:" || databaseName == "file::memory:") return true
            if (!databaseName.startsWith("file:")) return false

            return sqlitePath.substringAfter('?', "").split('&').any { it == "mode=memory" }
        }

        private fun writeJournalMarker(path: Path, contents: String) {
            try {
                FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                    val buffer = ByteBuffer.wrap(contents.toByteArray(Charsets.UTF_8))
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
            } catch (e: FileAlreadyExistsException) {
                val recordedId = runCatching { Files.readString(path).trim() }.getOrElse {
                    throw IOException("Execution journal identity marker cannot be read.", it)
                }
                if (recordedId != contents.trim()) {
                    throw IOException("Execution database identity does not match its initialized journal marker.", e)
                }
            }
        }

        private fun deleteSqliteArtifacts(databasePath: Path) {
            listOf("-wal", "-shm", "-journal", "").forEach { suffix ->
                Files.deleteIfExists(databasePath.resolveSibling("${databasePath.fileName}$suffix"))
            }
        }

        private fun ExecutionDatabase.enableWalForInitialization() {
            DriverManager.getConnection(jdbcUrl).use { connection ->
                configureFileConnection(connection, enableWal = true)
            }
        }
    }
}
