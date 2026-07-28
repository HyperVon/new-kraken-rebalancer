package com.gemini.krakenbot.config

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.sql.DriverManager

@Suppress("unused")
class DatabaseConfigTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "should initialize database and create file" {
            val dbFile = File("test-config.db")
            if (dbFile.exists()) dbFile.delete()

            try {
                val db = DatabaseConfig.init(dbFile.name)
                db shouldNotBe null
                dbFile.exists() shouldNotBe false
            } finally {
                if (dbFile.exists()) dbFile.delete()
            }
        }

        "should initialize in-memory database" {
            val db = DatabaseConfig.init(":memory:")
            db shouldNotBe null
        }

        "migrates ambiguous source-less legacy trades to LEGACY_UNKNOWN" {
            val dbFile = File.createTempFile("legacy-trade-source", ".db")
            dbFile.delete()

            try {
                DatabaseConfig.init(dbFile.absolutePath)
                DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeUpdate(
                            """
                            INSERT INTO trades (
                                timestamp, pair, side, symbol, volume, usd_amount, success, dry_run,
                                error_message, price, fee, slippage_percent, expected_price, source,
                                cycle_id, order_txid, trade_id
                            ) VALUES (
                                1700000000000, 'XBTUSD', 'BUY', 'BTC', '1.00000000', '10.00', 1, 0,
                                NULL, '10.00000000', '0.0200', NULL, NULL, NULL, NULL, 'ORDER-1', NULL
                            )
                            """.trimIndent(),
                        )
                    }
                }

                DatabaseConfig.init(dbFile.absolutePath)
                DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT source FROM trades").use { result ->
                            result.next() shouldBe true
                            result.getString("source") shouldBe "LEGACY_UNKNOWN"
                        }
                    }
                }
            } finally {
                dbFile.delete()
            }
        }
    }
}
