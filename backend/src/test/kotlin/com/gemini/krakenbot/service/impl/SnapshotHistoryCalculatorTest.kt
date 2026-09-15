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
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord
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
            snapshots.forEach { it.balancesObservedAt shouldBe null }

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

        "calculateHistoricalSnapshots should apply authoritative trade ledger legs to base fees" {
            val now = Instant.parse("2026-07-10T12:00:00Z")
            val tradeTime = now.minus(2, ChronoUnit.DAYS)
            val trade =
                TestFixtures.tradeRecord(
                    timestamp = tradeTime,
                    pair = "XXBTZUSD",
                    side = OrderSide.BUY.uppercaseName,
                    symbol = "BTC",
                    volume = BigDecimal("0.00703085"),
                    usdAmount = BigDecimal("461.14"),
                    price = BigDecimal("65588"),
                    fee = BigDecimal("0.9223"),
                    tradeId = "btc-base-fee-snapshot",
                )
            val legs =
                listOf(
                    LedgerEvent(
                        ledgerId = "snapshot-base-leg",
                        refid = "btc-base-fee-snapshot",
                        time = tradeTime,
                        type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                        asset = "BTC",
                        amount = BigDecimal("0.00703085"),
                        fee = BigDecimal("0.00001406"),
                        balance = BigDecimal("0.06541898"),
                        hasAuthoritativeBalance = true,
                    ),
                    LedgerEvent(
                        ledgerId = "snapshot-quote-leg",
                        refid = "btc-base-fee-snapshot",
                        time = tradeTime,
                        type = KrakenApiConstants.LEDGER_TYPE_TRADE,
                        asset = "USD",
                        amount = BigDecimal("-461.1394"),
                        balance = BigDecimal("0.0103"),
                        hasAuthoritativeBalance = true,
                    ),
                )

            val events =
                SnapshotHistoryCalculator.buildTimelineEvents(
                    historicalTrades = listOf(trade),
                    cutoffTime = now.minus(5, ChronoUnit.DAYS),
                    now = now,
                )

            val runningBalances =
                mutableMapOf(
                    "BTC" to BigDecimal("0.06541898"),
                    "USD" to BigDecimal("0.0103"),
                )

            SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = events,
                allocations =
                listOf(
                    Allocation(Asset.BTC, 50.0),
                    Allocation(Asset.USD, 50.0),
                ),
                runningBalances = runningBalances,
                currentPrices = mapOf("BTC" to BigDecimal("65588"), "USD" to BigDecimal.ONE),
                ohlcData = emptyMap(),
                tradePrices = emptyMap(),
                settings = defaultSettings,
                tradeLegsByRefId = mapOf("btc-base-fee-snapshot" to legs),
            )

            // Reverse replay from the recorded post-fill checkpoints: BTC takes the base-leg net
            // delta (volume minus fee) and USD the quote leg exactly, not the USD-equivalent fee.
            runningBalances["BTC"]!!.shouldBeEqualComparingTo(BigDecimal("0.05840219"))
            runningBalances["USD"]!!.shouldBeEqualComparingTo(BigDecimal("461.1497"))
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

        "reverse reconstruction consumes Kraken's rounded staking fee at the older checkpoint" {
            val now = Instant.parse("2026-09-11T12:00:00Z")
            val cutoff = now.minus(1, ChronoUnit.HOURS)
            val tradeTime = Instant.parse("2026-09-02T10:51:32.060Z")
            val rewardTime = Instant.parse("2026-09-03T00:51:57Z")
            val refid = "TKFSSQ-MLHDO-E3XTOE"
            val trade =
                TestFixtures.tradeRecord(
                    timestamp = tradeTime,
                    pair = "SOLUSD",
                    side = "buy",
                    symbol = "SOL",
                    volume = BigDecimal("0.21787766"),
                    usdAmount = BigDecimal("21.2431"),
                    price = BigDecimal("97.5"),
                    fee = BigDecimal("0.1275"),
                    tradeId = refid,
                )
            val staking =
                LedgerEvent(
                    ledgerId = "ledger-stake",
                    time = rewardTime,
                    type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                    asset = "SOL",
                    amount = BigDecimal("0.01426563"),
                    fee = BigDecimal("0.0043"),
                    balance = BigDecimal("28.13243918"),
                    hasAuthoritativeBalance = true,
                )
            val legs =
                mapOf(
                    refid to
                        listOf(
                            historicalTradeLeg("leg-base", refid, tradeTime, "SOL", "0.21787766", "28.12245324"),
                            historicalTradeLeg(
                                "leg-quote",
                                refid,
                                tradeTime,
                                "ZUSD",
                                "-21.2431",
                                "597.0624",
                                fee = "0.1275",
                            ),
                        ),
                )
            val events =
                SnapshotHistoryCalculator.buildTimelineEvents(
                    historicalTrades = listOf(trade),
                    historicalRewards = listOf(staking),
                    cutoffTime = cutoff,
                    now = now,
                )
            val runningBalances =
                mutableMapOf(
                    "SOL" to BigDecimal("28.13243918"),
                    "USD" to BigDecimal("597.0624"),
                )

            val snapshots =
                SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                    events = events,
                    allocations = listOf(Allocation(Asset(Asset.SOL), 50.0), Allocation(Asset.USD, 50.0)),
                    runningBalances = runningBalances,
                    currentPrices = mapOf("SOL" to BigDecimal.ONE, "USD" to BigDecimal.ONE),
                    ohlcData = emptyMap(),
                    tradePrices = emptyMap(),
                    settings = defaultSettings,
                    tradeLegsByRefId = legs,
                )

            snapshots.shouldNotBeEmpty()
            runningBalances.getValue("SOL") shouldBeEqualComparingTo BigDecimal("27.90457558")
            runningBalances.getValue("USD") shouldBeEqualComparingTo BigDecimal("618.433")
            val tradeSnapshotSol = snapshots.first { it.timestamp == tradeTime }.assets.getValue(Asset.SOL).balance
            tradeSnapshotSol shouldBeEqualComparingTo BigDecimal("28.12245324")
            val rewardSnapshotSol = snapshots.first { it.timestamp == rewardTime }.assets.getValue(Asset.SOL).balance
            rewardSnapshotSol shouldBeEqualComparingTo BigDecimal("28.13243918")
        }

        "reverse reconstruction fails closed when a rounded fee exceeds the validator envelope" {
            val now = Instant.parse("2026-09-11T12:00:00Z")
            val cutoff = now.minus(1, ChronoUnit.HOURS)
            val tradeTime = Instant.parse("2026-09-02T10:51:32.060Z")
            val rewardTime = Instant.parse("2026-09-03T00:51:57Z")
            val refid = "TKFSSQ-MLHDO-E3XTOE"
            val trade =
                TestFixtures.tradeRecord(
                    timestamp = tradeTime,
                    pair = "SOLUSD",
                    side = "buy",
                    symbol = "SOL",
                    volume = BigDecimal("0.21787766"),
                    usdAmount = BigDecimal("21.2431"),
                    price = BigDecimal("97.5"),
                    fee = BigDecimal("0.1275"),
                    tradeId = refid,
                )
            val staking =
                LedgerEvent(
                    ledgerId = "ledger-stake",
                    time = rewardTime,
                    type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                    asset = "SOL",
                    amount = BigDecimal("0.01426563"),
                    fee = BigDecimal("0.002"),
                    balance = BigDecimal("28.13243918"),
                    hasAuthoritativeBalance = true,
                )
            val legs =
                mapOf(
                    refid to
                        listOf(
                            historicalTradeLeg("leg-base", refid, tradeTime, "SOL", "0.21787766", "28.12245324"),
                            historicalTradeLeg(
                                "leg-quote",
                                refid,
                                tradeTime,
                                "ZUSD",
                                "-21.2431",
                                "597.0624",
                                fee = "0.1275",
                            ),
                        ),
                )
            val events =
                SnapshotHistoryCalculator.buildTimelineEvents(
                    historicalTrades = listOf(trade),
                    historicalRewards = listOf(staking),
                    cutoffTime = cutoff,
                    now = now,
                )

            shouldThrow<IllegalArgumentException> {
                SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                    events = events,
                    allocations = listOf(Allocation(Asset(Asset.SOL), 50.0), Allocation(Asset.USD, 50.0)),
                    runningBalances =
                    mutableMapOf(
                        "SOL" to BigDecimal("28.13243918"),
                        "USD" to BigDecimal("597.0624"),
                    ),
                    currentPrices = mapOf("SOL" to BigDecimal.ONE, "USD" to BigDecimal.ONE),
                    ohlcData = emptyMap(),
                    tradePrices = emptyMap(),
                    settings = defaultSettings,
                    tradeLegsByRefId = legs,
                )
            }.message shouldBe "Missing tracked balance during historical reconstruction for SOL"
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

        "same-instant replay orders ledger rows before trades and daily closes" {
            val now = Instant.parse("2026-07-10T12:00:00Z")
            val time = Instant.parse("2026-07-09T23:59:59Z")
            val trade = TestFixtures.tradeRecord(
                timestamp = time,
                pair = "BTCUSD",
                side = "buy",
                symbol = "BTC",
                volume = BigDecimal("0.5"),
                usdAmount = BigDecimal("10000"),
                fee = BigDecimal.ZERO,
            )
            val reward = LedgerEvent(
                ledgerId = "same-instant-reward",
                time = time,
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                asset = Asset.BTC,
                amount = BigDecimal("0.5"),
            )

            val events = SnapshotHistoryCalculator.buildTimelineEvents(
                historicalTrades = listOf(trade),
                historicalRewards = listOf(reward),
                cutoffTime = now,
                now = now,
            )

            events.filter { it.timestamp == time } shouldBe listOf(
                SnapshotHistoryCalculator.TimelineEvent.RewardEvent(time, reward),
                SnapshotHistoryCalculator.TimelineEvent.TradeEvent(time, trade),
                SnapshotHistoryCalculator.TimelineEvent.DailyCloseEvent(time),
            )
        }

        "historical replay fails closed on unsupported trade markets" {
            val now = Instant.parse("2026-07-10T12:00:00Z")
            val trade = TestFixtures.tradeRecord(
                timestamp = now.minusSeconds(60),
                pair = "ADAEUR",
                side = "buy",
                symbol = "ADA",
                volume = BigDecimal("1"),
                usdAmount = BigDecimal("1"),
            )
            val events = SnapshotHistoryCalculator.buildTimelineEvents(
                historicalTrades = listOf(trade),
                cutoffTime = now,
                now = now,
            )

            val exception = shouldThrow<IllegalArgumentException> {
                SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                    events = events,
                    allocations = listOf(Allocation(Asset.USD, 100.0)),
                    runningBalances = mutableMapOf(Asset.USD to BigDecimal("1000")),
                    currentPrices = mapOf(Asset.USD to BigDecimal.ONE),
                    ohlcData = emptyMap(),
                    tradePrices = emptyMap(),
                    settings = defaultSettings,
                )
            }
            exception.message shouldBe "unsupported historical market ADAEUR"
        }

        "same-instant trades reverse-apply newest-first through their checkpoint chain" {
            val tradeTime = Instant.ofEpochMilli(1766166391727L)
            val rewardTime = Instant.ofEpochMilli(1766125623634L)
            val purchase = TestFixtures.tradeRecord(
                timestamp = tradeTime,
                pair = "SOLUSD",
                side = "buy",
                symbol = Asset.SOL,
                volume = BigDecimal("3.30644757"),
                usdAmount = BigDecimal("410"),
                price = BigDecimal("124"),
                fee = BigDecimal("0.82"),
                id = 4341,
                tradeId = "TVZEP2-JWRID-HBY5IZ",
            )
            val dust = TestFixtures.tradeRecord(
                timestamp = tradeTime,
                pair = "SOLUSD",
                side = "buy",
                symbol = Asset.SOL,
                volume = BigDecimal("0.00000405"),
                usdAmount = BigDecimal.ZERO,
                price = BigDecimal("124"),
                id = 4340,
                tradeId = "TDAE2L-A4IV2-QZ3X6Q",
            )
            val reward = LedgerEvent(
                ledgerId = "10443",
                refid = "TRX2EFH-2SYPP-E7LQ2T",
                time = rewardTime,
                type = KrakenApiConstants.LEDGER_TYPE_REWARD,
                subtype = "welcomebonus",
                asset = Asset.SOL,
                amount = BigDecimal("0.00406444"),
                balance = BigDecimal("0.00406444"),
                hasAuthoritativeBalance = true,
            )
            val legs = mapOf(
                "TVZEP2-JWRID-HBY5IZ" to listOf(
                    historicalTradeLeg(
                        ledgerId = "10323",
                        refid = "TVZEP2-JWRID-HBY5IZ",
                        time = tradeTime,
                        asset = Asset.SOL,
                        amount = "3.30644757",
                        balance = "3.30389911",
                        fee = "0.0066129",
                    ),
                    historicalTradeLeg(
                        ledgerId = "10324",
                        refid = "TVZEP2-JWRID-HBY5IZ",
                        time = tradeTime,
                        asset = Asset.USD,
                        amount = "-409.9995",
                        balance = "2710.0096",
                    ),
                ),
                "TDAE2L-A4IV2-QZ3X6Q" to listOf(
                    historicalTradeLeg(
                        ledgerId = "10321",
                        refid = "TDAE2L-A4IV2-QZ3X6Q",
                        time = tradeTime,
                        asset = Asset.SOL,
                        amount = "0.00000405",
                        balance = "3.30390316",
                    ),
                    historicalTradeLeg(
                        ledgerId = "10322",
                        refid = "TDAE2L-A4IV2-QZ3X6Q",
                        time = tradeTime,
                        asset = Asset.USD,
                        amount = "-0.0005",
                        balance = "2710.0091",
                    ),
                ),
            )
            val allocations = listOf(
                Allocation(Asset.SOL, 50.0),
                Allocation(Asset.BTC, 0.0),
                Allocation(Asset.USD, 50.0),
            )
            val prices = mapOf(
                Asset.SOL to BigDecimal.ONE,
                Asset.BTC to BigDecimal.ONE,
                Asset.USD to BigDecimal.ONE,
            )

            fun reconstruct(trades: List<TradeRecord>): Pair<List<PortfolioSnapshot>, MutableMap<String, BigDecimal>> {
                val events = SnapshotHistoryCalculator.buildTimelineEvents(
                    historicalTrades = trades,
                    historicalRewards = listOf(reward),
                    cutoffTime = tradeTime.plusSeconds(1),
                    now = tradeTime,
                    reconstructionStart = rewardTime.minus(1, ChronoUnit.DAYS),
                )
                val running = mutableMapOf(
                    Asset.SOL to BigDecimal("3.30390316"),
                    Asset.USD to BigDecimal("2710.0091"),
                    Asset.BTC to BigDecimal("2.5"),
                )
                val snapshots = SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                    events = events,
                    allocations = allocations,
                    runningBalances = running,
                    currentPrices = prices,
                    ohlcData = emptyMap(),
                    tradePrices = emptyMap(),
                    settings = defaultSettings,
                    tradeLegsByRefId = legs,
                )
                return snapshots to running
            }

            val (snapshots, running) = reconstruct(listOf(purchase, dust))
            val rows = snapshots.filter { it.timestamp == tradeTime }
            rows.size shouldBe 2
            rows[0].assets.getValue(Asset.SOL).balance shouldBeEqualComparingTo BigDecimal("3.30390316")
            rows[0].assets.getValue(Asset.USD).balance shouldBeEqualComparingTo BigDecimal("2710.0091")
            rows[1].assets.getValue(Asset.SOL).balance shouldBeEqualComparingTo BigDecimal("3.30389911")
            rows[1].assets.getValue(Asset.USD).balance shouldBeEqualComparingTo BigDecimal("2710.0096")

            val atReward = snapshots.first { it.timestamp == rewardTime }
            atReward.assets.getValue(Asset.SOL).balance shouldBeEqualComparingTo BigDecimal("0.00406444")
            atReward.assets.getValue(Asset.USD).balance shouldBeEqualComparingTo BigDecimal("3120.0091")
            snapshots.forEach { it.assets.getValue(Asset.BTC).balance shouldBeEqualComparingTo BigDecimal("2.5") }
            running.getValue(Asset.SOL) shouldBeEqualComparingTo BigDecimal.ZERO
            running.getValue(Asset.USD) shouldBeEqualComparingTo BigDecimal("3120.0091")

            val (reordered, _) = reconstruct(listOf(dust, purchase))
            solUsdSnapshotSignature(reordered) shouldBe solUsdSnapshotSignature(snapshots)

            val beforeMutation = solUsdSnapshotSignature(snapshots)
            running[Asset.SOL] = BigDecimal("999")
            solUsdSnapshotSignature(snapshots) shouldBe beforeMutation
        }

        "same-instant sells keep their checkpoint chain in both input orders" {
            val time = Instant.ofEpochMilli(1764973195611L)
            val older = TestFixtures.tradeRecord(
                timestamp = time,
                pair = "SOLUSD",
                side = "sell",
                symbol = Asset.SOL,
                volume = BigDecimal("0.25127882"),
                usdAmount = BigDecimal("89.1083"),
                price = BigDecimal("354.61"),
                id = 4576,
                tradeId = "TBXYJV-ZRONH-77RF2G",
            )
            val newer = TestFixtures.tradeRecord(
                timestamp = time,
                pair = "SOLUSD",
                side = "sell",
                symbol = Asset.SOL,
                volume = BigDecimal("0.00825306"),
                usdAmount = BigDecimal("2.9267"),
                price = BigDecimal("354.61"),
                id = 4575,
                tradeId = "T26AF6-3XN46-RP2KRG",
            )
            val legs = mapOf(
                "TBXYJV-ZRONH-77RF2G" to listOf(
                    historicalTradeLeg(
                        ledgerId = "11080",
                        refid = "TBXYJV-ZRONH-77RF2G",
                        time = time,
                        asset = Asset.SOL,
                        amount = "-0.25127882",
                        balance = "0.00825306",
                    ),
                    historicalTradeLeg(
                        ledgerId = "11079",
                        refid = "TBXYJV-ZRONH-77RF2G",
                        time = time,
                        asset = Asset.USD,
                        amount = "89.1083",
                        balance = "1752.4063",
                    ),
                ),
                "T26AF6-3XN46-RP2KRG" to listOf(
                    historicalTradeLeg(
                        ledgerId = "11082",
                        refid = "T26AF6-3XN46-RP2KRG",
                        time = time,
                        asset = Asset.SOL,
                        amount = "-0.00825306",
                        balance = "0",
                    ),
                    historicalTradeLeg(
                        ledgerId = "11081",
                        refid = "T26AF6-3XN46-RP2KRG",
                        time = time,
                        asset = Asset.USD,
                        amount = "2.9267",
                        balance = "1755.333",
                    ),
                ),
            )

            fun reconstruct(trades: List<TradeRecord>): Pair<List<PortfolioSnapshot>, MutableMap<String, BigDecimal>> {
                val events = SnapshotHistoryCalculator.buildTimelineEvents(
                    historicalTrades = trades,
                    cutoffTime = time.plusSeconds(1),
                    now = time,
                    reconstructionStart = time.minus(1, ChronoUnit.DAYS),
                )
                val running = mutableMapOf(
                    Asset.SOL to BigDecimal.ZERO,
                    Asset.USD to BigDecimal("1755.333"),
                )
                val snapshots = SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                    events = events,
                    allocations = listOf(Allocation(Asset.SOL, 50.0), Allocation(Asset.USD, 50.0)),
                    runningBalances = running,
                    currentPrices = mapOf(Asset.SOL to BigDecimal.ONE, Asset.USD to BigDecimal.ONE),
                    ohlcData = emptyMap(),
                    tradePrices = emptyMap(),
                    settings = defaultSettings,
                    tradeLegsByRefId = legs,
                )
                return snapshots to running
            }

            val (snapshots, running) = reconstruct(listOf(older, newer))
            val rows = snapshots.filter { it.timestamp == time }
            rows.size shouldBe 2
            rows[0].assets.getValue(Asset.SOL).balance shouldBeEqualComparingTo BigDecimal.ZERO
            rows[0].assets.getValue(Asset.USD).balance shouldBeEqualComparingTo BigDecimal("1755.333")
            rows[1].assets.getValue(Asset.SOL).balance shouldBeEqualComparingTo BigDecimal("0.00825306")
            rows[1].assets.getValue(Asset.USD).balance shouldBeEqualComparingTo BigDecimal("1752.4063")
            running.getValue(Asset.SOL) shouldBeEqualComparingTo BigDecimal("0.25953188")
            running.getValue(Asset.USD) shouldBeEqualComparingTo BigDecimal("1663.2980")

            val (reordered, _) = reconstruct(listOf(newer, older))
            solUsdSnapshotSignature(reordered) shouldBe solUsdSnapshotSignature(snapshots)
        }

        "same-instant reward and trade follow their checkpoint chain regardless of input order" {
            val time = Instant.ofEpochMilli(1766166391727L)
            val reward = LedgerEvent(
                ledgerId = "r-1",
                time = time,
                type = KrakenApiConstants.LEDGER_TYPE_REWARD,
                asset = Asset.SOL,
                amount = BigDecimal.ONE,
                balance = BigDecimal.ONE,
                hasAuthoritativeBalance = true,
            )
            val trade = TestFixtures.tradeRecord(
                timestamp = time,
                pair = "SOLUSD",
                side = "buy",
                symbol = Asset.SOL,
                volume = BigDecimal.ONE,
                usdAmount = BigDecimal("124"),
                price = BigDecimal("124"),
                tradeId = "CHAIN-1",
            )
            val legs = mapOf(
                "CHAIN-1" to listOf(
                    historicalTradeLeg(
                        ledgerId = "c-1",
                        refid = "CHAIN-1",
                        time = time,
                        asset = Asset.SOL,
                        amount = "1",
                        balance = "2",
                    ),
                    historicalTradeLeg(
                        ledgerId = "c-2",
                        refid = "CHAIN-1",
                        time = time,
                        asset = Asset.USD,
                        amount = "-124",
                        balance = "100",
                    ),
                ),
            )
            val rewardEvent = SnapshotHistoryCalculator.TimelineEvent.RewardEvent(time, reward)
            val tradeEvent = SnapshotHistoryCalculator.TimelineEvent.TradeEvent(time, trade)

            fun reconstruct(
                events: List<SnapshotHistoryCalculator.TimelineEvent>,
            ): Pair<List<PortfolioSnapshot>, MutableMap<String, BigDecimal>> {
                val running = mutableMapOf(Asset.SOL to BigDecimal("2"), Asset.USD to BigDecimal("100"))
                val snapshots = SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                    events = events,
                    allocations = listOf(Allocation(Asset.SOL, 50.0), Allocation(Asset.USD, 50.0)),
                    runningBalances = running,
                    currentPrices = mapOf(Asset.SOL to BigDecimal.ONE, Asset.USD to BigDecimal.ONE),
                    ohlcData = emptyMap(),
                    tradePrices = emptyMap(),
                    settings = defaultSettings,
                    tradeLegsByRefId = legs,
                )
                return snapshots to running
            }

            val (snapshots, running) = reconstruct(listOf(rewardEvent, tradeEvent))
            snapshots.size shouldBe 2
            snapshots[0].assets.getValue(Asset.SOL).balance shouldBeEqualComparingTo BigDecimal("2")
            snapshots[1].assets.getValue(Asset.SOL).balance shouldBeEqualComparingTo BigDecimal.ONE
            running.getValue(Asset.SOL) shouldBeEqualComparingTo BigDecimal.ZERO
            running.getValue(Asset.USD) shouldBeEqualComparingTo BigDecimal("224")

            val (reordered, _) = reconstruct(listOf(tradeEvent, rewardEvent))
            solUsdSnapshotSignature(reordered) shouldBe solUsdSnapshotSignature(snapshots)
        }

        "same-instant trades without retained ledger legs keep repository order" {
            val time = Instant.ofEpochMilli(1766166391727L)
            val first = TestFixtures.tradeRecord(
                timestamp = time,
                pair = "SOLUSD",
                side = "buy",
                symbol = Asset.SOL,
                volume = BigDecimal.ONE,
                usdAmount = BigDecimal("124"),
                price = BigDecimal("124"),
            )
            val second = TestFixtures.tradeRecord(
                timestamp = time,
                pair = "SOLUSD",
                side = "buy",
                symbol = Asset.SOL,
                volume = BigDecimal("2"),
                usdAmount = BigDecimal("248"),
                price = BigDecimal("124"),
            )

            fun reconstruct(trades: List<TradeRecord>): List<PortfolioSnapshot> {
                val events = SnapshotHistoryCalculator.buildTimelineEvents(
                    historicalTrades = trades,
                    cutoffTime = time.plusSeconds(1),
                    now = time,
                    reconstructionStart = time.minus(1, ChronoUnit.DAYS),
                )
                return SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                    events = events,
                    allocations = listOf(Allocation(Asset.SOL, 50.0), Allocation(Asset.USD, 50.0)),
                    runningBalances = mutableMapOf(Asset.SOL to BigDecimal("3"), Asset.USD to BigDecimal.ZERO),
                    currentPrices = mapOf(Asset.SOL to BigDecimal.ONE, Asset.USD to BigDecimal.ONE),
                    ohlcData = emptyMap(),
                    tradePrices = emptyMap(),
                    settings = defaultSettings,
                    tradeLegsByRefId = emptyMap(),
                )
            }

            val rows = reconstruct(listOf(first, second)).filter { it.timestamp == time }
            rows.size shouldBe 2
            rows[0].assets.getValue(Asset.SOL).balance shouldBeEqualComparingTo BigDecimal("3")
            rows[1].assets.getValue(Asset.SOL).balance shouldBeEqualComparingTo BigDecimal("2")

            val reversedRows = reconstruct(listOf(second, first)).filter { it.timestamp == time }
            reversedRows[1].assets.getValue(Asset.SOL).balance shouldBeEqualComparingTo BigDecimal.ONE
        }

        "same-instant reward checkpoints apply only to tracked authoritative spot balances" {
            val time = Instant.ofEpochMilli(1766166391727L)
            val trade = TestFixtures.tradeRecord(
                timestamp = time,
                pair = "SOLUSD",
                side = "buy",
                symbol = Asset.SOL,
                volume = BigDecimal.ONE,
                usdAmount = BigDecimal("124"),
                price = BigDecimal("124"),
                tradeId = "SCOPE-1",
            )
            val legs = mapOf(
                "SCOPE-1" to listOf(
                    historicalTradeLeg("s-1", "SCOPE-1", time, Asset.SOL, "1", "2"),
                    historicalTradeLeg("s-2", "SCOPE-1", time, Asset.USD, "-124", "100"),
                ),
            )
            val spotReward = LedgerEvent(
                ledgerId = "spot-reward",
                time = time,
                type = KrakenApiConstants.LEDGER_TYPE_REWARD,
                asset = Asset.SOL,
                amount = BigDecimal.ONE,
                balance = BigDecimal("3"),
                hasAuthoritativeBalance = true,
            )
            val unpostedReward = LedgerEvent(
                ledgerId = "unposted-reward",
                time = time,
                type = KrakenApiConstants.LEDGER_TYPE_REWARD,
                asset = Asset.SOL,
                amount = BigDecimal("0.5"),
                hasAuthoritativeBalance = false,
            )
            val stakingReward = LedgerEvent(
                ledgerId = "staking-reward",
                time = time,
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                asset = Asset.SOL,
                amount = BigDecimal("0.25"),
                balance = BigDecimal("9"),
                hasAuthoritativeBalance = true,
            )
            val untrackedReward = LedgerEvent(
                ledgerId = "doge-reward",
                time = time,
                type = KrakenApiConstants.LEDGER_TYPE_REWARD,
                asset = "DOGE",
                amount = BigDecimal("5"),
                balance = BigDecimal("5"),
                hasAuthoritativeBalance = true,
            )
            val events = SnapshotHistoryCalculator.buildTimelineEvents(
                historicalTrades = listOf(trade),
                historicalRewards = listOf(spotReward, unpostedReward, stakingReward, untrackedReward),
                cutoffTime = time.plusSeconds(1),
                now = time,
                reconstructionStart = time.minus(1, ChronoUnit.DAYS),
            )
            val running = mutableMapOf(Asset.SOL to BigDecimal("3"), Asset.USD to BigDecimal("100"))
            val snapshots = SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = events,
                allocations = listOf(Allocation(Asset.SOL, 50.0), Allocation(Asset.USD, 50.0)),
                runningBalances = running,
                currentPrices = mapOf(Asset.SOL to BigDecimal.ONE, Asset.USD to BigDecimal.ONE),
                ohlcData = emptyMap(),
                tradePrices = emptyMap(),
                settings = defaultSettings,
                resolvedScopes = mapOf(
                    "staking-reward" to AuthoritativeLedgerBalanceValidator.LedgerWalletScope.STAKING,
                ),
                tradeLegsByRefId = legs,
            )
            val rows = snapshots.filter { it.timestamp == time }
            rows.size shouldBe 5
            rows[0].assets.getValue(Asset.SOL).balance shouldBeEqualComparingTo BigDecimal("3")
            rows[1].assets.getValue(Asset.SOL).balance shouldBeEqualComparingTo BigDecimal("2")
            running.getValue(Asset.SOL) shouldBeEqualComparingTo BigDecimal("0.5")
            running.getValue(Asset.USD) shouldBeEqualComparingTo BigDecimal("224")
        }

        "same-instant trades chain through a single authoritative leg" {
            val time = Instant.ofEpochMilli(1766166391727L)
            val older = TestFixtures.tradeRecord(
                timestamp = time,
                pair = "SOLUSD",
                side = "buy",
                symbol = Asset.SOL,
                volume = BigDecimal.ONE,
                usdAmount = BigDecimal("124"),
                price = BigDecimal("124"),
                id = 6001,
                tradeId = "ONE-LEG-1",
            )
            val newer = TestFixtures.tradeRecord(
                timestamp = time,
                pair = "SOLUSD",
                side = "buy",
                symbol = Asset.SOL,
                volume = BigDecimal.ONE,
                usdAmount = BigDecimal("124"),
                price = BigDecimal("124"),
                id = 6002,
                tradeId = "ONE-LEG-2",
            )
            val legs = mapOf(
                "ONE-LEG-1" to listOf(
                    historicalTradeLeg("o-1", "ONE-LEG-1", time, Asset.SOL, "1", "2"),
                    historicalTradeLeg("o-2", "ONE-LEG-1", time, Asset.USD, "-124", "100", authoritative = false),
                ),
                "ONE-LEG-2" to listOf(
                    historicalTradeLeg("n-1", "ONE-LEG-2", time, Asset.SOL, "1", "3"),
                    historicalTradeLeg("n-2", "ONE-LEG-2", time, Asset.USD, "-124", "100", authoritative = false),
                ),
            )

            fun reconstruct(trades: List<TradeRecord>): Pair<List<PortfolioSnapshot>, MutableMap<String, BigDecimal>> {
                val events = SnapshotHistoryCalculator.buildTimelineEvents(
                    historicalTrades = trades,
                    cutoffTime = time.plusSeconds(1),
                    now = time,
                    reconstructionStart = time.minus(1, ChronoUnit.DAYS),
                )
                val running = mutableMapOf(Asset.SOL to BigDecimal("3"), Asset.USD to BigDecimal("100"))
                val snapshots = SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                    events = events,
                    allocations = listOf(Allocation(Asset.SOL, 50.0), Allocation(Asset.USD, 50.0)),
                    runningBalances = running,
                    currentPrices = mapOf(Asset.SOL to BigDecimal.ONE, Asset.USD to BigDecimal.ONE),
                    ohlcData = emptyMap(),
                    tradePrices = emptyMap(),
                    settings = defaultSettings,
                    tradeLegsByRefId = legs,
                )
                return snapshots to running
            }

            val (snapshots, running) = reconstruct(listOf(newer, older))
            val rows = snapshots.filter { it.timestamp == time }
            rows.size shouldBe 2
            rows[0].assets.getValue(Asset.SOL).balance shouldBeEqualComparingTo BigDecimal("3")
            rows[1].assets.getValue(Asset.SOL).balance shouldBeEqualComparingTo BigDecimal("2")
            running.getValue(Asset.SOL) shouldBeEqualComparingTo BigDecimal.ONE
            running.getValue(Asset.USD) shouldBeEqualComparingTo BigDecimal("348")

            val (reordered, _) = reconstruct(listOf(older, newer))
            solUsdSnapshotSignature(reordered) shouldBe solUsdSnapshotSignature(snapshots)
        }

        "jan-9 same-instant USD cycle resolves conversion before deposit from the reverse boundary" {
            val groupTime = Instant.parse("2026-01-09T10:44:15Z")
            val boundaryTime = Instant.parse("2026-01-09T10:44:59Z")
            val tradeTime = Instant.parse("2026-01-09T07:41:49.777Z")
            val refid = "TL63EH-7C4Z3-JESP44"
            val monTrade = TestFixtures.tradeRecord(
                timestamp = tradeTime,
                pair = "MONUSD",
                side = "sell",
                symbol = "MON",
                volume = BigDecimal.ONE,
                usdAmount = BigDecimal("20.5525"),
                price = BigDecimal("20.5525"),
                tradeId = refid,
            )
            val deposit = LedgerEvent(
                ledgerId = "LLEYGS-AG7JV-X2UU6A",
                time = groupTime,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "USD",
                amount = BigDecimal("1000"),
                balance = BigDecimal("1389.2793"),
                hasAuthoritativeBalance = true,
            )
            val conversionUsd = LedgerEvent(
                ledgerId = "L6IOH3-Z5DT7-35QDAV",
                refid = "JAN9-CONVERSION",
                time = groupTime,
                type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                asset = "USD",
                amount = BigDecimal("-1000"),
                balance = BigDecimal("389.2793"),
                hasAuthoritativeBalance = true,
                hasAuthoritativeFee = true,
            )
            val conversionUsdg = LedgerEvent(
                ledgerId = "L6NCOU-KB3QA-KJTSJN",
                refid = "JAN9-CONVERSION",
                time = groupTime,
                type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                asset = "USDG",
                amount = BigDecimal("1000"),
                balance = BigDecimal("1000"),
                hasAuthoritativeBalance = true,
                hasAuthoritativeFee = true,
            )
            val receiveUsd = LedgerEvent(
                ledgerId = "a-receive",
                time = boundaryTime,
                type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                asset = "USD",
                amount = BigDecimal("1000"),
                balance = BigDecimal("1389.2793"),
                hasAuthoritativeBalance = true,
            )
            val spendUsdg = LedgerEvent(
                ledgerId = "b-spend",
                time = boundaryTime,
                type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                asset = "USDG",
                amount = BigDecimal("-1000"),
                balance = BigDecimal.ZERO,
                hasAuthoritativeBalance = true,
            )
            val legs = mapOf(
                refid to listOf(
                    historicalTradeLeg("L-MON-BASE", refid, tradeTime, "MON", "-1", "29239.47506"),
                    historicalTradeLeg("L-USD-QUOTE", refid, tradeTime, "USD", "20.5525", "389.2793"),
                ),
            )
            val events = SnapshotHistoryCalculator.buildTimelineEvents(
                historicalTrades = listOf(monTrade),
                historicalRewards = listOf(deposit, conversionUsd, conversionUsdg, receiveUsd, spendUsdg),
                cutoffTime = boundaryTime.plusSeconds(1),
                now = boundaryTime,
                reconstructionStart = tradeTime.minus(1, ChronoUnit.DAYS),
            )
            val running = mutableMapOf(
                "MON" to BigDecimal("29239.47506"),
                "USD" to BigDecimal("1389.2793"),
                "USDG" to BigDecimal.ZERO,
            )

            val snapshots = SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = events,
                allocations = listOf(Allocation(Asset("MON"), 50.0), Allocation(Asset.USD, 50.0)),
                runningBalances = running,
                currentPrices = mapOf("MON" to BigDecimal.ONE, "USD" to BigDecimal.ONE),
                ohlcData = emptyMap(),
                tradePrices = emptyMap(),
                settings = defaultSettings,
                tradeLegsByRefId = legs,
            )

            running.getValue("USD") shouldBeEqualComparingTo BigDecimal("368.7268")
            running.getValue("USDG") shouldBeEqualComparingTo BigDecimal.ZERO
            running.getValue("MON") shouldBeEqualComparingTo BigDecimal("29240.47506")
            snapshots.filter { it.timestamp == groupTime }.first().assets.getValue(Asset.USD).balance
                .shouldBeEqualComparingTo(BigDecimal("389.2793"))
            snapshots.first { it.timestamp == tradeTime }.assets.getValue(Asset.USD).balance
                .shouldBeEqualComparingTo(BigDecimal("389.2793"))
        }

        "same-instant group fails closed when orderings produce different balances" {
            val time = Instant.parse("2026-07-10T12:00:00Z")
            val rewardA = LedgerEvent(
                ledgerId = "amb-a",
                time = time,
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                asset = Asset.SOL,
                amount = BigDecimal("0.00000001").negate(),
                balance = BigDecimal("100"),
                hasAuthoritativeBalance = true,
            )
            val rewardB = LedgerEvent(
                ledgerId = "amb-b",
                time = time,
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                asset = Asset.SOL,
                amount = BigDecimal("0.00000002"),
                balance = BigDecimal("100.00000001"),
                hasAuthoritativeBalance = true,
            )

            shouldThrow<IllegalArgumentException> {
                SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                    events = listOf(
                        SnapshotHistoryCalculator.TimelineEvent.RewardEvent(time, rewardA),
                        SnapshotHistoryCalculator.TimelineEvent.RewardEvent(time, rewardB),
                    ),
                    allocations = listOf(Allocation(Asset.SOL, 50.0), Allocation(Asset.USD, 50.0)),
                    runningBalances = mutableMapOf(Asset.SOL to BigDecimal("100"), Asset.USD to BigDecimal("1000")),
                    currentPrices = mapOf(Asset.SOL to BigDecimal.ONE, Asset.USD to BigDecimal.ONE),
                    ohlcData = emptyMap(),
                    tradePrices = emptyMap(),
                    settings = defaultSettings,
                )
            }.message shouldBe
                "Ambiguous same-instant ordering at $time: 2 complete orderings produce different balances"
        }

        "same-instant group fails closed when no ordering fits the boundary state" {
            val time = Instant.parse("2026-07-10T12:00:00Z")
            val conflicting = (1..2).map { index ->
                LedgerEvent(
                    ledgerId = "no-fit-$index",
                    time = time,
                    type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                    asset = Asset.SOL,
                    amount = BigDecimal("10"),
                    balance = BigDecimal("100"),
                    hasAuthoritativeBalance = true,
                )
            }

            shouldThrow<IllegalArgumentException> {
                SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                    events = conflicting.map { SnapshotHistoryCalculator.TimelineEvent.RewardEvent(time, it) },
                    allocations = listOf(Allocation(Asset.SOL, 50.0), Allocation(Asset.USD, 50.0)),
                    runningBalances = mutableMapOf(Asset.SOL to BigDecimal("100"), Asset.USD to BigDecimal("1000")),
                    currentPrices = mapOf(Asset.SOL to BigDecimal.ONE, Asset.USD to BigDecimal.ONE),
                    ohlcData = emptyMap(),
                    tradePrices = emptyMap(),
                    settings = defaultSettings,
                )
            }.message shouldBe "No valid same-instant ordering at $time for 2 checkpointed events"
        }

        "staking-scope transfer twins stay outside the spot same-instant solver" {
            val time = Instant.parse("2026-12-08T12:00:00Z")
            val twinA = LedgerEvent(
                ledgerId = "twin-a",
                time = time,
                type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                subtype = "spottostaking",
                asset = Asset.SOL,
                amount = BigDecimal("-4"),
                balance = BigDecimal("6"),
                hasAuthoritativeBalance = true,
            )
            val twinB = LedgerEvent(
                ledgerId = "twin-b",
                time = time,
                type = KrakenApiConstants.LEDGER_TYPE_TRANSFER,
                subtype = "stakingtospot",
                asset = Asset.SOL,
                amount = BigDecimal("4"),
                balance = BigDecimal("7"),
                hasAuthoritativeBalance = true,
            )
            val running = mutableMapOf(Asset.SOL to BigDecimal("10"), Asset.USD to BigDecimal("1000"))

            SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = listOf(
                    SnapshotHistoryCalculator.TimelineEvent.RewardEvent(time, twinA),
                    SnapshotHistoryCalculator.TimelineEvent.RewardEvent(time, twinB),
                    SnapshotHistoryCalculator.TimelineEvent.DailyCloseEvent(time.minusSeconds(1)),
                ),
                allocations = listOf(Allocation(Asset.SOL, 50.0), Allocation(Asset.USD, 50.0)),
                runningBalances = running,
                currentPrices = mapOf(Asset.SOL to BigDecimal.ONE, Asset.USD to BigDecimal.ONE),
                ohlcData = emptyMap(),
                tradePrices = emptyMap(),
                settings = defaultSettings,
                resolvedScopes = mapOf(
                    "twin-a" to AuthoritativeLedgerBalanceValidator.LedgerWalletScope.STAKING,
                    "twin-b" to AuthoritativeLedgerBalanceValidator.LedgerWalletScope.STAKING,
                ),
            )

            running.getValue(Asset.SOL).shouldBeEqualComparingTo(BigDecimal("10"))
        }

        "same-instant solver respects the fee-rounding allowance between checkpoints" {
            val time = Instant.parse("2026-07-10T12:00:00Z")
            val stakingA = LedgerEvent(
                ledgerId = "fee-a",
                time = time,
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                asset = Asset.SOL,
                amount = BigDecimal("0.01426563"),
                fee = BigDecimal("0.0043"),
                balance = BigDecimal("100.00000000"),
                hasAuthoritativeBalance = true,
            )
            val stakingB = LedgerEvent(
                ledgerId = "fee-b",
                time = time,
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                asset = Asset.SOL,
                amount = BigDecimal("0.00002"),
                balance = BigDecimal("99.99005437"),
                hasAuthoritativeBalance = true,
            )
            val running = mutableMapOf(Asset.SOL to BigDecimal("100.00000000"), Asset.USD to BigDecimal("1000"))

            SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = listOf(
                    SnapshotHistoryCalculator.TimelineEvent.RewardEvent(time, stakingA),
                    SnapshotHistoryCalculator.TimelineEvent.RewardEvent(time, stakingB),
                    SnapshotHistoryCalculator.TimelineEvent.DailyCloseEvent(time.minusSeconds(1)),
                ),
                allocations = listOf(Allocation(Asset.SOL, 50.0), Allocation(Asset.USD, 50.0)),
                runningBalances = running,
                currentPrices = mapOf(Asset.SOL to BigDecimal.ONE, Asset.USD to BigDecimal.ONE),
                ohlcData = emptyMap(),
                tradePrices = emptyMap(),
                settings = defaultSettings,
            )

            running.getValue(Asset.SOL).shouldBeEqualComparingTo(BigDecimal("99.99003437"))
        }

        "same-instant solver fails closed when the node budget is exhausted" {
            val time = Instant.parse("2026-07-10T12:00:00Z")
            // Same asset with dust deltas: every candidate stays within the checkpoint allowance
            // no matter which ordering is used, so all 8! complete orders are enumerated before
            // the commutation pruning can collapse them and the node budget trips first.
            val rewards = (0 until 8).map { index ->
                LedgerEvent(
                    ledgerId = "budget-$index",
                    time = time,
                    type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                    asset = Asset.SOL,
                    amount = BigDecimal("0.000000001"),
                    balance = BigDecimal("100"),
                    hasAuthoritativeBalance = true,
                )
            }
            val running = mutableMapOf(Asset.SOL to BigDecimal("100"))

            shouldThrow<IllegalArgumentException> {
                SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                    events = rewards.map { SnapshotHistoryCalculator.TimelineEvent.RewardEvent(time, it) },
                    allocations = listOf(Allocation(Asset.SOL, 100.0)),
                    runningBalances = running,
                    currentPrices = mapOf(Asset.SOL to BigDecimal.ONE),
                    ohlcData = emptyMap(),
                    tradePrices = emptyMap(),
                    settings = defaultSettings,
                )
            }.message shouldBe "Same-instant ordering search exceeded its 10000-node budget at $time"
        }

        "same-instant solver tie-breaks identical-terminal orderings on content keys" {
            val time = Instant.parse("2026-07-10T12:00:00Z")
            val solA = LedgerEvent(
                ledgerId = "a-sol",
                time = time,
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                asset = Asset.SOL,
                amount = BigDecimal("0.000000001"),
                balance = BigDecimal("100"),
                hasAuthoritativeBalance = true,
            )
            val solB = LedgerEvent(
                ledgerId = "b-sol",
                time = time,
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                asset = Asset.SOL,
                amount = BigDecimal("0.000000001"),
                balance = BigDecimal("100"),
                hasAuthoritativeBalance = true,
            )
            val usdg = LedgerEvent(
                ledgerId = "c-usdg",
                time = time,
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                asset = "USDG",
                amount = BigDecimal.ONE,
                balance = BigDecimal("100"),
                hasAuthoritativeBalance = true,
            )
            val xrp = LedgerEvent(
                ledgerId = "d-xrp",
                time = time,
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                asset = "XRP",
                amount = BigDecimal.ONE,
                balance = BigDecimal("100"),
                hasAuthoritativeBalance = true,
            )
            val running = mutableMapOf(
                Asset.SOL to BigDecimal("100"),
                "USDG" to BigDecimal("100"),
                "XRP" to BigDecimal("100"),
            )

            SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = listOf(solA, solB, usdg, xrp).map {
                    SnapshotHistoryCalculator.TimelineEvent.RewardEvent(time, it)
                },
                allocations = listOf(Allocation(Asset.SOL, 34.0), Allocation("USDG", 33.0), Allocation("XRP", 33.0)),
                runningBalances = running,
                currentPrices = mapOf(Asset.SOL to BigDecimal.ONE, "USDG" to BigDecimal.ONE, "XRP" to BigDecimal.ONE),
                ohlcData = emptyMap(),
                tradePrices = emptyMap(),
                settings = defaultSettings,
            )

            running.getValue(Asset.SOL).shouldBeEqualComparingTo(BigDecimal("99.999999999"))
            running.getValue("USDG") shouldBeEqualComparingTo BigDecimal("99")
            running.getValue("XRP") shouldBeEqualComparingTo BigDecimal("99")
        }

        "same-instant solver orders a checkpointed trade against the live boundary state" {
            val time = Instant.parse("2026-07-10T12:00:00Z")
            val refid = "T2-TRADE-REFID"
            val trade = TestFixtures.tradeRecord(
                timestamp = time,
                pair = "SOLUSD",
                side = "sell",
                symbol = "SOL",
                volume = BigDecimal.TEN,
                usdAmount = BigDecimal("50"),
                price = BigDecimal("5"),
                tradeId = refid,
            )
            val partnerReward = LedgerEvent(
                ledgerId = "t2-reward",
                time = time,
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                asset = Asset.SOL,
                amount = BigDecimal("-10"),
                balance = BigDecimal("110"),
                hasAuthoritativeBalance = true,
            )
            val legs = mapOf(
                refid to listOf(
                    historicalTradeLeg("L2-SOL-BASE", refid, time, "SOL", "-10", "100"),
                    historicalTradeLeg("L2-USD-QUOTE", refid, time, "USD", "50", "50"),
                ),
            )
            val running = mutableMapOf(
                Asset.SOL to BigDecimal("100"),
                Asset.USD to BigDecimal("50"),
            )

            val snapshots = SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = listOf(
                    SnapshotHistoryCalculator.TimelineEvent.TradeEvent(time, trade),
                    SnapshotHistoryCalculator.TimelineEvent.RewardEvent(time, partnerReward),
                ),
                allocations = listOf(Allocation(Asset.SOL, 50.0), Allocation(Asset.USD, 50.0)),
                runningBalances = running,
                currentPrices = mapOf(Asset.SOL to BigDecimal.ONE, Asset.USD to BigDecimal.ONE),
                ohlcData = emptyMap(),
                tradePrices = emptyMap(),
                settings = defaultSettings,
                tradeLegsByRefId = legs,
            )

            running.getValue(Asset.SOL).shouldBeEqualComparingTo(BigDecimal("120"))
            running.getValue(Asset.USD) shouldBeEqualComparingTo BigDecimal.ZERO
            val groupRows = snapshots.filter { it.timestamp == time }
            groupRows.first().assets.getValue(Asset.SOL).balance.shouldBeEqualComparingTo(BigDecimal("100"))
            groupRows.last().assets.getValue(Asset.SOL).balance.shouldBeEqualComparingTo(BigDecimal("110"))
        }

        "untracked reward inside a solver group stays outside the ordering search" {
            val time = Instant.parse("2026-07-10T12:00:00Z")
            val refid = "T3-TRADE-REFID"
            val trade = TestFixtures.tradeRecord(
                timestamp = time,
                pair = "DOGEUSD",
                side = "sell",
                symbol = "DOGE",
                volume = BigDecimal.TEN,
                usdAmount = BigDecimal("2"),
                price = BigDecimal("0.2"),
                tradeId = refid,
            )
            val legs = mapOf(
                refid to listOf(
                    historicalTradeLeg("L3-DOGE-BASE", refid, time, "DOGE", "-10", "5"),
                    historicalTradeLeg("L3-USD-QUOTE", refid, time, "USD", "2", "75"),
                ),
            )
            val untrackedDoge = LedgerEvent(
                ledgerId = "t3-doge-reward",
                time = time,
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                asset = "DOGE",
                amount = BigDecimal("2"),
                balance = BigDecimal("5"),
                hasAuthoritativeBalance = true,
            )
            val running = mutableMapOf(Asset.USD to BigDecimal("75"))

            SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = listOf(
                    SnapshotHistoryCalculator.TimelineEvent.TradeEvent(time, trade),
                    SnapshotHistoryCalculator.TimelineEvent.RewardEvent(time, untrackedDoge),
                ),
                allocations = listOf(Allocation(Asset.USD, 100.0)),
                runningBalances = running,
                currentPrices = mapOf(Asset.USD to BigDecimal.ONE),
                ohlcData = emptyMap(),
                tradePrices = emptyMap(),
                settings = defaultSettings,
                tradeLegsByRefId = legs,
            )

            running.getValue(Asset.USD) shouldBeEqualComparingTo BigDecimal("73")
            running.getValue("DOGE") shouldBeEqualComparingTo BigDecimal("3")
        }

        "uncheckpointed solver trade leg is seeded at zero before its inversion" {
            val time = Instant.parse("2026-07-11T12:00:00Z")
            val refid = "T4-TRADE-REFID"
            val trade = TestFixtures.tradeRecord(
                timestamp = time,
                pair = "SOLUSD",
                side = "sell",
                symbol = "SOL",
                volume = BigDecimal.TEN,
                usdAmount = BigDecimal("50"),
                price = BigDecimal("5"),
                tradeId = refid,
            )
            val legs = mapOf(
                refid to listOf(
                    // Base leg retained without an authoritative balance: inversion seeds SOL at zero.
                    historicalTradeLeg("L4-SOL-BASE", refid, time, "SOL", "-10", "0", authoritative = false),
                    historicalTradeLeg("L4-USD-QUOTE", refid, time, "USD", "50", "50"),
                ),
            )
            val running = mutableMapOf(Asset.USD to BigDecimal("50"))

            SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = listOf(
                    SnapshotHistoryCalculator.TimelineEvent.TradeEvent(time, trade),
                ),
                allocations = listOf(Allocation(Asset.USD, 100.0)),
                runningBalances = running,
                currentPrices = mapOf(Asset.USD to BigDecimal.ONE),
                ohlcData = emptyMap(),
                tradePrices = emptyMap(),
                settings = defaultSettings,
                tradeLegsByRefId = legs,
            )

            running.getValue(Asset.USD) shouldBeEqualComparingTo BigDecimal.ZERO
            running.getValue(Asset.SOL) shouldBeEqualComparingTo BigDecimal.TEN
        }

        "solver fails closed when no trade leg matches the boundary state" {
            val time = Instant.parse("2026-07-12T12:00:00Z")
            val refid = "T5-TRADE-REFID"
            val trade = TestFixtures.tradeRecord(
                timestamp = time,
                pair = "SOLUSD",
                side = "sell",
                symbol = "SOL",
                volume = BigDecimal.TEN,
                usdAmount = BigDecimal("50"),
                price = BigDecimal("5"),
                tradeId = refid,
            )
            val legs = mapOf(
                refid to listOf(
                    historicalTradeLeg("L5-SOL-BASE", refid, time, "SOL", "-10", "100"),
                    historicalTradeLeg("L5-USD-QUOTE", refid, time, "USD", "50", "50"),
                ),
            )
            val running = mutableMapOf(Asset.USD to BigDecimal("999"))

            shouldThrow<IllegalArgumentException> {
                SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                    events = listOf(
                        SnapshotHistoryCalculator.TimelineEvent.TradeEvent(time, trade),
                    ),
                    allocations = listOf(Allocation(Asset.USD, 100.0)),
                    runningBalances = running,
                    currentPrices = mapOf(Asset.USD to BigDecimal.ONE),
                    ohlcData = emptyMap(),
                    tradePrices = emptyMap(),
                    settings = defaultSettings,
                    tradeLegsByRefId = legs,
                )
            }.message shouldBe "Missing tracked balance during historical reconstruction for SOL"
        }

        "solver treats an unsupported trade as non-constraining and still throws at its walk position" {
            val time = Instant.parse("2026-07-13T12:00:00Z")
            val unparseableTrade = TestFixtures.tradeRecord(
                timestamp = time,
                pair = "NONSENSE",
                side = "sell",
                symbol = "SOL",
                volume = BigDecimal.TEN,
                usdAmount = BigDecimal("50"),
                price = BigDecimal("5"),
                tradeId = "T6-TRADE-REFID",
            )
            val running = mutableMapOf("USDG" to BigDecimal("100"), "XRP" to BigDecimal("100"))

            shouldThrow<IllegalArgumentException> {
                SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                    events = listOf(
                        SnapshotHistoryCalculator.TimelineEvent.RewardEvent(
                            time,
                            LedgerEvent(
                                ledgerId = "t6-usdg-reward",
                                time = time,
                                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                                asset = "USDG",
                                amount = BigDecimal.ONE,
                                balance = BigDecimal("100"),
                                hasAuthoritativeBalance = true,
                            ),
                        ),
                        SnapshotHistoryCalculator.TimelineEvent.RewardEvent(
                            time,
                            LedgerEvent(
                                ledgerId = "t6-xrp-reward",
                                time = time,
                                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                                asset = "XRP",
                                amount = BigDecimal.ONE,
                                balance = BigDecimal("100"),
                                hasAuthoritativeBalance = true,
                            ),
                        ),
                        SnapshotHistoryCalculator.TimelineEvent.TradeEvent(time, unparseableTrade),
                    ),
                    allocations = listOf(Allocation("USDG", 50.0), Allocation("XRP", 50.0)),
                    runningBalances = running,
                    currentPrices = mapOf("USDG" to BigDecimal.ONE, "XRP" to BigDecimal.ONE),
                    ohlcData = emptyMap(),
                    tradePrices = emptyMap(),
                    settings = defaultSettings,
                )
            }.message shouldBe "unsupported historical market NONSENSE"
        }

        "no-checkpoint rewards inside a solver group stay outside the ordering search" {
            val time = Instant.parse("2026-07-14T12:00:00Z")
            val noAuthReward = LedgerEvent(
                ledgerId = "t7-noauth",
                time = time,
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                asset = "SOL",
                amount = BigDecimal("2"),
                hasAuthoritativeBalance = false,
            )
            val zeroDeltaReward = LedgerEvent(
                ledgerId = "t7-zero-delta",
                time = time,
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                asset = "XRP",
                amount = BigDecimal.ZERO,
                balance = BigDecimal("10"),
                hasAuthoritativeBalance = true,
            )
            val running = mutableMapOf(
                Asset.SOL to BigDecimal("10"),
                Asset.XRP to BigDecimal("10"),
                "USDG" to BigDecimal("10"),
            )

            SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = listOf(
                    SnapshotHistoryCalculator.TimelineEvent.RewardEvent(time, noAuthReward),
                    SnapshotHistoryCalculator.TimelineEvent.RewardEvent(time, zeroDeltaReward),
                    SnapshotHistoryCalculator.TimelineEvent.RewardEvent(
                        time,
                        LedgerEvent(
                            ledgerId = "t7-usdg-reward",
                            time = time,
                            type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                            asset = "USDG",
                            amount = BigDecimal("3"),
                            balance = BigDecimal("10"),
                            hasAuthoritativeBalance = true,
                        ),
                    ),
                ),
                allocations = listOf(
                    Allocation(Asset.SOL, 40.0),
                    Allocation(Asset.XRP, 30.0),
                    Allocation("USDG", 30.0),
                ),
                runningBalances = running,
                currentPrices = mapOf(
                    Asset.SOL to BigDecimal.ONE,
                    Asset.XRP to BigDecimal.ONE,
                    "USDG" to BigDecimal.ONE,
                ),
                ohlcData = emptyMap(),
                tradePrices = emptyMap(),
                settings = defaultSettings,
            )

            running.getValue(Asset.SOL) shouldBeEqualComparingTo BigDecimal("8")
            running.getValue(Asset.XRP) shouldBeEqualComparingTo BigDecimal("10")
            running.getValue("USDG") shouldBeEqualComparingTo BigDecimal("7")
        }

        "solver sort key falls back to database id for missing or blank trade ids" {
            val time = Instant.parse("2026-07-14T12:00:00Z")
            val nullIdTrade = TestFixtures.tradeRecord(
                timestamp = time,
                pair = "SOLUSD",
                side = "sell",
                symbol = "SOL",
                volume = BigDecimal.TEN,
                usdAmount = BigDecimal("40"),
                price = BigDecimal("4"),
                id = 77,
                tradeId = null,
            )
            val blankIdTrade = TestFixtures.tradeRecord(
                timestamp = time,
                pair = "XRPUSD",
                side = "sell",
                symbol = "XRP",
                volume = BigDecimal("5"),
                usdAmount = BigDecimal("5"),
                price = BigDecimal.ONE,
                id = 81,
                tradeId = "   ",
            )
            val legs = mapOf(
                "db-id:77" to listOf(
                    historicalTradeLeg("L8-SOL-BASE", "T8-TRADE-REFID", time, "SOL", "-10", "100"),
                    historicalTradeLeg("L8-USD-A", "T8-TRADE-REFID", time, "USD", "40", "45"),
                ),
                "db-id:81" to listOf(
                    historicalTradeLeg("L8-XRP-BASE", "T8-XRP-REFID", time, "XRP", "-5", "85"),
                    historicalTradeLeg("L8-USD-B", "T8-XRP-REFID", time, "USD", "5", "50"),
                ),
            )
            val running = mutableMapOf(
                Asset.SOL to BigDecimal("100"),
                Asset.XRP to BigDecimal("85"),
                Asset.USD to BigDecimal("50"),
            )

            SnapshotHistoryCalculator.calculateHistoricalSnapshots(
                events = listOf(
                    SnapshotHistoryCalculator.TimelineEvent.TradeEvent(time, nullIdTrade),
                    SnapshotHistoryCalculator.TimelineEvent.TradeEvent(time, blankIdTrade),
                ),
                allocations = listOf(
                    Allocation(Asset.SOL, 40.0),
                    Allocation(Asset.XRP, 30.0),
                    Allocation(Asset.USD, 30.0),
                ),
                runningBalances = running,
                currentPrices = mapOf(
                    Asset.SOL to BigDecimal.ONE,
                    Asset.XRP to BigDecimal.ONE,
                    Asset.USD to BigDecimal.ONE,
                ),
                ohlcData = emptyMap(),
                tradePrices = emptyMap(),
                settings = defaultSettings,
                tradeLegsByTradeIdentity = legs,
            )

            running.getValue(Asset.SOL) shouldBeEqualComparingTo BigDecimal("110")
            running.getValue(Asset.XRP) shouldBeEqualComparingTo BigDecimal("90")
            running.getValue(Asset.USD) shouldBeEqualComparingTo BigDecimal("5")
        }
    }
}

private fun historicalTradeLeg(
    ledgerId: String,
    refid: String,
    time: Instant,
    asset: String,
    amount: String,
    balance: String,
    fee: String = "0",
    authoritative: Boolean = true,
): LedgerEvent = LedgerEvent(
    ledgerId = ledgerId,
    refid = refid,
    time = time,
    type = KrakenApiConstants.LEDGER_TYPE_TRADE,
    asset = asset,
    amount = BigDecimal(amount),
    fee = BigDecimal(fee),
    balance = BigDecimal(balance),
    hasAuthoritativeBalance = authoritative,
)

private fun solUsdSnapshotSignature(snapshots: List<PortfolioSnapshot>): List<String> = snapshots.map { snapshot ->
    val sol = snapshot.assets.getValue(Asset.SOL).balance
    val usd = snapshot.assets.getValue(Asset.USD).balance
    "${snapshot.timestamp.toEpochMilli()}|${sol.toPlainString()}|${usd.toPlainString()}"
}
