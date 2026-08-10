package com.gemini.krakenbot.config

import com.gemini.krakenbot.repository.table.ActionLogTable
import com.gemini.krakenbot.repository.table.AssetSnapshotTable
import com.gemini.krakenbot.repository.table.HistorySyncMetadataTable
import com.gemini.krakenbot.repository.table.LedgerTable
import com.gemini.krakenbot.repository.table.OrderIntentTable
import com.gemini.krakenbot.repository.table.PortfolioSnapshotTable
import com.gemini.krakenbot.repository.table.PortfolioStatsTable
import com.gemini.krakenbot.repository.table.SchemaMigrationTable
import com.gemini.krakenbot.repository.table.TradeTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.exists
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object DatabaseConfig {
    private val log = LoggerFactory.getLogger(DatabaseConfig::class.java)
    private const val CURRENT_SCHEMA_VERSION = 3

    // A shared in-memory SQLite database disappears when its last connection closes.
    // Retain one connection per URL until JVM shutdown so pooled test connections share stable state.
    private val keepAliveConnections = ConcurrentHashMap<String, Connection>()

    init {
        Runtime.getRuntime().addShutdownHook(
            object : Thread() {
                override fun run() {
                    keepAliveConnections.values.forEach { connection ->
                        try {
                            connection.close()
                        } catch (e: Exception) {
                            log.warn("Failed to close keepalive connection", e)
                        }
                    }
                    keepAliveConnections.clear()
                }
            },
        )
    }

    private val baseTables =
        arrayOf(
            SchemaMigrationTable,
            PortfolioSnapshotTable,
            AssetSnapshotTable,
            TradeTable,
            LedgerTable,
            PortfolioStatsTable,
            ActionLogTable,
            HistorySyncMetadataTable,
        )

    private val allTables = baseTables + OrderIntentTable

    fun init(dbPath: String = System.getProperty("kraken.db.path", "kraken-rebalancer.db")): Database {
        backupBeforeMigrationIfNeeded(dbPath)
        val url = buildSqliteUrl(dbPath)
        maintainMemoryDatabase(url)

        return Database.connect(url).also { database ->
            // SchemaUtils.createMissingTablesAndColumns is deprecated; use the non-deprecated
            // building blocks sequentially within one transaction instead.
            transaction(database) {
                currentDialectMetadata.resetCaches()

                val (tablesToCreate, tablesToAlter) = baseTables.partition { !it.exists() }
                val createStatements = SchemaUtils.createStatements(*tablesToCreate.toTypedArray())
                createStatements.forEach { exec(it) }

                val alterStatements = SchemaUtils.addMissingColumnsStatements(
                    tables = tablesToAlter.toTypedArray(),
                    withLogs = false,
                )
                alterStatements.forEach { exec(it) }
                currentDialectMetadata.resetCaches()

                // Rows written before provenance existed cannot distinguish a settled API fill
                // from a local order estimate when both have no slippage. Preserve that ambiguity
                // rather than treating it as an API fill and risking an unsafe reconciliation.
                exec(
                    """
                    UPDATE trades
                    SET source = 'LEGACY_UNKNOWN'
                    WHERE source IS NULL
                      AND success = 1
                      AND dry_run = 0
                      AND error_message IS NULL
                      AND slippage_percent IS NULL
                    """.trimIndent(),
                )

                applySchemaMigrations()
                recoverPendingOrderIntents()

                val executedStatements = createStatements + alterStatements
                val mappingStatements =
                    SchemaUtils
                        .checkMappingConsistence(tables = allTables, withLogs = false)
                        .filter { it !in executedStatements }
                mappingStatements.forEach { exec(it) }

                currentDialectMetadata.resetCaches()
            }
            log.info("Database initialized successfully.")
        }
    }

    private fun JdbcTransaction.applySchemaMigrations() {
        val appliedVersion =
            SchemaMigrationTable
                .select(SchemaMigrationTable.version)
                .orderBy(SchemaMigrationTable.version, SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.get(SchemaMigrationTable.version)
                ?: 0

        if (appliedVersion < 1) {
            SchemaMigrationTable.insert {
                it[version] = 1
                it[name] = "baseline"
                it[appliedAt] = Instant.now().toEpochMilli()
            }
        }

        if (!OrderIntentTable.exists()) {
            currentDialectMetadata.resetCaches()
            SchemaUtils.createStatements(OrderIntentTable).forEach { exec(it) }
            currentDialectMetadata.resetCaches()
        }

        if (appliedVersion < 2) {
            SchemaMigrationTable.insert {
                it[version] = 2
                it[name] = "order-intent-journal"
                it[appliedAt] = Instant.now().toEpochMilli()
            }
        }

        if (appliedVersion < 3) {
            // Preserve legacy PENDING/UNCERTAIN rows in the operator-facing journal. Keep the
            // original guard column too: retention and duplicate cleanup must not delete local
            // evidence while an imported intent remains unresolved.
            exec(
                """
                INSERT INTO order_intents (
                    cycle_id, client_order_id, pair, symbol, side, volume, usd_amount,
                    expected_price, created_at, state, order_txid, error_message,
                    resolved_at, resolution_evidence
                )
                SELECT cycle_id, client_order_id, pair, symbol, side, volume, usd_amount,
                       expected_price, timestamp, submission_state, order_txid, error_message,
                       NULL, NULL
                FROM trades
                WHERE dry_run = 0
                  AND submission_state IN ('PENDING', 'UNCERTAIN')
                """.trimIndent(),
            )
            SchemaMigrationTable.insert {
                it[version] = 3
                it[name] = "legacy-submission-guard-import"
                it[appliedAt] = Instant.now().toEpochMilli()
            }
        }
    }

    private fun JdbcTransaction.recoverPendingOrderIntents() {
        exec(
            """
            UPDATE order_intents
            SET state = 'UNCERTAIN',
                error_message = COALESCE(
                    error_message,
                    'Recovered pending intent after restart; verify Kraken before resolution.'
                )
            WHERE state = 'PENDING'
            """.trimIndent(),
        )
    }

    private fun backupBeforeMigrationIfNeeded(dbPath: String) {
        val databaseFile = resolveFileBackedDatabase(dbPath) ?: return
        if (!databaseFile.isFile || databaseFile.length() == 0L || !requiresMigrationBackup(databaseFile)) return

        val source = databaseFile.toPath()
        val backup = source.resolveSibling(
            "${source.fileName}.pre-migration-${Instant.now().toEpochMilli()}.bak",
        )
        try {
            Files.copy(source, backup, StandardCopyOption.COPY_ATTRIBUTES)
            log.warn("Created pre-migration database backup at {}", backup)
        } catch (e: Exception) {
            throw IllegalStateException("Cannot create pre-migration database backup for $dbPath", e)
        }
    }

    private fun resolveFileBackedDatabase(dbPath: String): File? {
        val sqlitePath = dbPath.removePrefix("jdbc:sqlite:")
        val querylessPath = sqlitePath.substringBefore('?')
        if (dbPath == ":memory:" ||
            querylessPath == ":memory:" ||
            sqlitePath.contains("mode=memory", ignoreCase = true)
        ) {
            return null
        }

        val filePath = querylessPath.removePrefix("file:")
        return filePath.takeIf { it.isNotBlank() }?.let(::File)
    }

    private fun requiresMigrationBackup(databaseFile: File): Boolean = try {
        DriverManager.getConnection("jdbc:sqlite:${databaseFile.path}").use { connection ->
            connection.createStatement().use { statement ->
                val version = statement.executeQuery(
                    "SELECT MAX(version) FROM schema_migrations",
                ).use { resultSet ->
                    if (resultSet.next()) resultSet.getInt(1) else 0
                }
                val orderIntentTableExists = statement.executeQuery(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'order_intents' LIMIT 1",
                ).use { resultSet -> resultSet.next() }
                version < CURRENT_SCHEMA_VERSION || !orderIntentTableExists
            }
        }
    } catch (_: Exception) {
        // Missing schema_migrations on an existing database is the first migration boundary.
        true
    }

    /**
     * Builds an SQLite JDBC URL.
     *
     * - `:memory:` generates a shared in-memory database and enables foreign keys.
     * - Strings already starting with `jdbc:sqlite:` are returned unchanged, as they are treated
     *   as fully formed URLs provided by the caller.
     * - Partial SQLite URLs/file paths get the `foreign_keys=true` parameter appended.
     */
    private fun buildSqliteUrl(dbPath: String): String = when {
        dbPath == ":memory:" -> {
            val dbName = UUID.randomUUID().toString()
            "jdbc:sqlite:file:$dbName?mode=memory&cache=shared&foreign_keys=true"
        }

        dbPath.startsWith("jdbc:sqlite:") -> dbPath

        dbPath.contains("?") -> "jdbc:sqlite:$dbPath&foreign_keys=true"

        else -> "jdbc:sqlite:$dbPath?foreign_keys=true"
    }

    private fun maintainMemoryDatabase(url: String) {
        if (url.isInMemoryShared) {
            keepAliveConnections.getOrPut(url) { DriverManager.getConnection(url) }
        }
    }

    private val String.isInMemoryShared: Boolean
        get() = contains("mode=memory") && contains("cache=shared")
}
