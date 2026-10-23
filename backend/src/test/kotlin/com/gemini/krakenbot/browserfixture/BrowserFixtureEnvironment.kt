package com.gemini.krakenbot.browserfixture

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.DataDirectoryIdentity
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.controller.DashboardController
import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.service.AuthenticatedAccountInfo
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.CurrentPortfolioState
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.OrderIntentService
import com.gemini.krakenbot.service.history.HistoryApiPresenter
import com.gemini.krakenbot.service.history.HistoryCsvExporter
import com.gemini.krakenbot.service.history.HistoryCycleGate
import com.gemini.krakenbot.service.history.HistoryDailyPriceResolver
import com.gemini.krakenbot.service.history.HistoryDatabase
import com.gemini.krakenbot.service.history.HistoryObservationRecorder
import com.gemini.krakenbot.service.history.HistoryObservationValuator
import com.gemini.krakenbot.service.history.HistoryService
import com.gemini.krakenbot.service.history.HistoryServiceImpl
import com.gemini.krakenbot.service.history.HistorySourceMode
import com.gemini.krakenbot.service.history.HistorySourceScope
import com.gemini.krakenbot.service.history.HistoryStore
import com.gemini.krakenbot.service.history.HistorySyncCoordinator
import com.gemini.krakenbot.service.history.HistorySynchronizer
import com.gemini.krakenbot.service.history.HistoryValuationCoordinator
import com.gemini.krakenbot.service.history.SqliteHistoryStore
import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.PortfolioAnalyzerImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal const val BROWSER_FIXTURE_ACCOUNT_IDENTITY = "AA00 TEST TEST TEST"
internal val BROWSER_FIXTURE_PERIODIC_SYNC_INTERVAL: Duration = Duration.ofSeconds(20)
internal val BROWSER_FIXTURE_LATE_EVENT_DELAY: Duration = Duration.ofSeconds(15)
internal val BROWSER_FIXTURE_INCOMPLETE_EVENT_DELAY: Duration = Duration.ofSeconds(60)

