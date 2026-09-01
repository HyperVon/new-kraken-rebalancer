package com.gemini.krakenbot.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.controller.DashboardController
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.OrderIntentRepository
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.repository.impl.SqliteLedgerRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteOrderIntentRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqlitePortfolioStatsRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.OrderExecutor
import com.gemini.krakenbot.service.OrderIntentService
import com.gemini.krakenbot.service.PortfolioAnalyzer
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.service.impl.ConfigServiceImpl
import com.gemini.krakenbot.service.impl.DynamicKrakenService
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.OrderIntentServiceImpl
import com.gemini.krakenbot.service.impl.PortfolioAnalyzerImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import com.gemini.krakenbot.service.impl.SimulatedKrakenService
import com.gemini.krakenbot.service.impl.history.LedgersSyncService
import com.gemini.krakenbot.service.impl.history.TradeHistoryQueryService
import com.gemini.krakenbot.service.impl.history.TradeHistoryReconstructionService
import com.gemini.krakenbot.service.impl.history.TradeHistoryServiceImpl
import com.gemini.krakenbot.service.impl.history.TradeHistorySnapshotStore
import com.gemini.krakenbot.service.impl.history.TradeHistorySyncService
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
import org.jetbrains.exposed.v1.jdbc.Database
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
                registerModule(JavaTimeModule())
                disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            }
        }

        single<Database> { DatabaseConfig.init() }

        single<ConfigService> { ConfigServiceImpl(objectMapper = get()) }
        singleOf(::SqliteTradeRepositoryImpl) { bind<TradeRepository>() }
        singleOf(::SqliteOrderIntentRepositoryImpl) { bind<OrderIntentRepository>() }
        singleOf(::OrderIntentServiceImpl) { bind<OrderIntentService>() }
        singleOf(::SqliteLedgerRepositoryImpl) { bind<LedgerRepository>() }
        single<PortfolioStatsRepository> { SqlitePortfolioStatsRepositoryImpl(database = get(), objectMapper = get()) }
        single {
            TradeHistorySnapshotStore(
                repository = get(),
                krakenService = get(),
                configService = get(),
                objectMapper = get(),
                portfolioStatsRepository = get(),
            )
        }
        single {
            TradeHistoryQueryService(
                repository = get(),
                portfolioStatsRepository = get(),
                ledgerRepository = get(),
                orderIntentRepository = get(),
            )
        }
        single {
            LedgersSyncService(
                repository = get(),
                krakenService = get(),
                configService = get(),
            )
        }
        single {
            TradeHistoryReconstructionService(
                repository = get(),
                ledgerRepository = get(),
                krakenService = get(),
                configService = get(),
                portfolioAnalyzer = get(),
                portfolioStatsRepository = get(),
            )
        }
        single {
            TradeHistorySyncService(
                repository = get(),
                krakenService = get(),
                configService = get(),
                reconstructionService = get(),
            )
        }
        single<TradeHistoryService> {
            TradeHistoryServiceImpl(
                snapshotStore = get(),
                queryService = get(),
                syncService = get(),
                ledgersSyncService = get(),
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
                portfolioStatsRepository = get(),
            )
        }
        single<OrderExecutor> {
            OrderExecutorImpl(
                krakenService = get(),
                tradeHistoryService = get(),
                orderIntentService = get(),
            )
        }
        // Explicit ctor: nullable `krakenService` defaults to null; singleOf would skip injection
        // and leave cycle-level DynamicKraken pinning disabled (#89).
        single<PortfolioManager> {
            PortfolioManagerImpl(
                configService = get(),
                tradeHistoryService = get(),
                portfolioAnalyzer = get(),
                orderExecutor = get(),
                krakenService = get(),
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
        singleOf(::HistoryPageComponent)
        singleOf(::DashboardView)
        singleOf(::DashboardController)
    }

val appModule =
    module {
        includes(coreModule, webModule)
    }
