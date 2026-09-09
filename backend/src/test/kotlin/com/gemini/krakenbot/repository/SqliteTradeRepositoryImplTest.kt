package com.gemini.krakenbot.repository

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.InceptionCandidateEvidence
import com.gemini.krakenbot.model.InceptionInferenceEvidence
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeSource
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldBeNull
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

        "getLatestSnapshot returns only the newest snapshot" {
            runTest {
                val baseTime = Instant.parse("2033-05-01T12:00:00Z")
                repository.saveSnapshot(TestFixtures.emptySnapshot(baseTime, BigDecimal("1000.00")))
                val newest = TestFixtures.emptySnapshot(baseTime.plusSeconds(10), BigDecimal("2000.00")).copy(
                    actions = listOf("Newest action"),
                )
                repository.saveSnapshot(newest)

                val latest = repository.getLatestSnapshot()

                latest?.timestamp shouldBe newest.timestamp
                latest?.totalValueUSD?.shouldBeEqualComparingTo(BigDecimal("2000.00"))
                latest?.actions shouldBe listOf("Newest action")
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

        "getAllSnapshotsInRange returns every retained snapshot beyond the chart limit" {
            runTest {
                val baseTime = Instant.parse("2033-06-01T00:00:00Z")
                val snapshots = (0..500).map { index ->
                    TestFixtures.emptySnapshot(
                        timestamp = baseTime.plusSeconds(index.toLong()),
                        totalValueUSD = BigDecimal(index + 1),
                    ).copy(
                        actions = if (index == 499 || index == 500) listOf("boundary-$index") else emptyList(),
                    )
                }
                repository.save(snapshots)

                repository.getSnapshotsInRange(baseTime, baseTime.plusSeconds(500)).size shouldBe 300
                val all = repository.getAllSnapshotsInRange(baseTime, baseTime.plusSeconds(500))

                all.size shouldBe snapshots.size
                all.first().timestamp shouldBe baseTime
                all.last().timestamp shouldBe baseTime.plusSeconds(500)
                all[499].actions shouldBe listOf("boundary-499")
                all[500].actions shouldBe listOf("boundary-500")
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
                        tradeId = "same-fill",
                    )
                val tPairAlias2 =
                    t1.copy(
                        timestamp = now.plusMillis(400),
                        pair = TestFixtures.XXBTZUSD,
                        tradeId = "same-fill",
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
                all.size shouldBe 10
                all.any { it.timestamp == t2FarFuture.timestamp } shouldBe true
                all.any { it.timestamp == tPairAlias1.timestamp } shouldBe true
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

        "getSnapshotBefore returns newest snapshot strictly before timestamp" {
            runTest {
                val t0 = Instant.parse("2026-07-01T10:00:00Z")
                val t1 = Instant.parse("2026-07-01T11:00:00Z")
                val t2 = Instant.parse("2026-07-01T12:00:00Z")

                val s0 = TestFixtures.emptySnapshot(t0, BigDecimal("1000.00"))
                val s1 = TestFixtures.emptySnapshot(t1, BigDecimal("1100.00"))
                val s2 = TestFixtures.emptySnapshot(t2, BigDecimal("1200.00"))

                repository.saveSnapshot(s0)
                val firstTimestampId = repository.saveSnapshot(s1)
                repository.saveSnapshot(s2)
                val duplicateFirstId = repository.saveSnapshot(
                    TestFixtures.emptySnapshot(t1, BigDecimal("1110.00")),
                )
                val duplicateSecondId = repository.saveSnapshot(
                    TestFixtures.emptySnapshot(t1, BigDecimal("1120.00")),
                )

                repository.getSnapshotBefore(t0) shouldBe null
                repository.getSnapshotBefore(t1)?.timestamp shouldBe t0
                repository.getSnapshotBefore(t2)?.totalValueUSD shouldBe BigDecimal("1120.00")
                repository.getSnapshotBefore(t2.plusSeconds(3600))?.timestamp shouldBe t2
                repository.getSnapshotId(t1) shouldBe firstTimestampId
                repository.getSnapshotId(t1, 1) shouldBe duplicateFirstId
                repository.getSnapshotId(t1, 2) shouldBe duplicateSecondId
                repository.getSnapshotId(t1, 3) shouldBe null
                repository.getSnapshotId(t1, -1) shouldBe null
            }
        }

        "persists an inference metadata revision" {
            runTest {
                repository.setSyncMetadataAtomically(
                    mapOf(
                        "inference-version" to "1",
                        "inference-start" to "1766378880000",
                        "inference-window-end" to "1766378913000",
                    ),
                )

                repository.getSyncMetadata("inference-version") shouldBe "1"
                repository.getSyncMetadata("inference-start") shouldBe "1766378880000"
                repository.getSyncMetadata("inference-window-end") shouldBe "1766378913000"
            }
        }

        "atomic metadata write treats an empty batch as a no-op" {
            runTest {
                repository.setSyncMetadataAtomically(emptyMap())

                repository.getSyncMetadata("inference-empty-batch-key").shouldBeNull()
            }
        }

        "retention floor metadata never moves later or becomes invalid" {
            runTest {
                val key = SyncMetadataKeys.INCEPTION_RETENTION_FLOOR_EPOCH_MS
                repository.setSyncMetadata(key, "1000")
                repository.setSyncMetadata(key, "2000")

                repository.getSyncMetadata(key) shouldBe "1000"

                repository.setSyncMetadata(key, "not-a-floor")

                repository.getSyncMetadata(key) shouldBe "1000"
            }
        }

        "retention floor metadata rejects invalid and future values without an existing floor" {
            runTest {
                val key = SyncMetadataKeys.INCEPTION_RETENTION_FLOOR_EPOCH_MS

                repository.setSyncMetadata(key, "not-a-floor")
                repository.getSyncMetadata(key).shouldBeNull()

                repository.setSyncMetadata(key, Instant.now().plusSeconds(86_400).toEpochMilli().toString())
                repository.getSyncMetadata(key).shouldBeNull()
            }
        }

        "persists and reloads inception inference evidence with candidates deterministically" {
            runTest {
                val observedStart = Instant.parse("2025-12-22T02:08:00Z")
                val observedEnd = observedStart.plusSeconds(33)
                val evidence = InceptionInferenceEvidence(
                    fingerprint = "fingerprint-a",
                    evidenceDigest = "digest-a",
                    modelVersion = "1",
                    coverageStart = observedStart.minusSeconds(3_600),
                    coverageEnd = observedEnd,
                    horizon = observedEnd.plusSeconds(60),
                    firstPositive = observedEnd.plusSeconds(120),
                    inferredStart = observedStart,
                    inferredWindowStart = observedStart,
                    inferredWindowEnd = observedEnd,
                    strongestObservedStart = observedEnd,
                    inferredStartStrength = "LOW",
                    inferredStartReasons = listOf("PURCHASE_ONLY_EPISODE"),
                    inferredStartContradictions = emptyList(),
                    strongestEpisodeStrength = "HIGH",
                    strongestEpisodeReasons = listOf("MULTI_ASSET_EPISODE", "REDISTRIBUTION_SELL_THEN_BUY"),
                    strongestEpisodeContradictions = listOf("EARLIER_ACTIVITY_IN_WINDOW"),
                    earliestAmbiguousStart = observedStart.minusSeconds(3_600),
                    earlierAmbiguousCandidateCount = 2,
                    unsupportedMarketCount = 2,
                    unsupportedMarketSamples = listOf("ADAUSDT", "XYZUSDT"),
                    competingCandidateCount = 1,
                    candidates = listOf(
                        InceptionCandidateEvidence(
                            observedStart = observedStart,
                            observedEnd = observedEnd,
                            windowStart = observedStart,
                            windowEnd = observedEnd,
                            strength = "HIGH",
                            reasons = listOf("REDISTRIBUTION_SELL_THEN_BUY"),
                            contradictions = emptyList(),
                            assetCount = 4,
                            assetSymbols = listOf("ASSET1", "ASSET2"),
                            orderCount = 12,
                            repeatedEvidenceCount = 2,
                            timescalesSeconds = setOf(5L, 15L),
                        ),
                        InceptionCandidateEvidence(
                            observedStart = observedStart.plusSeconds(600),
                            observedEnd = observedStart.plusSeconds(605),
                            windowStart = observedStart,
                            windowEnd = observedStart.plusSeconds(605),
                            strength = "LOW",
                            reasons = listOf("PURCHASE_ONLY_EPISODE"),
                            contradictions = listOf("EARLIER_ACTIVITY_IN_WINDOW"),
                            assetCount = 2,
                            assetSymbols = listOf("BTC", "ETH"),
                            orderCount = 2,
                            repeatedEvidenceCount = 1,
                            timescalesSeconds = setOf(5L),
                        ),
                    ),
                )

                repository.saveInceptionInferenceEvidence(evidence, mapOf("inception_inference_version" to "1"))

                val reloaded = repository.findInceptionInferenceEvidence("fingerprint-a")
                requireNotNull(reloaded)
                reloaded shouldBe evidence
                repository.getSyncMetadata("inception_inference_version") shouldBe "1"

                val replaced = evidence.copy(
                    evidenceDigest = "digest-b",
                    competingCandidateCount = 3,
                    candidates = evidence.candidates.take(1),
                )
                repository.saveInceptionInferenceEvidence(replaced)
                val afterReplace = repository.findInceptionInferenceEvidence("fingerprint-a")
                requireNotNull(afterReplace)
                afterReplace.evidenceDigest shouldBe "digest-b"
                afterReplace.competingCandidateCount shouldBe 3
                afterReplace.candidates.size shouldBe 1
                afterReplace.candidates.first().observedStart shouldBe observedStart
            }
        }

        "persists first positive without candidates and candidates without first positive" {
            runTest {
                val start = Instant.parse("2025-12-22T02:08:00Z")
                val firstPositiveOnly = InceptionInferenceEvidence(
                    fingerprint = "fingerprint-solo",
                    evidenceDigest = "digest-solo",
                    modelVersion = "1",
                    coverageStart = null,
                    coverageEnd = null,
                    horizon = null,
                    firstPositive = start,
                    inferredStart = null,
                    inferredWindowStart = null,
                    inferredWindowEnd = null,
                    strongestObservedStart = null,
                    inferredStartStrength = null,
                    inferredStartReasons = emptyList(),
                    inferredStartContradictions = emptyList(),
                    strongestEpisodeStrength = null,
                    strongestEpisodeReasons = emptyList(),
                    strongestEpisodeContradictions = emptyList(),
                    earliestAmbiguousStart = null,
                    earlierAmbiguousCandidateCount = 0,
                    unsupportedMarketCount = 0,
                    unsupportedMarketSamples = emptyList(),
                    competingCandidateCount = 0,
                )
                repository.saveInceptionInferenceEvidence(firstPositiveOnly)

                val soloReloaded = repository.findInceptionInferenceEvidence("fingerprint-solo")
                requireNotNull(soloReloaded)
                soloReloaded.firstPositive shouldBe start
                soloReloaded.inferredStart.shouldBeNull()
                soloReloaded.inferredStartStrength.shouldBeNull()
                soloReloaded.candidates shouldBe emptyList()
                soloReloaded.coverageStart.shouldBeNull()

                val candidatesOnly = InceptionInferenceEvidence(
                    fingerprint = "fingerprint-candidates",
                    evidenceDigest = "digest-candidates",
                    modelVersion = "1",
                    coverageStart = null,
                    coverageEnd = null,
                    horizon = null,
                    firstPositive = null,
                    inferredStart = start,
                    inferredWindowStart = start,
                    inferredWindowEnd = start.plusSeconds(10),
                    inferredStartStrength = "LOW",
                    inferredStartReasons = listOf("PURCHASE_ONLY_EPISODE"),
                    inferredStartContradictions = emptyList(),
                    strongestObservedStart = null,
                    strongestEpisodeStrength = null,
                    strongestEpisodeReasons = emptyList(),
                    strongestEpisodeContradictions = emptyList(),
                    earliestAmbiguousStart = null,
                    earlierAmbiguousCandidateCount = 0,
                    unsupportedMarketCount = 0,
                    unsupportedMarketSamples = emptyList(),
                    competingCandidateCount = 0,
                    candidates = listOf(
                        InceptionCandidateEvidence(
                            observedStart = start,
                            observedEnd = start.plusSeconds(10),
                            windowStart = start,
                            windowEnd = start.plusSeconds(10),
                            strength = "LOW",
                            reasons = listOf("PURCHASE_ONLY_EPISODE"),
                            contradictions = emptyList(),
                            assetCount = 2,
                            assetSymbols = listOf("BTC", "ETH"),
                            orderCount = 2,
                            repeatedEvidenceCount = 1,
                            timescalesSeconds = setOf(5L),
                        ),
                    ),
                )
                repository.saveInceptionInferenceEvidence(candidatesOnly)

                val candidatesReloaded = repository.findInceptionInferenceEvidence("fingerprint-candidates")
                requireNotNull(candidatesReloaded)
                candidatesReloaded.firstPositive.shouldBeNull()
                candidatesReloaded.inferredStart shouldBe start
                candidatesReloaded.candidates.size shouldBe 1
            }
        }

        "returns null inference evidence for an unknown fingerprint" {
            runTest {
                repository.findInceptionInferenceEvidence("missing-fingerprint").shouldBeNull()
            }
        }

        "strips commas from persisted list entries so the delimiter roundtrip stays lossless" {
            runTest {
                val start = Instant.parse("2025-12-22T02:08:00Z")
                val evidence = InceptionInferenceEvidence(
                    fingerprint = "fingerprint-commas",
                    evidenceDigest = "digest-commas",
                    modelVersion = "1",
                    coverageStart = null,
                    coverageEnd = null,
                    horizon = null,
                    firstPositive = null,
                    inferredStart = start,
                    inferredWindowStart = start,
                    inferredWindowEnd = start.plusSeconds(10),
                    strongestObservedStart = null,
                    inferredStartStrength = "LOW",
                    inferredStartReasons = listOf("PURCHASE,ONLY", "EPISODE"),
                    inferredStartContradictions = listOf("EARLIER,ACTIVITY"),
                    strongestEpisodeStrength = null,
                    strongestEpisodeReasons = emptyList(),
                    strongestEpisodeContradictions = emptyList(),
                    earliestAmbiguousStart = null,
                    earlierAmbiguousCandidateCount = 0,
                    unsupportedMarketCount = 1,
                    unsupportedMarketSamples = listOf("ADA,USDT"),
                    competingCandidateCount = 0,
                    candidates = listOf(
                        InceptionCandidateEvidence(
                            observedStart = start,
                            observedEnd = start.plusSeconds(10),
                            windowStart = start,
                            windowEnd = start.plusSeconds(10),
                            strength = "LOW",
                            reasons = listOf("REASON,WITH,COMMAS"),
                            contradictions = emptyList(),
                            assetCount = 2,
                            assetSymbols = listOf("AS,SET1", "ASSET2"),
                            orderCount = 2,
                            repeatedEvidenceCount = 1,
                            timescalesSeconds = setOf(5L),
                        ),
                    ),
                )
                repository.saveInceptionInferenceEvidence(evidence)

                val reloaded = repository.findInceptionInferenceEvidence("fingerprint-commas")
                requireNotNull(reloaded)
                reloaded.inferredStartReasons shouldBe listOf("PURCHASEONLY", "EPISODE")
                reloaded.inferredStartContradictions shouldBe listOf("EARLIERACTIVITY")
                reloaded.unsupportedMarketSamples shouldBe listOf("ADAUSDT")
                reloaded.candidates.first().reasons shouldBe listOf("REASONWITHCOMMAS")
                reloaded.candidates.first().assetSymbols shouldBe listOf("ASSET1", "ASSET2")
            }
        }
    }
}
