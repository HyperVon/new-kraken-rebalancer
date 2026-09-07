package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.OrderSubmissionState
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeReconciliationConflictException
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

@Suppress("unused")
class TradeHistorySyncServiceTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val db = DatabaseConfig.init(TestFixtures.MEMORY_)
    private val repository = SqliteTradeRepositoryImpl(db)
    private val krakenService = mockk<KrakenService>(relaxed = true)
    private val configService = mockk<ConfigService>(relaxed = true)
    private val reconstructionService = mockk<TradeHistoryReconstructionService>(relaxed = true)

    private val fixedNow = Instant.parse("2026-07-01T12:00:00Z")
    private val baseTime = Instant.parse("2026-06-25T12:00:00Z")

    private val appConfig = TestFixtures.config(
        kraken = KrakenCredentials(TestFixtures.TRADE_HISTORY_API_KEY, TestFixtures.TRADE_HISTORY_API_SECRET),
        settings = TestFixtures.settings(dryRun = false, simulation = false, loopDelaySeconds = 60),
        allocations = emptyList(),
    )

    private fun service(now: Instant = fixedNow) = TradeHistorySyncService(
        repository = repository,
        krakenService = krakenService,
        configService = configService,
        reconstructionService = reconstructionService,
        nowProvider = { now },
    )

    private fun stubStableBackend() {
        coEvery { krakenService.withStableBackend(any<suspend (KrakenService) -> Any?>()) } coAnswers {
            val block = firstArg<suspend (KrakenService) -> Any?>()
            block(krakenService)
        }
    }

    private fun stubConfig(config: AppConfig = appConfig) {
        every { configService.getConfig() } returns config
    }

    private fun apiFill(index: Int, time: Instant = baseTime, fee: BigDecimal = BigDecimal("1.00")): TradeRecord =
        TestFixtures.tradeRecord(
            timestamp = time,
            pair = "XBTUSD",
            side = "buy",
            symbol = "XBT",
            volume = BigDecimal("0.01"),
            usdAmount = BigDecimal("1000.00"),
            price = BigDecimal("100000.00"),
            fee = fee,
            source = TradeSource.API_FILL,
            tradeId = "api-fill-$index",
        )

    private fun localEstimate(time: Instant = baseTime, dryRun: Boolean = false): TradeRecord =
        TestFixtures.tradeRecord(
            timestamp = time,
            pair = "XBTUSD",
            side = "buy",
            symbol = "XBT",
            volume = BigDecimal("0.01"),
            usdAmount = BigDecimal("1000.00"),
            price = BigDecimal("100000.00"),
            expectedPrice = BigDecimal("99900.00"),
            source = TradeSource.LOCAL_ESTIMATE,
            cycleId = "cycle-1",
            dryRun = dryRun,
        )

    init {
        "scope mismatch blocks trade API reads and persistence" {
            stubStableBackend()
            stubConfig()
            val scopeGuard = mockk<AccountHistoryScopeGuard>()
            coEvery { scopeGuard.validateAccountScope() } returns AccountScopeValidationResult.scopeMismatch(
                current = "account-b-digest",
            )
            val sync = TradeHistorySyncService(
                repository,
                krakenService,
                configService,
                reconstructionService,
                nowProvider = { fixedNow },
                accountHistoryScopeGuard = scopeGuard,
            )

            sync.syncTradesFromKraken()

            coVerify(exactly = 0) { krakenService.getTradeHistory(any(), any()) }
            repository.getTradesInRange(Instant.EPOCH, fixedNow).size shouldBe 0
        }

        "skips sync when run again within the 300s throttle window" {
            stubStableBackend()
            stubConfig()
            coEvery { krakenService.getTradeHistory(any(), any()) } returns emptyList()

            val sync = service()
            sync.syncTradesFromKraken()
            sync.syncTradesFromKraken()

            coVerify(exactly = 1) { krakenService.getTradeHistory(any(), any()) }
        }

        "skips sync when credentials are missing and simulation is off" {
            stubStableBackend()
            stubConfig(TestFixtures.config(kraken = KrakenCredentials("", "")))

            val sync = service()
            sync.syncTradesFromKraken()

            coVerify(exactly = 0) { krakenService.getTradeHistory(any(), any()) }
            sync.isHistorySeeded() shouldBe false
        }

        "seeds paginated history and finalizes with completed metadata" {
            stubStableBackend()
            stubConfig()
            val pageOne = (0 until 50).map { apiFill(it) }
            val pageTwo = listOf(apiFill(50))
            coEvery { krakenService.getTradeHistory(any(), 0) } returns pageOne
            coEvery { krakenService.getTradeHistory(any(), 50) } returns pageTwo
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 51

            val sync = service()
            sync.syncTradesFromKraken()

            repository.getTradesInRange(Instant.EPOCH, fixedNow).size shouldBe 51
            sync.isHistorySeeded() shouldBe true
            sync.getSyncMetadata(SyncMetadataKeys.SYNC_OFFSET) shouldBe SyncMetadataKeys.COMPLETED
            sync.getSyncMetadata(SyncMetadataKeys.SYNC_TOTAL) shouldBe SyncMetadataKeys.COMPLETED
            sync.getSyncMetadata(SyncMetadataKeys.SYNC_WATERMARK_EPOCH_SEC) shouldBe fixedNow.epochSecond.toString()
            val seedBound = fixedNow.minus(96, ChronoUnit.DAYS).epochSecond
            coVerify(exactly = 1) { krakenService.getTradeHistory(seedBound, 0) }
            coVerify(exactly = 1) { krakenService.getTradeHistory(seedBound, 50) }
        }

        "deduplicates a fill re-emitted across page boundaries" {
            stubStableBackend()
            stubConfig()
            val pageOne = (0 until 50).map { apiFill(it) }
            val pageTwo = listOf(apiFill(49))
            coEvery { krakenService.getTradeHistory(any(), 0) } returns pageOne
            coEvery { krakenService.getTradeHistory(any(), 50) } returns pageTwo
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 51

            val sync = service()
            sync.syncTradesFromKraken()

            repository.getTradesInRange(Instant.EPOCH, fixedNow).size shouldBe 50
        }

        "reconciles a local estimate with its API fill instead of double-inserting" {
            stubStableBackend()
            stubConfig()
            repository.saveTrade(localEstimate())
            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(apiFill(0))
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            val sync = service()
            sync.syncTradesFromKraken()

            val rows = repository.getTradesInRange(Instant.EPOCH, fixedNow)
            rows.size shouldBe 1
            val reconciled = rows.single()
            reconciled.source shouldBe TradeSource.API_FILL
            val expectedPrice = reconciled.expectedPrice
            expectedPrice.shouldNotBeNull()
            expectedPrice shouldBeEqualComparingTo BigDecimal("99900.00")
            reconciled.cycleId shouldBe "cycle-1"
            val slippage = reconciled.slippagePercent
            slippage.shouldNotBeNull()
            slippage.shouldBeEqualComparingTo(BigDecimal("0.1001"))
        }

        "keeps an already-persisted settled fill intact when re-fetched" {
            stubStableBackend()
            stubConfig()
            repository.saveTrade(apiFill(0, fee = BigDecimal("1.00")))
            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(apiFill(0, fee = BigDecimal("2.00")))
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            val sync = service()
            sync.syncTradesFromKraken()

            val rows = repository.getTradesInRange(Instant.EPOCH, fixedNow)
            rows.size shouldBe 1
            rows.single().fee shouldBeEqualComparingTo BigDecimal("1.00")
        }

        "keeps an already-persisted identifier-free fill intact when re-fetched" {
            stubStableBackend()
            stubConfig()
            val persisted = apiFill(0).copy(
                tradeId = null,
                orderTxid = null,
                expectedPrice = BigDecimal("99900.00"),
            )
            repository.saveTrade(persisted)
            val fetched = persisted.copy(expectedPrice = null)
            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(fetched)
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            val sync = service()
            sync.syncTradesFromKraken()

            val rows = repository.getTradesInRange(Instant.EPOCH, fixedNow)
            rows.size shouldBe 1
            val expectedPrice = rows.single().expectedPrice
            expectedPrice.shouldNotBeNull()
            expectedPrice shouldBeEqualComparingTo BigDecimal("99900.00")
        }

        "does not deduplicate persisted fills with conflicting trade ids" {
            stubStableBackend()
            stubConfig()
            repository.saveTrade(apiFill(0))
            val fetched = apiFill(1)
            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(fetched)
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            service().syncTradesFromKraken()

            repository.getTradesInRange(Instant.EPOCH, fixedNow).size shouldBe 2
        }

        "does not deduplicate persisted fills with conflicting order ids" {
            stubStableBackend()
            stubConfig()
            val persisted = apiFill(0).copy(tradeId = null, orderTxid = "order-one")
            repository.saveTrade(persisted)
            val fetched = persisted.copy(orderTxid = "order-two")
            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(fetched)
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            service().syncTradesFromKraken()

            repository.getTradesInRange(Instant.EPOCH, fixedNow).size shouldBe 2
        }

        "never rewrites a dry-run estimate into an API fill" {
            stubStableBackend()
            stubConfig()
            repository.saveTrade(localEstimate(dryRun = true))
            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(apiFill(0))
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            val sync = service()
            sync.syncTradesFromKraken()

            val rows = repository.getTradesInRange(Instant.EPOCH, fixedNow)
            rows.size shouldBe 2
            val dryRunRow = rows.single { it.dryRun }
            dryRunRow.source shouldBe TradeSource.LOCAL_ESTIMATE
            rows.single { !it.dryRun }.source shouldBe TradeSource.API_FILL
        }

        "recovers an interrupted seed from the bounded window and completes the markers" {
            stubStableBackend()
            stubConfig()
            repository.setSyncMetadata(SyncMetadataKeys.SYNC_OFFSET, "50")
            coEvery { krakenService.getTradeHistory(any(), any()) } returns emptyList()

            val sync = service()
            sync.syncTradesFromKraken()

            val seedBound = fixedNow.minus(96, ChronoUnit.DAYS).epochSecond
            coVerify(exactly = 1) { krakenService.getTradeHistory(seedBound, 0) }
            sync.isHistorySeeded() shouldBe true
            sync.getSyncMetadata(SyncMetadataKeys.SYNC_OFFSET) shouldBe SyncMetadataKeys.COMPLETED
            sync.getSyncMetadata(SyncMetadataKeys.SYNC_TOTAL) shouldBe SyncMetadataKeys.COMPLETED
        }

        "self-heals orphaned numeric offsets when the store is already seeded" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.SYNC_OFFSET, "50")
            repository.setSyncMetadata(SyncMetadataKeys.SYNC_TOTAL, "75")
            coEvery { krakenService.getTradeHistory(any(), any()) } returns emptyList()

            val sync = service()
            sync.syncTradesFromKraken()

            sync.getSyncMetadata(SyncMetadataKeys.SYNC_OFFSET) shouldBe SyncMetadataKeys.COMPLETED
            sync.getSyncMetadata(SyncMetadataKeys.SYNC_TOTAL) shouldBe SyncMetadataKeys.COMPLETED
        }

        "does not treat a malformed seeded offset as an interrupted seed" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)
            repository.saveTrade(apiFill(0, time = baseTime))
            repository.setSyncMetadata(SyncMetadataKeys.SYNC_OFFSET, "-1")
            coEvery { krakenService.getTradeHistory(any(), any()) } returns emptyList()

            service().syncTradesFromKraken()

            coVerify(exactly = 1) {
                krakenService.getTradeHistory(baseTime.minusSeconds(300).epochSecond, 0)
            }
        }

        "starts incremental syncs five minutes before the successful watermark" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)
            repository.saveTrade(apiFill(0))
            repository.setSyncMetadata(
                SyncMetadataKeys.SYNC_WATERMARK_EPOCH_SEC,
                baseTime.minusSeconds(3600).epochSecond.toString(),
            )
            coEvery { krakenService.getTradeHistory(any(), any()) } returns emptyList()

            val sync = service()
            sync.syncTradesFromKraken()

            val expectedStartSec = baseTime.minusSeconds(3600 + 300).epochSecond
            coVerify(exactly = 1) { krakenService.getTradeHistory(expectedStartSec, 0) }
        }

        "uses the successful watermark on the next sync and captures a late fill in the overlap" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)
            repository.saveTrade(apiFill(0, time = baseTime))

            var now = fixedNow
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1
            coEvery { krakenService.getTradeHistory(any(), any()) } coAnswers {
                if (now == fixedNow) emptyList() else listOf(apiFill(1, time = fixedNow.minusSeconds(120)))
            }

            val sync = TradeHistorySyncService(
                repository = repository,
                krakenService = krakenService,
                configService = configService,
                reconstructionService = reconstructionService,
                nowProvider = { now },
            )
            sync.syncTradesFromKraken()

            now = fixedNow.plusSeconds(600)
            sync.syncTradesFromKraken()

            coVerify(exactly = 1) {
                krakenService.getTradeHistory(baseTime.minusSeconds(300).epochSecond, 0)
            }
            coVerify(exactly = 1) {
                krakenService.getTradeHistory(fixedNow.minusSeconds(300).epochSecond, 0)
            }
            repository.getTradesInRange(Instant.EPOCH, now).map { it.tradeId } shouldBe
                listOf("api-fill-1", "api-fill-0")
            sync.getSyncMetadata(SyncMetadataKeys.SYNC_WATERMARK_EPOCH_SEC) shouldBe
                now.epochSecond.toString()
        }

        "does not advance the trade watermark after a failed sync" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)
            repository.saveTrade(apiFill(0, time = baseTime))

            var now = fixedNow
            var callCount = 0
            val failure = RuntimeException("history unavailable")
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 0
            coEvery { krakenService.getTradeHistory(any(), any()) } coAnswers {
                callCount++
                when (callCount) {
                    1 -> emptyList()
                    2 -> throw failure
                    else -> emptyList()
                }
            }

            val sync = TradeHistorySyncService(
                repository = repository,
                krakenService = krakenService,
                configService = configService,
                reconstructionService = reconstructionService,
                nowProvider = { now },
            )
            sync.syncTradesFromKraken()
            val firstWatermark = sync.getSyncMetadata(SyncMetadataKeys.SYNC_WATERMARK_EPOCH_SEC)
            firstWatermark shouldBe fixedNow.epochSecond.toString()

            now = fixedNow.plusSeconds(600)
            shouldThrow<RuntimeException> { sync.syncTradesFromKraken() } shouldBe failure
            sync.getSyncMetadata(SyncMetadataKeys.SYNC_WATERMARK_EPOCH_SEC) shouldBe firstWatermark

            now = fixedNow.plusSeconds(1_200)
            sync.syncTradesFromKraken()
            coVerify(exactly = 2) {
                krakenService.getTradeHistory(fixedNow.minusSeconds(300).epochSecond, 0)
            }
            sync.getSyncMetadata(SyncMetadataKeys.SYNC_WATERMARK_EPOCH_SEC) shouldBe
                now.epochSecond.toString()
        }

        "triggers snapshot reconstruction after a live seed that added trades when canRebuildSnapshots is true" {
            stubStableBackend()
            stubConfig()
            coEvery { reconstructionService.canRebuildSnapshots() } returns true
            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(apiFill(0))
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            service().syncTradesFromKraken()

            coVerify(exactly = 1) { reconstructionService.reconstructHistoricalSnapshots(any(), any()) }
        }

        "skips snapshot reconstruction during trade sync when ledger coverage is stale or unseeded" {
            stubStableBackend()
            stubConfig()
            coEvery { reconstructionService.canRebuildSnapshots() } returns false
            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(apiFill(0))
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            service().syncTradesFromKraken()

            coVerify(exactly = 0) { reconstructionService.reconstructHistoricalSnapshots(any(), any()) }
        }

        "completes seeding even when snapshot reconstruction fails" {
            stubStableBackend()
            stubConfig()
            coEvery { reconstructionService.canRebuildSnapshots() } returns true
            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(apiFill(0))
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1
            coEvery { reconstructionService.reconstructHistoricalSnapshots(any(), any()) } throws
                RuntimeException("reconstruction boom")

            val sync = service()
            sync.syncTradesFromKraken()

            sync.isHistorySeeded() shouldBe true
            sync.getSyncMetadata(SyncMetadataKeys.SYNC_WATERMARK_EPOCH_SEC) shouldBe fixedNow.epochSecond.toString()
            repository.getTradesInRange(Instant.EPOCH, fixedNow).size shouldBe 1
        }

        "multi-fill sync replaces local aggregate and propagates order metadata to all fill legs (T1 then T2)" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)

            val local = TestFixtures.tradeRecord(
                timestamp = baseTime,
                pair = "XBTUSD",
                side = "buy",
                symbol = "XBT",
                volume = BigDecimal("6.50000000"),
                usdAmount = BigDecimal("195000.00"),
                price = BigDecimal("30000.00"),
                expectedPrice = BigDecimal("30000.00"),
                source = TradeSource.LOCAL_ESTIMATE,
                cycleId = "cycle-1",
                clientOrderId = "client-1",
                orderTxid = "O1",
                success = true,
                dryRun = false,
                submissionState = null,
            )
            repository.saveTrade(local)

            val t1 = TestFixtures.tradeRecord(
                timestamp = baseTime.plusMillis(100),
                pair = "XBTUSD",
                side = "buy",
                symbol = "XBT",
                volume = BigDecimal("3.25000000"),
                usdAmount = BigDecimal("97825.00"),
                price = BigDecimal("30100.00"),
                fee = BigDecimal("15.00"),
                source = TradeSource.API_FILL,
                tradeId = "T1",
                orderTxid = "O1",
                success = true,
                dryRun = false,
            )
            val t2 = TestFixtures.tradeRecord(
                timestamp = baseTime.plusMillis(200),
                pair = "XBTUSD",
                side = "buy",
                symbol = "XBT",
                volume = BigDecimal("3.25000000"),
                usdAmount = BigDecimal("98150.00"),
                price = BigDecimal("30200.00"),
                fee = BigDecimal("15.00"),
                source = TradeSource.API_FILL,
                tradeId = "T2",
                orderTxid = "O1",
                success = true,
                dryRun = false,
            )

            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(t1, t2)
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 2

            service().syncTradesFromKraken()

            val trades = repository.getTradesInRange(Instant.EPOCH, fixedNow)
            trades.size shouldBe 2
            trades.all { it.source == TradeSource.API_FILL } shouldBe true
            trades.map { it.tradeId }.toSet() shouldBe setOf("T1", "T2")
            trades.none { it.source == TradeSource.LOCAL_ESTIMATE } shouldBe true

            val totalVolume = trades.fold(BigDecimal.ZERO) { acc, t -> acc.add(t.volume) }
            val totalUsd = trades.fold(BigDecimal.ZERO) { acc, t -> acc.add(t.usdAmount) }
            val totalFee = trades.fold(BigDecimal.ZERO) { acc, t -> acc.add(t.fee) }
            totalVolume.shouldBeEqualComparingTo(BigDecimal("6.50000000"))
            totalUsd.shouldBeEqualComparingTo(BigDecimal("195975.00"))
            totalFee.shouldBeEqualComparingTo(BigDecimal("30.00"))

            trades.all { it.cycleId == "cycle-1" } shouldBe true
            trades.all { it.clientOrderId == "client-1" } shouldBe true
            trades.all { it.orderTxid == "O1" } shouldBe true
            trades.all {
                it.expectedPrice != null && it.expectedPrice!!.compareTo(BigDecimal("30000.00")) == 0
            } shouldBe
                true
            trades.all { it.slippagePercent != null } shouldBe true

            val fill1 = trades.single { it.tradeId == "T1" }
            fill1.price.shouldBeEqualComparingTo(BigDecimal("30100.00"))
            val fill2 = trades.single { it.tradeId == "T2" }
            fill2.price.shouldBeEqualComparingTo(BigDecimal("30200.00"))

            val stats = repository.getTradeSummaryStats()
            stats.totalTradesExecuted shouldBe 2L
            stats.totalVolumeTraded.shouldBeEqualComparingTo(BigDecimal("195975.00"))
            stats.totalFeesPaid.shouldBeEqualComparingTo(BigDecimal("30.00"))
        }

        "multi-fill sync is order-independent when fills arrive in reverse order (T2 then T1)" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)

            val local = TestFixtures.tradeRecord(
                timestamp = baseTime,
                pair = "XBTUSD",
                side = "buy",
                symbol = "XBT",
                volume = BigDecimal("6.50000000"),
                usdAmount = BigDecimal("195000.00"),
                price = BigDecimal("30000.00"),
                expectedPrice = BigDecimal("30000.00"),
                source = TradeSource.LOCAL_ESTIMATE,
                cycleId = "cycle-1",
                clientOrderId = "client-1",
                orderTxid = "O1",
                success = true,
                dryRun = false,
                submissionState = null,
            )
            repository.saveTrade(local)

            val t1 = TestFixtures.tradeRecord(
                timestamp = baseTime.plusMillis(100),
                pair = "XBTUSD",
                side = "buy",
                symbol = "XBT",
                volume = BigDecimal("3.25000000"),
                usdAmount = BigDecimal("97825.00"),
                price = BigDecimal("30100.00"),
                fee = BigDecimal("15.00"),
                source = TradeSource.API_FILL,
                tradeId = "T1",
                orderTxid = "O1",
                success = true,
                dryRun = false,
            )
            val t2 = TestFixtures.tradeRecord(
                timestamp = baseTime.plusMillis(200),
                pair = "XBTUSD",
                side = "buy",
                symbol = "XBT",
                volume = BigDecimal("3.25000000"),
                usdAmount = BigDecimal("98150.00"),
                price = BigDecimal("30200.00"),
                fee = BigDecimal("15.00"),
                source = TradeSource.API_FILL,
                tradeId = "T2",
                orderTxid = "O1",
                success = true,
                dryRun = false,
            )

            // Return T2 before T1
            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(t2, t1)
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 2

            service().syncTradesFromKraken()

            val trades = repository.getTradesInRange(Instant.EPOCH, fixedNow)
            trades.size shouldBe 2
            trades.all { it.source == TradeSource.API_FILL } shouldBe true
            trades.map { it.tradeId }.toSet() shouldBe setOf("T1", "T2")
            trades.none { it.source == TradeSource.LOCAL_ESTIMATE } shouldBe true

            trades.all { it.cycleId == "cycle-1" } shouldBe true
            trades.all { it.clientOrderId == "client-1" } shouldBe true
            trades.all { it.orderTxid == "O1" } shouldBe true
            trades.all {
                it.expectedPrice != null && it.expectedPrice!!.compareTo(BigDecimal("30000.00")) == 0
            } shouldBe
                true
            trades.all { it.slippagePercent != null } shouldBe true

            val stats = repository.getTradeSummaryStats()
            stats.totalTradesExecuted shouldBe 2L
            stats.totalVolumeTraded.shouldBeEqualComparingTo(BigDecimal("195975.00"))
            stats.totalFeesPaid.shouldBeEqualComparingTo(BigDecimal("30.00"))
        }

        "fails closed with conflict when local estimate for exact orderTxid is incompatible" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)

            val local = TestFixtures.tradeRecord(
                timestamp = baseTime,
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("6.50000000"),
                usdAmount = BigDecimal("56.44"),
                price = BigDecimal("8.68"),
                expectedPrice = BigDecimal("8.68"),
                source = TradeSource.LOCAL_ESTIMATE,
                cycleId = "cycle-link",
                clientOrderId = "cl-link",
                orderTxid = "O1",
                success = true,
                dryRun = false,
                submissionState = null,
            )
            repository.saveTrade(local)

            val incompatibleApiFill = TestFixtures.tradeRecord(
                timestamp = baseTime.plusMillis(100),
                pair = "ETHUSD",
                side = "buy",
                symbol = "ETH",
                volume = BigDecimal("0.50000000"),
                usdAmount = BigDecimal("1500.00"),
                price = BigDecimal("3000.00"),
                fee = BigDecimal("1.50"),
                source = TradeSource.API_FILL,
                tradeId = "T1",
                orderTxid = "O1",
                success = true,
                dryRun = false,
            )

            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(incompatibleApiFill)
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            shouldThrow<TradeReconciliationConflictException> {
                service().syncTradesFromKraken()
            }

            val trades = repository.getTradesInRange(Instant.EPOCH, fixedNow)
            trades.size shouldBe 1
            trades.single().source shouldBe TradeSource.LOCAL_ESTIMATE
            trades.single().symbol shouldBe "LINK"
            trades.none { it.symbol == "ETH" } shouldBe true
        }

        "fails closed with conflict when multiple local estimates share the same exact orderTxid" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)

            val local1 = TestFixtures.tradeRecord(
                timestamp = baseTime,
                pair = "XBTUSD",
                side = "buy",
                symbol = "XBT",
                volume = BigDecimal("3.25000000"),
                usdAmount = BigDecimal("97500.00"),
                price = BigDecimal("30000.00"),
                expectedPrice = BigDecimal("30000.00"),
                source = TradeSource.LOCAL_ESTIMATE,
                cycleId = "cycle-1",
                clientOrderId = "cl-1",
                orderTxid = "O1",
                success = true,
                dryRun = false,
                submissionState = null,
            )
            val local2 = TestFixtures.tradeRecord(
                timestamp = baseTime.plusMillis(10),
                pair = "XBTUSD",
                side = "buy",
                symbol = "XBT",
                volume = BigDecimal("3.25000000"),
                usdAmount = BigDecimal("97500.00"),
                price = BigDecimal("30000.00"),
                expectedPrice = BigDecimal("30000.00"),
                source = TradeSource.LOCAL_ESTIMATE,
                cycleId = "cycle-2",
                clientOrderId = "cl-2",
                orderTxid = "O1",
                success = true,
                dryRun = false,
                submissionState = null,
            )
            repository.saveTrade(local1)
            repository.saveTrade(local2)

            val apiFill = TestFixtures.tradeRecord(
                timestamp = baseTime.plusMillis(100),
                pair = "XBTUSD",
                side = "buy",
                symbol = "XBT",
                volume = BigDecimal("3.25000000"),
                usdAmount = BigDecimal("97500.00"),
                price = BigDecimal("30000.00"),
                fee = BigDecimal("15.00"),
                source = TradeSource.API_FILL,
                tradeId = "T1",
                orderTxid = "O1",
                success = true,
                dryRun = false,
            )

            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(apiFill)
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            shouldThrow<TradeReconciliationConflictException> {
                service().syncTradesFromKraken()
            }

            val trades = repository.getTradesInRange(Instant.EPOCH, fixedNow)
            trades.size shouldBe 2
            trades.all { it.source == TradeSource.LOCAL_ESTIMATE } shouldBe true
            trades.none { it.source == TradeSource.API_FILL } shouldBe true
        }

        "fails closed with conflict when compatible and incompatible local rows share the same exact orderTxid" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)

            val localCompatible = TestFixtures.tradeRecord(
                timestamp = baseTime,
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("6.50000000"),
                usdAmount = BigDecimal("56.44"),
                price = BigDecimal("8.68"),
                expectedPrice = BigDecimal("8.68"),
                source = TradeSource.LOCAL_ESTIMATE,
                cycleId = "cycle-link",
                clientOrderId = "cl-link",
                orderTxid = "O1",
                success = true,
                dryRun = false,
                submissionState = null,
            )
            val localIncompatible = TestFixtures.tradeRecord(
                timestamp = baseTime.plusMillis(10),
                pair = "ETHUSD",
                side = "buy",
                symbol = "ETH",
                volume = BigDecimal("0.50000000"),
                usdAmount = BigDecimal("1500.00"),
                price = BigDecimal("3000.00"),
                expectedPrice = BigDecimal("3000.00"),
                source = TradeSource.LOCAL_ESTIMATE,
                cycleId = "cycle-eth",
                clientOrderId = "cl-eth",
                orderTxid = "O1",
                success = true,
                dryRun = false,
                submissionState = null,
            )
            repository.saveTrade(localCompatible)
            repository.saveTrade(localIncompatible)

            val apiFill = TestFixtures.tradeRecord(
                timestamp = baseTime.plusMillis(100),
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("6.50000000"),
                usdAmount = BigDecimal("56.44"),
                price = BigDecimal("8.68"),
                fee = BigDecimal("0.10"),
                source = TradeSource.API_FILL,
                tradeId = "T1",
                orderTxid = "O1",
                success = true,
                dryRun = false,
            )

            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(apiFill)
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            shouldThrow<TradeReconciliationConflictException> {
                service().syncTradesFromKraken()
            }

            val trades = repository.getTradesInRange(Instant.EPOCH, fixedNow)
            trades.size shouldBe 2
            trades.all { it.source == TradeSource.LOCAL_ESTIMATE } shouldBe true
        }

        "fails closed with conflict when cached order metadata is incompatible with incoming API fill" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)

            // Persist a settled API fill for O1 (LINK SELL)
            val settledFill = TestFixtures.tradeRecord(
                timestamp = baseTime,
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("3.25000000"),
                usdAmount = BigDecimal("28.22"),
                price = BigDecimal("8.68"),
                fee = BigDecimal("0.05"),
                source = TradeSource.API_FILL,
                tradeId = "T-SETTLED",
                orderTxid = "O1",
                cycleId = "cycle-link",
                clientOrderId = "cl-link",
                expectedPrice = BigDecimal("8.68"),
                success = true,
                dryRun = false,
                submissionState = null,
            )
            repository.saveTrade(settledFill)

            // Incoming API fill claims O1 but is ETH BUY
            val badFill = TestFixtures.tradeRecord(
                timestamp = baseTime.plusMillis(100),
                pair = "ETHUSD",
                side = "buy",
                symbol = "ETH",
                volume = BigDecimal("0.50000000"),
                usdAmount = BigDecimal("1500.00"),
                price = BigDecimal("3000.00"),
                fee = BigDecimal("1.50"),
                source = TradeSource.API_FILL,
                tradeId = "T-BAD",
                orderTxid = "O1",
                success = true,
                dryRun = false,
            )

            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(settledFill, badFill)
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 2

            shouldThrow<TradeReconciliationConflictException> {
                service().syncTradesFromKraken()
            }

            val trades = repository.getTradesInRange(Instant.EPOCH, fixedNow)
            trades.size shouldBe 1
            trades.none { it.tradeId == "T-BAD" } shouldBe true
        }

        "inserts authoritative API fill normally when no local estimate exists for orderTxid" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)

            val unassociatedApiFill = TestFixtures.tradeRecord(
                timestamp = baseTime.plusMillis(100),
                pair = "XBTUSD",
                side = "buy",
                symbol = "XBT",
                volume = BigDecimal("0.01000000"),
                usdAmount = BigDecimal("1000.00"),
                price = BigDecimal("100000.00"),
                fee = BigDecimal("1.00"),
                source = TradeSource.API_FILL,
                tradeId = "T-UNASSOCIATED",
                orderTxid = "O-EXTERNAL",
                success = true,
                dryRun = false,
            )

            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(unassociatedApiFill)
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            service().syncTradesFromKraken()

            val trades = repository.getTradesInRange(Instant.EPOCH, fixedNow)
            trades.size shouldBe 1
            val saved = trades.single()
            saved.tradeId shouldBe "T-UNASSOCIATED"
            saved.orderTxid shouldBe "O-EXTERNAL"
            saved.source shouldBe TradeSource.API_FILL
            saved.cycleId shouldBe null
            saved.expectedPrice shouldBe null
        }

        "fails closed when multiple unkeyed local estimates match heuristic criteria" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)

            val local1 = TestFixtures.tradeRecord(
                timestamp = baseTime,
                pair = "XBTUSD",
                side = "buy",
                symbol = "XBT",
                volume = BigDecimal("0.01000000"),
                usdAmount = BigDecimal("1000.00"),
                price = BigDecimal("100000.00"),
                expectedPrice = BigDecimal("100000.00"),
                source = TradeSource.LOCAL_ESTIMATE,
                orderTxid = null,
                success = true,
                dryRun = false,
                submissionState = null,
            )
            val local2 = TestFixtures.tradeRecord(
                timestamp = baseTime.plusMillis(10),
                pair = "XBTUSD",
                side = "buy",
                symbol = "XBT",
                volume = BigDecimal("0.01000000"),
                usdAmount = BigDecimal("1000.00"),
                price = BigDecimal("100000.00"),
                expectedPrice = BigDecimal("100000.00"),
                source = TradeSource.LOCAL_ESTIMATE,
                orderTxid = null,
                success = true,
                dryRun = false,
                submissionState = null,
            )
            repository.saveTrade(local1)
            repository.saveTrade(local2)

            val apiFill = TestFixtures.tradeRecord(
                timestamp = baseTime.plusMillis(5),
                pair = "XBTUSD",
                side = "buy",
                symbol = "XBT",
                volume = BigDecimal("0.01000000"),
                usdAmount = BigDecimal("1000.00"),
                price = BigDecimal("100000.00"),
                fee = BigDecimal("1.00"),
                source = TradeSource.API_FILL,
                tradeId = "T-HEURISTIC-MULTI",
                orderTxid = null,
                success = true,
                dryRun = false,
            )

            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(apiFill)
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            shouldThrow<TradeReconciliationConflictException> {
                service().syncTradesFromKraken()
            }

            val trades = repository.getTradesInRange(Instant.EPOCH, fixedNow)
            trades.size shouldBe 2
            trades.all { it.source == TradeSource.LOCAL_ESTIMATE } shouldBe true
        }

        "does not pre-populate metadata cache when settled API fills under same orderTxid conflict" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)

            // Persist conflicting settled API fills under O1
            val fill1 = TestFixtures.tradeRecord(
                timestamp = baseTime,
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("3.25000000"),
                usdAmount = BigDecimal("28.22"),
                price = BigDecimal("8.68"),
                fee = BigDecimal("0.05"),
                source = TradeSource.API_FILL,
                tradeId = "T-1",
                orderTxid = "O1",
                cycleId = "cycle-1",
                success = true,
                dryRun = false,
                submissionState = null,
            )
            val fill2 = TestFixtures.tradeRecord(
                timestamp = baseTime.plusMillis(10),
                pair = "ETHUSD",
                side = "buy",
                symbol = "ETH",
                volume = BigDecimal("0.50000000"),
                usdAmount = BigDecimal("1500.00"),
                price = BigDecimal("3000.00"),
                fee = BigDecimal("1.50"),
                source = TradeSource.API_FILL,
                tradeId = "T-2",
                orderTxid = "O1",
                cycleId = "cycle-1",
                success = true,
                dryRun = false,
                submissionState = null,
            )
            repository.saveTrade(fill1)
            repository.saveTrade(fill2)

            val newFill = TestFixtures.tradeRecord(
                timestamp = baseTime.plusMillis(100),
                pair = "XBTUSD",
                side = "buy",
                symbol = "XBT",
                volume = BigDecimal("0.01000000"),
                usdAmount = BigDecimal("1000.00"),
                price = BigDecimal("100000.00"),
                fee = BigDecimal("1.00"),
                source = TradeSource.API_FILL,
                tradeId = "T-NEW",
                orderTxid = "O-NEW",
                success = true,
                dryRun = false,
            )

            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(fill1, fill2, newFill)
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 3

            service().syncTradesFromKraken()

            val trades = repository.getTradesInRange(Instant.EPOCH, fixedNow)
            trades.size shouldBe 3
            trades.single { it.tradeId == "T-NEW" }.source shouldBe TradeSource.API_FILL
        }

        "reconciles unkeyed local estimate with keyed API fill via heuristic matching" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)

            val local = TestFixtures.tradeRecord(
                timestamp = baseTime,
                pair = "XBTUSD",
                side = "buy",
                symbol = "XBT",
                volume = BigDecimal("0.01000000"),
                usdAmount = BigDecimal("1000.00"),
                price = BigDecimal("100000.00"),
                expectedPrice = BigDecimal("100000.00"),
                source = TradeSource.LOCAL_ESTIMATE,
                cycleId = "cycle-heuristic",
                clientOrderId = "cl-heuristic",
                orderTxid = null,
                success = true,
                dryRun = false,
                submissionState = null,
            )
            repository.saveTrade(local)

            val apiFill = TestFixtures.tradeRecord(
                timestamp = baseTime.plusMillis(100),
                pair = "XBTUSD",
                side = "buy",
                symbol = "XBT",
                volume = BigDecimal("0.01000000"),
                usdAmount = BigDecimal("1000.00"),
                price = BigDecimal("100000.00"),
                fee = BigDecimal("1.00"),
                source = TradeSource.API_FILL,
                tradeId = "T-MATCHED",
                orderTxid = "O-KRAKEN",
                success = true,
                dryRun = false,
            )

            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(apiFill)
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            service().syncTradesFromKraken()

            val trades = repository.getTradesInRange(Instant.EPOCH, fixedNow)
            trades.size shouldBe 1
            val reconciled = trades.single()
            reconciled.source shouldBe TradeSource.API_FILL
            reconciled.tradeId shouldBe "T-MATCHED"
            reconciled.cycleId shouldBe "cycle-heuristic"
            reconciled.clientOrderId shouldBe "cl-heuristic"
        }

        "healthy already-persisted fill remains idempotent upon re-fetch" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)

            val fill = TestFixtures.tradeRecord(
                timestamp = baseTime,
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("6.50000000"),
                usdAmount = BigDecimal("56.44"),
                price = BigDecimal("8.68"),
                fee = BigDecimal("0.10"),
                source = TradeSource.API_FILL,
                tradeId = "T1",
                orderTxid = "O1",
                cycleId = "cycle-link",
                clientOrderId = "cl-link",
                expectedPrice = BigDecimal("8.68"),
                success = true,
                dryRun = false,
            )
            repository.saveTrade(fill)

            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(fill)
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            service().syncTradesFromKraken()

            val trades = repository.getTradesInRange(Instant.EPOCH, fixedNow)
            trades.size shouldBe 1
            val single = trades.single()
            single.tradeId shouldBe "T1"
            single.orderTxid shouldBe "O1"
            single.source shouldBe TradeSource.API_FILL
            single.cycleId shouldBe "cycle-link"
        }

        "fails closed when already-persisted fill has compatible successful local aggregate" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)

            val persistedFill = TestFixtures.tradeRecord(
                timestamp = baseTime,
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("3.25000000"),
                usdAmount = BigDecimal("28.22"),
                price = BigDecimal("8.68"),
                fee = BigDecimal("0.05"),
                source = TradeSource.API_FILL,
                tradeId = "T1",
                orderTxid = "O1",
                success = true,
                dryRun = false,
            )
            val staleLocal = TestFixtures.tradeRecord(
                timestamp = baseTime.plusMillis(10),
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("6.50000000"),
                usdAmount = BigDecimal("56.44"),
                price = BigDecimal("8.68"),
                expectedPrice = BigDecimal("8.68"),
                source = TradeSource.LOCAL_ESTIMATE,
                cycleId = "cycle-stale",
                clientOrderId = "cl-stale",
                orderTxid = "O1",
                success = true,
                dryRun = false,
                submissionState = null,
            )
            repository.saveTrade(persistedFill)
            val localId = repository.saveTrade(staleLocal)

            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(persistedFill)
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            val ex = shouldThrow<TradeReconciliationConflictException> {
                service().syncTradesFromKraken()
            }
            ex.message shouldBe "Cannot reconcile Kraken order O1: authoritative API fill (tradeId=T1) is already " +
                "persisted, but un-superseded local order estimate (ID: $localId) claims this order identity."

            val trades = repository.getTradesInRange(Instant.EPOCH, fixedNow)
            trades.size shouldBe 2
            trades.single { it.source == TradeSource.API_FILL }.tradeId shouldBe "T1"
            trades.single { it.source == TradeSource.LOCAL_ESTIMATE }.cycleId shouldBe "cycle-stale"
        }

        "fails closed when already-persisted fill has incompatible local aggregate" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)

            val persistedFill = TestFixtures.tradeRecord(
                timestamp = baseTime,
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("6.50000000"),
                usdAmount = BigDecimal("56.44"),
                price = BigDecimal("8.68"),
                fee = BigDecimal("0.10"),
                source = TradeSource.API_FILL,
                tradeId = "T1",
                orderTxid = "O1",
                success = true,
                dryRun = false,
            )
            val incompatibleLocal = TestFixtures.tradeRecord(
                timestamp = baseTime.plusMillis(10),
                pair = "ETHUSD",
                side = "buy",
                symbol = "ETH",
                volume = BigDecimal("0.50000000"),
                usdAmount = BigDecimal("1500.00"),
                price = BigDecimal("3000.00"),
                expectedPrice = BigDecimal("3000.00"),
                source = TradeSource.LOCAL_ESTIMATE,
                cycleId = "cycle-eth",
                clientOrderId = "cl-eth",
                orderTxid = "O1",
                success = true,
                dryRun = false,
                submissionState = null,
            )
            repository.saveTrade(persistedFill)
            repository.saveTrade(incompatibleLocal)

            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(persistedFill)
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            shouldThrow<TradeReconciliationConflictException> {
                service().syncTradesFromKraken()
            }

            val trades = repository.getTradesInRange(Instant.EPOCH, fixedNow)
            trades.size shouldBe 2
            trades.single { it.source == TradeSource.API_FILL }.tradeId shouldBe "T1"
            trades.single { it.source == TradeSource.LOCAL_ESTIMATE }.symbol shouldBe "ETH"
        }

        "fails closed when already-persisted fill has multiple local estimates" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)

            val persistedFill = TestFixtures.tradeRecord(
                timestamp = baseTime,
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("6.50000000"),
                usdAmount = BigDecimal("56.44"),
                price = BigDecimal("8.68"),
                fee = BigDecimal("0.10"),
                source = TradeSource.API_FILL,
                tradeId = "T1",
                orderTxid = "O1",
                success = true,
                dryRun = false,
            )
            val localA = TestFixtures.tradeRecord(
                timestamp = baseTime.plusMillis(5),
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("3.25000000"),
                usdAmount = BigDecimal("28.22"),
                price = BigDecimal("8.68"),
                expectedPrice = BigDecimal("8.68"),
                source = TradeSource.LOCAL_ESTIMATE,
                cycleId = "cycle-a",
                orderTxid = "O1",
                success = true,
                dryRun = false,
                submissionState = null,
            )
            val localB = TestFixtures.tradeRecord(
                timestamp = baseTime.plusMillis(10),
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("3.25000000"),
                usdAmount = BigDecimal("28.22"),
                price = BigDecimal("8.68"),
                expectedPrice = BigDecimal("8.68"),
                source = TradeSource.LOCAL_ESTIMATE,
                cycleId = "cycle-b",
                orderTxid = "O1",
                success = true,
                dryRun = false,
                submissionState = null,
            )
            repository.saveTrade(persistedFill)
            repository.saveTrade(localA)
            repository.saveTrade(localB)

            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(persistedFill)
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            shouldThrow<TradeReconciliationConflictException> {
                service().syncTradesFromKraken()
            }

            val trades = repository.getTradesInRange(Instant.EPOCH, fixedNow)
            trades.size shouldBe 3
        }

        "unrelated local estimate with different orderTxid does not conflict with persisted fill" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)

            val persistedFill = TestFixtures.tradeRecord(
                timestamp = baseTime,
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("6.50000000"),
                usdAmount = BigDecimal("56.44"),
                price = BigDecimal("8.68"),
                fee = BigDecimal("0.10"),
                source = TradeSource.API_FILL,
                tradeId = "T1",
                orderTxid = "O1",
                success = true,
                dryRun = false,
            )
            val unrelatedLocal = TestFixtures.tradeRecord(
                timestamp = baseTime.plusMillis(10),
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("6.50000000"),
                usdAmount = BigDecimal("56.44"),
                price = BigDecimal("8.68"),
                expectedPrice = BigDecimal("8.68"),
                source = TradeSource.LOCAL_ESTIMATE,
                cycleId = "cycle-unrelated",
                orderTxid = "O-UNRELATED",
                success = true,
                dryRun = false,
                submissionState = null,
            )
            repository.saveTrade(persistedFill)
            repository.saveTrade(unrelatedLocal)

            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(persistedFill)
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            service().syncTradesFromKraken()

            val trades = repository.getTradesInRange(Instant.EPOCH, fixedNow)
            trades.size shouldBe 2
            trades.single { it.source == TradeSource.API_FILL }.tradeId shouldBe "T1"
            trades.single { it.source == TradeSource.LOCAL_ESTIMATE }.orderTxid shouldBe "O-UNRELATED"
        }

        "recovery journal rows with submissionState are not misclassified as active local estimates" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)

            val persistedFill = TestFixtures.tradeRecord(
                timestamp = baseTime,
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("6.50000000"),
                usdAmount = BigDecimal("56.44"),
                price = BigDecimal("8.68"),
                fee = BigDecimal("0.10"),
                source = TradeSource.API_FILL,
                tradeId = "T1",
                orderTxid = "O1",
                success = true,
                dryRun = false,
            )
            val pendingJournal = TestFixtures.tradeRecord(
                timestamp = baseTime.plusMillis(5),
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("6.50000000"),
                usdAmount = BigDecimal("56.44"),
                price = BigDecimal("8.68"),
                expectedPrice = BigDecimal("8.68"),
                source = TradeSource.LOCAL_ESTIMATE,
                cycleId = "cycle-pending",
                orderTxid = "O1",
                success = true,
                dryRun = false,
                submissionState = OrderSubmissionState.PENDING,
            )
            val uncertainJournal = TestFixtures.tradeRecord(
                timestamp = baseTime.plusMillis(10),
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("6.50000000"),
                usdAmount = BigDecimal("56.44"),
                price = BigDecimal("8.68"),
                expectedPrice = BigDecimal("8.68"),
                source = TradeSource.LOCAL_ESTIMATE,
                cycleId = "cycle-uncertain",
                orderTxid = "O1",
                success = true,
                dryRun = false,
                submissionState = OrderSubmissionState.UNCERTAIN,
            )
            val failedLocal = TestFixtures.tradeRecord(
                timestamp = baseTime.plusMillis(15),
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("6.50000000"),
                usdAmount = BigDecimal("56.44"),
                price = BigDecimal("8.68"),
                expectedPrice = BigDecimal("8.68"),
                source = TradeSource.LOCAL_ESTIMATE,
                cycleId = "cycle-failed",
                orderTxid = "O1",
                success = false,
                dryRun = false,
                submissionState = null,
            )
            repository.saveTrade(persistedFill)
            repository.saveTrade(pendingJournal)
            repository.saveTrade(uncertainJournal)
            repository.saveTrade(failedLocal)

            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(persistedFill)
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            service().syncTradesFromKraken()

            val trades = repository.getTradesInRange(Instant.EPOCH, fixedNow)
            trades.size shouldBe 4
            trades.single { it.source == TradeSource.API_FILL }.tradeId shouldBe "T1"
        }

        "watermark does not advance when sync fails with TradeReconciliationConflictException" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)

            val initialWatermark = 1700000000L
            repository.setSyncMetadata(SyncMetadataKeys.SYNC_WATERMARK_EPOCH_SEC, initialWatermark.toString())

            val local = TestFixtures.tradeRecord(
                timestamp = Instant.ofEpochSecond(initialWatermark + 100),
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("6.50000000"),
                usdAmount = BigDecimal("56.44"),
                price = BigDecimal("8.68"),
                expectedPrice = BigDecimal("8.68"),
                source = TradeSource.LOCAL_ESTIMATE,
                cycleId = "cycle-link",
                clientOrderId = "cl-link",
                orderTxid = "O1",
                success = true,
                dryRun = false,
                submissionState = null,
            )
            repository.saveTrade(local)

            val incompatibleApiFill = TestFixtures.tradeRecord(
                timestamp = Instant.ofEpochSecond(initialWatermark + 200),
                pair = "ETHUSD",
                side = "buy",
                symbol = "ETH",
                volume = BigDecimal("0.50000000"),
                usdAmount = BigDecimal("1500.00"),
                price = BigDecimal("3000.00"),
                fee = BigDecimal("1.50"),
                source = TradeSource.API_FILL,
                tradeId = "T1",
                orderTxid = "O1",
                success = true,
                dryRun = false,
            )

            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(incompatibleApiFill)
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            shouldThrow<TradeReconciliationConflictException> {
                service().syncTradesFromKraken()
            }

            val savedWatermark = repository.getSyncMetadata(SyncMetadataKeys.SYNC_WATERMARK_EPOCH_SEC)
            savedWatermark shouldBe initialWatermark.toString()
        }

        "fails closed when already-persisted fill has incompatible cached metadata" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)

            val fill1 = TestFixtures.tradeRecord(
                timestamp = baseTime,
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("3.25000000"),
                usdAmount = BigDecimal("28.22"),
                price = BigDecimal("8.68"),
                fee = BigDecimal("0.05"),
                source = TradeSource.API_FILL,
                tradeId = "T1",
                orderTxid = "O1",
                cycleId = "cycle-link",
                clientOrderId = "cl-link",
                expectedPrice = BigDecimal("8.68"),
                success = true,
                dryRun = false,
            )
            val fill2IncompatiblePersisted = TestFixtures.tradeRecord(
                timestamp = baseTime.plusMillis(10),
                pair = "ETHUSD",
                side = "buy",
                symbol = "ETH",
                volume = BigDecimal("0.50000000"),
                usdAmount = BigDecimal("1500.00"),
                price = BigDecimal("3000.00"),
                fee = BigDecimal("1.50"),
                source = TradeSource.API_FILL,
                tradeId = "T2-BAD",
                orderTxid = "O1",
                success = true,
                dryRun = false,
            )
            repository.saveTrade(fill1)
            repository.saveTrade(fill2IncompatiblePersisted)

            // Re-emit both fills
            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(fill1, fill2IncompatiblePersisted)
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 2

            val ex = shouldThrow<TradeReconciliationConflictException> {
                service().syncTradesFromKraken()
            }
            ex.message shouldContain "cached local order metadata"

            val trades = repository.getTradesInRange(Instant.EPOCH, fixedNow)
            trades.size shouldBe 2
        }

        "already-persisted unkeyed fill without orderTxid remains idempotent" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)

            val unkeyedFill = TestFixtures.tradeRecord(
                timestamp = baseTime,
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("6.50000000"),
                usdAmount = BigDecimal("56.44"),
                price = BigDecimal("8.68"),
                fee = BigDecimal("0.10"),
                source = TradeSource.API_FILL,
                tradeId = "T-UNKEYED",
                orderTxid = null,
                success = true,
                dryRun = false,
            )
            repository.saveTrade(unkeyedFill)

            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(unkeyedFill)
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            service().syncTradesFromKraken()

            val trades = repository.getTradesInRange(Instant.EPOCH, fixedNow)
            trades.size shouldBe 1
            trades.single().tradeId shouldBe "T-UNKEYED"
        }

        "distinguishes distinct API fill legs under same orderTxid during overlap sync" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)

            val fill1 = TestFixtures.tradeRecord(
                timestamp = baseTime,
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("3.25000000"),
                usdAmount = BigDecimal("28.22"),
                price = BigDecimal("8.68"),
                fee = BigDecimal("0.05"),
                source = TradeSource.API_FILL,
                tradeId = "T1",
                orderTxid = "O1",
                cycleId = "cycle-1",
                success = true,
                dryRun = false,
            )
            val fill2 = TestFixtures.tradeRecord(
                timestamp = baseTime.plusMillis(100),
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("3.25000000"),
                usdAmount = BigDecimal("28.22"),
                price = BigDecimal("8.68"),
                fee = BigDecimal("0.05"),
                source = TradeSource.API_FILL,
                tradeId = "T2",
                orderTxid = "O1",
                cycleId = "cycle-1",
                success = true,
                dryRun = false,
            )
            repository.saveTrade(fill1)

            // Feed fill2 (different tradeId on same orderTxid)
            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(fill2)
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            service().syncTradesFromKraken()

            val trades = repository.getTradesInRange(Instant.EPOCH, fixedNow)
            trades.size shouldBe 2
            trades.map { it.tradeId }.toSet() shouldBe setOf("T1", "T2")
        }

        "identifies legacy API fill by fingerprint when tradeId is absent" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)

            val legacyFill = TestFixtures.tradeRecord(
                timestamp = baseTime,
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("6.50000000"),
                usdAmount = BigDecimal("56.44"),
                price = BigDecimal("8.68"),
                fee = BigDecimal("0.10"),
                source = TradeSource.API_FILL,
                tradeId = null,
                orderTxid = "O-LEGACY",
                success = true,
                dryRun = false,
            )
            repository.saveTrade(legacyFill)

            // Re-emit identical legacy fill
            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(legacyFill)
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            service().syncTradesFromKraken()

            val trades = repository.getTradesInRange(Instant.EPOCH, fixedNow)
            trades.size shouldBe 1
        }

        "does not match legacy API fill by fingerprint when price differs" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)

            val legacyFill = TestFixtures.tradeRecord(
                timestamp = baseTime,
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("6.50000000"),
                usdAmount = BigDecimal("56.44"),
                price = BigDecimal("8.68"),
                fee = BigDecimal("0.10"),
                source = TradeSource.API_FILL,
                tradeId = null,
                orderTxid = null,
                success = true,
                dryRun = false,
            )
            repository.saveTrade(legacyFill)

            val differingFill = legacyFill.copy(
                price = BigDecimal("8.70"),
                usdAmount = BigDecimal("56.55"),
            )

            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(differingFill)
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            service().syncTradesFromKraken()

            val trades = repository.getTradesInRange(Instant.EPOCH, fixedNow)
            trades.size shouldBe 2
        }

        "fails closed when already-persisted fill has multiple local estimates with blank and null candidates present" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)

            val persistedFill = TestFixtures.tradeRecord(
                timestamp = baseTime,
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("6.50000000"),
                usdAmount = BigDecimal("56.44"),
                price = BigDecimal("8.68"),
                fee = BigDecimal("0.10"),
                source = TradeSource.API_FILL,
                tradeId = "T1",
                orderTxid = "O1",
                success = true,
                dryRun = false,
            )
            val local1 = TestFixtures.tradeRecord(
                timestamp = baseTime.plusMillis(10),
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("3.25000000"),
                usdAmount = BigDecimal("28.22"),
                price = BigDecimal("8.68"),
                expectedPrice = BigDecimal("8.68"),
                source = TradeSource.LOCAL_ESTIMATE,
                cycleId = "cycle-1",
                orderTxid = "O1",
                success = true,
                dryRun = false,
                submissionState = null,
            )
            val local2 = local1.copy(
                cycleId = "cycle-2",
            )
            val localWithBlankTxid = local1.copy(
                orderTxid = "   ",
            )
            val localWithNullTxid = local1.copy(
                orderTxid = null,
            )
            repository.saveTrade(persistedFill)
            val id1 = repository.saveTrade(local1)
            val id2 = repository.saveTrade(local2)
            repository.saveTrade(localWithBlankTxid)
            repository.saveTrade(localWithNullTxid)

            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(persistedFill)
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            val ex = shouldThrow<TradeReconciliationConflictException> {
                service().syncTradesFromKraken()
            }
            ex.message shouldContain "IDs: [$id2, $id1]"
        }

        "ignores already-persisted fill when apiTrade orderTxid is blank" {
            stubStableBackend()
            stubConfig()
            repository.setHistorySeeded(true)

            val blankTxidFill = TestFixtures.tradeRecord(
                timestamp = baseTime,
                pair = "LINKUSD",
                side = "sell",
                symbol = "LINK",
                volume = BigDecimal("6.50000000"),
                usdAmount = BigDecimal("56.44"),
                price = BigDecimal("8.68"),
                fee = BigDecimal("0.10"),
                source = TradeSource.API_FILL,
                tradeId = "T-BLANK",
                orderTxid = "   ",
                success = true,
                dryRun = false,
            )
            repository.saveTrade(blankTxidFill)

            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(blankTxidFill)
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            service().syncTradesFromKraken()

            val trades = repository.getTradesInRange(Instant.EPOCH, fixedNow)
            trades.size shouldBe 1
        }
    }
}
