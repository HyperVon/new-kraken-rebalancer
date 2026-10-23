package com.gemini.krakenbot.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime

class DataDirectoryConfigTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "resolveDataDirectory uses system property when set" {
            val original = System.getProperty(DataDirectoryConfig.DATA_DIR_PROPERTY)
            try {
                System.setProperty(DataDirectoryConfig.DATA_DIR_PROPERTY, "/custom/data/dir")
                DataDirectoryConfig.resolveDataDirectory() shouldBe
                    Path.of("/custom/data/dir").toAbsolutePath().normalize()
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

        "executionDatabasePath resolves the execution journal file name" {
            val dir = Path.of("/test/data")
            DataDirectoryConfig.executionDatabasePath(dir) shouldBe dir.resolve(DataDirectoryConfig.EXECUTION_DB_FILE)
        }

        "no-argument startup methods use the configured data directory" {
            val tempDir = Files.createTempDirectory("data-dir-configured-default-test-")
            val original = System.getProperty(DataDirectoryConfig.DATA_DIR_PROPERTY)
            try {
                System.setProperty(DataDirectoryConfig.DATA_DIR_PROPERTY, tempDir.toString())

                DataDirectoryConfig.initializeDataDirectory()

                val executionDb = DataDirectoryConfig.executionDatabasePath(tempDir)
                val originalBytes = Files.readAllBytes(executionDb)
                DataDirectoryConfig.ensureDataDirectory()

                Files.readAllBytes(executionDb) shouldBe originalBytes
            } finally {
                if (original != null) {
                    System.setProperty(DataDirectoryConfig.DATA_DIR_PROPERTY, original)
                } else {
                    System.clearProperty(DataDirectoryConfig.DATA_DIR_PROPERTY)
                }
                tempDir.toFile().deleteRecursively()
            }
        }

        "initializeDataDirectory creates the execution database and removes its marker" {
            val tempDir = Files.createTempDirectory("data-dir-config-test-")
            try {
                DataDirectoryConfig.initializeDataDirectory(tempDir)
                Files.exists(DataDirectoryConfig.executionDatabasePath(tempDir)) shouldBe true
                Files.exists(tempDir.resolve(DataDirectoryConfig.INITIALIZATION_MARKER_FILE)) shouldBe false
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "initializeDataDirectory resumes after execution database creation" {
            val tempDir = Files.createTempDirectory("data-dir-resume-test-")
            val executionDb = DataDirectoryConfig.executionDatabasePath(tempDir)
            try {
                Files.createFile(tempDir.resolve(DataDirectoryConfig.INITIALIZATION_MARKER_FILE))
                ExecutionDatabase.initialize(executionDb.toString())

                DataDirectoryConfig.initializeDataDirectory(tempDir)

                ExecutionDatabase.init(executionDb.toString()).journalId().isNotBlank() shouldBe true
                Files.exists(tempDir.resolve(DataDirectoryConfig.INITIALIZATION_MARKER_FILE)) shouldBe false
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "initializeDataDirectory refuses a marker placed over a pre-existing foreign execution database" {
            val tempDir = Files.createTempDirectory("data-dir-stray-marker-test-")
            val executionDb = DataDirectoryConfig.executionDatabasePath(tempDir)
            val marker = tempDir.resolve(DataDirectoryConfig.INITIALIZATION_MARKER_FILE)
            val foreign = "foreign execution data"
            try {
                Files.writeString(executionDb, foreign)
                Files.setLastModifiedTime(executionDb, FileTime.fromMillis(1_000))
                Files.createFile(marker)
                Files.setLastModifiedTime(marker, FileTime.fromMillis(5_000))

                shouldThrow<IllegalStateException> { DataDirectoryConfig.initializeDataDirectory(tempDir) }

                Files.readString(executionDb) shouldBe foreign
                Files.exists(marker) shouldBe true
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "ensureDataDirectory fails when another process holds the initialization lock" {
            val tempDir = Files.createTempDirectory("data-dir-init-lock-test-")
            val lockFile = tempDir.resolve(DataDirectoryConfig.INITIALIZATION_LOCK_FILE)
            try {
                FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                    channel.tryLock().use {
                        val error = shouldThrow<IllegalStateException> {
                            DataDirectoryConfig.ensureDataDirectory(tempDir)
                        }
                        error.message shouldContain "another process"
                    }
                }
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "initialization lock is released when ensure fails" {
            val tempDir = Files.createTempDirectory("data-dir-lock-release-test-")
            val lockFile = tempDir.resolve(DataDirectoryConfig.INITIALIZATION_LOCK_FILE)
            val executionMarker = DataDirectoryConfig.executionDatabasePath(tempDir)
                .resolveSibling("${DataDirectoryConfig.EXECUTION_DB_FILE}${ExecutionDatabase.JOURNAL_MARKER_SUFFIX}")
            try {
                Files.writeString(executionMarker, "orphaned journal marker")
                shouldThrow<IllegalStateException> { DataDirectoryConfig.ensureDataDirectory(tempDir) }

                FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                    channel.tryLock().use { }
                }
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "initializeDataDirectory recovers an incomplete execution database under its marker" {
            val tempDir = Files.createTempDirectory("data-dir-incomplete-execution-test-")
            val executionDb = DataDirectoryConfig.executionDatabasePath(tempDir)
            try {
                Files.createFile(tempDir.resolve(DataDirectoryConfig.INITIALIZATION_MARKER_FILE))
                Files.createFile(executionDb)

                DataDirectoryConfig.initializeDataDirectory(tempDir)

                ExecutionDatabase.init(executionDb.toString()).journalId().isNotBlank() shouldBe true
                Files.exists(tempDir.resolve(DataDirectoryConfig.INITIALIZATION_MARKER_FILE)) shouldBe false
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
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "initializeDataDirectory leaves legacy reporting data untouched before creating execution state" {
            val tempDir = Files.createTempDirectory("data-dir-partial-init-test-")
            val legacyDb = tempDir.resolve(DataDirectoryConfig.LEGACY_REPORTING_DB_FILE)
            val original = "preserve existing reporting data"
            try {
                Files.writeString(legacyDb, original)

                shouldThrow<IllegalStateException> { DataDirectoryConfig.initializeDataDirectory(tempDir) }

                Files.exists(DataDirectoryConfig.executionDatabasePath(tempDir)) shouldBe false
                Files.readString(legacyDb) shouldBe original
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "initializeDataDirectory refuses an orphaned execution journal identity marker" {
            val tempDir = Files.createTempDirectory("data-dir-orphan-marker-test-")
            val executionDb = DataDirectoryConfig.executionDatabasePath(tempDir)
            val marker = executionDb.resolveSibling("${executionDb.fileName}${ExecutionDatabase.JOURNAL_MARKER_SUFFIX}")
            try {
                Files.writeString(marker, "orphaned identity")

                shouldThrow<IllegalStateException> { DataDirectoryConfig.initializeDataDirectory(tempDir) }

                Files.exists(executionDb) shouldBe false
                Files.readString(marker) shouldBe "orphaned identity"
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "ensureDataDirectory creates the execution database on a fresh directory" {
            val tempDir = Files.createTempDirectory("data-dir-ensure-fresh-test-")
            try {
                DataDirectoryConfig.ensureDataDirectory(tempDir)

                Files.exists(DataDirectoryConfig.executionDatabasePath(tempDir)) shouldBe true
                ExecutionDatabase.init(DataDirectoryConfig.executionDatabasePath(tempDir).toString())
                    .journalId().isNotBlank() shouldBe true
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
                Files.exists(tempDir.resolve(DataDirectoryConfig.INITIALIZATION_MARKER_FILE)) shouldBe false
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }

        "ensureDataDirectory fails closed on a legacy reporting data directory" {
            for (withInitializationMarker in listOf(false, true)) {
                val tempDir = Files.createTempDirectory("data-dir-ensure-mixed-test-")
                val legacyDb = tempDir.resolve(DataDirectoryConfig.LEGACY_REPORTING_DB_FILE)
                try {
                    Files.writeString(legacyDb, "legacy data")
                    if (withInitializationMarker) {
                        Files.createFile(tempDir.resolve(DataDirectoryConfig.INITIALIZATION_MARKER_FILE))
                    }

                    shouldThrow<IllegalStateException> { DataDirectoryConfig.ensureDataDirectory(tempDir) }

                    Files.readString(legacyDb) shouldBe "legacy data"
                    Files.exists(DataDirectoryConfig.executionDatabasePath(tempDir)) shouldBe false
                } finally {
                    tempDir.toFile().deleteRecursively()
                }
            }
        }
    }
}
