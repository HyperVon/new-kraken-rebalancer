@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.DataDirectoryIdentity
import com.gemini.krakenbot.model.ExchangeHistoryPage
import com.gemini.krakenbot.model.ExchangeLedgerHistoryEntry
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.CurrentPortfolioState
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.OrderIntentService
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.history.HISTORY_ACCOUNT_DAILY_SCOPE
import com.gemini.krakenbot.service.history.HistoryApiPresenter
import com.gemini.krakenbot.service.history.HistoryCsvExporter
import com.gemini.krakenbot.service.history.HistoryDailyPriceResolver
import com.gemini.krakenbot.service.history.HistoryDailyValue
import com.gemini.krakenbot.service.history.HistoryDatabase
import com.gemini.krakenbot.service.history.HistoryEventQuery
import com.gemini.krakenbot.service.history.HistoryFeed
import com.gemini.krakenbot.service.history.HistoryObservationRecorder
import com.gemini.krakenbot.service.history.HistoryPageRequest
import com.gemini.krakenbot.service.history.HistoryServiceImpl
import com.gemini.krakenbot.service.history.HistorySourceMode
import com.gemini.krakenbot.service.history.HistorySourceScope
import com.gemini.krakenbot.service.history.HistoryStore
import com.gemini.krakenbot.service.history.HistorySyncCoordinator
import com.gemini.krakenbot.service.history.HistorySyncJobState
import com.gemini.krakenbot.service.history.HistorySyncPurpose
import com.gemini.krakenbot.service.history.HistorySyncRetryPolicy
import com.gemini.krakenbot.service.history.HistorySyncWindow
import com.gemini.krakenbot.service.history.HistorySynchronizer
import com.gemini.krakenbot.service.history.HistoryValuationCoordinator
import com.gemini.krakenbot.service.history.HistoryValuationJob
import com.gemini.krakenbot.service.history.HistoryValuationJobState
import com.gemini.krakenbot.service.history.HistoryValuationStage
import com.gemini.krakenbot.service.history.SqliteHistoryStore
import com.gemini.krakenbot.service.history.VerifiedHistoryAccount
import com.gemini.krakenbot.service.impl.KrakenPublicApiException
import com.gemini.krakenbot.service.reporting.HistoryReportingService
import com.gemini.krakenbot.view.DashboardView
import com.gemini.krakenbot.view.component.AllocationChartComponent
import com.gemini.krakenbot.view.component.DashboardFragmentComponent
import com.gemini.krakenbot.view.component.DashboardShellComponent
import com.gemini.krakenbot.view.component.HistoryPageComponent
import com.gemini.krakenbot.view.component.OverviewGridComponent
import com.gemini.krakenbot.view.component.PerformanceTableComponent
import com.gemini.krakenbot.view.component.RecentActivityComponent
import com.gemini.krakenbot.view.component.SettingsFormComponent
import com.gemini.krakenbot.view.util.Routes
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class HistoryRecoveryAcceptanceTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val accountDigest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val secondAccountDigest = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
    private val fingerprint = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
    private val secondFingerprint = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"

    init {
        "late ledger valuation resumes after restart and publishes atomically to History HTTP views" {
            kotlinx.coroutines.test.runTest {
                val now = Instant.parse("2026-10-21T12:00:00Z")
                val targetEnd = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
                val targetStart = targetEnd.minusDays(364)
                val lateDay = targetStart.plusDays(100)
                val startSec = targetStart.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
                val throughSec = targetEnd.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() - 1
                val lateTime = lateDay.atTime(12, 0).toInstant(ZoneOffset.UTC)
                val directory = Files.createTempDirectory("history-recovery-acceptance-")
                val databasePath = directory.resolve("history.db")
                var database: HistoryDatabase? = null
                try {
                    database = HistoryDatabase(databasePath)
                    database.initializeFreshSchema()
                    var store = SqliteHistoryStore(database)
                    val synchronizer = mockk<com.gemini.krakenbot.service.history.HistorySynchronizer>()
                    coEvery { synchronizer.verifiedAccount() } returns VerifiedHistoryAccount(accountDigest, null, null)
                    val kraken = mockk<KrakenService>()
                    val candles = (0 until 365).map { offset ->
                        (startSec + offset * 86_400L) to BigDecimal("100")
                    }
                    coEvery { kraken.getOHLC("XBTUSD", 1_440, startSec) } returns candles

                    seedOpeningLedgerHistory(store, now, startSec, throughSec)
                    coordinator(this, store, synchronizer, kraken, now).runOnce()
                    val originalValues = store.queryDailyValues(
                        accountDigest,
                        HISTORY_ACCOUNT_DAILY_SCOPE,
                        limit = 500,
                    ).items
                    originalValues.size shouldBe 365
                    originalValues.all { it.status.name == "COMPLETE" && it.totalUsd == BigDecimal("200") } shouldBe
                        true
                    val originalGeneration = originalValues.first().generationId
                    val originalRevision = store.currentLedgerRevision(accountDigest)

                    commitLateUsdDeposit(store, now, lateTime)
                    store.currentLedgerRevision(accountDigest) shouldBe originalRevision + 1

                    var persistedBalanceDays = 0
                    val stopDuringBalances = object : HistoryStore by store {
                        override suspend fun replaceDailyBalanceDay(
                            balances: List<com.gemini.krakenbot.service.history.HistoryDailyBalance>,
                        ) {
                            store.replaceDailyBalanceDay(balances)
                            persistedBalanceDays++
                            if (persistedBalanceDays == 100) {
                                throw CancellationException("simulated process stop during balance reconstruction")
                            }
                        }
                    }
                    shouldThrow<CancellationException> {
                        coordinator(this, stopDuringBalances, synchronizer, kraken, now).runOnce()
                    }
                    val interruptedJob = store.loadValuationJob(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE)
                    interruptedJob?.state shouldBe HistoryValuationJobState.RUNNING
                    interruptedJob?.stage shouldBe HistoryValuationStage.ESTABLISH_BALANCES
                    store.queryDailyValues(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE, limit = 500)
                        .items.map { it.generationId }.distinct() shouldBe listOf(originalGeneration)

                    database.close()
                    database = HistoryDatabase(databasePath)
                    store = SqliteHistoryStore(database)
                    val stopBeforePublish = object : HistoryStore by store {
                        override suspend fun publishDailyValues(
                            job: com.gemini.krakenbot.service.history.HistoryValuationJob,
                            values: List<com.gemini.krakenbot.service.history.HistoryDailyValue>,
                        ): Unit = throw CancellationException("simulated process stop before value publication")
                    }
                    shouldThrow<CancellationException> {
                        coordinator(this, stopBeforePublish, synchronizer, kraken, now).runOnce()
                    }
                    val prePublishJob = store.loadValuationJob(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE)
                    prePublishJob?.state shouldBe HistoryValuationJobState.RUNNING
                    prePublishJob?.stage shouldBe HistoryValuationStage.PRICE_AND_VALUE
                    store.queryDailyValues(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE, limit = 500)
                        .items.map { it.generationId }.distinct() shouldBe listOf(originalGeneration)

                    database.close()
                    database = HistoryDatabase(databasePath)
                    store = SqliteHistoryStore(database)
                    coordinator(this, store, synchronizer, kraken, now).runOnce()

                    val finalJob = store.loadValuationJob(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE)
                    finalJob?.state shouldBe HistoryValuationJobState.COMPLETE
                    finalJob?.sourceRevision shouldBe originalRevision + 1
                    val values = store.queryDailyValues(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE, limit = 500).items
                    values.size shouldBe 365
                    values.map { it.generationId }.distinct().size shouldBe 1
                    (values.first().generationId != originalGeneration) shouldBe true
                    values.single { it.dayLabel == lateDay.minusDays(1) }.totalUsd
                        ?.compareTo(BigDecimal("200")) shouldBe 0
                    values.single { it.dayLabel == lateDay }.totalUsd?.compareTo(BigDecimal("201")) shouldBe 0
                    values.last().totalUsd?.compareTo(BigDecimal("201")) shouldBe 0

                    val controller = historyController(store, synchronizer, now)
                    testApplication {
                        application {
                            install(SSE)
                            routing { controller.registerRoutes(this) }
                        }
                        val chart = client.get(
                            "${Routes.API_HISTORY_CHARTS}?preset=all&tz=UTC&selection=PORTFOLIO",
                        )
                        chart.status shouldBe HttpStatusCode.OK
                        val chartJson = mapper.readTree(chart.bodyAsText())
                        val latePoint = chartJson.path(
                            "series",
                        ).path(0).path("points").elements().asSequence().firstOrNull {
                            it.path("dayLabel").asText() == lateDay.toString()
                        }
                        latePoint?.path("v")?.decimalValue()?.compareTo(BigDecimal("201")) shouldBe 0

                        val holdings = client.get("${Routes.API_HISTORY_HOLDINGS}?date=$lateDay")
                        holdings.status shouldBe HttpStatusCode.OK
                        val holdingsJson = mapper.readTree(holdings.bodyAsText())
                        holdingsJson.path("totalUsd").asText() shouldBe "201"
                        holdingsJson.path("holdings").elements().asSequence().firstOrNull {
                            it.path("asset").asText() == "USD"
                        }?.path("quantity")?.asText() shouldBe "101"
                    }
                } finally {
                    database?.close()
                    listOf("", "-wal", "-shm").forEach { suffix ->
                        Files.deleteIfExists(Path.of(databasePath.toString() + suffix))
                    }
                    Files.deleteIfExists(directory)
                }
            }
        }

        "a new sync coordinator resumes a committed ledger page cursor after SQLite reopen" {
            kotlinx.coroutines.test.runTest {
                val now = Instant.parse("2026-10-21T12:00:00Z")
                val firstEntry = ledger("resumed-page-one", now.minusSeconds(60), "ZUSD", "1", "1")
                val secondEntry = ledger("resumed-page-two", now.minusSeconds(30), "ZUSD", "1", "2")
                val directory = Files.createTempDirectory("history-sync-reopen-")
                val databasePath = directory.resolve("history.db")
                var database = HistoryDatabase(databasePath)
                val firstScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
                val firstPageCommitted = CompletableDeferred<Unit>()
                try {
                    database.initializeFreshSchema()
                    var store = SqliteHistoryStore(database)
                    val firstKraken = FakeKrakenService()
                    firstKraken.fundingEvidenceScopeSupplier = { "acceptance-account" }
                    val firstCalls = mutableListOf<String?>()
                    firstKraken.ledgerPageSupplier = { start, end, cursor ->
                        firstCalls += cursor
                        ExchangeHistoryPage(
                            entries = listOf(firstEntry),
                            startSec = start,
                            endSec = end,
                            requestedCursor = cursor,
                            nextCursor = "C1",
                            count = 1,
                            rawSize = 1,
                            valid = true,
                        )
                    }
                    lateinit var firstCoordinator: HistorySyncCoordinator
                    val firstSynchronizer = HistorySynchronizer(
                        applicationScope = firstScope,
                        krakenService = firstKraken,
                        configService = mockk<ConfigService>(relaxed = true),
                        dataDirectoryIdentity = mockk<DataDirectoryIdentity>(relaxed = true),
                        store = store,
                        nowProvider = { now },
                        onLedgerPageCommitted = {
                            firstCoordinator.stop()
                            firstPageCommitted.complete(Unit)
                        },
                    )
                    val digest = checkNotNull(firstSynchronizer.accountIdentityDigest())
                    firstCoordinator = HistorySyncCoordinator(
                        applicationScope = firstScope,
                        synchronizer = firstSynchronizer,
                        store = store,
                        settingsProvider = { TestFixtures.settings(simulation = false, dryRun = true) },
                        nowProvider = { now },
                        delayFn = {},
                        retryPolicy = HistorySyncRetryPolicy(jitter = { it }),
                        periodicTicks = emptyFlow(),
                    )

                    firstCoordinator.start()
                    firstCoordinator.requestRecentRefresh()
                    firstPageCommitted.await()
                    firstCoordinator.stop()

                    val interrupted = store.loadSyncJobs(digest).single {
                        it.feed == HistoryFeed.LEDGERS && it.purpose == HistorySyncPurpose.RECENT
                    }
                    interrupted.state shouldBe HistorySyncJobState.RUNNING
                    interrupted.cursor shouldBe "C1"
                    interrupted.windowStartSec shouldNotBe null
                    interrupted.windowEndSec shouldNotBe null
                    firstCalls shouldBe listOf(null)
                    store.currentLedgerRevision(digest) shouldBe 1L
                    val firstRows = store.queryLedgers(HistoryEventQuery(accountIdentityDigest = digest, limit = 10))
                    firstRows.total shouldBe 1L
                    firstRows.items.single().ledgerId shouldBe firstEntry.ledgerId

                    firstScope.cancel()
                    database.close()
                    database = HistoryDatabase(databasePath)
                    store = SqliteHistoryStore(database)

                    val restartedKraken = FakeKrakenService()
                    restartedKraken.fundingEvidenceScopeSupplier = { "acceptance-account" }
                    val resumedCursors = mutableListOf<String?>()
                    restartedKraken.ledgerPageSupplier = { start, end, cursor ->
                        resumedCursors += cursor
                        if (cursor == "C1") {
                            ExchangeHistoryPage(
                                entries = listOf(firstEntry, secondEntry),
                                startSec = start,
                                endSec = end,
                                requestedCursor = cursor,
                                nextCursor = null,
                                count = 2,
                                rawSize = 2,
                                valid = true,
                            )
                        } else {
                            ExchangeHistoryPage(
                                entries = emptyList(),
                                startSec = start,
                                endSec = end,
                                requestedCursor = cursor,
                                nextCursor = null,
                                count = 0,
                                rawSize = 0,
                                valid = true,
                            )
                        }
                    }
                    val restartedSynchronizer = HistorySynchronizer(
                        applicationScope = this,
                        krakenService = restartedKraken,
                        configService = mockk<ConfigService>(relaxed = true),
                        dataDirectoryIdentity = mockk<DataDirectoryIdentity>(relaxed = true),
                        store = store,
                        nowProvider = { now },
                    )
                    restartedSynchronizer.accountIdentityDigest() shouldBe digest
                    val restartedCoordinator = HistorySyncCoordinator(
                        applicationScope = this,
                        synchronizer = restartedSynchronizer,
                        store = store,
                        settingsProvider = { TestFixtures.settings(simulation = false, dryRun = true) },
                        nowProvider = { now },
                        delayFn = {},
                        retryPolicy = HistorySyncRetryPolicy(jitter = { it }),
                        periodicTicks = emptyFlow(),
                    )

                    restartedCoordinator.runCoalescedPass()

                    resumedCursors.first() shouldBe "C1"
                    val completed = store.loadSyncJobs(digest).single {
                        it.feed == HistoryFeed.LEDGERS && it.purpose == HistorySyncPurpose.RECENT
                    }
                    completed.state shouldBe HistorySyncJobState.COMPLETE
                    completed.cursor shouldBe null
                    val rows = store.queryLedgers(HistoryEventQuery(accountIdentityDigest = digest, limit = 10))
                    rows.total shouldBe 2L
                    rows.items.map { it.ledgerId }.toSet() shouldBe setOf(firstEntry.ledgerId, secondEntry.ledgerId)
                    store.querySyncCoverageWindows(digest, HistoryFeed.LEDGERS).isNotEmpty() shouldBe true
                    val revisionAfterRecovery = store.currentLedgerRevision(digest)
                    revisionAfterRecovery shouldBe 3L

                    restartedCoordinator.runCoalescedPass()

                    store.queryLedgers(HistoryEventQuery(accountIdentityDigest = digest, limit = 10)).total shouldBe 2L
                    store.currentLedgerRevision(digest) shouldBe revisionAfterRecovery
                } finally {
                    firstScope.cancel()
                    database.close()
                    listOf("", "-wal", "-shm").forEach { suffix ->
                        Files.deleteIfExists(Path.of(databasePath.toString() + suffix))
                    }
                    Files.deleteIfExists(directory)
                }
            }
        }

        "terminal price failure survives SQLite reopen until an explicit operator retry" {
            kotlinx.coroutines.test.runTest {
                val now = Instant.parse("2026-10-21T12:00:00Z")
                val targetEnd = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
                val targetStart = targetEnd.minusDays(364)
                val lateDay = targetStart.plusDays(100)
                val startSec = targetStart.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
                val lateStartSec = lateDay.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
                val throughSec = targetEnd.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() - 1
                val lateDays = java.time.temporal.ChronoUnit.DAYS.between(lateDay, targetEnd).toInt() + 1
                val directory = Files.createTempDirectory("history-terminal-retry-")
                val databasePath = directory.resolve("history.db")
                var database = HistoryDatabase(databasePath)
                try {
                    database.initializeFreshSchema()
                    var store = SqliteHistoryStore(database)
                    val synchronizer = verifiedSynchronizer(accountDigest)
                    val kraken = mockk<KrakenService>()
                    val btcCandles = (0 until 365).map { offset ->
                        (startSec + offset * 86_400L) to BigDecimal("100")
                    }
                    val xmrCandles = (0 until lateDays).map { offset ->
                        (lateStartSec + offset * 86_400L) to BigDecimal("10")
                    }
                    var xmrAvailable = false
                    var xmrCalls = 0
                    coEvery { kraken.getOHLC("XBTUSD", 1_440, startSec) } returns btcCandles
                    coEvery { kraken.getOHLC("XMRUSD", 1_440, lateStartSec) } coAnswers {
                        xmrCalls++
                        if (!xmrAvailable) throw IllegalStateException("temporary historical price outage")
                        xmrCandles
                    }
                    seedOpeningLedgerHistory(store, now, startSec, throughSec)
                    coordinator(this, store, synchronizer, kraken, now).runOnce()
                    val previousValues = store.queryDailyValueSnapshot(
                        accountDigest,
                        HISTORY_ACCOUNT_DAILY_SCOPE,
                    ).values
                    previousValues.size shouldBe 365
                    previousValues.all {
                        it.status.name == "COMPLETE" && it.totalUsd?.compareTo(BigDecimal("200")) == 0
                    } shouldBe true
                    val previousGeneration = previousValues.first().generationId
                    val oldRevision = store.currentLedgerRevision(accountDigest)

                    commitLateLedgerEntry(
                        store = store,
                        now = now,
                        time = lateDay.atTime(12, 0).toInstant(ZoneOffset.UTC),
                        ledgerId = "late-xmr-for-terminal-retry",
                        assetKey = "XXMR",
                        amount = "1",
                        balance = "1",
                    )
                    val changedRevision = store.currentLedgerRevision(accountDigest)
                    changedRevision shouldBe oldRevision + 1
                    val retryDelays = listOf(Duration.ofMinutes(1))
                    HistoryValuationCoordinator(
                        applicationScope = this,
                        synchronizer = synchronizer,
                        store = store,
                        priceResolver = HistoryDailyPriceResolver(kraken, nowProvider = { now }),
                        nowProvider = { now },
                        pollInterval = Duration.ofDays(1),
                        retryDelays = retryDelays,
                    ).runOnce()

                    val terminal = store.loadValuationJob(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE)
                    terminal?.state shouldBe HistoryValuationJobState.NEEDS_ATTENTION
                    terminal?.retryAttempt shouldBe 1
                    terminal?.nextRetryAt shouldBe null
                    terminal?.lastError shouldBe "Historical daily prices could not be fetched."
                    terminal?.sourceRevision shouldBe changedRevision
                    xmrCalls shouldBe 1
                    store.queryDailyValueSnapshot(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE).values.map {
                        it.generationId
                    }.distinct() shouldBe listOf(previousGeneration)

                    database.close()
                    database = HistoryDatabase(databasePath)
                    store = SqliteHistoryStore(database)
                    val restoredJob = store.loadValuationJob(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE)
                    restoredJob?.state shouldBe HistoryValuationJobState.NEEDS_ATTENTION
                    restoredJob?.retryAttempt shouldBe 1
                    restoredJob?.lastError shouldBe "Historical daily prices could not be fetched."
                    val restoredValues = store.queryDailyValueSnapshot(
                        accountDigest,
                        HISTORY_ACCOUNT_DAILY_SCOPE,
                    ).values
                    restoredValues.map { it.generationId }.distinct() shouldBe listOf(previousGeneration)
                    restoredValues.all { it.status.name == "COMPLETE" } shouldBe true

                    val pendingPublication = CompletableDeferred<Boolean>()
                    val retryStore = object : HistoryStore by store {
                        override suspend fun publishDailyValues(
                            job: HistoryValuationJob,
                            values: List<HistoryDailyValue>,
                        ) {
                            val current = store.queryDailyValueSnapshot(
                                accountDigest,
                                HISTORY_ACCOUNT_DAILY_SCOPE,
                            ).values
                            val previousGenerationStillVisible = current.size == 365 &&
                                current.all { it.generationId == previousGeneration && it.status.name == "COMPLETE" }
                            store.publishDailyValues(job, values)
                            pendingPublication.complete(previousGenerationStillVisible)
                        }
                    }
                    val restartedCoordinator = HistoryValuationCoordinator(
                        applicationScope = this,
                        synchronizer = synchronizer,
                        store = retryStore,
                        priceResolver = HistoryDailyPriceResolver(kraken, nowProvider = { now }),
                        nowProvider = { now },
                        pollInterval = Duration.ofDays(1),
                        retryDelays = retryDelays,
                    )

                    restartedCoordinator.runOnce()
                    store.loadValuationJob(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE)?.state shouldBe
                        HistoryValuationJobState.NEEDS_ATTENTION
                    xmrCalls shouldBe 1
                    store.queryDailyValueSnapshot(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE).values.map {
                        it.generationId
                    }.distinct() shouldBe listOf(previousGeneration)

                    xmrAvailable = true
                    restartedCoordinator.start()
                    restartedCoordinator.retryFailedWork()
                    runCurrent()
                    pendingPublication.await() shouldBe true
                    restartedCoordinator.stop()

                    val recovered = store.loadValuationJob(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE)
                    recovered?.state shouldBe HistoryValuationJobState.COMPLETE
                    recovered?.retryAttempt shouldBe 0
                    recovered?.sourceRevision shouldBe changedRevision
                    val values = store.queryDailyValueSnapshot(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE).values
                    values.size shouldBe 365
                    values.map { it.generationId }.distinct().size shouldBe 1
                    (values.first().generationId != previousGeneration) shouldBe true
                    values.single { it.dayLabel == lateDay.minusDays(1) }.totalUsd
                        ?.compareTo(BigDecimal("200")) shouldBe 0
                    values.single { it.dayLabel == lateDay }.totalUsd?.compareTo(BigDecimal("210")) shouldBe 0
                    xmrCalls shouldBe 2
                    coVerify(exactly = 1) { kraken.getOHLC("XBTUSD", 1_440, startSec) }
                } finally {
                    database.close()
                    listOf("", "-wal", "-shm").forEach { suffix ->
                        Files.deleteIfExists(Path.of(databasePath.toString() + suffix))
                    }
                    Files.deleteIfExists(directory)
                }
            }
        }

        "concurrent account valuations keep prices, jobs, and HTTP results isolated" {
            kotlinx.coroutines.test.runTest {
                val now = Instant.parse("2026-10-21T12:00:00Z")
                val targetEnd = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
                val targetStart = targetEnd.minusDays(364)
                val startSec = targetStart.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
                val throughSec = targetEnd.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() - 1
                val directory = Files.createTempDirectory("history-account-isolation-")
                val databasePath = directory.resolve("history.db")
                var database: HistoryDatabase? = null
                try {
                    database = HistoryDatabase(databasePath)
                    database.initializeFreshSchema()
                    val store = SqliteHistoryStore(database)
                    val synchronizerA = verifiedSynchronizer(accountDigest)
                    val synchronizerB = verifiedSynchronizer(secondAccountDigest)
                    val kraken = mockk<KrakenService>()
                    val candles = (0 until 365).map { offset ->
                        (startSec + offset * 86_400L) to BigDecimal("100")
                    }
                    coEvery { kraken.getOHLC("XBTUSD", 1_440, startSec) } returns candles
                    coEvery { kraken.getOHLC("ETHUSD", 1_440, startSec) } returns candles.map {
                        it.first to BigDecimal("200")
                    }

                    seedOpeningLedgerHistory(
                        store,
                        now,
                        startSec,
                        throughSec,
                        accountIdentityDigest = accountDigest,
                        openingUsd = "100",
                        openingAsset = "XXBT",
                        openingAssetQuantity = "1",
                    )
                    seedOpeningLedgerHistory(
                        store,
                        now,
                        startSec,
                        throughSec,
                        accountIdentityDigest = secondAccountDigest,
                        openingUsd = "250",
                        openingAsset = "XETH",
                        openingAssetQuantity = "2",
                    )

                    listOf(
                        async { coordinator(this, store, synchronizerA, kraken, now).runOnce() },
                        async { coordinator(this, store, synchronizerB, kraken, now).runOnce() },
                    ).awaitAll()

                    val valuesA = store.queryDailyValueSnapshot(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE).values
                    val valuesB = store.queryDailyValueSnapshot(secondAccountDigest, HISTORY_ACCOUNT_DAILY_SCOPE).values
                    valuesA.size shouldBe 365
                    valuesB.size shouldBe 365
                    valuesA.all { it.totalUsd?.compareTo(BigDecimal("200")) == 0 } shouldBe true
                    valuesB.all { it.totalUsd?.compareTo(BigDecimal("650")) == 0 } shouldBe true
                    valuesA.all { it.accountIdentityDigest == accountDigest } shouldBe true
                    valuesB.all { it.accountIdentityDigest == secondAccountDigest } shouldBe true
                    val sourceA = store.queryDailyCheckpointInputPage(accountDigest, throughSec, 20, 0)
                    val sourceB = store.queryDailyCheckpointInputPage(secondAccountDigest, throughSec, 20, 0)
                    sourceA.items.map { it.rawAssetKey }.toSet() shouldBe setOf("ZUSD", "XXBT")
                    sourceB.items.map { it.rawAssetKey }.toSet() shouldBe setOf("ZUSD", "XETH")
                    store.queryDailyPrices(accountDigest, "BTC", startSec, throughSec).isNotEmpty() shouldBe true
                    store.queryDailyPrices(secondAccountDigest, "ETH", startSec, throughSec).isNotEmpty() shouldBe true
                    store.queryDailyPrices(accountDigest, "ETH", startSec, throughSec) shouldBe emptyList()
                    store.queryDailyPrices(secondAccountDigest, "BTC", startSec, throughSec) shouldBe emptyList()
                    store.loadValuationJob(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE)?.state shouldBe
                        HistoryValuationJobState.COMPLETE
                    store.loadValuationJob(secondAccountDigest, HISTORY_ACCOUNT_DAILY_SCOPE)?.state shouldBe
                        HistoryValuationJobState.COMPLETE

                    val managerA = mockk<PortfolioManager>(relaxed = true)
                    val managerB = mockk<PortfolioManager>(relaxed = true)
                    val chartA = accountChart(
                        historyController(store, synchronizerA, now, accountDigest, managerA),
                    )
                    val chartB = accountChart(
                        historyController(store, synchronizerB, now, secondAccountDigest, managerB),
                    )
                    chartA.first shouldBe 365
                    chartA.second?.compareTo(BigDecimal("200")) shouldBe 0
                    chartB.first shouldBe 365
                    chartB.second?.compareTo(BigDecimal("650")) shouldBe 0
                    io.mockk.coVerify(exactly = 0) { managerA.runLoop() }
                    io.mockk.coVerify(exactly = 0) { managerB.runLoop() }
                    io.mockk.verify(exactly = 0) { managerA.startRebalancingLoop() }
                    io.mockk.verify(exactly = 0) { managerB.startRebalancingLoop() }

                    val accountBGeneration = valuesB.first().generationId
                    val accountBRevision = store.currentLedgerRevision(secondAccountDigest)
                    commitLateUsdDeposit(
                        store,
                        now,
                        targetStart.plusDays(100).atTime(12, 0).toInstant(ZoneOffset.UTC),
                        accountIdentityDigest = accountDigest,
                    )
                    var accountABalanceDays = 0
                    val cancelledAccountA = object : HistoryStore by store {
                        override suspend fun replaceDailyBalanceDay(
                            balances: List<com.gemini.krakenbot.service.history.HistoryDailyBalance>,
                        ) {
                            store.replaceDailyBalanceDay(balances)
                            accountABalanceDays++
                            if (accountABalanceDays == 10) {
                                throw CancellationException("simulated account A valuation cancellation")
                            }
                        }
                    }
                    shouldThrow<CancellationException> {
                        coordinator(this, cancelledAccountA, synchronizerA, kraken, now).runOnce()
                    }
                    store.queryDailyValueSnapshot(secondAccountDigest, HISTORY_ACCOUNT_DAILY_SCOPE)
                        .values.map { it.generationId }.distinct() shouldBe listOf(accountBGeneration)
                    store.currentLedgerRevision(secondAccountDigest) shouldBe accountBRevision
                    store.loadValuationJob(secondAccountDigest, HISTORY_ACCOUNT_DAILY_SCOPE)?.state shouldBe
                        HistoryValuationJobState.COMPLETE
                } finally {
                    database?.close()
                    listOf("", "-wal", "-shm").forEach { suffix ->
                        Files.deleteIfExists(Path.of(databasePath.toString() + suffix))
                    }
                    Files.deleteIfExists(directory)
                }
            }
        }

        "rate-limited price work resumes after restart without replacing the last complete generation" {
            kotlinx.coroutines.test.runTest {
                var now = Instant.parse("2026-10-21T12:00:00Z")
                val targetEnd = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
                val targetStart = targetEnd.minusDays(364)
                val lateDay = targetStart.plusDays(100)
                val startSec = targetStart.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
                val lateStartSec = lateDay.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
                val throughSec = targetEnd.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() - 1
                val lateDays = java.time.temporal.ChronoUnit.DAYS.between(lateDay, targetEnd).toInt() + 1
                val directory = Files.createTempDirectory("history-price-recovery-")
                val databasePath = directory.resolve("history.db")
                var database: HistoryDatabase? = null
                try {
                    database = HistoryDatabase(databasePath)
                    database.initializeFreshSchema()
                    var store = SqliteHistoryStore(database)
                    val synchronizer = verifiedSynchronizer(accountDigest)
                    val kraken = mockk<KrakenService>()
                    val btcCandles = (0 until 365).map { offset ->
                        (startSec + offset * 86_400L) to BigDecimal("100")
                    }
                    val xmrCandles = (0 until lateDays).map { offset ->
                        (lateStartSec + offset * 86_400L) to BigDecimal("100")
                    }
                    var rateLimited = true
                    var xmrCalls = 0
                    coEvery { kraken.getOHLC("XBTUSD", 1_440, startSec) } returns btcCandles
                    coEvery { kraken.getOHLC("XMRUSD", 1_440, lateStartSec) } coAnswers {
                        xmrCalls++
                        if (rateLimited) {
                            throw KrakenPublicApiException(
                                endpoint = "/0/public/OHLC",
                                errors = listOf("EAPI:Rate limit exceeded"),
                                message = "Kraken rate limit",
                            )
                        }
                        xmrCandles
                    }
                    seedOpeningLedgerHistory(store, now, startSec, throughSec)
                    coordinator(this, store, synchronizer, kraken, now).runOnce()
                    val oldValues = store.queryDailyValueSnapshot(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE).values
                    oldValues.size shouldBe 365
                    val oldGeneration = oldValues.first().generationId

                    commitLateLedgerEntry(
                        store,
                        now,
                        lateDay.atTime(12, 0).toInstant(ZoneOffset.UTC),
                        ledgerId = "late-xmr-deposit",
                        assetKey = "XXMR",
                        amount = "1",
                        balance = "1",
                    )
                    coordinator(this, store, synchronizer, kraken, now).runOnce()

                    val waiting = store.loadValuationJob(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE)
                    waiting?.state shouldBe HistoryValuationJobState.WAITING
                    waiting?.stage shouldBe HistoryValuationStage.PRICE_AND_VALUE
                    waiting?.retryAttempt shouldBe 1
                    waiting?.nextRetryAt shouldBe now.plus(Duration.ofMinutes(1))
                    waiting?.completedPriceTasks shouldBe 730
                    waiting?.requiredPriceTasks shouldBe 730 + lateDays
                    store.queryDailyValueSnapshot(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE)
                        .values.map { it.generationId }.distinct() shouldBe listOf(oldGeneration)

                    database.close()
                    database = HistoryDatabase(databasePath)
                    store = SqliteHistoryStore(database)
                    store.loadValuationJob(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE)?.nextRetryAt shouldBe
                        now.plus(Duration.ofMinutes(1))
                    coordinator(this, store, synchronizer, kraken, now).runOnce()
                    xmrCalls shouldBe 1
                    store.queryDailyValueSnapshot(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE)
                        .values.map { it.generationId }.distinct() shouldBe listOf(oldGeneration)

                    rateLimited = false
                    now = now.plus(Duration.ofMinutes(2))
                    coordinator(this, store, synchronizer, kraken, now).runOnce()

                    val completed = store.loadValuationJob(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE)
                    completed?.state shouldBe HistoryValuationJobState.COMPLETE
                    completed?.retryAttempt shouldBe 0
                    completed?.sourceRevision shouldBe 2L
                    val values = store.queryDailyValueSnapshot(accountDigest, HISTORY_ACCOUNT_DAILY_SCOPE).values
                    values.size shouldBe 365
                    values.map { it.generationId }.distinct().size shouldBe 1
                    (values.first().generationId != oldGeneration) shouldBe true
                    values.single { it.dayLabel == lateDay.minusDays(1) }.totalUsd
                        ?.compareTo(BigDecimal("200")) shouldBe 0
                    values.single { it.dayLabel == lateDay }.totalUsd?.compareTo(BigDecimal("300")) shouldBe 0
                    values.last().totalUsd?.compareTo(BigDecimal("300")) shouldBe 0
                    xmrCalls shouldBe 2
                    io.mockk.coVerify(exactly = 1) { kraken.getOHLC("XBTUSD", 1_440, startSec) }
                } finally {
                    database?.close()
                    listOf("", "-wal", "-shm").forEach { suffix ->
                        Files.deleteIfExists(Path.of(databasePath.toString() + suffix))
                    }
                    Files.deleteIfExists(directory)
                }
            }
        }
    }

    private fun coordinator(
        scope: CoroutineScope,
        store: HistoryStore,
        synchronizer: com.gemini.krakenbot.service.history.HistorySynchronizer,
        kraken: KrakenService,
        now: Instant,
    ) = HistoryValuationCoordinator(
        applicationScope = scope,
        synchronizer = synchronizer,
        store = store,
        priceResolver = HistoryDailyPriceResolver(kraken, nowProvider = { now }),
        nowProvider = { now },
        pollInterval = Duration.ofDays(1),
    )

    private suspend fun seedOpeningLedgerHistory(
        store: HistoryStore,
        now: Instant,
        startSec: Long,
        throughSec: Long,
        accountIdentityDigest: String = accountDigest,
        openingUsd: String = "100",
        openingAsset: String = "XXBT",
        openingAssetQuantity: String = "1",
    ) {
        store.beginSyncWindow(
            HistorySyncWindow(
                accountIdentityDigest = accountIdentityDigest,
                feed = HistoryFeed.LEDGERS,
                scanId = "opening-ledger-import-$accountIdentityDigest",
                startSec = 0,
                endSec = throughSec,
                purpose = HistorySyncPurpose.BACKFILL,
            ),
        )
        val openingTime = Instant.ofEpochSecond(startSec)
        val entries = listOf(
            ledger("opening-usd", openingTime, "ZUSD", openingUsd, openingUsd),
            ledger("opening-asset", openingTime, openingAsset, openingAssetQuantity, openingAssetQuantity),
        )
        store.commitLedgerPage(
            HistoryPageRequest(
                accountIdentityDigest = accountIdentityDigest,
                feed = HistoryFeed.LEDGERS,
                scanId = "opening-ledger-import-$accountIdentityDigest",
                startSec = 0,
                endSec = throughSec,
                requestedCursor = null,
                committedAt = now,
                purpose = HistorySyncPurpose.BACKFILL,
            ),
            ExchangeHistoryPage(
                entries = entries,
                startSec = 0,
                endSec = throughSec,
                requestedCursor = null,
                nextCursor = null,
                count = entries.size.toLong(),
                rawSize = entries.size,
                valid = true,
            ),
        )
    }

    private suspend fun commitLateUsdDeposit(
        store: HistoryStore,
        now: Instant,
        time: Instant,
        accountIdentityDigest: String = accountDigest,
    ) = commitLateLedgerEntry(
        store = store,
        now = now,
        time = time,
        ledgerId = "late-usd-deposit",
        assetKey = "ZUSD",
        amount = "1",
        balance = "101",
        accountIdentityDigest = accountIdentityDigest,
    )

    private suspend fun commitLateLedgerEntry(
        store: HistoryStore,
        now: Instant,
        time: Instant,
        ledgerId: String,
        assetKey: String,
        amount: String,
        balance: String,
        accountIdentityDigest: String = accountDigest,
    ) {
        val second = time.epochSecond
        val scanId = "late-$ledgerId"
        store.beginSyncWindow(
            HistorySyncWindow(
                accountIdentityDigest = accountIdentityDigest,
                feed = HistoryFeed.LEDGERS,
                scanId = scanId,
                startSec = second,
                endSec = second,
                purpose = HistorySyncPurpose.REPAIR,
            ),
        )
        store.commitLedgerPage(
            HistoryPageRequest(
                accountIdentityDigest = accountIdentityDigest,
                feed = HistoryFeed.LEDGERS,
                scanId = scanId,
                startSec = second,
                endSec = second,
                requestedCursor = null,
                committedAt = now,
                purpose = HistorySyncPurpose.REPAIR,
            ),
            ExchangeHistoryPage(
                entries = listOf(ledger(ledgerId, time, assetKey, amount, balance)),
                startSec = second,
                endSec = second,
                requestedCursor = null,
                nextCursor = null,
                count = 1,
                rawSize = 1,
                valid = true,
            ),
        )
    }

    private fun ledger(id: String, time: Instant, asset: String, amount: String, balance: String) =
        ExchangeLedgerHistoryEntry(
            ledgerId = id,
            refid = null,
            time = time,
            rawTime = time.toString(),
            type = "deposit",
            subtype = null,
            assetClass = "currency",
            asset = asset,
            amount = amount,
            fee = "0",
            balance = balance,
            rawRecordJson = "{}",
            hasValidTime = true,
            hasValidAmount = true,
            hasValidFee = true,
            hasValidBalance = true,
            isValidRecord = true,
        )

    private fun historyController(
        store: SqliteHistoryStore,
        synchronizer: com.gemini.krakenbot.service.history.HistorySynchronizer,
        now: Instant,
        accountIdentityDigest: String = accountDigest,
        portfolioManager: PortfolioManager = mockk(relaxed = true),
    ): DashboardController {
        val scope = HistorySourceScope(
            mode = HistorySourceMode.ACCOUNT,
            managedScopeFingerprint = if (accountIdentityDigest ==
                secondAccountDigest
            ) {
                secondFingerprint
            } else {
                fingerprint
            },
            managedScopeSymbols = if (accountIdentityDigest == secondAccountDigest) {
                listOf("ETH", "USD")
            } else {
                listOf("BTC", "USD")
            },
            accountIdentityDigest = accountIdentityDigest,
        )
        val config = mockk<ConfigService>()
        every { config.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG
        val recorder = mockk<HistoryObservationRecorder>()
        coEvery { recorder.currentSourceScope(any()) } returns scope
        val service = HistoryServiceImpl(store)
        val reporting = HistoryReportingService(service) { now }
        val syncCoordinator = mockk<HistorySyncCoordinator>(relaxed = true)
        return DashboardController(
            configService = config,
            objectMapper = mapper,
            dashboardView = DashboardView(
                shellComponent = DashboardShellComponent(),
                settingsFormComponent = SettingsFormComponent(),
                fragmentComponent = DashboardFragmentComponent(
                    overviewGridComponent = OverviewGridComponent(),
                    allocationChartComponent = AllocationChartComponent(),
                    performanceTableComponent = PerformanceTableComponent(),
                    recentActivityComponent = RecentActivityComponent(),
                ),
                historyPageComponent = HistoryPageComponent(),
            ),
            portfolioManager = portfolioManager,
            currentPortfolioState = CurrentPortfolioState(),
            orderIntentService = mockk<OrderIntentService>(relaxed = true),
            historyService = service,
            historyObservationRecorder = recorder,
            historyApi = HistoryApiPresenter(service, store, reporting, syncCoordinator) { now },
            historyReportingService = reporting,
            historyCsvExporter = HistoryCsvExporter(service, store),
        )
    }

    private fun verifiedSynchronizer(accountIdentityDigest: String) =
        mockk<com.gemini.krakenbot.service.history.HistorySynchronizer>().also { synchronizer ->
            coEvery { synchronizer.verifiedAccount() } returns VerifiedHistoryAccount(accountIdentityDigest, null, null)
        }

    private suspend fun accountChart(controller: DashboardController): Pair<Int, BigDecimal?> {
        var pointCount = 0
        var lastValue: BigDecimal? = null
        testApplication {
            application {
                install(SSE)
                routing { controller.registerRoutes(this) }
            }
            val response = client.get("${Routes.API_HISTORY_CHARTS}?preset=all&tz=UTC&selection=PORTFOLIO")
            response.status shouldBe HttpStatusCode.OK
            val points = mapper.readTree(
                response.bodyAsText(),
            ).path("series").path(0).path("points").elements().asSequence()
            val values = points.toList()
            pointCount = values.size
            lastValue = values.lastOrNull()?.path("v")?.decimalValue()
        }
        return pointCount to lastValue
    }

    private companion object {
        val mapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    }
}
