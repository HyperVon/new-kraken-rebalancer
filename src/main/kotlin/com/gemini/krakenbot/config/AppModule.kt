package com.gemini.krakenbot.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.repository.impl.FileTradeRepositoryImpl
import com.gemini.krakenbot.repository.impl.PortfolioStatsRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import com.gemini.krakenbot.service.PortfolioManager
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.service.impl.ConfigServiceImpl
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import com.gemini.krakenbot.service.impl.TradeHistoryServiceImpl
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
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
    single<TradeRepository> { FileTradeRepositoryImpl(get()) }
    single<PortfolioStatsRepository> { PortfolioStatsRepositoryImpl(get()) }
    single<TradeHistoryService> { TradeHistoryServiceImpl(get()).apply { init() } }
    single<KrakenService> { KrakenServiceImpl(get(), get(), get()) }
    single<PortfolioManager> { PortfolioManagerImpl(get(), get(), get(), get()) }
}
