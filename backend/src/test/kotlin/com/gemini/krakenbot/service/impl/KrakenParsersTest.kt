package com.gemini.krakenbot.service.impl

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.model.KrakenAssetMetadata
import com.gemini.krakenbot.service.DirectEvidenceStatus
import com.gemini.krakenbot.service.DirectTickerRequest
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

@Suppress("unused")
class KrakenParsersTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val objectMapper = jacksonObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
    private fun assetFixture(name: String): String =
        checkNotNull(javaClass.getResource("/kraken-assets/$name")).readText()

    init {
        "parses asset IDs from object keys and retains unknown response classes" {
            KrakenParsers.parseAssetMetadata(
                objectMapper.readTree(assetFixture("assets-metadata-edge-cases.json")),
            ) shouldBe listOf(
                KrakenAssetMetadata("XXBT", "currency"),
                KrakenAssetMetadata("TOKENIZED_ASSET_1", "tokenized_asset"),
                KrakenAssetMetadata("FUTURE_CLASS_ASSET", "future_asset_class"),
            )
        }

        "returns no metadata for missing or malformed result containers" {
            listOf("{}", "{\"result\": null}", "{\"result\": []}").forEach { json ->
                KrakenParsers.parseAssetMetadata(objectMapper.readTree(json)) shouldBe emptyList()
            }
        }

        "parses balance and ticker golden responses without changing positive values" {
            val balanceResult = objectMapper.readTree(
                """
                {
                  "XXBT": "2.50000000",
                  "USD": "100.00",
                  "ZERO": "0",
                  "NEGATIVE": "-1",
                  "BAD": "not-a-number"
                }
                """.trimIndent(),
            )
            val balances = KrakenParsers.parseBalances(
                balanceResult,
            )
            balances.keys shouldBe setOf("XXBT", "USD")
            balances["XXBT"]!!.shouldBeEqualComparingTo(BigDecimal("2.5"))
            balances["USD"]!!.shouldBeEqualComparingTo(BigDecimal("100.00"))

            val balanceStart = Instant.parse("2026-10-09T12:00:00Z")
            val balanceEnd = balanceStart.plusSeconds(1)
            val balanceCapture = KrakenParsers.parseDirectBalanceCapture(
                balanceResult,
                balanceStart,
                balanceEnd,
            )
            balanceCapture.requestStartedAt shouldBe balanceStart
            balanceCapture.responseEndedAt shouldBe balanceEnd
            balanceCapture.valuesByAssetId.keys shouldBe setOf("XXBT", "USD", "ZERO", "NEGATIVE", "BAD")
            balanceCapture.valuesByAssetId.getValue("ZERO").status shouldBe DirectEvidenceStatus.VALID
            balanceCapture.valuesByAssetId.getValue("ZERO").value!!.shouldBeEqualComparingTo(BigDecimal.ZERO)
            balanceCapture.valuesByAssetId.getValue("NEGATIVE").status shouldBe DirectEvidenceStatus.NEGATIVE
            balanceCapture.valuesByAssetId.getValue("NEGATIVE").rawValue shouldBe "-1"
            balanceCapture.valuesByAssetId.getValue("BAD").status shouldBe DirectEvidenceStatus.MALFORMED
            balanceCapture.valuesByAssetId.getValue("BAD").rawValue shouldBe "not-a-number"
            balanceCapture.forConfiguredAsset("BTC").status shouldBe DirectEvidenceStatus.VALID
            balanceCapture.forConfiguredAsset("BTC").rawKeys shouldBe listOf("XXBT")
            balanceCapture.forConfiguredAsset("DOGE").status shouldBe DirectEvidenceStatus.MISSING

            val aliasedCapture = KrakenParsers.parseDirectBalanceCapture(
                objectMapper.readTree("""{"BTC":"1.0","XXBT":"2.0"}"""),
                balanceStart,
                balanceEnd,
            ).forConfiguredAsset("XBT")
            aliasedCapture.status shouldBe DirectEvidenceStatus.AMBIGUOUS
            aliasedCapture.rawKeys shouldBe listOf("BTC", "XXBT")
            aliasedCapture.rawValuesByKey shouldBe mapOf("BTC" to "1.0", "XXBT" to "2.0")

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

        "captures every requested ticker mark with raw last-trade value and typed gaps" {
            val result = objectMapper.readTree(
                """
                {
                  "XXBTZUSD": { "c": ["65000.12345678", "0.01"] },
                  "XETHZUSD": { "c": [] },
                  "SOLUSD": { "c": ["not-a-number"] },
                  "ADAUSD": { "c": ["0"] }
                }
                """.trimIndent(),
            )
            val startedAt = Instant.parse("2026-10-09T12:10:00Z")
            val endedAt = startedAt.plusMillis(250)
            val capture = KrakenParsers.parseDirectTickerCapture(
                result,
                listOf(
                    DirectTickerRequest("BTC", "XBTUSD"),
                    DirectTickerRequest("ETH", "ETHUSD"),
                    DirectTickerRequest("SOL", "SOLUSD"),
                    DirectTickerRequest("ADA", "ADAUSD"),
                    DirectTickerRequest("DOGE", "XDGUSD"),
                ),
                startedAt,
                endedAt,
            )

            capture.requestStartedAt shouldBe startedAt
            capture.responseEndedAt shouldBe endedAt
            capture.marksBySymbol.keys shouldBe setOf("BTC", "ETH", "SOL", "ADA", "DOGE")
            capture.marksBySymbol.getValue("BTC").requestedPair shouldBe "XBTUSD"
            capture.marksBySymbol.getValue("BTC").responsePair shouldBe "XXBTZUSD"
            capture.marksBySymbol.getValue("BTC").rawPrice shouldBe "65000.12345678"
            capture.marksBySymbol.getValue("BTC").price!!.shouldBeEqualComparingTo(BigDecimal("65000.12345678"))
            capture.marksBySymbol.getValue("BTC").status shouldBe DirectEvidenceStatus.VALID
            capture.marksBySymbol.getValue("ETH").status shouldBe DirectEvidenceStatus.MISSING
            capture.marksBySymbol.getValue("SOL").rawPrice shouldBe "not-a-number"
            capture.marksBySymbol.getValue("SOL").status shouldBe DirectEvidenceStatus.MALFORMED
            capture.marksBySymbol.getValue("ADA").rawPrice shouldBe "0"
            capture.marksBySymbol.getValue("ADA").status shouldBe DirectEvidenceStatus.NON_POSITIVE
            capture.marksBySymbol.getValue("DOGE").status shouldBe DirectEvidenceStatus.MISSING
        }

        "marks multiple ticker response aliases as ambiguous and preserves their keys" {
            val capture = KrakenParsers.parseDirectTickerCapture(
                objectMapper.readTree(
                    """{"XBTUSD":{"c":["10"]},"XXBTZUSD":{"c":["11"]}}""",
                ),
                listOf(DirectTickerRequest("BTC", "XBTUSD")),
                Instant.parse("2026-10-09T12:20:00Z"),
                Instant.parse("2026-10-09T12:20:01Z"),
            )

            val mark = capture.marksBySymbol.getValue("BTC")
            mark.status shouldBe DirectEvidenceStatus.AMBIGUOUS
            mark.candidateResponsePairs shouldBe listOf("XBTUSD", "XXBTZUSD")
            mark.candidateValuesByPair.mapValues { it.value.rawValue } shouldBe
                mapOf("XBTUSD" to "10", "XXBTZUSD" to "11")
            mark.rawPrice shouldBe null
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

        "parses native trade pages with exact raw values and retains malformed rows" {
            val page = KrakenParsers.parseAccountTradePage(
                objectMapper.readTree(
                    """
                    {
                      "trades": {
                        "T-NATIVE": {
                          "ordertxid": "O1",
                          "postxid": "P1",
                          "pair": "SOLUSDT",
                          "time": 1700000000.123456789,
                          "type": "sell",
                          "ordertype": "limit",
                          "price": "123.450000000000000001",
                          "cost": "12.3450000000000000001",
                          "fee": "0.01234500000000000001",
                          "vol": "0.1000000000000000001",
                          "margin": "0.00000000000000000001",
                          "misc": "stp",
                          "trade_id": "42",
                          "maker": true,
                          "ledgers": ["L1", "L2"]
                        },
                        "T-MALFORMED": {
                          "pair": "ADAEUR",
                          "time": "not-time",
                          "type": "buy",
                          "price": "1.0",
                          "cost": "not-a-number",
                          "fee": "0",
                          "vol": "1.0"
                        }
                      },
                      "count": 2,
                      "cursor": {"next": "cursor-next"}
                    }
                    """.trimIndent(),
                ),
                startSec = 1_700_000_000L,
                endSec = 1_700_000_100L,
                requestedCursor = "cursor-current",
            )

            page.valid shouldBe true
            page.startSec shouldBe 1_700_000_000L
            page.endSec shouldBe 1_700_000_100L
            page.requestedCursor shouldBe "cursor-current"
            page.nextCursor shouldBe "cursor-next"
            page.count shouldBe 2L
            page.rawSize shouldBe 2
            page.entries.size shouldBe 2

            val native = page.entries.first()
            native.tradeId shouldBe "T-NATIVE"
            native.orderTxid shouldBe "O1"
            native.postTxid shouldBe "P1"
            native.pair shouldBe "SOLUSDT"
            native.baseAsset shouldBe "SOL"
            native.quoteCurrency shouldBe "USDT"
            native.feeCurrency shouldBe null
            native.time shouldBe Instant.parse("2023-11-14T22:13:20.123456789Z")
            native.rawTime shouldBe "1700000000.123456789"
            native.price shouldBe "123.450000000000000001"
            native.cost shouldBe "12.3450000000000000001"
            native.fee shouldBe "0.01234500000000000001"
            native.volume shouldBe "0.1000000000000000001"
            native.margin shouldBe "0.00000000000000000001"
            native.misc shouldBe "stp"
            native.tradeSequenceId shouldBe "42"
            native.maker shouldBe true
            native.ledgerIds shouldBe listOf("L1", "L2")
            native.isValidRecord shouldBe true

            val malformed = page.entries.last()
            malformed.pair shouldBe "ADAEUR"
            malformed.time shouldBe null
            malformed.rawTime shouldBe "not-time"
            malformed.cost shouldBe "not-a-number"
            malformed.hasValidTime shouldBe false
            malformed.hasValidCost shouldBe false
            malformed.isValidRecord shouldBe true
        }

        "parses native ledger pages without normalizing raw asset suffixes" {
            val page = KrakenParsers.parseAccountLedgerPage(
                objectMapper.readTree(
                    """
                    {
                      "ledger": {
                        "L-DOT": {
                          "refid": "R1",
                          "time": 1700000100.000000001,
                          "type": "staking",
                          "subtype": "reward",
                          "aclass": "currency",
                          "asset": "DOT.S",
                          "amount": "1.234567890123456789",
                          "fee": "0.000000000000000001",
                          "balance": "10.234567890123456789"
                        },
                        "L-BAD": {
                          "asset": "XBT.F",
                          "time": "bad-time",
                          "amount": "bad-amount",
                          "fee": "0",
                          "balance": "1"
                        }
                      },
                      "cursor": {}
                    }
                    """.trimIndent(),
                ),
                startSec = null,
                endSec = 1_700_001_000L,
                requestedCursor = null,
            )

            page.valid shouldBe true
            page.startSec shouldBe null
            page.endSec shouldBe 1_700_001_000L
            page.requestedCursor shouldBe null
            page.nextCursor shouldBe null
            page.count shouldBe null
            page.rawSize shouldBe 2

            val staking = page.entries.first()
            staking.ledgerId shouldBe "L-DOT"
            staking.refid shouldBe "R1"
            staking.time shouldBe Instant.parse("2023-11-14T22:15:00.000000001Z")
            staking.assetClass shouldBe "currency"
            staking.asset shouldBe "DOT.S"
            staking.amount shouldBe "1.234567890123456789"
            staking.fee shouldBe "0.000000000000000001"
            staking.balance shouldBe "10.234567890123456789"
            staking.hasValidAmount shouldBe true

            val malformed = page.entries.last()
            malformed.asset shouldBe "XBT.F"
            malformed.rawTime shouldBe "bad-time"
            malformed.time shouldBe null
            malformed.hasValidTime shouldBe false
            malformed.hasValidAmount shouldBe false
            malformed.rawRecordJson!!.contains("XBT.F") shouldBe true
        }

        "validates native page containers, optional count, and cursor tokens" {
            val validTerminalResponses = listOf(
                """{"trades":{}}""",
                """{"trades":{},"count":null,"cursor":{}}""",
                """{"trades":{},"cursor":{"next":null}}""",
            )
            validTerminalResponses.forEach { response ->
                KrakenParsers.parseAccountTradePage(
                    objectMapper.readTree(response),
                    startSec = null,
                    endSec = null,
                    requestedCursor = null,
                ).valid shouldBe true
            }

            listOf(
                """{"trades":[],"cursor":{}}""",
                """{"trades":{},"count":-1}""",
                """{"trades":{},"cursor":{"next":" "}}""",
                """{"trades":{},"cursor":{"next":17}}""",
                """{"ledger":[],"cursor":{}}""",
            ).forEach { response ->
                val root = objectMapper.readTree(response)
                if (root.has("ledger")) {
                    KrakenParsers.parseAccountLedgerPage(root, null, null, null).valid shouldBe false
                } else {
                    KrakenParsers.parseAccountTradePage(root, null, null, null).valid shouldBe false
                }
            }
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

        "parses account trade edge cases for single-element ledgers, zero/negative metrics, and invalid timestamps" {
            val json = objectMapper.readTree(
                """
                {
                  "trades": {
                    "T1": {
                      "time": "-100",
                      "pair": "XBTUSD",
                      "price": "0",
                      "cost": "-5",
                      "fee": "-1",
                      "vol": "0",
                      "type": "buy",
                      "ordertype": "limit",
                      "ledgers": "L-SINGLE"
                    },
                    "T2": {
                      "time": "1700000000.12345678901",
                      "pair": "XBTUSD",
                      "price": "not-a-number",
                      "cost": "not-a-number",
                      "fee": "not-a-number",
                      "vol": "not-a-number",
                      "type": "sell",
                      "ordertype": "market"
                    }
                  }
                }
                """.trimIndent(),
            )
            val page = KrakenParsers.parseAccountTradePage(json, null, null, null)
            page.valid shouldBe true
            page.entries.size shouldBe 2

            val t1 = page.entries.first { it.tradeId == "T1" }
            t1.hasValidTime shouldBe false
            t1.time shouldBe null
            t1.hasValidPrice shouldBe false
            t1.hasValidCost shouldBe false
            t1.hasValidFee shouldBe false
            t1.hasValidVolume shouldBe false
            t1.ledgerIds shouldBe listOf("L-SINGLE")

            val t2 = page.entries.first { it.tradeId == "T2" }
            t2.hasValidTime shouldBe false
            t2.hasValidPrice shouldBe false
            t2.hasValidCost shouldBe false
            t2.hasValidFee shouldBe false
            t2.hasValidVolume shouldBe false
            t2.ledgerIds shouldBe null
        }

        "parses account ledger edge cases for malformed amount, negative fee, and invalid balance" {
            val json = objectMapper.readTree(
                """
                {
                  "ledger": {
                    "L1": {
                      "time": "1700000000",
                      "type": "deposit",
                      "asset": "USD",
                      "amount": "not-a-number",
                      "fee": "-0.5",
                      "balance": "not-a-number"
                    }
                  }
                }
                """.trimIndent(),
            )
            val page = KrakenParsers.parseAccountLedgerPage(json, null, null, null)
            page.valid shouldBe true
            val entry = page.entries.single()
            entry.hasValidAmount shouldBe false
            entry.hasValidFee shouldBe false
            entry.hasValidBalance shouldBe false
        }

        "parses direct ticker capture edge cases including malformed root, missing, ambiguous, and non-positive marks" {
            val now = Instant.now()
            val requests = listOf(
                DirectTickerRequest("BTC", "XXBTZUSD"),
                DirectTickerRequest("ETH", "XETHZUSD"),
                DirectTickerRequest("SOL", "SOLUSD"),
            )

            // Non-object root -> MALFORMED
            val malformedCapture = KrakenParsers.parseDirectTickerCapture(
                objectMapper.readTree("[]"),
                requests,
                now,
                now,
            )
            malformedCapture.resultShapeValid shouldBe false
            malformedCapture.marksBySymbol.getValue("BTC").status shouldBe DirectEvidenceStatus.MALFORMED

            // Ticker with empty "c", non-array "c", non-positive "c", missing candidate, and ambiguous candidate
            val tickerJson = objectMapper.readTree(
                """
                {
                  "XXBTZUSD": { "c": [] },
                  "XETHZUSD": { "c": "not-an-array" },
                  "SOLUSD": { "c": ["0.00"] },
                  "EXTRA_BTC_USD": { "c": ["50000"] },
                  "XBTUSD": { "c": ["50000"] }
                }
                """.trimIndent(),
            )
            val capture = KrakenParsers.parseDirectTickerCapture(
                tickerJson,
                listOf(
                    DirectTickerRequest("BTC", "XXBTZUSD"),
                    DirectTickerRequest("ETH", "XETHZUSD"),
                    DirectTickerRequest("SOL", "SOLUSD"),
                    DirectTickerRequest("DOGE", "XDGUSD"),
                ),
                now,
                now,
            )
            capture.marksBySymbol.getValue("BTC").status shouldBe DirectEvidenceStatus.AMBIGUOUS
            capture.marksBySymbol.getValue("ETH").status shouldBe DirectEvidenceStatus.MALFORMED
            capture.marksBySymbol.getValue("SOL").status shouldBe DirectEvidenceStatus.NON_POSITIVE
            capture.marksBySymbol.getValue("DOGE").status shouldBe DirectEvidenceStatus.MISSING
        }
    }
}
