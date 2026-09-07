@file:OptIn(ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.repository.impl.SqliteLedgerRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.FakeKrakenService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant

class AccountHistoryScopeGuardTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val database = DatabaseConfig.init(TestFixtures.MEMORY_)
    private val tradeRepository = SqliteTradeRepositoryImpl(database)
    private val ledgerRepository = SqliteLedgerRepositoryImpl(database)
    private val krakenService = FakeKrakenService()
    private val configService = mockk<ConfigService>(relaxed = true)
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

        "legacy unbound database binds after consistent continuity proof" {
            runTest {
                listOf(
                    "2026-01-01T00:00:00Z" to "legacy-account-fill-old",
                    "2026-03-01T00:00:00Z" to "legacy-account-fill-new",
                ).forEach { (timestamp, tradeId) ->
                    tradeRepository.saveTrade(
                        TestFixtures.tradeRecord(
                            timestamp = Instant.parse(timestamp),
                            pair = Asset.BTC_USD_PAIR,
                            side = "buy",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.01"),
                            usdAmount = BigDecimal("100.00"),
                            price = BigDecimal("10000.00"),
                            source = TradeSource.API_FILL,
                            tradeId = tradeId,
                        ),
                    )
                }
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
                            tradeId = "legacy-account-fill-old",
                        ),
                        TestFixtures.tradeRecord(
                            timestamp = Instant.parse("2026-03-01T00:00:00Z"),
                            pair = Asset.BTC_USD_PAIR,
                            side = "buy",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.01"),
                            usdAmount = BigDecimal("100.00"),
                            price = BigDecimal("10000.00"),
                            source = TradeSource.API_FILL,
                            tradeId = "legacy-account-fill-new",
                        ),
                    )
                }

                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.VALID
                result.isValid shouldBe true
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
                ) shouldBe AccountHistoryScopeGuard.CURRENT_BINDING_VERSION
                // The new binding is durable: the local History-path read trusts
                // the bound credentials without another round of proof.
                guard.readLocalTrustState().status shouldBe AccountScopeValidationStatus.VALID
            }
        }

        "legacy unbound database with a single overlapping row stays unbound" {
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

                // One overlapping row proves contribution, not single-account
                // ownership: no binding may be written.
                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.UNBOUND_EXISTING_HISTORY
                result.isValid shouldBe false
                tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) shouldBe null
            }
        }

        "legacy mixed-account history fails closed without binding" {
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
                        tradeId = "account-A-old-fill",
                    ),
                )
                tradeRepository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = Instant.parse("2026-03-01T00:00:00Z"),
                        pair = Asset.BTC_USD_PAIR,
                        side = "buy",
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.01"),
                        usdAmount = BigDecimal("100.00"),
                        price = BigDecimal("10000.00"),
                        source = TradeSource.API_FILL,
                        tradeId = "account-B-new-fill",
                    ),
                )
                krakenService.fundingEvidenceScopeSupplier = { "account-B" }
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(
                        TestFixtures.tradeRecord(
                            timestamp = Instant.parse("2026-03-01T00:00:00Z"),
                            pair = Asset.BTC_USD_PAIR,
                            side = "buy",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.01"),
                            usdAmount = BigDecimal("100.00"),
                            price = BigDecimal("10000.00"),
                            source = TradeSource.API_FILL,
                            tradeId = "account-B-new-fill",
                        ),
                    )
                }

                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.UNBOUND_EXISTING_HISTORY
                result.isValid shouldBe false
                result.reason shouldBe "account history is inconsistent with the active account"
                tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) shouldBe null
            }
        }

        "ledger-only legacy history binds on consistent ledger proof" {
            runTest {
                ledgerRepository.saveLedgers(
                    listOf(
                        LedgerEvent(
                            ledgerId = "legacy-ledger-old",
                            time = Instant.parse("2026-01-01T00:00:00Z"),
                            type = "staking",
                            asset = Asset.BTC,
                            amount = BigDecimal.ZERO,
                        ),
                        LedgerEvent(
                            ledgerId = "legacy-ledger-new",
                            time = Instant.parse("2026-03-01T00:00:00Z"),
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
                            ledgerId = "legacy-ledger-old",
                            time = Instant.parse("2026-01-01T00:00:00Z"),
                            type = "staking",
                            asset = Asset.BTC,
                            amount = BigDecimal.ZERO,
                        ),
                        LedgerEvent(
                            ledgerId = "legacy-ledger-new",
                            time = Instant.parse("2026-03-01T00:00:00Z"),
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
                val base = Instant.parse("2026-01-01T00:00:00Z")
                // Two markers so the legacy proof actually reaches the exchange
                // instead of short-circuiting on insufficient evidence.
                listOf(
                    "legacy-fill-old" to base,
                    "legacy-fill-new" to base.plusSeconds(100_000L),
                ).forEach { (id, time) ->
                    tradeRepository.saveTrade(
                        TestFixtures.tradeRecord(
                            timestamp = time,
                            pair = Asset.BTC_USD_PAIR,
                            side = "buy",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.01"),
                            usdAmount = BigDecimal("100.00"),
                            price = BigDecimal("10000.00"),
                            source = TradeSource.API_FILL,
                            tradeId = id,
                        ),
                    )
                }
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
                // Retained history is what protects the binding: an empty bound
                // database may adopt authenticated replacement credentials, but
                // history forces the continuity rules below.
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

        "incomplete continuity search fails closed and retains the binding" {
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

                // Every window page stays full without ever showing the marker:
                // the search is unproven, not absent.
                krakenService.fundingEvidenceScopeSupplier = { "account-B" }
                krakenService.tradeHistorySupplier = { _, _ ->
                    (0 until 50).map {
                        TestFixtures.tradeRecord(
                            timestamp = Instant.parse("2026-01-01T00:00:00Z"),
                            pair = Asset.BTC_USD_PAIR,
                            side = "buy",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.01"),
                            usdAmount = BigDecimal("100.00"),
                            price = BigDecimal("10000.00"),
                            source = TradeSource.API_FILL,
                            tradeId = "unrelated-fill-$it",
                        )
                    }
                }
                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.SCOPE_UNAVAILABLE
                result.isValid shouldBe false
                tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) shouldBe original
            }
        }

        "local trust read answers from the durable binding without network calls" {
            runTest {
                krakenService.fundingEvidenceScopeSupplier = { "account-A" }
                guard.validateAccountScope().status shouldBe AccountScopeValidationStatus.VALID

                val callsBefore = krakenService.getTradeHistoryCallCount + krakenService.getLedgersCallCount +
                    krakenService.getBalancesCallCount
                val trusted = guard.readLocalTrustState()

                trusted.status shouldBe AccountScopeValidationStatus.VALID
                trusted.isValid shouldBe true
                krakenService.getTradeHistoryCallCount + krakenService.getLedgersCallCount +
                    krakenService.getBalancesCallCount shouldBe callsBefore
            }
        }

        "local trust read rejects a changed fingerprint without starting continuity proof" {
            runTest {
                krakenService.fundingEvidenceScopeSupplier = { "account-A" }
                guard.validateAccountScope().status shouldBe AccountScopeValidationStatus.VALID

                krakenService.fundingEvidenceScopeSupplier = { "account-B" }
                val callsBefore = krakenService.getTradeHistoryCallCount + krakenService.getLedgersCallCount
                val result = guard.readLocalTrustState()

                result.status shouldBe AccountScopeValidationStatus.SCOPE_MISMATCH
                result.isValid shouldBe false
                krakenService.getTradeHistoryCallCount + krakenService.getLedgersCallCount shouldBe callsBefore
            }
        }

        "local trust read reports pending on an empty database without verifying credentials" {
            runTest {
                krakenService.fundingEvidenceScopeSupplier = { "account-A" }
                krakenService.balanceSupplier = { error("must not be called") }
                tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST, " ")

                val result = guard.readLocalTrustState()

                result.status shouldBe AccountScopeValidationStatus.VALIDATION_PENDING
                result.isValid shouldBe false
                tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) shouldBe " "
            }
        }

        "local trust read reports unbound history without network calls" {
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
                        tradeId = "legacy-fill",
                    ),
                )
                krakenService.fundingEvidenceScopeSupplier = { "account-A" }
                val callsBefore = krakenService.getTradeHistoryCallCount + krakenService.getLedgersCallCount
                val result = guard.readLocalTrustState()

                result.status shouldBe AccountScopeValidationStatus.UNBOUND_EXISTING_HISTORY
                result.isValid shouldBe false
                krakenService.getTradeHistoryCallCount + krakenService.getLedgersCallCount shouldBe callsBefore
            }
        }

        "local trust read reports unbound metadata-only history" {
            runTest {
                tradeRepository.setSyncMetadata(SyncMetadataKeys.SYNC_OFFSET, "1")
                krakenService.fundingEvidenceScopeSupplier = { "account-A" }

                guard.readLocalTrustState().status shouldBe AccountScopeValidationStatus.UNBOUND_EXISTING_HISTORY
            }
        }

        "local trust read answers simulation and credential failures locally" {
            runTest {
                every { configService.getConfig() } returns config.copy(
                    settings = config.settings.copy(simulation = true),
                )
                guard.readLocalTrustState().status shouldBe AccountScopeValidationStatus.SIMULATION

                every { configService.getConfig() } returns config.copy(
                    kraken = KrakenCredentials("", ""),
                )
                guard.readLocalTrustState().status shouldBe AccountScopeValidationStatus.SCOPE_UNAVAILABLE

                every { configService.getConfig() } returns config
                krakenService.fundingEvidenceScopeSupplier = { "scope-unavailable" }
                guard.readLocalTrustState().status shouldBe AccountScopeValidationStatus.SCOPE_UNAVAILABLE
            }
        }

        "local trust read propagates cancellation" {
            runTest {
                krakenService.fundingEvidenceScopeSupplier = { throw CancellationException("cancelled") }

                shouldThrow<CancellationException> { guard.readLocalTrustState() }
            }
        }

        "unbound incomplete search fails closed without binding" {
            runTest {
                val base = Instant.parse("2026-01-01T00:00:00Z")
                // Two markers so the legacy proof actually reaches the exchange
                // instead of short-circuiting on insufficient evidence.
                listOf(
                    "legacy-fill-old" to base,
                    "legacy-fill-new" to base.plusSeconds(100_000L),
                ).forEach { (id, time) ->
                    tradeRepository.saveTrade(
                        TestFixtures.tradeRecord(
                            timestamp = time,
                            pair = Asset.BTC_USD_PAIR,
                            side = "buy",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.01"),
                            usdAmount = BigDecimal("100.00"),
                            price = BigDecimal("10000.00"),
                            source = TradeSource.API_FILL,
                            tradeId = id,
                        ),
                    )
                }
                krakenService.fundingEvidenceScopeSupplier = { "account-A" }
                krakenService.tradeHistorySupplier = { _, _ ->
                    (0 until 50).map {
                        TestFixtures.tradeRecord(
                            timestamp = base,
                            pair = Asset.BTC_USD_PAIR,
                            side = "buy",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.01"),
                            usdAmount = BigDecimal("100.00"),
                            price = BigDecimal("10000.00"),
                            source = TradeSource.API_FILL,
                            tradeId = "unrelated-fill-$it",
                        )
                    }
                }

                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.SCOPE_UNAVAILABLE
                result.isValid shouldBe false
                tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) shouldBe null
            }
        }

        "unverified binding write is never claimed valid" {
            runTest {
                val tradeRepository = mockk<TradeRepository>(relaxed = true)
                coEvery { tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) } returns
                    "stale-digest"
                coEvery { tradeRepository.hasAnyTradeRows() } returns false
                coEvery { tradeRepository.getLatestTradeTime() } returns null
                coEvery { tradeRepository.getLatestSnapshot() } returns null
                coEvery { tradeRepository.getTradesInRange(any(), any()) } returns listOf(
                    TestFixtures.tradeRecord(
                        timestamp = Instant.parse("2026-01-01T00:00:00Z"),
                        pair = Asset.BTC_USD_PAIR,
                        side = "buy",
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.01"),
                        usdAmount = BigDecimal("100.00"),
                        price = BigDecimal("10000.00"),
                        success = true,
                        dryRun = false,
                        source = TradeSource.API_FILL,
                        tradeId = "account-fill",
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
                            tradeId = "account-fill",
                        ),
                    )
                }
                val writeBlindGuard = AccountHistoryScopeGuard(
                    krakenService,
                    tradeRepository,
                    ledgerRepository,
                    configService,
                )

                // Proof succeeds, but the binding write never lands: the previous
                // binding must be retained and VALID must not be claimed.
                val result = writeBlindGuard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.SCOPE_UNAVAILABLE
                result.isValid shouldBe false
            }
        }

        "credential probe cancellation propagates instead of binding" {
            runTest {
                krakenService.fundingEvidenceScopeSupplier = { "account-A" }
                krakenService.balanceSupplier = { throw CancellationException("cancelled") }

                shouldThrow<CancellationException> { guard.validateAccountScope() }
            }
        }

        "old-version binding with consistent history revalidates and upgrades" {
            runTest {
                val base = Instant.parse("2026-01-01T00:00:00Z")
                listOf("old-fill-A" to base, "old-fill-B" to base.plusSeconds(100_000L)).forEach { (id, time) ->
                    tradeRepository.saveTrade(
                        TestFixtures.tradeRecord(
                            timestamp = time,
                            pair = Asset.BTC_USD_PAIR,
                            side = "buy",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.01"),
                            usdAmount = BigDecimal("100.00"),
                            price = BigDecimal("10000.00"),
                            source = TradeSource.API_FILL,
                            tradeId = id,
                        ),
                    )
                }
                // Binding persisted by a pre-strengthening build: digest but no version.
                krakenService.fundingEvidenceScopeSupplier = { "account-A" }
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST,
                    AccountHistoryScopeGuard.digestAccountScope("account-A"),
                )
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(
                        TestFixtures.tradeRecord(
                            timestamp = base,
                            pair = Asset.BTC_USD_PAIR,
                            side = "buy",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.01"),
                            usdAmount = BigDecimal("100.00"),
                            price = BigDecimal("10000.00"),
                            source = TradeSource.API_FILL,
                            tradeId = "old-fill-A",
                        ),
                        TestFixtures.tradeRecord(
                            timestamp = base.plusSeconds(100_000L),
                            pair = Asset.BTC_USD_PAIR,
                            side = "buy",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.01"),
                            usdAmount = BigDecimal("100.00"),
                            price = BigDecimal("10000.00"),
                            source = TradeSource.API_FILL,
                            tradeId = "old-fill-B",
                        ),
                    )
                }

                // Not fast-pathed: full legacy consistency proof runs once...
                guard.readLocalTrustState().status shouldBe AccountScopeValidationStatus.VALIDATION_PENDING

                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.VALID
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
                ) shouldBe AccountHistoryScopeGuard.CURRENT_BINDING_VERSION
                // ...and afterwards the fast path is restored without further proof.
                krakenService.tradeHistorySupplier = { _, _ -> error("must not be called") }
                guard.validateAccountScope().status shouldBe AccountScopeValidationStatus.VALID
            }
        }

        "old-version binding with mixed history stays untrusted" {
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
                        tradeId = "account-A-old-fill",
                    ),
                )
                tradeRepository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = Instant.parse("2026-03-01T00:00:00Z"),
                        pair = Asset.BTC_USD_PAIR,
                        side = "buy",
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.01"),
                        usdAmount = BigDecimal("100.00"),
                        price = BigDecimal("10000.00"),
                        source = TradeSource.API_FILL,
                        tradeId = "account-B-new-fill",
                    ),
                )
                krakenService.fundingEvidenceScopeSupplier = { "account-B" }
                val staleDigest = AccountHistoryScopeGuard.digestAccountScope("account-B")
                tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST, staleDigest)
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(
                        TestFixtures.tradeRecord(
                            timestamp = Instant.parse("2026-03-01T00:00:00Z"),
                            pair = Asset.BTC_USD_PAIR,
                            side = "buy",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.01"),
                            usdAmount = BigDecimal("100.00"),
                            price = BigDecimal("10000.00"),
                            source = TradeSource.API_FILL,
                            tradeId = "account-B-new-fill",
                        ),
                    )
                }

                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.UNBOUND_EXISTING_HISTORY
                result.isValid shouldBe false
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST,
                ) shouldBe staleDigest
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
                ) shouldBe null
            }
        }

        "old-version binding with changed fingerprint and consistent history revalidates to current" {
            runTest {
                val base = Instant.parse("2026-01-01T00:00:00Z")
                // Stored lineage predates the strengthened contract and names
                // generation A, while the active credentials are generation B.
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST,
                    AccountHistoryScopeGuard.digestAccountScope("account-A"),
                )
                listOf("gen-B-old" to base, "gen-B-new" to base.plusSeconds(100_000L)).forEach { (id, time) ->
                    tradeRepository.saveTrade(
                        TestFixtures.tradeRecord(
                            timestamp = time,
                            pair = Asset.BTC_USD_PAIR,
                            side = "buy",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.01"),
                            usdAmount = BigDecimal("100.00"),
                            price = BigDecimal("10000.00"),
                            source = TradeSource.API_FILL,
                            tradeId = id,
                        ),
                    )
                }
                krakenService.fundingEvidenceScopeSupplier = { "account-B" }
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf("gen-B-old" to base, "gen-B-new" to base.plusSeconds(100_000L)).map { (id, time) ->
                        TestFixtures.tradeRecord(
                            timestamp = time,
                            pair = Asset.BTC_USD_PAIR,
                            side = "buy",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.01"),
                            usdAmount = BigDecimal("100.00"),
                            price = BigDecimal("10000.00"),
                            source = TradeSource.API_FILL,
                            tradeId = id,
                        )
                    }
                }

                // The strong legacy proof must run despite the fingerprint
                // change — never the lightweight rotation path.
                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.VALID
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST,
                ) shouldBe AccountHistoryScopeGuard.digestAccountScope("account-B")
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
                ) shouldBe AccountHistoryScopeGuard.CURRENT_BINDING_VERSION
            }
        }

        "old-version binding with changed fingerprint and mixed history is never promoted" {
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
                        tradeId = "account-A-old-fill",
                    ),
                )
                tradeRepository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = Instant.parse("2026-03-01T00:00:00Z"),
                        pair = Asset.BTC_USD_PAIR,
                        side = "buy",
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.01"),
                        usdAmount = BigDecimal("100.00"),
                        price = BigDecimal("10000.00"),
                        source = TradeSource.API_FILL,
                        tradeId = "account-B-new-fill",
                    ),
                )
                val staleDigest = AccountHistoryScopeGuard.digestAccountScope("account-A")
                tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST, staleDigest)
                // Generation B credentials see the B row: the old rotation path
                // would bind on this single hit, laundering weak lineage.
                krakenService.fundingEvidenceScopeSupplier = { "account-B" }
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(
                        TestFixtures.tradeRecord(
                            timestamp = Instant.parse("2026-03-01T00:00:00Z"),
                            pair = Asset.BTC_USD_PAIR,
                            side = "buy",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.01"),
                            usdAmount = BigDecimal("100.00"),
                            price = BigDecimal("10000.00"),
                            source = TradeSource.API_FILL,
                            tradeId = "account-B-new-fill",
                        ),
                    )
                }

                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.UNBOUND_EXISTING_HISTORY
                result.isValid shouldBe false
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST,
                ) shouldBe staleDigest
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
                ) shouldBe null
            }
        }

        "old-version binding with changed fingerprint and one marker stays untrusted" {
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
                        tradeId = "shared-fill",
                    ),
                )
                val staleDigest = AccountHistoryScopeGuard.digestAccountScope("account-A")
                tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST, staleDigest)
                krakenService.fundingEvidenceScopeSupplier = { "account-B" }
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
                            tradeId = "shared-fill",
                        ),
                    )
                }

                // One overlapping row must not promote weak lineage via the
                // rotation shortcut: insufficient evidence stays unbound.
                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.UNBOUND_EXISTING_HISTORY
                result.isValid shouldBe false
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST,
                ) shouldBe staleDigest
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
                ) shouldBe null
            }
        }

        "old-version binding with changed fingerprint and incomplete search stays untrusted" {
            runTest {
                val base = Instant.parse("2026-01-01T00:00:00Z")
                listOf("old-a" to base, "old-b" to base.plusSeconds(100_000L)).forEach { (id, time) ->
                    tradeRepository.saveTrade(
                        TestFixtures.tradeRecord(
                            timestamp = time,
                            pair = Asset.BTC_USD_PAIR,
                            side = "buy",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.01"),
                            usdAmount = BigDecimal("100.00"),
                            price = BigDecimal("10000.00"),
                            source = TradeSource.API_FILL,
                            tradeId = id,
                        ),
                    )
                }
                val staleDigest = AccountHistoryScopeGuard.digestAccountScope("account-A")
                tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST, staleDigest)
                krakenService.fundingEvidenceScopeSupplier = { "account-B" }
                krakenService.tradeHistorySupplier = { _, _ ->
                    (0 until 50).map {
                        TestFixtures.tradeRecord(
                            timestamp = base,
                            pair = Asset.BTC_USD_PAIR,
                            side = "buy",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.01"),
                            usdAmount = BigDecimal("100.00"),
                            price = BigDecimal("10000.00"),
                            source = TradeSource.API_FILL,
                            tradeId = "unrelated-fill-$it",
                        )
                    }
                }

                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.SCOPE_UNAVAILABLE
                result.isValid shouldBe false
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST,
                ) shouldBe staleDigest
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
                ) shouldBe null
            }
        }

        "old-version binding with changed fingerprint and outage stays untrusted" {
            runTest {
                val base = Instant.parse("2026-01-01T00:00:00Z")
                listOf("old-a" to base, "old-b" to base.plusSeconds(100_000L)).forEach { (id, time) ->
                    tradeRepository.saveTrade(
                        TestFixtures.tradeRecord(
                            timestamp = time,
                            pair = Asset.BTC_USD_PAIR,
                            side = "buy",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.01"),
                            usdAmount = BigDecimal("100.00"),
                            price = BigDecimal("10000.00"),
                            source = TradeSource.API_FILL,
                            tradeId = id,
                        ),
                    )
                }
                val staleDigest = AccountHistoryScopeGuard.digestAccountScope("account-A")
                tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST, staleDigest)
                krakenService.fundingEvidenceScopeSupplier = { "account-B" }
                krakenService.tradeHistorySupplier = { _, _ -> error("network") }

                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.SCOPE_UNAVAILABLE
                result.isValid shouldBe false
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST,
                ) shouldBe staleDigest
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
                ) shouldBe null
            }
        }

        "old-version empty binding upgrades after authentication" {
            runTest {
                krakenService.fundingEvidenceScopeSupplier = { "account-A" }
                tradeRepository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST,
                    AccountHistoryScopeGuard.digestAccountScope("account-A"),
                )

                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.VALID
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
                ) shouldBe AccountHistoryScopeGuard.CURRENT_BINDING_VERSION
            }
        }

        "bound empty database adopts authenticated replacement credentials" {
            runTest {
                krakenService.fundingEvidenceScopeSupplier = { "account-A" }
                guard.validateAccountScope().status shouldBe AccountScopeValidationStatus.VALID

                krakenService.fundingEvidenceScopeSupplier = { "account-B" }
                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.VALID
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST,
                ) shouldBe AccountHistoryScopeGuard.digestAccountScope("account-B")
            }
        }

        "bound empty database retains its binding when replacement credentials fail" {
            runTest {
                krakenService.fundingEvidenceScopeSupplier = { "account-A" }
                guard.validateAccountScope().status shouldBe AccountScopeValidationStatus.VALID
                val original = tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST)

                krakenService.fundingEvidenceScopeSupplier = { "account-B" }
                krakenService.balanceSupplier = { error("invalid key") }
                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.SCOPE_UNAVAILABLE
                tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) shouldBe original
            }
        }

        "non-empty database never uses the empty-rebind shortcut" {
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
                        tradeId = "account-a-fill",
                    ),
                )

                // The credential probe is dead: only exact continuity proof can
                // rebind now that history exists.
                krakenService.fundingEvidenceScopeSupplier = { "account-B" }
                krakenService.balanceSupplier = { error("invalid key") }
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
                            tradeId = "account-a-fill",
                        ),
                    )
                }

                guard.validateAccountScope().status shouldBe AccountScopeValidationStatus.VALID
            }
        }

        "credential change during proof aborts without writing" {
            runTest {
                val base = Instant.parse("2026-01-01T00:00:00Z")
                // Two markers so the legacy proof actually runs instead of
                // short-circuiting on insufficient evidence.
                listOf("flip-fill-old" to base, "flip-fill-new" to base.plusSeconds(100_000L)).forEach { (id, time) ->
                    tradeRepository.saveTrade(
                        TestFixtures.tradeRecord(
                            timestamp = time,
                            pair = Asset.BTC_USD_PAIR,
                            side = "buy",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.01"),
                            usdAmount = BigDecimal("100.00"),
                            price = BigDecimal("10000.00"),
                            source = TradeSource.API_FILL,
                            tradeId = id,
                        ),
                    )
                }
                var scopeCalls = 0
                krakenService.fundingEvidenceScopeSupplier = {
                    scopeCalls++
                    if (scopeCalls == 1) "account-A" else "account-B"
                }
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(
                        TestFixtures.tradeRecord(
                            timestamp = base,
                            pair = Asset.BTC_USD_PAIR,
                            side = "buy",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.01"),
                            usdAmount = BigDecimal("100.00"),
                            price = BigDecimal("10000.00"),
                            source = TradeSource.API_FILL,
                            tradeId = "flip-fill-old",
                        ),
                        TestFixtures.tradeRecord(
                            timestamp = base.plusSeconds(100_000L),
                            pair = Asset.BTC_USD_PAIR,
                            side = "buy",
                            symbol = Asset.BTC,
                            volume = BigDecimal("0.01"),
                            usdAmount = BigDecimal("100.00"),
                            price = BigDecimal("10000.00"),
                            source = TradeSource.API_FILL,
                            tradeId = "flip-fill-new",
                        ),
                    )
                }

                // Proof succeeds against generation B, but the proven digest was
                // computed for generation A: the write must not happen.
                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.SCOPE_UNAVAILABLE
                result.isValid shouldBe false
                tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) shouldBe null
            }
        }

        "local trust read never waits behind network validation" {
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
                        tradeId = "account-a-fill",
                    ),
                )

                krakenService.fundingEvidenceScopeSupplier = { "account-B" }
                val enteredProof = CompletableDeferred<Unit>()
                val releaseProof = CompletableDeferred<AccountHistoryContinuityStatus>()
                val hangingVerifier = mockk<AccountHistoryContinuityVerifier>()
                coEvery { hangingVerifier.verifyContinuity() } coAnswers {
                    enteredProof.complete(Unit)
                    releaseProof.await()
                }
                coEvery { hangingVerifier.verifyLegacyConsistency() } returns
                    AccountHistoryContinuityStatus.NO_OVERLAP
                val hangingGuard = AccountHistoryScopeGuard(
                    krakenService,
                    tradeRepository,
                    ledgerRepository,
                    configService,
                    hangingVerifier,
                )
                val background = launch { hangingGuard.validateAccountScope() }
                runCurrent()
                enteredProof.await()

                // The mutex is held inside suspended network proof: the local
                // read must fail closed immediately instead of waiting.
                val immediate = hangingGuard.readLocalTrustState()

                immediate.status shouldBe AccountScopeValidationStatus.VALIDATION_PENDING
                immediate.isValid shouldBe false

                releaseProof.complete(AccountHistoryContinuityStatus.VERIFIED)
                background.join()
                // The eventual background result still lands: the proof outcome
                // rebinds, so the local read trusts the rotated binding.
                hangingGuard.readLocalTrustState().status shouldBe AccountScopeValidationStatus.VALID
            }
        }

        "v2 binding with same fingerprint and consistent history strong-revalidates to v3" {
            runTest {
                val base = Instant.parse("2026-01-01T00:00:00Z")
                seedVersionedBinding("account-A", "2")
                listOf("v2-fill-old" to base, "v2-fill-new" to base.plusSeconds(100_000L)).forEach { (id, time) ->
                    tradeRepository.saveTrade(apiFill(id, time))
                }
                krakenService.fundingEvidenceScopeSupplier = { "account-A" }
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(
                        apiFill("v2-fill-old", base),
                        apiFill("v2-fill-new", base.plusSeconds(100_000L)),
                    )
                }

                val result = guard.validateAccountScope()

                // Must NOT fast-path on digest equality: the strong proof runs.
                result.status shouldBe AccountScopeValidationStatus.VALID
                krakenService.getTradeHistoryCallCount shouldBe 2
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
                ) shouldBe "3"
            }
        }

        "v2 binding with same fingerprint and mixed history is never promoted" {
            runTest {
                seedVersionedBinding("account-A", "2")
                tradeRepository.saveTrade(apiFill("account-A-old", Instant.parse("2026-01-01T00:00:00Z")))
                tradeRepository.saveTrade(apiFill("foreign-new", Instant.parse("2026-03-01T00:00:00Z")))
                krakenService.fundingEvidenceScopeSupplier = { "account-A" }
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(apiFill("account-A-old", Instant.parse("2026-01-01T00:00:00Z")))
                }

                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.UNBOUND_EXISTING_HISTORY
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST,
                ) shouldBe AccountHistoryScopeGuard.digestAccountScope("account-A")
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
                ) shouldBe "2"
            }
        }

        "v2 binding with changed fingerprint and consistent history binds current as v3" {
            runTest {
                val base = Instant.parse("2026-01-01T00:00:00Z")
                seedVersionedBinding("account-A", "2")
                tradeRepository.saveTrade(apiFill("gen-B-old", base))
                tradeRepository.saveTrade(apiFill("gen-B-new", base.plusSeconds(100_000L)))
                krakenService.fundingEvidenceScopeSupplier = { "account-B" }
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(apiFill("gen-B-old", base), apiFill("gen-B-new", base.plusSeconds(100_000L)))
                }

                // Strong legacy proof, not the lightweight rotation path.
                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.VALID
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST,
                ) shouldBe AccountHistoryScopeGuard.digestAccountScope("account-B")
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
                ) shouldBe "3"
            }
        }

        "v2 binding with changed fingerprint and mixed history stays at v2" {
            runTest {
                seedVersionedBinding("account-A", "2")
                tradeRepository.saveTrade(apiFill("account-A-old", Instant.parse("2026-01-01T00:00:00Z")))
                tradeRepository.saveTrade(apiFill("account-B-new", Instant.parse("2026-03-01T00:00:00Z")))
                krakenService.fundingEvidenceScopeSupplier = { "account-B" }
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(apiFill("account-B-new", Instant.parse("2026-03-01T00:00:00Z")))
                }

                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.UNBOUND_EXISTING_HISTORY
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST,
                ) shouldBe AccountHistoryScopeGuard.digestAccountScope("account-A")
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
                ) shouldBe "2"
            }
        }

        "v2 binding with changed fingerprint and one marker is not promoted by rotation" {
            runTest {
                seedVersionedBinding("account-A", "2")
                tradeRepository.saveTrade(apiFill("shared-fill", Instant.parse("2026-01-01T00:00:00Z")))
                krakenService.fundingEvidenceScopeSupplier = { "account-B" }
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(apiFill("shared-fill", Instant.parse("2026-01-01T00:00:00Z")))
                }

                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.UNBOUND_EXISTING_HISTORY
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
                ) shouldBe "2"
            }
        }

        "v2 binding with incomplete legacy search preserves v2" {
            runTest {
                val base = Instant.parse("2026-01-01T00:00:00Z")
                seedVersionedBinding("account-A", "2")
                tradeRepository.saveTrade(apiFill("v2-a", base))
                tradeRepository.saveTrade(apiFill("v2-b", base.plusSeconds(100_000L)))
                krakenService.fundingEvidenceScopeSupplier = { "account-B" }
                krakenService.tradeHistorySupplier = { _, _ ->
                    (0 until 50).map { apiFill("unrelated-$it", base) }
                }

                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.SCOPE_UNAVAILABLE
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST,
                ) shouldBe AccountHistoryScopeGuard.digestAccountScope("account-A")
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
                ) shouldBe "2"
            }
        }

        "v2 binding with Kraken outage preserves v2" {
            runTest {
                val base = Instant.parse("2026-01-01T00:00:00Z")
                seedVersionedBinding("account-A", "2")
                tradeRepository.saveTrade(apiFill("v2-a", base))
                tradeRepository.saveTrade(apiFill("v2-b", base.plusSeconds(100_000L)))
                krakenService.fundingEvidenceScopeSupplier = { "account-B" }
                krakenService.tradeHistorySupplier = { _, _ -> error("network") }

                val result = guard.validateAccountScope()

                result.status shouldBe AccountScopeValidationStatus.SCOPE_UNAVAILABLE
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
                ) shouldBe "2"
            }
        }

        "v2 empty binding upgrades to v3 after authentication" {
            runTest {
                seedVersionedBinding("account-A", "2")
                krakenService.fundingEvidenceScopeSupplier = { "account-A" }

                guard.validateAccountScope().status shouldBe AccountScopeValidationStatus.VALID
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
                ) shouldBe "3"
            }
        }

        "v3 binding with matching fingerprint is fast VALID without proof" {
            runTest {
                krakenService.fundingEvidenceScopeSupplier = { "account-A" }
                guard.validateAccountScope().status shouldBe AccountScopeValidationStatus.VALID

                krakenService.tradeHistorySupplier = { _, _ -> error("must not be called") }
                krakenService.ledgerSupplier = { _, _, _, _ -> error("must not be called") }
                krakenService.balanceSupplier = { error("must not be called") }

                guard.validateAccountScope().status shouldBe AccountScopeValidationStatus.VALID
            }
        }

        "v3 credential rotation still uses the lightweight one-hit proof" {
            runTest {
                krakenService.fundingEvidenceScopeSupplier = { "account-A" }
                guard.validateAccountScope().status shouldBe AccountScopeValidationStatus.VALID
                tradeRepository.saveTrade(apiFill("rotated-fill", Instant.parse("2026-01-01T00:00:00Z")))

                krakenService.fundingEvidenceScopeSupplier = { "account-A-rotated" }
                krakenService.tradeHistorySupplier = { _, _ ->
                    listOf(apiFill("rotated-fill", Instant.parse("2026-01-01T00:00:00Z")))
                }

                guard.validateAccountScope().status shouldBe AccountScopeValidationStatus.VALID
                tradeRepository.getSyncMetadata(
                    SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
                ) shouldBe "3"
            }
        }
    }

    private suspend fun seedVersionedBinding(scope: String, version: String) {
        tradeRepository.setSyncMetadata(
            SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST,
            AccountHistoryScopeGuard.digestAccountScope(scope),
        )
        tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION, version)
    }

    private fun apiFill(id: String, timestamp: Instant) = TestFixtures.tradeRecord(
        timestamp = timestamp,
        pair = Asset.BTC_USD_PAIR,
        side = "buy",
        symbol = Asset.BTC,
        volume = BigDecimal("0.01"),
        usdAmount = BigDecimal("100.00"),
        price = BigDecimal("10000.00"),
        source = TradeSource.API_FILL,
        tradeId = id,
    )
}
