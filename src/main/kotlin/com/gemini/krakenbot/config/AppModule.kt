package com.gemini.krakenbot.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.repository.impl.SqlitePortfolioStatsRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.*
import com.gemini.krakenbot.service.impl.*
import com.gemini.krakenbot.view.DashboardView
import com.gemini.krakenbot.view.component.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import org.jetbrains.exposed.sql.Database
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val appModule = module {
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

    // Database
    single<Database> { DatabaseConfig.init() }

    single<ConfigService> { ConfigServiceImpl(get()) }
    single<TradeRepository> { SqliteTradeRepositoryImpl(get()) }
    single<PortfolioStatsRepository> { SqlitePortfolioStatsRepositoryImpl(get()) }
    single<TradeHistoryService> { TradeHistoryServiceImpl(get(), get(), get(), get()).apply { init() } }
    singleOf(::KrakenServiceImpl)
    singleOf(::SimulatedKrakenService)
    single<KrakenService> { DynamicKrakenService(get(), get(), get()) }
    single<PortfolioAnalyzer> { PortfolioAnalyzerImpl(get(), get(), get()) }
    single<OrderExecutor> { OrderExecutorImpl(get(), get(), get()) }
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
}
