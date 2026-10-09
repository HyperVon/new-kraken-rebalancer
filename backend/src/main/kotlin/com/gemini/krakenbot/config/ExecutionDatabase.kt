package com.gemini.krakenbot.config

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The durable database used only by the order-submission safety journal. */
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

    fun initializeSchema() {
        connect().use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE IF NOT EXISTS execution_schema_migrations (
                            version INTEGER PRIMARY KEY,
                            name TEXT NOT NULL,
                            applied_at INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    val maxVersion = statement.executeQuery(
                        "SELECT COALESCE(MAX(version), 0) FROM execution_schema_migrations",
                    ).use { resultSet ->
                        check(resultSet.next()) { "Execution schema version could not be read." }
                        resultSet.getInt(1)
                    }
                    check(maxVersion <= CURRENT_EXECUTION_SCHEMA_VERSION) {
                        "Execution database schema version $maxVersion is newer than this binary supports " +
                            "($CURRENT_EXECUTION_SCHEMA_VERSION)."
                    }
                    statement.execute(
                        """
                        CREATE TABLE IF NOT EXISTS execution_journal_metadata (
                            singleton_id INTEGER PRIMARY KEY CHECK(singleton_id = 1),
                            journal_id TEXT NOT NULL,
                            account_binding_initialized INTEGER NOT NULL DEFAULT 0
                                CHECK(account_binding_initialized IN (0, 1))
                        )
                        """.trimIndent(),
                    )
                    val hasAccountBindingAnchor = statement.executeQuery(
                        "PRAGMA table_info(execution_journal_metadata)",
                    ).use { resultSet ->
                        generateSequence { if (resultSet.next()) resultSet.getString("name") else null }
                            .any { it == "account_binding_initialized" }
                    }
                    if (!hasAccountBindingAnchor) {
                        statement.execute(
                            "ALTER TABLE execution_journal_metadata ADD COLUMN account_binding_initialized " +
                                "INTEGER NOT NULL DEFAULT 0 CHECK(account_binding_initialized IN (0, 1))",
                        )
                    }
                    statement.execute(
                        """
                        CREATE TABLE IF NOT EXISTS execution_order_intents (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            cycle_id TEXT,
                            client_order_id TEXT,
                            client_order_id_ambiguous INTEGER NOT NULL DEFAULT 0,
                            pair TEXT NOT NULL,
                            symbol TEXT NOT NULL,
                            side TEXT NOT NULL,
                            volume TEXT NOT NULL,
                            usd_amount TEXT NOT NULL,
                            expected_price TEXT,
                            created_at INTEGER NOT NULL,
                            state TEXT NOT NULL,
                            order_txid TEXT,
                            error_message TEXT,
                            resolved_at INTEGER,
                            resolution_evidence TEXT,
                            outcome_volume TEXT,
                            legacy_source_id TEXT,
                            legacy_intent_id INTEGER,
                            legacy_trade_id INTEGER,
                            legacy_source_state TEXT,
                            CHECK(state IN ('PENDING', 'UNCERTAIN', 'CONFIRMED', 'REJECTED'))
                        )
                        """.trimIndent(),
                    )
                    statement.execute(
                        "CREATE UNIQUE INDEX IF NOT EXISTS ux_execution_client_order_id " +
                            "ON execution_order_intents(client_order_id) " +
                            "WHERE client_order_id IS NOT NULL AND client_order_id_ambiguous = 0",
                    )
                    statement.execute(
                        "CREATE UNIQUE INDEX IF NOT EXISTS ux_execution_legacy_intent " +
                            "ON execution_order_intents(legacy_source_id, legacy_intent_id) " +
                            "WHERE legacy_source_id IS NOT NULL AND legacy_intent_id IS NOT NULL",
                    )
                    statement.execute(
                        "CREATE INDEX IF NOT EXISTS idx_execution_intents_state_created " +
                            "ON execution_order_intents(state, created_at)",
                    )
                    statement.execute(
                        """
                        CREATE TABLE IF NOT EXISTS execution_projection_outbox (
                            event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                            intent_id INTEGER NOT NULL REFERENCES execution_order_intents(id) ON DELETE RESTRICT,
                            revision INTEGER NOT NULL,
                            UNIQUE(intent_id, revision),
                            CHECK(revision > 0)
                        )
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        CREATE TABLE IF NOT EXISTS execution_legacy_migrations (
                            source_id TEXT PRIMARY KEY,
                            source_path TEXT NOT NULL,
                            backup_path TEXT,
                            backup_sha256 TEXT,
                            imported_intents INTEGER NOT NULL,
                            completed_at INTEGER NOT NULL,
                            cutover_guard_written INTEGER NOT NULL DEFAULT 0 CHECK(cutover_guard_written IN (0, 1))
                        )
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        CREATE TABLE IF NOT EXISTS execution_account_binding (
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
                        CREATE TABLE IF NOT EXISTS execution_account_binding_audit (
                            event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                            account_identity_digest TEXT NOT NULL,
                            previous_credential_generation_digest TEXT,
                            credential_generation_digest TEXT NOT NULL,
                            verification_method TEXT NOT NULL,
                            verified_at INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    statement.execute(
                        "INSERT OR IGNORE INTO execution_journal_metadata(singleton_id, journal_id) VALUES (1, '${UUID.randomUUID()}')",
                    )
                    if (maxVersion == 0) {
                        connection.prepareStatement(
                            "INSERT INTO execution_schema_migrations(version, name, applied_at) VALUES (?, ?, ?)",
                        ).use { insert ->
                            insert.setInt(1, 1)
                            insert.setString(2, "separate-execution-journal-and-projection-outbox")
                            insert.setLong(3, System.currentTimeMillis())
                            insert.executeUpdate()
                        }
                    }
                    if (maxVersion < 2) {
                        connection.prepareStatement(
                            "INSERT INTO execution_schema_migrations(version, name, applied_at) VALUES (?, ?, ?)",
                        ).use { insert ->
                            insert.setInt(1, 2)
                            insert.setString(2, "execution-account-binding")
                            insert.setLong(3, System.currentTimeMillis())
                            insert.executeUpdate()
                        }
                    }
                }
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
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

    fun markReadyForSubmission() {
        synchronized(readinessLock) { readyForSubmission = true }
    }

    fun ensureReadyForSubmission() {
        check(readyForSubmission) {
            "Execution journal legacy migration and restart recovery have not completed."
        }
        check(!isMemoryDatabase(path)) {
            "Live order submission requires a file-backed execution journal."
        }
    }

    companion object {
        private const val BUSY_TIMEOUT_MILLIS = 5_000
        private const val SQLITE_SYNCHRONOUS_FULL = 2
        private const val CURRENT_EXECUTION_SCHEMA_VERSION = 2
        private val log = LoggerFactory.getLogger(ExecutionDatabase::class.java)
        private val keepAliveConnections = ConcurrentHashMap<String, Connection>()

        private fun configureConnectionPragmas(connection: Connection) {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute("PRAGMA busy_timeout = $BUSY_TIMEOUT_MILLIS")
                statement.execute("PRAGMA synchronous = FULL")
            }
        }

        fun init(
            executionDbPath: String = resolveExecutionDatabasePath(),
            reportingDbPath: String = System.getProperty("kraken.db.path", "kraken-rebalancer.db"),
        ): ExecutionDatabase {
            requireSeparateDatabaseFiles(
                reportingDbPath = reportingDbPath,
                executionDbPath = executionDbPath,
            )
            val jdbcUrl = buildSqliteUrl(executionDbPath)
            val fileBackedPath = filePath(executionDbPath)
            val identityMarker = fileBackedPath?.let(::identityMarkerPath)
            val independentWitness = if (fileBackedPath == null) {
                null
            } else {
                configuredIndependentWitnessPath(reportingDbPath) ?: independentWitnessPath(
                    filePath(reportingDbPath) ?: fileBackedPath,
                )
            }
            if (isMemoryDatabase(executionDbPath) && jdbcUrl.contains("mode=memory")) {
                keepAliveConnections.computeIfAbsent(jdbcUrl) { url ->
                    DriverManager.getConnection(url).also(::configureConnectionPragmas)
                }
            } else {
                checkNotNull(fileBackedPath).parent?.let { Files.createDirectories(it) }
                val resolvedIndependentWitness = checkNotNull(independentWitness)
                val reportingPath = filePath(reportingDbPath)
                val reservedPaths = buildSet {
                    addAll(sqliteArtifactPaths(fileBackedPath))
                    reportingPath?.let { addAll(sqliteArtifactPaths(it)) }
                    identityMarker?.let(::add)
                }
                require(resolvedIndependentWitness !in reservedPaths) {
                    "The independent execution-journal witness must use a separate file path."
                }
                resolvedIndependentWitness.parent?.let { Files.createDirectories(it) }
                verifyJournalArtifacts(
                    fileBackedPath,
                    checkNotNull(identityMarker),
                    resolvedIndependentWitness,
                    reportingDbPath,
                )
            }
            val database = ExecutionDatabase(executionDbPath, jdbcUrl)
            if (!isMemoryDatabase(executionDbPath)) {
                DriverManager.getConnection(jdbcUrl).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("PRAGMA foreign_keys = ON")
                        statement.execute("PRAGMA busy_timeout = $BUSY_TIMEOUT_MILLIS")
                        statement.execute("PRAGMA synchronous = FULL")
                        statement.executeQuery("PRAGMA journal_mode = WAL").use { resultSet ->
                            check(resultSet.next() && resultSet.getString(1).equals("wal", ignoreCase = true)) {
                                "Execution database could not enter WAL mode."
                            }
                        }
                    }
                }
            }
            database.initializeSchema()
            if (identityMarker != null && independentWitness != null) {
                val journalId = database.journalId()
                database.verifyOrCreateIdentityMarker(identityMarker, journalId)
                database.verifyOrCreateIdentityMarker(independentWitness, journalId)
            }
            log.info("Execution journal initialized at {}", executionDbPath)
            return database
        }

        private fun identityMarkerPath(databasePath: Path): Path =
            databasePath.resolveSibling("${databasePath.fileName}.journal-id")

        private fun sqliteArtifactPaths(databasePath: Path): Set<Path> = buildSet {
            add(databasePath)
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                val sidecar = databasePath.resolveSibling("${databasePath.fileName}$suffix")
                add(if (Files.exists(sidecar)) sidecar.toRealPath() else sidecar)
            }
        }

        private fun independentWitnessPath(reportingDatabasePath: Path): Path =
            reportingDatabasePath.resolveSibling("${reportingDatabasePath.fileName}.execution-journal-witness")

        private fun configuredIndependentWitnessPath(reportingDbPath: String): Path? =
            System.getProperty("kraken.execution.witness.path")
                ?.takeIf(String::isNotBlank)
                ?.let(::filePath)
                ?: filePath(reportingDbPath)?.let(::independentWitnessPath)

        fun resolveExecutionDatabasePath(
            reportingDbPath: String = System.getProperty("kraken.db.path", "kraken-rebalancer.db"),
        ): String = System.getProperty("kraken.execution.db.path")?.takeIf(String::isNotBlank)
            ?: when {
                isMemoryDatabase(reportingDbPath) -> ":memory:"

                else -> {
                    val rawPath = reportingDbPath.removePrefix(
                        "jdbc:sqlite:",
                    ).substringBefore('?').removePrefix("file:")
                    val source = Path.of(rawPath).toAbsolutePath()
                    val fileName = source.fileName.toString()
                    val stem = fileName.substringBeforeLast('.', fileName)
                    source.resolveSibling("$stem-execution.db").toString()
                }
            }

        private fun requireSeparateDatabaseFiles(reportingDbPath: String, executionDbPath: String) {
            val reportingFile = filePath(reportingDbPath)
            val executionFile = filePath(executionDbPath)
            val sameFile = when {
                reportingFile != null && executionFile != null && reportingFile == executionFile -> true

                reportingFile != null && executionFile != null &&
                    Files.exists(reportingFile) && Files.exists(executionFile) ->
                    Files.isSameFile(reportingFile, executionFile)

                else -> false
            }
            val sameNamedMemoryDatabase = reportingDbPath != ":memory:" && executionDbPath != ":memory:" &&
                isMemoryDatabase(reportingDbPath) &&
                isMemoryDatabase(executionDbPath) &&
                memoryDatabaseIdentity(reportingDbPath) == memoryDatabaseIdentity(executionDbPath)
            require(!sameFile && !sameNamedMemoryDatabase) {
                "Execution and reporting databases must use separate SQLite files."
            }
        }

        private fun filePath(databasePath: String): Path? {
            if (isMemoryDatabase(databasePath)) return null
            val rawPath = databasePath
                .removePrefix("jdbc:sqlite:")
                .substringBefore('?')
                .removePrefix("file:")
            val normalizedPath = Path.of(rawPath).toAbsolutePath().normalize()
            val realParent = normalizedPath.parent?.takeIf(Files::isDirectory)?.toRealPath()
            val resolved = realParent?.resolve(normalizedPath.fileName) ?: normalizedPath
            return if (Files.exists(resolved)) resolved.toRealPath() else resolved
        }

        private fun memoryDatabaseIdentity(databasePath: String): String = databasePath
            .removePrefix("jdbc:sqlite:")
            .removePrefix("file:")
            .substringBefore('?')

        private fun buildSqliteUrl(dbPath: String): String = when {
            dbPath.startsWith("jdbc:sqlite:") -> dbPath
            dbPath == ":memory:" -> "jdbc:sqlite:file:execution-${UUID.randomUUID()}?mode=memory&cache=shared"
            else -> "jdbc:sqlite:$dbPath"
        }

        private fun isMemoryDatabase(dbPath: String): Boolean =
            dbPath == ":memory:" || dbPath.contains("mode=memory") || dbPath.contains(":memory:")

        private fun verifyJournalArtifacts(
            databasePath: Path,
            markerPath: Path,
            independentWitnessPath: Path,
            reportingDbPath: String,
        ) {
            val databaseExists = Files.isRegularFile(databasePath)
            val markerExists = Files.isRegularFile(markerPath)
            val independentWitnessExists = Files.isRegularFile(independentWitnessPath)
            if (!databaseExists && (markerExists || independentWitnessExists)) {
                throw IOException("The initialized execution database is missing; refusing to create an empty journal.")
            }

            // The local identity marker and reporting-side witness preserve journal identity.
            // Reporting storage is consulted only when an execution artifact is missing.
            if (databaseExists && markerExists && independentWitnessExists) return
            val reportingCutover = reportingCutoverWitness(reportingDbPath)
            val backupExists = filePath(reportingDbPath)?.let { source ->
                Files.exists(source.resolveSibling("${source.fileName}.pre-execution-journal.bak"))
            } ?: false
            if (!databaseExists && (reportingCutover || backupExists)) {
                throw IOException(
                    "Execution journal is missing after the reporting database cutover; refusing to create an empty journal.",
                )
            }
            if (databaseExists && (!markerExists || !independentWitnessExists)) {
                if (reportingCutover) {
                    throw IOException(
                        "An execution journal identity sidecar is missing after reporting cutover; refusing live startup.",
                    )
                }
                if (hasCommittedJournalState(databasePath)) {
                    throw IOException(
                        "An execution journal identity sidecar is missing for a non-empty journal; refusing live startup.",
                    )
                }
            }
        }

        private fun hasCommittedJournalState(databasePath: Path): Boolean =
            DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
                connection.createStatement().use { statement ->
                    val hasSchema = statement.executeQuery(
                        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='execution_order_intents' LIMIT 1",
                    ).use { it.next() }
                    if (!hasSchema) return@use false
                    statement.executeQuery(
                        "SELECT (SELECT COUNT(*) FROM execution_order_intents) + " +
                            "(SELECT COUNT(*) FROM execution_legacy_migrations)",
                    ).use { resultSet ->
                        check(resultSet.next()) { "Execution journal contents could not be verified." }
                        resultSet.getLong(1) > 0L
                    }
                }
            }

        /** Null means no reporting file exists; an unreadable existing file fails closed. */
        private fun reportingCutoverWitness(reportingDbPath: String): Boolean {
            val reportingFile = filePath(reportingDbPath) ?: return false
            if (!Files.isRegularFile(reportingFile) || Files.size(reportingFile) == 0L) return false
            try {
                val readOnlyUrl = "jdbc:sqlite:file:$reportingFile?mode=ro"
                DriverManager.getConnection(readOnlyUrl).use { connection ->
                    val hasMetadataTable = connection.createStatement().use { statement ->
                        statement.executeQuery(
                            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='history_sync_metadata' LIMIT 1",
                        ).use { it.next() }
                    }
                    if (!hasMetadataTable) return false
                    return connection.prepareStatement(
                        "SELECT 1 FROM history_sync_metadata WHERE key LIKE 'execution-cutover:%' LIMIT 1",
                    ).use { statement ->
                        statement.executeQuery().use { it.next() }
                    }
                }
            } catch (e: Exception) {
                throw IOException(
                    "Cannot verify whether the reporting database already completed execution-journal migration.",
                    e,
                )
            }
        }
    }

    private fun verifyOrCreateIdentityMarker(markerPath: Path, identity: String) {
        if (Files.exists(markerPath)) {
            check(Files.readString(markerPath).trim() == identity) {
                "Execution database identity does not match its retained journal marker."
            }
            return
        }

        val temporary = Files.createTempFile(markerPath.parent, "${markerPath.fileName}.", ".tmp")
        try {
            FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(identity.toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                Files.move(temporary, markerPath, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: java.nio.file.FileAlreadyExistsException) {
                check(Files.readString(markerPath).trim() == identity) {
                    "Execution database identity does not match its retained journal marker."
                }
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
