package com.gemini.krakenbot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.repository.impl.FileTradeRepositoryImpl
import com.gemini.krakenbot.repository.impl.PortfolioStatsRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import com.gemini.krakenbot.service.impl.TradeHistoryServiceImpl
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.nulls.shouldBeNull
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.Base64

class KrakenE2ETest : StringSpec() {

    override fun isolationMode() = io.kotest.core.spec.IsolationMode.InstancePerTest

    init {
        "should execute a full rebalance cycle end-to-end" {
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
                            content = "{\"error\":[],\"result\":{\"XXBT\":0.5,\"ZUSD\":25000.0}}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                    "/0/public/Ticker" -> {
                        respond(
                            content = "{\"error\":[],\"result\":{\"XXBTZUSD\":{\"c\":[\"50000.0\"]}}}",
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
            
            // Repositories
            val statsFile = File("e2e-stats.json")
            val tradesFile = File("e2e-trades.json")
            if (statsFile.exists()) statsFile.delete()
            if (tradesFile.exists()) tradesFile.delete()
            
            val statsRepo = PortfolioStatsRepositoryImpl(objectMapper)
            val statsRepoField = PortfolioStatsRepositoryImpl::class.java.getDeclaredField("filePath")
            statsRepoField.isAccessible = true
            statsRepoField.set(statsRepo, statsFile.name)
            
            val tradesRepo = FileTradeRepositoryImpl(objectMapper)
            val tradesRepoField = FileTradeRepositoryImpl::class.java.getDeclaredField("filePath")
            tradesRepoField.isAccessible = true
            tradesRepoField.set(tradesRepo, tradesFile.name)

            // Services
            val krakenService = KrakenServiceImpl(mockConfigService, objectMapper, httpClient)
            val tradeHistoryService = TradeHistoryServiceImpl(tradesRepo)
            
            val portfolioManager = PortfolioManagerImpl(krakenService, mockConfigService, tradeHistoryService, statsRepo)

            // Execute E2E Rebalance
            portfolioManager.performRebalanceCycle()

            // Verify no order was executed because the portfolio is perfectly balanced!
            capturedOrderPayload.shouldBeNull()
            
            if (statsFile.exists()) statsFile.delete()
            if (tradesFile.exists()) tradesFile.delete()
        }
    }

    "should execute a full rebalance cycle end-to-end and trigger a trade" {
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
                            content = "{\"error\":[],\"result\":{\"XXBT\":0.4,\"ZUSD\":30000.0}}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                    "/0/public/Ticker" -> {
                        respond(
                            content = "{\"error\":[],\"result\":{\"XXBTZUSD\":{\"c\":[\"50000.0\"]}}}",
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
            
            val statsFile = File("e2e-stats.json")
            val tradesFile = File("e2e-trades.json")
            if (statsFile.exists()) statsFile.delete()
            if (tradesFile.exists()) tradesFile.delete()
            
            val statsRepo = PortfolioStatsRepositoryImpl(objectMapper)
            val statsRepoField = PortfolioStatsRepositoryImpl::class.java.getDeclaredField("filePath")
            statsRepoField.isAccessible = true
            statsRepoField.set(statsRepo, statsFile.name)
            
            val tradesRepo = FileTradeRepositoryImpl(objectMapper)
            val tradesRepoField = FileTradeRepositoryImpl::class.java.getDeclaredField("filePath")
            tradesRepoField.isAccessible = true
            tradesRepoField.set(tradesRepo, tradesFile.name)

            val krakenService = KrakenServiceImpl(mockConfigService, objectMapper, httpClient)
            val tradeHistoryService = TradeHistoryServiceImpl(tradesRepo)
            
            val portfolioManager = PortfolioManagerImpl(krakenService, mockConfigService, tradeHistoryService, statsRepo)

            portfolioManager.performRebalanceCycle()

            // Verify
            capturedOrderPayload.shouldNotBeNull()
            capturedOrderPayload.contains("pair=BTCUSD").shouldBeTrue()
            capturedOrderPayload.contains("type=buy").shouldBeTrue()
            capturedOrderPayload.contains("ordertype=market").shouldBeTrue()
            capturedOrderPayload.contains("volume=0.1").shouldBeTrue()
            if (statsFile.exists()) statsFile.delete()
            if (tradesFile.exists()) tradesFile.delete()
        }
    }
}
}
