package com.gemini.krakenbot.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.UUID

class ExecutionDatabaseLifecycleTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "explicit initialization creates a fresh journal that normal startup can reopen" {
            withTempDirectory { directory ->
                val path = directory.resolve("execution.db")
                val initialized = ExecutionDatabase.initialize(path.toString())
                val journalId = initialized.journalId()

                val reopened = ExecutionDatabase.init(path.toString())

                reopened.journalId() shouldBe journalId
            }
        }

        "normal startup refuses a missing initialized journal without recreating it" {
            withTempDirectory { directory ->
                val path = directory.resolve("execution.db")
                ExecutionDatabase.initialize(path.toString())
                Files.delete(path)

                shouldThrow<IOException> { ExecutionDatabase.init(path.toString()) }

                Files.exists(path) shouldBe false
                Files.exists(path.resolveSibling("execution.db.journal-id")) shouldBe true
            }
        }

        "normal startup refuses a missing journal marker without recreating it" {
            withTempDirectory { directory ->
                val path = directory.resolve("execution.db")
                ExecutionDatabase.initialize(path.toString())
                val marker = path.resolveSibling("execution.db.journal-id")
                Files.delete(marker)

                shouldThrow<IOException> { ExecutionDatabase.init(path.toString()) }

                Files.exists(path) shouldBe true
                Files.exists(marker) shouldBe false
            }
        }

        "normal startup refuses an incomplete paired data directory without changing the execution journal" {
            withTempDirectory { directory ->
                val path = directory.resolve("execution.db")
                val initialized = ExecutionDatabase.initialize(path.toString())
                val journalId = initialized.journalId()
                val initializationMarker = directory.resolve(DataDirectoryConfig.INITIALIZATION_MARKER_FILE)
                Files.createFile(initializationMarker)

                shouldThrow<IOException> { ExecutionDatabase.init(path.toString()) }

                Files.exists(path) shouldBe true
                Files.readString(path.resolveSibling("execution.db.journal-id")) shouldBe "$journalId\n"
                Files.exists(initializationMarker) shouldBe true
            }
        }

        "normal startup refuses a journal marker for a different database identity" {
            withTempDirectory { directory ->
                val path = directory.resolve("execution.db")
                ExecutionDatabase.initialize(path.toString())
                val marker = path.resolveSibling("execution.db.journal-id")
                Files.writeString(marker, "different-journal-id\n")

                shouldThrow<IOException> { ExecutionDatabase.init(path.toString()) }

                Files.readString(marker) shouldBe "different-journal-id\n"
            }
        }

        "explicit initialization refuses to replace an existing file" {
            withTempDirectory { directory ->
                val path = directory.resolve("execution.db")
                Files.writeString(path, "preserve this file")

                shouldThrow<IllegalStateException> { ExecutionDatabase.initialize(path.toString()) }

                Files.readString(path) shouldBe "preserve this file"
            }
        }

        "initialize creates an in-memory database" {
            ExecutionDatabase.initialize(":memory:").use { db ->
                val id = db.journalId()
                id.shouldNotBeBlank()
                db.journalId() shouldBe id
            }
        }

        "closing memory database owners releases keepers including failed initialization" {
            val url = "jdbc:sqlite:file:lifecycle-${UUID.randomUUID()}?mode=memory&cache=shared"
            val db = ExecutionDatabase.initialize(url)
            val id = db.journalId()
            shouldThrow<SQLException> { ExecutionDatabase.initialize(url) }
            db.close()
            db.close()
            shouldThrow<IllegalStateException> { db.connect() }
            ExecutionDatabase.initialize(url).use { reopened ->
                (reopened.journalId() != id) shouldBe true
            }
        }

        "memory database detection recognizes SQLite memory URIs without matching file paths" {
            ExecutionDatabase.isMemoryDatabase(":memory:") shouldBe true
            ExecutionDatabase.isMemoryDatabase("jdbc:sqlite:file:execution?mode=memory&cache=shared") shouldBe true
            ExecutionDatabase.isMemoryDatabase("C:\\data\\mode=memory\\legacy.db") shouldBe false
        }

        "an empty journal has no account evidence" {
            ExecutionDatabase.init(":memory:").use { db ->
                db.hasAccountEvidence() shouldBe false
            }
        }

        "the initialized account marker remains evidence even without account rows" {
            ExecutionDatabase.init(":memory:").use { db ->
                db.connect().use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeUpdate(
                            "UPDATE execution_journal_metadata SET account_binding_initialized = 1",
                        )
                    }
                }
                db.hasAccountEvidence() shouldBe true
            }
        }

        listOf(
            "account binding" to """
                INSERT INTO execution_account_binding VALUES (1, 'account', 'credentials', 1, 'test', 0)
            """.trimIndent(),
            "binding audit" to """
                INSERT INTO execution_account_binding_audit
                    (account_identity_digest, credential_generation_digest, verification_method, verified_at)
                VALUES ('account', 'credentials', 'test', 0)
            """.trimIndent(),
            "order intent" to """
                INSERT INTO execution_order_intents
                    (pair, symbol, side, volume, usd_amount, created_at, state)
                VALUES ('XBTUSD', 'BTC', 'BUY', '0.01', '100', 0, 'CONFIRMED')
            """.trimIndent(),
        ).forEach { (source, insert) ->
            "$source alone prevents rebinding an unmarked journal" {
                ExecutionDatabase.init(":memory:").use { db ->
                    db.connect().use { connection ->
                        connection.createStatement().use { it.executeUpdate(insert) }
                    }
                    db.hasAccountEvidence() shouldBe true
                }
            }
        }

        "missing account metadata fails closed rather than reporting no evidence" {
            ExecutionDatabase.init(":memory:").use { db ->
                db.connect().use { connection ->
                    connection.createStatement().use { it.executeUpdate("DELETE FROM execution_journal_metadata") }
                }
                shouldThrow<IllegalStateException> { db.hasAccountEvidence() }
            }
        }

        "ensureReadyForSubmission enforces readiness and file-backed storage" {
            withTempDirectory { directory ->
                val path = directory.resolve("execution.db")
                val db = ExecutionDatabase.initialize(path.toString())
                shouldThrow<IllegalStateException> { db.ensureReadyForSubmission() }
                db.markReadyForSubmission()
                db.ensureReadyForSubmission()

                ExecutionDatabase.init(":memory:").use { memDb ->
                    memDb.markReadyForSubmission()
                    shouldThrow<IllegalStateException> { memDb.ensureReadyForSubmission() }
                }
            }
        }
    }

    private fun withTempDirectory(block: suspend (Path) -> Unit) = runTest {
        val directory = Files.createTempDirectory("execution-database-lifecycle-")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
