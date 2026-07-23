package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.TradeRecord
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

@Suppress("unused")
class SnapshotHistoryCalculatorTest : StringSpec({

    "buildTimelineEvents should generate trade and daily close events sorted descending" {
        val now = Instant.now()
        val cutoff = now.minus(5, ChronoUnit.DAYS)
        val trade = TradeRecord(
            timestamp = now.minus(2, ChronoUnit.DAYS),
            pair = "XBTUSD",
            side = OrderSide.BUY.uppercaseName,
            symbol = "BTC",
            volume = BigDecimal("0.1"),
            usdAmount = BigDecimal("5000.00"),
            success = true,
            dryRun = false
        )

        val events = SnapshotHistoryCalculator.buildTimelineEvents(
            historicalTrades = listOf(trade),
            cutoffTime = cutoff,
            now = now
        )

        events.shouldNotBeEmpty()
        (events.first().timestamp >= events.last().timestamp) shouldBe true
    }

    "calculateHistoricalSnapshots should calculate portfolio snapshots and reverse-apply trades" {
        val now = Instant.now()
        val cutoff = now.minus(5, ChronoUnit.DAYS)
        val trade = TradeRecord(
            timestamp = now.minus(2, ChronoUnit.DAYS),
            pair = "XBTUSD",
            side = OrderSide.BUY.uppercaseName,
            symbol = "BTC",
            volume = BigDecimal("0.1"),
            usdAmount = BigDecimal("5000.00"),
            fee = BigDecimal("13.00"),
            success = true,
            dryRun = false
        )

        val events = SnapshotHistoryCalculator.buildTimelineEvents(
            historicalTrades = listOf(trade),
            cutoffTime = cutoff,
            now = now
        )

        val allocations = listOf(
            Allocation(Asset(Asset.BTC), 50.0),
            Allocation(Asset.USD, 50.0)
        )

        val runningBalances = mutableMapOf(
            "BTC" to BigDecimal("0.5"),
            "USD" to BigDecimal("10000.00")
        )

        val currentPrices = mapOf("BTC" to BigDecimal("50000.00"), "USD" to BigDecimal.ONE)

        val snapshots = SnapshotHistoryCalculator.calculateHistoricalSnapshots(
            events = events,
            allocations = allocations,
            runningBalances = runningBalances,
            currentPrices = currentPrices,
            ohlcData = emptyMap(),
            tradePrices = emptyMap()
        )

        snapshots.shouldNotBeEmpty()
    }
})
