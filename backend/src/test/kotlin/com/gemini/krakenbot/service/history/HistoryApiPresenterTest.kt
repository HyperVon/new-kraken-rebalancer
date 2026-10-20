package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.service.reporting.HistoryReportingService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant
import java.util.Base64

class HistoryApiPresenterTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val accountDigest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val fingerprint = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
    private val fixedNow = Instant.parse("2026-03-10T12:00:00Z")
    private val accountScope = HistorySourceScope(
        mode = HistorySourceMode.ACCOUNT,
        managedScopeFingerprint = fingerprint,
        managedScopeSymbols = listOf("BTC", "ETH", "USD"),
        accountIdentityDigest = accountDigest,
    )
    private val simulationScope = HistorySourceScope(
        mode = HistorySourceMode.SIMULATION,
        managedScopeFingerprint = fingerprint,
        managedScopeSymbols = listOf("BTC", "USD"),
        simulationSessionId = "sim-history-api",
    )
    private val historyService = mockk<HistoryService>(relaxed = true)
    private val historyStore = mockk<HistoryStore>(relaxed = true)
    private val syncCoordinator = mockk<HistorySyncCoordinator>(relaxed = true)
    private val reportingService = HistoryReportingService(historyService, nowProvider = { fixedNow })
    private val presenter = newPresenter()

    init {
        stubEmptyReads()

        "rejects an unknown range preset with the API error contract" {
            expectRequestError("Unknown history range preset.") {
                charts(HistoryApiRangeParams(preset = "fortnight"))
            }
        }

        "rejects an invalid timezone before querying history" {
            expectRequestError("Invalid history timezone.") {
                charts(HistoryApiRangeParams(tz = "Mars/Olympus"))
            }
            coVerify(exactly = 0) { historyService.queryChartObservations(any(), any(), any(), any()) }
        }

        "requires both custom date bounds" {
            expectRequestError("A custom history range requires start and end dates.") {
                charts(HistoryApiRangeParams(preset = "custom", start = "2026-03-01"))
            }
        }

        "rejects a custom range with both date bounds absent" {
            expectRequestError("A custom history range requires start and end dates.") {
                charts(HistoryApiRangeParams(preset = "custom"))
            }
        }

        "rejects impossible local dates" {
            expectRequestError("History range dates must use the YYYY-MM-DD format.") {
                charts(HistoryApiRangeParams(preset = "custom", start = "2026-02-30", end = "2026-03-01"))
            }
        }

        "rejects inverted custom bounds" {
            expectRequestError("History range start must not follow its end.") {
                charts(HistoryApiRangeParams(preset = "custom", start = "2026-03-09", end = "2026-03-08"))
            }
        }

        "rejects malformed from and to instants" {
            expectRequestError("History range instants must be epoch seconds or ISO-8601.") {
                charts(HistoryApiRangeParams(from = "not-an-instant"))
            }
            expectRequestError("History range instants must be epoch seconds or ISO-8601.") {
                charts(HistoryApiRangeParams(to = "not-an-instant"))
            }
            expectRequestError("History range instants must be epoch seconds or ISO-8601.") {
                charts(HistoryApiRangeParams(from = "999999999999999999"))
            }
        }

        "rejects a viewport beginning at or after its selected upper bound" {
            expectRequestError("History range start must not follow its end.") {
                charts(
                    HistoryApiRangeParams(
                        preset = "24h",
                        from = "2026-03-10T12:00:01Z",
                    ),
                )
            }
        }

        "all range keeps its lower bound unbounded" {
            val response = charts(HistoryApiRangeParams(preset = "all"))

            response.range.fromIso.shouldBeNull()
            response.fullRange.fromIso.shouldBeNull()
            response.range.toIso shouldBe "2026-03-10T12:00:00Z"
            coVerify { historyService.queryChartObservations(accountScope, null, fixedNow, any()) }
        }

        "accepts epoch seconds, ISO instants, and inclusive local date upper bounds" {
            val epochAndDate = charts(
                HistoryApiRangeParams(
                    preset = "all",
                    from = "0",
                    to = "2026-03-02",
                    tz = "Europe/Berlin",
                ),
            )
            epochAndDate.range.fromIso shouldBe "1970-01-01T00:00:00Z"
            epochAndDate.range.toIso shouldBe "2026-03-02T23:00:00Z"

            val isoInstants = charts(
                HistoryApiRangeParams(
                    preset = "all",
                    from = "2026-03-01T06:30:00Z",
                    to = "2026-03-02T06:30:00Z",
                ),
            )
            isoInstants.range.fromIso shouldBe "2026-03-01T06:30:00Z"
            isoInstants.range.toIso shouldBe "2026-03-02T06:30:00Z"
        }

        "captures now once for all rolling range bounds" {
            var calls = 0
            val singleCapture = newPresenter(nowProvider = {
                calls += 1
                fixedNow.plusSeconds(calls.toLong())
            })

            val response = charts(api = singleCapture)

            calls shouldBe 1
            response.range.fromIso shouldBe "2026-02-08T12:00:01Z"
            response.range.toIso shouldBe "2026-03-10T12:00:01Z"
        }

        "rejects scope identifiers not recorded for the verified account" {
            expectRequestError("Unknown recorded portfolio scope.") {
                charts(scopeId = "ACCOUNT:$accountDigest:unrecorded")
            }
        }

        "scope listing returns persisted account scopes and their recorded bounds" {
            val earlierFingerprint = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            val earlierScope = HistoryRecordedScope(
                sourceKey = "ACCOUNT:$accountDigest:$earlierFingerprint",
                mode = HistorySourceMode.ACCOUNT,
                managedScopeFingerprint = earlierFingerprint,
                managedScopeSymbols = listOf("BTC", "USD"),
                accountIdentityDigest = accountDigest,
                simulationSessionId = null,
                firstObservedAt = Instant.parse("2026-02-01T00:00:00Z"),
                lastObservedAt = Instant.parse("2026-03-01T00:00:00Z"),
                observationCount = 3,
            )
            coEvery { historyStore.listRecordedScopes(accountDigest) } returns listOf(earlierScope)
            coEvery { historyStore.valuesCoverage(any()) } answers {
                val scope = firstArg<HistorySourceScope>()
                HistoryValuesCoverage(
                    sourceKey = scope.sourceKey,
                    firstRecordedAt = if (scope.managedScopeFingerprint == earlierFingerprint) {
                        Instant.parse("2026-02-01T00:00:00Z")
                    } else {
                        null
                    },
                    lastRecordedAt = if (scope.managedScopeFingerprint == earlierFingerprint) {
                        Instant.parse("2026-03-01T00:00:00Z")
                    } else {
                        null
                    },
                    completeCount = 3,
                    incompleteCount = 0,
                )
            }

            val response = presenter.scopes(accountScope)

            response.scopes shouldBe listOf(
                HistoryApiScope(
                    id = "ACCOUNT:$accountDigest:$earlierFingerprint",
                    symbols = listOf("BTC", "USD"),
                    earliestIso = "2026-02-01T00:00:00Z",
                    latestIso = "2026-03-01T00:00:00Z",
                    current = false,
                ),
            )
        }

        "summary scope remains the recorded portfolio when targets contain an unobserved asset" {
            val response = presenter.summary(
                params = HistoryApiRangeParams(),
                currentScope = accountScope,
                scopeId = null,
                targetAllocations = listOf(Allocation("DOGE", 100.0)),
            )

            response.scope.symbols shouldContainExactly listOf("BTC", "ETH", "USD")
        }

        "rejects a requested activity mode that differs from the selected scope" {
            expectRequestError("Recorded portfolio scope does not match the requested mode.") {
                presenter.activities(
                    params = HistoryApiRangeParams(),
                    currentScope = accountScope,
                    scopeId = null,
                    modeParam = "SIMULATION",
                    cursor = null,
                    limit = null,
                )
            }
        }

        "rejects malformed Base64 activity cursors" {
            expectRequestError("Invalid activities cursor.") {
                activities(cursor = "not-a-cursor!")
            }
        }

        "rejects activity cursors with malformed timestamps" {
            val cursor = encodeCursor("not-an-instant\ncycle-1")

            expectRequestError("Invalid activities cursor.") {
                activities(cursor = cursor)
            }
        }

        "rejects activity cursor payloads without both timestamp and cycle id" {
            val cursorWithoutSeparator = encodeCursor("2026-03-10T11:59:00Z")
            val cursorWithoutCycleId = encodeCursor("2026-03-10T11:59:00Z\n")

            expectRequestError("Invalid activities cursor.") {
                activities(cursor = cursorWithoutSeparator)
            }
            expectRequestError("Invalid activities cursor.") {
                activities(cursor = cursorWithoutCycleId)
            }
        }

        "same-time activity cursors page by cycle id without skipped or repeated rows" {
            val database = HistoryDatabase.inMemory().apply { initializeFreshSchema() }
            try {
                val store = SqliteHistoryStore(database)
                val occurredAt = fixedNow.minusSeconds(60)
                listOf("a", "b", "c", "d", "e").forEach { cycleId ->
                    store.appendCycleActivity(
                        HistoryCycleActivity(
                            cycleId = cycleId,
                            sourceScope = accountScope,
                            occurredAt = occurredAt,
                            dryRun = false,
                            actions = listOf("action-$cycleId"),
                            outcome = HistoryCycleOutcome.NO_TRADE,
                        ),
                    )
                }
                val api = newPresenter(store = store)
                val cycleIds = mutableListOf<String>()
                var cursor: String? = null
                do {
                    val response = api.activities(
                        params = HistoryApiRangeParams(preset = "30d"),
                        currentScope = accountScope,
                        scopeId = null,
                        modeParam = null,
                        cursor = cursor,
                        limit = 2,
                    )
                    cycleIds += response.items.map { item -> item.cycleId }
                    cursor = response.nextCursor
                } while (cursor != null)

                cycleIds shouldBe listOf("e", "d", "c", "b", "a")
                cycleIds.distinct().size shouldBe 5
            } finally {
                database.close()
            }
        }

        "activity outcomes preserve every known state and legacy null rows" {
            val mappings = listOf(
                HistoryCycleOutcome.NO_TRADE to "No trade",
                HistoryCycleOutcome.DRY_RUN_PLAN to "Dry-run plan",
                HistoryCycleOutcome.ORDERS_ATTEMPTED to "Orders attempted",
                HistoryCycleOutcome.PROBLEM to "Problem",
                HistoryCycleOutcome.RECORDED to "Recorded activity",
                null to "Recorded activity",
            )

            mappings.forEach { (outcome, expected) -> outcome.toOutcomeText() shouldBe expected }
        }

        "repair date resolves to local day boundaries across a daylight-saving transition" {
            val window = presenter.resolveRepairWindow(
                HistoryApiRepairParams(date = "2026-03-08", tz = "America/Los_Angeles"),
            )

            window.start shouldBe Instant.parse("2026-03-08T08:00:00Z")
            window.end shouldBe Instant.parse("2026-03-09T07:00:00Z")
        }

        "repair custom dates include the full last local date" {
            val window = presenter.resolveRepairWindow(
                HistoryApiRepairParams(start = "2026-03-28", end = "2026-03-29", tz = "Europe/Berlin"),
            )

            window.start shouldBe Instant.parse("2026-03-27T23:00:00Z")
            window.end shouldBe Instant.parse("2026-03-29T22:00:00Z")
        }

        "repair rejects invalid timezone, date, missing bounds, and inverted dates" {
            expectRequestError("Invalid history timezone.") {
                presenter.resolveRepairWindow(HistoryApiRepairParams(date = "2026-03-01", tz = "Mars/Olympus"))
            }
            expectRequestError("History range dates must use the YYYY-MM-DD format.") {
                presenter.resolveRepairWindow(HistoryApiRepairParams(date = "2026-02-30"))
            }
            expectRequestError("History repair requires a date or start and end dates.") {
                presenter.resolveRepairWindow(HistoryApiRepairParams())
            }
            expectRequestError("History range start must not follow its end.") {
                presenter.resolveRepairWindow(HistoryApiRepairParams(start = "2026-03-02", end = "2026-03-01"))
            }
        }

        "incomplete portfolio samples remain null breaks" {
            val incomplete = observation(
                id = "incomplete",
                time = fixedNow.minusSeconds(60),
                totalUsd = null,
                assets = listOf(asset("BTC", valueUsd = null)),
            )
            val complete = observation("complete", fixedNow, "50.00")

            val points = portfolioSeries(listOf(incomplete, complete)).single().points

            points[0].v.shouldBeNull()
            points[0].complete shouldBe false
            points[1].v!!.shouldBeEqualComparingTo(BigDecimal("50.00"))
            points[1].complete shouldBe true
        }

        "missing and valueless holdings remain null instead of being fabricated" {
            val first = observation(
                id = "btc-only",
                time = fixedNow.minusSeconds(60),
                totalUsd = "100.00",
                assets = listOf(asset("BTC", valueUsd = "100.00")),
            )
            val second = observation(
                id = "eth-only",
                time = fixedNow,
                totalUsd = "200.00",
                assets = listOf(asset("ETH", valueUsd = "200.00")),
            )

            val series = holdingsSeries(listOf(first, second)).associateBy { it.key }

            series.getValue("BTC").points[1].v.shouldBeNull()
            series.getValue("BTC").points[1].complete shouldBe false
            series.getValue("ETH").points[0].v.shouldBeNull()
            series.getValue("ETH").points[0].complete shouldBe false

            val missingValue = observation(
                id = "missing-value",
                time = fixedNow,
                totalUsd = "200.00",
                assets = listOf(asset("BTC", valueUsd = null)),
            )
            val point = holdingsSeries(listOf(missingValue)).single().points.single()
            point.v.shouldBeNull()
            point.complete shouldBe false
        }

        "null-total allocation is a null incomplete point" {
            val nullTotal = observation(
                id = "null-total",
                time = fixedNow.minusSeconds(60),
                totalUsd = null,
                assets = listOf(asset("BTC", valueUsd = null)),
            )

            val point = allocationSeries(listOf(nullTotal)).single().points.single()

            point.v.shouldBeNull()
            point.complete shouldBe false
        }

        "zero-total allocation is a null incomplete point" {
            val zeroTotal = observation(
                id = "zero-total",
                time = fixedNow,
                totalUsd = "0",
                assets = listOf(asset("BTC", valueUsd = "0")),
            )

            val point = allocationSeries(listOf(zeroTotal)).single().points.single()

            point.v.shouldBeNull()
            point.complete shouldBe false
        }

        "ledger chart selection excludes deposits from non-deposit ledger families" {
            val rows = listOf(
                ledger("deposit", type = " Deposit ", asset = "ZUSD", amount = "100.00"),
                ledger("withdrawal", type = "withdrawal", asset = "ZEUR", amount = "10.00"),
                ledger("stake", type = "staking", asset = "XXBT", amount = "0.1"),
            )
            coEvery { historyService.queryLedgers(match { query -> query.limit == HISTORY_MAX_PAGE_LIMIT }) } returns
                page(rows, rows.size.toLong(), HISTORY_MAX_PAGE_LIMIT)

            val response = charts(params = HistoryApiRangeParams(preset = "all"), selection = "deposits")

            response.series.map { series -> series.key } shouldContainExactly listOf("ZUSD")
            response.series.single().points.single().v!!.shouldBeEqualComparingTo(BigDecimal("100.00"))
        }

        "non-observation chart selections do not compare points with portfolio observations" {
            val rows = listOf(ledger("deposit", type = "deposit", asset = "ZUSD", amount = "100.00"))
            coEvery { historyService.queryLedgers(match { query -> query.limit == HISTORY_MAX_PAGE_LIMIT }) } returns
                page(rows, rows.size.toLong(), HISTORY_MAX_PAGE_LIMIT)
            coEvery { historyService.queryValues(any()) } returns page(emptyList(), total = 100L)

            val response = charts(params = HistoryApiRangeParams(preset = "all"), selection = "deposits")

            response.fullRange.pointCount shouldBe 1
            response.fullRange.totalObservations shouldBe 100L
            response.fullRange.representative shouldBe false
        }

        "empty continuation pages terminate ledger and checkpoint chart scans" {
            var ledgerPageCalls = 0
            coEvery { historyService.queryLedgers(match { query -> query.limit == HISTORY_MAX_PAGE_LIMIT }) } coAnswers
                {
                    ledgerPageCalls += 1
                    check(ledgerPageCalls == 1) { "Presenter retried an empty ledger page." }
                    page(emptyList(), total = null, limit = HISTORY_MAX_PAGE_LIMIT, hasMore = true)
                }
            var checkpointPageCalls = 0
            coEvery { historyService.queryBalanceCheckpoints(any()) } coAnswers {
                checkpointPageCalls += 1
                check(checkpointPageCalls == 1) { "Presenter retried an empty checkpoint page." }
                page(emptyList(), total = null, limit = HISTORY_MAX_PAGE_LIMIT, hasMore = true)
            }

            val deposits = charts(params = HistoryApiRangeParams(preset = "all"), selection = "deposits")
            val checkpoints = charts(params = HistoryApiRangeParams(preset = "all"), selection = "checkpoints")

            deposits.series shouldBe emptyList()
            checkpoints.series shouldBe emptyList()
            ledgerPageCalls shouldBe 1
            checkpointPageCalls shouldBe 1
        }

        "invalid ledger amounts stay incomplete and missing time or asset rows are omitted" {
            val rows = listOf(
                ledger("no-time", type = "deposit", asset = "XXBT", amount = "1", time = null),
                ledger("invalid-amount", type = "deposit", asset = "XXBT", amount = "not-a-number"),
                ledger("no-asset", type = "deposit", asset = null, amount = "1"),
            )

            val series = ledgerQuantitySeries(rows)

            series.map { it.key } shouldContainExactly listOf("XXBT")
            series.single().points.size shouldBe 1
            series.single().points.single().v.shouldBeNull()
            series.single().points.single().complete shouldBe false
        }

        "reward chart recognizes legacy and subtype rewards but excludes ordinary ledger rows" {
            val rows = listOf(
                ledger("staking", type = "staking", asset = "STAKING", amount = "1"),
                ledger("dividend", type = "dividend", asset = "DIVIDEND", amount = "1"),
                ledger("reward", type = "reward", asset = "REWARD", amount = "1"),
                ledger("earn-reward", type = "earn", subtype = " Reward ", asset = "EARN", amount = "1"),
                ledger("airdrop", type = "transfer", subtype = "airdrop", asset = "AIRDROP", amount = "1"),
                ledger("transfer-reward", type = "transfer", subtype = "reward", asset = "TRANSFER", amount = "1"),
                ledger("earn-interest", type = "earn", subtype = "interest", asset = "INTEREST", amount = "1"),
                ledger(
                    "ordinary-transfer",
                    type = "transfer",
                    subtype = "spottostaking",
                    asset = "ORDINARY",
                    amount = "1",
                ),
                ledger("deposit-reward", type = "deposit", subtype = "reward", asset = "DEPOSIT", amount = "1"),
            )
            coEvery { historyService.queryLedgers(match { query -> query.limit == HISTORY_MAX_PAGE_LIMIT }) } returns
                page(rows, rows.size.toLong(), HISTORY_MAX_PAGE_LIMIT)

            val response = charts(params = HistoryApiRangeParams(preset = "all"), selection = "rewards")

            response.series.map { series -> series.key } shouldContainExactly
                listOf("STAKING", "DIVIDEND", "REWARD", "EARN", "AIRDROP", "TRANSFER")
        }

        "invalid and null checkpoint quantities are incomplete" {
            val rows = listOf(
                checkpoint("invalid", HistoryBalanceStatus.INVALID, BigDecimal("1.5")),
                checkpoint("missing", HistoryBalanceStatus.VALID, null),
                checkpoint("no-time", HistoryBalanceStatus.VALID, BigDecimal("2"), observedAt = null),
                checkpoint("no-asset", HistoryBalanceStatus.VALID, BigDecimal("3"), rawAssetKey = null),
            )

            val series = checkpointQuantitySeries(rows)
            val points = series.single().points

            series.map { it.key } shouldContainExactly listOf("XXBT")
            points.size shouldBe 2
            points.map { it.complete } shouldContainExactly listOf(false, false)
            points[0].v!!.shouldBeEqualComparingTo(BigDecimal("1.5"))
            points[1].v.shouldBeNull()
        }

        "simulation status and scope reads expose no private account rows or bounds" {
            val status = presenter.dataStatus(HistoryApiRangeParams(preset = "all"), simulationScope)
            val scopes = presenter.scopes(simulationScope)
            val deposits = charts(
                params = HistoryApiRangeParams(preset = "all"),
                currentScope = simulationScope,
                selection = "deposits",
            )

            status.coverage.tradesFirstIso.shouldBeNull()
            status.coverage.tradesLastIso.shouldBeNull()
            status.coverage.ledgersFirstIso.shouldBeNull()
            status.coverage.ledgersLastIso.shouldBeNull()
            status.jobs shouldBe emptyList()
            status.progress shouldBe emptyList()
            scopes.scopes shouldBe emptyList()
            deposits.series shouldBe emptyList()
            coVerify(exactly = 0) { historyService.queryTrades(any()) }
            coVerify(exactly = 0) { historyService.queryLedgers(any()) }
            coVerify(exactly = 0) { historyStore.loadSyncJobs(any()) }
            coVerify(exactly = 0) { historyStore.syncProgress(any()) }
            coVerify(exactly = 0) { historyStore.listRecordedScopes(any()) }
        }

        "empty coverage reports null bounds and marks the history incomplete" {
            val status = presenter.dataStatus(HistoryApiRangeParams(preset = "all"), accountScope)

            status.coverage.valuesFirstIso.shouldBeNull()
            status.coverage.valuesLastIso.shouldBeNull()
            status.coverage.tradesFirstIso.shouldBeNull()
            status.coverage.tradesLastIso.shouldBeNull()
            status.coverage.ledgersFirstIso.shouldBeNull()
            status.coverage.ledgersLastIso.shouldBeNull()
            status.coverage.incomplete shouldBe true
            status.coverage.gapCount shouldBe 0
        }

        "unknown stored interval totals fall back to returned point count" {
            val sample = observation("sample", fixedNow.minusSeconds(60), "10.00")
            coEvery { historyService.queryChartObservations(any(), any(), any(), any()) } returns listOf(sample)
            coEvery { historyService.queryValues(any()) } returns page(listOf(sample), total = null)

            val response = charts(HistoryApiRangeParams(preset = "all"))

            response.fullRange.pointCount shouldBe 1
            response.fullRange.totalObservations shouldBe 1L
            response.fullRange.representative shouldBe false
        }

        "representative chart points retain the stored observation count" {
            val samples = (0..2).map { index ->
                observation("sample-$index", fixedNow.minusSeconds(index * 60L), "10.00")
            }
            coEvery { historyService.queryChartObservations(any(), any(), any(), any()) } returns samples
            coEvery { historyService.queryValues(any()) } returns page(listOf(samples.first()), total = 2403L)

            val response = charts(HistoryApiRangeParams(preset = "all"))

            response.fullRange.pointCount shouldBe 3
            response.fullRange.totalObservations shouldBe 2403L
            response.fullRange.representative shouldBe true
        }

        "derived charts do not imply observation sampling when no asset series exists" {
            val sample = observation("sample", fixedNow.minusSeconds(60), "10.00").copy(assets = emptyList())
            coEvery { historyService.queryChartObservations(any(), any(), any(), any()) } returns listOf(sample)
            coEvery { historyService.queryValues(any()) } returns page(listOf(sample), total = 1L)

            val response = charts(selection = "holdings")

            response.fullRange.pointCount shouldBe 0
            response.fullRange.representative shouldBe false
        }

        "chart selection parsing defaults and normalizes names but rejects unknown choices" {
            HistoryChartSelection.fromParam(null) shouldBe HistoryChartSelection.PORTFOLIO
            HistoryChartSelection.fromParam("  ReWaRdS ") shouldBe HistoryChartSelection.REWARDS
            HistoryChartSelection.fromParam("unknown") shouldBe null
        }
    }

    private fun stubEmptyReads() {
        coEvery { historyService.queryValues(any()) } returns page(emptyList(), 0L)
        coEvery { historyService.queryChartObservations(any(), any(), any(), any()) } returns emptyList()
        coEvery { historyService.queryTrades(any()) } returns page(emptyList(), 0L)
        coEvery { historyService.queryLedgers(any()) } returns page(emptyList(), 0L)
        coEvery { historyService.queryBalanceCheckpoints(any()) } returns page(emptyList(), 0L)
        coEvery { historyService.queryCycleActivities(any(), any()) } returns emptyList()
        coEvery { historyStore.valuesCoverage(any()) } answers {
            val scope = firstArg<HistorySourceScope>()
            HistoryValuesCoverage(scope.sourceKey, null, null, 0L, 0L)
        }
        coEvery { historyStore.listRecordedScopes(any()) } returns emptyList()
        coEvery { historyStore.currentDataRevision() } returns 0L
        coEvery { historyStore.loadSyncJobs(any()) } returns emptyList()
        coEvery { historyStore.syncProgress(any()) } returns emptyList()
        every { syncCoordinator.blockedReason() } returns null
    }

    private fun newPresenter(store: HistoryStore = historyStore, nowProvider: () -> Instant = { fixedNow }) =
        HistoryApiPresenter(
            historyService = historyService,
            historyStore = store,
            reportingService = reportingService,
            syncCoordinator = syncCoordinator,
            nowProvider = nowProvider,
        )

    private suspend fun charts(
        params: HistoryApiRangeParams = HistoryApiRangeParams(),
        currentScope: HistorySourceScope = accountScope,
        selection: String? = null,
        scopeId: String? = null,
        api: HistoryApiPresenter = presenter,
    ) = api.charts(params, currentScope, selection, scopeId, null)

    private suspend fun activities(cursor: String) = presenter.activities(
        params = HistoryApiRangeParams(),
        currentScope = accountScope,
        scopeId = null,
        modeParam = null,
        cursor = cursor,
        limit = null,
    )

    private suspend fun expectRequestError(expectedMessage: String, action: suspend () -> Any?) {
        val actualMessage = try {
            action()
            null
        } catch (error: HistoryApiRequestException) {
            error.message
        }
        actualMessage shouldBe expectedMessage
    }

    private fun observation(
        id: String,
        time: Instant,
        totalUsd: String?,
        assets: List<HistoryAssetValue> = listOf(asset("BTC", valueUsd = totalUsd)),
    ) = HistoryObservation(
        observationId = id,
        cycleId = "cycle-$id",
        sourceScope = accountScope,
        observedAt = time,
        balanceRequestStartedAt = time.minusMillis(50),
        balanceResponseEndedAt = time,
        priceRequestStartedAt = time.minusMillis(40),
        priceResponseEndedAt = time,
        status = if (totalUsd == null) HistoryObservationStatus.INCOMPLETE else HistoryObservationStatus.COMPLETE,
        totalUsd = totalUsd?.let(::BigDecimal),
        incompleteReasons = if (totalUsd == null) listOf("missing-price") else emptyList(),
        assets = assets,
    )

    private fun asset(symbol: String, valueUsd: String?) = HistoryAssetValue(
        symbol = symbol,
        status = if (valueUsd == null) HistoryAssetValueStatus.MISSING_PRICE else HistoryAssetValueStatus.COMPLETE,
        quantity = valueUsd?.let(::BigDecimal),
        priceUsd = valueUsd?.let(::BigDecimal),
        valueUsd = valueUsd?.let(::BigDecimal),
    )

    private fun ledger(
        id: String,
        type: String?,
        asset: String?,
        amount: String?,
        subtype: String? = null,
        time: Instant? = fixedNow.minusSeconds(60),
    ) = HistoryLedger(
        accountIdentityDigest = accountDigest,
        ledgerId = id,
        refid = null,
        time = time,
        rawTime = time?.toString(),
        type = type,
        subtype = subtype,
        assetClass = "currency",
        asset = asset,
        amount = amount,
        fee = "0",
        balance = null,
        rawRecordJson = null,
        hasValidTime = time != null,
        hasValidAmount = amount?.toBigDecimalOrNull() != null,
        hasValidFee = true,
        hasValidBalance = false,
        isValidRecord = true,
    )

    private fun checkpoint(
        id: String,
        status: HistoryBalanceStatus,
        quantity: BigDecimal?,
        rawAssetKey: String? = "XXBT",
        observedAt: Instant? = fixedNow.minusSeconds(60).plusNanos(id.length.toLong()),
    ) = HistoryBalanceCheckpoint(
        accountIdentityDigest = accountDigest,
        ledgerId = id,
        observedAt = observedAt,
        rawAssetClass = "currency",
        rawAssetKey = rawAssetKey,
        rawAmount = null,
        rawFee = null,
        rawBalance = quantity?.toPlainString(),
        quantity = quantity,
        status = status,
    )

    private fun <T> page(items: List<T>, total: Long?, limit: Int = 1, offset: Int = 0, hasMore: Boolean = false) =
        HistoryPage(items = items, total = total, limit = limit, offset = offset, hasMore = hasMore)

    private fun encodeCursor(payload: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
}
