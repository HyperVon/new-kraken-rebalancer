package com.gemini.krakenbot.config

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import java.util.Comparator
import java.util.UUID

class DatabaseConfigTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "should initialize database with in-memory connection" {
            val databaseUrl = "jdbc:sqlite:file:testdb-${UUID.randomUUID()}?mode=memory&cache=shared"
            val db = DatabaseConfig.init(databaseUrl)
            db shouldNotBe null
        }

        "should initialize in-memory database" {
            val db = DatabaseConfig.init(TestFixtures.MEMORY_)
            db shouldNotBe null
        }

        "records the current schema version and creates the order intent journal" {
            val databaseUrl = "jdbc:sqlite:file:schema-version-${UUID.randomUUID()}?mode=memory&cache=shared"
            DatabaseConfig.init(databaseUrl)

            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT MAX(version) FROM schema_migrations").use { resultSet ->
                        resultSet.next() shouldBe true
                        resultSet.getInt(1) shouldBe 4
                    }
                    statement.executeQuery(
                        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'order_intents'",
                    ).use { resultSet ->
                        resultSet.next() shouldBe true
                    }
                }
            }
        }

        "imports legacy pending trade guards into the operator journal" {
            val databaseUrl = "jdbc:sqlite:file:legacy-guard-${UUID.randomUUID()}?mode=memory&cache=shared"
            DatabaseConfig.init(databaseUrl)

            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 4")
                    statement.executeUpdate(
                        """
                        INSERT INTO trades (
                            timestamp, pair, side, symbol, volume, usd_amount, success, dry_run,
                            error_message, price, fee, slippage_percent, expected_price, source,
                            cycle_id, order_txid, trade_id, client_order_id, submission_state
                        ) VALUES
                        (
                            1700000000000, 'XBTUSD', 'BUY', 'BTC', '0.01000000', '500.00', 0, 0,
                            'timeout', '50000.00000000', '0.0000', NULL, '50000.00000000', 'LOCAL_ESTIMATE',
                            'cycle-legacy', NULL, NULL, 'client-legacy', 'PENDING'
                        ), (
                            1700000001000, 'XBTUSD', 'SELL', 'BTC', '0.00500000', '250.00', 0, 0,
                            'connection reset', '50000.00000000', '0.0000', NULL, '50000.00000000', 'LOCAL_ESTIMATE',
                            'cycle-legacy-uncertain', NULL, NULL, 'client-legacy-uncertain', 'UNCERTAIN'
                        )
                        """.trimIndent(),
                    )
                }
            }

            DatabaseConfig.init(databaseUrl)
            DatabaseConfig.init(databaseUrl)

            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT COUNT(*) FROM order_intents WHERE state = 'PENDING'",
                    ).use { resultSet ->
                        resultSet.next() shouldBe true
                        resultSet.getInt(1) shouldBe 0
                    }
                    statement.executeQuery(
                        "SELECT COUNT(*) FROM order_intents WHERE state = 'UNCERTAIN'",
                    ).use { resultSet ->
                        resultSet.next() shouldBe true
                        resultSet.getInt(1) shouldBe 2
                    }
                    statement.executeQuery("SELECT submission_state FROM trades").use { resultSet ->
                        resultSet.next() shouldBe true
                        resultSet.getString(1) shouldBe "PENDING"
                        resultSet.next() shouldBe true
                        resultSet.getString(1) shouldBe "UNCERTAIN"
                    }
                }
            }
        }

        "imports duplicate legacy guards without collapsing their local trade identities" {
            val databaseUrl = "jdbc:sqlite:file:duplicate-legacy-guard-${UUID.randomUUID()}?mode=memory&cache=shared"
            DatabaseConfig.init(databaseUrl)

            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        INSERT INTO trades (
                            timestamp, pair, side, symbol, volume, usd_amount, success, dry_run,
                            error_message, price, fee, slippage_percent, expected_price, source,
                            cycle_id, order_txid, trade_id, client_order_id, submission_state
                        ) VALUES
                        (
                            1700000010000, 'XBTUSD', 'BUY', 'BTC', '0.01000000', '500.00', 0, 0,
                            'timeout-1', '50000.00000000', '0.0000', NULL, '50000.00000000', 'LOCAL_ESTIMATE',
                            NULL, NULL, NULL, NULL, 'UNCERTAIN'
                        ), (
                            1700000010000, 'XBTUSD', 'BUY', 'BTC', '0.01000000', '500.00', 0, 0,
                            'timeout-2', '50000.00000000', '0.0000', NULL, '50000.00000000', 'LOCAL_ESTIMATE',
                            NULL, NULL, NULL, NULL, 'UNCERTAIN'
                        )
                        """.trimIndent(),
                    )
                }
            }

            DatabaseConfig.init(databaseUrl)

            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT local_trade_id FROM order_intents ORDER BY local_trade_id",
                    ).use { resultSet ->
                        resultSet.next() shouldBe true
                        val firstTradeId = resultSet.getInt(1)
                        resultSet.next() shouldBe true
                        val secondTradeId = resultSet.getInt(1)
                        (firstTradeId != secondTradeId) shouldBe true
                        resultSet.next() shouldBe false
                    }
                }
            }
        }

        "keeps duplicate non-null legacy client IDs explicitly ambiguous" {
            val databaseUrl = "jdbc:sqlite:file:duplicate-client-guard-${UUID.randomUUID()}?mode=memory&cache=shared"
            DatabaseConfig.init(databaseUrl)

            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        INSERT INTO trades (
                            timestamp, pair, side, symbol, volume, usd_amount, success, dry_run,
                            error_message, price, fee, slippage_percent, expected_price, source,
                            cycle_id, order_txid, trade_id, client_order_id, submission_state
                        ) VALUES
                        (
                            1700000011000, 'XBTUSD', 'BUY', 'BTC', '0.01000000', '500.00', 0, 0,
                            'timeout-1', '50000.00000000', '0.0000', NULL, '50000.00000000', 'LOCAL_ESTIMATE',
                            NULL, NULL, NULL, 'duplicate-client', 'UNCERTAIN'
                        ), (
                            1700000012000, 'XBTUSD', 'BUY', 'BTC', '0.01000000', '500.00', 0, 0,
                            'timeout-2', '50000.00000000', '0.0000', NULL, '50000.00000000', 'LOCAL_ESTIMATE',
                            NULL, NULL, NULL, 'duplicate-client', 'UNCERTAIN'
                        )
                        """.trimIndent(),
                    )
                }
            }

            DatabaseConfig.init(databaseUrl)

            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT client_order_id, local_trade_id, error_message FROM order_intents ORDER BY local_trade_id",
                    ).use { resultSet ->
                        repeat(2) {
                            resultSet.next() shouldBe true
                            resultSet.getString("client_order_id") shouldBe null
                            val expectedError =
                                "Ambiguous legacy client_order_id 'duplicate-client'; " +
                                    "verify this trade before resolution. timeout-${it + 1}"
                            resultSet.getString("error_message") shouldBe
                                expectedError
                        }
                        resultSet.next() shouldBe false
                    }
                }
            }
        }

        "does not let a resolved duplicate hide an unresolved legacy guard" {
            val databaseUrl = "jdbc:sqlite:file:mixed-client-guard-${UUID.randomUUID()}?mode=memory&cache=shared"
            DatabaseConfig.init(databaseUrl)

            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        INSERT INTO trades (
                            timestamp, pair, side, symbol, volume, usd_amount, success, dry_run,
                            error_message, price, fee, slippage_percent, expected_price, source,
                            cycle_id, order_txid, trade_id, client_order_id, submission_state
                        ) VALUES
                        (
                            1700000013000, 'XBTUSD', 'BUY', 'BTC', '0.01000000', '500.00', 1, 0,
                            NULL, '50000.00000000', '0.0000', NULL, '50000.00000000', 'LOCAL_ESTIMATE',
                            NULL, 'O-RESOLVED', NULL, 'mixed-client', NULL
                        ), (
                            1700000014000, 'XBTUSD', 'BUY', 'BTC', '0.01000000', '500.00', 0, 0,
                            'timeout', '50000.00000000', '0.0000', NULL, '50000.00000000', 'LOCAL_ESTIMATE',
                            NULL, NULL, NULL, 'mixed-client', 'UNCERTAIN'
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        INSERT INTO order_intents (
                            cycle_id, client_order_id, pair, symbol, side, volume, usd_amount,
                            expected_price, created_at, state, order_txid, error_message,
                            resolved_at, resolution_evidence, local_trade_id
                        ) VALUES (
                            NULL, 'mixed-client', 'XBTUSD', 'BTC', 'BUY', '0.01000000', '500.00',
                            '50000.00000000', 1700000013000, 'CONFIRMED', 'O-RESOLVED', NULL,
                            1700000015000, 'Already confirmed', NULL
                        )
                        """.trimIndent(),
                    )
                }
            }

            DatabaseConfig.init(databaseUrl)

            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT client_order_id, local_trade_id, state FROM order_intents ORDER BY id",
                    ).use { resultSet ->
                        resultSet.next() shouldBe true
                        resultSet.getString("client_order_id") shouldBe "mixed-client"
                        resultSet.getInt("local_trade_id") shouldNotBe 0
                        resultSet.getString("state") shouldBe "CONFIRMED"
                        resultSet.next() shouldBe true
                        resultSet.getString("client_order_id") shouldBe null
                        resultSet.getInt("local_trade_id") shouldNotBe 0
                        resultSet.getString("state") shouldBe "UNCERTAIN"
                        resultSet.next() shouldBe false
                    }
                }
            }
        }

        "migrates a v3 terminal null-client intent and clears its legacy guard" {
            val databaseUrl = "jdbc:sqlite:file:v3-terminal-intent-${UUID.randomUUID()}?mode=memory&cache=shared"
            DatabaseConfig.init(databaseUrl)
            var tradeId = 0

            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DROP TABLE order_intents")
                    statement.executeUpdate(
                        """
                        CREATE TABLE order_intents (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            cycle_id VARCHAR(36),
                            client_order_id VARCHAR(36),
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
                            resolution_evidence TEXT
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        INSERT INTO trades (
                            timestamp, pair, side, symbol, volume, usd_amount, success, dry_run,
                            error_message, price, fee, slippage_percent, expected_price, source,
                            cycle_id, order_txid, trade_id, client_order_id, submission_state
                        ) VALUES (
                            1700000020000, 'XBTUSD', 'BUY', 'BTC', '0.01000000', '500.00', 0, 0,
                            'response lost', '50000.00000000', '0.0000', NULL, '50000.00000000', 'LOCAL_ESTIMATE',
                            NULL, NULL, NULL, NULL, 'PENDING'
                        )
                        """.trimIndent(),
                    )
                    tradeId = statement.executeQuery(
                        "SELECT id FROM trades ORDER BY id DESC LIMIT 1",
                    ).use { resultSet ->
                        resultSet.next() shouldBe true
                        resultSet.getInt(1)
                    }
                    statement.executeUpdate(
                        """
                        INSERT INTO order_intents (
                            cycle_id, client_order_id, pair, symbol, side, volume, usd_amount,
                            expected_price, created_at, state, order_txid, error_message,
                            resolved_at, resolution_evidence
                        ) VALUES (
                            NULL, NULL, 'XBTUSD', 'BTC', 'BUY', '0.01000000', '500.00',
                            '50000.00000000', 1700000020000, 'CONFIRMED', 'O-V3', NULL,
                            1700000021000, 'Exchange query confirmed O-V3'
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 4")
                }
            }

            DatabaseConfig.init(databaseUrl)

            DriverManager.getConnection(databaseUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT local_trade_id FROM order_intents WHERE state = 'CONFIRMED'",
                    ).use { resultSet ->
                        resultSet.next() shouldBe true
                        resultSet.getInt(1) shouldBe tradeId
                    }
                    statement.executeQuery(
                        "SELECT success, submission_state, order_txid FROM trades",
                    ).use { resultSet ->
                        resultSet.next() shouldBe true
                        resultSet.getBoolean("success") shouldBe true
                        resultSet.getString("submission_state") shouldBe null
                        resultSet.getString("order_txid") shouldBe "O-V3"
                    }
                }
            }
        }

        "creates a backup for a file-backed JDBC URL before migration" {
            val directory = Files.createTempDirectory("kraken-db-backup-")
            try {
                val databasePath = directory.resolve("rebalancer.db")
                val databaseUrl = "jdbc:sqlite:$databasePath"
                DatabaseConfig.init(databaseUrl)

                DriverManager.getConnection(databaseUrl).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 4")
                    }
                }

                DatabaseConfig.init(databaseUrl)

                Files.list(directory).use { files ->
                    files.anyMatch { it.fileName.toString().startsWith("rebalancer.db.pre-migration-") } shouldBe true
                }
            } finally {
                Files.walk(directory).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }

        "migrates a genuine pre-provenance schema and preserves ambiguous provenance" {
            runTest {
                val databaseUrl =
                    "jdbc:sqlite:file:legacy-trade-${UUID.randomUUID()}?mode=memory&cache=shared&foreign_keys=true"

                DriverManager.getConnection(databaseUrl).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeUpdate(
                            """
                            CREATE TABLE trades (
                                id INTEGER PRIMARY KEY AUTOINCREMENT,
                                timestamp INTEGER NOT NULL,
                                pair VARCHAR(16) NOT NULL,
                                side VARCHAR(4) NOT NULL,
                                symbol VARCHAR(16) NOT NULL,
                                volume DECIMAL(24, 8) NOT NULL,
                                usd_amount DECIMAL(18, 2) NOT NULL,
                                success BOOLEAN NOT NULL,
                                dry_run BOOLEAN NOT NULL,
                                error_message TEXT,
                                price DECIMAL(24, 8) NOT NULL,
                                fee DECIMAL(18, 4) NOT NULL,
                                slippage_percent DECIMAL(10, 4)
                            )
                            """.trimIndent(),
                        )
                        statement.executeUpdate(
                            """
                            INSERT INTO trades (
                                timestamp, pair, side, symbol, volume, usd_amount, success, dry_run,
                                error_message, price, fee, slippage_percent
                            ) VALUES (
                                1700000000000, 'XBTUSD', 'BUY', 'BTC', '1.00000000', '10.00', 1, 0,
                                NULL, '10.00000000', '0.0200', NULL
                            )
                            """.trimIndent(),
                        )
                    }

                    val database = DatabaseConfig.init(databaseUrl)
                    val migrated = SqliteTradeRepositoryImpl(database)
                        .getTradesInRange(Instant.EPOCH, Instant.ofEpochMilli(1700000000000))
                        .single()

                    migrated.source shouldBe TradeSource.LEGACY_UNKNOWN
                    migrated.orderTxid shouldBe null
                    migrated.tradeId shouldBe null
                    migrated.clientOrderId shouldBe null
                    migrated.submissionState shouldBe null
                }
            }
        }
    }
}
