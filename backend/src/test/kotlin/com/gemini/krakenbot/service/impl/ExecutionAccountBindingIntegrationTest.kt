package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.config.ExecutionDatabase
import com.gemini.krakenbot.config.ExecutionJournalBootstrap
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.ExecutionAccountBinding
import com.gemini.krakenbot.repository.ExecutionAccountBindingWriteResult
import com.gemini.krakenbot.repository.impl.SqliteExecutionAccountBindingRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteExecutionOrderIntentRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteLedgerRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.impl.history.AccountHistoryScopeGuard
import com.gemini.krakenbot.util.PrecisionConstants
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.Locale

class ExecutionAccountBindingIntegrationTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "matching legacy account evidence binds the execution journal before a live order" {
            withTempDirectory { directory ->
                val legacyTrade = seedLegacyAccountA(directory)
                val exchange = FakeKrakenService().apply {
                    fundingEvidenceScopeSupplier = { GENERATION_A }
                    authenticatedAccountIdentitySupplier = { ACCOUNT_A }
                    tradeHistorySupplier = { _, _ -> listOf(legacyTrade) }
                }
                val environment = openEnvironment(directory, exchange)

                executeBuy(environment)

                exchange.executedOrders.size shouldBe 1
                val binding = environment.bindingRepository.load()
                binding?.accountIdentityDigest shouldBe identityDigest(ACCOUNT_A)
                binding?.credentialGenerationDigest shouldBe digest(GENERATION_A)
                binding?.auditVerified shouldBe true
                scalar(environment.executionPath, "SELECT COUNT(*) FROM execution_account_binding_audit") shouldBe 1
                scalar(environment.executionPath, "SELECT COUNT(*) FROM execution_order_intents") shouldBe 1
            }
        }

        "different account credentials cannot replace the legacy account binding" {
            withTempDirectory { directory ->
                seedLegacyAccountA(directory)
                val exchange = FakeKrakenService().apply {
                    fundingEvidenceScopeSupplier = { GENERATION_B }
                    authenticatedAccountIdentitySupplier = { ACCOUNT_B }
                    tradeHistorySupplier = { _, _ -> emptyList() }
                }
                val environment = openEnvironment(directory, exchange)

                shouldThrow<IllegalStateException> { executeBuy(environment) }

                exchange.executedOrders.shouldBeEmpty()
                environment.bindingRepository.load() shouldBe null
                environment.tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST) shouldBe
                    AccountHistoryScopeGuard.digestAccountScope(GENERATION_A)
                scalar(environment.executionPath, "SELECT COUNT(*) FROM execution_order_intents") shouldBe 0

                val historyCallsAfterFirstVerification =
                    exchange.getTradeHistoryCallCount + exchange.getLedgersCallCount
                shouldThrow<IllegalStateException> { executeBuy(environment, "different-account-retry") }
                exchange.getTradeHistoryCallCount + exchange.getLedgersCallCount shouldBe
                    historyCallsAfterFirstVerification
                exchange.executedOrders.shouldBeEmpty()
            }
        }

        "credential rotation is blocked until authenticated account identity is available" {
            withTempDirectory { directory ->
                val exchange = FakeKrakenService().apply {
                    fundingEvidenceScopeSupplier = { GENERATION_A }
                    authenticatedAccountIdentitySupplier = { ACCOUNT_A }
                }
                val environment = openEnvironment(directory, exchange)
                executeBuy(environment, "bind-a")
                val originalBinding = checkNotNull(environment.bindingRepository.load())

                exchange.fundingEvidenceScopeSupplier = { GENERATION_B }
                exchange.authenticatedAccountIdentitySupplier = { null }
                shouldThrow<IllegalStateException> { executeBuy(environment, "rotation-unverified") }
                exchange.executedOrders.size shouldBe 1
                environment.bindingRepository.load() shouldBe originalBinding

                exchange.authenticatedAccountIdentitySupplier = { ACCOUNT_A }
                executeBuy(environment, "rotation-verified")
                exchange.executedOrders.size shouldBe 2
                val rotated = checkNotNull(environment.bindingRepository.load())
                rotated.accountIdentityDigest shouldBe originalBinding.accountIdentityDigest
                rotated.credentialGenerationDigest shouldBe digest(GENERATION_B)
                scalar(environment.executionPath, "SELECT COUNT(*) FROM execution_account_binding_audit") shouldBe 2
            }
        }

        "Actual observation identity requires a matching verified account and credential scope" {
            withTempDirectory { directory ->
                val exchange = FakeKrakenService().apply {
                    fundingEvidenceScopeSupplier = { GENERATION_A }
                }
                val environment = openEnvironment(directory, exchange)
                val binding = ExecutionAccountBinding(
                    accountIdentityDigest = identityDigest(ACCOUNT_A),
                    credentialGenerationDigest = digest(GENERATION_A),
                    bindingVersion = 1,
                    verificationMethod = "legacy-scope-guard-plus-kraken-iiban",
                    verifiedAtMillis = 1L,
                    auditVerified = true,
                )
                environment.bindingRepository.createIfPristine(binding) shouldBe
                    ExecutionAccountBindingWriteResult.CREATED
                val identityCallsBeforeObservation = exchange.getAuthenticatedAccountIdentityCallCount

                environment.bindingService.verifiedAccountIdentityDigestForObservation(GENERATION_A) shouldBe
                    identityDigest(ACCOUNT_A)
                environment.bindingService.verifiedAccountIdentityDigestForObservation(GENERATION_B) shouldBe null
                exchange.getAuthenticatedAccountIdentityCallCount shouldBe identityCallsBeforeObservation
            }
        }

        "Actual observation identity remains unavailable for simulation and unbound dry run" {
            withTempDirectory { directory ->
                val simulationExchange = FakeKrakenService().apply {
                    fundingEvidenceScopeSupplier = { GENERATION_A }
                }
                val simulationEnvironment = openEnvironment(
                    directory,
                    simulationExchange,
                    settings = TestFixtures.settings(simulation = true),
                )

                simulationEnvironment.bindingService.verifiedAccountIdentityDigestForObservation(GENERATION_A) shouldBe
                    null
                simulationExchange.getAuthenticatedAccountIdentityCallCount shouldBe 0
            }
            withTempDirectory { directory ->
                val dryRunExchange = FakeKrakenService().apply {
                    fundingEvidenceScopeSupplier = { GENERATION_A }
                }
                val dryRunEnvironment = openEnvironment(
                    directory,
                    dryRunExchange,
                    settings = TestFixtures.settings(dryRun = true, simulation = false),
                )

                dryRunEnvironment.bindingService.verifiedAccountIdentityDigestForObservation(GENERATION_A) shouldBe
                    null
                dryRunExchange.getAuthenticatedAccountIdentityCallCount shouldBe 0
            }
        }

        "missing or corrupt binding with post-cutover order history fails closed" {
            withTempDirectory { directory ->
                val exchange = FakeKrakenService().apply {
                    fundingEvidenceScopeSupplier = { GENERATION_A }
                    authenticatedAccountIdentitySupplier = { ACCOUNT_A }
                }
                val environment = openEnvironment(directory, exchange)
                val id = environment.intentRepository.savePending(newIntent("existing-live-order"))
                environment.intentRepository.recordOutcome(
                    id = id,
                    state = OrderIntentState.CONFIRMED,
                    orderTxid = "EXISTING-TX",
                    errorMessage = null,
                    resolvedAt = Instant.now(),
                    outcomeVolume = BigDecimal("0.02500000"),
                ) shouldBe true

                shouldThrow<IllegalStateException> { executeBuy(environment) }

                exchange.executedOrders.shouldBeEmpty()
                environment.bindingRepository.load() shouldBe null
            }
            withTempDirectory { directory ->
                val exchange = FakeKrakenService().apply {
                    fundingEvidenceScopeSupplier = { GENERATION_A }
                    authenticatedAccountIdentitySupplier = { ACCOUNT_A }
                }
                val environment = openEnvironment(directory, exchange)
                executeBuy(environment, "bind-corrupt")
                DriverManager.getConnection("jdbc:sqlite:${environment.executionPath}").use { connection ->
                    connection.createStatement().use {
                        it.executeUpdate("UPDATE execution_account_binding SET account_identity_digest = 'bad'")
                    }
                }

                shouldThrow<IllegalStateException> { executeBuy(environment, "after-corrupt") }

                exchange.executedOrders.size shouldBe 1
            }
            withTempDirectory { directory ->
                val exchange = FakeKrakenService().apply {
                    fundingEvidenceScopeSupplier = { GENERATION_A }
                    authenticatedAccountIdentitySupplier = { ACCOUNT_A }
                }
                val environment = openEnvironment(directory, exchange)
                environment.bindingService.ensureVerifiedForSubmission()
                DriverManager.getConnection("jdbc:sqlite:${environment.executionPath}").use { connection ->
                    connection.createStatement().use {
                        it.executeUpdate("DELETE FROM execution_account_binding")
                        it.executeUpdate("DELETE FROM execution_account_binding_audit")
                    }
                }

                shouldThrow<IllegalStateException> { executeBuy(environment, "missing-bound-record") }

                exchange.executedOrders.shouldBeEmpty()
                environment.bindingRepository.hasBindingAnchor() shouldBe true
            }
        }

        "steady-state live submission remains independent of unavailable reporting metadata" {
            withTempDirectory { directory ->
                val exchange = FakeKrakenService().apply {
                    fundingEvidenceScopeSupplier = { GENERATION_A }
                    authenticatedAccountIdentitySupplier = { ACCOUNT_A }
                }
                val environment = openEnvironment(directory, exchange)
                executeBuy(environment, "bind-reporting")
                DriverManager.getConnection("jdbc:sqlite:${environment.reportingPath}").use { connection ->
                    connection.createStatement().use { it.execute("DROP TABLE history_sync_metadata") }
                }
                val identityChecksBeforeSecondOrder = exchange.getAuthenticatedAccountIdentityCallCount

                executeBuy(environment, "reporting-unavailable")

                exchange.executedOrders.size shouldBe 2
                exchange.getAuthenticatedAccountIdentityCallCount shouldBe identityChecksBeforeSecondOrder
                scalar(environment.executionPath, "SELECT COUNT(*) FROM execution_order_intents") shouldBe 2
            }
        }

        "binding persists across restart and mismatched credentials remain blocked" {
            withTempDirectory { directory ->
                val firstExchange = FakeKrakenService().apply {
                    fundingEvidenceScopeSupplier = { GENERATION_A }
                    authenticatedAccountIdentitySupplier = { ACCOUNT_A }
                }
                val first = openEnvironment(directory, firstExchange)
                executeBuy(first, "before-restart")
                val persisted = checkNotNull(first.bindingRepository.load())

                val restartExchange = FakeKrakenService().apply {
                    fundingEvidenceScopeSupplier = { GENERATION_A }
                    authenticatedAccountIdentitySupplier = { ACCOUNT_A }
                }
                val restarted = openEnvironment(directory, restartExchange)
                restarted.bindingRepository.load() shouldBe persisted
                executeBuy(restarted, "after-restart")
                restartExchange.executedOrders.size shouldBe 1

                restartExchange.fundingEvidenceScopeSupplier = { GENERATION_B }
                restartExchange.authenticatedAccountIdentitySupplier = { ACCOUNT_B }
                shouldThrow<IllegalStateException> { executeBuy(restarted, "wrong-account") }
                restarted.bindingRepository.load() shouldBe persisted

                val finalExchange = FakeKrakenService().apply {
                    fundingEvidenceScopeSupplier = { GENERATION_B }
                    authenticatedAccountIdentitySupplier = { ACCOUNT_B }
                }
                val afterMismatchRestart = openEnvironment(directory, finalExchange)
                shouldThrow<IllegalStateException> { executeBuy(afterMismatchRestart, "wrong-account-restart") }
                finalExchange.executedOrders.shouldBeEmpty()
                afterMismatchRestart.bindingRepository.load() shouldBe persisted
            }
        }

        "simulation and dry-run do not bind; switching to live requires a binding" {
            withTempDirectory { directory ->
                val exchange = FakeKrakenService().apply {
                    fundingEvidenceScopeSupplier = { GENERATION_A }
                    authenticatedAccountIdentitySupplier = { ACCOUNT_A }
                }
                val environment = openEnvironment(directory, exchange)

                executeBuy(environment, "dry-run", dryRun = true, simulation = false)
                executeBuy(environment, "simulation", dryRun = false, simulation = true)

                exchange.getAuthenticatedAccountIdentityCallCount shouldBe 0
                environment.bindingRepository.load() shouldBe null
                scalar(environment.executionPath, "SELECT COUNT(*) FROM execution_order_intents") shouldBe 0

                executeBuy(environment, "switch-to-live")

                exchange.executedOrders.size shouldBe 3
                checkNotNull(environment.bindingRepository.load()).accountIdentityDigest shouldBe
                    identityDigest(ACCOUNT_A)
            }
        }

        "unresolved intents block account-binding changes and retain one-shot execution" {
            withTempDirectory { directory ->
                val exchange = FakeKrakenService().apply {
                    fundingEvidenceScopeSupplier = { GENERATION_A }
                    authenticatedAccountIdentitySupplier = { ACCOUNT_A }
                    orderResultFactory = { pair, _, side, volume ->
                        OrderResult(
                            success = false,
                            pair = pair,
                            side = side,
                            volume = volume,
                            errorMessage = "ambiguous response",
                            submissionUncertain = true,
                        )
                    }
                }
                val environment = openEnvironment(directory, exchange)
                executeBuy(environment, "uncertain-order")
                val original = checkNotNull(environment.bindingRepository.load())
                val identityChecksBeforeRotation = exchange.getAuthenticatedAccountIdentityCallCount
                exchange.fundingEvidenceScopeSupplier = { GENERATION_B }

                shouldThrow<IllegalStateException> { environment.bindingService.ensureVerifiedForSubmission() }
                executeBuy(environment, "blocked-by-uncertain")

                exchange.executedOrders.size shouldBe 1
                environment.bindingRepository.load() shouldBe original
                exchange.getAuthenticatedAccountIdentityCallCount shouldBe identityChecksBeforeRotation + 1
                scalar(environment.executionPath, "SELECT COUNT(*) FROM execution_order_intents") shouldBe 1
                scalar(
                    environment.executionPath,
                    "SELECT COUNT(*) FROM execution_order_intents WHERE state = 'UNCERTAIN'",
                ) shouldBe
                    1
            }
        }
    }

    private suspend fun seedLegacyAccountA(directory: Path): com.gemini.krakenbot.model.TradeRecord {
        val database = DatabaseConfig.init(directory.resolve("reporting.db").toString())
        val tradeRepository = SqliteTradeRepositoryImpl(database)
        val legacyTrade = TestFixtures.tradeRecord(
            timestamp = Instant.now().minusSeconds(3600),
            pair = Asset.BTC_USD_PAIR,
            side = "buy",
            symbol = Asset.BTC,
            volume = BigDecimal("0.01"),
            usdAmount = BigDecimal("100.00"),
            price = BigDecimal("10000.00"),
            source = TradeSource.API_FILL,
            tradeId = LEGACY_TRADE_ID,
        )
        tradeRepository.saveTrade(legacyTrade)
        tradeRepository.setSyncMetadata(
            SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST,
            AccountHistoryScopeGuard.digestAccountScope(GENERATION_A),
        )
        tradeRepository.setSyncMetadata(
            SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
            AccountHistoryScopeGuard.CURRENT_BINDING_VERSION,
        )
        return legacyTrade
    }

    private suspend fun openEnvironment(
        directory: Path,
        exchange: FakeKrakenService,
        settings: Settings = liveSettings(),
        credentials: KrakenCredentials = KrakenCredentials(
            TestFixtures.TRADE_HISTORY_API_KEY,
            TestFixtures.TRADE_HISTORY_API_SECRET,
        ),
    ): Environment {
        val reportingPath = directory.resolve("reporting.db")
        val executionPath = directory.resolve("execution.db")
        val reportingDatabase = DatabaseConfig.init(reportingPath.toString())
        val tradeRepository = SqliteTradeRepositoryImpl(reportingDatabase)
        val ledgerRepository = SqliteLedgerRepositoryImpl(reportingDatabase)
        val executionDatabase = ExecutionDatabase.init(executionPath.toString(), reportingPath.toString())
        val intentRepository = SqliteExecutionOrderIntentRepositoryImpl(executionDatabase)
        ExecutionJournalBootstrap(executionDatabase, intentRepository, reportingPath.toString()).initialize()
        val bindingRepository = SqliteExecutionAccountBindingRepositoryImpl(executionDatabase)
        val configService = mockk<ConfigService>()
        every { configService.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG.copy(
            kraken = credentials,
            settings = settings,
        )
        coEvery { configService.beginExecutionSession() } returns Unit
        coEvery { configService.endExecutionSession() } returns Unit
        val bindingService = ExecutionAccountBindingService(
            database = executionDatabase,
            bindingRepository = bindingRepository,
            krakenService = exchange,
            configService = configService,
            accountHistoryScopeGuard = AccountHistoryScopeGuard(
                krakenService = exchange,
                tradeRepository = tradeRepository,
                ledgerRepository = ledgerRepository,
                configService = configService,
            ),
        )
        val intentService = OrderIntentServiceImpl(intentRepository, bindingService)
        return Environment(
            reportingPath = reportingPath,
            executionPath = executionPath,
            executionDatabase = executionDatabase,
            tradeRepository = tradeRepository,
            intentRepository = intentRepository,
            bindingRepository = bindingRepository,
            bindingService = bindingService,
            intentService = intentService,
            exchange = exchange,
        )
    }

    private suspend fun executeBuy(
        environment: Environment,
        cycleId: String = "account-binding-cycle",
        dryRun: Boolean = false,
        simulation: Boolean = false,
    ) = OrderExecutorImpl(
        krakenService = environment.exchange,
        tradeHistoryService = null,
        orderIntentService = environment.intentService,
    ).executeOrders(
        buyOrders = mapOf(Asset.BTC to BigDecimal("25.00")),
        sellOrders = emptyMap(),
        currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
        prices = mapOf(Asset.BTC to BigDecimal("1000.00")),
        settings = TestFixtures.settings(dryRun = dryRun, simulation = simulation),
        actionLog = mutableListOf(),
        cycleId = cycleId,
    )

    private fun liveSettings() = TestFixtures.settings(dryRun = false, simulation = false)

    private fun newIntent(cycleId: String) = OrderIntent(
        cycleId = cycleId,
        clientOrderId = "client-$cycleId",
        pair = Asset.BTC_USD_PAIR,
        symbol = Asset.BTC,
        side = "buy",
        volume = BigDecimal("0.02500000").setScale(PrecisionConstants.SCALE_CRYPTO),
        usdAmount = BigDecimal("25.00").setScale(PrecisionConstants.SCALE_USD),
        expectedPrice = BigDecimal("1000.00"),
        createdAt = Instant.now(),
        state = OrderIntentState.PENDING,
    )

    private fun scalar(databasePath: Path, query: String): Int =
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(query).use { resultSet ->
                    check(resultSet.next()) { "Expected a SQLite scalar result." }
                    resultSet.getInt(1)
                }
            }
        }

    private fun digest(value: String): String = AccountHistoryScopeGuard.digestAccountScope(value)

    private fun identityDigest(value: String): String =
        digest(value.filterNot(Char::isWhitespace).uppercase(Locale.ROOT))

    private fun withTempDirectory(block: suspend (Path) -> Unit) = runTest {
        val directory = Files.createTempDirectory("execution-account-binding-")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private data class Environment(
        val reportingPath: Path,
        val executionPath: Path,
        val executionDatabase: ExecutionDatabase,
        val tradeRepository: SqliteTradeRepositoryImpl,
        val intentRepository: SqliteExecutionOrderIntentRepositoryImpl,
        val bindingRepository: SqliteExecutionAccountBindingRepositoryImpl,
        val bindingService: ExecutionAccountBindingService,
        val intentService: OrderIntentServiceImpl,
        val exchange: FakeKrakenService,
    )

    private companion object {
        const val ACCOUNT_A = "AA00 TEST ACCOUNT A"
        const val ACCOUNT_B = "AA00 TEST ACCOUNT B"
        const val GENERATION_A = "credential-generation-a"
        const val GENERATION_B = "credential-generation-b"
        const val LEGACY_TRADE_ID = "legacy-trade-account-a"
    }
}