internal class BrowserFixtureEnvironment private constructor(
    val applicationScope: CoroutineScope,
    val tempDirectory: Path,
    val fixtureNow: Instant,
    val accountScope: HistorySourceScope,
    val alternateScope: HistorySourceScope,
    val fakeKraken: FakeKrakenService,
    private val exchangeFeed: BrowserFixtureExchangeFeed,
    val historyDatabase: HistoryDatabase,
    val historyStore: HistoryStore,
    val historyService: HistoryService,
    val historyObservationRecorder: HistoryObservationRecorder,
    val historySyncCoordinator: HistorySyncCoordinator,
    val historyValuationCoordinator: HistoryValuationCoordinator,
    val portfolioState: CurrentPortfolioState,
    val portfolioManager: PortfolioManagerImpl,
    val controller: DashboardController,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    val firstSampleAt: Instant = fixtureNow.minusSeconds(100L * 24 * 60 * 60)
    val rangeBoundary: Instant = fixtureNow.minusSeconds(30L * 24 * 60 * 60)

    fun publishLateExchangeEvents(at: Instant) = exchangeFeed.publish(at)

    fun publishIncompleteEvidence(at: Instant) = exchangeFeed.publishIncompleteEvidence(at)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            historySyncCoordinator.stop()
            historyValuationCoordinator.stop()
            historyObservationRecorder.stop()
            val stoppedWorker = portfolioManager.stopRebalancingLoop()
            val scopeJob = applicationScope.coroutineContext[Job]
            applicationScope.cancel()
            runBlocking {
                stoppedWorker?.join()
                scopeJob?.join()
            }
        } finally {
            try {
                historyDatabase.close()
            } finally {
                removeIdentityMarkerDirectory(tempDirectory)
            }
        }
    }

    companion object {
        suspend fun create(
            applicationScope: CoroutineScope,
            fixtureNow: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS),
        ): BrowserFixtureEnvironment {
            val tempDirectory = Files.createTempDirectory("kraken-history-browser-fixture-")
            var database: HistoryDatabase? = null
            try {
                val historyDatabase = HistoryDatabase(tempDirectory.resolve("history.db")).also {
                    database = it
                    it.initializeFreshSchema()
                }
                val historyStore = SqliteHistoryStore(historyDatabase)
                val appConfig = AppConfig(
                    kraken = KrakenCredentials("", ""),
                    settings = Settings(
                        loopDelaySeconds = 2,
                        deviationTriggerPercent = 2.0,
                        minimumOrderSizeUSD = 5.0,
                        dryRun = true,
                        simulation = false,
                    ),
                    allocations = listOf(
                        Allocation(Asset.BTC, 40.0),
                        Allocation(Asset.ETH, 30.0),
                        Allocation(Asset.USD, 30.0),
                    ),
                )
                val configService = BrowserFixtureConfigService(appConfig)
                val exchangeFeed = BrowserFixtureExchangeFeed()
                val fakeKraken = FakeKrakenService().apply {
                    balanceSupplier = {
                        mapOf(
                            "XXBT" to BigDecimal("0.10"),
                            "XETH" to BigDecimal("1.20"),
                            "ZUSD" to BigDecimal("3000.00"),
                        )
                    }
                    pricesSupplier = {
                        mapOf("XXBTZUSD" to BigDecimal("40000"), "XETHZUSD" to BigDecimal("2500"))
                    }
                    fundingEvidenceScopeSupplier = { "browser-fixture-account-v1" }
                    authenticatedAccountIdentitySupplier = { BROWSER_FIXTURE_ACCOUNT_IDENTITY }
                    authenticatedAccountInfoFactory = { identity ->
                        AuthenticatedAccountInfo(
                            identity = identity,
                            queryFromSec = fixtureNow.minusSeconds(401L * 24 * 60 * 60).epochSecond,
                            queryBoundsVerified = true,
                        )
                    }
                    tradePageSupplier = { startSec, endSec, cursor, _ ->
                        exchangeFeed.tradePage(startSec, endSec, cursor)
                    }
                    ledgerPageSupplier = { startSec, endSec, cursor ->
                        exchangeFeed.ledgerPage(startSec, endSec, cursor)
                    }
                }
                val directoryIdentity = DataDirectoryIdentity(tempDirectory) { historyDatabase.hasAccountEvidence() }
                val accountKraken = BrowserFixtureKrakenService(fakeKraken)
                val historyObservationRecorder = HistoryObservationRecorder(
                    store = historyStore,
                    krakenService = accountKraken,
                    configService = configService,
                    dataDirectoryIdentity = directoryIdentity,
                    nowProvider = Instant::now,
                )
                val accountScope = checkNotNull(historyObservationRecorder.currentSourceScope(appConfig)) {
                    "The explicit browser fixture account did not verify."
                }
                check(accountScope.mode == HistorySourceMode.ACCOUNT)
                val alternateScope = HistoryObservationValuator.scope(
                    symbols = listOf("BTC", "USD"),
                    mode = HistorySourceMode.ACCOUNT,
                    accountIdentityDigest = checkNotNull(accountScope.accountIdentityDigest),
                )
                BrowserFixtureHistorySeed.seed(historyStore, accountScope, alternateScope, fixtureNow)

                val historyService = HistoryServiceImpl(historyStore)
                val portfolioState = CurrentPortfolioState()
                val orderIntentService = BrowserFixtureOrderIntentService()
                val portfolioManager = PortfolioManagerImpl(
                    configService = configService,
                    portfolioAnalyzer = PortfolioAnalyzerImpl(accountKraken, configService),
                    orderExecutor = OrderExecutorImpl(fakeKraken, orderIntentService),
                    krakenService = accountKraken,
                    currentPortfolioState = portfolioState,
                    historyObservationRecorder = historyObservationRecorder,
                )
                lateinit var valuationCoordinator: HistoryValuationCoordinator
                val synchronizer = HistorySynchronizer(
                    applicationScope = applicationScope,
                    krakenService = fakeKraken,
                    configService = configService,
                    dataDirectoryIdentity = directoryIdentity,
                    store = historyStore,
                    cycleGate = HistoryCycleGate { block -> portfolioManager.runHistoryPageIfAdmitted(block) },
                    onLedgerPageCommitted = {
                        println("LEDGER_PAGE_COMMITTED at=${Instant.now()}")
                        valuationCoordinator.requestRebuild()
                    },
                )
                valuationCoordinator = HistoryValuationCoordinator(
                    applicationScope = applicationScope,
                    synchronizer = synchronizer,
                    store = historyStore,
                    priceResolver = HistoryDailyPriceResolver(fakeKraken),
                )
                val historySyncCoordinator = HistorySyncCoordinator(
                    applicationScope = applicationScope,
                    synchronizer = synchronizer,
                    store = historyStore,
                    settingsProvider = { configService.getConfig().settings },
                    nowProvider = Instant::now,
                    periodicInterval = BROWSER_FIXTURE_PERIODIC_SYNC_INTERVAL,
                )
                val mapper = jacksonObjectMapper()
                    .registerModule(JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                val reportingService = HistoryReportingService(historyService) { Instant.now() }
                val apiPresenter = HistoryApiPresenter(
                    historyService = historyService,
                    historyStore = historyStore,
                    reportingService = reportingService,
                    syncCoordinator = historySyncCoordinator,
                    nowProvider = Instant::now,
                )
                val csvExporter = HistoryCsvExporter(historyService, historyStore)
                val view = DashboardView(
                    shellComponent = DashboardShellComponent(),
                    settingsFormComponent = SettingsFormComponent(),
                    fragmentComponent = DashboardFragmentComponent(
                        overviewGridComponent = OverviewGridComponent(),
                        allocationChartComponent = AllocationChartComponent(),
                        performanceTableComponent = PerformanceTableComponent(),
                        recentActivityComponent = RecentActivityComponent(),
                    ),
                    historyPageComponent = HistoryPageComponent(),
                )
                val controller = DashboardController(
                    configService = configService,
                    objectMapper = mapper,
                    dashboardView = view,
                    portfolioManager = portfolioManager,
                    currentPortfolioState = portfolioState,
                    orderIntentService = orderIntentService,
                    historyService = historyService,
                    historyObservationRecorder = historyObservationRecorder,
                    historySynchronizer = synchronizer,
                    historyApi = apiPresenter,
                    historyReportingService = reportingService,
                    historyCsvExporter = csvExporter,
                )
                return BrowserFixtureEnvironment(
                    applicationScope = applicationScope,
                    tempDirectory = tempDirectory,
                    fixtureNow = fixtureNow,
                    accountScope = accountScope,
                    alternateScope = alternateScope,
                    fakeKraken = fakeKraken,
                    exchangeFeed = exchangeFeed,
                    historyDatabase = historyDatabase,
                    historyStore = historyStore,
                    historyService = historyService,
                    historyObservationRecorder = historyObservationRecorder,
                    historySyncCoordinator = historySyncCoordinator,
                    historyValuationCoordinator = valuationCoordinator,
                    portfolioState = portfolioState,
                    portfolioManager = portfolioManager,
                    controller = controller,
                )
            } catch (error: Throwable) {
                try {
                    database?.close()
                } finally {
                    try {
                        removeIdentityMarkerDirectory(tempDirectory)
                    } finally {
                        applicationScope.cancel()
                    }
                }
                throw error
            }
        }

        private fun removeIdentityMarkerDirectory(directory: Path) {
            listOf("history.db-wal", "history.db-shm", "history.db", "account-identity.sha256")
                .forEach { Files.deleteIfExists(directory.resolve(it)) }
            Files.deleteIfExists(directory)
        }
    }
}

