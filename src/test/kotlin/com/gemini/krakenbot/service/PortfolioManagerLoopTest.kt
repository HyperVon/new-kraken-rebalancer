package com.gemini.krakenbot.service

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.math.BigDecimal

class PortfolioManagerLoopTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val krakenService = FakeKrakenService()
    private val configService = mockk<ConfigService>(relaxed = true)
    private val tradeHistoryService = mockk<TradeHistoryService>(relaxed = true)
    private lateinit var portfolioManager: PortfolioManagerImpl

    init {
        beforeTest {
            val repo = mockk<PortfolioStatsRepository>(relaxed = true)
            every { repo.load() } returns PortfolioStats(BigDecimal.ZERO)
            portfolioManager = PortfolioManagerImpl(
                krakenService,
                configService,
                tradeHistoryService,
                repo
            )
        }

        "startRebalancingLoop_RunsWhenEnabled" {
            runTest {
                val settings = Settings(60L, 2.0, 1.0, true, 0.0, 1.0)
                val config = AppConfig(
                    KrakenCredentials("k", "s"),
                    settings,
                    emptyList()
                )
                every { configService.getConfig() } returns config
                krakenService.balanceSupplier = { emptyMap() }

                portfolioManager.startRebalancingLoop()
                val job = launch {
                    portfolioManager.runLoop()
                }
                yield()
                portfolioManager.stopRebalancingLoop()
                job.join()

                krakenService.getBalancesCallCount shouldBe 1
            }
        }

        "stopRebalancingLoop_StopsExecution" {
            runTest {
                val settings = Settings(60L, 2.0, 1.0, true, 0.0, 1.0)
                val config = AppConfig(
                    KrakenCredentials("k", "s"),
                    settings,
                    emptyList()
                )
                every { configService.getConfig() } returns config

                portfolioManager.startRebalancingLoop()
                portfolioManager.stopRebalancingLoop()

                portfolioManager.runLoop()

                krakenService.getBalancesCallCount shouldBe 0
            }
        }

        "checkAndRunCycle_HandlesExceptionGracefully" {
            runTest {
                val settings = Settings(60L, 2.0, 1.0, true, 0.0, 1.0)
                val config = AppConfig(
                    KrakenCredentials("k", "s"),
                    settings,
                    emptyList()
                )
                every { configService.getConfig() } returns config

                krakenService.balanceSupplier =
                    { throw RuntimeException("API Error!") }

                portfolioManager.startRebalancingLoop()
                val job = launch {
                    portfolioManager.runLoop()
                }
                yield()
                portfolioManager.stopRebalancingLoop()
                job.join()

                krakenService.getBalancesCallCount shouldBe 1
            }
        }
    }
}
