package com.gemini.krakenbot.service.history

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.util.Properties

class HistoryDatabaseTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "initializes fresh schema idempotently on file database" {
            withTempDirectory { dir ->
                val dbPath = dir.resolve("history.db")
                val db = HistoryDatabase(dbPath)
                db.initializeFreshSchema()

                // Idempotent second call
                db.initializeFreshSchema()

                db.connect().use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.executeQuery("SELECT schema_version FROM history_schema_meta WHERE id = 1").use { rs ->
                            rs.next() shouldBe true
                            rs.getInt(1) shouldBe 4
                        }
                    }
                }
                db.verifyInitialized()
                db.close()
            }
        }

        "connect fails if database file does not exist" {
            withTempDirectory { dir ->
                val dbPath = dir.resolve("non-existent.db")
                val db = HistoryDatabase(dbPath)
                shouldThrow<IllegalStateException> {
                    db.connect()
                }
            }
        }

        "connect fails if schema meta table is missing" {
            withTempDirectory { dir ->
                val dbPath = dir.resolve("uninitialized.db")
                DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute("CREATE TABLE some_random_table (id INT)")
                    }
                }
                val db = HistoryDatabase(dbPath)
                shouldThrow<IllegalStateException> {
                    db.connect()
                }
            }
        }

        "connect closes the connection when the schema marker query fails" {
            withTempDirectory { dir ->
                val dbPath = Files.createFile(dir.resolve("marker-query-fails.db"))
                val database = HistoryDatabase(dbPath)
                val connection = mockk<Connection>(relaxed = true)
                val statement = mockk<Statement>(relaxed = true)
                mockkStatic(DriverManager::class)
                try {
                    every { DriverManager.getConnection(any<String>(), any<Properties>()) } returns connection
                    every { connection.createStatement() } returns statement
                    every { statement.executeQuery(any<String>()) } throws SQLException("schema marker unavailable")

                    shouldThrow<SQLException> { database.connect() }

                    verify(exactly = 1) { connection.close() }
                } finally {
                    unmockkStatic(DriverManager::class)
                }
            }
        }

        "initializeFreshSchema refuses to reuse database with tables but no meta table" {
            withTempDirectory { dir ->
                val dbPath = dir.resolve("tainted.db")
                DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute("CREATE TABLE other_table (id INT)")
                    }
                }
                val db = HistoryDatabase(dbPath)
                shouldThrow<IllegalStateException> {
                    db.initializeFreshSchema()
                }
            }
        }

        "refuses database with unsupported schema version" {
            withTempDirectory { dir ->
                val dbPath = dir.resolve("old_version.db")
                DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute(
                            "CREATE TABLE history_schema_meta (id INT PRIMARY KEY, schema_version INT, initialized_at_ns INT)",
                        )
                        stmt.execute("INSERT INTO history_schema_meta VALUES (1, 999, 1000)")
                    }
                }
                val db = HistoryDatabase(dbPath)
                shouldThrow<IllegalStateException> {
                    db.initializeFreshSchema()
                }
                shouldThrow<IllegalStateException> {
                    db.connect()
                }
            }
        }

        "startup validation rejects a missing required table" {
            withTempDirectory { dir ->
                val database = HistoryDatabase(dir.resolve("history.db"))
                database.initializeFreshSchema()
                database.connect().use { connection ->
                    connection.createStatement().use { it.execute("DROP TABLE history_sync_status") }
                }
                shouldThrow<IllegalStateException> { database.verifyInitialized() }
            }
        }

        "account evidence check distinguishes empty and populated History stores" {
            withTempDirectory { dir ->
                val database = HistoryDatabase(dir.resolve("history.db"))
                database.initializeFreshSchema()
                database.hasAccountEvidence() shouldBe false
                database.connect().use { connection ->
                    connection.prepareStatement(
                        "INSERT INTO history_daily_prices(account_identity_digest, raw_asset_key, candle_open_sec, " +
                            "candle_end_sec, pair, close_usd, source) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    ).use { statement ->
                        statement.setString(1, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                        statement.setString(2, "XXBT")
                        statement.setLong(3, 1L)
                        statement.setLong(4, 2L)
                        statement.setString(5, "XXBTZUSD")
                        statement.setString(6, "100")
                        statement.setString(7, "test")
                        statement.executeUpdate()
                    }
                }
                database.hasAccountEvidence() shouldBe true
            }
        }

        "upgrades a reviewed v2 database in place, preserving records and seeding only proven horizons" {
            withTempDirectory { dir ->
                val dbPath = dir.resolve("history.db")
                seedV2Records(dbPath)

                val db = HistoryDatabase(dbPath)
                db.initializeFreshSchema()

                db.connect().use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.executeQuery("SELECT schema_version FROM history_schema_meta WHERE id = 1").use { rs ->
                            rs.next() shouldBe true
                            rs.getInt(1) shouldBe 4
                        }
                        stmt.executeQuery("SELECT COUNT(*) FROM history_trades").use { rs ->
                            rs.next() shouldBe true
                            rs.getInt(1) shouldBe 1
                        }
                        stmt.executeQuery("SELECT outcome FROM history_cycle_activity").use { rs ->
                            rs.next() shouldBe true
                            rs.getString(1) shouldBe null
                        }
                    }
                    conn.prepareStatement(
                        "SELECT purpose, state, completed_through_sec FROM history_sync_jobs " +
                            "WHERE account_identity_digest = ? AND feed = 'TRADES'",
                    ).use { statement ->
                        statement.setString(1, ACCOUNT_DIGEST)
                        statement.executeQuery().use { rs ->
                            rs.next() shouldBe true
                            rs.getString(1) shouldBe "RECENT"
                            rs.getString(2) shouldBe "COMPLETE"
                            rs.getLong(3) shouldBe 1_700_000_000L
                            rs.next() shouldBe false
                        }
                    }
                    conn.createStatement().use { stmt ->
                        stmt.executeQuery("SELECT revision FROM history_data_revision WHERE id = 1").use { rs ->
                            rs.next() shouldBe true
                            rs.getLong(1) shouldBe 1
                        }
                    }
                }
                db.verifyInitialized()
                db.hasAccountEvidence() shouldBe true
                db.close()
            }
        }

        "repeated open of a migrated database is idempotent" {
            withTempDirectory { dir ->
                val dbPath = dir.resolve("history.db")
                seedV2Records(dbPath)
                HistoryDatabase(dbPath).use { db -> db.initializeFreshSchema() }
                HistoryDatabase(dbPath).use { db ->
                    db.connect().use { connection -> connection.createStatement().use { it.execute("SELECT 1") } }
                }
                HistoryDatabase(dbPath).use { db ->
                    db.connect().use { conn ->
                        conn.createStatement().use { stmt ->
                            stmt.executeQuery("SELECT schema_version FROM history_schema_meta WHERE id = 1").use { rs ->
                                rs.next() shouldBe true
                                rs.getInt(1) shouldBe 4
                            }
                            stmt.executeQuery("SELECT COUNT(*) FROM history_sync_jobs").use { rs ->
                                rs.next() shouldBe true
                                rs.getInt(1) shouldBe 1
                            }
                        }
                    }
                }
            }
        }

        "failed upgrade rolls back and leaves the reviewed schema recoverable" {
            withTempDirectory { dir ->
                val dbPath = dir.resolve("history.db")
                seedV2Records(dbPath)
                DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
                    conn.createStatement().use { stmt ->
                        // Conflicts with the v3 CREATE so the upgrade fails mid-transaction.
                        stmt.execute("CREATE TABLE history_sync_jobs(x INTEGER)")
                    }
                }

                val db = HistoryDatabase(dbPath)
                shouldThrow<IllegalStateException> { db.initializeFreshSchema() }

                DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.executeQuery("SELECT schema_version FROM history_schema_meta WHERE id = 1").use { rs ->
                            rs.next() shouldBe true
                            rs.getInt(1) shouldBe 2
                        }
                        stmt.executeQuery("SELECT COUNT(*) FROM history_trades").use { rs ->
                            rs.next() shouldBe true
                            rs.getInt(1) shouldBe 1
                        }
                        stmt.executeQuery("SELECT COUNT(*) FROM history_cycle_activity").use { rs ->
                            rs.next() shouldBe true
                            rs.getInt(1) shouldBe 1
                        }
                    }
                }
                shouldThrow<IllegalStateException> { db.connect() }
                db.close()
            }
        }
    }

    private fun seedV2Records(dbPath: Path) {
        openV2HistoryDatabase(dbPath).use { conn ->
            conn.prepareStatement(
                "INSERT INTO history_trades(account_identity_digest, trade_id, ledger_ids, " +
                    "has_valid_time, has_valid_price, has_valid_cost, has_valid_fee, has_valid_volume, " +
                    "is_valid_record, payload_hash) VALUES (?, 'trade-1', '[]', 1, 1, 1, 1, 1, 1, 'h')",
            ).use { statement ->
                statement.setString(1, ACCOUNT_DIGEST)
                statement.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO history_cycle_activity(source_key, cycle_id, source_mode, account_identity_digest, " +
                    "managed_scope_fingerprint, managed_scope_symbols, occurred_at_ns, dry_run, actions, " +
                    "payload_hash) VALUES ('source', 'cycle-1', 'ACCOUNT', ?, 'fp', '[]', 1, 1, '[]', 'h')",
            ).use { statement ->
                statement.setString(1, ACCOUNT_DIGEST)
                statement.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO history_sync_windows(scan_id, account_identity_digest, feed, start_sec, end_sec, " +
                    "state, started_at_ns, completed_at_ns) " +
                    "VALUES ('scan-complete', ?, 'TRADES', 0, 1700000000, 'COMPLETE', 1, 2)",
            ).use { statement ->
                statement.setString(1, ACCOUNT_DIGEST)
                statement.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO history_sync_windows(scan_id, account_identity_digest, feed, start_sec, end_sec, " +
                    "state, started_at_ns, completed_at_ns) " +
                    "VALUES ('scan-open', ?, 'LEDGERS', 0, 1700000500, 'PAUSED', 3, NULL)",
            ).use { statement ->
                statement.setString(1, ACCOUNT_DIGEST)
                statement.executeUpdate()
            }
        }
    }

    private fun withTempDirectory(block: suspend (Path) -> Unit) = runTest {
        val directory = Files.createTempDirectory("history-database-test-")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val ACCOUNT_DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