private class BrowserFixtureConfigService(initialConfig: AppConfig) : ConfigService {
    private val config = AtomicReference(initialConfig)
    private val settings = MutableStateFlow(initialConfig.settings)

    override suspend fun beginExecutionSession() = Unit

    override suspend fun endExecutionSession() = Unit

    override suspend fun loadConfig() = Unit

    override fun getConfig(): AppConfig = config.get()

    override suspend fun updateConfig(newConfig: AppConfig) {
        config.set(newConfig)
        settings.value = newConfig.settings
    }

    override suspend fun isConfigPublicationPending(): Boolean = false

    override fun watchConfigChanges(): Flow<Settings> = settings
}

private class BrowserFixtureOrderIntentService : OrderIntentService {
    private val lock = Any()
    private val intents = linkedMapOf<Int, OrderIntent>()
    private var nextId = 1

    override suspend fun ensureReadyForSubmission() = Unit

    override suspend fun ensureAccountBindingForSubmission() = Unit

    override suspend fun savePending(intent: OrderIntent): Int = synchronized(lock) {
        val id = nextId++
        intents[id] = intent.copy(id = id)
        id
    }

    override suspend fun recordOutcome(id: Int, result: OrderResult): Boolean = synchronized(lock) {
        val intent = intents[id] ?: return@synchronized false
        intents[id] = intent.copy(
            state = when {
                result.submissionUncertain -> OrderIntentState.UNCERTAIN
                result.success -> OrderIntentState.CONFIRMED
                else -> OrderIntentState.REJECTED
            },
            orderTxid = result.orderTxid,
            errorMessage = result.errorMessage,
            outcomeVolume = result.volume,
            resolvedAt = Instant.now(),
        )
        true
    }

    override suspend fun hasUnresolvedIntents(): Boolean = countUnresolvedIntents() > 0

    override suspend fun countUnresolvedIntents(): Long = synchronized(lock) {
        intents.values.count { it.state in UNRESOLVED_STATES }.toLong()
    }

    override suspend fun getUnresolvedIntents(): List<OrderIntent> = synchronized(lock) {
        intents.values.filter { it.state in UNRESOLVED_STATES }
    }

    override suspend fun resolve(id: Int, state: OrderIntentState, evidence: String, orderTxid: String?) {
        synchronized(lock) {
            val existing = checkNotNull(intents[id])
            intents[id] = existing.copy(
                state = state,
                resolutionEvidence = evidence,
                orderTxid = orderTxid ?: existing.orderTxid,
                resolvedAt = Instant.now(),
            )
        }
    }

    private companion object {
        val UNRESOLVED_STATES = setOf(OrderIntentState.PENDING, OrderIntentState.UNCERTAIN)
    }
}

internal fun fakeAccountIdentityDigest(): String = MessageDigest.getInstance("SHA-256")
    .digest(
        BROWSER_FIXTURE_ACCOUNT_IDENTITY.filterNot(Char::isWhitespace)
            .uppercase(Locale.ROOT)
            .toByteArray(Charsets.UTF_8),
    )
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
