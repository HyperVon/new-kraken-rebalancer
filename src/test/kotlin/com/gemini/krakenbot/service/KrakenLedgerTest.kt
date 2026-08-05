@file:OptIn(ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal

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
                                    "asset": "XBT",
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
                val staking = entries.first { it.type == LedgerEvent.TYPE_STAKING }
                staking.refid shouldBe "L1"
                staking.time.toEpochMilli() shouldBe 1700000000123L
                staking.subtype shouldBe "reward"
                staking.aclass shouldBe "currency"
                staking.asset shouldBe "XBT"
                staking.amount.shouldBeEqualComparingTo(BigDecimal("0.1"))
                staking.fee.shouldBeEqualComparingTo(BigDecimal("0"))
                staking.balance.shouldBeEqualComparingTo(BigDecimal("10.5"))
                val dividend = entries.first { it.type == LedgerEvent.TYPE_DIVIDEND }
                dividend.subtype.shouldBeNull()
                dividend.aclass shouldBe "currency"
                service.getLastLedgerTotalCount() shouldBe 2
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
                val entries = service.getLedgers(types = setOf(LedgerEvent.TYPE_STAKING))

                entries.size shouldBe 1
                entries.first().type shouldBe LedgerEvent.TYPE_STAKING
                service.getLastLedgerTotalCount() shouldBe 2
            }
        }

        "getLedgers_BlankApiKey_ReturnsEmpty" {
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

                val entries = service.getLedgers()
                entries.isEmpty().shouldBeTrue()
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
    }
}
