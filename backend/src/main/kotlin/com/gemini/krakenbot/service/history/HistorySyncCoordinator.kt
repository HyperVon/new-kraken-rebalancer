package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.config.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** Deterministic exponential backoff with jitter; tests inject an identity jitter function. */
class HistorySyncRetryPolicy(
    private val baseDelay: Duration = DEFAULT_BASE_DELAY,
    private val maxDelay: Duration = DEFAULT_MAX_DELAY,
    private val jitter: (Duration) -> Duration = ::defaultJitter,
) {
    fun nextDelay(attempt: Int): Duration {
        val boundedAttempt = attempt.coerceIn(0, MAX_EXPONENT)
        val exponential = baseDelay.multipliedBy(1L shl boundedAttempt)
        val capped = if (exponential > maxDelay) maxDelay else exponential
        return jitter(capped)
    }

    companion object {
        private val DEFAULT_BASE_DELAY: Duration = Duration.ofSeconds(30)
        private val DEFAULT_MAX_DELAY: Duration = Duration.ofMinutes(15)
        private const val MAX_EXPONENT = 10
        private const val JITTER_DIVISOR = 4

        private fun defaultJitter(delay: Duration): Duration {
            val millis = delay.toMillis()
            if (millis <= 0) return delay
            return delay.plusMillis(Random.nextLong(0, millis / JITTER_DIVISOR + 1))
        }
    }
}

/**
 * One serialized worker owning automatic History synchronization: periodic recent refresh,
 * urgent post-order refresh, a frozen older backfill that yields to recent work, and a daily
 * repair scan. Job windows and cursors are durable per account/feed/purpose, so a recent
 * scan never restarts committed backfill progress and a restart resumes where it stopped.
 */
