package com.gemini.krakenbot.service

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal

class PortfolioDeviationEdgeCasesTest : PortfolioManagerEdgeCasesTestBase() {

    init {
        "testPerformRebalanceCycle_DeviationExactTriggerGeneratesOrders" {
            runTest {
                val allocs = listOf(
                    Allocation(Asset.BTC, 50.0),
                    Allocation(Asset.USD, 50.0),
                )
                every { configService.getConfig() } returns TestFixtures.config(
                    settings = TestFixtures.settings(dryRun = false),
                    allocations = allocs,
                )

                krakenService.pricesSupplier = { mapOf(Asset.BTC_USD_PAIR to 50000.0) }
                // Total $10,000: BTC $5,100 (+2.0% vs $5,000 target), USD $4,900
                krakenService.balanceSupplier = {
                    mapOf(Asset.BTC to 0.102, Asset.USD to 4900.0)
                }

                portfolioManager.startRebalancingLoop()
                portfolioManager.performRebalanceCycle()

                krakenService.executedOrders.isNotEmpty() shouldBe true
                krakenService.executedOrders.any { it.pair == Asset.BTC_USD_PAIR } shouldBe true

                val captor = slot<PortfolioSnapshot>()
                coVerify { tradeHistoryService.addSnapshot(capture(captor)) }
                captor.captured.actions.any { it.contains("Deviation: BTC") } shouldBe true
            }
        }

        "testPerformRebalanceCycle_DeviationExactUnderweightTriggerGeneratesBuy" {
            runTest {
                val allocs = listOf(
                    Allocation(Asset.BTC, 50.0),
                    Allocation(Asset.USD, 50.0),
                )
                every { configService.getConfig() } returns TestFixtures.config(
                    settings = TestFixtures.settings(dryRun = false),
                    allocations = allocs,
                )

                krakenService.pricesSupplier = { mapOf(Asset.BTC_USD_PAIR to 50000.0) }
                // Total $10,000: BTC $4,900 (−2.0% vs $5,000 target), USD $5,100
                krakenService.balanceSupplier = {
                    mapOf(Asset.BTC to 0.098, Asset.USD to 5100.0)
                }

                portfolioManager.startRebalancingLoop()
                portfolioManager.performRebalanceCycle()

                krakenService.executedOrders.isNotEmpty() shouldBe true
                krakenService.executedOrders.any {
                    it.pair == Asset.BTC_USD_PAIR && it.side == TestFixtures.BUY
                } shouldBe true

                val captor = slot<PortfolioSnapshot>()
                coVerify { tradeHistoryService.addSnapshot(capture(captor)) }
                captor.captured.actions.any { it.contains("Deviation: BTC") } shouldBe true
                captor.captured.actions.none {
                    it.contains("fiat correction", ignoreCase = true)
                } shouldBe true
            }
        }

        "testPerformRebalanceCycle_DeviationJustBelowTriggerNoCryptoOrders" {
            runTest {
                val allocs = listOf(
                    Allocation(Asset.BTC, 50.0),
                    Allocation(Asset.USD, 50.0),
                )
                every { configService.getConfig() } returns TestFixtures.config(
                    settings = TestFixtures.settings(dryRun = false),
                    allocations = allocs,
                )

                krakenService.pricesSupplier = { mapOf(Asset.BTC_USD_PAIR to 50000.0) }
                // Total $10,000: BTC $5,099.50 (+1.99% vs $5,000 target), USD $4,900.50
                krakenService.balanceSupplier = {
                    mapOf(Asset.BTC to 0.10199, Asset.USD to 4900.50)
                }

                portfolioManager.startRebalancingLoop()
                portfolioManager.performRebalanceCycle()

                krakenService.executedOrders.isEmpty() shouldBe true
            }
        }

        "testAnalyzeDeviations_MissingSymbolInCurrentValues" {
            runTest {
                val totalVal = BigDecimal.valueOf(1000.0)
                val currentValuesUSD =
                    mapOf(Asset.USD to BigDecimal.valueOf(1000.0))
                val effUsdTarget = BigDecimal.valueOf(50.0)
                val cryptoScale = BigDecimal.valueOf(0.5)
                val buyOrders = mutableMapOf<String, BigDecimal>()
                val sellOrders = mutableMapOf<String, BigDecimal>()
                val actionLog = mutableListOf<String>()

                val allocs = listOf(
                    Allocation(Asset.USD, 50.0),
                    Allocation(Asset.BTC, 50.0),
                )
                every { configService.getConfig() } returns TestFixtures.config(
                    settings = TestFixtures.settings(),
                    allocations = allocs,
                )

                val result = portfolioAnalyzer.analyzeDeviations(
                    totalPortfolioValueUSD = totalVal,
                    currentValuesUSD = currentValuesUSD,
                    effectiveUsdTarget = effUsdTarget,
                    cryptoScaleFactor = cryptoScale,
                )
                buyOrders.putAll(result.buyOrders)
                sellOrders.putAll(result.sellOrders)
                actionLog.addAll(result.actionLog)
                buyOrders[Asset.BTC]?.compareTo(
                    BigDecimal("250.0"),
                ) shouldBe 0
            }
        }

        "testAnalyzeDeviations_USDTriggerOnlyEnforcesFiatCorrection" {
            runTest {
                val allocs = listOf(
                    Allocation(Asset.USD, 20.0),
                    Allocation(Asset.BTC, 40.0),
                    Allocation(Asset.ETH, 40.0),
                )
                val settings = TestFixtures.settings(deviationTriggerPercent = 15.0)
                every { configService.getConfig() } returns TestFixtures.config(
                    settings = settings,
                    allocations = allocs,
                )

                val currentValuesUSD = mapOf(
                    Asset.USD to BigDecimal("240.0"),
                    Asset.BTC to BigDecimal("380.0"),
                    Asset.ETH to BigDecimal("380.0"),
                )
                val buyOrders = mutableMapOf<String, BigDecimal>()
                val sellOrders = mutableMapOf<String, BigDecimal>()
                val actionLog = mutableListOf<String>()

                val result = portfolioAnalyzer.analyzeDeviations(
                    totalPortfolioValueUSD = BigDecimal("1000.0"),
                    currentValuesUSD = currentValuesUSD,
                    effectiveUsdTarget = BigDecimal("20.0"),
                    cryptoScaleFactor = BigDecimal.ONE,
                )
                buyOrders.putAll(result.buyOrders)
                sellOrders.putAll(result.sellOrders)
                actionLog.addAll(result.actionLog)

                buyOrders.isNotEmpty() shouldBe true
                buyOrders[Asset.BTC]!!.compareTo(
                    BigDecimal("20.0"),
                ) shouldBe 0
                buyOrders[Asset.ETH]!!.compareTo(
                    BigDecimal("20.0"),
                ) shouldBe 0
            }
        }

        "CQ-7-1: testAnalyzeDeviations_USDAndCryptoTriggersCreateCryptoOrders" {
            runTest {
                val allocs = listOf(
                    Allocation(Asset.USD, 20.0),
                    Allocation(Asset.BTC, 40.0),
                    Allocation(Asset.ETH, 40.0),
                )
                val settings = TestFixtures.settings(deviationTriggerPercent = 15.0)
                every { configService.getConfig() } returns TestFixtures.config(
                    settings = settings,
                    allocations = allocs,
                )

                val result = portfolioAnalyzer.analyzeDeviations(
                    totalPortfolioValueUSD = BigDecimal("1000.0"),
                    currentValuesUSD = mapOf(
                        Asset.USD to BigDecimal("240.0"),
                        Asset.BTC to BigDecimal("340.0"),
                        Asset.ETH to BigDecimal("420.0"),
                    ),
                    effectiveUsdTarget = BigDecimal("20.0"),
                    cryptoScaleFactor = BigDecimal.ONE,
                )

                result.buyOrders[Asset.BTC]!!.shouldBeEqualComparingTo(BigDecimal("60.0"))
                result.actionLog.any { it == "USD Deviation Triggered. Enforcing fiat correction." } shouldBe false
            }
        }

        "CQ-7-2: testAnalyzeDeviations_ExactTriggerBelowDustSkipsAssetOrder" {
            runTest {
                val allocs = listOf(
                    Allocation(Asset.USD, 90.0),
                    Allocation(Asset.BTC, 10.0),
                )
                val settings = TestFixtures.settings(dustThresholdUSD = 0.50)
                every { configService.getConfig() } returns TestFixtures.config(
                    settings = settings,
                    allocations = allocs,
                )

                val result = portfolioAnalyzer.analyzeDeviations(
                    totalPortfolioValueUSD = BigDecimal("249.50"),
                    currentValuesUSD = mapOf(
                        Asset.USD to BigDecimal("224.051"),
                        Asset.BTC to BigDecimal("25.449"),
                    ),
                    effectiveUsdTarget = BigDecimal("90.0"),
                    cryptoScaleFactor = BigDecimal.ONE,
                )

                result.buyOrders.containsKey(Asset.BTC) shouldBe false
                result.sellOrders.containsKey(Asset.BTC) shouldBe false
                result.actionLog.any { it.contains("Deviation: BTC") } shouldBe false
            }
        }

        "testAnalyzeDeviations_dustDeviationIsIgnored" {
            runTest {
                val totalVal = BigDecimal.valueOf(1000.0)
                val currentValuesUSD = mapOf(
                    Asset.USD to BigDecimal("0.0001"),
                    Asset.BTC to BigDecimal("999.9999"),
                )
                val effUsdTarget = BigDecimal.ZERO
                val cryptoScale = BigDecimal("2.0")
                val allocs = listOf(
                    Allocation(Asset.USD, 50.0),
                    Allocation(Asset.BTC, 50.0),
                )
                val settings = TestFixtures.settings(dustThresholdUSD = 5.0)
                every { configService.getConfig() } returns TestFixtures.config(
                    settings = settings,
                    allocations = allocs,
                )

                val result = portfolioAnalyzer.analyzeDeviations(
                    totalPortfolioValueUSD = totalVal,
                    currentValuesUSD = currentValuesUSD,
                    effectiveUsdTarget = effUsdTarget,
                    cryptoScaleFactor = cryptoScale,
                )

                result.buyOrders.isEmpty() shouldBe true
                result.sellOrders.isEmpty() shouldBe true
                result.actionLog.none { it.contains("USD Dev") } shouldBe true
            }
        }
    }
}
