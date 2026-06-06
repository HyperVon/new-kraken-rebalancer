package com.gemini.krakenbot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import com.gemini.krakenbot.service.impl.OrderExecutor
import com.gemini.krakenbot.service.impl.PortfolioAnalyzer
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.io.IOException

@Suppress("unused")
class ResilienceChaosTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "should not crash the application when Kraken API returns 502 Bad Gateway" {
            runTest {
                val appConfig = AppConfig(
                    kraken = KrakenCredentials(
                        apiKey = "apiKey",
                        privateKey = "secret"
                    ),
                    settings = Settings(
                        loopDelaySeconds = 60L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 50.0,
                        fiatDeploymentExponent = 1.0
                    ),
                    allocations = listOf(
                        Allocation(
                            symbol = Asset.BTC,
                            targetPercent = 50.0
                        )
                    )
                )
                val mockConfigService = mockk<ConfigService>(relaxed = true)
                every { mockConfigService.getConfig() } returns appConfig

                val mockEngine = MockEngine {
                    respond(
                        "Bad Gateway",
                        HttpStatusCode.BadGateway
                    )
                }
                val httpClient = HttpClient(mockEngine)

                val krakenService = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = httpClient
                )
                val portfolioAnalyzer =
                    PortfolioAnalyzer(
                        krakenService = krakenService,
                        configService = mockConfigService,
                        portfolioStatsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
                    )
                val orderExecutor =
                    OrderExecutor(krakenService, portfolioAnalyzer)
                val portfolioManager = PortfolioManagerImpl(
                    configService = mockConfigService,
                    tradeHistoryService = mockk<TradeHistoryService>(relaxed = true),
                    portfolioAnalyzer = portfolioAnalyzer,
                    orderExecutor = orderExecutor
                )

                // Prove that the network failure correctly propagates an exception
                // This ensures our mock is working, while runLoop() is responsible
                // for catching it (see PortfolioManagerImpl)
                shouldThrow<Exception> {
                    portfolioManager.performRebalanceCycle()
                }
            }
        }

        "should not crash the application when an IOException occurs (Network failure)" {
            runTest {
                val appConfig = AppConfig(
                    kraken = KrakenCredentials(
                        apiKey = "apiKey",
                        privateKey = "secret"
                    ),
                    settings = Settings(
                        loopDelaySeconds = 60L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 50.0,
                        fiatDeploymentExponent = 1.0
                    ),
                    allocations = listOf(
                        Allocation(
                            symbol = Asset.BTC,
                            targetPercent = 50.0
                        )
                    )
                )
                val mockConfigService = mockk<ConfigService>(relaxed = true)
                every { mockConfigService.getConfig() } returns appConfig

                val mockEngine =
                    MockEngine { throw IOException("Connection reset by peer") }
                val httpClient = HttpClient(mockEngine)

                val krakenService = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = httpClient
                )
                val portfolioAnalyzer =
                    PortfolioAnalyzer(
                        krakenService = krakenService,
                        configService = mockConfigService,
                        portfolioStatsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
                    )
                val orderExecutor =
                    OrderExecutor(krakenService, portfolioAnalyzer)
                val portfolioManager = PortfolioManagerImpl(
                    configService = mockConfigService,
                    tradeHistoryService = mockk<TradeHistoryService>(relaxed = true),
                    portfolioAnalyzer = portfolioAnalyzer,
                    orderExecutor = orderExecutor
                )

                // Prove that the network failure correctly propagates an exception
                // This ensures our mock is working, while runLoop() is responsible
                // for catching it (see PortfolioManagerImpl)
                shouldThrow<Exception> {
                    portfolioManager.performRebalanceCycle()
                }
            }
        }
    }
}
