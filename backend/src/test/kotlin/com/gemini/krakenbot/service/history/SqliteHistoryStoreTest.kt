package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.model.ExchangeHistoryPage
import com.gemini.krakenbot.model.ExchangeLedgerHistoryEntry
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
import java.time.Duration
import java.time.Instant

class SqliteHistoryStoreTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val accountDigest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val fingerprint = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"

    private fun testScope(symbols: List<String> = listOf("BTC", "USD")) = HistorySourceScope(
        mode = HistorySourceMode.ACCOUNT,
        managedScopeFingerprint = fingerprint,
        managedScopeSymbols = symbols,
        accountIdentityDigest = accountDigest,
    )

    private fun createObservation(
        id: String,
        cycleId: String = "cycle-$id",
        time: Instant,
        scope: HistorySourceScope = testScope(),
        totalUsd: BigDecimal = BigDecimal("50000.00"),
    ): HistoryObservation = HistoryObservation(
        observationId = id,
        cycleId = cycleId,
        sourceScope = scope,
        observedAt = time,
        balanceRequestStartedAt = time.minusMillis(100),
        balanceResponseEndedAt = time,
        priceRequestStartedAt = time.minusMillis(80),
        priceResponseEndedAt = time,
        status = HistoryObservationStatus.COMPLETE,
        totalUsd = totalUsd,
        incompleteReasons = emptyList(),
        assets = listOf(
            HistoryAssetValue(
                symbol = "BTC",
                status = HistoryAssetValueStatus.COMPLETE,
                quantity = BigDecimal("1.0"),
                priceUsd = totalUsd,
                valueUsd = totalUsd,
            ),
        ),
    )

    init {
        "appends observations and queries with time range and pagination" {
            withStore { store, _ ->
                val now = Instant.ofEpochSecond(1_700_000_000)
                val obs1 = createObservation("obs-1", time = now.minusSeconds(100))
                val obs2 = createObservation("obs-2", time = now.minusSeconds(50))
                val obs3 = createObservation("obs-3", time = now)

                store.appendObservation(obs1) shouldBe true
                store.appendObservation(obs2) shouldBe true
                store.appendObservation(obs3) shouldBe true

                // Idempotent duplicate insert
                store.appendObservation(obs1) shouldBe false

                val page = store.queryObservations(
                    HistoryRangeQuery(
                        from = now.minusSeconds(80),
                        until = now.plusSeconds(10),
                        sourceScope = testScope(),
                        limit = 10,
                    ),
                )

                page.total shouldBe 2L
                page.items.map { it.observationId } shouldBe listOf("obs-3", "obs-2")
                page.hasMore shouldBe false

                val latest = store.latestObservation(testScope())
                latest?.observationId shouldBe "obs-3"

                val atOrBefore = store.observationAtOrBefore(
                    sourceScope = testScope(),
                    targetTime = now.minusSeconds(40),
                    maxAge = Duration.ofSeconds(60),
                )
                atOrBefore?.observationId shouldBe "obs-2"

                val expired = store.observationAtOrBefore(
                    sourceScope = testScope(),
                    targetTime = now.minusSeconds(40),
                    maxAge = Duration.ofSeconds(5),
                )
                expired shouldBe null
            }
        }

        "appends cycle activity and queries recent activities" {
            withStore { store, _ ->
                val now = Instant.ofEpochSecond(1_700_000_000)
                val act1 = HistoryCycleActivity(
                    cycleId = "c1",
                    sourceScope = testScope(),
                    occurredAt = now.minusSeconds(10),
                    dryRun = true,
                    actions = listOf("Action 1", "Action 2"),
                )
                val act2 = HistoryCycleActivity(
                    cycleId = "c2",
                    sourceScope = testScope(),
                    occurredAt = now,
                    dryRun = false,
                    actions = listOf("Action 3"),
                )

                store.appendCycleActivity(act1) shouldBe true
                store.appendCycleActivity(act2) shouldBe true
                store.appendCycleActivity(act1) shouldBe false

                val activities = store.queryCycleActivities(testScope(), limit = 5)
                activities.size shouldBe 2
                activities[0].cycleId shouldBe "c2"
                activities[1].cycleId shouldBe "c1"
                activities[0].actions shouldBe listOf("Action 3")
            }
        }

        "appendCycle atomically records observation and activity" {
            withStore { store, _ ->
                val now = Instant.ofEpochSecond(1_700_000_000)
                val obs = createObservation("obs-c1", cycleId = "c1", time = now)
                val act = HistoryCycleActivity(
                    cycleId = "c1",
                    sourceScope = testScope(),
                    occurredAt = now,
                    dryRun = false,
                    actions = listOf("Rebalance BUY BTC"),
                )

                store.appendCycle(obs, act) shouldBe true
                store.latestObservation(testScope())?.observationId shouldBe "obs-c1"
                store.queryCycleActivities(testScope(), 1).single().cycleId shouldBe "c1"
            }
        }

        "queries asset values individually with range filters" {
            withStore { store, _ ->
                val now = Instant.ofEpochSecond(1_700_000_000)
                val obs = createObservation("obs-asset", time = now)
                store.appendObservation(obs)

                val assets = store.queryAssetValues(
                    HistoryRangeQuery(
                        from = now.minusSeconds(10),
                        until = now.plusSeconds(10),
                        sourceScope = testScope(),
                    ),
                )

                assets.items.size shouldBe 1
                assets.items.single().symbol shouldBe "BTC"
                assets.items.single().status shouldBe HistoryAssetValueStatus.COMPLETE
            }
        }

        "syncs trades and ledgers, tracks scan status, and derives balance checkpoints" {
            withStore { store, _ ->
                val scanWindow = HistorySyncWindow(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.TRADES,
                    scanId = "scan-trades-1",
                    startSec = 1_700_000_000,
                    endSec = 1_700_010_000,
                )
                val initialStatus = store.beginSyncWindow(scanWindow)
                initialStatus.state shouldBe HistorySyncState.RUNNING

                val trade1 = ExchangeTradeHistoryEntry(
                    tradeId = "T1",
                    orderTxid = "O1",
                    postTxid = "P1",
                    pair = "XXBTZUSD",
                    baseAsset = "BTC",
                    quoteCurrency = "USD",
                    feeCurrency = "USD",
                    time = Instant.ofEpochSecond(1_700_000_100),
                    rawTime = "1700000100.0",
                    type = "buy",
                    orderType = "limit",
                    price = "50000.0",
                    cost = "5000.0",
                    fee = "8.0",
                    volume = "0.1",
                    margin = "0.0",
                    misc = "",
                    tradeSequenceId = "1",
                    maker = true,
                    ledgerIds = listOf("L1"),
                    rawRecordJson = "{}",
                    hasValidTime = true,
                    hasValidPrice = true,
                    hasValidCost = true,
                    hasValidFee = true,
                    hasValidVolume = true,
                    isValidRecord = true,
                )

                val req = HistoryPageRequest(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.TRADES,
                    scanId = "scan-trades-1",
                    startSec = 1_700_000_000,
                    endSec = 1_700_010_000,
                    requestedCursor = null,
                    committedAt = Instant.now(),
                )

                val committedStatus = store.commitTradePage(
                    request = req,
                    page = ExchangeHistoryPage(
                        entries = listOf(trade1),
                        startSec = 1_700_000_000,
                        endSec = 1_700_010_000,
                        requestedCursor = null,
                        nextCursor = null,
                        count = 1L,
                        rawSize = 1,
                        valid = true,
                    ),
                )

                committedStatus.state shouldBe HistorySyncState.COMPLETE
                committedStatus.storedCount shouldBe 1L

                // Query trade
                val tradesPage = store.queryTrades(
                    HistoryEventQuery(
                        accountIdentityDigest = accountDigest,
                        pair = "XXBTZUSD",
                    ),
                )
                tradesPage.total shouldBe 1L
                tradesPage.items.single().tradeId shouldBe "T1"

                // Sync Ledgers
                val ledgerWindow = HistorySyncWindow(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.LEDGERS,
                    scanId = "scan-ledgers-1",
                    startSec = 1_700_000_000,
                    endSec = 1_700_010_000,
                )
                store.beginSyncWindow(ledgerWindow)

                val ledgerEntry = ExchangeLedgerHistoryEntry(
                    ledgerId = "L1",
                    refid = "T1",
                    time = Instant.ofEpochSecond(1_700_000_100),
                    rawTime = "1700000100.0",
                    type = "trade",
                    subtype = "",
                    assetClass = "currency",
                    asset = "XXBT",
                    amount = "0.1",
                    fee = "0.0001",
                    balance = "1.5",
                    rawRecordJson = "{}",
                    hasValidTime = true,
                    hasValidAmount = true,
                    hasValidFee = true,
                    hasValidBalance = true,
                    isValidRecord = true,
                )

                val ledgerReq = HistoryPageRequest(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.LEDGERS,
                    scanId = "scan-ledgers-1",
                    startSec = 1_700_000_000,
                    endSec = 1_700_010_000,
                    requestedCursor = null,
                    committedAt = Instant.now(),
                )

                store.commitLedgerPage(
                    request = ledgerReq,
                    page = ExchangeHistoryPage(
                        entries = listOf(ledgerEntry),
                        startSec = 1_700_000_000,
                        endSec = 1_700_010_000,
                        requestedCursor = null,
                        nextCursor = null,
                        count = 1L,
                        rawSize = 1,
                        valid = true,
                    ),
                )

                // Query ledgers
                val ledgersPage = store.queryLedgers(
                    HistoryEventQuery(
                        accountIdentityDigest = accountDigest,
                        assetKey = "XXBT",
                    ),
                )
                ledgersPage.total shouldBe 1L
                ledgersPage.items.single().ledgerId shouldBe "L1"

                // Query balance checkpoints derived from ledger
                val checkpoints = store.queryBalanceCheckpoints(
                    HistoryEventQuery(
                        accountIdentityDigest = accountDigest,
                        assetKey = "XXBT",
                    ),
                )
                checkpoints.total shouldBe 1L
                val cp = checkpoints.items.single()
                cp.ledgerId shouldBe "L1"
                cp.quantity shouldBe BigDecimal("1.5")
                cp.status shouldBe HistoryBalanceStatus.VALID

                // Ledger coverage windows
                store.hasLedgerCoverage(accountDigest, 1_700_000_000, 1_700_010_000) shouldBe true
                val coverage = store.queryLedgerCoverageWindows(accountDigest, 1_700_000_000, 1_700_010_000)
                coverage.isNotEmpty() shouldBe true

                // Pause & Fail sync
                store.pauseSync(accountDigest, HistoryFeed.TRADES, "scan-trades-1")
                store.failSync(accountDigest, HistoryFeed.TRADES, "scan-trades-1", "Simulated error")
                val statuses = store.syncStatus(accountDigest)
                statuses.any { it.state == HistorySyncState.FAILED } shouldBe true
            }
        }

        "saves and queries daily prices, checkpoint inputs, and daily values" {
            withStore { store, _ ->
                val dayEnd = Instant.ofEpochSecond(1_700_000_000)
                val price = HistoryDailyPrice(
                    accountIdentityDigest = accountDigest,
                    rawAssetKey = "XXBT",
                    pair = "XXBTZUSD",
                    candleOpen = dayEnd.minusSeconds(86400),
                    candleEnd = dayEnd,
                    closeUsd = BigDecimal("50000.00"),
                )
                store.saveDailyPrices(listOf(price))

                val prices = store.queryDailyPrices(
                    accountIdentityDigest = accountDigest,
                    rawAssetKey = "XXBT",
                    fromSec = 1_699_000_000,
                    throughSec = 1_701_000_000,
                )
                prices.size shouldBe 1
                prices.single().closeUsd shouldBe BigDecimal("50000.00")

                val dailyValue = HistoryDailyValue(
                    dayEnd = dayEnd,
                    accountIdentityDigest = accountDigest,
                    sourceAssetScope = "BTC+USD",
                    totalUsd = BigDecimal("75000.00"),
                    status = HistoryDailyValueStatus.COMPLETE,
                    assets = listOf(
                        HistoryDailyAssetValue(
                            dayEnd = dayEnd,
                            accountIdentityDigest = accountDigest,
                            rawAssetClass = "currency",
                            rawAssetKey = "XXBT",
                            quantity = BigDecimal("1.5"),
                            priceUsd = BigDecimal("50000.00"),
                            valueUsd = BigDecimal("75000.00"),
                            pricePair = "XXBTZUSD",
                            priceCandleOpen = dayEnd.minusSeconds(86400),
                            priceSource = "OHLC",
                            status = HistoryDailyValueStatus.COMPLETE,
                        ),
                    ),
                )
                store.saveDailyValues(listOf(dailyValue))

                val values = store.queryDailyValues(
                    accountIdentityDigest = accountDigest,
                    sourceAssetScope = "BTC+USD",
                )
                values.total shouldBe 1L
                values.items.single().totalUsd shouldBe BigDecimal("75000.00")

                val dailyAssets = store.queryDailyAssetValues(
                    accountIdentityDigest = accountDigest,
                    sourceAssetScope = "BTC+USD",
                )
                dailyAssets.total shouldBe 1L
                dailyAssets.items.single().rawAssetKey shouldBe "XXBT"

                val checkpointInputs = store.queryDailyCheckpointInputs(
                    accountIdentityDigest = accountDigest,
                    fromSec = 1_699_000_000,
                    throughSec = 1_701_000_000,
                )
                checkpointInputs shouldNotBe null
            }
        }

        "commitTradePage marks scan as failed when page is marked invalid" {
            withStore { store, _ ->
                val scanWindow = HistorySyncWindow(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.TRADES,
                    scanId = "scan-trades-invalid",
                    startSec = 1_700_000_000,
                    endSec = 1_700_010_000,
                )
                store.beginSyncWindow(scanWindow)

                val req = HistoryPageRequest(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.TRADES,
                    scanId = "scan-trades-invalid",
                    startSec = 1_700_000_000,
                    endSec = 1_700_010_000,
                    requestedCursor = null,
                    committedAt = Instant.now(),
                )

                val failedStatus = store.commitTradePage(
                    request = req,
                    page = ExchangeHistoryPage(
                        entries = emptyList(),
                        startSec = 1_700_000_000,
                        endSec = 1_700_010_000,
                        requestedCursor = null,
                        nextCursor = null,
                        count = 0L,
                        rawSize = 0,
                        valid = false,
                    ),
                )
                failedStatus.state shouldBe HistorySyncState.FAILED
            }
        }

        "commitLedgerPage marks scan as failed when page is marked invalid" {
            withStore { store, _ ->
                val scanWindow = HistorySyncWindow(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.LEDGERS,
                    scanId = "scan-ledgers-invalid",
                    startSec = 1_700_000_000,
                    endSec = 1_700_010_000,
                )
                store.beginSyncWindow(scanWindow)

                val req = HistoryPageRequest(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.LEDGERS,
                    scanId = "scan-ledgers-invalid",
                    startSec = 1_700_000_000,
                    endSec = 1_700_010_000,
                    requestedCursor = null,
                    committedAt = Instant.now(),
                )

                val failedStatus = store.commitLedgerPage(
                    request = req,
                    page = ExchangeHistoryPage(
                        entries = emptyList(),
                        startSec = 1_700_000_000,
                        endSec = 1_700_010_000,
                        requestedCursor = null,
                        nextCursor = null,
                        count = 0L,
                        rawSize = 0,
                        valid = false,
                    ),
                )
                failedStatus.state shouldBe HistorySyncState.FAILED
            }
        }

        "queries trades and ledgers with full filter combinations" {
            withStore { store, _ ->
                val from = Instant.ofEpochSecond(1_700_000_000)
                val until = Instant.ofEpochSecond(1_700_000_200)
                val trades = store.queryTrades(
                    HistoryEventQuery(
                        from = from,
                        until = until,
                        accountIdentityDigest = accountDigest,
                        assetKey = "BTC",
                        pair = "XXBTZUSD",
                        type = "buy",
                        limit = 10,
                        offset = 0,
                    ),
                )
                trades.items shouldBe emptyList()

                val ledgers = store.queryLedgers(
                    HistoryEventQuery(
                        from = from,
                        until = until,
                        accountIdentityDigest = accountDigest,
                        assetKey = "XXBT",
                        pair = "XXBTZUSD",
                        type = "trade",
                        limit = 10,
                        offset = 0,
                    ),
                )
                ledgers.items shouldBe emptyList()

                val checkpoints = store.queryBalanceCheckpoints(
                    HistoryEventQuery(
                        from = from,
                        until = until,
                        accountIdentityDigest = accountDigest,
                        assetKey = "XXBT",
                        limit = 10,
                        offset = 0,
                    ),
                )
                checkpoints.items shouldBe emptyList()
            }
        }

        "queryDailyCheckpointInputs filters and partitions balance checkpoints correctly" {
            withStore { store, _ ->
                val scanWindow = HistorySyncWindow(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.LEDGERS,
                    scanId = "scan-ledgers-ck",
                    startSec = 1_699_000_000,
                    endSec = 1_701_000_000,
                )
                store.beginSyncWindow(scanWindow)

                val req = HistoryPageRequest(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.LEDGERS,
                    scanId = "scan-ledgers-ck",
                    startSec = 1_699_000_000,
                    endSec = 1_701_000_000,
                    requestedCursor = null,
                    committedAt = Instant.now(),
                )

                // 2 before fromSec for same asset partition (XXBT)
                val entry1 = ledgerEntry("L1", Instant.ofEpochSecond(1_699_500_000), "1.0")
                val entry2 = ledgerEntry("L2", Instant.ofEpochSecond(1_699_600_000), "1.2")
                // 1 in range for XXBT
                val entry3 = ledgerEntry("L3", Instant.ofEpochSecond(1_700_050_000), "1.5")

                store.commitLedgerPage(
                    request = req,
                    page = ExchangeHistoryPage(
                        entries = listOf(entry1, entry2, entry3),
                        startSec = 1_699_000_000,
                        endSec = 1_701_000_000,
                        requestedCursor = null,
                        nextCursor = null,
                        count = 3L,
                        rawSize = 3,
                        valid = true,
                    ),
                )

                val results = store.queryDailyCheckpointInputs(
                    accountIdentityDigest = accountDigest,
                    fromSec = 1_700_000_000,
                    throughSec = 1_700_100_000,
                    limit = 10,
                )
                results.size shouldBe 2
                results[0].ledgerId shouldBe "L2"
                results[1].ledgerId shouldBe "L3"

                // Validation errors
                shouldThrow<IllegalArgumentException> {
                    store.queryDailyCheckpointInputs("bad-digest", 100, 200, 10)
                }
                shouldThrow<IllegalArgumentException> {
                    store.queryDailyCheckpointInputs(accountDigest, 200, 100, 10)
                }
                shouldThrow<IllegalArgumentException> {
                    store.queryDailyCheckpointInputs(accountDigest, 100, 200, 0)
                }
            }
        }

        "beginSyncWindow resumes paused window and returns running window" {
            withStore { store, _ ->
                val window = HistorySyncWindow(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.TRADES,
                    scanId = "scan-sync-window",
                    startSec = 1_700_000_000,
                    endSec = 1_700_010_000,
                )
                val initial = store.beginSyncWindow(window)
                initial.state shouldBe HistorySyncState.RUNNING

                // Calling beginSyncWindow when already running returns active window
                val runningAgain = store.beginSyncWindow(window)
                runningAgain.state shouldBe HistorySyncState.RUNNING

                // Pause sync, then beginSyncWindow resumes it
                store.pauseSync(accountDigest, HistoryFeed.TRADES, "scan-sync-window")
                val resumed = store.beginSyncWindow(window)
                resumed.state shouldBe HistorySyncState.RUNNING
            }
        }

        "handles empty daily prices and daily values without errors" {
            withStore { store, _ ->
                store.saveDailyPrices(emptyList())
                store.saveDailyValues(emptyList())

                val dayEnd = Instant.ofEpochSecond(1_700_000_000)
                val badIncomplete = HistoryDailyValue(
                    dayEnd = dayEnd,
                    accountIdentityDigest = accountDigest,
                    sourceAssetScope = "BTC+USD",
                    totalUsd = BigDecimal("100.00"),
                    status = HistoryDailyValueStatus.INCOMPLETE,
                    assets = emptyList(),
                )
                shouldThrow<IllegalArgumentException> {
                    store.saveDailyValues(listOf(badIncomplete))
                }

                val badComplete = HistoryDailyValue(
                    dayEnd = dayEnd,
                    accountIdentityDigest = accountDigest,
                    sourceAssetScope = "BTC+USD",
                    totalUsd = null,
                    status = HistoryDailyValueStatus.COMPLETE,
                    assets = emptyList(),
                )
                shouldThrow<IllegalArgumentException> {
                    store.saveDailyValues(listOf(badComplete))
                }
            }
        }

        "commitTradePage handles cursor continuation, maker variants, and duplicate mismatch failure" {
            withStore { store, _ ->
                val scanWindow = HistorySyncWindow(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.TRADES,
                    scanId = "scan-trades-cursor",
                    startSec = 1_700_000_000,
                    endSec = 1_700_010_000,
                )
                store.beginSyncWindow(scanWindow)

                val req1 = HistoryPageRequest(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.TRADES,
                    scanId = "scan-trades-cursor",
                    startSec = 1_700_000_000,
                    endSec = 1_700_010_000,
                    requestedCursor = null,
                    committedAt = Instant.now(),
                )

                val trade1 = tradeEntry("T10", Instant.ofEpochSecond(1_700_000_100), maker = false)
                val trade2 = tradeEntry("T11", Instant.ofEpochSecond(1_700_000_200), maker = null)

                val status1 = store.commitTradePage(
                    request = req1,
                    page = ExchangeHistoryPage(
                        entries = listOf(trade1, trade2),
                        startSec = 1_700_000_000,
                        endSec = 1_700_010_000,
                        requestedCursor = null,
                        nextCursor = "cursor-2",
                        count = 2L,
                        rawSize = 2,
                        valid = true,
                    ),
                )
                status1.state shouldBe HistorySyncState.RUNNING
                status1.nextCursor shouldBe "cursor-2"

                // Page metadata mismatch
                val mismatchReq = req1.copy(requestedCursor = "cursor-2", startSec = 1_700_000_500)
                val mismatchStatus = store.commitTradePage(
                    request = mismatchReq,
                    page = ExchangeHistoryPage(
                        entries = emptyList(),
                        startSec = 1_700_000_000,
                        endSec = 1_700_010_000,
                        requestedCursor = "cursor-2",
                        nextCursor = null,
                        count = 0L,
                        rawSize = 0,
                        valid = true,
                    ),
                )
                mismatchStatus.state shouldBe HistorySyncState.FAILED
                mismatchStatus.lastError shouldBe "History page metadata did not match its request."

                // Stale request returns active status
                val staleReq = req1.copy(scanId = "old-scan-id")
                val staleStatus = store.commitTradePage(
                    request = staleReq,
                    page = ExchangeHistoryPage(
                        entries = emptyList(),
                        startSec = 1_700_000_000,
                        endSec = 1_700_010_000,
                        requestedCursor = null,
                        nextCursor = null,
                        count = 0L,
                        rawSize = 0,
                        valid = true,
                    ),
                )
                staleStatus.state shouldBe HistorySyncState.FAILED
            }
        }

        "commitTradePage throws when duplicate trade payload changes across pages" {
            withStore { store, _ ->
                val scanWindow = HistorySyncWindow(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.TRADES,
                    scanId = "scan-trades-dup",
                    startSec = 1_700_000_000,
                    endSec = 1_700_010_000,
                )
                store.beginSyncWindow(scanWindow)

                val req = HistoryPageRequest(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.TRADES,
                    scanId = "scan-trades-dup",
                    startSec = 1_700_000_000,
                    endSec = 1_700_010_000,
                    requestedCursor = null,
                    committedAt = Instant.now(),
                )

                val trade = tradeEntry("T-DUP", Instant.ofEpochSecond(1_700_000_100), volume = "1.0")
                store.commitTradePage(
                    request = req,
                    page = ExchangeHistoryPage(
                        entries = listOf(trade),
                        startSec = 1_700_000_000,
                        endSec = 1_700_010_000,
                        requestedCursor = null,
                        nextCursor = "c2",
                        count = 2L,
                        rawSize = 1,
                        valid = true,
                    ),
                )

                val alteredTrade = trade.copy(volume = "99.0")
                val req2 = req.copy(requestedCursor = "c2")
                shouldThrow<IllegalStateException> {
                    store.commitTradePage(
                        request = req2,
                        page = ExchangeHistoryPage(
                            entries = listOf(alteredTrade),
                            startSec = 1_700_000_000,
                            endSec = 1_700_010_000,
                            requestedCursor = "c2",
                            nextCursor = null,
                            count = 2L,
                            rawSize = 1,
                            valid = true,
                        ),
                    )
                }
            }
        }

        "commitLedgerPage throws when duplicate ledger payload changes across pages" {
            withStore { store, _ ->
                val scanWindow = HistorySyncWindow(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.LEDGERS,
                    scanId = "scan-ledgers-dup",
                    startSec = 1_700_000_000,
                    endSec = 1_700_010_000,
                )
                store.beginSyncWindow(scanWindow)

                val req = HistoryPageRequest(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.LEDGERS,
                    scanId = "scan-ledgers-dup",
                    startSec = 1_700_000_000,
                    endSec = 1_700_010_000,
                    requestedCursor = null,
                    committedAt = Instant.now(),
                )

                val ledger = ledgerEntry("L-DUP", Instant.ofEpochSecond(1_700_000_100), balance = "1.0")
                store.commitLedgerPage(
                    request = req,
                    page = ExchangeHistoryPage(
                        entries = listOf(ledger),
                        startSec = 1_700_000_000,
                        endSec = 1_700_010_000,
                        requestedCursor = null,
                        nextCursor = "c2",
                        count = 2L,
                        rawSize = 1,
                        valid = true,
                    ),
                )

                val alteredLedger = ledger.copy(balance = "99.0")
                val req2 = req.copy(requestedCursor = "c2")
                shouldThrow<IllegalStateException> {
                    store.commitLedgerPage(
                        request = req2,
                        page = ExchangeHistoryPage(
                            entries = listOf(alteredLedger),
                            startSec = 1_700_000_000,
                            endSec = 1_700_010_000,
                            requestedCursor = "c2",
                            nextCursor = null,
                            count = 2L,
                            rawSize = 1,
                            valid = true,
                        ),
                    )
                }
            }
        }

        "commitLedgerPage handles all ledger balance validation variants" {
            withStore { store, _ ->
                val scanWindow = HistorySyncWindow(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.LEDGERS,
                    scanId = "scan-ledgers-val",
                    startSec = 1_700_000_000,
                    endSec = 1_700_010_000,
                )
                store.beginSyncWindow(scanWindow)

                val req = HistoryPageRequest(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.LEDGERS,
                    scanId = "scan-ledgers-val",
                    startSec = 1_700_000_000,
                    endSec = 1_700_010_000,
                    requestedCursor = null,
                    committedAt = Instant.now(),
                )

                val lNullBalance = ledgerEntry("L-NULL", Instant.ofEpochSecond(1_700_000_100)).copy(balance = null)
                val lNotDecimal = ledgerEntry(
                    "L-NAN",
                    Instant.ofEpochSecond(1_700_000_200),
                ).copy(balance = "not-a-number")
                val lNegative = ledgerEntry("L-NEG", Instant.ofEpochSecond(1_700_000_300)).copy(balance = "-5.0")
                val lBlankAsset = ledgerEntry("L-BLANK", Instant.ofEpochSecond(1_700_000_400)).copy(asset = "  ")
                val lInvalidTime = ledgerEntry(
                    "L-NOTIME",
                    Instant.ofEpochSecond(1_700_000_500),
                ).copy(hasValidTime = false)
                val lInvalidRecord = ledgerEntry(
                    "L-INVREC",
                    Instant.ofEpochSecond(1_700_000_600),
                ).copy(isValidRecord = false)

                store.commitLedgerPage(
                    request = req,
                    page = ExchangeHistoryPage(
                        entries = listOf(
                            lNullBalance,
                            lNotDecimal,
                            lNegative,
                            lBlankAsset,
                            lInvalidTime,
                            lInvalidRecord,
                        ),
                        startSec = 1_700_000_000,
                        endSec = 1_700_010_000,
                        requestedCursor = null,
                        nextCursor = null,
                        count = 6L,
                        rawSize = 6,
                        valid = true,
                    ),
                )

                val checkpoints = store.queryBalanceCheckpoints(
                    HistoryEventQuery(
                        accountIdentityDigest = accountDigest,
                        from = Instant.ofEpochSecond(1_700_000_000),
                        until = Instant.ofEpochSecond(1_700_010_000),
                        limit = 20,
                    ),
                )
                checkpoints.items.size shouldBe 6
                checkpoints.items.any { it.status == HistoryBalanceStatus.MISSING } shouldBe true
                checkpoints.items.any { it.status == HistoryBalanceStatus.INVALID } shouldBe true
            }
        }

        "appendObservation stores incomplete observation with null total and reasons" {
            withStore { store, _ ->
                val obs = HistoryObservation(
                    observationId = "obs-inc-1",
                    cycleId = "cycle-inc-1",
                    sourceScope = testScope(),
                    status = HistoryObservationStatus.INCOMPLETE,
                    totalUsd = null,
                    incompleteReasons = listOf("ETH: MISSING_PRICE"),
                    observedAt = Instant.ofEpochSecond(1_700_000_100),
                    balanceRequestStartedAt = Instant.ofEpochSecond(1_700_000_090),
                    balanceResponseEndedAt = Instant.ofEpochSecond(1_700_000_100),
                    priceRequestStartedAt = Instant.ofEpochSecond(1_700_000_095),
                    priceResponseEndedAt = Instant.ofEpochSecond(1_700_000_100),
                    assets = listOf(
                        HistoryAssetValue(
                            symbol = "BTC",
                            status = HistoryAssetValueStatus.COMPLETE,
                            quantity = BigDecimal("1.0"),
                            priceUsd = BigDecimal("50000"),
                            valueUsd = BigDecimal("50000"),
                            rawPrice = "50000",
                        ),
                        HistoryAssetValue(
                            symbol = "ETH",
                            status = HistoryAssetValueStatus.MISSING_PRICE,
                            quantity = BigDecimal("5.0"),
                            reason = "MISSING_PRICE",
                        ),
                    ),
                )
                store.appendObservation(obs) shouldBe true
                store.appendObservation(obs) shouldBe false

                val loaded = store.queryObservations(HistoryRangeQuery(sourceScope = testScope()))
                loaded.items.size shouldBe 1
                loaded.items.single().status shouldBe HistoryObservationStatus.INCOMPLETE
                loaded.items.single().totalUsd shouldBe null
                loaded.items.single().incompleteReasons shouldBe listOf("ETH: MISSING_PRICE")
            }
        }

        "commitTradePage and commitLedgerPage handle invalid page metadata and stalled cursor" {
            withStore { store, _ ->
                val scanTrades =
                    HistorySyncWindow(accountDigest, HistoryFeed.TRADES, "scan-meta-t", 1_700_000_000, 1_700_010_000)
                store.beginSyncWindow(scanTrades)

                val req =
                    HistoryPageRequest(
                        accountDigest,
                        HistoryFeed.TRADES,
                        "scan-meta-t",
                        1_700_000_000,
                        1_700_010_000,
                        null,
                        Instant.now(),
                    )

                // Metadata mismatch (startSec differs)
                val statusMismatch = store.commitTradePage(
                    request = req,
                    page = ExchangeHistoryPage(
                        emptyList(),
                        startSec = 1_700_000_001,
                        endSec = 1_700_010_000,
                        requestedCursor = null,
                        nextCursor = null,
                        count = 0L,
                        rawSize = 0,
                        valid = true,
                    ),
                )
                statusMismatch.state shouldBe HistorySyncState.FAILED
                statusMismatch.lastError shouldBe "History page metadata did not match its request."

                // Invalid page
                val scanTrades2 =
                    HistorySyncWindow(accountDigest, HistoryFeed.TRADES, "scan-invalid-t", 1_700_000_000, 1_700_010_000)
                store.beginSyncWindow(scanTrades2)
                val req2 =
                    HistoryPageRequest(
                        accountDigest,
                        HistoryFeed.TRADES,
                        "scan-invalid-t",
                        1_700_000_000,
                        1_700_010_000,
                        null,
                        Instant.now(),
                    )
                val statusInvalid = store.commitTradePage(
                    request = req2,
                    page = ExchangeHistoryPage(
                        emptyList(),
                        startSec = 1_700_000_000,
                        endSec = 1_700_010_000,
                        requestedCursor = null,
                        nextCursor = null,
                        count = 0L,
                        rawSize = 0,
                        valid = false,
                    ),
                )
                statusInvalid.state shouldBe HistorySyncState.FAILED
                statusInvalid.lastError shouldBe "Exchange trade page was invalid."

                // Cursor did not advance
                val scanTrades3 =
                    HistorySyncWindow(
                        accountDigest,
                        HistoryFeed.TRADES,
                        "scan-stall-t",
                        1_700_000_000,
                        1_700_010_000,
                        cursor = "c1",
                    )
                store.beginSyncWindow(scanTrades3)
                val req3 =
                    HistoryPageRequest(
                        accountDigest,
                        HistoryFeed.TRADES,
                        "scan-stall-t",
                        1_700_000_000,
                        1_700_010_000,
                        "c1",
                        Instant.now(),
                    )
                val statusStall = store.commitTradePage(
                    request = req3,
                    page = ExchangeHistoryPage(
                        emptyList(),
                        startSec = 1_700_000_000,
                        endSec = 1_700_010_000,
                        requestedCursor = "c1",
                        nextCursor = "c1",
                        count = 0L,
                        rawSize = 0,
                        valid = true,
                    ),
                )
                statusStall.state shouldBe HistorySyncState.FAILED
                statusStall.lastError shouldBe "Exchange trade cursor did not advance."
            }
        }

        "validates daily value queries and enforces constraints on saveDailyValues" {
            withStore { store, _ ->
                val dayEnd = Instant.parse("2023-11-14T00:00:00Z")
                val validAsset = HistoryDailyAssetValue(
                    dayEnd = dayEnd,
                    accountIdentityDigest = accountDigest,
                    rawAssetClass = "currency",
                    rawAssetKey = "XXBT",
                    quantity = BigDecimal("1.0"),
                    priceUsd = BigDecimal("50000"),
                    valueUsd = BigDecimal("50000"),
                    pricePair = "XXBTZUSD",
                    priceCandleOpen = null,
                    priceSource = "OHLC",
                    status = HistoryDailyValueStatus.COMPLETE,
                )
                val validDaily = HistoryDailyValue(
                    dayEnd = dayEnd,
                    accountIdentityDigest = accountDigest,
                    sourceAssetScope = "BTC+USD",
                    totalUsd = BigDecimal("50000"),
                    status = HistoryDailyValueStatus.COMPLETE,
                    source = "TEST",
                    assets = listOf(validAsset),
                )
                store.saveDailyValues(listOf(validDaily))

                // Query with from only, until only, and both
                val qFrom = store.queryDailyValues(accountDigest, "BTC+USD", from = dayEnd.minusSeconds(1))
                qFrom.total shouldBe 1L
                val qUntil = store.queryDailyValues(accountDigest, "BTC+USD", until = dayEnd.plusSeconds(1))
                qUntil.total shouldBe 1L
                val qBoth = store.queryDailyValues(
                    accountDigest,
                    "BTC+USD",
                    from = dayEnd.minusSeconds(1),
                    until = dayEnd.plusSeconds(1),
                )
                qBoth.total shouldBe 1L

                val aFrom = store.queryDailyAssetValues(accountDigest, "BTC+USD", from = dayEnd.minusSeconds(1))
                aFrom.total shouldBe 1L
                val aUntil = store.queryDailyAssetValues(accountDigest, "BTC+USD", until = dayEnd.plusSeconds(1))
                aUntil.total shouldBe 1L
                val aBoth = store.queryDailyAssetValues(
                    accountDigest,
                    "BTC+USD",
                    from = dayEnd.minusSeconds(1),
                    until = dayEnd.plusSeconds(1),
                )
                aBoth.total shouldBe 1L

                // Validation errors on queryDailyValues
                shouldThrow<IllegalArgumentException> {
                    store.queryDailyValues("bad-digest", "BTC+USD")
                }
                shouldThrow<IllegalArgumentException> {
                    store.queryDailyValues(accountDigest, "BTC+USD", from = dayEnd.plusSeconds(10), until = dayEnd)
                }
                shouldThrow<IllegalArgumentException> {
                    store.queryDailyValues(accountDigest, "BTC+USD", limit = 0)
                }
                shouldThrow<IllegalArgumentException> {
                    store.queryDailyValues(accountDigest, "BTC+USD", limit = 2001)
                }
                shouldThrow<IllegalArgumentException> {
                    store.queryDailyValues(accountDigest, "BTC+USD", offset = -1)
                }

                // Validation errors on saveDailyValues
                shouldThrow<IllegalArgumentException> {
                    store.saveDailyValues(listOf(validDaily.copy(accountIdentityDigest = "not-a-digest")))
                }
                shouldThrow<IllegalArgumentException> {
                    store.saveDailyValues(listOf(validDaily.copy(sourceAssetScope = "   ")))
                }
                shouldThrow<IllegalArgumentException> {
                    store.saveDailyValues(listOf(validDaily.copy(totalUsd = null)))
                }
                shouldThrow<IllegalArgumentException> {
                    store.saveDailyValues(listOf(validDaily.copy(status = HistoryDailyValueStatus.INCOMPLETE)))
                }
                shouldThrow<IllegalArgumentException> {
                    store.saveDailyValues(listOf(validDaily.copy(assets = listOf(validAsset, validAsset))))
                }
            }
        }

        "insertObservation enforces idempotency payload hash and supports SIMULATION mode" {
            withStore { store, _ ->
                val time = Instant.parse("2023-11-14T00:00:00Z")
                val obs = createObservation("obs-idemp", time = time)
                store.appendObservation(obs) shouldBe true

                // Id reused for different evidence
                val conflictObs = createObservation("obs-idemp", time = time, totalUsd = BigDecimal("99999.00"))
                shouldThrow<IllegalStateException> {
                    store.appendObservation(conflictObs)
                }

                // SIMULATION mode observation
                val simScope = HistorySourceScope(
                    mode = HistorySourceMode.SIMULATION,
                    managedScopeFingerprint = testScope().managedScopeFingerprint,
                    managedScopeSymbols = listOf("BTC", "USD"),
                    simulationSessionId = "sim-session-1",
                )
                val simObs = createObservation("obs-sim", time = time, scope = simScope)
                store.appendObservation(simObs) shouldBe true
                store.appendObservation(simObs) shouldBe false
            }
        }

        "saveDailyPrices and saveDailyValues handle empty lists and input validation" {
            withStore { store, _ ->
                store.saveDailyPrices(emptyList())
                store.saveDailyValues(emptyList())

                val validPrice = HistoryDailyPrice(
                    accountIdentityDigest = accountDigest,
                    rawAssetKey = "XXBT",
                    candleOpen = Instant.ofEpochSecond(1_700_000_000),
                    candleEnd = Instant.ofEpochSecond(1_700_086_400),
                    pair = "XXBTZUSD",
                    closeUsd = BigDecimal("50000.00"),
                )

                // Invalid closeUsd <= 0
                shouldThrow<IllegalArgumentException> {
                    store.saveDailyPrices(listOf(validPrice.copy(closeUsd = BigDecimal.ZERO)))
                }

                // Invalid candleOpen >= candleEnd
                shouldThrow<IllegalArgumentException> {
                    store.saveDailyPrices(listOf(validPrice.copy(candleEnd = validPrice.candleOpen)))
                }

                // queryDailyPrices fromSec > throughSec
                shouldThrow<IllegalArgumentException> {
                    store.queryDailyPrices(accountDigest, "XXBT", fromSec = 200, throughSec = 100)
                }

                // Insert and update on conflict
                store.saveDailyPrices(listOf(validPrice))
                val queried = store.queryDailyPrices(
                    accountDigest,
                    "XXBT",
                    fromSec = 1_700_000_000,
                    throughSec = 1_700_086_400,
                )
                queried.size shouldBe 1
                queried.single().closeUsd shouldBe BigDecimal("50000.00")

                // Update same day price
                store.saveDailyPrices(listOf(validPrice.copy(closeUsd = BigDecimal("55000.00"))))
                val updated = store.queryDailyPrices(
                    accountDigest,
                    "XXBT",
                    fromSec = 1_700_000_000,
                    throughSec = 1_700_086_400,
                )
                updated.size shouldBe 1
                updated.single().closeUsd shouldBe BigDecimal("55000.00")
            }
        }

        "commitTradePage and commitLedgerPage reject duplicate entries with conflicting payloads" {
            withStore { store, _ ->
                val now = Instant.ofEpochSecond(1_700_000_000)
                val window = store.beginSyncWindow(
                    HistorySyncWindow(
                        accountIdentityDigest = accountDigest,
                        feed = HistoryFeed.TRADES,
                        scanId = "scan-trades-x1",
                        startSec = now.minusSeconds(1000).epochSecond,
                        endSec = now.epochSecond,
                        cursor = null,
                    ),
                )

                val trade1 = tradeEntry("T1", now.minusSeconds(500), maker = null)
                val tradePage1 = ExchangeHistoryPage(
                    entries = listOf(trade1),
                    startSec = now.minusSeconds(1000).epochSecond,
                    endSec = now.epochSecond,
                    requestedCursor = null,
                    nextCursor = null,
                    count = 1L,
                    rawSize = 1,
                    valid = true,
                )
                val req1 = HistoryPageRequest(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.TRADES,
                    scanId = window.scanId,
                    startSec = now.minusSeconds(1000).epochSecond,
                    endSec = now.epochSecond,
                    requestedCursor = null,
                    committedAt = now,
                )
                val status1 = store.commitTradePage(req1, tradePage1)
                status1.state shouldBe HistorySyncState.COMPLETE

                // Raced window commit where trade has changed payload
                val conflictingTrade = trade1.copy(price = "99999.0")
                val window2 = store.beginSyncWindow(
                    HistorySyncWindow(
                        accountIdentityDigest = accountDigest,
                        feed = HistoryFeed.TRADES,
                        scanId = "scan-trades-x2",
                        startSec = now.minusSeconds(1000).epochSecond,
                        endSec = now.epochSecond,
                        cursor = null,
                    ),
                )
                val req2 = req1.copy(scanId = window2.scanId)
                val tradePage2 = tradePage1.copy(entries = listOf(conflictingTrade))
                shouldThrow<IllegalStateException> {
                    store.commitTradePage(req2, tradePage2)
                }

                // Ledger payload conflict
                val ledgerWindow = store.beginSyncWindow(
                    HistorySyncWindow(
                        accountIdentityDigest = accountDigest,
                        feed = HistoryFeed.LEDGERS,
                        scanId = "scan-ledgers-x1",
                        startSec = now.minusSeconds(1000).epochSecond,
                        endSec = now.epochSecond,
                        cursor = null,
                    ),
                )
                val ledger1 = ledgerEntry("L1", now.minusSeconds(500))
                val ledgerPage1 = ExchangeHistoryPage(
                    entries = listOf(ledger1),
                    startSec = now.minusSeconds(1000).epochSecond,
                    endSec = now.epochSecond,
                    requestedCursor = null,
                    nextCursor = null,
                    count = 1L,
                    rawSize = 1,
                    valid = true,
                )
                val lReq1 = HistoryPageRequest(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.LEDGERS,
                    scanId = ledgerWindow.scanId,
                    startSec = now.minusSeconds(1000).epochSecond,
                    endSec = now.epochSecond,
                    requestedCursor = null,
                    committedAt = now,
                )
                store.commitLedgerPage(lReq1, ledgerPage1).state shouldBe HistorySyncState.COMPLETE

                val conflictingLedger = ledger1.copy(amount = "999.0")
                val ledgerWindow2 = store.beginSyncWindow(
                    HistorySyncWindow(
                        accountIdentityDigest = accountDigest,
                        feed = HistoryFeed.LEDGERS,
                        scanId = "scan-ledgers-x2",
                        startSec = now.minusSeconds(1000).epochSecond,
                        endSec = now.epochSecond,
                        cursor = null,
                    ),
                )
                val lReq2 = lReq1.copy(scanId = ledgerWindow2.scanId)
                shouldThrow<IllegalStateException> {
                    store.commitLedgerPage(lReq2, ledgerPage1.copy(entries = listOf(conflictingLedger)))
                }
            }
        }

        "commitPageStart marks page failed when page bounds do not match request" {
            withStore { store, _ ->
                val now = Instant.ofEpochSecond(1_700_000_000)
                val window = store.beginSyncWindow(
                    HistorySyncWindow(
                        accountIdentityDigest = accountDigest,
                        feed = HistoryFeed.TRADES,
                        scanId = "scan-trades-x3",
                        startSec = now.minusSeconds(1000).epochSecond,
                        endSec = now.epochSecond,
                        cursor = null,
                    ),
                )
                val mismatchedPage = ExchangeHistoryPage(
                    entries = emptyList<ExchangeTradeHistoryEntry>(),
                    startSec = now.minusSeconds(500).epochSecond, // Mismatched startSec
                    endSec = now.epochSecond,
                    requestedCursor = null,
                    nextCursor = null,
                    count = 0L,
                    rawSize = 0,
                    valid = true,
                )
                val req = HistoryPageRequest(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.TRADES,
                    scanId = window.scanId,
                    startSec = now.minusSeconds(1000).epochSecond,
                    endSec = now.epochSecond,
                    requestedCursor = null,
                    committedAt = now,
                )
                val status = store.commitTradePage(req, mismatchedPage)
                status.state shouldBe HistorySyncState.FAILED
                status.lastError shouldBe "History page metadata did not match its request."
            }
        }

        "insertTrade and insertLedger handle invalid flags and intermediate pagination" {
            withStore { store, _ ->
                val now = Instant.ofEpochSecond(1_700_000_000)
                val window = store.beginSyncWindow(
                    HistorySyncWindow(
                        accountIdentityDigest = accountDigest,
                        feed = HistoryFeed.TRADES,
                        scanId = "scan-trades-x4",
                        startSec = now.minusSeconds(1000).epochSecond,
                        endSec = now.epochSecond,
                        cursor = null,
                    ),
                )
                val invalidTrade = ExchangeTradeHistoryEntry(
                    tradeId = "T-INVALID",
                    orderTxid = null,
                    postTxid = null,
                    pair = "XXBTZUSD",
                    baseAsset = "BTC",
                    quoteCurrency = "USD",
                    feeCurrency = "USD",
                    time = now.minusSeconds(400),
                    rawTime = now.minusSeconds(400).epochSecond.toString(),
                    type = "sell",
                    orderType = "limit",
                    price = "40000.0",
                    cost = "4000.0",
                    fee = "5.0",
                    volume = "0.1",
                    margin = "0.0",
                    misc = "",
                    tradeSequenceId = "2",
                    maker = false,
                    ledgerIds = emptyList(),
                    rawRecordJson = "{}",
                    hasValidTime = false,
                    hasValidPrice = false,
                    hasValidCost = false,
                    hasValidFee = false,
                    hasValidVolume = false,
                    isValidRecord = false,
                )
                val intermediatePage = ExchangeHistoryPage(
                    entries = listOf(invalidTrade),
                    startSec = now.minusSeconds(1000).epochSecond,
                    endSec = now.epochSecond,
                    requestedCursor = null,
                    nextCursor = "cursor-next-page",
                    count = 2L,
                    rawSize = 1,
                    valid = true,
                )
                val req1 = HistoryPageRequest(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.TRADES,
                    scanId = window.scanId,
                    startSec = now.minusSeconds(1000).epochSecond,
                    endSec = now.epochSecond,
                    requestedCursor = null,
                    committedAt = now,
                )
                val runningStatus = store.commitTradePage(req1, intermediatePage)
                runningStatus.state shouldBe HistorySyncState.RUNNING
                runningStatus.nextCursor shouldBe "cursor-next-page"

                val tradesPage = store.queryTrades(
                    HistoryEventQuery(
                        accountIdentityDigest = accountDigest,
                        from = now.minusSeconds(500),
                        until = now,
                        pair = "XXBTZUSD",
                        type = "sell",
                        limit = 10,
                    ),
                )
                tradesPage.items.size shouldBe 1
                tradesPage.items.single().hasValidTime shouldBe false
                tradesPage.items.single().maker shouldBe false

                val staleReq = req1.copy(scanId = "scan-trades-superseded")
                store.commitTradePage(staleReq, intermediatePage).scanId shouldBe window.scanId

                val ledgerWindow = store.beginSyncWindow(
                    HistorySyncWindow(
                        accountIdentityDigest = accountDigest,
                        feed = HistoryFeed.LEDGERS,
                        scanId = "scan-ledgers-x4",
                        startSec = now.minusSeconds(1000).epochSecond,
                        endSec = now.epochSecond,
                        cursor = null,
                    ),
                )
                val invalidLedger = ExchangeLedgerHistoryEntry(
                    ledgerId = "L-INVALID",
                    refid = null,
                    time = now.minusSeconds(300),
                    rawTime = now.minusSeconds(300).epochSecond.toString(),
                    type = "deposit",
                    subtype = null,
                    assetClass = "currency",
                    asset = "XXBT",
                    amount = "1.0",
                    fee = "0.0",
                    balance = "2.0",
                    rawRecordJson = "{}",
                    hasValidTime = false,
                    hasValidAmount = false,
                    hasValidFee = false,
                    hasValidBalance = false,
                    isValidRecord = false,
                )
                val lReq = HistoryPageRequest(
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.LEDGERS,
                    scanId = ledgerWindow.scanId,
                    startSec = now.minusSeconds(1000).epochSecond,
                    endSec = now.epochSecond,
                    requestedCursor = null,
                    committedAt = now,
                )
                val ledgerPage = ExchangeHistoryPage(
                    entries = listOf(invalidLedger),
                    startSec = now.minusSeconds(1000).epochSecond,
                    endSec = now.epochSecond,
                    requestedCursor = null,
                    nextCursor = null,
                    count = 1L,
                    rawSize = 1,
                    valid = true,
                )
                store.commitLedgerPage(lReq, ledgerPage).state shouldBe HistorySyncState.COMPLETE

                val ledgersPage = store.queryLedgers(
                    HistoryEventQuery(
                        accountIdentityDigest = accountDigest,
                        from = now.minusSeconds(500),
                        until = now,
                        assetKey = "XXBT",
                        type = "deposit",
                        limit = 10,
                    ),
                )
                ledgersPage.items.size shouldBe 1
                ledgersPage.items.single().hasValidTime shouldBe false

                val cpPage = store.queryBalanceCheckpoints(
                    HistoryEventQuery(
                        accountIdentityDigest = accountDigest,
                        assetKey = "XXBT",
                        limit = 10,
                    ),
                )
                cpPage.items.size shouldBe 1

                val incompleteDaily = HistoryDailyValue(
                    accountIdentityDigest = accountDigest,
                    sourceAssetScope = "test-scope",
                    dayEnd = now,
                    totalUsd = null,
                    status = HistoryDailyValueStatus.INCOMPLETE,
                    source = "history-daily-v1",
                    assets = emptyList(),
                )
                store.saveDailyValues(listOf(incompleteDaily))

                val activity = HistoryCycleActivity(
                    cycleId = "live-c-1",
                    sourceScope = testScope(),
                    occurredAt = now,
                    dryRun = false,
                    actions = listOf("SELL ETH"),
                )
                val obs = createObservation("live-obs-1", cycleId = "live-c-1", time = now)
                store.appendCycle(obs, activity)
                store.appendCycle(null, activity.copy(cycleId = "live-c-2")) shouldBe true

                shouldThrow<IllegalArgumentException> {
                    store.appendCycle(obs.copy(cycleId = "different-cycle"), activity)
                }
                shouldThrow<IllegalArgumentException> {
                    store.appendCycle(obs.copy(sourceScope = testScope(listOf("BTC"))), activity)
                }

                // observationAtOrBefore branches
                shouldThrow<IllegalArgumentException> {
                    store.observationAtOrBefore(testScope(), now, Duration.ofSeconds(-1))
                }
                store.observationAtOrBefore(testScope(), now.minusSeconds(10000), Duration.ofSeconds(10)) shouldBe null
                store.observationAtOrBefore(testScope(), now.plusSeconds(100), Duration.ofSeconds(10)) shouldBe null
                store.observationAtOrBefore(
                    testScope(),
                    now.plusSeconds(5),
                    Duration.ofSeconds(10),
                )?.observationId shouldBe
                    "live-obs-1"

                // queryCycleActivities limit boundaries
                shouldThrow<IllegalArgumentException> {
                    store.queryCycleActivities(testScope(), limit = 0)
                }
                shouldThrow<IllegalArgumentException> {
                    store.queryCycleActivities(testScope(), limit = 501)
                }
                store.queryCycleActivities(testScope(), limit = 5).size shouldBe 2

                // queryDailyCheckpointInputs parameter validation
                shouldThrow<IllegalArgumentException> {
                    store.queryDailyCheckpointInputs(accountDigest, fromSec = 100, throughSec = 50, limit = 10)
                }
                shouldThrow<IllegalArgumentException> {
                    store.queryDailyCheckpointInputs(accountDigest, fromSec = 50, throughSec = 100, limit = 0)
                }
                shouldThrow<IllegalArgumentException> {
                    store.queryDailyCheckpointInputs(accountDigest, fromSec = 50, throughSec = 100, limit = 100001)
                }

                // beginSyncWindow when RUNNING or PAUSED with matching window
                val syncWindow = HistorySyncWindow(
                    scanId = "scan-active-1",
                    accountIdentityDigest = accountDigest,
                    feed = HistoryFeed.TRADES,
                    startSec = 1000L,
                    endSec = 2000L,
                    cursor = null,
                )
                val firstStatus = store.beginSyncWindow(syncWindow)
                firstStatus.state shouldBe HistorySyncState.RUNNING

                // Call again while RUNNING with same window -> returns active
                val secondStatus = store.beginSyncWindow(syncWindow)
                secondStatus.state shouldBe HistorySyncState.RUNNING

                // Transition to PAUSED, then call beginSyncWindow -> updates to RUNNING
                store.pauseSync(accountDigest, HistoryFeed.TRADES, "scan-active-1")
                val resumedStatus = store.beginSyncWindow(syncWindow)
                resumedStatus.state shouldBe HistorySyncState.RUNNING
            }
        }
    }

    private fun tradeEntry(
        id: String,
        time: Instant,
        maker: Boolean? = true,
        volume: String = "0.1",
        price: String = "50000.0",
    ) = ExchangeTradeHistoryEntry(
        tradeId = id,
        orderTxid = "O-$id",
        postTxid = "P-$id",
        pair = "XXBTZUSD",
        baseAsset = "BTC",
        quoteCurrency = "USD",
        feeCurrency = "USD",
        time = time,
        rawTime = time.epochSecond.toString(),
        type = "buy",
        orderType = "limit",
        price = price,
        cost = "5000.0",
        fee = "8.0",
        volume = volume,
        margin = "0.0",
        misc = "",
        tradeSequenceId = "1",
        maker = maker,
        ledgerIds = listOf("L1"),
        rawRecordJson = "{}",
        hasValidTime = true,
        hasValidPrice = true,
        hasValidCost = true,
        hasValidFee = true,
        hasValidVolume = true,
        isValidRecord = true,
    )

    private fun ledgerEntry(id: String, time: Instant, balance: String = "1.5", asset: String = "XXBT") =
        ExchangeLedgerHistoryEntry(
            ledgerId = id,
            refid = "ref-$id",
            time = time,
            rawTime = time.epochSecond.toString(),
            type = "trade",
            subtype = null,
            assetClass = "currency",
            asset = asset,
            amount = "0.5",
            fee = "0.001",
            balance = balance,
            rawRecordJson = "{}",
            hasValidTime = true,
            hasValidAmount = true,
            hasValidFee = true,
            hasValidBalance = true,
            isValidRecord = true,
        )

    private fun withStore(block: suspend (SqliteHistoryStore, Path) -> Unit) = runTest {
        val directory = Files.createTempDirectory("sqlite-history-store-test-")
        try {
            val dbPath = directory.resolve("history.db")
            val database = HistoryDatabase(dbPath)
            database.initializeFreshSchema()
            val store = SqliteHistoryStore(database)
            block(store, directory)
            database.close()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
