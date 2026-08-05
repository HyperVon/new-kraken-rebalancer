package com.gemini.krakenbot.config

import com.gemini.krakenbot.repository.table.ActionLogTable
import com.gemini.krakenbot.repository.table.AssetSnapshotTable
import com.gemini.krakenbot.repository.table.HistorySyncMetadataTable
import com.gemini.krakenbot.repository.table.LedgerTable
import com.gemini.krakenbot.repository.table.PortfolioSnapshotTable
import com.gemini.krakenbot.repository.table.PortfolioStatsTable
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

    private val tables =
        arrayOf(
            PortfolioSnapshotTable,
            AssetSnapshotTable,
            TradeTable,
            LedgerTable,
            PortfolioStatsTable,
            ActionLogTable,
            HistorySyncMetadataTable,
        )

    fun init(dbPath: String = System.getProperty("kraken.db.path", "kraken-rebalancer.db")): Database {
        val url = buildSqliteUrl(dbPath)
        maintainMemoryDatabase(url)

        return Database.connect(url).also { database ->
            // SchemaUtils.createMissingTablesAndColumns is deprecated; use the non-deprecated
            // building blocks sequentially within one transaction instead.
            transaction(database) {
                currentDialectMetadata.resetCaches()

                val (tablesToCreate, tablesToAlter) = tables.partition { !it.exists() }
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

                val executedStatements = createStatements + alterStatements
                val mappingStatements =
                    SchemaUtils
                        .checkMappingConsistence(tables = tables, withLogs = false)
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
