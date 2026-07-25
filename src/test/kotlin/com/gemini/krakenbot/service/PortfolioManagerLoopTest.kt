@file:OptIn(ExperimentalCoroutinesApi::class)

package com.gemini.krakenbot.service

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.PortfolioAnalyzerImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.math.BigDecimal
import kotlin.time.Duration.Companion.milliseconds

class PortfolioManagerLoopTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val krakenService = FakeKrakenService()
    private val configService = mockk<ConfigService>(relaxed = true)
    private val tradeHistoryService = mockk<TradeHistoryService>(relaxed = true)
    private lateinit var portfolioManager: PortfolioManagerImpl
    private lateinit var portfolioAnalyzer: PortfolioAnalyzer
    private lateinit var orderExecutor: OrderExecutor

    init {
        beforeTest {
            val repo = mockk<PortfolioStatsRepository>(relaxed = true)
            coEvery {
                repo.load()
            } returns PortfolioStats(BigDecimal.ZERO)
            portfolioAnalyzer =
                PortfolioAnalyzerImpl(
                    krakenService = krakenService,
                    configService = configService,
                    portfolioStatsRepository = repo,
                )
            orderExecutor = OrderExecutorImpl(krakenService, portfolioAnalyzer, tradeHistoryService)
            portfolioManager = PortfolioManagerImpl(
                configService = configService,
                tradeHistoryService = tradeHistoryService,
                portfolioAnalyzer = portfolioAnalyzer,
                orderExecutor = orderExecutor,
            )
            every { configService.watchConfigChanges() } answers {
                flowOf(configService.getConfig().settings)
            }
        }

        "startRebalancingLoop_RunsWhenEnabled" {
            runTest {
                val settings = Settings(
                    loopDelaySeconds = 60L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
                val config = AppConfig(
                    kraken = KrakenCredentials(apiKey = "k", privateKey = "s"),
                    settings = settings,
                    allocations = emptyList(),
                )
                every { configService.getConfig() } returns config
                krakenService.balanceSupplier = { emptyMap() }

                portfolioManager.startRebalancingLoop()
                val job = launch {
                    portfolioManager.runLoop()
                }
                delay(10.milliseconds)
                portfolioManager.stopRebalancingLoop()
                job.cancel()

                krakenService.getBalancesCallCount shouldBe 1
            }
        }

        "stopRebalancingLoop_StopsExecution" {
            runTest {
                val settings = Settings(
                    loopDelaySeconds = 60L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
                val config = AppConfig(
                    kraken = KrakenCredentials(apiKey = "k", privateKey = "s"),
                    settings = settings,
                    allocations = emptyList(),
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
                val settings = Settings(
                    loopDelaySeconds = 60L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
                val config = AppConfig(
                    kraken = KrakenCredentials(apiKey = "k", privateKey = "s"),
                    settings = settings,
                    allocations = emptyList(),
                )
                every { configService.getConfig() } returns config

                krakenService.balanceSupplier =
                    { throw RuntimeException("API Error!") }

                portfolioManager.startRebalancingLoop()
                val job = launch {
                    portfolioManager.runLoop()
                }
                delay(10.milliseconds)
                portfolioManager.stopRebalancingLoop()
                job.cancel()

                krakenService.getBalancesCallCount shouldBe 1
            }
        }

        "runLoop_HandlesSyncTradesExceptionGracefully" {
            runTest {
                val settings = Settings(
                    loopDelaySeconds = 60L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
                val config = AppConfig(
                    kraken = KrakenCredentials(apiKey = "k", privateKey = "s"),
                    settings = settings,
                    allocations = emptyList(),
                )
                every { configService.getConfig() } returns config

                coEvery { tradeHistoryService.syncTradesFromKraken() } throws RuntimeException("Sync error!")

                portfolioManager.startRebalancingLoop()
                val job = launch {
                    portfolioManager.runLoop()
                }
                yield()
                portfolioManager.stopRebalancingLoop()
                job.join()
            }
        }

        "runLoop propagates cancellation from startup sync instead of entering the loop" {
            runTest {
                val settings = Settings(
                    loopDelaySeconds = 60L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
                val config = AppConfig(
                    kraken = KrakenCredentials(apiKey = "k", privateKey = "s"),
                    settings = settings,
                    allocations = emptyList(),
                )
                every { configService.getConfig() } returns config

                coEvery {
                    tradeHistoryService.syncTradesFromKraken()
                } throws CancellationException("shutting down")

                portfolioManager.startRebalancingLoop()

                shouldThrow<CancellationException> { portfolioManager.runLoop() }

                // Swallowing cancellation here would log it and start rebalancing anyway.
                krakenService.getBalancesCallCount shouldBe 0
            }
        }

        "cancellation during in-cycle sync stops the cycle before any rebalance work" {
            runTest {
                val settings = Settings(
                    loopDelaySeconds = 60L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
                val config = AppConfig(
                    kraken = KrakenCredentials(apiKey = "k", privateKey = "s"),
                    settings = settings,
                    allocations = emptyList(),
                )
                every { configService.getConfig() } returns config

                var syncCalls = 0
                coEvery { tradeHistoryService.syncTradesFromKraken() } answers {
                    syncCalls++
                    // Succeed on startup, then cancel once the loop body syncs.
                    if (syncCalls > 1) throw CancellationException("config changed")
                }

                portfolioManager.startRebalancingLoop()
                portfolioManager.runLoop()

                krakenService.getBalancesCallCount shouldBe 0
            }
        }

        "config change mid-delay restarts the loop without waiting out the old delay" {
            runTest {
                val longDelaySettings = Settings(
                    loopDelaySeconds = 3600L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )
                val config = AppConfig(
                    kraken = KrakenCredentials(apiKey = "k", privateKey = "s"),
                    settings = longDelaySettings,
                    allocations = emptyList(),
                )
                every { configService.getConfig() } returns config
                krakenService.balanceSupplier = { emptyMap() }

                val configFlow = MutableSharedFlow<Settings>(replay = 1, extraBufferCapacity = 8)
                every { configService.watchConfigChanges() } returns configFlow
                configFlow.emit(longDelaySettings)

                portfolioManager.startRebalancingLoop()
                val job = launch { portfolioManager.runLoop() }
                runCurrent()

                val cyclesBeforeChange = krakenService.getBalancesCallCount
                cyclesBeforeChange shouldBe 1

                // Emitted while the loop is parked in the 1h delay: collectLatest must cancel and
                // restart the cycle immediately with the new settings.
                configFlow.emit(longDelaySettings.copy(deviationTriggerPercent = 5.0))
                runCurrent()

                krakenService.getBalancesCallCount shouldBe cyclesBeforeChange + 1

                job.cancel()
            }
        }
    }
}
