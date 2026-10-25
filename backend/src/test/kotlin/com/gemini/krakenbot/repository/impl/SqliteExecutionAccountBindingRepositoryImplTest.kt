@file:Suppress("unused")

package com.gemini.krakenbot.repository.impl

import com.gemini.krakenbot.config.ExecutionDatabase
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.repository.ExecutionAccountBinding
import com.gemini.krakenbot.repository.ExecutionAccountBindingWriteResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Instant

class SqliteExecutionAccountBindingRepositoryImplTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val validHashA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val validHashB = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
    private val validHashC = "1111111111111111111111111111111111111111111111111111111111111111"

    init {
        "load returns null on pristine database and hasBindingAnchor returns false" {
            runTest {
                withRepository { _, repo ->
                    repo.load() shouldBe null
                    repo.hasBindingAnchor() shouldBe false
                }
            }
        }

        "createIfPristine succeeds and sets anchor, subsequent create returns ALREADY_PRESENT" {
            runTest {
                withRepository { _, repo ->
                    val binding = testBinding()
                    repo.createIfPristine(binding) shouldBe ExecutionAccountBindingWriteResult.CREATED
                    repo.hasBindingAnchor() shouldBe true
                    val loaded = repo.load()
                    loaded shouldBe binding.copy(auditVerified = true)
                    repo.createIfPristine(binding) shouldBe ExecutionAccountBindingWriteResult.ALREADY_PRESENT
                }
            }
        }

        "createIfPristine returns BLOCKED_BY_PRIOR_BINDING_EVIDENCE when anchor is true but binding is deleted" {
            runTest {
                withRepository { database, repo ->
                    repo.createIfPristine(testBinding()) shouldBe ExecutionAccountBindingWriteResult.CREATED
                    execute(database, "DELETE FROM execution_account_binding")
                    repo.createIfPristine(testBinding()) shouldBe
                        ExecutionAccountBindingWriteResult.BLOCKED_BY_PRIOR_BINDING_EVIDENCE
                }
            }
        }

        "createIfPristine returns BLOCKED_BY_PRIOR_BINDING_EVIDENCE when audit rows exist but binding is deleted" {
            runTest {
                withRepository { database, repo ->
                    execute(
                        database,
                        "INSERT INTO execution_account_binding_audit(account_identity_digest, " +
                            "previous_credential_generation_digest, " +
                            "credential_generation_digest, verification_method, verified_at) " +
                            "VALUES ('$validHashA', null, '$validHashB', 'manual', 1000)",
                    )
                    repo.createIfPristine(testBinding()) shouldBe
                        ExecutionAccountBindingWriteResult.BLOCKED_BY_PRIOR_BINDING_EVIDENCE
                }
            }
        }

        "createIfPristine returns BLOCKED_BY_UNRESOLVED_INTENT when pending intent exists" {
            runTest {
                withRepository { database, repo ->
                    val intentRepo = SqliteExecutionOrderIntentRepositoryImpl(database)
                    intentRepo.savePending(testIntent())
                    repo.createIfPristine(testBinding()) shouldBe
                        ExecutionAccountBindingWriteResult.BLOCKED_BY_UNRESOLVED_INTENT
                }
            }
        }

        "createIfPristine returns BLOCKED_BY_PRIOR_ORDER_HISTORY when confirmed intent exists" {
            runTest {
                withRepository { database, repo ->
                    val intentRepo = SqliteExecutionOrderIntentRepositoryImpl(database)
                    val id = intentRepo.savePending(testIntent())
                    intentRepo.recordOutcome(
                        id = id,
                        state = OrderIntentState.CONFIRMED,
                        orderTxid = "tx-1",
                        errorMessage = null,
                        resolvedAt = Instant.now(),
                        outcomeVolume = BigDecimal("0.025"),
                    )
                    repo.createIfPristine(testBinding()) shouldBe
                        ExecutionAccountBindingWriteResult.BLOCKED_BY_PRIOR_ORDER_HISTORY
                }
            }
        }

        "updateCredentialGeneration returns BLOCKED_BY_UNRESOLVED_INTENT when pending intent exists" {
            runTest {
                withRepository { database, repo ->
                    repo.createIfPristine(testBinding()) shouldBe ExecutionAccountBindingWriteResult.CREATED
                    val intentRepo = SqliteExecutionOrderIntentRepositoryImpl(database)
                    intentRepo.savePending(testIntent())
                    repo.updateCredentialGeneration(
                        expectedAccountIdentityDigest = validHashA,
                        expectedCredentialGenerationDigest = validHashB,
                        updated = testBinding().copy(credentialGenerationDigest = validHashC),
                    ) shouldBe ExecutionAccountBindingWriteResult.BLOCKED_BY_UNRESOLVED_INTENT
                }
            }
        }

        "updateCredentialGeneration returns STALE_BINDING when current binding is missing" {
            runTest {
                withRepository { _, repo ->
                    repo.updateCredentialGeneration(
                        expectedAccountIdentityDigest = validHashA,
                        expectedCredentialGenerationDigest = validHashB,
                        updated = testBinding().copy(credentialGenerationDigest = validHashC),
                    ) shouldBe ExecutionAccountBindingWriteResult.STALE_BINDING
                }
            }
        }

        "updateCredentialGeneration returns STALE_BINDING when current binding auditVerified is false" {
            runTest {
                withRepository { database, repo ->
                    repo.createIfPristine(testBinding()) shouldBe ExecutionAccountBindingWriteResult.CREATED
                    execute(database, "DELETE FROM execution_account_binding_audit")
                    repo.updateCredentialGeneration(
                        expectedAccountIdentityDigest = validHashA,
                        expectedCredentialGenerationDigest = validHashB,
                        updated = testBinding().copy(credentialGenerationDigest = validHashC),
                    ) shouldBe ExecutionAccountBindingWriteResult.STALE_BINDING
                }
            }
        }

        "updateCredentialGeneration returns STALE_BINDING when expected digests mismatch" {
            runTest {
                withRepository { _, repo ->
                    repo.createIfPristine(testBinding()) shouldBe ExecutionAccountBindingWriteResult.CREATED
                    repo.updateCredentialGeneration(
                        expectedAccountIdentityDigest = "mismatched-account",
                        expectedCredentialGenerationDigest = validHashB,
                        updated = testBinding().copy(credentialGenerationDigest = validHashC),
                    ) shouldBe ExecutionAccountBindingWriteResult.STALE_BINDING

                    repo.updateCredentialGeneration(
                        expectedAccountIdentityDigest = validHashA,
                        expectedCredentialGenerationDigest = "mismatched-cred",
                        updated = testBinding().copy(credentialGenerationDigest = validHashC),
                    ) shouldBe ExecutionAccountBindingWriteResult.STALE_BINDING
                }
            }
        }

        "updateCredentialGeneration returns STALE_BINDING when updateCount is 0" {
            runTest {
                withRepository { _, repo ->
                    repo.createIfPristine(testBinding()) shouldBe ExecutionAccountBindingWriteResult.CREATED
                    // Version mismatch in WHERE clause (binding in db has version 1, updated has version 99)
                    repo.updateCredentialGeneration(
                        expectedAccountIdentityDigest = validHashA,
                        expectedCredentialGenerationDigest = validHashB,
                        updated = testBinding().copy(credentialGenerationDigest = validHashC, bindingVersion = 99),
                    ) shouldBe ExecutionAccountBindingWriteResult.STALE_BINDING
                }
            }
        }

        "updateCredentialGeneration succeeds when expected values match, returning UPDATED and inserting audit" {
            runTest {
                withRepository { database, repo ->
                    repo.createIfPristine(testBinding()) shouldBe ExecutionAccountBindingWriteResult.CREATED
                    val updatedBinding = testBinding().copy(
                        credentialGenerationDigest = validHashC,
                        verificationMethod = "kraken-iiban-credential-rotation",
                        verifiedAtMillis = 2000L,
                    )
                    repo.updateCredentialGeneration(
                        expectedAccountIdentityDigest = validHashA,
                        expectedCredentialGenerationDigest = validHashB,
                        updated = updatedBinding,
                    ) shouldBe ExecutionAccountBindingWriteResult.UPDATED

                    val loaded = repo.load()
                    loaded shouldBe updatedBinding.copy(auditVerified = true)

                    database.connect().use { conn ->
                        conn.createStatement().use { stmt ->
                            stmt.executeQuery("SELECT COUNT(*) FROM execution_account_binding_audit").use { rs ->
                                rs.next() shouldBe true
                                rs.getInt(1) shouldBe 2
                            }
                        }
                    }
                }
            }
        }

        "bindingAnchor throws when anchor in database is missing" {
            runTest {
                withRepository { database, repo ->
                    execute(database, "DELETE FROM execution_journal_metadata")
                    val ex = shouldThrow<IllegalStateException> { repo.hasBindingAnchor() }
                    ex.message shouldBe "Execution journal account binding anchor is missing."
                }
            }
        }

        "inTransaction throws IllegalStateException when operation fails" {
            runTest {
                withRepository { database, repo ->
                    // Drop metadata table so createIfPristine fails during update of execution_journal_metadata
                    execute(database, "DROP TABLE execution_journal_metadata")
                    val ex = shouldThrow<IllegalStateException> {
                        repo.createIfPristine(testBinding())
                    }
                    ex.message shouldBe "Could not create execution account binding in the execution journal."
                }
            }
        }
    }

    private fun testBinding() = ExecutionAccountBinding(
        accountIdentityDigest = validHashA,
        credentialGenerationDigest = validHashB,
        bindingVersion = 1,
        verificationMethod = "kraken-iiban-initial-binding",
        verifiedAtMillis = 1000L,
        auditVerified = false,
    )

    private fun testIntent() = OrderIntent(
        cycleId = "cycle-1",
        clientOrderId = "client-order-1",
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

    private fun execute(database: ExecutionDatabase, sql: String) {
        database.connect().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(sql)
            }
        }
    }

    private suspend fun withRepository(
        block: suspend (ExecutionDatabase, SqliteExecutionAccountBindingRepositoryImpl) -> Unit,
    ) {
        val directory = withContext(Dispatchers.IO) {
            Files.createTempDirectory("execution-binding-repo-")
        }
        try {
            val database = ExecutionDatabase.initialize(directory.resolve("execution.db").toString())
            database.markReadyForSubmission()
            block(database, SqliteExecutionAccountBindingRepositoryImpl(database))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
