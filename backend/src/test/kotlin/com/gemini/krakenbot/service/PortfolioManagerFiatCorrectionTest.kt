package com.gemini.krakenbot.service

import com.gemini.krakenbot.domain.RebalancerEngine
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class PortfolioManagerFiatCorrectionTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "testDistributeFiatCorrection_Deposit_OnlyBuysUnderweight" {
            val usdDev = BigDecimal.valueOf(100.0)
            // A is overweight (+10), B is underweight (-10) → only B should receive a buy
            val allDevs = mapOf(
                "A" to BigDecimal.valueOf(10.0),
                "B" to BigDecimal.valueOf(-10.0),
            )
            val buyOrders = mutableMapOf<String, BigDecimal>()
            val sellOrders = mutableMapOf<String, BigDecimal>()

            RebalancerEngine.distributeFiatCorrectionPlan(
                usdDev = usdDev,
                allDevs = allDevs,
                buyOrders = buyOrders,
                sellOrders = sellOrders,
                events = mutableListOf(),
            )

            buyOrders.containsKey("B").shouldBeTrue()
            buyOrders.getOrDefault("A", BigDecimal.ZERO).shouldBeEqualComparingTo(BigDecimal.ZERO)
            sellOrders.isEmpty().shouldBeTrue()
        }

        "testDistributeFiatCorrection_Withdrawal_OnlySellsOverweight" {
            val usdDev = BigDecimal.valueOf(-100.0)
            // A is overweight (+10), B is underweight (-10) → only A should receive a sell
            val allDevs = mapOf(
                "A" to BigDecimal.valueOf(10.0),
                "B" to BigDecimal.valueOf(-10.0),
            )
            val buyOrders = mutableMapOf<String, BigDecimal>()
            val sellOrders = mutableMapOf<String, BigDecimal>()

            RebalancerEngine.distributeFiatCorrectionPlan(
                usdDev = usdDev,
                allDevs = allDevs,
                buyOrders = buyOrders,
                sellOrders = sellOrders,
                events = mutableListOf(),
            )

            sellOrders.containsKey("A").shouldBeTrue()
            sellOrders.getOrDefault("B", BigDecimal.ZERO).shouldBeEqualComparingTo(BigDecimal.ZERO)
            buyOrders.isEmpty().shouldBeTrue()
        }

        "testDistributeFiatCorrection_ProportionalDistribution" {
            val usdDev = BigDecimal.valueOf(100.0)
            // A is underweight by 200, B by 50; C is overweight by 50.
            // With a 100 USD deposit, distribution should be proportional to underweight magnitude:
            //   A gets 200/(200+50) * 100 = 80, B gets 50/(200+50) * 100 = 20.
            val allDevs = mapOf(
                "A" to BigDecimal.valueOf(-200.0),
                "B" to BigDecimal.valueOf(-50.0),
                "C" to BigDecimal.valueOf(50.0),
            )
            val buyOrders = mutableMapOf<String, BigDecimal>()
            val sellOrders = mutableMapOf<String, BigDecimal>()

            RebalancerEngine.distributeFiatCorrectionPlan(
                usdDev = usdDev,
                allDevs = allDevs,
                buyOrders = buyOrders,
                sellOrders = sellOrders,
                events = mutableListOf(),
            )

            buyOrders.getOrDefault("A", BigDecimal.ZERO).shouldBeEqualComparingTo(BigDecimal.valueOf(80.0))
            buyOrders.getOrDefault("B", BigDecimal.ZERO).shouldBeEqualComparingTo(BigDecimal.valueOf(20.0))
            buyOrders.getOrDefault("C", BigDecimal.ZERO).shouldBeEqualComparingTo(BigDecimal.ZERO)
        }

        "testDistributeFiatCorrection_ShareUsesUsdScale" {
            // Uneven counter-devs produce a many-decimal ratio; share must round to USD scale 2.
            val usdDev = BigDecimal("100.00")
            val allDevs = mapOf(
                "A" to BigDecimal("-70.00"),
                "B" to BigDecimal("-30.00"),
            )
            val buyOrders = mutableMapOf<String, BigDecimal>()

            RebalancerEngine.distributeFiatCorrectionPlan(
                usdDev = usdDev,
                allDevs = allDevs,
                buyOrders = buyOrders,
                sellOrders = mutableMapOf(),
                events = mutableListOf(),
            )

            buyOrders.getValue("A").scale() shouldBe 2
            buyOrders.getValue("B").scale() shouldBe 2
            buyOrders.getValue("A").shouldBeEqualComparingTo(BigDecimal("70.00"))
            buyOrders.getValue("B").shouldBeEqualComparingTo(BigDecimal("30.00"))
        }

        // CQ-3-26 / #76: a $0.00 rounded share must never be enqueued as an order.
        "testDistributeFiatCorrection_ZeroRoundedShareNotEnqueued_Deposit" {
            // TINY ratio = 0.001/100 → share = $1.00 * 0.00001 = $0.00001 → $0.00 at USD scale.
            val usdDev = BigDecimal("1.00")
            val allDevs = mapOf(
                "TINY" to BigDecimal("-0.001"),
                "BIG" to BigDecimal("-99.999"),
            )
            val buyOrders = mutableMapOf<String, BigDecimal>()
            val sellOrders = mutableMapOf<String, BigDecimal>()

            RebalancerEngine.distributeFiatCorrectionPlan(
                usdDev = usdDev,
                allDevs = allDevs,
                buyOrders = buyOrders,
                sellOrders = sellOrders,
                events = mutableListOf(),
            )

            buyOrders.containsKey("TINY") shouldBe false
            buyOrders.values.none { it.signum() == 0 }.shouldBeTrue()
            sellOrders.isEmpty().shouldBeTrue()
            (
                buyOrders.values
                    .fold(BigDecimal.ZERO, BigDecimal::add) <= usdDev.abs()
                ).shouldBeTrue()
        }

        // CQ-3-26 / #76: same zero-share filter on withdrawal sells.
        "testDistributeFiatCorrection_ZeroRoundedShareNotEnqueued_Withdrawal" {
            val usdDev = BigDecimal("-1.00")
            val allDevs = mapOf(
                "TINY" to BigDecimal("0.001"),
                "BIG" to BigDecimal("99.999"),
            )
            val buyOrders = mutableMapOf<String, BigDecimal>()
            val sellOrders = mutableMapOf<String, BigDecimal>()

            RebalancerEngine.distributeFiatCorrectionPlan(
                usdDev = usdDev,
                allDevs = allDevs,
                buyOrders = buyOrders,
                sellOrders = sellOrders,
                events = mutableListOf(),
            )

            sellOrders.containsKey("TINY") shouldBe false
            sellOrders.values.none { it.signum() == 0 }.shouldBeTrue()
            buyOrders.isEmpty().shouldBeTrue()
            (
                sellOrders.values.fold(BigDecimal.ZERO, BigDecimal::add) <= usdDev.abs()
                ).shouldBeTrue()
        }

        // CQ-3-26 / #76: HALF_UP USD rounding must not let shares sum exceed |usdDev|.
        "testDistributeFiatCorrection_RoundedSharesDoNotExceedUsdDev" {
            // Equal ratios: each share = $0.05 * 0.5 = $0.025 → $0.03 after USD scale;
            // naive enqueue would sum to $0.06 > $0.05.
            val usdDev = BigDecimal("0.05")
            val allDevs = mapOf(
                "A" to BigDecimal("-1.00"),
                "B" to BigDecimal("-1.00"),
            )
            val buyOrders = mutableMapOf<String, BigDecimal>()

            RebalancerEngine.distributeFiatCorrectionPlan(
                usdDev = usdDev,
                allDevs = allDevs,
                buyOrders = buyOrders,
                sellOrders = mutableMapOf(),
                events = mutableListOf(),
            )

            buyOrders.values.none { it.signum() == 0 }.shouldBeTrue()
            (
                buyOrders.values
                    .fold(BigDecimal.ZERO, BigDecimal::add) <= usdDev.abs()
                ).shouldBeTrue()
        }
    }
}
