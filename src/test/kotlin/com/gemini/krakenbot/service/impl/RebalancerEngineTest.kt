package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.Result
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
            RebalancerEngine.resolvePriceFromTicker(Asset.BTC, rawPrices)
                .shouldBeEqualComparingTo(BigDecimal("60000.00"))
            RebalancerEngine.resolvePriceFromTicker(Asset.ETH, rawPrices)
                .shouldBeEqualComparingTo(BigDecimal("3000.00"))
            RebalancerEngine.resolvePriceFromTicker("SOL", rawPrices)
                .shouldBeEqualComparingTo(BigDecimal.ZERO)
        }

        "calculateDrawdown computes exact percentage from ATH" {
            val ath = BigDecimal("100000.00")
            val current = BigDecimal("80000.00")
            val dd = RebalancerEngine.calculateDrawdown(current, ath)
            dd.shouldBeEqualComparingTo(BigDecimal("20.00"))
        }

        "calculateFiatDeployment scales correctly with drawdown exponent" {
            val dd = BigDecimal("10.00")
            val deployment = RebalancerEngine.calculateFiatDeployment(dd, settings)
            deployment.shouldBeEqualComparingTo(BigDecimal("50.00"))
        }

        "calculateEffectiveUsdTarget shrinks USD target when fiat deployment > 0" {
            val deployment = BigDecimal("50.00")
            val effectiveUsd = RebalancerEngine.calculateEffectiveUsdTarget(deployment, allocations)
            effectiveUsd.shouldBeEqualComparingTo(BigDecimal("10.00"))
        }

        "calculateCryptoScaleFactor redistributes freed USD to crypto allocations" {
            val effectiveUsd = BigDecimal("10.00")
            val scale = RebalancerEngine.calculateCryptoScaleFactor(effectiveUsd, allocations)
            scale.shouldBeEqualComparingTo(BigDecimal("1.12500000"))
        }

        "calculatePortfolioValues aborts when a non-USD price is missing" {
            val balances = mapOf(
                "XXBT" to BigDecimal("1"),
                "XETH" to BigDecimal("1"),
                "ZUSD" to BigDecimal("1000"),
            )
            val prices = mapOf(Asset.BTC to BigDecimal("60000"), Asset.ETH to BigDecimal.ZERO)
            val result = RebalancerEngine.calculatePortfolioValues(balances, prices, allocations)
            result.shouldBeInstanceOf<Result.Failure<*>>()
        }

        "analyzeDeviations sells overweight crypto when both gates fire" {
            val total = BigDecimal("10000.00")
            // BTC 60% vs 50% target with large USD deviation → sell trigger
            val values = mapOf(
                Asset.BTC to BigDecimal("6000.00"),
                Asset.ETH to BigDecimal("3000.00"),
                Asset.USD to BigDecimal("1000.00"),
            )
            val result = RebalancerEngine.analyzeDeviations(
                totalPortfolioValueUSD = total,
                currentValuesUSD = values,
                effectiveUsdTarget = BigDecimal("20.00"),
                cryptoScaleFactor = BigDecimal.ONE,
                allocations = allocations,
                settings = settings.copy(deviationTriggerPercent = 5.0, dustThresholdUSD = 10.0),
            )
            result.sellOrders.shouldContainKey(Asset.BTC)
            result.buyOrders.shouldBeEmpty()
        }

        "distributeFiatCorrection buys underweights on USD deposit" {
            val buyOrders = mutableMapOf<String, BigDecimal>()
            val sellOrders = mutableMapOf<String, BigDecimal>()
            val actionLog = mutableListOf<String>()
            RebalancerEngine.distributeFiatCorrection(
                usdDev = BigDecimal("100.00"),
                allDevs = mapOf(
                    Asset.USD to BigDecimal("100.00"),
                    Asset.BTC to BigDecimal("-60.00"),
                    Asset.ETH to BigDecimal("-40.00"),
                ),
                buyOrders = buyOrders,
                sellOrders = sellOrders,
                actionLog = actionLog,
            )
            buyOrders[Asset.BTC]!!.shouldBeEqualComparingTo(BigDecimal("60.00"))
            buyOrders[Asset.ETH]!!.shouldBeEqualComparingTo(BigDecimal("40.00"))
            sellOrders.shouldBeEmpty()
            actionLog.isNotEmpty() shouldBe true
        }
    })
