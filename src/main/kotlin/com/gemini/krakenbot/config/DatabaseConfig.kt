package com.gemini.krakenbot.config

import com.gemini.krakenbot.repository.table.ActionLogTable
import com.gemini.krakenbot.repository.table.AssetSnapshotTable
import com.gemini.krakenbot.repository.table.PortfolioSnapshotTable
import com.gemini.krakenbot.repository.table.PortfolioStatsTable
import com.gemini.krakenbot.repository.table.TradeTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Initializes the SQLite database connection and creates tables if they don't exist.
 */
object DatabaseConfig {

    private val log = LoggerFactory.getLogger(DatabaseConfig::class.java)
    
    // Keeps dummy connections to prevent SQLite from dropping named in-memory databases
    private val keepAliveConnections = ConcurrentHashMap<String, Connection>()

    fun init(dbPath: String = "kraken-rebalancer.db"): Database {
        log.info("Initializing SQLite database at: {}", dbPath)
        
        val url = when {
            dbPath == ":memory:" -> {
                val dbName = UUID.randomUUID().toString()
                "jdbc:sqlite:file:$dbName?mode=memory&cache=shared&foreign_keys=true"
            }
            dbPath.startsWith("jdbc:sqlite:") -> {
                dbPath
            }
            dbPath.contains("?") -> {
                "jdbc:sqlite:$dbPath&foreign_keys=true"
            }
            else -> {
                "jdbc:sqlite:$dbPath?foreign_keys=true"
            }
        }

        // For in-memory shared cache, open a connection to keep the database alive
        if (url.contains("mode=memory") && url.contains("cache=shared")) {
            keepAliveConnections.computeIfAbsent(url) {
                DriverManager.getConnection(url)
            }
        }

        val database = Database.connect(
            url = url,
            driver = "org.sqlite.JDBC"
        )

        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(
                PortfolioSnapshotTable,
                AssetSnapshotTable,
                TradeTable,
                PortfolioStatsTable,
                ActionLogTable
            )
        }

        log.info("Database initialized successfully.")
        return database
    }
}
