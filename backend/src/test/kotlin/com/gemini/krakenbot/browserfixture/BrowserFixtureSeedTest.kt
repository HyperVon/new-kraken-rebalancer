package com.gemini.krakenbot.browserfixture

import com.gemini.krakenbot.service.history.HISTORY_MAX_PAGE_LIMIT
import com.gemini.krakenbot.service.history.HistoryActivityQuery
import com.gemini.krakenbot.service.history.HistoryCycleOutcome
import com.gemini.krakenbot.service.history.HistoryEventQuery
import com.gemini.krakenbot.service.history.HistoryRangeQuery
import com.gemini.krakenbot.service.history.HistorySourceMode
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

class BrowserFixtureSeedTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "seeded account history covers 100 days, range edges, scopes, and event tables" {
            val fixture = BrowserFixtureEnvironment.create(CoroutineScope(SupervisorJob() + Dispatchers.Default))
            val tempDirectory = fixture.tempDirectory
            try {
                Files.isRegularFile(tempDirectory.resolve("history.db")) shouldBe true
                val scope = fixture.accountScope
                scope.mode shouldBe HistorySourceMode.ACCOUNT
                scope.accountIdentityDigest shouldBe fakeAccountIdentityDigest()

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

                val digest = checkNotNull(scope.accountIdentityDigest)
                val recordedScopes = fixture.historyStore.listRecordedScopes(digest)
                recordedScopes.all { it.mode == HistorySourceMode.ACCOUNT } shouldBe true
                recordedScopes.size shouldBe 2
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
                allLedgers.total shouldBe BROWSER_FIXTURE_EVENT_COUNT.toLong()
                allLedgers.items.size shouldBe BROWSER_FIXTURE_EVENT_COUNT
                allLedgers.items.map { it.ledgerId }.distinct().size shouldBe BROWSER_FIXTURE_EVENT_COUNT
                allLedgers.items.mapNotNull { it.time?.epochSecond?.div(86_400L) }.toSet().size shouldBe
                    BROWSER_FIXTURE_EVENT_DAY_COUNT

                val ledgerPages = (0..2).map { page ->
                    fixture.historyStore.queryLedgers(
                        HistoryEventQuery(accountIdentityDigest = digest, limit = 50, offset = page * 50),
                    )
                }
                ledgerPages.map { it.items.size } shouldBe listOf(50, 50, 3)
                ledgerPages.all { it.total == BROWSER_FIXTURE_EVENT_COUNT.toLong() } shouldBe true
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
                val lateLedgerId = "fixture-post-start-ledger-${lateAt.epochSecond}"
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
                ingestedLedgers.total shouldBe (BROWSER_FIXTURE_EVENT_COUNT + 1).toLong()
                ingestedLedgers.items.any { it.ledgerId == lateLedgerId } shouldBe true

                (BROWSER_FIXTURE_PERIODIC_SYNC_INTERVAL < Duration.ofSeconds(90)) shouldBe true
                (BROWSER_FIXTURE_LATE_EVENT_DELAY < BROWSER_FIXTURE_PERIODIC_SYNC_INTERVAL) shouldBe true
            } finally {
                fixture.close()
            }
            Files.exists(tempDirectory) shouldBe false
        }
    }
}
