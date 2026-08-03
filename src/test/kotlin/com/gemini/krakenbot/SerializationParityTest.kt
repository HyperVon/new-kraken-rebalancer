package com.gemini.krakenbot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.gemini.krakenbot.api.SyncProgressResponse
import com.gemini.krakenbot.api.buildSyncProgressResponse
import com.gemini.krakenbot.api.toApiDto
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonConfidence
import com.gemini.krakenbot.model.ComparisonUnavailableReason
import com.gemini.krakenbot.model.HistoryStats
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.model.RebalancerComparison
import com.gemini.krakenbot.model.RebalancerComparisonPoint
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.math.BigDecimal
import java.time.Instant
import com.gemini.krakenbot.api.HistoryStats as ApiHistoryStats
import com.gemini.krakenbot.api.PortfolioSnapshot as ApiPortfolioSnapshot
import com.gemini.krakenbot.api.RebalancerComparison as ApiRebalancerComparison
import com.gemini.krakenbot.api.TradeRecord as ApiTradeRecord

class SerializationParityTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    init {
        "should parse legacy Java PortfolioStats JSON accurately" {
            val legacyJson =
                """
                {
                  "allTimeHigh": 123456.789101112
                }
                """.trimIndent()

            val parsed: PortfolioStats = mapper.readValue(legacyJson)
            parsed.allTimeHigh.shouldBeEqualComparingTo(BigDecimal("123456.789101112"))
        }

        "should parse legacy Java PortfolioSnapshot JSON accurately" {
            val legacyJson =
                """
                [
                  {
                    "timestamp": 1672567200.000000000,
                    "totalValueUSD": 15000.50,
                    "assets": {
                      "XXBTZUSD": {
                        "symbol": "XXBTZUSD",
                        "balance": 0.5,
                        "price": 20000.0,
                        "valueUSD": 10000.0,
                        "targetPercent": 50.0,
                        "currentPercent": 66.6666,
                        "deviationPercent": 16.6666,
                        "deviationUSD": 2500.25
                      }
                    },
                    "actions": [
                      "SELL 0.125 XXBTZUSD"
                    ],
                    "drawdownPercent": 5.0,
                    "fiatDeploymentPercent": 10.0,
                    "effectiveUsdTargetPercent": 40.0
                  }
                ]
                """.trimIndent()

            val parsed: List<PortfolioSnapshot> = mapper.readValue(legacyJson)
            parsed shouldHaveSize 1
            val snapshot = parsed[0]

            snapshot.totalValueUSD.shouldBeEqualComparingTo(BigDecimal("15000.50"))
            snapshot.drawdownPercent.shouldBeEqualComparingTo(BigDecimal("5.0"))
            snapshot.fiatDeploymentPercent.shouldBeEqualComparingTo(BigDecimal("10.0"))
            snapshot.effectiveUsdTargetPercent.shouldBeEqualComparingTo(BigDecimal("40.0"))
            snapshot.actions shouldHaveSize 1
            snapshot.actions[0] shouldBe "SELL 0.125 XXBTZUSD"

            val btcAsset = snapshot.assets[TestFixtures.XXBTZUSD]
            btcAsset?.symbol?.value shouldBe TestFixtures.XXBTZUSD
            btcAsset?.balance?.shouldBeEqualComparingTo(BigDecimal("0.5"))
            btcAsset?.price?.shouldBeEqualComparingTo(BigDecimal("20000.0"))
            btcAsset?.valueUSD?.shouldBeEqualComparingTo(BigDecimal("10000.0"))
            btcAsset?.targetPercent?.shouldBeEqualComparingTo(BigDecimal("50.0"))
            btcAsset?.currentPercent?.shouldBeEqualComparingTo(BigDecimal("66.6666"))
            btcAsset?.deviationPercent?.shouldBeEqualComparingTo(BigDecimal("16.6666"))
            btcAsset?.deviationUSD?.shouldBeEqualComparingTo(BigDecimal("2500.25"))
        }

