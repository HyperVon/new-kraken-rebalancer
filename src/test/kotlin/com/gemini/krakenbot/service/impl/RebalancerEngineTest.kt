package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class RebalancerEngineTest :
    StringSpec({
        val allocations = listOf(
            Allocation(Asset.BTC, 50.0),
            Allocation(Asset.ETH, 30.0),
            Allocation(Asset.USD, 20.0),
        )

        val settings = Settings(
            loopDelaySeconds = 60,
            deviationTriggerPercent = 5.0,
            dustThresholdUSD = 10.0,
            dryRun = true,
            simulation = true,
            fiatMaxDrawdown = 20.0,
            fiatDeploymentExponent = 1.0,
        )

        "resolvePriceFromTicker resolves exact trading pair" {
            val rawPrices = mapOf("XXBTZUSD" to BigDecimal("60000.00"), "XETHZUSD" to BigDecimal("3000.00"))
            RebalancerEngine.resolvePriceFromTicker(Asset.BTC, rawPrices) shouldBe BigDecimal("60000.00")
            RebalancerEngine.resolvePriceFromTicker(Asset.ETH, rawPrices) shouldBe BigDecimal("3000.00")
            RebalancerEngine.resolvePriceFromTicker("SOL", rawPrices) shouldBe BigDecimal.ZERO
        }

        "calculateDrawdown computes exact percentage from ATH" {
            val ath = BigDecimal("100000.00")
            val current = BigDecimal("80000.00")
            val dd = RebalancerEngine.calculateDrawdown(current, ath)
            dd.compareTo(BigDecimal("20.00")) shouldBe 0
        }

        "calculateFiatDeployment scales correctly with drawdown exponent" {
            val dd = BigDecimal("10.00") // 10% drawdown with max 20%
            val deployment = RebalancerEngine.calculateFiatDeployment(dd, settings)
            deployment.compareTo(BigDecimal("50.00")) shouldBe 0
        }

        "calculateEffectiveUsdTarget shrinks USD target when fiat deployment > 0" {
            val deployment = BigDecimal("50.00") // 50% deploy
            val effectiveUsd = RebalancerEngine.calculateEffectiveUsdTarget(deployment, allocations)
            // 20% base * (1 - 0.50) = 10%
            effectiveUsd.compareTo(BigDecimal("10.00")) shouldBe 0
        }

        "calculateCryptoScaleFactor redistributes freed USD to crypto allocations" {
            val effectiveUsd = BigDecimal("10.00") // 90% left for crypto (base total 80%)
            val scale = RebalancerEngine.calculateCryptoScaleFactor(effectiveUsd, allocations)
            // 90 / 80 = 1.125
            scale.compareTo(BigDecimal("1.12500000")) shouldBe 0
        }
    })
