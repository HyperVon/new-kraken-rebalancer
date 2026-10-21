package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.DataDirectoryIdentity
import com.gemini.krakenbot.model.ExchangeHistoryPage
import com.gemini.krakenbot.model.ExchangeLedgerHistoryEntry
import com.gemini.krakenbot.model.ExchangeTradeHistoryEntry
import com.gemini.krakenbot.service.AuthenticatedAccountInfo
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.FakeKrakenService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class HistorySyncCoordinatorTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "backfill resumes from its committed cursor after restart and replay creates no duplicates" {
            withFixture { fixture ->
                val trades = fixture.tradesScript()
                trades.onPage(0L, null, "T1", "T2", nextCursor = "C1")
                trades.onPage(0L, "C1", "T3", nextCursor = null)
                trades.failWhen = { startSec, cursor -> startSec == 0L && cursor == "C1" }

                fixture.coordinator.runCoalescedPass()

                val attempts = trades.calls.filter { it.startSec == 0L }.map { it.cursor }
                attempts shouldBe listOf(null, "C1", "C1")
                fixture.store.loadSyncJobs(fixture.digest).first {
                    it.purpose == HistorySyncPurpose.BACKFILL && it.feed == HistoryFeed.TRADES
                }.cursor shouldBe "C1"

                trades.failWhen = { _, _ -> false }
                val restarted = fixture.restartCoordinator()
                restarted.runCoalescedPass()

                trades.calls.filter { it.startSec == 0L }.map { it.cursor } shouldBe
                    listOf(null, "C1", "C1", "C1")
                val job = fixture.store.loadSyncJobs(fixture.digest).first {
                    it.purpose == HistorySyncPurpose.BACKFILL && it.feed == HistoryFeed.TRADES
                }
                job.state shouldBe HistorySyncJobState.COMPLETE
                job.completedThroughSec shouldBe fixture.backfillEndSec
                fixture.tradeCount() shouldBe 3
            }
        }

        "a window failing without cursor progress retries once from its beginning and keeps the horizon" {
            withFixture { fixture ->
                val trades = fixture.tradesScript()
                trades.onPage(fixture.recentStartSec, null, "T1", nextCursor = null)
                fixture.coordinator.runCoalescedPass()
                val horizon = fixture.recentJob().completedThroughSec
                horizon shouldNotBe null

                fixture.clock.advance(Duration.ofMinutes(5))
                val failureCalls = mutableListOf<String?>()
                fixture.kraken.tradePageSupplier = { _, _, cursor, _ ->
                    failureCalls.add(cursor)
                    throw IllegalStateException("exchange page unavailable")
                }
                fixture.coordinator.runCoalescedPass()

                // No cursor ever progressed, so the frozen window retried from its beginning once.
                failureCalls.size shouldBe 2
                failureCalls.toSet() shouldBe setOf<String?>(null)
                fixture.recentJob().completedThroughSec shouldBe horizon
            }
        }

        "post-order refresh runs five seconds after the cycle and coalesces into one scan" {
            withFixture { fixture ->
                val trades = fixture.tradesScript()

                fixture.coordinator.requestUrgentAfterOrderCycle()
                fixture.coordinator.requestUrgentAfterOrderCycle()
                fixture.delays shouldBe listOf(
                    HistorySyncCoordinator.URGENT_DELAY,
                    HistorySyncCoordinator.URGENT_DELAY,
                )

                fixture.coordinator.runCoalescedPass()
                val recentStart = fixture.clock.now.epochSecond - HistorySyncCoordinator.RECENT_FIRST_WINDOW_SECONDS
                trades.calls.count { it.startSec == recentStart } shouldBe 1
                fixture.recentJob().completedThroughSec shouldBe fixture.clock.now.epochSecond
            }
        }

        "empty exchange windows record real coverage without fabricating rows" {
            withFixture { fixture ->
                fixture.coordinator.runCoalescedPass()

                fixture.recentJob().completedThroughSec shouldBe fixture.clock.now.epochSecond
                fixture.store.loadSyncJobs(fixture.digest).first {
                    it.purpose == HistorySyncPurpose.RECENT && it.feed == HistoryFeed.LEDGERS
                }.completedThroughSec shouldBe fixture.clock.now.epochSecond
                fixture.tradeCount() shouldBe 0
                fixture.ledgerCount() shouldBe 0
            }
        }

        "ledger entries repeated across shifted pages store each ledger id once" {
            withFixture { fixture ->
                val ledgers = fixture.ledgersScript()
                ledgers.onPage(fixture.recentStartSec, null, "L1", "L2", nextCursor = "C1")
                ledgers.onPage(fixture.recentStartSec, "C1", "L2", "L3", nextCursor = null)

                fixture.coordinator.runCoalescedPass()

                fixture.ledgerCount() shouldBe 3
            }
        }

        "account identity change blocks sync until a separate operator retry" {
            withFixture { fixture ->
                val trades = fixture.tradesScript()
                trades.onPage(fixture.recentStartSec, null, "T1", "T2", nextCursor = "C1")
                trades.onPage(fixture.recentStartSec, "C1", "T3", nextCursor = null)
                var flipped = false
                trades.beforeSupply = { _, cursor ->
                    if (cursor == "C1" && !flipped) {
                        flipped = true
                        fixture.kraken.authenticatedAccountIdentitySupplier = { "ACCOUNT-CHANGED" }
                    }
                }

                fixture.coordinator.runCoalescedPass()
                fixture.coordinator.blockedReason() shouldNotBe null

                fixture.kraken.authenticatedAccountIdentitySupplier = { "AA00 TEST TEST TEST" }
                fixture.coordinator.runCoalescedPass()
                fixture.tradeCount() shouldBe 2

                fixture.coordinator.requestOperatorRetry()
                fixture.coordinator.blockedReason() shouldBe null
                fixture.coordinator.runCoalescedPass()
                fixture.tradeCount() shouldBe 3
            }
        }

        "a transport failure schedules a bounded retry instead of blocking" {
            withFixture { fixture ->
                val trades = fixture.tradesScript()
                var identityCalls = 0
                fixture.kraken.authenticatedAccountIdentitySupplier = {
                    if (identityCalls++ == 0) "AA00 TEST TEST TEST" else throw IllegalStateException("network down")
                }

                fixture.coordinator.runCoalescedPass()

                fixture.coordinator.blockedReason() shouldBe null
                trades.calls.size shouldBe 0
                val callsAfterFailure = identityCalls

                fixture.coordinator.runCoalescedPass()
                identityCalls shouldBe callsAfterFailure

                fixture.clock.advance(Duration.ofMinutes(40))
                fixture.coordinator.runCoalescedPass()
                (identityCalls > callsAfterFailure) shouldBe true
            }
        }

        "an api-key window without overlap backs off without advancing coverage" {
            withFixture { fixture ->
                val trades = fixture.tradesScript()
                fixture.coordinator.runCoalescedPass()
                val callsAfterFirstPass = trades.calls.size
                val completedBefore = fixture.recentJob().completedThroughSec

                fixture.clock.advance(Duration.ofMinutes(5))
                val secondPassNow = fixture.clock.now.epochSecond
                fixture.kraken.authenticatedAccountInfoFactory = { identity ->
                    AuthenticatedAccountInfo(
                        identity = identity,
                        queryFromSec = secondPassNow + 10_000,
                        queryToSec = secondPassNow + 20_000,
                        queryBoundsVerified = true,
                    )
                }

                fixture.coordinator.runCoalescedPass()

                trades.calls.size shouldBe callsAfterFirstPass
                val job = fixture.recentJob()
                job.state shouldBe HistorySyncJobState.FAILED
                job.completedThroughSec shouldBe completedBefore
                job.lastError shouldBe HistorySyncErrors.API_KEY_WINDOW_NO_OVERLAP
                job.nextRetryAt shouldBe fixture.clock.now.plus(Duration.ofMinutes(1))
            }
        }

        "a clamped backfill keeps its frozen window and reopens it after the key widens" {
            withFixture { fixture ->
                val restrictedFrom = fixture.clock.now.epochSecond - 2L * 86_400
                var restricted = true
                fixture.kraken.authenticatedAccountInfoFactory = { identity ->
                    AuthenticatedAccountInfo(
                        identity = identity,
                        queryFromSec = restrictedFrom.takeIf { restricted },
                        queryToSec = null,
                        queryBoundsVerified = true,
                    )
                }
                fixture.tradesScript()

                fixture.coordinator.runCoalescedPass()

                val clamped = fixture.backfillJob()
                clamped.windowStartSec shouldBe restrictedFrom
                clamped.state shouldBe HistorySyncJobState.COMPLETE

                restricted = false
                fixture.clock.advance(Duration.ofMinutes(1))
                fixture.coordinator.runCoalescedPass()

                val resumed = fixture.backfillJob()
                resumed.windowStartSec shouldBe 0
                resumed.state shouldBe HistorySyncJobState.COMPLETE
            }
        }

        "a backfill window the key truncates at its end stays retryable" {
            withFixture { fixture ->
                val restrictedTo = fixture.clock.now.epochSecond - 2L * 86_400
                fixture.kraken.authenticatedAccountInfoFactory = { identity ->
                    AuthenticatedAccountInfo(
                        identity = identity,
                        queryFromSec = null,
                        queryToSec = restrictedTo,
                        queryBoundsVerified = true,
                    )
                }
                fixture.tradesScript()

                fixture.coordinator.runCoalescedPass()

                val backfill = fixture.backfillJob()
                backfill.state shouldBe HistorySyncJobState.FAILED
                // The scan only reached the key's permitted end, so the window never completed.
                backfill.windowEndSec shouldBe restrictedTo
                backfill.completedThroughSec shouldBe null
                backfill.lastError shouldBe HistorySyncErrors.API_KEY_WINDOW_PARTIAL
                // A failed window keeps a retry time and is not blocked, so it runs again.
                backfill.nextRetryAt shouldNotBe null
                backfill.state shouldNotBe HistorySyncJobState.BLOCKED
            }
        }

        "operator retry clears blocked jobs and manual refresh serves one feed" {
            withFixture { fixture ->
                val trades = fixture.tradesScript()
                trades.onPage(fixture.recentStartSec, null, "T1", "T2", nextCursor = "C1")
                trades.onPage(fixture.recentStartSec, "C1", "T3", nextCursor = null)
                val ledgers = fixture.ledgersScript()
                var flipped = false
                trades.beforeSupply = { _, cursor ->
                    if (cursor == "C1" && !flipped) {
                        flipped = true
                        fixture.kraken.authenticatedAccountIdentitySupplier = { "ACCOUNT-CHANGED" }
                    }
                }

                fixture.coordinator.start()
                try {
                    fixture.coordinator.runCoalescedPass()
                    fixture.coordinator.blockedReason() shouldNotBe null

                    fixture.kraken.authenticatedAccountIdentitySupplier = { "AA00 TEST TEST TEST" }
                    fixture.coordinator.requestOperatorRetry()
                    awaitQuiescent({ trades.calls.size }, { ledgers.calls.size })

                    fixture.coordinator.blockedReason() shouldBe null
                    fixture.store.loadSyncJobs(fixture.digest)
                        .none { it.state == HistorySyncJobState.BLOCKED } shouldBe true

                    val tradesBefore = trades.calls.size
                    val ledgersBefore = ledgers.calls.size
                    fixture.coordinator.requestFeedRefresh(HistoryFeed.LEDGERS)
                    awaitQuiescent({ trades.calls.size }, { ledgers.calls.size })

                    trades.calls.size shouldBe tradesBefore
                    (ledgers.calls.size > ledgersBefore) shouldBe true
                } finally {
                    fixture.coordinator.stop()
                }
            }
        }

        "backfill pauses for a pending refresh and resumes from its committed cursor" {
            withFixture { fixture ->
                val trades = fixture.tradesScript()
                trades.onPage(0L, null, "T1", nextCursor = "C1")
                trades.onPage(0L, "C1", "T2", nextCursor = null)
                trades.beforeSupply = { startSec, cursor ->
                    if (startSec == 0L && cursor == null) fixture.coordinator.requestRecentRefresh()
                }

                fixture.coordinator.runCoalescedPass()

                trades.calls.count { it.startSec == 0L } shouldBe 1
                val paused = fixture.backfillJob()
                paused.state shouldBe HistorySyncJobState.RUNNING
                paused.cursor shouldBe "C1"

                trades.beforeSupply = { _, _ -> }
                fixture.coordinator.runCoalescedPass()

                trades.calls.count { it.startSec == 0L && it.cursor == "C1" } shouldBe 1
                val resumed = fixture.backfillJob()
                resumed.state shouldBe HistorySyncJobState.COMPLETE
                resumed.completedThroughSec shouldBe fixture.backfillEndSec
            }
        }

        "a partial recent scan resumes its frozen window before starting a newer one" {
            withFixture { fixture ->
                val now = fixture.clock.now
                fixture.store.upsertSyncJob(
                    HistorySyncJob(
                        accountIdentityDigest = fixture.digest,
                        feed = HistoryFeed.TRADES,
                        purpose = HistorySyncPurpose.RECENT,
                        state = HistorySyncJobState.RUNNING,
                        windowStartSec = fixture.recentStartSec,
                        windowEndSec = now.epochSecond,
                        cursor = "C1",
                        completedThroughSec = null,
                        lastError = null,
                        nextRetryAt = null,
                        lastSuccessAt = null,
                        updatedAt = now,
                    ),
                )
                val trades = fixture.tradesScript()
                trades.onPage(fixture.recentStartSec, "C1", "T2", nextCursor = null)

                fixture.coordinator.runCoalescedPass()

                trades.calls.count { it.startSec == fixture.recentStartSec && it.cursor == "C1" } shouldBe 1
                fixture.recentJob().state shouldBe HistorySyncJobState.COMPLETE
            }
        }

        "a repair scan yields to a pending recent refresh and keeps its cursor" {
            withFixture { fixture ->
                val repairStart = fixture.clock.now.epochSecond - HistorySyncCoordinator.REPAIR_WINDOW_SECONDS
                val trades = fixture.tradesScript()
                trades.onPage(repairStart, null, "R1", nextCursor = "RC1")
                trades.beforeSupply = { startSec, _ ->
                    if (startSec == repairStart) fixture.coordinator.requestRecentRefresh()
                }

                fixture.coordinator.runCoalescedPass()

                val repair = fixture.store.loadSyncJobs(fixture.digest)
                    .single { it.purpose == HistorySyncPurpose.REPAIR && it.feed == HistoryFeed.TRADES }
                repair.state shouldBe HistorySyncJobState.RUNNING
                repair.cursor shouldBe "RC1"
                trades.calls.count { it.startSec == repairStart } shouldBe 1
            }
        }

        "a repair scan resumes from its committed cursor and frozen window" {
            withFixture { fixture ->
                val now = fixture.clock.now
                val repairStart = now.epochSecond - 3_600L
                fixture.store.upsertSyncJob(
                    HistorySyncJob(
                        accountIdentityDigest = fixture.digest,
                        feed = HistoryFeed.TRADES,
                        purpose = HistorySyncPurpose.REPAIR,
                        state = HistorySyncJobState.RUNNING,
                        windowStartSec = repairStart,
                        windowEndSec = now.epochSecond,
                        cursor = "RC1",
                        completedThroughSec = null,
                        lastError = null,
                        nextRetryAt = null,
                        lastSuccessAt = null,
                        updatedAt = now,
                    ),
                )
                val trades = fixture.tradesScript()
                trades.onPage(repairStart, "RC1", "R2", nextCursor = null)

                fixture.coordinator.runCoalescedPass()

                trades.calls.count { it.startSec == repairStart && it.cursor == "RC1" } shouldBe 1
                val repair = fixture.store.loadSyncJobs(fixture.digest)
                    .single { it.purpose == HistorySyncPurpose.REPAIR && it.feed == HistoryFeed.TRADES }
                repair.state shouldBe HistorySyncJobState.COMPLETE
                repair.windowStartSec shouldBe repairStart
            }
        }

        "a failed recent scan resumes from its committed cursor and frozen window" {
            withFixture { fixture ->
                val now = fixture.clock.now
                fixture.store.upsertSyncJob(
                    HistorySyncJob(
                        accountIdentityDigest = fixture.digest,
                        feed = HistoryFeed.TRADES,
                        purpose = HistorySyncPurpose.RECENT,
                        state = HistorySyncJobState.FAILED,
                        windowStartSec = fixture.recentStartSec,
                        windowEndSec = now.epochSecond,
                        cursor = "C1",
                        completedThroughSec = null,
                        lastError = HistorySyncErrors.PAGE_FAILED,
                        nextRetryAt = null,
                        lastSuccessAt = null,
                        updatedAt = now,
                    ),
                )
                val trades = fixture.tradesScript()
                trades.onPage(fixture.recentStartSec, "C1", "T2", nextCursor = null)

                fixture.coordinator.runCoalescedPass()

                trades.calls.count { it.startSec == fixture.recentStartSec && it.cursor == "C1" } shouldBe 1
                val job = fixture.recentJob()
                job.state shouldBe HistorySyncJobState.COMPLETE
                job.windowStartSec shouldBe fixture.recentStartSec
            }
        }

        "backfill jobs that are blocked or inverted are settled without fetching their windows" {
            withFixture { fixture ->
                val now = fixture.clock.now
                fixture.store.upsertSyncJob(
                    HistorySyncJob(
                        accountIdentityDigest = fixture.digest,
                        feed = HistoryFeed.TRADES,
                        purpose = HistorySyncPurpose.BACKFILL,
                        state = HistorySyncJobState.PENDING,
                        windowStartSec = 500L,
                        windowEndSec = 100L,
                        cursor = null,
                        completedThroughSec = null,
                        lastError = null,
                        nextRetryAt = null,
                        lastSuccessAt = null,
                        updatedAt = now,
                    ),
                )
                fixture.store.upsertSyncJob(
                    HistorySyncJob(
                        accountIdentityDigest = fixture.digest,
                        feed = HistoryFeed.LEDGERS,
                        purpose = HistorySyncPurpose.BACKFILL,
                        state = HistorySyncJobState.BLOCKED,
                        windowStartSec = 0L,
                        windowEndSec = 100L,
                        cursor = "L1",
                        completedThroughSec = null,
                        lastError = HistorySyncErrors.ACCOUNT_SCOPE_CHANGED,
                        nextRetryAt = null,
                        lastSuccessAt = null,
                        updatedAt = now,
                    ),
                )
                val trades = fixture.tradesScript()

                fixture.coordinator.runCoalescedPass()

                trades.calls.none { it.startSec == 0L } shouldBe true
                fixture.backfillJob(HistoryFeed.TRADES).state shouldBe HistorySyncJobState.COMPLETE
                fixture.backfillJob(HistoryFeed.LEDGERS).state shouldBe HistorySyncJobState.BLOCKED
            }
        }

        "simulation or unavailable settings leave history sync inactive" {
            withFixture { fixture ->
                fixture.settings = fixture.settings.copy(simulation = true)
                fixture.coordinator.runCoalescedPass()
                fixture.store.loadSyncJobs(fixture.digest) shouldBe emptyList()

                val broken = HistorySyncCoordinator(
                    applicationScope = fixture.applicationScope,
                    synchronizer = fixture.synchronizer,
                    store = fixture.store,
                    settingsProvider = { error("config unavailable") },
                    nowProvider = { fixture.clock.now },
                    delayFn = { fixture.delays.add(it) },
                    retryPolicy = HistorySyncRetryPolicy(jitter = { it }),
                )
                broken.runCoalescedPass()
                fixture.store.loadSyncJobs(fixture.digest) shouldBe emptyList()
            }
        }

        "cancellation from the settings provider propagates out of the sync pass" {
            withFixture { fixture ->
                val cancellation = CancellationException("settings provider cancelled")
                val coordinator = HistorySyncCoordinator(
                    applicationScope = fixture.applicationScope,
                    synchronizer = fixture.synchronizer,
                    store = fixture.store,
                    settingsProvider = { throw cancellation },
                    nowProvider = { fixture.clock.now },
                    delayFn = { fixture.delays.add(it) },
                    retryPolicy = HistorySyncRetryPolicy(jitter = { it }),
                )
                var caught: CancellationException? = null

                try {
                    coordinator.runCoalescedPass()
                } catch (error: CancellationException) {
                    caught = error
                }

                caught shouldBe cancellation
                fixture.store.loadSyncJobs(fixture.digest) shouldBe emptyList()
            }
        }

        "the automatic sync worker survives a failed pass and keeps serving later triggers" {
            withFixture { fixture ->
                val trades = fixture.tradesScript()
                trades.onPage(fixture.recentStartSec, null, "T1", nextCursor = null)

                var storeFailures = 1
                val flakyStore = mockk<HistoryStore>(relaxed = true)
                coEvery { flakyStore.loadSyncJobs(any()) } coAnswers {
                    if (storeFailures > 0) {
                        storeFailures--
                        throw IllegalStateException("history store briefly unavailable")
                    }
                    emptyList()
                }
                val coordinator = HistorySyncCoordinator(
                    applicationScope = fixture.applicationScope,
                    synchronizer = fixture.synchronizer,
                    store = flakyStore,
                    settingsProvider = { fixture.settings },
                    nowProvider = { fixture.clock.now },
                    delayFn = { fixture.delays.add(it) },
                    retryPolicy = HistorySyncRetryPolicy(jitter = { it }),
                )

                coordinator.start()
                try {
                    coordinator.requestRecentRefresh()
                    awaitTrue { storeFailures == 0 }

                    coordinator.requestRecentRefresh()
                    awaitTrue { trades.calls.count { it.startSec == fixture.recentStartSec } >= 1 }
                    coordinator.requestRecentRefresh()
                    awaitQuiescent({ trades.calls.size })

                    trades.calls.count { it.startSec == fixture.recentStartSec } shouldBe 2
                    coordinator.blockedReason() shouldBe null
                } finally {
                    coordinator.stop()
                }
            }
        }

        "the default retry jitter stays within one quarter of its step delay" {
            val policy = HistorySyncRetryPolicy()
            repeat(16) { attempt ->
                val step = HistorySyncRetryPolicy.DEFAULT_STEPS[attempt.coerceAtMost(3)]
                val delayMs = policy.nextDelay(attempt).toMillis()
                (delayMs >= step.toMillis()) shouldBe true
                (delayMs <= step.toMillis() + step.toMillis() / 4) shouldBe true
            }
        }

        "a backfill job without a frozen window end is never fetched" {
            withFixture { fixture ->
                fixture.store.upsertSyncJob(
                    HistorySyncJob(
                        accountIdentityDigest = fixture.digest,
                        feed = HistoryFeed.TRADES,
                        purpose = HistorySyncPurpose.BACKFILL,
                        state = HistorySyncJobState.PENDING,
                        windowStartSec = 0L,
                        windowEndSec = null,
                        cursor = "C1",
                        completedThroughSec = null,
                        lastError = null,
                        nextRetryAt = null,
                        lastSuccessAt = null,
                        updatedAt = fixture.clock.now,
                    ),
                )
                val trades = fixture.tradesScript()

                fixture.coordinator.runCoalescedPass()

                trades.calls.none { it.startSec == 0L } shouldBe true
                fixture.backfillJob().windowEndSec shouldBe null
            }
        }

        "restriction changes mid-scan block sync until an operator retry" {
            withFixture { fixture ->
                val trades = fixture.tradesScript()
                trades.onPage(fixture.recentStartSec, null, "T1", nextCursor = "C1")
                trades.onPage(fixture.recentStartSec, "C1", "T2", nextCursor = null)
                var restricted = false
                trades.beforeSupply = { _, cursor ->
                    if (cursor == "C1" && !restricted) {
                        restricted = true
                        fixture.kraken.authenticatedAccountInfoFactory = { identity ->
                            AuthenticatedAccountInfo(
                                identity = identity,
                                queryFromSec = fixture.recentStartSec + 1,
                                queryToSec = fixture.recentStartSec + 2,
                                queryBoundsVerified = true,
                            )
                        }
                    }
                }

                fixture.coordinator.runCoalescedPass()

                fixture.coordinator.blockedReason() shouldBe HistorySyncErrors.API_KEY_RESTRICTIONS_CHANGED
                fixture.recentJob().state shouldBe HistorySyncJobState.BLOCKED
            }
        }

        "retry policy escalates through the 1, 2, 5 and 15 minute steps" {
            val policy = HistorySyncRetryPolicy(jitter = { it })
            policy.nextDelay(0) shouldBe Duration.ofMinutes(1)
            policy.nextDelay(1) shouldBe Duration.ofMinutes(2)
            policy.nextDelay(2) shouldBe Duration.ofMinutes(5)
            policy.nextDelay(3) shouldBe Duration.ofMinutes(15)
            policy.nextDelay(50) shouldBe Duration.ofMinutes(15)
        }
        "a long backfill yields to the periodic recent refresh without a manual request" {
            withFixture { fixture ->
                val trades = fixture.tradesScript()
                trades.onPage(0L, null, "T1", nextCursor = "C1")
                trades.onPage(0L, "C1", "T2", nextCursor = "C2")
                trades.onPage(0L, "C2", "T3", nextCursor = null)
                var ticked = false
                trades.beforeSupply = { startSec, cursor ->
                    if (startSec == 0L && cursor == null && !ticked) {
                        ticked = true
                        fixture.emitPeriodicTick()
                    }
                }

                fixture.coordinator.start()
                try {
                    fixture.emitPeriodicTick()

                    awaitTrue { trades.calls.count { it.startSec == 0L } >= 1 }
                    awaitTrue { trades.calls.count { it.startSec == fixture.recentStartSec } >= 2 }
                    awaitQuiescent({ trades.calls.size })

                    // The periodic deadline interleaved a recent scan into the running backfill.
                    trades.calls.map { it.startSec }
                        .filter { it == fixture.recentStartSec || it == 0L } shouldBe listOf(
                        fixture.recentStartSec,
                        0L,
                        fixture.recentStartSec,
                        0L,
                        0L,
                    )
                    fixture.backfillJob().state shouldBe HistorySyncJobState.COMPLETE
                } finally {
                    fixture.coordinator.stop()
                }
            }
        }

        "a failing feed escalates its own retry while a healthy feed keeps syncing" {
            withFixture { fixture ->
                val trades = fixture.tradesScript()
                trades.failWhen = { _, _ -> true }
                val ledgers = fixture.ledgersScript()

                fixture.coordinator.runCoalescedPass()

                val failedJob = fixture.recentJob()
                failedJob.state shouldBe HistorySyncJobState.FAILED
                failedJob.lastError shouldBe HistorySyncErrors.PAGE_FAILED
                failedJob.nextRetryAt shouldBe fixture.clock.now.plus(Duration.ofMinutes(1))
                val ledgersAfterFirstPass = ledgers.calls.size

                fixture.clock.advance(Duration.ofMinutes(2))
                fixture.coordinator.runCoalescedPass()

                fixture.recentJob().nextRetryAt shouldBe fixture.clock.now.plus(Duration.ofMinutes(2))
                (ledgers.calls.size > ledgersAfterFirstPass) shouldBe true

                fixture.clock.advance(Duration.ofMinutes(6))
                fixture.coordinator.runCoalescedPass()

                fixture.recentJob().nextRetryAt shouldBe fixture.clock.now.plus(Duration.ofMinutes(5))

                fixture.clock.advance(Duration.ofMinutes(16))
                fixture.coordinator.runCoalescedPass()

                fixture.recentJob().nextRetryAt shouldBe fixture.clock.now.plus(Duration.ofMinutes(15))
            }
        }

        "a recovered feed clears its persisted failure and retry schedule" {
            withFixture { fixture ->
                val trades = fixture.tradesScript()
                trades.failWhen = { _, _ -> true }

                fixture.coordinator.runCoalescedPass()
                fixture.recentJob().nextRetryAt shouldNotBe null

                trades.failWhen = { _, _ -> false }
                fixture.clock.advance(Duration.ofMinutes(2))
                fixture.coordinator.runCoalescedPass()

                val recovered = fixture.recentJob()
                recovered.nextRetryAt shouldBe null
                recovered.lastError shouldBe null
            }
        }

        "a changed account scope blocks the sync and marks the job as blocked" {
            withFixture { fixture ->
                val script = fixture.tradesScript()
                val delegate = fixture.kraken.tradePageSupplier
                fixture.kraken.tradePageSupplier = { startSec, endSec, cursor, limit ->
                    // The account identity changes while the page is in flight.
                    fixture.kraken.authenticatedAccountInfoFactory = { _ ->
                        AuthenticatedAccountInfo("BB11 OTHER ACCOUNT", queryBoundsVerified = true)
                    }
                    delegate(startSec, endSec, cursor, limit)
                }
                script.onPage(fixture.recentStartSec, null, "T1", nextCursor = null)

                fixture.coordinator.runCoalescedPass()

                fixture.coordinator.blockedReason() shouldBe HistorySyncErrors.ACCOUNT_SCOPE_CHANGED
                val blocked = fixture.recentJob()
                blocked.state shouldBe HistorySyncJobState.BLOCKED
                blocked.lastError shouldBe HistorySyncErrors.ACCOUNT_SCOPE_CHANGED
                blocked.nextRetryAt shouldBe null
            }
        }

        "a window outside the API key bounds fails closed and stays retryable" {
            withFixture { fixture ->
                fixture.kraken.authenticatedAccountInfoFactory = { identity ->
                    AuthenticatedAccountInfo(
                        identity = identity,
                        queryFromSec = fixture.clock.now.epochSecond + 3_600,
                        queryToSec = fixture.clock.now.epochSecond + 7_200,
                        queryBoundsVerified = true,
                    )
                }

                fixture.coordinator.runCoalescedPass()

                val skipped = fixture.recentJob()
                skipped.state shouldBe HistorySyncJobState.FAILED
                skipped.lastError shouldBe HistorySyncErrors.API_KEY_WINDOW_NO_OVERLAP
                skipped.completedThroughSec shouldBe null
                skipped.nextRetryAt shouldBe fixture.clock.now.plus(Duration.ofMinutes(1))
            }
        }

        "a failing feed keeps its own escalation while the other feed keeps syncing" {
            withFixture { fixture ->
                val coordinator = fixture.coordinatorWithFailingSynchronizer(
                    IllegalStateException("exchange page unavailable " + "A".repeat(40) + "\nsecond line"),
                )

                coordinator.runCoalescedPass()

                val failed = fixture.recentJob()
                failed.state shouldBe HistorySyncJobState.FAILED
                failed.lastError shouldBe "exchange page unavailable [redacted]"
                failed.nextRetryAt shouldBe fixture.clock.now.plus(Duration.ofMinutes(1))
                coordinator.nextRetryAt() shouldBe failed.nextRetryAt
                // A single failing feed is not an account-wide failure.
                coordinator.lastFailure() shouldBe null
            }
        }

        "an unavailable account stops the pass and reports the failure without a retry job" {
            withFixture { fixture ->
                fixture.kraken.authenticatedAccountIdentitySupplier = { null }

                fixture.coordinator.runCoalescedPass()

                fixture.coordinator.lastFailure() shouldBe "History sync could not verify the authenticated account."
                fixture.coordinator.nextRetryAt() shouldNotBe null
                fixture.store.loadSyncJobs(fixture.digest) shouldBe emptyList()
            }
        }
    }

    private class FakeClock(var now: Instant) {
        fun advance(duration: Duration) {
            now = now.plus(duration)
        }
    }

    private data class PageCall(val startSec: Long?, val cursor: String?)

    private data class PageSpec(val ids: List<String>, val nextCursor: String?)

    private class TradeScript {
        val calls = CopyOnWriteArrayList<PageCall>()
        var failWhen: (Long?, String?) -> Boolean = { _, _ -> false }
        var beforeSupply: (Long?, String?) -> Unit = { _, _ -> }
        private val specs = mutableMapOf<Pair<Long?, String?>, PageSpec>()

        fun onPage(startSec: Long?, cursor: String?, vararg ids: String, nextCursor: String?) {
            specs[startSec to cursor] = PageSpec(ids.toList(), nextCursor)
        }

        fun supply(startSec: Long?, endSec: Long?, cursor: String?): ExchangeHistoryPage<ExchangeTradeHistoryEntry> {
            calls.add(PageCall(startSec, cursor))
            beforeSupply(startSec, cursor)
            if (failWhen(startSec, cursor)) throw IllegalStateException("exchange page unavailable")
            val spec = specs[startSec to cursor]
            return ExchangeHistoryPage(
                entries = spec?.ids.orEmpty().map(::tradeEntry),
                startSec = startSec,
                endSec = endSec,
                requestedCursor = cursor,
                nextCursor = spec?.nextCursor,
                count = spec?.ids?.size?.toLong() ?: 0L,
                rawSize = spec?.ids?.size ?: 0,
                valid = true,
            )
        }
    }

    private class LedgerScript {
        val calls = CopyOnWriteArrayList<PageCall>()
        private val specs = mutableMapOf<Pair<Long?, String?>, PageSpec>()

        fun onPage(startSec: Long?, cursor: String?, vararg ids: String, nextCursor: String?) {
            specs[startSec to cursor] = PageSpec(ids.toList(), nextCursor)
        }

        fun supply(startSec: Long?, endSec: Long?, cursor: String?): ExchangeHistoryPage<ExchangeLedgerHistoryEntry> {
            calls.add(PageCall(startSec, cursor))
            val spec = specs[startSec to cursor]
            return ExchangeHistoryPage(
                entries = spec?.ids.orEmpty().map(::ledgerEntry),
                startSec = startSec,
                endSec = endSec,
                requestedCursor = cursor,
                nextCursor = spec?.nextCursor,
                count = spec?.ids?.size?.toLong() ?: 0L,
                rawSize = spec?.ids?.size ?: 0,
                valid = true,
            )
        }
    }

    private class Fixture(val clock: FakeClock) {
        val database = HistoryDatabase.inMemory().apply { initializeFreshSchema() }
        val store = SqliteHistoryStore(database, nowProvider = { clock.now })
        val kraken = FakeKrakenService()
        val configService = mockk<ConfigService>(relaxed = true)
        val identity = mockk<DataDirectoryIdentity>(relaxed = true)
        val delays = mutableListOf<Duration>()
        val applicationScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val periodicTicks = Channel<Unit>(Channel.CONFLATED)
        var settings = TestFixtures.settings(simulation = false, dryRun = true)
        val recentStartSec = clock.now.epochSecond - HistorySyncCoordinator.RECENT_FIRST_WINDOW_SECONDS
        val backfillEndSec = recentStartSec

        val synchronizer = HistorySynchronizer(
            applicationScope = applicationScope,
            krakenService = kraken,
            configService = configService,
            dataDirectoryIdentity = identity,
            store = store,
            nowProvider = { clock.now },
        )

        val digest: String = run {
            val accountIdentity = "AA00 TEST TEST TEST"
            MessageDigest.getInstance("SHA-256")
                .digest(
                    accountIdentity.filterNot(Char::isWhitespace).uppercase(Locale.ROOT)
                        .toByteArray(Charsets.UTF_8),
                )
                .joinToString("") { byte -> "%02x".format(byte) }
        }

        val coordinator = newCoordinator()

        fun restartCoordinator(): HistorySyncCoordinator = newCoordinator()

        /** A coordinator whose exchange calls fail with the given error, for the failure paths. */
        fun coordinatorWithFailingSynchronizer(error: Throwable): HistorySyncCoordinator {
            val failing = mockk<HistorySynchronizer>()
            coEvery { failing.verifiedAccount() } returns VerifiedHistoryAccount(
                digest = digest,
                queryFromSec = null,
                queryToSec = null,
            )
            coEvery {
                failing.syncWindow(any(), any(), any(), any())
            } throws error
            coEvery {
                failing.syncWindow(any(), any(), any(), any(), any(), any())
            } throws error
            return HistorySyncCoordinator(
                applicationScope = applicationScope,
                synchronizer = failing,
                store = store,
                settingsProvider = { settings },
                nowProvider = { clock.now },
                delayFn = { duration ->
                    delays.add(duration)
                    clock.advance(duration)
                },
                retryPolicy = HistorySyncRetryPolicy(jitter = { it }),
                periodicTicks = periodicTicks.receiveAsFlow(),
            )
        }

        /** Emits the periodic recent-refresh deadline without any manual refresh request. */
        fun emitPeriodicTick() {
            periodicTicks.trySend(Unit)
        }

        private fun newCoordinator() = HistorySyncCoordinator(
            applicationScope = applicationScope,
            synchronizer = synchronizer,
            store = store,
            settingsProvider = { settings },
            nowProvider = { clock.now },
            delayFn = { duration ->
                delays.add(duration)
                clock.advance(duration)
            },
            retryPolicy = HistorySyncRetryPolicy(jitter = { it }),
            periodicTicks = periodicTicks.receiveAsFlow(),
        )

        fun tradesScript(): TradeScript {
            val script = TradeScript()
            kraken.tradePageSupplier = { startSec, endSec, cursor, _ -> script.supply(startSec, endSec, cursor) }
            return script
        }

        fun ledgersScript(): LedgerScript {
            val script = LedgerScript()
            kraken.ledgerPageSupplier = { startSec, endSec, cursor -> script.supply(startSec, endSec, cursor) }
            return script
        }

        suspend fun tradeCount(): Long = countRows("history_trades")

        suspend fun ledgerCount(): Long = countRows("history_ledgers")

        suspend fun recentJob(): HistorySyncJob = store.loadSyncJobs(digest).first {
            it.purpose == HistorySyncPurpose.RECENT && it.feed == HistoryFeed.TRADES
        }

        suspend fun backfillJob(feed: HistoryFeed = HistoryFeed.TRADES): HistorySyncJob =
            store.loadSyncJobs(digest).first {
                it.purpose == HistorySyncPurpose.BACKFILL && it.feed == feed
            }

        private suspend fun countRows(table: String): Long {
            var total = 0L
            database.connect().use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM $table").use { resultSet ->
                        check(resultSet.next())
                        total = resultSet.getLong(1)
                    }
                }
            }
            return total
        }
    }

    private suspend fun awaitTrue(condition: suspend () -> Boolean) {
        repeat(200) {
            if (condition()) return
            delay(5)
        }
        error("condition was not met while waiting for the coordinator worker")
    }

    private suspend fun awaitQuiescent(vararg counters: () -> Int) {
        var stable = 0
        repeat(250) {
            val snapshot = counters.map { it() }
            delay(20)
            if (counters.map { it() } == snapshot) {
                if (++stable >= 5) return
            } else {
                stable = 0
            }
        }
        error("the coordinator worker did not settle")
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture(FakeClock(Instant.parse("2026-06-01T00:00:00Z")))
        fixture.kraken.fundingEvidenceScopeSupplier = { "fake-account" }
        fixture.kraken.authenticatedAccountIdentitySupplier = { "AA00 TEST TEST TEST" }
        block(fixture)
        fixture.database.close()
    }
}

