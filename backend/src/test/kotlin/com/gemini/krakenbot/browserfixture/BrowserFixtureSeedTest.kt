package com.gemini.krakenbot.browserfixture

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.config.configureSerialization
import com.gemini.krakenbot.service.history.HISTORY_ACCOUNT_DAILY_SCOPE
import com.gemini.krakenbot.service.history.HISTORY_MAX_PAGE_LIMIT
import com.gemini.krakenbot.service.history.HistoryActivityQuery
import com.gemini.krakenbot.service.history.HistoryCycleOutcome
import com.gemini.krakenbot.service.history.HistoryDailyValueStatus
import com.gemini.krakenbot.service.history.HistoryEventQuery
import com.gemini.krakenbot.service.history.HistoryObservationStatus
import com.gemini.krakenbot.service.history.HistoryObservationValuator
import com.gemini.krakenbot.service.history.HistoryRangeQuery
import com.gemini.krakenbot.service.history.HistorySourceMode
import com.gemini.krakenbot.service.history.HistorySyncJobState
import com.gemini.krakenbot.service.history.HistoryValuationJobState
import com.gemini.krakenbot.view.util.Routes
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class BrowserFixtureSeedTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "24-hour full-account samples span a regular half-hour cadence and resolve exact holdings" {
            val fixture = BrowserFixtureEnvironment.create(CoroutineScope(SupervisorJob() + Dispatchers.Default))
            try {
                testApplication {
                    application {
                        install(SSE)
                        configureSerialization()
                        routing { fixture.controller.registerRoutes(this) }
                    }
                    startApplication()
                    val chartResponse = client.get("${Routes.API_HISTORY_CHARTS}?preset=24h")
                    chartResponse.status shouldBe HttpStatusCode.OK
                    val chart = jacksonObjectMapper().readTree(chartResponse.bodyAsText())
                    chart["resolution"].asText() shouldBe "cycle"
                    chart["fullRange"]["totalObservations"].asLong() shouldBe
                        BROWSER_FIXTURE_FULL_ACCOUNT_INTRADAY_SAMPLE_COUNT.toLong()

                    val portfolio = chart["series"].first { it["key"].asText() == "portfolio" }
                    val points = portfolio["points"]
                    points.size() shouldBe BROWSER_FIXTURE_FULL_ACCOUNT_INTRADAY_SAMPLE_COUNT
                    val sampleTimes = points.map { Instant.parse(it["selectionTimeIso"].asText()) }
                    sampleTimes.zipWithNext().forEach { (previous, current) ->
                        Duration.between(previous, current) shouldBe Duration.ofMinutes(30)
                    }
                    Duration.between(sampleTimes.first(), sampleTimes.last()) shouldBe Duration.ofMinutes(1_410)

                    val selectedPoint = points[24]
                    val selectedAt = Instant.parse(selectedPoint["selectionTimeIso"].asText())
                    val selectedDay = selectedAt.atZone(ZoneOffset.UTC).toLocalDate()
                    val holdingsResponse = client.get(
                        "${Routes.API_HISTORY_HOLDINGS}?date=$selectedDay&at=$selectedAt",
                    )
                    holdingsResponse.status shouldBe HttpStatusCode.OK
                    val holdings = jacksonObjectMapper().readTree(holdingsResponse.bodyAsText())
                    holdings["available"].asBoolean() shouldBe true
                    holdings["sampleAtIso"].asText() shouldBe selectedAt.toString()
                    BigDecimal(holdings["totalUsd"].asText()) shouldBeEqualComparingTo
                        BigDecimal(selectedPoint["v"].asText())
                }
            } finally {
                fixture.close()
            }
        }

        "account cycle values drive the 24-hour chart and exact-time holdings route" {
            val fixture = BrowserFixtureEnvironment.create(CoroutineScope(SupervisorJob() + Dispatchers.Default))
            try {
                fixture.historyObservationRecorder.start(fixture.applicationScope)
                val snapshot = fixture.portfolioManager.performRebalanceCycle()
                checkNotNull(snapshot).totalValueUSD shouldBeEqualComparingTo BigDecimal("10000")

                val intradayScope = HistoryObservationValuator.fullAccountScope(
                    checkNotNull(fixture.accountScope.accountIdentityDigest),
                )
                val observation = withTimeout(5_000) {
                    while (true) {
                        val page = fixture.historyStore.queryObservations(
                            HistoryRangeQuery(
                                from = Instant.now().minusSeconds(24 * 60 * 60),
                                until = Instant.now().plusSeconds(1),
                                sourceScope = intradayScope,
                                limit = HISTORY_MAX_PAGE_LIMIT,
                            ),
                        )
                        page.items.filter { it.observedAt.isAfter(fixture.fixtureNow) }
                            .maxByOrNull { it.observedAt }
                            ?.let { return@withTimeout it }
                        delay(10)
                    }
                    error("Unreachable")
                }
                check(observation.status == HistoryObservationStatus.COMPLETE) {
                    "Full-account sample incomplete: ${observation.incompleteReasons}"
                }
                checkNotNull(observation.totalUsd) shouldBeEqualComparingTo BigDecimal("10000")
                val exactSample = fixture.historyStore.queryObservations(
                    HistoryRangeQuery(
                        from = observation.observedAt,
                        until = observation.observedAt.plusNanos(1),
                        sourceScope = intradayScope,
                        limit = 2,
                    ),
                )
                exactSample.items.single().observedAt shouldBe observation.observedAt

                testApplication {
                    application {
                        install(SSE)
                        configureSerialization()
                        routing { fixture.controller.registerRoutes(this) }
                    }
                    startApplication()
                    val chartResponse = client.get("${Routes.API_HISTORY_CHARTS}?preset=24h")
                    chartResponse.status shouldBe HttpStatusCode.OK
                    val chart = jacksonObjectMapper().readTree(chartResponse.bodyAsText())
                    chart["resolution"].asText() shouldBe "cycle"
                    chart["fullRange"]["totalObservations"].asLong() shouldBe
                        (BROWSER_FIXTURE_FULL_ACCOUNT_INTRADAY_SAMPLE_COUNT + 1).toLong()
                    val points = chart["series"][0]["points"]
                    val point = points[points.size() - 1]
                    point["selectionTimeIso"].asText() shouldBe observation.observedAt.toString()
                    point["v"].decimalValue() shouldBeEqualComparingTo BigDecimal("10000")

                    val day = observation.observedAt.atZone(ZoneOffset.UTC).toLocalDate().toString()
                    val holdingsResponse = client.get(
                        "${Routes.API_HISTORY_HOLDINGS}?date=$day&at=${observation.observedAt}",
                    )
                    holdingsResponse.status shouldBe HttpStatusCode.OK
                    val holdings = jacksonObjectMapper().readTree(holdingsResponse.bodyAsText())
                    check(holdings["available"].asBoolean()) { "Exact-time holdings unavailable: $holdings" }
                    holdings["sampleAtIso"].asText() shouldBe observation.observedAt.toString()
                    check(BigDecimal(holdings["totalUsd"].asText()).compareTo(BigDecimal("10000")) == 0) {
                        "Exact-time holdings total differed: $holdings"
                    }
                }
            } finally {
                fixture.close()
            }
        }

        "History page and 24-hour requests read successfully while a writer remains active" {
            val fixture = BrowserFixtureEnvironment.create(CoroutineScope(SupervisorJob() + Dispatchers.Default))
            try {
                val revision = fixture.historyStore.currentDataRevision()
                testApplication {
                    application {
                        install(SSE)
                        configureSerialization()
                        routing { fixture.controller.registerRoutes(this) }
                    }
                    startApplication()
                    fixture.historyDatabase.connect().use { writer ->
                        writer.autoCommit = false
                        try {
                            writer.createStatement().use {
                                it.executeUpdate(
                                    "UPDATE history_data_revision SET revision = revision + 1 WHERE id = 1",
                                )
                            }
                            for (url in listOf("/history", "/history?range=24h")) {
                                val response = client.get(url)
                                response.status shouldBe HttpStatusCode.OK
                                val body = response.bodyAsText()
                                body.contains("History") shouldBe true
                                if (url.endsWith("range=24h")) {
                                    body.contains("24h cycle values ·") shouldBe true
                                    body.contains("data-history-at=") shouldBe true
                                    body.contains("Daily marks") shouldBe false
                                }
                            }
                            coroutineScope {
                                listOf("charts", "summary", "trades", "ledgers", "activities").map { endpoint ->
                                    async {
                                        val eventPage = endpoint == "trades" || endpoint == "ledgers"
                                        val until = fixture.fixtureNow.plusSeconds(1)
                                        val from = until.minusSeconds(24 * 60 * 60)
                                        val response = client.get("/api/history/$endpoint") {
                                            if (eventPage) {
                                                parameter("from", from.toString())
                                                parameter("until", until.toString())
                                            } else {
                                                parameter("preset", "24h")
                                            }
                                        }
                                        response.status shouldBe HttpStatusCode.OK
                                        val json = jacksonObjectMapper().readTree(response.bodyAsText())
                                        if (eventPage) {
                                            (json["items"].size() > 0) shouldBe true
                                            json["items"].forEach { item ->
                                                val time = Instant.parse(item["time"].asText())
                                                (time >= from && time < until) shouldBe true
                                            }
                                        } else {
                                            json["range"]["preset"].asText() shouldBe "24h"
                                            if (endpoint == "charts") {
                                                json["resolution"].asText() shouldBe "cycle"
                                                json["fullRange"]["totalObservations"].asLong() shouldBe
                                                    BROWSER_FIXTURE_FULL_ACCOUNT_INTRADAY_SAMPLE_COUNT.toLong()
                                            }
                                        }
                                    }
                                }.awaitAll()
                            }
                            fixture.historyStore.currentDataRevision() shouldBe revision
                            writer.commit()
                        } catch (error: Throwable) {
                            writer.rollback()
                            throw error
                        }
                    }
                }
                fixture.historyStore.currentDataRevision() shouldBe revision + 1
                val query = HistoryEventQuery(accountIdentityDigest = fixture.accountScope.accountIdentityDigest)
                fixture.historyStore.queryTrades(query).total shouldBe BROWSER_FIXTURE_EVENT_COUNT.toLong()
                fixture.historyStore.queryLedgers(query).total shouldBe BROWSER_FIXTURE_LEDGER_EVENT_COUNT.toLong()
            } finally {
                fixture.close()
            }
        }

        "seeded account history covers 400 daily values, complete import coverage, and 100 days of events" {
            val fixture = BrowserFixtureEnvironment.create(CoroutineScope(SupervisorJob() + Dispatchers.Default))
            val tempDirectory = fixture.tempDirectory
            try {
                Files.isRegularFile(tempDirectory.resolve("history.db")) shouldBe true
                val scope = fixture.accountScope
                scope.mode shouldBe HistorySourceMode.ACCOUNT
                scope.accountIdentityDigest shouldBe fakeAccountIdentityDigest()

                val digest = checkNotNull(scope.accountIdentityDigest)
                val dailyValues = fixture.historyStore.queryDailyValues(
                    digest,
                    HISTORY_ACCOUNT_DAILY_SCOPE,
                    limit = 500,
                )
                val fixtureEndDay = fixture.fixtureNow.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
                dailyValues.total shouldBe 400L
                dailyValues.items.size shouldBe 400
                dailyValues.items.all { it.status == HistoryDailyValueStatus.COMPLETE } shouldBe true
                dailyValues.items.first().dayLabel shouldBe fixtureEndDay.minusDays(399)
                dailyValues.items.last().dayLabel shouldBe fixtureEndDay
                val fixtureJob = fixture.historyStore.loadValuationJob(digest, HISTORY_ACCOUNT_DAILY_SCOPE)
                fixtureJob?.state shouldBe HistoryValuationJobState.COMPLETE
                fixtureJob?.targetDays shouldBe 365
                val importProgress = fixture.historyStore.syncProgress(digest)
                importProgress.size shouldBe 2
                importProgress.all { it.backfillState == HistorySyncJobState.COMPLETE } shouldBe true
                val importStartSec = fixture.fixtureNow.minusSeconds(401 * 24 * 60 * 60L).epochSecond
                val importEndSec = fixture.fixtureNow.epochSecond + 1
                importProgress.all { progress ->
                    progress.coveredWindows.any { it.startSec <= importStartSec && it.endSec >= importEndSec }
                } shouldBe true
                val firstDayAssets = fixture.historyStore.queryDailyAssetValues(
                    digest,
                    HISTORY_ACCOUNT_DAILY_SCOPE,
                    dailyValues.items.first().dayEnd,
                    dailyValues.items.first().dayEnd.plusSeconds(1),
                ).items.map { it.rawAssetKey }.toSet()
                val lastDayAssets = fixture.historyStore.queryDailyAssetValues(
                    digest,
                    HISTORY_ACCOUNT_DAILY_SCOPE,
                    dailyValues.items.last().dayEnd,
                    dailyValues.items.last().dayEnd.plusSeconds(1),
                ).items.map { it.rawAssetKey }.toSet()
                firstDayAssets shouldBe setOf("XXBT", "XETH", "ZUSD")
                lastDayAssets shouldBe firstDayAssets

                val coverage = fixture.historyStore.valuesCoverage(scope)
                coverage.firstRecordedAt shouldBe fixture.firstSampleAt
                coverage.lastRecordedAt shouldBe fixture.fixtureNow
                (coverage.completeCount + coverage.incompleteCount) shouldBe
                    (BROWSER_FIXTURE_SAMPLE_COUNT + BROWSER_FIXTURE_RANGE_EDGE_COUNT).toLong()
                coverage.incompleteCount shouldBe 6L

                val activities = fixture.historyStore.queryActivityPage(
                    HistoryActivityQuery(sourceScope = scope, limit = HISTORY_MAX_PAGE_LIMIT),
                )
                activities.total shouldBe
                    (BROWSER_FIXTURE_SAMPLE_COUNT + BROWSER_FIXTURE_RANGE_EDGE_COUNT).toLong()
                val dashboardActivities = fixture.historyStore.queryActivityPage(
                    HistoryActivityQuery(sourceScope = scope, limit = 120),
                )
                dashboardActivities.items.any { it.outcome == HistoryCycleOutcome.PROBLEM } shouldBe true

                val recordedScopes = fixture.historyStore.listRecordedScopes(digest)
                recordedScopes.all { it.mode == HistorySourceMode.ACCOUNT } shouldBe true
                recordedScopes.size shouldBe 3
                val alternate = recordedScopes.single {
                    it.managedScopeFingerprint == fixture.alternateScope.managedScopeFingerprint
                }
                alternate.managedScopeSymbols.toSet() shouldBe setOf("BTC", "USD")
                alternate.observationCount shouldBe 6L
                alternate.firstObservedAt shouldBe fixture.fixtureNow.minusSeconds(24 * 60 * 60)
                alternate.lastObservedAt shouldBe fixture.fixtureNow
                val fullScope = recordedScopes.single {
                    it.managedScopeFingerprint == fixture.accountScope.managedScopeFingerprint
                }
                fullScope.managedScopeSymbols.toSet() shouldBe setOf("BTC", "ETH", "USD")

                val allTrades = fixture.historyStore.queryTrades(
                    HistoryEventQuery(accountIdentityDigest = digest, limit = HISTORY_MAX_PAGE_LIMIT),
                )
                allTrades.total shouldBe BROWSER_FIXTURE_EVENT_COUNT.toLong()
                allTrades.items.size shouldBe BROWSER_FIXTURE_EVENT_COUNT
                allTrades.items.map { it.tradeId }.distinct().size shouldBe BROWSER_FIXTURE_EVENT_COUNT
                allTrades.items.mapNotNull { it.time?.epochSecond?.div(86_400L) }.toSet().size shouldBe
                    BROWSER_FIXTURE_EVENT_DAY_COUNT

                val tradePages = (0..2).map { page ->
                    fixture.historyStore.queryTrades(
                        HistoryEventQuery(accountIdentityDigest = digest, limit = 50, offset = page * 50),
                    )
                }
                tradePages.map { it.items.size } shouldBe listOf(50, 50, 3)
                tradePages.all { it.total == BROWSER_FIXTURE_EVENT_COUNT.toLong() } shouldBe true
                tradePages.map { it.hasMore } shouldBe listOf(true, true, false)

                val allLedgers = fixture.historyStore.queryLedgers(
                    HistoryEventQuery(accountIdentityDigest = digest, limit = HISTORY_MAX_PAGE_LIMIT),
                )
                allLedgers.total shouldBe BROWSER_FIXTURE_LEDGER_EVENT_COUNT.toLong()
                allLedgers.items.size shouldBe BROWSER_FIXTURE_LEDGER_EVENT_COUNT
                allLedgers.items.map { it.ledgerId }.distinct().size shouldBe BROWSER_FIXTURE_LEDGER_EVENT_COUNT
                allLedgers.items.mapNotNull { it.time?.epochSecond?.div(86_400L) }.toSet().size shouldBe
                    BROWSER_FIXTURE_LEDGER_EVENT_DAY_COUNT

                val ledgerPages = (0..2).map { page ->
                    fixture.historyStore.queryLedgers(
                        HistoryEventQuery(accountIdentityDigest = digest, limit = 50, offset = page * 50),
                    )
                }
                ledgerPages.map { it.items.size } shouldBe listOf(50, 50, 6)
                ledgerPages.all { it.total == BROWSER_FIXTURE_LEDGER_EVENT_COUNT.toLong() } shouldBe true
                ledgerPages.map { it.hasMore } shouldBe listOf(true, true, false)

                val exactBoundaryTrade = fixture.historyStore.queryTrades(
                    HistoryEventQuery(
                        from = fixture.rangeBoundary,
                        until = fixture.rangeBoundary.plusNanos(1),
                        accountIdentityDigest = digest,
                    ),
                )
                exactBoundaryTrade.items.single().tradeId shouldBe "fixture-trade-1"
                val beforeBoundaryTrade = fixture.historyStore.queryTrades(
                    HistoryEventQuery(
                        from = fixture.rangeBoundary.minusNanos(1),
                        until = fixture.rangeBoundary,
                        accountIdentityDigest = digest,
                    ),
                )
                beforeBoundaryTrade.items.single().tradeId shouldBe "fixture-trade-0"

                val exactBoundaryValue = fixture.historyStore.queryObservations(
                    HistoryRangeQuery(
                        from = fixture.rangeBoundary,
                        until = fixture.rangeBoundary.plusNanos(1),
                        sourceScope = scope,
                    ),
                )
                exactBoundaryValue.total shouldBe 1L
                val beforeBoundaryValue = fixture.historyStore.queryObservations(
                    HistoryRangeQuery(
                        from = fixture.rangeBoundary.minusNanos(1),
                        until = fixture.rangeBoundary,
                        sourceScope = scope,
                    ),
                )
                beforeBoundaryValue.total shouldBe 1L

                val exactBoundaryLedger = fixture.historyStore.queryLedgers(
                    HistoryEventQuery(
                        from = fixture.rangeBoundary,
                        until = fixture.rangeBoundary.plusNanos(1),
                        accountIdentityDigest = digest,
                    ),
                )
                exactBoundaryLedger.items.single().ledgerId shouldBe "fixture-ledger-1"
                val beforeBoundaryLedger = fixture.historyStore.queryLedgers(
                    HistoryEventQuery(
                        from = fixture.rangeBoundary.minusNanos(1),
                        until = fixture.rangeBoundary,
                        accountIdentityDigest = digest,
                    ),
                )
                beforeBoundaryLedger.items.single().ledgerId shouldBe "fixture-ledger-0"

                val lateAt = fixture.fixtureNow.plusSeconds(1).truncatedTo(ChronoUnit.SECONDS)
                val lateTradeId = "fixture-post-start-trade-${lateAt.epochSecond}"
                val lateLedgerId = "fixture-post-start-ledger-${lateAt.epochSecond - 1}-first"
                val secondLateLedgerId = "fixture-post-start-ledger-${lateAt.epochSecond}-second"
                val startSec = lateAt.epochSecond - 1
                val endSec = lateAt.epochSecond + 1
                fixture.fakeKraken.getAccountTradePage(startSec, endSec, null, 50).entries.size shouldBe 0
                fixture.fakeKraken.getAccountLedgerPage(startSec, endSec, null).entries.size shouldBe 0
                fixture.publishLateExchangeEvents(lateAt)
                val lateTrades = fixture.fakeKraken.getAccountTradePage(startSec, endSec, null, 50)
                lateTrades.entries.single().tradeId shouldBe lateTradeId
                val lateLedgers = fixture.fakeKraken.getAccountLedgerPage(startSec, endSec, null)
                lateLedgers.entries.single().ledgerId shouldBe lateLedgerId

                fixture.historySyncCoordinator.runCoalescedPass()
                val ingestedTrades = fixture.historyStore.queryTrades(
                    HistoryEventQuery(accountIdentityDigest = digest, limit = HISTORY_MAX_PAGE_LIMIT),
                )
                val ingestedLedgers = fixture.historyStore.queryLedgers(
                    HistoryEventQuery(accountIdentityDigest = digest, limit = HISTORY_MAX_PAGE_LIMIT),
                )
                ingestedTrades.total shouldBe (BROWSER_FIXTURE_EVENT_COUNT + 1).toLong()
                ingestedTrades.items.any { it.tradeId == lateTradeId } shouldBe true
                ingestedLedgers.total shouldBe (BROWSER_FIXTURE_LEDGER_EVENT_COUNT + 2).toLong()
                ingestedLedgers.items.any { it.ledgerId == lateLedgerId } shouldBe true
                ingestedLedgers.items.any { it.ledgerId == secondLateLedgerId } shouldBe true

                (BROWSER_FIXTURE_PERIODIC_SYNC_INTERVAL < Duration.ofSeconds(90)) shouldBe true
                (BROWSER_FIXTURE_LATE_EVENT_DELAY < BROWSER_FIXTURE_PERIODIC_SYNC_INTERVAL) shouldBe true
            } finally {
                fixture.close()
            }
            Files.exists(tempDirectory) shouldBe false
        }
    }
}
