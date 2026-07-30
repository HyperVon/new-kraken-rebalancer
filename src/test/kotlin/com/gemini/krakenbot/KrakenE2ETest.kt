package com.gemini.krakenbot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.repository.impl.SqlitePortfolioStatsRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.impl.ConfigServiceImpl
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.PortfolioAnalyzerImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import com.gemini.krakenbot.service.impl.history.TradeHistoryServiceImpl
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.util.Base64

class KrakenE2ETest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "should execute a full rebalance cycle end-to-end" {
            runTest {
                val validSecret =
                    Base64
                        .getEncoder()
                        .encodeToString("secret".toByteArray())
                val appConfig =
                    AppConfig(
                        kraken =
                        KrakenCredentials(
                            apiKey = "apiKey",
                            privateKey = validSecret,
                        ),
                        settings =
                        TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L, fiatMaxDrawdown = 50.0),
                        allocations =
                        listOf(
                            Allocation(Asset.BTC, 50.0),
                            Allocation(Asset.USD, 50.0),
                        ),
                    )

                val mockConfigService = mockk<ConfigService>(relaxed = true)
                every { mockConfigService.getConfig() } returns appConfig

                var capturedOrderPayload: String? = null

                val mockEngine =
                    MockEngine { request ->
                        when (request.url.encodedPath) {
                            "/0/private/Balance" -> {
                                respond(
                                    content = "{\"error\":[],\"result\":{\"XXBT\":0.5,\"ZUSD\":25000.0}}",
                                    status = HttpStatusCode.OK,
                                    headers =
                                    headersOf(
                                        HttpHeaders.ContentType,
                                        TestFixtures.APPLICATION_JSON,
                                    ),
                                )
                            }

                            "/0/public/Ticker" -> {
                                respond(
                                    content = "{\"error\":[],\"result\":{\"XXBTZUSD\":{\"c\":[\"50000.0\"]}}}",
                                    status = HttpStatusCode.OK,
                                    headers =
                                    headersOf(
                                        HttpHeaders.ContentType,
                                        TestFixtures.APPLICATION_JSON,
                                    ),
                                )
                            }

                            "/0/private/AddOrder" -> {
                                capturedOrderPayload =
                                    (request.body as TextContent).text
                                respond(
                                    content =
                                    "{\"error\":[],\"result\":{\"descr\":{\"order\":\"buy\"},\"txid\":[\"TX-1\"]}}",
                                    status = HttpStatusCode.OK,
                                    headers =
                                    headersOf(
                                        HttpHeaders.ContentType,
                                        TestFixtures.APPLICATION_JSON,
                                    ),
                                )
                            }

                            else -> {
                                respond(
                                    "{\"error\":[\"Unknown path\"]}",
                                    HttpStatusCode.NotFound,
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

                val krakenService =
                    KrakenServiceImpl(
                        configService = mockConfigService,
                        objectMapper = objectMapper,
                        httpClient = httpClient,
                    )
                val portfolioAnalyzer =
                    PortfolioAnalyzerImpl(
                        krakenService = krakenService,
                        configService = mockConfigService,
                        portfolioStatsRepository = statsRepo,
                    )
                val tradeHistoryService =
                    TradeHistoryServiceImpl(
                        repository = tradesRepo,
                        portfolioStatsRepository = statsRepo,
                        krakenService = krakenService,
                        configService = mockConfigService,
                        objectMapper = objectMapper,
                        portfolioAnalyzer = portfolioAnalyzer,
                    )

                val orderExecutor =
                    OrderExecutorImpl(krakenService, tradeHistoryService)
                val portfolioManager =
                    PortfolioManagerImpl(
                        configService = mockConfigService,
                        tradeHistoryService = tradeHistoryService,
                        portfolioAnalyzer = portfolioAnalyzer,
                        orderExecutor = orderExecutor,
                    )

                portfolioManager.performRebalanceCycle()

                // 0.5 BTC @ $50,000 is $25,000 against $25,000 of cash — exactly the 50/50 target,
                // so the cycle must never reach AddOrder.
                capturedOrderPayload.shouldBeNull()
            }
        }

        "should execute a full rebalance cycle end-to-end and trigger a trade" {
            runTest {
                val validSecret =
                    Base64.getEncoder().encodeToString("secret".toByteArray())
                val appConfig =
                    AppConfig(
                        kraken =
                        KrakenCredentials(
                            apiKey = "apiKey",
                            privateKey = validSecret,
                        ),
                        settings =
                        TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L, fiatMaxDrawdown = 50.0),
                        allocations =
                        listOf(
                            Allocation(Asset.BTC, 50.0),
                            Allocation(Asset.USD, 50.0),
                        ),
                    )

                val mockConfigService = mockk<ConfigService>(relaxed = true)
                every { mockConfigService.getConfig() } returns appConfig

                var capturedOrderPayload: String? = null

                val mockEngine =
                    MockEngine { request ->
                        when (request.url.encodedPath) {
                            "/0/private/Balance" -> {
                                respond(
                                    content = "{\"error\":[],\"result\":{\"XXBT\":0.4,\"ZUSD\":30000.0}}",
                                    status = HttpStatusCode.OK,
                                    headers =
                                    headersOf(
                                        HttpHeaders.ContentType,
                                        TestFixtures.APPLICATION_JSON,
                                    ),
                                )
                            }

                            "/0/public/Ticker" -> {
                                respond(
                                    content = "{\"error\":[],\"result\":{\"XXBTZUSD\":{\"c\":[\"50000.0\"]}}}",
                                    status = HttpStatusCode.OK,
                                    headers =
                                    headersOf(
                                        HttpHeaders.ContentType,
                                        TestFixtures.APPLICATION_JSON,
                                    ),
                                )
                            }

                            "/0/private/AddOrder" -> {
                                capturedOrderPayload =
                                    (request.body as TextContent).text
                                respond(
                                    content =
                                    "{\"error\":[],\"result\":{\"descr\":{\"order\":\"buy\"},\"txid\":[\"TX-1\"]}}",
                                    status = HttpStatusCode.OK,
                                    headers =
                                    headersOf(
                                        HttpHeaders.ContentType,
                                        TestFixtures.APPLICATION_JSON,
                                    ),
                                )
                            }

                            else ->
                                respond(
                                    "{\"error\":[\"Unknown path\"]}",
                                    HttpStatusCode.NotFound,
                                )
                        }
                    }

                val httpClient = HttpClient(mockEngine)
                val objectMapper =
                    jacksonObjectMapper().findAndRegisterModules()

                val db = DatabaseConfig.init(":memory:")
                val statsRepo = SqlitePortfolioStatsRepositoryImpl(db, objectMapper)
                val tradesRepo = SqliteTradeRepositoryImpl(db)

                val krakenService =
                    KrakenServiceImpl(
                        configService = mockConfigService,
                        objectMapper = objectMapper,
                        httpClient = httpClient,
                    )
                val portfolioAnalyzer =
                    PortfolioAnalyzerImpl(
                        krakenService = krakenService,
                        configService = mockConfigService,
                        portfolioStatsRepository = statsRepo,
                    )
                val tradeHistoryService =
                    TradeHistoryServiceImpl(
                        repository = tradesRepo,
                        portfolioStatsRepository = statsRepo,
                        krakenService = krakenService,
                        configService = mockConfigService,
                        objectMapper = objectMapper,
                        portfolioAnalyzer = portfolioAnalyzer,
                    )

                val orderExecutor =
                    OrderExecutorImpl(krakenService, tradeHistoryService)
                val portfolioManager =
                    PortfolioManagerImpl(
                        configService = mockConfigService,
                        tradeHistoryService = tradeHistoryService,
                        portfolioAnalyzer = portfolioAnalyzer,
                        orderExecutor = orderExecutor,
                    )

                portfolioManager.performRebalanceCycle()

                // 0.4 BTC @ $50,000 is $20,000 of a $50,000 portfolio (40%); restoring the 50/50 split
                // buys $5,000 of BTC, hence volume 0.1 on the XBTUSD pair alias.
                capturedOrderPayload.shouldNotBeNull()
                capturedOrderPayload.contains("pair=XBTUSD").shouldBeTrue()
                capturedOrderPayload.contains("type=buy").shouldBeTrue()
                capturedOrderPayload.contains("ordertype=market").shouldBeTrue()
                capturedOrderPayload.contains("volume=0.1").shouldBeTrue()
            }
        }
    }
}
