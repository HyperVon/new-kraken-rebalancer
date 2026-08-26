package com.gemini.krakenbot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.PortfolioAnalyzerImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
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
import java.math.BigDecimal
import java.util.Base64

class PrecisionRoundingFuzzTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "should handle extremely high precision balances and prices without throwing exceptions" {
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
                                    content =
                                    "{\"error\":[],\"result\":{\"XXBT\":0.3333333333333333,\"ZUSD\":31415.9265358979323846}}",
                                    status = HttpStatusCode.OK,
                                    headers =
                                    headersOf(
                                        HttpHeaders.ContentType,
                                        "application/json",
                                    ),
                                )
                            }

                            "/0/public/Ticker" -> {
                                respond(
                                    content = "{\"error\":[],\"result\":{\"XXBTZUSD\":{\"c\":[\"68453.123456789\"]}}}",
                                    status = HttpStatusCode.OK,
                                    headers =
                                    headersOf(
                                        HttpHeaders.ContentType,
                                        "application/json",
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
                                        "application/json",
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
                        portfolioStatsRepository = mockk<PortfolioStatsRepository>(relaxed = true),
                    )
                val tradeHistoryService = mockk<TradeHistoryService>(relaxed = true)
                val orderExecutor =
                    OrderExecutorImpl(krakenService, tradeHistoryService)
                val portfolioManager =
                    PortfolioManagerImpl(
                        configService = mockConfigService,
                        tradeHistoryService = tradeHistoryService,
                        portfolioAnalyzer = portfolioAnalyzer,
                        orderExecutor = orderExecutor,
                    )

                shouldNotThrowAny {
                    portfolioManager.performRebalanceCycle()
                }

                // The deficit above does not divide evenly by the fuzzed price, so an unclamped volume
                // would reach the wire with far more precision than Kraken accepts. Matching the
                // 1-to-8-decimal pattern is the assertion that crypto scale 8 was applied.
                capturedOrderPayload.shouldNotBeNull()

                val volumeMatch =
                    Regex("volume=(\\d+\\.\\d{1,8})(&|$)").find(
                        capturedOrderPayload,
                    )
                volumeMatch.shouldNotBeNull()
                // A $4,299.11 BTC deviation at the $68,453.12 ticker floors to 0.06280370 BTC at
                // crypto scale 8; assert the actual submitted volume, not just the wire shape.
                val submittedVolume = volumeMatch.groupValues[1].toBigDecimal()
                submittedVolume shouldBeEqualComparingTo BigDecimal("0.06280370")
            }
        }
    }
}
