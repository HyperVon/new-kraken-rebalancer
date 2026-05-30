package com.gemini.krakenbot.service

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal

/**
 * Tests for [PortfolioManagerImpl.distributeFiatCorrection].
 *
 * This method is synchronous (no suspend), so no [kotlinx.coroutines.test.runTest] wrapper is needed.
 * We still use [FakeKrakenService] to stay consistent with the rest of the test suite.
 */
class PortfolioManagerFiatCorrectionTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    /** Creates a [PortfolioManagerImpl] wired with a given [AppConfig]. */
    private fun makePortfolioManager(vararg allocs: Allocation): PortfolioManagerImpl {
        val configService = mockk<ConfigService>(relaxed = true)
        val tradeHistoryService = mockk<TradeHistoryService>(relaxed = true)
        val repo = mockk<PortfolioStatsRepository>(relaxed = true)

        val config = AppConfig(
            KrakenCredentials("k", "s"),
            Settings(
                60L,
                2.0,
                1.0,
                false,
                0.0,
                1.0
            ),
            allocs.toList()
        )
        every { configService.getConfig() } returns config

        return PortfolioManagerImpl(
            FakeKrakenService(),
            configService,
            tradeHistoryService,
            repo
        )
    }

    init {
        "testDistributeFiatCorrection_Deposit_OnlyBuysUnderweight" {
            val portfolioManager = makePortfolioManager(
                Allocation("A", 50.0),
                Allocation("B", 50.0)
            )

            val usdDev = BigDecimal.valueOf(100.0)
            // A is overweight (+10), B is underweight (-10) → only B should receive a buy
            val allDevs = mapOf(
                "A" to BigDecimal.valueOf(10.0),
                "B" to BigDecimal.valueOf(-10.0)
            )
            val buyOrders = mutableMapOf<String, BigDecimal>()
            val sellOrders = mutableMapOf<String, BigDecimal>()

            portfolioManager.distributeFiatCorrection(
                usdDev,
                allDevs,
                buyOrders,
                sellOrders,
                mutableListOf()
            )

            buyOrders.containsKey("B").shouldBeTrue()
            buyOrders.getOrDefault("A", BigDecimal.ZERO)
                .compareTo(BigDecimal.ZERO) shouldBe 0
            sellOrders.isEmpty().shouldBeTrue()
        }

        "testDistributeFiatCorrection_Withdrawal_OnlySellsOverweight" {
            val portfolioManager = makePortfolioManager(
                Allocation("A", 50.0),
                Allocation("B", 50.0)
            )

            val usdDev = BigDecimal.valueOf(-100.0)
            // A is overweight (+10), B is underweight (-10) → only A should receive a sell
            val allDevs = mapOf(
                "A" to BigDecimal.valueOf(10.0),
                "B" to BigDecimal.valueOf(-10.0)
            )
            val buyOrders = mutableMapOf<String, BigDecimal>()
            val sellOrders = mutableMapOf<String, BigDecimal>()

            portfolioManager.distributeFiatCorrection(
                usdDev,
                allDevs,
                buyOrders,
                sellOrders,
                mutableListOf()
            )

            sellOrders.containsKey("A").shouldBeTrue()
            sellOrders.getOrDefault("B", BigDecimal.ZERO)
                .compareTo(BigDecimal.ZERO) shouldBe 0
            buyOrders.isEmpty().shouldBeTrue()
        }

        "testDistributeFiatCorrection_ProportionalDistribution" {
            val portfolioManager = makePortfolioManager(
                Allocation("A", 30.0),
                Allocation("B", 30.0),
                Allocation("C", 40.0)
            )

            val usdDev = BigDecimal.valueOf(100.0)
            // A is underweight by 200, B by 50; C is overweight by 50.
            // With a 100 USD deposit, distribution should be proportional to underweight magnitude:
            //   A gets 200/(200+50) * 100 = 80, B gets 50/(200+50) * 100 = 20.
            val allDevs = mapOf(
                "A" to BigDecimal.valueOf(-200.0),
                "B" to BigDecimal.valueOf(-50.0),
                "C" to BigDecimal.valueOf(50.0)
            )
            val buyOrders = mutableMapOf<String, BigDecimal>()
            val sellOrders = mutableMapOf<String, BigDecimal>()

            portfolioManager.distributeFiatCorrection(
                usdDev,
                allDevs,
                buyOrders,
                sellOrders,
                mutableListOf()
            )

            (buyOrders.getOrDefault("A", BigDecimal.ZERO)
                .compareTo(BigDecimal.valueOf(80.0))) shouldBe 0
            (buyOrders.getOrDefault("B", BigDecimal.ZERO)
                .compareTo(BigDecimal.valueOf(20.0))) shouldBe 0
            buyOrders.getOrDefault("C", BigDecimal.ZERO)
                .compareTo(BigDecimal.ZERO) shouldBe 0
        }
    }
}
