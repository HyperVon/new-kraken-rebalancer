package com.gemini.krakenbot.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class KrakenServiceTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private lateinit var configService: ConfigService

    private fun createService(responseContent: String): KrakenService {
        val objectMapper = jacksonObjectMapper()
        configService = mockk(relaxed = true)

        val credentials = KrakenCredentials(
            apiKey = "public-key",
            privateKey = Base64.getEncoder()
                .encodeToString("secret-key".toByteArray())
        )
        val settings = Settings(
            loopDelaySeconds = 60L,
            deviationTriggerPercent = 2.0,
            dustThresholdUSD = 1.0,
            dryRun = false,
            fiatMaxDrawdown = 0.0,
            fiatDeploymentExponent = 1.0
        )
        val config = AppConfig(
            kraken = credentials,
            settings = settings,
            allocations = listOf(
                Allocation("BTC", 50.0),
                Allocation("ETH", 50.0)
            )
        )
        every { configService.getConfig() } returns config

        val mockEngine = MockEngine { request ->
            respond(
                content = responseContent,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val httpClient = HttpClient(mockEngine)
        return KrakenServiceImpl(configService, objectMapper, httpClient)
    }

    init {
        "getBalances_Success" {
            runTest {
                val responseJson =
                    "{\"error\":[],\"result\":{\"XXBTZUSD\":63000.0,\"XETHZUSD\":3000.0,\"USD\":5000.0}}"
                val service = createService(responseJson)

                val balances = service.getBalances()

                balances["XXBTZUSD"] shouldBe 63000.0
                balances["XETHZUSD"] shouldBe 3000.0
                balances["USD"] shouldBe 5000.0
            }
        }

        "getTickerPrices_Success" {
            runTest {
                val responseJson =
                    "{\"error\":[],\"result\":{\"XXBTZUSD\":{\"c\":[\"65000.0\"]},\"XETHZUSD\":{\"c\":[\"3200.0\"]}}}"
                val service = createService(responseJson)

                val prices = service.getTickerPrices("XXBTZUSD,XETHZUSD")

                prices["XXBTZUSD"] shouldBe 65000.0
                prices["XETHZUSD"] shouldBe 3200.0
            }
        }

        "executeOrder_Success" {
            runTest {
                val responseJson =
                    "{\"error\":[],\"result\":{\"descr\":{\"order\":\"buy 0.1 XBTUSD @ limit 50000\"},\"txid\":[\"THVR-...-TC\"]}}"
                val service = createService(responseJson)

                val result = service.executeOrder(
                    pair = "XBTUSD",
                    type = "limit",
                    side = "buy",
                    volume = BigDecimal("0.1")
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
                    fiatDeploymentExponent = 1.0
                )
                val config = AppConfig(
                    kraken = KrakenCredentials(apiKey = "k", privateKey = "s"),
                    settings = settings,
                    allocations = emptyList()
                )
                every { configService.getConfig() } returns config

                val result = service.executeOrder(
                    pair = "XBTUSD",
                    type = "limit",
                    side = "buy",
                    volume = BigDecimal("0.1")
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
                    service.getTickerPrices("XBTUSD")
                }
            }
        }

        "executeOrder_ApiError" {
            runTest {
                val responseJson = "{\"error\":[\"EOrder:Insufficient funds\"]}"
                val service = createService(responseJson)

                val result = service.executeOrder(
                    pair = "XBTUSD",
                    type = "limit",
                    side = "buy",
                    volume = BigDecimal.ONE
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
                        .encodeToString("secret-key".toByteArray())
                )
                val settings = Settings(
                    loopDelaySeconds = 60L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = false,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0
                )
                val config = AppConfig(
                    kraken = credentials,
                    settings = settings,
                    allocations = emptyList()
                )
                every { configService.getConfig() } returns config

                val mockEngine = MockEngine { request ->
                    throw RuntimeException(null as String?)
                }
                val httpClient = HttpClient(mockEngine)
                val service =
                    KrakenServiceImpl(configService, objectMapper, httpClient)

                val result = service.executeOrder(
                    pair = "XBTUSD",
                    type = "limit",
                    side = "buy",
                    volume = BigDecimal.ONE
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
                        privateKey = "secret"
                    ),
                    settings = Settings(
                        loopDelaySeconds = 60L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0
                    ),
                    allocations = emptyList()
                )
                every { mockConfigService.getConfig() } returns config

                val mockEngine = MockEngine { respond(content = "") }
                val localService = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(mockEngine)
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
                        privateKey = "invalid_base64_!@#$"
                    ),
                    settings = Settings(
                        loopDelaySeconds = 60L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0
                    ),
                    allocations = emptyList()
                )
                every { mockConfigService.getConfig() } returns config

                val mockEngine = MockEngine { respond(content = "") }
                val localService = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(mockEngine)
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
                            "application/json"
                        )
                    )
                }

                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val validSecret =
                    Base64.getEncoder().encodeToString("secret".toByteArray())
                val config = AppConfig(
                    kraken = KrakenCredentials(
                        apiKey = "k",
                        privateKey = validSecret
                    ),
                    settings = Settings(
                        loopDelaySeconds = 60L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0
                    ),
                    allocations = emptyList()
                )
                every { mockConfigService.getConfig() } returns config

                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(mockEngine)
                )

                val balances = service.getBalances()
                balances["XXBTZUSD"] shouldBe 63000.0
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
                            "application/json"
                        )
                    )
                }

                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val validSecret =
                    Base64.getEncoder().encodeToString("secret".toByteArray())
                val config = AppConfig(
                    kraken = KrakenCredentials(
                        apiKey = "k",
                        privateKey = validSecret
                    ),
                    settings = Settings(
                        loopDelaySeconds = 60L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0
                    ),
                    allocations = emptyList()
                )
                every { mockConfigService.getConfig() } returns config

                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(mockEngine)
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
                first.pair shouldBe "XXBTZUSD"
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
                        fiatDeploymentExponent = 1.0
                    ),
                    allocations = emptyList()
                )
                every { mockConfigService.getConfig() } returns config

                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(MockEngine { respond("") })
                )

                val trades = service.getTradeHistory()
                trades.isEmpty().shouldBeTrue()
            }
        }

        "getTradeHistory_PlaceholderApiKey" {
            runTest {
                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val config = AppConfig(
                    kraken = KrakenCredentials("YOUR_KRAKEN_API_KEY", "YOUR_KRAKEN_PRIVATE_KEY"),
                    settings = Settings(
                        loopDelaySeconds = 60L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0
                    ),
                    allocations = emptyList()
                )
                every { mockConfigService.getConfig() } returns config

                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(MockEngine { respond("") })
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
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }

                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val credentials = KrakenCredentials("api-key", Base64.getEncoder().encodeToString("secret".toByteArray()))
                val config = AppConfig(
                    kraken = credentials,
                    settings = Settings(
                        loopDelaySeconds = 60L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0
                    ),
                    allocations = emptyList()
                )
                every { mockConfigService.getConfig() } returns config

                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(mockEngine)
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
                    capturedBody = (request.body as io.ktor.http.content.TextContent).text
                    respond(
                        content = """{"error":[],"result":{"trades":{}}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }

                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val credentials = KrakenCredentials("api-key", Base64.getEncoder().encodeToString("secret".toByteArray()))
                val config = AppConfig(
                    kraken = credentials,
                    settings = Settings(
                        loopDelaySeconds = 60L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0
                    ),
                    allocations = emptyList()
                )
                every { mockConfigService.getConfig() } returns config

                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(mockEngine)
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
                                    "price": "50000.00",
                                    "cost": "invalid_cost",
                                    "fee": "10.00",
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
    }
}
