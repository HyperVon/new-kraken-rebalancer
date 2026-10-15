package com.gemini.krakenbot.config

import com.gemini.krakenbot.repository.ExecutionOrderIntentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Checks prior live submissions and recovers the active journal before enabling live orders. */
class ExecutionJournalBootstrap(
    private val database: ExecutionDatabase,
    private val repository: ExecutionOrderIntentRepository,
    private val legacyDatabasePath: String = System.getProperty("kraken.db.path", "kraken-rebalancer.db"),
) {
    suspend fun initialize() = withContext(Dispatchers.IO) {
        rejectUnresolvedLegacySubmissions()
        repository.recoverInterruptedSubmissions()
        database.markReadyForSubmission()
    }

    private fun rejectUnresolvedLegacySubmissions() {
        if (ExecutionDatabase.isMemoryDatabase(legacyDatabasePath)) return

        val path = Path.of(legacyDatabasePath)
        try {
            if (Files.notExists(path)) return
            check(Files.exists(path)) {
                "Could not determine whether the legacy order database exists; live order submission remains blocked."
            }
            check(Files.isRegularFile(path)) {
                "Legacy order database path is not a regular file; live order submission remains blocked."
            }
            if (Files.size(path) == 0L) return

            val properties = SQLiteConfig().apply { setReadOnly(true) }.toProperties()
            DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}", properties).use { connection ->
                val tables = connection.tableNames()
                if ("order_intents" in tables) connection.verifyLegacyIntents()
                if ("trades" in tables) connection.verifyLegacyTrades()
            }
        } catch (error: Exception) {
            throw IllegalStateException(
                "Could not verify legacy order submissions at $path; live order submission remains blocked.",
                error,
            )
        }
    }

    private fun Connection.tableNames(): Set<String> = createStatement().use { statement ->
        statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { resultSet ->
            buildSet { while (resultSet.next()) add(resultSet.getString(1)) }
        }
    }

    private fun Connection.columns(table: String): Set<String> = createStatement().use { statement ->
        statement.executeQuery("PRAGMA table_info($table)").use { resultSet ->
            buildSet { while (resultSet.next()) add(resultSet.getString("name")) }
        }
    }

    private fun Connection.verifyLegacyIntents() {
        check("state" in columns("order_intents")) {
            "Legacy order intent state is missing; live order submission remains blocked."
        }
        createStatement().use { statement ->
            statement.executeQuery("SELECT id, state FROM order_intents").use { resultSet ->
                while (resultSet.next()) {
                    val id = resultSet.getInt("id")
                    val state = resultSet.getString("state")
                    when (state) {
                        "PENDING", "UNCERTAIN" -> error(
                            "Legacy order intent $id is $state; reconcile it before live submission.",
                        )

                        "CONFIRMED", "REJECTED" -> Unit

                        else -> error(
                            "Legacy order intent $id has unsupported state '$state'; live order submission remains blocked.",
                        )
                    }
                }
            }
        }
    }

    private fun Connection.verifyLegacyTrades() {
        val tradeColumns = columns("trades")
        if ("submission_state" !in tradeColumns) return
        val liveFilter = if ("dry_run" in tradeColumns) " WHERE dry_run = 0" else " WHERE 1 = 1"
        createStatement().use { statement ->
            statement.executeQuery(
                "SELECT id, submission_state FROM trades$liveFilter AND submission_state IS NOT NULL",
            ).use { resultSet ->
                while (resultSet.next()) {
                    val id = resultSet.getInt("id")
                    val state = resultSet.getString("submission_state")
                    when (state) {
                        "PENDING", "UNCERTAIN" -> error(
                            "Legacy live trade $id is $state; reconcile it before live submission.",
                        )

                        else -> error(
                            "Legacy live trade $id has unsupported submission state '$state'; live order submission remains blocked.",
                        )
                    }
                }
            }
        }
    }
}
