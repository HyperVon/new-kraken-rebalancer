package com.gemini.krakenbot.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.controller.DashboardController
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.repository.impl.SqlitePortfolioStatsRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.OrderExecutor
import com.gemini.krakenbot.service.PortfolioAnalyzer
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.service.impl.ConfigServiceImpl
import com.gemini.krakenbot.service.impl.DynamicKrakenService
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.PortfolioAnalyzerImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import com.gemini.krakenbot.service.impl.SimulatedKrakenService
import com.gemini.krakenbot.service.impl.TradeHistoryServiceImpl
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
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val appModule =
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
        single<PortfolioStatsRepository> { SqlitePortfolioStatsRepositoryImpl(database = get(), objectMapper = get()) }
        single<TradeHistoryService> {
            TradeHistoryServiceImpl(
                repository = get(),
                portfolioStatsRepository = get(),
                krakenService = get(),
                configService = get(),
                objectMapper = get(),
                portfolioAnalyzer = get(),
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
        singleOf(::PortfolioAnalyzerImpl) { bind<PortfolioAnalyzer>() }
        singleOf(::OrderExecutorImpl) { bind<OrderExecutor>() }
        singleOf(::PortfolioManagerImpl) { bind<PortfolioManager>() }
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
