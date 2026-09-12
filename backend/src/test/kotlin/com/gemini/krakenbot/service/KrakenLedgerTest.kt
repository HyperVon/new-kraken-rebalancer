package com.gemini.krakenbot.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.service.impl.KrakenApiPermissionDeniedException
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import com.gemini.krakenbot.test.TestConstants
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.util.Base64

@Suppress("unused")
class KrakenLedgerTest : KrakenServiceTestBase() {

    init {
        "getLedgers_ParsesEntriesAndTracksCount" {
            runTest {
                val responseJson = """
                    {
                        "error": [],
                        "result": {
                            "ledger": {
                                "L1": {
                                    "refid": "R1",
                                    "time": 1700000000.1234,
                                    "type": "staking",
                                    "subtype": "reward",
                                    "aclass": "currency",
                                    "asset": "XXBT",
                                    "amount": "0.10000000",
                                    "fee": "0.00000000",
                                    "balance": "10.50000000"
                                },
                                "L2": {
                                    "refid": "R2",
                                    "time": 1700000300.0000,
                                    "type": "dividend",
                                    "aclass": "currency",
                                    "asset": "STRC",
                                    "amount": "1.25000000",
                                    "fee": "0.01000000",
                                    "balance": "12.75000000"
                                }
                            },
                            "count": 2
                        }
                    }
                """.trimIndent()
                val service = createService(responseJson)
                val entries = service.getLedgers()

                entries.size shouldBe 2
                val staking = entries.first { it.type == KrakenApiConstants.LEDGER_TYPE_STAKING }
                staking.ledgerId shouldBe "L1"
                staking.refid shouldBe "R1"
                staking.time.toEpochMilli() shouldBe 1700000000123L
                staking.subtype shouldBe "reward"
                staking.aclass shouldBe "currency"
                staking.asset shouldBe "BTC"
                staking.amount.shouldBeEqualComparingTo(BigDecimal("0.1"))
                staking.fee.shouldBeEqualComparingTo(BigDecimal("0"))
                staking.balance.shouldBeEqualComparingTo(BigDecimal("10.5"))
                staking.hasAuthoritativeBalance shouldBe true
                val dividend = entries.first { it.type == KrakenApiConstants.LEDGER_TYPE_DIVIDEND }
                dividend.ledgerId shouldBe "L2"
                dividend.refid shouldBe "R2"
                dividend.subtype.shouldBeNull()
                dividend.aclass shouldBe "currency"
                dividend.asset shouldBe "STRC"
                service.getLastLedgerTotalCount() shouldBe 2
            }
        }

        fun configureFundingCredentials() {
            configService = mockk(relaxed = true)
            every { configService.getConfig() } returns AppConfig(
                kraken = KrakenCredentials(
                    TestConstants.API_KEY,
                    Base64.getEncoder().encodeToString(TestConstants.API_SECRET.toByteArray()),
                ),
                settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                allocations = emptyList(),
            )
        }

        "getDepositStatus_UsesFundingApiAndEnrichesFromLegacyStatus" {
            runTest {
                configureFundingCredentials()
                val requests = mutableListOf<HttpRequestData>()
                val client = HttpClient(
                    MockEngine { request ->
                        requests += request
                        val content =
                            if (request.url.encodedPath == KrakenApiConstants.PATH_FUNDING_DEPOSITS) {
                                """
                                {
                                  "deposits": [{
                                    "deposit_id": "DEP-1",
                                    "method_id": "method-1",
                                    "status": "success",
                                    "amount": {"asset": {"class": "currency", "name": "USD"}, "amount": "100.00"},
                                    "fee": {"asset": {"class": "currency", "name": "USD"}, "amount": "0.00"},
                                    "create_time": "2023-11-14T22:13:20Z"
                                  }]
                                }
                                """.trimIndent()
                            } else {
                                """
                                {
                                  "error": [],
                                  "result": [{
                                    "method": "Wire",
                                    "asset": "ZUSD",
                                    "refid": "DEP-1",
                                    "txid": "wire-1",
                                    "amount": "100.00",
                                    "fee": "0.00",
                                    "time": 1700000000,
                                    "status": "Success"
                                  }]
                                }
                                """.trimIndent()
                            }
                        respond(
                            content = content,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val service = KrakenServiceImpl(configService, jacksonObjectMapper(), client, RecordingRateLimiter())

                val records = service.getDepositStatus(startSec = 1700000000L, endSec = 1700003600L)

                val fundingRequest = requests.first {
                    it.url.encodedPath == KrakenApiConstants.PATH_FUNDING_DEPOSITS
                }
                fundingRequest.url.parameters["start_time"] shouldBe "2023-11-14T22:13:20Z"
                fundingRequest.url.parameters["end_time"] shouldBe "2023-11-14T23:13:20Z"
                fundingRequest.url.parameters["limit"] shouldBe "500"
                val enrichmentRequest = requests.first {
                    it.url.encodedPath == KrakenApiConstants.PATH_DEPOSIT_STATUS
                }
                val enrichmentBody = (enrichmentRequest.body as TextContent).text
                enrichmentBody shouldContain "start=1700000000"
                enrichmentBody shouldContain "end=1700003600"
                enrichmentBody shouldContain "limit=500"
                enrichmentBody.contains("cursor").shouldBeFalse()
                records.single().refid shouldBe "DEP-1"
                records.single().asset shouldBe "USD"
                records.single().method shouldBe "Wire"
                records.single().txid shouldBe "wire-1"
                records.single().status shouldBe "success"
                records.single().hasAuthoritativeFee shouldBe true
            }
        }

        "getWithdrawStatus_UsesFundingApiAndEnrichesFromLegacyStatus" {
            runTest {
                configureFundingCredentials()
                val requests = mutableListOf<HttpRequestData>()
                val client = HttpClient(
                    MockEngine { request ->
                        requests += request
                        val content =
                            if (request.url.encodedPath == KrakenApiConstants.PATH_FUNDING_WITHDRAWALS) {
                                """
                                {
                                  "withdrawals": [{
                                    "withdrawal_id": "W-1",
                                    "method_id": "method-w1",
                                    "status": "success",
                                    "amount": {"asset": {"class": "currency", "name": "XXBT"}, "amount": "0.25"},
                                    "fee": {"asset": {"class": "currency", "name": "XXBT"}, "amount": "0.0002"},
                                    "create_time": "2023-11-14T22:13:20Z"
                                  }]
                                }
                                """.trimIndent()
                            } else {
                                """
                                {
                                  "error": [],
                                  "result": [{
                                    "method": "Bitcoin",
                                    "asset": "XXBT",
                                    "refid": "W-1",
                                    "txid": "tx-1",
                                    "amount": "0.25",
                                    "fee": "0.0002",
                                    "time": 1700000000,
                                    "status": "Pending"
                                  }]
                                }
                                """.trimIndent()
                            }
                        respond(
                            content = content,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val service = KrakenServiceImpl(configService, jacksonObjectMapper(), client, RecordingRateLimiter())

                val records = service.getWithdrawStatus()

                requests.first {
                    it.url.encodedPath == KrakenApiConstants.PATH_FUNDING_WITHDRAWALS
                }
                records.single().refid shouldBe "W-1"
                records.single().asset shouldBe "BTC"
                records.single().method shouldBe "Bitcoin"
                records.single().txid shouldBe "tx-1"
                records.single().status shouldBe "success"
                records.single().fee shouldBeEqualComparingTo BigDecimal("0.0002")
            }
        }

        "funding history pagination follows next cursor with cursor-only next page" {
            runTest {
                configureFundingCredentials()
                val fundingRequests = mutableListOf<HttpRequestData>()
                var depositPage = 0
                val client = HttpClient(
                    MockEngine { request ->
                        val content = when (request.url.encodedPath) {
                            KrakenApiConstants.PATH_FUNDING_DEPOSITS -> {
                                fundingRequests += request
                                depositPage++
                                if (depositPage == 1) {
                                    """
                                    {
                                      "deposits": [{
                                        "deposit_id": "DEP-1",
                                        "method_id": "method-1",
                                        "status": "success",
                                        "amount": {"asset": {"class": "currency", "name": "USD"}, "amount": "100.00"},
                                        "fee": {"asset": {"class": "currency", "name": "USD"}, "amount": "0.00"},
                                        "create_time": "2023-11-14T22:13:20Z"
                                      }],
                                      "next_cursor": "next-page"
                                    }
                                    """.trimIndent()
                                } else {
                                    """
                                    {
                                      "deposits": [{
                                        "deposit_id": "DEP-2",
                                        "method_id": "method-2",
                                        "status": "success",
                                        "amount": {"asset": {"class": "currency", "name": "USD"}, "amount": "50.00"},
                                        "fee": {"asset": {"class": "currency", "name": "USD"}, "amount": "0.00"},
                                        "create_time": "2023-11-14T22:14:20Z"
                                      }]
                                    }
                                    """.trimIndent()
                                }
                            }

                            else -> """
                                {
                                  "error": [],
                                  "result": [
                                    {
                                      "method": "Wire", "asset": "USD", "refid": "DEP-1", "txid": "tx-1",
                                      "amount": "100.00", "fee": "0.00", "time": 1700000000, "status": "Success"
                                    },
                                    {
                                      "method": "Wire", "asset": "USD", "refid": "DEP-2", "txid": "tx-2",
                                      "amount": "50.00", "fee": "0.00", "time": 1700000060, "status": "Success"
                                    }
                                  ]
                                }
                            """.trimIndent()
                        }
                        respond(
                            content = content,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val service = KrakenServiceImpl(configService, jacksonObjectMapper(), client, RecordingRateLimiter())

                val records = service.getDepositStatus()

                records.map { it.refid } shouldBe listOf("DEP-1", "DEP-2")
                fundingRequests.size shouldBe 2
                fundingRequests[1].url.parameters["cursor"] shouldBe "next-page"
                fundingRequests[1].url.parameters["start_time"] shouldBe null
                fundingRequests[1].url.parameters["limit"] shouldBe null
            }
        }

        "funding history fails closed when a page cannot be fully parsed" {
            runTest {
                configureFundingCredentials()
                val client = HttpClient(
                    MockEngine {
                        respond(
                            content = """
                                {
                                  "deposits": [
                                    {
                                      "deposit_id": "DEP-1",
                                      "method_id": "method-1",
                                      "status": "success",
                                      "amount": {"asset": {"class": "currency", "name": "USD"}, "amount": "100.00"},
                                      "fee": {"asset": {"class": "currency", "name": "USD"}, "amount": "0.00"},
                                      "create_time": "2023-11-14T22:13:20Z"
                                    },
                                    {
                                      "deposit_id": "DEP-2",
                                      "method_id": "method-2",
                                      "status": "success",
                                      "amount": {"asset": {"class": "currency", "name": "USD"}, "amount": "50.00"},
                                      "fee": {"asset": {"class": "currency", "name": "USD"}, "amount": "0.00"},
                                      "create_time": "not-a-time"
                                    }
                                  ]
                                }
                            """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val service = KrakenServiceImpl(configService, jacksonObjectMapper(), client, RecordingRateLimiter())

                shouldThrow<IllegalStateException> {
                    service.getDepositStatus()
                }
            }
        }

        "funding method list resolves records unknown to legacy enrichment" {
            runTest {
                configureFundingCredentials()
                val requests = mutableListOf<HttpRequestData>()
                val client = HttpClient(
                    MockEngine { request ->
                        requests += request
                        val content = when (request.url.encodedPath) {
                            KrakenApiConstants.PATH_FUNDING_DEPOSITS -> """
                                {
                                  "deposits": [{
                                    "deposit_id": "DEP-1",
                                    "method_id": "method-1",
                                    "status": "success",
                                    "amount": {"asset": {"class": "currency", "name": "USD"}, "amount": "100.00"},
                                    "fee": {"asset": {"class": "currency", "name": "USD"}, "amount": "0.00"},
                                    "create_time": "2023-11-14T22:13:20Z"
                                  }]
                                }
                            """.trimIndent()

                            KrakenApiConstants.PATH_FUNDING_METHODS_DEPOSIT -> """
                                {
                                  "methods": [{
                                    "method_id": "method-1",
                                    "method_name": "ACH (Plaid Transfer, via Plaid)"
                                  }]
                                }
                            """.trimIndent()

                            else -> "{\"error\":[],\"result\":[]}"
                        }
                        respond(
                            content = content,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val service = KrakenServiceImpl(configService, jacksonObjectMapper(), client, RecordingRateLimiter())

                val records = service.getDepositStatus()

                requests.any {
                    it.url.encodedPath == KrakenApiConstants.PATH_FUNDING_METHODS_DEPOSIT
                } shouldBe true
                records.single().method shouldBe "ACH (Plaid Transfer, via Plaid)"
            }
        }

        "legacy enrichment truncation is not trusted" {
            runTest {
                configureFundingCredentials()
                val truncatedRecords = (1..500).joinToString(",") { index ->
                    """
                    {
                      "method": "Wire", "asset": "USD", "refid": "LEGACY-$index", "txid": "tx-$index",
                      "amount": "1.00", "fee": "0.00", "time": 1700000000, "status": "Success"
                    }
                    """.trimIndent()
                }
                val client = HttpClient(
                    MockEngine { request ->
                        val content = when (request.url.encodedPath) {
                            KrakenApiConstants.PATH_FUNDING_DEPOSITS -> """
                                {
                                  "deposits": [{
                                    "deposit_id": "DEP-1",
                                    "method_id": "method-1",
                                    "status": "success",
                                    "amount": {"asset": {"class": "currency", "name": "USD"}, "amount": "100.00"},
                                    "fee": {"asset": {"class": "currency", "name": "USD"}, "amount": "0.00"},
                                    "create_time": "2023-11-14T22:13:20Z"
                                  }]
                                }
                            """.trimIndent()

                            KrakenApiConstants.PATH_FUNDING_METHODS_DEPOSIT -> """
                                {
                                  "methods": [{
                                    "method_id": "method-1",
                                    "method_name": "ACH (Plaid Transfer, via Plaid)"
                                  }]
                                }
                            """.trimIndent()

                            else -> "{\"error\":[],\"result\":[$truncatedRecords]}"
                        }
                        respond(
                            content = content,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val service = KrakenServiceImpl(configService, jacksonObjectMapper(), client, RecordingRateLimiter())

                val records = service.getDepositStatus()

                records.single().method shouldBe "ACH (Plaid Transfer, via Plaid)"
            }
        }

        "funding history fails closed on a repeated cursor" {
            runTest {
                configureFundingCredentials()
                val client = HttpClient(
                    MockEngine {
                        respond(
                            content = """
                                {
                                  "deposits": [],
                                  "next_cursor": "repeat"
                                }
                            """.trimIndent(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val service = KrakenServiceImpl(configService, jacksonObjectMapper(), client, RecordingRateLimiter())

                shouldThrow<IllegalStateException> {
                    service.getDepositStatus()
                }
            }
        }

        "funding history permission errors preserve the denied endpoint" {
            runTest {
                configureFundingCredentials()
                val depositClient = HttpClient(
                    MockEngine {
                        respond(
                            content = "{\"error\":[\"EGeneral:Permission denied\"]}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val depositService = KrakenServiceImpl(
                    configService,
                    jacksonObjectMapper(),
                    depositClient,
                    RecordingRateLimiter(),
                )

                val depositError = shouldThrow<KrakenApiPermissionDeniedException> {
                    depositService.getDepositStatus()
                }
                depositError.endpoint shouldBe KrakenApiConstants.PATH_FUNDING_DEPOSITS

                val withdrawalClient = HttpClient(
                    MockEngine {
                        respond(
                            content = "{\"error\":[\"EGeneral:Permission denied\"]}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, TestFixtures.APPLICATION_JSON),
                        )
                    },
                )
                val withdrawalService = KrakenServiceImpl(
                    configService,
                    jacksonObjectMapper(),
                    withdrawalClient,
                    RecordingRateLimiter(),
                )
                val withdrawalError = shouldThrow<KrakenApiPermissionDeniedException> {
                    withdrawalService.getWithdrawStatus()
                }
                withdrawalError.endpoint shouldBe KrakenApiConstants.PATH_FUNDING_WITHDRAWALS
            }
        }

        "getLedgers_FiltersByRequestedTypes" {
            runTest {
                val responseJson = """
                    {
                        "error": [],
                        "result": {
                            "ledger": {
                                "L1": {
                                    "refid": "R1",
                                    "time": 1700000000.1234,
                                    "type": "staking",
                                    "asset": "XBT",
                                    "amount": "0.10000000",
                                    "fee": "0.00000000",
                                    "balance": "10.50000000"
                                },
                                "L2": {
                                    "refid": "R2",
                                    "time": 1700000300.0000,
                                    "type": "dividend",
                                    "asset": "STRC",
                                    "amount": "1.25000000",
                                    "fee": "0.01000000",
                                    "balance": "12.75000000"
                                }
                            },
                            "count": 2
                        }
                    }
                """.trimIndent()
                val service = createService(responseJson)
                val entries = service.getLedgers(types = setOf(KrakenApiConstants.LEDGER_TYPE_STAKING))

                entries.size shouldBe 1
                entries.first().type shouldBe KrakenApiConstants.LEDGER_TYPE_STAKING
                service.getLastLedgerTotalCount() shouldBe 2
            }
        }

        "getLedgers_SendsRequestedTypesToApi" {
            runTest {
                val responseJson = """
                    {
                        "error": [],
                        "result": {
                            "count": 0
                        }
                    }
                """.trimIndent()
                var capturedBody = ""
                val service = createService(responseJson) { request ->
                    capturedBody = (request.body as TextContent).text
                }
                service.getLedgers(types = setOf(KrakenApiConstants.LEDGER_TYPE_STAKING))

                capturedBody shouldContain "type=staking"
            }
        }

        "getLedgers_QueriesSaleForConsumerLedgerTypesAndFiltersReturnedRows" {
            runTest {
                val responseJson = """
                    {
                        "error": [],
                        "result": {
                            "ledger": {
                                "SPEND-1": {
                                    "refid": "BUY-1",
                                    "time": 1700000000.0000,
                                    "type": "spend",
                                    "asset": "ZUSD",
                                    "amount": "-5000.00000000",
                                    "fee": "10.00000000",
                                    "balance": "4990.00000000"
                                },
                                "RECEIVE-1": {
                                    "refid": "BUY-1",
                                    "time": 1700000000.0000,
                                    "type": "receive",
                                    "asset": "XXBT",
                                    "amount": "0.10000000",
                                    "fee": "0.00100000",
                                    "balance": "0.09900000"
                                },
                                "TRADE-1": {
                                    "refid": "TRADE-1",
                                    "time": 1700000000.0000,
                                    "type": "trade",
                                    "asset": "ZUSD",
                                    "amount": "-1.00000000",
                                    "fee": "0.00000000",
                                    "balance": "4989.00000000"
                                }
                            },
                            "count": 3
                        }
                    }
                """.trimIndent()
                var capturedBody = ""
                val service = createService(responseJson) { request ->
                    capturedBody = (request.body as TextContent).text
                }

                val entries = service.getLedgers(types = setOf(KrakenApiConstants.LEDGER_TYPE_SPEND))

                capturedBody shouldContain "type=${KrakenApiConstants.LEDGER_TYPE_SALE}"
                entries.map { it.ledgerId } shouldBe listOf("SPEND-1")
                entries.single().refid shouldBe "BUY-1"
                entries.single().netBalanceDelta().shouldBeEqualComparingTo(BigDecimal("-5010"))
                service.getLastLedgerTotalCount() shouldBe 3
            }
        }

        "getLedgers_QueriesSaleOnceForBothConsumerLedgerTypes" {
            runTest {
                val responseJson = """
                    {
                        "error": [],
                        "result": {
                            "ledger": {
                                "SPEND-1": {
                                    "time": 1700000000.0000,
                                    "type": "spend",
                                    "asset": "ZUSD",
                                    "amount": "-5000.00000000",
                                    "fee": "10.00000000",
                                    "balance": "4990.00000000"
                                },
                                "RECEIVE-1": {
                                    "time": 1700000000.0000,
                                    "type": "receive",
                                    "asset": "XXBT",
                                    "amount": "0.10000000",
                                    "fee": "0.00100000",
                                    "balance": "0.09900000"
                                }
                            },
                            "count": 2
                        }
                    }
                """.trimIndent()
                val requestBodies = mutableListOf<String>()
                val service = createService(responseJson) { request ->
                    requestBodies += (request.body as TextContent).text
                }

                val entries = service.getLedgers(
                    types = setOf(
                        KrakenApiConstants.LEDGER_TYPE_SPEND,
                        KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                    ),
                )

                requestBodies.size shouldBe 1
                requestBodies.single() shouldContain "type=${KrakenApiConstants.LEDGER_TYPE_SALE}"
                entries.map { it.type }.toSet() shouldBe setOf(
                    KrakenApiConstants.LEDGER_TYPE_SPEND,
                    KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                )
                service.getLastLedgerTotalCount() shouldBe 2
            }
        }

        "getLedgers_QueriesAllForEarnAndPromotionRewardTypesAndFiltersReturnedRows" {
            runTest {
                val responseJson = """
                    {
                        "error": [],
                        "result": {
                            "ledger": {
                                "EARN-1": {
                                    "refid": "REWARD-1",
                                    "time": 1700000000.0000,
                                    "type": "earn",
                                    "subtype": "reward",
                                    "asset": "DOT.HOLD",
                                    "amount": "1.50000000",
                                    "fee": "0.00000000",
                                    "balance": "100.00000000"
                                },
                                "PROMO-1": {
                                    "refid": "PROMO-1",
                                    "time": 1700000000.0000,
                                    "type": "reward",
                                    "asset": "XBT",
                                    "amount": "0.01000000",
                                    "fee": "0.00000000",
                                    "balance": "100.01000000"
                                },
                                "TRADE-1": {
                                    "refid": "TRADE-1",
                                    "time": 1700000000.0000,
                                    "type": "trade",
                                    "asset": "ZUSD",
                                    "amount": "-10.00000000",
                                    "fee": "0.00000000",
                                    "balance": "4989.00000000"
                                },
                                "DEPOSIT-1": {
                                    "refid": "DEP-1",
                                    "time": 1700000000.0000,
                                    "type": "deposit",
                                    "asset": "ZUSD",
                                    "amount": "100.00000000",
                                    "fee": "0.00000000",
                                    "balance": "5089.00000000"
                                }
                            },
                            "count": 4
                        }
                    }
                """.trimIndent()
                var capturedBody = ""
                val service = createService(responseJson) { request ->
                    capturedBody = (request.body as TextContent).text
                }

                val entries = service.getLedgers(types = setOf(KrakenApiConstants.LEDGER_TYPE_EARN))

                capturedBody shouldContain "type=${KrakenApiConstants.LEDGER_TYPE_ALL}"
                entries.map { it.ledgerId } shouldBe listOf("EARN-1")
                entries.single().type shouldBe KrakenApiConstants.LEDGER_TYPE_EARN
                entries.single().subtype shouldBe "reward"
                service.getLastLedgerTotalCount() shouldBe 4
                service.hasLastLedgerTotalCount() shouldBe true
                service.hasLastLedgerPageShape() shouldBe true
                service.getLastLedgerRawPageSize() shouldBe 4

                val promotionEntries = service.getLedgers(types = setOf(KrakenApiConstants.LEDGER_TYPE_REWARD))

                capturedBody shouldContain "type=${KrakenApiConstants.LEDGER_TYPE_ALL}"
                promotionEntries.map { it.ledgerId } shouldBe listOf("PROMO-1")
                promotionEntries.single().type shouldBe KrakenApiConstants.LEDGER_TYPE_REWARD
                service.getLastLedgerTotalCount() shouldBe 4
                service.hasLastLedgerTotalCount() shouldBe true
                service.hasLastLedgerPageShape() shouldBe true
                service.getLastLedgerRawPageSize() shouldBe 4
            }
        }

        "getLedgers_QueriesAllForObservedConversionAndFiltersReturnedRows" {
            runTest {
                val responseJson = """
                    {
                        "error": [],
                        "result": {
                            "ledger": {
                                "CONVERSION-SOURCE": {
                                    "refid": "CONVERSION-1",
                                    "time": 1700000000.0000,
                                    "type": "conversion",
                                    "asset": "ZUSD",
                                    "amount": "-1000.00000000",
                                    "fee": "0.00000000",
                                    "balance": "0.00000000"
                                },
                                "CONVERSION-DESTINATION": {
                                    "refid": "CONVERSION-1",
                                    "time": 1700000000.0000,
                                    "type": "conversion",
                                    "asset": "USDG",
                                    "amount": "1000.00000000",
                                    "fee": "0.00000000",
                                    "balance": "1000.00000000"
                                },
                                "OTHER": {
                                    "refid": "OTHER",
                                    "time": 1700000000.0000,
                                    "type": "staking",
                                    "asset": "XBT",
                                    "amount": "0.10000000",
                                    "fee": "0.00000000",
                                    "balance": "1.00000000"
                                }
                            },
                            "count": 3
                        }
                    }
                """.trimIndent()
                var capturedBody = ""
                val service = createService(responseJson) { request ->
                    capturedBody = (request.body as TextContent).text
                }

                val entries = service.getLedgers(types = setOf(KrakenApiConstants.LEDGER_TYPE_CONVERSION))

                capturedBody shouldContain "type=${KrakenApiConstants.LEDGER_TYPE_ALL}"
                entries.map { it.ledgerId } shouldBe listOf("CONVERSION-SOURCE", "CONVERSION-DESTINATION")
                entries.all { it.type == KrakenApiConstants.LEDGER_TYPE_CONVERSION } shouldBe true
                service.getLastLedgerTotalCount() shouldBe 3
                service.getLastLedgerRawPageSize() shouldBe 3
            }
        }

        "getLedgers_MultipleTypes_QueriesEachTypeSeparatelyAndMerges" {
            runTest {
                val stakingJson = """
                    {
                        "error": [],
                        "result": {
                            "ledger": {
                                "S1": {
                                    "time": 1700000000.0000,
                                    "type": "staking",
                                    "asset": "XXBT",
                                    "amount": "0.10000000",
                                    "fee": "0.00000000",
                                    "balance": "1.00000000"
                                }
                            },
                            "count": 290
                        }
                    }
                """.trimIndent()
                val dividendJson = """
                    {
                        "error": [],
                        "result": {
                            "ledger": {
                                "D1": {
                                    "time": 1700000100.0000,
                                    "type": "dividend",
                                    "asset": "STRC",
                                    "amount": "1.25000000",
                                    "fee": "0.01000000",
                                    "balance": "2.25000000"
                                }
                            },
                            "count": 6
                        }
                    }
                """.trimIndent()
                val requestBodies = mutableListOf<String>()
                val engine = MockEngine { request ->
                    val body = (request.body as TextContent).text
                    requestBodies += body
                    if (body.contains("type=staking")) {
                        respond(stakingJson)
                    } else {
                        respond(dividendJson)
                    }
                }
                val mockConfigService = mockk<ConfigService>(relaxed = true)
                val config = AppConfig(
                    kraken = KrakenCredentials(
                        TestFixtures.TRADE_HISTORY_API_KEY,
                        TestFixtures.TRADE_HISTORY_API_SECRET,
                    ),
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L),
                    allocations = emptyList(),
                )
                every { mockConfigService.getConfig() } returns config
                val service = KrakenServiceImpl(
                    configService = mockConfigService,
                    objectMapper = jacksonObjectMapper(),
                    httpClient = HttpClient(engine),
                )

                val entries = service.getLedgers(
                    types = setOf(KrakenApiConstants.LEDGER_TYPE_STAKING, KrakenApiConstants.LEDGER_TYPE_DIVIDEND),
                )

                requestBodies.size shouldBe 2
                requestBodies.any { it.contains("type=staking") }.shouldBeTrue()
                requestBodies.any { it.contains("type=dividend") }.shouldBeTrue()
                requestBodies.none { it.contains("staking,dividend") }.shouldBeTrue()
                entries.size shouldBe 2
                val staking = entries.first { it.type == KrakenApiConstants.LEDGER_TYPE_STAKING }
                staking.ledgerId shouldBe "S1"
                staking.asset shouldBe "BTC"
                val dividend = entries.first { it.type == KrakenApiConstants.LEDGER_TYPE_DIVIDEND }
                dividend.ledgerId shouldBe "D1"
                dividend.asset shouldBe "STRC"
                service.getLastLedgerTotalCount() shouldBe 296
            }
        }

        "getLedgers_BlankApiKey_FailsTyped" {
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

                shouldThrow<KrakenCredentialsUnavailableException> { service.getLedgers() }
            }
        }

        "getLedgers_MissingLedgerObject_ReturnsEmpty" {
            runTest {
                val responseJson = """
                    {
                        "error": [],
                        "result": {
                            "count": 0
                        }
                    }
                """.trimIndent()
                val service = createService(responseJson)
                val entries = service.getLedgers()

                entries.isEmpty().shouldBeTrue()
                service.getLastLedgerTotalCount() shouldBe 0
            }
        }

        "getLedgers_NormalizesEarnSuffixAndLegacyAssetCodes" {
            runTest {
                val responseJson = """
                    {
                        "error": [],
                        "result": {
                            "ledger": {
                                "L1": {
                                    "time": 1700000000.0000,
                                    "type": "staking",
                                    "asset": "DOT.S",
                                    "amount": "1.00000000",
                                    "fee": "0.00000000",
                                    "balance": "10.00000000"
                                },
                                "L2": {
                                    "time": 1700000100.0000,
                                    "type": "staking",
                                    "asset": "USDT.F",
                                    "amount": "2.00000000",
                                    "fee": "0.00000000",
                                    "balance": "20.00000000"
                                },
                                "L3": {
                                    "time": 1700000200.0000,
                                    "type": "staking",
                                    "asset": "XXBT",
                                    "amount": "0.10000000",
                                    "fee": "0.00000000",
                                    "balance": "1.00000000"
                                },
                                "L4": {
                                    "time": 1700000300.0000,
                                    "type": "staking",
                                    "asset": "ZUSD",
                                    "amount": "5.00000000",
                                    "fee": "0.00000000",
                                    "balance": "50.00000000"
                                },
                                "L5": {
                                    "time": 1700000400.0000,
                                    "type": "staking",
                                    "asset": "ZGBP",
                                    "amount": "3.00000000",
                                    "fee": "0.00000000",
                                    "balance": "30.00000000"
                                }
                            },
                            "count": 5
                        }
                    }
                """.trimIndent()
                val service = createService(responseJson)
                val entries = service.getLedgers()

                entries.first { it.ledgerId == "L1" }.asset shouldBe "DOT"
                entries.first { it.ledgerId == "L2" }.asset shouldBe "USDT"
                entries.first { it.ledgerId == "L3" }.asset shouldBe "BTC"
                entries.first { it.ledgerId == "L4" }.asset shouldBe "USD"
                entries.first { it.ledgerId == "L5" }.asset shouldBe "GBP"
                service.getLastLedgerTotalCount() shouldBe 5
            }
        }
    }
}
