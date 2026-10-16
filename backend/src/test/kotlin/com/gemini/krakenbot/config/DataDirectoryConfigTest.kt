package com.gemini.krakenbot.config

import com.gemini.krakenbot.service.history.HistoryDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import java.nio.file.Files
import java.nio.file.Path

class DataDirectoryConfigTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "resolveDataDirectory uses system property when set" {
            val original = System.getProperty(DataDirectoryConfig.DATA_DIR_PROPERTY)
            try {
                System.setProperty(DataDirectoryConfig.DATA_DIR_PROPERTY, "/custom/data/dir")
                val resolved = DataDirectoryConfig.resolveDataDirectory()
                resolved shouldBe Path.of("/custom/data/dir").toAbsolutePath().normalize()
            } finally {
                if (original != null) {
                    System.setProperty(DataDirectoryConfig.DATA_DIR_PROPERTY, original)
                } else {
                    System.clearProperty(DataDirectoryConfig.DATA_DIR_PROPERTY)
                }
            }
        }

        "resolveDataDirectory falls back to default when system property is blank" {
            val original = System.getProperty(DataDirectoryConfig.DATA_DIR_PROPERTY)
            try {
                System.setProperty(DataDirectoryConfig.DATA_DIR_PROPERTY, "   ")
                val resolved = DataDirectoryConfig.resolveDataDirectory()
                // If KRAKEN_DATA_DIR env var is not set, it should end with DEFAULT_DATA_DIR
                if (System.getenv("KRAKEN_DATA_DIR").isNullOrBlank()) {
                    resolved.toString() shouldEndWith DataDirectoryConfig.DEFAULT_DATA_DIR
                }
            } finally {
                if (original != null) {
                    System.setProperty(DataDirectoryConfig.DATA_DIR_PROPERTY, original)
                } else {
                    System.clearProperty(DataDirectoryConfig.DATA_DIR_PROPERTY)
                }
            }
        }

        "executionDatabasePath and historyDatabasePath resolve expected file names" {
            val dir = Path.of("/test/data")
            DataDirectoryConfig.executionDatabasePath(dir) shouldBe dir.resolve(DataDirectoryConfig.EXECUTION_DB_FILE)
            DataDirectoryConfig.historyDatabasePath(dir) shouldBe dir.resolve(DataDirectoryConfig.HISTORY_DB_FILE)
        }

        "initializeDataDirectory creates directories and initial databases" {
            val tempDir = Files.createTempDirectory("data-dir-config-test-")
            try {
                DataDirectoryConfig.initializeDataDirectory(tempDir)
                Files.exists(DataDirectoryConfig.executionDatabasePath(tempDir)) shouldBe true
                Files.exists(DataDirectoryConfig.historyDatabasePath(tempDir)) shouldBe true
                Files.exists(tempDir.resolve(DataDirectoryConfig.INITIALIZATION_MARKER_FILE)) shouldBe false
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "initializeDataDirectory resumes after execution database creation" {
            val tempDir = Files.createTempDirectory("data-dir-resume-test-")
            val executionDb = DataDirectoryConfig.executionDatabasePath(tempDir)
            val historyDb = DataDirectoryConfig.historyDatabasePath(tempDir)
            try {
                Files.createFile(tempDir.resolve(DataDirectoryConfig.INITIALIZATION_MARKER_FILE))
                ExecutionDatabase.initialize(executionDb.toString())
                Files.createFile(historyDb)

                DataDirectoryConfig.initializeDataDirectory(tempDir)

                ExecutionDatabase.init(executionDb.toString()).journalId().isNotBlank() shouldBe true
                HistoryDatabase(historyDb).connect().use { }
                Files.exists(tempDir.resolve(DataDirectoryConfig.INITIALIZATION_MARKER_FILE)) shouldBe false
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "initializeDataDirectory recovers an incomplete execution database under its initialization marker" {
            val tempDir = Files.createTempDirectory("data-dir-incomplete-execution-test-")
            val executionDb = DataDirectoryConfig.executionDatabasePath(tempDir)
            val historyDb = DataDirectoryConfig.historyDatabasePath(tempDir)
            try {
                Files.createFile(tempDir.resolve(DataDirectoryConfig.INITIALIZATION_MARKER_FILE))
                Files.createFile(executionDb)
                Files.createFile(historyDb)

                DataDirectoryConfig.initializeDataDirectory(tempDir)

                ExecutionDatabase.init(executionDb.toString()).journalId().isNotBlank() shouldBe true
                HistoryDatabase(historyDb).connect().use { }
                Files.exists(tempDir.resolve(DataDirectoryConfig.INITIALIZATION_MARKER_FILE)) shouldBe false
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "initializeDataDirectory leaves an existing history database untouched before creating execution state" {
            val tempDir = Files.createTempDirectory("data-dir-partial-init-test-")
            val historyDb = DataDirectoryConfig.historyDatabasePath(tempDir)
            val original = "preserve existing history data"
            try {
                Files.writeString(historyDb, original)

                shouldThrow<IllegalStateException> { DataDirectoryConfig.initializeDataDirectory(tempDir) }

                Files.exists(DataDirectoryConfig.executionDatabasePath(tempDir)) shouldBe false
                Files.readString(historyDb) shouldBe original
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "initializeDataDirectory refuses to replace an existing execution database" {
            val tempDir = Files.createTempDirectory("data-dir-existing-execution-test-")
            val executionDb = DataDirectoryConfig.executionDatabasePath(tempDir)
            val original = "existing execution journal"
            try {
                Files.writeString(executionDb, original)

                shouldThrow<IllegalStateException> { DataDirectoryConfig.initializeDataDirectory(tempDir) }

                Files.readString(executionDb) shouldBe original
                Files.exists(DataDirectoryConfig.historyDatabasePath(tempDir)) shouldBe false
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "initializeDataDirectory refuses an orphaned active journal identity marker" {
            val tempDir = Files.createTempDirectory("data-dir-orphan-marker-test-")
            val executionDb = DataDirectoryConfig.executionDatabasePath(tempDir)
            val marker = executionDb.resolveSibling("${executionDb.fileName}${ExecutionDatabase.JOURNAL_MARKER_SUFFIX}")
            try {
                Files.writeString(marker, "orphaned identity")

                shouldThrow<IllegalStateException> { DataDirectoryConfig.initializeDataDirectory(tempDir) }

                Files.exists(executionDb) shouldBe false
                Files.readString(marker) shouldBe "orphaned identity"
                Files.exists(DataDirectoryConfig.historyDatabasePath(tempDir)) shouldBe false
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "ensureDataDirectory creates both stores on a fresh directory" {
            val tempDir = Files.createTempDirectory("data-dir-ensure-fresh-test-")
            try {
                DataDirectoryConfig.ensureDataDirectory(tempDir)

                Files.exists(DataDirectoryConfig.executionDatabasePath(tempDir)) shouldBe true
                Files.exists(DataDirectoryConfig.historyDatabasePath(tempDir)) shouldBe true
                ExecutionDatabase.init(DataDirectoryConfig.executionDatabasePath(tempDir).toString())
                    .journalId().isNotBlank() shouldBe true
                HistoryDatabase(DataDirectoryConfig.historyDatabasePath(tempDir)).verifyInitialized()
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "ensureDataDirectory leaves an initialized directory untouched" {
            val tempDir = Files.createTempDirectory("data-dir-ensure-ready-test-")
            try {
                DataDirectoryConfig.initializeDataDirectory(tempDir)
                val executionBefore = Files.readAllBytes(DataDirectoryConfig.executionDatabasePath(tempDir))

                DataDirectoryConfig.ensureDataDirectory(tempDir)

                Files.readAllBytes(DataDirectoryConfig.executionDatabasePath(tempDir)) shouldBe executionBefore
                HistoryDatabase(DataDirectoryConfig.historyDatabasePath(tempDir)).verifyInitialized()
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "ensureDataDirectory resumes an interrupted initialization" {
            val tempDir = Files.createTempDirectory("data-dir-ensure-resume-test-")
            try {
                Files.createFile(tempDir.resolve(DataDirectoryConfig.INITIALIZATION_MARKER_FILE))

                DataDirectoryConfig.ensureDataDirectory(tempDir)

                Files.exists(DataDirectoryConfig.executionDatabasePath(tempDir)) shouldBe true
                Files.exists(DataDirectoryConfig.historyDatabasePath(tempDir)) shouldBe true
                Files.exists(tempDir.resolve(DataDirectoryConfig.INITIALIZATION_MARKER_FILE)) shouldBe false
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "ensureDataDirectory fails closed on a mixed directory" {
            val tempDir = Files.createTempDirectory("data-dir-ensure-mixed-test-")
            val historyDb = DataDirectoryConfig.historyDatabasePath(tempDir)
            try {
                Files.writeString(historyDb, "half-copied store")

                shouldThrow<IllegalStateException> { DataDirectoryConfig.ensureDataDirectory(tempDir) }

                Files.readString(historyDb) shouldBe "half-copied store"
                Files.exists(DataDirectoryConfig.executionDatabasePath(tempDir)) shouldBe false
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }
    }
}
