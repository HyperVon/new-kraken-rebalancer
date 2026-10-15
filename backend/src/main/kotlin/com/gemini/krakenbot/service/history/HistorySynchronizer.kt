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
    fun isCycleActive(): Boolean
}

/** Runs one bounded exchange page at a time and resumes from the committed cursor after restart. */
class HistorySynchronizer(
    private val applicationScope: CoroutineScope,
    private val krakenService: KrakenService,
    private val configService: ConfigService,
    private val dataDirectoryIdentity: DataDirectoryIdentity,
    private val store: HistoryStore,
    internal val cycleGate: HistoryCycleGate = HistoryCycleGate { false },
    private val nowProvider: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(HistorySynchronizer::class.java)
    private val feedLocks = ConcurrentHashMap<HistoryFeed, Mutex>()
    private val jobsLock = Any()
    private val jobs = mutableMapOf<HistoryFeed, Job>()

    /** Starts or joins the one active scan for this feed. */
    fun startSync(feed: HistoryFeed, startSec: Long, endSec: Long): Job {
        require(startSec <= endSec)
        synchronized(jobsLock) {
            jobs[feed]?.takeIf(Job::isActive)?.let { return it }
            val job = applicationScope.launch { syncWindow(feed, startSec, endSec) }
            jobs[feed] = job
            return job
        }
    }

    /** A scan processes pages serially; storage commits each page before the next request starts. */
    suspend fun syncWindow(feed: HistoryFeed, startSec: Long, endSec: Long): HistorySyncStatus {
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
                throw IllegalStateException("History sync requires an authenticated, bound Kraken account.")
            }
            var status = store.beginSyncWindow(
                HistorySyncWindow(
                    accountIdentityDigest = identity.digest,
                    feed = feed,
                    scanId = UUID.randomUUID().toString(),
                    startSec = startSec,
                    endSec = endSec,
                ),
            )
            while (status.state == HistorySyncState.RUNNING) {
                if (cycleGate.isCycleActive()) {
                    store.pauseSync(identity.digest, feed, status.scanId)
                    status = status.copy(state = HistorySyncState.PAUSED)
                    delay(CYCLE_YIELD_DELAY_MS)
                    status = store.beginSyncWindow(
                        HistorySyncWindow(
                            accountIdentityDigest = identity.digest,
                            feed = feed,
                            scanId = status.scanId,
                            startSec = startSec,
                            endSec = endSec,
                        ),
                    )
                    continue
                }
                try {
                    val (nextStatus, restarted) = configService.withExecutionSession {
                        val currentIdentity = authenticatedAccount(expectedDigest = identity.digest)
                        identity = currentIdentity
                        val requestedCursor = status.nextCursor
                        val page = fetchPage(feed, currentIdentity, startSec, endSec, requestedCursor)
                        val request = HistoryPageRequest(
                            accountIdentityDigest = currentIdentity.digest,
                            feed = feed,
                            scanId = status.scanId,
                            startSec = startSec,
                            endSec = endSec,
                            requestedCursor = requestedCursor,
                            committedAt = nowProvider(),
                        )

                        // fetchPage returns the entry type matching the requested feed.
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
                        if (committedStatus.state == HistorySyncState.FAILED && restarts == 0) {
                            restarts++
                            willRestart = true
                            updatedStatus = restartWindow(currentIdentity.digest, feed, startSec, endSec)
                        }
                        Pair(updatedStatus, willRestart)
                    }
                    status = nextStatus
                    if (status.state == HistorySyncState.FAILED && !restarted) {
                        return@withLock status
                    }
                    if (status.state == HistorySyncState.RUNNING) yield()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: HistoryAccountScopeException) {
                    store.failSync(identity.digest, feed, status.scanId, ACCOUNT_SCOPE_CHANGED)
                    return@withLock status.copy(state = HistorySyncState.FAILED, lastError = ACCOUNT_SCOPE_CHANGED)
                } catch (error: Exception) {
                    log.warn("History {} page failed ({}).", feed.name.lowercase(Locale.ROOT), error::class.simpleName)
                    if (restarts == 0) {
                        restarts++
                        store.failSync(identity.digest, feed, status.scanId, PAGE_RESTARTED)
                        status = restartWindow(identity.digest, feed, startSec, endSec)
                    } else {
                        store.failSync(identity.digest, feed, status.scanId, PAGE_FAILED)
                        return@withLock status.copy(state = HistorySyncState.FAILED, lastError = PAGE_FAILED)
                    }
                }
            }
            status
        }
    }

    private suspend fun restartWindow(
        accountIdentityDigest: String,
        feed: HistoryFeed,
        startSec: Long,
        endSec: Long,
    ): HistorySyncStatus = store.beginSyncWindow(
        HistorySyncWindow(
            accountIdentityDigest = accountIdentityDigest,
            feed = feed,
            scanId = UUID.randomUUID().toString(),
            startSec = startSec,
            endSec = endSec,
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
                val before = verifyBackendIdentity(backend, expectedIdentity.digest)
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
                val after = verifyBackendIdentity(backend, expectedIdentity.digest)
                if (before.digest != after.digest) throw HistoryAccountScopeException()
                page
            }
        }
    } catch (timeout: TimeoutCancellationException) {
        throw IllegalStateException("History page timed out.")
    }

    private suspend fun authenticatedAccount(expectedDigest: String? = null): AccountIdentity =
        krakenService.withStableBackend { backend -> verifyBackendIdentity(backend, expectedDigest) }

    private suspend fun verifyBackendIdentity(
        backend: KrakenService,
        expectedDigest: String? = null,
    ): AccountIdentity {
        val credentialScope = backend.getFundingEvidenceScope().trim()
        if (credentialScope.isBlank() || credentialScope == "scope-unavailable") throw HistoryAccountScopeException()
        val rawIdentity = backend.getAuthenticatedAccountIdentity()
            ?.filterNot(Char::isWhitespace)
            ?.uppercase(Locale.ROOT)
            ?.takeIf(String::isNotBlank)
            ?: throw HistoryAccountScopeException()
        val digest = sha256(rawIdentity)
        if (expectedDigest != null && digest != expectedDigest) throw HistoryAccountScopeException()
        try {
            dataDirectoryIdentity.bindOrVerify(digest)
        } catch (error: Exception) {
            throw HistoryAccountScopeException()
        }
        return AccountIdentity(digest)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private class AccountIdentity(val digest: String) {
        override fun equals(other: Any?): Boolean = other is AccountIdentity && digest == other.digest
        override fun hashCode(): Int = digest.hashCode()
    }

    private class HistoryAccountScopeException : Exception()

    private companion object {
        const val PAGE_SIZE = 100
        const val PAGE_TIMEOUT_MS = 30_000L
        const val CYCLE_YIELD_DELAY_MS = 250L
        const val ACCOUNT_SCOPE_CHANGED = "History sync stopped because authenticated account or credentials changed."
        const val PAGE_RESTARTED = "History page was retried from the start of its bounded time window."
        const val PAGE_FAILED = "History page could not be validated after one bounded restart."
    }
}
