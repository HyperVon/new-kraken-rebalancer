package com.gemini.krakenbot.service.impl.history

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.impl.SqliteLedgerRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqlitePortfolioStatsRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.FakeKrakenService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

class TradeHistoryReconstructionInceptionBackfillTest :
    StringSpec({
        isolationMode = IsolationMode.InstancePerTest

        val database = DatabaseConfig.init(TestFixtures.MEMORY_)
        val repository = SqliteTradeRepositoryImpl(database)
        val ledgerRepository = SqliteLedgerRepositoryImpl(database)
        val statsRepository = SqlitePortfolioStatsRepositoryImpl(
            database,
            jacksonObjectMapper(),
            Files.createTempDirectory("backfill-stats").resolve("stats.json").toString(),
        )
        val krakenService = FakeKrakenService()
        val configService = mockk<ConfigService>(relaxed = true)

        val inception = Instant.parse("2025-12-05T17:00:56Z")
        val now = Instant.parse("2026-09-10T18:00:00Z")

        val appConfig = AppConfig(
            kraken = KrakenCredentials(TestFixtures.TRADE_HISTORY_API_KEY, TestFixtures.TRADE_HISTORY_API_SECRET),
            settings = TestFixtures.settings(
                dryRun = false,
                simulation = false,
            ).copy(inceptionDate = inception.toString()),
            allocations = listOf(
                Allocation(Asset.BTC, 50.0),
                Allocation(Asset.USD, 50.0),
            ),
        )

        every { configService.getConfig() } returns appConfig

        val reconstructionService = TradeHistoryReconstructionService(
            repository = repository,
            ledgerRepository = ledgerRepository,
            krakenService = krakenService,
            configService = configService,
            portfolioStatsRepository = statsRepository,
            nowProvider = { now },
        )

        val queryService = TradeHistoryQueryService(
            repository = repository,
            portfolioStatsRepository = statsRepository,
            ledgerRepository = ledgerRepository,
            nowProvider = { now },
        )

        "reconstructs full historical timeline back to inception date bridging all snapshot gaps" {
            runTest {
                // Seed ledger seeded marker and coverage
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGERS_SEEDED, "true")
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_COVERAGE_VERSION,
                    LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
                )

                // Seed fake OHLC daily close prices from inception to now (~280 days)
                val days = ChronoUnit.DAYS.between(
                    inception.truncatedTo(ChronoUnit.DAYS),
                    now.truncatedTo(ChronoUnit.DAYS),
                ).toInt()
                val ohlc = (0..days + 1).map { d ->
                    val dayInstant = inception.truncatedTo(ChronoUnit.DAYS).plus(d.toLong(), ChronoUnit.DAYS)
                    val daySec = dayInstant.epochSecond
                    val price = BigDecimal("90000.00").add(BigDecimal(d * 10))
                    daySec to price
                }
                krakenService.ohlcSupplier = { pair, _, _ ->
                    if (pair.contains("BTC", ignoreCase = true)) ohlc else emptyList()
                }

                // Seed initial trade at inception
                val trade = TradeRecord(
                    id = 1,
                    timestamp = inception,
                    pair = Asset.tradingPair(Asset.BTC),
                    side = "buy",
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.5"),
                    usdAmount = BigDecimal("45000.00"),
                    price = BigDecimal("90000.00"),
                    fee = BigDecimal("5.00"),
                    success = true,
                    dryRun = false,
                    source = TradeSource.API_FILL,
                )
                repository.saveTrade(trade)

                // Seed balances
                krakenService.balanceSupplier = {
                    mapOf(
                        Asset.BTC to BigDecimal("0.5"),
                        Asset.USD to BigDecimal("50000.00"),
                    )
                }

                reconstructionService.rebuildHistoricalSnapshots(appConfig, krakenService)

                val snapshots = repository.getAllSnapshotsInRange(Instant.EPOCH, Instant.ofEpochMilli(Long.MAX_VALUE))
                    .sortedBy { it.timestamp }

                // Should have ~280 daily close snapshots plus inception anchor
                snapshots.size shouldBe days + 1

                val firstSnapshot = snapshots.first()
                firstSnapshot.timestamp shouldBe inception

                // No coverage gap > 86400s between any consecutive snapshots
                snapshots.zipWithNext().forEach { (prev, curr) ->
                    (Duration.between(prev.timestamp, curr.timestamp).seconds <= 86400L) shouldBe true
                }

                // Verify continuous history start is recorded at inception
                repository.getSyncMetadata(SyncMetadataKeys.CONTINUOUS_HISTORY_START_EPOCH_MS) shouldBe
                    inception.toEpochMilli().toString()

                // Query comparison from inception to now
                val comparison = queryService.getRebalancerComparison(inception, now)
                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                (comparison.points.size > 2) shouldBe true
            }
        }

        "reverse ledger replay respects wallet scopes during snapshot reconstruction" {
            runTest {
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGERS_SEEDED, "true")
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_COVERAGE_VERSION,
                    LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
                )

                val ohlc = listOf(
                    inception.truncatedTo(ChronoUnit.DAYS).epochSecond to BigDecimal("90000.00"),
                    now.truncatedTo(ChronoUnit.DAYS).epochSecond to BigDecimal("90000.00"),
                )
                krakenService.ohlcSupplier = { _, _, _ -> ohlc }

                // Staking reward in non-Spot wallet should not mutate Spot balance during reverse replay
                val stakingLedger = LedgerEvent(
                    ledgerId = "staking-reward-1",
                    time = inception.plusSeconds(3600),
                    type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                    asset = Asset.BTC,
                    amount = BigDecimal("0.05"),
                    fee = BigDecimal.ZERO,
                    balance = BigDecimal("0.05"),
                    hasAuthoritativeBalance = false,
                )
                ledgerRepository.saveLedgers(listOf(stakingLedger))

                krakenService.balanceSupplier = {
                    mapOf(
                        Asset.BTC to BigDecimal("1.0"),
                        Asset.USD to BigDecimal("10000.00"),
                    )
                }

                reconstructionService.rebuildHistoricalSnapshots(appConfig, krakenService)

                val snapshots = repository.getAllSnapshotsInRange(Instant.EPOCH, Instant.ofEpochMilli(Long.MAX_VALUE))
                    .sortedBy { it.timestamp }

                val inceptionSnapshot = snapshots.first { it.timestamp == inception }
                // BTC balance should remain 1.0 because the staking reward was non-Spot and not subtracted from Spot
                inceptionSnapshot.assets.getValue(Asset.BTC).balance shouldBeEqualComparingTo BigDecimal("1.0")
            }
        }

        "handles various inception dates and invalid ledger validation" {
            runTest {
                ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGERS_SEEDED, "true")
                ledgerRepository.setSyncMetadata(
                    SyncMetadataKeys.LEDGER_COVERAGE_VERSION,
                    LedgersSyncService.CURRENT_LEDGER_COVERAGE_VERSION,
                )

                val ohlc = listOf(
                    now.minus(96, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS).epochSecond to BigDecimal("90000.00"),
                    now.truncatedTo(ChronoUnit.DAYS).epochSecond to BigDecimal("90000.00"),
                )
                krakenService.ohlcSupplier = { _, _, _ -> ohlc }
                krakenService.balanceSupplier = {
                    mapOf(Asset.BTC to BigDecimal("1.0"), Asset.USD to BigDecimal("10000.00"))
                }

                // 1. Blank inception date
                val blankConfig = appConfig.copy(settings = appConfig.settings.copy(inceptionDate = ""))
                every { configService.getConfig() } returns blankConfig
                reconstructionService.rebuildHistoricalSnapshots(blankConfig, krakenService)
                repository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION) shouldBe
                    TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION

                // 2. Invalid inception date
                val invalidConfig = appConfig.copy(settings = appConfig.settings.copy(inceptionDate = "not-a-date"))
                every { configService.getConfig() } returns invalidConfig
                reconstructionService.rebuildHistoricalSnapshots(invalidConfig, krakenService)
                repository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION) shouldBe
                    TradeHistoryReconstructionService.CURRENT_RECONSTRUCTION_VERSION

                // 3. Recent inception date (e.g. 5 days ago, not before defaultSince = 95 days ago)
                val recentConfig = appConfig.copy(
                    settings = appConfig.settings.copy(inceptionDate = now.minus(5, ChronoUnit.DAYS).toString()),
                )
                every { configService.getConfig() } returns recentConfig
                reconstructionService.rebuildHistoricalSnapshots(recentConfig, krakenService)
                val recentSnapshots = repository.getAllSnapshotsInRange(
                    Instant.EPOCH,
                    Instant.ofEpochMilli(Long.MAX_VALUE),
                )
                recentSnapshots.minOf { it.timestamp } shouldBe now.minus(5, ChronoUnit.DAYS)

                // 4. Ledger validation is invalid (contradictory checkpoints)
                val contradictory1 = LedgerEvent(
                    ledgerId = "c1",
                    time = now.minus(10, ChronoUnit.DAYS),
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = Asset.BTC,
                    amount = BigDecimal("1.0"),
                    balance = BigDecimal("1.0"),
                    hasAuthoritativeBalance = true,
                )
                val contradictory2 = LedgerEvent(
                    ledgerId = "c2",
                    time = now.minus(9, ChronoUnit.DAYS),
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = Asset.BTC,
                    amount = BigDecimal("1.0"),
                    balance = BigDecimal("5.0"),
                    hasAuthoritativeBalance = true,
                )
                ledgerRepository.saveLedgers(listOf(contradictory1, contradictory2))
                repository.setSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION, "7")
                val snapshotCountBeforeInvalidValidation = repository
                    .getAllSnapshotsInRange(Instant.EPOCH, Instant.ofEpochMilli(Long.MAX_VALUE))
                    .size
                reconstructionService.rebuildHistoricalSnapshots(recentConfig, krakenService)
                repository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION) shouldBe "7"
                repository.getAllSnapshotsInRange(Instant.EPOCH, Instant.ofEpochMilli(Long.MAX_VALUE)).size shouldBe
                    snapshotCountBeforeInvalidValidation
            }
        }
    })
