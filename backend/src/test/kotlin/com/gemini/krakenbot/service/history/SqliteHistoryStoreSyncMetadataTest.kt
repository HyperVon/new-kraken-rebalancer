package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.model.ExchangeHistoryPage
import com.gemini.krakenbot.model.ExchangeTradeHistoryEntry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class SqliteHistoryStoreSyncMetadataTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val accountDigest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val fingerprint = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"

    private fun testScope(symbols: List<String> = listOf("BTC", "USD")) = HistorySourceScope(
        mode = HistorySourceMode.ACCOUNT,
        managedScopeFingerprint = fingerprint,
        managedScopeSymbols = symbols,
        accountIdentityDigest = accountDigest,
    )

    private fun activity(
        id: String,
        time: Instant,
        outcome: HistoryCycleOutcome?,
        scope: HistorySourceScope = testScope(),
    ) = HistoryCycleActivity(
        cycleId = id,
        sourceScope = scope,
        occurredAt = time,
        dryRun = true,
        actions = listOf("ACTION-$id"),
        outcome = outcome,
    )

    private fun observation(
        id: String,
        time: Instant,
        status: HistoryObservationStatus = HistoryObservationStatus.COMPLETE,
        scope: HistorySourceScope = testScope(),
    ) = HistoryObservation(
        observationId = id,
        cycleId = "cycle-$id",
        sourceScope = scope,
        observedAt = time,
        balanceRequestStartedAt = time,
        balanceResponseEndedAt = time,
        priceRequestStartedAt = time,
        priceResponseEndedAt = time,
        status = status,
        totalUsd = if (status == HistoryObservationStatus.COMPLETE) BigDecimal("100.00") else null,
        incompleteReasons = emptyList(),
        assets = emptyList(),
    )

    private fun syncJob(
        feed: HistoryFeed,
        purpose: HistorySyncPurpose,
        state: HistorySyncJobState = HistorySyncJobState.PENDING,
        cursor: String? = null,
        completedThroughSec: Long? = null,
        lastError: String? = null,
        nextRetryAt: Instant? = null,
        lastSuccessAt: Instant? = null,
        updatedAt: Instant = Instant.ofEpochSecond(1_700_000_000),
    ) = HistorySyncJob(
        accountIdentityDigest = accountDigest,
        feed = feed,
        purpose = purpose,
        state = state,
        windowStartSec = 0L,
        windowEndSec = 100L,
        cursor = cursor,
        completedThroughSec = completedThroughSec,
        lastError = lastError,
        nextRetryAt = nextRetryAt,
        lastSuccessAt = lastSuccessAt,
        updatedAt = updatedAt,
    )

    init {
        "data revision advances with committed observations and cycle activity" {
            withStore { store, _ ->
                store.currentDataRevision() shouldBe 1L

                store.appendObservation(observation("obs-1", Instant.ofEpochSecond(1_700_000_000))) shouldBe true
                store.currentDataRevision() shouldBe 2L

                store.appendCycleActivity(activity("cycle-1", Instant.ofEpochSecond(1_700_000_010), null)) shouldBe true
                store.currentDataRevision() shouldBe 3L

                // Duplicate rows are rejected without advancing the revision.
                store.appendCycleActivity(activity("cycle-1", Instant.ofEpochSecond(1_700_000_010), null)) shouldBe
                    false
                store.currentDataRevision() shouldBe 3L
            }
        }

        "persisted page progress never regresses the completed-through horizon" {
            withStore { store, _ ->
                val now = Instant.ofEpochSecond(1_700_000_000)

                store.beginSyncWindow(
                    HistorySyncWindow(
                        accountIdentityDigest = accountDigest,
                        feed = HistoryFeed.TRADES,
                        scanId = "scan-1",
                        startSec = 0L,
                        endSec = 1_000L,
                        cursor = null,
                        purpose = HistorySyncPurpose.RECENT,
                    ),
                )
                store.commitTradePage(
                    request = HistoryPageRequest(
                        accountIdentityDigest = accountDigest,
                        feed = HistoryFeed.TRADES,
                        scanId = "scan-1",
                        startSec = 0L,
                        endSec = 1_000L,
                        requestedCursor = null,
                        committedAt = now,
                    ),
                    page = emptyTradePage(0L, 1_000L, null, nextCursor = "C1"),
                ).state shouldBe HistorySyncState.RUNNING

                var job = store.loadSyncJobs(accountDigest).single()
                job.cursor shouldBe "C1"
                job.completedThroughSec shouldBe null

                store.commitTradePage(
                    request = HistoryPageRequest(
                        accountIdentityDigest = accountDigest,
                        feed = HistoryFeed.TRADES,
                        scanId = "scan-1",
                        startSec = 0L,
                        endSec = 1_000L,
                        requestedCursor = "C1",
                        committedAt = now.plusSeconds(1),
                    ),
                    page = emptyTradePage(0L, 1_000L, "C1", nextCursor = null),
                ).state shouldBe HistorySyncState.COMPLETE

                job = store.loadSyncJobs(accountDigest).single()
                job.state shouldBe HistorySyncJobState.COMPLETE
                job.completedThroughSec shouldBe 1_000L

                store.beginSyncWindow(
                    HistorySyncWindow(
                        accountIdentityDigest = accountDigest,
                        feed = HistoryFeed.TRADES,
                        scanId = "scan-2",
                        startSec = 0L,
                        endSec = 200L,
                        cursor = null,
                        purpose = HistorySyncPurpose.RECENT,
                    ),
                )
                store.commitTradePage(
                    request = HistoryPageRequest(
                        accountIdentityDigest = accountDigest,
                        feed = HistoryFeed.TRADES,
                        scanId = "scan-2",
                        startSec = 0L,
                        endSec = 200L,
                        requestedCursor = null,
                        committedAt = now.plusSeconds(2),
                    ),
                    page = emptyTradePage(0L, 200L, null, nextCursor = null),
                ).state shouldBe HistorySyncState.COMPLETE

                job = store.loadSyncJobs(accountDigest).single()
                job.completedThroughSec shouldBe 1_000L
            }
        }

        "successive completed scans accumulate durable coverage instead of replacing it" {
            withStore { store, _ ->
                val now = Instant.parse("2026-05-01T00:00:00Z")
                listOf(0L to 1_000L, 1_000L to 2_000L, 2_000L to 3_000L).forEachIndexed { index, window ->
                    store.beginSyncWindow(
                        HistorySyncWindow(
                            accountIdentityDigest = accountDigest,
                            feed = HistoryFeed.TRADES,
                            scanId = "scan-$index",
                            startSec = window.first,
                            endSec = window.second,
                            cursor = null,
                            purpose = HistorySyncPurpose.RECENT,
                        ),
                    )
                    store.commitTradePage(
                        request = HistoryPageRequest(
                            accountIdentityDigest = accountDigest,
                            feed = HistoryFeed.TRADES,
                            scanId = "scan-$index",
                            startSec = window.first,
                            endSec = window.second,
                            requestedCursor = null,
                            committedAt = now.plusSeconds(index.toLong()),
                        ),
                        page = emptyTradePage(window.first, window.second, null, nextCursor = null),
                    ).state shouldBe HistorySyncState.COMPLETE
                }

                store.querySyncCoverageWindows(accountDigest, HistoryFeed.TRADES) shouldBe listOf(
                    HistorySyncCoverageWindow(startSec = 0L, endSec = 3_000L),
                )
                val progress = store.syncProgress(accountDigest).single { it.feed == HistoryFeed.TRADES }
                progress.coveredWindows shouldBe listOf(HistorySyncCoverageWindow(startSec = 0L, endSec = 3_000L))
            }
        }

        "activity pages order newest first with stable pagination and round-trip outcomes" {
            withStore { store, _ ->
                val base = Instant.ofEpochSecond(1_700_000_000)
                store.appendCycleActivity(activity("cycle-a", base, HistoryCycleOutcome.NO_TRADE)) shouldBe true
                store.appendCycleActivity(
                    activity("cycle-b", base.plusSeconds(1), HistoryCycleOutcome.DRY_RUN_PLAN),
                ) shouldBe
                    true
                store.appendCycleActivity(
                    activity("cycle-c", base.plusSeconds(2), HistoryCycleOutcome.ORDERS_ATTEMPTED),
                ) shouldBe true
                store.appendCycleActivity(
                    activity("cycle-d", base.plusSeconds(3), HistoryCycleOutcome.PROBLEM),
                ) shouldBe
                    true

                val firstPage = store.queryActivityPage(
                    HistoryActivityQuery(sourceScope = testScope(), limit = 2, offset = 0),
                )
                firstPage.total shouldBe 4L
                firstPage.hasMore shouldBe true
                firstPage.items.map { it.cycleId } shouldBe listOf("cycle-d", "cycle-c")
                firstPage.items.map { it.outcome } shouldBe listOf(
                    HistoryCycleOutcome.PROBLEM,
                    HistoryCycleOutcome.ORDERS_ATTEMPTED,
                )

                val secondPage = store.queryActivityPage(
                    HistoryActivityQuery(sourceScope = testScope(), limit = 2, offset = 2),
                )
                secondPage.hasMore shouldBe false
                secondPage.items.map { it.cycleId } shouldBe listOf("cycle-b", "cycle-a")

                val untilFiltered = store.queryActivityPage(
                    HistoryActivityQuery(
                        sourceScope = testScope(),
                        from = base.plusSeconds(1),
                        until = base.plusSeconds(3),
                    ),
                )
                untilFiltered.items.map { it.cycleId } shouldBe listOf("cycle-c", "cycle-b")

                val otherScope = HistorySourceScope(
                    mode = HistorySourceMode.ACCOUNT,
                    managedScopeFingerprint = "a".repeat(64),
                    managedScopeSymbols = listOf("ETH", "USD"),
                    accountIdentityDigest = accountDigest,
                )
                store.appendCycleActivity(
                    activity("cycle-x", base.plusSeconds(4), HistoryCycleOutcome.NO_TRADE, scope = otherScope),
                ) shouldBe true
                store.queryActivityPage(HistoryActivityQuery(sourceScope = otherScope)).items.map {
                    it.cycleId
                } shouldBe
                    listOf("cycle-x")

                shouldThrow<IllegalArgumentException> {
                    HistoryActivityQuery(sourceScope = testScope(), from = base, until = base.minusSeconds(1))
                }
            }
        }

        "page commits reject metadata that does not match its request" {
            withStore { store, _ ->
                val now = Instant.ofEpochSecond(1_700_000_000)
                store.beginSyncWindow(
                    HistorySyncWindow(
                        accountIdentityDigest = accountDigest,
                        feed = HistoryFeed.TRADES,
                        scanId = "scan-1",
                        startSec = 0L,
                        endSec = 1_000L,
                    ),
                )

                val status = store.commitTradePage(
                    request = HistoryPageRequest(
                        accountIdentityDigest = accountDigest,
                        feed = HistoryFeed.TRADES,
                        scanId = "scan-1",
                        startSec = 0L,
                        endSec = 1_000L,
                        requestedCursor = null,
                        committedAt = now,
                    ),
                    page = emptyTradePage(5L, 1_000L, null, nextCursor = null),
                )

                status.state shouldBe HistorySyncState.FAILED
                status.lastError shouldBe "History page metadata did not match its request."
            }
        }

        "page commits reject stalled and repeated cursors within a scan" {
            withStore { store, _ ->
                val now = Instant.ofEpochSecond(1_700_000_000)

                fun request(scanId: String, cursor: String?, committedAt: Instant) = HistoryPageRequest(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.TRADES,
                    scanId = scanId,
                    startSec = 0L,
                    endSec = 1_000L,
                    requestedCursor = cursor,
                    committedAt = committedAt,
                )

                store.beginSyncWindow(
                    HistorySyncWindow(
                        accountIdentityDigest = accountDigest,
                        feed = HistoryFeed.TRADES,
                        scanId = "scan-stall",
                        startSec = 0L,
                        endSec = 1_000L,
                    ),
                )
                store.commitTradePage(
                    request = request("scan-stall", null, now),
                    page = emptyTradePage(0L, 1_000L, null, nextCursor = "C1"),
                ).state shouldBe HistorySyncState.RUNNING
                store.commitTradePage(
                    request = request("scan-stall", "C1", now.plusSeconds(1)),
                    page = emptyTradePage(0L, 1_000L, "C1", nextCursor = "C1"),
                ).lastError shouldBe "Exchange trade cursor did not advance."

                store.beginSyncWindow(
                    HistorySyncWindow(
                        accountIdentityDigest = accountDigest,
                        feed = HistoryFeed.TRADES,
                        scanId = "scan-repeat",
                        startSec = 0L,
                        endSec = 1_000L,
                    ),
                )
                store.commitTradePage(
                    request = request("scan-repeat", null, now.plusSeconds(2)),
                    page = emptyTradePage(0L, 1_000L, null, nextCursor = "C1"),
                ).state shouldBe HistorySyncState.RUNNING
                store.commitTradePage(
                    request = request("scan-repeat", "C1", now.plusSeconds(3)),
                    page = emptyTradePage(0L, 1_000L, "C1", nextCursor = "C2"),
                ).state shouldBe HistorySyncState.RUNNING
                store.commitTradePage(
                    request = request("scan-repeat", "C2", now.plusSeconds(4)),
                    page = emptyTradePage(0L, 1_000L, "C2", nextCursor = "C1"),
                ).lastError shouldBe "Exchange trade cursor repeated within the current scan."
            }
        }

        "a scan fails instead of paging past the configured page limit" {
            withStore(maxPagesPerScan = 2) { store, _ ->
                val now = Instant.ofEpochSecond(1_700_000_000)
                store.beginSyncWindow(
                    HistorySyncWindow(
                        accountIdentityDigest = accountDigest,
                        feed = HistoryFeed.TRADES,
                        scanId = "scan-flood",
                        startSec = 0L,
                        endSec = 1_000L,
                    ),
                )

                fun request(cursor: String?, seconds: Long) = HistoryPageRequest(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.TRADES,
                    scanId = "scan-flood",
                    startSec = 0L,
                    endSec = 1_000L,
                    requestedCursor = cursor,
                    committedAt = now.plusSeconds(seconds),
                )

                store.commitTradePage(
                    request = request(null, 0),
                    page = emptyTradePage(0L, 1_000L, null, nextCursor = "C1"),
                ).state shouldBe HistorySyncState.RUNNING
                store.commitTradePage(
                    request = request("C1", 1),
                    page = emptyTradePage(0L, 1_000L, "C1", nextCursor = "C2"),
                ).state shouldBe HistorySyncState.RUNNING

                val flood = store.commitTradePage(
                    request = request("C2", 2),
                    page = emptyTradePage(0L, 1_000L, "C2", nextCursor = "C3"),
                )
                flood.state shouldBe HistorySyncState.FAILED
                flood.lastError shouldBe "History trade scan exceeded the 2 page limit."
            }
        }

        "repeated exchange record ids across shifted pages store each row once" {
            withStore { store, _ ->
                val now = Instant.ofEpochSecond(1_700_000_000)
                store.beginSyncWindow(
                    HistorySyncWindow(
                        accountIdentityDigest = accountDigest,
                        feed = HistoryFeed.TRADES,
                        scanId = "scan-dedup",
                        startSec = 0L,
                        endSec = 1_000L,
                    ),
                )

                fun request(cursor: String?, seconds: Long) = HistoryPageRequest(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.TRADES,
                    scanId = "scan-dedup",
                    startSec = 0L,
                    endSec = 1_000L,
                    requestedCursor = cursor,
                    committedAt = now.plusSeconds(seconds),
                )

                store.commitTradePage(
                    request = request(null, 0),
                    page = ExchangeHistoryPage(
                        entries = listOf(completeTradeEntry("T1"), completeTradeEntry("T2")),
                        startSec = 0L,
                        endSec = 1_000L,
                        requestedCursor = null,
                        nextCursor = "C1",
                        count = 2L,
                        rawSize = 2,
                        valid = true,
                    ),
                ).state shouldBe HistorySyncState.RUNNING

                store.commitTradePage(
                    request = request("C1", 1),
                    page = ExchangeHistoryPage(
                        entries = listOf(completeTradeEntry("T2"), completeTradeEntry("T3")),
                        startSec = 0L,
                        endSec = 1_000L,
                        requestedCursor = "C1",
                        nextCursor = null,
                        count = 2L,
                        rawSize = 2,
                        valid = true,
                    ),
                ).state shouldBe HistorySyncState.COMPLETE

                store.queryTrades(HistoryEventQuery(accountIdentityDigest = accountDigest)).total shouldBe 3L
            }
        }

        "multi-asset observations persist every asset and hash them stably" {
            withStore { store, _ ->
                val now = Instant.ofEpochSecond(1_700_000_000)
                val observation = HistoryObservation(
                    observationId = "obs-multi",
                    cycleId = "cycle-multi",
                    sourceScope = testScope(),
                    observedAt = now,
                    balanceRequestStartedAt = now,
                    balanceResponseEndedAt = now,
                    priceRequestStartedAt = now,
                    priceResponseEndedAt = now,
                    status = HistoryObservationStatus.COMPLETE,
                    totalUsd = BigDecimal("300.00"),
                    incompleteReasons = emptyList(),
                    assets = listOf(
                        HistoryAssetValue(
                            symbol = "BTC",
                            status = HistoryAssetValueStatus.COMPLETE,
                            quantity = BigDecimal.ONE,
                            priceUsd = BigDecimal("200.00"),
                            valueUsd = BigDecimal("200.00"),
                        ),
                        HistoryAssetValue(
                            symbol = "ETH",
                            status = HistoryAssetValueStatus.COMPLETE,
                            quantity = BigDecimal("2.0"),
                            priceUsd = BigDecimal("50.00"),
                            valueUsd = BigDecimal("100.00"),
                        ),
                    ),
                )

                store.appendObservation(observation) shouldBe true
                store.appendObservation(observation) shouldBe false

                val page = store.queryObservations(HistoryRangeQuery(sourceScope = testScope()))
                page.total shouldBe 1L
                page.items.single().assets.map { it.symbol } shouldBe listOf("BTC", "ETH")
                store.latestObservation(testScope())?.totalUsd shouldBe BigDecimal("300.00")
            }
        }

        "sync progress merges recent, backfill, and repair jobs per feed" {
            withStore { store, _ ->
                val now = Instant.ofEpochSecond(1_700_000_000)
                store.upsertSyncJob(
                    syncJob(
                        HistoryFeed.TRADES,
                        HistorySyncPurpose.RECENT,
                        state = HistorySyncJobState.COMPLETE,
                        completedThroughSec = 1_500L,
                        lastSuccessAt = now,
                    ),
                )
                store.upsertSyncJob(
                    syncJob(
                        HistoryFeed.TRADES,
                        HistorySyncPurpose.BACKFILL,
                        state = HistorySyncJobState.RUNNING,
                        cursor = "BACKFILL-C1",
                        completedThroughSec = 900L,
                        lastError = "page failed",
                        nextRetryAt = now.plusSeconds(30),
                    ),
                )
                store.upsertSyncJob(
                    syncJob(
                        HistoryFeed.TRADES,
                        HistorySyncPurpose.REPAIR,
                        state = HistorySyncJobState.COMPLETE,
                        lastSuccessAt = now.minusSeconds(60),
                        nextRetryAt = now.plusSeconds(120),
                    ),
                )
                store.upsertSyncJob(
                    syncJob(HistoryFeed.LEDGERS, HistorySyncPurpose.BACKFILL, state = HistorySyncJobState.PENDING),
                )

                val progress = store.syncProgress(accountDigest)
                progress.size shouldBe 2

                val trades = progress.single { it.feed == HistoryFeed.TRADES }
                trades.recentCompletedThroughSec shouldBe 1_500L
                trades.recentLastSuccessAt shouldBe now
                trades.backfillState shouldBe HistorySyncJobState.RUNNING
                trades.backfillCompletedThroughSec shouldBe 900L
                trades.backfillCursor shouldBe "BACKFILL-C1"
                trades.repairLastRunAt shouldBe now.minusSeconds(60)
                trades.lastError shouldBe "page failed"
                trades.nextRetryAt shouldBe now.plusSeconds(30)

                val ledgers = progress.single { it.feed == HistoryFeed.LEDGERS }
                ledgers.recentCompletedThroughSec shouldBe null
                ledgers.backfillState shouldBe HistorySyncJobState.PENDING
            }
        }

        "recorded scopes and value coverage report observed facts only" {
            withStore { store, _ ->
                val base = Instant.ofEpochSecond(1_700_000_000)
                store.appendObservation(observation("obs-1", base)) shouldBe true
                store.appendObservation(
                    observation("obs-2", base.plusSeconds(60), status = HistoryObservationStatus.INCOMPLETE),
                ) shouldBe true

                val scopes = store.listRecordedScopes(accountDigest)
                scopes.size shouldBe 1
                scopes.single().observationCount shouldBe 2L
                scopes.single().firstObservedAt shouldBe base
                scopes.single().lastObservedAt shouldBe base.plusSeconds(60)

                val coverage = store.valuesCoverage(testScope())
                coverage.firstRecordedAt shouldBe base
                coverage.lastRecordedAt shouldBe base.plusSeconds(60)
                coverage.completeCount shouldBe 1L
                coverage.incompleteCount shouldBe 1L

                val unknown = store.valuesCoverage(
                    HistorySourceScope(
                        mode = HistorySourceMode.ACCOUNT,
                        managedScopeFingerprint = "b".repeat(64),
                        managedScopeSymbols = listOf("XRP", "USD"),
                        accountIdentityDigest = accountDigest,
                    ),
                )
                unknown.firstRecordedAt shouldBe null
                unknown.lastRecordedAt shouldBe null
                unknown.completeCount shouldBe 0L
                unknown.incompleteCount shouldBe 0L

                val revisionAfter = store.currentDataRevision()
                revisionAfter shouldNotBe 1L
            }
        }
    }

    private fun emptyTradePage(startSec: Long?, endSec: Long?, cursor: String?, nextCursor: String?) =
        ExchangeHistoryPage<ExchangeTradeHistoryEntry>(
            entries = emptyList(),
            startSec = startSec,
            endSec = endSec,
            requestedCursor = cursor,
            nextCursor = nextCursor,
            count = 0L,
            rawSize = 0,
            valid = true,
        )

    private fun completeTradeEntry(id: String) = ExchangeTradeHistoryEntry(
        tradeId = id,
        orderTxid = "O-$id",
        postTxid = "P-$id",
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
        margin = "0.5",
        misc = "misc-record",
        tradeSequenceId = "SEQ-$id",
        maker = false,
        ledgerIds = listOf("L-$id"),
        rawRecordJson = "{}",
        hasValidTime = true,
        hasValidPrice = true,
        hasValidCost = true,
        hasValidFee = true,
        hasValidVolume = true,
        isValidRecord = true,
    )

    private fun withStore(
        maxPagesPerScan: Int = HISTORY_MAX_SYNC_PAGES,
        block: suspend (SqliteHistoryStore, Path) -> Unit,
    ) = runTest {
        val directory = Files.createTempDirectory("sqlite-history-store-sync-")
        try {
            val dbPath = directory.resolve("history.db")
            val database = HistoryDatabase(dbPath)
            database.initializeFreshSchema()
            val store = SqliteHistoryStore(database, maxPagesPerScan = maxPagesPerScan)
            block(store, dbPath)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
