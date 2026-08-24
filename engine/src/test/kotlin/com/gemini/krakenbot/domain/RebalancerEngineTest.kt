package com.gemini.krakenbot.domain

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.Result
import com.gemini.krakenbot.util.PrecisionConstants
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
        val allocations = EngineTestFixtures.defaultAllocations()

        val settings = EngineTestFixtures.settings(
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

        "calculateFiatDeployment returns zero when drawdown is zero or exponent is non-positive" {
            val zeroDdDeployment = RebalancerEngine.calculateFiatDeployment(
                BigDecimal.ZERO,
                settings.copy(fiatDeploymentExponent = 0.0),
            )
            zeroDdDeployment.shouldBeEqualComparingTo(BigDecimal.ZERO)

            val zeroExpDeployment = RebalancerEngine.calculateFiatDeployment(
                BigDecimal("10.00"),
                settings.copy(fiatDeploymentExponent = 0.0),
            )
            zeroExpDeployment.shouldBeEqualComparingTo(BigDecimal.ZERO)

            val negExpDeployment = RebalancerEngine.calculateFiatDeployment(
                BigDecimal("10.00"),
                settings.copy(fiatDeploymentExponent = -1.5),
            )
            negExpDeployment.shouldBeEqualComparingTo(BigDecimal.ZERO)
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
            failure.exception.message shouldBe "Price not found for ${Asset.ETH}"
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
            val plan = RebalancerEngine.analyzeDeviationsPlan(
                totalPortfolioValueUSD = total,
                currentValuesUSD = values,
                effectiveUsdTarget = BigDecimal("20.00"),
                cryptoScaleFactor = BigDecimal.ONE,
                allocations = allocations,
                settings = settings.copy(deviationTriggerPercent = 5.0, minimumOrderSizeUSD = 10.0),
            )
            plan.sellOrders.shouldContainKey(Asset.BTC)
            plan.sellOrders.getValue(Asset.BTC).shouldBeEqualComparingTo(BigDecimal("1000.00"))
            plan.buyOrders.shouldBeEmpty()
            plan.events.filterIsInstance<RebalanceEvent.DeviationTriggered>()
                .any { it.symbol == Asset.BTC && it.deviationPercent.compareTo(BigDecimal("20")) == 0 } shouldBe true
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
            val events = mutableListOf<RebalanceEvent>()
            RebalancerEngine.distributeFiatCorrectionPlan(
                usdDev = BigDecimal("100.00"),
                allDevs = mapOf(
                    Asset.USD to BigDecimal("100.00"),
                    Asset.BTC to BigDecimal("-60.00"),
                    Asset.ETH to BigDecimal("-40.00"),
                ),
                buyOrders = buyOrders,
                sellOrders = sellOrders,
                events = events,
            )
            buyOrders[Asset.BTC]!!.shouldBeEqualComparingTo(BigDecimal("60.00"))
            buyOrders[Asset.ETH]!!.shouldBeEqualComparingTo(BigDecimal("40.00"))
            sellOrders.shouldBeEmpty()
            events.filterIsInstance<RebalanceEvent.FiatCorrectionDistributed>().single()
                .let {
                    it.usdAmount.shouldBeEqualComparingTo(BigDecimal("100.00"))
                    it.candidateCount shouldBe 2
                }
        }

        "calculatePortfolioValues returns failure when crypto price is missing or zero" {
            val balances = mapOf("BTC" to BigDecimal("1.0"), "USD" to BigDecimal("100.00"))
            val missingPrice = RebalancerEngine.calculatePortfolioValues(
                balances = balances,
                prices = emptyMap(),
                allocations = EngineTestFixtures.defaultAllocations(),
            )
            (missingPrice is Result.Failure) shouldBe true

            val zeroPrice = RebalancerEngine.calculatePortfolioValues(
                balances = balances,
                prices = mapOf("BTC" to BigDecimal.ZERO, "ETH" to BigDecimal("3000.00")),
                allocations = EngineTestFixtures.defaultAllocations(),
            )
            (zeroPrice is Result.Failure) shouldBe true
        }

        "calculateCryptoScaleFactor returns ONE when all allocations are USD" {
            val allUsd = listOf(Allocation(Asset.USD, 100.0))
            RebalancerEngine.calculateCryptoScaleFactor(BigDecimal("100.00"), allUsd) shouldBe BigDecimal.ONE
        }

        "distributeFiatCorrection sells overweights on USD withdrawal" {
            val buyOrders = mutableMapOf<String, BigDecimal>()
            val sellOrders = mutableMapOf<String, BigDecimal>()
            val events = mutableListOf<RebalanceEvent>()
            RebalancerEngine.distributeFiatCorrectionPlan(
                usdDev = BigDecimal("-100.00"),
                allDevs = mapOf(
                    Asset.USD to BigDecimal("-100.00"),
                    Asset.BTC to BigDecimal("60.00"),
                    Asset.ETH to BigDecimal("40.00"),
                ),
                buyOrders = buyOrders,
                sellOrders = sellOrders,
                events = events,
            )
            sellOrders[Asset.BTC]!!.shouldBeEqualComparingTo(BigDecimal("60.00"))
            sellOrders[Asset.ETH]!!.shouldBeEqualComparingTo(BigDecimal("40.00"))
            buyOrders.shouldBeEmpty()
            events.filterIsInstance<RebalanceEvent.FiatCorrectionDistributed>().single()
                .candidateCount shouldBe 2
        }

        "BalanceKeys resolves matching keys and aliases" {
            val balances = mapOf("XXBT" to BigDecimal("1.5"), "ZUSD" to BigDecimal("1000.00"))
            resolveBalance("BTC", balances).shouldBeEqualComparingTo(BigDecimal("1.5"))
            resolveBalanceOrNull("BTC", balances)!!.shouldBeEqualComparingTo(BigDecimal("1.5"))
            resolveBalance("ETH", balances).shouldBeEqualComparingTo(BigDecimal.ZERO)
            resolveBalanceOrNull("ETH", balances) shouldBe null
        }

        "PrecisionConstantsJvm values match expectations" {
            PrecisionConstants.HUNDRED.shouldBeEqualComparingTo(BigDecimal("100"))
            PrecisionConstants.FEE_RATE_ESTIMATE.shouldBeEqualComparingTo(BigDecimal("0.006"))
            PrecisionConstants.CASH_RESERVE_FACTOR.shouldBeEqualComparingTo(BigDecimal("0.99"))
            PrecisionConstants.ALLOCATION_TOLERANCE.shouldBeEqualComparingTo(BigDecimal("0.01"))
        }

        "calculateDrawdown returns ZERO when totalPortfolioValue >= ath or ath <= 0" {
            RebalancerEngine.calculateDrawdown(BigDecimal("100.00"), BigDecimal("100.00")) shouldBe BigDecimal.ZERO
            RebalancerEngine.calculateDrawdown(BigDecimal("110.00"), BigDecimal("100.00")) shouldBe BigDecimal.ZERO
            RebalancerEngine.calculateDrawdown(BigDecimal("100.00"), BigDecimal.ZERO) shouldBe BigDecimal.ZERO
            RebalancerEngine.calculateDrawdown(BigDecimal("100.00"), BigDecimal("-10.00")) shouldBe BigDecimal.ZERO
        }

        "calculateFiatDeployment returns ZERO when fiatMaxDrawdown <= 0 or drawdown <= 0" {
            RebalancerEngine.calculateFiatDeployment(
                BigDecimal("10.00"),
                settings.copy(fiatMaxDrawdown = 0.0),
            ) shouldBe BigDecimal.ZERO
            RebalancerEngine.calculateFiatDeployment(
                BigDecimal("-5.00"),
                settings.copy(fiatMaxDrawdown = 20.0),
            ) shouldBe BigDecimal.ZERO
        }

        "analyzeDeviationsPlan handles crypto sells, USD withdrawal fiat correction, and skips fiat correction" {
            // Case 1: Crypto overweight -> sellOrders populated
            val sellPlan = RebalancerEngine.analyzeDeviationsPlan(
                totalPortfolioValueUSD = BigDecimal("1000.00"),
                currentValuesUSD = mapOf(
                    "BTC" to BigDecimal("700.00"),
                    "ETH" to BigDecimal("200.00"),
                    "USD" to BigDecimal("100.00"),
                ),
                effectiveUsdTarget = BigDecimal("20.00"),
                cryptoScaleFactor = BigDecimal.ONE,
                allocations = allocations,
                settings = settings,
            )
            sellPlan.sellOrders.shouldContainKey("BTC")

            // Case 2: USD withdrawal triggers fiat correction when crypto does not trigger
            val withdrawalPlan = RebalancerEngine.analyzeDeviationsPlan(
                totalPortfolioValueUSD = BigDecimal("1000.00"),
                currentValuesUSD = mapOf(
                    "BTC" to BigDecimal("550.00"),
                    "ETH" to BigDecimal("350.00"),
                    "USD" to BigDecimal("100.00"),
                ),
                effectiveUsdTarget = BigDecimal("20.00"),
                cryptoScaleFactor = BigDecimal.ONE,
                allocations = allocations,
                settings = settings.copy(deviationTriggerPercent = 18.0),
            )
            withdrawalPlan.sellOrders.isNotEmpty() shouldBe true

            // Case 3: USD triggers AND crypto triggers -> fiat correction skipped
            val bothTriggerPlan = RebalancerEngine.analyzeDeviationsPlan(
                totalPortfolioValueUSD = BigDecimal("1000.00"),
                currentValuesUSD = mapOf(
                    "BTC" to BigDecimal("700.00"),
                    "ETH" to BigDecimal("200.00"),
                    "USD" to BigDecimal("100.00"),
                ),
                effectiveUsdTarget = BigDecimal("20.00"),
                cryptoScaleFactor = BigDecimal.ONE,
                allocations = allocations,
                settings = settings.copy(deviationTriggerPercent = 5.0),
            )
            bothTriggerPlan.events.filterIsInstance<RebalanceEvent.FiatCorrectionEnforced>().isEmpty() shouldBe true
        }

        "distributeFiatCorrectionPlan emits NoCounterBalancingAssets when totalCounterDev is zero" {
            val events = mutableListOf<RebalanceEvent>()
            RebalancerEngine.distributeFiatCorrectionPlan(
                usdDev = BigDecimal("100.00"),
                allDevs = mapOf(Asset.USD to BigDecimal("100.00"), Asset.BTC to BigDecimal("0.00")),
                buyOrders = mutableMapOf(),
                sellOrders = mutableMapOf(),
                events = events,
            )
            events.filterIsInstance<RebalanceEvent.NoCounterBalancingAssets>().isNotEmpty() shouldBe true
        }

        "MathUtils isWithinRelativeTolerance edge cases" {
            isWithinRelativeTolerance(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal("0.01")) shouldBe true
            isWithinRelativeTolerance(BigDecimal.ZERO, BigDecimal.TEN, BigDecimal("0.01")) shouldBe false
            isWithinRelativeTolerance(BigDecimal("100.00"), BigDecimal("100.50"), BigDecimal("0.01")) shouldBe true
            isWithinRelativeTolerance(BigDecimal("100.00"), BigDecimal("105.00"), BigDecimal("0.01")) shouldBe false
        }
    }
}
