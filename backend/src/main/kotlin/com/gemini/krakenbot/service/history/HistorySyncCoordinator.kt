package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.config.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Fixed retry schedule for transient History synchronization failures: 1, 2, 5 and then 15 minutes.
 * Repeated transient failures stay at 15 minutes until the operation succeeds.
 */
class HistorySyncRetryPolicy(
    private val steps: List<Duration> = DEFAULT_STEPS,
    private val jitter: (Duration) -> Duration = ::defaultJitter,
) {
    init {
        require(steps.isNotEmpty()) { "History sync retry policy needs at least one step." }
    }

    fun nextDelay(attempt: Int): Duration {
        val index = attempt.coerceIn(0, steps.lastIndex)
        return jitter(steps[index])
    }

    companion object {
        val DEFAULT_STEPS: List<Duration> = listOf(
            Duration.ofMinutes(1),
            Duration.ofMinutes(2),
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
        )
        private const val JITTER_DIVISOR = 4

        private fun defaultJitter(delay: Duration): Duration {
            val millis = delay.toMillis()
            if (millis <= 0) return delay
            return delay.plusMillis(Random.nextLong(0, millis / JITTER_DIVISOR + 1))
        }
    }
}

/**
 * One serialized worker owning automatic History synchronization: a periodic recent refresh on its
 * own clock, an urgent post-order refresh, a frozen older backfill that yields to recent work, and a
 * daily repair scan. Job windows and cursors are durable per account/feed/purpose, so a recent scan
 * never restarts committed backfill progress and a restart resumes where it stopped. Failure state is
 * tracked per account/feed/purpose so one failing feed cannot clear another feed's retry schedule.
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
    private val periodicTicks: Flow<Unit> = flow {
        while (true) {
            delay(periodicInterval.toMillis())
            emit(Unit)
        }
    },
) {
    private val log = LoggerFactory.getLogger(HistorySyncCoordinator::class.java)
    private val triggers = Channel<Trigger>(Channel.CONFLATED)
    private val refreshPending = AtomicBoolean(false)
    private val failures = ConcurrentHashMap<FailureKey, FailureState>()

    @Volatile
    private var worker: Job? = null

    @Volatile
    private var blockedReason: String? = null

    fun start(scope: CoroutineScope = applicationScope) {
        synchronized(this) {
            if (worker?.isActive == true) return
            worker = scope.launch {
                val ticker = launch {
                    periodicTicks.collect { requestRecentRefresh() }
                }
                try {
                    while (isActive) {
                        val trigger = withTimeoutOrNull(waitBudgetMs()) { triggers.receive() } ?: Trigger.Periodic
                        runCatching { process(trigger) }
                            .onFailure { error ->
                                if (error is CancellationException) throw error
                                log.warn("History sync coordinator pass failed.", error)
                            }
                    }
                } finally {
                    ticker.cancel()
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
        failures.clear()
        triggers.trySend(Trigger.OperatorRetry)
    }

    fun blockedReason(): String? = blockedReason

    /** Account-level transient failure that no durable job can carry (the identity is unknown). */
    fun lastFailure(): String? = failures[ACCOUNT_KEY]?.message

    /** Earliest moment a failed account/feed/purpose operation may be attempted again. */
    fun nextRetryAt(): Instant? = failures.values.minOfOrNull { it.nextAttemptAt }

    /** Runs one coalesced synchronization pass; the background worker invokes this per trigger. */
    internal suspend fun runCoalescedPass() = process(Trigger.Recent)

    private suspend fun process(trigger: Trigger) {
        if (blockedReason != null && trigger != Trigger.OperatorRetry) return
        if (trigger == Trigger.OperatorRetry) clearBlockedJobs()
        if (!accountSourceActive()) return
        val now = nowProvider()
        val automatic = trigger == Trigger.Periodic || trigger == Trigger.Recent
        if (automatic && failureBlocks(ACCOUNT_KEY, now)) return
        val digest = synchronizer.accountIdentityDigest()
        if (digest == null) {
            registerFailure(ACCOUNT_KEY, "History sync could not verify the authenticated account.")
            return
        }
        clearFailure(ACCOUNT_KEY)

        ensureBackfillWindow(digest, store.loadSyncJobs(digest), now)
        refreshPending.set(false)

        val feeds = when (trigger) {
            is Trigger.Manual -> listOf(trigger.feed)
            else -> HistoryFeed.entries
        }
        runRecentScans(digest, feeds, now)
        if (blockedReason != null || refreshPending.get() || accountFailed()) return

        runBackfill(digest, now)
        if (blockedReason != null || refreshPending.get() || accountFailed()) return

        runRepairIfDue(digest, now)
    }

    /** True while the authenticated account itself is unavailable; every feed is affected. */
    private fun accountFailed(): Boolean = failures.containsKey(ACCOUNT_KEY)

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
        for (feed in feeds) {
            val key = FailureKey(feed, HistorySyncPurpose.RECENT)
            if (failureBlocks(key, now)) continue
            val jobs = store.loadSyncJobs(digest)
            val start = recentScanStartForFeed(jobs, feed, now)
            val result = runCatching {
                synchronizer.syncWindow(feed, start, now.epochSecond, HistorySyncPurpose.RECENT)
            }
            val failure = result.exceptionOrNull()
            if (failure != null) {
                if (handleSyncFailure(digest, feed, HistorySyncPurpose.RECENT, failure)) return
                continue
            }
            handleSyncStatus(digest, feed, HistorySyncPurpose.RECENT, result.getOrThrow())
        }
    }

    private suspend fun runBackfill(digest: String, now: Instant) {
        for (feed in HistoryFeed.entries) {
            val key = FailureKey(feed, HistorySyncPurpose.BACKFILL)
            if (failureBlocks(key, now)) continue
            val job = store.loadSyncJobs(digest).firstOrNull {
                it.feed == feed && it.purpose == HistorySyncPurpose.BACKFILL
            } ?: continue
            if (job.state == HistorySyncJobState.COMPLETE || job.state == HistorySyncJobState.BLOCKED) continue
            val start = job.windowStartSec ?: 0L
            val end = job.windowEndSec ?: continue
            if (start > end) {
                store.upsertSyncJob(job.copy(state = HistorySyncJobState.COMPLETE, updatedAt = nowProvider()))
                continue
            }
            val result = runCatching {
                synchronizer.syncWindow(
                    feed = feed,
                    startSec = start,
                    endSec = end,
                    purpose = HistorySyncPurpose.BACKFILL,
                    resumeCursor = job.cursor,
                    betweenPages = { refreshPending.get() },
                )
            }
            val failure = result.exceptionOrNull()
            if (failure != null) {
                if (handleSyncFailure(digest, feed, HistorySyncPurpose.BACKFILL, failure)) return
                continue
            }
            handleSyncStatus(digest, feed, HistorySyncPurpose.BACKFILL, result.getOrThrow())
        }
    }

    private suspend fun runRepairIfDue(digest: String, now: Instant) {
        for (feed in HistoryFeed.entries) {
            val key = FailureKey(feed, HistorySyncPurpose.REPAIR)
            if (failureBlocks(key, now)) continue
            val job = store.loadSyncJobs(digest).firstOrNull {
                it.feed == feed && it.purpose == HistorySyncPurpose.REPAIR
            }
            val lastRun = job?.lastSuccessAt
            if (lastRun != null && Duration.between(lastRun, now) < REPAIR_INTERVAL) continue
            val end = now.epochSecond
            val start = end - REPAIR_WINDOW_SECONDS
            val result = runCatching {
                synchronizer.syncWindow(feed, start, end, HistorySyncPurpose.REPAIR)
            }
            val failure = result.exceptionOrNull()
            if (failure != null) {
                if (handleSyncFailure(digest, feed, HistorySyncPurpose.REPAIR, failure)) return
                continue
            }
            handleSyncStatus(digest, feed, HistorySyncPurpose.REPAIR, result.getOrThrow())
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
                clearFailure(FailureKey(feed, purpose))
                clearJobFailure(digest, feed, purpose)
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
                registerFailure(
                    key = FailureKey(feed, purpose),
                    message = status.lastError ?: HistorySyncErrors.PAGE_FAILED,
                    digest = digest,
                    feed = feed,
                    purpose = purpose,
                )
            }
        }
    }

    private suspend fun markWindowSkipped(
        digest: String,
        feed: HistoryFeed,
        purpose: HistorySyncPurpose,
        status: HistorySyncStatus,
    ) {
        val now = nowProvider()
        val jobs = store.loadSyncJobs(digest).firstOrNull { it.feed == feed && it.purpose == purpose }
        val skipped = jobs?.copy(
            state = HistorySyncJobState.COMPLETE,
            completedThroughSec = status.windowEnd?.epochSecond,
            lastError = status.lastError,
            nextRetryAt = null,
            updatedAt = now,
        ) ?: HistorySyncJob(
            accountIdentityDigest = digest,
            feed = feed,
            purpose = purpose,
            state = HistorySyncJobState.COMPLETE,
            windowStartSec = status.windowStart?.epochSecond,
            windowEndSec = status.windowEnd?.epochSecond,
            cursor = null,
            completedThroughSec = status.windowEnd?.epochSecond,
            lastError = status.lastError,
            nextRetryAt = null,
            lastSuccessAt = null,
            updatedAt = now,
        )
        store.upsertSyncJob(skipped)
    }

    /**
     * Records one failure for its own account/feed/purpose only. A healthy identity lookup or a
     * healthy sibling feed never clears another operation's escalation. Returns true when the
     * failure is account-wide and the pass must stop.
     */
    private suspend fun handleSyncFailure(
        digest: String,
        feed: HistoryFeed,
        purpose: HistorySyncPurpose,
        error: Throwable,
    ): Boolean {
        if (error is CancellationException) throw error
        if (error is HistoryAccountUnavailableException) {
            registerFailure(ACCOUNT_KEY, error.message.orEmpty().ifBlank { "History sync account unavailable." })
            return true
        }
        registerFailure(
            key = FailureKey(feed, purpose),
            message = error.message.orEmpty().ifBlank { error::class.simpleName ?: "History sync failed." },
            digest = digest,
            feed = feed,
            purpose = purpose,
        )
        return false
    }

    private suspend fun blockSync(digest: String, feed: HistoryFeed, purpose: HistorySyncPurpose, reason: String?) {
        val resolved = reason ?: HistorySyncErrors.ACCOUNT_SCOPE_CHANGED
        blockedReason = resolved
        clearFailure(FailureKey(feed, purpose))
        val now = nowProvider()
        val job = store.loadSyncJobs(digest).firstOrNull { it.feed == feed && it.purpose == purpose }
        val blocked = job?.copy(
            state = HistorySyncJobState.BLOCKED,
            lastError = resolved,
            nextRetryAt = null,
            updatedAt = now,
        ) ?: HistorySyncJob(
            accountIdentityDigest = digest,
            feed = feed,
            purpose = purpose,
            state = HistorySyncJobState.BLOCKED,
            windowStartSec = null,
            windowEndSec = null,
            cursor = null,
            completedThroughSec = null,
            lastError = resolved,
            nextRetryAt = null,
            lastSuccessAt = null,
            updatedAt = now,
        )
        store.upsertSyncJob(blocked)
        log.warn("History sync blocked for {}/{}: {}", feed, purpose, resolved)
    }

    private suspend fun clearBlockedJobs() {
        val digest = synchronizer.accountIdentityDigest() ?: return
        store.loadSyncJobs(digest).filter { it.state == HistorySyncJobState.BLOCKED }.forEach { job ->
            store.upsertSyncJob(
                job.copy(
                    state = HistorySyncJobState.PENDING,
                    lastError = null,
                    nextRetryAt = null,
                    updatedAt = nowProvider(),
                ),
            )
        }
    }

    /**
     * Records one failure for its own account/feed/purpose only. A healthy identity lookup or a
     * healthy sibling feed never clears another operation's escalation.
     */
    private suspend fun registerFailure(
        key: FailureKey,
        message: String,
        digest: String? = null,
        feed: HistoryFeed? = null,
        purpose: HistorySyncPurpose? = null,
    ) {
        val now = nowProvider()
        val state = failures.computeIfAbsent(key) { FailureState() }
        val delay = retryPolicy.nextDelay(state.attempts)
        state.attempts++
        state.nextAttemptAt = now.plus(delay)
        state.message = sanitizeFailureMessage(message)
        log.warn("History sync attempt failed ({}); retrying in {}s.", state.message, delay.toSeconds())
        if (digest != null && feed != null && purpose != null) {
            persistJobFailure(digest, feed, purpose, state.message, state.nextAttemptAt)
        }
    }

    private fun clearFailure(key: FailureKey) {
        failures.remove(key)
    }

    private fun failureBlocks(key: FailureKey, now: Instant): Boolean {
        val state = failures[key] ?: return false
        return now.isBefore(state.nextAttemptAt)
    }

    /** Failure state and next retry live on the durable job so Data status can report them. */
    private suspend fun persistJobFailure(
        digest: String,
        feed: HistoryFeed,
        purpose: HistorySyncPurpose,
        message: String,
        nextRetryAt: Instant,
    ) {
        val job = store.loadSyncJobs(digest).firstOrNull { it.feed == feed && it.purpose == purpose }
        if (job?.state == HistorySyncJobState.BLOCKED) return
        val now = nowProvider()
        val failed = job?.copy(
            state = HistorySyncJobState.FAILED,
            lastError = message,
            nextRetryAt = nextRetryAt,
            updatedAt = now,
        ) ?: HistorySyncJob(
            accountIdentityDigest = digest,
            feed = feed,
            purpose = purpose,
            state = HistorySyncJobState.FAILED,
            windowStartSec = null,
            windowEndSec = null,
            cursor = null,
            completedThroughSec = null,
            lastError = message,
            nextRetryAt = nextRetryAt,
            lastSuccessAt = null,
            updatedAt = now,
        )
        store.upsertSyncJob(failed)
    }

    private suspend fun clearJobFailure(digest: String, feed: HistoryFeed, purpose: HistorySyncPurpose) {
        val job = store.loadSyncJobs(digest).firstOrNull { it.feed == feed && it.purpose == purpose } ?: return
        if (job.lastError == null && job.nextRetryAt == null) return
        store.upsertSyncJob(job.copy(lastError = null, nextRetryAt = null, updatedAt = nowProvider()))
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
        val untilRetry = failures.values.minOfOrNull { it.nextAttemptAt }
            ?.let { Duration.between(now, it).toMillis() }
            ?: Long.MAX_VALUE
        return untilRetry.coerceAtLeast(MIN_WAIT_MS)
    }

    /**
     * Keeps only a short, non-sensitive failure reason: one line, no long opaque tokens, and a
     * bounded length so raw private exchange payloads never reach the durable store.
     */
    private fun sanitizeFailureMessage(raw: String): String {
        val collapsed = raw.lineSequence().firstOrNull()?.trim().orEmpty()
        if (collapsed.isEmpty()) return "History sync failed."
        return OPAQUE_TOKEN_PATTERN.replace(collapsed, "[redacted]").take(MAX_FAILURE_MESSAGE_LENGTH)
    }

    private data class FailureKey(val feed: HistoryFeed?, val purpose: HistorySyncPurpose?)

    private class FailureState {
        var attempts: Int = 0
        var nextAttemptAt: Instant = Instant.EPOCH
        var message: String = ""
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
        private const val MAX_FAILURE_MESSAGE_LENGTH = 200
        private val ACCOUNT_KEY = FailureKey(null, null)
        private val OPAQUE_TOKEN_PATTERN = Regex("(?<![A-Za-z0-9_])[A-Za-z0-9+/_=-]{32,}(?![A-Za-z0-9_])")
    }
}
