package com.gemini.krakenbot.config

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.sql.Connection
import java.sql.DriverManager
import java.util.Comparator

class MigrationBackupTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "online backup includes committed WAL rows while an old reader holds its snapshot" {
            val directory = Files.createTempDirectory("migration-backup-wal-")
            val databasePath = directory.resolve("legacy.db")
            val databaseUrl = "jdbc:sqlite:$databasePath"
            try {
                createLegacyWalDatabase(databaseUrl)
                val permissionView = Files.getFileAttributeView(databasePath, PosixFileAttributeView::class.java)
                val expectedPermissions = setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ,
                )
                val canSetPosixPermissions = runCatching {
                    permissionView?.setPermissions(expectedPermissions)
                    permissionView != null
                }.getOrDefault(false)

                DriverManager.getConnection(databaseUrl).use { reader ->
                    reader.autoCommit = false
                    countRows(reader) shouldBe 1

                    DriverManager.getConnection(databaseUrl).use { writer ->
                        writer.createStatement().use { statement ->
                            statement.executeUpdate(
                                "INSERT INTO sample_rows (value) VALUES ('after-reader')",
                            ) shouldBe 1
                        }
                    }

                    countRows(reader) shouldBe 1
                    (Files.size(databasePath.resolveSibling("${databasePath.fileName}-wal")) > 32L) shouldBe true

                    backupBeforeMigrationIfNeeded(databasePath.toString(), emptyArray())

                    val backupPath = Files.list(directory).use { paths ->
                        paths.filter { it.fileName.toString().endsWith(".bak") }.findFirst().get()
                    }
                    backupPath.fileName.toString().startsWith("legacy.db.pre-migration-") shouldBe true
                    DriverManager.getConnection("jdbc:sqlite:$backupPath").use { backup ->
                        countRows(backup) shouldBe 2
                    }
                    if (canSetPosixPermissions) {
                        Files.getPosixFilePermissions(backupPath) shouldBe expectedPermissions
                    }
                    Files.exists(backupPath.resolveSibling("${backupPath.fileName}-wal")) shouldBe false
                    Files.exists(backupPath.resolveSibling("${backupPath.fileName}-shm")) shouldBe false
                }
            } finally {
                deleteTree(directory)
            }
        }

        "failed online backup removes its temporary destination and fails closed" {
            val directory = Files.createTempDirectory("migration-backup-failure-")
            val databasePath = directory.resolve("corrupt.db")
            val originalContents = "not a sqlite database".toByteArray()
            try {
                Files.write(databasePath, originalContents)

                val failure = runCatching {
                    backupBeforeMigrationIfNeeded(databasePath.toString(), emptyArray())
                }.exceptionOrNull()

                requireNotNull(failure).message shouldContain "Cannot create pre-migration database backup"
                Files.readAllBytes(databasePath).toList() shouldBe originalContents.toList()
                Files.list(directory).use { paths ->
                    paths.noneMatch { it.fileName.toString().contains("pre-migration") } shouldBe true
                }
            } finally {
                deleteTree(directory)
            }
        }
    }

    private fun createLegacyWalDatabase(databaseUrl: String) {
        DriverManager.getConnection(databaseUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA journal_mode=WAL").use { resultSet ->
                    resultSet.next() shouldBe true
                    resultSet.getString(1).lowercase() shouldBe "wal"
                }
                statement.execute("CREATE TABLE schema_migrations (version INTEGER NOT NULL)")
                statement.executeUpdate("INSERT INTO schema_migrations (version) VALUES (0)")
                statement.execute("CREATE TABLE sample_rows (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
                statement.executeUpdate("INSERT INTO sample_rows (value) VALUES ('before-reader')")
            }
        }
    }

    private fun countRows(connection: Connection): Int = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM sample_rows").use { resultSet ->
            resultSet.next() shouldBe true
            resultSet.getInt(1)
        }
    }

    private fun deleteTree(directory: Path) {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
