@file:OptIn(ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.TradeSummaryStats
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.milliseconds

class TradeHistorySyncCoordinationTest : TradeHistoryServiceTestBase() {

    init {
        "syncTradesFromKraken_ThrottlingWithin300Seconds" {
            runTest {
                val service = createService()
                service.syncTradesFromKraken()

                service.syncTradesFromKraken()
                coVerify(exactly = 1) { krakenService.getTradeHistory(any(), any()) }
                verify(exactly = 1) { configService.beginExecutionSession() }
                verify(exactly = 1) { configService.endExecutionSession() }
            }
        }

        "CQ-11-L3: standalone sync keeps an execution session active through Kraken work" {
            runTest {
                val service = createService()
                var sessionActive = false
                every { configService.beginExecutionSession() } answers { sessionActive = true }
                every { configService.endExecutionSession() } answers { sessionActive = false }
                coEvery { krakenService.getTradeHistory(any(), any()) } coAnswers {
                    sessionActive shouldBe true
                    emptyList()
                }

                service.syncTradesFromKraken()

                sessionActive shouldBe false
                verify(exactly = 1) { configService.beginExecutionSession() }
                verify(exactly = 1) { configService.endExecutionSession() }
            }
        }

        "syncTradesFromKraken_MissingCredentialsDoesNotThrottleImmediateRetry" {
            runTest {
                val service = createService()
                val validConfig = configService.getConfig()
                val missingCredentialsConfig = validConfig.copy(kraken = KrakenCredentials("", ""))
                every { configService.getConfig() } returns missingCredentialsConfig andThen validConfig
                coEvery { krakenService.getTradeHistory(any(), any()) } returns emptyList()

                service.syncTradesFromKraken()
                service.syncTradesFromKraken()

                coVerify(exactly = 1) { krakenService.getTradeHistory(any(), any()) }
                verify(exactly = 1) { configService.beginExecutionSession() }
                verify(exactly = 1) { configService.endExecutionSession() }
            }
        }

        "rebuildHistoricalSnapshotsIfNeeded_replacesLegacyHistoryAfterLedgerSync" {
            runTest {
                val service = createService()
                val config = TestFixtures.config(
                    settings = TestFixtures.settings(dryRun = false),
                    allocations = listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(TestFixtures.USD, 50.0),
                    ),
                )
                every { configService.getConfig() } returns config
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION)
                } returns null
                coEvery { repository.getTradeSummaryStats() } returns TradeSummaryStats(
                    totalTradesExecuted = 1L,
                    totalVolumeTraded = BigDecimal.ZERO,
                    totalFeesPaid = BigDecimal.ZERO,
                    latestSnapshotTime = null,
                )
                coEvery { ledgerRepository.isLedgersSeeded() } returns true
                coEvery { krakenService.getBalances() } returns mapOf(
                    Asset.BTC to BigDecimal.ONE,
                    TestFixtures.USD to BigDecimal("30000.00"),
                )
                every { portfolioAnalyzer.resolveBalance(Asset.BTC, any()) } returns BigDecimal.ONE
                every { portfolioAnalyzer.resolveBalance(TestFixtures.USD, any()) } returns BigDecimal("30000.00")
                coEvery { krakenService.getTickerPrices(any()) } returns mapOf(
                    TestFixtures.BTCUSD to BigDecimal("30000.00"),
                )
                every { portfolioAnalyzer.resolvePriceFromTicker(Asset.BTC, any()) } returns BigDecimal("30000.00")
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns listOf(
                    Instant.now().minus(1, ChronoUnit.DAYS).epochSecond to BigDecimal("30000.00"),
                )
                coEvery { repository.replaceSnapshots(any()) } just Runs
                coEvery { repository.setSyncMetadata(any(), any()) } just Runs

                service.rebuildHistoricalSnapshotsIfNeeded()

                coVerify(exactly = 1) { repository.replaceSnapshots(any()) }
                coVerify {
                    repository.setSyncMetadata(
                        SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION,
                        "3",
                    )
                }
            }
        }

        "rebuildHistoricalSnapshotsIfNeeded_skipsCurrentVersion" {
            runTest {
                val service = createService()
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION)
                } returns "3"

                service.rebuildHistoricalSnapshotsIfNeeded()

                coVerify(exactly = 0) { ledgerRepository.isLedgersSeeded() }
                coVerify(exactly = 0) { krakenService.getBalances() }
            }
        }

        "CQ-15-L2: rebuildHistoricalSnapshotsIfNeeded_skipsWhenLedgersUnseeded" {
            runTest {
                val service = createService()
                val config = TestFixtures.config(settings = TestFixtures.settings(dryRun = false))
                every { configService.getConfig() } returns config
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION)
                } returns null
                coEvery { ledgerRepository.isLedgersSeeded() } returns false

                service.rebuildHistoricalSnapshotsIfNeeded()

                coVerify(exactly = 0) { krakenService.getBalances() }
            }
        }

        "syncTradesFromKraken_FailedAttemptDoesNotThrottleImmediateRetry" {
            runTest {
                val service = createService()
                var attempts = 0
                coEvery { krakenService.getTradeHistory(any(), any()) } coAnswers {
                    if (attempts++ == 0) throw RuntimeException("Kraken API down")
                    emptyList()
                }

                try {
                    service.syncTradesFromKraken()
                } catch (_: RuntimeException) {
                    // A failed sync remains visible to the caller and must not start the throttle window.
                }
                service.syncTradesFromKraken()

                coVerify(exactly = 2) { krakenService.getTradeHistory(any(), any()) }
            }
        }

        "CQ-12-L6: concurrent sync calls serialize and recheck the throttle" {
            runTest {
                val service = createService()
                val firstPageStarted = CompletableDeferred<Unit>()
                val releaseFirstPage = CompletableDeferred<Unit>()
                coEvery { krakenService.getTradeHistory(any(), 0) } coAnswers {
                    firstPageStarted.complete(Unit)
                    releaseFirstPage.await()
                    emptyList()
                }

                val first = launch { service.syncTradesFromKraken() }
                firstPageStarted.await()
                val second = launch { service.syncTradesFromKraken() }
                yield()
                releaseFirstPage.complete(Unit)
                first.join()
                second.join()

                coVerify(exactly = 1) { krakenService.getTradeHistory(any(), 0) }
                verify(exactly = 1) { configService.beginExecutionSession() }
                verify(exactly = 1) { configService.endExecutionSession() }
            }
        }

        "CQ-12-L7: clock rollback rebases instead of suppressing history sync" {
            runTest {
                var now = Instant.parse("2026-07-28T12:00:00Z")
                val service = createService { now }
                coEvery { krakenService.getTradeHistory(any(), 0) } returns emptyList()

                service.syncTradesFromKraken()
                now = now.minusSeconds(3_600)
                service.syncTradesFromKraken()

                coVerify(exactly = 2) { krakenService.getTradeHistory(any(), 0) }
            }
        }

        "syncTradesFromKraken_MatchingFailuresSavedAsNew" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns true
                val latestTime = Instant.ofEpochSecond(1700000000)
                coEvery { repository.getLatestTradeTime() } returns latestTime

                val baseLocal = TestFixtures.tradeRecord(
                    timestamp = latestTime,
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                )

                // Locals differ from the API twin on exactly one attribute each → no dedupe match.
                val diffPair = baseLocal.copy(pair = "ETHUSD")
                val diffSide = baseLocal.copy(side = TestFixtures.SELL)
                val diffVol = baseLocal.copy(volume = BigDecimal.TEN)
                // 600s apart — outside isMatchingApiTrade's default 10s window
                val diffTime = baseLocal.copy(timestamp = latestTime.minusSeconds(600))

                coEvery { repository.getTradesInRange(any(), any()) } returns
                    listOf(diffPair, diffSide, diffVol, diffTime)

                val apiTrade = baseLocal.copy()
                coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(apiTrade) andThen emptyList()

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                coVerify(exactly = 1) { repository.saveTrade(apiTrade) }
            }
        }

        // CQ-8-L1 / #97: dry-run locals never hit the exchange — must not be rewritten into API_FILL.
        "syncTradesFromKraken_DoesNotPromoteDryRunLocalToApiFill" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns true
                val latestTime = Instant.ofEpochSecond(1700000000)
                coEvery { repository.getLatestTradeTime() } returns latestTime

                val localTrade = TestFixtures.tradeRecord(
                    timestamp = latestTime,
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    dryRun = true,
                    source = TradeSource.LOCAL_ESTIMATE,
                    orderTxid = "DRY-RUN-OID",
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(localTrade)

                val apiTrade = localTrade.copy(dryRun = false, source = TradeSource.API_FILL)

                coEvery { krakenService.getTradeHistory(any(), 0) } returns listOf(apiTrade)
                coEvery { krakenService.getTradeHistory(any(), 50) } returns emptyList()

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                coVerify(exactly = 0) { repository.updateTrade(any(), any()) }
                coVerify(exactly = 1) { repository.saveTrade(apiTrade) }
            }
        }

        // CQ-8-M1 / #98: pagination window shift re-emits fill X on page 1 — save once.
        "syncTradesFromKraken_SkipsCrossPageDuplicateApiFill" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns true
                coEvery { repository.getLatestTradeTime() } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()

                val fillX =
                    TestFixtures.tradeRecord(
                        timestamp = Instant.ofEpochSecond(1700000000),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.SELL,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("100.00"),
                        fee = BigDecimal("0.25"),
                        source = TradeSource.API_FILL,
                        orderTxid = "OID-X",
                    )
                val fillY =
                    fillX.copy(
                        timestamp = Instant.ofEpochSecond(1700000001),
                        volume = BigDecimal("0.2"),
                        usdAmount = BigDecimal("200.00"),
                        orderTxid = "OID-Y",
                    )
                // Page 0 ends with X; page 1 starts with the same X then Y (shifted window).
                val page0 =
                    List(49) { idx ->
                        fillX.copy(
                            timestamp = Instant.ofEpochSecond(1700000100L + idx),
                            volume = BigDecimal("0.01"),
                            usdAmount = BigDecimal("10.00"),
                            orderTxid = "OID-PAD-$idx",
                        )
                    } + fillX
                val page1 = listOf(fillX, fillY)

                coEvery { krakenService.getTradeHistory(startSec = any(), offset = 0) } returns page0
                coEvery { krakenService.getTradeHistory(startSec = any(), offset = 50) } returns page1
                coEvery { krakenService.getTradeHistory(startSec = any(), offset = 100) } returns emptyList()

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                coVerify(exactly = 1) { repository.saveTrade(fillX) }
                coVerify(exactly = 1) { repository.saveTrade(fillY) }
            }
        }

        // CQ-8-M2 / #99: first sync with no real fills writes a watermark.
        "syncTradesFromKraken_WritesWatermarkWhenLatestTradeTimeIsNull" {
            runTest {
                val service = createService()
                coEvery { repository.isHistorySeeded() } returns true
                coEvery { repository.getLatestTradeTime() } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { repository.getSyncMetadata("sync_watermark_epoch_sec") } returns null
                coEvery { krakenService.getTradeHistory(startSec = any(), offset = 0) } returns emptyList()

                service.syncTradesFromKraken()

                coVerify(exactly = 1) { krakenService.getTradeHistory(startSec = any(), offset = 0) }
                coVerify(exactly = 1) {
                    repository.setSyncMetadata(
                        "sync_watermark_epoch_sec",
                        match { it.toLongOrNull() != null },
                    )
                }
            }
        }

        "syncTradesFromKraken_BoundsInitialPullAndReconciliationWindow" {
            runTest {
                val fixedNow = Instant.parse("2033-05-01T12:00:00Z")
                val expectedQueryStart = fixedNow.minus(96, ChronoUnit.DAYS)
                val expectedStartSec = expectedQueryStart.epochSecond
                var observedQueryStart: Instant? = null

                coEvery { repository.isHistorySeeded() } returns false
                coEvery { repository.getLatestTradeTime() } returns null
                coEvery {
                    repository.getSyncMetadata(SyncMetadataKeys.SYNC_WATERMARK_EPOCH_SEC)
                } returns null
                coEvery { repository.getTradesInRange(any(), any()) } coAnswers {
                    observedQueryStart = firstArg()
                    emptyList()
                }
                coEvery { krakenService.getTradeHistory(any(), 0) } coAnswers {
                    firstArg<Long?>() shouldBe expectedStartSec
                    emptyList()
                }

                createService(syncNowProvider = { fixedNow }).syncTradesFromKraken()

                observedQueryStart shouldBe expectedQueryStart
                coVerify(exactly = 1) {
                    repository.getTradesInRange(expectedQueryStart, fixedNow.plusSeconds(300))
                }
                coVerify(exactly = 1) {
                    krakenService.getTradeHistory(expectedStartSec, 0)
                }
            }
        }

        // CQ-8-M2 / #99: watermark alone (no real fills) drives incremental startSec, not EPOCH.
        "syncTradesFromKraken_UsesWatermarkWhenOnlyDryRunLocalsExist" {
            runTest {
                val watermarkSec = 1_700_000_000L
                val service = createService()
                coEvery { repository.isHistorySeeded() } returns true
                coEvery { repository.getLatestTradeTime() } returns null
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery {
                    repository.getSyncMetadata("sync_watermark_epoch_sec")
                } returns watermarkSec.toString()
                coEvery { krakenService.getTradeHistory(any(), any()) } returns emptyList()

                service.syncTradesFromKraken()

                val expectedStart = watermarkSec - 300
                coVerify(exactly = 1) {
                    krakenService.getTradeHistory(startSec = expectedStart, offset = 0)
                }
                coVerify(exactly = 0) {
                    krakenService.getTradeHistory(startSec = null, offset = any())
                }
            }
        }

        // CQ-8-M2: when real fills exist, prefer latestTradeTime over a newer wall-clock watermark.
        "syncTradesFromKraken_PrefersLatestTradeTimeOverNewerWatermark" {
            runTest {
                val latestTradeSec = 1_700_000_000L
                val newerWatermarkSec = latestTradeSec + 3_600L
                val service = createService()
                coEvery { repository.isHistorySeeded() } returns true
                coEvery { repository.getLatestTradeTime() } returns Instant.ofEpochSecond(latestTradeSec)
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()
                coEvery {
                    repository.getSyncMetadata("sync_watermark_epoch_sec")
                } returns newerWatermarkSec.toString()
                coEvery { krakenService.getTradeHistory(any(), any()) } returns emptyList()

                service.syncTradesFromKraken()

                val expectedStart = latestTradeSec - 300
                coVerify(exactly = 1) {
                    krakenService.getTradeHistory(startSec = expectedStart, offset = 0)
                }
                coVerify(exactly = 0) {
                    krakenService.getTradeHistory(startSec = newerWatermarkSec - 300, offset = any())
                }
            }
        }

        "syncTradesFromKraken_SeededButNoTrades" {
            runTest {
                val service = createService()
                coEvery { repository.isHistorySeeded() } returns true
                coEvery { repository.getLatestTradeTime() } returns null
                coEvery { krakenService.getTradeHistory(startSec = any(), offset = 0) } returns emptyList()

                service.syncTradesFromKraken()
                coVerify(exactly = 1) { krakenService.getTradeHistory(startSec = any(), offset = 0) }
            }
        }

        "syncTradesFromKraken_MultipleBatches" {
            runTest {
                val service = createService()
                coEvery { repository.isHistorySeeded() } returns true
                coEvery { repository.getLatestTradeTime() } returns null

                val batch1 = List(50) {
                    TestFixtures.tradeRecord(
                        timestamp = Instant.now(),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal.ONE,
                        usdAmount = BigDecimal.TEN,
                    )
                }

                coEvery { krakenService.getTradeHistory(startSec = any(), offset = 0) } returns batch1
                coEvery { krakenService.getTradeHistory(startSec = any(), offset = 50) } returns emptyList()

                service.syncTradesFromKraken()
                coVerify(exactly = 1) { krakenService.getTradeHistory(startSec = any(), offset = 0) }
                coVerify(exactly = 1) { krakenService.getTradeHistory(startSec = any(), offset = 50) }
            }
        }

        "syncTradesFromKraken_MatchingExactTradeSkipsReconciliation" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns true
                val latestTime = Instant.ofEpochSecond(1700000000)
                coEvery { repository.getLatestTradeTime() } returns latestTime

                val localTrade = TestFixtures.tradeRecord(
                    timestamp = latestTime,
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(localTrade)

                val apiTrade = localTrade.copy()

                coEvery { krakenService.getTradeHistory(any(), 0) } returns listOf(apiTrade)
                coEvery { krakenService.getTradeHistory(any(), 50) } returns emptyList()

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                coVerify(exactly = 0) { repository.updateTrade(any(), any()) }
                coVerify(exactly = 0) { repository.saveTrade(any()) }
            }
        }

        "CQ-10-L2: sync reconciles a legacy estimate without overwriting a distinct API fill" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns true
                val latestTime = Instant.ofEpochSecond(1700000000)
                coEvery { repository.getLatestTradeTime() } returns latestTime

                val persistedApiFill = TestFixtures.tradeRecord(
                    timestamp = latestTime,
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal("10.00"),
                    price = BigDecimal("10.00"),
                    fee = BigDecimal("0.02"),
                    source = TradeSource.API_FILL,
                    id = 1,
                    orderTxid = "OID-A",
                )
                val legacyLocalEstimate = persistedApiFill.copy(
                    timestamp = latestTime.plusSeconds(1),
                    usdAmount = BigDecimal("10.05"),
                    expectedPrice = BigDecimal("10.05"),
                    slippagePercent = BigDecimal.ZERO,
                    source = null,
                    id = 2,
                    cycleId = "cycle-local",
                    orderTxid = "OID-B",
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns
                    listOf(persistedApiFill, legacyLocalEstimate)

                val newApiFill = persistedApiFill.copy(
                    timestamp = latestTime.plusSeconds(2),
                    usdAmount = BigDecimal("10.04"),
                    price = BigDecimal("10.04"),
                    fee = BigDecimal("0.03"),
                    id = null,
                    orderTxid = "OID-B",
                )
                coEvery { krakenService.getTradeHistory(any(), 0) } returns listOf(newApiFill)

                val reconciledSlot = slot<TradeRecord>()
                coEvery { repository.updateTrade(legacyLocalEstimate, capture(reconciledSlot)) } just Runs

                createService().syncTradesFromKraken()

                coVerify(exactly = 0) { repository.updateTrade(persistedApiFill, any()) }
                coVerify(exactly = 1) { repository.updateTrade(legacyLocalEstimate, any()) }
                reconciledSlot.captured.source shouldBe TradeSource.API_FILL
                reconciledSlot.captured.orderTxid shouldBe "OID-B"
                reconciledSlot.captured.cycleId shouldBe "cycle-local"
                coVerify(exactly = 0) { repository.saveTrade(any()) }
            }
        }

        "CQ-10-L2: exact persisted API fill wins before a nearby local estimate" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns true
                val latestTime = Instant.ofEpochSecond(1700000000)
                coEvery { repository.getLatestTradeTime() } returns latestTime
                val persistedApiFill = TestFixtures.tradeRecord(
                    timestamp = latestTime,
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    price = BigDecimal.TEN,
                    fee = BigDecimal("0.02"),
                    source = TradeSource.API_FILL,
                    id = 1,
                    orderTxid = "OID-EXACT",
                )
                val nearbyEstimate = persistedApiFill.copy(
                    timestamp = latestTime.plusSeconds(1),
                    source = TradeSource.LOCAL_ESTIMATE,
                    id = 2,
                    cycleId = "cycle-nearby",
                    orderTxid = "LOCAL-OID",
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns
                    listOf(nearbyEstimate, persistedApiFill)
                coEvery { krakenService.getTradeHistory(any(), 0) } returns
                    listOf(persistedApiFill.copy(id = null))

                createService().syncTradesFromKraken()

                coVerify(exactly = 0) { repository.updateTrade(any(), any()) }
                coVerify(exactly = 0) { repository.saveTrade(any()) }
            }
        }

        "CQ-10-L6: preserves distinct Kraken fill ids for economically identical order legs" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns true
                val timestamp = Instant.ofEpochSecond(1700000000)
                coEvery { repository.getLatestTradeTime() } returns timestamp
                coEvery { repository.getTradesInRange(any(), any()) } returns emptyList()

                val firstLeg = TestFixtures.tradeRecord(
                    timestamp = timestamp,
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    price = BigDecimal.TEN,
                    fee = BigDecimal("0.02"),
                    source = TradeSource.API_FILL,
                    orderTxid = "ORDER-SHARED",
                    tradeId = "TRADE-ONE",
                )
                val secondLeg = firstLeg.copy(tradeId = "TRADE-TWO")
                coEvery { krakenService.getTradeHistory(any(), 0) } returns listOf(firstLeg, secondLeg)

                createService().syncTradesFromKraken()

                coVerify(exactly = 1) { repository.saveTrade(firstLeg) }
                coVerify(exactly = 1) { repository.saveTrade(secondLeg) }
            }
        }

        "CQ-10-L7: preserves an ambiguous legacy row when its refetched API fill matches exactly" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns true
                val timestamp = Instant.ofEpochSecond(1700000000)
                coEvery { repository.getLatestTradeTime() } returns timestamp
                val legacyUnknown = TestFixtures.tradeRecord(
                    timestamp = timestamp,
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    price = BigDecimal.TEN,
                    fee = BigDecimal("0.02"),
                    id = 1,
                    orderTxid = "ORDER-LEGACY",
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(legacyUnknown)
                coEvery { krakenService.getTradeHistory(any(), 0) } returns
                    listOf(legacyUnknown.copy(id = null, source = TradeSource.API_FILL, tradeId = "TRADE-LEGACY"))

                createService().syncTradesFromKraken()

                coVerify(exactly = 0) { repository.updateTrade(any(), any()) }
                coVerify(exactly = 0) { repository.saveTrade(any()) }
            }
        }

        "syncTradesFromKraken_ReconcilesUsdAmountDifference" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns true
                val latestTime = Instant.ofEpochSecond(1700000000)
                coEvery { repository.getLatestTradeTime() } returns latestTime

                val localTrade = TestFixtures.tradeRecord(
                    timestamp = latestTime,
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    source = TradeSource.LOCAL_ESTIMATE,
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(localTrade)

                val apiTrade =
                    localTrade.copy(
                        usdAmount = BigDecimal.valueOf(11),
                        source = TradeSource.API_FILL,
                    )

                coEvery { krakenService.getTradeHistory(any(), 0) } returns listOf(apiTrade)
                coEvery { krakenService.getTradeHistory(any(), 50) } returns emptyList()

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                coVerify(exactly = 1) {
                    repository.updateTrade(
                        localTrade,
                        match {
                            !it.dryRun && it.source == TradeSource.API_FILL
                        },
                    )
                }
            }
        }

        "syncTradesFromKraken_ReconcilesTimestampDifference" {
            runTest {
                coEvery { repository.isHistorySeeded() } returns true
                val latestTime = Instant.ofEpochSecond(1700000000)
                coEvery { repository.getLatestTradeTime() } returns latestTime

                val localTrade = TestFixtures.tradeRecord(
                    timestamp = latestTime,
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal.TEN,
                    source = TradeSource.LOCAL_ESTIMATE,
                )
                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(localTrade)

                val apiTrade =
                    localTrade.copy(
                        timestamp = latestTime.minusMillis(500),
                        source = TradeSource.API_FILL,
                    )

                coEvery { krakenService.getTradeHistory(any(), 0) } returns listOf(apiTrade)
                coEvery { krakenService.getTradeHistory(any(), 50) } returns emptyList()

                val tradeHistoryService = createService()
                tradeHistoryService.syncTradesFromKraken()

                coVerify(exactly = 1) {
                    repository.updateTrade(
                        localTrade,
                        match {
                            !it.dryRun && it.source == TradeSource.API_FILL
                        },
                    )
                }
            }
        }

        "syncTradesFromKraken_BlankApiKey" {
            runTest {
                val appConfig = AppConfig(
                    kraken = KrakenCredentials("", TestFixtures.TRADE_HISTORY_API_SECRET),
                    settings = TestFixtures.settings(
                        dryRun = false,
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
                    ),
                    allocations = emptyList(),
                )
                val service = createService()
                every { configService.getConfig() } returns appConfig
                service.syncTradesFromKraken()

                coVerify(exactly = 0) { krakenService.getTradeHistory(any(), any()) }
            }
        }

        "syncTradesFromKraken_Cancelled" {
            runTest {
                val service = createService()

                coEvery { krakenService.getTradeHistory(any(), any()) } coAnswers {
                    delay(10000.milliseconds)
                    emptyList()
                }

                val job = launch {
                    service.syncTradesFromKraken()
                }
                yield()
                job.cancel()
                job.join()

                verify(exactly = 1) { configService.beginExecutionSession() }
                verify(exactly = 1) { configService.endExecutionSession() }
            }
        }

        "syncTradesFromKraken_TriggersReconstructionWhenSnapshotsEmpty" {
            runTest {
                val appConfig = AppConfig(
                    kraken =
                    KrakenCredentials(
                        TestFixtures.TRADE_HISTORY_API_KEY,
                        TestFixtures.TRADE_HISTORY_API_SECRET,
                    ),

                    settings = TestFixtures.settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 5.0,
                        dustThresholdUSD = 5.0,
                    ),
                    allocations = listOf(
                        Allocation(Asset.BTC, 30.0),
                        Allocation("ETH", 30.0),
                        Allocation("EUR", 20.0),
                        Allocation("DOGE", 10.0),
                        Allocation(TestFixtures.USD, 10.0),
                    ),
                )
                every { configService.getConfig() } returns appConfig

                coEvery { repository.isHistorySeeded() } returns false
                coEvery { repository.getLatestTradeTime() } returns null
                coEvery { repository.getSyncMetadata(TestFixtures.SYNC_OFFSET) } returns null
                coEvery { repository.getSyncMetadata(TestFixtures.SYNC_TOTAL) } returns null

                coEvery { repository.load() } returns emptyList()
                coEvery { repository.getTradeSummaryStats() } returns TradeSummaryStats(
                    totalTradesExecuted = 2L,
                    totalVolumeTraded = BigDecimal.ZERO,
                    totalFeesPaid = BigDecimal.ZERO,
                    latestSnapshotTime = null,
                )

                val apiTrade1 = TestFixtures.tradeRecord(
                    timestamp = Instant.now().minus(2, ChronoUnit.DAYS),
                    pair = TestFixtures.BTCUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.5"),
                    usdAmount = BigDecimal("15000.00"),
                    price = BigDecimal("30000.00"),
                    fee = BigDecimal("15.00"),
                )
                val apiTrade2 = TestFixtures.tradeRecord(
                    timestamp = Instant.now().minus(1, ChronoUnit.DAYS),
                    pair = TestFixtures.BTCUSD,
                    side = TestFixtures.SELL,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.2"),
                    usdAmount = BigDecimal("7000.00"),
                    price = BigDecimal("35000.00"),
                    fee = BigDecimal("7.00"),
                )

                coEvery { krakenService.getTradeHistory(any(), 0) } returns listOf(apiTrade1, apiTrade2)
                coEvery { krakenService.getTradeHistory(any(), 50) } returns emptyList()

                coEvery { repository.getTradesInRange(any(), any()) } returns listOf(apiTrade1, apiTrade2)
                coEvery { repository.saveTrade(any()) } returns 1
                coEvery { repository.updateTrade(any(), any()) } just Runs
                coEvery { repository.setHistorySeeded(true) } just Runs
                coEvery { repository.setSyncMetadata(any(), any()) } just Runs

                val mockBalances = mapOf(
                    Asset.BTC to BigDecimal("1.0"),
                    "XETH" to BigDecimal("2.0"),
                    "ZEUR" to BigDecimal("100.0"),
                    TestFixtures.USD to BigDecimal("5000.0"),
                )
                coEvery { krakenService.getBalances() } returns mockBalances
                every { portfolioAnalyzer.resolveBalance(Asset.BTC, mockBalances) } returns BigDecimal("1.0")
                every { portfolioAnalyzer.resolveBalance("ETH", mockBalances) } returns BigDecimal("2.0")
                every { portfolioAnalyzer.resolveBalance("EUR", mockBalances) } returns BigDecimal("100.0")
                every { portfolioAnalyzer.resolveBalance("DOGE", mockBalances) } returns BigDecimal.ZERO
                every { portfolioAnalyzer.resolveBalance(TestFixtures.USD, mockBalances) } returns BigDecimal("5000.0")
                coEvery { krakenService.getTickerPrices(any()) } returns
                    mapOf(TestFixtures.BTCUSD to BigDecimal("30000.0"))
                val dayStart = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS).epochSecond
                coEvery { krakenService.getOHLC(TestFixtures.BTCUSD, 1440, any()) } returns
                    listOf(Pair(dayStart, BigDecimal("30000.0")))
                coEvery { krakenService.getOHLC("ETHUSD", 1440, any()) } returns emptyList()
                coEvery { krakenService.getOHLC("EURUSD", 1440, any()) } returns emptyList()
                coEvery { krakenService.getOHLC("DOGEUSD", 1440, any()) } returns emptyList()

                val reconstructedSnapshots = slot<List<PortfolioSnapshot>>()
                coEvery { repository.save(capture(reconstructedSnapshots)) } just Runs

                val service = createService()
                every { configService.getConfig() } returns appConfig
                service.syncTradesFromKraken()

                reconstructedSnapshots.captured.first().assets.getValue(Asset.BTC).balance
                    .shouldBeEqualComparingTo(BigDecimal("1.0"))
                reconstructedSnapshots.captured.first().assets.getValue(TestFixtures.USD).balance
                    .shouldBeEqualComparingTo(BigDecimal("5000.0"))
            }
        }
    }
}
