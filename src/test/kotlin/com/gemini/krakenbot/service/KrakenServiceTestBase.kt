@file:OptIn(ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import com.gemini.krakenbot.service.impl.RateLimiter
import com.gemini.krakenbot.test.TestConstants
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.util.*

abstract class KrakenServiceTestBase : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    protected lateinit var configService: ConfigService

    /** Records each [RateLimiter.acquireWithCost] argument for CQ-3-22 assertions. */
    protected class RecordingRateLimiter :
        RateLimiter(
            safeLimit = 100.0,
            decayRate = 0.0,
            clock = { 0L },
        ) {
        val acquiredCosts = mutableListOf<Double>()

        override suspend fun acquireWithCost(cost: Double): Double {
            acquiredCosts += cost
            return super.acquireWithCost(cost)
        }
    }

    protected fun createService(
        responseContent: String,
        rateLimiter: RateLimiter = RateLimiter(),
        onRequest: (HttpRequestData) -> Unit = {},
    ): KrakenService {
        val objectMapper = jacksonObjectMapper()
        configService = mockk(relaxed = true)

        val credentials = KrakenCredentials(
            apiKey = TestConstants.API_KEY,
            privateKey = Base64.getEncoder()
                .encodeToString(TestConstants.API_SECRET.toByteArray()),
        )
        val settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L)
        val config = AppConfig(
            kraken = credentials,
            settings = settings,
            allocations = listOf(
                Allocation(Asset.BTC, 50.0),
                Allocation(Asset.ETH, 50.0),
            ),
        )
        every { configService.getConfig() } returns config

        val mockEngine = MockEngine { request ->
            onRequest(request)
            respond(
                content = responseContent,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
            )
        }
        val httpClient = HttpClient(mockEngine)
        return KrakenServiceImpl(configService, objectMapper, httpClient, rateLimiter)
    }
}
