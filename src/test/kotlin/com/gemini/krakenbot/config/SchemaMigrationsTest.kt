package com.gemini.krakenbot.config

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.sql.DriverManager
import java.util.UUID

class SchemaMigrationsTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "SCHEMA_MIGRATIONS contains all version records up to CURRENT_SCHEMA_VERSION" {
            SCHEMA_MIGRATIONS.size shouldBe CURRENT_SCHEMA_VERSION
            SCHEMA_MIGRATIONS.map { it.version } shouldBe (1..CURRENT_SCHEMA_VERSION).toList()
            SCHEMA_MIGRATIONS.map { it.name } shouldBe listOf(
                "baseline",
                "order-intent-journal",
                "legacy-submission-guard-import",
                "legacy-trade-identity",
                "ambiguous-legacy-client-order-id",
            )
        }

        "applies all pending migrations from baseline via DatabaseConfig.init" {
            val databaseUrl = "jdbc:sqlite:file:test-migrations-baseline-${UUID.randomUUID()}?mode=memory&cache=shared"
            DatabaseConfig.init(databaseUrl) shouldNotBe null

            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    val recorded = buildList {
                        statement.executeQuery(
                            "SELECT version, name FROM schema_migrations ORDER BY version",
                        ).use { resultSet ->
                            while (resultSet.next()) {
                                add(resultSet.getInt("version") to resultSet.getString("name"))
                            }
                        }
                    }
                    recorded shouldBe listOf(
                        1 to "baseline",
                        2 to "order-intent-journal",
                        3 to "legacy-submission-guard-import",
                        4 to "legacy-trade-identity",
                        5 to "ambiguous-legacy-client-order-id",
                    )
                }
            }
        }

        "applies only migrations with version greater than appliedVersion on pre-existing database" {
            val databaseUrl =
                "jdbc:sqlite:file:test-migrations-incremental-${UUID.randomUUID()}?mode=memory&cache=shared"
            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        CREATE TABLE schema_migrations (
                            version INTEGER PRIMARY KEY,
                            name TEXT NOT NULL,
                            applied_at INTEGER NOT NULL
                        );
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        INSERT INTO schema_migrations (version, name, applied_at) VALUES
                            (1, 'baseline', 1000),
                            (2, 'order-intent-journal', 2000),
                            (3, 'legacy-submission-guard-import', 3000);
                        """.trimIndent(),
                    )
                }
            }

            DatabaseConfig.init(databaseUrl) shouldNotBe null

            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    val recorded = buildList {
                        statement.executeQuery(
                            "SELECT version, name FROM schema_migrations ORDER BY version",
                        ).use { resultSet ->
                            while (resultSet.next()) {
                                add(resultSet.getInt("version") to resultSet.getString("name"))
                            }
                        }
                    }
                    recorded shouldBe listOf(
                        1 to "baseline",
                        2 to "order-intent-journal",
                        3 to "legacy-submission-guard-import",
                        4 to "legacy-trade-identity",
                        5 to "ambiguous-legacy-client-order-id",
                    )
                }
            }
        }

        "does nothing when database is already at CURRENT_SCHEMA_VERSION" {
            val databaseUrl = "jdbc:sqlite:file:test-migrations-current-${UUID.randomUUID()}?mode=memory&cache=shared"
            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        CREATE TABLE schema_migrations (
                            version INTEGER PRIMARY KEY,
                            name TEXT NOT NULL,
                            applied_at INTEGER NOT NULL
                        );
                        """.trimIndent(),
                    )
                    (1..CURRENT_SCHEMA_VERSION).forEach { v ->
                        statement.executeUpdate(
                            "INSERT INTO schema_migrations (version, name, applied_at) VALUES ($v, 'custom-$v', 1000);",
                        )
                    }
                }
            }

            DatabaseConfig.init(databaseUrl) shouldNotBe null

            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    val count = statement.executeQuery(
                        "SELECT COUNT(*) AS total FROM schema_migrations",
                    ).use { rs ->
                        rs.next()
                        rs.getInt("total")
                    }
                    count shouldBe CURRENT_SCHEMA_VERSION
                }
            }
        }
    }
}
