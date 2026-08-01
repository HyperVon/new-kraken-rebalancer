package com.gemini.krakenbot.util

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSubmissionState
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class TradeDeduplicatorTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "should return empty list when no duplicates are present" {
            val now = Instant.now()
            val records = listOf(
                TestFixtures.tradeRecord(
                    now, TestFixtures.XBTUSD, TestFixtures.BUY_UPPER, Asset.BTC,
                    BigDecimal(
                        "1.0",
                    ),
                    BigDecimal("50000.00"), success = true, dryRun = false, id = 1,
                ),
                TestFixtures.tradeRecord(
                    now.plusSeconds(
                        600,
                    ),
                    "XDGUSD",
                    "BUY",
                    "DOGE",
                    BigDecimal("100.0"),
                    BigDecimal("10.00"),
                    id = 2,
                ),
            )

            val duplicates = TradeDeduplicator.findDuplicateTradeIds(records)
            duplicates.isEmpty() shouldBe true
        }

        "should identify pair alias duplicate trade records" {
            val now = Instant.now()
            val t1 = TestFixtures.tradeRecord(
                now,
                TestFixtures.XBTUSD,
                TestFixtures.BUY_UPPER,
                Asset.BTC,
                BigDecimal("1.0"),
                BigDecimal("50000.00"),
                id = 1,
            )
            val t2 = TestFixtures.tradeRecord(
                now.plusSeconds(1),
                TestFixtures.XXBTZUSD,
                TestFixtures.BUY_UPPER,
                Asset.BTC,
                BigDecimal("1.0"),
                BigDecimal("50000.00"),
                id = 2,
            )

            val duplicates = TradeDeduplicator.findDuplicateTradeIds(listOf(t1, t2))
            duplicates shouldContainExactly listOf(2)
        }

        "CQ-7-5: should not treat pair aliases with volume over one percent apart as duplicates" {
            val now = Instant.now()
            val record1 = TestFixtures.tradeRecord(
                timestamp = now,
                pair = TestFixtures.XBTUSD,
                side = TestFixtures.BUY_UPPER,
                symbol = Asset.BTC,
                volume = BigDecimal("1.0"),
                usdAmount = BigDecimal("50000.00"),
                source = TradeSource.LOCAL_ESTIMATE,
                id = 3,
            )
            val record2 = record1.copy(
                timestamp = now.plusMillis(100),
                pair = TestFixtures.XXBTZUSD,
                volume = BigDecimal("1.02"),
                id = 4,
            )

            TradeDeduplicator.findDuplicateTradeIds(listOf(record1, record2)).isEmpty() shouldBe true
        }

        "CQ-11-L6: a doomed pair-alias row does not bridge trades outside the five-minute window" {
            val now = Instant.now()
            val first = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "XBTUSD",
                side = "BUY",
                symbol = "BTC",
                volume = BigDecimal.ONE,
                usdAmount = BigDecimal("50000.00"),
                id = 201,
            )
            val bridge = first.copy(
                timestamp = now.plusSeconds(4 * 60),
                pair = "XXBTZUSD",
                id = 202,
            )
            val legitimateLaterTrade = first.copy(
                timestamp = now.plusSeconds(8 * 60),
                id = 203,
            )

            TradeDeduplicator.findDuplicateTradeIds(listOf(first, bridge, legitimateLaterTrade)) shouldContainExactly
                listOf(202)
        }

        "CQ-10-L6: should keep pair-alias API fills with distinct Kraken trade IDs" {
            val now = Instant.now()
            val firstFill = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "XBTUSD",
                side = "BUY",
                symbol = "BTC",
                volume = BigDecimal("1.0"),
                usdAmount = BigDecimal("50000.00"),
                fee = BigDecimal("100.00"),
                source = TradeSource.API_FILL,
                id = 31,
                tradeId = "KRAKEN-LEG-ONE",
            )
            val secondFill = firstFill.copy(
                timestamp = now.plusMillis(100),
                pair = "XXBTZUSD",
                id = 32,
                tradeId = "KRAKEN-LEG-TWO",
            )

            TradeDeduplicator.findDuplicateTradeIds(listOf(firstFill, secondFill)).isEmpty() shouldBe true
        }

        "CQ-10-L7: should keep an ambiguous legacy row beside an ID-bearing API fill" {
            val now = Instant.now()
            val legacyUnknown = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "XBTUSD",
                side = "BUY",
                symbol = "BTC",
                volume = BigDecimal("1.0"),
                usdAmount = BigDecimal("50000.00"),
                fee = BigDecimal("100.00"),
                id = 41,
            )
            val currentFill = legacyUnknown.copy(
                timestamp = now.plusMillis(100),
                pair = "XXBTZUSD",
                source = TradeSource.API_FILL,
                id = 42,
                tradeId = "KRAKEN-CURRENT-FILL",
            )

            TradeDeduplicator.findDuplicateTradeIds(listOf(legacyUnknown, currentFill)).isEmpty() shouldBe true
        }

        "should identify local estimate duplicate trade records with material fee rate differences" {
            val now = Instant.now()
            val localEstimate = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "XBTUSD",
                side = "BUY",
                symbol = "BTC",
                volume = BigDecimal("1.0"),
                usdAmount = BigDecimal("50000.00"),
                fee = BigDecimal("10.00"),
                slippagePercent = BigDecimal.ZERO,
                source = TradeSource.LOCAL_ESTIMATE,
                id = 10,
            )
            val settledFill = TestFixtures.tradeRecord(
                timestamp = now.plusSeconds(2),
                pair = "XBTUSD",
                side = "BUY",
                symbol = "BTC",
                volume = BigDecimal("1.0"),
                usdAmount = BigDecimal("50000.00"),
                fee = BigDecimal("100.00"),
                source = TradeSource.API_FILL,
                id = 11,
            )

            val duplicates = TradeDeduplicator.findDuplicateTradeIds(listOf(localEstimate, settledFill))
            duplicates shouldContainExactly listOf(10)
        }

        "should preserve legitimate equal-sized fills with different financial details" {
            val now = Instant.now()
            val firstFill = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "XBTUSD",
                side = "BUY",
                symbol = "BTC",
                volume = BigDecimal("1.0"),
                usdAmount = BigDecimal("50000.00"),
                price = BigDecimal("50000.00"),
                fee = BigDecimal("100.00"),
                id = 20,
            )
            val secondFill = firstFill.copy(
                timestamp = now.plusSeconds(30),
                pair = "XXBTZUSD",
                usdAmount = BigDecimal("50500.00"),
                price = BigDecimal("50500.00"),
                fee = BigDecimal("101.00"),
                id = 21,
            )

            TradeDeduplicator.findDuplicateTradeIds(listOf(firstFill, secondFill)).isEmpty() shouldBe true
        }

        "should identify legacy local estimate duplicates when source is null" {
            val now = Instant.now()
            val legacyEstimate =
                TestFixtures.tradeRecord(
                    timestamp = now,
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal("1.0"),
                    usdAmount = BigDecimal("50000.00"),
                    fee = BigDecimal("10.00"),
                    slippagePercent = BigDecimal.ZERO,
                    id = 30,
                )
            val settledFill =
                legacyEstimate.copy(
                    timestamp = now.plusSeconds(2),
                    fee = BigDecimal("100.00"),
                    slippagePercent = null,
                    source = TradeSource.API_FILL,
                    id = 31,
                )

            TradeDeduplicator.findDuplicateTradeIds(listOf(legacyEstimate, settledFill)) shouldContainExactly listOf(30)
        }

        "should not treat pair alias API fills with different fees as duplicates" {
            val now = Instant.now()
            val record1 =
                TestFixtures.tradeRecord(
                    timestamp = now,
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal("1.0"),
                    usdAmount = BigDecimal("50000.00"),
                    fee = BigDecimal("100.00"),
                    source = TradeSource.API_FILL,
                    id = 40,
                )
            val record2 =
                TestFixtures.tradeRecord(
                    timestamp = now.plusMillis(100),
                    pair = "XXBTZUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal("1.0"),
                    usdAmount = BigDecimal("50000.00"),
                    fee = BigDecimal("150.00"),
                    source = TradeSource.API_FILL,
                    id = 41,
                )

            TradeDeduplicator.findDuplicateTradeIds(listOf(record1, record2)).isEmpty() shouldBe true
        }

        "should delete later id when both pair alias records are settled API fills" {
            val now = Instant.now()
            val record1 =
                TestFixtures.tradeRecord(
                    timestamp = now,
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal("1.0"),
                    usdAmount = BigDecimal("50000.00"),
                    price = BigDecimal("50000.00"),
                    fee = BigDecimal("100.00"),
                    source = TradeSource.API_FILL,
                    id = 50,
                )
            val record2 =
                TestFixtures.tradeRecord(
                    timestamp = now.plusMillis(100),
                    pair = "XXBTZUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal("1.0"),
                    usdAmount = BigDecimal("50000.00"),
                    price = BigDecimal("50000.00"),
                    fee = BigDecimal("100.00"),
                    source = TradeSource.API_FILL,
                    id = 51,
                )

            TradeDeduplicator.findDuplicateTradeIds(listOf(record1, record2)) shouldContainExactly listOf(51)
        }

        "should not treat opposite sides as duplicates even when otherwise identical" {
            val now = Instant.now()
            val buyRecord =
                TestFixtures.tradeRecord(
                    timestamp = now,
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal("1.0"),
                    usdAmount = BigDecimal("50000.00"),
                    fee = BigDecimal("100.00"),
                    source = TradeSource.API_FILL,
                    id = 60,
                )
            val sellRecord =
                buyRecord.copy(
                    timestamp = now.plusMillis(100),
                    side = "SELL",
                    id = 61,
                )

            TradeDeduplicator.findDuplicateTradeIds(listOf(buyRecord, sellRecord)).isEmpty() shouldBe true
        }

        "should still treat pair-alias matches at exactly the 5-minute window as duplicates" {
            val now = Instant.now()
            val record1 =
                TestFixtures.tradeRecord(
                    timestamp = now,
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal("1.0"),
                    usdAmount = BigDecimal("50000.00"),
                    fee = BigDecimal("100.00"),
                    id = 70,
                )
            val record2 =
                record1.copy(
                    timestamp = now.plusMillis(300_000),
                    pair = "XXBTZUSD",
                    id = 71,
                )

            TradeDeduplicator.findDuplicateTradeIds(listOf(record1, record2)) shouldContainExactly listOf(71)
        }

        "should not treat pair-alias matches beyond the 5-minute window as duplicates" {
            val now = Instant.now()
            val record1 =
                TestFixtures.tradeRecord(
                    timestamp = now,
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal("1.0"),
                    usdAmount = BigDecimal("50000.00"),
                    fee = BigDecimal("100.00"),
                    id = 72,
                )
            val record2 =
                record1.copy(
                    timestamp = now.plusMillis(300_001),
                    pair = "XXBTZUSD",
                    id = 73,
                )

            TradeDeduplicator.findDuplicateTradeIds(listOf(record1, record2)).isEmpty() shouldBe true
        }

        "CQ-3-8: should delete the later local estimate when the API fill arrives first" {
            val now = Instant.now()
            val settledFill =
                TestFixtures.tradeRecord(
                    timestamp = now,
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal("1.0"),
                    usdAmount = BigDecimal("100000.00"),
                    fee = BigDecimal("100.00"),
                    source = TradeSource.API_FILL,
                    id = 100,
                )
            val localEstimate =
                TestFixtures.tradeRecord(
                    timestamp = now.plusSeconds(2),
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal("1.0"),
                    usdAmount = BigDecimal("100000.00"),
                    fee = BigDecimal("300.00"),
                    slippagePercent = BigDecimal.ZERO,
                    source = TradeSource.LOCAL_ESTIMATE,
                    id = 101,
                )

            TradeDeduplicator.findDuplicateTradeIds(listOf(settledFill, localEstimate)) shouldContainExactly listOf(101)
        }

        "CQ-3-8: should keep a local/API pair whose fee rates do not differ materially" {
            val now = Instant.now()
            val settledFill =
                TestFixtures.tradeRecord(
                    timestamp = now,
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal("1.0"),
                    usdAmount = BigDecimal("100000.00"),
                    fee = BigDecimal("100.00"),
                    source = TradeSource.API_FILL,
                    id = 102,
                )
            val localEstimate =
                TestFixtures.tradeRecord(
                    timestamp = now.plusSeconds(2),
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal("1.0"),
                    usdAmount = BigDecimal("100000.00"),
                    fee = BigDecimal("100.00"),
                    slippagePercent = BigDecimal.ZERO,
                    source = TradeSource.LOCAL_ESTIMATE,
                    id = 103,
                )

            TradeDeduplicator.findDuplicateTradeIds(listOf(settledFill, localEstimate)).isEmpty() shouldBe true
        }

        "CQ-3-21: should treat a fee-rate delta exactly at the 0.001 material threshold as a duplicate" {
            val now = Instant.now()
            val localEstimate =
                TestFixtures.tradeRecord(
                    timestamp = now,
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal("1.0"),
                    usdAmount = BigDecimal("100000.00"),
                    fee = BigDecimal("100.00"), // rate 0.001
                    slippagePercent = BigDecimal.ZERO,
                    source = TradeSource.LOCAL_ESTIMATE,
                    id = 80,
                )
            val settledFill =
                TestFixtures.tradeRecord(
                    timestamp = now.plusSeconds(2),
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal("1.0"),
                    usdAmount = BigDecimal("100000.00"),
                    fee = BigDecimal("0.00"), // Δ rate == 0.001 threshold (inclusive → duplicate)
                    source = TradeSource.API_FILL,
                    id = 81,
                )

            TradeDeduplicator.findDuplicateTradeIds(listOf(localEstimate, settledFill)) shouldContainExactly listOf(80)
        }

        "CQ-3-21: should keep a pair whose fee-rate delta is one unit below the 0.001 material threshold" {
            val now = Instant.now()
            val localEstimate =
                TestFixtures.tradeRecord(
                    timestamp = now,
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal("1.0"),
                    usdAmount = BigDecimal("100000.00"),
                    fee = BigDecimal("99.999"),
                    slippagePercent = BigDecimal.ZERO,
                    source = TradeSource.LOCAL_ESTIMATE,
                    id = 82,
                )
            val settledFill =
                TestFixtures.tradeRecord(
                    timestamp = now.plusSeconds(2),
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal("1.0"),
                    usdAmount = BigDecimal("100000.00"),
                    fee = BigDecimal("0.00"),
                    source = TradeSource.API_FILL,
                    id = 83,
                )

            TradeDeduplicator.findDuplicateTradeIds(listOf(localEstimate, settledFill)).isEmpty() shouldBe true
        }

        "CQ-3-21: should treat a local estimate exactly at the 10-second window as a duplicate" {
            val now = Instant.now()
            val localEstimate =
                TestFixtures.tradeRecord(
                    timestamp = now,
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal("1.0"),
                    usdAmount = BigDecimal("100000.00"),
                    fee = BigDecimal("100.00"),
                    slippagePercent = BigDecimal.ZERO,
                    source = TradeSource.LOCAL_ESTIMATE,
                    id = 110,
                )
            val settledFill =
                TestFixtures.tradeRecord(
                    timestamp = now.plusMillis(10_000),
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal("1.0"),
                    usdAmount = BigDecimal("100000.00"),
                    fee = BigDecimal("300.00"),
                    source = TradeSource.API_FILL,
                    id = 111,
                )

            TradeDeduplicator.findDuplicateTradeIds(listOf(localEstimate, settledFill)) shouldContainExactly listOf(110)
        }

        "CQ-3-21: should not treat a local estimate one millisecond beyond the 10-second window as a duplicate" {
            val now = Instant.now()
            val localEstimate =
                TestFixtures.tradeRecord(
                    timestamp = now,
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal("1.0"),
                    usdAmount = BigDecimal("100000.00"),
                    fee = BigDecimal("100.00"),
                    slippagePercent = BigDecimal.ZERO,
                    source = TradeSource.LOCAL_ESTIMATE,
                    id = 112,
                )
            val settledFill =
                TestFixtures.tradeRecord(
                    timestamp = now.plusMillis(10_001),
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal("1.0"),
                    usdAmount = BigDecimal("100000.00"),
                    fee = BigDecimal("300.00"),
                    source = TradeSource.API_FILL,
                    id = 113,
                )

            TradeDeduplicator.findDuplicateTradeIds(listOf(localEstimate, settledFill)).isEmpty() shouldBe true
        }

        "CQ-3-12: should skip safely when the later record has a null id" {
            val now = Instant.now()
            val localEstimate =
                TestFixtures.tradeRecord(
                    timestamp = now,
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal("1.0"),
                    usdAmount = BigDecimal("100000.00"),
                    fee = BigDecimal("100.00"),
                    slippagePercent = BigDecimal.ZERO,
                    source = TradeSource.LOCAL_ESTIMATE,
                    id = 90,
                )
            val settledFillNullId =
                TestFixtures.tradeRecord(
                    timestamp = now.plusSeconds(2),
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal("1.0"),
                    usdAmount = BigDecimal("100000.00"),
                    fee = BigDecimal("300.00"),
                    source = TradeSource.API_FILL,
                    id = null,
                )

            TradeDeduplicator.findDuplicateTradeIds(listOf(localEstimate, settledFillNullId)).isEmpty() shouldBe true
        }

        "CQ-3-12: should skip safely when the record to delete is the unsettled one with a null id" {
            val now = Instant.now()
            val localEstimateNullId =
                TestFixtures.tradeRecord(
                    timestamp = now,
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal("1.0"),
                    usdAmount = BigDecimal("100000.00"),
                    fee = BigDecimal("100.00"),
                    slippagePercent = BigDecimal.ZERO,
                    source = TradeSource.LOCAL_ESTIMATE,
                    id = null,
                )
            val settledFill =
                TestFixtures.tradeRecord(
                    timestamp = now.plusSeconds(2),
                    pair = "XBTUSD",
                    side = "BUY",
                    symbol = "BTC",
                    volume = BigDecimal("1.0"),
                    usdAmount = BigDecimal("100000.00"),
                    fee = BigDecimal("300.00"),
                    source = TradeSource.API_FILL,
                    id = 91,
                )

            TradeDeduplicator.findDuplicateTradeIds(listOf(localEstimateNullId, settledFill)).isEmpty() shouldBe true
        }

        "CQ-13-4: should identify duplicates at zero delta and when the API fill precedes the local estimate" {
            val now = Instant.now()
            val apiFill = TestFixtures.tradeRecord(
                timestamp = now,
                pair = "XBTUSD",
                side = "BUY",
                symbol = "BTC",
                volume = BigDecimal("1.0"),
                usdAmount = BigDecimal("50000.00"),
                fee = BigDecimal("100.00"), // 0.2% fee rate
                source = TradeSource.API_FILL,
                id = 101,
            )
            // Local estimate with exact same timestamp (zero delta) and differing fee rate (0.4% fee rate)
            val localZeroDelta = apiFill.copy(
                fee = BigDecimal("200.00"),
                source = TradeSource.LOCAL_ESTIMATE,
                id = 102,
            )
            // Local estimate recorded 500ms after the API fill exercises the opposite source ordering.
            val localAfterApiFill = apiFill.copy(
                timestamp = now.plusMillis(500),
                fee = BigDecimal("200.00"),
                source = TradeSource.LOCAL_ESTIMATE,
                id = 103,
            )

            val dupes1 = TradeDeduplicator.findDuplicateTradeIds(listOf(apiFill, localZeroDelta))
            dupes1 shouldContainExactly listOf(102)

            val dupes2 = TradeDeduplicator.findDuplicateTradeIds(listOf(apiFill, localAfterApiFill))
            dupes2 shouldContainExactly listOf(103)
        }

        "CQ-14-L4: startup cleanup preserves non-equivalent trade identity" {
            val now = Instant.now()
            val base = canonicalXbtApiFill(now)

            val conflictingOrderIds = listOf(
                base.copy(id = 120, orderTxid = "ORDER-ONE"),
                base.copy(
                    timestamp = now.plusMillis(100),
                    id = 121,
                    pair = TestFixtures.XXBTZUSD,
                    orderTxid = "ORDER-TWO",
                ),
            )
            TradeDeduplicator.findDuplicateTradeIds(conflictingOrderIds).isEmpty() shouldBe true

            val conflictingLocalApiOrderIds = listOf(
                base.copy(
                    id = 128,
                    source = TradeSource.LOCAL_ESTIMATE,
                    fee = BigDecimal("10.00"),
                    slippagePercent = BigDecimal.ZERO,
                    orderTxid = "LOCAL-ORDER",
                ),
                base.copy(
                    timestamp = now.plusMillis(100),
                    id = 129,
                    fee = BigDecimal("300.00"),
                    orderTxid = "API-ORDER",
                ),
            )
            TradeDeduplicator.findDuplicateTradeIds(conflictingLocalApiOrderIds).isEmpty() shouldBe true

            val conflictingLocalApiTradeIds = listOf(
                base.copy(
                    id = 130,
                    source = TradeSource.LOCAL_ESTIMATE,
                    fee = BigDecimal("10.00"),
                    slippagePercent = BigDecimal.ZERO,
                    tradeId = "LOCAL-TRADE",
                ),
                base.copy(
                    timestamp = now.plusMillis(100),
                    id = 131,
                    fee = BigDecimal("300.00"),
                    tradeId = "API-TRADE",
                ),
            )
            TradeDeduplicator.findDuplicateTradeIds(conflictingLocalApiTradeIds).isEmpty() shouldBe true

            val distinctProvenance = listOf(
                base.copy(id = 122, source = TradeSource.LEGACY_UNKNOWN),
                base.copy(
                    timestamp = now.plusMillis(100),
                    id = 123,
                    pair = TestFixtures.XXBTZUSD,
                    source = TradeSource.API_FILL,
                ),
            )
            TradeDeduplicator.findDuplicateTradeIds(distinctProvenance).isEmpty() shouldBe true

            val differentStatus = listOf(
                base.copy(
                    id = 124,
                    source = TradeSource.LOCAL_ESTIMATE,
                    success = false,
                    slippagePercent = BigDecimal.ZERO,
                ),
                base.copy(
                    timestamp = now.plusMillis(100),
                    id = 125,
                    success = true,
                ),
            )
            TradeDeduplicator.findDuplicateTradeIds(differentStatus).isEmpty() shouldBe true

            val differentDryRunStatus = listOf(
                base.copy(
                    id = 126,
                    source = TradeSource.LOCAL_ESTIMATE,
                    dryRun = true,
                    slippagePercent = BigDecimal.ZERO,
                ),
                base.copy(
                    timestamp = now.plusMillis(100),
                    id = 127,
                    dryRun = false,
                ),
            )
            TradeDeduplicator.findDuplicateTradeIds(differentDryRunStatus).isEmpty() shouldBe true
        }

        "startup cleanup preserves unresolved submissions and accepts matching identities" {
            val now = Instant.now()
            val base = canonicalXbtApiFill(now)

            TradeDeduplicator.findDuplicateTradeIds(
                listOf(
                    base.copy(id = 140, submissionState = OrderSubmissionState.PENDING),
                    base.copy(id = 141, pair = TestFixtures.XXBTZUSD),
                ),
            ).isEmpty() shouldBe true
            TradeDeduplicator.findDuplicateTradeIds(
                listOf(
                    base.copy(id = 142),
                    base.copy(id = 143, pair = TestFixtures.XXBTZUSD, submissionState = OrderSubmissionState.UNCERTAIN),
                ),
            ).isEmpty() shouldBe true

            TradeDeduplicator.findDuplicateTradeIds(
                listOf(
                    base.copy(id = 144, tradeId = "same-trade", orderTxid = "same-order"),
                    base.copy(
                        id = 145,
                        pair = TestFixtures.XXBTZUSD,
                        tradeId = "same-trade",
                        orderTxid = "same-order",
                    ),
                ),
            ) shouldContainExactly listOf(145)
        }

        "startup cleanup treats blank trade and order identifiers as absent" {
            val now = Instant.now()
            val base = canonicalXbtApiFill(now).copy(
                id = 150,
                tradeId = " ",
                orderTxid = " ",
            )
            val alias = base.copy(
                timestamp = now.plusMillis(100),
                pair = TestFixtures.XXBTZUSD,
                id = 151,
            )

            TradeDeduplicator.findDuplicateTradeIds(listOf(base, alias)) shouldContainExactly listOf(151)
        }
    }

    private fun canonicalXbtApiFill(timestamp: Instant): TradeRecord = TestFixtures.tradeRecord(
        timestamp = timestamp,
        pair = TestFixtures.XBTUSD,
        side = TestFixtures.BUY_UPPER,
        symbol = Asset.BTC,
        volume = BigDecimal.ONE,
        usdAmount = BigDecimal("50000.00"),
        price = BigDecimal("50000.00"),
        fee = BigDecimal("100.00"),
        source = TradeSource.API_FILL,
    )
}
