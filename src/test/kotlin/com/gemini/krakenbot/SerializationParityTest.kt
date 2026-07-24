package com.gemini.krakenbot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.PortfolioStats
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

@Suppress("unused")
class SerializationParityTest : StringSpec() {
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
            parsed.allTimeHigh.compareTo(BigDecimal("123456.789101112")) shouldBe 0
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

            snapshot.totalValueUSD.compareTo(BigDecimal("15000.50")) shouldBe 0
            snapshot.drawdownPercent.compareTo(BigDecimal("5.0")) shouldBe 0
            snapshot.fiatDeploymentPercent.compareTo(BigDecimal("10.0")) shouldBe 0
            snapshot.effectiveUsdTargetPercent.compareTo(BigDecimal("40.0")) shouldBe 0
            snapshot.actions shouldHaveSize 1
            snapshot.actions[0] shouldBe "SELL 0.125 XXBTZUSD"

            val btcAsset = snapshot.assets[TestFixtures.XXBTZUSD]
            btcAsset?.symbol?.value shouldBe TestFixtures.XXBTZUSD
            btcAsset?.balance?.compareTo(BigDecimal("0.5")) shouldBe 0
            btcAsset?.price?.compareTo(BigDecimal("20000.0")) shouldBe 0
            btcAsset?.valueUSD?.compareTo(BigDecimal("10000.0")) shouldBe 0
            btcAsset?.targetPercent?.compareTo(BigDecimal("50.0")) shouldBe 0
            btcAsset?.currentPercent?.compareTo(BigDecimal("66.6666")) shouldBe 0
            btcAsset?.deviationPercent?.compareTo(BigDecimal("16.6666")) shouldBe 0
            btcAsset?.deviationUSD?.compareTo(BigDecimal("2500.25")) shouldBe 0
        }
    }
}
