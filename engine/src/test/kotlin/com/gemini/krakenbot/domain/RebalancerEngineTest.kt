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

        "adjustAthForCashFlow returns current ATH when ATH or pre-flow value is non-positive or net flow is zero" {
            val ath = BigDecimal("10000.00")
            val preFlow = BigDecimal("8000.00")

            RebalancerEngine.adjustAthForCashFlow(BigDecimal.ZERO, preFlow, BigDecimal("1000.00"))
                .shouldBeEqualComparingTo(BigDecimal.ZERO)
            RebalancerEngine.adjustAthForCashFlow(BigDecimal("-100.00"), preFlow, BigDecimal("1000.00"))
                .shouldBeEqualComparingTo(BigDecimal("-100.00"))
            RebalancerEngine.adjustAthForCashFlow(ath, BigDecimal.ZERO, BigDecimal("1000.00"))
                .shouldBeEqualComparingTo(ath)
            RebalancerEngine.adjustAthForCashFlow(ath, BigDecimal("-500.00"), BigDecimal("1000.00"))
                .shouldBeEqualComparingTo(ath)
            RebalancerEngine.adjustAthForCashFlow(ath, preFlow, BigDecimal.ZERO)
                .shouldBeEqualComparingTo(ath)
        }

        "adjustAthForCashFlow returns ZERO when net withdrawal equals or exceeds pre-flow value" {
            val ath = BigDecimal("10000.00")
            val preFlow = BigDecimal("5000.00")

            RebalancerEngine.adjustAthForCashFlow(ath, preFlow, BigDecimal("-5000.00"))
                .shouldBeEqualComparingTo(BigDecimal.ZERO)
            RebalancerEngine.adjustAthForCashFlow(ath, preFlow, BigDecimal("-6000.00"))
                .shouldBeEqualComparingTo(BigDecimal.ZERO)
        }

        "adjustAthForCashFlow proportionally scales ATH for deposits and preserves drawdown ratio" {
            // Portfolio value: 8,000, ATH: 10,000 (20% drawdown).
            // Deposit: 2,000 -> postFlow = 10,000.
            // Factor = 10,000 / 8,000 = 1.25. New ATH = 10,000 * 1.25 = 12,500.
            // New drawdown = (12,500 - 10,000) / 12,500 = 20%. Drawdown is preserved!
            val ath = BigDecimal("10000.00")
            val preFlow = BigDecimal("8000.00")
            val deposit = BigDecimal("2000.00")

            val adjustedAth = RebalancerEngine.adjustAthForCashFlow(ath, preFlow, deposit)
            adjustedAth.shouldBeEqualComparingTo(BigDecimal("12500.00"))

            val newDd = RebalancerEngine.calculateDrawdown(preFlow.add(deposit), adjustedAth)
            newDd.shouldBeEqualComparingTo(BigDecimal("20.00"))
        }

        "adjustAthForCashFlow proportionally scales ATH for withdrawals and preserves drawdown ratio" {
            // Portfolio value: 8,000, ATH: 10,000 (20% drawdown).
            // Withdrawal: -2,000 -> postFlow = 6,000.
            // Factor = 6,000 / 8,000 = 0.75. New ATH = 10,000 * 0.75 = 7,500.
            // New drawdown = (7,500 - 6,000) / 7,500 = 20%. Drawdown is preserved!
            val ath = BigDecimal("10000.00")
            val preFlow = BigDecimal("8000.00")
            val withdrawal = BigDecimal("-2000.00")

            val adjustedAth = RebalancerEngine.adjustAthForCashFlow(ath, preFlow, withdrawal)
            adjustedAth.shouldBeEqualComparingTo(BigDecimal("7500.00"))

            val newDd = RebalancerEngine.calculateDrawdown(preFlow.add(withdrawal), adjustedAth)
            newDd.shouldBeEqualComparingTo(BigDecimal("20.00"))
        }

        "calculateFiatDeployment respects deployment threshold deadband" {
            val withThreshold = settings.copy(
                fiatMaxDrawdown = 20.0,
                fiatDeploymentExponent = 1.0,
                fiatDeploymentThresholdPercent = 5.0,
            )

            // Drawdown at or below threshold produces 0 deployment
            RebalancerEngine.calculateFiatDeployment(BigDecimal("3.00"), withThreshold)
                .shouldBeEqualComparingTo(BigDecimal.ZERO)
            RebalancerEngine.calculateFiatDeployment(BigDecimal("5.00"), withThreshold)
                .shouldBeEqualComparingTo(BigDecimal.ZERO)

            // Drawdown between threshold and maxDrawdown scales linearly from threshold to maxDD:
            // effectiveDD = 12.5 - 5 = 7.5; effectiveMaxDD = 20 - 5 = 15; ratio = 7.5 / 15 = 0.5 -> 50%
            RebalancerEngine.calculateFiatDeployment(BigDecimal("12.50"), withThreshold)
                .shouldBeEqualComparingTo(BigDecimal("50.00"))

            // When maxDrawdown is less than or equal to threshold, any drawdown exceeding threshold deploys 100%
            val clampedThreshold = settings.copy(
                fiatMaxDrawdown = 5.0,
                fiatDeploymentThresholdPercent = 5.0,
            )
            RebalancerEngine.calculateFiatDeployment(BigDecimal("6.00"), clampedThreshold)
                .shouldBeEqualComparingTo(BigDecimal("100.00"))
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

            // Zero drawdown with negative threshold must still return ZERO
            RebalancerEngine.calculateFiatDeployment(
                BigDecimal.ZERO,
                settings.copy(fiatDeploymentThresholdPercent = -5.0),
            ).shouldBeEqualComparingTo(BigDecimal.ZERO)

            // Non-finite threshold must return ZERO safely without throwing NumberFormatException
            RebalancerEngine.calculateFiatDeployment(
                BigDecimal("10.00"),
                settings.copy(fiatDeploymentThresholdPercent = Double.NaN),
            ).shouldBeEqualComparingTo(BigDecimal.ZERO)
            RebalancerEngine.calculateFiatDeployment(
                BigDecimal("10.00"),
                settings.copy(fiatDeploymentThresholdPercent = Double.POSITIVE_INFINITY),
            ).shouldBeEqualComparingTo(BigDecimal.ZERO)

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

        "calculateFiatDeployment preserves intermediate ratio precision without premature truncation" {
            // 10 / 30 = 1/3 = 0.33333333... With linear exponent, Deploy% = 33.3333%
            val thirdSettings = settings.copy(
                fiatMaxDrawdown = 30.0,
                fiatDeploymentExponent = 1.0,
                fiatDeploymentThresholdPercent = 0.0,
            )
            val deployment = RebalancerEngine.calculateFiatDeployment(BigDecimal("10.00"), thirdSettings)
            deployment.shouldBeEqualComparingTo(BigDecimal("33.3333"))
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
            withdrawalPlan.sellOrders.getValue("BTC").shouldBeEqualComparingTo(BigDecimal("50.00"))
            withdrawalPlan.sellOrders.getValue("ETH").shouldBeEqualComparingTo(BigDecimal("50.00"))
            withdrawalPlan.buyOrders.shouldBeEmpty()

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
