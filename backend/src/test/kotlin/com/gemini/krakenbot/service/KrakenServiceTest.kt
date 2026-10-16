package com.gemini.krakenbot.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.ApiKey
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.PrivateKey
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.KrakenAssetMetadata
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.OrderType
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import com.gemini.krakenbot.service.impl.PublicRateLimiter
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

    private fun assetFixture(name: String): String =
        checkNotNull(javaClass.getResource("/kraken-assets/$name")).readText()

    init {
        "getAuthenticatedAccountIdentity reads the IIBAN returned by Kraken" {
            runTest {
                val paths = mutableListOf<String>()
                val service = createService(
                    """{"error":[],"result":{"iban":"AA00 TEST ACCOUNT A","apiKey":"not-returned"}}""",
                ) { request -> paths += request.url.encodedPath }

                service.getAuthenticatedAccountIdentity() shouldBe "AA00 TEST ACCOUNT A"
                paths shouldBe listOf(KrakenApiConstants.PATH_API_KEY_INFO)
            }
        }

        "getAuthenticatedAccountInfo parses and validates Kraken query date restrictions" {
            runTest {
                val bounded = createService(
                    """{"error":[],"result":{"iban":"AA00 ACCOUNT","query_from":"1700000000","query_to":"1800000000"}}""",
                ).getAuthenticatedAccountInfo()
                bounded shouldBe AuthenticatedAccountInfo(
                    identity = "AA00 ACCOUNT",
                    queryFromSec = 1_700_000_000L,
                    queryToSec = 1_800_000_000L,
                    queryBoundsVerified = true,
                )

                val unrestricted = createService(
                    """{"error":[],"result":{"iban":"AA00 ACCOUNT","query_from":"0","query_to":"0"}}""",
                ).getAuthenticatedAccountInfo()
                unrestricted shouldBe AuthenticatedAccountInfo("AA00 ACCOUNT", queryBoundsVerified = true)

                val malformed = createService(
                    """{"error":[],"result":{"iban":"AA00 ACCOUNT","query_from":"yesterday","query_to":"0"}}""",
                ).getAuthenticatedAccountInfo()
                malformed?.queryBoundsVerified shouldBe false
                malformed?.queryFromSec shouldBe null
                malformed?.queryToSec shouldBe null
            }
        }

        "getAuthenticatedAccountInfo rejects incomplete or invalid query date restrictions" {
            runTest {
                val invalidResponses = listOf(
                    """{"error":[],"result":{"iban":"AA00 ACCOUNT","query_from":"-1","query_to":"0"}}""",
                    """{"error":[],"result":{"iban":"AA00 ACCOUNT","query_from":"1800000000","query_to":"1700000000"}}""",
                    """{"error":[],"result":{"iban":"AA00 ACCOUNT","query_from":1.5,"query_to":0}}""",
                    """{"error":[],"result":{"iban":"AA00 ACCOUNT","query_from":"9223372036854775808","query_to":"0"}}""",
                    """{"error":[],"result":{"iban":"AA00 ACCOUNT","query_to":"0"}}""",
                    """{"error":[],"result":{"query_from":"0","query_to":"0"}}""",
                )

                invalidResponses.forEach { response ->
                    val info = createService(response).getAuthenticatedAccountInfo()
                    if (response.contains("AA00 ACCOUNT")) {
                        info?.queryBoundsVerified shouldBe false
                        info?.queryFromSec shouldBe null
                        info?.queryToSec shouldBe null
                    } else {
                        info shouldBe null
                    }
                }
            }
        }

        "history page requests send bounded cursor parameters to Kraken" {
            runTest {
                val paths = mutableListOf<String>()
                val bodies = mutableListOf<String>()
                val tradeService = createService(
                    """{"error":[],"result":{"trades":{},"count":0,"cursor":{"next":"trade-next"}}}""",
                ) { request ->
                    paths += request.url.encodedPath
                    bodies += (request.body as TextContent).text
                }
                val tradePage = tradeService.getAccountTradePage(
                    startSec = 1_700_000_000,
                    endSec = 1_700_000_100,
                    cursor = "trade-current",
                    limit = 75,
                )
                tradePage.valid shouldBe true
                tradePage.requestedCursor shouldBe "trade-current"
                tradePage.nextCursor shouldBe "trade-next"
                paths.single() shouldBe KrakenApiConstants.PATH_TRADES_HISTORY
                val tradeBody = bodies.single()
                tradeBody.contains("${KrakenApiConstants.PARAM_START}=1700000000").shouldBeTrue()
                tradeBody.contains("${KrakenApiConstants.PARAM_END}=1700000100").shouldBeTrue()
                tradeBody.contains("${KrakenApiConstants.PARAM_CURSOR}=trade-current").shouldBeTrue()
                tradeBody.contains("${KrakenApiConstants.PARAM_LIMIT}=75").shouldBeTrue()
                tradeBody.contains("${KrakenApiConstants.PARAM_TRADES}=true").shouldBeTrue()

                val unboundedTradePage = tradeService.getAccountTradePage()
                unboundedTradePage.valid shouldBe true
                val unboundedTradeBody = bodies.last()
                unboundedTradeBody.contains("${KrakenApiConstants.PARAM_START}=").shouldBeFalse()
                unboundedTradeBody.contains("${KrakenApiConstants.PARAM_END}=").shouldBeFalse()
                Regex("(?:^|&)${KrakenApiConstants.PARAM_CURSOR}=").containsMatchIn(unboundedTradeBody)
                    .shouldBeFalse()
                unboundedTradeBody.contains("${KrakenApiConstants.PARAM_LIMIT}=").shouldBeFalse()
                shouldThrow<IllegalArgumentException> { tradeService.getAccountTradePage(limit = 0) }

                paths.clear()
                bodies.clear()
                val ledgerService = createService(
                    """{"error":[],"result":{"ledger":{},"count":0,"cursor":{"next":"ledger-next"}}}""",
                ) { request ->
                    paths += request.url.encodedPath
                    bodies += (request.body as TextContent).text
                }
                val ledgerPage = ledgerService.getAccountLedgerPage(
                    startSec = 1_700_000_000,
                    endSec = 1_700_000_100,
                    cursor = "ledger-current",
                )
                ledgerPage.valid shouldBe true
                ledgerPage.requestedCursor shouldBe "ledger-current"
                ledgerPage.nextCursor shouldBe "ledger-next"
                paths.single() shouldBe KrakenApiConstants.PATH_LEDGERS
                val ledgerBody = bodies.single()
                Regex("(?:^|&)${KrakenApiConstants.PARAM_CURSOR}=ledger-current")
                    .containsMatchIn(ledgerBody).shouldBeTrue()
                ledgerBody.contains("${KrakenApiConstants.PARAM_WITH_CURSOR}=true").shouldBeTrue()
                ledgerBody.contains("${KrakenApiConstants.PARAM_TYPE}=all").shouldBeTrue()

                val unboundedLedgerPage = ledgerService.getAccountLedgerPage()
                unboundedLedgerPage.valid shouldBe true
                val unboundedLedgerBody = bodies.last()
                unboundedLedgerBody.contains("${KrakenApiConstants.PARAM_START}=").shouldBeFalse()
                unboundedLedgerBody.contains("${KrakenApiConstants.PARAM_END}=").shouldBeFalse()
                Regex("(?:^|&)${KrakenApiConstants.PARAM_CURSOR}=").containsMatchIn(unboundedLedgerBody)
                    .shouldBeFalse()
            }
        }

        "history page requests fail when Kraken credentials are unavailable" {
            runTest {
                val service = createService("""{"error":[],"result":{}}""")
                val config = configService.getConfig()
                every { configService.getConfig() } returns config.copy(
                    kraken = KrakenCredentials(ApiKey(""), PrivateKey("")),
                )

                shouldThrow<KrakenCredentialsUnavailableException> { service.getAccountTradePage() }
                shouldThrow<KrakenCredentialsUnavailableException> { service.getAccountLedgerPage() }
            }
        }

        "getAuthenticatedAccountIdentity is unavailable when Kraken omits the IIBAN" {
            runTest {
                val service = createService("""{"error":[],"result":{"permissions":[]}}""")

                service.getAuthenticatedAccountIdentity() shouldBe null
            }
        }

        "funding evidence scope normalizes credential whitespace" {
            runTest {
                val service = createService("{\"error\":[],\"result\":{}}")
                val config = configService.getConfig()
                every { configService.getConfig() } returns config.copy(
                    kraken = KrakenCredentials(apiKey = " key \n", privateKey = " secret \t"),
                )
                val material = "key\u0000secret".toByteArray(Charsets.UTF_8)
                val expectedScope = MessageDigest.getInstance("SHA-256")
                    .digest(material)
                    .joinToString(separator = "") { byte -> "%02x".format(byte) }

                service.getFundingEvidenceScope() shouldBe expectedScope
                service.getApiCallCounter() shouldBe 0.0
            }
        }

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

        "getAssetMetadata queries both public asset classes through the public limiter" {
            runTest {
                val responseBodies = listOf(
                    assetFixture("assets-currency.json"),
                    assetFixture("assets-tokenized-asset.json"),
                )
                val requestPaths = mutableListOf<String>()
                var responseIndex = 0
                val publicRateLimiter = object : PublicRateLimiter(minIntervalMs = 0) {
                    var acquireCount = 0

                    override suspend fun acquire() {
                        acquireCount++
                        super.acquire()
                    }
                }
                val engine = MockEngine { request ->
                    requestPaths += request.url.toString().removePrefix("https://api.kraken.com")
                    respond(
                        content = responseBodies[responseIndex++],
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                    )
                }
                val service = KrakenServiceImpl(
                    configService = mockk(relaxed = true),
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(engine),
                    publicRateLimiter = publicRateLimiter,
                )

                service.getAssetMetadata() shouldBe listOf(
                    KrakenAssetMetadata("XXBT", "currency"),
                    KrakenAssetMetadata("ZUSD", "currency"),
                    KrakenAssetMetadata("TOKENIZED_ASSET_1", "tokenized_asset"),
                )
                requestPaths shouldBe listOf(
                    "/0/public/Assets?aclass=currency",
                    "/0/public/Assets?aclass=tokenized_asset",
                )
                publicRateLimiter.acquireCount shouldBe 2
            }
        }

        "getOHLC forwards its optional query bound" {
            runTest {
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
