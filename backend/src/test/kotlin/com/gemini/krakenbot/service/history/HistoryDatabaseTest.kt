package com.gemini.krakenbot.service.history

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

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
                            rs.getInt(1) shouldBe 1
                        }
                    }
                }
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
    }

    private fun withTempDirectory(block: suspend (Path) -> Unit) = runTest {
        val directory = Files.createTempDirectory("history-database-test-")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
