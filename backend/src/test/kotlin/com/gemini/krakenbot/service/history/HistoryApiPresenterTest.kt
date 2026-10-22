package com.gemini.krakenbot.service.history

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.service.reporting.HistoryReportingService
import com.gemini.krakenbot.view.component.allocationDeviationSeries
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
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64

class HistoryApiPresenterTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val accountDigest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val fingerprint = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
    private val oldFingerprint = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val newFingerprint = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
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

        "all range requests daily values without a lower bound" {
            val response = charts(HistoryApiRangeParams(preset = "all"))

            response.range.fromIso.shouldBeNull()
            response.fullRange.fromIso.shouldBeNull()
            response.range.toIso shouldBe "2026-03-10T12:00:00Z"
            response.resolution shouldBe "1d"
            coVerify {
                historyStore.queryDailyValues(
                    accountDigest,
                    HISTORY_ACCOUNT_DAILY_SCOPE,
                    null,
                    any(),
                    any(),
                    any(),
                )
            }
        }

        "asset filter plots one USD component and keeps incomplete days as gaps" {
            val days = (7..9).map { LocalDate.of(2026, 3, it) }
            fun assetValue(day: LocalDate, key: String, amount: String, assetClass: String = "currency") =
                HistoryDailyAssetValue(
                    dayEnd = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                    accountIdentityDigest = accountDigest,
                    rawAssetClass = assetClass,
                    rawAssetKey = key,
                    quantity = BigDecimal.ONE,
                    priceUsd = BigDecimal(amount),
                    valueUsd = BigDecimal(amount),
                    pricePair = "${key}USD",
                    priceCandleOpen = day.atStartOfDay(ZoneOffset.UTC).toInstant(),
                    priceSource = "fixture",
                    status = HistoryDailyValueStatus.COMPLETE,
                )
            val completeDays = listOf(
                HistoryDailyValue(
                    dayEnd = days[0].plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                    accountIdentityDigest = accountDigest,
                    sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
                    totalUsd = BigDecimal("160"),
                    status = HistoryDailyValueStatus.COMPLETE,
                    assets = listOf(assetValue(days[0], "XXBT", "100"), assetValue(days[0], "ZUSD", "60")),
                    dayLabel = days[0],
                ),
                HistoryDailyValue(
                    dayEnd = days[1].plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                    accountIdentityDigest = accountDigest,
                    sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
                    totalUsd = BigDecimal("75"),
                    status = HistoryDailyValueStatus.COMPLETE,
                    assets = listOf(assetValue(days[1], "ZUSD", "75")),
                    dayLabel = days[1],
                ),
                HistoryDailyValue(
                    dayEnd = days[2].plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                    accountIdentityDigest = accountDigest,
                    sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
                    totalUsd = null,
                    status = HistoryDailyValueStatus.INCOMPLETE,
                    assets = listOf(assetValue(days[2], "STRC", "900", "equity")),
                    dayLabel = days[2],
                ),
            )
            coEvery {
                historyStore.queryDailyValues(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE, any(), any(), any(), any())
            } returns page(completeDays, total = 3L)

            val response = charts(HistoryApiRangeParams(preset = "all", valueAsset = "BTC"))

            response.availableValueAssets shouldContainExactly listOf("BTC", "USD")
            response.selectedValueAsset shouldBe "BTC"
            response.series.single().points.map { it.v } shouldBe
                listOf(BigDecimal("100"), BigDecimal.ZERO, null)
            response.series.single().points.map { it.complete } shouldBe listOf(true, true, false)
        }

        "asset chart filters equity rows and explains a missing selected close" {
            val days = listOf(
                LocalDate.parse("2026-03-07"),
                LocalDate.parse("2026-03-08"),
                LocalDate.parse("2026-03-09"),
            )
            fun assetValue(
                day: LocalDate,
                key: String,
                assetClass: String = "currency",
                value: String? = null,
                status: HistoryDailyValueStatus = HistoryDailyValueStatus.COMPLETE,
                reason: String? = null,
            ) = HistoryDailyAssetValue(
                dayEnd = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                accountIdentityDigest = accountDigest,
                rawAssetClass = assetClass,
                rawAssetKey = key,
                quantity = value?.let(::BigDecimal),
                priceUsd = value?.let(::BigDecimal),
                valueUsd = value?.let(::BigDecimal),
                pricePair = value?.let { "${key}USD" },
                priceCandleOpen = value?.let { day.atStartOfDay(ZoneOffset.UTC).toInstant() },
                priceSource = value?.let { "TEST" },
                status = status,
                reason = reason,
            )
            val values = listOf(
                dailyValue(days[0], "100").copy(
                    assets = listOf(
                        assetValue(days[0], "BTC", value = "100"),
                        assetValue(days[0], "AAPL", assetClass = "equity", value = "10"),
                    ),
                ),
                dailyValue(days[1], "100").copy(
                    totalUsd = null,
                    status = HistoryDailyValueStatus.NO_PRICE,
                    assets = listOf(
                        assetValue(
                            days[1],
                            "XBT",
                            status = HistoryDailyValueStatus.NO_PRICE,
                            reason = "No same-day close.",
                        ),
                        assetValue(
                            days[1],
                            "AAPL",
                            assetClass = "equity",
                            status = HistoryDailyValueStatus.NO_PRICE,
                        ),
                    ),
                ),
                dailyValue(days[2], "100").copy(totalUsd = null, status = HistoryDailyValueStatus.INCOMPLETE),
            )
            coEvery {
                historyStore.queryDailyValues(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE, any(), any(), any(), any())
            } returns page(values, 3L)

            val filtered = charts(HistoryApiRangeParams(preset = "all", valueAsset = "BTC"))
            val invalidFilter = charts(HistoryApiRangeParams(preset = "all", valueAsset = "DOGE"))

            filtered.availableValueAssets shouldContainExactly listOf("BTC")
            filtered.series.single().key shouldBe "asset:BTC"
            filtered.series.single().points.map { it.v } shouldContainExactly listOf(BigDecimal("100"), null, null)
            filtered.series.single().points[1].reasons shouldBe listOf("BTC: No same-day close.")
            filtered.series.single().points[2].reasons shouldBe listOf("INCOMPLETE")
            invalidFilter.selectedValueAsset shouldBe null
            invalidFilter.series.single().key shouldBe HISTORY_API_PORTFOLIO_KEY
            invalidFilter.series.single().points.first().v shouldBe BigDecimal("100")
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

            val point = allocationSeries(listOf(nullTotal), mapOf("BTC" to BigDecimal("40"))).single().points.single()

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

            val point = allocationSeries(listOf(zeroTotal), mapOf("BTC" to BigDecimal("40"))).single().points.single()

            point.v.shouldBeNull()
            point.complete shouldBe false
        }

        "allocation deviation is the observed share minus the configured target in percentage points" {
            val onTarget = observation(
                id = "on-target",
                time = fixedNow,
                totalUsd = "100.00",
                assets = listOf(asset("BTC", valueUsd = "40.00"), asset("ETH", valueUsd = "60.00")),
            )

            val series = allocationSeries(listOf(onTarget), mapOf("BTC" to BigDecimal("40"), "ETH" to BigDecimal("60")))

            series.map { it.key } shouldContainExactly listOf("BTC", "ETH")
            series.forEach { entry -> entry.unit shouldBe "percent" }
            series.forEach { entry -> entry.points.single().v!!.shouldBeEqualComparingTo(BigDecimal("0")) }
            series.forEach { entry -> entry.points.single().complete shouldBe true }
        }

        "allocation deviation is negative when a holding is below its target" {
            val underTarget = observation(
                id = "under-target",
                time = fixedNow,
                totalUsd = "100.00",
                assets = listOf(asset("BTC", valueUsd = "30.00"), asset("ETH", valueUsd = "70.00")),
            )

            val series = allocationSeries(
                listOf(underTarget),
                mapOf(
                    "BTC" to BigDecimal("40"),
                    "ETH" to BigDecimal("60"),
                ),
            )

            series.first { it.key == "BTC" }.points.single().v!!.shouldBeEqualComparingTo(BigDecimal("-10"))
            series.first { it.key == "ETH" }.points.single().v!!.shouldBeEqualComparingTo(BigDecimal("10"))
        }

        "the chart api and the server rendered chart report the same deviation for one sample" {
            val sample = observation(
                id = "parity",
                time = fixedNow,
                totalUsd = "100.00",
                assets = listOf(asset("BTC", valueUsd = "45.00"), asset("ETH", valueUsd = "55.00")),
            )
            val targets = mapOf("BTC" to BigDecimal("40"), "ETH" to BigDecimal("60"))

            val api = allocationSeries(listOf(sample), targets)
            val rendered = allocationDeviationSeries(listOf(sample), targets)

            api.map { it.key } shouldContainExactly listOf("BTC", "ETH")
            api.forEach { entry ->
                val deviation = entry.points.single().v!!
                deviation.shouldBeEqualComparingTo(
                    if (entry.key == "BTC") BigDecimal("5") else BigDecimal("-5"),
                )
            }
            api.map { it.key } shouldContainExactly rendered.map { it.first }
            api.forEach { entry ->
                val renderedPoints = rendered.first { it.first == entry.key }.second
                entry.points.map { it.v } shouldContainExactly renderedPoints.map { it.value }
            }
        }

        "recorded scope boundaries separate chronological episodes instead of comparing spans" {
            val oldScopeSource = accountScope.copy(managedScopeFingerprint = oldFingerprint)
            val newScopeSource = accountScope.copy(managedScopeFingerprint = newFingerprint)
            val oldScope = HistoryApiScope(
                id = oldScopeSource.sourceKey,
                symbols = listOf("BTC", "USD"),
                earliestIso = "2026-03-01T00:00:00Z",
                latestIso = "2026-03-05T00:00:00Z",
                current = false,
            )
            val newScope = HistoryApiScope(
                id = newScopeSource.sourceKey,
                symbols = listOf("BTC", "ETH", "USD"),
                earliestIso = "2026-03-03T00:00:00Z",
                latestIso = "2026-03-09T00:00:00Z",
                current = true,
            )
            val window = HistoryApiWindow(
                preset = "7d",
                zone = ZoneOffset.UTC,
                selectedFrom = Instant.parse("2026-03-02T00:00:00Z"),
                selectedUntil = Instant.parse("2026-03-08T00:00:00Z"),
                from = Instant.parse("2026-03-02T00:00:00Z"),
                until = Instant.parse("2026-03-08T00:00:00Z"),
                now = fixedNow,
                baselineFrom = Instant.parse("2026-03-02T00:00:00Z"),
            )
            val observations = mapOf(
                oldScope.id to listOf(
                    observation("old-1", Instant.parse("2026-03-03T00:00:00Z"), "150.00", scope = oldScopeSource),
                ),
                newScope.id to listOf(
                    observation("new-1", Instant.parse("2026-03-04T00:00:00Z"), "200.00", scope = newScopeSource),
                ),
            )

            val episodes = recordedEpisodes(observations)
            val conflicts = recordedConflictInstants(observations)
            val boundaries = recordedScopeBoundaries(episodes, conflicts, window)

            boundaries.single().kind shouldBe HistoryApiBoundaryKind.ASSET_SET_CHANGE
            boundaries.single().fromIso shouldBe "2026-03-03T00:00:00Z"
            boundaries.single().toIso shouldBe "2026-03-04T00:00:00Z"
            conflicts shouldBe emptySet()
            val series = recordedPortfolioSeries(oldScope, episodes, conflicts)
            series.points.single().v!!.shouldBeEqualComparingTo(BigDecimal("150.00"))
        }

        "a recorded scope observed at the same instant as another scope withholds both totals" {
            val shared = Instant.parse("2026-03-04T00:00:00Z")
            val oldScopeSource = accountScope.copy(managedScopeFingerprint = oldFingerprint)
            val newScopeSource = accountScope.copy(managedScopeFingerprint = newFingerprint)
            val oldScope = HistoryApiScope(
                id = oldScopeSource.sourceKey,
                symbols = listOf("BTC", "USD"),
                earliestIso = "2026-03-03T00:00:00Z",
                latestIso = "2026-03-05T00:00:00Z",
                current = false,
            )
            val newScope = HistoryApiScope(
                id = newScopeSource.sourceKey,
                symbols = listOf("BTC", "ETH", "USD"),
                earliestIso = "2026-03-03T00:00:00Z",
                latestIso = "2026-03-05T00:00:00Z",
                current = true,
            )
            val observations = mapOf(
                oldScope.id to listOf(observation("old-1", shared, "150.00", scope = oldScopeSource)),
                newScope.id to listOf(observation("new-1", shared, "200.00", scope = newScopeSource)),
            )

            val episodes = recordedEpisodes(observations)
            val conflicts = recordedConflictInstants(observations)
            val window = HistoryApiWindow(
                preset = "7d",
                zone = ZoneOffset.UTC,
                selectedFrom = Instant.parse("2026-03-02T00:00:00Z"),
                selectedUntil = Instant.parse("2026-03-08T00:00:00Z"),
                from = Instant.parse("2026-03-02T00:00:00Z"),
                until = Instant.parse("2026-03-08T00:00:00Z"),
                now = fixedNow,
                baselineFrom = Instant.parse("2026-03-02T00:00:00Z"),
            )
            val boundaries = recordedScopeBoundaries(episodes, conflicts, window)

            conflicts shouldBe setOf(shared)
            boundaries.single().kind shouldBe HistoryApiBoundaryKind.OVERLAPPING_SCOPES
            recordedPortfolioSeries(oldScope, episodes, conflicts).points.single().v.shouldBeNull()
            recordedPortfolioSeries(newScope, episodes, conflicts).points.single().v.shouldBeNull()
        }

        "ledger chart selection keeps funding events and excludes staking rows" {
            val rows = listOf(
                ledger("deposit", type = " Deposit ", asset = "ZUSD", amount = "100.00"),
                ledger("withdrawal", type = "withdrawal", asset = "ZEUR", amount = "10.00"),
                ledger("stake", type = "staking", asset = "XXBT", amount = "0.1"),
            )
            coEvery { historyService.queryLedgers(match { query -> query.limit == HISTORY_MAX_PAGE_LIMIT }) } returns
                page(rows, rows.size.toLong(), HISTORY_MAX_PAGE_LIMIT)

            val response = charts(params = HistoryApiRangeParams(preset = "all"), selection = "deposits")

            response.series.map { series -> series.key } shouldContainExactly listOf("ZUSD", "ZEUR")
            response.series.first().points.single().v!!.shouldBeEqualComparingTo(BigDecimal("100.00"))
            response.series.last().points.single().v!!.shouldBeEqualComparingTo(BigDecimal("-10.00"))
        }

        "cash dividends from stock holdings are USD funding deposits rather than rewards" {
            val rows = listOf(
                ledger(
                    "usd-cash-dividend",
                    type = " DIVIDEND ",
                    subtype = " CashDividend ",
                    asset = "ZUSD",
                    amount = "3.25",
                ),
                ledger("stock-dividend", type = "dividend", subtype = "cashdividend", asset = "STRC", amount = "1"),
            )
            coEvery { historyService.queryLedgers(match { query -> query.limit == HISTORY_MAX_PAGE_LIMIT }) } returns
                page(rows, rows.size.toLong(), HISTORY_MAX_PAGE_LIMIT)

            val funding = charts(params = HistoryApiRangeParams(preset = "all"), selection = "deposits")
            val rewards = charts(params = HistoryApiRangeParams(preset = "all"), selection = "rewards")

            funding.series.map { it.key } shouldContainExactly listOf("ZUSD")
            funding.series.single().points.single().v!!.shouldBeEqualComparingTo(BigDecimal("3.25"))
            rewards.series shouldBe emptyList()
        }

        "a withdrawal reduces the cumulative funding balance of its asset" {
            val rows = listOf(
                ledger(
                    "deposit",
                    type = "deposit",
                    asset = "ZUSD",
                    amount = "100.00",
                    time = fixedNow.minusSeconds(120),
                ),
                ledger(
                    "withdrawal",
                    type = "withdrawal",
                    asset = "ZUSD",
                    amount = "40.00",
                    time = fixedNow.minusSeconds(60),
                ),
            )
            coEvery { historyService.queryLedgers(match { query -> query.limit == HISTORY_MAX_PAGE_LIMIT }) } returns
                page(rows, rows.size.toLong(), HISTORY_MAX_PAGE_LIMIT)

            val response = charts(params = HistoryApiRangeParams(preset = "all"), selection = "deposits")

            val points = response.series.single().points
            points.map { it.v } shouldContainExactly listOf(
                BigDecimal("100.00"),
                BigDecimal("60.00"),
            )
        }

        "a zoomed period measures its cumulative ledger from the requested baseline" {
            val rows = listOf(ledger("deposit", type = "deposit", asset = "ZUSD", amount = "100.00"))
            coEvery { historyService.queryLedgers(match { query -> query.limit == HISTORY_MAX_PAGE_LIMIT }) } returns
                page(rows, rows.size.toLong(), HISTORY_MAX_PAGE_LIMIT)

            charts(
                params = HistoryApiRangeParams(
                    preset = "all",
                    baselineFrom = "2026-03-01T00:00:00Z",
                ),
                selection = "deposits",
            )

            coVerify {
                historyService.queryLedgers(
                    match { query ->
                        query.from == Instant.parse("2026-03-01T00:00:00Z") &&
                            query.limit == HISTORY_MAX_PAGE_LIMIT
                    },
                )
            }
        }

        "a ledger series labels its asset canonically and keeps the raw key" {
            val rows = listOf(ledger("deposit", type = "deposit", asset = "ZUSD", amount = "100.00"))
            coEvery { historyService.queryLedgers(match { query -> query.limit == HISTORY_MAX_PAGE_LIMIT }) } returns
                page(rows, rows.size.toLong(), HISTORY_MAX_PAGE_LIMIT)

            val response = charts(params = HistoryApiRangeParams(preset = "all"), selection = "deposits")

            response.series.single().key shouldBe "ZUSD"
            response.series.single().label shouldBe "USD"
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
            val ledgerCalls = mutableListOf<String?>()
            coEvery { historyService.queryLedgers(match { query -> query.limit == HISTORY_MAX_PAGE_LIMIT }) } coAnswers
                {
                    ledgerCalls += firstArg<HistoryEventQuery>().type
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
            ledgerCalls.distinct() shouldContainExactly ledgerCalls
            ledgerCalls.size shouldBe ledgerCalls.distinct().size
            checkpointPageCalls shouldBe 1
        }

        "invalid ledger amounts stay incomplete and missing time or asset rows are omitted" {
            val rows = listOf(
                ledger("no-time", type = "deposit", asset = "XXBT", amount = "1", time = null),
                ledger("invalid-amount", type = "deposit", asset = "XXBT", amount = "not-a-number"),
                ledger("no-asset", type = "deposit", asset = null, amount = "1"),
            )

            val series = ledgerQuantitySeries(rows, ::fundingAmountOf)

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
                listOf("STAKING", "REWARD", "EARN", "AIRDROP", "TRANSFER")
        }

        "invalid and missing checkpoint quantities are incomplete and withheld" {
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
            points[0].v.shouldBeNull()
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

        "data status reports the first and last durable daily values" {
            val day = LocalDate.parse("2026-03-09")
            val value = dailyValue(day, "125.50")
            coEvery {
                historyStore.queryDailyValues(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE, any(), any(), any(), any())
            } returns page(listOf(value), 1L)

            val status = presenter.dataStatus(HistoryApiRangeParams(preset = "all"), accountScope)

            status.availableValueFromIso shouldBe "2026-03-09T00:00:00Z"
            status.availableValueToIso shouldBe "2026-03-09T00:00:00Z"
            status.availableValueDays shouldBe 1L
            status.coverage.valuesFirstIso shouldBe "2026-03-09T00:00:00Z"
            status.coverage.valuesLastIso shouldBe "2026-03-09T00:00:00Z"
            status.coverage.incomplete shouldBe false
        }

        "data status counts durable incomplete daily rows when no valuation job exists" {
            val day = LocalDate.parse("2026-03-09")
            val incompleteValue = dailyValue(day, "0").copy(
                totalUsd = null,
                status = HistoryDailyValueStatus.NO_PRICE,
            )
            coEvery {
                historyStore.queryDailyValues(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE, any(), any(), any(), any())
            } coAnswers {
                val limit = arg<Int>(4)
                val offset = arg<Int>(5)
                val items = listOf(incompleteValue).drop(offset).take(limit)
                page(
                    items,
                    total = 1L,
                    limit = limit,
                    offset = offset,
                    hasMore = offset + items.size < 1,
                )
            }
            coEvery {
                historyStore.countIncompleteDailyValues(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE)
            } returns 1L

            val status = presenter.dataStatus(HistoryApiRangeParams(preset = "all"), accountScope)

            status.valuation shouldBe null
            status.coverage.incomplete shouldBe true
            status.coverage.gapCount shouldBe 1
        }

        "import readiness checks gaps only inside the frozen valuation target" {
            val targetStart = LocalDate.parse("2025-10-21")
            val targetEnd = LocalDate.parse("2026-10-20")
            val targetStartSec = targetStart.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
            val targetThroughSec = targetEnd.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() - 1
            val job = HistoryValuationJob(
                accountIdentityDigest = accountDigest,
                sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
                generationId = "generation",
                state = HistoryValuationJobState.COMPLETE,
                stage = HistoryValuationStage.PRICE_AND_VALUE,
                targetStartDay = targetStart,
                targetEndDay = targetEnd,
                nextDay = null,
                targetDays = 365,
                balancesProcessed = 365,
                balancesValid = 365,
                balancesBlocked = 0,
                requiredPriceTasks = 1,
                completedPriceTasks = 1,
                completeValueDays = 365,
                blockedValueDays = 0,
                sourceRevision = 1,
                calculationPolicyVersion = HISTORY_DAILY_VALUE_POLICY_VERSION,
                lastProgressAt = fixedNow,
                nextRetryAt = null,
                lastSuccessAt = fixedNow,
                lastError = null,
                updatedAt = fixedNow,
            )
            fun progress(feed: HistoryFeed, windows: List<HistorySyncCoverageWindow>) = HistorySyncProgressSummary(
                accountIdentityDigest = accountDigest,
                feed = feed,
                recentWindowStartSec = null,
                recentCompletedThroughSec = null,
                recentLastSuccessAt = null,
                backfillState = HistorySyncJobState.COMPLETE,
                backfillCompletedThroughSec = targetThroughSec,
                backfillCursor = null,
                repairLastRunAt = null,
                lastError = null,
                nextRetryAt = null,
                coveredWindows = windows,
            )
            coEvery { historyStore.loadValuationJob(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE) } returns job
            coEvery { historyStore.syncProgress(accountDigest) } returns listOf(
                progress(
                    HistoryFeed.TRADES,
                    listOf(
                        HistorySyncCoverageWindow(0, targetStartSec - 2),
                        HistorySyncCoverageWindow(targetStartSec, targetThroughSec),
                    ),
                ),
                progress(HistoryFeed.LEDGERS, listOf(HistorySyncCoverageWindow(targetStartSec, targetThroughSec - 1))),
            )

            val status = presenter.dataStatus(HistoryApiRangeParams(preset = "all"), accountScope)

            status.progress.first { it.feed == "TRADES" }.coveredGapCount shouldBe 1
            status.progress.first { it.feed == "TRADES" }.targetCoverageComplete shouldBe true
            status.progress.first { it.feed == "LEDGERS" }.targetCoverageComplete shouldBe false
        }

        "data status reports durable per-asset reasons for blocked daily values" {
            val targetStart = LocalDate.parse("2025-03-10")
            val targetEnd = targetStart.plusDays(364)
            val firstDay = dailyValue(targetStart, "0").copy(
                totalUsd = null,
                status = HistoryDailyValueStatus.NO_PRICE,
                assets = listOf(
                    HistoryDailyAssetValue(
                        dayEnd = targetStart.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                        accountIdentityDigest = accountDigest,
                        rawAssetClass = "currency",
                        rawAssetKey = "XXBT",
                        quantity = BigDecimal.ONE,
                        priceUsd = null,
                        valueUsd = null,
                        pricePair = null,
                        priceCandleOpen = null,
                        priceSource = null,
                        status = HistoryDailyValueStatus.NO_PRICE,
                        reason = "No completed USD close.",
                    ),
                ),
            )
            val secondDay = dailyValue(targetStart.plusDays(1), "0").copy(
                totalUsd = null,
                status = HistoryDailyValueStatus.NO_BALANCE,
            )
            val dailyRows = listOf(firstDay, secondDay)
            coEvery {
                historyStore.loadValuationJob(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE)
            } returns valuationJob(targetStart, targetEnd)
            coEvery {
                historyStore.queryDailyValues(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE, any(), any(), any(), any())
            } coAnswers {
                val limit = arg<Int>(4)
                val offset = arg<Int>(5)
                val items = dailyRows.drop(offset).take(limit)
                page(
                    items,
                    total = dailyRows.size.toLong(),
                    limit = limit,
                    offset = offset,
                    hasMore =
                    offset + items.size < dailyRows.size,
                )
            }

            val status = presenter.dataStatus(HistoryApiRangeParams(preset = "all"), accountScope)

            status.valuation?.blockedValueReasons shouldContainExactly listOf(
                "$targetStart UTC · BTC: No completed USD close.",
                "${targetStart.plusDays(1)} UTC · no_balance",
            )
            status.valuation?.state shouldBe "NEEDS_ATTENTION"
            status.valuation?.blockedValueDays shouldBe 2
            status.coverage.gapCount shouldBe 2
        }

        "holdings at a date preserve cash and never expose equity-class shares" {
            val day = LocalDate.parse("2026-03-09")
            val dayEnd = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
            val total = HistoryDailyValue(
                dayEnd = dayEnd,
                accountIdentityDigest = accountDigest,
                sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
                totalUsd = BigDecimal("100"),
                status = HistoryDailyValueStatus.INCOMPLETE,
                assets = emptyList(),
                dayLabel = day,
                generationId = "generation",
                calculationPolicyVersion = HISTORY_DAILY_VALUE_POLICY_VERSION,
                sourceRevision = 1,
            )
            val cash = HistoryDailyAssetValue(
                dayEnd = dayEnd,
                accountIdentityDigest = accountDigest,
                rawAssetClass = "currency",
                rawAssetKey = "ZUSD",
                quantity = BigDecimal("90"),
                priceUsd = BigDecimal.ONE,
                valueUsd = BigDecimal("90"),
                pricePair = "USD",
                priceCandleOpen = day.atStartOfDay(ZoneOffset.UTC).toInstant(),
                priceSource = "USD_AT_PAR",
                status = HistoryDailyValueStatus.COMPLETE,
            )
            val equityShares = cash.copy(
                rawAssetClass = "equity",
                rawAssetKey = "AAPL",
                quantity = BigDecimal.ONE,
                priceUsd = BigDecimal("10"),
                valueUsd = BigDecimal("10"),
            )
            coEvery {
                historyStore.queryDailyValues(
                    accountDigest,
                    HISTORY_ACCOUNT_DAILY_SCOPE,
                    dayEnd,
                    dayEnd.plusSeconds(1),
                    1,
                    0,
                )
            } returns page(listOf(total), 1L)
            coEvery {
                historyStore.queryDailyAssetValues(
                    accountDigest,
                    HISTORY_ACCOUNT_DAILY_SCOPE,
                    dayEnd,
                    dayEnd.plusSeconds(1),
                    HISTORY_MAX_PAGE_LIMIT,
                    0,
                )
            } returns page(listOf(cash, equityShares), 2L, HISTORY_MAX_PAGE_LIMIT)

            val response = presenter.holdingsAtDate(accountScope, day.toString())

            response.available shouldBe true
            response.totalUsd shouldBe "100"
            response.holdings.map { it.asset } shouldContainExactly listOf("USD")
            response.holdings.single().quantity shouldBe "90"
        }

        "holdings at a date report outside coverage without manufacturing an empty balance" {
            val day = LocalDate.parse("2026-03-09")
            val dayEnd = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
            coEvery {
                historyStore.queryDailyValues(
                    accountDigest,
                    HISTORY_ACCOUNT_DAILY_SCOPE,
                    dayEnd,
                    dayEnd.plusSeconds(1),
                    1,
                    0,
                )
            } returns page(emptyList(), 0L)

            val response = presenter.holdingsAtDate(accountScope, day.toString())

            response.available shouldBe false
            response.totalUsd.shouldBeNull()
            response.totalStatus shouldBe "OUTSIDE_COVERAGE"
            response.holdings shouldBe emptyList()
        }

        "daily summary keeps observed value change unavailable across a missing day" {
            val first = dailyValue(LocalDate.parse("2026-03-07"), "100")
            val last = dailyValue(LocalDate.parse("2026-03-09"), "125").copy(
                totalUsd = null,
                status = HistoryDailyValueStatus.NO_PRICE,
                assets = listOf(
                    HistoryDailyAssetValue(
                        dayEnd = LocalDate.parse("2026-03-10").atStartOfDay(ZoneOffset.UTC).toInstant(),
                        accountIdentityDigest = accountDigest,
                        rawAssetClass = "currency",
                        rawAssetKey = "XXBT",
                        quantity = BigDecimal.ONE,
                        priceUsd = null,
                        valueUsd = null,
                        pricePair = null,
                        priceCandleOpen = null,
                        priceSource = null,
                        status = HistoryDailyValueStatus.NO_PRICE,
                        reason = "No close is available.",
                    ),
                ),
            )
            coEvery {
                historyStore.queryDailyValues(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE, any(), any(), any(), any())
            } returns page(listOf(first, last), 2L)

            val response = presenter.summary(
                params = HistoryApiRangeParams(preset = "all"),
                currentScope = accountScope,
                scopeId = null,
                targetAllocations = emptyList(),
            )

            response.statistics.latestObservedValue.complete shouldBe false
            response.statistics.latestObservedValue.valueUsd.shouldBeNull()
            response.statistics.observedValueChange.complete shouldBe false
            response.statistics.observedValueChange.changeUsd.shouldBeNull()
            response.statistics.rangeExtrema.complete shouldBe false
            response.statistics.observedValueChange.incompleteReason shouldBe
                "Daily value is unavailable on 2026-03-09: no_price."
        }

        "daily summary computes change and extrema only across an entirely complete range" {
            val first = dailyValue(LocalDate.parse("2026-03-07"), "100")
            val second = dailyValue(LocalDate.parse("2026-03-08"), "150")
            coEvery {
                historyStore.queryDailyValues(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE, any(), any(), any(), any())
            } returns page(listOf(first, second), 2L)

            val statistics = presenter.summary(
                params = HistoryApiRangeParams(preset = "all"),
                currentScope = accountScope,
                scopeId = null,
                targetAllocations = emptyList(),
            ).statistics

            statistics.latestObservedValue.valueUsd shouldBe BigDecimal("150")
            statistics.latestObservedValue.complete shouldBe true
            statistics.observedValueChange.changeUsd shouldBe BigDecimal("50")
            statistics.observedValueChange.complete shouldBe true
            statistics.rangeExtrema.high?.valueUsd shouldBe BigDecimal("150")
            statistics.rangeExtrema.low?.valueUsd shouldBe BigDecimal("100")
            statistics.rangeExtrema.complete shouldBe true
        }

        "daily summary reports explicit absence when no account-value rows exist" {
            coEvery {
                historyStore.queryDailyValues(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE, any(), any(), any(), any())
            } returns page(emptyList(), 0L)

            val statistics = presenter.summary(
                params = HistoryApiRangeParams(preset = "all"),
                currentScope = accountScope,
                scopeId = null,
                targetAllocations = emptyList(),
            ).statistics

            statistics.latestObservedValue.valueUsd.shouldBeNull()
            statistics.latestObservedValue.complete shouldBe false
            statistics.observedValueChange.changeUsd.shouldBeNull()
            statistics.observedValueChange.incompleteReason shouldBe "No daily account values are available."
            statistics.rangeExtrema.high.shouldBeNull()
            statistics.rangeExtrema.low.shouldBeNull()
        }

        "daily export queries preserve UTC day labels and reject invalid account dates" {
            val values = presenter.dailyValueExportQuery(
                HistoryApiRangeParams(
                    preset = "custom",
                    start = "2026-03-07",
                    end = "2026-03-09",
                    tz = "America/Los_Angeles",
                ),
                accountScope,
            )
            values.query.accountIdentityDigest shouldBe accountDigest
            values.query.from shouldBe Instant.parse("2026-03-08T00:00:00Z")
            values.query.until shouldBe Instant.parse("2026-03-11T00:00:00Z")
            values.zone.id shouldBe "America/Los_Angeles"

            val holdings = presenter.dailyHoldingsExportQuery("2026-03-09", accountScope)
            holdings.query.day shouldBe LocalDate.parse("2026-03-09")
            holdings.zone shouldBe ZoneOffset.UTC
            expectRequestError("History range dates must use the YYYY-MM-DD format.") {
                presenter.dailyHoldingsExportQuery("2026-02-30", accountScope)
            }
            expectRequestError(
                "Kraken account identity is not verified yet; imported history appears after the first successful authentication.",
            ) {
                presenter.dailyValueExportQuery(HistoryApiRangeParams(preset = "all"), simulationScope)
            }
            expectRequestError(
                "Kraken account identity is not verified yet; imported history appears after the first successful authentication.",
            ) {
                presenter.dailyHoldingsExportQuery("2026-03-09", simulationScope)
            }
            expectRequestError(
                "Kraken account identity is not verified yet; imported history appears after the first successful authentication.",
            ) {
                presenter.holdingsAtDate(simulationScope, "2026-03-09")
            }
            expectRequestError("History range dates must use the YYYY-MM-DD format.") {
                presenter.holdingsAtDate(accountScope, "2026-02-30")
            }
        }

        "unknown stored interval totals fall back to returned point count" {
            val sample = dailyValue(LocalDate.parse("2026-03-09"), "10.00")
            coEvery {
                historyStore.queryDailyValues(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE, any(), any(), any(), any())
            } returns page(listOf(sample), total = null)

            val response = charts(HistoryApiRangeParams(preset = "all"))

            response.fullRange.pointCount shouldBe 1
            response.fullRange.totalObservations shouldBe 1L
            response.fullRange.representative shouldBe false
        }

        "representative chart points retain the stored observation count" {
            val samples = (0..2).map { index ->
                dailyValue(LocalDate.parse("2026-03-${(7 + index).toString().padStart(2, '0')}"), "10.00")
            }
            coEvery {
                historyStore.queryDailyValues(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE, any(), any(), any(), any())
            } returns page(samples, total = 2403L)

            val response = charts(HistoryApiRangeParams(preset = "all"))

            response.fullRange.pointCount shouldBe 3
            response.fullRange.totalObservations shouldBe 2403L
            response.fullRange.representative shouldBe true
        }

        "daily downsampling retains every incomplete day even when gaps exceed the point cap" {
            val start = LocalDate.parse("2024-07-01")
            val gapIndices = setOf(100, 250, 450)
            val values = (0 until 600).map { index ->
                val value = dailyValue(start.plusDays(index.toLong()), "10.00")
                if (index in gapIndices) {
                    value.copy(totalUsd = null, status = HistoryDailyValueStatus.NO_PRICE)
                } else {
                    value
                }
            }
            coEvery {
                historyStore.queryDailyValues(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE, any(), any(), any(), any())
            } coAnswers {
                val offset = arg<Int>(5)
                val pageItems = values.drop(offset).take(HISTORY_PAGE_LIMIT)
                page(
                    pageItems,
                    total = values.size.toLong(),
                    limit = HISTORY_PAGE_LIMIT,
                    offset = offset,
                    hasMore =
                    offset + pageItems.size < values.size,
                )
            }

            val response = charts(HistoryApiRangeParams(preset = "all"), maxPoints = 2)

            response.series.single().points.size shouldBe 5
            response.series.single().points.mapNotNull { it.dayLabel } shouldContainExactly listOf(
                start.toString(),
                start.plusDays(100).toString(),
                start.plusDays(250).toString(),
                start.plusDays(450).toString(),
                start.plusDays(599).toString(),
            )
            response.series.single().points.count { !it.complete } shouldBe 3
            response.coverage.gapCount shouldBe 3
            response.fullRange.totalObservations shouldBe 600L
            response.fullRange.representative shouldBe true
            coVerify(exactly = 2) {
                historyStore.queryDailyValues(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE, any(), any(), any(), any())
            }
        }

        "daily downsampling chooses a middle representative when one chart slot remains" {
            val start = LocalDate.parse("2026-03-06")
            val samples = (0..3).map { index -> dailyValue(start.plusDays(index.toLong()), (10 + index).toString()) }
            coEvery {
                historyStore.queryDailyValues(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE, any(), any(), any(), any())
            } returns page(samples, total = 4L)

            val response = charts(HistoryApiRangeParams(preset = "all"), maxPoints = 3)

            response.series.single().points.mapNotNull { it.dayLabel } shouldContainExactly listOf(
                start.toString(),
                start.plusDays(1).toString(),
                start.plusDays(3).toString(),
            )
            response.series.single().points.map { it.v } shouldContainExactly listOf(
                BigDecimal("10"),
                BigDecimal("11"),
                BigDecimal("13"),
            )
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
        coEvery { historyStore.loadValuationJob(any(), any()) } returns null
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
        maxPoints: Int? = null,
    ) = api.charts(params, currentScope, selection, scopeId, maxPoints)

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
        scope: HistorySourceScope = accountScope,
    ) = HistoryObservation(
        observationId = id,
        cycleId = "cycle-$id",
        sourceScope = scope,
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

    private fun dailyValue(day: LocalDate, totalUsd: String) = HistoryDailyValue(
        dayEnd = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
        accountIdentityDigest = accountDigest,
        sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
        totalUsd = BigDecimal(totalUsd),
        status = HistoryDailyValueStatus.COMPLETE,
        assets = emptyList(),
        dayLabel = day,
        generationId = "test-generation",
        calculationPolicyVersion = HISTORY_DAILY_VALUE_POLICY_VERSION,
        sourceRevision = 1,
    )

    private fun valuationJob(start: LocalDate, end: LocalDate) = HistoryValuationJob(
        accountIdentityDigest = accountDigest,
        sourceAssetScope = HISTORY_ACCOUNT_DAILY_SCOPE,
        generationId = "blocked-generation",
        state = HistoryValuationJobState.NEEDS_ATTENTION,
        stage = HistoryValuationStage.PRICE_AND_VALUE,
        targetStartDay = start,
        targetEndDay = end,
        nextDay = null,
        targetDays = 365,
        balancesProcessed = 365,
        balancesValid = 365,
        balancesBlocked = 0,
        requiredPriceTasks = 1,
        completedPriceTasks = 0,
        completeValueDays = 0,
        blockedValueDays = 2,
        sourceRevision = 1,
        calculationPolicyVersion = HISTORY_DAILY_VALUE_POLICY_VERSION,
        lastProgressAt = fixedNow,
        nextRetryAt = null,
        lastSuccessAt = null,
        lastError = "Historical daily prices could not be fetched.",
        updatedAt = fixedNow,
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
