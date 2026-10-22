package com.gemini.krakenbot.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.controller.DashboardController
import com.gemini.krakenbot.repository.ExecutionAccountBindingRepository
import com.gemini.krakenbot.repository.ExecutionOrderIntentRepository
import com.gemini.krakenbot.repository.impl.SqliteExecutionAccountBindingRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteExecutionOrderIntentRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.CurrentPortfolioState
import com.gemini.krakenbot.service.ExecutionAccountBindingVerifier
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.OrderExecutor
import com.gemini.krakenbot.service.OrderIntentService
import com.gemini.krakenbot.service.PortfolioAnalyzer
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.history.HistoryApiPresenter
import com.gemini.krakenbot.service.history.HistoryCsvExporter
import com.gemini.krakenbot.service.history.HistoryCycleGate
import com.gemini.krakenbot.service.history.HistoryDailyPriceResolver
import com.gemini.krakenbot.service.history.HistoryDatabase
import com.gemini.krakenbot.service.history.HistoryObservationRecorder
import com.gemini.krakenbot.service.history.HistoryService
import com.gemini.krakenbot.service.history.HistoryServiceImpl
import com.gemini.krakenbot.service.history.HistoryStore
import com.gemini.krakenbot.service.history.HistorySyncCoordinator
import com.gemini.krakenbot.service.history.HistorySynchronizer
import com.gemini.krakenbot.service.history.HistoryValuationCoordinator
import com.gemini.krakenbot.service.history.SqliteHistoryStore
import com.gemini.krakenbot.service.impl.ConfigServiceImpl
import com.gemini.krakenbot.service.impl.DynamicKrakenService
import com.gemini.krakenbot.service.impl.ExecutionAccountBindingService
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.OrderIntentServiceImpl
import com.gemini.krakenbot.service.impl.PortfolioAnalyzerImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import com.gemini.krakenbot.service.impl.SimulatedKrakenService
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
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

// Koin qualifier shared with the application entrypoint, which resolves the same scope by name.
const val APPLICATION_SCOPE_QUALIFIER = "applicationScope"

