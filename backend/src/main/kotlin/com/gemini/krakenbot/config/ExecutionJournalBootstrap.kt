package com.gemini.krakenbot.config

import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.repository.ExecutionOrderIntentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.sqlite.SQLiteConnection
import org.sqlite.SQLiteErrorCode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/** Performs the one-time legacy import and restart recovery before the live loop is enabled. */
class ExecutionJournalBootstrap(
    private val database: ExecutionDatabase,
    private val repository: ExecutionOrderIntentRepository,
    private val reportingDbPath: String = System.getProperty("kraken.db.path", "kraken-rebalancer.db"),
) {
    suspend fun initialize() = withContext(Dispatchers.IO) {
        LegacyExecutionJournalMigrator(database, reportingDbPath).migrate()
        repository.recoverInterruptedSubmissions()
        database.markReadyForSubmission()
    }
}

private class LegacyExecutionJournalMigrator(
    private val executionDatabase: ExecutionDatabase,
    private val reportingDbPath: String,
) {
    fun migrate() {
        val absoluteSource = canonicalFileBackedPath(reportingDbPath)
        val sourcePath = absoluteSource?.toString() ?: reportingDbPath
        val sourceId = sha256(sourcePath.toByteArray())
        if (absoluteSource == null || !Files.isRegularFile(absoluteSource) || Files.size(absoluteSource) == 0L) {
            recordEmptySource(sourcePath, sourceId)
            return
        }

        val retainedBackup = absoluteSource.resolveSibling("${absoluteSource.fileName}.pre-execution-journal.bak")
        existingMigration(sourceId)?.let { existing ->
            check(existing.sourcePath == absoluteSource.toString()) {
                "Execution journal legacy source identity does not match the configured reporting database."
            }
            if (existing.backupSha256 == null) {
                // This source was absent or empty before the reporting database was initialized.
                // New reporting state is downstream-only and must not become a restart dependency.
                return
            } else {
                val priorBackup = Path.of(checkNotNull(existing.backupPath))
                verifyStandaloneDatabase(priorBackup)
                check(sha256(Files.readAllBytes(priorBackup)) == existing.backupSha256) {
                    "Retained legacy backup changed after the execution journal import."
                }
                if (!existing.cutoverGuardWritten) {
                    markPreviousBinaryIncompatible(absoluteSource, sourceId)
                    markCutoverGuardWritten(sourceId)
                }
                return
            }
        }

        val backup = createRetainedBackup(absoluteSource, retainedBackup)
        val backupHash = sha256(Files.readAllBytes(backup))

        val intents = readCanonicalLegacyIntents(backup, absoluteSource.parent)
        importAtomically(
            sourceId = sourceId,
            sourcePath = absoluteSource.toString(),
            backupPath = backup.toString(),
            backupHash = backupHash,
            intents = intents,
        )
        markPreviousBinaryIncompatible(absoluteSource, sourceId)
        markCutoverGuardWritten(sourceId)
        log.info(
            "Imported {} legacy order intents from the retained database snapshot into the execution journal",
            intents.size,
        )
    }

    private fun recordEmptySource(sourcePath: String, sourceId: String) {
        executionDatabase.connect().use { connection ->
            connection.autoCommit = false
            try {
                val current = connection.prepareStatement(
                    "SELECT source_path, backup_sha256, cutover_guard_written " +
                        "FROM execution_legacy_migrations WHERE source_id = ?",
                ).use { statement ->
                    statement.setString(1, sourceId)
                    statement.executeQuery().use { resultSet ->
                        if (resultSet.next()) {
                            Triple(resultSet.getString(1), resultSet.getString(2), resultSet.getInt(3) != 0)
                        } else {
                            null
                        }
                    }
                }
                if (current == null) {
                    connection.prepareStatement(
                        """
                        INSERT INTO execution_legacy_migrations(
                            source_id, source_path, backup_path, backup_sha256, imported_intents, completed_at,
                            cutover_guard_written
                        ) VALUES (?, ?, NULL, NULL, 0, ?, 1)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, sourceId)
                        statement.setString(2, sourcePath)
                        statement.setLong(3, System.currentTimeMillis())
                        statement.executeUpdate()
                    }
                } else {
                    check(current.first == sourcePath) {
                        "Execution journal legacy source identity does not match the configured reporting database."
                    }
                    check(current.second == null || current.third) {
                        "Legacy reporting database disappeared before its cutover guard was written; " +
                            "live submission remains blocked."
                    }
                }
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }
    }

    private fun existingMigration(sourceId: String): MigrationEntry? = executionDatabase.connect().use { connection ->
        connection.prepareStatement(
            "SELECT source_path, backup_path, backup_sha256, cutover_guard_written " +
                "FROM execution_legacy_migrations WHERE source_id = ?",
        ).use { statement ->
            statement.setString(1, sourceId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) {
                    MigrationEntry(
                        sourcePath = resultSet.getString(1),
                        backupPath = resultSet.getString(2),
                        backupSha256 = resultSet.getString(3),
                        cutoverGuardWritten = resultSet.getInt(4) != 0,
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun markCutoverGuardWritten(sourceId: String) {
        executionDatabase.connect().use { connection ->
            connection.prepareStatement(
                "UPDATE execution_legacy_migrations SET cutover_guard_written = 1 WHERE source_id = ?",
            ).use { statement ->
                statement.setString(1, sourceId)
                check(statement.executeUpdate() == 1) { "Execution journal cutover state was not retained." }
            }
        }
    }

    private fun readCanonicalLegacyIntents(backup: Path, parent: Path): List<LegacyIntent> {
        val temporaryDirectory = Files.createTempDirectory(parent, "execution-journal-normalize-")
        try {
            val workingDatabase = temporaryDirectory.resolve("legacy.db")
            Files.copy(backup, workingDatabase, StandardCopyOption.REPLACE_EXISTING)
            val rawIntentStates = readRawIntentStates(workingDatabase)
            DatabaseConfig.init(workingDatabase.toString())
            DriverManager.getConnection("jdbc:sqlite:$workingDatabase").use { connection ->
                val intents = connection.createStatement().use { statement ->
                    statement.executeQuery(
                        """
                        SELECT id, cycle_id, client_order_id, client_order_id_ambiguous, pair, symbol, side,
                               volume, usd_amount, expected_price, created_at, state, order_txid, error_message,
                               resolved_at, resolution_evidence, local_trade_id
                        FROM order_intents ORDER BY id
                        """.trimIndent(),
                    ).use { resultSet ->
                        buildList {
                            while (resultSet.next()) {
                                val sourceState = resultSet.getString("state")
                                val normalizedState = runCatching { OrderIntentState.valueOf(sourceState) }
                                    .getOrElse {
                                        throw IllegalStateException(
                                            "Legacy order intent ${resultSet.getInt("id")} has unsupported state " +
                                                "'$sourceState'; live submission remains blocked.",
                                            it,
                                        )
                                    }
                                add(
                                    LegacyIntent(
                                        id = resultSet.getInt("id"),
                                        cycleId = resultSet.getString("cycle_id"),
                                        clientOrderId = resultSet.getString("client_order_id"),
                                        clientOrderIdAmbiguous = resultSet.getInt("client_order_id_ambiguous") != 0,
                                        pair = resultSet.getString("pair"),
                                        symbol = resultSet.getString("symbol"),
                                        side = resultSet.getString("side"),
                                        volume = resultSet.getString("volume"),
                                        usdAmount = resultSet.getString("usd_amount"),
                                        expectedPrice = resultSet.getString("expected_price"),
                                        createdAt = resultSet.getLong("created_at"),
                                        sourceState = normalizedState,
                                        legacySourceState = rawIntentStates[resultSet.getInt("id")]
                                            ?: error("Legacy intent source state is missing."),
                                        orderTxid = resultSet.getString("order_txid"),
                                        errorMessage = resultSet.getString("error_message"),
                                        resolvedAt = resultSet.getNullableLong("resolved_at"),
                                        resolutionEvidence = resultSet.getString("resolution_evidence"),
                                        legacyTradeId = resultSet.getNullableInt("local_trade_id"),
                                    ),
                                )
                            }
                        }
                    }
                }
                verifyNoUnimportedSubmissionGuards(connection, intents)
                return intents
            }
        } finally {
            temporaryDirectory.toFile().deleteRecursively()
        }
    }

    private fun readRawIntentStates(database: Path): Map<Int, String> =
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            val hasIntentTable = connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'order_intents' LIMIT 1",
                ).use { it.next() }
            }
            if (!hasIntentTable) return@use emptyMap()
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id, state FROM order_intents ORDER BY id").use { resultSet ->
                    buildMap {
                        while (resultSet.next()) put(resultSet.getInt("id"), resultSet.getString("state"))
                    }
                }
            }
        }

    private fun verifyNoUnimportedSubmissionGuards(connection: Connection, intents: List<LegacyIntent>) {
        val intentTradeIds = intents.mapNotNull(LegacyIntent::legacyTradeId).toSet()
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT id, submission_state FROM trades WHERE dry_run = 0 AND submission_state IS NOT NULL",
            ).use { resultSet ->
                while (resultSet.next()) {
                    val tradeId = resultSet.getInt("id")
                    val state = resultSet.getString("submission_state")
                    check(state == "PENDING" || state == "UNCERTAIN") {
                        "Legacy live trade $tradeId has unsupported submission state '$state'; live submission remains blocked."
                    }
                    check(tradeId in intentTradeIds) {
                        "Legacy live trade $tradeId has no matching durable order intent; live submission remains blocked."
                    }
                }
            }
        }
    }

    private fun importAtomically(
        sourceId: String,
        sourcePath: String,
        backupPath: String,
        backupHash: String,
        intents: List<LegacyIntent>,
    ) {
        executionDatabase.connect().use { connection ->
            connection.autoCommit = false
            try {
                val alreadyImported = connection.prepareStatement(
                    "SELECT 1 FROM execution_legacy_migrations WHERE source_id = ?",
                ).use { statement ->
                    statement.setString(1, sourceId)
                    statement.executeQuery().use { it.next() }
                }
                check(!alreadyImported) {
                    "Legacy execution migration was concurrently completed; restart to verify it."
                }
                intents.forEach { intent -> importIntent(connection, sourceId, intent) }
                connection.prepareStatement(
                    """
                    INSERT INTO execution_legacy_migrations(
                        source_id, source_path, backup_path, backup_sha256, imported_intents, completed_at,
                        cutover_guard_written
                    ) VALUES (?, ?, ?, ?, ?, ?, 0)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, sourceId)
                    statement.setString(2, sourcePath)
                    statement.setString(3, backupPath)
                    statement.setString(4, backupHash)
                    statement.setInt(5, intents.size)
                    statement.setLong(6, System.currentTimeMillis())
                    statement.executeUpdate()
                }
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }
    }

    private fun importIntent(connection: Connection, sourceId: String, source: LegacyIntent) {
        val state = if (source.sourceState == OrderIntentState.PENDING) {
            OrderIntentState.UNCERTAIN
        } else {
            source.sourceState
        }
        val errorMessage = if (source.sourceState == OrderIntentState.PENDING) {
            source.errorMessage ?: "Imported legacy PENDING intent; verify the exchange outcome before resuming."
        } else {
            source.errorMessage
        }
        val newIntentId = connection.prepareStatement(
            """
            INSERT INTO execution_order_intents (
                cycle_id, client_order_id, client_order_id_ambiguous, pair, symbol, side,
                volume, usd_amount, expected_price, created_at, state, order_txid,
                error_message, resolved_at, resolution_evidence, outcome_volume,
                legacy_source_id, legacy_intent_id, legacy_trade_id, legacy_source_state
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, source.cycleId)
            statement.setString(2, source.clientOrderId)
            statement.setInt(3, if (source.clientOrderIdAmbiguous) 1 else 0)
            statement.setString(4, source.pair)
            statement.setString(5, source.symbol)
            statement.setString(6, source.side)
            statement.setString(7, source.volume)
            statement.setString(8, source.usdAmount)
            statement.setString(9, source.expectedPrice)
            statement.setLong(10, source.createdAt)
            statement.setString(11, state.name)
            statement.setString(12, source.orderTxid)
            statement.setString(13, errorMessage)
            statement.setObject(14, source.resolvedAt)
            statement.setString(15, source.resolutionEvidence)
            statement.setString(16, sourceId)
            statement.setInt(17, source.id)
            statement.setObject(18, source.legacyTradeId)
            statement.setString(19, source.legacySourceState)
            statement.executeUpdate()
            connection.createStatement().use { query ->
                query.executeQuery("SELECT last_insert_rowid()").use { resultSet ->
                    check(resultSet.next()) { "SQLite did not return the imported execution intent ID." }
                    resultSet.getInt(1)
                }
            }
        }
        if (state == OrderIntentState.UNCERTAIN) {
            connection.prepareStatement(
                "INSERT INTO execution_projection_outbox(intent_id, revision) VALUES (?, 1)",
            ).use { statement ->
                statement.setInt(1, newIntentId)
                statement.executeUpdate()
            }
        }
    }

    private fun markPreviousBinaryIncompatible(source: Path, sourceId: String) {
        DriverManager.getConnection("jdbc:sqlite:$source").use { connection ->
            val hasMigrations = connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'schema_migrations' LIMIT 1",
                ).use { it.next() }
            }
            if (!hasMigrations) return
            val hasHistoryMetadata = connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'history_sync_metadata' LIMIT 1",
                ).use { it.next() }
            }
            check(hasHistoryMetadata) {
                "Legacy reporting database has no history metadata table for an execution cutover witness."
            }
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    "INSERT OR IGNORE INTO schema_migrations(version, name, applied_at) VALUES (?, ?, ?)",
                ).use { statement ->
                    statement.setInt(1, CURRENT_SCHEMA_VERSION)
                    statement.setString(2, "separate-execution-journal-cutover")
                    statement.setLong(3, Instant.now().toEpochMilli())
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT OR REPLACE INTO history_sync_metadata(key, value) VALUES (?, ?)",
                ).use { statement ->
                    statement.setString(1, "$EXECUTION_CUTOVER_KEY_PREFIX${sourceId.take(44)}")
                    statement.setString(2, sourceId)
                    statement.executeUpdate()
                }
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }
    }

    private fun createRetainedBackup(source: Path, backup: Path): Path {
        if (Files.exists(backup)) {
            val archivedBackup = backup.resolveSibling(
                "${backup.fileName}.prior-${Instant.now().toEpochMilli()}-${UUID.randomUUID().toString().take(8)}",
            )
            Files.move(backup, archivedBackup, StandardCopyOption.ATOMIC_MOVE)
            log.info("Kept prior interrupted-migration backup at {}", archivedBackup)
        }
        val temporary = Files.createTempFile(source.parent, "${source.fileName}.execution-journal-", ".tmp")
        try {
            DriverManager.getConnection("jdbc:sqlite:$source").use { connection ->
                val resultCode = connection.unwrap(SQLiteConnection::class.java).getDatabase()
                    .backup("main", temporary.toAbsolutePath().toString(), null)
                check(resultCode == SQLiteErrorCode.SQLITE_OK.code) {
                    "SQLite backup for execution-journal migration failed with result code $resultCode."
                }
            }
            DriverManager.getConnection("jdbc:sqlite:$temporary").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA journal_mode = DELETE").use { resultSet ->
                        check(resultSet.next() && resultSet.getString(1).equals("delete", ignoreCase = true)) {
                            "Retained legacy backup could not be finalized as a standalone database."
                        }
                    }
                    statement.executeQuery("PRAGMA integrity_check").use { resultSet ->
                        check(resultSet.next() && resultSet.getString(1).equals("ok", ignoreCase = true)) {
                            "Retained legacy backup failed SQLite integrity_check."
                        }
                    }
                }
            }
            Files.move(temporary, backup, StandardCopyOption.ATOMIC_MOVE)
            return backup
        } catch (e: Exception) {
            Files.deleteIfExists(temporary)
            throw IllegalStateException("Could not create a retained legacy backup at $backup", e)
        }
    }

    private fun verifyStandaloneDatabase(path: Path) {
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA integrity_check").use { resultSet ->
                    check(resultSet.next() && resultSet.getString(1).equals("ok", ignoreCase = true)) {
                        "Retained legacy backup at $path is not a valid SQLite database."
                    }
                }
            }
        }
        check(!Files.exists(Path.of("$path-wal")) && !Files.exists(Path.of("$path-shm"))) {
            "Retained legacy backup has live SQLite sidecars and cannot be reused safely."
        }
    }

    private fun fileBackedPath(value: String): Path? {
        if (value == ":memory:" || value.contains("mode=memory", ignoreCase = true)) return null
        val raw = value.removePrefix("jdbc:sqlite:").substringBefore('?').removePrefix("file:")
        return raw.takeIf(String::isNotBlank)?.let(Path::of)
    }

    private fun canonicalFileBackedPath(value: String): Path? = fileBackedPath(value)?.let { path ->
        val absolutePath = path.toAbsolutePath().normalize()
        val realParent = absolutePath.parent?.takeIf(Files::isDirectory)?.toRealPath()
        val resolvedPath = realParent?.resolve(absolutePath.fileName) ?: absolutePath
        if (Files.exists(resolvedPath)) resolvedPath.toRealPath() else resolvedPath
    }

    private fun java.sql.ResultSet.getNullableLong(column: String): Long? = getObject(column)?.let { getLong(column) }

    private fun java.sql.ResultSet.getNullableInt(column: String): Int? = getObject(column)?.let { getInt(column) }

    private data class LegacyIntent(
        val id: Int,
        val cycleId: String?,
        val clientOrderId: String?,
        val clientOrderIdAmbiguous: Boolean,
        val pair: String,
        val symbol: String,
        val side: String,
        val volume: String,
        val usdAmount: String,
        val expectedPrice: String?,
        val createdAt: Long,
        val sourceState: OrderIntentState,
        val legacySourceState: String,
        val orderTxid: String?,
        val errorMessage: String?,
        val resolvedAt: Long?,
        val resolutionEvidence: String?,
        val legacyTradeId: Int?,
    )

    private data class MigrationEntry(
        val sourcePath: String,
        val backupPath: String?,
        val backupSha256: String?,
        val cutoverGuardWritten: Boolean,
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        private const val EXECUTION_CUTOVER_KEY_PREFIX = "execution-cutover:"
        private val log = LoggerFactory.getLogger(LegacyExecutionJournalMigrator::class.java)
    }
}
