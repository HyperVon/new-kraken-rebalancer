package com.gemini.krakenbot.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.IsolationMode
import io.mockk.every
import io.mockk.mockk
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.util.Base64
import io.kotest.matchers.booleans.shouldBeFalse
import java.util.Collections
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

        val credentials = KrakenCredentials("public-key", Base64.getEncoder().encodeToString("secret-key".toByteArray()))
        val settings = Settings(60L, 2.0, 1.0, false, 0.0, 1.0)
        val config = AppConfig(credentials, settings, emptyList())
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
                val responseJson = "{\"error\":[],\"result\":{\"XXBTZUSD\":63000.0,\"XETHZUSD\":3000.0,\"USD\":5000.0}}"
                val service = createService(responseJson)

                val balances = service.getBalances()

                (balances["XXBTZUSD"]) shouldBe 63000.0
                (balances["XETHZUSD"]) shouldBe 3000.0
                (balances["USD"]) shouldBe 5000.0
            }
        }

        "getTickerPrices_Success" {
            runTest {
                val responseJson = "{\"error\":[],\"result\":{\"XXBTZUSD\":{\"c\":[\"65000.0\"]},\"XETHZUSD\":{\"c\":[\"3200.0\"]}}}"
                val service = createService(responseJson)

                val prices = service.getTickerPrices("XXBTZUSD,XETHZUSD")

                (prices["XXBTZUSD"]) shouldBe 65000.0
                (prices["XETHZUSD"]) shouldBe 3200.0
            }
        }

        "executeOrder_Success" {
            runTest {
                val responseJson = "{\"error\":[],\"result\":{\"descr\":{\"order\":\"buy 0.1 XBTUSD @ limit 50000\"},\"txid\":[\"THVR-...-TC\"]}}"
                val service = createService(responseJson)

                val result = service.executeOrder("XBTUSD", "limit", "buy", BigDecimal("0.1"))
                result.success.shouldBeTrue()
            }
        }

        "executeOrder_DryRun" {
            runTest {
                val service = createService("")
                val settings = Settings(60L, 2.0, 1.0, true, 0.0, 1.0)
                val config = AppConfig(KrakenCredentials("k", "s"), settings, emptyList())
                every { configService.getConfig() } returns config

                val result = service.executeOrder("XBTUSD", "limit", "buy", BigDecimal("0.1"))
                result.success.shouldBeTrue()
                result.dryRun.shouldBeTrue()
            }
        }

        "getTickerPrices_Malformed" {
            runTest {
                val responseJson = "{\"error\":[],\"result\":{\"XXBTZUSD\":{\"c\":[]}, \"XETHZUSD\":{}}}"
                val service = createService(responseJson)

                val prices = service.getTickerPrices("XXBTZUSD,XETHZUSD")
                prices.isEmpty().shouldBeTrue()
            }
        }

        "queryPublic_ErrorResponse" {
            runTest {
                val responseJson = "{\"error\":[\"EQuery:Unknown asset pair\"]}"
                val service = createService(responseJson)

                shouldThrow<RuntimeException> { service.getTickerPrices("INVALID") }
            }
        }

        "queryPublic_JsonProcessingException" {
            runTest {
                val service = createService("{invalid-json")
                shouldThrow<RuntimeException> { service.getTickerPrices("XBTUSD") }
            }
        }

        "executeOrder_ApiError" {
            runTest {
                val responseJson = "{\"error\":[\"EOrder:Insufficient funds\"]}"
                val service = createService(responseJson)

                val result = service.executeOrder("XBTUSD", "limit", "buy", BigDecimal.ONE)
                result.success.shouldBeFalse()
                result.errorMessage.shouldNotBeNull()
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
                service::class.java.getDeclaredField("nonceGenerator").apply { isAccessible = true }.get(service)
                 as AtomicLong
            (nonceGen).shouldNotBeNull()

            val numThreads = 10
            val incrementsPerThread = 1000
            val generatedNonces = Collections.synchronizedSet(mutableSetOf<Long>())

            val executor = Executors.newFixedThreadPool(numThreads)
            val latch = CountDownLatch(numThreads)

            for (i in 0 until numThreads) {
                executor.submit {
                    try {
                        for (j in 0 until incrementsPerThread) {
                            generatedNonces.add(nonceGen.incrementAndGet())
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }

            (latch.await(5, TimeUnit.SECONDS).shouldBeTrue())
            executor.shutdown()

            generatedNonces.size shouldBe (numThreads * incrementsPerThread)
        }

        "queryPrivate_ApiKeyNull" {
            runTest {
                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val config = AppConfig(KrakenCredentials("", "secret"), Settings(60L, 2.0, 1.0, false, 0.0, 1.0), emptyList())
                every { mockConfigService.getConfig() } returns config

                val mockEngine = MockEngine { respond(content = "") }
                val localService = KrakenServiceImpl(mockConfigService, jacksonObjectMapper(), HttpClient(mockEngine))

                val ex = shouldThrow<RuntimeException> { localService.getBalances() }
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
                    KrakenCredentials("apiKey", "invalid_base64_!@#$"),
                    Settings(60L, 2.0, 1.0, false, 0.0, 1.0),
                    emptyList()
                )
                every { mockConfigService.getConfig() } returns config

                val mockEngine = MockEngine { respond(content = "") }
                val localService = KrakenServiceImpl(mockConfigService, jacksonObjectMapper(), HttpClient(mockEngine))

                shouldThrow<RuntimeException> { localService.getBalances() }
            }
        }

        "queryPrivate_InvalidNonce_RetrySuccess" {
            runTest {
                val errorJson = "{\"error\":[\"EAPI:Invalid nonce\"]}"
                val successJson = "{\"error\":[],\"result\":{\"XXBTZUSD\":63000.0}}"
                var attempt = 0
                val mockEngine = MockEngine { request ->
                    val content = if (attempt++ == 0) errorJson else successJson
                    respond(
                        content = content,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }

                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val validSecret = Base64.getEncoder().encodeToString("secret".toByteArray())
                val config = AppConfig(KrakenCredentials("k", validSecret), Settings(60L, 2.0, 1.0, false, 0.0, 1.0), emptyList())
                every { mockConfigService.getConfig() } returns config

                val service = KrakenServiceImpl(mockConfigService, jacksonObjectMapper(), HttpClient(mockEngine))
                
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
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }

                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val validSecret = Base64.getEncoder().encodeToString("secret".toByteArray())
                val config = AppConfig(KrakenCredentials("k", validSecret), Settings(60L, 2.0, 1.0, false, 0.0, 1.0), emptyList())
                every { mockConfigService.getConfig() } returns config

                val service = KrakenServiceImpl(mockConfigService, jacksonObjectMapper(), HttpClient(mockEngine))
                
                val ex = shouldThrow<RuntimeException> { service.getBalances() }
                ex.message?.contains("Invalid nonce")?.shouldBeTrue()
            }
        }
    }
}
