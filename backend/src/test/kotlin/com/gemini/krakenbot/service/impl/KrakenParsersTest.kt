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
