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
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal

class PortfolioManagerFiatCorrectionTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    /** Creates a [PortfolioAnalyzerImpl] configured with the supplied allocations. */
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
            buyOrders.getOrDefault("A", BigDecimal.ZERO)
                .compareTo(BigDecimal.ZERO) shouldBe 0
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
            sellOrders.getOrDefault("B", BigDecimal.ZERO)
                .compareTo(BigDecimal.ZERO) shouldBe 0
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

            buyOrders.getOrDefault("A", BigDecimal.ZERO)
                .compareTo(BigDecimal.valueOf(80.0)) shouldBe 0
            buyOrders.getOrDefault("B", BigDecimal.ZERO)
                .compareTo(BigDecimal.valueOf(20.0)) shouldBe 0
            buyOrders.getOrDefault("C", BigDecimal.ZERO)
                .compareTo(BigDecimal.ZERO) shouldBe 0
        }
    }
}