private fun tradeEntry(id: String) = ExchangeTradeHistoryEntry(
    tradeId = id,
    orderTxid = "O-$id",
    postTxid = null,
    pair = "XXBTZUSD",
    baseAsset = "BTC",
    quoteCurrency = "USD",
    feeCurrency = "USD",
    time = Instant.parse("2026-05-01T00:00:00Z"),
    rawTime = "1777584000",
    type = "buy",
    orderType = "limit",
    price = "50000",
    cost = "5000",
    fee = "8",
    volume = "0.1",
    margin = "0",
    misc = "",
    tradeSequenceId = id,
    maker = true,
    ledgerIds = emptyList(),
    rawRecordJson = "{}",
    hasValidTime = true,
    hasValidPrice = true,
    hasValidCost = true,
    hasValidFee = true,
    hasValidVolume = true,
    isValidRecord = true,
)

private fun ledgerEntry(id: String) = ExchangeLedgerHistoryEntry(
    ledgerId = id,
    refid = "R-$id",
    time = Instant.parse("2026-05-01T00:00:00Z"),
    rawTime = "1777584000",
    type = "trade",
    subtype = "",
    assetClass = "currency",
    asset = "XXBT",
    amount = "0.1",
    fee = "8",
    balance = "1.0",
    rawRecordJson = "{}",
    hasValidTime = true,
    hasValidAmount = true,
    hasValidFee = true,
    hasValidBalance = true,
    isValidRecord = true,
)
