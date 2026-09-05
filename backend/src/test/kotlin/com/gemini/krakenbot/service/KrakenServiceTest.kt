package com.gemini.krakenbot.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.OrderType
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import com.gemini.krakenbot.test.TestConstants
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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

        "getSpendableBalances_Success" {
            runTest {
                val responseJson =
                    """{"error":[],"result":{"ZUSD":{"balance":"100.0","credit":"5.0","credit_used":"2.0","hold_trade":"30.0"}}}"""
                val service = createService(responseJson)

                val balances = (service as SpendableBalanceService).getSpendableBalances()

                balances["ZUSD"]!!.shouldBeEqualComparingTo(BigDecimal("73.0"))
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

        "getLedgers and getOHLC forward optional query bounds" {
            runTest {
                var ledgerBody = ""
                val ledgerService = createService(
                    responseContent =
                    """
                        {
                          "error": [],
                          "result": {
                            "ledger": {
                              "L1": {
                                "time": 1700000000,
                                "type": "staking",
                                "asset": "USD",
                                "amount": "1.00",
                                "fee": "0.00",
                                "balance": "1.00"
                              }
                            }
                          }
                        }
                    """.trimIndent(),
                    onRequest = { request -> ledgerBody = (request.body as TextContent).text },
                )

                ledgerService.getLedgers(startSec = 100L, offset = 5, endSec = 200L)
                ledgerService.getLedgers(types = emptySet()) shouldBe emptyList()

                ledgerBody.contains("start=100").shouldBeTrue()
                ledgerBody.contains("end=200").shouldBeTrue()
                ledgerBody.contains("ofs=5").shouldBeTrue()

                var ohlcUrl = ""
                val ohlcService = createService(
                    responseContent =
                    """
                        {
                          "error": [],
                          "result": {
                            "XXBTZUSD": [[1700000000, "1", "2", "0.5", "1.5", "10", 1]],
                            "last": 1700000100
                          }
                        }
                    """.trimIndent(),
                    onRequest = { request -> ohlcUrl = request.url.toString() },
                )

                ohlcService.getOHLC(TestFixtures.XBTUSD, interval = 15, since = 100L).single().second shouldBe
                    BigDecimal("1.5")
                ohlcUrl.contains("since=100").shouldBeTrue()
            }
        }

        "getFundingStatus_UsesAuthenticatedPagesAndFollowsCursor" {
            runTest {
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                val credentials = KrakenCredentials(
                    apiKey = TestConstants.API_KEY,
                    privateKey = Base64.getEncoder()
                        .encodeToString(TestConstants.API_SECRET.toByteArray()),
                )
                every { configService.getConfig() } returns AppConfig(
                    kraken = credentials,
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                val responses = listOf(
                    """
                    {
                      "error": [],
                      "result": {
                        "deposit": [{
                          "method": "Wire",
                          "asset": "USD",
                          "refid": "DEP-PAGE-1",
                          "amount": "100.00",
                          "fee": "0.00",
                          "time": 1700000000,
                          "status": "Success"
                        }],
                        "cursor": "next-page"
                      }
                    }
                    """.trimIndent(),
                    """
                    {
                      "error": [],
                      "result": {
                        "deposit": [{
                          "method": "Wire",
                          "asset": "USD",
                          "refid": "DEP-PAGE-2",
                          "amount": "50.00",
                          "fee": "",
                          "time": 1700000100,
                          "status": "Settled"
                        }],
                        "cursor": "   "
                      }
                    }
                    """.trimIndent(),
                    """
                    {
                      "error": [],
                      "result": {
                        "withdrawal": [{
                          "method": "Wire",
                          "asset": "USD",
                          "refid": "WITH-PAGE-1",
                          "amount": "25.00",
                          "fee": "0.00",
                          "time": 1700000200,
                          "status": "Success"
                        }]
                      }
                    }
                    """.trimIndent(),
                )
                val bodies = mutableListOf<String>()
                var requestIndex = 0
                val client = HttpClient(
                    MockEngine { request ->
                        bodies += (request.body as TextContent).text
                        respond(
                            content = responses[requestIndex++],
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val service = KrakenServiceImpl(configService, objectMapper, client)

                val deposits = service.getDepositStatus(startSec = 100L, endSec = 200L)
                val withdrawals = service.getWithdrawStatus()

                deposits.map { it.refid } shouldBe listOf("DEP-PAGE-1", "DEP-PAGE-2")
                withdrawals.single().refid shouldBe "WITH-PAGE-1"
                bodies[0].contains("start=100").shouldBeTrue()
                bodies[0].contains("end=200").shouldBeTrue()
                bodies[0].contains("cursor=true").shouldBeTrue()
                bodies[0].contains("limit=25").shouldBeTrue()
                bodies[1].contains("cursor=next-page").shouldBeTrue()
                bodies[2].contains("cursor=true").shouldBeTrue()
            }
        }

        "getFundingStatus_rejects_repeated_cursor_and_missing_credentials" {
            runTest {
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(TestConstants.API_KEY, ""),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                val repeatedResponse = """
                    {
                      "error": [],
                      "result": {
                        "deposit": [],
                        "cursor": "repeat"
                      }
                    }
                """.trimIndent()
                val client = HttpClient(
                    MockEngine {
                        respond(
                            content = repeatedResponse,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val service = KrakenServiceImpl(configService, objectMapper, client)

                shouldThrow<IllegalStateException> { service.getDepositStatus() }

                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials("", ""),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                shouldThrow<KrakenCredentialsUnavailableException> { service.getWithdrawStatus() }
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
                    dryRun = false,
                )
                result.success.shouldBeTrue()
                result.orderTxid shouldBe "THVR-...-TC"
            }
        }

        "executeOrder rejects empty txid arrays and blank txid values" {
            runTest {
                for (txid in listOf("[]", "[\"\"]")) {
                    val service = createService(
                        """{"error":[],"result":{"txid":$txid}}""",
                    )

                    val result = service.executeOrder(
                        pair = TestFixtures.XBTUSD,
                        type = OrderType.MARKET.apiValue,
                        side = OrderSide.BUY.apiValue,
                        volume = BigDecimal.ONE,
                        dryRun = false,
                    )

                    result.success.shouldBeFalse()
                    result.submissionUncertain shouldBe true
                }
            }
        }

        "executeOrder_IncludesClOrdIdInAddOrderBody" {
            runTest {
                val responseJson =
                    "{\"error\":[],\"result\":{\"descr\":{\"order\":\"buy 0.1 XBTUSD @ market\"},\"txid\":[\"TX-CLORD\"]}}"
                var capturedBody = ""
                var capturedSignature = ""
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
                    capturedSignature = request.headers[KrakenApiConstants.HEADER_API_SIGN].orEmpty()
                    respond(
                        content = responseJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                    )
                }
                val service = KrakenServiceImpl(configService, objectMapper, HttpClient(mockEngine))
                val clOrdId = "id+ with&symbols"

                val result = service.executeOrder(
                    pair = Asset.BTC_USD_PAIR,
                    type = OrderType.MARKET.apiValue,
                    side = OrderSide.BUY.apiValue,
                    volume = BigDecimal("0.1"),
                    dryRun = false,
                    clOrdId = clOrdId,
                )

                result.success.shouldBeTrue()
                capturedBody.contains("${KrakenApiConstants.PARAM_CL_ORD_ID}=id%2B+with%26symbols").shouldBeTrue()
                capturedBody.contains("userref=").shouldBeFalse()

                val nonce =
                    Regex("""(?:^|&)${KrakenApiConstants.PARAM_NONCE}=([^&]+)""")
                        .find(capturedBody)
                        ?.groupValues
                        ?.get(1)
                        ?: error("AddOrder body did not contain a nonce")
                val nonceHash = MessageDigest.getInstance("SHA-256")
                    .digest((nonce + capturedBody).toByteArray(Charsets.UTF_8))
                val signingMessage = "/0/private/AddOrder".toByteArray(Charsets.UTF_8) + nonceHash
                val mac = Mac.getInstance("HmacSHA512")
                mac.init(SecretKeySpec(TestConstants.API_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA512"))
                val expectedSignature = Base64.getEncoder().encodeToString(mac.doFinal(signingMessage))
                capturedSignature shouldBe expectedSignature
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
                    dryRun = true,
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
                    dryRun = false,
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
                    dryRun = false,
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
                    dryRun = false,
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
                    dryRun = false,
                )

                result.success.shouldBeFalse()
                result.submissionUncertain shouldBe true
            }
        }

        "executeOrder_MalformedResponseIsUncertain" {
            runTest {
                val service = createService("{broken-json")

                val result = service.executeOrder(
                    pair = TestFixtures.XBTUSD,
                    type = OrderType.MARKET.apiValue,
                    side = OrderSide.BUY.apiValue,
                    volume = BigDecimal.ONE,
                    dryRun = false,
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
                    dryRun = false,
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
                    dryRun = false,
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
                val nonces = mutableListOf<Long>()
                val mockEngine = MockEngine { request ->
                    val body = (request.body as TextContent).text
                    Regex("""(?:^|&)${KrakenApiConstants.PARAM_NONCE}=([^&]+)""")
                        .find(body)
                        ?.groupValues
                        ?.get(1)
                        ?.toLong()
                        ?.let(nonces::add)
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
                nonces.size shouldBe 2
                nonces[1] - nonces[0] shouldBe 100_000_001L
            }
        }

        "queryPrivate_InvalidNonce_RetryExceeded" {
            runTest {
                val errorJson = "{\"error\":[\"EAPI:Invalid nonce\"]}"
                var attempt = 0
                val mockEngine = MockEngine {
                    attempt++
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
                attempt shouldBe 6
            }
        }
    }
}
