package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.FlowCategory
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.LedgerFlowClassifier
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.service.impl.history.AuthoritativeLedgerBalanceValidator
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

    private val defaultSettings =
        Settings(
            loopDelaySeconds = 60,
            deviationTriggerPercent = 5.0,
            minimumOrderSizeUSD = 5.0,
            dryRun = true,
            fiatMaxDrawdown = 50.0,
            fiatDeploymentExponent = 1.0,
        )

    private fun historicalBalanceBeforeTransfer(
        asset: String,
        anchorBalance: String,
        legs: List<LedgerEvent>,
        resolvedScopes: Map<String, AuthoritativeLedgerBalanceValidator.LedgerWalletScope>,
    ): BigDecimal {
        val transferTime = legs.first().time
        val preTransferPoint = transferTime.minusSeconds(1)
        // The spot-facing leg is the only spot-affecting row, so its authoritative post-entry
        // balance is the anchor the helper walks back from.
        val scopedLegs = legs.map { leg ->
            if (resolvedScopes[leg.ledgerId] == AuthoritativeLedgerBalanceValidator.LedgerWalletScope.SPOT) {
                leg.copy(balance = BigDecimal(anchorBalance))
            } else {
                leg
            }
        }
        val runningBalances = mutableMapOf(asset to BigDecimal(anchorBalance)).apply {
            if (asset != Asset.USD) this[Asset.USD] = BigDecimal("1000.00")
        }
        val allocations = if (asset == Asset.USD) {
            listOf(Allocation(Asset.USD, 100.0))
        } else {
            listOf(Allocation(Asset(asset), 50.0), Allocation(Asset.USD, 50.0))
        }
        val currentPrices = if (asset == Asset.USD) {
            mapOf(Asset.USD to BigDecimal.ONE)
        } else {
            mapOf(asset to BigDecimal.ONE, Asset.USD to BigDecimal.ONE)
        }
        val snapshots = SnapshotHistoryCalculator.calculateHistoricalSnapshots(
            events = (
                scopedLegs.map { SnapshotHistoryCalculator.TimelineEvent.RewardEvent(it.time, it) } +
                    SnapshotHistoryCalculator.TimelineEvent.DailyCloseEvent(preTransferPoint)
                ).sorted(),
            allocations = allocations,
            runningBalances = runningBalances,
            currentPrices = currentPrices,
            ohlcData = emptyMap(),
            tradePrices = emptyMap(),
            settings = defaultSettings,
            resolvedScopes = resolvedScopes,
        )
        return snapshots.first { it.timestamp == preTransferPoint }.assets.getValue(asset).balance
    }

    private fun transferLeg(
        ledgerId: String,
        time: Instant,
        asset: String,
        amount: String,
        subtype: String,
        refid: String,
    ) = LedgerEvent(
        ledgerId = ledgerId,
        time = time,
        type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
        subtype = subtype,
        refid = refid,
        asset = asset,
        amount = BigDecimal(amount),
        hasAuthoritativeBalance = true,
        hasAuthoritativeFee = true,
    )

    init {
        "buildTimelineEvents should generate trade and daily close events sorted descending" {
            val now = Instant.now()
            val cutoff = now.minus(5, ChronoUnit.DAYS)
            val trade = TestFixtures.tradeRecord(
                timestamp = now.minus(2, ChronoUnit.DAYS),
                pair = "XBTUSD",
                side = OrderSide.BUY.uppercaseName,
                symbol = "BTC",
                volume = BigDecimal("0.1"),
                usdAmount = BigDecimal("5000.00"),
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
            val trade = TestFixtures.tradeRecord(
                timestamp = now.minus(2, ChronoUnit.DAYS),
                pair = "XBTUSD",
                side = OrderSide.BUY.uppercaseName,
                symbol = "BTC",
                volume = BigDecimal("0.1"),
                usdAmount = BigDecimal("5000.00"),
                fee = BigDecimal("13.00"),
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
                settings = defaultSettings,
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
                TestFixtures.tradeRecord(
                    timestamp = now.minus(2, ChronoUnit.DAYS),
                    pair = "XBTUSD",
                    side = OrderSide.BUY.apiValue,
                    symbol = "BTC",
                    volume = BigDecimal("0.1"),
                    usdAmount = BigDecimal("5000.00"),
                    fee = BigDecimal("13.00"),
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
                settings = defaultSettings,
            )

            runningBalances["BTC"]!!.shouldBeEqualComparingTo(BigDecimal("0.4"))
            runningBalances["USD"]!!.shouldBeEqualComparingTo(BigDecimal("15013.00"))
        }

        "calculateHistoricalSnapshots should reverse-apply SELL trades to running balances" {
            val now = Instant.now()
            val cutoff = now.minus(5, ChronoUnit.DAYS)
            val trade =
                TestFixtures.tradeRecord(
                    timestamp = now.minus(2, ChronoUnit.DAYS),
                    pair = "XBTUSD",
                    side = OrderSide.SELL.uppercaseName,
                    symbol = "BTC",
                    volume = BigDecimal("0.1"),
                    usdAmount = BigDecimal("5000.00"),
                    fee = BigDecimal("13.00"),
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
                settings = defaultSettings,
            )

            // After reverse-applying the SELL: BTC += 0.1, USD -= 5000 + fee returned
            runningBalances["BTC"]!!.shouldBeEqualComparingTo(BigDecimal("0.6"))
            runningBalances["USD"]!!.shouldBeEqualComparingTo(BigDecimal("5013.00"))
        }

        "calculateHistoricalSnapshots should reverse-apply both conversion legs and each fee once" {
            val now = Instant.parse("2026-07-10T12:00:00Z")
            val conversionTime = now.minus(2, ChronoUnit.DAYS)
            val conversion = listOf(
                LedgerEvent(
                    ledgerId = "conversion-source",
                    refid = "CONVERSION-SNAPSHOT",
                    time = conversionTime,
                    type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                    asset = "BTC",
                    amount = BigDecimal("-1.00"),
                    fee = BigDecimal("0.01"),
                    balance = BigDecimal("0.50"),
                    hasAuthoritativeBalance = true,
                    hasAuthoritativeFee = true,
                ),
                LedgerEvent(
                    ledgerId = "conversion-destination",
                    refid = "CONVERSION-SNAPSHOT",
                    time = conversionTime,
                    type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                    asset = "ETH",
                    amount = BigDecimal("2.00"),
                    fee = BigDecimal("0.02"),
                    balance = BigDecimal("1.98"),
                    hasAuthoritativeBalance = true,
                    hasAuthoritativeFee = true,
                ),
            )
            val events = SnapshotHistoryCalculator.buildTimelineEvents(
                historicalTrades = emptyList(),
                historicalRewards = conversion,
                cutoffTime = now.minus(5, ChronoUnit.DAYS),
                now = now,
            )
            val runningBalances = mutableMapOf(
                "BTC" to BigDecimal("0.50"),
                "ETH" to BigDecimal("1.98"),
                "USD" to BigDecimal("1000.00"),
            )

            SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = events,
                allocations = listOf(
                    Allocation(Asset.BTC, 40.0),
                    Allocation(Asset.ETH, 40.0),
                    Allocation(Asset.USD, 20.0),
                ),
                runningBalances = runningBalances,
                currentPrices = mapOf(
                    "BTC" to BigDecimal("50000.00"),
                    "ETH" to BigDecimal("3000.00"),
                    "USD" to BigDecimal.ONE,
                ),
                ohlcData = emptyMap(),
                tradePrices = emptyMap(),
                settings = defaultSettings,
            )

            // Reverse replay returns the pre-conversion balances: BTC +1.01 and ETH -1.98.
            runningBalances["BTC"]!!.shouldBeEqualComparingTo(BigDecimal("1.51"))
            runningBalances["ETH"]!!.shouldBeEqualComparingTo(BigDecimal.ZERO)
            runningBalances["USD"]!!.shouldBeEqualComparingTo(BigDecimal("1000.00"))
        }

        "buildTimelineEvents should reject an incomplete conversion before raw reverse replay" {
            val now = Instant.parse("2026-07-10T12:00:00Z")
            val conversionTime = now.minus(2, ChronoUnit.DAYS)
            shouldThrow<IllegalArgumentException> {
                SnapshotHistoryCalculator.buildTimelineEvents(
                    historicalTrades = emptyList(),
                    historicalRewards = listOf(
                        LedgerEvent(
                            ledgerId = "conversion-only",
                            refid = "CONVERSION-INCOMPLETE-SNAPSHOT",
                            time = conversionTime,
                            type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                            asset = "BTC",
                            amount = BigDecimal("-1.00"),
                            fee = BigDecimal("0.01"),
                            hasAuthoritativeBalance = true,
                            hasAuthoritativeFee = true,
                        ),
                    ),
                    cutoffTime = now.minus(5, ChronoUnit.DAYS),
                    now = now,
                )
            }
        }

        "calculateHistoricalSnapshots should use OHLC closest price over currentPrices" {
            val now = Instant.now()
            val cutoff = now.minus(5, ChronoUnit.DAYS)
            val tradeTime = now.minus(2, ChronoUnit.DAYS)
            val trade =
                TestFixtures.tradeRecord(
                    timestamp = tradeTime,
                    pair = "XBTUSD",
                    side = OrderSide.BUY.uppercaseName,
                    symbol = "BTC",
                    volume = BigDecimal("0.1"),
                    usdAmount = BigDecimal("5000.00"),
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
                    settings = defaultSettings,
                )

            val tradeSnapshot = snapshots.first { it.timestamp == tradeTime }
            tradeSnapshot.assets["BTC"]!!.price.shouldBeEqualComparingTo(ohlcPrice)
        }

        "calculateHistoricalSnapshots should sum raw asset values before rounding total" {
            val now = Instant.now()
            val events = SnapshotHistoryCalculator.buildTimelineEvents(
                historicalTrades = emptyList(),
                cutoffTime = now.minus(5, ChronoUnit.DAYS),
                now = now,
            )
            val allocations = listOf(
                Allocation(Asset.BTC, 33.3),
                Allocation(Asset.ETH, 33.3),
                Allocation(Asset.USD, 33.4),
            )
            val balances = mutableMapOf(
                "BTC" to BigDecimal("1.0"),
                "ETH" to BigDecimal("1.0"),
                "USD" to BigDecimal("1.005"),
            )
            val prices = mapOf(
                "BTC" to BigDecimal("1.005"),
                "ETH" to BigDecimal("1.005"),
                "USD" to BigDecimal.ONE,
            )

            val snapshot = SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = events,
                allocations = allocations,
                runningBalances = balances,
                currentPrices = prices,
                ohlcData = emptyMap(),
                tradePrices = emptyMap(),
                settings = defaultSettings,
            ).first()

            snapshot.totalValueUSD.shouldBeEqualComparingTo(BigDecimal("3.02"))
            snapshot.assets.values.forEach { it.valueUSD.shouldBeEqualComparingTo(BigDecimal("1.01")) }
        }

        "calculateHistoricalSnapshots should pick first OHLC point when equidistant (strict less-than)" {
            val now = Instant.now()
            val cutoff = now.minus(5, ChronoUnit.DAYS)
            val tradeTime = now.minus(2, ChronoUnit.DAYS)
            val trade =
                TestFixtures.tradeRecord(
                    timestamp = tradeTime,
                    pair = "XBTUSD",
                    side = OrderSide.BUY.uppercaseName,
                    symbol = "BTC",
                    volume = BigDecimal("0.1"),
                    usdAmount = BigDecimal("5000.00"),
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
                    settings = defaultSettings,
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
                    settings = defaultSettings,
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
            val trade = TestFixtures.tradeRecord(
                timestamp = now.minus(2, ChronoUnit.DAYS),
                pair = "XBTUSD",
                side = "UNKNOWN_SIDE",
                symbol = "BTC",
                volume = BigDecimal("0.1"),
                usdAmount = BigDecimal("5000.00"),
                fee = BigDecimal("13.00"),
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
                    settings = defaultSettings,
                )
            }

            runningBalances["BTC"]!!.shouldBeEqualComparingTo(BigDecimal("0.5"))
            runningBalances["USD"]!!.shouldBeEqualComparingTo(BigDecimal("10000.00"))
        }

        "buildTimelineEvents emits staking and dividend rewards as RewardEvents" {
            val now = Instant.now()
            val cutoff = now.minus(5, ChronoUnit.DAYS)
            val stakingReward =
                LedgerEvent(
                    ledgerId = "ledger-stake",
                    time = now.minus(2, ChronoUnit.DAYS),
                    type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                    asset = "XBT",
                    amount = BigDecimal("0.1"),
                )
            val dividend =
                LedgerEvent(
                    ledgerId = "ledger-div",
                    time = now.minus(1, ChronoUnit.DAYS),
                    type = KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
                    asset = "BTC",
                    amount = BigDecimal("1.25"),
                )

            val events =
                SnapshotHistoryCalculator.buildTimelineEvents(
                    historicalTrades = emptyList(),
                    historicalRewards = listOf(stakingReward, dividend),
                    cutoffTime = cutoff,
                    now = now,
                )

            val rewardEvents = events.filterIsInstance<SnapshotHistoryCalculator.TimelineEvent.RewardEvent>()
            rewardEvents.map { it.event.ledgerId }.toSet() shouldBe setOf("ledger-stake", "ledger-div")
        }

        "calculateHistoricalSnapshots reverse-applies staking rewards to running balances" {
            val now = Instant.now()
            val cutoff = now.minus(5, ChronoUnit.DAYS)
            val rewardTime = now.minus(2, ChronoUnit.DAYS)
            val reward =
                LedgerEvent(
                    ledgerId = "ledger-stake",
                    time = rewardTime,
                    type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                    asset = "BTC",
                    amount = BigDecimal("0.1"),
                )

            val events =
                SnapshotHistoryCalculator.buildTimelineEvents(
                    historicalTrades = emptyList(),
                    historicalRewards = listOf(reward),
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

            val snapshots =
                SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                    events = events,
                    allocations = allocations,
                    runningBalances = runningBalances,
                    currentPrices = currentPrices,
                    ohlcData = emptyMap(),
                    tradePrices = emptyMap(),
                    settings = defaultSettings,
                )

            snapshots.shouldNotBeEmpty()
            snapshots.first { it.timestamp == rewardTime }.assets["BTC"]!!.balance
                .shouldBeEqualComparingTo(BigDecimal("0.5"))
            // After reverse-applying the reward: BTC -= 0.1
            runningBalances["BTC"]!!.shouldBeEqualComparingTo(BigDecimal("0.4"))
            runningBalances["USD"]!!.shouldBeEqualComparingTo(BigDecimal("10000.00"))
        }

        "calculateHistoricalSnapshots reverse-applies promotion rewards net of fee" {
            val now = Instant.now()
            val cutoff = now.minus(5, ChronoUnit.DAYS)
            val rewardTime = now.minus(2, ChronoUnit.DAYS)
            val reward =
                LedgerEvent(
                    ledgerId = "ledger-promotion",
                    time = rewardTime,
                    type = KrakenApiConstants.LEDGER_TYPE_REWARD,
                    asset = "BTC",
                    amount = BigDecimal("0.1"),
                    fee = BigDecimal("0.01"),
                )

            val events = SnapshotHistoryCalculator.buildTimelineEvents(
                historicalTrades = emptyList(),
                historicalRewards = listOf(reward),
                cutoffTime = cutoff,
                now = now,
            )
            val runningBalances = mutableMapOf(
                "BTC" to BigDecimal("0.5"),
                "USD" to BigDecimal("10000.00"),
            )

            SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = events,
                allocations = listOf(
                    Allocation(Asset(Asset.BTC), 50.0),
                    Allocation(Asset.USD, 50.0),
                ),
                runningBalances = runningBalances,
                currentPrices = mapOf("BTC" to BigDecimal("50000.00"), "USD" to BigDecimal.ONE),
                ohlcData = emptyMap(),
                tradePrices = emptyMap(),
                settings = defaultSettings,
            ).shouldNotBeEmpty()

            runningBalances["BTC"]!!.shouldBeEqualComparingTo(BigDecimal("0.41"))
            runningBalances["USD"]!!.shouldBeEqualComparingTo(BigDecimal("10000.00"))
        }

        "calculateHistoricalSnapshots floors OHLC price to prior DailyCloseEvent (CQ-18-4)" {
            val now = Instant.parse("2026-08-07T12:00:00Z")
            val cutoff = now.minus(5, ChronoUnit.DAYS)
            val day1Close =
                now.minus(1, ChronoUnit.DAYS)
                    .truncatedTo(ChronoUnit.DAYS)
                    .plus(23, ChronoUnit.HOURS)
                    .plus(59, ChronoUnit.MINUTES)
                    .plus(59, ChronoUnit.SECONDS)
            val day2Close =
                now.minus(2, ChronoUnit.DAYS)
                    .truncatedTo(ChronoUnit.DAYS)
                    .plus(23, ChronoUnit.HOURS)
                    .plus(59, ChronoUnit.MINUTES)
                    .plus(59, ChronoUnit.SECONDS)

            val events =
                listOf(
                    SnapshotHistoryCalculator.TimelineEvent.DailyCloseEvent(day1Close),
                    SnapshotHistoryCalculator.TimelineEvent.DailyCloseEvent(day2Close),
                ).sorted()

            val day1Candle = day1Close.truncatedTo(ChronoUnit.DAYS).epochSecond
            val day2Candle = day2Close.truncatedTo(ChronoUnit.DAYS).epochSecond
            val day0Candle =
                day1Close.plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS).epochSecond

            val ohlcData =
                mapOf(
                    "BTC" to
                        listOf(
                            day2Candle to BigDecimal("20000.00"),
                            day1Candle to BigDecimal("21000.00"),
                            day0Candle to BigDecimal("22000.00"),
                        ),
                )

            val snapshots =
                SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                    events = events,
                    allocations = listOf(Allocation(Asset(Asset.BTC), 100.0)),
                    runningBalances = mutableMapOf("BTC" to BigDecimal.ONE, "USD" to BigDecimal.ZERO),
                    currentPrices = mapOf("BTC" to BigDecimal("20000.00"), "USD" to BigDecimal.ONE),
                    ohlcData = ohlcData,
                    tradePrices = emptyMap(),
                    settings = defaultSettings,
                )

            val day1Snapshot = snapshots.first { it.timestamp == day1Close }
            day1Snapshot.assets["BTC"]!!.price.shouldBeEqualComparingTo(BigDecimal("21000.00"))
        }

        "calculateHistoricalSnapshots reverse-applies un-normalized reward assets to base asset running balances" {
            val now = Instant.now()
            val cutoff = now.minus(5, ChronoUnit.DAYS)
            val rewardTime = now.minus(2, ChronoUnit.DAYS)
            val reward =
                LedgerEvent(
                    ledgerId = "ledger-stake-unnorm",
                    time = rewardTime,
                    type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                    asset = "DOT.S", // Earn-staking suffix un-normalized asset
                    amount = BigDecimal("0.5"),
                )

            val events =
                SnapshotHistoryCalculator.buildTimelineEvents(
                    historicalTrades = emptyList(),
                    historicalRewards = listOf(reward),
                    cutoffTime = cutoff,
                    now = now,
                )

            val allocations =
                listOf(
                    Allocation(Asset("DOT"), 50.0),
                    Allocation(Asset.USD, 50.0),
                )

            val runningBalances =
                mutableMapOf(
                    "DOT" to BigDecimal("2.0"),
                    "USD" to BigDecimal("10000.00"),
                )

            val currentPrices = mapOf("DOT" to BigDecimal("10.00"), "USD" to BigDecimal.ONE)

            SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = events,
                allocations = allocations,
                runningBalances = runningBalances,
                currentPrices = currentPrices,
                ohlcData = emptyMap(),
                tradePrices = emptyMap(),
                settings = defaultSettings,
            )

            // After reverse-applying DOT.S reward: runningBalances["DOT"] -= 0.5 -> 1.5
            runningBalances["DOT"]!!.shouldBeEqualComparingTo(BigDecimal("1.5"))
        }

        "calculateHistoricalSnapshots computes effective USD targets forward without peak leakage" {
            val now = Instant.now()
            val cutoff = now.minus(5, ChronoUnit.DAYS)
            val tOldest = now.minus(4, ChronoUnit.DAYS)
            val tPeak = now.minus(3, ChronoUnit.DAYS)
            val tDrawdown = now.minus(2, ChronoUnit.DAYS)

            val events = listOf(
                SnapshotHistoryCalculator.TimelineEvent.DailyCloseEvent(tOldest),
                SnapshotHistoryCalculator.TimelineEvent.DailyCloseEvent(tPeak),
                SnapshotHistoryCalculator.TimelineEvent.DailyCloseEvent(tDrawdown),
            ).sorted() // sorted newest first

            val allocations = listOf(
                Allocation(Asset(Asset.BTC), 50.0),
                Allocation(Asset.USD, 50.0),
            )

            // Price sequence over time: tOldest ($20k) -> tPeak ($40k) -> tDrawdown ($20k)
            val ohlcData = mapOf(
                "BTC" to listOf(
                    tOldest.epochSecond to BigDecimal("20000.00"),
                    tPeak.epochSecond to BigDecimal("40000.00"),
                    tDrawdown.epochSecond to BigDecimal("20000.00"),
                ),
            )

            val runningBalances = mutableMapOf(
                "BTC" to BigDecimal("1.0"),
                "USD" to BigDecimal("10000.00"),
            )

            val settings = defaultSettings

            val snapshots = SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = events,
                allocations = allocations,
                runningBalances = runningBalances,
                currentPrices = mapOf("BTC" to BigDecimal("20000.00"), "USD" to BigDecimal.ONE),
                ohlcData = ohlcData,
                tradePrices = emptyMap(),
                settings = settings,
                currentAth = BigDecimal("30000.00"), // Starting ATH before tOldest
            )

            snapshots.size shouldBe 3
            // Snapshots returned newest first: tDrawdown, tPeak, tOldest
            val sDrawdown = snapshots.first { it.timestamp == tDrawdown }
            val sPeak = snapshots.first { it.timestamp == tPeak }
            val sOldest = snapshots.first { it.timestamp == tOldest }

            // sOldest ($30k portfolio = 1 BTC * $20k + $10k USD): ATH was $30k => drawdown = 0% => USD target = 50%
            sOldest.drawdownPercent.shouldBeEqualComparingTo(BigDecimal.ZERO)
            sOldest.effectiveUsdTargetPercent.shouldBeEqualComparingTo(BigDecimal("50.00"))

            // sPeak ($50k portfolio = 1 BTC * $40k + $10k USD): new ATH $50k => drawdown = 0% => USD target = 50%
            sPeak.drawdownPercent.shouldBeEqualComparingTo(BigDecimal.ZERO)
            sPeak.effectiveUsdTargetPercent.shouldBeEqualComparingTo(BigDecimal("50.00"))

            // sDrawdown ($30k portfolio): ATH was $50k at tPeak => drawdown = (50k-30k)/50k = 40%
            // maxDD = 50% => fiat deployment = 40/50 * 100 = 80%
            // effective USD target = 50% * (1 - 0.8) = 10%
            sDrawdown.drawdownPercent.shouldBeEqualComparingTo(BigDecimal("40.00"))
            sDrawdown.fiatDeploymentPercent.shouldBeEqualComparingTo(BigDecimal("80.00"))
            sDrawdown.effectiveUsdTargetPercent.shouldBeEqualComparingTo(BigDecimal("10.00"))
        }

        "calculateHistoricalSnapshots reverse-applies USD cash dividends to USD running balance" {
            val now = Instant.now()
            val cutoff = now.minus(5, ChronoUnit.DAYS)
            val dividendTime = now.minus(2, ChronoUnit.DAYS)
            val dividend = LedgerEvent(
                ledgerId = "ledger-cash-div",
                time = dividendTime,
                type = KrakenApiConstants.LEDGER_TYPE_DIVIDEND,
                asset = "USD",
                amount = BigDecimal("25.00"),
                fee = BigDecimal("0.10"),
            )

            val events = SnapshotHistoryCalculator.buildTimelineEvents(
                historicalTrades = emptyList(),
                historicalRewards = listOf(dividend),
                cutoffTime = cutoff,
                now = now,
            )

            val allocations = listOf(
                Allocation(Asset(Asset.BTC), 50.0),
                Allocation(Asset.USD, 50.0),
            )

            val runningBalances = mutableMapOf(
                "BTC" to BigDecimal("1.0"),
                "USD" to BigDecimal("50024.90"),
            )

            val currentPrices = mapOf("BTC" to BigDecimal("50000.00"), "USD" to BigDecimal.ONE)

            SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = events,
                allocations = allocations,
                runningBalances = runningBalances,
                currentPrices = currentPrices,
                ohlcData = emptyMap(),
                tradePrices = emptyMap(),
                settings = defaultSettings,
            )

            // After reverse-applying net USD cash dividend (+24.90): USD -= 24.90 => 50000.00
            runningBalances["USD"]!!.shouldBeEqualComparingTo(BigDecimal("50000.00"))
            runningBalances["BTC"]!!.shouldBeEqualComparingTo(BigDecimal("1.0"))
        }

        "calculateHistoricalSnapshots reverse-applies deposits and withdrawals with fees" {
            val now = Instant.now()
            val cutoff = now.minus(5, ChronoUnit.DAYS)
            val deposit = LedgerEvent(
                ledgerId = "ledger-dep",
                time = now.minus(3, ChronoUnit.DAYS),
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "USD",
                amount = BigDecimal("10000.00"),
                fee = BigDecimal.ZERO,
            )
            val withdrawal = LedgerEvent(
                ledgerId = "ledger-wdr",
                time = now.minus(2, ChronoUnit.DAYS),
                type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                asset = "BTC",
                amount = BigDecimal("-0.2"),
                fee = BigDecimal("0.0005"),
            )

            val events = SnapshotHistoryCalculator.buildTimelineEvents(
                historicalTrades = emptyList(),
                historicalRewards = listOf(deposit, withdrawal),
                cutoffTime = cutoff,
                now = now,
            )

            val allocations = listOf(
                Allocation(Asset(Asset.BTC), 50.0),
                Allocation(Asset.USD, 50.0),
            )

            val runningBalances = mutableMapOf(
                "BTC" to BigDecimal("0.8"),
                "USD" to BigDecimal("20000.00"),
            )

            val currentPrices = mapOf("BTC" to BigDecimal("50000.00"), "USD" to BigDecimal.ONE)

            SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = events,
                allocations = allocations,
                runningBalances = runningBalances,
                currentPrices = currentPrices,
                ohlcData = emptyMap(),
                tradePrices = emptyMap(),
                settings = defaultSettings,
            )

            // Withdrawal net delta is -0.2 - 0.0005 = -0.2005. Going backward: 0.8 - (-0.2005) = 1.0005
            runningBalances["BTC"]!!.shouldBeEqualComparingTo(BigDecimal("1.0005"))
            // Deposit net delta is +10000.00. Going backward: 20000 - 10000 = 10000.00
            runningBalances["USD"]!!.shouldBeEqualComparingTo(BigDecimal("10000.00"))
        }

        "Reconstruction symmetry: reverses through consumer spend and receive transactions" {
            val now = Instant.now()
            val cutoff = now.minus(5, ChronoUnit.DAYS)
            val spendEvent = LedgerEvent(
                ledgerId = "ledger-spend",
                time = now.minus(3, ChronoUnit.DAYS),
                type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                asset = "USD",
                amount = BigDecimal("-5000.00"),
                fee = BigDecimal("10.00"),
            )
            val receiveEvent = LedgerEvent(
                ledgerId = "ledger-receive",
                time = now.minus(3, ChronoUnit.DAYS),
                type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                asset = "BTC",
                amount = BigDecimal("0.10"),
                fee = BigDecimal("0.001"),
            )

            val events = SnapshotHistoryCalculator.buildTimelineEvents(
                historicalTrades = emptyList(),
                historicalRewards = listOf(spendEvent, receiveEvent),
                cutoffTime = cutoff,
                now = now,
            )

            val allocations = listOf(
                Allocation(Asset(Asset.BTC), 50.0),
                Allocation(Asset.USD, 50.0),
            )

            val runningBalances = mutableMapOf(
                "BTC" to BigDecimal("0.10"),
                "USD" to BigDecimal("5000.00"),
            )

            val currentPrices = mapOf("BTC" to BigDecimal("50000.00"), "USD" to BigDecimal.ONE)

            SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = events,
                allocations = allocations,
                runningBalances = runningBalances,
                currentPrices = currentPrices,
                ohlcData = emptyMap(),
                tradePrices = emptyMap(),
                settings = defaultSettings,
            )

            // BTC netDelta is +0.099. Reverse: 0.10 - 0.099 = 0.001
            runningBalances["BTC"]!!.shouldBeEqualComparingTo(BigDecimal("0.001"))
            // USD netDelta is -5010.00. Reverse: 5000.00 - (-5010.00) = 10010.00
            runningBalances["USD"]!!.shouldBeEqualComparingTo(BigDecimal("10010.00"))

            // Forward symmetry check
            val forwardBtc = runningBalances["BTC"]!!.add(receiveEvent.netBalanceDelta())
            val forwardUsd = runningBalances["USD"]!!.add(spendEvent.netBalanceDelta())
            forwardBtc.shouldBeEqualComparingTo(BigDecimal("0.10"))
            forwardUsd.shouldBeEqualComparingTo(BigDecimal("5000.00"))
        }

        "buildTimelineEvents ignores reconstructionStart when at or after cutoffTime" {
            val now = Instant.now()
            val cutoff = now.minus(5, ChronoUnit.DAYS)
            val events = SnapshotHistoryCalculator.buildTimelineEvents(
                historicalTrades = emptyList(),
                cutoffTime = cutoff,
                now = now,
                reconstructionStart = cutoff.plusSeconds(10),
            )
            events.none { it.timestamp == cutoff.plusSeconds(10) } shouldBe true
        }

        "calculateHistoricalSnapshots handles sell trade and reverse applies correctly" {
            val now = Instant.now()
            val cutoff = now.minus(5, ChronoUnit.DAYS)
            val trade = TestFixtures.tradeRecord(
                timestamp = now.minus(2, ChronoUnit.DAYS),
                pair = "XBTUSD",
                side = OrderSide.SELL.uppercaseName,
                symbol = "BTC",
                volume = BigDecimal("0.1"),
                usdAmount = BigDecimal("5000.00"),
                fee = BigDecimal("10.00"),
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

            SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = events,
                allocations = allocations,
                runningBalances = runningBalances,
                currentPrices = currentPrices,
                ohlcData = emptyMap(),
                tradePrices = emptyMap(),
                settings = defaultSettings,
            )
            runningBalances["BTC"]!!.shouldBeEqualComparingTo(BigDecimal("0.6"))
            runningBalances["USD"]!!.shouldBeEqualComparingTo(BigDecimal("5010.00"))
        }

        "calculateHistoricalSnapshots throws on unsupported trade side" {
            val now = Instant.now()
            val cutoff = now.minus(5, ChronoUnit.DAYS)
            val trade = TestFixtures.tradeRecord(
                timestamp = now.minus(2, ChronoUnit.DAYS),
                pair = "XBTUSD",
                side = "UNSUPPORTED",
                symbol = "BTC",
                volume = BigDecimal("0.1"),
                usdAmount = BigDecimal("5000.00"),
            )
            val events = listOf(SnapshotHistoryCalculator.TimelineEvent.TradeEvent(trade.timestamp, trade))
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
                    settings = defaultSettings,
                )
            }
        }

        "calculateHistoricalSnapshots respects resolved wallet scope and unallocated asset" {
            val now = Instant.now()
            val cutoff = now.minus(5, ChronoUnit.DAYS)
            val spotReward = LedgerEvent(
                ledgerId = "spot-reward",
                time = now.minus(3, ChronoUnit.DAYS),
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                asset = "BTC",
                amount = BigDecimal("0.02"),
                fee = BigDecimal.ZERO,
                balance = BigDecimal("0.50"),
                hasAuthoritativeBalance = true,
            )
            val earnMarker = LedgerEvent(
                ledgerId = "earn-marker",
                time = now.minus(2, ChronoUnit.DAYS),
                type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                subtype = "spottostaking",
                asset = "BTC",
                amount = BigDecimal("0.05"),
                fee = BigDecimal.ZERO,
                balance = BigDecimal("0.57"),
                hasAuthoritativeBalance = true,
            )
            val unallocated = LedgerEvent(
                ledgerId = "eth-reward",
                time = now.minus(1, ChronoUnit.DAYS),
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                asset = "ETH",
                amount = BigDecimal("1.0"),
                fee = BigDecimal.ZERO,
                balance = BigDecimal("1.0"),
                hasAuthoritativeBalance = true,
            )
            val events = SnapshotHistoryCalculator.buildTimelineEvents(
                historicalTrades = emptyList(),
                historicalRewards = listOf(spotReward, earnMarker, unallocated),
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

            SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = events,
                allocations = allocations,
                runningBalances = runningBalances,
                currentPrices = currentPrices,
                ohlcData = emptyMap(),
                tradePrices = emptyMap(),
                settings = defaultSettings,
                resolvedScopes = mapOf(
                    "spot-reward" to AuthoritativeLedgerBalanceValidator.LedgerWalletScope.SPOT,
                    "earn-marker" to AuthoritativeLedgerBalanceValidator.LedgerWalletScope.STAKING,
                ),
            )
            runningBalances["BTC"]!!.shouldBeEqualComparingTo(BigDecimal("0.48"))
        }

        "historical replay restores the Spot leg of a Spot-to-staking transfer" {
            val time = Instant.parse("2026-07-10T12:00:00Z")
            val legs = listOf(
                transferLeg("spot-debit", time, Asset.SOL, "-4", "spottostaking", "staking-1"),
                transferLeg("staking-credit", time, Asset.SOL, "4", "spottostaking", "staking-1"),
            )

            historicalBalanceBeforeTransfer(
                asset = Asset.SOL,
                anchorBalance = "6",
                legs = legs,
                resolvedScopes = mapOf(
                    "spot-debit" to AuthoritativeLedgerBalanceValidator.LedgerWalletScope.SPOT,
                    "staking-credit" to AuthoritativeLedgerBalanceValidator.LedgerWalletScope.STAKING,
                ),
            ).shouldBeEqualComparingTo(BigDecimal("10"))
            LedgerFlowClassifier.classifyAll(legs).values.toSet() shouldBe setOf(FlowCategory.INTERNAL_MOVE)
            legs.all { !LedgerEvent.isRewardEvent(it) } shouldBe true
        }

        "historical replay restores the Spot leg of a staking-to-Spot transfer" {
            val time = Instant.parse("2026-07-10T12:00:00Z")
            val legs = listOf(
                transferLeg("staking-debit", time, Asset.SOL, "-4", "stakingtospot", "staking-2"),
                transferLeg("spot-credit", time, Asset.SOL, "4", "stakingtospot", "staking-2"),
            )

            historicalBalanceBeforeTransfer(
                asset = Asset.SOL,
                anchorBalance = "10",
                legs = legs,
                resolvedScopes = mapOf(
                    "staking-debit" to AuthoritativeLedgerBalanceValidator.LedgerWalletScope.STAKING,
                    "spot-credit" to AuthoritativeLedgerBalanceValidator.LedgerWalletScope.SPOT,
                ),
            ).shouldBeEqualComparingTo(BigDecimal("6"))
            LedgerFlowClassifier.classifyAll(legs).values.toSet() shouldBe setOf(FlowCategory.INTERNAL_MOVE)
            legs.all { !LedgerEvent.isRewardEvent(it) } shouldBe true
        }

        "historical replay restores the Spot leg of a Spot-to-Futures transfer" {
            val time = Instant.parse("2026-07-10T12:00:00Z")
            val legs = listOf(
                transferLeg("spot-debit", time, Asset.USD, "-100", "spottofutures", "futures-1"),
                transferLeg("futures-credit", time, Asset.USD, "100", "spottofutures", "futures-1"),
            )

            historicalBalanceBeforeTransfer(
                asset = Asset.USD,
                anchorBalance = "1000",
                legs = legs,
                resolvedScopes = mapOf(
                    "spot-debit" to AuthoritativeLedgerBalanceValidator.LedgerWalletScope.SPOT,
                    "futures-credit" to AuthoritativeLedgerBalanceValidator.LedgerWalletScope.FUTURES,
                ),
            ).shouldBeEqualComparingTo(BigDecimal("1100"))
            LedgerFlowClassifier.classifyAll(legs).values.toSet() shouldBe setOf(FlowCategory.INTERNAL_MOVE)
            legs.all { !LedgerEvent.isRewardEvent(it) } shouldBe true
        }

        "historical replay restores the Spot leg of a Futures-to-Spot transfer" {
            val time = Instant.parse("2026-07-10T12:00:00Z")
            val legs = listOf(
                transferLeg("futures-debit", time, Asset.USD, "-100", "spotfromfutures", "futures-2"),
                transferLeg("spot-credit", time, Asset.USD, "100", "spotfromfutures", "futures-2"),
            )

            historicalBalanceBeforeTransfer(
                asset = Asset.USD,
                anchorBalance = "1100",
                legs = legs,
                resolvedScopes = mapOf(
                    "futures-debit" to AuthoritativeLedgerBalanceValidator.LedgerWalletScope.FUTURES,
                    "spot-credit" to AuthoritativeLedgerBalanceValidator.LedgerWalletScope.SPOT,
                ),
            ).shouldBeEqualComparingTo(BigDecimal("1000"))
            LedgerFlowClassifier.classifyAll(legs).values.toSet() shouldBe setOf(FlowCategory.INTERNAL_MOVE)
            legs.all { !LedgerEvent.isRewardEvent(it) } shouldBe true
        }

        "historical replay ignores trade ledger rows" {
            val time = Instant.parse("2026-07-10T12:00:00Z")
            val tradeLedger = LedgerEvent(
                ledgerId = "trade-ledger",
                time = time,
                type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                asset = Asset.USD,
                amount = BigDecimal("-100"),
            )
            val events = SnapshotHistoryCalculator.buildTimelineEvents(
                historicalTrades = emptyList(),
                historicalRewards = listOf(tradeLedger),
                cutoffTime = time.plusSeconds(1),
                now = time,
                reconstructionStart = time.minusSeconds(1),
            )
            events.filterIsInstance<SnapshotHistoryCalculator.TimelineEvent.RewardEvent>() shouldBe emptyList()
            val runningBalances = mutableMapOf(Asset.USD to BigDecimal("1000"))
            SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = events,
                allocations = listOf(Allocation(Asset.USD, 100.0)),
                runningBalances = runningBalances,
                currentPrices = mapOf(Asset.USD to BigDecimal.ONE),
                ohlcData = emptyMap(),
                tradePrices = emptyMap(),
                settings = defaultSettings,
            )
            runningBalances[Asset.USD]!! shouldBeEqualComparingTo BigDecimal("1000")
        }

        "historical replay skips an opaque staking scope" {
            val time = Instant.parse("2026-07-10T12:00:00Z")
            val opaqueStaking = LedgerEvent(
                ledgerId = "opaque-staking",
                time = time,
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                asset = Asset.SOL,
                amount = BigDecimal("0.5"),
                hasAuthoritativeBalance = true,
            )
            val runningBalances = mutableMapOf(Asset.SOL to BigDecimal("1"), Asset.USD to BigDecimal("1000"))
            SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = listOf(
                    SnapshotHistoryCalculator.TimelineEvent.RewardEvent(time, opaqueStaking),
                    SnapshotHistoryCalculator.TimelineEvent.DailyCloseEvent(time.minusSeconds(1)),
                ),
                allocations = listOf(
                    Allocation(Asset.SOL, 50.0),
                    Allocation(Asset.USD, 50.0),
                ),
                runningBalances = runningBalances,
                currentPrices = mapOf(Asset.SOL to BigDecimal.ONE, Asset.USD to BigDecimal.ONE),
                ohlcData = emptyMap(),
                tradePrices = emptyMap(),
                settings = defaultSettings,
                resolvedScopes = mapOf(
                    opaqueStaking.ledgerId to AuthoritativeLedgerBalanceValidator.LedgerWalletScope.OPAQUE_STAKING,
                ),
            )
            runningBalances[Asset.SOL]!! shouldBeEqualComparingTo BigDecimal("1")
        }
    }
}
