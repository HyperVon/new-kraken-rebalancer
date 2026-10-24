package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.DataDirectoryIdentity
import com.gemini.krakenbot.config.ExecutionDatabase
import com.gemini.krakenbot.config.ExecutionJournalBootstrap
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.repository.ExecutionAccountBinding
import com.gemini.krakenbot.repository.ExecutionAccountBindingRepository
import com.gemini.krakenbot.repository.ExecutionAccountBindingWriteResult
import com.gemini.krakenbot.repository.impl.SqliteExecutionAccountBindingRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteExecutionOrderIntentRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.test.TestConstants
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

class ExecutionAccountBindingIntegrationTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "initial binding records the directly authenticated Kraken account" {
            withTempDirectory { directory ->
                val exchange = exchange(ACCOUNT_A, GENERATION_A)
                val environment = openEnvironment(directory, exchange)

                environment.bindingService.ensureVerifiedForSubmission()

                val binding = checkNotNull(environment.bindingRepository.load())
                binding.accountIdentityDigest shouldBe identityDigest(ACCOUNT_A)
                binding.credentialGenerationDigest shouldBe digest(GENERATION_A)
                binding.verificationMethod shouldBe "kraken-iiban-initial-binding"
                binding.auditVerified shouldBe true
                environment.dataDirectoryIdentity.boundAccountIdentity() shouldBe identityDigest(ACCOUNT_A)
            }
        }

        "prior directory identity prevents execution from binding another account" {
            withTempDirectory { directory ->
                val identity = DataDirectoryIdentity(directory) { false }
                identity.bindOrVerify(identityDigest(ACCOUNT_A))
                val environment = openEnvironment(directory, exchange(ACCOUNT_B, GENERATION_B), identity)

                shouldThrow<IllegalStateException> {
                    environment.bindingService.ensureVerifiedForSubmission()
                }

                environment.bindingRepository.load() shouldBe null
                environment.dataDirectoryIdentity.boundAccountIdentity() shouldBe identityDigest(ACCOUNT_A)
            }
        }

        "credential rotation accepts the same Kraken account and rejects an account change" {
            withTempDirectory { directory ->
                val exchange = exchange(ACCOUNT_A, GENERATION_A)
                val environment = openEnvironment(directory, exchange)
                environment.bindingService.ensureVerifiedForSubmission()

                exchange.fundingEvidenceScopeSupplier = { GENERATION_B }
                environment.bindingService.ensureVerifiedForSubmission()
                val rotated = checkNotNull(environment.bindingRepository.load())
                rotated.accountIdentityDigest shouldBe identityDigest(ACCOUNT_A)
                rotated.credentialGenerationDigest shouldBe digest(GENERATION_B)
                scalar(environment.executionPath, "SELECT COUNT(*) FROM execution_account_binding_audit") shouldBe 2

                exchange.fundingEvidenceScopeSupplier = { GENERATION_C }
                exchange.authenticatedAccountIdentitySupplier = { ACCOUNT_B }
                shouldThrow<IllegalStateException> {
                    environment.bindingService.ensureVerifiedForSubmission()
                }

                environment.bindingRepository.load() shouldBe rotated
                scalar(environment.executionPath, "SELECT COUNT(*) FROM execution_account_binding_audit") shouldBe 2
            }
        }

        "missing authenticated identity leaves the journal unbound" {
            withTempDirectory { directory ->
                val environment = openEnvironment(directory, exchange("  \t", GENERATION_A))

                shouldThrow<IllegalStateException> {
                    environment.bindingService.ensureVerifiedForSubmission()
                }

                environment.bindingRepository.load() shouldBe null
                environment.dataDirectoryIdentity.boundAccountIdentity() shouldBe null
            }
        }

        "refuses binding when simulation or dry-run is active" {
            withTempDirectory { directory ->
                val exchange = exchange(ACCOUNT_A, GENERATION_A)
                val config = TestFixtures.DEFAULT_TEST_CONFIG.copy(
                    kraken = KrakenCredentials(
                        TestConstants.API_KEY,
                        TestFixtures.KRAKEN_TEST_API_SECRET,
                    ),
                    settings = TestFixtures.settings(dryRun = true, simulation = false),
                )
                val environment = openEnvironment(directory, exchange, customConfig = config)
                shouldThrow<IllegalStateException> {
                    environment.bindingService.ensureVerifiedForSubmission()
                }
            }
        }

        "refuses binding when credentials are missing" {
            withTempDirectory { directory ->
                val exchange = exchange(ACCOUNT_A, GENERATION_A)
                val config = TestFixtures.DEFAULT_TEST_CONFIG.copy(
                    kraken = KrakenCredentials("", ""),
                    settings = TestFixtures.settings(dryRun = false, simulation = false),
                )
                val environment = openEnvironment(directory, exchange, customConfig = config)
                shouldThrow<IllegalStateException> {
                    environment.bindingService.ensureVerifiedForSubmission()
                }
            }
        }

        "refuses binding when credential scope is unavailable" {
            withTempDirectory { directory ->
                val exchange = exchange(ACCOUNT_A, "scope-unavailable")
                val environment = openEnvironment(directory, exchange)
                shouldThrow<IllegalStateException> {
                    environment.bindingService.ensureVerifiedForSubmission()
                }
            }
        }

        "refuses binding when authenticated account identity lookup throws" {
            withTempDirectory { directory ->
                val exchange = exchange(ACCOUNT_A, GENERATION_A).apply {
                    authenticatedAccountIdentitySupplier = { throw RuntimeException("Kraken unavailable") }
                }
                val environment = openEnvironment(directory, exchange)
                shouldThrow<IllegalStateException> {
                    environment.bindingService.ensureVerifiedForSubmission()
                }
            }
        }

        "tampered audit_verified in existing binding blocks live orders" {
            withTempDirectory { directory ->
                val exchange = exchange(ACCOUNT_A, GENERATION_A)
                val environment = openEnvironment(directory, exchange)
                environment.bindingService.ensureVerifiedForSubmission()

                execute(environment.executionPath, "DELETE FROM execution_account_binding_audit")

                val ex = shouldThrow<IllegalStateException> {
                    environment.bindingService.ensureVerifiedForSubmission()
                }
                ex.message shouldBe "Execution account binding audit evidence is missing or corrupt."
            }
        }

        "tampered binding_version in existing binding blocks live orders" {
            withTempDirectory { directory ->
                val exchange = exchange(ACCOUNT_A, GENERATION_A)
                val environment = openEnvironment(directory, exchange)
                environment.bindingService.ensureVerifiedForSubmission()

                execute(environment.executionPath, "UPDATE execution_account_binding SET binding_version = 999")

                val ex = shouldThrow<IllegalStateException> {
                    environment.bindingService.ensureVerifiedForSubmission()
                }
                ex.message shouldBe "Unsupported execution account binding version 999; live orders are blocked."
            }
        }

        "corrupt digests in existing binding blocks live orders" {
            withTempDirectory { directory ->
                val exchange = exchange(ACCOUNT_A, GENERATION_A)
                val environment = openEnvironment(directory, exchange)
                environment.bindingService.ensureVerifiedForSubmission()

                execute(
                    environment.executionPath,
                    "UPDATE execution_account_binding SET account_identity_digest = 'not-a-digest'",
                )
                execute(
                    environment.executionPath,
                    "UPDATE execution_account_binding_audit SET account_identity_digest = 'not-a-digest'",
                )

                val ex = shouldThrow<IllegalStateException> {
                    environment.bindingService.ensureVerifiedForSubmission()
                }
                ex.message shouldBe "Execution account binding is corrupt; live orders are blocked."
            }
        }

        "unrecognized verification_method in existing binding blocks live orders" {
            withTempDirectory { directory ->
                val exchange = exchange(ACCOUNT_A, GENERATION_A)
                val environment = openEnvironment(directory, exchange)
                environment.bindingService.ensureVerifiedForSubmission()

                execute(
                    environment.executionPath,
                    "UPDATE execution_account_binding SET verification_method = 'unknown-method'",
                )
                execute(
                    environment.executionPath,
                    "UPDATE execution_account_binding_audit SET verification_method = 'unknown-method'",
                )

                val ex = shouldThrow<IllegalStateException> {
                    environment.bindingService.ensureVerifiedForSubmission()
                }
                ex.message shouldBe "Execution account binding proof is unrecognized; live orders are blocked."
            }
        }

        "unresolved execution intents prevent initial account binding" {
            withTempDirectory { directory ->
                val exchange = exchange(ACCOUNT_A, GENERATION_A)
                val environment = openEnvironment(directory, exchange)
                environment.intentRepository.savePending(testIntent())

                val ex = shouldThrow<IllegalStateException> {
                    environment.bindingService.ensureVerifiedForSubmission()
                }
                ex.message shouldBe
                    "Live account binding failed closed: unresolved execution intents prevent initial account binding."
            }
        }

        "execution journal has order history without a trusted account binding" {
            withTempDirectory { directory ->
                val exchange = exchange(ACCOUNT_A, GENERATION_A)
                val environment = openEnvironment(directory, exchange)
                val id = environment.intentRepository.savePending(testIntent())
                environment.intentRepository.recordOutcome(
                    id = id,
                    state = OrderIntentState.CONFIRMED,
                    orderTxid = "OID-1",
                    errorMessage = null,
                    resolvedAt = Instant.now(),
                    outcomeVolume = BigDecimal("0.025"),
                )

                val ex = shouldThrow<IllegalStateException> {
                    environment.bindingService.ensureVerifiedForSubmission()
                }
                ex.message shouldBe
                    "Live account binding failed closed: execution journal has order history without a trusted account binding."
            }
        }

        "unresolved execution intents prevent credential rotation" {
            withTempDirectory { directory ->
                val exchange = exchange(ACCOUNT_A, GENERATION_A)
                val environment = openEnvironment(directory, exchange)
                environment.bindingService.ensureVerifiedForSubmission()

                environment.intentRepository.savePending(testIntent())
                exchange.fundingEvidenceScopeSupplier = { GENERATION_B }

                val ex = shouldThrow<IllegalStateException> {
                    environment.bindingService.ensureVerifiedForSubmission()
                }
                ex.message shouldBe
                    "Live account binding failed closed: unresolved execution intents prevent credential rotation."
            }
        }

        "missing binding record despite durable binding evidence" {
            withTempDirectory { directory ->
                val exchange = exchange(ACCOUNT_A, GENERATION_A)
                val environment = openEnvironment(directory, exchange)
                environment.bindingService.ensureVerifiedForSubmission()

                execute(environment.executionPath, "DELETE FROM execution_account_binding")

                val ex = shouldThrow<IllegalStateException> {
                    environment.bindingService.ensureVerifiedForSubmission()
                }
                ex.message shouldBe
                    "Live account binding failed closed: execution account binding record is missing despite durable binding history."
            }
        }

        "unavailable credential generation fails closed" {
            withTempDirectory { directory ->
                val exchange = exchange(ACCOUNT_A, "   ")
                val environment = openEnvironment(directory, exchange)

                val ex = shouldThrow<IllegalStateException> {
                    environment.bindingService.ensureVerifiedForSubmission()
                }
                ex.message shouldBe "Live account binding failed closed: credential generation is unavailable."
            }
        }

        "incomplete prior binding evidence in audit log blocks initial binding" {
            withTempDirectory { directory ->
                val exchange = exchange(ACCOUNT_A, GENERATION_A)
                val environment = openEnvironment(directory, exchange)

                execute(
                    environment.executionPath,
                    "INSERT INTO execution_account_binding_audit(account_identity_digest, " +
                        "previous_credential_generation_digest, " +
                        "credential_generation_digest, verification_method, verified_at) " +
                        "VALUES ('audit-digest', null, 'cred-digest', 'manual', 1000)",
                )

                val ex = shouldThrow<IllegalStateException> {
                    environment.bindingService.ensureVerifiedForSubmission()
                }
                ex.message shouldBe
                    "Live account binding failed closed: execution account binding evidence is incomplete; " +
                    "refusing to replace it."
            }
        }

        "raced initial binding succeeds if matching and fails if mismatched" {
            withTempDirectory { directory ->
                val exchange = exchange(ACCOUNT_A, GENERATION_A)
                val environment = openEnvironment(directory, exchange)

                val mockRepo = mockk<ExecutionAccountBindingRepository>()
                val service = ExecutionAccountBindingService(
                    database = environment.database,
                    bindingRepository = mockRepo,
                    krakenService = exchange,
                    configService = mockk {
                        every { getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG.copy(
                            kraken = KrakenCredentials(
                                TestConstants.API_KEY,
                                TestFixtures.KRAKEN_TEST_API_SECRET,
                            ),
                            settings = TestFixtures.settings(dryRun = false, simulation = false),
                        )
                        coEvery { beginExecutionSession() } returns Unit
                        coEvery { endExecutionSession() } returns Unit
                    },
                    dataDirectoryIdentity = environment.dataDirectoryIdentity,
                )

                val matchingBinding = ExecutionAccountBinding(
                    accountIdentityDigest = identityDigest(ACCOUNT_A),
                    credentialGenerationDigest = digest(GENERATION_A),
                    bindingVersion = 1,
                    verificationMethod = "kraken-iiban-initial-binding",
                    verifiedAtMillis = System.currentTimeMillis(),
                    auditVerified = true,
                )

                coEvery { mockRepo.load() } returnsMany listOf(null, matchingBinding)
                coEvery { mockRepo.hasBindingAnchor() } returns false
                coEvery { mockRepo.createIfPristine(any()) } returns
                    ExecutionAccountBindingWriteResult.ALREADY_PRESENT

                service.ensureVerifiedForSubmission()

                // Mismatched raced binding
                val mismatchedBinding = matchingBinding.copy(accountIdentityDigest = identityDigest(ACCOUNT_B))
                coEvery { mockRepo.load() } returnsMany listOf(null, mismatchedBinding)
                val mismatchEx = shouldThrow<IllegalStateException> {
                    service.ensureVerifiedForSubmission()
                }
                mismatchEx.message shouldBe
                    "Live account binding failed closed: execution account binding changed during verification."

                // Unexpected write result
                coEvery { mockRepo.load() } returns null
                coEvery { mockRepo.createIfPristine(any()) } returns
                    ExecutionAccountBindingWriteResult.UPDATED
                val unexpectedEx = shouldThrow<IllegalStateException> {
                    service.ensureVerifiedForSubmission()
                }
                unexpectedEx.message shouldBe
                    "Live account binding failed closed: unexpected execution account binding state."
            }
        }

        "raced credential rotation succeeds if matching and fails if mismatched or unexpected result" {
            withTempDirectory { directory ->
                val exchange = exchange(ACCOUNT_A, GENERATION_B)
                val environment = openEnvironment(directory, exchange)

                val currentBinding = ExecutionAccountBinding(
                    accountIdentityDigest = identityDigest(ACCOUNT_A),
                    credentialGenerationDigest = digest(GENERATION_A),
                    bindingVersion = 1,
                    verificationMethod = "kraken-iiban-initial-binding",
                    verifiedAtMillis = 1000L,
                    auditVerified = true,
                )
                val matchingRotated = currentBinding.copy(
                    credentialGenerationDigest = digest(GENERATION_B),
                    verificationMethod = "kraken-iiban-credential-rotation",
                    verifiedAtMillis = 2000L,
                )

                val mockRepo = mockk<ExecutionAccountBindingRepository>()
                val service = ExecutionAccountBindingService(
                    database = environment.database,
                    bindingRepository = mockRepo,
                    krakenService = exchange,
                    configService = mockk {
                        every { getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG.copy(
                            kraken = KrakenCredentials(
                                TestConstants.API_KEY,
                                TestFixtures.KRAKEN_TEST_API_SECRET,
                            ),
                            settings = TestFixtures.settings(dryRun = false, simulation = false),
                        )
                        coEvery { beginExecutionSession() } returns Unit
                        coEvery { endExecutionSession() } returns Unit
                    },
                    dataDirectoryIdentity = environment.dataDirectoryIdentity,
                )

                // Raced match
                coEvery { mockRepo.load() } returnsMany listOf(currentBinding, matchingRotated)
                coEvery { mockRepo.updateCredentialGeneration(any(), any(), any()) } returns
                    ExecutionAccountBindingWriteResult.STALE_BINDING

                service.ensureVerifiedForSubmission()

                // Raced mismatch
                coEvery { mockRepo.load() } returnsMany listOf(currentBinding, currentBinding)
                val mismatchEx = shouldThrow<IllegalStateException> {
                    service.ensureVerifiedForSubmission()
                }
                mismatchEx.message shouldBe
                    "Live account binding failed closed: execution account binding changed during credential verification."

                // Unexpected write result
                coEvery { mockRepo.load() } returns currentBinding
                coEvery { mockRepo.updateCredentialGeneration(any(), any(), any()) } returns
                    ExecutionAccountBindingWriteResult.CREATED
                val unexpectedEx = shouldThrow<IllegalStateException> {
                    service.ensureVerifiedForSubmission()
                }
                unexpectedEx.message shouldBe
                    "Live account binding failed closed: unexpected execution account credential rotation state."
            }
        }

        "refuses binding when hasBindingAnchor is true but binding is missing" {
            withTempDirectory { directory ->
                val exchange = exchange(ACCOUNT_A, GENERATION_A)
                val environment = openEnvironment(directory, exchange)
                environment.bindingService.ensureVerifiedForSubmission()

                execute(environment.executionPath, "DELETE FROM execution_account_binding")

                val ex = shouldThrow<IllegalStateException> {
                    environment.bindingService.ensureVerifiedForSubmission()
                }
                ex.message shouldBe
                    "Live account binding failed closed: execution account binding record is missing despite durable binding history."
            }
        }

        "initial binding blocked by prior order history" {
            withTempDirectory { directory ->
                val exchange = exchange(ACCOUNT_A, GENERATION_A)
                val environment = openEnvironment(directory, exchange)
                val id = environment.intentRepository.savePending(testIntent())
                environment.intentRepository.recordOutcome(
                    id,
                    OrderIntentState.CONFIRMED,
                    "tx-1",
                    null,
                    Instant.now(),
                    BigDecimal("0.02500000"),
                )

                val ex = shouldThrow<IllegalStateException> {
                    environment.bindingService.ensureVerifiedForSubmission()
                }
                ex.message shouldBe
                    "Live account binding failed closed: execution journal has order history without a trusted account binding."
            }
        }

        "initial binding blocked by unresolved intent" {
            withTempDirectory { directory ->
                val exchange = exchange(ACCOUNT_A, GENERATION_A)
                val environment = openEnvironment(directory, exchange)
                environment.intentRepository.savePending(testIntent())

                val ex = shouldThrow<IllegalStateException> {
                    environment.bindingService.ensureVerifiedForSubmission()
                }
                ex.message shouldBe
                    "Live account binding failed closed: unresolved execution intents prevent initial account binding."
            }
        }

        "credential rotation blocked by unresolved intent" {
            withTempDirectory { directory ->
                val exchange = exchange(ACCOUNT_A, GENERATION_A)
                val environment = openEnvironment(directory, exchange)
                environment.bindingService.ensureVerifiedForSubmission()

                environment.intentRepository.savePending(testIntent())
                exchange.fundingEvidenceScopeSupplier = { GENERATION_B }

                val ex = shouldThrow<IllegalStateException> {
                    environment.bindingService.ensureVerifiedForSubmission()
                }
                ex.message shouldBe
                    "Live account binding failed closed: unresolved execution intents prevent credential rotation."
            }
        }
    }

    private suspend fun openEnvironment(
        directory: Path,
        exchange: FakeKrakenService,
        dataDirectoryIdentity: DataDirectoryIdentity = DataDirectoryIdentity(directory) { false },
        customConfig: AppConfig? = null,
    ): Environment {
        val executionPath = directory.resolve("execution.db")
        val database = ExecutionDatabase.initialize(executionPath.toString())
        val intentRepository = SqliteExecutionOrderIntentRepositoryImpl(database)
        ExecutionJournalBootstrap(database, intentRepository).initialize()
        val bindingRepository = SqliteExecutionAccountBindingRepositoryImpl(database)
        val configService = mockk<ConfigService>()
        val config = customConfig ?: TestFixtures.DEFAULT_TEST_CONFIG.copy(
            kraken = KrakenCredentials(
                TestConstants.API_KEY,
                TestFixtures.KRAKEN_TEST_API_SECRET,
            ),
            settings = TestFixtures.settings(dryRun = false, simulation = false),
        )
        every { configService.getConfig() } returns config
        coEvery { configService.beginExecutionSession() } returns Unit
        coEvery { configService.endExecutionSession() } returns Unit
        val bindingService = ExecutionAccountBindingService(
            database = database,
            bindingRepository = bindingRepository,
            krakenService = exchange,
            configService = configService,
            dataDirectoryIdentity = dataDirectoryIdentity,
        )
        return Environment(
            executionPath,
            bindingRepository,
            bindingService,
            exchange,
            dataDirectoryIdentity,
            intentRepository,
            database,
        )
    }

    private fun exchange(accountIdentity: String, credentialGeneration: String) = FakeKrakenService().apply {
        fundingEvidenceScopeSupplier = { credentialGeneration }
        authenticatedAccountIdentitySupplier = { accountIdentity }
    }

    private fun scalar(databasePath: Path, query: String): Int =
        java.sql.DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(query).use { resultSet ->
                    check(resultSet.next()) { "Expected a SQLite scalar result." }
                    resultSet.getInt(1)
                }
            }
        }

    private fun execute(databasePath: Path, sql: String) {
        java.sql.DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(sql)
            }
        }
    }

    private fun testIntent(clientOrderId: String = "client-order-1") = OrderIntent(
        cycleId = "cycle-1",
        clientOrderId = clientOrderId,
        clientOrderIdAmbiguous = true,
        pair = "XBTUSD",
        symbol = Asset.BTC,
        side = "BUY",
        volume = BigDecimal("0.02500000"),
        usdAmount = BigDecimal("25.00"),
        expectedPrice = BigDecimal("1000.00"),
        createdAt = Instant.parse("2026-10-08T12:00:00Z"),
        state = OrderIntentState.PENDING,
    )

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun identityDigest(value: String): String = digest(value.filterNot(Char::isWhitespace).uppercase())

    private fun withTempDirectory(block: suspend (Path) -> Unit) = runTest {
        val directory = Files.createTempDirectory("execution-account-binding-")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private data class Environment(
        val executionPath: Path,
        val bindingRepository: ExecutionAccountBindingRepository,
        val bindingService: ExecutionAccountBindingService,
        val exchange: FakeKrakenService,
        val dataDirectoryIdentity: DataDirectoryIdentity,
        val intentRepository: SqliteExecutionOrderIntentRepositoryImpl,
        val database: ExecutionDatabase,
    )

    private companion object {
        const val ACCOUNT_A = "AA00 TEST ACCOUNT A"
        const val ACCOUNT_B = "AA00 TEST ACCOUNT B"
        const val GENERATION_A = "credential-generation-a"
        const val GENERATION_B = "credential-generation-b"
        const val GENERATION_C = "credential-generation-c"
    }
}
