package com.gemini.krakenbot.repository

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeSource
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

class SqliteTradeRepositoryImplTest : SqliteTradeRepositoryTestBase() {

    init {
        "save and load snapshots" {
            runTest {
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val snapshot =
                    PortfolioSnapshot(
                        timestamp = now,
                        totalValueUSD = BigDecimal("1000.50"),
                        assets =
                        mapOf(
                            Asset.BTC to
                                TestFixtures.assetSnapshot(
                                    symbol = Asset.BTC,
                                    balance = BigDecimal("0.5"),
                                    price = BigDecimal("18000.00"),
                                    valueUSD = BigDecimal("900.00"),
                                    targetPercent = BigDecimal("90.0"),
                                ),
                            TestFixtures.USD to
                                TestFixtures.assetSnapshot(
                                    symbol = TestFixtures.USD,
                                    balance = BigDecimal("100.50"),
                                    price = BigDecimal.ONE,
                                    valueUSD = BigDecimal("100.50"),
                                    targetPercent = BigDecimal("10.0"),
                                ),
                        ),
                        actions = listOf("Action 1", "Action 2"),
                        drawdownPercent = BigDecimal("1.25"),
                        fiatDeploymentPercent = BigDecimal("10.0"),
                        effectiveUsdTargetPercent = BigDecimal("10.0"),
                    )

                repository.saveSnapshot(snapshot)

                val loaded = repository.load()
                loaded.size shouldBe 1
                val first = loaded.first()
                first.timestamp shouldBe now
                first.totalValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.50"))
                first.drawdownPercent.shouldBeEqualComparingTo(BigDecimal("1.25"))
                first.fiatDeploymentPercent.shouldBeEqualComparingTo(BigDecimal("10.0"))
                first.effectiveUsdTargetPercent.shouldBeEqualComparingTo(BigDecimal("10.0"))
                first.actions shouldBe listOf("Action 1", "Action 2")

                val btc = first.assets[Asset.BTC]!!
                btc.symbol.value shouldBe Asset.BTC
                btc.balance.shouldBeEqualComparingTo(BigDecimal("0.5"))
                btc.price.shouldBeEqualComparingTo(BigDecimal("18000.00"))
                btc.valueUSD.shouldBeEqualComparingTo(BigDecimal("900.00"))
            }
        }

        "save trade and queries" {
            runTest {
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val trade1 =
                    TestFixtures.tradeRecord(
                        timestamp = now.minusSeconds(10),
                        pair = Asset.BTC_USD_PAIR,
                        side = OrderSide.BUY.name,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("5000.00"),
                        fee = BigDecimal("15.50"),
                    )
                val trade2 =
                    TestFixtures.tradeRecord(
                        timestamp = now,
                        pair = TestFixtures.ETHUSD,
                        side = OrderSide.SELL.name,
                        symbol = Asset.ETH,
                        volume = BigDecimal("1.0"),
                        usdAmount = BigDecimal("2000.00"),
                        dryRun = true,
                        fee = BigDecimal("5.25"),
                    )
                val failedTrade =
                    TestFixtures.tradeRecord(
                        timestamp = now.plusSeconds(10),
                        pair = TestFixtures.DOGEUSD,
                        side = OrderSide.BUY.name,
                        symbol = Asset.DOGE,
                        volume = BigDecimal("100.0"),
                        usdAmount = BigDecimal("10.00"),
                        success = false,
                        errorMessage = "API Error",
                        fee = BigDecimal("1.50"),
                    )

                repository.saveTrade(trade1)
                repository.saveTrade(trade2)
                repository.saveTrade(failedTrade)

                val stats = repository.getTradeSummaryStats()
                stats.totalTradesExecuted shouldBe 1L
                stats.totalVolumeTraded.shouldBeEqualComparingTo(BigDecimal("5000.00"))
                stats.totalFeesPaid.shouldBeEqualComparingTo(BigDecimal("15.50"))

                val trades = repository.getTradesInRange(now.minusSeconds(20), now.plusSeconds(20))
                trades.size shouldBe 3
                trades[0].pair shouldBe TestFixtures.DOGEUSD
                trades[0].success shouldBe false
                trades[0].errorMessage shouldBe "API Error"
                trades[1].pair shouldBe TestFixtures.ETHUSD
                trades[1].dryRun shouldBe true
                trades[2].pair shouldBe TestFixtures.XBTUSD
            }
        }

        "getTradeSummaryStats with time range" {
            runTest {
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val trade1 =
                    TestFixtures.tradeRecord(
                        timestamp = now.minus(10, ChronoUnit.DAYS),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("5000.00"),
                        fee = BigDecimal("15.50"),
                    )
                val trade2 =
                    TestFixtures.tradeRecord(
                        timestamp = now.minus(2, ChronoUnit.DAYS),
                        pair = TestFixtures.ETHUSD,
                        side = TestFixtures.SELL,
                        symbol = TestFixtures.ETH,
                        volume = BigDecimal("1.0"),
                        usdAmount = BigDecimal("2000.00"),
                        dryRun = true,
                        fee = BigDecimal("5.25"),
                    )

                repository.saveTrade(trade1)
                repository.saveTrade(trade2)

                val s1 = TestFixtures.emptySnapshot(now.minus(2, ChronoUnit.DAYS), BigDecimal("15000.00"))
                repository.saveSnapshot(s1)
                repository.saveSnapshot(
                    s1.copy(
                        timestamp = now.plus(1, ChronoUnit.DAYS),
                        totalValueUSD = BigDecimal("20000.00"),
                    ),
                )

                val rangeStats = repository.getTradeSummaryStats(from = now.minus(3, ChronoUnit.DAYS), to = now)
                rangeStats.totalTradesExecuted shouldBe 0L
                rangeStats.totalVolumeTraded.shouldBeEqualComparingTo(BigDecimal.ZERO)
                rangeStats.totalFeesPaid.shouldBeEqualComparingTo(BigDecimal.ZERO)
                rangeStats.periodHigh?.shouldBeEqualComparingTo(BigDecimal("15000.00"))
                rangeStats.latestSnapshotTime shouldBe s1.timestamp
            }
        }

        "getSnapshotsInRange and boundary times" {
            runTest {
                val baseTime = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val s1 = TestFixtures.emptySnapshot(baseTime.minusSeconds(10), BigDecimal("1000.00"))
                val s2 = TestFixtures.emptySnapshot(baseTime, BigDecimal("2000.00"))

                repository.saveSnapshot(s1)
                repository.saveSnapshot(s2)

                repository.getTradeSummaryStats().latestSnapshotTime shouldBe baseTime

                val inRange = repository.getSnapshotsInRange(baseTime.minusSeconds(5), baseTime.plusSeconds(5))
                inRange.size shouldBe 1
                inRange.first().totalValueUSD.shouldBeEqualComparingTo(BigDecimal("2000.00"))
            }
        }

        "legacy save saves snapshots" {
            runTest {
                val snapshot = TestFixtures.emptySnapshot(Instant.now(), BigDecimal.ZERO)
                repository.save(listOf(snapshot))
                repository.load().size shouldBe 1
            }
        }

        "replace snapshots replaces history without deleting trades" {
            runTest {
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val original = TestFixtures.emptySnapshot(now.minusSeconds(10), BigDecimal("1000.00"))
                val replacement = TestFixtures.emptySnapshot(now, BigDecimal("2000.00"))
                val trade = TestFixtures.tradeRecord(
                    timestamp = now,
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.01"),
                    usdAmount = BigDecimal("600.00"),
                )

                repository.saveSnapshot(original)
                repository.saveTrade(trade)
                repository.replaceSnapshots(listOf(replacement))

                repository.load().map { it.totalValueUSD } shouldBe listOf(BigDecimal("2000.00"))
                repository.getTradesInRange(now.minusSeconds(1), now.plusSeconds(1)).size shouldBe 1
            }
        }

        "getLatestTradeTime with empty and populated trades" {
            runTest {
                repository.getLatestTradeTime() shouldBe null

                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val trade1 =
                    TestFixtures.tradeRecord(
                        timestamp = now.minusSeconds(10),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("5000.00"),
                    )
                val trade2 =
                    TestFixtures.tradeRecord(
                        timestamp = now,
                        pair = TestFixtures.ETHUSD,
                        side = TestFixtures.SELL,
                        symbol = TestFixtures.ETH,
                        volume = BigDecimal("1.0"),
                        usdAmount = BigDecimal("2000.00"),
                    )
                repository.saveTrade(trade1)
                repository.saveTrade(trade2)

                repository.getLatestTradeTime() shouldBe now
            }
        }

        "getLatestTradeTime ignores newer dry-run rows" {
            runTest {
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val liveTrade =
                    TestFixtures.tradeRecord(
                        timestamp = now,
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("5000.00"),
                    )
                val dryRunTrade =
                    TestFixtures.tradeRecord(
                        timestamp = now.plusSeconds(60),
                        pair = TestFixtures.ETHUSD,
                        side = TestFixtures.SELL,
                        symbol = TestFixtures.ETH,
                        volume = BigDecimal("1.0"),
                        usdAmount = BigDecimal("2000.00"),
                        dryRun = true,
                    )
                repository.saveTrade(liveTrade)
                repository.saveTrade(dryRunTrade)

                repository.getLatestTradeTime() shouldBe now
            }
        }

        "CQ-10-L1: getLatestTradeTime ignores newer failed live attempts" {
            runTest {
                val fillTime = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                repository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = fillTime,
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("5000.00"),
                        source = TradeSource.API_FILL,
                    ),
                )
                repository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = fillTime.plusSeconds(60),
                        pair = TestFixtures.ETHUSD,
                        side = TestFixtures.SELL,
                        symbol = TestFixtures.ETH,
                        volume = BigDecimal.ONE,
                        usdAmount = BigDecimal("2000.00"),
                        success = false,
                        errorMessage = "Order rejected",
                        source = TradeSource.LOCAL_ESTIMATE,
                    ),
                )

                repository.getLatestTradeTime() shouldBe fillTime
            }
        }

        "update trade updates record" {
            runTest {
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val oldTrade =
                    TestFixtures.tradeRecord(
                        timestamp = now,
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.5"),
                        usdAmount = BigDecimal("15000.00"),
                    )
                repository.saveTrade(oldTrade)

                val newTrade =
                    oldTrade.copy(
                        timestamp = now.plusSeconds(3),
                        pair = TestFixtures.XXBTZUSD,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.49980000"),
                        usdAmount = BigDecimal("14980.50"),
                        price = BigDecimal("29972.00"),
                        fee = BigDecimal("38.95"),
                    )
                repository.updateTrade(oldTrade, newTrade)

                val trades = repository.getTradesInRange(now.minusSeconds(10), now.plusSeconds(10))
                trades.size shouldBe 1
                trades.first().timestamp shouldBe now.plusSeconds(3)
                trades.first().pair shouldBe TestFixtures.XXBTZUSD
                trades.first().volume.shouldBeEqualComparingTo(BigDecimal("0.49980000"))
                trades.first().usdAmount.shouldBeEqualComparingTo(BigDecimal("14980.50"))
                trades.first().price.shouldBeEqualComparingTo(BigDecimal("29972.00"))
                trades.first().fee.shouldBeEqualComparingTo(BigDecimal("38.95"))
            }
        }

        "cleanupDuplicateTrades removes a local estimate but preserves a distinct nearby order" {
            runTest {
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val krakenFill =
                    TestFixtures.tradeRecord(
                        timestamp = now.minusMillis(500),
                        pair = "TAOUSD",
                        side = TestFixtures.SELL,
                        symbol = "TAO",
                        volume = BigDecimal("0.07708233"),
                        usdAmount = BigDecimal("16.62393026"),
                        price = BigDecimal("215.66460511"),
                        fee = BigDecimal("0.0432"),
                        source = TradeSource.API_FILL,
                    )
                val localEstimate =
                    krakenFill.copy(
                        timestamp = now,
                        volume = BigDecimal("0.07708000"),
                        usdAmount = BigDecimal("16.63"),
                        price = BigDecimal("215.6867"),
                        fee = BigDecimal("0.0998"),
                        slippagePercent = BigDecimal.ZERO,
                        source = TradeSource.LOCAL_ESTIMATE,
                    )
                val distinctOrder =
                    krakenFill.copy(
                        timestamp = now.plusSeconds(1),
                        volume = BigDecimal("0.07000000"),
                        usdAmount = BigDecimal("15.10"),
                    )

                repository.saveTrade(krakenFill)
                repository.saveTrade(localEstimate)
                repository.saveTrade(distinctOrder)

                repository.cleanupDuplicateTrades()

                val trades = repository.getTradesInRange(now.minusSeconds(10), now.plusSeconds(10))
                trades.size shouldBe 2
                trades.any { it.timestamp == krakenFill.timestamp } shouldBe true
                trades.any { it.timestamp == localEstimate.timestamp } shouldBe false
                trades.any { it.timestamp == distinctOrder.timestamp } shouldBe true
            }
        }

        "CQ-14-L4: repository cleanup preserves conflicting trade identity" {
            runTest {
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val base = TestFixtures.tradeRecord(
                    timestamp = now,
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal("50000.00"),
                    price = BigDecimal("50000.00"),
                    fee = BigDecimal("100.00"),
                )
                val validLocalEstimate = base.copy(
                    source = TradeSource.LOCAL_ESTIMATE,
                    slippagePercent = BigDecimal.ZERO,
                    orderTxid = "VALID-ORDER",
                )
                val validApiFill = base.copy(
                    timestamp = now.plusMillis(100),
                    source = TradeSource.API_FILL,
                    fee = BigDecimal("300.00"),
                    orderTxid = "VALID-ORDER",
                    tradeId = "VALID-TRADE",
                )
                val conflictingLocalApiOrderIds = listOf(
                    base.copy(
                        timestamp = now.plusSeconds(3_600),
                        source = TradeSource.LOCAL_ESTIMATE,
                        fee = BigDecimal("10.00"),
                        slippagePercent = BigDecimal.ZERO,
                        orderTxid = "LOCAL-CONFLICTING-ORDER",
                    ),
                    base.copy(
                        timestamp = now.plusSeconds(3_600).plusMillis(100),
                        source = TradeSource.API_FILL,
                        fee = BigDecimal("300.00"),
                        orderTxid = "API-CONFLICTING-ORDER",
                    ),
                )
                val conflictingLocalApiTradeIds = listOf(
                    base.copy(
                        timestamp = now.plusSeconds(4_200),
                        source = TradeSource.LOCAL_ESTIMATE,
                        fee = BigDecimal("10.00"),
                        slippagePercent = BigDecimal.ZERO,
                        tradeId = "LOCAL-CONFLICTING-TRADE",
                    ),
                    base.copy(
                        timestamp = now.plusSeconds(4_200).plusMillis(100),
                        source = TradeSource.API_FILL,
                        fee = BigDecimal("300.00"),
                        tradeId = "API-CONFLICTING-TRADE",
                    ),
                )
                val conflictingOrderTxids = listOf(
                    base.copy(
                        timestamp = now.plusSeconds(600),
                        source = TradeSource.API_FILL,
                        orderTxid = "CONFLICTING-ORDER-ONE",
                    ),
                    base.copy(
                        timestamp = now.plusSeconds(600).plusMillis(100),
                        pair = TestFixtures.XXBTZUSD,
                        source = TradeSource.API_FILL,
                        orderTxid = "CONFLICTING-ORDER-TWO",
                    ),
                )
                val conflictingTradeIds = listOf(
                    base.copy(
                        timestamp = now.plusSeconds(1_200),
                        source = TradeSource.API_FILL,
                        tradeId = "CONFLICTING-TRADE-ONE",
                    ),
                    base.copy(
                        timestamp = now.plusSeconds(1_200).plusMillis(100),
                        pair = TestFixtures.XXBTZUSD,
                        source = TradeSource.API_FILL,
                        tradeId = "CONFLICTING-TRADE-TWO",
                    ),
                )
                val differentStatus = listOf(
                    base.copy(
                        timestamp = now.plusSeconds(1_800),
                        source = TradeSource.LOCAL_ESTIMATE,
                        success = false,
                        slippagePercent = BigDecimal.ZERO,
                    ),
                    base.copy(
                        timestamp = now.plusSeconds(1_800).plusMillis(100),
                        source = TradeSource.API_FILL,
                    ),
                )
                val differentDryRunStatus = listOf(
                    base.copy(
                        timestamp = now.plusSeconds(2_400),
                        source = TradeSource.LOCAL_ESTIMATE,
                        dryRun = true,
                        slippagePercent = BigDecimal.ZERO,
                    ),
                    base.copy(
                        timestamp = now.plusSeconds(2_400).plusMillis(100),
                        source = TradeSource.API_FILL,
                    ),
                )
                val distinctProvenance = listOf(
                    base.copy(
                        timestamp = now.plusSeconds(3_000),
                        source = TradeSource.LEGACY_UNKNOWN,
                    ),
                    base.copy(
                        timestamp = now.plusSeconds(3_000).plusMillis(100),
                        pair = TestFixtures.XXBTZUSD,
                        source = TradeSource.API_FILL,
                    ),
                )

                listOf(validLocalEstimate, validApiFill)
                    .asSequence()
                    .plus(conflictingLocalApiOrderIds)
                    .plus(conflictingLocalApiTradeIds)
                    .plus(conflictingOrderTxids)
                    .plus(conflictingTradeIds)
                    .plus(differentStatus)
                    .plus(differentDryRunStatus)
                    .plus(distinctProvenance)
                    .toList()
                    .forEach { repository.saveTrade(it) }

                repository.cleanupDuplicateTrades()

                val remaining = repository.getTradesInRange(now.minusSeconds(1), now.plusSeconds(4_201))
                remaining.size shouldBe 15
                remaining.any {
                    it.source == TradeSource.LOCAL_ESTIMATE && it.orderTxid == "VALID-ORDER"
                } shouldBe false
                remaining.any { it.tradeId == "VALID-TRADE" } shouldBe true
                remaining.any { it.orderTxid == "LOCAL-CONFLICTING-ORDER" } shouldBe true
                remaining.any { it.orderTxid == "API-CONFLICTING-ORDER" } shouldBe true
                remaining.any { it.tradeId == "LOCAL-CONFLICTING-TRADE" } shouldBe true
                remaining.any { it.tradeId == "API-CONFLICTING-TRADE" } shouldBe true
                remaining.any { it.orderTxid == "CONFLICTING-ORDER-ONE" } shouldBe true
                remaining.any { it.orderTxid == "CONFLICTING-ORDER-TWO" } shouldBe true
                remaining.any { it.tradeId == "CONFLICTING-TRADE-ONE" } shouldBe true
                remaining.any { it.tradeId == "CONFLICTING-TRADE-TWO" } shouldBe true
                remaining.count { !it.success } shouldBe 1
                remaining.count { it.dryRun } shouldBe 1
                remaining.count { it.source == TradeSource.LEGACY_UNKNOWN } shouldBe 1
            }
        }

        "cleanupDuplicateTrades exercises all duplicate scenarios and branches" {
            runTest {
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)

                val t1 =
                    TestFixtures.tradeRecord(
                        timestamp = now,
                        pair = TestFixtures.BTCUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("1.0"),
                        usdAmount = BigDecimal("60000.0"),
                        price = BigDecimal("60000.0"),
                        fee = BigDecimal("100.0"),
                    )
                // The outer scan spans five minutes even though estimate/API matching has its own
                // ten-second constraint; this record exercises the sorted-scan cutoff.
                val t2FarFuture =
                    t1.copy(
                        timestamp = now.plusMillis(300_001),
                        volume = BigDecimal("1.0"),
                    )
                repository.saveTrade(t1)
                repository.saveTrade(t2FarFuture)

                val tDifferentSymbol =
                    t1.copy(
                        timestamp = now.plusMillis(100),
                        symbol = TestFixtures.ETH,
                        pair = TestFixtures.ETHUSD,
                    )
                repository.saveTrade(tDifferentSymbol)

                val tDifferentSide =
                    t1.copy(
                        timestamp = now.plusMillis(200),
                        side = TestFixtures.SELL,
                    )
                repository.saveTrade(tDifferentSide)

                val tPairAlias1 =
                    t1.copy(
                        timestamp = now.plusMillis(300),
                        pair = TestFixtures.XBTUSD,
                    )
                val tPairAlias2 =
                    t1.copy(
                        timestamp = now.plusMillis(400),
                        pair = TestFixtures.XXBTZUSD,
                    )
                repository.saveTrade(tPairAlias1)
                repository.saveTrade(tPairAlias2)

                val tVolDiffers =
                    t1.copy(
                        timestamp = now.plusMillis(500),
                        volume = BigDecimal("1.5"),
                        usdAmount = BigDecimal("90000.0"),
                    )
                repository.saveTrade(tVolDiffers)

                val tFeeRate1 =
                    t1.copy(
                        timestamp = now.plusMillis(600),
                        volume = BigDecimal("1.0"),
                        usdAmount = BigDecimal("1000.0"),
                        fee = BigDecimal("1.0"),
                    )
                val tFeeRate2 =
                    t1.copy(
                        timestamp = now.plusMillis(700),
                        volume = BigDecimal("1.0"),
                        usdAmount = BigDecimal("1000.0"),
                        fee = BigDecimal("1.0001"),
                    )
                repository.saveTrade(tFeeRate1)
                repository.saveTrade(tFeeRate2)

                val tZeroVolume1 =
                    t1.copy(
                        timestamp = now.plusMillis(800),
                        volume = BigDecimal.ZERO,
                        usdAmount = BigDecimal.ZERO,
                        fee = BigDecimal.ZERO,
                    )
                val tZeroVolume2 =
                    t1.copy(
                        timestamp = now.plusMillis(900),
                        volume = BigDecimal.ZERO,
                        usdAmount = BigDecimal.ZERO,
                        fee = BigDecimal.ZERO,
                    )
                repository.saveTrade(tZeroVolume1)
                repository.saveTrade(tZeroVolume2)

                repository.cleanupDuplicateTrades()

                val all = repository.getTradesInRange(now.minusSeconds(1), now.plusSeconds(3600))
                all.size shouldBe 9
                all.any { it.timestamp == t2FarFuture.timestamp } shouldBe true
                all.any { it.timestamp == tPairAlias1.timestamp } shouldBe false
                all.any { it.timestamp == tPairAlias2.timestamp } shouldBe false
            }
        }

        "isHistorySeeded and setHistorySeeded" {
            runTest {
                repository.isHistorySeeded() shouldBe false
                repository.setHistorySeeded(true)
                repository.isHistorySeeded() shouldBe true
                repository.setHistorySeeded(false)
                repository.isHistorySeeded() shouldBe false
            }
        }

        "getTradeSummaryStats returns zero/null values when no data exists" {
            runTest {
                val stats = repository.getTradeSummaryStats()
                stats.latestSnapshotTime shouldBe null
                stats.totalVolumeTraded.shouldBeEqualComparingTo(BigDecimal.ZERO)
                stats.totalFeesPaid.shouldBeEqualComparingTo(BigDecimal.ZERO)
                stats.totalTradesExecuted shouldBe 0L
                stats.avgFeeRatePercent.shouldBeEqualComparingTo(BigDecimal.ZERO)
                stats.avgSlippagePercent shouldBe null
                stats.failedTradeCount shouldBe 0L
                stats.dryRunTradeCount shouldBe 0L
            }
        }

        "save trade round trips expectedPrice source and Kraken trade id" {
            runTest {
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val trade =
                    TestFixtures.tradeRecord(
                        timestamp = now,
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("5000.00"),
                        price = BigDecimal("50000.00"),
                        fee = BigDecimal("13.0000"),
                        slippagePercent = BigDecimal("0.2500"),
                        expectedPrice = BigDecimal("49875.00"),
                        source = TradeSource.LOCAL_ESTIMATE,
                        tradeId = "TRADE-ROUND-TRIP",
                    )
                repository.saveTrade(trade)

                val loaded = repository.getTradesInRange(now.minusSeconds(1), now.plusSeconds(1)).single()
                loaded.expectedPrice!!.shouldBeEqualComparingTo(BigDecimal("49875.00"))
                loaded.source shouldBe TradeSource.LOCAL_ESTIMATE
                loaded.slippagePercent!!.shouldBeEqualComparingTo(BigDecimal("0.2500"))
                loaded.tradeId shouldBe "TRADE-ROUND-TRIP"
            }
        }

        "getTradeSummaryStats aggregates fee rate slippage and status counts" {
            runTest {
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                repository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = now,
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal.ONE,
                        usdAmount = BigDecimal("1000.00"),
                        fee = BigDecimal("2.6000"),
                        slippagePercent = BigDecimal("0.1000"),
                        source = TradeSource.API_FILL,
                    ),
                )
                repository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = now.plusSeconds(1),
                        pair = TestFixtures.ETHUSD,
                        side = TestFixtures.SELL,
                        symbol = Asset.ETH,
                        volume = BigDecimal.ONE,
                        usdAmount = BigDecimal("500.00"),
                        dryRun = true,
                        fee = BigDecimal("1.3000"),
                        slippagePercent = BigDecimal("0.2000"),
                    ),
                )
                repository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = now.plusSeconds(2),
                        pair = TestFixtures.DOGEUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.DOGE,
                        volume = BigDecimal.TEN,
                        usdAmount = BigDecimal("10.00"),
                        success = false,
                        fee = BigDecimal.ZERO,
                    ),
                )

                val stats = repository.getTradeSummaryStats(now.minusSeconds(5), now.plusSeconds(5))
                stats.totalTradesExecuted shouldBe 1L
                stats.totalVolumeTraded.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                stats.totalFeesPaid.shouldBeEqualComparingTo(BigDecimal("2.6000"))
                stats.avgFeeRatePercent.shouldBeEqualComparingTo(BigDecimal("0.2600"))
                stats.avgSlippagePercent!!.shouldBeEqualComparingTo(BigDecimal("0.1000"))
                stats.failedTradeCount shouldBe 1L
                stats.dryRunTradeCount shouldBe 1L
            }
        }
    }
}
