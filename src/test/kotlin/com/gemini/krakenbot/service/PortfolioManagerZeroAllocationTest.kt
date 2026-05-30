package com.gemini.krakenbot.service

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.service.impl.OrderExecutor
import com.gemini.krakenbot.service.impl.PortfolioAnalyzer
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import com.gemini.krakenbot.util.KrakenSymbols
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal

class PortfolioManagerZeroAllocationTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val krakenService = FakeKrakenService()
    private val configService = mockk<ConfigService>(relaxed = true)
    private val tradeHistoryService = mockk<TradeHistoryService>(relaxed = true)
    private val portfolioStatsRepository =
        mockk<PortfolioStatsRepository>(relaxed = true)
    private lateinit var portfolioManager: PortfolioManagerImpl
    private lateinit var portfolioAnalyzer: PortfolioAnalyzer
    private lateinit var orderExecutor: OrderExecutor

    init {
        beforeTest {
            every {
                portfolioStatsRepository.load()
            } returns PortfolioStats(
                BigDecimal.ZERO
            )
            portfolioAnalyzer = PortfolioAnalyzer(
                krakenService,
                configService,
                portfolioStatsRepository
            )
            orderExecutor = OrderExecutor(krakenService, portfolioAnalyzer)
            portfolioManager = PortfolioManagerImpl(
                configService,
                tradeHistoryService,
                portfolioAnalyzer,
                orderExecutor
            )
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
                    kraken = KrakenCredentials("k", "s"),
                    settings = mockSettings,
                    allocations = allAllocations
                )
                every { configService.getConfig() } returns mockConfig

                val balances = mapOf(
                    "A" to 10.0,
                    "B" to 0.0,
                    KrakenSymbols.USD to 100.0
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
