package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.config.DataDirectoryIdentity
import com.gemini.krakenbot.model.ExchangeHistoryPage
import com.gemini.krakenbot.model.ExchangeLedgerHistoryEntry
import com.gemini.krakenbot.model.ExchangeTradeHistoryEntry
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.withExecutionSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

fun interface HistoryCycleGate {
    suspend fun runPageIfAdmitted(block: suspend () -> Unit): Boolean
}

/** Runs one bounded exchange page at a time and resumes from the committed cursor after restart. */
class HistorySynchronizer(
    private val applicationScope: CoroutineScope,
    private val krakenService: KrakenService,
    private val configService: ConfigService,
    private val dataDirectoryIdentity: DataDirectoryIdentity,
    private val store: HistoryStore,
    private val cycleGate: HistoryCycleGate = HistoryCycleGate { block ->
        block()
        true
    },
    private val nowProvider: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(HistorySynchronizer::class.java)
    private val feedLocks = ConcurrentHashMap<HistoryFeed, Mutex>()
    private val jobsLock = Any()
    private val jobs = mutableMapOf<HistoryFeed, Job>()

    /** Starts or joins the one active scan for this feed. */
    fun startSync(
        feed: HistoryFeed,
        startSec: Long,
        endSec: Long,
        purpose: HistorySyncPurpose = HistorySyncPurpose.RECENT,
        resumeCursor: String? = null,
    ): Job {
        require(startSec <= endSec)
        synchronized(jobsLock) {
            jobs[feed]?.takeIf(Job::isActive)?.let { return it }
            val job = applicationScope.launch {
                try {
                    syncWindow(feed, startSec, endSec, purpose, resumeCursor)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    log.error(
                        "History {} sync failed (window={}..{}).",
                        feed.name.lowercase(Locale.ROOT),
                        startSec,
                        endSec,
                        error,
                    )
                    throw error
                }
            }
            jobs[feed] = job
            return job
        }
    }

    /**
     * A scan processes pages serially; storage commits each page before the next request starts.
     * [betweenPages] runs after each committed page and may pause the window so pending recent
     * work can run first; the committed cursor then resumes the same frozen window later.
     */
    suspend fun syncWindow(
        feed: HistoryFeed,
        startSec: Long,
        endSec: Long,
        purpose: HistorySyncPurpose = HistorySyncPurpose.RECENT,
        resumeCursor: String? = null,
        betweenPages: suspend () -> Boolean = { false },
    ): HistorySyncStatus {
        require(startSec <= endSec)
        return feedLocks.getOrPut(feed) { Mutex() }.withLock {
            var restarts = 0
            var identity = try {
                configService.withExecutionSession {
                    authenticatedAccount()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw HistoryAccountUnavailableException(
                    "History sync requires an authenticated, bound Kraken account.",
                    error,
                )
            }
            val permittedStartSec = maxOf(startSec, identity.queryFromSec ?: startSec)
            val permittedEndSec = minOf(endSec, identity.queryToSec ?: endSec)
            val overlapsApiKeyWindow = permittedStartSec <= permittedEndSec
            val scanStartSec = if (overlapsApiKeyWindow) permittedStartSec else startSec
            val scanEndSec = if (overlapsApiKeyWindow) permittedEndSec else endSec
            var status = store.beginSyncWindow(
                HistorySyncWindow(
                    accountIdentityDigest = identity.digest,
                    feed = feed,
                    scanId = UUID.randomUUID().toString(),
                    startSec = scanStartSec,
                    endSec = scanEndSec,
                    cursor = resumeCursor,
                    purpose = purpose,
                ),
            )
            val restrictionFailure = when {
                !identity.queryBoundsVerified -> HistorySyncErrors.API_KEY_RESTRICTIONS_UNAVAILABLE
                !overlapsApiKeyWindow -> HistorySyncErrors.API_KEY_WINDOW_NO_OVERLAP
                else -> null
            }
            if (restrictionFailure != null) {
                store.failSync(identity.digest, feed, status.scanId, restrictionFailure)
                return@withLock status.copy(state = HistorySyncState.FAILED, lastError = restrictionFailure)
            }
            while (status.state == HistorySyncState.RUNNING) {
                try {
                    var nextStatus: HistorySyncStatus? = null
                    var restarted = false
                    val pageAdmitted = cycleGate.runPageIfAdmitted {
                        val (pageStatus, pageRestarted) = configService.withExecutionSession {
                            val currentIdentity = authenticatedAccount(
                                expectedDigest = identity.digest,
                                startSec = scanStartSec,
                                endSec = scanEndSec,
                            )
                            identity = currentIdentity
                            val requestedCursor = status.nextCursor
                            val page = fetchPage(feed, currentIdentity, scanStartSec, scanEndSec, requestedCursor)
                            val request = HistoryPageRequest(
                                accountIdentityDigest = currentIdentity.digest,
                                feed = feed,
                                scanId = status.scanId,
                                startSec = scanStartSec,
                                endSec = scanEndSec,
                                requestedCursor = requestedCursor,
                                committedAt = nowProvider(),
                                purpose = purpose,
                            )

                            @Suppress("UNCHECKED_CAST")
                            val committedStatus = when (feed) {
                                HistoryFeed.TRADES -> store.commitTradePage(
                                    request,
                                    page as ExchangeHistoryPage<ExchangeTradeHistoryEntry>,
                                )

                                HistoryFeed.LEDGERS -> store.commitLedgerPage(
                                    request,
                                    page as ExchangeHistoryPage<ExchangeLedgerHistoryEntry>,
                                )
                            }
                            var willRestart = false
                            var updatedStatus = committedStatus
                            if (committedStatus.state == HistorySyncState.FAILED && restarts == 0 &&
                                committedStatus.lastError?.isCursorProgressFailure() != true
                            ) {
                                restarts++
                                willRestart = true
                                updatedStatus = restartWindow(
                                    currentIdentity.digest,
                                    feed,
                                    scanStartSec,
                                    scanEndSec,
                                    purpose,
                                    requestedCursor,
                                )
                            }
                            Pair(updatedStatus, willRestart)
                        }
                        nextStatus = pageStatus
                        restarted = pageRestarted
                    }
                    if (!pageAdmitted) {
                        store.pauseSync(identity.digest, feed, status.scanId)
                        status = status.copy(state = HistorySyncState.PAUSED)
                        delay(CYCLE_YIELD_DELAY_MS)
                        status = store.beginSyncWindow(
                            HistorySyncWindow(
                                accountIdentityDigest = identity.digest,
                                feed = feed,
                                scanId = status.scanId,
                                startSec = scanStartSec,
                                endSec = scanEndSec,
                                cursor = status.nextCursor,
                                purpose = purpose,
                            ),
                        )
                        continue
                    }
                    status = checkNotNull(nextStatus)
                    if (status.state == HistorySyncState.FAILED && !restarted) {
                        return@withLock status
                    }
                    if (status.state == HistorySyncState.RUNNING) {
                        if (betweenPages()) {
                            store.pauseSync(identity.digest, feed, status.scanId)
                            return@withLock status.copy(state = HistorySyncState.PAUSED)
                        }
                        yield()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: HistoryAccountScopeException) {
                    store.failSync(identity.digest, feed, status.scanId, HistorySyncErrors.ACCOUNT_SCOPE_CHANGED)
                    return@withLock status.copy(
                        state = HistorySyncState.FAILED,
                        lastError = HistorySyncErrors.ACCOUNT_SCOPE_CHANGED,
                    )
                } catch (error: HistoryRestrictionException) {
                    val reason = error.message ?: HistorySyncErrors.API_KEY_RESTRICTIONS_CHANGED
                    store.failSync(identity.digest, feed, status.scanId, reason)
                    return@withLock status.copy(state = HistorySyncState.FAILED, lastError = reason)
                } catch (error: Exception) {
                    log.warn(
                        "History {} page failed (scan={}, window={}..{}, restart={}/1).",
                        feed.name.lowercase(Locale.ROOT),
                        status.scanId,
                        scanStartSec,
                        scanEndSec,
                        restarts,
                        error,
                    )
                    if (restarts == 0) {
                        restarts++
                        store.failSync(identity.digest, feed, status.scanId, HistorySyncErrors.PAGE_RESTARTED)
                        status =
                            restartWindow(identity.digest, feed, scanStartSec, scanEndSec, purpose, status.nextCursor)
                    } else {
                        store.failSync(identity.digest, feed, status.scanId, HistorySyncErrors.PAGE_FAILED)
                        return@withLock status.copy(
                            state = HistorySyncState.FAILED,
                            lastError = HistorySyncErrors.PAGE_FAILED,
                        )
                    }
                }
            }
            status
        }
    }

    /** Digest of the currently authenticated, bound account, or null when verification fails. */
    suspend fun accountIdentityDigest(): String? = try {
        configService.withExecutionSession { authenticatedAccount().digest }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        null
    }

    private suspend fun restartWindow(
        accountIdentityDigest: String,
        feed: HistoryFeed,
        startSec: Long,
        endSec: Long,
        purpose: HistorySyncPurpose,
        cursor: String?,
    ): HistorySyncStatus = store.beginSyncWindow(
        HistorySyncWindow(
            accountIdentityDigest = accountIdentityDigest,
            feed = feed,
            scanId = UUID.randomUUID().toString(),
            startSec = startSec,
            endSec = endSec,
            cursor = cursor,
            purpose = purpose,
        ),
    )

    private suspend fun fetchPage(
        feed: HistoryFeed,
        expectedIdentity: AccountIdentity,
        startSec: Long,
        endSec: Long,
        cursor: String?,
    ): ExchangeHistoryPage<*> = try {
        withTimeout(PAGE_TIMEOUT_MS) {
            krakenService.withStableBackend { backend ->
                val before = verifyBackendIdentity(backend, expectedIdentity.digest, startSec, endSec)
                val page = when (feed) {
                    HistoryFeed.TRADES -> backend.getAccountTradePage(
                        startSec = startSec,
                        endSec = endSec,
                        cursor = cursor,
                        limit = PAGE_SIZE,
                    )

                    HistoryFeed.LEDGERS -> backend.getAccountLedgerPage(
                        startSec = startSec,
                        endSec = endSec,
                        cursor = cursor,
                    )
                }
                val after = verifyBackendIdentity(backend, expectedIdentity.digest, startSec, endSec)
                if (before.digest != after.digest) throw HistoryAccountScopeException()
                page
            }
        }
    } catch (timeout: TimeoutCancellationException) {
        throw IllegalStateException("History page timed out.")
    }

    private suspend fun authenticatedAccount(
        expectedDigest: String? = null,
        startSec: Long? = null,
        endSec: Long? = null,
    ): AccountIdentity = krakenService.withStableBackend { backend ->
        verifyBackendIdentity(backend, expectedDigest, startSec, endSec)
    }

    private suspend fun verifyBackendIdentity(
        backend: KrakenService,
        expectedDigest: String?,
        startSec: Long?,
        endSec: Long?,
    ): AccountIdentity {
        val credentialScope = backend.getFundingEvidenceScope().trim()
        if (credentialScope.isBlank() || credentialScope == "scope-unavailable") throw HistoryAccountScopeException()
        val accountInfo = backend.getAuthenticatedAccountInfo() ?: throw HistoryAccountScopeException()
        val digest = sha256(accountInfo.identity.filterNot(Char::isWhitespace).uppercase(Locale.ROOT))
        if (expectedDigest != null && digest != expectedDigest) throw HistoryAccountScopeException()
        if (startSec != null || endSec != null) {
            if (!accountInfo.queryBoundsVerified) {
                throw HistoryRestrictionException(
                    HistorySyncErrors.API_KEY_RESTRICTIONS_UNAVAILABLE,
                )
            }
            if ((startSec != null && accountInfo.queryFromSec != null && startSec < accountInfo.queryFromSec) ||
                (endSec != null && accountInfo.queryToSec != null && endSec > accountInfo.queryToSec)
            ) {
                throw HistoryRestrictionException(HistorySyncErrors.API_KEY_RESTRICTIONS_CHANGED)
            }
        }
        try {
            dataDirectoryIdentity.bindOrVerify(digest)
        } catch (error: Exception) {
            throw HistoryAccountScopeException()
        }
        return AccountIdentity(
            digest = digest,
            queryFromSec = accountInfo.queryFromSec,
            queryToSec = accountInfo.queryToSec,
            queryBoundsVerified = accountInfo.queryBoundsVerified,
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun String.isCursorProgressFailure(): Boolean =
        contains("cursor", ignoreCase = true) || contains("page limit", ignoreCase = true)

    private data class AccountIdentity(
        val digest: String,
        val queryFromSec: Long?,
        val queryToSec: Long?,
        val queryBoundsVerified: Boolean,
    )

    private class HistoryAccountScopeException : Exception()
    private class HistoryRestrictionException(message: String) : Exception(message)

    private companion object {
        const val PAGE_SIZE = 100
        const val PAGE_TIMEOUT_MS = 30_000L
        const val CYCLE_YIELD_DELAY_MS = 250L
    }
}