val coreModule =
    module {
        single<HttpClient> {
            HttpClient(CIO) {
                install(HttpTimeout) {
                    connectTimeoutMillis = 5000
                    socketTimeoutMillis = 15000
                    requestTimeoutMillis = 15000
                }
            }
        }
        single<ObjectMapper> {
            jacksonObjectMapper().apply {
                enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                registerModule(JavaTimeModule())
                disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            }
        }

        single {
            DataDirectoryIdentity(DataDirectoryConfig.resolveDataDirectory()) {
                get<ExecutionDatabase>().hasAccountEvidence() || get<HistoryDatabase>().hasAccountEvidence()
            }
        }
        single { ExecutionDatabase.init(DataDirectoryConfig.executionDatabasePath().toString()) }
        single { HistoryDatabase(DataDirectoryConfig.historyDatabasePath()) }

        single<ConfigService> { ConfigServiceImpl(objectMapper = get()) }
        single { CurrentPortfolioState() }
        single<ExecutionOrderIntentRepository> { SqliteExecutionOrderIntentRepositoryImpl(database = get()) }
        single<ExecutionAccountBindingRepository> { SqliteExecutionAccountBindingRepositoryImpl(database = get()) }
        single<ExecutionAccountBindingService> {
            ExecutionAccountBindingService(
                database = get(),
                bindingRepository = get(),
                krakenService = get(),
                configService = get(),
                dataDirectoryIdentity = get(),
            )
        }
        single<ExecutionAccountBindingVerifier> { get<ExecutionAccountBindingService>() }
        single<OrderIntentService> {
            OrderIntentServiceImpl(
                repository = get(),
                accountBindingVerifier = get(),
            )
        }
        single<ExecutionJournalBootstrap> {
            ExecutionJournalBootstrap(database = get(), repository = get())
        }
        single<HistoryStore> { SqliteHistoryStore(get()) }
        single<HistoryService> { HistoryServiceImpl(get()) }
        single { HistoryCsvExporter(historyService = get(), historyStore = get()) }
        single { HistoryReportingService(historyService = get()) }
        single {
            HistoryApiPresenter(
                historyService = get(),
                historyStore = get(),
                reportingService = get(),
                syncCoordinator = get(),
            )
        }
        single {
            HistoryObservationRecorder(
                store = get(),
                krakenService = get(),
                configService = get(),
                dataDirectoryIdentity = get(),
            )
        }
        single {
            HistorySynchronizer(
                applicationScope = get(qualifier = named(APPLICATION_SCOPE_QUALIFIER)),
                krakenService = get(),
                configService = get(),
                dataDirectoryIdentity = get(),
                store = get(),
                cycleGate = HistoryCycleGate { block -> get<PortfolioManager>().runHistoryPageIfAdmitted(block) },
                onLedgerPageCommitted = { get<HistoryValuationCoordinator>().requestRebuild() },
            )
        }
        single {
            HistoryDailyPriceResolver(krakenService = get())
        }
        single {
            HistoryValuationCoordinator(
                applicationScope = get(qualifier = named(APPLICATION_SCOPE_QUALIFIER)),
                synchronizer = get(),
                store = get(),
                priceResolver = get(),
            )
        }
        single {
            HistorySyncCoordinator(
                applicationScope = get(qualifier = named(APPLICATION_SCOPE_QUALIFIER)),
                synchronizer = get(),
                store = get(),
                settingsProvider = { get<ConfigService>().getConfig().settings },
            )
        }
        // Explicit constructor call (not singleOf) so the default `RateLimiter()` is used:
        // the limiter is a constructor param only so tests can record acquire costs.
        single { KrakenServiceImpl(configService = get(), objectMapper = get(), httpClient = get()) }
        singleOf(::SimulatedKrakenService)
        single<KrakenService> {
            DynamicKrakenService(
                realService = get(),
                simulatedService = get(),
                configService = get(),
            )
        }
        single<PortfolioAnalyzer> {
            PortfolioAnalyzerImpl(
                krakenService = get(),
                configService = get(),
            )
        }
        single<OrderExecutor> {
            OrderExecutorImpl(
                krakenService = get(),
                orderIntentService = get(),
            )
        }
        // Explicit ctor: nullable `krakenService` defaults to null; singleOf would skip injection
        // and leave cycle-level DynamicKraken pinning disabled (#89).
        single<PortfolioManager> {
            PortfolioManagerImpl(
                configService = get(),
                portfolioAnalyzer = get(),
                orderExecutor = get(),
                krakenService = get(),
                currentPortfolioState = get(),
                historyObservationRecorder = get(),
                historySyncCoordinator = get(),
            )
        }
        single<CoroutineScope>(qualifier = named(APPLICATION_SCOPE_QUALIFIER)) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }
    }

val webModule =
    module {
        singleOf(::DashboardShellComponent)
        singleOf(::SettingsFormComponent)
        singleOf(::OverviewGridComponent)
        singleOf(::AllocationChartComponent)
        singleOf(::PerformanceTableComponent)
        singleOf(::RecentActivityComponent)
        singleOf(::DashboardFragmentComponent)
        single { HistoryPageComponent() }
        singleOf(::DashboardView)
        single {
            DashboardController(
                configService = get(),
                objectMapper = get(),
                dashboardView = get(),
                portfolioManager = get(),
                currentPortfolioState = get(),
                orderIntentService = get(),
                historyService = get(),
                historyObservationRecorder = get(),
                historySynchronizer = get(),
                historyApi = get(),
                historyReportingService = get(),
                historyCsvExporter = get(),
                historyValuationCoordinator = get(),
            )
        }
    }

val appModule =
    module {
        includes(coreModule, webModule)
    }
