package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

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
            (slippage > BigDecimal.ZERO) shouldBe true
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

        "starts incremental syncs five minutes before the newest stored trade" {
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

            val expectedStartSec = baseTime.minusSeconds(300).epochSecond
            coVerify(exactly = 1) { krakenService.getTradeHistory(expectedStartSec, 0) }
        }

        "triggers snapshot reconstruction after a live seed that added trades" {
            stubStableBackend()
            stubConfig()
            coEvery { krakenService.getTradeHistory(any(), any()) } returns listOf(apiFill(0))
            coEvery { krakenService.getLastTradeHistoryTotalCount() } returns 1

            service().syncTradesFromKraken()

            coVerify(exactly = 1) { reconstructionService.reconstructHistoricalSnapshots(any(), any()) }
        }

        "completes seeding even when snapshot reconstruction fails" {
            stubStableBackend()
            stubConfig()
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
    }
}
