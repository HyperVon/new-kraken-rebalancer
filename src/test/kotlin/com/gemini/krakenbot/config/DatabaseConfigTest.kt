package com.gemini.krakenbot.config

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import java.sql.DriverManager
import java.time.Instant
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
