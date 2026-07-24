package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.TradeHistoryService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal

class OrderExecutorCashCapTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val krakenService = FakeKrakenService()
    private val portfolioAnalyzer = PortfolioAnalyzerImpl(
        krakenService = krakenService,
        configService = mockk(relaxed = true),
        portfolioStatsRepository = mockk(relaxed = true),
    )
    private val tradeHistoryService = mockk<TradeHistoryService>(relaxed = true)
    private val orderExecutor = OrderExecutorImpl(krakenService, portfolioAnalyzer, tradeHistoryService)

    init {
        "should always cap buys to 99% of available cash even when cost equals cash" {
            runTest {
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(success = true, pair = pair, side = side, volume = volume)
                }

                val buyOrders = mapOf(Asset.ETH to BigDecimal("1000.00"))
                val sellOrders = emptyMap<String, BigDecimal>()
                val currentValuesUSD = mapOf(Asset.USD to BigDecimal("1000.00"))
                val prices = mapOf(Asset.ETH to BigDecimal("2000.00"))
                val settings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )

                orderExecutor.executeOrders(
                    buyOrders = buyOrders,
                    sellOrders = sellOrders,
                    currentValuesUSD = currentValuesUSD,
                    prices = prices,
                    settings = settings,
                    actionLog = mutableListOf(),
                )

                // Max affordable = 1000 * 0.99 = 990; volume = 990 / 2000 = 0.495
                krakenService.executedOrders.size shouldBe 1
                krakenService.executedOrders[0].volume.shouldBeEqualComparingTo(BigDecimal("0.495"))
            }
        }

        "should cap buys that fit under cash but exceed the 99% reserve" {
            runTest {
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(success = true, pair = pair, side = side, volume = volume)
                }

                val buyOrders = mapOf(Asset.ETH to BigDecimal("995.00"))
                val sellOrders = emptyMap<String, BigDecimal>()
                val currentValuesUSD = mapOf(Asset.USD to BigDecimal("1000.00"))
                val prices = mapOf(Asset.ETH to BigDecimal("1000.00"))
                val settings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )

                orderExecutor.executeOrders(
                    buyOrders = buyOrders,
                    sellOrders = sellOrders,
                    currentValuesUSD = currentValuesUSD,
                    prices = prices,
                    settings = settings,
                    actionLog = mutableListOf(),
                )

                // Max affordable = 990; volume = 990 / 1000 = 0.99
                krakenService.executedOrders.size shouldBe 1
                krakenService.executedOrders[0].volume.shouldBeEqualComparingTo(BigDecimal("0.99"))
            }
        }

        "should not reduce buys that already sit under the 99% cash reserve" {
            runTest {
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(success = true, pair = pair, side = side, volume = volume)
                }

                val buyOrders = mapOf(Asset.ETH to BigDecimal("500.00"))
                val sellOrders = emptyMap<String, BigDecimal>()
                val currentValuesUSD = mapOf(Asset.USD to BigDecimal("1000.00"))
                val prices = mapOf(Asset.ETH to BigDecimal("1000.00"))
                val settings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )

                orderExecutor.executeOrders(
                    buyOrders = buyOrders,
                    sellOrders = sellOrders,
                    currentValuesUSD = currentValuesUSD,
                    prices = prices,
                    settings = settings,
                    actionLog = mutableListOf(),
                )

                krakenService.executedOrders.size shouldBe 1
                krakenService.executedOrders[0].volume.shouldBeEqualComparingTo(BigDecimal("0.5"))
            }
        }

        "should keep aggregate multi-buy spend within 99% of opening cash" {
            runTest {
                krakenService.orderResultFactory = { pair, _, side, volume ->
                    OrderResult(success = true, pair = pair, side = side, volume = volume)
                }

                // Two $500 buys against $1000 cash must spend at most $990 total (not $995).
                val buyOrders =
                    linkedMapOf(
                        Asset.ETH to BigDecimal("500.00"),
                        Asset.BTC to BigDecimal("500.00"),
                    )
                val sellOrders = emptyMap<String, BigDecimal>()
                val currentValuesUSD = mapOf(Asset.USD to BigDecimal("1000.00"))
                val prices =
                    mapOf(
                        Asset.ETH to BigDecimal("1000.00"),
                        Asset.BTC to BigDecimal("1000.00"),
                    )
                val settings = Settings(
                    loopDelaySeconds = 0L,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    fiatMaxDrawdown = 0.0,
                    fiatDeploymentExponent = 1.0,
                )

                orderExecutor.executeOrders(
                    buyOrders = buyOrders,
                    sellOrders = sellOrders,
                    currentValuesUSD = currentValuesUSD,
                    prices = prices,
                    settings = settings,
                    actionLog = mutableListOf(),
                )

                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[0].volume.shouldBeEqualComparingTo(BigDecimal("0.5"))
                // Remaining cycle budget after first buy: 990 - 500 = 490 → 0.49 BTC
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.49"))
            }
        }

        "should abort live buys when no positive USD balance is observed after sells" {
            runTest {
                var balancePoll = 0
                krakenService.balanceSupplier = {
                    balancePoll++
                    when (balancePoll) {
                        1 -> error("Temporary balance failure")
                        2 -> emptyMap()
                        else -> mapOf(Asset.USD to BigDecimal.ZERO)
                    }
                }

                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.ETH to BigDecimal("100.00")),
                    sellOrders = mapOf(Asset.BTC to BigDecimal("100.00")),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices =
                    mapOf(
                        Asset.BTC to BigDecimal("1000.00"),
                        Asset.ETH to BigDecimal("1000.00"),
                    ),
                    settings =
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    actionLog = mutableListOf(),
                )

                krakenService.getBalancesCallCount shouldBe 3
                krakenService.executedOrders.size shouldBe 1
                krakenService.executedOrders.single().side shouldBe "sell"
            }
        }

        "should cap live buys using the best positive USD balance observed after sells" {
            runTest {
                val observedBalances =
                    listOf(
                        mapOf(Asset.USD to BigDecimal("50.00")),
                        mapOf(Asset.USD to BigDecimal("25.00")),
                        emptyMap(),
                    )
                var balancePoll = 0
                krakenService.balanceSupplier = { observedBalances[balancePoll++] }

                orderExecutor.executeOrders(
                    buyOrders = mapOf(Asset.ETH to BigDecimal("100.00")),
                    sellOrders = mapOf(Asset.BTC to BigDecimal("100.00")),
                    currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                    prices =
                    mapOf(
                        Asset.BTC to BigDecimal("1000.00"),
                        Asset.ETH to BigDecimal("1000.00"),
                    ),
                    settings =
                    Settings(
                        loopDelaySeconds = 0L,
                        deviationTriggerPercent = 2.0,
                        dustThresholdUSD = 1.0,
                        dryRun = false,
                        fiatMaxDrawdown = 0.0,
                        fiatDeploymentExponent = 1.0,
                    ),
                    actionLog = mutableListOf(),
                )

                krakenService.getBalancesCallCount shouldBe 3
                krakenService.executedOrders.size shouldBe 2
                krakenService.executedOrders[1].side shouldBe "buy"
                krakenService.executedOrders[1].volume.shouldBeEqualComparingTo(BigDecimal("0.0495"))
            }
        }
    }
}
