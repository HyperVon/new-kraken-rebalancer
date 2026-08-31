@file:OptIn(ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import com.gemini.krakenbot.service.impl.PublicRateLimiter
import com.gemini.krakenbot.service.impl.krakenPrivateEndpointCost
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.http.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.math.BigDecimal
import java.util.*
import kotlin.time.Duration.Companion.minutes

class KrakenRetryAndRateLimitTest : KrakenServiceTestBase() {

    private fun configuredService(
        mockEngine: MockEngine,
        publicRateLimiter: PublicRateLimiter = PublicRateLimiter(minIntervalMs = 0),
    ): KrakenServiceImpl {
        val mockConfigService = mockk<ConfigService>(relaxed = true)
        val credentials = KrakenCredentials(
            "k",
            Base64.getEncoder().encodeToString(TestFixtures.SECRET.toByteArray()),
        )
        every { mockConfigService.getConfig() } returns AppConfig(
            credentials,
            TestFixtures.settings(dryRun = false, loopDelaySeconds = 60),
            emptyList(),
        )
        return KrakenServiceImpl(
            configService = mockConfigService,
            objectMapper = jacksonObjectMapper(),
            httpClient = HttpClient(mockEngine),
            publicRateLimiter = publicRateLimiter,
        )
    }

    init {
        "permanentHttpFailure_failsFastWithoutRetry" {
            runTest {
                var attempt = 0
                val service = configuredService(
                    MockEngine {
                        attempt++
                        respond(
                            content = "unauthorized",
                            status = HttpStatusCode.Unauthorized,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )

                shouldThrow<ResponseException> { service.getBalances() }
                attempt shouldBe 1
                currentTime shouldBe 0L
            }
        }

        "transientHttp5xx_retriesWithCappedClassifier" {
            runTest {
                var attempt = 0
                val service = configuredService(
                    MockEngine {
                        if (attempt++ == 0) {
                            respond(
                                content = "server error",
                                status = HttpStatusCode.InternalServerError,
                                headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                            )
                        } else {
                            respond(
                                content = "{\"error\":[],\"result\":{\"XXBTZUSD\":63000.0}}",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                            )
                        }
                    },
                )

                val balances = service.getBalances()
                balances[TestFixtures.XXBTZUSD]!!.shouldBeEqualComparingTo(BigDecimal("63000.0"))
                attempt shouldBe 2
                currentTime shouldBe 2_000L
            }
        }

        "transientPublicHttp5xx_retriesWithCappedClassifier" {
            runTest {
                var attempt = 0
                val service = configuredService(
                    MockEngine {
                        if (attempt++ == 0) {
                            respond(
                                content = "server error",
                                status = HttpStatusCode.InternalServerError,
                                headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                            )
                        } else {
                            respond(
                                content =
                                "{\"error\":[],\"result\":{\"XXBTZUSD\":[[1700000000,\"1\",\"2\",\"3\",\"50000.0\",\"4\",\"5\",6]],\"last\":1700000000}}",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                            )
                        }
                    },
                )

                service.getOHLC(TestFixtures.XXBTZUSD, 1440, null).size shouldBe 1
                attempt shouldBe 2
                currentTime shouldBe 2_000L
            }
        }

        "http429_usesRateLimitBackoff" {
            runTest {
                var attempt = 0
                val service = configuredService(
                    MockEngine {
                        if (attempt++ == 0) {
                            respond(
                                content = "rate limited",
                                status = HttpStatusCode.TooManyRequests,
                                headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                            )
                        } else {
                            respond(
                                content = "{\"error\":[],\"result\":{\"XXBTZUSD\":63000.0}}",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                            )
                        }
                    },
                )

                val balances = service.getBalances()
                balances[TestFixtures.XXBTZUSD]!!.shouldBeEqualComparingTo(BigDecimal("63000.0"))
                attempt shouldBe 2
                currentTime shouldBe 10_000L
            }
        }

        "retryOnTransientFailure_SucceedsOnSecondAttempt" {
            runTest {
                var attempt = 0
                val mockEngine = MockEngine {
                    if (attempt++ == 0) {
                        respond(
                            content = "{\"error\":[\"EAPI:Rate limit exceeded\"]}",
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
                    TestFixtures.settings(dryRun = false, loopDelaySeconds = 60),
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
                val mockEngine = MockEngine {
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
                    TestFixtures.settings(dryRun = false, loopDelaySeconds = 60),
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
                val mockEngine = MockEngine {
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
                    TestFixtures.settings(dryRun = false, loopDelaySeconds = 60),
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
                val mockEngine = MockEngine {
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
                    TestFixtures.settings(dryRun = false, loopDelaySeconds = 60),
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
                val mockEngine = MockEngine {
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
                    TestFixtures.settings(dryRun = false, loopDelaySeconds = 60),
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
                val mockEngine = MockEngine {
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
                    TestFixtures.settings(dryRun = false, loopDelaySeconds = 60),
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
                val mockEngine = MockEngine {
                    if (attempt++ == 0) {
                        throw IOException("Simulated transient network failure")
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
                    TestFixtures.settings(dryRun = false, loopDelaySeconds = 60),
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
                service.getLastTradeHistoryTotalCount() shouldBe 0
            }
        }

        "getOHLC_Error" {
            runTest {
                val service = createService("invalid-json")
                shouldThrow<RuntimeException> { service.getOHLC(TestFixtures.XXBTZUSD, 1440, null) }
            }
        }

        "CQ-12-1: getOHLC rethrows coroutine cancellation" {
            runTest {
                val cancellation = CancellationException("OHLC request cancelled")
                val mockEngine = MockEngine { throw cancellation }
                val service = KrakenServiceImpl(
                    configService = mockk(relaxed = true),
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(mockEngine),
                )

                val thrown = shouldThrow<CancellationException> {
                    service.getOHLC(TestFixtures.XXBTZUSD, 1440, null)
                }

                thrown shouldBe cancellation
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

        "getOHLC_SkipsUnparseableClosePrice" {
            runTest {
                val responseJson = "{\"error\":[],\"result\":{\"XXBTZUSD\":[\"not-an-array\", " +
                    "[1616662800,\"52000.0\",\"53000.0\",\"51000.0\",\"invalid-price\",\"52200.0\",\"100.5\",1234]]}}"
                val service = createService(responseJson) as KrakenServiceImpl
                val ohlc = service.getOHLC(TestFixtures.XXBTZUSD, 1440, null)
                ohlc.isEmpty().shouldBeTrue()
            }
        }

        "queryPrivate_RateLimiter_ThrottlesCorrectly" {
            runTest {
                var callCount = 0
                val mockEngine = MockEngine {
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
                    TestFixtures.settings(dryRun = false, loopDelaySeconds = 60),
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

        "queryPrivate_TradesHistory_acquiresWithCost4" {
            runTest {
                val limiter = RecordingRateLimiter()
                val responseJson = """
                    {"error":[],"result":{"trades":{},"count":0}}
                """.trimIndent()
                val service = createService(responseJson, limiter)

                service.getTradeHistory()

                limiter.acquiredCosts shouldBe listOf(4.0)
            }
        }

        "krakenPrivateEndpointCost_TradesHistory_Ledgers_ClosedOrders_are4" {
            krakenPrivateEndpointCost(KrakenApiConstants.PATH_TRADES_HISTORY) shouldBe 4.0
            krakenPrivateEndpointCost("/0/private/Ledgers") shouldBe 4.0
            krakenPrivateEndpointCost("/0/private/ClosedOrders") shouldBe 4.0
            KrakenApiConstants.SUBSTRING_TRADES_HISTORY shouldBe "TradesHistory"
            KrakenApiConstants.SUBSTRING_LEDGERS shouldBe "Ledgers"
            KrakenApiConstants.SUBSTRING_CLOSED_ORDERS shouldBe "ClosedOrders"
        }

        "krakenPrivateEndpointCost_Balance_is1_AddOrder_is0" {
            krakenPrivateEndpointCost(KrakenApiConstants.PATH_BALANCE) shouldBe 1.0
            krakenPrivateEndpointCost(KrakenApiConstants.PATH_BALANCE_EX) shouldBe 1.0
            krakenPrivateEndpointCost(KrakenApiConstants.PATH_ADD_ORDER) shouldBe 0.0
        }
    }
}
