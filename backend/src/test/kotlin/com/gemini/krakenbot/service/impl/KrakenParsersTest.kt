package com.gemini.krakenbot.service.impl

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.TradeSource
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

@Suppress("unused")
class KrakenParsersTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val objectMapper = jacksonObjectMapper()

    init {
        "parses balance and ticker golden responses without changing positive values" {
            val balances = KrakenParsers.parseBalances(
                objectMapper.readTree(
                    """
                    {
                      "XXBT": "2.50000000",
                      "USD": "100.00",
                      "ZERO": "0",
                      "NEGATIVE": "-1",
                      "BAD": "not-a-number"
                    }
                    """.trimIndent(),
                ),
            )
            balances.keys shouldBe setOf("XXBT", "USD")
            balances["XXBT"]!!.shouldBeEqualComparingTo(BigDecimal("2.5"))
            balances["USD"]!!.shouldBeEqualComparingTo(BigDecimal("100.00"))

            val prices = KrakenParsers.parseTickerPrices(
                objectMapper.readTree(
                    """
                    {
                      "XXBTZUSD": { "c": ["65000.12345678", "65001.00"] },
                      "XETHZUSD": { "c": [] },
                      "BAD": { "c": ["not-a-number"] },
                      "ZERO": { "c": ["0"] }
                    }
                    """.trimIndent(),
                ),
            )
            prices.keys shouldBe setOf("XXBTZUSD")
            prices["XXBTZUSD"]!!.shouldBeEqualComparingTo(BigDecimal("65000.12345678"))
        }

        "parses extended balances as spendable amounts" {
            val balances = KrakenParsers.parseSpendableBalances(
                objectMapper.readTree(
                    """
                    {
                      "ZUSD": {"balance": "100.00", "credit": "5.00", "credit_used": "2.00", "hold_trade": "30.00"},
                      "XXBT": {"balance": "1.00", "hold_trade": "0.25"},
                      "ZERO": {"balance": "1.00", "hold_trade": "1.00"},
                      "BAD": "not-an-object"
                    }
                    """.trimIndent(),
                ),
            )

            balances["ZUSD"]!!.shouldBeEqualComparingTo(BigDecimal("73.00"))
            balances["XXBT"]!!.shouldBeEqualComparingTo(BigDecimal("0.75"))
            balances.containsKey("ZERO").shouldBeFalse()
            balances.containsKey("BAD").shouldBeFalse()
        }

        "parses trade history golden response into API fills with stable identity" {
            val (trades, count) = KrakenParsers.parseTradeHistory(
                objectMapper.readTree(
                    """
                    {
                      "count": 2,
                      "trades": {
                        "T1": {
                          "ordertxid": "O1",
                          "pair": "XXBTZUSD",
                          "time": 1700000000.1234,
                          "type": "buy",
                          "price": "50000.00",
                          "cost": "5000.00",
                          "fee": "10.00",
                          "vol": "0.10000000"
                        },
                        "T2": {
                          "pair": "XETHZUSD",
                          "time": 1700000005.0000,
                          "type": "sell",
                          "price": "2000.00",
                          "cost": "200.00",
                          "fee": "1.00",
                          "vol": "0.10000000"
                        }
                      }
                    }
                    """.trimIndent(),
                ),
                allocations = listOf("BTC", "ETH", "USD"),
            )

            count shouldBe 2
            trades.size shouldBe 2
            val bitcoin = trades.single { it.tradeId == "T1" }
            bitcoin.symbol shouldBe "BTC"
            bitcoin.side shouldBe "BUY"
            bitcoin.timestamp.toEpochMilli() shouldBe 1700000000123L
            bitcoin.volume.shouldBeEqualComparingTo(BigDecimal("0.1"))
            bitcoin.usdAmount.shouldBeEqualComparingTo(BigDecimal("5000.00"))
            bitcoin.source shouldBe TradeSource.API_FILL
            bitcoin.orderTxid shouldBe "O1"

            val ether = trades.single { it.tradeId == "T2" }
            ether.symbol shouldBe "ETH"
            ether.side shouldBe "SELL"
            ether.orderTxid shouldBe null
        }

        "skips malformed and out-of-universe trade history entries" {
            val (emptyTrades, emptyCount) = KrakenParsers.parseTradeHistory(
                objectMapper.readTree("{\"count\": 4, \"trades\": []}"),
                allocations = listOf("BTC", "USD"),
            )
            emptyTrades shouldBe emptyList()
            emptyCount shouldBe 4

            val (trades, count) = KrakenParsers.parseTradeHistory(
                objectMapper.readTree(
                    """
                    {
                      "count": 2,
                      "trades": {
                        "UNKNOWN": {
                          "pair": "XRPZUSD",
                          "time": 1700000000,
                          "type": "buy",
                          "price": "1",
                          "cost": "1",
                          "vol": "1",
                          "fee": "0"
                        },
                        "BLANK-ORDER": {
                          "ordertxid": "   ",
                          "pair": "XXBTZUSD",
                          "time": 1700000001,
                          "type": "buy",
                          "price": "1",
                          "cost": "1",
                          "vol": "1",
                          "fee": "0"
                        },
                        "NULL-ORDER": {
                          "ordertxid": null,
                          "pair": "XXBTZUSD",
                          "time": 1700000002,
                          "type": "buy",
                          "price": "1",
                          "cost": "1",
                          "vol": "1",
                          "fee": "0"
                        }
                      }
                    }
                    """.trimIndent(),
                ),
                allocations = listOf("BTC", "USD"),
            )

            count shouldBe 2
            trades.size shouldBe 2
            trades.forEach { it.orderTxid shouldBe null }

            val (blankIdTrades, _) = KrakenParsers.parseTradeHistory(
                objectMapper.readTree(
                    """
                    {
                      "trades": {
                        "": {
                          "pair": "XXBTZUSD",
                          "time": 1700000003,
                          "type": "buy",
                          "price": "1",
                          "cost": "1",
                          "vol": "1",
                          "fee": "0"
                        }
                      }
                    }
                    """.trimIndent(),
                ),
                allocations = listOf("BTC", "USD"),
            )
            blankIdTrades.single().tradeId shouldBe null
        }

        "parses filtered ledger golden response and retains the API total count" {
            val response = objectMapper.readTree(
                """
                {
                  "count": 2,
                  "ledger": {
                    "L1": {
                      "refid": "R1",
                      "time": 1700000100.0000,
                      "type": "staking",
                      "subtype": "reward",
                      "aclass": "currency",
                      "asset": "DOT.S",
                      "amount": "1.25000000",
                      "fee": "0.01001234",
                      "balance": "10.50000000"
                    },
                    "L2": {
                      "time": 1700000200.0000,
                      "type": "dividend",
                      "asset": "STRC",
                      "amount": "2.00000000",
                      "fee": "0.00000000",
                      "balance": "2.00000000"
                    }
                  }
                }
                """.trimIndent(),
            )

            val (entries, count) = KrakenParsers.parseLedgerPage(
                response,
                setOf(KrakenApiConstants.LEDGER_TYPE_STAKING),
            )

            count shouldBe 2
            entries.size shouldBe 1
            val staking = entries.single()
            staking.ledgerId shouldBe "L1"
            staking.refid shouldBe "R1"
            staking.asset shouldBe "DOT"
            staking.subtype shouldBe "reward"
            staking.amount.shouldBeEqualComparingTo(BigDecimal("1.25"))
            staking.fee.shouldBeEqualComparingTo(BigDecimal("0.01001234"))
            staking.balance.shouldBeEqualComparingTo(BigDecimal("10.5"))
            staking.hasAuthoritativeBalance shouldBe true
            staking.hasValidFee shouldBe true
        }

        "retains blank fees, rejects negative fees, and accepts unfiltered ledger pages" {
            val response = objectMapper.readTree(
                """
                {
                  "count": 3,
                  "ledger": {
                    "BLANK-FEE": {
                      "time": 1700000100,
                      "type": "staking",
                      "asset": "DOT",
                      "amount": "1.0",
                      "fee": "",
                      "balance": ""
                    },
                    "NEGATIVE-FEE": {
                      "time": 1700000200,
                      "type": "dividend",
                      "asset": "ETH",
                      "amount": "2.0",
                      "fee": "-0.1",
                      "balance": "2.0"
                    },
                    "NO-FEE": {
                      "time": 1700000300,
                      "type": "receive",
                      "asset": "USD",
                      "amount": "3.0",
                      "balance": "3.0"
                    }
                  }
                }
                """.trimIndent(),
            )

            val (entries, count) = KrakenParsers.parseLedgerPage(response, null)

            count shouldBe 3
            entries.map { it.ledgerId } shouldBe listOf("BLANK-FEE", "NEGATIVE-FEE", "NO-FEE")
            entries[0].hasValidFee shouldBe true
            entries[0].hasAuthoritativeFee shouldBe false
            entries[0].hasAuthoritativeBalance shouldBe false
            entries[1].hasValidFee shouldBe false
            entries[1].fee shouldBe BigDecimal.ZERO
            entries[2].hasValidFee shouldBe true
            entries[2].hasAuthoritativeFee shouldBe false
        }

        "marks explicit zero ledger balances authoritative but rejects malformed balances" {
            val response = objectMapper.readTree(
                """
                {
                  "count": 2,
                  "ledger": {
                    "L1": {
                      "time": 1700000100.0000,
                      "type": "staking",
                      "asset": "DOT",
                      "amount": "1.25000000",
                      "fee": "0.01001234",
                      "balance": "not-a-number"
                    },
                    "L2": {
                      "time": 1700000200.0000,
                      "type": "staking",
                      "asset": "ETH",
                      "amount": "0.00000001",
                      "fee": "0.00000000",
                      "balance": "0.00000000"
                    }
                  }
                }
                """.trimIndent(),
            )

            val (entries, count) = KrakenParsers.parseLedgerPage(
                response,
                setOf(KrakenApiConstants.LEDGER_TYPE_STAKING),
            )

            count shouldBe 2
            entries.size shouldBe 2
            entries.single { it.ledgerId == "L1" }.hasAuthoritativeBalance.shouldBeFalse()
            entries.single { it.ledgerId == "L2" }.hasAuthoritativeBalance shouldBe true
        }

        "parses deposit and withdrawal status pages with cursor and explicit zero fee" {
            val depositPage = KrakenParsers.parseDepositStatusPage(
                objectMapper.readTree(
                    """
                    {
                      "error": [],
                      "result": {
                        "deposit": [
                          {
                            "method": "Bitcoin",
                            "asset": "XXBT",
                            "refid": "DEP-1",
                            "txid": "0xabc",
                            "amount": "0.50000000",
                            "fee": "0.0000000000",
                            "time": 1700000000.1234,
                            "status": "Success"
                          }
                        ],
                        "cursor": "next-deposit"
                      }
                    }
                    """.trimIndent(),
                ),
            )
            val withdrawalPage = KrakenParsers.parseWithdrawStatusPage(
                objectMapper.readTree(
                    """
                    {
                      "result": [
                        {
                          "method": "Bitcoin",
                          "asset": "XXBT",
                          "refid": "W-1",
                          "txid": "0xdef",
                          "amount": "0.25000000",
                          "fee": "0.00020000",
                          "time": 1700000100,
                          "status": "Failure"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            )

            depositPage.nextCursor shouldBe "next-deposit"
            depositPage.records.single().asset shouldBe "BTC"
            depositPage.records.single().hasAuthoritativeFee shouldBe true
            depositPage.records.single().fee.shouldBeEqualComparingTo(BigDecimal.ZERO)
            depositPage.records.single().status shouldBe "Success"
            withdrawalPage.nextCursor shouldBe null
            withdrawalPage.records.single().asset shouldBe "BTC"
            withdrawalPage.records.single().fee.shouldBeEqualComparingTo(BigDecimal("0.0002"))
            withdrawalPage.records.single().status shouldBe "Failure"
        }

        "parses data-wrapped funding pages and skips malformed records" {
            val page = KrakenParsers.parseWithdrawStatusPage(
                objectMapper.readTree(
                    """
                    {
                      "data": [
                        {
                          "method": "Wire",
                          "asset": "ZUSD",
                          "refid": "GOOD-DATA",
                          "amount": "10.00",
                          "fee": "",
                          "time": 1700000000.5,
                          "status": "Settled"
                        },
                        {
                          "method": "Wire",
                          "asset": "USD",
                          "refid": "BAD-FEE",
                          "amount": "10.00",
                          "fee": "-0.01",
                          "time": 1700000001,
                          "status": "Success"
                        },
                        {
                          "method": "Wire",
                          "asset": "USD",
                          "refid": "BAD-AMOUNT",
                          "amount": "not-a-number",
                          "time": 1700000002,
                          "status": "Success"
                        },
                        {
                          "method": "Wire",
                          "asset": "USD",
                          "refid": "BAD-TIME",
                          "amount": "10.00",
                          "time": -1,
                          "status": "Success"
                        },
                        {
                          "method": "Wire",
                          "asset": "USD",
                          "refid": "",
                          "amount": "10.00",
                          "time": 1700000003,
                          "status": "Success"
                        },
                        "not-an-object"
                      ],
                      "cursor": "next-data"
                    }
                    """.trimIndent(),
                ),
            )

            page.nextCursor shouldBe "next-data"
            page.records.size shouldBe 1
            page.records.single().refid shouldBe "GOOD-DATA"
            page.records.single().asset shouldBe "USD"
            page.records.single().hasAuthoritativeFee shouldBe false
            page.records.single().time.toEpochMilli() shouldBe 1700000000500L

            KrakenParsers.parseDepositStatus(
                objectMapper.readTree("{\"deposit\": {}}"),
            ) shouldBe emptyList()
        }

        "handles root-array funding pages and blank optional ledger fields" {
            val rootArray = KrakenParsers.parseDepositStatusPage(
                objectMapper.readTree(
                    """
                    [
                      {
                        "method": "",
                        "asset": "USD",
                        "refid": "ROOT-ARRAY",
                        "txid": "",
                        "amount": "10.00",
                        "time": 1700000000,
                        "status": "Success"
                      }
                    ]
                    """.trimIndent(),
                ),
            )
            rootArray.records.single().refid shouldBe "ROOT-ARRAY"
            rootArray.records.single().txid shouldBe null
            rootArray.records.single().method shouldBe null
            rootArray.records.single().hasAuthoritativeFee shouldBe false

            val (entries, count) = KrakenParsers.parseLedgerPage(
                objectMapper.readTree(
                    """
                    {
                      "ledger": {
                        "BLANK-FIELDS": {
                          "refid": "",
                          "subtype": "",
                          "aclass": "",
                          "time": 1700000000,
                          "type": "staking",
                          "asset": "USD",
                          "amount": "1.00",
                          "fee": "0.00",
                          "balance": "1.00"
                        },
                        "NULL-FIELDS": {
                          "refid": null,
                          "subtype": null,
                          "aclass": null,
                          "time": 1700000001,
                          "type": "staking",
                          "asset": "USD",
                          "amount": "2.00",
                          "fee": "0.00",
                          "balance": "2.00"
                        }
                      }
                    }
                    """.trimIndent(),
                ),
                null,
            )
            count shouldBe 0
            entries.size shouldBe 2
            entries.forEach {
                it.refid shouldBe null
                it.subtype shouldBe null
                it.aclass shouldBe null
            }

            val (invalidFeeEntries, _) = KrakenParsers.parseLedgerPage(
                objectMapper.readTree(
                    """
                    {
                      "ledger": {
                        "INVALID-FEE": {
                          "time": 1700000002,
                          "type": "staking",
                          "asset": "USD",
                          "amount": "1.00",
                          "fee": "not-a-number",
                          "balance": "1.00"
                        }
                      }
                    }
                    """.trimIndent(),
                ),
                null,
            )
            invalidFeeEntries.single().hasValidFee shouldBe false
        }

        "rejects non-finite funding timestamps" {
            val page = KrakenParsers.parseDepositStatusPage(
                objectMapper.readTree(
                    """
                    {
                      "deposit": [{
                        "asset": "USD",
                        "refid": "NON-FINITE-TIME",
                        "amount": "10.00",
                        "time": "NaN",
                        "status": "Success"
                      }]
                    }
                    """.trimIndent(),
                ),
            )

            page.records shouldBe emptyList()
        }

        "rejects malformed or negative funding fees at the parser boundary" {
            val page = KrakenParsers.parseDepositStatusPage(
                objectMapper.readTree(
                    """
                    {
                      "deposit": [
                        {
                          "method": "Wire",
                          "asset": "USD",
                          "refid": "GOOD",
                          "amount": "10.00",
                          "fee": "0.00",
                          "time": 1700000000,
                          "status": "Success"
                        },
                        {
                          "method": "Wire",
                          "asset": "USD",
                          "refid": "BAD-TEXT",
                          "amount": "10.00",
                          "fee": "not-a-number",
                          "time": 1700000001,
                          "status": "Success"
                        },
                        {
                          "method": "Wire",
                          "asset": "USD",
                          "refid": "BAD-SIGN",
                          "amount": "10.00",
                          "fee": "-0.01",
                          "time": 1700000002,
                          "status": "Success"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            )

            page.records.map { it.refid } shouldBe listOf("GOOD")
        }

        "parses OHLC golden response while ignoring last and malformed rows" {
            val prices = KrakenParsers.parseOHLC(
                objectMapper.readTree(
                    """
                    {
                      "result": {
                        "last": 1700000300,
                        "XXBTZUSD": [
                          [1700000000, "50000", "50100", "49900", "50050.00", "100", 10],
                          [1700000060, "50050", "50100", "50000", "not-a-number", "100", 10],
                          [1700000120, "50100"]
                        ]
                      }
                    }
                    """.trimIndent(),
                ),
            )

            prices shouldBe listOf(1700000000L to BigDecimal("50050.00"))
        }

        "fails closed for malformed OHLC envelopes and logs malformed pair rows" {
            KrakenParsers.parseOHLC(objectMapper.readTree("{}")) shouldBe emptyList()
            KrakenParsers.parseOHLC(objectMapper.readTree("{\"result\": {\"last\": 1}}")) shouldBe emptyList()
            KrakenParsers.parseOHLC(objectMapper.readTree("{\"result\": {\"PAIR\": {}}}")) shouldBe emptyList()

            KrakenParsers.parseOHLC(
                objectMapper.readTree(
                    """
                    {
                      "result": {
                        "PAIR": [[1700000000, "1", "1", "1", "bad"]]
                      }
                    }
                    """.trimIndent(),
                ),
                pair = "XXBTZUSD",
            ) shouldBe emptyList()
        }
    }
}