        "history API PortfolioSnapshot DTO serializes string decimals and ISO timestamps" {
            val domain =
                PortfolioSnapshot(
                    timestamp = Instant.parse("2023-01-01T12:00:00Z"),
                    totalValueUSD = BigDecimal("15000.50"),
                    assets =
                    mapOf(
                        TestFixtures.XXBTZUSD to
                            TestFixtures.assetSnapshot(
                                symbol = TestFixtures.XXBTZUSD,
                                balance = BigDecimal("0.5"),
                                price = BigDecimal("20000.0"),
                                valueUSD = BigDecimal("10000.0"),
                                targetPercent = BigDecimal("50.0"),
                                currentPercent = BigDecimal("66.6666"),
                                deviationPercent = BigDecimal("16.6666"),
                                deviationUSD = BigDecimal("2500.25"),
                            ),
                    ),
                    actions = listOf("SELL 0.125 XXBTZUSD"),
                    drawdownPercent = BigDecimal("5.0"),
                    fiatDeploymentPercent = BigDecimal("10.0"),
                    effectiveUsdTargetPercent = BigDecimal("40.0"),
                )

            val json = mapper.writeValueAsString(listOf(domain.toApiDto()))
            json shouldContain "\"timestamp\":\"2023-01-01T12:00:00Z\""
            json shouldContain "\"totalValueUSD\":\"15000.50\""
            json shouldContain "\"deviationUSD\":\"2500.25\""

            val roundTrip: List<ApiPortfolioSnapshot> = mapper.readValue(json)
            roundTrip shouldHaveSize 1
            roundTrip[0].assets[TestFixtures.XXBTZUSD]?.symbol shouldBe TestFixtures.XXBTZUSD
        }

        "history API TradeRecord DTO serializes string economics" {
            val domain =
                TestFixtures.tradeRecord(
                    timestamp = Instant.parse("2023-01-01T10:00:00Z"),
                    pair = TestFixtures.BTCUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.125"),
                    usdAmount = BigDecimal("2500.00"),
                    price = BigDecimal("20000.0"),
                    fee = BigDecimal("6.50"),
                    slippagePercent = BigDecimal("0.15"),
                    expectedPrice = BigDecimal("19970.0"),
                    source = TradeSource.LOCAL_ESTIMATE,
                    id = 42,
                )

            val json = mapper.writeValueAsString(domain.toApiDto())
            json shouldContain "\"usdAmount\":\"2500.00\""
            json shouldContain "\"source\":\"LOCAL_ESTIMATE\""
            json shouldContain "\"id\":42"

            val roundTrip: ApiTradeRecord = mapper.readValue(json)
            roundTrip.symbol shouldBe Asset.BTC
            roundTrip.slippagePercent shouldBe "0.15"
        }

        "history API HistoryStats DTO serializes string decimals and counts" {
            val domain =
                HistoryStats(
                    allTimeHigh = BigDecimal("15000.00"),
                    totalTradesExecuted = 12L,
                    totalVolumeTraded = BigDecimal("50000.00"),
                    totalFeesPaid = BigDecimal("25.50"),
                    latestSnapshotTime = Instant.parse("2023-01-01T12:00:00Z"),
                    avgFeeRatePercent = BigDecimal("0.26"),
                    avgSlippagePercent = BigDecimal("0.15"),
                    failedTradeCount = 1L,
                    dryRunTradeCount = 2L,
                )

            val json = mapper.writeValueAsString(domain.toApiDto())
            json shouldContain "\"allTimeHigh\":\"15000.00\""
            json shouldContain "\"totalTradesExecuted\":12"
            json shouldContain "\"avgSlippagePercent\":\"0.15\""

            val roundTrip: ApiHistoryStats = mapper.readValue(json)
            roundTrip.totalVolumeTraded shouldBe "50000.00"
        }

        "history API TradeRecord DTO round-trips null optionals and zero economics" {
            val domain =
                TestFixtures.tradeRecord(
                    timestamp = Instant.parse("2023-01-01T10:00:00Z"),
                    pair = TestFixtures.BTCUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.1"),
                    usdAmount = BigDecimal("100.00"),
                    // price/fee default to ZERO; all optionals null.
                )

            val json = mapper.writeValueAsString(domain.toApiDto())
            // ZERO BigDecimal serializes as the plain string "0" the JS parser also defaults to.
            json shouldContain "\"price\":\"0\""
            json shouldContain "\"fee\":\"0\""

            val roundTrip: ApiTradeRecord = mapper.readValue(json)
            roundTrip.price shouldBe "0"
            roundTrip.fee shouldBe "0"
            roundTrip.slippagePercent shouldBe null
            roundTrip.expectedPrice shouldBe null
            roundTrip.source shouldBe null
            roundTrip.errorMessage shouldBe null
            roundTrip.id shouldBe null
        }

