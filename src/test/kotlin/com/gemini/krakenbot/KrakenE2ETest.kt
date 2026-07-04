package com.gemini.krakenbot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.repository.impl.SqlitePortfolioStatsRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.impl.*
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.*

private const val APPLICATION_JSON = "application/json"
private const val FILE_PATH = "filePath"

@Suppress("unused")
class KrakenE2ETest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "should execute a full rebalance cycle end-to-end" {
            runTest {
                val validSecret =
                    Base64
                        .getEncoder()
                        .encodeToString("secret".toByteArray())
                val appConfig = AppConfig(
                    kraken = KrakenCredentials(
                        apiKey = "apiKey",
                        privateKey = validSecret
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
                        Allocation(Asset.BTC, 50.0),
                        Allocation(Asset.USD, 50.0)
                    )
                )

                val mockConfigService = mockk<ConfigService>(relaxed = true)
                every { mockConfigService.getConfig() } returns appConfig

                var capturedOrderPayload: String? = null

                val mockEngine = MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/0/private/Balance" -> {
                            respond(
                                content = "{\"error\":[],\"result\":{\"XXBT\":0.5,\"ZUSD\":25000.0}}",
                                status = HttpStatusCode.OK,
                                headers = headersOf(
                                    HttpHeaders.ContentType,
                                    APPLICATION_JSON
                                )
                            )
                        }

                        "/0/public/Ticker" -> {
                            respond(
                                content = "{\"error\":[],\"result\":{\"XXBTZUSD\":{\"c\":[\"50000.0\"]}}}",
                                status = HttpStatusCode.OK,
                                headers = headersOf(
                                    HttpHeaders.ContentType,
                                    APPLICATION_JSON
                                )
                            )
                        }

                        "/0/private/AddOrder" -> {
                            capturedOrderPayload =
                                (request.body as TextContent).text
                            respond(
                                content =
                                    "{\"error\":[],\"result\":{\"descr\":{\"order\":\"buy\"},\"txid\":[\"TX-1\"]}}",
                                status = HttpStatusCode.OK,
                                headers = headersOf(
                                    HttpHeaders.ContentType,
                                    APPLICATION_JSON
                                )
                            )
                        }

                        else -> {
                            respond(
                                "{\"error\":[\"Unknown path\"]}",
                                HttpStatusCode.NotFound
                            )
                        }
                    }
                }

                val httpClient = HttpClient(mockEngine)
                val objectMapper =
                    jacksonObjectMapper().findAndRegisterModules()

                val db = DatabaseConfig.init(":memory:")
                val statsRepo = SqlitePortfolioStatsRepositoryImpl(db, objectMapper)
                val tradesRepo = SqliteTradeRepositoryImpl(db)

                // Services
                val krakenService = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = objectMapper,
                    httpClient = httpClient
                )
                val tradeHistoryService =
                    TradeHistoryServiceImpl(tradesRepo, statsRepo, krakenService, mockConfigService, objectMapper)

                val portfolioAnalyzer = PortfolioAnalyzerImpl(
                    krakenService = krakenService,
                    configService = mockConfigService,
                    portfolioStatsRepository = statsRepo
                )
                val orderExecutor =
                    OrderExecutorImpl(krakenService, portfolioAnalyzer, tradeHistoryService)
                val portfolioManager = PortfolioManagerImpl(
                    configService = mockConfigService,
                    tradeHistoryService = tradeHistoryService,
                    portfolioAnalyzer = portfolioAnalyzer,
                    orderExecutor = orderExecutor
                )

                // Execute E2E Rebalance
                portfolioManager.performRebalanceCycle()

                // Verify no order was executed because the portfolio is perfectly balanced!
                capturedOrderPayload.shouldBeNull()
            }
        }

        "should execute a full rebalance cycle end-to-end and trigger a trade" {
            runTest {
                val validSecret =
                    Base64.getEncoder().encodeToString("secret".toByteArray())
                val appConfig = AppConfig(
                    kraken = KrakenCredentials(
                        apiKey = "apiKey",
                        privateKey = validSecret
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
                        Allocation(Asset.BTC, 50.0),
                        Allocation(Asset.USD, 50.0)
                    )
                )

                val mockConfigService = mockk<ConfigService>(relaxed = true)
                every { mockConfigService.getConfig() } returns appConfig

                var capturedOrderPayload: String? = null

                val mockEngine = MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/0/private/Balance" -> {
                            respond(
                                content = "{\"error\":[],\"result\":{\"XXBT\":0.4,\"ZUSD\":30000.0}}",
                                status = HttpStatusCode.OK,
                                headers = headersOf(
                                    HttpHeaders.ContentType,
                                    APPLICATION_JSON
                                )
                            )
                        }

                        "/0/public/Ticker" -> {
                            respond(
                                content = "{\"error\":[],\"result\":{\"XXBTZUSD\":{\"c\":[\"50000.0\"]}}}",
                                status = HttpStatusCode.OK,
                                headers = headersOf(
                                    HttpHeaders.ContentType,
                                    APPLICATION_JSON
                                )
                            )
                        }

                        "/0/private/AddOrder" -> {
                            capturedOrderPayload =
                                (request.body as TextContent).text
                            respond(
                                content =
                                    "{\"error\":[],\"result\":{\"descr\":{\"order\":\"buy\"},\"txid\":[\"TX-1\"]}}",
                                status = HttpStatusCode.OK,
                                headers = headersOf(
                                    HttpHeaders.ContentType,
                                    APPLICATION_JSON
                                )
                            )
                        }

                        else -> respond(
                            "{\"error\":[\"Unknown path\"]}",
                            HttpStatusCode.NotFound
                        )
                    }
                }

                val httpClient = HttpClient(mockEngine)
                val objectMapper =
                    jacksonObjectMapper().findAndRegisterModules()

                val db = DatabaseConfig.init(":memory:")
                val statsRepo = SqlitePortfolioStatsRepositoryImpl(db, objectMapper)
                val tradesRepo = SqliteTradeRepositoryImpl(db)

                val krakenService = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = objectMapper,
                    httpClient = httpClient
                )
                val tradeHistoryService = TradeHistoryServiceImpl(tradesRepo, statsRepo, krakenService, mockConfigService, objectMapper)

                val portfolioAnalyzer = PortfolioAnalyzerImpl(
                    krakenService = krakenService,
                    configService = mockConfigService,
                    portfolioStatsRepository = statsRepo
                )
                val orderExecutor =
                    OrderExecutorImpl(krakenService, portfolioAnalyzer, tradeHistoryService)
                val portfolioManager = PortfolioManagerImpl(
                    configService = mockConfigService,
                    tradeHistoryService = tradeHistoryService,
                    portfolioAnalyzer = portfolioAnalyzer,
                    orderExecutor = orderExecutor
                )

                portfolioManager.performRebalanceCycle()

                // Verify
                capturedOrderPayload.shouldNotBeNull()
                capturedOrderPayload.contains("pair=XBTUSD").shouldBeTrue()
                capturedOrderPayload.contains("type=buy").shouldBeTrue()
                capturedOrderPayload.contains("ordertype=market").shouldBeTrue()
                capturedOrderPayload.contains("volume=0.1").shouldBeTrue()
            }
        }
    }
}
