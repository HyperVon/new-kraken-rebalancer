@file:OptIn(ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.impl.SqliteLedgerRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant

class LedgersSyncServiceTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val db = DatabaseConfig.init(TestFixtures.MEMORY_)
    private val repository = SqliteLedgerRepositoryImpl(db)
    private val krakenService = mockk<KrakenService>(relaxed = true)
    private val configService = mockk<ConfigService>(relaxed = true)

    private val fixedNow = Instant.parse("2026-07-01T12:00:00Z")
    private val baseTime = Instant.parse("2026-06-25T12:00:00Z")

    private val appConfig =
        AppConfig(
            kraken = KrakenCredentials(TestFixtures.TRADE_HISTORY_API_KEY, TestFixtures.TRADE_HISTORY_API_SECRET),
            settings = TestFixtures.settings(
                dryRun = false,
                simulation = false,
                loopDelaySeconds = 60,
            ),
            allocations = emptyList(),
        )

    private fun stubStableBackend() {
        coEvery { krakenService.withStableBackend(any<suspend (KrakenService) -> Any?>()) } coAnswers {
            val block = firstArg<suspend (KrakenService) -> Any?>()
            block(krakenService)
        }
    }

    private fun event(index: Int, time: Instant = baseTime): LedgerEvent = LedgerEvent(
        refid = "ref-$index",
        time = time,
        type = LedgerEvent.TYPE_STAKING,
        asset = "XBT",
        amount = BigDecimal("0.1"),
    )

    init {
        "skips sync when run again within the 300s throttle window" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            var now = fixedNow
            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { now })

            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } returns emptyList()
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0

            service.syncLedgersFromKraken()
            service.syncLedgersFromKraken()

            coVerify(exactly = 1) { krakenService.getLedgers(any(), any(), any(), any()) }
            service.isLedgersSeeded() shouldBe true
        }

        "skips sync when credentials are missing and simulation is off" {
            stubStableBackend()
            every {
                configService.getConfig()
            } returns
                appConfig.copy(
                    kraken = KrakenCredentials("", ""),
                )

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { fixedNow })
            service.syncLedgersFromKraken()

            coVerify(exactly = 0) { krakenService.getLedgers(any(), any(), any(), any()) }
            service.isLedgersSeeded() shouldBe false
        }

        "paginates with offsets and finalizes the seed with metadata" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig

            val pageOne = (0 until 50).map { event(it) }
            val pageTwo = (50 until 75).map { event(it) }
            coEvery { krakenService.getLedgers(any(), 0, any(), any()) } returns pageOne
            coEvery { krakenService.getLedgers(any(), 50, any(), any()) } returns pageTwo
            coEvery { krakenService.getLastLedgerTotalCount() } returns 75

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { fixedNow })
            service.syncLedgersFromKraken()

            repository.getLedgersInRange(Instant.EPOCH, fixedNow).size shouldBe 75
            service.isLedgersSeeded() shouldBe true
            service.getSyncMetadata(SyncMetadataKeys.LEDGER_OFFSET) shouldBe SyncMetadataKeys.COMPLETED
            service.getSyncMetadata(SyncMetadataKeys.LEDGER_TOTAL) shouldBe SyncMetadataKeys.COMPLETED
            service.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC) shouldBe
                fixedNow.epochSecond.toString()
            coVerify(exactly = 1) { krakenService.getLedgers(any(), 0, any(), any()) }
            coVerify(exactly = 1) { krakenService.getLedgers(any(), 50, any(), any()) }
        }

        "deduplicates the newest-first offset overlap across pages" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig

            val pageOne = (0 until 50).map { event(it) }
            val pageTwo = listOf(event(49)) + (50 until 74).map { event(it) }
            coEvery { krakenService.getLedgers(any(), 0, any(), any()) } returns pageOne
            coEvery { krakenService.getLedgers(any(), 50, any(), any()) } returns pageTwo
            coEvery { krakenService.getLastLedgerTotalCount() } returns 74

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { fixedNow })
            service.syncLedgersFromKraken()

            repository.getLedgersInRange(Instant.EPOCH, fixedNow).size shouldBe 74
        }

        "recovering an interrupted seed restarts from full history with a null start" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            repository.setSyncMetadata(SyncMetadataKeys.LEDGER_OFFSET, "50")

            coEvery { krakenService.getLedgers(any(), 0, any(), any()) } returns emptyList()
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { fixedNow })
            service.syncLedgersFromKraken()

            coVerify { krakenService.getLedgers(startSec = null, offset = 0, endSec = any(), types = any()) }
        }

        "self-heals orphaned numeric offsets when the store is already seeded" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.LEDGER_OFFSET, "50")
            repository.setSyncMetadata(SyncMetadataKeys.LEDGER_TOTAL, "75")
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } returns emptyList()
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { fixedNow })
            service.syncLedgersFromKraken()

            service.getSyncMetadata(SyncMetadataKeys.LEDGER_OFFSET) shouldBe SyncMetadataKeys.COMPLETED
            service.getSyncMetadata(SyncMetadataKeys.LEDGER_TOTAL) shouldBe SyncMetadataKeys.COMPLETED
        }

        "incremental syncs start five minutes before the newest stored entry" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            repository.saveLedgers(listOf(event(0, time = baseTime)))

            coEvery { krakenService.getLedgers(any(), 0, any(), any()) } returns emptyList()
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0

            var now = fixedNow
            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { now })
            service.syncLedgersFromKraken()

            val expectedStartSec = baseTime.minusSeconds(300).epochSecond
            coVerify {
                krakenService.getLedgers(startSec = expectedStartSec, offset = 0, endSec = any(), types = any())
            }
        }

        "simulation sync with no entries does not mark the store seeded or advance the watermark" {
            stubStableBackend()
            every {
                configService.getConfig()
            } returns
                appConfig.copy(settings = TestFixtures.settings(simulation = true, loopDelaySeconds = 60))

            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } returns emptyList()
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { fixedNow })
            service.syncLedgersFromKraken()

            service.isLedgersSeeded() shouldBe false
            service.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC).shouldBeNull()
            coVerify {
                krakenService.getLedgers(startSec = null, offset = 0, endSec = any(), types = any())
            }
        }
    }
}
