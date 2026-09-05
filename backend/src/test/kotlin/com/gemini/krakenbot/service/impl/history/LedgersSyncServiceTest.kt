package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.impl.SqliteLedgerRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

@Suppress("unused")
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
        ledgerId = "ledger-$index",
        time = time,
        type = KrakenApiConstants.LEDGER_TYPE_STAKING,
        asset = "XBT",
        amount = BigDecimal("0.1"),
    )

    init {
        "skips sync when run again within the 300s throttle window" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            var now = fixedNow
            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { now })

            val requestedTypes = mutableListOf<Set<String>>()
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } coAnswers {
                requestedTypes += arg<Set<String>>(3)
                emptyList()
            }
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0

            service.syncLedgersFromKraken()
            service.syncLedgersFromKraken()

            // Per-type cursors: 9 ledger types each fetch once per sync (offset 0), second sync throttled.
            coVerify(exactly = 9) { krakenService.getLedgers(any(), any(), any(), any()) }
            requestedTypes.toSet() shouldBe LedgersSyncService.SUPPORTED_LEDGER_TYPES.map { setOf(it) }.toSet()
            service.isLedgersSeeded() shouldBe true
            service.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) shouldBe
                LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
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
            service.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) shouldBe
                LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
            service.getSyncMetadata(SyncMetadataKeys.LEDGER_OFFSET) shouldBe SyncMetadataKeys.COMPLETED
            service.getSyncMetadata(SyncMetadataKeys.LEDGER_TOTAL) shouldBe SyncMetadataKeys.COMPLETED
            service.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC) shouldBe
                fixedNow.epochSecond.toString()
            // Per-type cursors: each offset is fetched for all 9 types (duplicates are deduped by unique index).
            coVerify(exactly = 9) { krakenService.getLedgers(any(), 0, any(), any()) }
            coVerify(exactly = 9) { krakenService.getLedgers(any(), 50, any(), any()) }
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

        "failed initial seed stays unseeded and retries the bounded history" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig

            var failureEnabled = true
            val failure = RuntimeException("initial ledger history unavailable")
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } coAnswers {
                if (failureEnabled) throw failure else emptyList()
            }

            var now = fixedNow
            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { now })
            shouldThrow<RuntimeException> { service.syncLedgersFromKraken() } shouldBe failure

            service.isLedgersSeeded() shouldBe false
            service.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) shouldBe null
            service.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC) shouldBe null

            now = fixedNow.plusSeconds(600)
            failureEnabled = false
            service.syncLedgersFromKraken()

            service.isLedgersSeeded() shouldBe true
            service.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) shouldBe
                LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
            val expectedSeedBound = fixedNow.minus(96, ChronoUnit.DAYS).epochSecond
            coVerify {
                krakenService.getLedgers(startSec = expectedSeedBound, offset = 0, endSec = any(), types = any())
            }
        }

        "per-type cursors continue staking after dividend is exhausted" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig

            // Staking has 75 (2 pages), dividend has 10 (1 page) — exercises the `continue` branch when perTypeDone[dividend] becomes true.
            val stakingPageOne = (0 until 50).map { event(it, time = baseTime) }
            val stakingPageTwo = (50 until 75).map { event(it, time = baseTime) }
            val dividendPage = (100 until 110).map {
                event(it, time = baseTime).copy(type = KrakenApiConstants.LEDGER_TYPE_DIVIDEND)
            }

            coEvery {
                krakenService.getLedgers(any(), any(), any(), eq(setOf(KrakenApiConstants.LEDGER_TYPE_STAKING)))
            } coAnswers
                {
                    val offset = secondArg<Int?>() ?: 0
                    if (offset == 0) stakingPageOne else stakingPageTwo
                }
            coEvery {
                krakenService.getLedgers(any(), any(), any(), eq(setOf(KrakenApiConstants.LEDGER_TYPE_DIVIDEND)))
            } returns
                dividendPage
            var callCount = 0
            coEvery { krakenService.getLastLedgerTotalCount() } coAnswers {
                callCount++
                // Calls interleave: staking offset0 -> getLast (1) => 75, dividend offset0 -> getLast (2) => 10, staking offset50 -> getLast (3) => 75
                when (callCount) {
                    1 -> 75
                    2 -> 10
                    3 -> 75
                    else -> 0
                }
            }

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { fixedNow })
            service.syncLedgersFromKraken()

            repository.getLedgersInRange(Instant.EPOCH, fixedNow).size shouldBe 85
            // Dividend done after first page, second iteration only fetches staking pageTwo.
            coVerify(exactly = 1) {
                krakenService.getLedgers(any(), 0, any(), eq(setOf(KrakenApiConstants.LEDGER_TYPE_DIVIDEND)))
            }
            coVerify(exactly = 2) {
                krakenService.getLedgers(any(), any(), any(), eq(setOf(KrakenApiConstants.LEDGER_TYPE_STAKING)))
            }
        }

        "recovering an interrupted seed restarts from 96-day bounded history" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            repository.setSyncMetadata(SyncMetadataKeys.LEDGER_OFFSET, "50")

            coEvery { krakenService.getLedgers(any(), 0, any(), any()) } returns emptyList()
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { fixedNow })
            service.syncLedgersFromKraken()

            val expectedSeedBound = fixedNow.minus(96, ChronoUnit.DAYS).epochSecond
            coVerify {
                krakenService.getLedgers(startSec = expectedSeedBound, offset = 0, endSec = any(), types = any())
            }
        }

        "self-heals orphaned numeric offsets when the store is already seeded" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_VERSION,
                LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
            )
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
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_VERSION,
                LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
            )
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

        "incremental ledger syncs prefer the successful watermark over the newest entry" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_VERSION,
                LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
            )
            repository.saveLedgers(listOf(event(0, time = baseTime)))
            val watermark = baseTime.minusSeconds(3600)
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                watermark.epochSecond.toString(),
            )

            coEvery { krakenService.getLedgers(any(), 0, any(), any()) } returns emptyList()
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { fixedNow })
            service.syncLedgersFromKraken()

            val expectedStartSec = watermark.minusSeconds(300).epochSecond
            coVerify {
                krakenService.getLedgers(startSec = expectedStartSec, offset = 0, endSec = any(), types = any())
            }
        }

        "uses the successful ledger watermark on the next sync and captures a late entry in the overlap" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_VERSION,
                LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
            )
            repository.saveLedgers(listOf(event(0, time = baseTime)))

            var now = fixedNow
            coEvery { krakenService.getLastLedgerTotalCount() } answers {
                if (now == fixedNow) 0 else 1
            }
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } coAnswers {
                val types = arg<Set<String>?>(3)
                if (now != fixedNow && types == setOf(KrakenApiConstants.LEDGER_TYPE_STAKING)) {
                    listOf(event(1, time = fixedNow.minusSeconds(120)))
                } else {
                    emptyList()
                }
            }

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { now })
            service.syncLedgersFromKraken()

            now = fixedNow.plusSeconds(600)
            service.syncLedgersFromKraken()

            val expectedInitialStart = baseTime.minusSeconds(300).epochSecond
            coVerify(exactly = 9) {
                krakenService.getLedgers(
                    startSec = expectedInitialStart,
                    offset = 0,
                    endSec = any(),
                    types = any(),
                )
            }
            val expectedIncrementalStart = fixedNow.minusSeconds(300).epochSecond
            coVerify(exactly = 9) {
                krakenService.getLedgers(
                    startSec = expectedIncrementalStart,
                    offset = 0,
                    endSec = any(),
                    types = any(),
                )
            }
            repository.getLedgersInRange(Instant.EPOCH, now).map { it.ledgerId } shouldBe
                listOf("ledger-1", "ledger-0")
            service.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC) shouldBe
                now.epochSecond.toString()
        }

        "does not advance the ledger watermark after a failed sync" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_VERSION,
                LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
            )
            repository.saveLedgers(listOf(event(0, time = baseTime)))

            var now = fixedNow
            var failureEnabled = false
            val failure = RuntimeException("ledger history unavailable")
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } coAnswers {
                if (failureEnabled) throw failure else emptyList()
            }

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { now })
            service.syncLedgersFromKraken()
            val firstWatermark = service.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
            firstWatermark shouldBe fixedNow.epochSecond.toString()

            now = fixedNow.plusSeconds(600)
            failureEnabled = true
            shouldThrow<RuntimeException> { service.syncLedgersFromKraken() } shouldBe failure
            service.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC) shouldBe firstWatermark

            now = fixedNow.plusSeconds(1_200)
            failureEnabled = false
            service.syncLedgersFromKraken()
            // The failed retry reaches the first per-type request before
            // throwing: 1 failed call + 9 retry calls at the preserved watermark.
            coVerify(exactly = 10) {
                krakenService.getLedgers(
                    startSec = fixedNow.minusSeconds(300).epochSecond,
                    offset = 0,
                    endSec = any(),
                    types = any(),
                )
            }
            service.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC) shouldBe
                now.epochSecond.toString()
        }

        "simulation sync with no entries does not mark the store seeded or advance the watermark" {
            stubStableBackend()
            every {
                configService.getConfig()
            } returns
                appConfig.copy(
                    settings = appConfig.settings.copy(simulation = true),
                )

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { fixedNow })
            service.syncLedgersFromKraken()

            service.isLedgersSeeded() shouldBe false
            service.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) shouldBe null
            service.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC) shouldBe null
        }

        "bounds initial and unseeded sync to the 96-day lookback window" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } returns emptyList()
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { fixedNow })
            service.syncLedgersFromKraken()

            val expectedSeedBound = fixedNow.minus(96, ChronoUnit.DAYS).epochSecond
            coVerify {
                krakenService.getLedgers(startSec = expectedSeedBound, offset = 0, endSec = any(), types = any())
            }
        }

        "recovering an interrupted seed with non-multiple-of-50 multi-type offset" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            repository.setSyncMetadata(SyncMetadataKeys.LEDGER_OFFSET, "62")

            coEvery { krakenService.getLedgers(any(), 0, any(), any()) } returns emptyList()
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { fixedNow })
            service.syncLedgersFromKraken()

            val expectedSeedBound = fixedNow.minus(96, ChronoUnit.DAYS).epochSecond
            coVerify {
                krakenService.getLedgers(startSec = expectedSeedBound, offset = 0, endSec = any(), types = any())
            }
        }

        "retains ledger entries indefinitely during finalize for lifetime reconstruction" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            repository.saveLedgers(
                listOf(
                    event(0, time = fixedNow.minus(100, ChronoUnit.DAYS)),
                    event(1, time = fixedNow.minus(5, ChronoUnit.DAYS)),
                ),
            )

            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } returns emptyList()
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { fixedNow })
            service.syncLedgersFromKraken()

            // Lifetime retention contract: even century-old entries stay so ATH
            // owner-capital netting and Buy & Hold replay keep full history.
            val remaining = repository.getLedgersInRange(Instant.EPOCH, fixedNow.plus(1, ChronoUnit.DAYS))
            remaining.map { it.ledgerId }.toSet() shouldBe setOf("ledger-0", "ledger-1")
        }

        "existing seeded v1/v2 database triggers bounded backfill across 96 days for newly supported types" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            repository.setLedgersSeeded(true)
            // Stale coverage version (v1 or v2)
            repository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "2")
            repository.saveLedgers(listOf(event(0, time = baseTime)))

            val depositEvent = event(1, time = fixedNow.minus(30, ChronoUnit.DAYS))
                .copy(type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT, asset = "USD", amount = BigDecimal("10000.00"))
            val withdrawalEvent = event(2, time = fixedNow.minus(20, ChronoUnit.DAYS))
                .copy(type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL, asset = "USD", amount = BigDecimal("-1000.00"))
            val transferEvent = event(3, time = fixedNow.minus(10, ChronoUnit.DAYS))
                .copy(type = KrakenApiConstants.LEDGER_TYPE_TRANSFER, asset = "USD", amount = BigDecimal("500.00"))
            val adjustmentEvent = event(4, time = fixedNow.minus(5, ChronoUnit.DAYS))
                .copy(type = KrakenApiConstants.LEDGER_TYPE_ADJUSTMENT, asset = "USD", amount = BigDecimal("50.00"))
            val spendEvent = event(5, time = fixedNow.minus(3, ChronoUnit.DAYS))
                .copy(type = KrakenApiConstants.LEDGER_TYPE_SPEND, asset = "USD", amount = BigDecimal("-5000.00"))
            val receiveEvent = event(6, time = fixedNow.minus(3, ChronoUnit.DAYS))
                .copy(type = KrakenApiConstants.LEDGER_TYPE_RECEIVE, asset = "BTC", amount = BigDecimal("0.10"))
            val earnRewardEvent = event(7, time = fixedNow.minus(2, ChronoUnit.DAYS))
                .copy(
                    type = KrakenApiConstants.LEDGER_TYPE_EARN,
                    subtype = "reward",
                    asset = "ETH",
                    amount = BigDecimal("0.01000000"),
                )

            coEvery { krakenService.getLastLedgerTotalCount() } returns 0
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } coAnswers {
                val types = arg<Set<String>?>(3)
                when (types) {
                    setOf(KrakenApiConstants.LEDGER_TYPE_DEPOSIT) -> listOf(depositEvent)

                    setOf(KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL) -> listOf(withdrawalEvent)

                    setOf(KrakenApiConstants.LEDGER_TYPE_TRANSFER) -> listOf(transferEvent)

                    setOf(KrakenApiConstants.LEDGER_TYPE_ADJUSTMENT) -> listOf(adjustmentEvent)

                    setOf(KrakenApiConstants.LEDGER_TYPE_SPEND) -> listOf(spendEvent)

                    setOf(KrakenApiConstants.LEDGER_TYPE_RECEIVE) -> listOf(receiveEvent)

                    setOf(KrakenApiConstants.LEDGER_TYPE_STAKING) -> listOf(event(0, time = baseTime))

                    setOf(KrakenApiConstants.LEDGER_TYPE_EARN) -> listOf(earnRewardEvent)

                    // duplicate
                    else -> emptyList()
                }
            }

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { fixedNow })
            service.isLedgerCoverageCurrent() shouldBe false

            service.syncLedgersFromKraken()

            service.isLedgerCoverageCurrent() shouldBe true
            service.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) shouldBe
                LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
            val allEvents = repository.getLedgersInRange(Instant.EPOCH, fixedNow.plusSeconds(300))
            allEvents.map { it.ledgerId }.toSet() shouldBe
                setOf("ledger-0", "ledger-1", "ledger-2", "ledger-3", "ledger-4", "ledger-5", "ledger-6", "ledger-7")

            val expectedSeedBound = fixedNow.minus(96, ChronoUnit.DAYS).epochSecond
            coVerify {
                krakenService.getLedgers(startSec = expectedSeedBound, offset = 0, endSec = any(), types = any())
            }
        }

        "failed backfill does not advance coverage version so subsequent sync retries" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "2")

            var failureEnabled = true
            val failure = RuntimeException("Kraken API 503 Service Unavailable")
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } coAnswers {
                if (failureEnabled) throw failure else emptyList()
            }

            var now = fixedNow
            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { now })
            shouldThrow<RuntimeException> { service.syncLedgersFromKraken() } shouldBe failure

            service.isLedgerCoverageCurrent() shouldBe false
            service.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) shouldBe "2"

            // Retry after failure
            now = fixedNow.plusSeconds(600)
            failureEnabled = false
            service.syncLedgersFromKraken()

            service.isLedgerCoverageCurrent() shouldBe true
            service.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) shouldBe
                LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
        }

        "partial backfill failure across multiple ledger types leaves coverage version stale" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "2")

            val failure = RuntimeException("Kraken API Rate Limit on spend type")
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } coAnswers {
                val types = arg<Set<String>?>(3)
                if (types == setOf(KrakenApiConstants.LEDGER_TYPE_SPEND)) {
                    throw failure
                }
                emptyList()
            }

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { fixedNow })
            shouldThrow<RuntimeException> { service.syncLedgersFromKraken() } shouldBe failure

            service.isLedgerCoverageCurrent() shouldBe false
            service.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) shouldBe "2"
        }

        "partial backfill retries safely without duplicating persisted pages" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "2")

            val firstPage = (0 until 50).map { event(it) }
            val secondPage = listOf(event(50))
            val failure = RuntimeException("Kraken API failed during the second staking page")
            var failureEnabled = true
            var lastTotalCount = 0
            coEvery { krakenService.getLastLedgerTotalCount() } coAnswers { lastTotalCount }
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } coAnswers {
                val types = arg<Set<String>>(3)
                val offset = secondArg<Int?>() ?: 0
                if (types == setOf(KrakenApiConstants.LEDGER_TYPE_STAKING)) {
                    lastTotalCount = 100
                    when (offset) {
                        0 -> firstPage
                        50 -> if (failureEnabled) throw failure else secondPage
                        else -> emptyList()
                    }
                } else {
                    lastTotalCount = 0
                    emptyList()
                }
            }

            var now = fixedNow
            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { now })
            shouldThrow<RuntimeException> { service.syncLedgersFromKraken() } shouldBe failure

            service.isLedgerCoverageCurrent() shouldBe false
            repository.getLedgersInRange(Instant.EPOCH, fixedNow).size shouldBe 50

            now = fixedNow.plusSeconds(600)
            failureEnabled = false
            service.syncLedgersFromKraken()

            service.isLedgerCoverageCurrent() shouldBe true
            val ledgerIds = repository.getLedgersInRange(Instant.EPOCH, now).map { it.ledgerId }
            ledgerIds.toSet() shouldBe (0..50).map { "ledger-$it" }.toSet()
            ledgerIds.size shouldBe 51
        }
    }
}
