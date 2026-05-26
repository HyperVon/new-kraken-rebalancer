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
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.matchers.nulls.shouldNotBeNull
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.util.Base64

class PrecisionRoundingFuzzTest : StringSpec() {

    override fun isolationMode() = io.kotest.core.spec.IsolationMode.InstancePerTest

    init {
        "should handle extremely high precision balances and prices without throwing exceptions" {
        runTest {
            val validSecret = Base64.getEncoder().encodeToString("secret".toByteArray())
            val appConfig = AppConfig(
                KrakenCredentials("apiKey", validSecret),
                Settings(60L, 2.0, 1.0, false, 50.0, 1.0),
                listOf(Allocation("BTC", 50.0), Allocation("USD", 50.0))
            )

            val mockConfigService = mockk<ConfigService>(relaxed = true)
            every { mockConfigService.getConfig() } returns appConfig

            var capturedOrderPayload: String? = null

            val mockEngine = MockEngine { request ->
                when (request.url.encodedPath) {
                    "/0/private/Balance" -> {
                        respond(
                            content = "{\"error\":[],\"result\":{\"XXBT\":0.3333333333333333,\"ZUSD\":31415.9265358979323846}}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                    "/0/public/Ticker" -> {
                        respond(
                            content = "{\"error\":[],\"result\":{\"XXBTZUSD\":{\"c\":[\"68453.123456789\"]}}}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                    "/0/private/AddOrder" -> {
                        capturedOrderPayload = (request.body as io.ktor.http.content.TextContent).text
                        respond(
                            content = "{\"error\":[],\"result\":{\"descr\":{\"order\":\"buy\"},\"txid\":[\"TX-1\"]}}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                    else -> respond("{\"error\":[\"Unknown path\"]}", HttpStatusCode.NotFound)
                }
            }

            val httpClient = HttpClient(mockEngine)
            val objectMapper = jacksonObjectMapper().findAndRegisterModules()
            val krakenService = KrakenServiceImpl(mockConfigService, objectMapper, httpClient)
            
            val portfolioManager = PortfolioManagerImpl(
                krakenService, mockConfigService, mockk<TradeHistoryService>(relaxed = true), mockk<PortfolioStatsRepository>(relaxed = true)
            )

            shouldNotThrowAny {
                portfolioManager.performRebalanceCycle()
            }

            // Verify that an order was executed and the volume was rounded cleanly (max 8 decimal places)
            // It should not contain a huge trailing decimal like 0.3333333333333
            capturedOrderPayload.shouldNotBeNull()
            
            // Regex asserts volume is a number with 1 to 8 decimal places
            val volumeMatch = Regex("volume=(\\d+\\.\\d{1,8})(&|$)").find(capturedOrderPayload!!)
            volumeMatch.shouldNotBeNull()
        }
    }
}
}
