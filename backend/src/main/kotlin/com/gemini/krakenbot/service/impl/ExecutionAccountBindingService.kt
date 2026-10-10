package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.ExecutionDatabase
import com.gemini.krakenbot.repository.ExecutionAccountBinding
import com.gemini.krakenbot.repository.ExecutionAccountBindingRepository
import com.gemini.krakenbot.repository.ExecutionAccountBindingWriteResult
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.ExecutionAccountBindingVerifier
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.impl.history.AccountHistoryScopeGuard
import com.gemini.krakenbot.service.impl.history.AccountScopeValidationStatus
import com.gemini.krakenbot.service.withExecutionSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.Locale

/** Authenticates the Kraken account once per credential generation and stores its binding in the execution journal. */
class ExecutionAccountBindingService(
    private val database: ExecutionDatabase,
    private val bindingRepository: ExecutionAccountBindingRepository,
    private val krakenService: KrakenService,
    private val configService: ConfigService,
    private val accountHistoryScopeGuard: AccountHistoryScopeGuard,
) : ExecutionAccountBindingVerifier {
    private val log = LoggerFactory.getLogger(ExecutionAccountBindingService::class.java)
    private val verificationMutex = Mutex()
    private val initialProofs = mutableMapOf<String, InitialAccountProof>()
    private val failedInitialProofs = mutableMapOf<String, String>()

    override suspend fun ensureVerifiedForSubmission() = verificationMutex.withLock {
        configService.withExecutionSession {
            database.ensureReadyForSubmission()
            val config = configService.getConfig()
            check(!config.settings.simulation && !config.settings.dryRun) {
                "Execution account binding is required only for live submissions."
            }
            check(config.kraken.hasValidCredentials()) {
                "Live account binding is unavailable: credentials are missing."
            }
            krakenService.withStableBackend { backend ->
                val credentialGenerationDigest = digest(
                    backend.getFundingEvidenceScope().trim().takeUnless { it.isBlank() || it == "scope-unavailable" }
                        ?: fail("credential generation is unavailable"),
                )
                val current = bindingRepository.load()
                if (current == null) {
                    if (bindingRepository.hasBindingAnchor()) {
                        fail("execution account binding record is missing despite durable binding history")
                    } else {
                        bindInitialAccount(backend, credentialGenerationDigest)
                    }
                } else {
                    verifyExistingBinding(current)
                    if (current.credentialGenerationDigest != credentialGenerationDigest) {
                        verifyCredentialRotation(backend, current, credentialGenerationDigest)
                    }
                }
            }
        }
    }

    /**
     * Returns the already-bound account digest for Actual observations. It only reads an existing,
     * verified binding whose credential generation matches the captured evidence scope. Live mode
     * may establish the same binding required by a future order, but that safety procedure runs in
     * the background worker. Once bound, observation capture performs no extra authenticated call.
     */
    suspend fun verifiedAccountIdentityDigestForObservation(expectedFundingEvidenceScope: String): String? {
        val initialConfig = configService.getConfig()
        if (initialConfig.settings.simulation || !initialConfig.kraken.hasValidCredentials()) return null
        try {
            if (!initialConfig.settings.dryRun) ensureVerifiedForSubmission()
            return configService.withExecutionSession {
                val config = configService.getConfig()
                if (config.settings.simulation || !config.kraken.hasValidCredentials()) return@withExecutionSession null
                krakenService.withStableBackend { backend ->
                    val currentScope = backend.getFundingEvidenceScope().trim()
                    if (currentScope.isBlank() || currentScope != expectedFundingEvidenceScope) {
                        return@withStableBackend null
                    }
                    val binding = bindingRepository.load() ?: return@withStableBackend null
                    verifyExistingBinding(binding)
                    if (binding.credentialGenerationDigest != digest(currentScope)) {
                        log.warn(
                            "Skipping Actual observation because credentials do not match the verified journal binding",
                        )
                        null
                    } else {
                        binding.accountIdentityDigest
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Skipping Actual observation because account identity could not be verified: {}", e.message)
            return null
        }
    }

    private suspend fun bindInitialAccount(backend: KrakenService, credentialGenerationDigest: String) {
        failedInitialProofs[credentialGenerationDigest]?.let { reason ->
            fail(
                "legacy account-scope verification already failed for this credential generation ($reason); " +
                    "restart after correcting credentials or reporting evidence to retry",
            )
        }
        val proof =
            initialProofs[credentialGenerationDigest] ?: verifyInitialAccount(backend, credentialGenerationDigest)

        val now = System.currentTimeMillis()
        val initial = ExecutionAccountBinding(
            accountIdentityDigest = proof.accountIdentityDigest,
            credentialGenerationDigest = credentialGenerationDigest,
            bindingVersion = CURRENT_BINDING_VERSION,
            verificationMethod = LEGACY_BINDING_METHOD,
            verifiedAtMillis = now,
            auditVerified = true,
        )
        when (bindingRepository.createIfPristine(initial)) {
            ExecutionAccountBindingWriteResult.CREATED -> log.info(
                "Verified live Kraken account binding for this journal",
            )

            ExecutionAccountBindingWriteResult.ALREADY_PRESENT -> {
                val raced = bindingRepository.load()
                if (raced == null || raced.accountIdentityDigest != proof.accountIdentityDigest ||
                    raced.credentialGenerationDigest != credentialGenerationDigest
                ) {
                    fail("execution account binding changed during verification")
                }
                verifyExistingBinding(raced)
            }

            ExecutionAccountBindingWriteResult.BLOCKED_BY_LIVE_ORDER_HISTORY ->
                fail("execution journal has live-order history without a trusted account binding")

            ExecutionAccountBindingWriteResult.BLOCKED_BY_UNRESOLVED_INTENT ->
                fail("unresolved execution intents prevent initial account binding")

            ExecutionAccountBindingWriteResult.BLOCKED_BY_PRIOR_BINDING_EVIDENCE ->
                fail("execution account binding evidence is incomplete; refusing to replace it")

            ExecutionAccountBindingWriteResult.UPDATED,
            ExecutionAccountBindingWriteResult.STALE_BINDING,
            -> fail("unexpected execution account binding state")
        }
    }

    private suspend fun verifyInitialAccount(
        backend: KrakenService,
        credentialGenerationDigest: String,
    ): InitialAccountProof {
        val accountIdentityDigest = authenticatedAccountIdentityDigest(backend)
        val legacyResult = try {
            accountHistoryScopeGuard.validateAccountScope()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedInitialProofs[credentialGenerationDigest] = "legacy account history could not be verified"
            throw IllegalStateException(
                "Live account binding failed closed: legacy account history could not be verified; " +
                    "restart after correcting reporting evidence to retry.",
                e,
            )
        }
        if (legacyResult.status != AccountScopeValidationStatus.VALID) {
            val reason = legacyResult.reason
                ?: "legacy account history could not be verified (${legacyResult.status.name.lowercase()})"
            failedInitialProofs[credentialGenerationDigest] = reason
            fail(reason)
        }
        return InitialAccountProof(accountIdentityDigest).also { initialProofs[credentialGenerationDigest] = it }
    }

    private suspend fun verifyCredentialRotation(
        backend: KrakenService,
        current: ExecutionAccountBinding,
        credentialGenerationDigest: String,
    ) {
        val accountIdentityDigest = authenticatedAccountIdentityDigest(backend)
        if (accountIdentityDigest != current.accountIdentityDigest) {
            fail("configured Kraken account does not match this execution journal's bound account")
        }
        val updated = current.copy(
            credentialGenerationDigest = credentialGenerationDigest,
            verificationMethod = ROTATION_BINDING_METHOD,
            verifiedAtMillis = System.currentTimeMillis(),
            auditVerified = true,
        )
        when (
            bindingRepository.updateCredentialGeneration(
                expectedAccountIdentityDigest = current.accountIdentityDigest,
                expectedCredentialGenerationDigest = current.credentialGenerationDigest,
                updated = updated,
            )
        ) {
            ExecutionAccountBindingWriteResult.UPDATED -> log.info(
                "Verified rotated Kraken credentials against the execution journal account binding",
            )

            ExecutionAccountBindingWriteResult.BLOCKED_BY_UNRESOLVED_INTENT ->
                fail("unresolved execution intents prevent credential rotation")

            ExecutionAccountBindingWriteResult.STALE_BINDING -> {
                val raced = bindingRepository.load()
                if (raced == null || raced.accountIdentityDigest != accountIdentityDigest ||
                    raced.credentialGenerationDigest != credentialGenerationDigest
                ) {
                    fail("execution account binding changed during credential verification")
                }
                verifyExistingBinding(raced)
            }

            ExecutionAccountBindingWriteResult.CREATED,
            ExecutionAccountBindingWriteResult.ALREADY_PRESENT,
            ExecutionAccountBindingWriteResult.BLOCKED_BY_LIVE_ORDER_HISTORY,
            ExecutionAccountBindingWriteResult.BLOCKED_BY_PRIOR_BINDING_EVIDENCE,
            -> fail("unexpected execution account credential rotation state")
        }
    }

    private fun verifyExistingBinding(binding: ExecutionAccountBinding) {
        check(binding.auditVerified) { "Execution account binding audit evidence is missing or corrupt." }
        check(binding.bindingVersion == CURRENT_BINDING_VERSION) {
            "Unsupported execution account binding version ${binding.bindingVersion}; live orders are blocked."
        }
        check(isDigest(binding.accountIdentityDigest) && isDigest(binding.credentialGenerationDigest)) {
            "Execution account binding is corrupt; live orders are blocked."
        }
        check(binding.verificationMethod in setOf(LEGACY_BINDING_METHOD, ROTATION_BINDING_METHOD)) {
            "Execution account binding proof is unrecognized; live orders are blocked."
        }
    }

    private suspend fun authenticatedAccountIdentityDigest(backend: KrakenService): String {
        val identity = try {
            backend.getAuthenticatedAccountIdentity()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(
                "Live account binding could not read Kraken's authenticated account identity.",
                e,
            )
        }
        val canonicalIdentity = identity
            ?.filterNot(Char::isWhitespace)
            ?.uppercase(Locale.ROOT)
            ?.takeIf(String::isNotBlank)
            ?: fail("Kraken did not return an authenticated account identity")
        return digest(canonicalIdentity)
    }

    private fun fail(reason: String): Nothing =
        throw IllegalStateException("Live account binding failed closed: $reason.")

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun isDigest(value: String): Boolean = value.matches(HEX_DIGEST)

    private data class InitialAccountProof(val accountIdentityDigest: String)

    private companion object {
        const val CURRENT_BINDING_VERSION = 1
        const val LEGACY_BINDING_METHOD = "legacy-scope-guard-plus-kraken-iiban"
        const val ROTATION_BINDING_METHOD = "kraken-iiban-credential-rotation"
        val HEX_DIGEST = Regex("[0-9a-f]{64}")
    }
}
