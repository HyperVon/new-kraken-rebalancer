package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.withExecutionSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.security.MessageDigest

enum class AccountScopeValidationStatus {
    VALID,
    SCOPE_UNAVAILABLE,
    SCOPE_MISMATCH,
    UNBOUND_EXISTING_HISTORY,
    VALIDATION_PENDING,
    SIMULATION,
}

class AccountScopeValidationResult(
    val status: AccountScopeValidationStatus,
    val reason: String? = null,
    val currentScopeDigest: String? = null,
) {
    val isValid: Boolean get() = status == AccountScopeValidationStatus.VALID ||
        status == AccountScopeValidationStatus.SIMULATION

    companion object {
        val VALID = AccountScopeValidationResult(AccountScopeValidationStatus.VALID)
        val SIMULATION = AccountScopeValidationResult(AccountScopeValidationStatus.SIMULATION)

        fun scopeUnavailable(reason: String = "account scope unavailable") =
            AccountScopeValidationResult(AccountScopeValidationStatus.SCOPE_UNAVAILABLE, reason)

        fun scopeMismatch(current: String, reason: String = "account scope changed; use correct DB or perform reset") =
            AccountScopeValidationResult(
                AccountScopeValidationStatus.SCOPE_MISMATCH,
                reason = reason,
                currentScopeDigest = current,
            )

        fun unboundExistingHistory(reason: String = "existing history cannot be verified for active credentials") =
            AccountScopeValidationResult(AccountScopeValidationStatus.UNBOUND_EXISTING_HISTORY, reason)
    }
}

