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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.minutes

class KrakenServiceTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private lateinit var configService: ConfigService

    /** Records each [RateLimiter.acquireWithCost] argument for CQ-3-22 assertions. */
    private class RecordingRateLimiter :
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

    private fun createService(responseContent: String, rateLimiter: RateLimiter = RateLimiter()): KrakenService {
        val objectMapper = jacksonObjectMapper()
        configService = mockk(relaxed = true)

        val credentials = KrakenCredentials(
            apiKey = TestConstants.API_KEY,
            privateKey = Base64.getEncoder()
                .encodeToString(TestConstants.API_SECRET.toByteArray()),
        )
        val settings = Settings(
            loopDelaySeconds = 60L,
            deviationTriggerPercent = 2.0,
            dustThresholdUSD = 1.0,
            dryRun = false,
            fiatMaxDrawdown = 0.0,
            fiatDeploymentExponent = 1.0,
        )
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
            respond(
                content = responseContent,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
            )
        }
        val httpClient = HttpClient(mockEngine)
        return KrakenServiceImpl(configService, objectMapper, httpClient, rateLimiter)
    }

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
            }
        }

        "executeOrder_DryRun" {
            runTest {
                val service = createService("")
                val settings = Settings(
                    loopDelaySeconds = 60L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
                val config = AppConfig(
                    kraken = KrakenCredentials(apiKey = "k", privateKey = "s"),
                    settings = settings,
                    allocations = emptyList(),
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
                val settings = Settings(
                    loopDelaySeconds = 60L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = false,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
                val config = AppConfig(
                    kraken = credentials,
                    settings = settings,
                    allocations = emptyList(),
                )
                every { configService.getConfig() } returns config

                val mockEngine = MockEngine { request ->
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
                    settings = Settings(
                        loopDelaySeconds = 60L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
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
                    settings = Settings(
                        loopDelaySeconds = 60L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
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
                    settings = Settings(
                        loopDelaySeconds = 60L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
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
                val mockEngine = MockEngine { request ->
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
                    settings = Settings(
                        loopDelaySeconds = 60L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
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

        "getTradeHistory_Success" {
            runTest {
                val responseJson = """
                    {
                        "error": [],
                        "result": {
                            "trades": {
                                "T1": {
                                    "ordertxid": "O1",
                                    "pair": "XXBTZUSD",
                                    "time": 1700000000.1234,
                                    "type": "buy",
                                    "ordertype": "market",
                                    "price": "50000.00",
                                    "cost": "5000.00",
                                    "fee": "10.00",
                                    "vol": "0.10000000",
                                    "margin": "0.0",
                                    "misc": ""
                                }
                            },
                            "count": 1
                        }
                    }
                """.trimIndent()
                val service = createService(responseJson)
                val trades = service.getTradeHistory()

                trades.size shouldBe 1
                val first = trades.first()
                first.pair shouldBe TestFixtures.XXBTZUSD
                first.side shouldBe "BUY"
                first.symbol shouldBe "BTC"
                first.volume.compareTo(BigDecimal("0.1")) shouldBe 0
                first.usdAmount.compareTo(BigDecimal("5000.00")) shouldBe 0
                first.timestamp.toEpochMilli() shouldBe 1700000000123L
            }
        }

        "getTradeHistory_BlankApiKey" {
            runTest {
                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val config = AppConfig(
                    kraken = KrakenCredentials("", ""),
                    settings = Settings(
                        loopDelaySeconds = 60L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    allocations = emptyList(),
                )
                every { mockConfigService.getConfig() } returns config

                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(MockEngine { respond("") }),
                )

                val trades = service.getTradeHistory()
                trades.isEmpty().shouldBeTrue()
            }
        }

        "getTradeHistory_PlaceholderApiKey" {
            runTest {
                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val config = AppConfig(
                    kraken = KrakenCredentials(
                        KrakenCredentials.PLACEHOLDER_API_KEY,
                        KrakenCredentials.PLACEHOLDER_PRIVATE_KEY,
                    ),
                    settings = Settings(
                        loopDelaySeconds = 60L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    allocations = emptyList(),
                )
                every { mockConfigService.getConfig() } returns config

                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(MockEngine { respond("") }),
                )

                val trades = service.getTradeHistory()
                trades.isEmpty().shouldBeTrue()
            }
        }

        "getTradeHistory_MalformedPrivateKey" {
            runTest {
                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val config = AppConfig(
                    kraken = KrakenCredentials(
                        apiKey = "apiKey",
                        privateKey = "invalid_base64_!@#$",
                    ),
                    settings = Settings(
                        loopDelaySeconds = 60L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    allocations = emptyList(),
                )
                every { mockConfigService.getConfig() } returns config

                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(MockEngine { respond("") }),
                )

                val trades = service.getTradeHistory()
                trades.isEmpty().shouldBeTrue()
            }
        }

        "getTradeHistory_FallbackSymbols" {
            runTest {
                val responseJson = """
                    {
                        "error": [],
                        "result": {
                            "trades": {
                                "T1": {
                                    "ordertxid": "O1",
                                    "pair": "XETHZUSD",
                                    "time": 1700000000.1234,
                                    "type": "buy",
                                    "ordertype": "market",
                                    "price": "2000.00",
                                    "cost": "200.00",
                                    "fee": "1.00",
                                    "vol": "0.10000000",
                                    "margin": "0.0",
                                    "misc": ""
                                },
                                "T2": {
                                    "ordertxid": "O2",
                                    "pair": "XXDGZUSD",
                                    "time": 1700000005.1234,
                                    "type": "sell",
                                    "ordertype": "market",
                                    "price": "0.10",
                                    "cost": "10.00",
                                    "fee": "0.10",
                                    "vol": "100.00000000",
                                    "margin": "0.0",
                                    "misc": ""
                                },
                                "T3": {
                                    "ordertxid": "O3",
                                    "pair": "XLTCZUSD",
                                    "time": 1700000010.1234,
                                    "type": "buy",
                                    "ordertype": "market",
                                    "price": "100.00",
                                    "cost": "100.00",
                                    "fee": "0.50",
                                    "vol": "1.00000000",
                                    "margin": "0.0",
                                    "misc": ""
                                }
                            },
                            "count": 3
                        }
                    }
                """.trimIndent()
                val service = createService(responseJson)
                val trades = service.getTradeHistory()

                // ETH and DOGE should succeed, LTC should be skipped
                trades.size shouldBe 2

                val ethTrade = trades.first { it.pair == "XETHZUSD" }
                ethTrade.symbol shouldBe "ETH"
                ethTrade.volume.compareTo(BigDecimal("0.1")) shouldBe 0
                ethTrade.usdAmount.compareTo(BigDecimal("200.00")) shouldBe 0

                val dogeTrade = trades.first { it.pair == "XXDGZUSD" }
                dogeTrade.symbol shouldBe "DOGE"
                dogeTrade.volume.compareTo(BigDecimal("100")) shouldBe 0
                dogeTrade.usdAmount.compareTo(BigDecimal("10.00")) shouldBe 0
            }
        }

        "getTradeHistory_InvalidJson" {
            runTest {
                val responseJson = """
                    {
                        "error": [],
                        "result": {
                            "trades": "not_an_object",
                            "count": 0
                        }
                    }
                """.trimIndent()
                val service = createService(responseJson)
                val trades = service.getTradeHistory()
                trades.isEmpty().shouldBeTrue()
            }
        }

        "getTradeHistory_QueryPrivateException" {
            runTest {
                val errorJson = "{\"error\":[\"EGeneral:Internal Error\"]}"
                val mockEngine = MockEngine { request ->
                    respond(
                        content = errorJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                    )
                }

                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val credentials = KrakenCredentials(
                    "api-key",
                    Base64.getEncoder().encodeToString(TestFixtures.SECRET.toByteArray()),
                )
                val config = AppConfig(
                    kraken = credentials,
                    settings = Settings(
                        loopDelaySeconds = 60L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    allocations = emptyList(),
                )
                every { mockConfigService.getConfig() } returns config

                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(mockEngine),
                )

                shouldThrow<RuntimeException> {
                    service.getTradeHistory()
                }
            }
        }

        "getTradeHistory_WithStartAndOffset" {
            runTest {
                var capturedBody: String? = null
                val mockEngine = MockEngine { request ->
                    capturedBody = (request.body as TextContent).text
                    respond(
                        content = """{"error":[],"result":{"trades":{}}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                    )
                }

                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val credentials = KrakenCredentials(
                    "api-key",
                    Base64.getEncoder().encodeToString(TestFixtures.SECRET.toByteArray()),
                )
                val config = AppConfig(
                    kraken = credentials,
                    settings = Settings(
                        loopDelaySeconds = 60L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    allocations = emptyList(),
                )
                every { mockConfigService.getConfig() } returns config

                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(mockEngine),
                )

                service.getTradeHistory(startSec = 1700000000L, offset = 50)

                capturedBody.shouldNotBeNull()
                capturedBody.contains("start=1700000000") shouldBe true
                capturedBody.contains("ofs=50") shouldBe true
            }
        }

        "getTradeHistory_InvalidNumericValues" {
            runTest {
                val responseJson = """
                    {
                        "error": [],
                        "result": {
                            "trades": {
                                "T1": {
                                    "ordertxid": "O1",
                                    "pair": "XXBTZUSD",
                                    "time": 1700000000.1234,
                                    "type": "buy",
                                    "ordertype": "market",
                                    "price": "invalid_price",
                                    "cost": "invalid_cost",
                                    "fee": "invalid_fee",
                                    "vol": "invalid_vol",
                                    "margin": "0.0",
                                    "misc": ""
                                }
                            },
                            "count": 1
                        }
                    }
                """.trimIndent()
                val service = createService(responseJson)
                val trades = service.getTradeHistory()

                trades.size shouldBe 1
                val first = trades.first()
                first.volume.compareTo(BigDecimal.ZERO) shouldBe 0
                first.usdAmount.compareTo(BigDecimal.ZERO) shouldBe 0
            }
        }

        "getTradeHistory_FallbackSymbols_HardcodedBtcEthDoge" {
            runTest {
                // Allocations list deliberately DOES NOT contain BTC, ETH, or DOGE
                // so the hardcoded fallback paths in parseSymbolFromPair are exercised
                val responseJson = """
                    {
                        "error": [],
                        "result": {
                            "trades": {
                                "T1": {
                                    "pair": "XXBTZUSD",
                                    "time": 1700000000.1234,
                                    "type": "buy",
                                    "price": "50000.00",
                                    "cost": "5000.00",
                                    "vol": "0.10000000"
                                },
                                "T2": {
                                    "pair": "XETHZUSD",
                                    "time": 1700000001.1234,
                                    "type": "sell",
                                    "price": "2000.00",
                                    "cost": "200.00",
                                    "vol": "0.10000000"
                                },
                                "T3": {
                                    "pair": "XXDGZUSD",
                                    "time": 1700000002.1234,
                                    "type": "buy",
                                    "price": "0.10",
                                    "cost": "10.00",
                                    "vol": "100.00000000"
                                },
                                "T4": {
                                    "pair": "XLTCZUSD",
                                    "time": 1700000003.1234,
                                    "type": "buy",
                                    "price": "100.00",
                                    "cost": "100.00",
                                    "vol": "1.00000000"
                                }
                            }
                        }
                    }
                """.trimIndent()

                val objectMapper = jacksonObjectMapper()
                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val credentials = KrakenCredentials(
                    apiKey = "api-key",
                    privateKey = Base64.getEncoder().encodeToString(TestFixtures.SECRET.toByteArray()),
                )
                // Allocations with SOL only — no BTC, ETH, or DOGE in the list
                val config = AppConfig(
                    kraken = credentials,
                    settings = Settings(
                        loopDelaySeconds = 60L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    allocations = listOf(
                        Allocation("SOL", 100.0),
                    ),
                )
                every { mockConfigService.getConfig() } returns config

                val mockEngine = MockEngine {
                    respond(
                        content = responseJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                    )
                }
                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = objectMapper,
                    httpClient = HttpClient(mockEngine),
                )

                val trades = service.getTradeHistory()

                // BTC, ETH, and DOGE should be resolved via hardcoded fallbacks; LTC should be filtered out
                trades.size shouldBe 3
                trades.any { it.symbol == "BTC" }.shouldBeTrue()
                trades.any { it.symbol == "ETH" }.shouldBeTrue()
                trades.any { it.symbol == "DOGE" }.shouldBeTrue()
            }
        }

        "getTradeHistory_CalledWithDefaults" {
            runTest {
                // This test exercises the KrakenService interface default parameter values
                // (the $DefaultImpls class generated by Kotlin for default params)
                val responseJson = """{"error":[],"result":{"trades":{}}}"""
                val service: KrakenService = createService(responseJson)
                val trades = service.getTradeHistory()
                trades.isEmpty().shouldBeTrue()
            }
        }

        "getTradeHistory_FallbackSymbols_AlternativePairNames" {
            runTest {
                // Tests the || second branches: "BTC" (not XBT), "DOGE" (not XDG)
                val responseJson = """
                    {
                        "error": [],
                        "result": {
                            "trades": {
                                "T1": {
                                    "pair": "BTCUSD",
                                    "time": 1700000000.1234,
                                    "type": "buy",
                                    "price": "50000.00",
                                    "cost": "5000.00",
                                    "vol": "0.10000000"
                                },
                                "T2": {
                                    "pair": "DOGEUSD",
                                    "time": 1700000001.1234,
                                    "type": "sell",
                                    "price": "0.10",
                                    "cost": "10.00",
                                    "vol": "100.00000000"
                                }
                            }
                        }
                    }
                """.trimIndent()

                val objectMapper = jacksonObjectMapper()
                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val credentials = KrakenCredentials(
                    apiKey = "api-key",
                    privateKey = Base64.getEncoder().encodeToString(TestFixtures.SECRET.toByteArray()),
                )
                // No relevant allocations — forces fallback paths
                val config = AppConfig(
                    kraken = credentials,
                    settings = Settings(
                        loopDelaySeconds = 60L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    allocations = listOf(
                        Allocation("SOL", 100.0),
                    ),
                )
                every { mockConfigService.getConfig() } returns config

                val mockEngine = MockEngine {
                    respond(
                        content = responseJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                    )
                }
                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = objectMapper,
                    httpClient = HttpClient(mockEngine),
                )

                val trades = service.getTradeHistory()
                trades.size shouldBe 2
                trades.any { it.symbol == "BTC" && it.pair == "BTCUSD" }.shouldBeTrue()
                trades.any { it.symbol == "DOGE" && it.pair == "DOGEUSD" }.shouldBeTrue()
            }
        }

        "getTradeHistory_AllocationMatchesViaSymbolUppercase" {
            runTest {
                // Tests line 194: the normalizedPair.contains(symbol.uppercase()) branch
                // When the pair contains the symbol name but not the Kraken ticker
                // e.g., pair "BTCUSD" with allocation "BTC" — ticker is "XBT" which won't match,
                // but "BTC" (symbol.uppercase()) will match
                val responseJson = """
                    {
                        "error": [],
                        "result": {
                            "trades": {
                                "T1": {
                                    "pair": "BTCUSD",
                                    "time": 1700000000.1234,
                                    "type": "buy",
                                    "price": "50000.00",
                                    "cost": "5000.00",
                                    "vol": "0.10000000"
                                }
                            }
                        }
                    }
                """.trimIndent()

                // BTC is in the allocations list, so the allocation-level match is exercised
                // The pair "BTCUSD" contains "BTC" (symbol.uppercase()) but NOT "XBT" (ticker)
                val service = createService(responseJson)
                val trades = service.getTradeHistory()
                trades.size shouldBe 1
                trades.first().symbol shouldBe "BTC"
            }
        }

        "retryOnTransientFailure_SucceedsOnSecondAttempt" {
            runTest {
                var attempt = 0
                val mockEngine = MockEngine { request ->
                    if (attempt++ == 0) {
                        // Rate limit error response
                        respond(
                            content = "{\"error\":[\"EAPI:Rate limit exceeded\"]}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    } else {
                        // Success response
                        respond(
                            content = "{\"error\":[],\"result\":{\"XXBTZUSD\":63000.0}}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    }
                }
                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val credentials = KrakenCredentials(
                    "k",
                    Base64.getEncoder().encodeToString(TestFixtures.SECRET.toByteArray()),
                )
                every { mockConfigService.getConfig() } returns AppConfig(
                    credentials,
                    Settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                    ),
                    emptyList(),
                )

                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(mockEngine),
                )

                val balances = service.getBalances()
                balances[TestFixtures.XXBTZUSD]!!.shouldBeEqualComparingTo(BigDecimal("63000.0"))
                attempt shouldBe 2
            }
        }

        "retryOnTransientFailure_SucceedsOnSecondAttempt_Lockout" {
            runTest {
                var attempt = 0
                val mockEngine = MockEngine { request ->
                    if (attempt++ == 0) {
                        respond(
                            content = "{\"error\":[\"EGeneral:Temporary lockout\"]}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    } else {
                        respond(
                            content = "{\"error\":[],\"result\":{\"XXBTZUSD\":63000.0}}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    }
                }
                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val credentials = KrakenCredentials(
                    "k",
                    Base64.getEncoder().encodeToString(TestFixtures.SECRET.toByteArray()),
                )
                every { mockConfigService.getConfig() } returns AppConfig(
                    credentials,
                    Settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                    ),
                    emptyList(),
                )

                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(mockEngine),
                )

                val balances = service.getBalances()
                balances[TestFixtures.XXBTZUSD]!!.shouldBeEqualComparingTo(BigDecimal("63000.0"))
                attempt shouldBe 2
                // First lockout wait starts at 10s.
                currentTime shouldBe 10_000L
            }
        }

        "retryOnTransientFailure_LockoutBackoffReachesFifteenMinuteCap" {
            runTest {
                var attempt = 0
                val mockEngine = MockEngine { request ->
                    // 8 lockouts then success → waits 10+20+40+80+160+320+640+900s
                    if (attempt++ < 8) {
                        respond(
                            content = "{\"error\":[\"EGeneral:Temporary lockout\"]}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    } else {
                        respond(
                            content = "{\"error\":[],\"result\":{\"XXBTZUSD\":63000.0}}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    }
                }
                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val credentials = KrakenCredentials(
                    "k",
                    Base64.getEncoder().encodeToString(TestFixtures.SECRET.toByteArray()),
                )
                every { mockConfigService.getConfig() } returns AppConfig(
                    credentials,
                    Settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                    ),
                    emptyList(),
                )

                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(mockEngine),
                )

                val balances = service.getBalances()
                balances[TestFixtures.XXBTZUSD]!!.shouldBeEqualComparingTo(BigDecimal("63000.0"))
                attempt shouldBe 9
                // 10+20+40+80+160+320+640 seconds, then the 15-minute cap.
                val expectedWaitMs =
                    (10L + 20 + 40 + 80 + 160 + 320 + 640) * 1_000 +
                        15.minutes.inWholeMilliseconds
                currentTime shouldBe expectedWaitMs
            }
        }

        "retryOnTransientFailure_FailsExhausted" {
            runTest {
                val mockEngine = MockEngine { request ->
                    respond(
                        content = "{\"error\":[\"EAPI:Rate limit exceeded\"]}",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                    )
                }
                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val credentials = KrakenCredentials(
                    "k",
                    Base64.getEncoder().encodeToString(TestFixtures.SECRET.toByteArray()),
                )
                every { mockConfigService.getConfig() } returns AppConfig(
                    credentials,
                    Settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                    ),
                    emptyList(),
                )

                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(mockEngine),
                )

                shouldThrow<RuntimeException> {
                    service.getBalances()
                }
            }
        }

        "retryOnTransientFailure_LockoutFailsExhausted" {
            runTest {
                // maxLockoutAttempts = 9: nine consecutive Temporary lockout responses exhaust retries.
                var attempt = 0
                val mockEngine = MockEngine { request ->
                    attempt++
                    respond(
                        content = "{\"error\":[\"EGeneral:Temporary lockout\"]}",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                    )
                }
                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val credentials = KrakenCredentials(
                    "k",
                    Base64.getEncoder().encodeToString(TestFixtures.SECRET.toByteArray()),
                )
                every { mockConfigService.getConfig() } returns AppConfig(
                    credentials,
                    Settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                    ),
                    emptyList(),
                )

                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(mockEngine),
                )

                shouldThrow<RuntimeException> {
                    service.getBalances()
                }
                attempt shouldBe 9
                // Eight retries: 10+20+40+80+160+320+640s, then the 15-minute cap (no 9th wait).
                val expectedWaitMs =
                    (10L + 20 + 40 + 80 + 160 + 320 + 640) * 1_000 +
                        15.minutes.inWholeMilliseconds
                currentTime shouldBe expectedWaitMs
            }
        }

        "retryOnTransientFailure_SocketTimeoutException_RetrySuccess" {
            runTest {
                var attempt = 0
                val mockEngine = MockEngine { request ->
                    if (attempt++ == 0) {
                        throw SocketTimeoutException("Simulated socket timeout", null)
                    } else {
                        respond(
                            content = "{\"error\":[],\"result\":{\"XXBTZUSD\":63000.0}}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    }
                }
                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val credentials = KrakenCredentials(
                    "k",
                    Base64.getEncoder().encodeToString(TestFixtures.SECRET.toByteArray()),
                )
                every { mockConfigService.getConfig() } returns AppConfig(
                    credentials,
                    Settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                    ),
                    emptyList(),
                )

                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(mockEngine),
                )

                val balances = service.getBalances()
                balances[TestFixtures.XXBTZUSD]!!.shouldBeEqualComparingTo(BigDecimal("63000.0"))
                attempt shouldBe 2
            }
        }

        "retryOnTransientFailure_ClientRequestException_RetrySuccess" {
            runTest {
                var attempt = 0
                val mockEngine = MockEngine { request ->
                    if (attempt++ == 0) {
                        val response = mockk<HttpResponse>(relaxed = true)
                        throw ClientRequestException(response, "Simulated rate limit / error")
                    } else {
                        respond(
                            content = "{\"error\":[],\"result\":{\"XXBTZUSD\":63000.0}}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    }
                }
                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val credentials = KrakenCredentials(
                    "k",
                    Base64.getEncoder().encodeToString(TestFixtures.SECRET.toByteArray()),
                )
                every { mockConfigService.getConfig() } returns AppConfig(
                    credentials,
                    Settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                    ),
                    emptyList(),
                )

                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(mockEngine),
                )

                val balances = service.getBalances()
                balances[TestFixtures.XXBTZUSD]!!.shouldBeEqualComparingTo(BigDecimal("63000.0"))
                attempt shouldBe 2
            }
        }

        "getOHLC_Success" {
            runTest {
                val responseJson = "{\"error\":[],\"result\":{\"XXBTZUSD\":[[" +
                    "1616662800,\"52000.0\",\"53000.0\",\"51000.0\",\"52500.0\",\"52200.0\",\"100.5\",1234]],\"last\":1616835600}}"
                val service = createService(responseJson) as KrakenServiceImpl
                val ohlc = service.getOHLC(TestFixtures.XXBTZUSD, 1440, null)
                ohlc.size shouldBe 1
                ohlc[0].first shouldBe 1616662800L
                ohlc[0].second.shouldBeEqualComparingTo(BigDecimal("52500.0"))
                service.lastFetchedCount.get() shouldBe 0
            }
        }

        "getOHLC_Error" {
            runTest {
                val service = createService("invalid-json")
                val ohlc = service.getOHLC(TestFixtures.XXBTZUSD, 1440, null)
                ohlc.isEmpty().shouldBeTrue()
            }
        }

        "getOHLC_NonObjectResult" {
            runTest {
                val responseJson = "{\"error\":[],\"result\":\"not-an-object\"}"
                val service = createService(responseJson) as KrakenServiceImpl
                val ohlc = service.getOHLC(TestFixtures.XXBTZUSD, 1440, null)
                ohlc.isEmpty().shouldBeTrue()
            }
        }

        "getOHLC_LastFieldFirst" {
            runTest {
                val responseJson = "{\"error\":[],\"result\":{\"last\":1616835600,\"XXBTZUSD\":[[" +
                    "1616662800,\"52000.0\",\"53000.0\",\"51000.0\",\"52500.0\",\"52200.0\",\"100.5\",1234]]}}"
                val service = createService(responseJson) as KrakenServiceImpl
                val ohlc = service.getOHLC(TestFixtures.XXBTZUSD, 1440, null)
                ohlc.size shouldBe 1
            }
        }

        "getOHLC_NullOrNonArrayOhlcNode" {
            runTest {
                val responseJson = "{\"error\":[],\"result\":{\"XXBTZUSD\":\"not-an-array\",\"last\":1616835600}}"
                val service = createService(responseJson) as KrakenServiceImpl
                val ohlc = service.getOHLC(TestFixtures.XXBTZUSD, 1440, null)
                ohlc.isEmpty().shouldBeTrue()
            }
        }

        "getOHLC_InvalidEntries" {
            runTest {
                val responseJson = "{\"error\":[],\"result\":{\"XXBTZUSD\":[\"not-an-array\", " +
                    "[1616662800,\"52000.0\",\"53000.0\",\"51000.0\",\"invalid-price\",\"52200.0\",\"100.5\",1234]]}}"
                val service = createService(responseJson) as KrakenServiceImpl
                val ohlc = service.getOHLC(TestFixtures.XXBTZUSD, 1440, null)
                ohlc.size shouldBe 1
                ohlc[0].second.shouldBeEqualComparingTo(BigDecimal.ZERO)
            }
        }

        "queryPrivate_RateLimiter_ThrottlesCorrectly" {
            runTest {
                var callCount = 0
                val mockEngine = MockEngine { request ->
                    callCount++
                    respond(
                        content = "{\"error\":[],\"result\":{}}",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                    )
                }
                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val credentials = KrakenCredentials(
                    "k",
                    Base64.getEncoder().encodeToString(TestFixtures.SECRET.toByteArray()),
                )
                every { mockConfigService.getConfig() } returns AppConfig(
                    credentials,
                    Settings(
                        loopDelaySeconds = 60,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                    ),
                    emptyList(),
                )

                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(mockEngine),
                )

                repeat(8) {
                    service.getBalances()
                }
                callCount shouldBe 8
            }
        }

        "queryPublic_getTickerPrices_neverAcquiresRateLimiter" {
            runTest {
                val limiter = RecordingRateLimiter()
                val responseJson =
                    "{\"error\":[],\"result\":{\"XXBTZUSD\":{\"c\":[\"65000.0\"]}}}"
                val service = createService(responseJson, limiter)

                service.getTickerPrices(TestFixtures.XXBTZUSD)

                limiter.acquiredCosts shouldBe emptyList()
            }
        }

        "queryPublic_getOHLC_neverAcquiresRateLimiter" {
            runTest {
                val limiter = RecordingRateLimiter()
                val responseJson =
                    "{\"error\":[],\"result\":{\"XXBTZUSD\":[[1700000000,\"1\",\"2\",\"3\",\"50000.0\",\"4\",\"5\",6]],\"last\":1700000000}}"
                val service = createService(responseJson, limiter)

                service.getOHLC(TestFixtures.XXBTZUSD, 1440, null)

                limiter.acquiredCosts shouldBe emptyList()
            }
        }

        "queryPrivate_Balance_acquiresWithCost1" {
            runTest {
                val limiter = RecordingRateLimiter()
                val service = createService("{\"error\":[],\"result\":{\"ZUSD\":\"100.0\"}}", limiter)

                service.getBalances()

                limiter.acquiredCosts shouldBe listOf(1.0)
            }
        }

        "queryPrivate_TradesHistory_acquiresWithCost2" {
            runTest {
                val limiter = RecordingRateLimiter()
                val responseJson = """
                    {"error":[],"result":{"trades":{},"count":0}}
                """.trimIndent()
                val service = createService(responseJson, limiter)

                service.getTradeHistory()

                limiter.acquiredCosts shouldBe listOf(2.0)
            }
        }

        "krakenPrivateEndpointCost_TradesHistory_Ledgers_ClosedOrders_are2" {
            krakenPrivateEndpointCost(KrakenApiConstants.PATH_TRADES_HISTORY) shouldBe 2.0
            krakenPrivateEndpointCost("/0/private/Ledgers") shouldBe 2.0
            krakenPrivateEndpointCost("/0/private/ClosedOrders") shouldBe 2.0
            KrakenApiConstants.SUBSTRING_TRADES_HISTORY shouldBe "TradesHistory"
            KrakenApiConstants.SUBSTRING_LEDGERS shouldBe "Ledgers"
            KrakenApiConstants.SUBSTRING_CLOSED_ORDERS shouldBe "ClosedOrders"
        }

        "krakenPrivateEndpointCost_Balance_and_AddOrder_are1" {
            krakenPrivateEndpointCost(KrakenApiConstants.PATH_BALANCE) shouldBe 1.0
            krakenPrivateEndpointCost(KrakenApiConstants.PATH_ADD_ORDER) shouldBe 1.0
        }
    }
}
