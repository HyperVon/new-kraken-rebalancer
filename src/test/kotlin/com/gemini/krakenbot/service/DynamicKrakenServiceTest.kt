package com.gemini.krakenbot.service

import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.OrderType
import com.gemini.krakenbot.service.impl.DynamicKrakenService
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import com.gemini.krakenbot.service.impl.SimulatedKrakenService
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal

private const val BTCUSD = "BTCUSD"

@Suppress("unused")
class DynamicKrakenServiceTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val realService = mockk<KrakenServiceImpl>(relaxed = true)
    private val simulatedService = mockk<SimulatedKrakenService>(relaxed = true)
    private val configService = mockk<ConfigService>(relaxed = true)

    private fun createService(): DynamicKrakenService {
        return DynamicKrakenService(realService, simulatedService, configService)
    }

    init {
        "delegates to simulated service when simulation is true" {
            val appConfig = AppConfig(
                kraken = KrakenCredentials("test-api-key", "test-private-key"),
                settings = Settings(
                    loopDelaySeconds = 60,
                    deviationTriggerPercent = 5.0,
                    dustThresholdUSD = 5.0,
                    dryRun = false,
                    simulation = true, // Simulation mode active
                    fiatMaxDrawdown = 30.0,
                    fiatDeploymentExponent = 1.0
                ),
                allocations = emptyList()
            )
            every { configService.getConfig() } returns appConfig

            val dynamicService = createService()

            // getBalances
            dynamicService.getBalances()
            coVerify(exactly = 1) { simulatedService.getBalances() }
            coVerify(exactly = 0) { realService.getBalances() }

            // getTickerPrices
            dynamicService.getTickerPrices(BTCUSD)
            coVerify(exactly = 1) { simulatedService.getTickerPrices(BTCUSD) }
            coVerify(exactly = 0) { realService.getTickerPrices(any()) }

            // executeOrder
            dynamicService.executeOrder(
                Asset.BTC_USD_PAIR,
                OrderSide.SELL.apiValue,
                OrderType.MARKET.apiValue,
                BigDecimal.ONE
            )
            coVerify(exactly = 1) { simulatedService.executeOrder(
                Asset.BTC_USD_PAIR,
                OrderSide.SELL.apiValue,
                OrderType.MARKET.apiValue,
                BigDecimal.ONE
            ) }
            coVerify(exactly = 0) { realService.executeOrder(
                any(),
                any(),
                any(),
                any()
            ) }

            // getTradeHistory
            dynamicService.getTradeHistory(12345L, 10)
            coVerify(exactly = 1) { simulatedService.getTradeHistory(12345L, 10) }
            coVerify(exactly = 0) { realService.getTradeHistory(any(), any()) }

            // getOHLC
            dynamicService.getOHLC(BTCUSD, 1440, null)
            coVerify(exactly = 1) { simulatedService.getOHLC(BTCUSD, 1440, null) }
            coVerify(exactly = 0) { realService.getOHLC(any(), any(), any()) }

            // getRealService
            dynamicService.realService shouldBe realService
        }

        "delegates to real service when simulation is false" {
            val appConfig = AppConfig(
                kraken = KrakenCredentials("test-api-key", "test-private-key"),
                settings = Settings(
                    loopDelaySeconds = 60,
                    deviationTriggerPercent = 5.0,
                    dustThresholdUSD = 5.0,
                    dryRun = false,
                    simulation = false, // Simulation mode inactive
                    fiatMaxDrawdown = 30.0,
                    fiatDeploymentExponent = 1.0
                ),
                allocations = emptyList()
            )
            every { configService.getConfig() } returns appConfig

            val dynamicService = createService()

            // getBalances
            dynamicService.getBalances()
            coVerify(exactly = 1) { realService.getBalances() }
            coVerify(exactly = 0) { simulatedService.getBalances() }

            // getTickerPrices
            dynamicService.getTickerPrices(BTCUSD)
            coVerify(exactly = 1) { realService.getTickerPrices(BTCUSD) }
            coVerify(exactly = 0) { simulatedService.getTickerPrices(any()) }

            // executeOrder
            dynamicService.executeOrder(
                Asset.BTC_USD_PAIR,
                OrderSide.SELL.apiValue,
                OrderType.MARKET.apiValue,
                BigDecimal.ONE
            )
            coVerify(exactly = 1) { realService.executeOrder(
                Asset.BTC_USD_PAIR,
                OrderSide.SELL.apiValue,
                OrderType.MARKET.apiValue,
                BigDecimal.ONE
            ) }
            coVerify(exactly = 0) { simulatedService.executeOrder(
                any(),
                any(),
                any(),
                any()
            ) }

            // getTradeHistory
            dynamicService.getTradeHistory(12345L, 10)
            coVerify(exactly = 1) { realService.getTradeHistory(12345L, 10) }
            coVerify(exactly = 0) { simulatedService.getTradeHistory(any(), any()) }

            // getOHLC
            dynamicService.getOHLC(BTCUSD, 1440, null)
            coVerify(exactly = 1) { realService.getOHLC(BTCUSD, 1440, null) }
            coVerify(exactly = 0) { simulatedService.getOHLC(any(), any(), any()) }

            // getRealService
            dynamicService.realService shouldBe realService
        }
    }
}
