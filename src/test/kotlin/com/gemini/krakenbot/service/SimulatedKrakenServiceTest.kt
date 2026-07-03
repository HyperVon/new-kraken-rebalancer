package com.gemini.krakenbot.service

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.impl.SimulatedKrakenService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal

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
                    Allocation("BTC", 50.0),
                    Allocation("ETH", 40.0),
                    Allocation("USD", 10.0)
                )
            )
            every { configService.getConfig() } returns config

            val simulatedService = SimulatedKrakenService(configService)

            val prices = simulatedService.getTickerPrices("XXBTZUSD,XETHZUSD")
            prices["XXBTZUSD"] shouldNotBe null
            prices["XETHZUSD"] shouldNotBe null
            prices["XXBTZUSD"]!! shouldBeGreaterThan 0.0

            val balances = simulatedService.getBalances()
            balances["BTC"] shouldNotBe null
            balances["ETH"] shouldNotBe null
            balances["USD"] shouldNotBe null

            val totalValue = balances["USD"]!! +
                    balances["BTC"]!! * prices["XXBTZUSD"]!! +
                    balances["ETH"]!! * prices["XETHZUSD"]!!

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
            val initialBtc = initialBalances["BTC"] ?: 0.0
            val initialUsd = initialBalances["USD"] ?: 0.0

            val prices = simulatedService.getTickerPrices("BTCUSD")
            val btcPrice = prices["BTCUSD"]!!

            val buyVolume = BigDecimal.valueOf(0.5)
            val result = simulatedService.executeOrder("BTCUSD", "market", "buy", buyVolume)

            result.success shouldBe true

            val newBalances = simulatedService.getBalances()
            newBalances["BTC"] shouldBe (initialBtc + 0.5)
            newBalances["USD"] shouldBe (initialUsd - 0.5 * btcPrice)
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
            val initialBtc = initialBalances["BTC"] ?: 0.0
            val initialUsd = initialBalances["USD"] ?: 0.0

            val prices = simulatedService.getTickerPrices("BTCUSD")
            val btcPrice = prices["BTCUSD"]!!

            val sellVolume = BigDecimal.valueOf(0.2)
            val result = simulatedService.executeOrder("BTCUSD", "market", "sell", sellVolume)

            result.success shouldBe true

            val newBalances = simulatedService.getBalances()
            newBalances["BTC"] shouldBe (initialBtc - 0.2)
            newBalances["USD"] shouldBe (initialUsd + 0.2 * btcPrice)
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
            val initialBtc = initialBalances["BTC"] ?: 0.0

            // Try to sell way too much BTC
            val sellVolume = BigDecimal.valueOf(initialBtc + 10.0)
            val result = simulatedService.executeOrder("BTCUSD", "market", "sell", sellVolume)

            result.success shouldBe false
            result.errorMessage shouldNotBe null
        }
    }
}
