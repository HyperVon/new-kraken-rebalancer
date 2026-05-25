package com.gemini.krakenbot.service

import io.kotest.core.spec.style.StringSpec
import io.mockk.every
import io.mockk.mockk
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal

class PortfolioManagerZeroAllocationTest : StringSpec() {

    override fun isolationMode() = io.kotest.core.spec.IsolationMode.InstancePerTest

    private val krakenService = FakeKrakenService()
    private val configService = mockk<ConfigService>(relaxed = true)
    private val tradeHistoryService = mockk<TradeHistoryService>(relaxed = true)
    private val portfolioStatsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
    private lateinit var portfolioManager: PortfolioManagerImpl

    init {
        beforeTest {
            every { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal.ZERO)
            portfolioManager = PortfolioManagerImpl(krakenService, configService, tradeHistoryService, portfolioStatsRepository)
        }

        "testZeroAllocationToOtherAssetRebalance" {
            runTest {
                val allocA = Allocation("A", 0.0)
                val allocB = Allocation("B", 100.0)
                val allAllocations = listOf(allocA, allocB)

                val mockSettings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = false
                )
                val mockConfig = AppConfig(
                    kraken = com.gemini.krakenbot.config.KrakenCredentials("k", "s"),
                    settings = mockSettings,
                    allocations = allAllocations
                )
                every { configService.getConfig() } returns mockConfig

                val balances = mapOf(
                    "A" to 10.0,
                    "B" to 0.0,
                    "USD" to 100.0
                )
                krakenService.balanceSupplier = { balances }

                val prices = mapOf(
                    "AUSD" to 100.0,
                    "BUSD" to 50.0
                )
                krakenService.pricesSupplier = { prices }

                portfolioManager.performRebalanceCycle()

                krakenService.executedOrders.any { 
                    it.pair == "AUSD" && it.type == "market" && it.side == "sell"
                } shouldBe true
            }
        }
    }
}
