package com.gemini.krakenbot.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.TextContent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal

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

        "getDepositStatus_UsesAuthenticatedFundingEndpointAndParsesRecord" {
            runTest {
                val responseJson = """
                    {
                      "error": [],
                      "result": [
                        {
                          "method": "Wire",
                          "asset": "ZUSD",
                          "refid": "DEP-1",
                          "txid": "wire-1",
                          "amount": "100.00",
                          "fee": "0.00",
                          "time": 1700000000,
                          "status": "Success"
                        }
                      ]
                    }
                """.trimIndent()
                var capturedPath = ""
                var capturedBody = ""
                val service = createService(responseJson) { request ->
                    capturedPath = request.url.encodedPath
                    capturedBody = (request.body as TextContent).text
                }

                val records = service.getDepositStatus(startSec = 1700000000L, endSec = 1700003600L)

                capturedPath shouldBe KrakenApiConstants.PATH_DEPOSIT_STATUS
                capturedBody shouldContain "start=1700000000"
                capturedBody shouldContain "end=1700003600"
                capturedBody shouldContain "cursor=true"
                capturedBody shouldContain "limit=25"
                records.single().refid shouldBe "DEP-1"
                records.single().asset shouldBe "USD"
                records.single().hasAuthoritativeFee shouldBe true
            }
        }

        "getWithdrawStatus_UsesAuthenticatedFundingEndpointAndParsesRecord" {
            runTest {
                val responseJson = """
                    {
                      "error": [],
                      "result": [
                        {
                          "method": "Bitcoin",
                          "asset": "XXBT",
                          "refid": "W-1",
                          "txid": "tx-1",
                          "amount": "0.25",
                          "fee": "0.0002",
                          "time": 1700000000,
                          "status": "Pending"
                        }
                      ]
                    }
                """.trimIndent()
                var capturedPath = ""
                val service = createService(responseJson) { request ->
                    capturedPath = request.url.encodedPath
                }

                val records = service.getWithdrawStatus()

                capturedPath shouldBe KrakenApiConstants.PATH_WITHDRAW_STATUS
                records.single().asset shouldBe "BTC"
                records.single().status shouldBe "Pending"
            }
        }

        "funding status pagination fails closed on a repeated cursor" {
            runTest {
                val responseJson = """
                    {
                      "error": [],
                      "result": {
                        "deposit": [],
                        "cursor": "true"
                      }
                    }
                """.trimIndent()
                val service = createService(responseJson)

                shouldThrow<IllegalStateException> {
                    service.getDepositStatus()
                }
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
