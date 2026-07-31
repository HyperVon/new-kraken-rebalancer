@file:OptIn(ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.OrderType
import com.gemini.krakenbot.service.impl.KrakenApiConstants
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import com.gemini.krakenbot.service.impl.RateLimiter
import com.gemini.krakenbot.service.impl.krakenPrivateEndpointCost
import com.gemini.krakenbot.test.TestConstants
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.math.BigDecimal
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.minutes

class KrakenServiceTest : KrakenServiceTestBase() {

    init {
        "getBalances_Success" {
            runTest {
                val responseJson =
                    "{\"error\":[],\"result\":{\"XXBTZUSD\":63000.0,\"XETHZUSD\":3000.0,\"USD\":5000.0}}"
                val service = createService(responseJson)

                val balances = service.getBalances()

                balances[TestFixtures.XXBTZUSD]!!.shouldBeEqualComparingTo(BigDecimal("63000.0"))
                balances["XETHZUSD"]!!.shouldBeEqualComparingTo(BigDecimal("3000.0"))
                balances["USD"]!!.shouldBeEqualComparingTo(BigDecimal("5000.0"))
            }
        }

        "getTickerPrices_Success" {
            runTest {
                val responseJson =
                    "{\"error\":[],\"result\":{\"XXBTZUSD\":{\"c\":[\"65000.0\"]},\"XETHZUSD\":{\"c\":[\"3200.0\"]}}}"
                val service = createService(responseJson)

                val prices = service.getTickerPrices("XXBTZUSD,XETHZUSD")

                prices[TestFixtures.XXBTZUSD]!!.shouldBeEqualComparingTo(BigDecimal("65000.0"))
                prices["XETHZUSD"]!!.shouldBeEqualComparingTo(BigDecimal("3200.0"))
            }
        }

        "executeOrder_Success" {
            runTest {
                val responseJson =
                    "{\"error\":[],\"result\":{\"descr\":{\"order\":\"buy 0.1 XBTUSD @ limit 50000\"},\"txid\":[\"THVR-...-TC\"]}}"
                val service = createService(responseJson)

                val result = service.executeOrder(
                    pair = Asset.BTC_USD_PAIR,
                    type = OrderType.MARKET.apiValue,
                    side = OrderSide.BUY.apiValue,
                    volume = BigDecimal("0.1"),
                )
                result.success.shouldBeTrue()
                result.orderTxid shouldBe "THVR-...-TC"
            }
        }

        "executeOrder_IncludesClOrdIdInAddOrderBody" {
            runTest {
                val responseJson =
                    "{\"error\":[],\"result\":{\"descr\":{\"order\":\"buy 0.1 XBTUSD @ market\"},\"txid\":[\"TX-CLORD\"]}}"
                var capturedBody = ""
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                val credentials = KrakenCredentials(
                    apiKey = TestConstants.API_KEY,
                    privateKey = Base64.getEncoder()
                        .encodeToString(TestConstants.API_SECRET.toByteArray()),
                )
                val settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L)
                every { configService.getConfig() } returns AppConfig(
                    kraken = credentials,
                    settings = settings,
                    allocations = emptyList(),
                )
                val mockEngine = MockEngine { request ->
                    capturedBody = (request.body as TextContent).text
                    respond(
                        content = responseJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                    )
                }
                val service = KrakenServiceImpl(configService, objectMapper, HttpClient(mockEngine))
                val clOrdId = "6d1b345e-2821-40e2-ad83-4ecb18a06876"

                val result = service.executeOrder(
                    pair = Asset.BTC_USD_PAIR,
                    type = OrderType.MARKET.apiValue,
                    side = OrderSide.BUY.apiValue,
                    volume = BigDecimal("0.1"),
                    clOrdId = clOrdId,
                )

                result.success.shouldBeTrue()
                capturedBody.contains("${KrakenApiConstants.PARAM_CL_ORD_ID}=$clOrdId").shouldBeTrue()
                capturedBody.contains("userref=").shouldBeFalse()
            }
        }

        "executeOrder_DryRun" {
            runTest {
                val service = createService("")
                val settings = TestFixtures.settings(loopDelaySeconds = 60L)
                val config = TestFixtures.config(
                    settings = settings,
                )
                every { configService.getConfig() } returns config

                val result = service.executeOrder(
                    pair = Asset.BTC_USD_PAIR,
                    type = OrderType.MARKET.apiValue,
                    side = OrderSide.BUY.apiValue,
                    volume = BigDecimal("0.1"),
                )
                result.success.shouldBeTrue()
                result.dryRun.shouldBeTrue()
            }
        }

        "getTickerPrices_Malformed" {
            runTest {
                val responseJson =
                    "{\"error\":[],\"result\":{\"XXBTZUSD\":{\"c\":[]}, \"XETHZUSD\":{}}}"
                val service = createService(responseJson)

                val prices = service.getTickerPrices("XXBTZUSD,XETHZUSD")
                prices.isEmpty().shouldBeTrue()
            }
        }

        "queryPublic_ErrorResponse" {
            runTest {
                val responseJson = "{\"error\":[\"EQuery:Unknown asset pair\"]}"
                val service = createService(responseJson)

                shouldThrow<RuntimeException> {
                    service.getTickerPrices("INVALID")
                }
            }
        }

        "queryPublic_JsonProcessingException" {
            runTest {
                val service = createService("{invalid-json")
                shouldThrow<RuntimeException> {
                    service.getTickerPrices(TestFixtures.XBTUSD)
                }
            }
        }

        "executeOrder_ApiError" {
            runTest {
                val responseJson = "{\"error\":[\"EOrder:Insufficient funds\"]}"
                val service = createService(responseJson)

                val result = service.executeOrder(
                    pair = TestFixtures.XBTUSD,
                    type = "limit",
                    side = "buy",
                    volume = BigDecimal.ONE,
                )
                result.success.shouldBeFalse()
                result.errorMessage.shouldNotBeNull()
                result.submissionUncertain shouldBe false
            }
        }

        "executeOrder_TransportFailureIsUncertainAndIsNotRetried" {
            runTest {
                var requestCount = 0
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(
                        apiKey = "public-key",
                        privateKey = Base64.getEncoder().encodeToString("secret-key".toByteArray()),
                    ),
                    settings = Settings(60L, 2.0, dryRun = false),
                    allocations = emptyList(),
                )
                val service = KrakenServiceImpl(
                    configService,
                    objectMapper,
                    HttpClient(
                        MockEngine {
                            requestCount++
                            throw IOException("response lost after acceptance")
                        },
                    ),
                )

                val result = service.executeOrder(
                    pair = TestFixtures.XBTUSD,
                    type = OrderType.MARKET.apiValue,
                    side = OrderSide.BUY.apiValue,
                    volume = BigDecimal.ONE,
                )

                result.success.shouldBeFalse()
                result.submissionUncertain shouldBe true
                requestCount shouldBe 1
            }
        }

        "executeOrder_InvalidNonceIsUncertainAndIsNotRetried" {
            runTest {
                var requestCount = 0
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(
                        apiKey = "public-key",
                        privateKey = Base64.getEncoder().encodeToString("secret-key".toByteArray()),
                    ),
                    settings = Settings(60L, 2.0, dryRun = false),
                    allocations = emptyList(),
                )
                val service = KrakenServiceImpl(
                    configService,
                    objectMapper,
                    HttpClient(
                        MockEngine {
                            requestCount++
                            respond(
                                content = "{\"error\":[\"EAPI:Invalid nonce\"]}",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                            )
                        },
                    ),
                )

                val result = service.executeOrder(
                    pair = TestFixtures.XBTUSD,
                    type = OrderType.MARKET.apiValue,
                    side = OrderSide.BUY.apiValue,
                    volume = BigDecimal.ONE,
                )

                result.success.shouldBeFalse()
                result.submissionUncertain shouldBe true
                requestCount shouldBe 1
            }
        }

        "executeOrder_MissingTxidIsUncertain" {
            runTest {
                val service = createService("{\"error\":[],\"result\":{}}")

                val result = service.executeOrder(
                    pair = TestFixtures.XBTUSD,
                    type = OrderType.MARKET.apiValue,
                    side = OrderSide.BUY.apiValue,
                    volume = BigDecimal.ONE,
                )

                result.success.shouldBeFalse()
                result.submissionUncertain shouldBe true
            }
        }

        "executeOrder_ServerErrorJsonIsUncertain" {
            runTest {
                var requestCount = 0
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(
                        apiKey = "public-key",
                        privateKey = Base64.getEncoder().encodeToString("secret-key".toByteArray()),
                    ),
                    settings = Settings(60L, 2.0, dryRun = false),
                    allocations = emptyList(),
                )
                val service = KrakenServiceImpl(
                    configService,
                    objectMapper,
                    HttpClient(
                        MockEngine {
                            requestCount++
                            respond(
                                content = "{\"error\":[\"EService:Temporary lockout\"]}",
                                status = HttpStatusCode.InternalServerError,
                                headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                            )
                        },
                    ),
                )

                val result = service.executeOrder(
                    pair = TestFixtures.XBTUSD,
                    type = OrderType.MARKET.apiValue,
                    side = OrderSide.BUY.apiValue,
                    volume = BigDecimal.ONE,
                )

                result.success.shouldBeFalse()
                result.submissionUncertain shouldBe true
                requestCount shouldBe 1
            }
        }

        "executeOrder_ExceptionWithNullMessage" {
            runTest {
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                val credentials = KrakenCredentials(
                    apiKey = "public-key",
                    privateKey = Base64.getEncoder()
                        .encodeToString("secret-key".toByteArray()),
                )
                val settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L)
                val config = AppConfig(
                    kraken = credentials,
                    settings = settings,
                    allocations = emptyList(),
                )
                every { configService.getConfig() } returns config

                val mockEngine = MockEngine {
                    throw RuntimeException(null as String?)
                }
                val httpClient = HttpClient(mockEngine)
                val service =
                    KrakenServiceImpl(configService, objectMapper, httpClient)

                val result = service.executeOrder(
                    pair = TestFixtures.XBTUSD,
                    type = "limit",
                    side = "buy",
                    volume = BigDecimal.ONE,
                )
                result.success.shouldBeFalse()
                result.errorMessage shouldBe "RuntimeException"
            }
        }

        "queryPrivate_JsonProcessingException" {
            runTest {
                val service = createService("{broken-json")
                shouldThrow<RuntimeException> { service.getBalances() }
            }
        }

        "testNonceGeneration_Concurrency" {
            val service = createService("{}")
            val nonceGen =
                service::class.java.getDeclaredField("nonceGenerator")
                    .apply { isAccessible = true }.get(service)
                    as AtomicLong
            nonceGen.shouldNotBeNull()

            val numThreads = 10
            val incrementsPerThread = 1000
            val generatedNonces =
                Collections.synchronizedSet(mutableSetOf<Long>())

            val executor = Executors.newFixedThreadPool(numThreads)
            val latch = CountDownLatch(numThreads)

            repeat(numThreads) {
                executor.submit {
                    try {
                        repeat(incrementsPerThread) {
                            generatedNonces.add(nonceGen.incrementAndGet())
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }

            latch.await(5, TimeUnit.SECONDS).shouldBeTrue()
            executor.shutdown()

            generatedNonces.size shouldBe (numThreads * incrementsPerThread)
        }

        "queryPrivate_ApiKeyNull" {
            runTest {
                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val config = AppConfig(
                    kraken = KrakenCredentials(
                        apiKey = "",
                        privateKey = TestFixtures.SECRET,
                    ),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                every { mockConfigService.getConfig() } returns config

                val mockEngine = MockEngine { respond(content = "") }
                val localService = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(mockEngine),
                )

                val ex =
                    shouldThrow<RuntimeException> { localService.getBalances() }
                ex.message shouldBe "API Key is null"
            }
        }

        "queryPublic_NullResponse" {
            runTest {
                val service = createService("{}")
                val prices = service.getTickerPrices("BTCUSD")
                prices.isEmpty().shouldBeTrue()
            }
        }

        "queryPrivate_NullResponse" {
            runTest {
                val service = createService("{}")
                val balances = service.getBalances()
                balances.isEmpty().shouldBeTrue()
            }
        }

        "queryPrivate_InvalidPrivateKeyBase64" {
            runTest {
                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val config = AppConfig(
                    kraken = KrakenCredentials(
                        apiKey = "apiKey",
                        privateKey = "invalid_base64_!@#$",
                    ),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                every { mockConfigService.getConfig() } returns config

                val mockEngine = MockEngine { respond(content = "") }
                val localService = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(mockEngine),
                )

                shouldThrow<RuntimeException> { localService.getBalances() }
            }
        }

        "queryPrivate_InvalidNonce_RetrySuccess" {
            runTest {
                val errorJson = "{\"error\":[\"EAPI:Invalid nonce\"]}"
                val successJson =
                    "{\"error\":[],\"result\":{\"XXBTZUSD\":63000.0}}"
                var attempt = 0
                val mockEngine = MockEngine { _ ->
                    val content = if (attempt++ == 0) errorJson else successJson
                    respond(
                        content = content,
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            TestFixtures.APPLICATION_JSON,
                        ),
                    )
                }

                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val validSecret =
                    Base64.getEncoder().encodeToString(TestFixtures.SECRET.toByteArray())
                val config = AppConfig(
                    kraken = KrakenCredentials(
                        apiKey = "k",
                        privateKey = validSecret,
                    ),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                every { mockConfigService.getConfig() } returns config

                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(mockEngine),
                )

                val balances = service.getBalances()
                balances[TestFixtures.XXBTZUSD]!!.shouldBeEqualComparingTo(BigDecimal("63000.0"))
            }
        }

        "queryPrivate_InvalidNonce_RetryExceeded" {
            runTest {
                val errorJson = "{\"error\":[\"EAPI:Invalid nonce\"]}"
                val mockEngine = MockEngine {
                    respond(
                        content = errorJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            TestFixtures.APPLICATION_JSON,
                        ),
                    )
                }

                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val validSecret =
                    Base64.getEncoder().encodeToString(TestFixtures.SECRET.toByteArray())
                val config = AppConfig(
                    kraken = KrakenCredentials(
                        apiKey = "k",
                        privateKey = validSecret,
                    ),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                every { mockConfigService.getConfig() } returns config

                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(mockEngine),
                )

                val ex = shouldThrow<RuntimeException> { service.getBalances() }
                ex.message?.contains("Invalid nonce")?.shouldBeTrue()
            }
        }
    }
}
