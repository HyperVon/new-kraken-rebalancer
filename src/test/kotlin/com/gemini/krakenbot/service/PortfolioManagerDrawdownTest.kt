package com.gemini.krakenbot.service

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.service.impl.OrderExecutor
import com.gemini.krakenbot.service.impl.PortfolioAnalyzer
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import com.gemini.krakenbot.util.KrakenSymbols
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal

class PortfolioManagerDrawdownTest : StringSpec() {

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

            val settings = Settings(
                60L,
                2.0,
                1.0,
                false,
                50.0,
                1.0
            )
            val appConfig =
                AppConfig(
                    KrakenCredentials(
                        "k",
                        "s"
                    ),
                    settings,
                    emptyList()
                )
            every { configService.getConfig() } returns appConfig
        }

        "testDrawdownAndFiatDeployment" {
            runTest {
                every {
                    portfolioStatsRepository.load()
                } returns PortfolioStats(
                    BigDecimal("2000.0")
                )

                val allocs = listOf(
                    Allocation("A", 50.0),
                    Allocation(KrakenSymbols.USD, 50.0)
                )

                val appConfig = AppConfig(
                    KrakenCredentials("k", "s"),
                    Settings(
                        60L,
                        2.0,
                        1.0,
                        false,
                        50.0,
                        1.0
                    ),
                    allocs
                )
                every { configService.getConfig() } returns appConfig

                val prices = mapOf("AUSD" to 100.0)
                krakenService.pricesSupplier = { prices }

                val balances = mapOf(
                    "A" to 7.5,
                    KrakenSymbols.USD to 750.0
                )
                krakenService.balanceSupplier = { balances }

                portfolioManager.performRebalanceCycle()

                krakenService.executedOrders.size shouldBe 1
                val order = krakenService.executedOrders[0]
                order.pair shouldBe "AUSD"
                order.type shouldBe "market"
                order.side shouldBe "buy"
                (order.volume.subtract(BigDecimal.valueOf(3.75))
                    .abs() < BigDecimal("0.01")).shouldBeTrue()

                val captor = slot<PortfolioSnapshot>()
                verify { tradeHistoryService.addSnapshot(capture(captor)) }
                val s = captor.captured

                s.drawdownPercent.compareTo(BigDecimal("25.0")) shouldBe 0
                s.fiatDeploymentPercent.compareTo(BigDecimal("50.0")) shouldBe 0
                s.effectiveUsdTargetPercent.compareTo(BigDecimal("25.0")) shouldBe 0
            }
        }

        "testNewATH" {
            runTest {
                val stats = PortfolioStats(BigDecimal("1000.0"))
                every { portfolioStatsRepository.load() } returns stats

                val allocs = listOf(Allocation(
                    KrakenSymbols.USD,
                    100.0
                ))

                val appConfig = AppConfig(
                    KrakenCredentials("k", "s"),
                    Settings(
                        60L,
                        2.0,
                        1.0,
                        false,
                        50.0,
                        1.0
                    ),
                    allocs
                )
                every { configService.getConfig() } returns appConfig
                krakenService.pricesSupplier = { emptyMap() }

                val balances = mapOf(KrakenSymbols.USD to 1500.0)
                krakenService.balanceSupplier = { balances }

                portfolioManager.performRebalanceCycle()

                verify { portfolioStatsRepository.save(stats) }
                (BigDecimal("1500.0").compareTo(stats.allTimeHigh)) shouldBe 0
            }
        }
    }
}
