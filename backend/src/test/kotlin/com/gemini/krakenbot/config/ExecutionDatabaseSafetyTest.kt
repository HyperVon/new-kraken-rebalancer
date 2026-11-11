package com.gemini.krakenbot.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement

class ExecutionDatabaseSafetyTest : StringSpec() {
    init {
        listOf(
            Triple(
                "missing journal identity",
                "DELETE FROM execution_journal_metadata",
                "SELECT NOT EXISTS(SELECT 1 FROM execution_journal_metadata)",
            ),
            Triple(
                "blank journal identity",
                "UPDATE execution_journal_metadata SET journal_id = ''",
                "SELECT EXISTS(SELECT 1 FROM execution_journal_metadata WHERE journal_id = '')",
            ),
            Triple(
                "missing intent table",
                "DROP TABLE execution_order_intents",
                "SELECT NOT EXISTS(SELECT 1 FROM sqlite_master WHERE name = 'execution_order_intents')",
            ),
            Triple(
                "unexpected foreign table",
                "CREATE TABLE foreign_evidence(value TEXT)",
                "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'foreign_evidence')",
            ),
        ).forEach { (corruption, mutation, verifyCorruption) ->
            "startup rejects $corruption without repairing or deleting the journal" {
                withTempDirectory { directory ->
                    val path = directory.resolve("execution.db")
                    ExecutionDatabase.initialize(path.toString()).use { db ->
                        db.connect().use { connection ->
                            connection.createStatement().use { statement ->
                                statement.executeUpdate(
                                    """
                                    INSERT INTO execution_account_binding_audit
                                        (account_identity_digest, credential_generation_digest,
                                         verification_method, verified_at)
                                    VALUES ('preserved-account-evidence', 'credentials', 'test', 0)
                                    """.trimIndent(),
                                )
                                statement.executeUpdate(mutation)
                            }
                        }
                    }
                    val marker = Files.readString(directory.resolve("execution.db.journal-id"))

                    shouldThrow<IllegalStateException> { ExecutionDatabase.init(path.toString()) }

                    Files.exists(path) shouldBe true
                    Files.readString(directory.resolve("execution.db.journal-id")) shouldBe marker
                    DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                        connection.createStatement().use { statement ->
                            statement.executeQuery(verifyCorruption).use { result ->
                                result.next() shouldBe true
                                result.getInt(1) shouldBe 1
                            }
                            statement.executeQuery(
                                "SELECT account_identity_digest FROM execution_account_binding_audit",
                            ).use { result ->
                                result.next() shouldBe true
                                result.getString(1) shouldBe "preserved-account-evidence"
                                result.next() shouldBe false
                            }
                        }
                    }
                }
            }
        }

        "resumed initialization refuses an unreadable existing identity marker" {
            withTempDirectory { directory ->
                val path = directory.resolve("execution.db")
                ExecutionDatabase.initialize(path.toString()).close()
                val marker = directory.resolve("execution.db.journal-id")
                Files.delete(marker)
                Files.createDirectory(marker)

                shouldThrow<IOException> { ExecutionDatabase.resumeFreshInitialization(path.toString()) }
                    .message shouldContain "cannot be read"
                Files.isDirectory(marker) shouldBe true
                Files.exists(path) shouldBe true
            }
        }

        listOf("matching", "different", "unreadable").forEach { markerState ->
            "a concurrent marker creator is accepted only with matching identity: $markerState" {
                withTempDirectory { directory ->
                    val path = directory.resolve("execution.db")
                    val marker = directory.resolve("execution.db.journal-id")
                    mockkStatic(FileChannel::class)
                    try {
                        every {
                            FileChannel.open(marker, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                        } answers {
                            val identity = DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                                connection.createStatement().use { statement ->
                                    statement.executeQuery("SELECT journal_id FROM execution_journal_metadata").use {
                                        it.next()
                                        it.getString(1)
                                    }
                                }
                            }
                            when (markerState) {
                                "matching" -> Files.writeString(marker, "$identity\n")
                                "different" -> Files.writeString(marker, "other-journal\n")
                                else -> Files.createDirectory(marker)
                            }
                            throw FileAlreadyExistsException(marker.toString())
                        }

                        if (markerState == "matching") {
                            ExecutionDatabase.initialize(path.toString()).use { db ->
                                Files.readString(marker) shouldBe "${db.journalId()}\n"
                                db.path shouldBe path.toString()
                            }
                        } else {
                            shouldThrow<IOException> { ExecutionDatabase.initialize(path.toString()) }
                                .message shouldContain
                                if (markerState == "different") "does not match" else "cannot be read"
                            Files.exists(path) shouldBe false
                            if (markerState == "different") {
                                Files.readString(marker) shouldBe "other-journal\n"
                            } else {
                                Files.isDirectory(marker) shouldBe true
                            }
                        }
                    } finally {
                        unmockkStatic(FileChannel::class)
                    }
                }
            }
        }

        "initialization refuses a driver that cannot enable WAL and removes the unfinished database" {
            withTempDirectory { directory ->
                val path = directory.resolve("execution.db")
                val connection = mockk<Connection>(relaxed = true)
                val statement = mockk<Statement>(relaxed = true)
                val result = mockk<ResultSet>(relaxed = true)
                every { connection.createStatement() } returns statement
                every { statement.executeQuery("PRAGMA journal_mode = WAL") } returns result
                every { result.next() } returns true
                every { result.getString(1) } returns "delete"
                mockkStatic(DriverManager::class)
                try {
                    every { DriverManager.getConnection("jdbc:sqlite:$path") } returns connection

                    shouldThrow<IllegalStateException> { ExecutionDatabase.initialize(path.toString()) }
                        .message shouldContain "could not enter WAL mode"

                    verify(exactly = 1) { connection.close() }
                    Files.exists(path) shouldBe false
                    Files.exists(directory.resolve("execution.db.journal-id")) shouldBe false
                } finally {
                    unmockkStatic(DriverManager::class)
                }
            }
        }

        listOf("foreign_keys", "busy_timeout", "synchronous").forEach { rejectedPragma ->
            "connections fail closed and release resources when $rejectedPragma cannot be enforced" {
                val connection = mockk<Connection>(relaxed = true)
                val statement = mockk<Statement>(relaxed = true)
                every { connection.createStatement() } returns statement
                every { statement.executeQuery(any()) } answers {
                    val pragma = firstArg<String>().substringAfter("PRAGMA ")
                    mockk<ResultSet>(relaxed = true) {
                        every { next() } returns true
                        every { getInt(1) } returns when (pragma) {
                            rejectedPragma -> -1
                            "foreign_keys" -> 1
                            "busy_timeout" -> 5000
                            else -> 2
                        }
                    }
                }
                val url = "jdbc:sqlite:pragma-enforcement-test"
                mockkStatic(DriverManager::class)
                try {
                    every { DriverManager.getConnection(url) } returns connection
                    val db = ExecutionDatabase(":memory:", url)

                    shouldThrow<IllegalStateException> { db.connect() }

                    verify(exactly = 1) { connection.close() }
                    verify(exactly = 1) { statement.close() }
                } finally {
                    unmockkStatic(DriverManager::class)
                }
            }
        }
    }

    private fun withTempDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("execution-database-safety-")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
