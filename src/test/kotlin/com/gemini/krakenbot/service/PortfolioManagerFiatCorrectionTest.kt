package com.gemini.krakenbot.service

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.service.impl.PortfolioAnalyzerImpl
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal

class PortfolioManagerFiatCorrectionTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private fun makePortfolioAnalyzer(vararg allocs: Allocation): PortfolioAnalyzer {
        val configService = mockk<ConfigService>(relaxed = true)
        val repo = mockk<PortfolioStatsRepository>(relaxed = true)

        val config = AppConfig(
            kraken = KrakenCredentials(apiKey = "k", privateKey = "s"),
            settings = Settings(
                loopDelaySeconds = 60L,
                deviationTriggerPercent = 2.0,
                dustThresholdUSD = 1.0,
                dryRun = false,
                fiatMaxDrawdown = 0.0,
                fiatDeploymentExponent = 1.0,
            ),
            allocations = allocs.toList(),
        )
        every { configService.getConfig() } returns config

        return PortfolioAnalyzerImpl(
            krakenService = FakeKrakenService(),
            configService = configService,
            portfolioStatsRepository = repo,
        )
    }

    init {
        "testDistributeFiatCorrection_Deposit_OnlyBuysUnderweight" {
            val portfolioAnalyzer = makePortfolioAnalyzer(
                Allocation("A", 50.0),
                Allocation("B", 50.0),
            )

            val usdDev = BigDecimal.valueOf(100.0)
            // A is overweight (+10), B is underweight (-10) → only B should receive a buy
            val allDevs = mapOf(
                "A" to BigDecimal.valueOf(10.0),
                "B" to BigDecimal.valueOf(-10.0),
            )
            val buyOrders = mutableMapOf<String, BigDecimal>()
            val sellOrders = mutableMapOf<String, BigDecimal>()

            portfolioAnalyzer.distributeFiatCorrection(
                usdDev = usdDev,
                allDevs = allDevs,
                buyOrders = buyOrders,
                sellOrders = sellOrders,
                actionLog = mutableListOf(),
            )

            buyOrders.containsKey("B").shouldBeTrue()
            buyOrders.getOrDefault("A", BigDecimal.ZERO).shouldBeEqualComparingTo(BigDecimal.ZERO)
            sellOrders.isEmpty().shouldBeTrue()
        }

        "testDistributeFiatCorrection_Withdrawal_OnlySellsOverweight" {
            val portfolioAnalyzer = makePortfolioAnalyzer(
                Allocation("A", 50.0),
                Allocation("B", 50.0),
            )

            val usdDev = BigDecimal.valueOf(-100.0)
            // A is overweight (+10), B is underweight (-10) → only A should receive a sell
            val allDevs = mapOf(
                "A" to BigDecimal.valueOf(10.0),
                "B" to BigDecimal.valueOf(-10.0),
            )
            val buyOrders = mutableMapOf<String, BigDecimal>()
            val sellOrders = mutableMapOf<String, BigDecimal>()

            portfolioAnalyzer.distributeFiatCorrection(
                usdDev = usdDev,
                allDevs = allDevs,
                buyOrders = buyOrders,
                sellOrders = sellOrders,
                actionLog = mutableListOf(),
            )

            sellOrders.containsKey("A").shouldBeTrue()
            sellOrders.getOrDefault("B", BigDecimal.ZERO).shouldBeEqualComparingTo(BigDecimal.ZERO)
            buyOrders.isEmpty().shouldBeTrue()
        }

        "testDistributeFiatCorrection_ProportionalDistribution" {
            val portfolioAnalyzer = makePortfolioAnalyzer(
                Allocation("A", 30.0),
                Allocation("B", 30.0),
                Allocation("C", 40.0),
            )

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

            portfolioAnalyzer.distributeFiatCorrection(
                usdDev = usdDev,
                allDevs = allDevs,
                buyOrders = buyOrders,
                sellOrders = sellOrders,
                actionLog = mutableListOf(),
            )

            buyOrders.getOrDefault("A", BigDecimal.ZERO).shouldBeEqualComparingTo(BigDecimal.valueOf(80.0))
            buyOrders.getOrDefault("B", BigDecimal.ZERO).shouldBeEqualComparingTo(BigDecimal.valueOf(20.0))
            buyOrders.getOrDefault("C", BigDecimal.ZERO).shouldBeEqualComparingTo(BigDecimal.ZERO)
        }

        "testDistributeFiatCorrection_ShareUsesUsdScale" {
            val portfolioAnalyzer = makePortfolioAnalyzer(
                Allocation("A", 50.0),
                Allocation("B", 50.0),
            )

            // Uneven counter-devs produce a many-decimal ratio; share must round to USD scale 2.
            val usdDev = BigDecimal("100.00")
            val allDevs = mapOf(
                "A" to BigDecimal("-70.00"),
                "B" to BigDecimal("-30.00"),
            )
            val buyOrders = mutableMapOf<String, BigDecimal>()

            portfolioAnalyzer.distributeFiatCorrection(
                usdDev = usdDev,
                allDevs = allDevs,
                buyOrders = buyOrders,
                sellOrders = mutableMapOf(),
                actionLog = mutableListOf(),
            )

            buyOrders.getValue("A").scale() shouldBe 2
            buyOrders.getValue("B").scale() shouldBe 2
            buyOrders.getValue("A").shouldBeEqualComparingTo(BigDecimal("70.00"))
            buyOrders.getValue("B").shouldBeEqualComparingTo(BigDecimal("30.00"))
        }

        // CQ-3-26 / #76: a $0.00 rounded share must never be enqueued as an order.
        "testDistributeFiatCorrection_ZeroRoundedShareNotEnqueued_Deposit" {
            val portfolioAnalyzer = makePortfolioAnalyzer(
                Allocation("TINY", 1.0),
                Allocation("BIG", 99.0),
            )

            // TINY ratio = 0.001/100 → share = $1.00 * 0.00001 = $0.00001 → $0.00 at USD scale.
            val usdDev = BigDecimal("1.00")
            val allDevs = mapOf(
                "TINY" to BigDecimal("-0.001"),
                "BIG" to BigDecimal("-99.999"),
            )
            val buyOrders = mutableMapOf<String, BigDecimal>()
            val sellOrders = mutableMapOf<String, BigDecimal>()

            portfolioAnalyzer.distributeFiatCorrection(
                usdDev = usdDev,
                allDevs = allDevs,
                buyOrders = buyOrders,
                sellOrders = sellOrders,
                actionLog = mutableListOf(),
            )

            buyOrders.containsKey("TINY") shouldBe false
            buyOrders.values.none { it.signum() == 0 }.shouldBeTrue()
            sellOrders.isEmpty().shouldBeTrue()
            (
                buyOrders.values.fold(BigDecimal.ZERO, BigDecimal::add)
                    .compareTo(usdDev.abs()) <= 0
                ).shouldBeTrue()
        }

        // CQ-3-26 / #76: same zero-share filter on withdrawal sells.
        "testDistributeFiatCorrection_ZeroRoundedShareNotEnqueued_Withdrawal" {
            val portfolioAnalyzer = makePortfolioAnalyzer(
                Allocation("TINY", 1.0),
                Allocation("BIG", 99.0),
            )

            val usdDev = BigDecimal("-1.00")
            val allDevs = mapOf(
                "TINY" to BigDecimal("0.001"),
                "BIG" to BigDecimal("99.999"),
            )
            val buyOrders = mutableMapOf<String, BigDecimal>()
            val sellOrders = mutableMapOf<String, BigDecimal>()

            portfolioAnalyzer.distributeFiatCorrection(
                usdDev = usdDev,
                allDevs = allDevs,
                buyOrders = buyOrders,
                sellOrders = sellOrders,
                actionLog = mutableListOf(),
            )

            sellOrders.containsKey("TINY") shouldBe false
            sellOrders.values.none { it.signum() == 0 }.shouldBeTrue()
            buyOrders.isEmpty().shouldBeTrue()
            (
                sellOrders.values.fold(BigDecimal.ZERO, BigDecimal::add)
                    .compareTo(usdDev.abs()) <= 0
                ).shouldBeTrue()
        }

        // CQ-3-26 / #76: HALF_UP USD rounding must not let shares sum exceed |usdDev|.
        "testDistributeFiatCorrection_RoundedSharesDoNotExceedUsdDev" {
            val portfolioAnalyzer = makePortfolioAnalyzer(
                Allocation("A", 50.0),
                Allocation("B", 50.0),
            )

            // Equal ratios: each share = $0.05 * 0.5 = $0.025 → $0.03 after USD scale;
            // naive enqueue would sum to $0.06 > $0.05.
            val usdDev = BigDecimal("0.05")
            val allDevs = mapOf(
                "A" to BigDecimal("-1.00"),
                "B" to BigDecimal("-1.00"),
            )
            val buyOrders = mutableMapOf<String, BigDecimal>()

            portfolioAnalyzer.distributeFiatCorrection(
                usdDev = usdDev,
                allDevs = allDevs,
                buyOrders = buyOrders,
                sellOrders = mutableMapOf(),
                actionLog = mutableListOf(),
            )

            buyOrders.values.none { it.signum() == 0 }.shouldBeTrue()
            (
                buyOrders.values.fold(BigDecimal.ZERO, BigDecimal::add)
                    .compareTo(usdDev.abs()) <= 0
                ).shouldBeTrue()
        }
    }
}
