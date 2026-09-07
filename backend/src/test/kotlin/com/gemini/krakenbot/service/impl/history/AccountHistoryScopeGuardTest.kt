package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.impl.SqliteLedgerRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.FakeKrakenService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant

class AccountHistoryScopeGuardTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val database = DatabaseConfig.init(TestFixtures.MEMORY_)
    private val tradeRepository = SqliteTradeRepositoryImpl(database)
    private val ledgerRepository = SqliteLedgerRepositoryImpl(database)
    private val krakenService = FakeKrakenService()
    private val configService = mockk<ConfigService>()
    private val config = TestFixtures.DEFAULT_TEST_CONFIG.copy(
        kraken = KrakenCredentials(
            TestFixtures.TRADE_HISTORY_API_KEY,
            TestFixtures.TRADE_HISTORY_API_SECRET,
        ),
        settings = TestFixtures.DEFAULT_TEST_CONFIG.settings.copy(simulation = false),
    )
    private val guard = AccountHistoryScopeGuard(krakenService, tradeRepository, ledgerRepository, configService)

    init {
        every { configService.getConfig() } returns config

        "empty database binds only the hashed active account scope" {
            runTest {
                krakenService.fundingEvidenceScopeSupplier = { "account-A-secret-scope" }

                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.VALID
                result.isValid shouldBe true
                val stored = tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST)
                stored shouldHaveLength 64
                (stored == "account-A-secret-scope") shouldBe false
            }
        }

        "empty database refuses to bind when credentials cannot be verified" {
            runTest {
                krakenService.fundingEvidenceScopeSupplier = { "account-A-secret-scope" }
                krakenService.balanceSupplier = { error("invalid key") }

                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.SCOPE_UNAVAILABLE
                result.isValid shouldBe false
                tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) shouldBe null
            }
        }

        "credential rotation rebinds after continuity proof" {
            runTest {
                krakenService.fundingEvidenceScopeSupplier = { "account-A" }
                guard.validateAccountScope().status shouldBe AccountScopeValidationStatus.VALID
                tradeRepository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = Instant.parse("2026-01-01T00:00:00Z"),
                        pair = Asset.BTC_USD_PAIR,
                        side = "buy",
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.01"),
                        usdAmount = BigDecimal("100.00"),
                        price = BigDecimal("10000.00"),
                        source = TradeSource.API_FILL,
                        tradeId = "rotated-account-fill",
                        orderTxid = "rotated-account-order",
                    ),
                )

                krakenService.fundingEvidenceScopeSupplier = { "account-A-rotated-keys" }
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(
                        TestFixtures.tradeRecord(
                            timestamp = Instant.parse("2026-01-01T00:00:00Z"),
                            pair = Asset.BTC_USD_PAIR,
                            side = "buy",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.01"),
                            usdAmount = BigDecimal("100.00"),
                            price = BigDecimal("10000.00"),
                            source = TradeSource.API_FILL,
                            tradeId = "rotated-account-fill",
                            orderTxid = "rotated-account-order",
                        ),
                    )
                }
                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.VALID
                result.isValid shouldBe true
            }
        }

        "scope mismatch with disjoint exchange history stays locked" {
            runTest {
                krakenService.fundingEvidenceScopeSupplier = { "account-A" }
                guard.validateAccountScope().status shouldBe AccountScopeValidationStatus.VALID
                val original = tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST)
                tradeRepository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = Instant.parse("2026-01-01T00:00:00Z"),
                        pair = Asset.BTC_USD_PAIR,
                        side = "buy",
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.01"),
                        usdAmount = BigDecimal("100.00"),
                        price = BigDecimal("10000.00"),
                        source = TradeSource.API_FILL,
                        tradeId = "account-a-fill",
                    ),
                )

                krakenService.fundingEvidenceScopeSupplier = { "account-B" }
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(
                        TestFixtures.tradeRecord(
                            timestamp = Instant.parse("2026-02-01T00:00:00Z"),
                            pair = Asset.BTC_USD_PAIR,
                            side = "buy",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.02"),
                            usdAmount = BigDecimal("200.00"),
                            price = BigDecimal("10000.00"),
                            source = TradeSource.API_FILL,
                            tradeId = "account-b-fill",
                        ),
                    )
                }
                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.SCOPE_MISMATCH
                result.isValid shouldBe false
                tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) shouldBe original
            }
        }

        "scope mismatch with an unreachable exchange fails closed without rebinding" {
            runTest {
                krakenService.fundingEvidenceScopeSupplier = { "account-A" }
                guard.validateAccountScope().status shouldBe AccountScopeValidationStatus.VALID
                val original = tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST)
                tradeRepository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = Instant.parse("2026-01-01T00:00:00Z"),
                        pair = Asset.BTC_USD_PAIR,
                        side = "buy",
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.01"),
                        usdAmount = BigDecimal("100.00"),
                        price = BigDecimal("10000.00"),
                        source = TradeSource.API_FILL,
                        tradeId = "account-a-fill",
                    ),
                )

                krakenService.fundingEvidenceScopeSupplier = { "account-B" }
                krakenService.tradeHistorySupplier = { _, _ -> error("network") }
                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.SCOPE_UNAVAILABLE
                result.isValid shouldBe false
                tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) shouldBe original
            }
        }

        "legacy unbound database binds after continuity proof" {
            runTest {
                tradeRepository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = Instant.parse("2026-01-01T00:00:00Z"),
                        pair = Asset.BTC_USD_PAIR,
                        side = "buy",
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.01"),
                        usdAmount = BigDecimal("100.00"),
                        price = BigDecimal("10000.00"),
                        source = TradeSource.API_FILL,
                        tradeId = "legacy-account-fill",
                    ),
                )
                krakenService.fundingEvidenceScopeSupplier = { "account-A" }
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(
                        TestFixtures.tradeRecord(
                            timestamp = Instant.parse("2026-01-01T00:00:00Z"),
                            pair = Asset.BTC_USD_PAIR,
                            side = "buy",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.01"),
                            usdAmount = BigDecimal("100.00"),
                            price = BigDecimal("10000.00"),
                            source = TradeSource.API_FILL,
                            tradeId = "legacy-account-fill",
                        ),
                    )
                }

                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.VALID
                result.isValid shouldBe true
                tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) shouldHaveLength 64
            }
        }

        "ledger-only legacy history binds on ledger continuity proof" {
            runTest {
                ledgerRepository.saveLedgers(
                    listOf(
                        LedgerEvent(
                            ledgerId = "legacy-ledger",
                            time = Instant.parse("2026-01-01T00:00:00Z"),
                            type = "staking",
                            asset = Asset.BTC,
                            amount = BigDecimal.ZERO,
                        ),
                    ),
                )
                krakenService.fundingEvidenceScopeSupplier = { "account-A" }
                krakenService.ledgerSupplier = { _, _, _, _ ->
                    listOf(
                        LedgerEvent(
                            ledgerId = "legacy-ledger",
                            time = Instant.parse("2026-01-01T00:00:00Z"),
                            type = "staking",
                            asset = Asset.BTC,
                            amount = BigDecimal.ZERO,
                        ),
                    )
                }

                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.VALID
                result.isValid shouldBe true
            }
        }

        "unverifiable exchange history fails closed without binding" {
            runTest {
                tradeRepository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = Instant.parse("2026-01-01T00:00:00Z"),
                        pair = Asset.BTC_USD_PAIR,
                        side = "buy",
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.01"),
                        usdAmount = BigDecimal("100.00"),
                        price = BigDecimal("10000.00"),
                        source = TradeSource.API_FILL,
                        tradeId = "legacy-account-fill",
                    ),
                )
                krakenService.fundingEvidenceScopeSupplier = { "account-A" }
                krakenService.tradeHistorySupplier = { _, _ -> error("network") }

                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.SCOPE_UNAVAILABLE
                result.isValid shouldBe false
                tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) shouldBe null
            }
        }

        "non-empty upgraded database remains unbound without continuity proof" {
            runTest {
                tradeRepository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = Instant.parse("2026-01-01T00:00:00Z"),
                        pair = Asset.BTC_USD_PAIR,
                        side = "buy",
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.01"),
                        usdAmount = BigDecimal("100.00"),
                        price = BigDecimal("10000.00"),
                        source = TradeSource.API_FILL,
                        tradeId = "existing-account-a-fill",
                    ),
                )
                krakenService.fundingEvidenceScopeSupplier = { "account-B" }

                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.UNBOUND_EXISTING_HISTORY
                result.isValid shouldBe false
                tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) shouldBe null
            }
        }

        "failed and dry-run trade rows also prevent first binding" {
            runTest {
                tradeRepository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = Instant.parse("2026-01-01T00:00:00Z"),
                        pair = Asset.BTC_USD_PAIR,
                        side = "buy",
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.01"),
                        usdAmount = BigDecimal("100.00"),
                        success = false,
                    ),
                )
                tradeRepository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = Instant.parse("2026-01-01T00:01:00Z"),
                        pair = Asset.BTC_USD_PAIR,
                        side = "buy",
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.01"),
                        usdAmount = BigDecimal("100.00"),
                        dryRun = true,
                    ),
                )
                krakenService.fundingEvidenceScopeSupplier = { "account-B" }

                guard.validateAccountScope().status shouldBe AccountScopeValidationStatus.UNBOUND_EXISTING_HISTORY
                tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) shouldBe null
            }
        }

        "bound database rejects a different active account without changing the binding" {
            runTest {
                krakenService.fundingEvidenceScopeSupplier = { "account-A" }
                guard.validateAccountScope().status shouldBe AccountScopeValidationStatus.VALID
                val original = tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST)

                krakenService.fundingEvidenceScopeSupplier = { "account-B" }
                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.SCOPE_MISMATCH
                result.isValid shouldBe false
                tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) shouldBe original

                krakenService.fundingEvidenceScopeSupplier = { "account-A" }
                guard.validateAccountScope().status shouldBe AccountScopeValidationStatus.VALID

                tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST, "")
                tradeRepository.setSyncMetadata(SyncMetadataKeys.SYNC_OFFSET, "1")
                krakenService.fundingEvidenceScopeSupplier = { "account-C" }
                guard.validateAccountScope().status shouldBe AccountScopeValidationStatus.UNBOUND_EXISTING_HISTORY
            }
        }

        "scope lookup and credentials fail closed before any binding" {
            runTest {
                every { configService.getConfig() } returns config.copy(
                    kraken = KrakenCredentials("", ""),
                )
                guard.validateAccountScope().status shouldBe AccountScopeValidationStatus.SCOPE_UNAVAILABLE
                guard.validateAccountScope().isValid shouldBe false

                every { configService.getConfig() } returns config
                krakenService.fundingEvidenceScopeSupplier = { "" }
                guard.validateAccountScope().status shouldBe AccountScopeValidationStatus.SCOPE_UNAVAILABLE
                krakenService.fundingEvidenceScopeSupplier = { "scope-unavailable" }
                guard.validateAccountScope().status shouldBe AccountScopeValidationStatus.SCOPE_UNAVAILABLE
                krakenService.fundingEvidenceScopeSupplier = { error("network") }
                guard.validateAccountScope().status shouldBe AccountScopeValidationStatus.SCOPE_UNAVAILABLE

                every { configService.getConfig() } returns config.copy(
                    settings = config.settings.copy(simulation = true),
                )
                guard.validateAccountScope().status shouldBe AccountScopeValidationStatus.SIMULATION
                guard.validateAccountScope().isValid shouldBe true
            }
        }

        "ledger-only and metadata-only history are treated as non-empty" {
            runTest {
                ledgerRepository.saveLedgers(
                    listOf(
                        LedgerEvent(
                            ledgerId = "legacy-ledger",
                            time = Instant.parse("2026-01-01T00:00:00Z"),
                            type = "staking",
                            asset = Asset.BTC,
                            amount = BigDecimal.ZERO,
                        ),
                    ),
                )
                krakenService.fundingEvidenceScopeSupplier = { "account-B" }
                guard.validateAccountScope().status shouldBe AccountScopeValidationStatus.UNBOUND_EXISTING_HISTORY

                val freshDatabase = DatabaseConfig.init(TestFixtures.MEMORY_)
                val freshGuard = AccountHistoryScopeGuard(
                    krakenService,
                    SqliteTradeRepositoryImpl(freshDatabase),
                    SqliteLedgerRepositoryImpl(freshDatabase),
                    configService,
                )
                val freshTradeRepository = SqliteTradeRepositoryImpl(freshDatabase)
                freshTradeRepository.setSyncMetadata(SyncMetadataKeys.SYNC_OFFSET, "1")
                freshGuard.validateAccountScope().status shouldBe AccountScopeValidationStatus.UNBOUND_EXISTING_HISTORY
                freshGuard.isFinancialHistoryPresent() shouldBe true
                val metadataDatabase = DatabaseConfig.init(TestFixtures.MEMORY_)
                val metadataTradeRepository = SqliteTradeRepositoryImpl(metadataDatabase)
                val metadataLedgerRepository = SqliteLedgerRepositoryImpl(metadataDatabase)
                metadataLedgerRepository.setSyncMetadata(SyncMetadataKeys.SYNC_OFFSET, "1")
                val metadataGuard = AccountHistoryScopeGuard(
                    krakenService,
                    metadataTradeRepository,
                    metadataLedgerRepository,
                    configService,
                )
                metadataGuard.isFinancialHistoryPresent() shouldBe true

                val snapshotDatabase = DatabaseConfig.init(TestFixtures.MEMORY_)
                val snapshotTradeRepository = SqliteTradeRepositoryImpl(snapshotDatabase)
                val snapshotGuard = AccountHistoryScopeGuard(
                    krakenService,
                    snapshotTradeRepository,
                    SqliteLedgerRepositoryImpl(snapshotDatabase),
                    configService,
                )
                snapshotTradeRepository.saveSnapshot(
                    PortfolioSnapshot(
                        timestamp = Instant.parse("2026-01-01T00:00:00Z"),
                        totalValueUSD = BigDecimal.ONE,
                        assets = emptyMap(),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    ),
                )
                snapshotGuard.validateAccountScope().status shouldBe
                    AccountScopeValidationStatus.UNBOUND_EXISTING_HISTORY
            }
        }
    }
}
