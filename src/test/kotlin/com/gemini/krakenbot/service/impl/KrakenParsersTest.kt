package com.gemini.krakenbot.service.impl

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
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
                      "fee": "0.01000000",
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
            staking.fee.shouldBeEqualComparingTo(BigDecimal("0.01"))
            staking.balance.shouldBeEqualComparingTo(BigDecimal("10.5"))
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
    }
}
