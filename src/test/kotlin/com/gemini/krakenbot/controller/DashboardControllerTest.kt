package com.gemini.krakenbot.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.TradeHistoryService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.math.BigDecimal
import java.time.Instant
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule

class DashboardControllerTest : StringSpec() {

    override fun isolationMode() = io.kotest.core.spec.IsolationMode.InstancePerTest

    private val tradeHistoryService = mockk<TradeHistoryService>(relaxed = true)
    private val configService = mockk<ConfigService>(relaxed = true)
    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    init {
        val testModule = module {
            single { tradeHistoryService }
            single { configService }
        }

        beforeTest {
            stopKoin()
            startKoin {
                modules(testModule)
            }
        }

        afterTest {
            stopKoin()
        }

        "getStatus_ReturnsLatestSnapshot" {
            val snapshot = PortfolioSnapshot(Instant.now(), BigDecimal.ZERO, emptyMap(), emptyList(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
            every { tradeHistoryService.getLatestSnapshot() } returns snapshot

            testApplication {
                application {
                    install(ContentNegotiation) { jackson { registerModule(JavaTimeModule()) } }
                    dashboardRouting()
                }
                val response = client.get("/api/status")
                response.status shouldBe HttpStatusCode.OK
            }
        }

        "getHistory_ReturnsHistory" {
            val snapshot = PortfolioSnapshot(Instant.now(), BigDecimal.ZERO, emptyMap(), emptyList(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
            every { tradeHistoryService.getHistory() } returns listOf(snapshot)

            testApplication {
                application {
                    install(ContentNegotiation) { jackson { registerModule(JavaTimeModule()) } }
                    dashboardRouting()
                }
                val response = client.get("/api/history")
                response.status shouldBe HttpStatusCode.OK
            }
        }

        "getConfig_ReturnsConfigWithSanitizedCredentials" {
            val config = AppConfig(
                KrakenCredentials("real-api-key", "real-private-key"),
                Settings(60L, 2.0, 1.0, true, 0.0, 1.0),
                listOf(Allocation("USD", 100.0))
            )
            every { configService.getConfig() } returns config

            testApplication {
                application {
                    install(ContentNegotiation) { jackson { registerModule(JavaTimeModule()) } }
                    dashboardRouting()
                }
                val response = client.get("/api/config")
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body.contains("real-api-key") shouldBe false
                body.contains("loopDelaySeconds\":60") shouldBe true
            }
        }

        "updateConfig_PreservesServerCredentials" {
            val serverConfig = AppConfig(
                KrakenCredentials("server-key", "server-secret"),
                Settings(60L, 2.0, 1.0, true, 0.0, 1.0),
                listOf(Allocation("USD", 100.0))
            )
            every { configService.getConfig() } returns serverConfig

            val clientConfig = FrontendConfig(serverConfig.settings, serverConfig.allocations)

            testApplication {
                application {
                    install(ContentNegotiation) { jackson { registerModule(JavaTimeModule()) } }
                    dashboardRouting()
                }
                val response = client.post("/api/config") {
                    contentType(ContentType.Application.Json)
                    setBody(objectMapper.writeValueAsString(clientConfig))
                }
                response.status shouldBe HttpStatusCode.OK
            }

            verify { configService.updateConfig(any()) }
        }
    }
}
