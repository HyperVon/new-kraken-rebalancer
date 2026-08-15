package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.domain.RebalanceEvent
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.Result
import com.gemini.krakenbot.service.PortfolioValues
import com.gemini.krakenbot.util.ActionLogFormatter
import com.gemini.krakenbot.view.util.ViewText
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal

class RebalancerEngineTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        val allocations = listOf(
            Allocation(Asset.BTC, 50.0),
            Allocation(Asset.ETH, 30.0),
            Allocation(Asset.USD, 20.0),
        )

        val settings = TestFixtures.settings(
            simulation = true,
            loopDelaySeconds = 60,
            deviationTriggerPercent = 5.0,
            minimumOrderSizeUSD = 10.0,
            fiatMaxDrawdown = 20.0,
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

        "calculateDrawdown retains four decimal places for repeating ratios" {
            val dd = RebalancerEngine.calculateDrawdown(
                totalPortfolioValueUSD = BigDecimal("2.00"),
                ath = BigDecimal("3.00"),
            )

            dd.shouldBeEqualComparingTo(BigDecimal("33.3333"))
        }

        "calculateFiatDeployment scales correctly with drawdown exponent" {
            val dd = BigDecimal("10.00")
            val deployment = RebalancerEngine.calculateFiatDeployment(dd, settings)
            deployment.shouldBeEqualComparingTo(BigDecimal("50.00"))
        }

        "calculateFiatDeployment returns zero when drawdown is zero even with 0.0 exponent" {
            val deployment = RebalancerEngine.calculateFiatDeployment(
                BigDecimal.ZERO,
                settings.copy(fiatDeploymentExponent = 0.0),
            )
            deployment.shouldBeEqualComparingTo(BigDecimal.ZERO)
        }

        "calculateFiatDeployment caps deployment at 100 percent" {
            val deployment = RebalancerEngine.calculateFiatDeployment(
                BigDecimal("50.00"),
                settings.copy(fiatMaxDrawdown = 20.0, fiatDeploymentExponent = 0.5),
            )
            deployment.shouldBeEqualComparingTo(BigDecimal("100.00"))
        }

        "calculateFiatDeployment returns zero when exponent evaluation is non-finite" {
            val deployment = RebalancerEngine.calculateFiatDeployment(
                BigDecimal("10.00"),
                settings.copy(fiatMaxDrawdown = 20.0, fiatDeploymentExponent = Double.NaN),
            )
            deployment.shouldBeEqualComparingTo(BigDecimal.ZERO)
        }

        "calculateEffectiveUsdTarget shrinks USD target when fiat deployment > 0" {
            val deployment = BigDecimal("50.00")
            val effectiveUsd = RebalancerEngine.calculateEffectiveUsdTarget(deployment, allocations)
            effectiveUsd.shouldBeEqualComparingTo(BigDecimal("10.00"))
        }

        "calculateEffectiveUsdTarget leaves USD unchanged when no crypto target can receive deployment" {
            listOf(
                listOf(Allocation(Asset.USD, 100.0)),
                listOf(Allocation(Asset.BTC, 0.0), Allocation(Asset.USD, 100.0)),
            ).forEach { noCryptoTargetAllocations ->
                RebalancerEngine.calculateEffectiveUsdTarget(BigDecimal("50.00"), noCryptoTargetAllocations)
                    .shouldBeEqualComparingTo(BigDecimal("100.00"))
            }
        }

        "calculateEffectiveUsdTarget leaves USD unchanged when deployment is zero" {
            RebalancerEngine.calculateEffectiveUsdTarget(BigDecimal.ZERO, allocations)
                .shouldBeEqualComparingTo(BigDecimal("20.00"))
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
            val failure = result.shouldBeInstanceOf<Result.Failure<*>>()
            failure.exception.message shouldBe "${ViewText.PRICE_NOT_FOUND_PREFIX}${Asset.ETH}"
        }

        "calculatePortfolioValues accumulates raw values before rounding the total once" {
            val tinyAllocations = listOf(
                Allocation(Asset.BTC, 50.0),
                Allocation(Asset.ETH, 50.0),
            )
            val result = RebalancerEngine.calculatePortfolioValues(
                balances = mapOf(Asset.BTC to BigDecimal("0.004"), Asset.ETH to BigDecimal("0.004")),
                prices = mapOf(Asset.BTC to BigDecimal.ONE, Asset.ETH to BigDecimal.ONE),
                allocations = tinyAllocations,
            ).shouldBeInstanceOf<Result.Success<PortfolioValues>>().value

            result.currentValuesUSD.getValue(Asset.BTC).shouldBeEqualComparingTo(BigDecimal("0.00"))
            result.currentValuesUSD.getValue(Asset.ETH).shouldBeEqualComparingTo(BigDecimal("0.00"))
            result.totalValueUSD.shouldBeEqualComparingTo(BigDecimal("0.01"))
        }

        "analyzeDeviations sells overweight crypto when both gates fire" {
            val total = BigDecimal("10000.00")
            // BTC 60% vs 50% target → DevUSD +1000; |Dev%| = 20 ≥ 5 and significant
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
                settings = settings.copy(deviationTriggerPercent = 5.0, minimumOrderSizeUSD = 10.0),
            )
            result.sellOrders.shouldContainKey(Asset.BTC)
            result.sellOrders.getValue(Asset.BTC).shouldBeEqualComparingTo(BigDecimal("1000.00"))
            result.buyOrders.shouldBeEmpty()
            result.actionLog.shouldContain(
                ActionLogFormatter.formatDeviationTrigger(Asset.BTC, BigDecimal("20")),
            )
        }

        "analyzeDeviationsPlan emits typed events before legacy log formatting" {
            val plan = RebalancerEngine.analyzeDeviationsPlan(
                totalPortfolioValueUSD = BigDecimal("10000.00"),
                currentValuesUSD = mapOf(
                    Asset.BTC to BigDecimal("6000.00"),
                    Asset.ETH to BigDecimal("3000.00"),
                    Asset.USD to BigDecimal("1000.00"),
                ),
                effectiveUsdTarget = BigDecimal("20.00"),
                cryptoScaleFactor = BigDecimal.ONE,
                allocations = allocations,
                settings = settings.copy(deviationTriggerPercent = 5.0, minimumOrderSizeUSD = 10.0),
            )

            val btcTrigger = plan.events
                .filterIsInstance<RebalanceEvent.DeviationTriggered>()
                .single { it.symbol == Asset.BTC }
            btcTrigger.deviationPercent.shouldBeEqualComparingTo(BigDecimal("20"))
            plan.events.filterIsInstance<RebalanceEvent.DeviationTriggered>()
                .map { it.symbol } shouldContain Asset.BTC
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
            actionLog.shouldContain(
                ActionLogFormatter.formatFiatCorrectionDistribution(BigDecimal("100.00"), 2),
            )
        }

        "distributeFiatCorrectionPlan returns typed distribution event" {
            val events = mutableListOf<RebalanceEvent>()
            RebalancerEngine.distributeFiatCorrectionPlan(
                usdDev = BigDecimal("100.00"),
                allDevs = mapOf(
                    Asset.USD to BigDecimal("100.00"),
                    Asset.BTC to BigDecimal("-60.00"),
                    Asset.ETH to BigDecimal("-40.00"),
                ),
                buyOrders = mutableMapOf(),
                sellOrders = mutableMapOf(),
                events = events,
            )

            val distribution = events.filterIsInstance<RebalanceEvent.FiatCorrectionDistributed>().single()
            distribution.usdAmount.shouldBeEqualComparingTo(BigDecimal("100.00"))
            distribution.candidateCount shouldBe 2
        }
    }
}
