package com.gemini.krakenbot.service.impl.history

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.model.hasValidEconomicFields
import com.gemini.krakenbot.repository.impl.SqliteLedgerRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqlitePortfolioStatsRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.impl.KrakenParsers
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant

@Suppress("unused")
class HistoricalEvidenceContractTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val db = DatabaseConfig.init(TestFixtures.MEMORY_)
    private val repository = SqliteTradeRepositoryImpl(db)
    private val ledgerRepository = SqliteLedgerRepositoryImpl(db)
    private val statsRepository = SqlitePortfolioStatsRepositoryImpl(db, jacksonObjectMapper())
    private val fakeKraken = FakeKrakenService()
    private val configService = mockk<ConfigService>(relaxed = true)
    private val scopeGuard = mockk<AccountHistoryScopeGuard>(relaxed = true)

    private val fixedNow = Instant.parse("2026-07-01T12:00:00Z")
    private val inception = Instant.parse("2025-12-05T17:00:56Z")
    private val scopeDigest = AccountHistoryScopeGuard.digestAccountScope("test-account")

    private val appConfig = AppConfig(
        kraken = KrakenCredentials(TestFixtures.TRADE_HISTORY_API_KEY, TestFixtures.TRADE_HISTORY_API_SECRET),
        settings = TestFixtures.settings(dryRun = false, simulation = false, loopDelaySeconds = 60)
            .copy(inceptionDate = inception.toString()),
        allocations = listOf(Allocation(Asset.BTC, 50.0), Allocation(Asset.USD, 50.0)),
    )

    private suspend fun seedCoverage(tradeHorizon: Instant, ledgerHorizon: Instant) {
        repository.setHistorySeeded(true)
        repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "1")
        repository.setSyncMetadata(
            SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC,
            inception.epochSecond.toString(),
        )
        repository.setSyncMetadata(
            SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC,
            tradeHorizon.epochSecond.toString(),
        )
        repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_ACCOUNT_SCOPE_DIGEST, scopeDigest)
        ledgerRepository.setLedgersSeeded(true)
        ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "9")
        ledgerRepository.setSyncMetadata(
            SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC,
            inception.epochSecond.toString(),
        )
        ledgerRepository.setSyncMetadata(
            SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
            ledgerHorizon.epochSecond.toString(),
        )
        ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_ACCOUNT_SCOPE_DIGEST, scopeDigest)
        every { configService.getConfig() } returns appConfig
        coEvery { scopeGuard.validateAccountScope() } returns AccountScopeValidationResult(
            status = AccountScopeValidationStatus.VALID,
            currentScopeDigest = scopeDigest,
        )
    }

    private fun reconService(now: Instant = fixedNow) = TradeHistoryReconstructionService(
        repository = repository,
        ledgerRepository = ledgerRepository,
        krakenService = fakeKraken,
        configService = configService,
        portfolioStatsRepository = statsRepository,
        nowProvider = { now },
        accountHistoryScopeGuard = scopeGuard,
    )

    init {
        // P1 BLOCKER 1 — common anchor, no stale tolerance.
        "anchor rejects evidence 180s behind balance observation" {
            seedCoverage(fixedNow.minusSeconds(180), fixedNow.minusSeconds(180))
            reconService().canRebuildSnapshots(appConfig, inception, fixedNow) shouldBe false
        }
        "anchor accepts evidence reaching observation" {
            seedCoverage(fixedNow, fixedNow)
            reconService().canRebuildSnapshots(appConfig, inception, fixedNow) shouldBe true
        }
        "anchor accepts evidence newer than observation" {
            seedCoverage(fixedNow.plusSeconds(60), fixedNow.plusSeconds(60))
            reconService().canRebuildSnapshots(appConfig, inception, fixedNow) shouldBe true
        }
        "anchor does not false-fail when wall clock moves after capture" {
            seedCoverage(fixedNow, fixedNow)
            val svc = reconService(now = fixedNow.plusSeconds(3600))
            svc.canRebuildSnapshots(appConfig, inception, fixedNow) shouldBe true
        }
        "reconstruction through equals anchor not later now" {
            seedCoverage(fixedNow, fixedNow)
            fakeKraken.balanceSupplier = {
                mapOf("XXBT" to BigDecimal("1.0"), "ZUSD" to BigDecimal("50000"))
            }
            fakeKraken.pricesSupplier = { mapOf("XXBTZUSD" to mapOf("c" to listOf("50000.0"))) }
            fakeKraken.ohlcSupplier = { _, _, _ -> listOf(1L to BigDecimal("50000")) }
            coEvery { scopeGuard.validateAccountScope() } returns AccountScopeValidationResult(
                status = AccountScopeValidationStatus.VALID,
                currentScopeDigest = scopeDigest,
            )
            reconService().reconstructHistoricalSnapshots(appConfig, fakeKraken)
            val through = repository.getSyncMetadata(
                SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC,
            )?.toLongOrNull()
            // Through must equal the captured anchor (fixedNow), not a later wall clock.
            through shouldBe fixedNow.epochSecond
        }

        // P1 BLOCKER 2 — raw ledgers reach comparison.
        "comparison fails closed on unknown raw ledger type" {
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
            coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns listOf(
                LedgerEvent(
                    ledgerId = "L-unknown",
                    time = fixedNow.plusSeconds(100),
                    type = "mysterytype",
                    asset = "BTC",
                    amount = BigDecimal("0.1"),
                ),
            )
            coEvery { mockTrades.getSyncMetadata(any()) } returns null
            coEvery { mockLedgers.getSyncMetadata(any()) } returns null
            val result = svc.getRebalancerComparison(fixedNow, fixedNow.plusSeconds(86400))
            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
        }
        "comparison fails closed on unsupported raw trade market" {
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
            val badTrade = TestFixtures.tradeRecord(
                timestamp = fixedNow.plusSeconds(100),
                pair = "ADAEUR",
                side = "buy",
                symbol = "ADAEUR",
                volume = BigDecimal("10"),
                usdAmount = BigDecimal.ZERO,
                price = BigDecimal("0.5"),
                fee = BigDecimal.ZERO,
                source = TradeSource.API_FILL,
                tradeId = "bad-1",
            )
            coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns listOf(s1, s2)
            coEvery { mockTrades.getAllSnapshotsInRange(any(), any()) } returns listOf(s1, s2)
            coEvery { mockTrades.getTradesInRange(any(), any()) } returns listOf(badTrade)
            coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns emptyList()
            coEvery { mockTrades.getSyncMetadata(any()) } returns null
            coEvery { mockLedgers.getSyncMetadata(any()) } returns null
            val result = svc.getRebalancerComparison(fixedNow, fixedNow.plusSeconds(86400))
            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
            result.unavailableReason shouldBe ComparisonUnavailableReason.UNSUPPORTED_TRADE
        }

        // P1 BLOCKER 3 — certification requires authoritative count.
        "seeded coverage migration with missing count does not promote version" {
            every { configService.getConfig() } returns appConfig
            coEvery { scopeGuard.validateAccountScope() } returns AccountScopeValidationResult(
                status = AccountScopeValidationStatus.VALID,
                currentScopeDigest = scopeDigest,
            )
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "")
            fakeKraken.tradeHistorySupplier = { _, _ -> emptyList() }
            fakeKraken.tradeHistoryTotalCountAvailable = false
            fakeKraken.tradeHistoryTotalCountOverride = 0
            val sync = TradeHistorySyncService(
                repository = repository,
                krakenService = fakeKraken,
                configService = configService,
                reconstructionService = reconService(),
                ledgerRepository = ledgerRepository,
                nowProvider = { fixedNow },
                accountHistoryScopeGuard = scopeGuard,
            )
            try {
                sync.syncTradesFromKraken()
            } catch (_: Exception) {
            }
            repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) shouldBe ""
        }
        "seeded coverage migration with count zero and valid empty certifies" {
            every { configService.getConfig() } returns appConfig
            coEvery { scopeGuard.validateAccountScope() } returns AccountScopeValidationResult(
                status = AccountScopeValidationStatus.VALID,
                currentScopeDigest = scopeDigest,
            )
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "")
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST,
                scopeDigest,
            )
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
                com.gemini.krakenbot.service.InceptionRecoveryStatus.COMPLETE,
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS,
                com.gemini.krakenbot.service.InceptionRecoveryStatus.COMPLETE,
            )
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_OFFSET, "completed")
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_OFFSET, "completed")
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.INCEPTION_RECOVERY_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_TRADE_TOTAL, "0")
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_TOTAL, "0")
            fakeKraken.tradeHistorySupplier = { _, _ -> emptyList() }
            fakeKraken.tradeHistoryTotalCountAvailable = true
            fakeKraken.tradeHistoryTotalCountOverride = 0
            val sync = TradeHistorySyncService(
                repository = repository,
                krakenService = fakeKraken,
                configService = configService,
                reconstructionService = reconService(),
                ledgerRepository = ledgerRepository,
                nowProvider = { fixedNow },
                accountHistoryScopeGuard = scopeGuard,
            )
            sync.syncTradesFromKraken()
            repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) shouldBe "1"
        }
        "seeded coverage migration with malformed page does not promote" {
            every { configService.getConfig() } returns appConfig
            coEvery { scopeGuard.validateAccountScope() } returns AccountScopeValidationResult(
                status = AccountScopeValidationStatus.VALID,
                currentScopeDigest = scopeDigest,
            )
            repository.setHistorySeeded(true)
            repository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "")
            fakeKraken.tradeHistorySupplier = { _, _ -> emptyList() }
            fakeKraken.tradeHistoryTotalCountAvailable = true
            fakeKraken.tradeHistoryTotalCountOverride = 0
            fakeKraken.tradeHistoryPageShapeValid = false
            val sync = TradeHistorySyncService(
                repository = repository,
                krakenService = fakeKraken,
                configService = configService,
                reconstructionService = reconService(),
                ledgerRepository = ledgerRepository,
                nowProvider = { fixedNow },
                accountHistoryScopeGuard = scopeGuard,
            )
            try {
                sync.syncTradesFromKraken()
            } catch (_: Exception) {
            }
            repository.getSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION) shouldBe ""
        }

        // Row-level validity.
        "malformed volume retained but marked invalid and fails closed" {
            val mapper = jacksonObjectMapper()
            val node = mapper.readTree(
                """{"count":1,"trades":{"T1":{"pair":"XBTUSD","type":"buy","time":1751328000.0,"price":"50000.0",""" +
                    """"cost":"1000.0","vol":"garbage","fee":"1.0","ordertxid":"O1"}}}""",
            )
            val page = KrakenParsers.parseTradeHistoryPage(node, listOf("BTC", "USD"), true)
            page.entries.size shouldBe 1
            page.entries[0].hasValidVolume shouldBe false
            page.entries[0].hasValidEconomicFields() shouldBe false
        }
        "malformed cost retained but marked invalid" {
            val mapper = jacksonObjectMapper()
            val node = mapper.readTree(
                """{"count":1,"trades":{"T1":{"pair":"XBTUSD","type":"buy","time":1751328000.0,"price":"50000.0",""" +
                    """"cost":"garbage","vol":"0.02","fee":"1.0","ordertxid":"O1"}}}""",
            )
            val page = KrakenParsers.parseTradeHistoryPage(node, listOf("BTC", "USD"), true)
            page.entries[0].hasValidCost shouldBe false
        }
        "malformed fee retained but marked invalid" {
            val mapper = jacksonObjectMapper()
            val node = mapper.readTree(
                """{"count":1,"trades":{"T1":{"pair":"XBTUSD","type":"buy","time":1751328000.0,"price":"50000.0",""" +
                    """"cost":"1000.0","vol":"0.02","fee":"garbage","ordertxid":"O1"}}}""",
            )
            val page = KrakenParsers.parseTradeHistoryPage(node, listOf("BTC", "USD"), true)
            page.entries[0].hasValidFee shouldBe false
        }
        "malformed supported trade cannot become valid zero fill" {
            val bad = TradeRecord(
                timestamp = fixedNow,
                pair = "XBTUSD",
                side = "BUY",
                symbol = "XBT",
                volume = BigDecimal.ZERO,
                usdAmount = BigDecimal.ZERO,
                success = true,
                dryRun = false,
                price = BigDecimal.ZERO,
                fee = BigDecimal.ZERO,
                source = TradeSource.API_FILL,
                hasValidVolume = false,
                hasValidCost = true,
                hasValidPrice = true,
                hasValidFee = true,
            )
            bad.hasValidEconomicFields() shouldBe false
        }
        "legacy rows default to valid" {
            val legacy = TestFixtures.tradeRecord(
                timestamp = fixedNow,
                pair = "XBTUSD",
                side = "buy",
                symbol = "XBT",
                volume = BigDecimal("0.01"),
                usdAmount = BigDecimal("500"),
            )
            legacy.hasValidEconomicFields() shouldBe true
        }

        // Stale reconstruction.
        "stale reconstructed snapshots do not verify" {
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
            coEvery { mockTrades.getSyncMetadata(any()) } returns null
            coEvery { mockLedgers.getSyncMetadata(any()) } returns null
            coEvery { mockTrades.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION) } returns ""
            coEvery { mockTrades.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_THROUGH_EPOCH_SEC) } returns
                fixedNow.plusSeconds(86400).epochSecond.toString()
            coEvery { mockTrades.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_START_EPOCH_SEC) } returns
                fixedNow.epochSecond.toString()
            val result = svc.getRebalancerComparison(fixedNow, fixedNow.plusSeconds(86400))
            result.availability shouldBe ComparisonAvailability.UNAVAILABLE
        }
    }
}
