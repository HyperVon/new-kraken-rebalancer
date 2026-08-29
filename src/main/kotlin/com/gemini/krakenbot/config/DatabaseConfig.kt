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
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.exists
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object DatabaseConfig {
    private val log = LoggerFactory.getLogger(DatabaseConfig::class.java)

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
        backupBeforeMigrationIfNeeded(dbPath, allTables)
        val url = buildSqliteUrl(dbPath)
        maintainMemoryDatabase(url)

        return Database.connect(
            url,
            setupConnection = { connection ->
                // `foreign_keys` is a per-connection SQLite pragma: the URL parameter alone does not
                // apply it to connections opened by Exposed/connection pools. Force it on every new
                // connection so FK/CASCADE enforcement is uniform across all init paths.
                connection.createStatement().use { statement -> statement.execute("PRAGMA foreign_keys = ON") }
            },
        ).also { database ->
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

                markLegacyUnknownTradeProvenance()

                applySchemaMigrations()
                val orderIntentAlterStatements = SchemaUtils.addMissingColumnsStatements(
                    tables = arrayOf(OrderIntentTable),
                    withLogs = false,
                )
                orderIntentAlterStatements.forEach { exec(it) }
                currentDialectMetadata.resetCaches()
                markLegacyAmbiguousClientOrderIds()
                backfillLegacyTradeIds()
                reconcileTerminalOrderIntents()
                reconcileLegacySubmissionGuards()
                recoverPendingOrderIntents()
                repairInvalidIndexes()
                currentDialectMetadata.resetCaches()

                val executedStatements = createStatements + alterStatements + orderIntentAlterStatements
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

    /**
     * Builds an SQLite JDBC URL.
     *
     * - `:memory:` generates a shared in-memory database.
     * - Strings already starting with `jdbc:sqlite:` are kept as fully formed caller URLs.
     * - Partial SQLite URLs/file paths get the `foreign_keys=true` parameter appended.
     *
     * Note: `foreign_keys` is a per-connection SQLite pragma; the URL parameter alone is not
     * reliably applied by the JDBC driver, so [init] also forces it via `setupConnection`.
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
