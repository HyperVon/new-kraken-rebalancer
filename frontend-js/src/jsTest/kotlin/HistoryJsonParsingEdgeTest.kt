package com.gemini.krakenbot.frontend

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.js.JSON
import kotlin.js.json

/**
 * Defensive-parsing and wire-type coverage for [HistoryJsonParsing]. Hand-built `json()` objects
 * exercise the missing/null/garbage field defaults that populated round-trips never reach; one case
 * feeds real `JSON.parse` output (native JS boolean + numeric `id`, string decimals) so the parser
 * is proven against the runtime types the API DTO wire produces, not only Kotlin-built objects.
 */
class HistoryJsonParsingEdgeTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "parseTradeRecord defaults missing price and fee to 0" {
            val raw =
                json(
                    "timestamp" to "2023-01-01T10:00:00Z",
                    "pair" to "BTCUSD",
                    "side" to "SELL",
                    "symbol" to "BTC",
                    "volume" to "1",
                    "usdAmount" to "100",
                    "success" to true,
                    "dryRun" to false,
                )

            val parsed = parseTradeRecord(raw)
            parsed.price shouldBe "0"
            parsed.fee shouldBe "0"
            parsed.slippagePercent shouldBe null
            parsed.expectedPrice shouldBe null
            parsed.source shouldBe null
            parsed.id shouldBe null
        }

        "parseTradeRecord treats missing success and dryRun as false" {
            val parsed =
                parseTradeRecord(
                    json(
                        "timestamp" to "2023-01-01T10:00:00Z",
                        "pair" to "BTCUSD",
                        "side" to "BUY",
                        "symbol" to "BTC",
                        "volume" to "1",
                        "usdAmount" to "100",
                    ),
                )
            parsed.success shouldBe false
            parsed.dryRun shouldBe false
        }

        "parseTradeRecord reads a JSON.parse payload with native boolean and numeric id" {
            // Real JSON.parse (not a Kotlin-built object): native JS boolean and a JS number id, with
            // string economics. Asserts every field the API DTO wire carries so numeric→string
            // coercion or an id shape drift would fail here.
            val wire =
                """
                {
                  "timestamp":"2023-01-01T10:00:00Z","pair":"XXBTZUSD","side":"SELL","symbol":"BTC",
                  "volume":"0.125","usdAmount":"2500.00","success":true,"dryRun":false,
                  "price":"20000.0","fee":"6.50","slippagePercent":"0.15","source":"API_FILL","id":42
                }
                """.trimIndent()

            val parsed = parseTradeRecord(JSON.parse(wire))
            parsed.timestamp shouldBe "2023-01-01T10:00:00Z"
            parsed.pair shouldBe "XXBTZUSD"
            parsed.side shouldBe "SELL"
            parsed.symbol shouldBe "BTC"
            parsed.success shouldBe true
            parsed.dryRun shouldBe false
            parsed.id shouldBe 42
            parsed.source shouldBe "API_FILL"
            parsed.price shouldBe "20000.0"
            parsed.fee shouldBe "6.50"
            parsed.volume shouldBe "0.125"
            parsed.usdAmount shouldBe "2500.00"
            parsed.slippagePercent shouldBe "0.15"
        }

        "dynamicBoolean reads a non-boolean string via the strict toString arm" {
            // A non-boolean string takes the `toString().toBoolean()` arm, which only accepts "true"
            // (case-insensitive). Locks that a truthy-looking "yes" does NOT read as true.
            val yes: dynamic = baseTrade()
            yes["success"] = "yes"
            parseTradeRecord(yes).success shouldBe false
        }

        "parseTradeRecord accepts a string id and rejects a non-numeric id" {
            val stringId: dynamic = baseTrade()
            stringId["id"] = "42"
            parseTradeRecord(stringId).id shouldBe 42

            val garbageId: dynamic = baseTrade()
            garbageId["id"] = "not-a-number"
            parseTradeRecord(garbageId).id shouldBe null
        }

        "parseTradeRecords and parsePortfolioSnapshots return empty for null or empty input" {
            parseTradeRecords(null).size shouldBe 0
            parseTradeRecords(emptyArray<dynamic>()).size shouldBe 0
            parsePortfolioSnapshots(null).size shouldBe 0
            parsePortfolioSnapshots(emptyArray<dynamic>()).size shouldBe 0
        }

        "parsePortfolioSnapshot yields empty assets and actions when both keys are absent" {
            val parsed =
                parsePortfolioSnapshot(
                    json(
                        "timestamp" to "2023-01-01T00:00:00Z",
                        "totalValueUSD" to "100",
                    ),
                )
            parsed.assets.isEmpty() shouldBe true
            parsed.actions shouldBe emptyList()
        }

        "parseHistoryStats coerces missing or garbage counts to zero" {
            val parsed =
                parseHistoryStats(
                    json(
                        "allTimeHigh" to "1000",
                        "totalVolumeTraded" to "5",
                        "totalFeesPaid" to "1",
                        "totalTradesExecuted" to "abc",
                    ),
                )
            parsed.allTimeHigh shouldBe "1000"
            parsed.totalVolumeTraded shouldBe "5"
            parsed.totalTradesExecuted shouldBe 0L
            parsed.failedTradeCount shouldBe 0L
            parsed.dryRunTradeCount shouldBe 0L
            // Missing avgFeeRatePercent falls back to "0"; missing avgSlippagePercent stays null.
            parsed.avgFeeRatePercent shouldBe "0"
            parsed.avgSlippagePercent shouldBe null
        }

        "parseSyncProgressResponse defaults missing offset and total to empty strings" {
            val parsed =
                parseSyncProgressResponse(
                    json("seeded" to true),
                )
            parsed.seeded shouldBe true
            parsed.offset shouldBe ""
            parsed.total shouldBe ""

            val fromNull = parseSyncProgressResponse(null)
            fromNull.seeded shouldBe false
            fromNull.offset shouldBe ""
            fromNull.total shouldBe ""

            val fromUndefined = parseSyncProgressResponse(js("undefined"))
            fromUndefined.seeded shouldBe false
            fromUndefined.offset shouldBe ""
            fromUndefined.total shouldBe ""
        }

        "parseTradeRecord and parsePortfolioSnapshot handle empty dynamic objects gracefully" {
            val emptyRecord = parseTradeRecord(json())
            emptyRecord.timestamp shouldBe ""
            emptyRecord.symbol shouldBe ""
            emptyRecord.success shouldBe false
            emptyRecord.dryRun shouldBe false
            emptyRecord.price shouldBe "0"
            emptyRecord.fee shouldBe "0"

            val emptySnapshot = parsePortfolioSnapshot(json())
            emptySnapshot.timestamp shouldBe ""
            emptySnapshot.totalValueUSD shouldBe ""
            emptySnapshot.assets.isEmpty() shouldBe true
            emptySnapshot.actions.isEmpty() shouldBe true
        }

        "parseRewardsOverTimePoint handles null perAssetUSD safely" {
            val point =
                parseRewardsOverTimePoint(
                    json(
                        "timestamp" to "2023-01-01T00:00:00Z",
                        "cumulativeUSD" to "10.00",
                        "perAssetUSD" to null,
                    ),
                )
            point.timestamp shouldBe "2023-01-01T00:00:00Z"
            point.cumulativeUSD shouldBe "10.00"
            point.perAssetUSD.isEmpty() shouldBe true
        }
    }

    private fun baseTrade(): dynamic = json(
        "timestamp" to "2023-01-01T10:00:00Z",
        "pair" to "BTCUSD",
        "side" to "SELL",
        "symbol" to "BTC",
        "volume" to "1",
        "usdAmount" to "100",
        "success" to true,
        "dryRun" to false,
    )
}
