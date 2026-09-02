package com.gemini.krakenbot.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.sql.DriverManager
import java.util.UUID

@Suppress("unused")
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
                "order-intent-trade-foreign-key",
                "portfolio-stats-singleton",
            )
        }

        "rejects duplicate, unordered, and incomplete migration definitions" {
            shouldThrow<IllegalStateException> {
                validateSchemaMigrations(
                    listOf(SchemaMigration(1, "first"), SchemaMigration(1, "duplicate")),
                )
            }
            shouldThrow<IllegalStateException> {
                validateSchemaMigrations(
                    listOf(SchemaMigration(2, "second"), SchemaMigration(1, "first")),
                )
            }
            shouldThrow<IllegalStateException> {
                validateSchemaMigrations(listOf(SchemaMigration(1, "only")))
            }
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
                        6 to "order-intent-trade-foreign-key",
                        7 to "portfolio-stats-singleton",
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
                        6 to "order-intent-trade-foreign-key",
                        7 to "portfolio-stats-singleton",
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

        "rejects a database recorded at a version newer than this binary" {
            val databaseUrl = "jdbc:sqlite:file:test-migrations-future-" + UUID.randomUUID() +
                "?mode=memory&cache=shared"
            DatabaseConfig.init(databaseUrl)
            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DELETE FROM schema_migrations")
                    statement.executeUpdate(
                        "INSERT INTO schema_migrations (version, name, applied_at) VALUES " +
                            "(" + (CURRENT_SCHEMA_VERSION + 1) + ", 'future', 1000)",
                    )
                }
            }

            shouldThrow<IllegalStateException> { DatabaseConfig.init(databaseUrl) }
        }

        "accepts a database without a schema migration table before bootstrap" {
            val databaseUrl = "jdbc:sqlite:file:test-migrations-no-table-" + UUID.randomUUID() +
                "?mode=memory&cache=shared"
            val database = Database.connect(databaseUrl)

            transaction(database) {
                rejectUnsupportedSchemaVersion()
            }
        }

        "accepts an empty schema migration table before bootstrap" {
            val databaseUrl = "jdbc:sqlite:file:test-migrations-empty-table-" + UUID.randomUUID() +
                "?mode=memory&cache=shared"
            val database = Database.connect(databaseUrl)

            transaction(database) {
                exec(
                    """
                    CREATE TABLE schema_migrations (
                        version INTEGER PRIMARY KEY,
                        name TEXT NOT NULL,
                        applied_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                rejectUnsupportedSchemaVersion()
            }
        }

        "replaying the order-intent migration is harmless when its table already exists" {
            val databaseUrl = "jdbc:sqlite:file:test-migrations-existing-intent-" + UUID.randomUUID() +
                "?mode=memory&cache=shared"
            DatabaseConfig.init(databaseUrl)
            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 2")
                }
            }

            DatabaseConfig.init(databaseUrl)

            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT COUNT(*) FROM order_intents",
                    ).use { resultSet ->
                        resultSet.next() shouldBe true
                        resultSet.getInt(1) shouldBe 0
                    }
                }
            }
        }

        "restores a missing migration record without losing schema data" {
            val databaseUrl = "jdbc:sqlite:file:test-migrations-gap-" + UUID.randomUUID() +
                "?mode=memory&cache=shared"
            DatabaseConfig.init(databaseUrl)
            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 6")
                }
            }

            DatabaseConfig.init(databaseUrl)

            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT version FROM schema_migrations ORDER BY version").use { resultSet ->
                        val versions = buildList {
                            while (resultSet.next()) add(resultSet.getInt(1))
                        }
                        versions shouldBe (1..CURRENT_SCHEMA_VERSION).toList()
                    }
                }
            }
        }

        "foreign-key migration nulls orphaned legacy intent links" {
            val databaseUrl = "jdbc:sqlite:file:test-migrations-orphan-" + UUID.randomUUID() +
                "?mode=memory&cache=shared"
            DatabaseConfig.init(databaseUrl)

            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DROP TABLE order_intents")
                    statement.executeUpdate(
                        """
                        CREATE TABLE order_intents (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            cycle_id VARCHAR(36),
                            client_order_id VARCHAR(36),
                            client_order_id_ambiguous BOOLEAN NOT NULL DEFAULT 0,
                            pair VARCHAR(16) NOT NULL,
                            symbol VARCHAR(16) NOT NULL,
                            side VARCHAR(4) NOT NULL,
                            volume DECIMAL(24, 8) NOT NULL,
                            usd_amount DECIMAL(18, 2) NOT NULL,
                            expected_price DECIMAL(24, 8),
                            created_at INTEGER NOT NULL,
                            state VARCHAR(16) NOT NULL,
                            order_txid VARCHAR(64),
                            error_message TEXT,
                            resolved_at INTEGER,
                            resolution_evidence TEXT,
                            local_trade_id INTEGER
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        INSERT INTO order_intents (
                            cycle_id, client_order_id, client_order_id_ambiguous, pair, symbol, side,
                            volume, usd_amount, expected_price, created_at, state, local_trade_id
                        ) VALUES ('legacy-cycle', 'legacy-client', 0, 'XBTUSD', 'BTC', 'BUY',
                            '0.01000000', '500.00', '50000.00000000', 1700000000000, 'CONFIRMED', 999)
                        """.trimIndent(),
                    )
                    statement.executeUpdate("DELETE FROM schema_migrations WHERE version > 5")
                }
            }

            DatabaseConfig.init(databaseUrl)

            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT local_trade_id FROM order_intents").use { resultSet ->
                        resultSet.next() shouldBe true
                        resultSet.getObject(1) shouldBe null
                    }
                    statement.executeQuery("PRAGMA foreign_key_list('order_intents')").use { resultSet ->
                        resultSet.next() shouldBe true
                        resultSet.getString("on_delete") shouldBe "RESTRICT"
                    }
                }
            }
        }

        "repairs a missing order-intent foreign key on a current-version database" {
            val databaseUrl = "jdbc:sqlite:file:test-migrations-current-fk-" + UUID.randomUUID() +
                "?mode=memory&cache=shared"
            DatabaseConfig.init(databaseUrl)

            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DROP TABLE order_intents")
                    statement.executeUpdate(
                        """
                        CREATE TABLE order_intents (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            cycle_id VARCHAR(36),
                            client_order_id VARCHAR(36),
                            client_order_id_ambiguous BOOLEAN NOT NULL DEFAULT 0,
                            pair VARCHAR(16) NOT NULL,
                            symbol VARCHAR(16) NOT NULL,
                            side VARCHAR(4) NOT NULL,
                            volume DECIMAL(24, 8) NOT NULL,
                            usd_amount DECIMAL(18, 2) NOT NULL,
                            expected_price DECIMAL(24, 8),
                            created_at INTEGER NOT NULL,
                            state VARCHAR(16) NOT NULL,
                            order_txid VARCHAR(64),
                            error_message TEXT,
                            resolved_at INTEGER,
                            resolution_evidence TEXT,
                            local_trade_id INTEGER
                        )
                        """.trimIndent(),
                    )
                }
            }

            DatabaseConfig.init(databaseUrl)

            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA foreign_key_list('order_intents')").use { resultSet ->
                        resultSet.next() shouldBe true
                        resultSet.getString("table") shouldBe "trades"
                        resultSet.getString("from") shouldBe "local_trade_id"
                        resultSet.getString("to") shouldBe "id"
                        resultSet.getString("on_delete") shouldBe "RESTRICT"
                        resultSet.next() shouldBe false
                    }
                }
            }
        }

        "portfolio stats migration keeps one row with the highest ATH" {
            val databaseUrl = "jdbc:sqlite:file:test-migrations-stats-" + UUID.randomUUID() +
                "?mode=memory&cache=shared"
            DatabaseConfig.init(databaseUrl)

            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DROP TABLE portfolio_stats")
                    statement.executeUpdate(
                        "CREATE TABLE portfolio_stats (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "all_time_high DECIMAL(18, 2))",
                    )
                    statement.executeUpdate("INSERT INTO portfolio_stats (all_time_high) VALUES (100.00), (250.00)")
                    statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 7")
                }
            }

            DatabaseConfig.init(databaseUrl)

            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT id, all_time_high FROM portfolio_stats").use { resultSet ->
                        resultSet.next() shouldBe true
                        resultSet.getInt("id") shouldBe 1
                        resultSet.getBigDecimal("all_time_high")
                            .shouldBeEqualComparingTo(BigDecimal("250.00"))
                        resultSet.next() shouldBe false
                    }
                }
            }
        }
    }
}
