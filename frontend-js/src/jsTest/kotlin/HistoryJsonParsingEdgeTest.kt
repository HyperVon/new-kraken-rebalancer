package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.model.SyncMetadataKeys
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.js.JSON
import kotlin.js.json

/**
 * Edge/wire-contract coverage for [HistoryJsonParsing] against the shape [HistoryApiMapper] emits.
 * Uses real `JSON.parse` (not hand-built `json()`) where the parser branches on runtime JS types
 * (native booleans, numeric `id`) so the Jackson→JS wire is exercised, plus the missing/null/blank
 * field defaults that hand-built round-trips never hit.
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

        "parseTradeRecord reads native JSON booleans and a numeric id (Jackson wire)" {
            // Jackson emits real JSON booleans and a numeric id; JSON.parse yields JS boolean/number,
            // exercising the `is Boolean` and numeric dynamicInt arms.
            val wire =
                """
                {
                  "timestamp":"2023-01-01T10:00:00Z","pair":"XXBTZUSD","side":"SELL","symbol":"BTC",
                  "volume":"0.125","usdAmount":"2500.00","success":true,"dryRun":false,
                  "price":"20000.0","fee":"6.50","slippagePercent":"0.15","source":"API_FILL","id":42
                }
                """.trimIndent()

            val parsed = parseTradeRecord(JSON.parse(wire))
            parsed.success shouldBe true
            parsed.dryRun shouldBe false
            parsed.id shouldBe 42
            parsed.source shouldBe "API_FILL"
            parsed.price shouldBe "20000.0"
        }

        "parseTradeRecord parses a string id and rejects a non-numeric id" {
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
                    json(SyncMetadataKeys.IS_SEEDED to true),
                )
            parsed.seeded shouldBe true
            parsed.offset shouldBe ""
            parsed.total shouldBe ""
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
