package com.gemini.krakenbot.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import io.kotest.assertions.throwables.shouldThrow
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
import java.math.BigDecimal
import java.util.*

class KrakenTradeHistoryTest : KrakenServiceTestBase() {

    init {
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
                first.volume.shouldBeEqualComparingTo(BigDecimal("0.1"))
                first.usdAmount.shouldBeEqualComparingTo(BigDecimal("5000.00"))
                first.timestamp.toEpochMilli() shouldBe 1700000000123L
                first.orderTxid shouldBe "O1"
                first.tradeId shouldBe "T1"
            }
        }

        "getTradeHistory_BlankApiKey" {
            runTest {
                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val config = AppConfig(
                    kraken = KrakenCredentials("", ""),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                every { mockConfigService.getConfig() } returns config

                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(MockEngine { respond("") }),
                )

                shouldThrow<KrakenCredentialsUnavailableException> { service.getTradeHistory() }
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
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                every { mockConfigService.getConfig() } returns config

                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(MockEngine { respond("") }),
                )

                shouldThrow<KrakenCredentialsUnavailableException> { service.getTradeHistory() }
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
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                every { mockConfigService.getConfig() } returns config

                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(MockEngine { respond("") }),
                )

                shouldThrow<KrakenCredentialsUnavailableException> { service.getTradeHistory() }
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

                trades.size shouldBe 3

                val ethTrade = trades.first { it.pair == "XETHZUSD" }
                ethTrade.symbol shouldBe "ETH"
                ethTrade.volume.shouldBeEqualComparingTo(BigDecimal("0.1"))
                ethTrade.usdAmount.shouldBeEqualComparingTo(BigDecimal("200.00"))

                val dogeTrade = trades.first { it.pair == "XXDGZUSD" }
                dogeTrade.symbol shouldBe "DOGE"
                dogeTrade.volume.shouldBeEqualComparingTo(BigDecimal("100"))
                dogeTrade.usdAmount.shouldBeEqualComparingTo(BigDecimal("10.00"))

                val ltcTrade = trades.first { it.pair == "XLTCZUSD" }
                ltcTrade.symbol shouldBe "LTC"
                ltcTrade.volume.shouldBeEqualComparingTo(BigDecimal("1.00000000"))
                ltcTrade.usdAmount.shouldBeEqualComparingTo(BigDecimal("100.00"))
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
                val mockEngine = MockEngine {
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
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
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

        "getTradeHistory_WithStartEndAndOffset" {
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
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                every { mockConfigService.getConfig() } returns config

                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(mockEngine),
                )

                service.getTradeHistoryUntil(startSec = 1700000000L, offset = 50, endSec = 1700000100L)

                capturedBody.shouldNotBeNull()
                capturedBody.contains("start=1700000000") shouldBe true
                capturedBody.contains("end=1700000100") shouldBe true
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
                first.volume.shouldBeEqualComparingTo(BigDecimal.ZERO)
                first.usdAmount.shouldBeEqualComparingTo(BigDecimal.ZERO)
            }
        }

        "getTradeHistory_GenericUsdPairResolution_ResolvesAllTrades" {
            runTest {
                // When configured for SOL, trades for other USD pairs (BTC, ETH, DOGE, LTC) still resolve generically.
                val responseJson = """
                    {
                        "error": [],
                        "result": {
                            "trades": {
                                "T1": {
                                    "pair": "XXBTZUSD",
                                    "time": 1700000000.1234,
                                    "type": "buy",
                                    "price": "30000.00",
                                    "cost": "300.00",
                                    "vol": "0.01000000"
                                },
                                "T2": {
                                    "pair": "XETHZUSD",
                                    "time": 1700000001.1234,
                                    "type": "buy",
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
                val config = AppConfig(
                    kraken = credentials,
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
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

                trades.size shouldBe 4
                trades.any { it.symbol == "BTC" }.shouldBeTrue()
                trades.any { it.symbol == "ETH" }.shouldBeTrue()
                trades.any { it.symbol == "DOGE" }.shouldBeTrue()
                trades.any { it.symbol == "LTC" }.shouldBeTrue()
            }
        }

        "getTradeHistory_CalledWithDefaults" {
            runTest {
                // Call via KrakenService so Kotlin interface default args (startSec/offset) are exercised.
                val responseJson = """{"error":[],"result":{"trades":{}}}"""
                val service: KrakenService = createService(responseJson)
                val trades = service.getTradeHistory()
                trades.isEmpty().shouldBeTrue()
            }
        }

        "getTradeHistory_FallbackSymbols_AlternativePairNames" {
            runTest {
                // Alt pair names BTCUSD / DOGEUSD (not XXBT / XXDG tickers) still resolve via fallbacks.
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
                val config = AppConfig(
                    kraken = credentials,
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
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
                // Pair "BTCUSD" matches allocation symbol "BTC" even though the Kraken ticker is "XBT".
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

                val service = createService(responseJson)
                val trades = service.getTradeHistory()
                trades.size shouldBe 1
                trades.first().symbol shouldBe "BTC"
            }
        }
    }
}
