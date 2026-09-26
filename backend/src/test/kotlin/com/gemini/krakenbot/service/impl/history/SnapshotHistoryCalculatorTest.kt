package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.LedgerEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

@Suppress("unused")
class SnapshotHistoryCalculatorTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "requireTrustworthyPrice rejects nonpositive values from every price source" {
            val timestamp = Instant.parse("2026-09-24T12:00:00Z")

            val exception = shouldThrow<HistoricalPriceUnavailableException> {
                SnapshotHistoryCalculator.requireTrustworthyPrice(
                    symbol = "BTC",
                    timestamp = timestamp,
                    ohlcData = mapOf(
                        "BTC" to listOf(
                            timestamp.epochSecond to BigDecimal.ZERO,
                            timestamp.plusSeconds(60).epochSecond to BigDecimal("-1"),
                        ),
                    ),
                    tradePrices = mapOf(
                        "BTC" to listOf(
                            timestamp to BigDecimal.ZERO,
                            timestamp.plusSeconds(60) to BigDecimal("-1"),
                        ),
                    ),
                    currentPrices = mapOf("BTC" to BigDecimal.ZERO),
                )
            }

            exception.message shouldBe
                "No trustworthy price for BTC at $timestamp during historical reconstruction."
        }

        "buildTimelineEvents rejects unknown raw ledger types instead of dropping them" {
            val timestamp = Instant.parse("2026-09-24T12:00:00Z")
            val exception = shouldThrow<IllegalArgumentException> {
                SnapshotHistoryCalculator.buildTimelineEvents(
                    historicalTrades = emptyList(),
                    historicalRewards = listOf(
                        LedgerEvent(
                            ledgerId = "unknown-ledger",
                            time = timestamp,
                            type = "future_ledger_type",
                            asset = "BTC",
                            amount = BigDecimal.ONE,
                        ),
                    ),
                    cutoffTime = timestamp.plusSeconds(1),
                    now = timestamp,
                )
            }

            exception.message shouldBe
                "Cannot build timeline with unknown raw ledger type: future_ledger_type (unknown-ledger)"
        }
    }
}
