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
            val db = ExecutionDatabase.initialize(":memory:")
            db.journalId().shouldNotBeBlank()
        }

        "ensureReadyForSubmission enforces readiness and file-backed storage" {
            withTempDirectory { directory ->
                val path = directory.resolve("execution.db")
                val db = ExecutionDatabase.initialize(path.toString())
                shouldThrow<IllegalStateException> { db.ensureReadyForSubmission() }
                db.markReadyForSubmission()
                db.ensureReadyForSubmission()

                val memDb = ExecutionDatabase.init(":memory:")
                memDb.markReadyForSubmission()
                shouldThrow<IllegalStateException> { memDb.ensureReadyForSubmission() }
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
