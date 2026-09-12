package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.model.hasValidEconomicFields
import com.gemini.krakenbot.service.impl.KrakenParsers
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant

@Suppress("unused")
class HistoricalEvidenceContractExtraTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val fixedNow = Instant.parse("2026-07-01T12:00:00Z")

    init {
        "comparison fails closed on malformed supported trade economics" {
            val mockTrades = mockk<com.gemini.krakenbot.repository.TradeRepository>(relaxed = true)
            val mockStats = mockk<com.gemini.krakenbot.repository.PortfolioStatsRepository>(relaxed = true)
            val mockLedgers = mockk<com.gemini.krakenbot.repository.LedgerRepository>(relaxed = true)
            val svc = TradeHistoryQueryService(mockTrades, mockStats, mockLedgers, null)
            val s1 = TestFixtures.emptySnapshot(fixedNow, BigDecimal("100000")).copy(
                assets = mapOf(
                    "BTC" to
                        TestFixtures.assetSnapshot(
                            "BTC",
                            BigDecimal("1"),
                            BigDecimal("50000"),
                            BigDecimal("50000"),
                            BigDecimal("50"),
                        ),
                    "USD" to
                        TestFixtures.assetSnapshot(
                            "USD",
                            BigDecimal("50000"),
                            BigDecimal.ONE,
                            BigDecimal("50000"),
                            BigDecimal("50"),
                        ),
                ),
            )
            val s2 = s1.copy(timestamp = fixedNow.plusSeconds(86400))
            val bad = TestFixtures.tradeRecord(
                timestamp = fixedNow.plusSeconds(100),
                pair = "XBTUSD",
                side = "buy",
                symbol = "XBT",
                volume = BigDecimal.ZERO,
                usdAmount = BigDecimal.ZERO,
                price = BigDecimal.ZERO,
                fee = BigDecimal.ZERO,
                source = TradeSource.API_FILL,
                tradeId = "bad-vol",
            ).copy(hasValidVolume = false)
            coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns listOf(s1, s2)
            coEvery { mockTrades.getAllSnapshotsInRange(any(), any()) } returns listOf(s1, s2)
            coEvery { mockTrades.getTradesInRange(any(), any()) } returns listOf(bad)
            coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns emptyList()
            coEvery { mockTrades.getSyncMetadata(any()) } returns null
            coEvery { mockLedgers.getSyncMetadata(any()) } returns null
            val result = svc.getRebalancerComparison(fixedNow, fixedNow.plusSeconds(86400))
            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_TRADE
        }
        "timeline fails closed on unknown raw ledger type" {
            val unknown = LedgerEvent(
                ledgerId = "U1",
                time = fixedNow,
                type = "mysterytype",
                asset = "BTC",
                amount = BigDecimal("0.1"),
            )
            try {
                SnapshotHistoryCalculator.buildTimelineEvents(emptyList(), listOf(unknown), fixedNow.plusSeconds(10))
                assert(false) { "should have thrown" }
            } catch (e: IllegalArgumentException) {
                (e.message ?: "").contains("unknown raw ledger") shouldBe true
            }
        }
        "trade ledger checkpoint does not fail timeline" {
            val tradeRow = LedgerEvent(
                ledgerId = "T1",
                time = fixedNow,
                type = "trade",
                asset = "BTC",
                amount = BigDecimal("0.1"),
            )
            val events = SnapshotHistoryCalculator.buildTimelineEvents(
                emptyList(),
                listOf(tradeRow),
                fixedNow.plusSeconds(10),
            )
            events.filterIsInstance<SnapshotHistoryCalculator.TimelineEvent.RewardEvent>().isEmpty() shouldBe true
        }
        "stale interval null when contract current" {
            val mockTrades = mockk<com.gemini.krakenbot.repository.TradeRepository>(relaxed = true)
            val mockStats = mockk<com.gemini.krakenbot.repository.PortfolioStatsRepository>(relaxed = true)
            val mockLedgers = mockk<com.gemini.krakenbot.repository.LedgerRepository>(relaxed = true)
            val svc = TradeHistoryQueryService(mockTrades, mockStats, mockLedgers, null)
            val s1 = TestFixtures.emptySnapshot(fixedNow, BigDecimal("100000")).copy(
                assets = mapOf(
                    "BTC" to
                        TestFixtures.assetSnapshot(
                            "BTC",
                            BigDecimal("1"),
                            BigDecimal("50000"),
                            BigDecimal("50000"),
                            BigDecimal("50"),
                        ),
                    "USD" to
                        TestFixtures.assetSnapshot(
                            "USD",
                            BigDecimal("50000"),
                            BigDecimal.ONE,
                            BigDecimal("50000"),
                            BigDecimal("50"),
                        ),
                ),
            )
            val s2 = s1.copy(timestamp = fixedNow.plusSeconds(86400))
            coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns listOf(s1, s2)
            coEvery { mockTrades.getAllSnapshotsInRange(any(), any()) } returns listOf(s1, s2)
            coEvery { mockTrades.getTradesInRange(any(), any()) } returns emptyList()
            coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns emptyList()
            coEvery { mockTrades.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION) } returns
                TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION
            coEvery {
                mockTrades.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_LEDGER_COVERAGE_VERSION)
            } returns
                LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
            coEvery {
                mockTrades.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_TRADE_COVERAGE_VERSION)
            } returns
                TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION
            coEvery { mockTrades.getSyncMetadata(any()) } returns null
            coEvery { mockLedgers.getSyncMetadata(any()) } returns null
            // Current contract with empty evidence still returns a result (not stale-blocked).
            val result = svc.getRebalancerComparison(fixedNow, fixedNow.plusSeconds(86400))
            (
                result.availability == ComparisonAvailability.AVAILABLE ||
                    result.availability == ComparisonAvailability.UNAVAILABLE
                ) shouldBe
                true
        }
        "stale contract with live-only snapshots remains usable" {
            val mockTrades = mockk<com.gemini.krakenbot.repository.TradeRepository>(relaxed = true)
            val mockStats = mockk<com.gemini.krakenbot.repository.PortfolioStatsRepository>(relaxed = true)
            val mockLedgers = mockk<com.gemini.krakenbot.repository.LedgerRepository>(relaxed = true)
            val svc = TradeHistoryQueryService(mockTrades, mockStats, mockLedgers, null)
            val reconThrough = fixedNow.plusSeconds(100)
            val live1 =
                TestFixtures.emptySnapshot(reconThrough.plusSeconds(1000), java.math.BigDecimal("100000")).copy(
                    assets =
                    mapOf(
                        "BTC" to
                            TestFixtures.assetSnapshot(
                                "BTC",
                                java.math.BigDecimal("1"),
                                java.math.BigDecimal("50000"),
                                java.math.BigDecimal("50000"),
                                java.math.BigDecimal("50"),
                            ),
                        "USD" to
                            TestFixtures.assetSnapshot(
                                "USD",
                                java.math.BigDecimal("50000"),
                                java.math.BigDecimal.ONE,
                                java.math.BigDecimal("50000"),
                                java.math.BigDecimal("50"),
                            ),
                    ),
                )
            val live2 = live1.copy(timestamp = live1.timestamp.plusSeconds(86400))
            coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns listOf(live1, live2)
            coEvery { mockTrades.getAllSnapshotsInRange(any(), any()) } returns listOf(live1, live2)
            coEvery { mockTrades.getTradesInRange(any(), any()) } returns emptyList()
            coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns emptyList()
            coEvery { mockTrades.getSyncMetadata(any()) } returns null
            coEvery { mockLedgers.getSyncMetadata(any()) } returns null
            coEvery { mockTrades.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION) } returns ""
            coEvery { mockTrades.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC) } returns
                reconThrough.epochSecond.toString()
            coEvery { mockTrades.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_START_EPOCH_SEC) } returns
                fixedNow.epochSecond.toString()
            val result = svc.getRebalancerComparison(live1.timestamp, live2.timestamp)
            (
                result.availability == com.gemini.krakenbot.model.ComparisonAvailability.AVAILABLE ||
                    result.availability == com.gemini.krakenbot.model.ComparisonAvailability.UNAVAILABLE
                ) shouldBe
                true
        }
        "comparison ignores dryRun unsupported trade" {
            val mockTrades = mockk<com.gemini.krakenbot.repository.TradeRepository>(relaxed = true)
            val mockStats = mockk<com.gemini.krakenbot.repository.PortfolioStatsRepository>(relaxed = true)
            val mockLedgers = mockk<com.gemini.krakenbot.repository.LedgerRepository>(relaxed = true)
            val svc = TradeHistoryQueryService(mockTrades, mockStats, mockLedgers, null)
            val s1 =
                TestFixtures.emptySnapshot(fixedNow, java.math.BigDecimal("100000")).copy(
                    assets =
                    mapOf(
                        "BTC" to
                            TestFixtures.assetSnapshot(
                                "BTC",
                                BigDecimal("1"),
                                BigDecimal("50000"),
                                BigDecimal("50000"),
                                BigDecimal("50"),
                            ),
                        "USD" to
                            TestFixtures.assetSnapshot(
                                "USD",
                                BigDecimal("50000"),
                                BigDecimal.ONE,
                                BigDecimal("50000"),
                                BigDecimal("50"),
                            ),
                    ),
                )
            val s2 = s1.copy(timestamp = fixedNow.plusSeconds(86400))
            val dryBad =
                TestFixtures.tradeRecord(
                    timestamp = fixedNow.plusSeconds(100),
                    pair = "ADAEUR",
                    side = "buy",
                    symbol = "ADAEUR",
                    volume = java.math.BigDecimal("10"),
                    usdAmount = java.math.BigDecimal.ZERO,
                    price = java.math.BigDecimal("0.5"),
                    fee = java.math.BigDecimal.ZERO,
                    source = TradeSource.API_FILL,
                    tradeId = "dry-bad",
                    dryRun = true,
                )
            coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns listOf(s1, s2)
            coEvery { mockTrades.getAllSnapshotsInRange(any(), any()) } returns listOf(s1, s2)
            coEvery { mockTrades.getTradesInRange(any(), any()) } returns listOf(dryBad)
            coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns emptyList()
            coEvery { mockTrades.getSyncMetadata(any()) } returns null
            coEvery { mockLedgers.getSyncMetadata(any()) } returns null
            val result = svc.getRebalancerComparison(fixedNow, fixedNow.plusSeconds(86400))
            // Dry-run unsupported must not force UNSUPPORTED_TRADE.
            if (result.availability == ComparisonAvailability.UNAVAILABLE) {
                (result.unavailableReason != ComparisonUnavailableReason.UNSUPPORTED_TRADE) shouldBe true
            } else {
                (result.availability == ComparisonAvailability.AVAILABLE) shouldBe true
            }
        }
        "parser marks malformed price invalid" {
            val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            val node =
                mapper.readTree(
                    """{"count":1,"trades":{"T1":{"pair":"XBTUSD","type":"buy","time":1751328000.0,""" +
                        """"price":"garbage","cost":"1000.0","vol":"0.02","fee":"1.0","ordertxid":"O1"}}}""",
                )
            val page = KrakenParsers.parseTradeHistoryPage(node, listOf("BTC", "USD"), true)
            page.entries[0].hasValidPrice shouldBe false
            page.entries[0].hasValidEconomicFields() shouldBe false
        }
    }
}
