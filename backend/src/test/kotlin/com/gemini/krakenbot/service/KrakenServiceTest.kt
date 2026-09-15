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
import com.gemini.krakenbot.service.impl.KrakenApiPermissionDeniedException
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import com.gemini.krakenbot.service.impl.KrakenTransport
import com.gemini.krakenbot.service.impl.PublicRateLimiter
import com.gemini.krakenbot.service.impl.RateLimiter
import com.gemini.krakenbot.test.TestConstants
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
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

        "getFundingHistory_UsesFundingApiPagesAndEnrichesFromLegacyStatus" {
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
                      "deposits": [{
                        "deposit_id": "DEP-PAGE-1",
                        "method_id": "method-1",
                        "status": "success",
                        "amount": {"asset": {"class": "currency", "name": "USD"}, "amount": "100.00"},
                        "fee": {"asset": {"class": "currency", "name": "USD"}, "amount": "0.00"},
                        "create_time": "2023-11-14T22:13:20Z"
                      }],
                      "next_cursor": "next-page"
                    }
                    """.trimIndent(),
                    """
                    {
                      "deposits": [{
                        "deposit_id": "DEP-PAGE-2",
                        "method_id": "method-2",
                        "status": "success",
                        "amount": {"asset": {"class": "currency", "name": "USD"}, "amount": "50.00"},
                        "fee": {"asset": {"class": "currency", "name": "USD"}, "amount": ""},
                        "create_time": "2023-11-14T22:15:00Z"
                      }]
                    }
                    """.trimIndent(),
                    """
                    {
                      "error": [],
                      "result": [
                        {
                          "method": "Wire",
                          "asset": "USD",
                          "refid": "DEP-PAGE-1",
                          "txid": "tx-1",
                          "amount": "100.00",
                          "fee": "0.00",
                          "time": 1700000000,
                          "status": "Success"
                        },
                        {
                          "method": "Wire",
                          "asset": "USD",
                          "refid": "DEP-PAGE-2",
                          "txid": "tx-2",
                          "amount": "50.00",
                          "fee": "0.00",
                          "time": 1700000100,
                          "status": "Settled"
                        }
                      ]
                    }
                    """.trimIndent(),
                    """
                    {
                      "withdrawals": [{
                        "withdrawal_id": "WITH-PAGE-1",
                        "method_id": "method-w1",
                        "status": "success",
                        "amount": {"asset": {"class": "currency", "name": "USD"}, "amount": "25.00"},
                        "fee": {"asset": {"class": "currency", "name": "USD"}, "amount": "0.00"},
                        "create_time": "2023-11-14T22:16:40Z"
                      }]
                    }
                    """.trimIndent(),
                    """
                    {
                      "error": [],
                      "result": [{
                        "method": "Wire",
                        "asset": "USD",
                        "refid": "WITH-PAGE-1",
                        "txid": "tx-w1",
                        "amount": "25.00",
                        "fee": "0.00",
                        "time": 1700000200,
                        "status": "Success"
                      }]
                    }
                    """.trimIndent(),
                )
                val requests = mutableListOf<HttpRequestData>()
                var requestIndex = 0
                val client = HttpClient(
                    MockEngine { request ->
                        requests += request
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
                deposits[0].method shouldBe "Wire"
                deposits[0].txid shouldBe "tx-1"
                deposits[0].status shouldBe "success"
                deposits[0].asset shouldBe "USD"
                deposits[0].hasAuthoritativeFee.shouldBeTrue()
                deposits[1].method shouldBe "Wire"
                deposits[1].txid shouldBe "tx-2"
                deposits[1].hasAuthoritativeFee.shouldBeFalse()
                withdrawals.single().refid shouldBe "WITH-PAGE-1"
                withdrawals.single().method shouldBe "Wire"
                withdrawals.single().txid shouldBe "tx-w1"

                requests[0].url.encodedPath shouldBe KrakenApiConstants.PATH_FUNDING_DEPOSITS
                requests[0].url.parameters["start_time"] shouldBe Instant.ofEpochSecond(100L).toString()
                requests[0].url.parameters["end_time"] shouldBe Instant.ofEpochSecond(200L).toString()
                requests[0].url.parameters["limit"] shouldBe "500"
                requests[1].url.parameters["cursor"] shouldBe "next-page"
                requests[1].url.parameters["start_time"] shouldBe null
                requests[2].url.encodedPath shouldBe KrakenApiConstants.PATH_DEPOSIT_STATUS
                val depositEnrichmentBody = (requests[2].body as TextContent).text
                depositEnrichmentBody.contains("start=100").shouldBeTrue()
                depositEnrichmentBody.contains("end=200").shouldBeTrue()
                depositEnrichmentBody.contains("limit=500").shouldBeTrue()
                depositEnrichmentBody.contains("cursor").shouldBeFalse()
                requests[3].url.encodedPath shouldBe KrakenApiConstants.PATH_FUNDING_WITHDRAWALS
                requests[4].url.encodedPath shouldBe KrakenApiConstants.PATH_WITHDRAW_STATUS
            }
        }

        "getFundingHistory_rejects_repeated_cursor_and_missing_credentials" {
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
                      "deposits": [],
                      "next_cursor": "repeat"
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

        "getFundingHistory_forwards_end_bound_only" {
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
                val requests = mutableListOf<HttpRequestData>()
                val client = HttpClient(
                    MockEngine { request ->
                        requests += request
                        val content = when (request.url.encodedPath) {
                            KrakenApiConstants.PATH_FUNDING_WITHDRAWALS -> """{"withdrawals":[]}"""
                            else -> """{"error":[],"result":[]}"""
                        }
                        respond(
                            content = content,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val service = KrakenServiceImpl(configService, objectMapper, client)

                val records = service.getWithdrawStatus(endSec = 200L)

                records.size shouldBe 0
                val fundingRequest = requests.first {
                    it.url.encodedPath == KrakenApiConstants.PATH_FUNDING_WITHDRAWALS
                }
                fundingRequest.url.parameters["start_time"] shouldBe null
                fundingRequest.url.parameters["end_time"] shouldBe Instant.ofEpochSecond(200L).toString()
            }
        }

        "getFundingHistory_forwards_partial_time_bounds" {
            runTest {
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(
                        apiKey = TestConstants.API_KEY,
                        privateKey = Base64.getEncoder()
                            .encodeToString(TestConstants.API_SECRET.toByteArray()),
                    ),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                val requests = mutableListOf<HttpRequestData>()
                val client = HttpClient(
                    MockEngine { request ->
                        requests += request
                        val content = when (request.url.encodedPath) {
                            KrakenApiConstants.PATH_FUNDING_WITHDRAWALS -> """{"withdrawals":[]}"""
                            else -> """{"error":[],"result":[]}"""
                        }
                        respond(
                            content = content,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val service = KrakenServiceImpl(configService, objectMapper, client)

                val records = service.getWithdrawStatus(startSec = 100L, endSec = null)

                records.size shouldBe 0
                requests.first().url.parameters["start_time"] shouldBe Instant.ofEpochSecond(100L).toString()
                requests.first().url.parameters["end_time"] shouldBe null
            }
        }

        "getWithdrawStatus_reuses_the_method_list_across_records" {
            runTest {
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(
                        apiKey = TestConstants.API_KEY,
                        privateKey = Base64.getEncoder()
                            .encodeToString(TestConstants.API_SECRET.toByteArray()),
                    ),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                var methodListRequests = 0
                val client = HttpClient(
                    MockEngine { request ->
                        val content = when (request.url.encodedPath) {
                            KrakenApiConstants.PATH_FUNDING_WITHDRAWALS -> """
                                {
                                  "withdrawals": [
                                    {
                                      "withdrawal_id": "W-M1",
                                      "method_id": "m-1",
                                      "status": "success",
                                      "amount": {
                                        "asset": {"class": "currency", "name": "USD"},
                                        "amount": "10.00"
                                      },
                                      "fee": {
                                        "asset": {"class": "currency", "name": "USD"},
                                        "amount": "0.00"
                                      },
                                      "create_time": "2023-11-14T22:13:20Z"
                                    },
                                    {
                                      "withdrawal_id": "W-M2",
                                      "method_id": "m-2",
                                      "status": "success",
                                      "amount": {
                                        "asset": {"class": "currency", "name": "USD"},
                                        "amount": "20.00"
                                      },
                                      "fee": {
                                        "asset": {"class": "currency", "name": "USD"},
                                        "amount": "0.00"
                                      },
                                      "create_time": "2023-11-14T22:14:20Z"
                                    }
                                  ]
                                }
                            """.trimIndent()

                            KrakenApiConstants.PATH_FUNDING_METHODS_WITHDRAW -> {
                                methodListRequests++
                                """
                                {
                                  "methods": [
                                    {"method_id": "m-1", "method_name": "ACH"},
                                    {"method_id": "m-2", "method_name": "Wire"}
                                  ]
                                }
                                """.trimIndent()
                            }

                            else -> """{"error":[],"result":[]}"""
                        }
                        respond(
                            content = content,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val service = KrakenServiceImpl(configService, objectMapper, client)

                val records = service.getWithdrawStatus()

                records.map { it.method } shouldBe listOf("ACH", "Wire")
                methodListRequests shouldBe 1
            }
        }

        "queryPrivateGet_signs_paths_without_query_parameters" {
            runTest {
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(
                        apiKey = TestConstants.API_KEY,
                        privateKey = Base64.getEncoder()
                            .encodeToString(TestConstants.API_SECRET.toByteArray()),
                    ),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                val client = HttpClient(
                    MockEngine {
                        respond(
                            content = """{"deposits":[]}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val transport = KrakenTransport(
                    configService = configService,
                    objectMapper = objectMapper,
                    httpClient = client,
                    rateLimiter = RateLimiter(),
                    nonceGenerator = AtomicLong(System.currentTimeMillis() * 1_000_000L),
                    publicRateLimiter = PublicRateLimiter(),
                )

                val result = transport.queryPrivateGet(KrakenApiConstants.PATH_FUNDING_DEPOSITS, emptyMap())

                result.path("deposits").isArray shouldBe true
            }
        }

        "queryPrivateGet_rejects_blank_credentials" {
            runTest {
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials("", ""),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                val client = HttpClient(
                    MockEngine {
                        respond(
                            content = "{}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val transport = KrakenTransport(
                    configService,
                    objectMapper,
                    client,
                    RateLimiter(),
                    AtomicLong(System.currentTimeMillis() * 1_000_000L),
                    PublicRateLimiter(),
                )

                shouldThrow<IllegalStateException> {
                    transport.queryPrivateGet(
                        KrakenApiConstants.PATH_FUNDING_DEPOSITS,
                        mapOf(KrakenApiConstants.PARAM_LIMIT to "1"),
                    )
                }
            }
        }

        "queryPrivateGet_rejects_permission_text_on_unrelated_endpoints" {
            runTest {
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(
                        apiKey = TestConstants.API_KEY,
                        privateKey = Base64.getEncoder()
                            .encodeToString(TestConstants.API_SECRET.toByteArray()),
                    ),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                val client = HttpClient(
                    MockEngine {
                        respond(
                            content = """{"error":["EGeneral:Permission denied"]}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val transport = KrakenTransport(
                    configService,
                    objectMapper,
                    client,
                    RateLimiter(),
                    AtomicLong(System.currentTimeMillis() * 1_000_000L),
                    PublicRateLimiter(),
                )

                val error = shouldThrow<RuntimeException> {
                    transport.queryPrivateGet(KrakenApiConstants.PATH_BALANCE, emptyMap())
                }
                (error is KrakenApiPermissionDeniedException) shouldBe false
            }
        }

        "queryPrivateGet_skips_rate_limiting_for_unmetered_endpoints" {
            runTest {
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(
                        TestConstants.API_KEY,
                        Base64.getEncoder().encodeToString(TestConstants.API_SECRET.toByteArray()),
                    ),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                val client = HttpClient(
                    MockEngine {
                        respond(
                            content = "{}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val transport = KrakenTransport(
                    configService,
                    objectMapper,
                    client,
                    RateLimiter(),
                    AtomicLong(System.currentTimeMillis() * 1_000_000L),
                    PublicRateLimiter(),
                )

                transport.queryPrivateGet(KrakenApiConstants.PATH_ADD_ORDER, emptyMap())
            }
        }

        "getFundingHistory_fails_closed_when_invalid_nonce_persists" {
            runTest {
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(
                        apiKey = TestConstants.API_KEY,
                        privateKey = Base64.getEncoder()
                            .encodeToString(TestConstants.API_SECRET.toByteArray()),
                    ),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                val client = HttpClient(
                    MockEngine {
                        respond(
                            content = """{"error":["EAPI:Invalid nonce"]}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val service = KrakenServiceImpl(configService, objectMapper, client)

                val error = shouldThrow<RuntimeException> { service.getDepositStatus() }
                error.message?.contains("EAPI:Invalid nonce") shouldBe true
            }
        }

        "getWithdrawStatus_leaves_method_null_when_the_method_id_is_unknown" {
            runTest {
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(
                        apiKey = TestConstants.API_KEY,
                        privateKey = Base64.getEncoder()
                            .encodeToString(TestConstants.API_SECRET.toByteArray()),
                    ),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                val responses = mapOf(
                    KrakenApiConstants.PATH_FUNDING_WITHDRAWALS to """
                        {
                          "withdrawals": [{
                            "withdrawal_id": "WITH-UNKNOWN",
                            "method_id": "retired-method",
                            "status": "success",
                            "amount": {
                              "asset": {"class": "currency", "name": "USD"},
                              "amount": "10.00"
                            },
                            "fee": {
                              "asset": {"class": "currency", "name": "USD"},
                              "amount": "0.00"
                            },
                            "create_time": "2023-11-14T22:13:20Z"
                          }]
                        }
                    """.trimIndent(),
                    KrakenApiConstants.PATH_WITHDRAW_STATUS to """{"error":[],"result":[]}""",
                    KrakenApiConstants.PATH_FUNDING_METHODS_WITHDRAW to """
                        {"methods":[{"method_id":"other-method","method_name":"ACH"}]}
                    """.trimIndent(),
                )
                val client = HttpClient(
                    MockEngine { request ->
                        respond(
                            content = responses.getValue(request.url.encodedPath),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val service = KrakenServiceImpl(configService, objectMapper, client)

                service.getWithdrawStatus().single().method shouldBe null
            }
        }

        "getFundingHistory_stops_method_pagination_at_the_page_limit" {
            runTest {
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(
                        apiKey = TestConstants.API_KEY,
                        privateKey = Base64.getEncoder()
                            .encodeToString(TestConstants.API_SECRET.toByteArray()),
                    ),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                val depositResponse = """
                    {
                      "deposits": [{
                        "deposit_id": "DEP-METHOD-LIMIT",
                        "method_id": "method-1",
                        "status": "success",
                        "amount": {
                          "asset": {"class": "currency", "name": "USD"},
                          "amount": "100.00"
                        },
                        "fee": {
                          "asset": {"class": "currency", "name": "USD"},
                          "amount": "0.00"
                        },
                        "create_time": "2023-11-14T22:13:20Z"
                      }]
                    }
                """.trimIndent()
                var methodPageCount = 0
                val client = HttpClient(
                    MockEngine { request ->
                        val path = request.url.encodedPath
                        when (path) {
                            KrakenApiConstants.PATH_FUNDING_METHODS_DEPOSIT -> {
                                methodPageCount++
                                respond(
                                    content = """
                                        {
                                          "methods": [{"method_id": "method-1", "method_name": "Wire"}],
                                          "next_cursor": "method-$methodPageCount"
                                        }
                                    """.trimIndent(),
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                                )
                            }

                            KrakenApiConstants.PATH_FUNDING_DEPOSITS -> respond(
                                content = depositResponse,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                            )

                            else -> respond(
                                content = """{"error":[],"result":[]}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                            )
                        }
                    },
                )
                val service = KrakenServiceImpl(configService, objectMapper, client)

                service.getDepositStatus().single().method shouldBe "Wire"
                methodPageCount shouldBe 20
            }
        }

        "getDepositStatus_discards_partial_legacy_metadata" {
            runTest {
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(
                        apiKey = TestConstants.API_KEY,
                        privateKey = Base64.getEncoder()
                            .encodeToString(TestConstants.API_SECRET.toByteArray()),
                    ),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                val responses = mapOf(
                    KrakenApiConstants.PATH_FUNDING_DEPOSITS to """
                        {
                          "deposits": [{
                            "deposit_id": "DEP-PARTIAL",
                            "method_id": "method-1",
                            "status": "success",
                            "amount": {
                              "asset": {"class": "currency", "name": "USD"},
                              "amount": "100.00"
                            },
                            "fee": {
                              "asset": {"class": "currency", "name": "USD"},
                              "amount": "0.00"
                            },
                            "create_time": "2023-11-14T22:13:20Z"
                          }]
                        }
                    """.trimIndent(),
                    KrakenApiConstants.PATH_DEPOSIT_STATUS to """
                        {
                          "error": [],
                          "result": [
                            {
                              "method": "Wire",
                              "asset": "USD",
                              "refid": "DEP-PARTIAL",
                              "txid": "tx-partial",
                              "amount": "100.00",
                              "fee": "0.00",
                              "time": 1700000000,
                              "status": "Success"
                            },
                            {
                              "asset": "USD",
                              "amount": "1.00",
                              "time": 1700000000,
                              "status": "Success"
                            }
                          ]
                        }
                    """.trimIndent(),
                    KrakenApiConstants.PATH_FUNDING_METHODS_DEPOSIT to """
                        {"methods":[{"method_id":"method-1","method_name":"ACH"}]}
                    """.trimIndent(),
                )
                val client = HttpClient(
                    MockEngine { request ->
                        respond(
                            content = responses.getValue(request.url.encodedPath),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val service = KrakenServiceImpl(configService, objectMapper, client)

                val record = service.getDepositStatus().single()
                record.method shouldBe "ACH"
                record.txid shouldBe null
            }
        }

        "getFundingHistory_retries invalid nonce and then succeeds" {
            runTest {
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(
                        TestConstants.API_KEY,
                        Base64.getEncoder().encodeToString(TestConstants.API_SECRET.toByteArray()),
                    ),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                val responses = listOf(
                    """{"error":["EAPI:Invalid nonce"],"result":null}""",
                    """
                    {
                      "deposits": [{
                        "deposit_id": "DEP-RETRY",
                        "status": "success",
                        "amount": {"asset": {"class": "currency", "name": "USD"}, "amount": "10.00"},
                        "fee": {"asset": {"class": "currency", "name": "USD"}, "amount": "0.00"},
                        "create_time": "2023-11-14T22:13:20Z"
                      }]
                    }
                    """.trimIndent(),
                    """{"error":[],"result":[]}""",
                )
                var requestIndex = 0
                val client = HttpClient(
                    MockEngine {
                        respond(
                            content = responses[requestIndex++],
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val service = KrakenServiceImpl(configService, objectMapper, client)

                val deposits = service.getDepositStatus(startSec = 100L, endSec = 200L)

                deposits.single().refid shouldBe "DEP-RETRY"
                deposits.single().method shouldBe null
                requestIndex shouldBe 3
            }
        }

        "getFundingHistory_rejects_non_permission_api_errors" {
            runTest {
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(
                        TestConstants.API_KEY,
                        Base64.getEncoder().encodeToString(TestConstants.API_SECRET.toByteArray()),
                    ),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                val client = HttpClient(
                    MockEngine {
                        respond(
                            content = """{"error":["EGeneral:Invalid arguments:asset"]}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val service = KrakenServiceImpl(configService, objectMapper, client)

                val error = shouldThrow<RuntimeException> { service.getDepositStatus() }

                error.message?.contains("EGeneral:Invalid arguments") shouldBe true
            }
        }

        "getFundingHistory_reports_http_failures" {
            runTest {
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(
                        TestConstants.API_KEY,
                        Base64.getEncoder().encodeToString(TestConstants.API_SECRET.toByteArray()),
                    ),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                val client = HttpClient(
                    MockEngine {
                        respond(
                            content = "upstream unavailable",
                            status = HttpStatusCode.InternalServerError,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val service = KrakenServiceImpl(configService, objectMapper, client)

                shouldThrow<ResponseException> { service.getDepositStatus() }
            }
        }

        "getFundingHistory_reports_malformed_payloads" {
            runTest {
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(
                        TestConstants.API_KEY,
                        Base64.getEncoder().encodeToString(TestConstants.API_SECRET.toByteArray()),
                    ),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                val client = HttpClient(
                    MockEngine {
                        respond(
                            content = "not-json",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val service = KrakenServiceImpl(configService, objectMapper, client)

                shouldThrow<RuntimeException> { service.getDepositStatus() }
            }
        }

        "getWithdrawStatus_resolves_method_names_from_the_method_list" {
            runTest {
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(
                        TestConstants.API_KEY,
                        Base64.getEncoder().encodeToString(TestConstants.API_SECRET.toByteArray()),
                    ),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                val responses = mapOf(
                    KrakenApiConstants.PATH_FUNDING_WITHDRAWALS to
                        """
                        {
                          "withdrawals": [{
                            "withdrawal_id": "WITH-METHOD",
                            "method_id": "method-w1",
                            "status": "success",
                            "amount": {"asset": {"class": "currency", "name": "XXBT"}, "amount": "0.25"},
                            "fee": {"asset": {"class": "currency", "name": "XXBT"}, "amount": "0.0002"},
                            "create_time": "2023-11-14T22:13:20Z"
                          }]
                        }
                        """.trimIndent(),
                    KrakenApiConstants.PATH_WITHDRAW_STATUS to """{"error":[],"result":[]}""",
                    KrakenApiConstants.PATH_FUNDING_METHODS_WITHDRAW to
                        """
                        {
                          "methods": [
                            {"method_id": "method-w1", "method_name": "ACH"},
                            {"method_name": "missing identifier"}
                          ]
                        }
                        """.trimIndent(),
                )
                val client = HttpClient(
                    MockEngine { request ->
                        respond(
                            content = responses.getValue(request.url.encodedPath),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val service = KrakenServiceImpl(configService, objectMapper, client)

                val records = service.getWithdrawStatus()

                records.single().refid shouldBe "WITH-METHOD"
                records.single().method shouldBe "ACH"
            }
        }

        "getWithdrawStatus_leaves_method_null_without_metadata" {
            runTest {
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(
                        TestConstants.API_KEY,
                        Base64.getEncoder().encodeToString(TestConstants.API_SECRET.toByteArray()),
                    ),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                val responses = mapOf(
                    KrakenApiConstants.PATH_FUNDING_WITHDRAWALS to
                        """
                        {
                          "withdrawals": [{
                            "withdrawal_id": "WITH-NO-METHOD",
                            "status": "success",
                            "amount": {"asset": {"class": "currency", "name": "XXBT"}, "amount": "0.25"},
                            "fee": {"asset": {"class": "currency", "name": "XXBT"}, "amount": "0.0002"},
                            "create_time": "2023-11-14T22:13:20Z"
                          }]
                        }
                        """.trimIndent(),
                    KrakenApiConstants.PATH_WITHDRAW_STATUS to """{"error":[],"result":[]}""",
                )
                var requestCount = 0
                val client = HttpClient(
                    MockEngine { request ->
                        requestCount++
                        respond(
                            content = responses.getValue(request.url.encodedPath),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val service = KrakenServiceImpl(configService, objectMapper, client)

                val records = service.getWithdrawStatus()

                records.single().method shouldBe null
                requestCount shouldBe 2
            }
        }

        "getDepositStatus_reuses_a_single_method_list_across_records" {
            runTest {
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(
                        TestConstants.API_KEY,
                        Base64.getEncoder().encodeToString(TestConstants.API_SECRET.toByteArray()),
                    ),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                val responses = mapOf(
                    KrakenApiConstants.PATH_FUNDING_DEPOSITS to
                        """
                        {
                          "deposits": [{
                            "deposit_id": "DEP-M1",
                            "method_id": "method-1",
                            "status": "success",
                            "amount": {"asset": {"class": "currency", "name": "USD"}, "amount": "10.00"},
                            "fee": {"asset": {"class": "currency", "name": "USD"}, "amount": "0.00"},
                            "create_time": "2023-11-14T22:13:20Z"
                          }, {
                            "deposit_id": "DEP-M2",
                            "method_id": "method-2",
                            "status": "success",
                            "amount": {"asset": {"class": "currency", "name": "USD"}, "amount": "20.00"},
                            "fee": {"asset": {"class": "currency", "name": "USD"}, "amount": "0.00"},
                            "create_time": "2023-11-14T22:13:30Z"
                          }]
                        }
                        """.trimIndent(),
                    KrakenApiConstants.PATH_DEPOSIT_STATUS to """{"error":[],"result":[]}""",
                    KrakenApiConstants.PATH_FUNDING_METHODS_DEPOSIT to
                        """
                        {
                          "methods": [
                            {"method_id": "method-1", "method_name": "Wire"},
                            {"method_id": "method-2", "method_name": "ACH"}
                          ]
                        }
                        """.trimIndent(),
                )
                var methodListRequests = 0
                val client = HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath == KrakenApiConstants.PATH_FUNDING_METHODS_DEPOSIT) {
                            methodListRequests++
                        }
                        respond(
                            content = responses.getValue(request.url.encodedPath),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val service = KrakenServiceImpl(configService, objectMapper, client)

                val deposits = service.getDepositStatus()

                deposits.map { it.method } shouldBe listOf("Wire", "ACH")
                methodListRequests shouldBe 1
            }
        }

        "getFundingHistory_fails_closed_when_pagination_does_not_terminate" {
            runTest {
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(
                        TestConstants.API_KEY,
                        Base64.getEncoder().encodeToString(TestConstants.API_SECRET.toByteArray()),
                    ),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                var pageCount = 0
                val client = HttpClient(
                    MockEngine {
                        pageCount++
                        respond(
                            content = """{"deposits":[],"next_cursor":"cursor-$pageCount"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val service = KrakenServiceImpl(configService, objectMapper, client)

                shouldThrow<IllegalStateException> { service.getDepositStatus() }
                pageCount shouldBe 20
            }
        }

        "getFundingHistory_degrades_when_legacy_enrichment_fails" {
            runTest {
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(
                        TestConstants.API_KEY,
                        Base64.getEncoder().encodeToString(TestConstants.API_SECRET.toByteArray()),
                    ),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                val responses = mapOf(
                    KrakenApiConstants.PATH_FUNDING_DEPOSITS to
                        """
                        {
                          "deposits": [{
                            "deposit_id": "DEP-LEGACY-FAIL",
                            "method_id": "method-1",
                            "status": "success",
                            "amount": {"asset": {"class": "currency", "name": "USD"}, "amount": "10.00"},
                            "fee": {"asset": {"class": "currency", "name": "USD"}, "amount": "0.00"},
                            "create_time": "2023-11-14T22:13:20Z"
                          }]
                        }
                        """.trimIndent(),
                    KrakenApiConstants.PATH_FUNDING_METHODS_DEPOSIT to
                        """{"methods":[{"method_id":"method-1","method_name":"Wire"}]}""",
                )
                val client = HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath == KrakenApiConstants.PATH_DEPOSIT_STATUS) {
                            respond(
                                content = "legacy unavailable",
                                status = HttpStatusCode.InternalServerError,
                                headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                            )
                        } else {
                            respond(
                                content = responses.getValue(request.url.encodedPath),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                            )
                        }
                    },
                )
                val service = KrakenServiceImpl(configService, objectMapper, client)

                val deposits = service.getDepositStatus(startSec = 100L, endSec = 200L)

                deposits.single().method shouldBe "Wire"
            }
        }

        "getFundingHistory_stops_method_pagination_on_repeated_cursor" {
            runTest {
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(
                        TestConstants.API_KEY,
                        Base64.getEncoder().encodeToString(TestConstants.API_SECRET.toByteArray()),
                    ),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                val responses = mapOf(
                    KrakenApiConstants.PATH_FUNDING_DEPOSITS to
                        """
                        {
                          "deposits": [{
                            "deposit_id": "DEP-CURSOR",
                            "method_id": "method-1",
                            "status": "success",
                            "amount": {"asset": {"class": "currency", "name": "USD"}, "amount": "10.00"},
                            "fee": {"asset": {"class": "currency", "name": "USD"}, "amount": "0.00"},
                            "create_time": "2023-11-14T22:13:20Z"
                          }]
                        }
                        """.trimIndent(),
                    KrakenApiConstants.PATH_DEPOSIT_STATUS to """{"error":[],"result":[]}""",
                    KrakenApiConstants.PATH_FUNDING_METHODS_DEPOSIT to
                        """
                        {
                          "methods": [{"method_id": "method-1", "method_name": "Wire"}],
                          "next_cursor": "same"
                        }
                        """.trimIndent(),
                )
                val client = HttpClient(
                    MockEngine { request ->
                        respond(
                            content = responses.getValue(request.url.encodedPath),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val service = KrakenServiceImpl(configService, objectMapper, client)

                val deposits = service.getDepositStatus()

                deposits.single().method shouldBe "Wire"
            }
        }

        "getFundingHistory_survives_method_list_failures" {
            runTest {
                val objectMapper = jacksonObjectMapper()
                configService = mockk(relaxed = true)
                every { configService.getConfig() } returns AppConfig(
                    kraken = KrakenCredentials(
                        TestConstants.API_KEY,
                        Base64.getEncoder().encodeToString(TestConstants.API_SECRET.toByteArray()),
                    ),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                val responses = mapOf(
                    KrakenApiConstants.PATH_FUNDING_DEPOSITS to
                        """
                        {
                          "deposits": [{
                            "deposit_id": "DEP-NO-METHODS",
                            "method_id": "method-1",
                            "status": "success",
                            "amount": {"asset": {"class": "currency", "name": "USD"}, "amount": "10.00"},
                            "fee": {"asset": {"class": "currency", "name": "USD"}, "amount": "0.00"},
                            "create_time": "2023-11-14T22:13:20Z"
                          }]
                        }
                        """.trimIndent(),
                    KrakenApiConstants.PATH_DEPOSIT_STATUS to """{"error":[],"result":[]}""",
                )
                val client = HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath == KrakenApiConstants.PATH_FUNDING_METHODS_DEPOSIT) {
                            respond(
                                content = "method list unavailable",
                                status = HttpStatusCode.InternalServerError,
                                headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                            )
                        } else {
                            respond(
                                content = responses.getValue(request.url.encodedPath),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                            )
                        }
                    },
                )
                val service = KrakenServiceImpl(configService, objectMapper, client)

                val deposits = service.getDepositStatus()

                deposits.single().method shouldBe null
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
