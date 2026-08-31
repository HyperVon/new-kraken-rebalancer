package com.gemini.krakenbot.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.OrderType
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import com.gemini.krakenbot.test.TestConstants
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.util.*

class AddOrderRateLimitSigningTest : KrakenServiceTestBase() {

    private companion object {
        const val SUCCESS_BODY =
            "{\"error\":[],\"result\":{\"descr\":{\"order\":\"buy 0.1 XBTUSD @ market\"},\"txid\":[\"TX-ABC\"]}}"
        const val RATE_LIMIT_BODY = "{\"error\":[\"EAPI:Rate limit exceeded\"]}"
        const val NONCE_REGEX = """(?:^|&)""" + KrakenApiConstants.PARAM_NONCE + """=([^&]+)"""
    }

    private fun wiredService(
        limiter: RecordingRateLimiter = RecordingRateLimiter(),
        respondBodies: List<String> = listOf(SUCCESS_BODY),
    ): Pair<KrakenServiceImpl, MutableList<String>> {
        val objectMapper = jacksonObjectMapper()
        configService = mockk(relaxed = true)
        val credentials = KrakenCredentials(
            apiKey = TestConstants.API_KEY,
            privateKey = Base64.getEncoder().encodeToString(TestConstants.API_SECRET.toByteArray()),
        )
        every { configService.getConfig() } returns AppConfig(
            kraken = credentials,
            settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
            allocations = emptyList(),
        )
        var callIndex = 0
        val capturedBodies = mutableListOf<String>()
        val mockEngine = MockEngine { request ->
            capturedBodies += (request.body as TextContent).text
            val body = respondBodies[callIndex.coerceAtMost(respondBodies.lastIndex)]
            callIndex++
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
            )
        }
        val service = KrakenServiceImpl(configService, objectMapper, HttpClient(mockEngine), limiter)
        return service to capturedBodies
    }

    init {
        "executeOrder_live_addOrder_does_not_charge_history_counter_and_carries_apiKey_apiSign_and_nonce" {
            runTest {
                val limiter = RecordingRateLimiter()
                var capturedApiKey: String? = null
                var capturedApiSign: String? = null
                var capturedContentType: ContentType? = null
                var capturedBody = ""
                configService = mockk(relaxed = true)
                val credentials = KrakenCredentials(
                    apiKey = TestConstants.API_KEY,
                    privateKey = Base64.getEncoder().encodeToString(TestConstants.API_SECRET.toByteArray()),
                )
                every { configService.getConfig() } returns AppConfig(
                    kraken = credentials,
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                val mockEngine = MockEngine { request ->
                    capturedApiKey = request.headers[KrakenApiConstants.HEADER_API_KEY]
                    capturedApiSign = request.headers[KrakenApiConstants.HEADER_API_SIGN]
                    val body = request.body as TextContent
                    capturedContentType = body.contentType
                    capturedBody = body.text
                    respond(
                        content = SUCCESS_BODY,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                    )
                }
                val service = KrakenServiceImpl(
                    configService,
                    jacksonObjectMapper(),
                    HttpClient(mockEngine),
                    limiter,
                )

                val result = service.executeOrder(
                    pair = Asset.BTC_USD_PAIR,
                    type = OrderType.MARKET.apiValue,
                    side = OrderSide.BUY.apiValue,
                    volume = BigDecimal("0.1"),
                    dryRun = false,
                )

                result.success.shouldBeTrue()
                limiter.acquiredCosts shouldBe emptyList()
                capturedApiKey shouldBe TestConstants.API_KEY
                capturedApiSign.shouldNotBeNull()
                capturedContentType?.toString() shouldBe KrakenApiConstants.CONTENT_TYPE_FORM_URLENCODED
                capturedBody shouldContain "${KrakenApiConstants.PARAM_NONCE}="
            }
        }

        "executeOrder_two_consecutive_live_addOrders_receive_strictly_increasing_nonces" {
            runTest {
                val (service, capturedBodies) = wiredService(respondBodies = listOf(SUCCESS_BODY, SUCCESS_BODY))

                service.executeOrder(
                    pair = Asset.BTC_USD_PAIR,
                    type = OrderType.MARKET.apiValue,
                    side = OrderSide.BUY.apiValue,
                    volume = BigDecimal("0.1"),
                    dryRun = false,
                )
                service.executeOrder(
                    pair = Asset.BTC_USD_PAIR,
                    type = OrderType.MARKET.apiValue,
                    side = OrderSide.SELL.apiValue,
                    volume = BigDecimal("0.2"),
                    dryRun = false,
                )

                val nonces = capturedBodies
                    .mapNotNull { Regex(NONCE_REGEX).find(it)?.groupValues?.get(1) }
                nonces shouldHaveSize 2
                (nonces[0].toBigInteger() < nonces[1].toBigInteger()).shouldBeTrue()
            }
        }

        "executeOrder_rate_limit_exceeded_EAPI_error_is_NOT_retried_even_though_nonAddOrder_paths_would_retry" {
            runTest {
                var requestCount = 0
                configService = mockk(relaxed = true)
                val credentials = KrakenCredentials(
                    apiKey = TestConstants.API_KEY,
                    privateKey = Base64.getEncoder().encodeToString(TestConstants.API_SECRET.toByteArray()),
                )
                every { configService.getConfig() } returns AppConfig(
                    kraken = credentials,
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                val mockEngine = MockEngine { _ ->
                    requestCount++
                    respond(
                        content = RATE_LIMIT_BODY,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                    )
                }
                val service = KrakenServiceImpl(
                    configService,
                    jacksonObjectMapper(),
                    HttpClient(mockEngine),
                )

                val result = service.executeOrder(
                    pair = Asset.BTC_USD_PAIR,
                    type = OrderType.MARKET.apiValue,
                    side = OrderSide.BUY.apiValue,
                    volume = BigDecimal("0.1"),
                    dryRun = false,
                )

                result.success.shouldBeFalse()
                // A structured `EAPI:Rate limit exceeded` body with HTTP 200 is a definitive pre-acceptance
                // rejection at Kraken's edge, not an ambiguous post-acceptance outcome — classifying it as
                // UNCERTAIN would falsely block all future live orders. Pin the classification so a future
                // widening of `isAmbiguousSubmissionFailure` (KrakenServiceImpl.kt:273-279) cannot silently
                // over-block live trading without this test catching it.
                result.submissionUncertain.shouldBeFalse()
                requestCount shouldBe 1
            }
        }
    }
}
