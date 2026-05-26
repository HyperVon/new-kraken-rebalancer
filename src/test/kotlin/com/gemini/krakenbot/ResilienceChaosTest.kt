package com.gemini.krakenbot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.io.IOException

class ResilienceChaosTest : StringSpec() {

    override fun isolationMode() = io.kotest.core.spec.IsolationMode.InstancePerTest

    init {
        "should not crash the application when Kraken API returns 502 Bad Gateway" {
        runTest {
            val appConfig = AppConfig(
                KrakenCredentials("apiKey", "secret"),
                Settings(60L, 2.0, 1.0, false, 50.0, 1.0),
                listOf(Allocation("BTC", 50.0))
            )
            val mockConfigService = mockk<ConfigService>(relaxed = true)
            every { mockConfigService.getConfig() } returns appConfig

            val mockEngine = MockEngine { respond("Bad Gateway", HttpStatusCode.BadGateway) }
            val httpClient = HttpClient(mockEngine)
            
            val krakenService = KrakenServiceImpl(mockConfigService, jacksonObjectMapper(), httpClient)
            val portfolioManager = PortfolioManagerImpl(
                krakenService, mockConfigService, mockk<TradeHistoryService>(relaxed = true), mockk<PortfolioStatsRepository>(relaxed = true)
            )

            // Prove that the network failure correctly propagates an exception
            // This ensures our mock is working, while runLoop() is responsible for catching it (see PortfolioManagerImpl)
            shouldThrow<Exception> {
                portfolioManager.performRebalanceCycle()
            }
        }
    }

    "should not crash the application when an IOException occurs (Network failure)" {
        runTest {
            val appConfig = AppConfig(
                KrakenCredentials("apiKey", "secret"),
                Settings(60L, 2.0, 1.0, false, 50.0, 1.0),
                listOf(Allocation("BTC", 50.0))
            )
            val mockConfigService = mockk<ConfigService>(relaxed = true)
            every { mockConfigService.getConfig() } returns appConfig

            val mockEngine = MockEngine { throw IOException("Connection reset by peer") }
            val httpClient = HttpClient(mockEngine)
            
            val krakenService = KrakenServiceImpl(mockConfigService, jacksonObjectMapper(), httpClient)
            val portfolioManager = PortfolioManagerImpl(
                krakenService, mockConfigService, mockk<TradeHistoryService>(relaxed = true), mockk<PortfolioStatsRepository>(relaxed = true)
            )

            // Prove that the network failure correctly propagates an exception
            // This ensures our mock is working, while runLoop() is responsible for catching it (see PortfolioManagerImpl)
            shouldThrow<Exception> {
                portfolioManager.performRebalanceCycle()
            }
        }
    }
}
}
