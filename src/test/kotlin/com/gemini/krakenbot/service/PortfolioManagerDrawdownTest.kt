package com.gemini.krakenbot.service

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.PortfolioAnalyzerImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal

@Suppress("unused")
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
            portfolioAnalyzer = PortfolioAnalyzerImpl(
                krakenService = krakenService,
                configService = configService,
                portfolioStatsRepository = portfolioStatsRepository,
            )
            orderExecutor = OrderExecutorImpl(krakenService, portfolioAnalyzer, tradeHistoryService)
            portfolioManager = PortfolioManagerImpl(
                configService = configService,
                tradeHistoryService = tradeHistoryService,
                portfolioAnalyzer = portfolioAnalyzer,
                orderExecutor = orderExecutor,
            )

            val settings = Settings(
                loopDelaySeconds = 60L,
                deviationTriggerPercent = 2.0,
                dustThresholdUSD = 1.0,
                dryRun = false,
                fiatMaxDrawdown = 50.0,
                fiatDeploymentExponent = 1.0,
            )
            val appConfig =
                AppConfig(
                    kraken = KrakenCredentials(
                        apiKey = "k",
                        privateKey = "s",
                    ),
                    settings = settings,
                    allocations = emptyList(),
                )
            every { configService.getConfig() } returns appConfig
        }

        "testDrawdownAndFiatDeployment" {
            runTest {
                every {
                    portfolioStatsRepository.load()
                } returns PortfolioStats(
                    BigDecimal("2000.0"),
                )

                val allocs = listOf(
                    Allocation("A", 50.0),
                    Allocation(Asset.USD, 50.0),
                )

                val appConfig = AppConfig(
                    kraken = KrakenCredentials("k", "s"),
                    settings = Settings(
                        loopDelaySeconds = 60L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 50.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    allocations = allocs,
                )
                every { configService.getConfig() } returns appConfig

                val prices = mapOf("AUSD" to 100.0)
                krakenService.pricesSupplier = { prices }

                val balances = mapOf(
                    "A" to 7.5,
                    Asset.USD to 750.0,
                )
                krakenService.balanceSupplier = { balances }

                portfolioManager.performRebalanceCycle()

                krakenService.executedOrders.size shouldBe 1
                val order = krakenService.executedOrders[0]
                order.pair shouldBe "AUSD"
                order.type shouldBe "market"
                order.side shouldBe "buy"
                (
                    order.volume.subtract(BigDecimal.valueOf(3.75))
                        .abs() < BigDecimal("0.01")
                    ).shouldBeTrue()

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

                val allocs = listOf(
                    Allocation(
                        symbol = Asset.USD,
                        targetPercent = 100.0,
                    ),
                )

                val appConfig = AppConfig(
                    kraken = KrakenCredentials("k", "s"),
                    settings = Settings(
                        loopDelaySeconds = 60L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 50.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    allocations = allocs,
                )
                every { configService.getConfig() } returns appConfig
                krakenService.pricesSupplier = { emptyMap() }

                val balances = mapOf(Asset.USD to 1500.0)
                krakenService.balanceSupplier = { balances }

                portfolioManager.performRebalanceCycle()

                val captor = slot<PortfolioStats>()
                verify { portfolioStatsRepository.save(capture(captor)) }
                captor.captured.allTimeHigh.shouldNotBeNull()
                BigDecimal("1500.0").compareTo(captor.captured.allTimeHigh) shouldBe 0
            }
        }
    }
}
