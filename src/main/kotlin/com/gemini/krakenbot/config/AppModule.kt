package com.gemini.krakenbot.config

import com.gemini.krakenbot.service.impl.*
import com.gemini.krakenbot.view.component.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.repository.impl.FileTradeRepositoryImpl
import com.gemini.krakenbot.repository.impl.PortfolioStatsRepositoryImpl
import com.gemini.krakenbot.service.*
import com.gemini.krakenbot.view.DashboardView
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val appModule = module {
    single<HttpClient> { HttpClient(CIO) }
    single<ObjectMapper> {
        jacksonObjectMapper().apply {
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }
    }
    single<ConfigService> { ConfigServiceImpl(get()) }
    singleOf(::FileTradeRepositoryImpl) { bind<TradeRepository>() }
    singleOf(::PortfolioStatsRepositoryImpl) { bind<PortfolioStatsRepository>() }
    single<TradeHistoryService> { TradeHistoryServiceImpl(get()).apply { init() } }
    singleOf(::KrakenServiceImpl) { bind<KrakenService>() }
    singleOf(::PortfolioAnalyzer)
    singleOf(::OrderExecutor)
    single<PortfolioManager> {
        PortfolioManagerImpl(
            configService = get(),
            tradeHistoryService = get(),
            portfolioAnalyzer = get(),
            orderExecutor = get()
        )
    }
    singleOf(::DashboardShellComponent)
    singleOf(::SettingsFormComponent)
    singleOf(::OverviewGridComponent)
    singleOf(::AllocationChartComponent)
    singleOf(::PerformanceTableComponent)
    singleOf(::RecentActivityComponent)
    singleOf(::DashboardFragmentComponent)
    singleOf(::DashboardView)
}
