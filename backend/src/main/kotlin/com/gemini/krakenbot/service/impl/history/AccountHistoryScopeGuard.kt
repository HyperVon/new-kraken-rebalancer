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
            return@withLock if (storedScopeDigest == scopeDigest) {
                AccountScopeValidationResult(AccountScopeValidationStatus.VALID, currentScopeDigest = scopeDigest)
            } else {
                AccountScopeValidationResult.scopeMismatch(scopeDigest)
            }
        }

        // No stored digest: verify whether the financial history is empty.
        if (tradeRepository.hasAnyTradeRows()) {
            return@withLock AccountScopeValidationResult.unboundExistingHistory()
        }
        val hasHistory = isFinancialHistoryPresent()
        if (!hasHistory) {
            tradeRepository.setSyncMetadata(SyncMetadataKeys.INCEPTION_ACCOUNT_SCOPE_DIGEST, scopeDigest)
            log.info("Bound empty database to initial Kraken account scope digest {}", scopeDigest)
            return@withLock AccountScopeValidationResult(
                AccountScopeValidationStatus.VALID,
                currentScopeDigest = scopeDigest,
            )
        }

        // Existing unscoped history predates the account binding contract. Do not let whichever
        // credentials happen to be configured first claim it. The operator must migrate/reset it.
        log.warn("Upgraded database has existing financial history but no account scope binding")
        AccountScopeValidationResult.unboundExistingHistory()
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
