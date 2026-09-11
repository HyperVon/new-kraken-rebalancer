package com.gemini.krakenbot.service.impl.history

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.impl.SqliteLedgerRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqlitePortfolioStatsRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.InceptionRecoveryStatus
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

class TradeHistoryCoverageTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val db = DatabaseConfig.init(TestFixtures.MEMORY_)
    private val repository = SqliteTradeRepositoryImpl(db)
    private val ledgerRepository = SqliteLedgerRepositoryImpl(db)
    private val portfolioStatsRepository = SqlitePortfolioStatsRepositoryImpl(db, jacksonObjectMapper())
    private val fakeKraken = FakeKrakenService()
    private val configService = mockk<ConfigService>(relaxed = true)
    private val scopeGuard = mockk<AccountHistoryScopeGuard>(relaxed = true)

    private val fixedNow = Instant.parse("2026-07-01T12:00:00Z")
    private val inception = Instant.parse("2025-12-05T17:00:56Z")
    private val defaultScopeDigest = AccountHistoryScopeGuard.digestAccountScope("test-account")

    private val appConfig = AppConfig(
        kraken = KrakenCredentials(TestFixtures.TRADE_HISTORY_API_KEY, TestFixtures.TRADE_HISTORY_API_SECRET),
        settings = TestFixtures.settings(
            dryRun = false,
            simulation = false,
            loopDelaySeconds = 60,
        ).copy(inceptionDate = inception.toString()),
        allocations = listOf(
            Allocation(Asset.BTC, 50.0),
            Allocation(Asset.USD, 50.0),
        ),
    )

    private fun stubBackend(scopeDigest: String? = defaultScopeDigest) {
        every { configService.getConfig() } returns appConfig
        coEvery { scopeGuard.validateAccountScope() } returns AccountScopeValidationResult(
            status = AccountScopeValidationStatus.VALID,
            currentScopeDigest = scopeDigest,
        )
    }

    private suspend fun markCompletedRecovery(
        scopeDigest: String = defaultScopeDigest,
        requiredStart: Instant = inception,
        recoveryHorizon: Instant = fixedNow,
        ledgerOldest: Instant = requiredStart.minusSeconds(1),
        tradeOldest: Instant = requiredStart.minusSeconds(1),
        bindingVersion: String = AccountHistoryScopeGuard.CURRENT_BINDING_VERSION,
        recoveryVersion: String = InceptionRecoveryService.CURRENT_RECOVERY_VERSION,
        tradeStatus: String = InceptionRecoveryStatus.COMPLETE,
        ledgerStatus: String = InceptionRecoveryStatus.COMPLETE,
        tradeOffset: String = "completed",
        ledgerOffset: String = "completed",
        tradeTotal: String = "1",
        ledgerTotal: String = "1",
    ) {
        repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST, scopeDigest)
        repository.setSyncMetadata(
            SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
            bindingVersion,
        )
        repository.setSyncMetadata(
            SyncMetadataKeys.INCEPTION_RECOVERY_VERSION,
            recoveryVersion,
        )
        repository.setSyncMetadata(
            SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS,
            tradeStatus,
        )
        repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, tradeOffset)
        repository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_TOTAL, tradeTotal)
        repository.setSyncMetadata(
            SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OLDEST_EPOCH_MS,
            tradeOldest.toEpochMilli().toString(),
        )
        repository.setSyncMetadata(
            SyncMetadataKeys.INCEPTION_RECOVERY_HORIZON_EPOCH_SEC,
            recoveryHorizon.epochSecond.toString(),
        )

        ledgerRepository.setSyncMetadata(
            SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS,
            ledgerStatus,
        )
        ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, ledgerOffset)
        ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL, ledgerTotal)
        ledgerRepository.setSyncMetadata(
            SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OLDEST_EPOCH_MS,
            ledgerOldest.toEpochMilli().toString(),
        )
    }

    private fun service(
        kraken: com.gemini.krakenbot.service.KrakenService = fakeKraken,
        now: Instant = fixedNow,
    ): TradeHistorySyncService {
        val reconstructionService = TradeHistoryReconstructionService(
            repository = repository,
            ledgerRepository = ledgerRepository,
            krakenService = kraken,
            configService = configService,
            portfolioStatsRepository = portfolioStatsRepository,
            nowProvider = { now },
            accountHistoryScopeGuard = scopeGuard,
        )
        return TradeHistorySyncService(
            repository = repository,
            krakenService = kraken,
            configService = configService,
            reconstructionService = reconstructionService,
            ledgerRepository = ledgerRepository,
            nowProvider = { now },
            accountHistoryScopeGuard = scopeGuard,
        )
    }

    private fun reconstructionService(now: Instant = fixedNow): TradeHistoryReconstructionService =
        TradeHistoryReconstructionService(
            repository = repository,
            ledgerRepository = ledgerRepository,
            krakenService = fakeKraken,
            configService = configService,
            portfolioStatsRepository = portfolioStatsRepository,
            nowProvider = { now },
            accountHistoryScopeGuard = scopeGuard,
        )

    private suspend fun seedCurrentCoverage(
        ledgerDigest: String = defaultScopeDigest,
        tradeDigest: String = defaultScopeDigest,
        ledgerHorizonSec: String? = fixedNow.epochSecond.toString(),
        tradeHorizonSec: String? = fixedNow.epochSecond.toString(),
        ledgerStartSec: String? = inception.epochSecond.toString(),
        tradeStartSec: String? = inception.epochSecond.toString(),
    ) {
        ledgerRepository.setLedgersSeeded(true)
        ledgerRepository.setSyncMetadata(
            SyncMetadataKeys.LEDGER_COVERAGE_VERSION,
            LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
        )
        ledgerHorizonSec?.let {
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC, it)
        }
        ledgerStartSec?.let {
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC, it)
        }
        ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST, ledgerDigest)

        repository.setHistorySeeded(true)
        repository.setSyncMetadata(
            SyncMetadataKeys.TRADE_COVERAGE_VERSION,
            TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION,
        )
        tradeHorizonSec?.let {
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC, it)
        }
        tradeStartSec?.let {
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC, it)
        }
        repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST, tradeDigest)
    }

    private fun createMockKraken(): KrakenService {
        val mock = mockk<KrakenService>(relaxed = true)
        coEvery { mock.withStableBackend(any<suspend (KrakenService) -> Any?>()) } coAnswers {
            val block = firstArg<suspend (KrakenService) -> Any?>()
            block(mock)
        }
        every { mock.hasLastLedgerPageShape() } returns true
        every { mock.hasLastTradeHistoryPageShape() } returns true
        every { mock.hasLastTradeHistoryTotalCount() } returns true
        every { mock.hasLastLedgerTotalCount() } returns true
        coEvery { mock.getTradeHistory(any(), any()) } returns emptyList()
        return mock
    }

    init {
        "configured inception older than default 96-day window triggers trade coverage backfill" {
            stubBackend()
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "")
            val mockKraken = createMockKraken()

            val syncService = service(kraken = mockKraken)
            syncService.syncTradesFromKraken()

            coVerify {
                mockKraken.getTradeHistory(
                    startSec = inception.minusSeconds(1).epochSecond,
                    offset = 0,
                )
            }
            repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) shouldBe "1"
            repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) shouldBe
                inception.epochSecond.toString()
            repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) shouldBe
                fixedNow.epochSecond.toString()
        }

        "ordinary trade coverage extends to inception when no reusable proof exists" {
            stubBackend()
            val mockKraken = createMockKraken()

            val syncService = service(kraken = mockKraken)
            syncService.syncTradesFromKraken()

            coVerify {
                mockKraken.getTradeHistory(
                    startSec = inception.epochSecond,
                    offset = 0,
                )
            }
            repository.isHistorySeeded() shouldBe true
            repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) shouldBe "1"
            repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) shouldBe
                inception.epochSecond.toString()
        }

        "completed account-bound inception recovery satisfies historical trade coverage with zero historical calls" {
            stubBackend()
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "")
            markCompletedRecovery(
                scopeDigest = defaultScopeDigest,
                requiredStart = inception,
                recoveryHorizon = fixedNow,
            )

            val mockKraken = createMockKraken()

            val syncService = service(kraken = mockKraken)
            syncService.syncTradesFromKraken()

            // Zero historical API calls because horizon covers fixedNow
            coVerify(exactly = 0) { mockKraken.getTradeHistory(any(), any()) }
            repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) shouldBe "1"
            repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) shouldBe
                inception.epochSecond.toString()
            repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) shouldBe
                fixedNow.epochSecond.toString()
            repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST) shouldBe defaultScopeDigest
        }

        "completed recovery horizon behind now fetches only the unproven tail" {
            stubBackend()
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "")
            val horizon = fixedNow.minus(2, ChronoUnit.DAYS)
            markCompletedRecovery(
                scopeDigest = defaultScopeDigest,
                requiredStart = inception,
                recoveryHorizon = horizon,
            )

            val mockKraken = createMockKraken()

            val syncService = service(kraken = mockKraken)
            syncService.syncTradesFromKraken()

            // Only queries from horizon - 300s to fixedNow, NOT inception!
            coVerify(exactly = 1) {
                mockKraken.getTradeHistory(
                    startSec = horizon.minusSeconds(300).epochSecond,
                    offset = 0,
                )
            }
            coVerify(exactly = 0) {
                mockKraken.getTradeHistory(
                    startSec = inception.minusSeconds(1).epochSecond,
                    offset = any(),
                )
            }
            repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) shouldBe "1"
            repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC) shouldBe
                inception.epochSecond.toString()
            repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) shouldBe
                fixedNow.epochSecond.toString()
        }

        "recovery starts later than required inception cannot be adopted" {
            stubBackend()
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "")
            markCompletedRecovery(
                scopeDigest = defaultScopeDigest,
                requiredStart = inception,
                tradeOldest = inception.plus(10, ChronoUnit.DAYS), // Starts after inception!
            )

            val mockKraken = createMockKraken()

            val syncService = service(kraken = mockKraken)
            syncService.syncTradesFromKraken()

            // Falls back to querying from inception bound
            coVerify {
                mockKraken.getTradeHistory(
                    startSec = inception.minusSeconds(1).epochSecond,
                    offset = any(),
                )
            }
        }

        "wrong account binding or scope digest cannot be adopted" {
            stubBackend()
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "")
            markCompletedRecovery(
                scopeDigest = "different-account-digest",
                requiredStart = inception,
            )

            val mockKraken = createMockKraken()

            val syncService = service(kraken = mockKraken)
            syncService.syncTradesFromKraken()

            coVerify {
                mockKraken.getTradeHistory(
                    startSec = inception.minusSeconds(1).epochSecond,
                    offset = any(),
                )
            }
        }

        "missing or invalid recovery evidence cannot be adopted" {
            stubBackend()
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "")
            markCompletedRecovery(
                scopeDigest = defaultScopeDigest,
                requiredStart = inception,
                tradeTotal = "invalid_total",
            )

            val mockKraken = createMockKraken()

            val syncService = service(kraken = mockKraken)
            syncService.syncTradesFromKraken()

            coVerify {
                mockKraken.getTradeHistory(
                    startSec = inception.minusSeconds(1).epochSecond,
                    offset = any(),
                )
            }
        }

        "partial or failed recovery cannot be adopted" {
            stubBackend()
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "")
            markCompletedRecovery(
                scopeDigest = defaultScopeDigest,
                requiredStart = inception,
                tradeStatus = "IN_PROGRESS",
                tradeOffset = "50",
            )

            val mockKraken = createMockKraken()

            val syncService = service(kraken = mockKraken)
            syncService.syncTradesFromKraken()

            coVerify {
                mockKraken.getTradeHistory(
                    startSec = inception.minusSeconds(1).epochSecond,
                    offset = any(),
                )
            }
        }

        "network backfill failure leaves old trade coverage marker unchanged" {
            stubBackend()
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "0")
            val failure = RuntimeException("Kraken API 500 error")

            val mockKraken = createMockKraken()
            coEvery { mockKraken.getTradeHistory(any(), any()) } throws failure

            val syncService = service(kraken = mockKraken)
            shouldThrow<RuntimeException> { syncService.syncTradesFromKraken() } shouldBe failure

            repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) shouldBe "0"
        }

        "canRebuildSnapshots() rejects ledger complete + trade incomplete" {
            stubBackend()
            ledgerRepository.setLedgersSeeded(true)
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "9")
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC,
                inception.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )

            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "1")
            // Trade start is AFTER inception!
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC,
                inception.plus(10, ChronoUnit.DAYS).epochSecond.toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )

            val reconstructionService = TradeHistoryReconstructionService(
                repository = repository,
                ledgerRepository = ledgerRepository,
                krakenService = fakeKraken,
                configService = configService,
                portfolioStatsRepository = portfolioStatsRepository,
                nowProvider = { fixedNow },
                accountHistoryScopeGuard = scopeGuard,
            )

            reconstructionService.canRebuildSnapshots(appConfig, inception) shouldBe false
        }

        "canRebuildSnapshots() accepts ledger complete + trade complete through required start" {
            stubBackend()
            ledgerRepository.setLedgersSeeded(true)
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "9")
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC,
                inception.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST,
                defaultScopeDigest,
            )

            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "1")
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC,
                inception.epochSecond.toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST,
                defaultScopeDigest,
            )

            val reconstructionService = TradeHistoryReconstructionService(
                repository = repository,
                ledgerRepository = ledgerRepository,
                krakenService = fakeKraken,
                configService = configService,
                portfolioStatsRepository = portfolioStatsRepository,
                nowProvider = { fixedNow },
                accountHistoryScopeGuard = scopeGuard,
            )

            reconstructionService.canRebuildSnapshots(appConfig, inception) shouldBe true
        }

        "canRebuildSnapshots() rejects an invalid account scope" {
            stubBackend()
            coEvery { scopeGuard.validateAccountScope() } returns
                AccountScopeValidationResult.scopeMismatch(current = "account-b-digest")
            seedCurrentCoverage()

            reconstructionService().canRebuildSnapshots(appConfig, inception) shouldBe false
        }

        "canRebuildSnapshots() skips digest checks when the current scope digest is blank" {
            stubBackend(null)
            seedCurrentCoverage()

            reconstructionService().canRebuildSnapshots(appConfig, inception) shouldBe true
        }

        "canRebuildSnapshots() rejects a mismatched ledger scope digest" {
            stubBackend()
            seedCurrentCoverage(ledgerDigest = "stale-ledger-digest")

            reconstructionService().canRebuildSnapshots(appConfig, inception) shouldBe false
        }

        "canRebuildSnapshots() rejects a mismatched trade scope digest" {
            stubBackend()
            seedCurrentCoverage(tradeDigest = "stale-trade-digest")

            reconstructionService().canRebuildSnapshots(appConfig, inception) shouldBe false
        }

        "canRebuildSnapshots() fails closed when the ledger coverage horizon is missing" {
            stubBackend()
            seedCurrentCoverage(ledgerHorizonSec = null)

            reconstructionService().canRebuildSnapshots(appConfig, inception) shouldBe false
        }

        "canRebuildSnapshots() fails closed when the trade coverage horizon is missing" {
            stubBackend()
            seedCurrentCoverage(tradeHorizonSec = null)

            reconstructionService().canRebuildSnapshots(appConfig, inception) shouldBe false
        }

        "canRebuildSnapshots() fails closed when the ledger coverage start is missing" {
            stubBackend()
            seedCurrentCoverage(ledgerStartSec = null)

            reconstructionService().canRebuildSnapshots(appConfig, inception) shouldBe false
        }

        "canRebuildSnapshots() fails closed when the trade coverage start is missing" {
            stubBackend()
            seedCurrentCoverage(tradeStartSec = null)

            reconstructionService().canRebuildSnapshots(appConfig, inception) shouldBe false
        }

        "earlier fills arriving after an existing reconstruction invalidate stale snapshots" {
            stubBackend()
            val continuousStart = Instant.parse("2026-06-01T00:00:00Z")
            repository.setSyncMetadata(
                SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS,
                continuousStart.toEpochMilli().toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION,
                TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION,
            )

            val syncService = service()
            val olderTrade = TradeRecord(
                id = 1,
                orderTxid = "order-txid-older",
                pair = "XBTUSD",
                symbol = "BTC",
                side = "buy",
                price = BigDecimal("90000.00"),
                volume = BigDecimal("0.1"),
                usdAmount = BigDecimal("9000.00"),
                fee = BigDecimal("9.00"),
                timestamp = continuousStart.minus(10, ChronoUnit.DAYS),
                success = true,
                dryRun = false,
                source = TradeSource.API_FILL,
            )

            syncService.importRecoveredApiTrades(listOf(olderTrade))

            // Snapshot reconstruction version must be invalidated (cleared)
            repository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION) shouldBe ""
        }

        "ordinary forward incremental trades do NOT invalidate snapshot reconstruction" {
            stubBackend()
            val continuousStart = Instant.parse("2026-06-01T00:00:00Z")
            repository.setSyncMetadata(
                SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS,
                continuousStart.toEpochMilli().toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION,
                TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION,
            )

            val syncService = service()
            val forwardTrade = TradeRecord(
                id = 2,
                orderTxid = "order-txid-forward",
                pair = "XBTUSD",
                symbol = "BTC",
                side = "buy",
                price = BigDecimal("90000.00"),
                volume = BigDecimal("0.1"),
                usdAmount = BigDecimal("9000.00"),
                fee = BigDecimal("9.00"),
                timestamp = fixedNow.minusSeconds(60), // New forward trade!
                success = true,
                dryRun = false,
                source = TradeSource.API_FILL,
            )

            syncService.importRecoveredApiTrades(listOf(forwardTrade))

            // Snapshot reconstruction version must remain current
            repository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION) shouldBe
                TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION
        }

        "fills exactly at reconstruction interval boundaries invalidate stale snapshots (inclusive)" {
            stubBackend()
            val intervalStart = Instant.parse("2026-05-01T00:00:00Z")
            val intervalThrough = Instant.parse("2026-06-01T00:00:00Z")

            suspend fun resetReconstructionMarkers() {
                repository.setSyncMetadata(
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION,
                    TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION,
                )
                repository.setSyncMetadata(
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_START_EPOCH_SEC,
                    intervalStart.epochSecond.toString(),
                )
                repository.setSyncMetadata(
                    SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC,
                    intervalThrough.epochSecond.toString(),
                )
                repository.setSyncMetadata(
                    SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS,
                    intervalStart.toEpochMilli().toString(),
                )
            }

            fun boundaryTrade(id: Int, txid: String, timestamp: Instant) = TradeRecord(
                id = id,
                orderTxid = txid,
                pair = "XBTUSD",
                symbol = "BTC",
                side = "buy",
                price = BigDecimal("90000.00"),
                volume = BigDecimal("0.1"),
                usdAmount = BigDecimal("9000.00"),
                fee = BigDecimal("9.00"),
                timestamp = timestamp,
                success = true,
                dryRun = false,
                source = TradeSource.API_FILL,
            )

            // Exactly at START invalidates (inclusive lower bound).
            resetReconstructionMarkers()
            service().importRecoveredApiTrades(listOf(boundaryTrade(11, "order-txid-start", intervalStart)))
            repository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION) shouldBe ""

            // Exactly at THROUGH invalidates (inclusive upper bound).
            resetReconstructionMarkers()
            service().importRecoveredApiTrades(listOf(boundaryTrade(12, "order-txid-through", intervalThrough)))
            repository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION) shouldBe ""

            // Just after THROUGH does not invalidate (forward incremental).
            resetReconstructionMarkers()
            service().importRecoveredApiTrades(
                listOf(boundaryTrade(13, "order-txid-after", intervalThrough.plusSeconds(1))),
            )
            repository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION) shouldBe
                TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION
        }

        "fresh install cannot claim historical inception from only a 96-day trade seed" {
            stubBackend()
            val seedBound = fixedNow.minus(96, ChronoUnit.DAYS)
            ledgerRepository.setLedgersSeeded(true)
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "9")
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC,
                seedBound.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )

            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "1")
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC,
                seedBound.epochSecond.toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )

            val reconstructionService = TradeHistoryReconstructionService(
                repository = repository,
                ledgerRepository = ledgerRepository,
                krakenService = fakeKraken,
                configService = configService,
                portfolioStatsRepository = portfolioStatsRepository,
                nowProvider = { fixedNow },
                accountHistoryScopeGuard = scopeGuard,
            )

            // Inception request (older than 96 days) must be rejected because seed start is only 96 days ago
            reconstructionService.canRebuildSnapshots(appConfig, inception) shouldBe false
        }

        "canRebuildSnapshots verifies ledger and trade start and horizon bounds" {
            stubBackend()
            val seedBound = fixedNow.minus(96, ChronoUnit.DAYS)
            ledgerRepository.setLedgersSeeded(true)
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "9")
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "1")

            val reconstructionService = TradeHistoryReconstructionService(
                repository = repository,
                ledgerRepository = ledgerRepository,
                krakenService = fakeKraken,
                configService = configService,
                portfolioStatsRepository = portfolioStatsRepository,
                nowProvider = { fixedNow },
                accountHistoryScopeGuard = scopeGuard,
            )

            // Ledger start too late
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC,
                inception.plusSeconds(100).epochSecond.toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC,
                inception.minusSeconds(100).epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )
            reconstructionService.canRebuildSnapshots(appConfig, inception) shouldBe false

            // Trade start null or too late
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC,
                inception.minusSeconds(100).epochSecond.toString(),
            )
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC, "")
            reconstructionService.canRebuildSnapshots(appConfig, inception) shouldBe false

            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC,
                inception.plusSeconds(100).epochSecond.toString(),
            )
            reconstructionService.canRebuildSnapshots(appConfig, inception) shouldBe false

            // Ledger horizon null or before inception
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC,
                inception.minusSeconds(100).epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC, "")
            reconstructionService.canRebuildSnapshots(appConfig, inception) shouldBe false

            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                inception.minusSeconds(100).epochSecond.toString(),
            )
            reconstructionService.canRebuildSnapshots(appConfig, inception) shouldBe false

            // Trade horizon null or before inception
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC, "")
            reconstructionService.canRebuildSnapshots(appConfig, inception) shouldBe false

            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC,
                inception.minusSeconds(100).epochSecond.toString(),
            )
            reconstructionService.canRebuildSnapshots(appConfig, inception) shouldBe false

            // Default lookback with horizon before seed bound
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                seedBound.minusSeconds(100).epochSecond.toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )
            val defaultConfig = appConfig.copy(
                settings = appConfig.settings.copy(inceptionDate = null),
            )
            reconstructionService.canRebuildSnapshots(defaultConfig) shouldBe false

            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC,
                seedBound.minusSeconds(100).epochSecond.toString(),
            )
            reconstructionService.canRebuildSnapshots(defaultConfig) shouldBe false

            // Scope guard validations
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST,
                "different-digest",
            )
            reconstructionService.canRebuildSnapshots(defaultConfig) shouldBe false

            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST,
                defaultScopeDigest,
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST,
                "different-digest",
            )
            reconstructionService.canRebuildSnapshots(defaultConfig) shouldBe false

            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST,
                defaultScopeDigest,
            )
            reconstructionService.canRebuildSnapshots(defaultConfig) shouldBe true
        }

        "trade coverage backfill adopts completed inception recovery prefix" {
            stubBackend()
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "")

            val recoveryHorizon = fixedNow.minus(10, ChronoUnit.DAYS)
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST, defaultScopeDigest)
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
                AccountHistoryScopeGuard.CURRENT_BINDING_VERSION,
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.INCEPTION_RECOVERY_VERSION,
                InceptionRecoveryService.CURRENT_RECOVERY_VERSION,
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS,
                InceptionRecoveryStatus.COMPLETE,
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS,
                InceptionRecoveryStatus.COMPLETE,
            )
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, "completed")
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, "completed")
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.INCEPTION_RECOVERY_HORIZON_EPOCH_SEC,
                recoveryHorizon.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_TOTAL, "10")
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OLDEST_EPOCH_MS,
                inception.toEpochMilli().toString(),
            )
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL, "15")
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OLDEST_EPOCH_MS,
                inception.toEpochMilli().toString(),
            )

            var requestedStartSec: Long? = null
            fakeKraken.tradeHistorySupplier = { startSec, _ ->
                requestedStartSec = startSec
                emptyList()
            }
            fakeKraken.tradeHistoryTotalCountOverride = 0

            val syncService = service(fakeKraken, fixedNow)
            syncService.syncTradesFromKraken()

            requestedStartSec shouldBe recoveryHorizon.minusSeconds(300).epochSecond
            repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) shouldBe "1"
            repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC) shouldBe
                fixedNow.epochSecond.toString()
        }

        "trade coverage backfill queries zero trades when completed recovery horizon reaches queryNow" {
            stubBackend()
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "")

            ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST, defaultScopeDigest)
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
                AccountHistoryScopeGuard.CURRENT_BINDING_VERSION,
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.INCEPTION_RECOVERY_VERSION,
                InceptionRecoveryService.CURRENT_RECOVERY_VERSION,
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_STATUS,
                InceptionRecoveryStatus.COMPLETE,
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS,
                InceptionRecoveryStatus.COMPLETE,
            )
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, "completed")
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, "completed")
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.INCEPTION_RECOVERY_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_TOTAL, "0")
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL, "0")

            fakeKraken.getTradeHistoryCallCount = 0

            val syncService = service(fakeKraken, fixedNow)
            syncService.syncTradesFromKraken()

            fakeKraken.getTradeHistoryCallCount shouldBe 0
            repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) shouldBe "1"
        }

        "rebuildHistoricalSnapshotsIfNeeded skips rebuild when continuous history start covers inception" {
            stubBackend()
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "1")
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC,
                inception.epochSecond.toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST,
                defaultScopeDigest,
            )
            ledgerRepository.setLedgersSeeded(true)
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "9")
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC,
                inception.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST,
                defaultScopeDigest,
            )

            // When continuous history start covers inception:
            repository.setSyncMetadata(
                SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS,
                inception.minusSeconds(3600).toEpochMilli().toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION,
                TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION,
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_LEDGER_COVERAGE_VERSION,
                LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_TRADE_COVERAGE_VERSION,
                TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION,
            )

            var balanceCallCount = 0
            fakeKraken.balanceSupplier = {
                balanceCallCount++
                mapOf(
                    Asset.BTC to BigDecimal.ONE,
                    Asset.USD to BigDecimal("10000.00"),
                )
            }

            val syncService = service(fakeKraken, fixedNow)
            syncService.rebuildHistoricalSnapshotsIfNeeded()

            balanceCallCount shouldBe 0
        }

        "rebuildHistoricalSnapshotsIfNeeded rebuilds when continuous history start does not cover inception" {
            stubBackend()
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "1")
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC,
                inception.epochSecond.toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST,
                defaultScopeDigest,
            )
            ledgerRepository.setLedgersSeeded(true)
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "9")
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC,
                inception.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST,
                defaultScopeDigest,
            )

            // When continuous history start is AFTER inception (does NOT cover inception):
            repository.setSyncMetadata(
                SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS,
                inception.plusSeconds(3600).toEpochMilli().toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION,
                TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION,
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_LEDGER_COVERAGE_VERSION,
                LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_TRADE_COVERAGE_VERSION,
                TradeHistorySyncService.CURRENT_TRADE_COVERAGE_VERSION,
            )

            var balanceCallCount = 0
            fakeKraken.balanceSupplier = {
                balanceCallCount++
                mapOf(
                    Asset.BTC to BigDecimal.ONE,
                    Asset.USD to BigDecimal("10000.00"),
                )
            }
            fakeKraken.ohlcSupplier = { _, _, _ ->
                listOf(
                    inception.truncatedTo(ChronoUnit.DAYS).epochSecond to BigDecimal("90000.00"),
                    fixedNow.truncatedTo(ChronoUnit.DAYS).epochSecond to BigDecimal("90000.00"),
                )
            }

            val syncService = service(fakeKraken, fixedNow)
            syncService.rebuildHistoricalSnapshotsIfNeeded()

            balanceCallCount shouldBe 1
        }

        "canRebuildSnapshots() rejects outdated trade coverage version" {
            stubBackend()
            ledgerRepository.setLedgersSeeded(true)
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "9")
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "0")

            val reconstructionService = TradeHistoryReconstructionService(
                repository = repository,
                ledgerRepository = ledgerRepository,
                krakenService = fakeKraken,
                configService = configService,
                portfolioStatsRepository = portfolioStatsRepository,
                nowProvider = { fixedNow },
                accountHistoryScopeGuard = scopeGuard,
            )

            reconstructionService.canRebuildSnapshots(appConfig, inception) shouldBe false
        }

        "canRebuildSnapshots() rejects invalid account scope" {
            every { configService.getConfig() } returns appConfig
            coEvery { scopeGuard.validateAccountScope() } returns AccountScopeValidationResult(
                status = AccountScopeValidationStatus.SCOPE_UNAVAILABLE,
                currentScopeDigest = null,
            )
            ledgerRepository.setLedgersSeeded(true)
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "9")
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC,
                inception.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "1")
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC,
                inception.epochSecond.toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )

            val reconstructionService = TradeHistoryReconstructionService(
                repository = repository,
                ledgerRepository = ledgerRepository,
                krakenService = fakeKraken,
                configService = configService,
                portfolioStatsRepository = portfolioStatsRepository,
                nowProvider = { fixedNow },
                accountHistoryScopeGuard = scopeGuard,
            )

            reconstructionService.canRebuildSnapshots(appConfig, inception) shouldBe false
        }

        "canRebuildSnapshots() rejects mismatched ledger or trade account scope digest" {
            stubBackend("digest-A")
            ledgerRepository.setLedgersSeeded(true)
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "9")
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC,
                inception.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST, "digest-B")
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "1")
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC,
                inception.epochSecond.toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST, "digest-A")

            val reconstructionService = TradeHistoryReconstructionService(
                repository = repository,
                ledgerRepository = ledgerRepository,
                krakenService = fakeKraken,
                configService = configService,
                portfolioStatsRepository = portfolioStatsRepository,
                nowProvider = { fixedNow },
                accountHistoryScopeGuard = scopeGuard,
            )

            reconstructionService.canRebuildSnapshots(appConfig, inception) shouldBe false

            ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST, "digest-A")
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST, "digest-B")
            reconstructionService.canRebuildSnapshots(appConfig, inception) shouldBe false

            ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST, "")
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST, "digest-A")
            reconstructionService.canRebuildSnapshots(appConfig, inception) shouldBe false

            ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST, "digest-A")
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST, "digest-A")
            reconstructionService.canRebuildSnapshots(appConfig, inception) shouldBe true
        }

        "rebuildHistoricalSnapshotsIfNeeded returns early when simulation mode is active" {
            val simConfig = appConfig.copy(settings = appConfig.settings.copy(simulation = true))
            every { configService.getConfig() } returns simConfig
            var balanceCallCount = 0
            fakeKraken.balanceSupplier = {
                balanceCallCount++
                emptyMap()
            }
            val syncService = service(fakeKraken, fixedNow)
            syncService.rebuildHistoricalSnapshotsIfNeeded()
            balanceCallCount shouldBe 0
        }

        "syncTradesFromKraken returns early when simulation mode is active" {
            val simConfig = appConfig.copy(settings = appConfig.settings.copy(simulation = true))
            every { configService.getConfig() } returns simConfig
            val syncService = service(fakeKraken, fixedNow)
            syncService.syncTradesFromKraken()
            fakeKraken.getTradeHistoryCallCount shouldBe 0
        }

        "trade coverage backfill rejects completed recovery when verified scope digest is null" {
            stubBackend(null)
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "")
            markCompletedRecovery(
                scopeDigest = defaultScopeDigest,
                requiredStart = inception,
                recoveryHorizon = fixedNow,
            )

            fakeKraken.getTradeHistoryCallCount = 0
            val syncService = service(fakeKraken, fixedNow)
            syncService.syncTradesFromKraken()
            fakeKraken.getTradeHistoryCallCount shouldBe 1
        }

        "trade coverage backfill rejects completed recovery with wrong binding version" {
            stubBackend()
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "")
            markCompletedRecovery(
                scopeDigest = defaultScopeDigest,
                requiredStart = inception,
                recoveryHorizon = fixedNow,
                bindingVersion = "old-binding",
            )

            fakeKraken.getTradeHistoryCallCount = 0
            val syncService = service(fakeKraken, fixedNow)
            syncService.syncTradesFromKraken()
            fakeKraken.getTradeHistoryCallCount shouldBe 1
        }

        "trade coverage backfill rejects completed recovery with wrong recovery version" {
            stubBackend()
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "")
            markCompletedRecovery(
                scopeDigest = defaultScopeDigest,
                requiredStart = inception,
                recoveryHorizon = fixedNow,
                recoveryVersion = "old-recovery-v0",
            )

            fakeKraken.getTradeHistoryCallCount = 0
            val syncService = service(fakeKraken, fixedNow)
            syncService.syncTradesFromKraken()
            fakeKraken.getTradeHistoryCallCount shouldBe 1
        }

        "trade coverage backfill rejects completed recovery when ledger status is incomplete" {
            stubBackend()
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "")
            markCompletedRecovery(
                scopeDigest = defaultScopeDigest,
                requiredStart = inception,
                recoveryHorizon = fixedNow,
                ledgerStatus = "FAILED",
            )

            fakeKraken.getTradeHistoryCallCount = 0
            val syncService = service(fakeKraken, fixedNow)
            syncService.syncTradesFromKraken()
            fakeKraken.getTradeHistoryCallCount shouldBe 1
        }

        "trade coverage backfill rejects completed recovery when ledger offset is not completed" {
            stubBackend()
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "")
            markCompletedRecovery(
                scopeDigest = defaultScopeDigest,
                requiredStart = inception,
                recoveryHorizon = fixedNow,
                ledgerOffset = "100",
            )

            fakeKraken.getTradeHistoryCallCount = 0
            val syncService = service(fakeKraken, fixedNow)
            syncService.syncTradesFromKraken()
            fakeKraken.getTradeHistoryCallCount shouldBe 1
        }

        "trade coverage backfill rejects completed recovery when trade offset is not completed" {
            stubBackend()
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "")
            markCompletedRecovery(
                scopeDigest = defaultScopeDigest,
                requiredStart = inception,
                recoveryHorizon = fixedNow,
                tradeOffset = "100",
            )

            fakeKraken.getTradeHistoryCallCount = 0
            val syncService = service(fakeKraken, fixedNow)
            syncService.syncTradesFromKraken()
            fakeKraken.getTradeHistoryCallCount shouldBe 1
        }

        "trade coverage backfill rejects completed recovery when horizon is before required start or after queryNow" {
            stubBackend()
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "")
            markCompletedRecovery(
                scopeDigest = defaultScopeDigest,
                requiredStart = inception,
                recoveryHorizon = inception.minusSeconds(10),
            )

            fakeKraken.getTradeHistoryCallCount = 0
            var syncService = service(fakeKraken, fixedNow)
            syncService.syncTradesFromKraken()
            fakeKraken.getTradeHistoryCallCount shouldBe 1

            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "")
            markCompletedRecovery(
                scopeDigest = defaultScopeDigest,
                requiredStart = inception,
                recoveryHorizon = fixedNow.plusSeconds(300),
            )
            fakeKraken.getTradeHistoryCallCount = 0
            syncService = service(fakeKraken, fixedNow)
            syncService.syncTradesFromKraken()
            fakeKraken.getTradeHistoryCallCount shouldBe 1
        }

        "trade coverage backfill rejects completed recovery with negative total or oldest trade after start" {
            stubBackend()
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "")
            markCompletedRecovery(
                scopeDigest = defaultScopeDigest,
                requiredStart = inception,
                recoveryHorizon = fixedNow,
                tradeTotal = "-1",
            )

            fakeKraken.getTradeHistoryCallCount = 0
            var syncService = service(fakeKraken, fixedNow)
            syncService.syncTradesFromKraken()
            fakeKraken.getTradeHistoryCallCount shouldBe 1

            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "")
            markCompletedRecovery(
                scopeDigest = defaultScopeDigest,
                requiredStart = inception,
                recoveryHorizon = fixedNow,
                tradeTotal = "5",
                tradeOldest = inception.plusSeconds(100),
            )
            fakeKraken.getTradeHistoryCallCount = 0
            syncService = service(fakeKraken, fixedNow)
            syncService.syncTradesFromKraken()
            fakeKraken.getTradeHistoryCallCount shouldBe 1
        }

        "trade coverage backfill rejects completed recovery with negative ledger total or oldest ledger after start" {
            stubBackend()
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "")
            markCompletedRecovery(
                scopeDigest = defaultScopeDigest,
                requiredStart = inception,
                recoveryHorizon = fixedNow,
                ledgerTotal = "-1",
            )

            fakeKraken.getTradeHistoryCallCount = 0
            var syncService = service(fakeKraken, fixedNow)
            syncService.syncTradesFromKraken()
            fakeKraken.getTradeHistoryCallCount shouldBe 1

            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "")
            markCompletedRecovery(
                scopeDigest = defaultScopeDigest,
                requiredStart = inception,
                recoveryHorizon = fixedNow,
                ledgerTotal = "5",
                ledgerOldest = inception.plusSeconds(100),
            )
            fakeKraken.getTradeHistoryCallCount = 0
            syncService = service(fakeKraken, fixedNow)
            syncService.syncTradesFromKraken()
            fakeKraken.getTradeHistoryCallCount shouldBe 1
        }

        "canRebuildSnapshots() enforces trade and ledger coverage horizons reach reconstruction anchor" {
            every { configService.getConfig() } returns appConfig
            stubBackend()
            ledgerRepository.setLedgersSeeded(true)
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "9")
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC,
                inception.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST,
                defaultScopeDigest,
            )
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "1")
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC,
                inception.epochSecond.toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST,
                defaultScopeDigest,
            )

            val reconstructionService = TradeHistoryReconstructionService(
                repository = repository,
                ledgerRepository = ledgerRepository,
                krakenService = fakeKraken,
                configService = configService,
                portfolioStatsRepository = portfolioStatsRepository,
                nowProvider = { fixedNow },
                accountHistoryScopeGuard = scopeGuard,
            )

            // Both horizons reach anchor: true
            reconstructionService.canRebuildSnapshots(appConfig, inception, fixedNow) shouldBe true
            // Default anchor (nowProvider = fixedNow): true
            reconstructionService.canRebuildSnapshots(appConfig, inception) shouldBe true

            // Trade horizon behind anchor: false
            val futureAnchor = fixedNow.plusSeconds(3600)
            reconstructionService.canRebuildSnapshots(appConfig, inception, futureAnchor) shouldBe false

            // Ledger horizon within the post-sync grace window: true. A horizon taken at
            // sync query time must still satisfy an anchor captured after pagination.
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.minusSeconds(60).epochSecond.toString(),
            )
            reconstructionService.canRebuildSnapshots(appConfig, inception, fixedNow) shouldBe true

            // Ledger horizon beyond the grace window: false
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.minusSeconds(
                    TradeHistoryReconstructionService.RECONSTRUCTION_ANCHOR_TOLERANCE_SECONDS + 60,
                ).epochSecond.toString(),
            )
            reconstructionService.canRebuildSnapshots(appConfig, inception, fixedNow) shouldBe false

            // Restore ledger horizon, trade horizon within grace window: true (symmetry).
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.minusSeconds(60).epochSecond.toString(),
            )
            reconstructionService.canRebuildSnapshots(appConfig, inception, fixedNow) shouldBe true

            // Trade horizon beyond the grace window: false
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.minusSeconds(
                    TradeHistoryReconstructionService.RECONSTRUCTION_ANCHOR_TOLERANCE_SECONDS + 60,
                ).epochSecond.toString(),
            )
            reconstructionService.canRebuildSnapshots(appConfig, inception, fixedNow) shouldBe false

            // Restore trade horizon, test trade start after requested start: false
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC,
                inception.plusSeconds(60).epochSecond.toString(),
            )
            reconstructionService.canRebuildSnapshots(appConfig, inception, fixedNow) shouldBe false

            // Restore trade start, test ledger start after requested start: false
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC,
                inception.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC,
                inception.plusSeconds(60).epochSecond.toString(),
            )
            reconstructionService.canRebuildSnapshots(appConfig, inception, fixedNow) shouldBe false
        }

        "reconstructHistoricalSnapshots fails closed and aborts when historical trades contain unsupported markets" {
            every { configService.getConfig() } returns appConfig
            stubBackend()
            ledgerRepository.setLedgersSeeded(true)
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "9")
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC,
                inception.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST,
                defaultScopeDigest,
            )
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "1")
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC,
                inception.epochSecond.toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )
            repository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST,
                defaultScopeDigest,
            )

            // Save an unsupported trade market
            val unsupportedTrade = TestFixtures.tradeRecord(
                timestamp = inception.plusSeconds(100),
                pair = "ADAEUR",
                side = "buy",
                symbol = "ADA",
                volume = BigDecimal("100.0"),
                usdAmount = BigDecimal.ZERO,
                price = BigDecimal("0.50"),
                fee = BigDecimal("0.10"),
                source = TradeSource.API_FILL,
                tradeId = "ADA-1",
            )
            repository.saveTrade(unsupportedTrade)

            val reconstructionService = TradeHistoryReconstructionService(
                repository = repository,
                ledgerRepository = ledgerRepository,
                krakenService = fakeKraken,
                configService = configService,
                portfolioStatsRepository = portfolioStatsRepository,
                nowProvider = { fixedNow },
                accountHistoryScopeGuard = scopeGuard,
            )

            reconstructionService.reconstructHistoricalSnapshots(appConfig, fakeKraken)

            // Reconstruction must abort and NOT stamp version 10
            repository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION) shouldBe null
        }
    }
}
