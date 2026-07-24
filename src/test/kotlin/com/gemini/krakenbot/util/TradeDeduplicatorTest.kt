package com.gemini.krakenbot.util

import com.gemini.krakenbot.model.TradeRecord
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

@Suppress("unused")
class TradeDeduplicatorTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "should return empty list when no duplicates are present" {
            val now = Instant.now()
            val records = listOf(
                TradeRecord(
                    now, "XBTUSD", "BUY", "BTC",
                    BigDecimal(
                        "1.0",
                    ),
                    BigDecimal("50000.00"), success = true, dryRun = false, id = 1,
                ),
                TradeRecord(
                    now.plusSeconds(
                        600,
                    ),
                    "XDGUSD", "BUY", "DOGE", BigDecimal("100.0"), BigDecimal("10.00"),
                    success = true,
                    dryRun = false,
                    id = 2,
                ),
            )

            val duplicates = TradeDeduplicator.findDuplicateTradeIds(records)
            duplicates.isEmpty() shouldBe true
        }

        "should identify pair alias duplicate trade records" {
            val now = Instant.now()
            val record1 =
                TradeRecord(
                    now, "XBTUSD", "BUY", "BTC", BigDecimal("1.0"), BigDecimal("50000.00"),
                    success = true,
                    dryRun = false,
                    id = 1,
                )
            val record2 =
                TradeRecord(
                    now.plusMillis(
                        100,
                    ),
                    "XXBTZUSD", "BUY", "BTC", BigDecimal("1.0"), BigDecimal("50000.00"),
                    success = true,
                    dryRun = false,
                    id = 2,
                )

            val duplicates = TradeDeduplicator.findDuplicateTradeIds(listOf(record1, record2))
            duplicates shouldContainExactly listOf(2)
        }

        "should identify local estimate duplicate trade records with material fee rate differences" {
            val now = Instant.now()
            val localEstimate = TradeRecord(
                timestamp = now,
                pair = "XBTUSD",
                side = "BUY",
                symbol = "BTC",
                volume = BigDecimal("1.0"),
                usdAmount = BigDecimal("50000.00"),
                success = true,
                dryRun = false,
                fee = BigDecimal("10.00"), // 0.02% fee rate
                id = 10,
            )
            val settledFill = TradeRecord(
                timestamp = now.plusSeconds(2),
                pair = "XBTUSD",
                side = "BUY",
                symbol = "BTC",
                volume = BigDecimal("1.0"),
                usdAmount = BigDecimal("50000.00"),
                success = true,
                dryRun = false,
                fee = BigDecimal("100.00"), // 0.20% fee rate (materially different)
                id = 11,
            )

            val duplicates = TradeDeduplicator.findDuplicateTradeIds(listOf(localEstimate, settledFill))
            duplicates shouldContainExactly listOf(11)
        }

        "should stop checking pairs if timestamp difference exceeds 300 seconds" {
            val now = Instant.now()
            val record1 =
                TradeRecord(
                    now, "XBTUSD", "BUY", "BTC", BigDecimal("1.0"), BigDecimal("50000.00"),
                    success = true,
                    dryRun = false,
                    id = 1,
                )
            val record2 =
                TradeRecord(
                    now.plusSeconds(
                        301,
                    ),
                    "XBTUSD", "BUY", "BTC", BigDecimal("1.0"), BigDecimal("50000.00"),
                    success = true,
                    dryRun = false,
                    id = 2,
                )

            val duplicates = TradeDeduplicator.findDuplicateTradeIds(listOf(record1, record2))
            duplicates.isEmpty() shouldBe true
        }
    }
}
