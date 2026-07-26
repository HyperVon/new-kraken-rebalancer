package com.gemini.krakenbot.frontend

import com.gemini.krakenbot.api.HistoryStats
import com.gemini.krakenbot.api.PortfolioSnapshot
import com.gemini.krakenbot.api.SyncProgressResponse
import com.gemini.krakenbot.api.TradeRecord
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeSourceKeys
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.js.json

class HistoryJsonParsingTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "parsePortfolioSnapshots reads string decimals and nested assets" {
            val raw =
                arrayOf(
                    json(
                        "timestamp" to "2023-01-01T00:00:00Z",
                        "totalValueUSD" to "15000.50",
                        "assets" to
                            json(
                                Asset.BTC to
                                    json(
                                        "symbol" to Asset.BTC,
                                        "balance" to "0.5",
                                        "price" to "20000",
                                        "valueUSD" to "10000",
                                        "targetPercent" to "50",
                                        "currentPercent" to "66.6666",
                                        "deviationPercent" to "16.6666",
                                        "deviationUSD" to "2500.25",
                                    ),
                            ),
                        "actions" to arrayOf("SELL 0.125 BTC"),
                        "drawdownPercent" to "5",
                        "fiatDeploymentPercent" to "10",
                        "effectiveUsdTargetPercent" to "40",
                    ),
                )

            val parsed = parsePortfolioSnapshots(raw)
            parsed.size shouldBe 1
            parsed[0].totalValueUSD shouldBe "15000.50"
            parsed[0].assets[Asset.BTC]?.deviationUSD shouldBe "2500.25"
            parsed[0].actions shouldBe listOf("SELL 0.125 BTC")
        }

        "parseTradeRecords accepts numeric or string economics" {
            val raw =
                arrayOf(
                    json(
                        "timestamp" to "2023-01-01T10:00:00Z",
                        "pair" to "BTCUSD",
                        "side" to OrderSide.BUY.name,
                        "symbol" to Asset.BTC,
                        "volume" to 0.125,
                        "usdAmount" to "2500.00",
                        "success" to true,
                        "dryRun" to false,
                        "price" to 20000,
                        "fee" to "6.50",
                        "slippagePercent" to 0.15,
                        "source" to TradeSourceKeys.LOCAL_ESTIMATE,
                    ),
                )

            val parsed = parseTradeRecords(raw)
            parsed.size shouldBe 1
            parsed[0].volume shouldBe "0.125"
            parsed[0].usdAmount shouldBe "2500.00"
            parsed[0].source shouldBe TradeSourceKeys.LOCAL_ESTIMATE
        }

        "parseHistoryStats reads counts and nullable averages" {
            val raw =
                json(
                    "allTimeHigh" to 15000.5,
                    "totalTradesExecuted" to 12,
                    "totalVolumeTraded" to "50000.00",
                    "totalFeesPaid" to "25.50",
                    "latestSnapshotTime" to "2023-01-01T12:00:00Z",
                    "avgFeeRatePercent" to "0.26",
                    "avgSlippagePercent" to null,
                    "failedTradeCount" to 1,
                    "dryRunTradeCount" to 2,
                )

            val parsed = parseHistoryStats(raw)
            parsed.allTimeHigh shouldBe "15000.5"
            parsed.totalTradesExecuted shouldBe 12L
            parsed.avgSlippagePercent shouldBe null
        }

        "parseSyncProgressResponse uses SyncMetadataKeys names" {
            val raw =
                json(
                    SyncMetadataKeys.IS_SEEDED to false,
                    SyncMetadataKeys.OFFSET to "123",
                    SyncMetadataKeys.TOTAL to "456",
                )

            parseSyncProgressResponse(raw) shouldBe
                SyncProgressResponse(seeded = false, offset = "123", total = "456")
        }

        "portfolioSnapshotToDynamic round-trips through parsePortfolioSnapshot" {
            val snapshot =
                PortfolioSnapshot(
                    timestamp = "2023-01-01T00:00:00Z",
                    totalValueUSD = "100",
                    assets =
                    mapOf(
                        Asset.BTC to
                            PortfolioSnapshot.AssetSnapshot(
                                symbol = Asset.BTC,
                                balance = "1",
                                price = "100",
                                valueUSD = "100",
                                targetPercent = "50",
                                currentPercent = "100",
                                deviationPercent = "0",
                                deviationUSD = "0",
                            ),
                    ),
                    actions = emptyList(),
                    drawdownPercent = "0",
                    fiatDeploymentPercent = "0",
                    effectiveUsdTargetPercent = "0",
                )

            parsePortfolioSnapshot(portfolioSnapshotToDynamic(snapshot)) shouldBe snapshot
        }

        "tradeRecordToDynamic round-trips through parseTradeRecord" {
            val trade =
                TradeRecord(
                    timestamp = "2023-01-01T10:00:00Z",
                    pair = "BTCUSD",
                    side = OrderSide.SELL.name,
                    symbol = Asset.BTC,
                    volume = "1",
                    usdAmount = "100",
                    success = true,
                    dryRun = false,
                )

            parseTradeRecord(tradeRecordToDynamic(trade)) shouldBe trade
        }

        "historyStatsToDynamic round-trips through parseHistoryStats" {
            val stats =
                HistoryStats(
                    allTimeHigh = "15000.5",
                    totalTradesExecuted = 42L,
                    totalVolumeTraded = "1000000.0",
                    totalFeesPaid = "250.75",
                    latestSnapshotTime = null,
                    avgFeeRatePercent = "0.26",
                    avgSlippagePercent = "0.15",
                )

            parseHistoryStats(historyStatsToDynamic(stats)) shouldBe stats
        }
    }
}
