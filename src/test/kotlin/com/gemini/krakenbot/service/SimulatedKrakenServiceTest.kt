package com.gemini.krakenbot.service

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.impl.SimulatedKrakenService
import com.gemini.krakenbot.test.TestConstants
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.math.RoundingMode

class SimulatedKrakenServiceTest : StringSpec() {
    init {
        "should initialize prices and drifted balances based on config allocations" {
            val configService = mockk<ConfigService>()
            every { configService.getConfig() } returns TestFixtures.DEFAULT_TEST_CONFIG

            val simulatedService = SimulatedKrakenService(configService)

            val prices = simulatedService.getTickerPrices("XXBTZUSD,XETHZUSD")
            prices[TestFixtures.XXBTZUSD] shouldNotBe null
            prices[TestFixtures.XETHZUSD] shouldNotBe null
            (prices[TestFixtures.XXBTZUSD]!! > BigDecimal.ZERO) shouldBe true

            val balances = simulatedService.getBalances()
            balances[Asset.BTC] shouldNotBe null
            balances[Asset.ETH] shouldNotBe null
            balances[Asset.USD] shouldNotBe null

            val totalValue =
                balances[Asset.USD]!!
                    .add(balances[Asset.BTC]!!.multiply(prices[TestFixtures.XXBTZUSD]!!))
                    .add(balances[Asset.ETH]!!.multiply(prices[TestFixtures.XETHZUSD]!!))

            // Total value should be around $100,000
            (totalValue > BigDecimal("70000")) shouldBe true
            (totalValue < BigDecimal("130000")) shouldBe true
        }

        "should execute buy orders and update balances" {
            val configService = mockk<ConfigService>()
            val config = AppConfig(
                kraken = KrakenCredentials(TestConstants.API_KEY, TestConstants.API_SECRET),
                settings = TestFixtures.DEFAULT_TEST_SETTINGS,
                allocations = listOf(
                    Allocation(Asset.BTC, 50.0),
                    Allocation(Asset.USD, 50.0),
                ),
            )
            every { configService.getConfig() } returns config

            val simulatedService = SimulatedKrakenService(configService)

            val initialBalances = simulatedService.getBalances()
            val initialBtc = initialBalances[Asset.BTC] ?: BigDecimal.ZERO
            val initialUsd = initialBalances[Asset.USD] ?: BigDecimal.ZERO

            val prices = simulatedService.getTickerPrices(TestFixtures.BTCUSD)
            val btcPrice = prices[TestFixtures.BTCUSD]!!

            val buyVolume = BigDecimal("0.5")
            val result = simulatedService.executeOrder(
                TestFixtures.BTCUSD,
                TestFixtures.MARKET,
                TestFixtures.BUY,
                buyVolume,
            )

            result.success shouldBe true

            val newBalances = simulatedService.getBalances()
            newBalances[Asset.BTC]!!.shouldBeEqualComparingTo(initialBtc.add(buyVolume))
            newBalances[Asset.USD]!!.shouldBeEqualComparingTo(
                initialUsd.subtract(buyVolume.multiply(btcPrice).setScale(2, RoundingMode.HALF_UP)),
            )
        }

        "should execute sell orders and update balances" {
            val configService = mockk<ConfigService>()
            val config = AppConfig(
                kraken = KrakenCredentials(TestConstants.API_KEY, TestConstants.API_SECRET),
                settings = TestFixtures.DEFAULT_TEST_SETTINGS,
                allocations = listOf(
                    Allocation(Asset.BTC, 50.0),
                    Allocation(Asset.USD, 50.0),
                ),
            )
            every { configService.getConfig() } returns config

            val simulatedService = SimulatedKrakenService(configService)

            val initialBalances = simulatedService.getBalances()
            val initialBtc = initialBalances[Asset.BTC] ?: BigDecimal.ZERO
            val initialUsd = initialBalances[Asset.USD] ?: BigDecimal.ZERO

            val prices = simulatedService.getTickerPrices(TestFixtures.BTCUSD)
            val btcPrice = prices[TestFixtures.BTCUSD]!!

            val sellVolume = BigDecimal("0.2")
            val result = simulatedService.executeOrder(
                TestFixtures.BTCUSD,
                TestFixtures.MARKET,
                TestFixtures.SELL,
                sellVolume,
            )

            result.success shouldBe true

            val newBalances = simulatedService.getBalances()
            newBalances[Asset.BTC]!!.shouldBeEqualComparingTo(initialBtc.subtract(sellVolume))
            newBalances[Asset.USD]!!.shouldBeEqualComparingTo(
                initialUsd.add(sellVolume.multiply(btcPrice).setScale(2, RoundingMode.HALF_UP)),
            )
        }

        "should fail orders if balance is insufficient" {
            val configService = mockk<ConfigService>()
            val config = AppConfig(
                kraken = KrakenCredentials(TestConstants.API_KEY, TestConstants.API_SECRET),
                settings = TestFixtures.DEFAULT_TEST_SETTINGS,
                allocations = listOf(
                    Allocation(Asset.BTC, 50.0),
                    Allocation(Asset.USD, 50.0),
                ),
            )
            every { configService.getConfig() } returns config

            val simulatedService = SimulatedKrakenService(configService)

            val initialBalances = simulatedService.getBalances()
            val initialBtc = initialBalances[Asset.BTC] ?: BigDecimal.ZERO

            val sellVolume = initialBtc.add(BigDecimal.TEN)
            val result = simulatedService.executeOrder(
                TestFixtures.BTCUSD,
                TestFixtures.MARKET,
                TestFixtures.SELL,
                sellVolume,
            )

            result.success shouldBe false
            result.errorMessage shouldNotBe null
        }

        "should support dryRun mode when executing orders" {
            val configService = mockk<ConfigService>()
            val config = AppConfig(
                kraken = KrakenCredentials(TestConstants.API_KEY, TestConstants.API_SECRET),
                settings = Settings(
                    loopDelaySeconds = 60,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true,
                    simulation = true,
                ),
                allocations = listOf(
                    Allocation(Asset.BTC, 50.0),
                    Allocation(Asset.USD, 50.0),
                ),
            )
            every { configService.getConfig() } returns config

            val simulatedService = SimulatedKrakenService(configService)
            val result = simulatedService.executeOrder(
                TestFixtures.BTCUSD,
                TestFixtures.MARKET,
                TestFixtures.BUY,
                BigDecimal("0.1"),
            )

            result.success shouldBe true
            result.dryRun shouldBe true
        }

        "should return trade history and support filtering" {
            val configService = mockk<ConfigService>()
            val config = AppConfig(
                kraken = KrakenCredentials(TestConstants.API_KEY, TestConstants.API_SECRET),
                settings = TestFixtures.DEFAULT_TEST_SETTINGS,
                allocations = listOf(
                    Allocation(Asset.BTC, 50.0),
                    Allocation(Asset.USD, 50.0),
                ),
            )
            every { configService.getConfig() } returns config

            val simulatedService = SimulatedKrakenService(configService)

            // Triggers initialization and seeding of 15 trades
            val history = simulatedService.getTradeHistory(null, null)
            history.size shouldBe 15

            // Test filtering by startSec
            val halfTime = history[7].timestamp.epochSecond
            val filteredTime = simulatedService.getTradeHistory(halfTime, null)
            filteredTime.all { it.timestamp.epochSecond >= halfTime } shouldBe true

            // Test offset pagination
            val paginated = simulatedService.getTradeHistory(null, 5)
            paginated.size shouldBe 10
        }

        "should seed nothing if there are no non-usd allocations" {
            val configService = mockk<ConfigService>()
            val config = AppConfig(
                kraken = KrakenCredentials("api", "sec"),
                settings = Settings(
                    loopDelaySeconds = 60,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = false,
                    simulation = true,
                ),
                allocations = listOf(
                    Allocation(TestFixtures.USD, 100.0),
                ),
            )
            every { configService.getConfig() } returns config

            val simulatedService = SimulatedKrakenService(configService)
            val history = simulatedService.getTradeHistory(null, null)
            history.size shouldBe 0
        }

        "should handle unknown symbols and missing balances/prices in edge cases" {
            val configService = mockk<ConfigService>()
            val config = AppConfig(
                kraken = KrakenCredentials("api", "sec"),
                settings = Settings(
                    loopDelaySeconds = 60,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = false,
                    simulation = true,
                ),
                // "UNKNOWN" exercises initialPrices and simulatedPrices fallback paths
                allocations = listOf(
                    Allocation("UNKNOWN", 50.0),
                    Allocation(TestFixtures.USD, 50.0),
                ),
            )
            every { configService.getConfig() } returns config

            val simulatedService = SimulatedKrakenService(configService)

            val balances = simulatedService.getBalances()
            balances["UNKNOWN"] shouldNotBe null

            val prices = simulatedService.getTickerPrices(TestFixtures.ADAEUR)
            prices[TestFixtures.ADAEUR]!!.shouldBeEqualComparingTo(BigDecimal.TEN)

            val buyResult = simulatedService.executeOrder(
                TestFixtures.ADAEUR,
                TestFixtures.MARKET,
                TestFixtures.BUY,
                BigDecimal("0.1"),
            )
            buyResult.success shouldBe true

            val sellResult = simulatedService.executeOrder(
                TestFixtures.ADAEUR,
                TestFixtures.MARKET,
                TestFixtures.SELL,
                BigDecimal("10.0"),
            )
            sellResult.success shouldBe false
            sellResult.errorMessage?.contains("Insufficient ADAEUR funds") shouldBe true

            val buyTooMuchResult = simulatedService.executeOrder(
                TestFixtures.ADAEUR,
                TestFixtures.MARKET,
                TestFixtures.BUY,
                BigDecimal("100000.0"),
            )
            buyTooMuchResult.success shouldBe false
            buyTooMuchResult.errorMessage?.contains("Insufficient USD funds") shouldBe true

            val invalidResult = simulatedService.executeOrder(
                TestFixtures.ADAEUR,
                TestFixtures.MARKET,
                "hold",
                BigDecimal.ONE,
            )
            invalidResult.success shouldBe false
            invalidResult.errorMessage?.contains("Unsupported order side") shouldBe true

            // 15 seeded + 1 successful buy above; the rejected "hold" adds nothing,
            // and an offset past the end returns an empty page (not the whole history).
            val emptyHistory = simulatedService.getTradeHistory(null, 100)
            emptyHistory.size shouldBe 0
        }

        "should reject unsupported order types" {
            val configService = mockk<ConfigService>()
            val config = AppConfig(
                kraken = KrakenCredentials(TestConstants.API_KEY, TestConstants.API_SECRET),
                settings = TestFixtures.DEFAULT_TEST_SETTINGS,
                allocations = listOf(
                    Allocation(Asset.BTC, 50.0),
                    Allocation(Asset.USD, 50.0),
                ),
            )
            every { configService.getConfig() } returns config

            val simulatedService = SimulatedKrakenService(configService)
            val result = simulatedService.executeOrder(
                TestFixtures.BTCUSD,
                "limit",
                TestFixtures.BUY,
                BigDecimal.ONE,
            )

            result.success shouldBe false
            result.errorMessage?.contains("Unsupported order type") shouldBe true
        }

        "getOHLC should return empty list" {
            val configService = mockk<ConfigService>(relaxed = true)
            val simulatedService = SimulatedKrakenService(configService)
            val ohlc = simulatedService.getOHLC(TestFixtures.BTCUSD, 1440, null)
            ohlc.isEmpty() shouldBe true
        }
    }
}
