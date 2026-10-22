package com.gemini.krakenbot.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.CurrentPortfolioState
import com.gemini.krakenbot.service.OrderIntentService
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.history.HistoryApiPresenter
import com.gemini.krakenbot.service.history.HistoryCsvExporter
import com.gemini.krakenbot.service.history.HistoryDatabase
import com.gemini.krakenbot.service.history.HistoryFeed
import com.gemini.krakenbot.service.history.HistoryObservationRecorder
import com.gemini.krakenbot.service.history.HistoryServiceImpl
import com.gemini.krakenbot.service.history.HistorySourceMode
import com.gemini.krakenbot.service.history.HistorySourceScope
import com.gemini.krakenbot.service.history.HistorySyncCoordinator
import com.gemini.krakenbot.service.history.HistorySyncJob
import com.gemini.krakenbot.service.history.HistorySyncJobState
import com.gemini.krakenbot.service.history.HistorySyncPurpose
import com.gemini.krakenbot.service.history.SqliteHistoryStore
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
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import io.ktor.server.sse.SSE as ServerSSE

/** Optional 20-request-per-endpoint p95 run against a disposable, populated History database. */
class HistoryApiPerformanceAcceptanceTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        val rawPath = System.getenv(DATABASE_ENV)?.takeIf(String::isNotBlank)
        if (rawPath != null) {
            "measures History HTTP latency during a concurrent checkpoint scan and job updates" {
                val databasePath = Path.of(rawPath).toAbsolutePath()
                require(databasePath.startsWith(Path.of("/tmp"))) {
                    "The performance runner only accepts a disposable database under /tmp."
                }
                val accountDigest = readAccountDigest(databasePath)
                val database = HistoryDatabase(databasePath)
                val store = SqliteHistoryStore(database)
                val startedScans = AtomicLong()
                val worker = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                    var sequence = 0L
                    val throughSec = LocalDate.parse("2026-10-21")
                        .atStartOfDay(ZoneOffset.UTC)
                        .toEpochSecond()
                    while (isActive) {
                        var offset = 0
                        while (isActive) {
                            val page = store.queryDailyCheckpointInputPage(
                                accountIdentityDigest = accountDigest,
                                throughSec = throughSec,
                                limit = 2_000,
                                offset = offset,
                            )
                            startedScans.incrementAndGet()
                            offset += page.items.size
                            if (!page.hasMore || page.items.isEmpty()) break
                            delay(2)
                        }
                        sequence++
                        store.upsertSyncJob(
                            HistorySyncJob(
                                accountIdentityDigest = accountDigest,
                                feed = HistoryFeed.LEDGERS,
                                purpose = HistorySyncPurpose.REPAIR,
                                state = HistorySyncJobState.RUNNING,
                                windowStartSec = 0,
                                windowEndSec = throughSec,
                                cursor = "perf-$sequence",
                                completedThroughSec = null,
                                lastError = null,
                                nextRetryAt = null,
                                lastSuccessAt = null,
                                updatedAt = Instant.now(),
                            ),
                        )
                        delay(2)
                    }
                }
                try {
                    withTimeout(10_000) {
                        while (startedScans.get() == 0L) delay(1)
                    }
                    runHistoryRequests(store, accountDigest)
                    check(startedScans.get() >= 20) {
                        "The background scan did not remain active during the request run."
                    }
                } finally {
                    worker.cancelAndJoin()
                    database.close()
                }
            }
        }
    }

    private suspend fun runHistoryRequests(store: SqliteHistoryStore, accountDigest: String) {
        val fingerprint = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
        val scope = HistorySourceScope(
            mode = HistorySourceMode.ACCOUNT,
            managedScopeFingerprint = fingerprint,
            managedScopeSymbols = listOf("BTC", "ETH", "USD"),
            accountIdentityDigest = accountDigest,
        )
        val configService = mockk<ConfigService>()
        every { configService.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG
        val recorder = mockk<HistoryObservationRecorder>()
        coEvery { recorder.currentSourceScope(any()) } returns scope
        val historyService = HistoryServiceImpl(store)
        val reporting = HistoryReportingService(historyService) { Instant.parse("2026-10-21T12:00:00Z") }
        val syncCoordinator = mockk<HistorySyncCoordinator>(relaxed = true)
        val historyApi = HistoryApiPresenter(
            historyService = historyService,
            historyStore = store,
            reportingService = reporting,
            syncCoordinator = syncCoordinator,
            nowProvider = { Instant.parse("2026-10-21T12:00:00Z") },
        )
        val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
        val controller = DashboardController(
            configService = configService,
            objectMapper = objectMapper,
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
            portfolioManager = mockk<PortfolioManager>(relaxed = true),
            currentPortfolioState = CurrentPortfolioState(),
            orderIntentService = mockk<OrderIntentService>(relaxed = true),
            historyService = historyService,
            historyObservationRecorder = recorder,
            historyApi = historyApi,
            historyReportingService = reporting,
            historyCsvExporter = HistoryCsvExporter(historyService, store),
        )

        val endpoints = listOf(
            "charts" to "${Routes.API_HISTORY_CHARTS}?preset=1y",
            "holdings" to "${Routes.API_HISTORY_HOLDINGS}?date=2026-10-20",
            "summary" to "${Routes.API_HISTORY_SUMMARY}?preset=1y",
            "data-status" to "${Routes.API_HISTORY_DATA_STATUS}?preset=1y",
            "trades-page" to "${Routes.API_HISTORY_TRADES}?limit=100&offset=0",
            "ledgers-page" to "${Routes.API_HISTORY_LEDGERS}?limit=100&offset=0",
            "account-value-export" to "${Routes.API_HISTORY_EXPORT_ACCOUNT_VALUES}?preset=1y",
        )
        val measurements = ConcurrentHashMap<String, MutableList<Double>>()

        testApplication {
            application {
                install(ServerSSE)
                routing { controller.registerRoutes(this) }
            }
            endpoints.forEach { (_, path) -> client.request(path) }
            repeat(20) {
                endpoints.forEach { (name, path) ->
                    val started = System.nanoTime()
                    client.request(path)
                    val elapsedMillis = (System.nanoTime() - started) / 1_000_000.0
                    measurements.computeIfAbsent(name) { mutableListOf() }.add(elapsedMillis)
                }
            }
        }

        endpoints.forEach { (name, _) ->
            val sorted = measurements.getValue(name).sorted()
            val p95 = sorted[ceil(sorted.size * 0.95).toInt() - 1]
            val median = (sorted[9] + sorted[10]) / 2.0
            println(
                "PERF $name n=${sorted.size} minMs=${"%.2f".format(sorted.first())} " +
                    "medianMs=${"%.2f".format(median)} p95Ms=${"%.2f".format(p95)} " +
                    "maxMs=${"%.2f".format(sorted.last())}",
            )
        }
    }

    private suspend fun io.ktor.client.HttpClient.request(path: String) {
        val response = get(path)
        val body = response.bodyAsText()
        check(response.status == HttpStatusCode.OK && body.isNotEmpty()) {
            "History endpoint failed: ${response.status.value}."
        }
    }

    private fun readAccountDigest(databasePath: Path): String = DriverManager.getConnection(
        "jdbc:sqlite:$databasePath",
    ).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT DISTINCT account_identity_digest FROM history_ledgers").use { resultSet ->
                check(resultSet.next()) { "The copied History database has no account ledger rows." }
                val digest = resultSet.getString(1)
                check(!resultSet.next()) { "The copied History database has multiple account identities." }
                digest
            }
        }
    }

    companion object {
        const val DATABASE_ENV = "HISTORY_PERFORMANCE_COPY_DB"
    }
}