        "buildSyncProgressResponse maps null offset and total to empty-string wire fields" {
            // buildSyncProgressResponse (production) applies orEmpty(); the emitted JSON must carry
            // empty strings (not null) so the JS parser's dynamicString(...).orEmpty() agrees.
            val json = mapper.writeValueAsString(buildSyncProgressResponse(seeded = false, offset = null, total = null))
            json shouldContain "\"offset\":\"\""
            json shouldContain "\"total\":\"\""
        }

        "history API SyncProgressResponse uses stable JSON names" {
            val response = buildSyncProgressResponse(seeded = false, offset = "123", total = "456")
            val json = mapper.writeValueAsString(response)
            json shouldContain "\"seeded\":false"
            json shouldContain "\"offset\":\"123\""
            json shouldContain "\"total\":\"456\""

            val roundTrip: SyncProgressResponse = mapper.readValue(json)
            roundTrip.seeded shouldBe false
            roundTrip.offset shouldBe "123"
        }

        "comparison API DTO serializes string decimals and ISO timestamps" {
            val domain = RebalancerComparison(
                availability = ComparisonAvailability.AVAILABLE,
                confidence = ComparisonConfidence.RECONCILED,
                baselineTimestamp = Instant.parse("2026-07-01T12:00:00Z"),
                points = listOf(
                    RebalancerComparisonPoint(
                        timestamp = Instant.parse("2026-07-01T12:00:00Z"),
                        rebalancerValueUSD = BigDecimal("100000.00"),
                        buyAndHoldValueUSD = BigDecimal("100000.00"),
                        differenceUSD = BigDecimal.ZERO,
                        differencePercent = BigDecimal.ZERO,
                    ),
                    RebalancerComparisonPoint(
                        timestamp = Instant.parse("2026-07-02T12:00:00Z"),
                        rebalancerValueUSD = BigDecimal("110000.00"),
                        buyAndHoldValueUSD = BigDecimal("105000.00"),
                        differenceUSD = BigDecimal("5000.00"),
                        differencePercent = BigDecimal("4.7619"),
                    ),
                ),
                latestDifferenceUSD = BigDecimal("5000.00"),
                latestDifferencePercent = BigDecimal("4.7619"),
                unavailableReason = null,
                unavailableAt = null,
            )

            val json = mapper.writeValueAsString(domain.toApiDto())
            json shouldContain "\"availability\":\"AVAILABLE\""
            json shouldContain "\"confidence\":\"RECONCILED\""
            json shouldContain "\"baselineTimestamp\":\"2026-07-01T12:00:00Z\""
            json shouldContain "\"rebalancerValueUSD\":\"100000.00\""
            json shouldContain "\"buyAndHoldValueUSD\":\"105000.00\""
            json shouldContain "\"differenceUSD\":\"5000.00\""
            json shouldContain "\"differencePercent\":\"4.7619\""
            json shouldContain "\"latestDifferenceUSD\":\"5000.00\""
            json shouldContain "\"unavailableReason\":null"

            val roundTrip: ApiRebalancerComparison = mapper.readValue(json)
            roundTrip.points shouldHaveSize 2
            roundTrip.points[1].differenceUSD shouldBe "5000.00"
        }

        "comparison API DTO serializes unavailable state" {
            val domain = RebalancerComparison(
                availability = ComparisonAvailability.UNAVAILABLE,
                confidence = null,
                baselineTimestamp = Instant.parse("2026-07-01T12:00:00Z"),
                points = emptyList(),
                latestDifferenceUSD = null,
                latestDifferencePercent = null,
                unavailableReason = ComparisonUnavailableReason.MISSING_PRICE,
                unavailableAt = Instant.parse("2026-07-01T12:00:00Z"),
            )

            val json = mapper.writeValueAsString(domain.toApiDto())
            json shouldContain "\"availability\":\"UNAVAILABLE\""
            json shouldContain "\"unavailableReason\":\"MISSING_PRICE\""
            json shouldContain "\"points\":[]"

            val roundTrip: ApiRebalancerComparison = mapper.readValue(json)
            roundTrip.availability shouldBe "UNAVAILABLE"
            roundTrip.unavailableReason shouldBe "MISSING_PRICE"
            roundTrip.points shouldHaveSize 0
        }
    }
}
