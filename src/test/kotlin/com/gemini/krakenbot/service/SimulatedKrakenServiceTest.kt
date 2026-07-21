package com.gemini.krakenbot.service

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.service.impl.SimulatedKrakenService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal

@Suppress("unused")
class SimulatedKrakenServiceTest : StringSpec() {
    init {
        "should initialize prices and drifted balances based on config allocations" {
            val configService = mockk<ConfigService>()
            val config = AppConfig(
                kraken = KrakenCredentials("api", "sec"),
                settings = Settings(
                    loopDelaySeconds = 60,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = false,
                    simulation = true
                ),
                allocations = listOf(
                    Allocation(Asset.BTC, 50.0),
                    Allocation(Asset.ETH, 40.0),
                    Allocation(Asset.USD, 10.0)
                )
            )
            every { configService.getConfig() } returns config

            val simulatedService = SimulatedKrakenService(configService)

            val prices = simulatedService.getTickerPrices("XXBTZUSD,XETHZUSD")
            prices["XXBTZUSD"] shouldNotBe null
            prices["XETHZUSD"] shouldNotBe null
            prices["XXBTZUSD"]!!.toDouble() shouldBeGreaterThan 0.0

            val balances = simulatedService.getBalances()
            balances[Asset.BTC] shouldNotBe null
            balances[Asset.ETH] shouldNotBe null
            balances[Asset.USD] shouldNotBe null

            val totalValue = balances[Asset.USD]!!.toDouble() +
                    balances[Asset.BTC]!!.toDouble() * prices["XXBTZUSD"]!!.toDouble() +
                    balances[Asset.ETH]!!.toDouble() * prices["XETHZUSD"]!!.toDouble()

            // Total value should be around $100,000
            totalValue shouldBeGreaterThan 70000.0
            totalValue shouldBeLessThan 130000.0
        }

        "should execute buy orders and update balances" {
            val configService = mockk<ConfigService>()
            val config = AppConfig(
                kraken = KrakenCredentials("api", "sec"),
                settings = Settings(
                    loopDelaySeconds = 60,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = false,
                    simulation = true
                ),
                allocations = listOf(
                    Allocation("BTC", 50.0),
                    Allocation("USD", 50.0)
                )
            )
            every { configService.getConfig() } returns config

            val simulatedService = SimulatedKrakenService(configService)

            val initialBalances = simulatedService.getBalances()
            val initialBtc = (initialBalances["BTC"] ?: BigDecimal.ZERO).toDouble()
            val initialUsd = (initialBalances["USD"] ?: BigDecimal.ZERO).toDouble()

            val prices = simulatedService.getTickerPrices("BTCUSD")
            val btcPrice = prices["BTCUSD"]!!.toDouble()

            val buyVolume = BigDecimal.valueOf(0.5)
            val result = simulatedService.executeOrder("BTCUSD", "market", "buy", buyVolume)

            result.success shouldBe true

            val newBalances = simulatedService.getBalances()
            newBalances["BTC"]!!.toDouble() shouldBe (initialBtc + 0.5)
            newBalances["USD"]!!.toDouble() shouldBe (initialUsd - 0.5 * btcPrice)
        }

        "should execute sell orders and update balances" {
            val configService = mockk<ConfigService>()
            val config = AppConfig(
                kraken = KrakenCredentials("api", "sec"),
                settings = Settings(
                    loopDelaySeconds = 60,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = false,
                    simulation = true
                ),
                allocations = listOf(
                    Allocation("BTC", 50.0),
                    Allocation("USD", 50.0)
                )
            )
            every { configService.getConfig() } returns config

            val simulatedService = SimulatedKrakenService(configService)

            val initialBalances = simulatedService.getBalances()
            val initialBtc = (initialBalances["BTC"] ?: BigDecimal.ZERO).toDouble()
            val initialUsd = (initialBalances["USD"] ?: BigDecimal.ZERO).toDouble()

            val prices = simulatedService.getTickerPrices("BTCUSD")
            val btcPrice = prices["BTCUSD"]!!.toDouble()

            val sellVolume = BigDecimal.valueOf(0.2)
            val result = simulatedService.executeOrder("BTCUSD", "market", "sell", sellVolume)

            result.success shouldBe true

            val newBalances = simulatedService.getBalances()
            newBalances["BTC"]!!.toDouble() shouldBe (initialBtc - 0.2)
            newBalances["USD"]!!.toDouble() shouldBe (initialUsd + 0.2 * btcPrice)
        }

        "should fail orders if balance is insufficient" {
            val configService = mockk<ConfigService>()
            val config = AppConfig(
                kraken = KrakenCredentials("api", "sec"),
                settings = Settings(
                    loopDelaySeconds = 60,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = false,
                    simulation = true
                ),
                allocations = listOf(
                    Allocation("BTC", 50.0),
                    Allocation("USD", 50.0)
                )
            )
            every { configService.getConfig() } returns config

            val simulatedService = SimulatedKrakenService(configService)

            val initialBalances = simulatedService.getBalances()
            val initialBtc = (initialBalances["BTC"] ?: BigDecimal.ZERO).toDouble()

            // Try to sell way too much BTC
            val sellVolume = BigDecimal.valueOf(initialBtc + 10.0)
            val result = simulatedService.executeOrder("BTCUSD", "market", "sell", sellVolume)

            result.success shouldBe false
            result.errorMessage shouldNotBe null
        }

        "should support dryRun mode when executing orders" {
            val configService = mockk<ConfigService>()
            val config = AppConfig(
                kraken = KrakenCredentials("api", "sec"),
                settings = Settings(
                    loopDelaySeconds = 60,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = true, // dryRun enabled
                    simulation = true
                ),
                allocations = listOf(
                    Allocation("BTC", 50.0),
                    Allocation("USD", 50.0)
                )
            )
            every { configService.getConfig() } returns config

            val simulatedService = SimulatedKrakenService(configService)
            val result = simulatedService.executeOrder("BTCUSD", "market", "buy", BigDecimal.valueOf(0.1))

            result.success shouldBe true
            result.dryRun shouldBe true
        }

        "should return trade history and support filtering" {
            val configService = mockk<ConfigService>()
            val config = AppConfig(
                kraken = KrakenCredentials("api", "sec"),
                settings = Settings(
                    loopDelaySeconds = 60,
                    deviationTriggerPercent = 2.0,
                    dustThresholdUSD = 1.0,
                    dryRun = false,
                    simulation = true
                ),
                allocations = listOf(
                    Allocation("BTC", 50.0),
                    Allocation("USD", 50.0)
                )
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
                    simulation = true
                ),
                allocations = listOf(
                    Allocation("USD", 100.0)
                )
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
                    simulation = true
                ),
                // "UNKNOWN" exercises initialPrices and simulatedPrices fallback paths (?: 10.0)
                allocations = listOf(
                    Allocation("UNKNOWN", 50.0),
                    Allocation("USD", 50.0)
                )
            )
            every { configService.getConfig() } returns config