class AccountHistoryScopeGuard(
    private val krakenService: KrakenService,
    private val tradeRepository: TradeRepository,
    private val ledgerRepository: LedgerRepository,
    private val configService: ConfigService,
    private val continuityVerifier: AccountHistoryContinuityVerifier = AccountHistoryContinuityVerifier(
        krakenService,
        tradeRepository,
        ledgerRepository,
    ),
) {
    private val log = LoggerFactory.getLogger(AccountHistoryScopeGuard::class.java)
    private val validationMutex = Mutex()

    suspend fun validateAccountScope(): AccountScopeValidationResult = validationMutex.withLock {
        // Lock ordering: validationMutex -> execution session (configLock, held
        // only briefly for depth/staging bookkeeping) -> backend pin.
        // updateConfig takes configLock but never validationMutex, so no lock
        // cycle is possible. The session freezes getConfig() for everything
        // below — starting with the simulation/credential preflight, so no
        // update can slip in between the first trust-relevant read and the
        // session start: initial scope derivation, credential probes, every
        // TradesHistory/Ledgers window, the pre-write recheck, and persistence
        // all observe exactly one credential generation — which also closes
        // A -> B -> A flips mid-proof. readLocalTrustState stays session-free
        // and try-locked so History rendering never blocks on this path.
        return@withLock configService.withExecutionSession {
            val pinnedConfig = configService.getConfig()
            if (pinnedConfig.settings.simulation) {
                return@withExecutionSession AccountScopeValidationResult.SIMULATION
            }
            if (!pinnedConfig.kraken.hasValidCredentials()) {
                return@withExecutionSession AccountScopeValidationResult.scopeUnavailable("credentials unavailable")
            }
            krakenService.withStableBackend { validatePinned() }
        }
    }

    /**
     * Runs under one execution session plus one pinned backend: every
     * credential observable here belongs to a single generation.
     *
     * Branching is deliberately version-first. An old/unversioned binding
     * carries no trusted lineage, so it must never reach the lightweight
     * rotation proof below — not even when the fingerprint changed (a
     * same-generation-looking rotation on weak lineage proves nothing about
     * the rest of the history). Only CURRENT-version lineage qualifies for
     * the one-hit rotation path.
     */
    private suspend fun validatePinned(): AccountScopeValidationResult {
        val accountScope = try {
            krakenService.getFundingEvidenceScope().trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Unable to read account scope", e)
            ""
        }
        if (accountScope.isBlank() || accountScope == "scope-unavailable") {
            return AccountScopeValidationResult.scopeUnavailable("account scope unavailable")
        }

        val scopeDigest = digestAccountScope(accountScope)
        val storedScopeDigest = tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST)?.trim()
        val storedVersion = tradeRepository.getSyncMetadata(
            SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
        )?.trim()

        if (!storedScopeDigest.isNullOrBlank() && storedVersion != CURRENT_BINDING_VERSION) {
            return revalidateOldBinding(scopeDigest)
        }

        if (!storedScopeDigest.isNullOrBlank()) {
            if (storedScopeDigest == scopeDigest) {
                return AccountScopeValidationResult(
                    AccountScopeValidationStatus.VALID,
                    currentScopeDigest = scopeDigest,
                )
            }
            if (isDatabaseFinanciallyEmpty()) {
                // Bound but financially empty: no history exists to protect, so
                // authenticated current credentials may safely replace the stale
                // binding instead of stranding the database in mismatch.
                if (!verifyCredentialsActive()) {
                    return AccountScopeValidationResult.scopeUnavailable(
                        "credentials could not be verified against Kraken",
                    )
                }
                return writeBinding(scopeDigest, "Rebound empty database to Kraken account scope digest {}")
            }
            // The scope digest is credential-derived, so a key rotation on the same
            // account also presents as a mismatch. This database already carries
            // trusted CURRENT-version lineage, so one exact authoritative identity
            // visible through the new credentials re-establishes continuity; a
            // different account whose history merely shares this database stays
            // locked out.
            return when (continuityVerifier.verifyContinuity()) {
                AccountHistoryContinuityStatus.VERIFIED ->
                    writeBinding(scopeDigest, "Rebound database to rotated Kraken account scope digest {}")

                AccountHistoryContinuityStatus.NO_OVERLAP ->
                    AccountScopeValidationResult.scopeMismatch(scopeDigest)

                // An incomplete search is unproven, not absent: keep the previous
                // binding and fail closed without rebinding.
                AccountHistoryContinuityStatus.INCOMPLETE ->
                    AccountScopeValidationResult.scopeUnavailable("account continuity search incomplete")

                AccountHistoryContinuityStatus.CONFLICT ->
                    AccountScopeValidationResult.scopeMismatch(scopeDigest)

                AccountHistoryContinuityStatus.UNAVAILABLE ->
                    AccountScopeValidationResult.scopeUnavailable("unable to verify account continuity")
            }
        }

        // No stored digest: verify whether the financial history is empty.
        if (isDatabaseFinanciallyEmpty()) {
            // Bind an empty database only after the credentials prove live: an
            // unauthenticated scope hash would otherwise bind (or poison) a fresh
            // database on unvalidated secrets.
            if (!verifyCredentialsActive()) {
                return AccountScopeValidationResult.scopeUnavailable(
                    "credentials could not be verified against Kraken",
                )
            }
            return writeBinding(scopeDigest, "Bound empty database to initial Kraken account scope digest {}")
        }

        return resolveUnboundDatabase(scopeDigest)
    }

    /**
     * Revalidates a binding written under an older proof contract, regardless of
     * whether the fingerprint matches. Same-generation equality proves nothing
     * about lineage strength: the old proof may have bound on a single row of
     * mixed history. Empty bindings upgrade after authentication; non-empty
     * history must pass the full legacy consistency proof again, binding the
     * CURRENT fingerprint only on success. Failure retains the old digest and
     * version untouched.
     */
    private suspend fun revalidateOldBinding(scopeDigest: String): AccountScopeValidationResult {
        if (isDatabaseFinanciallyEmpty()) {
            if (!verifyCredentialsActive()) {
                return AccountScopeValidationResult.scopeUnavailable(
                    "credentials could not be verified against Kraken",
                )
            }
            return writeBinding(scopeDigest, "Upgraded empty account binding to contract version {}")
        }
        return when (continuityVerifier.verifyLegacyConsistency()) {
            AccountHistoryContinuityStatus.VERIFIED ->
                writeBinding(scopeDigest, "Revalidated account binding under contract version {}")

            AccountHistoryContinuityStatus.CONFLICT ->
                AccountScopeValidationResult.unboundExistingHistory(
                    "account history is inconsistent with the active account",
                )

            AccountHistoryContinuityStatus.NO_OVERLAP ->
                AccountScopeValidationResult.unboundExistingHistory()

            AccountHistoryContinuityStatus.INCOMPLETE ->
                AccountScopeValidationResult.scopeUnavailable("account continuity search incomplete")

            AccountHistoryContinuityStatus.UNAVAILABLE ->
                AccountScopeValidationResult.scopeUnavailable("unable to verify account continuity")
        }
    }

    /**
     * Synchronous local trust read for request-path callers (History rendering).
     * Compares the locally computed credential-generation fingerprint against the
     * durable binding without any Kraken history call: no continuity proof, no
     * balance probe, no binding write — and no waiting behind a network-bound
     * validation either. If [validationMutex] is held by an in-flight proof,
     * this returns [AccountScopeValidationStatus.VALIDATION_PENDING]
     * immediately so the HTTP request fails closed instead of stalling.
     * A matching current-contract fingerprint reuses the previous durable
     * verdict; anything else fails closed as mismatch, unbound, or pending
     * until background validation ([validateAccountScope]) proves it.
     * Equal fingerprints never require network; changed fingerprints are never
     * locally VALID, so there is no window where credentials B is trusted on a
     * database bound to A just because no verifier has run yet.
     */
    suspend fun readLocalTrustState(): AccountScopeValidationResult {
        if (!validationMutex.tryLock()) {
            return AccountScopeValidationResult(
                AccountScopeValidationStatus.VALIDATION_PENDING,
                "account validation in progress",
            )
        }
        try {
            val config = configService.getConfig()
            if (config.settings.simulation) {
                return AccountScopeValidationResult.SIMULATION
            }
            if (!config.kraken.hasValidCredentials()) {
                return AccountScopeValidationResult.scopeUnavailable("credentials unavailable")
            }

            val accountScope = try {
                krakenService.getFundingEvidenceScope().trim()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Unable to read account scope", e)
                ""
            }
            if (accountScope.isBlank() || accountScope == "scope-unavailable") {
                return AccountScopeValidationResult.scopeUnavailable("account scope unavailable")
            }

            val scopeDigest = digestAccountScope(accountScope)
            val storedScopeDigest =
                tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST)?.trim()
            val storedVersion = tradeRepository.getSyncMetadata(
                SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
            )?.trim()
            if (!storedScopeDigest.isNullOrBlank()) {
                if (storedScopeDigest == scopeDigest && storedVersion == CURRENT_BINDING_VERSION) {
                    return AccountScopeValidationResult(
                        AccountScopeValidationStatus.VALID,
                        currentScopeDigest = scopeDigest,
                    )
                }
                if (storedScopeDigest == scopeDigest) {
                    // Same fingerprint under an older contract: background
                    // validation must revalidate once before trust resumes.
                    return AccountScopeValidationResult(
                        AccountScopeValidationStatus.VALIDATION_PENDING,
                        "account binding revalidation pending",
                    )
                }
                return AccountScopeValidationResult.scopeMismatch(scopeDigest)
            }
            if (!isDatabaseFinanciallyEmpty()) {
                return AccountScopeValidationResult.unboundExistingHistory()
            }
            return AccountScopeValidationResult(
                AccountScopeValidationStatus.VALIDATION_PENDING,
                "account validation pending",
            )
        } finally {
            validationMutex.unlock()
        }
    }

    /**
     * Binds an unscoped legacy database only when the full consistency proof
     * shows the active credentials own the sampled lifetime — never on a
     * single overlapping row, which could otherwise bind over undetected mixed-account history.
     * Conflict, absence, outage, and incomplete searches all fail closed.
     */
    private suspend fun resolveUnboundDatabase(scopeDigest: String): AccountScopeValidationResult =
        when (continuityVerifier.verifyLegacyConsistency()) {
            AccountHistoryContinuityStatus.VERIFIED ->
                writeBinding(
                    scopeDigest,
                    "Bound upgraded database to Kraken account scope digest {} after continuity proof",
                )

            AccountHistoryContinuityStatus.CONFLICT -> {
                log.warn("Upgraded database history is inconsistent with the active account; refusing to bind")
                AccountScopeValidationResult.unboundExistingHistory(
                    "account history is inconsistent with the active account",
                )
            }

            AccountHistoryContinuityStatus.NO_OVERLAP -> {
                log.warn("Upgraded database has existing financial history but no account scope binding")
                AccountScopeValidationResult.unboundExistingHistory()
            }

            AccountHistoryContinuityStatus.INCOMPLETE ->
                AccountScopeValidationResult.scopeUnavailable("account continuity search incomplete")

            AccountHistoryContinuityStatus.UNAVAILABLE ->
                AccountScopeValidationResult.scopeUnavailable("unable to verify account continuity")
        }

    /**
     * Proof first, then the binding write: the new digest is claimed VALID only
     * when it is durably re-readable, so a failed write (or a crash during the
     * proof) always retains the previous binding. The current fingerprint is
     * re-derived immediately before writing: if the configured credentials
     * changed while the proof was in flight, the proven digest no longer
     * describes the active generation and nothing is written.
     */
    private suspend fun writeBinding(provenDigest: String, logMessage: String): AccountScopeValidationResult {
        val current = currentScopeDigest()
        if (current == null || current != provenDigest) {
            log.warn("Account credentials changed during continuity verification; retaining previous binding")
            return AccountScopeValidationResult.scopeUnavailable("account scope changed during verification")
        }
        tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST, provenDigest)
        tradeRepository.setSyncMetadata(
            SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
            CURRENT_BINDING_VERSION,
        )
        val stored = tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST)?.trim()
        val storedVersion = tradeRepository.getSyncMetadata(
            SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_BINDING_VERSION,
        )?.trim()
        if (stored != provenDigest || storedVersion != CURRENT_BINDING_VERSION) {
            log.warn("Account scope binding write unverified; retaining previous binding")
            return AccountScopeValidationResult.scopeUnavailable("account scope binding write unverified")
        }
        log.info(logMessage, provenDigest)
        return AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = provenDigest)
    }

    /**
     * Local credential-generation fingerprint, or null when it cannot be read.
     * Pure configuration hash — no Kraken history call.
     */
    private suspend fun currentScopeDigest(): String? = try {
        val scope = krakenService.getFundingEvidenceScope().trim()
        if (scope.isBlank() || scope == "scope-unavailable") null else digestAccountScope(scope)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Unable to read account scope", e)
        null
    }

    private suspend fun verifyCredentialsActive(): Boolean = try {
        krakenService.getBalances()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Unable to verify Kraken credentials before binding empty database", e)
        false
    }

    /**
     * True only when no financial history of any kind exists: no trade rows at
     * all (including failed/dry-run attempts, which still occupy the database),
     * no snapshots, no ledgers, and no meaningful sync metadata. Only a database
     * this empty may take the authenticated empty-bind/rebind shortcut.
     */
    private suspend fun isDatabaseFinanciallyEmpty(): Boolean =
        !tradeRepository.hasAnyTradeRows() && !isFinancialHistoryPresent()

    suspend fun isFinancialHistoryPresent(): Boolean {
        val durableRows = sequenceOf(
            tradeRepository.getLatestTradeTime(),
            tradeRepository.getLatestSnapshot(),
            ledgerRepository.getLatestLedgerTime(),
        ).any { it != null }
        if (durableRows) return true
        return FINANCIAL_METADATA_KEYS.any { key ->
            sequenceOf(
                tradeRepository.getSyncMetadata(key),
                ledgerRepository.getSyncMetadata(key),
            ).any { !it.isNullOrBlank() }
        }
    }

    companion object {
        /**
         * Durable account-binding proof contract. Bindings written without this
         * version — missing, v1, or the weaker pre-merge v2 — are never
         * fast-pathed and never take the lightweight rotation proof: they are
         * revalidated once under the current strong policy before trust resumes.
         */
        const val CURRENT_BINDING_VERSION = "3"

        private val FINANCIAL_METADATA_KEYS = listOf(
            SyncMetadataKeys.SYNC_OFFSET,
            SyncMetadataKeys.SYNC_WATERMARK_EPOCH_SEC,
            SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS,
            SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
            SyncMetadataKeys.INCEPTION_RECOVERY_STATUS,
            SyncMetadataKeys.INCEPTION_RECOVERY_LEDGER_STATUS,
        )

        fun digestAccountScope(accountScope: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(accountScope.toByteArray(Charsets.UTF_8))
            return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}
