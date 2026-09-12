package com.gemini.krakenbot.service.impl.history

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.impl.SqliteLedgerRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqlitePortfolioStatsRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.FakeKrakenService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

class RawLedgerCoverageTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val db = DatabaseConfig.init(TestFixtures.MEMORY_)
    private val ledgerRepository = SqliteLedgerRepositoryImpl(db)
    private val tradeRepository = SqliteTradeRepositoryImpl(db)
    private val portfolioStatsRepository = SqlitePortfolioStatsRepositoryImpl(db, jacksonObjectMapper())
    private val fakeKraken = FakeKrakenService()
    private val configService = mockk<ConfigService>(relaxed = true)

    private val baseTime = Instant.parse("2026-06-01T12:00:00Z")
    private val fixedNow = Instant.parse("2026-07-01T12:00:00Z")

    private val appConfig = AppConfig(
        kraken = KrakenCredentials(TestFixtures.TRADE_HISTORY_API_KEY, TestFixtures.TRADE_HISTORY_API_SECRET),
        settings = TestFixtures.settings(
            dryRun = false,
            simulation = false,
            loopDelaySeconds = 60,
        ).copy(inceptionDate = "2026-06-01T00:00:00Z"),
        allocations = listOf(
            Allocation(Asset.BTC, 50.0),
            Allocation(Asset.USD, 50.0),
        ),
    )

    private fun stubBackend() {
        every { configService.getConfig() } returns appConfig
    }

    init {
        "ordinary ledger coverage retains trade ledger rows" {
            stubBackend()
            fakeKraken.seedLedgerEntries(
                listOf(
                    LedgerEvent(
                        ledgerId = "ledger-trade-1",
                        time = baseTime.plusSeconds(300),
                        type = "trade",
                        asset = "XXBT",
                        amount = BigDecimal("0.5"),
                        fee = BigDecimal("0.0005"),
                        balance = BigDecimal("1.5"),
                        hasAuthoritativeBalance = true,
                    ),
                    LedgerEvent(
                        ledgerId = "ledger-deposit-1",
                        time = baseTime.plusSeconds(100),
                        type = "deposit",
                        asset = "XXBT",
                        amount = BigDecimal("1.0"),
                        fee = BigDecimal.ZERO,
                        balance = BigDecimal("1.0"),
                        hasAuthoritativeBalance = true,
                    ),
                ),
            )

            val syncService = LedgersSyncService(
                repository = ledgerRepository,
                krakenService = fakeKraken,
                configService = configService,
                nowProvider = { fixedNow },
            )
            syncService.syncLedgersFromKraken()

            val stored = ledgerRepository.getLedgersInRange(Instant.EPOCH, fixedNow.plusSeconds(86400))
            stored.map { it.ledgerId }.toSet() shouldBe setOf("ledger-trade-1", "ledger-deposit-1")
            stored.map { it.type }.toSet() shouldBe setOf("trade", "deposit")
        }

        "trade ledger row participates in validator continuity" {
            val deposit = LedgerEvent(
                ledgerId = "dep-1",
                time = baseTime,
                type = "deposit",
                asset = "XXBT",
                amount = BigDecimal("1.0"),
                balance = BigDecimal("1.0"),
                hasAuthoritativeBalance = true,
            )
            val tradeLedger = LedgerEvent(
                ledgerId = "trade-led-1",
                time = baseTime.plusSeconds(600),
                type = "trade",
                asset = "XXBT",
                amount = BigDecimal("0.5"),
                balance = BigDecimal("1.5"),
                hasAuthoritativeBalance = true,
            )

            val validation = AuthoritativeLedgerBalanceValidator.validate(
                events = listOf(deposit, tradeLedger),
            )

            validation.isValid shouldBe true
            validation.resolvedScopes.keys shouldContain "dep-1"
        }

        "same economic trade is not replayed twice (trade ledger checkpoint only)" {
            stubBackend()
            val tradeTime = baseTime.plusSeconds(3600)
            val deposit = LedgerEvent(
                ledgerId = "dep-btc-1",
                time = baseTime,
                type = "deposit",
                asset = "BTC",
                amount = BigDecimal("1.0"),
                balance = BigDecimal("1.0"),
                hasAuthoritativeBalance = true,
            )
            val tradeLedger = LedgerEvent(
                ledgerId = "led-trade-btc",
                time = tradeTime,
                type = "trade",
                asset = "BTC",
                amount = BigDecimal("-0.2"),
                balance = BigDecimal("0.8"),
                hasAuthoritativeBalance = true,
            )
            ledgerRepository.saveLedgers(listOf(deposit, tradeLedger))
            ledgerRepository.setLedgersSeeded(true)
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "10")
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC,
                baseTime.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )

            val tradeRecord = TradeRecord(
                id = 1,
                orderTxid = "order-txid-1",
                pair = "XBTUSD",
                symbol = "BTC",
                side = "sell",
                price = BigDecimal("90000.00"),
                volume = BigDecimal("0.2"),
                usdAmount = BigDecimal("18000.00"),
                fee = BigDecimal("18.00"),
                timestamp = tradeTime,
                success = true,
                dryRun = false,
                source = TradeSource.API_FILL,
            )
            tradeRepository.saveTrade(tradeRecord)
            tradeRepository.setHistorySeeded(true)
            tradeRepository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "2")
            tradeRepository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC,
                baseTime.epochSecond.toString(),
            )
            tradeRepository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )

            val ohlc = listOf(
                baseTime.truncatedTo(ChronoUnit.DAYS).epochSecond to BigDecimal("90000.00"),
                fixedNow.truncatedTo(ChronoUnit.DAYS).epochSecond to BigDecimal("90000.00"),
            )
            fakeKraken.ohlcSupplier = { _, _, _ -> ohlc }
            fakeKraken.balanceSupplier = {
                mapOf(Asset.BTC to BigDecimal("0.8"), Asset.USD to BigDecimal("17982.00"))
            }

            val reconstructionService = TradeHistoryReconstructionService(
                repository = tradeRepository,
                ledgerRepository = ledgerRepository,
                krakenService = fakeKraken,
                configService = configService,
                portfolioStatsRepository = portfolioStatsRepository,
                nowProvider = { fixedNow },
            )

            reconstructionService.rebuildHistoricalSnapshots(appConfig, fakeKraken)

            val snapshots = tradeRepository.load()
            snapshots.shouldNotBeEmpty()
            val finalSnapshot = snapshots.first()
            // BTC balance must be 0.8 (1.0 - 0.2 from trade), NOT 0.6 from double-subtracting trade ledger!
            finalSnapshot.assets.getValue("BTC").balance shouldBeEqualComparingTo BigDecimal("0.8")
        }

        "unknown top-level ledger type returned by coverage query is retained in database" {
            stubBackend()
            fakeKraken.seedLedgerEntries(
                listOf(
                    LedgerEvent(
                        ledgerId = "unknown-type-ledger-1",
                        time = baseTime.plusSeconds(500),
                        type = "unknown_type",
                        asset = "XXBT",
                        amount = BigDecimal("0.05"),
                        balance = BigDecimal("1.05"),
                        hasAuthoritativeBalance = true,
                    ),
                ),
            )

            val syncService = LedgersSyncService(
                repository = ledgerRepository,
                krakenService = fakeKraken,
                configService = configService,
                nowProvider = { fixedNow },
            )
            syncService.syncLedgersFromKraken()

            val stored = ledgerRepository.getLedgersInRange(Instant.EPOCH, fixedNow.plusSeconds(86400))
            stored.map { it.ledgerId } shouldBe listOf("unknown-type-ledger-1")
            stored.first().type shouldBe "unknown_type"
        }

        "unknown raw ledger type causes downstream fail-closed validation and halts reconstruction" {
            stubBackend()
            val unknownLedger = LedgerEvent(
                ledgerId = "unknown-type-ledger-1",
                time = baseTime.plusSeconds(500),
                type = "super_staking",
                asset = "BTC",
                amount = BigDecimal("0.05"),
                balance = BigDecimal("1.05"),
                hasAuthoritativeBalance = true,
            )
            ledgerRepository.saveLedgers(listOf(unknownLedger))
            ledgerRepository.setLedgersSeeded(true)
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "10")
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC,
                baseTime.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )

            tradeRepository.setHistorySeeded(true)
            tradeRepository.setSyncMetadata(SyncMetadataKeys.TRADE_COVERAGE_VERSION, "2")
            tradeRepository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_START_EPOCH_SEC,
                baseTime.epochSecond.toString(),
            )
            tradeRepository.setSyncMetadata(
                SyncMetadataKeys.TRADE_COVERAGE_HORIZON_EPOCH_SEC,
                fixedNow.epochSecond.toString(),
            )
            tradeRepository.setSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION, "old_version")

            fakeKraken.balanceSupplier = {
                mapOf(Asset.BTC to BigDecimal("1.05"), Asset.USD to BigDecimal("10000.00"))
            }

            // Direct validator assertion: must be invalid due to unsupported ledger type
            val validation = AuthoritativeLedgerBalanceValidator.validate(
                events = listOf(unknownLedger),
            )
            validation.isValid shouldBe false
            validation.failure?.detail shouldBe "unsupported ledger type"

            // Reconstruction service assertion: halts and leaves reconstruction version unchanged
            val reconstructionService = TradeHistoryReconstructionService(
                repository = tradeRepository,
                ledgerRepository = ledgerRepository,
                krakenService = fakeKraken,
                configService = configService,
                portfolioStatsRepository = portfolioStatsRepository,
                nowProvider = { fixedNow },
            )

            reconstructionService.rebuildHistoricalSnapshots(appConfig, fakeKraken)

            tradeRepository.getSyncMetadata(SyncMetadataKeys.SNAPSHOT_RECONSTRUCTION_VERSION) shouldBe "old_version"
            tradeRepository.load().shouldBeEmpty()
        }

        "existing reward, conversion, staking, and deposit types remain retained" {
            stubBackend()
            fakeKraken.seedLedgerEntries(
                listOf(
                    LedgerEvent(
                        ledgerId = "dep-1",
                        time = baseTime,
                        type = "deposit",
                        asset = "ZUSD",
                        amount = BigDecimal("1000.00"),
                    ),
                    LedgerEvent(
                        ledgerId = "stk-1",
                        time = baseTime.plusSeconds(60),
                        type = "staking",
                        asset = "XXBT",
                        amount = BigDecimal("0.01"),
                    ),
                    LedgerEvent(
                        ledgerId = "earn-1",
                        time = baseTime.plusSeconds(120),
                        type = "earn",
                        subtype = "reward",
                        asset = "XXBT",
                        amount = BigDecimal("0.02"),
                    ),
                    LedgerEvent(
                        ledgerId = "conv-1",
                        time = baseTime.plusSeconds(180),
                        type = "conversion",
                        refid = "CONV-REF",
                        asset = "USDG",
                        amount = BigDecimal("500.00"),
                    ),
                ),
            )

            val syncService = LedgersSyncService(
                repository = ledgerRepository,
                krakenService = fakeKraken,
                configService = configService,
                nowProvider = { fixedNow },
            )
            syncService.syncLedgersFromKraken()

            val stored = ledgerRepository.getLedgersInRange(Instant.EPOCH, fixedNow.plusSeconds(86400))
            stored.map { it.ledgerId }.toSet() shouldBe setOf("dep-1", "stk-1", "earn-1", "conv-1")
            stored.map { it.type }.toSet() shouldBe setOf("deposit", "staking", "earn", "conversion")
        }

        "pagination and deduplication remain correct when using raw coverage retrieval" {
            stubBackend()
            val entries = (0 until 120).map { i ->
                LedgerEvent(
                    ledgerId = "raw-ledger-$i",
                    time = baseTime.plusSeconds(i.toLong() * 10),
                    type = if (i % 2 == 0) "trade" else "staking",
                    asset = "XXBT",
                    amount = BigDecimal("0.001"),
                    balance = BigDecimal("1.0"),
                    hasAuthoritativeBalance = true,
                )
            }
            fakeKraken.seedLedgerEntries(entries)

            val syncService = LedgersSyncService(
                repository = ledgerRepository,
                krakenService = fakeKraken,
                configService = configService,
                nowProvider = { fixedNow },
            )

            syncService.syncLedgersFromKraken()
            val initialCount = ledgerRepository.getLedgersInRange(Instant.EPOCH, fixedNow.plusSeconds(86400)).size
            initialCount shouldBe 120

            // Second sync over overlapping range should not create duplicates
            syncService.syncLedgersFromKraken()
            val secondCount = ledgerRepository.getLedgersInRange(Instant.EPOCH, fixedNow.plusSeconds(86400)).size
            secondCount shouldBe 120
        }

        "malformed page or envelope count continues to fail closed" {
            stubBackend()
            val mockKraken = mockk<com.gemini.krakenbot.service.KrakenService>(relaxed = true)
            coEvery {
                mockKraken.withStableBackend(any<suspend (com.gemini.krakenbot.service.KrakenService) -> Any?>())
            } coAnswers {
                val block = firstArg<suspend (com.gemini.krakenbot.service.KrakenService) -> Any?>()
                block(mockKraken)
            }
            every { mockKraken.hasLastLedgerTotalCount() } returns true
            every { mockKraken.hasLastLedgerPageShape() } returns true
            every { mockKraken.getLastLedgerTotalCount() } returns 100
            every { mockKraken.getLastLedgerRawPageSize() } returns 20 // mismatch: offset 0 with count 100 expects 50!
            coEvery { mockKraken.getLedgers(any(), any(), any(), any()) } returns emptyList()

            val syncService = LedgersSyncService(
                repository = ledgerRepository,
                krakenService = mockKraken,
                configService = configService,
                nowProvider = { fixedNow },
            )

            val ex = shouldThrow<IllegalStateException> {
                syncService.syncLedgersFromKraken()
            }
            ex.message shouldContain "Kraken ledger page occupancy disagreed with count"
        }

        "count-less incremental ledger refresh advances only the sync watermark, not certified coverage" {
            stubBackend()
            val certifiedHorizon = fixedNow.minusSeconds(3600)
            ledgerRepository.setLedgersSeeded(true)
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "10")
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC,
                baseTime.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                certifiedHorizon.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                certifiedHorizon.epochSecond.toString(),
            )
            fakeKraken.ledgerTotalCountAvailable = false
            fakeKraken.ledgerSupplier = { _, _, _, _ -> emptyList() }

            LedgersSyncService(
                repository = ledgerRepository,
                krakenService = fakeKraken,
                configService = configService,
                nowProvider = { fixedNow },
            ).syncLedgersFromKraken()

            ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC) shouldBe
                fixedNow.epochSecond.toString()
            ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) shouldBe
                certifiedHorizon.epochSecond.toString()
            ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) shouldBe "10"
        }

        "empty authoritative ledger scan certifies the contiguous tail" {
            stubBackend()
            val certifiedHorizon = fixedNow.minusSeconds(3600)
            ledgerRepository.setLedgersSeeded(true)
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "10")
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC,
                baseTime.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                certifiedHorizon.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                certifiedHorizon.epochSecond.toString(),
            )
            fakeKraken.ledgerTotalCountAvailable = true
            fakeKraken.ledgerTotalCountOverride = 0
            fakeKraken.ledgerSupplier = { _, _, _, _ -> emptyList() }

            LedgersSyncService(
                repository = ledgerRepository,
                krakenService = fakeKraken,
                configService = configService,
                nowProvider = { fixedNow },
            ).syncLedgersFromKraken()

            ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC) shouldBe
                fixedNow.epochSecond.toString()
            ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) shouldBe
                fixedNow.epochSecond.toString()
        }

        "authoritative ledger scan retains raw unknown and trade checkpoint rows and extends coverage" {
            stubBackend()
            val certifiedHorizon = fixedNow.minusSeconds(3600)
            ledgerRepository.setLedgersSeeded(true)
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "10")
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC,
                baseTime.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                certifiedHorizon.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                certifiedHorizon.epochSecond.toString(),
            )
            fakeKraken.ledgerTotalCountAvailable = true
            fakeKraken.ledgerTotalCountOverride = 2
            fakeKraken.ledgerSupplier = { _, offset, _, _ ->
                if (offset == 0) {
                    listOf(
                        LedgerEvent(
                            ledgerId = "mystery-1",
                            time = certifiedHorizon.plusSeconds(600),
                            type = "mystery_type",
                            asset = "XXBT",
                            amount = BigDecimal("0.01"),
                            balance = BigDecimal("1.01"),
                            hasAuthoritativeBalance = true,
                        ),
                        LedgerEvent(
                            ledgerId = "trade-checkpoint-1",
                            time = certifiedHorizon.plusSeconds(700),
                            type = "trade",
                            asset = "XXBT",
                            amount = BigDecimal("-0.1"),
                            balance = BigDecimal("0.91"),
                            hasAuthoritativeBalance = true,
                        ),
                    )
                } else {
                    emptyList()
                }
            }

            LedgersSyncService(
                repository = ledgerRepository,
                krakenService = fakeKraken,
                configService = configService,
                nowProvider = { fixedNow },
            ).syncLedgersFromKraken()

            val stored = ledgerRepository.getLedgersInRange(Instant.EPOCH, fixedNow.plusSeconds(86400))
            stored.map { it.ledgerId }.toSet() shouldBe setOf("mystery-1", "trade-checkpoint-1")
            stored.map { it.type }.toSet() shouldBe setOf("mystery_type", "trade")
            ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) shouldBe
                fixedNow.epochSecond.toString()
        }

        "failed incremental ledger pull advances neither the watermark nor certified coverage" {
            stubBackend()
            val certifiedHorizon = fixedNow.minusSeconds(3600)
            ledgerRepository.setLedgersSeeded(true)
            ledgerRepository.setSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION, "10")
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_START_EPOCH_SEC,
                baseTime.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC,
                certifiedHorizon.epochSecond.toString(),
            )
            ledgerRepository.setSyncMetadata(
                SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC,
                certifiedHorizon.epochSecond.toString(),
            )
            fakeKraken.ledgerTotalCountAvailable = true
            fakeKraken.ledgerTotalCountOverride = 1
            fakeKraken.ledgerSupplier = { _, _, _, _ -> throw IllegalStateException("ledger network down") }

            shouldThrow<IllegalStateException> {
                LedgersSyncService(
                    repository = ledgerRepository,
                    krakenService = fakeKraken,
                    configService = configService,
                    nowProvider = { fixedNow },
                ).syncLedgersFromKraken()
            }

            ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC) shouldBe
                certifiedHorizon.epochSecond.toString()
            ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_HORIZON_EPOCH_SEC) shouldBe
                certifiedHorizon.epochSecond.toString()
            ledgerRepository.getSyncMetadata(SyncMetadataKeys.LEDGER_COVERAGE_VERSION) shouldBe "10"
        }
    }
}