            val simulatedService = SimulatedKrakenService(configService)

            // Triggers initialization including the UNKNOWN allocation
            val balances = simulatedService.getBalances()
            balances["UNKNOWN"] shouldNotBe null

            // Query ticker price of non-existent pair to trigger fallback prices
            val prices = simulatedService.getTickerPrices("ADAEUR")
            prices["ADAEUR"]!!.toDouble() shouldBe 10.0

            // Try to execute a BUY order on ADAEUR (ADAEUR has 0 balance, USD has positive balance)
            val buyResult = simulatedService.executeOrder("ADAEUR", "market", "buy", BigDecimal.valueOf(0.1))
            buyResult.success shouldBe true

            // Try to execute a SELL order on ADAEUR with more volume than possessed
            val sellResult = simulatedService.executeOrder("ADAEUR", "market", "sell", BigDecimal.valueOf(10.0))
            sellResult.success shouldBe false
            sellResult.errorMessage?.contains("Insufficient ADAEUR funds") shouldBe true

            // Try to execute a BUY order on ADAEUR with way too much volume to trigger insufficient USD funds
            val buyTooMuchResult = simulatedService.executeOrder("ADAEUR", "market", "buy", BigDecimal.valueOf(100000.0))
            buyTooMuchResult.success shouldBe false
            buyTooMuchResult.errorMessage?.contains("Insufficient USD funds") shouldBe true

            // Try to execute an order with an invalid side (covers the fallback branches in executeOrder)
            val invalidResult = simulatedService.executeOrder("ADAEUR", "market", "hold", BigDecimal.valueOf(1.0))
            invalidResult.success shouldBe true

            // Query trade history with offset >= size to cover the bounds checking branch
            val emptyHistory = simulatedService.getTradeHistory(null, 100)
            emptyHistory.size shouldBe 17
        }

        "getOHLC should return empty list" {
            val configService = mockk<ConfigService>(relaxed = true)
            val simulatedService = SimulatedKrakenService(configService)
            val ohlc = simulatedService.getOHLC("BTCUSD", 1440, null)
            ohlc.isEmpty() shouldBe true
        }
    }
}
