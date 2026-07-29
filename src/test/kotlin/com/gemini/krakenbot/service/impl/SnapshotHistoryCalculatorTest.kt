package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.service.impl.history.SnapshotHistoryCalculator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

@Suppress("unused")
class SnapshotHistoryCalculatorTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
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
                dryRun = false,
            )

            val events = SnapshotHistoryCalculator.buildTimelineEvents(
                historicalTrades = listOf(trade),
                cutoffTime = cutoff,
                now = now,
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
                dryRun = false,
            )

            val events = SnapshotHistoryCalculator.buildTimelineEvents(
                historicalTrades = listOf(trade),
                cutoffTime = cutoff,
                now = now,
            )

            val allocations = listOf(
                Allocation(Asset(Asset.BTC), 50.0),
                Allocation(Asset.USD, 50.0),
            )

            val runningBalances = mutableMapOf(
                "BTC" to BigDecimal("0.5"),
                "USD" to BigDecimal("10000.00"),
            )

            val currentPrices = mapOf("BTC" to BigDecimal("50000.00"), "USD" to BigDecimal.ONE)

            val snapshots = SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = events,
                allocations = allocations,
                runningBalances = runningBalances,
                currentPrices = currentPrices,
                ohlcData = emptyMap(),
                tradePrices = emptyMap(),
            )

            snapshots.shouldNotBeEmpty()

            // After reverse-applying the BUY: BTC -= 0.1, USD += 5000 + 13
            runningBalances["BTC"]!!.shouldBeEqualComparingTo(BigDecimal("0.4"))
            runningBalances["USD"]!!.shouldBeEqualComparingTo(BigDecimal("15013.00"))
        }

        "calculateHistoricalSnapshots should reverse-apply lowercase buy side like API-shaped rows" {
            val now = Instant.now()
            val cutoff = now.minus(5, ChronoUnit.DAYS)
            val trade =
                TradeRecord(
                    timestamp = now.minus(2, ChronoUnit.DAYS),
                    pair = "XBTUSD",
                    side = OrderSide.BUY.apiValue,
                    symbol = "BTC",
                    volume = BigDecimal("0.1"),
                    usdAmount = BigDecimal("5000.00"),
                    fee = BigDecimal("13.00"),
                    success = true,
                    dryRun = false,
                )

            val events =
                SnapshotHistoryCalculator.buildTimelineEvents(
                    historicalTrades = listOf(trade),
                    cutoffTime = cutoff,
                    now = now,
                )

            val allocations =
                listOf(
                    Allocation(Asset(Asset.BTC), 50.0),
                    Allocation(Asset.USD, 50.0),
                )

            val runningBalances =
                mutableMapOf(
                    "BTC" to BigDecimal("0.5"),
                    "USD" to BigDecimal("10000.00"),
                )

            val currentPrices = mapOf("BTC" to BigDecimal("50000.00"), "USD" to BigDecimal.ONE)

            SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = events,
                allocations = allocations,
                runningBalances = runningBalances,
                currentPrices = currentPrices,
                ohlcData = emptyMap(),
                tradePrices = emptyMap(),
            )

            runningBalances["BTC"]!!.shouldBeEqualComparingTo(BigDecimal("0.4"))
            runningBalances["USD"]!!.shouldBeEqualComparingTo(BigDecimal("15013.00"))
        }

        "calculateHistoricalSnapshots should reverse-apply SELL trades to running balances" {
            val now = Instant.now()
            val cutoff = now.minus(5, ChronoUnit.DAYS)
            val trade =
                TradeRecord(
                    timestamp = now.minus(2, ChronoUnit.DAYS),
                    pair = "XBTUSD",
                    side = OrderSide.SELL.uppercaseName,
                    symbol = "BTC",
                    volume = BigDecimal("0.1"),
                    usdAmount = BigDecimal("5000.00"),
                    fee = BigDecimal("13.00"),
                    success = true,
                    dryRun = false,
                )

            val events =
                SnapshotHistoryCalculator.buildTimelineEvents(
                    historicalTrades = listOf(trade),
                    cutoffTime = cutoff,
                    now = now,
                )

            val allocations =
                listOf(
                    Allocation(Asset(Asset.BTC), 50.0),
                    Allocation(Asset.USD, 50.0),
                )

            val runningBalances =
                mutableMapOf(
                    "BTC" to BigDecimal("0.5"),
                    "USD" to BigDecimal("10000.00"),
                )

            val currentPrices = mapOf("BTC" to BigDecimal("50000.00"), "USD" to BigDecimal.ONE)

            SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = events,
                allocations = allocations,
                runningBalances = runningBalances,
                currentPrices = currentPrices,
                ohlcData = emptyMap(),
                tradePrices = emptyMap(),
            )

            // After reverse-applying the SELL: BTC += 0.1, USD -= 5000 + fee returned
            runningBalances["BTC"]!!.shouldBeEqualComparingTo(BigDecimal("0.6"))
            runningBalances["USD"]!!.shouldBeEqualComparingTo(BigDecimal("5013.00"))
        }

        "calculateHistoricalSnapshots should use OHLC closest price over currentPrices" {
            val now = Instant.now()
            val cutoff = now.minus(5, ChronoUnit.DAYS)
            val tradeTime = now.minus(2, ChronoUnit.DAYS)
            val trade =
                TradeRecord(
                    timestamp = tradeTime,
                    pair = "XBTUSD",
                    side = OrderSide.BUY.uppercaseName,
                    symbol = "BTC",
                    volume = BigDecimal("0.1"),
                    usdAmount = BigDecimal("5000.00"),
                    success = true,
                    dryRun = false,
                )

            val events =
                SnapshotHistoryCalculator.buildTimelineEvents(
                    historicalTrades = listOf(trade),
                    cutoffTime = cutoff,
                    now = now,
                )

            val allocations =
                listOf(
                    Allocation(Asset(Asset.BTC), 50.0),
                    Allocation(Asset.USD, 50.0),
                )

            val runningBalances =
                mutableMapOf(
                    "BTC" to BigDecimal("0.5"),
                    "USD" to BigDecimal("10000.00"),
                )

            val currentPrices = mapOf("BTC" to BigDecimal("50000.00"), "USD" to BigDecimal.ONE)
            val ohlcPrice = BigDecimal("48000.00")
            val ohlcData =
                mapOf(
                    "BTC" to
                        listOf(
                            tradeTime.epochSecond - 3600 to BigDecimal("47000.00"),
                            tradeTime.epochSecond to ohlcPrice,
                            tradeTime.epochSecond + 3600 to BigDecimal("49000.00"),
                        ),
                )

            val snapshots =
                SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                    events = events,
                    allocations = allocations,
                    runningBalances = runningBalances,
                    currentPrices = currentPrices,
                    ohlcData = ohlcData,
                    tradePrices = emptyMap(),
                )

            val tradeSnapshot = snapshots.first { it.timestamp == tradeTime }
            tradeSnapshot.assets["BTC"]!!.price.shouldBeEqualComparingTo(ohlcPrice)
        }

        "calculateHistoricalSnapshots should pick first OHLC point when equidistant (strict less-than)" {
            val now = Instant.now()
            val cutoff = now.minus(5, ChronoUnit.DAYS)
            val tradeTime = now.minus(2, ChronoUnit.DAYS)
            val trade =
                TradeRecord(
                    timestamp = tradeTime,
                    pair = "XBTUSD",
                    side = OrderSide.BUY.uppercaseName,
                    symbol = "BTC",
                    volume = BigDecimal("0.1"),
                    usdAmount = BigDecimal("5000.00"),
                    success = true,
                    dryRun = false,
                )

            val events =
                SnapshotHistoryCalculator.buildTimelineEvents(
                    historicalTrades = listOf(trade),
                    cutoffTime = cutoff,
                    now = now,
                )

            val allocations =
                listOf(
                    Allocation(Asset(Asset.BTC), 50.0),
                    Allocation(Asset.USD, 50.0),
                )

            val runningBalances =
                mutableMapOf(
                    "BTC" to BigDecimal("0.5"),
                    "USD" to BigDecimal("10000.00"),
                )

            val currentPrices = mapOf("BTC" to BigDecimal("50000.00"), "USD" to BigDecimal.ONE)
            val equidistantPriceA = BigDecimal("47000.00")
            val equidistantPriceB = BigDecimal("49000.00")
            val ohlcData =
                mapOf(
                    "BTC" to
                        listOf(
                            tradeTime.epochSecond - 3600 to equidistantPriceA,
                            tradeTime.epochSecond + 3600 to equidistantPriceB,
                        ),
                )

            val snapshots =
                SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                    events = events,
                    allocations = allocations,
                    runningBalances = runningBalances,
                    currentPrices = currentPrices,
                    ohlcData = ohlcData,
                    tradePrices = emptyMap(),
                )

            val tradeSnapshot = snapshots.first { it.timestamp == tradeTime }
            tradeSnapshot.assets["BTC"]!!.price.shouldBeEqualComparingTo(equidistantPriceA)
        }

        "calculateHistoricalSnapshots should clamp negative balances and handle missing USD key" {
            val now = Instant.now()
            val cutoff = now.minus(5, ChronoUnit.DAYS)

            val events =
                SnapshotHistoryCalculator.buildTimelineEvents(
                    historicalTrades = emptyList(),
                    cutoffTime = cutoff,
                    now = now,
                )

            val allocations =
                listOf(
                    Allocation(Asset(Asset.BTC), 50.0),
                    Allocation(Asset.USD, 50.0),
                )

            val runningBalances = mutableMapOf("BTC" to BigDecimal("-0.1"))
            val currentPrices = mapOf("BTC" to BigDecimal("50000.00"), "USD" to BigDecimal.ONE)

            val snapshots =
                SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                    events = events,
                    allocations = allocations,
                    runningBalances = runningBalances,
                    currentPrices = currentPrices,
                    ohlcData = emptyMap(),
                    tradePrices = emptyMap(),
                )

            snapshots.shouldNotBeEmpty()
            snapshots.forEach { snapshot ->
                snapshot.assets["BTC"]!!.balance.shouldBeEqualComparingTo(BigDecimal.ZERO)
                snapshot.assets["USD"]!!.balance.shouldBeEqualComparingTo(BigDecimal.ZERO)
            }
        }

        "calculateHistoricalSnapshots rejects unknown trade side without mutating balances" {
            val now = Instant.now()
            val cutoff = now.minus(5, ChronoUnit.DAYS)
            val trade = TradeRecord(
                timestamp = now.minus(2, ChronoUnit.DAYS),
                pair = "XBTUSD",
                side = "UNKNOWN_SIDE",
                symbol = "BTC",
                volume = BigDecimal("0.1"),
                usdAmount = BigDecimal("5000.00"),
                fee = BigDecimal("13.00"),
                success = true,
                dryRun = false,
            )

            val events = SnapshotHistoryCalculator.buildTimelineEvents(
                historicalTrades = listOf(trade),
                cutoffTime = cutoff,
                now = now,
            )

            val allocations = listOf(
                Allocation(Asset(Asset.BTC), 50.0),
                Allocation(Asset.USD, 50.0),
            )

            val runningBalances = mutableMapOf(
                "BTC" to BigDecimal("0.5"),
                "USD" to BigDecimal("10000.00"),
            )

            val currentPrices = mapOf("BTC" to BigDecimal("50000.00"), "USD" to BigDecimal.ONE)

            shouldThrow<IllegalArgumentException> {
                SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                    events = events,
                    allocations = allocations,
                    runningBalances = runningBalances,
                    currentPrices = currentPrices,
                    ohlcData = emptyMap(),
                    tradePrices = emptyMap(),
                )
            }

            runningBalances["BTC"]!!.shouldBeEqualComparingTo(BigDecimal("0.5"))
            runningBalances["USD"]!!.shouldBeEqualComparingTo(BigDecimal("10000.00"))
        }
    }
}
