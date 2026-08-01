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
import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.PortfolioAnalyzerImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.ResponseException
import io.ktor.http.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.io.IOException

class ResilienceChaosTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "propagates ResponseException when Kraken API returns 502 Bad Gateway" {
            runTest {
                val appConfig =
                    AppConfig(
                        kraken =
                        KrakenCredentials(
                            apiKey = "apiKey",
                            privateKey = "c2VjcmV0",
                        ),
                        settings =
                        TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L, fiatMaxDrawdown = 50.0),
                        allocations =
                        listOf(
                            Allocation(
                                symbol = Asset.BTC,
                                targetPercent = 50.0,
                            ),
                        ),
                    )
                val mockConfigService = mockk<ConfigService>(relaxed = true)
                every { mockConfigService.getConfig() } returns appConfig

                val mockEngine =
                    MockEngine {
                        respond(
                            "Bad Gateway",
                            HttpStatusCode.BadGateway,
                        )
                    }
                val httpClient = HttpClient(mockEngine)

                val krakenService =
                    KrakenServiceImpl(
                        configService = mockConfigService,
                        objectMapper = jacksonObjectMapper(),
                        httpClient = httpClient,
                    )
                val portfolioAnalyzer =
                    PortfolioAnalyzerImpl(
                        krakenService = krakenService,
                        configService = mockConfigService,
                        portfolioStatsRepository = mockk<PortfolioStatsRepository>(relaxed = true),
                    )
                val mockTradeHistoryService = mockk<TradeHistoryService>(relaxed = true)
                val orderExecutor =
                    OrderExecutorImpl(krakenService, mockTradeHistoryService)
                val portfolioManager =
                    PortfolioManagerImpl(
                        configService = mockConfigService,
                        tradeHistoryService = mockTradeHistoryService,
                        portfolioAnalyzer = portfolioAnalyzer,
                        orderExecutor = orderExecutor,
                    )

                shouldThrow<ResponseException> {
                    portfolioManager.performRebalanceCycle()
                }
            }
        }

        "propagates IOException on network failure" {
            runTest {
                val appConfig =
                    AppConfig(
                        kraken =
                        KrakenCredentials(
                            apiKey = "apiKey",
                            privateKey = "c2VjcmV0",
                        ),
                        settings =
                        TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L, fiatMaxDrawdown = 50.0),
                        allocations =
                        listOf(
                            Allocation(
                                symbol = Asset.BTC,
                                targetPercent = 50.0,
                            ),
                        ),
                    )
                val mockConfigService = mockk<ConfigService>(relaxed = true)
                every { mockConfigService.getConfig() } returns appConfig

                val mockEngine =
                    MockEngine { throw IOException("Connection reset by peer") }
                val httpClient = HttpClient(mockEngine)

                val krakenService =
                    KrakenServiceImpl(
                        configService = mockConfigService,
                        objectMapper = jacksonObjectMapper(),
                        httpClient = httpClient,
                    )
                val portfolioAnalyzer =
                    PortfolioAnalyzerImpl(
                        krakenService = krakenService,
                        configService = mockConfigService,
                        portfolioStatsRepository = mockk<PortfolioStatsRepository>(relaxed = true),
                    )
                val mockTradeHistoryService = mockk<TradeHistoryService>(relaxed = true)
                val orderExecutor =
                    OrderExecutorImpl(krakenService, mockTradeHistoryService)
                val portfolioManager =
                    PortfolioManagerImpl(
                        configService = mockConfigService,
                        tradeHistoryService = mockTradeHistoryService,
                        portfolioAnalyzer = portfolioAnalyzer,
                        orderExecutor = orderExecutor,
                    )

                shouldThrow<IOException> {
                    portfolioManager.performRebalanceCycle()
                }
            }
        }
    }
}