class HistorySyncCoordinator(
    private val applicationScope: CoroutineScope,
    private val synchronizer: HistorySynchronizer,
    private val store: HistoryStore,
    private val settingsProvider: suspend () -> Settings,
    private val nowProvider: () -> Instant = Instant::now,
    private val delayFn: suspend (Duration) -> Unit = { delay(it.toMillis()) },
    private val retryPolicy: HistorySyncRetryPolicy = HistorySyncRetryPolicy(),
    private val periodicInterval: Duration = PERIODIC_INTERVAL,
    private val urgentDelay: Duration = URGENT_DELAY,
) {
    private val log = LoggerFactory.getLogger(HistorySyncCoordinator::class.java)
    private val triggers = Channel<Trigger>(Channel.CONFLATED)
    private val refreshPending = AtomicBoolean(false)

    @Volatile
    private var worker: Job? = null

    @Volatile
    private var blockedReason: String? = null

    @Volatile
    private var nextAttemptAt: Instant? = null

    private var failureAttempts = 0

    fun start(scope: CoroutineScope = applicationScope) {
        synchronized(this) {
            if (worker?.isActive == true) return
            worker = scope.launch {
                while (isActive) {
                    val trigger = withTimeoutOrNull(waitBudgetMs()) { triggers.receive() } ?: Trigger.Periodic
                    runCatching { process(trigger) }
                        .onFailure { error ->
                            if (error is CancellationException) throw error
                            log.warn("History sync coordinator pass failed.", error)
                        }
                }
            }
        }
    }

    fun stop() {
        synchronized(this) {
            worker?.cancel()
            worker = null
        }
    }

    /** Coalescing refresh request; a scan in flight is never cancelled to serve it. */
    fun requestRecentRefresh() {
        refreshPending.set(true)
        triggers.trySend(Trigger.Recent)
    }

    /** Manual per-account Trade or Ledger button: start the affected feed as soon as possible. */
    fun requestFeedRefresh(feed: HistoryFeed) {
        refreshPending.set(true)
        triggers.trySend(Trigger.Manual(feed))
    }

    /** Nonblocking post-order signal: an urgent refresh runs five seconds after the cycle. */
    fun requestUrgentAfterOrderCycle() {
        applicationScope.launch {
            delayFn(urgentDelay)
            requestRecentRefresh()
        }
    }

    /** Operator Retry: clears a blocked account/restriction stop and resumes automatic sync. */
    fun requestOperatorRetry() {
        if (blockedReason != null) {
            log.info("Operator retried blocked History synchronization.")
            blockedReason = null
        }
        failureAttempts = 0
        nextAttemptAt = null
        triggers.trySend(Trigger.OperatorRetry)
    }

    fun blockedReason(): String? = blockedReason

    /** Runs one coalesced synchronization pass; the background worker invokes this per trigger. */
    internal suspend fun runCoalescedPass() = process(Trigger.Recent)

    private suspend fun process(trigger: Trigger) {
        if (blockedReason != null && trigger != Trigger.OperatorRetry) return
        if (trigger == Trigger.OperatorRetry) clearBlockedJobs()
        val automatic = trigger == Trigger.Periodic || trigger == Trigger.Recent
        nextAttemptAt?.let { retryAt ->
            if (automatic && nowProvider().isBefore(retryAt)) return
        }
        if (!accountSourceActive()) return
        val digest = synchronizer.accountIdentityDigest()
        if (digest == null) {
            registerTransientFailure("History sync could not verify the authenticated account.")
            return
        }
        failureAttempts = 0
        nextAttemptAt = null

        val now = nowProvider()
        ensureBackfillWindow(digest, store.loadSyncJobs(digest), now)
        refreshPending.set(false)

        val feeds = when (trigger) {
            is Trigger.Manual -> listOf(trigger.feed)
            else -> HistoryFeed.entries
        }
        runRecentScans(digest, feeds, now)
        if (blockedReason != null || refreshPending.get()) return

        runBackfill(digest)
        if (blockedReason != null || refreshPending.get()) return

        runRepairIfDue(digest, now)
    }

    /**
     * Freezes the backfill window from the permitted beginning (epoch zero if unbounded)
     * through the first recent scan's start. An existing job keeps its frozen window and
     * committed cursor; it is never restarted to service a recent refresh.
     */
    private suspend fun ensureBackfillWindow(digest: String, jobs: List<HistorySyncJob>, now: Instant) {
        HistoryFeed.entries.forEach { feed ->
            val exists = jobs.any { it.feed == feed && it.purpose == HistorySyncPurpose.BACKFILL }
            if (!exists) {
                store.upsertSyncJob(
                    HistorySyncJob(
                        accountIdentityDigest = digest,
                        feed = feed,
                        purpose = HistorySyncPurpose.BACKFILL,
                        state = HistorySyncJobState.PENDING,
                        windowStartSec = 0L,
                        windowEndSec = recentScanStartForFeed(jobs, feed, now),
                        cursor = null,
                        completedThroughSec = null,
                        lastError = null,
                        nextRetryAt = null,
                        lastSuccessAt = null,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    /**
     * First scan covers [now − 24h, now]; later scans resume at the per-feed
     * [completedThrough − 24h, now]. The synchronizer clamps both to the API-key window.
     */
    private fun recentScanStartForFeed(jobs: List<HistorySyncJob>, feed: HistoryFeed, now: Instant): Long {
        val horizon = jobs.firstOrNull {
            it.feed == feed && it.purpose == HistorySyncPurpose.RECENT
        }?.completedThroughSec
        val nominalStart = horizon?.let { it - RECENT_OVERLAP_SECONDS }
            ?: (now.epochSecond - RECENT_FIRST_WINDOW_SECONDS)
        return nominalStart.coerceAtMost(now.epochSecond)
    }

    private suspend fun runRecentScans(digest: String, feeds: List<HistoryFeed>, now: Instant) {
        feeds.forEach { feed ->
            val jobs = store.loadSyncJobs(digest)
            val start = recentScanStartForFeed(jobs, feed, now)
            val status = runCatching {
                synchronizer.syncWindow(feed, start, now.epochSecond, HistorySyncPurpose.RECENT)
            }.getOrElse { error ->
                handleSyncFailure(error)
                return@forEach
            }
            handleSyncStatus(digest, feed, HistorySyncPurpose.RECENT, status)
        }
    }

    private suspend fun runBackfill(digest: String) {
        HistoryFeed.entries.forEach { feed ->
            val job = store.loadSyncJobs(digest).firstOrNull {
                it.feed == feed && it.purpose == HistorySyncPurpose.BACKFILL
            } ?: return@forEach
            if (job.state == HistorySyncJobState.COMPLETE || job.state == HistorySyncJobState.BLOCKED) return@forEach
            val start = job.windowStartSec ?: 0L
            val end = job.windowEndSec ?: return@forEach
            if (start > end) {
                store.upsertSyncJob(job.copy(state = HistorySyncJobState.COMPLETE, updatedAt = nowProvider()))
                return@forEach
            }
            val status = runCatching {
                synchronizer.syncWindow(
                    feed = feed,
                    startSec = start,
                    endSec = end,
                    purpose = HistorySyncPurpose.BACKFILL,
                    resumeCursor = job.cursor,
                    betweenPages = { refreshPending.get() },
                )
            }.getOrElse { error ->
                handleSyncFailure(error)
                return@forEach
            }
            handleSyncStatus(digest, feed, HistorySyncPurpose.BACKFILL, status)
        }
    }

    private suspend fun runRepairIfDue(digest: String, now: Instant) {
        HistoryFeed.entries.forEach { feed ->
            val job = store.loadSyncJobs(digest).firstOrNull {
                it.feed == feed && it.purpose == HistorySyncPurpose.REPAIR
            }
            val lastRun = job?.lastSuccessAt
            if (lastRun != null && Duration.between(lastRun, now) < REPAIR_INTERVAL) return@forEach
            val end = now.epochSecond
            val start = end - REPAIR_WINDOW_SECONDS
            val status = runCatching {
                synchronizer.syncWindow(feed, start, end, HistorySyncPurpose.REPAIR)
            }.getOrElse { error ->
                handleSyncFailure(error)
                return@forEach
            }
            handleSyncStatus(digest, feed, HistorySyncPurpose.REPAIR, status)
        }
    }

    private suspend fun handleSyncStatus(
        digest: String,
        feed: HistoryFeed,
        purpose: HistorySyncPurpose,
        status: HistorySyncStatus,
    ) {
        when {
            status.state == HistorySyncState.COMPLETE -> {
                failureAttempts = 0
                nextAttemptAt = null
            }

            status.lastError == HistorySyncErrors.ACCOUNT_SCOPE_CHANGED ||
                status.lastError == HistorySyncErrors.API_KEY_RESTRICTIONS_CHANGED -> {
                blockSync(digest, feed, purpose, status.lastError)
            }

            status.lastError == HistorySyncErrors.API_KEY_WINDOW_NO_OVERLAP -> {
                // Nothing is fetchable inside this window; record it as processed coverage.
                markWindowSkipped(digest, feed, purpose, status)
            }

            status.state == HistorySyncState.FAILED -> {
                registerTransientFailure(status.lastError ?: HistorySyncErrors.PAGE_FAILED)
            }
        }
    }

    private suspend fun markWindowSkipped(
        digest: String,
        feed: HistoryFeed,
        purpose: HistorySyncPurpose,
        status: HistorySyncStatus,
    ) {
        val jobs = store.loadSyncJobs(digest).firstOrNull { it.feed == feed && it.purpose == purpose } ?: return
        store.upsertSyncJob(
            jobs.copy(
                state = HistorySyncJobState.COMPLETE,
                completedThroughSec = status.windowEnd?.epochSecond,
                lastError = status.lastError,
                updatedAt = nowProvider(),
            ),
        )
    }

    private fun handleSyncFailure(error: Throwable) {
        if (error is CancellationException) throw error
        registerTransientFailure(error.message.orEmpty().ifBlank { error::class.simpleName ?: "History sync failed." })
    }

    private suspend fun blockSync(digest: String, feed: HistoryFeed, purpose: HistorySyncPurpose, reason: String?) {
        val resolved = reason ?: HistorySyncErrors.ACCOUNT_SCOPE_CHANGED
        blockedReason = resolved
        val job = store.loadSyncJobs(digest).firstOrNull { it.feed == feed && it.purpose == purpose }
        if (job != null) {
            store.upsertSyncJob(
                job.copy(state = HistorySyncJobState.BLOCKED, lastError = resolved, updatedAt = nowProvider()),
            )
        }
        log.warn("History sync blocked for {}/{}: {}", feed, purpose, resolved)
    }

    private suspend fun clearBlockedJobs() {
        val digest = synchronizer.accountIdentityDigest() ?: return
        store.loadSyncJobs(digest).filter { it.state == HistorySyncJobState.BLOCKED }.forEach { job ->
            store.upsertSyncJob(
                job.copy(state = HistorySyncJobState.PENDING, lastError = null, updatedAt = nowProvider()),
            )
        }
    }

    private fun registerTransientFailure(message: String) {
        val delay = retryPolicy.nextDelay(failureAttempts)
        failureAttempts++
        nextAttemptAt = nowProvider().plus(delay)
        log.warn("History sync attempt failed ({}); retrying in {}s.", message, delay.toSeconds())
    }

    private suspend fun accountSourceActive(): Boolean = try {
        !settingsProvider().simulation
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private fun waitBudgetMs(): Long {
        val now = nowProvider()
        val untilRetry = nextAttemptAt?.let { Duration.between(now, it).toMillis() } ?: Long.MAX_VALUE
        return minOf(untilRetry.coerceAtLeast(MIN_WAIT_MS), periodicInterval.toMillis())
    }

    private sealed interface Trigger {
        data object Periodic : Trigger
        data object Recent : Trigger
        data class Manual(val feed: HistoryFeed) : Trigger
        data object OperatorRetry : Trigger
    }

    companion object {
        val PERIODIC_INTERVAL: Duration = Duration.ofSeconds(60)
        val URGENT_DELAY: Duration = Duration.ofSeconds(5)
        val REPAIR_INTERVAL: Duration = Duration.ofHours(24)
        const val REPAIR_WINDOW_SECONDS = 7L * 24 * 60 * 60
        const val RECENT_FIRST_WINDOW_SECONDS = 24L * 60 * 60
        const val RECENT_OVERLAP_SECONDS = 24L * 60 * 60
        private const val MIN_WAIT_MS = 50L
    }
}
