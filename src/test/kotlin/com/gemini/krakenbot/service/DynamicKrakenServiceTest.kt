package com.gemini.krakenbot.service

import com.gemini.krakenbot.TestFixtures
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal

class DynamicKrakenServiceTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val realService = mockk<KrakenServiceImpl>(relaxed = true)
    private val simulatedService = mockk<SimulatedKrakenService>(relaxed = true)
    private val configService = mockk<ConfigService>(relaxed = true)

    private fun createService(): DynamicKrakenService =
        DynamicKrakenService(realService, simulatedService, configService)

    private fun settings(simulation: Boolean, dryRun: Boolean = false) = Settings(
        loopDelaySeconds = 60,
        deviationTriggerPercent = 5.0,
        dustThresholdUSD = 5.0,
        dryRun = dryRun,
        simulation = simulation,
        fiatMaxDrawdown = 30.0,
        fiatDeploymentExponent = 1.0,
    )

    private fun appConfig(simulation: Boolean, dryRun: Boolean = false) = AppConfig(
        kraken = KrakenCredentials("test-api-key", "test-private-key"),
        settings = settings(simulation, dryRun),
        allocations = emptyList(),
    )

    init {
        "delegates to simulated service when simulation is true" {
            every { configService.getConfig() } returns appConfig(simulation = true)

            val dynamicService = createService()

            dynamicService.getBalances()
            coVerify(exactly = 1) { simulatedService.getBalances() }
            coVerify(exactly = 0) { realService.getBalances() }

            dynamicService.getTickerPrices(TestFixtures.BTCUSD)
            coVerify(exactly = 1) { simulatedService.getTickerPrices(TestFixtures.BTCUSD) }
            coVerify(exactly = 0) { realService.getTickerPrices(any()) }

            dynamicService.executeOrder(
                Asset.BTC_USD_PAIR,
                OrderSide.SELL.apiValue,
                OrderType.MARKET.apiValue,
                BigDecimal.ONE,
            )
            coVerify(exactly = 1) {
                simulatedService.executeOrder(
                    Asset.BTC_USD_PAIR,
                    OrderSide.SELL.apiValue,
                    OrderType.MARKET.apiValue,
                    BigDecimal.ONE,
                    null,
                )
            }
            coVerify(exactly = 0) {
                realService.executeOrder(any(), any(), any(), any(), any(), any())
            }

            dynamicService.getTradeHistory(12345L, 10)
            coVerify(exactly = 1) { simulatedService.getTradeHistory(12345L, 10) }
            coVerify(exactly = 0) { realService.getTradeHistory(any(), any()) }

            dynamicService.getOHLC(TestFixtures.BTCUSD, 1440, null)
            coVerify(exactly = 1) { simulatedService.getOHLC(TestFixtures.BTCUSD, 1440, null) }
            coVerify(exactly = 0) { realService.getOHLC(any(), any(), any()) }
        }

        "forwards clOrdId to the real backend when simulation is false" {
            every { configService.getConfig() } returns appConfig(simulation = false)
            val dynamicService = createService()
            val clOrdId = "6d1b345e-2821-40e2-ad83-4ecb18a06876"

            dynamicService.executeOrder(
                pair = Asset.BTC_USD_PAIR,
                type = OrderType.MARKET.apiValue,
                side = OrderSide.BUY.apiValue,
                volume = BigDecimal.ONE,
                dryRun = false,
                clOrdId = clOrdId,
            )

            coVerify(exactly = 1) {
                realService.executeOrder(
                    Asset.BTC_USD_PAIR,
                    OrderType.MARKET.apiValue,
                    OrderSide.BUY.apiValue,
                    BigDecimal.ONE,
                    false,
                    clOrdId,
                )
            }
            coVerify(exactly = 0) {
                simulatedService.executeOrder(any(), any(), any(), any(), any(), any())
            }
        }

        "forwards clOrdId to the simulated backend when simulation is true" {
            every { configService.getConfig() } returns appConfig(simulation = true)
            val dynamicService = createService()
            val clOrdId = "da8e4ad5-9b78-481c-93e5-89746b0cf91f"

            dynamicService.executeOrder(
                pair = Asset.BTC_USD_PAIR,
                type = OrderType.MARKET.apiValue,
                side = OrderSide.SELL.apiValue,
                volume = BigDecimal.ONE,
                dryRun = false,
                clOrdId = clOrdId,
            )

            coVerify(exactly = 1) {
                simulatedService.executeOrder(
                    Asset.BTC_USD_PAIR,
                    OrderType.MARKET.apiValue,
                    OrderSide.SELL.apiValue,
                    BigDecimal.ONE,
                    false,
                    clOrdId,
                )
            }
            coVerify(exactly = 0) {
                realService.executeOrder(any(), any(), any(), any(), any(), any())
            }
        }

        "delegates to real service when simulation is false" {
            every { configService.getConfig() } returns appConfig(simulation = false)

            val dynamicService = createService()

            dynamicService.getBalances()
            coVerify(exactly = 1) { realService.getBalances() }
            coVerify(exactly = 0) { simulatedService.getBalances() }

            dynamicService.getTickerPrices(TestFixtures.BTCUSD)
            coVerify(exactly = 1) { realService.getTickerPrices(TestFixtures.BTCUSD) }
            coVerify(exactly = 0) { simulatedService.getTickerPrices(any()) }

            dynamicService.executeOrder(
                Asset.BTC_USD_PAIR,
                OrderSide.BUY.apiValue,
                OrderType.MARKET.apiValue,
                BigDecimal.ONE,
            )
            coVerify(exactly = 1) {
                realService.executeOrder(
                    Asset.BTC_USD_PAIR,
                    OrderSide.BUY.apiValue,
                    OrderType.MARKET.apiValue,
                    BigDecimal.ONE,
                    null,
                )
            }
            coVerify(exactly = 0) {
                simulatedService.executeOrder(any(), any(), any(), any(), any(), any())
            }

            dynamicService.getTradeHistory(null, null)
            coVerify(exactly = 1) { realService.getTradeHistory(null, null) }
            coVerify(exactly = 0) { simulatedService.getTradeHistory(any(), any()) }

            dynamicService.getOHLC(TestFixtures.BTCUSD, 60, 1L)
            coVerify(exactly = 1) { realService.getOHLC(TestFixtures.BTCUSD, 60, 1L) }
            coVerify(exactly = 0) { simulatedService.getOHLC(any(), any(), any()) }
        }

        "withStableBackend keeps sell and buy on the backend pinned at entry despite mid-call flip" {
            every { configService.getConfig() } returns appConfig(simulation = true)

            val dynamicService = createService()
            dynamicService.withStableBackend { backend ->
                backend.executeOrder(
                    Asset.BTC_USD_PAIR,
                    OrderSide.SELL.apiValue,
                    OrderType.MARKET.apiValue,
                    BigDecimal.ONE,
                )

                every { configService.getConfig() } returns appConfig(simulation = false)

                backend.executeOrder(
                    Asset.ETH_USD_PAIR,
                    OrderSide.BUY.apiValue,
                    OrderType.MARKET.apiValue,
                    BigDecimal.ONE,
                )
            }

            coVerify(exactly = 2) {
                simulatedService.executeOrder(any(), any(), any(), any(), any(), any())
            }
            coVerify(exactly = 0) {
                realService.executeOrder(any(), any(), any(), any(), any(), any())
            }
        }

        "delegates to simulated service when simulation and dryRun are both true" {
            every { configService.getConfig() } returns appConfig(simulation = true, dryRun = true)

            val dynamicService = createService()

            dynamicService.getBalances()
            coVerify(exactly = 1) { simulatedService.getBalances() }
            coVerify(exactly = 0) { realService.getBalances() }

            dynamicService.executeOrder(
                Asset.BTC_USD_PAIR,
                OrderSide.BUY.apiValue,
                OrderType.MARKET.apiValue,
                BigDecimal.ONE,
            )
            coVerify(exactly = 1) {
                simulatedService.executeOrder(
                    Asset.BTC_USD_PAIR,
                    OrderSide.BUY.apiValue,
                    OrderType.MARKET.apiValue,
                    BigDecimal.ONE,
                    null,
                )
            }
            coVerify(exactly = 0) {
                realService.executeOrder(any(), any(), any(), any(), any(), any())
            }
        }

        "nested withStableBackend reuses outer pin instead of re-resolving" {
            every { configService.getConfig() } returns appConfig(simulation = true)

            val dynamicService = createService()
            dynamicService.withStableBackend { outer ->
                outer.executeOrder(
                    Asset.BTC_USD_PAIR,
                    OrderSide.SELL.apiValue,
                    OrderType.MARKET.apiValue,
                    BigDecimal.ONE,
                )

                every { configService.getConfig() } returns appConfig(simulation = false)

                dynamicService.withStableBackend { inner ->
                    // Nested wrap must keep the outer pin (sim), not flip to live.
                    inner.executeOrder(
                        Asset.ETH_USD_PAIR,
                        OrderSide.BUY.apiValue,
                        OrderType.MARKET.apiValue,
                        BigDecimal.ONE,
                    )
                }

                // Outer withStableBackend pin stays on simulated even after config flips to live.
                outer.getBalances()
            }

            coVerify(exactly = 2) {
                simulatedService.executeOrder(any(), any(), any(), any(), any(), any())
            }
            coVerify(exactly = 0) {
                realService.executeOrder(any(), any(), any(), any(), any(), any())
            }
            coVerify(exactly = 1) { simulatedService.getBalances() }
            coVerify(exactly = 0) { realService.getBalances() }

            dynamicService.getBalances()
            coVerify(exactly = 1) { realService.getBalances() }
        }

        "concurrent withStableBackend blocks do not share pin state" {
            runTest {
                every { configService.getConfig() } returns appConfig(simulation = true)
                val dynamicService = createService()

                // Overlapping pins: each block keeps its own backend; a mid-flight config flip
                // must not retarget the other caller's captured service.
                coroutineScope {
                    val first = async {
                        dynamicService.withStableBackend { backend ->
                            delay(50)
                            backend.executeOrder(
                                Asset.BTC_USD_PAIR,
                                OrderSide.SELL.apiValue,
                                OrderType.MARKET.apiValue,
                                BigDecimal.ONE,
                            )
                        }
                    }
                    val second = async {
                        delay(10)
                        every { configService.getConfig() } returns appConfig(simulation = false)
                        dynamicService.withStableBackend { backend ->
                            backend.executeOrder(
                                Asset.ETH_USD_PAIR,
                                OrderSide.BUY.apiValue,
                                OrderType.MARKET.apiValue,
                                BigDecimal.ONE,
                            )
                        }
                    }
                    first.await()
                    second.await()
                }

                coVerify(exactly = 1) {
                    simulatedService.executeOrder(any(), any(), any(), any(), any(), any())
                }
                coVerify(exactly = 1) {
                    realService.executeOrder(any(), any(), any(), any(), any(), any())
                }
            }
        }

        "withStableBackend pins DynamicKrakenService reads after mid-block simulation flip" {
            every { configService.getConfig() } returns appConfig(simulation = true)
            val dynamicService = createService()

            dynamicService.withStableBackend {
                every { configService.getConfig() } returns appConfig(simulation = false)
                // Call via DynamicKrakenService (not the captured backend) — must stay sim.
                dynamicService.getBalances()
                dynamicService.getTickerPrices(TestFixtures.BTCUSD)
            }

            coVerify(exactly = 1) { simulatedService.getBalances() }
            coVerify(exactly = 0) { realService.getBalances() }
            coVerify(exactly = 1) { simulatedService.getTickerPrices(TestFixtures.BTCUSD) }
            coVerify(exactly = 0) { realService.getTickerPrices(any()) }

            dynamicService.getBalances()
            coVerify(exactly = 1) { realService.getBalances() }
        }
    }
}
