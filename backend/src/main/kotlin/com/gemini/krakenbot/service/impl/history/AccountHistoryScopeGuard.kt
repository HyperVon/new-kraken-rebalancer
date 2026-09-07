package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
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
        val config = configService.getConfig()
        if (config.settings.simulation) {
            return@withLock AccountScopeValidationResult.SIMULATION
        }
        if (!config.kraken.hasValidCredentials()) {
            return@withLock AccountScopeValidationResult.scopeUnavailable("credentials unavailable")
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
            return@withLock AccountScopeValidationResult.scopeUnavailable("account scope unavailable")
        }

        val scopeDigest = digestAccountScope(accountScope)
        val storedScopeDigest = tradeRepository.getSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST)?.trim()

        if (!storedScopeDigest.isNullOrBlank()) {
            if (storedScopeDigest == scopeDigest) {
                return@withLock AccountScopeValidationResult(
                    AccountScopeValidationStatus.VALID,
                    currentScopeDigest = scopeDigest,
                )
            }
            // The scope digest is credential-derived, so a key rotation on the same
            // account also presents as a mismatch. Rebind only when the configured
            // credentials can still see the stored fills; a different account whose
            // history merely shares this database stays locked out.
            return@withLock when (continuityVerifier.verifyContinuity()) {
                AccountHistoryContinuityStatus.VERIFIED -> {
                    tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST, scopeDigest)
                    log.info("Rebound database to rotated Kraken account scope digest {}", scopeDigest)
                    AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = scopeDigest)
                }

                AccountHistoryContinuityStatus.NO_OVERLAP ->
                    AccountScopeValidationResult.scopeMismatch(scopeDigest)

                AccountHistoryContinuityStatus.UNAVAILABLE ->
                    AccountScopeValidationResult.scopeUnavailable("unable to verify account continuity")
            }
        }

        // No stored digest: verify whether the financial history is empty.
        if (tradeRepository.hasAnyTradeRows()) {
            return@withLock resolveUnboundDatabase(scopeDigest)
        }
        val hasHistory = isFinancialHistoryPresent()
        if (!hasHistory) {
            // Bind an empty database only after the credentials prove live: an
            // unauthenticated scope hash would otherwise bind (or poison) a fresh
            // database on unvalidated secrets.
            if (!verifyCredentialsActive()) {
                return@withLock AccountScopeValidationResult.scopeUnavailable(
                    "credentials could not be verified against Kraken",
                )
            }
            tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST, scopeDigest)
            log.info("Bound empty database to initial Kraken account scope digest {}", scopeDigest)
            return@withLock AccountScopeValidationResult(
                AccountScopeValidationStatus.VALID,
                currentScopeDigest = scopeDigest,
            )
        }

        return@withLock resolveUnboundDatabase(scopeDigest)
    }

    /**
     * Existing unscoped history predates the account binding contract. Bind it only
     * when continuity proof shows the active credentials own it; never let whichever
     * credentials happen to be configured first claim foreign history. Exchange
     * outages fail closed so a degraded network cannot launder a mismatch.
     */
    private suspend fun resolveUnboundDatabase(scopeDigest: String): AccountScopeValidationResult =
        when (continuityVerifier.verifyContinuity()) {
            AccountHistoryContinuityStatus.VERIFIED -> {
                tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST, scopeDigest)
                log.info(
                    "Bound upgraded database to Kraken account scope digest {} after continuity proof",
                    scopeDigest,
                )
                AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = scopeDigest)
            }

            AccountHistoryContinuityStatus.NO_OVERLAP -> {
                log.warn("Upgraded database has existing financial history but no account scope binding")
                AccountScopeValidationResult.unboundExistingHistory()
            }

            AccountHistoryContinuityStatus.UNAVAILABLE ->
                AccountScopeValidationResult.scopeUnavailable("unable to verify account continuity")
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
