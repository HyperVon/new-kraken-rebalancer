package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.impl.SqliteLedgerRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.InceptionRecoveryStatus
import com.gemini.krakenbot.service.KrakenService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
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
class LedgersSyncServiceTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val db = DatabaseConfig.init(TestFixtures.MEMORY_)
    private val repository = SqliteLedgerRepositoryImpl(db)
    private val tradeRepository = SqliteTradeRepositoryImpl(db)
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
        every { krakenService.hasLastLedgerTotalCount() } returns true
        every { krakenService.hasLastLedgerPageShape() } returns true
    }

    private suspend fun markCompletedRecovery(
        scopeDigest: String,
        requiredStart: Instant,
        recoveryHorizon: Instant = fixedNow,
        ledgerOldest: Instant = requiredStart.minusSeconds(1),
        tradeOldest: Instant = requiredStart.minusSeconds(1),
    ) {
        tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST, scopeDigest)
        tradeRepository.setSyncMetadata(
            SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
            AccountHistoryScopeGuard.CURRENT_BINDING_VERSION,
        )
        tradeRepository.setSyncMetadata(
            SyncMetadataKeys.INCEPTION_RECOVERY_VERSION,
            InceptionRecoveryService.CURRENT_RECOVERY_VERSION,
        )
        tradeRepository.setSyncMetadata(
            SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS,
            InceptionRecoveryStatus.COMPLETE,
        )
        tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, "completed")
        tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_TOTAL, "1")
        tradeRepository.setSyncMetadata(
            SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OLDEST_EPOCH_MS,
            tradeOldest.toEpochMilli().toString(),
        )
        tradeRepository.setSyncMetadata(
            SyncMetadataKeys.INCEPTION_RECOVERY_HORIZON_EPOCH_SEC,
            recoveryHorizon.epochSecond.toString(),
        )
        repository.setSyncMetadata(
            SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS,
            InceptionRecoveryStatus.COMPLETE,
        )
        repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, "completed")
        repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL, "1")
        repository.setSyncMetadata(
            SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OLDEST_EPOCH_MS,
            ledgerOldest.toEpochMilli().toString(),
        )
    }

    private fun event(index: Int, time: Instant = baseTime): LedgerEvent = LedgerEvent(
        ledgerId = "ledger-$index",
        time = time,
        type = KrakenApiConstants.LEDGER_TYPE_STAKING,
        asset = "XBT",
        amount = BigDecimal("0.1"),
    )

    private suspend fun seedLedgerCoverage() {
        repository.setLedgersSeeded(true)
        repository.setSyncMetadata(
            SyncMetadataKeys.LEDGER_COVERAGE_VERSION,
            LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
        )
        repository.setSyncMetadata(
            SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC,
            fixedNow.minus(96, ChronoUnit.DAYS).epochSecond.toString(),
        )
        repository.setSyncMetadata(
            SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
            fixedNow.epochSecond.toString(),
        )
    }

    private suspend fun setTradeReconstructionInterval(start: Instant, through: Instant) {
        tradeRepository.setSyncMetadata(
            SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION,
            TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION,
        )
        tradeRepository.setSyncMetadata(
            SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_START_EPOCH_SEC,
            start.epochSecond.toString(),
        )
        tradeRepository.setSyncMetadata(
            SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC,
            through.epochSecond.toString(),
        )
        tradeRepository.setSyncMetadata(
            SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS,
            start.toEpochMilli().toString(),
        )
    }

    init {
        "scope mismatch blocks ledger API reads and persistence" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "7")
            val scopeGuard = mockk<AccountHistoryScopeGuard>()
            coEvery { scopeGuard.validateAccountScope() } returns AccountScopeValidationResult.scopeMismatch(
                current = "account-b-digest",
            )
            val service = LedgersSyncService(
                repository,
                krakenService,
                configService,
                nowProvider = { fixedNow },
                accountHistoryScopeGuard = scopeGuard,
            )

            service.syncLedgersFromKraken()

            coVerify(exactly = 0) { krakenService.getLedgers(any(), any(), any(), any()) }
            repository.getLedgersInRange(Instant.EPOCH, fixedNow).size shouldBe 0
            repository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) shouldBe "7"
        }

        "skips sync when run again within the 300s throttle window" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            var now = fixedNow
            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { now })

            val requestedTypes = mutableListOf<Set<String>?>()
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } coAnswers {
                requestedTypes += arg<Set<String>?>(3)
                emptyList()
            }
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0

            service.syncLedgersFromKraken()
            service.syncLedgersFromKraken()

            // Unified raw coverage queries with types = null once per sync (offset 0),
            // while the second sync is throttled.
            coVerify(exactly = 1) {
                krakenService.getLedgers(any(), any(), any(), any())
            }
            requestedTypes shouldBe listOf(null)
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
            // Unified coverage queries each offset once with types = null.
            coVerify(exactly = 1) {
                krakenService.getLedgers(any(), 0, any(), any())
            }
            coVerify(exactly = 1) {
                krakenService.getLedgers(any(), 50, any(), any())
            }
        }

        "deduplicates the newest-first offset overlap across pages" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig

            val pageOne = (0 until 50).map { event(it) }
            val pageTwo = listOf(event(49)) + (50 until 74).map { event(it) }
            coEvery { krakenService.getLedgers(any(), 0, any(), any()) } returns pageOne
            coEvery { krakenService.getLedgers(any(), 50, any(), any()) } returns pageTwo
            coEvery { krakenService.getLastLedgerTotalCount() } returns 75

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
            val expectedSeedBound = fixedNow.minus(96, ChronoUnit.DAYS).minusSeconds(1).epochSecond
            coVerify {
                krakenService.getLedgers(startSec = expectedSeedBound, offset = 0, endSec = any(), types = any())
            }
        }

        "paginates multiple raw pages until total count is exhausted" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig

            val pageOne = (0 until 50).map { event(it, time = baseTime) }
            val pageTwo = (50 until 85).map {
                event(it, time = baseTime).copy(type = KrakenApiConstants.LEDGER_TYPE_DIVIDEND)
            }

            var lastTotalCount = 0
            var lastRawPageSize = 0
            coEvery { krakenService.getLastLedgerRawPageSize() } coAnswers { lastRawPageSize }
            coEvery { krakenService.getLastLedgerTotalCount() } coAnswers { lastTotalCount }
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } coAnswers {
                val offset = secondArg<Int?>() ?: 0
                lastTotalCount = 85
                lastRawPageSize = if (offset == 0) 50 else 35
                if (offset == 0) pageOne else pageTwo
            }

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { fixedNow })
            service.syncLedgersFromKraken()

            repository.getLedgersInRange(Instant.EPOCH, fixedNow).size shouldBe 85
            coVerify(exactly = 1) {
                krakenService.getLedgers(any(), 0, any(), any())
            }
            coVerify(exactly = 1) {
                krakenService.getLedgers(any(), 50, any(), any())
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

            val expectedSeedBound = fixedNow.minus(96, ChronoUnit.DAYS).minusSeconds(1).epochSecond
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
            every { krakenService.hasLastLedgerTotalCount() } returns false
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
                if (now != fixedNow) {
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
            coVerify(exactly = 1) {
                krakenService.getLedgers(
                    startSec = expectedInitialStart,
                    offset = 0,
                    endSec = any(),
                    types = any(),
                )
            }
            val expectedIncrementalStart = fixedNow.minusSeconds(300).epochSecond
            coVerify(exactly = 1) {
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
            // 1 failed call + 1 successful retry call.
            coVerify(exactly = 2) {
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

            val expectedSeedBound = fixedNow.minus(96, ChronoUnit.DAYS).minusSeconds(1).epochSecond
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

            val expectedSeedBound = fixedNow.minus(96, ChronoUnit.DAYS).minusSeconds(1).epochSecond
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

        "existing seeded stale-coverage database triggers bounded backfill across 96 days for newly supported types" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            every { krakenService.hasLastLedgerTotalCount() } returns true
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
            val promotionRewardEvent = event(8, time = fixedNow.minus(1, ChronoUnit.DAYS))
                .copy(
                    type = KrakenApiConstants.LEDGER_TYPE_REWARD,
                    asset = "BTC",
                    amount = BigDecimal("0.02000000"),
                )
            val conversionEvent = event(9, time = fixedNow.minus(1, ChronoUnit.HOURS))
                .copy(
                    type = KrakenApiConstants.LEDGER_TYPE_CONVERSION,
                    refid = "CONVERSION-1",
                    asset = "USDG",
                    amount = BigDecimal("1000.00000000"),
                )

            coEvery { krakenService.getLastLedgerTotalCount() } returns 9
            every { krakenService.getLastLedgerRawPageSize() } returns 9
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } returns listOf(
                depositEvent,
                withdrawalEvent,
                transferEvent,
                adjustmentEvent,
                spendEvent,
                receiveEvent,
                earnRewardEvent,
                promotionRewardEvent,
                conversionEvent,
            )

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { fixedNow })
            service.isLedgerCoverageCurrent() shouldBe false

            service.syncLedgersFromKraken()

            service.isLedgerCoverageCurrent() shouldBe true
            service.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) shouldBe
                LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
            val allEvents = repository.getLedgersInRange(Instant.EPOCH, fixedNow.plusSeconds(300))
            allEvents.map { it.ledgerId }.toSet() shouldBe
                setOf(
                    "ledger-0",
                    "ledger-1",
                    "ledger-2",
                    "ledger-3",
                    "ledger-4",
                    "ledger-5",
                    "ledger-6",
                    "ledger-7",
                    "ledger-8",
                    "ledger-9",
                )

            val expectedSeedBound = fixedNow.minus(96, ChronoUnit.DAYS).minusSeconds(1).epochSecond
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

        "coverage migration backfills from an older configured inception date" {
            stubStableBackend()
            val inception = fixedNow.minus(200, ChronoUnit.DAYS)
            val configured = appConfig.copy(
                settings = appConfig.settings.copy(inceptionDate = inception.toString()),
            )
            every { configService.getConfig() } returns configured
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "7")
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } returns emptyList()

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { fixedNow })
            service.syncLedgersFromKraken()

            coVerify(atLeast = 1) {
                krakenService.getLedgers(
                    startSec = inception.minusSeconds(1).epochSecond,
                    offset = 0,
                    endSec = any(),
                    types = any(),
                )
            }
            service.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) shouldBe
                LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
        }

        "coverage migration accepts a date-only configured inception date" {
            stubStableBackend()
            val inception = fixedNow.minus(200, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS)
            val configured = appConfig.copy(
                settings = appConfig.settings.copy(inceptionDate = inception.toString().substringBefore("T")),
            )
            every { configService.getConfig() } returns configured
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "7")
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } returns emptyList()

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { fixedNow })
            service.syncLedgersFromKraken()

            coVerify(atLeast = 1) {
                krakenService.getLedgers(
                    startSec = inception.minusSeconds(1).epochSecond,
                    offset = 0,
                    endSec = any(),
                    types = any(),
                )
            }
        }

        "coverage migration reuses completed account-scoped recovery without Kraken calls" {
            stubStableBackend()
            val inception = fixedNow.minus(200, ChronoUnit.DAYS)
            val scopeDigest = AccountHistoryScopeGuard.digestAccountScope("account-a")
            every { configService.getConfig() } returns appConfig.copy(
                settings = appConfig.settings.copy(inceptionDate = inception.toString()),
            )
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "7")
            markCompletedRecovery(scopeDigest, requiredStart = inception)
            val scopeGuard = mockk<AccountHistoryScopeGuard>()
            coEvery { scopeGuard.validateAccountScope() } returns AccountScopeValidationResult(
                status = AccountScopeValidationStatus.VALID,
                currentScopeDigest = scopeDigest,
            )

            val service = LedgersSyncService(
                repository = repository,
                krakenService = krakenService,
                configService = configService,
                tradeRepository = tradeRepository,
                nowProvider = { fixedNow },
                accountHistoryScopeGuard = scopeGuard,
            )
            service.syncLedgersFromKraken()

            coVerify(exactly = 0) { krakenService.getLedgers(any(), any(), any(), any()) }
            repository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) shouldBe
                LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
            repository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) shouldBe
                inception.epochSecond.toString()
        }

        "coverage migration falls back when recovery proof is incomplete or insufficient" {
            stubStableBackend()
            val inception = fixedNow.minus(200, ChronoUnit.DAYS)
            val scopeDigest = AccountHistoryScopeGuard.digestAccountScope("account-a")
            every { configService.getConfig() } returns appConfig.copy(
                settings = appConfig.settings.copy(inceptionDate = inception.toString()),
            )
            repository.setLedgersSeeded(true)
            val scopeGuard = mockk<AccountHistoryScopeGuard>()
            var activeScopeDigest: String? = scopeDigest
            coEvery { scopeGuard.validateAccountScope() } coAnswers {
                AccountScopeValidationResult(
                    status = AccountScopeValidationStatus.VALID,
                    currentScopeDigest = activeScopeDigest,
                )
            }
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0
            var apiCalls = 0
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } coAnswers {
                apiCalls++
                emptyList()
            }

            suspend fun fallback(currentDigest: String? = scopeDigest, mutateProof: suspend () -> Unit = {}) {
                activeScopeDigest = currentDigest
                repository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "7")
                markCompletedRecovery(scopeDigest, requiredStart = inception)
                mutateProof()
                val beforeCalls = apiCalls
                LedgersSyncService(
                    repository = repository,
                    krakenService = krakenService,
                    configService = configService,
                    tradeRepository = tradeRepository,
                    nowProvider = { fixedNow },
                    accountHistoryScopeGuard = scopeGuard,
                ).syncLedgersFromKraken()
                apiCalls - beforeCalls shouldBe 1
            }

            fallback(currentDigest = null)
            fallback(currentDigest = "")
            fallback { tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST, "other") }
            fallback {
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
                    "2",
                )
            }
            fallback {
                tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_VERSION, "0")
            }
            fallback {
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS, "IN_PROGRESS")
            }
            fallback {
                tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, "50")
            }
            fallback {
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, "50")
            }
            fallback {
                tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_HORIZON_EPOCH_SEC, "invalid")
            }
            fallback {
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_HORIZON_EPOCH_SEC,
                    inception.minusSeconds(1).epochSecond.toString(),
                )
            }
            fallback {
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_HORIZON_EPOCH_SEC,
                    fixedNow.plusSeconds(1).epochSecond.toString(),
                )
            }
            fallback {
                repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL, "invalid")
            }
            fallback {
                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OLDEST_EPOCH_MS,
                    inception.plusSeconds(1).toEpochMilli().toString(),
                )
            }
            fallback {
                tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_TOTAL, "invalid")
            }
            fallback {
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OLDEST_EPOCH_MS,
                    inception.plusSeconds(1).toEpochMilli().toString(),
                )
            }
        }

        "coverage migration reuses completed empty recovery streams with explicit zero totals" {
            stubStableBackend()
            val inception = fixedNow.minus(200, ChronoUnit.DAYS)
            val scopeDigest = AccountHistoryScopeGuard.digestAccountScope("empty-account")
            every { configService.getConfig() } returns appConfig.copy(
                settings = appConfig.settings.copy(inceptionDate = inception.toString()),
            )
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "7")
            markCompletedRecovery(scopeDigest, requiredStart = inception)
            tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_TOTAL, "0")
            tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OLDEST_EPOCH_MS, "")
            repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL, "0")
            repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OLDEST_EPOCH_MS, "")
            val scopeGuard = mockk<AccountHistoryScopeGuard>()
            coEvery { scopeGuard.validateAccountScope() } returns AccountScopeValidationResult(
                status = AccountScopeValidationStatus.VALID,
                currentScopeDigest = scopeDigest,
            )

            val service = LedgersSyncService(
                repository = repository,
                krakenService = krakenService,
                configService = configService,
                tradeRepository = tradeRepository,
                nowProvider = { fixedNow },
                accountHistoryScopeGuard = scopeGuard,
            )
            service.syncLedgersFromKraken()

            coVerify(exactly = 0) { krakenService.getLedgers(any(), any(), any(), any()) }
            repository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) shouldBe
                LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
        }

        "coverage migration does not bridge an unproven gap with an ordinary watermark" {
            stubStableBackend()
            val inception = fixedNow.minus(200, ChronoUnit.DAYS)
            val recoveryHorizon = fixedNow.minus(120, ChronoUnit.DAYS)
            val scopeDigest = AccountHistoryScopeGuard.digestAccountScope("account-a")
            every { configService.getConfig() } returns appConfig.copy(
                settings = appConfig.settings.copy(inceptionDate = inception.toString()),
            )
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "7")
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                fixedNow.minus(10, ChronoUnit.DAYS).epochSecond.toString(),
            )
            markCompletedRecovery(
                scopeDigest = scopeDigest,
                requiredStart = inception,
                recoveryHorizon = recoveryHorizon,
            )
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } returns emptyList()
            val scopeGuard = mockk<AccountHistoryScopeGuard>()
            coEvery { scopeGuard.validateAccountScope() } returns AccountScopeValidationResult(
                status = AccountScopeValidationStatus.VALID,
                currentScopeDigest = scopeDigest,
            )

            val service = LedgersSyncService(
                repository = repository,
                krakenService = krakenService,
                configService = configService,
                tradeRepository = tradeRepository,
                nowProvider = { fixedNow },
                accountHistoryScopeGuard = scopeGuard,
            )
            service.syncLedgersFromKraken()

            coVerify(atLeast = 1) {
                krakenService.getLedgers(
                    startSec = recoveryHorizon.minusSeconds(300).epochSecond,
                    offset = 0,
                    endSec = any(),
                    types = any(),
                )
            }
        }

        "coverage migration backfills when completed recovery starts after the required inception" {
            stubStableBackend()
            val inception = fixedNow.minus(200, ChronoUnit.DAYS)
            val scopeDigest = AccountHistoryScopeGuard.digestAccountScope("account-a")
            every { configService.getConfig() } returns appConfig.copy(
                settings = appConfig.settings.copy(inceptionDate = inception.toString()),
            )
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "7")
            markCompletedRecovery(
                scopeDigest = scopeDigest,
                requiredStart = inception,
                ledgerOldest = inception.plusSeconds(1),
                tradeOldest = inception.plusSeconds(1),
            )
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } returns emptyList()
            val scopeGuard = mockk<AccountHistoryScopeGuard>()
            coEvery { scopeGuard.validateAccountScope() } returns AccountScopeValidationResult(
                status = AccountScopeValidationStatus.VALID,
                currentScopeDigest = scopeDigest,
            )

            val service = LedgersSyncService(
                repository = repository,
                krakenService = krakenService,
                configService = configService,
                tradeRepository = tradeRepository,
                nowProvider = { fixedNow },
                accountHistoryScopeGuard = scopeGuard,
            )
            service.syncLedgersFromKraken()

            coVerify(atLeast = 1) {
                krakenService.getLedgers(
                    startSec = inception.minusSeconds(1).epochSecond,
                    offset = 0,
                    endSec = any(),
                    types = any(),
                )
            }
            repository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) shouldBe
                LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION
        }

        "coverage migration does not promote failed or partial recovery evidence" {
            stubStableBackend()
            val inception = fixedNow.minus(200, ChronoUnit.DAYS)
            val scopeDigest = AccountHistoryScopeGuard.digestAccountScope("account-a")
            every { configService.getConfig() } returns appConfig.copy(
                settings = appConfig.settings.copy(inceptionDate = inception.toString()),
            )
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "7")
            markCompletedRecovery(scopeDigest, requiredStart = inception)
            tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS, "IN_PROGRESS")
            val failure = RuntimeException("recovery continuation unavailable")
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } throws failure
            val scopeGuard = mockk<AccountHistoryScopeGuard>()
            coEvery { scopeGuard.validateAccountScope() } returns AccountScopeValidationResult(
                status = AccountScopeValidationStatus.VALID,
                currentScopeDigest = scopeDigest,
            )

            val service = LedgersSyncService(
                repository = repository,
                krakenService = krakenService,
                configService = configService,
                tradeRepository = tradeRepository,
                nowProvider = { fixedNow },
                accountHistoryScopeGuard = scopeGuard,
            )
            shouldThrow<RuntimeException> { service.syncLedgersFromKraken() } shouldBe failure

            repository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) shouldBe "7"
            repository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) shouldBe null
        }

        "initial sync backfills from an older configured inception date" {
            stubStableBackend()
            val inception = fixedNow.minus(200, ChronoUnit.DAYS)
            val configured = appConfig.copy(
                settings = appConfig.settings.copy(inceptionDate = inception.toString()),
            )
            every { configService.getConfig() } returns configured
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } returns emptyList()

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { fixedNow })
            service.syncLedgersFromKraken()

            coVerify(atLeast = 1) {
                krakenService.getLedgers(
                    startSec = inception.minusSeconds(1).epochSecond,
                    offset = 0,
                    endSec = any(),
                    types = any(),
                )
            }
            service.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) shouldBe
                inception.epochSecond.toString()
        }

        "current coverage migrates when configuration moves inception earlier" {
            stubStableBackend()
            val previousBound = fixedNow.minus(96, ChronoUnit.DAYS)
            val inception = fixedNow.minus(200, ChronoUnit.DAYS)
            val configured = appConfig.copy(
                settings = appConfig.settings.copy(inceptionDate = inception.toString()),
            )
            every { configService.getConfig() } returns configured
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_VERSION,
                LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC,
                previousBound.epochSecond.toString(),
            )
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } returns emptyList()

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { fixedNow })
            service.syncLedgersFromKraken()

            coVerify(atLeast = 1) {
                krakenService.getLedgers(
                    startSec = inception.minusSeconds(1).epochSecond,
                    offset = 0,
                    endSec = any(),
                    types = any(),
                )
            }
            service.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC) shouldBe
                inception.epochSecond.toString()
        }

        "current coverage keeps the default bound for a recent configured inception date" {
            stubStableBackend()
            val recentInception = fixedNow.minus(5, ChronoUnit.DAYS)
            val configured = appConfig.copy(
                settings = appConfig.settings.copy(inceptionDate = recentInception.toString()),
            )
            every { configService.getConfig() } returns configured
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_VERSION,
                LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
            )
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } returns emptyList()

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { fixedNow })
            service.syncLedgersFromKraken()

            coVerify(atLeast = 1) {
                krakenService.getLedgers(
                    startSec = fixedNow.minus(96, ChronoUnit.DAYS).epochSecond,
                    offset = 0,
                    endSec = any(),
                    types = any(),
                )
            }
        }

        "partial backfill failure across multiple ledger types leaves coverage version stale" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "2")

            val failure = RuntimeException("Kraken API Rate Limit on ledger sync")
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } throws failure

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { fixedNow })
            shouldThrow<RuntimeException> { service.syncLedgersFromKraken() } shouldBe failure

            service.isLedgerCoverageCurrent() shouldBe false
            service.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) shouldBe "2"
        }

        "partial backfill retries safely without duplicating persisted pages" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            every { krakenService.hasLastLedgerTotalCount() } returns true
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "2")

            val firstPage = (0 until 50).map { event(it) }
            val secondPage = listOf(event(50))
            val failure = RuntimeException("Kraken API failed during the second ledger page")
            var failureEnabled = true
            var lastTotalCount = 0
            coEvery { krakenService.getLastLedgerTotalCount() } coAnswers { lastTotalCount }
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } coAnswers {
                val offset = secondArg<Int?>() ?: 0
                lastTotalCount = 51
                when (offset) {
                    0 -> firstPage
                    50 -> if (failureEnabled) throw failure else secondPage
                    else -> emptyList()
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

        "earn pagination continues across raw pages when early pages contain zero earn rows" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            every { krakenService.hasLastLedgerTotalCount() } returns false
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_VERSION,
                LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC,
                fixedNow.minus(96, java.time.temporal.ChronoUnit.DAYS).epochSecond.toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST,
                AccountHistoryScopeGuard.digestAccountScope("test-account"),
            )

            val earnEvent1 = LedgerEvent(
                ledgerId = "earn-1",
                time = fixedNow.minusSeconds(60),
                type = KrakenApiConstants.LEDGER_TYPE_EARN,
                subtype = "reward",
                asset = "DOT.HOLD",
                amount = BigDecimal("1.50"),
            )
            val earnEvent2 = LedgerEvent(
                ledgerId = "earn-2",
                time = fixedNow.minusSeconds(30),
                type = KrakenApiConstants.LEDGER_TYPE_EARN,
                subtype = "reward",
                asset = "DOT.HOLD",
                amount = BigDecimal("2.00"),
            )

            var lastTotalCount = 0
            var lastRawPageSize = 0
            coEvery { krakenService.getLastLedgerTotalCount() } coAnswers { lastTotalCount }
            coEvery { krakenService.getLastLedgerRawPageSize() } coAnswers { lastRawPageSize }
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } coAnswers {
                val offset = secondArg<Int?>() ?: 0
                lastTotalCount = 51
                when (offset) {
                    0 -> {
                        lastRawPageSize = 50
                        emptyList()
                    }

                    50 -> {
                        lastRawPageSize = 50
                        listOf(earnEvent1, earnEvent2)
                    }

                    else -> {
                        lastRawPageSize = 0
                        emptyList()
                    }
                }
            }

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { fixedNow })
            service.syncLedgersFromKraken()

            val stored = repository.getLedgersInRange(Instant.EPOCH, fixedNow.plusSeconds(300))
            stored.map { it.ledgerId }.toSet() shouldBe setOf("earn-1", "earn-2")
            stored.all { it.type == KrakenApiConstants.LEDGER_TYPE_EARN } shouldBe true

            // Idempotency: duplicate identities in a subsequent sync pass are harmless
            val advancedNow = fixedNow.plusSeconds(600)
            val service2 = LedgersSyncService(repository, krakenService, configService, nowProvider = { advancedNow })
            service2.syncLedgersFromKraken()

            val storedAfterSecondSync = repository.getLedgersInRange(Instant.EPOCH, advancedNow.plusSeconds(300))
            storedAfterSecondSync.map { it.ledgerId }.toSet() shouldBe setOf("earn-1", "earn-2")
        }

        "pagination falls back to rawPageSize when totalCount is unstated or zero" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_VERSION,
                LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
            )
            every { krakenService.hasLastLedgerTotalCount() } returns false

            val earnEvent = LedgerEvent(
                ledgerId = "earn-fallback",
                time = fixedNow.minusSeconds(60),
                type = KrakenApiConstants.LEDGER_TYPE_EARN,
                subtype = "reward",
                asset = "DOT.HOLD",
                amount = BigDecimal("1.50"),
            )

            var lastRawPageSize = 0
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0
            coEvery { krakenService.getLastLedgerRawPageSize() } coAnswers { lastRawPageSize }
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } coAnswers {
                val offset = secondArg<Int?>() ?: 0
                when (offset) {
                    0 -> {
                        lastRawPageSize = 50
                        emptyList()
                    }

                    50 -> {
                        lastRawPageSize = 10
                        listOf(earnEvent)
                    }

                    else -> {
                        lastRawPageSize = 0
                        emptyList()
                    }
                }
            }

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { fixedNow })
            service.syncLedgersFromKraken()

            val stored = repository.getLedgersInRange(Instant.EPOCH, fixedNow.plusSeconds(300))
            stored.map { it.ledgerId } shouldBe listOf("earn-fallback")
        }

        "ledgers exactly at reconstruction interval boundaries invalidate stale snapshots (inclusive)" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_VERSION,
                LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC,
                fixedNow.minus(96, ChronoUnit.DAYS).epochSecond.toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )

            val intervalStart = Instant.parse("2026-05-01T00:00:00Z")
            val intervalThrough = Instant.parse("2026-06-01T00:00:00Z")

            suspend fun resetReconstructionMarkers() {
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION,
                    TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION,
                )
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_START_EPOCH_SEC,
                    intervalStart.epochSecond.toString(),
                )
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC,
                    intervalThrough.epochSecond.toString(),
                )
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS,
                    intervalStart.toEpochMilli().toString(),
                )
            }

            suspend fun syncBoundaryLedger(ledgerId: String, time: Instant) {
                resetReconstructionMarkers()
                val boundaryEvent = LedgerEvent(
                    ledgerId = ledgerId,
                    time = time,
                    type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                    asset = "XBT",
                    amount = BigDecimal("0.1"),
                )
                coEvery { krakenService.getLedgers(any(), any(), any(), any()) } returns listOf(boundaryEvent)
                coEvery { krakenService.getLastLedgerTotalCount() } returns 1
                coEvery { krakenService.getLastLedgerRawPageSize() } returns 1
                LedgersSyncService(
                    repository,
                    krakenService,
                    configService,
                    tradeRepository = tradeRepository,
                    nowProvider = { fixedNow },
                ).syncLedgersFromKraken()
            }

            // Exactly at START invalidates (inclusive lower bound).
            syncBoundaryLedger("ledger-boundary-start", intervalStart)
            tradeRepository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION) shouldBe ""

            // Exactly at THROUGH invalidates (inclusive upper bound).
            syncBoundaryLedger("ledger-boundary-through", intervalThrough)
            tradeRepository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION) shouldBe ""
        }

        "already-known ledgers re-fetched inside reconstruction interval keep snapshots" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_VERSION,
                LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC,
                fixedNow.minus(96, ChronoUnit.DAYS).epochSecond.toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )

            val intervalStart = Instant.parse("2026-05-01T00:00:00Z")
            val intervalThrough = Instant.parse("2026-06-01T00:00:00Z")
            tradeRepository.setSyncMetadata(
                SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION,
                TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION,
            )
            tradeRepository.setSyncMetadata(
                SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_START_EPOCH_SEC,
                intervalStart.epochSecond.toString(),
            )
            tradeRepository.setSyncMetadata(
                SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC,
                intervalThrough.epochSecond.toString(),
            )
            tradeRepository.setSyncMetadata(
                SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS,
                intervalStart.toEpochMilli().toString(),
            )

            val knownEvent = LedgerEvent(
                ledgerId = "ledger-already-known",
                time = intervalStart.plusSeconds(60),
                type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                asset = "XBT",
                amount = BigDecimal("0.1"),
            )
            repository.saveLedgers(listOf(knownEvent))
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } returns listOf(knownEvent)
            coEvery { krakenService.getLastLedgerTotalCount() } returns 1
            coEvery { krakenService.getLastLedgerRawPageSize() } returns 1
            LedgersSyncService(
                repository,
                krakenService,
                configService,
                tradeRepository = tradeRepository,
                nowProvider = { fixedNow },
            ).syncLedgersFromKraken()

            tradeRepository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION) shouldBe
                TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION
        }

        "fails closed when Kraken returns a malformed ledger page envelope" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            every { krakenService.hasLastLedgerPageShape() } returns false
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } returns listOf(event(0))

            val error = shouldThrow<IllegalStateException> {
                LedgersSyncService(
                    repository,
                    krakenService,
                    configService,
                    tradeRepository = tradeRepository,
                    nowProvider = { fixedNow },
                ).syncLedgersFromKraken()
            }
            error.message shouldContain "malformed ledger page envelope"
        }

        "fails closed when ledger page occupancy disagrees with the reported count" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } returns listOf(event(0))
            every { krakenService.hasLastLedgerTotalCount() } returns true
            coEvery { krakenService.getLastLedgerTotalCount() } returns 10
            coEvery { krakenService.getLastLedgerRawPageSize() } returns 1

            val error = shouldThrow<IllegalStateException> {
                LedgersSyncService(
                    repository,
                    krakenService,
                    configService,
                    tradeRepository = tradeRepository,
                    nowProvider = { fixedNow },
                ).syncLedgersFromKraken()
            }
            error.message shouldContain "page occupancy"
        }

        "fails closed when an unseeded ledger sync sees an unknown empty page" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            every { krakenService.hasLastLedgerTotalCount() } returns false
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } returns emptyList()

            val error = shouldThrow<IllegalStateException> {
                LedgersSyncService(
                    repository,
                    krakenService,
                    configService,
                    tradeRepository = tradeRepository,
                    nowProvider = { fixedNow },
                ).syncLedgersFromKraken()
            }
            error.message shouldContain "unknown empty page"
        }

        "seeds an empty ledger history when Kraken reports zero entries" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } returns emptyList()
            every { krakenService.hasLastLedgerTotalCount() } returns true
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0
            coEvery { krakenService.getLastLedgerRawPageSize() } returns 0

            val service = LedgersSyncService(
                repository,
                krakenService,
                configService,
                tradeRepository = tradeRepository,
                nowProvider = { fixedNow },
            )
            service.syncLedgersFromKraken()

            service.isLedgersSeeded() shouldBe true
            repository.getLedgersInRange(Instant.EPOCH, fixedNow).size shouldBe 0
        }

        "re-fetched ledger with a changed type invalidates snapshots" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            seedLedgerCoverage()
            val intervalStart = Instant.parse("2026-05-01T00:00:00Z")
            val intervalThrough = Instant.parse("2026-06-01T00:00:00Z")
            setTradeReconstructionInterval(intervalStart, intervalThrough)

            val stored = event(0, time = intervalStart.plusSeconds(60)).copy(ledgerId = "ledger-type-change")
            repository.saveLedgers(listOf(stored))
            val changedType = stored.copy(type = KrakenApiConstants.LEDGER_TYPE_TRANSFER)
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } returns listOf(changedType)
            coEvery { krakenService.getLastLedgerTotalCount() } returns 1
            coEvery { krakenService.getLastLedgerRawPageSize() } returns 1
            LedgersSyncService(
                repository,
                krakenService,
                configService,
                tradeRepository = tradeRepository,
                nowProvider = { fixedNow },
            ).syncLedgersFromKraken()

            tradeRepository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION) shouldBe ""
        }

        "re-fetched ledger with a changed asset invalidates snapshots" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            seedLedgerCoverage()
            val intervalStart = Instant.parse("2026-05-01T00:00:00Z")
            val intervalThrough = Instant.parse("2026-06-01T00:00:00Z")
            setTradeReconstructionInterval(intervalStart, intervalThrough)

            val stored = event(0, time = intervalStart.plusSeconds(60)).copy(ledgerId = "ledger-asset-change")
            repository.saveLedgers(listOf(stored))
            val changedAsset = stored.copy(asset = "ETH")
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } returns listOf(changedAsset)
            coEvery { krakenService.getLastLedgerTotalCount() } returns 1
            coEvery { krakenService.getLastLedgerRawPageSize() } returns 1
            LedgersSyncService(
                repository,
                krakenService,
                configService,
                tradeRepository = tradeRepository,
                nowProvider = { fixedNow },
            ).syncLedgersFromKraken()

            tradeRepository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION) shouldBe ""
        }

        "keeps snapshots when reconstruction markers are absent" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            seedLedgerCoverage()
            tradeRepository.setSyncMetadata(
                SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION,
                TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION,
            )
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } returns listOf(event(0))
            coEvery { krakenService.getLastLedgerTotalCount() } returns 1
            coEvery { krakenService.getLastLedgerRawPageSize() } returns 1
            LedgersSyncService(
                repository,
                krakenService,
                configService,
                tradeRepository = tradeRepository,
                nowProvider = { fixedNow },
            ).syncLedgersFromKraken()

            tradeRepository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION) shouldBe
                TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION
        }

        "falls back to continuous start when reconstruction through marker is absent" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            seedLedgerCoverage()
            val intervalStart = Instant.parse("2026-05-01T00:00:00Z")
            tradeRepository.setSyncMetadata(
                SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION,
                TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION,
            )
            tradeRepository.setSyncMetadata(
                SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_START_EPOCH_SEC,
                intervalStart.epochSecond.toString(),
            )
            tradeRepository.setSyncMetadata(
                SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS,
                Instant.parse("2026-06-01T00:00:00Z").toEpochMilli().toString(),
            )
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } returns
                listOf(event(0, time = intervalStart.plusSeconds(60)))
            coEvery { krakenService.getLastLedgerTotalCount() } returns 1
            coEvery { krakenService.getLastLedgerRawPageSize() } returns 1
            LedgersSyncService(
                repository,
                krakenService,
                configService,
                tradeRepository = tradeRepository,
                nowProvider = { fixedNow },
            ).syncLedgersFromKraken()

            tradeRepository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION) shouldBe ""
        }

        "ledgers outside reconstruction interval keep snapshots" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            seedLedgerCoverage()
            val intervalStart = Instant.parse("2026-05-01T00:00:00Z")
            val intervalThrough = Instant.parse("2026-06-01T00:00:00Z")
            setTradeReconstructionInterval(intervalStart, intervalThrough)
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } returns
                listOf(event(0, time = intervalThrough.plusSeconds(60)))
            coEvery { krakenService.getLastLedgerTotalCount() } returns 1
            coEvery { krakenService.getLastLedgerRawPageSize() } returns 1
            LedgersSyncService(
                repository,
                krakenService,
                configService,
                tradeRepository = tradeRepository,
                nowProvider = { fixedNow },
            ).syncLedgersFromKraken()

            tradeRepository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION) shouldBe
                TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION
        }

        "ledger with an unrecognized id inside reconstruction interval invalidates snapshots" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            seedLedgerCoverage()
            val intervalStart = Instant.parse("2026-05-01T00:00:00Z")
            val intervalThrough = Instant.parse("2026-06-01T00:00:00Z")
            setTradeReconstructionInterval(intervalStart, intervalThrough)

            // Same economics as nothing on record: identity is by ledger id, so an
            // unknown id counts as a new historical row even without a stored twin.
            repository.saveLedgers(listOf(event(99, time = intervalStart.plusSeconds(60))))
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } returns
                listOf(event(0, time = intervalStart.plusSeconds(120)))
            coEvery { krakenService.getLastLedgerTotalCount() } returns 1
            coEvery { krakenService.getLastLedgerRawPageSize() } returns 1
            LedgersSyncService(
                repository,
                krakenService,
                configService,
                tradeRepository = tradeRepository,
                nowProvider = { fixedNow },
            ).syncLedgersFromKraken()

            tradeRepository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION) shouldBe ""
        }

        "unseeded ledger sync persists the verified account scope digest" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            val scopeDigest = AccountHistoryScopeGuard.digestAccountScope("account-a")
            val scopeGuard = mockk<AccountHistoryScopeGuard>()
            coEvery { scopeGuard.validateAccountScope() } returns AccountScopeValidationResult(
                status = AccountScopeValidationStatus.VALID,
                currentScopeDigest = scopeDigest,
            )
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } returns emptyList()
            every { krakenService.hasLastLedgerTotalCount() } returns true
            coEvery { krakenService.getLastLedgerTotalCount() } returns 0
            coEvery { krakenService.getLastLedgerRawPageSize() } returns 0

            LedgersSyncService(
                repository,
                krakenService,
                configService,
                tradeRepository = tradeRepository,
                nowProvider = { fixedNow },
                accountHistoryScopeGuard = scopeGuard,
            ).syncLedgersFromKraken()

            repository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST) shouldBe scopeDigest
        }

        "pagination falls back to filtered page size when both totalCount and rawPageSize are unstated" {
            stubStableBackend()
            every { configService.getConfig() } returns appConfig
            repository.setLedgersSeeded(true)
            repository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_VERSION,
                LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
            )
            every { krakenService.hasLastLedgerTotalCount() } returns false

            coEvery { krakenService.getLastLedgerTotalCount() } returns 0
            coEvery { krakenService.getLastLedgerRawPageSize() } returns 0
            coEvery { krakenService.getLedgers(any(), any(), any(), any()) } coAnswers {
                val offset = secondArg<Int?>() ?: 0
                val types = arg<Set<String>?>(3)
                if (offset == 0) {
                    List(50) { i ->
                        LedgerEvent(
                            ledgerId = "ledger-fallback-$i",
                            refid = "ref-$i",
                            time = baseTime.plusSeconds(i.toLong()),
                            type = types?.singleOrNull() ?: KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                            asset = "USD",
                            amount = BigDecimal("10.00"),
                        )
                    }
                } else {
                    emptyList()
                }
            }

            val service = LedgersSyncService(repository, krakenService, configService, nowProvider = { fixedNow })
            service.syncLedgersFromKraken()

            val stored = repository.getLedgersInRange(Instant.EPOCH, fixedNow.plusSeconds(300))
            stored.isEmpty() shouldBe false
        }
    }
}
