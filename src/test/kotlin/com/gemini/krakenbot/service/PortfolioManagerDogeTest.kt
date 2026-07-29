package com.gemini.krakenbot.service

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import kotlinx.coroutines.test.runTest

class PortfolioManagerDogeTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private lateinit var fixture: PortfolioManagerTestFixture
    private val krakenService get() = fixture.krakenService
    private val configService get() = fixture.configService
    private val portfolioManager: PortfolioManagerImpl get() = fixture.portfolioManager

    init {
        beforeTest {
            fixture = createPortfolioManagerTestFixture()
        }

        "testDogeMapping" {
            runTest {
                val settings = TestFixtures.settings(loopDelaySeconds = 60L)
                val config = AppConfig(
                    kraken = KrakenCredentials(
                        apiKey = "k",
                        privateKey = "s",
                    ),
                    settings = settings,
                    allocations = listOf(
                        Allocation(
                            symbol = Asset.DOGE,
                            targetPercent = 50.0,
                        ),
                        Allocation(
                            symbol = Asset.USD,
                            targetPercent = 50.0,
                        ),
                    ),
                )
                every { configService.getConfig() } returns config

                krakenService.balanceSupplier =
                    { mapOf("XDG" to 1000.0, "ZUSD" to 500.0) }
                krakenService.pricesSupplier = { pairs ->
                    if (pairs.contains("XDGUSD")) {
                        mapOf("XDGUSD" to 0.10)
                    } else {
                        emptyMap()
                    }
                }

                portfolioManager.performRebalanceCycle()

                krakenService.getBalancesCallCount shouldBe 2
            }
        }

        "testBtcMapping" {
            runTest {
                val settings = TestFixtures.settings(loopDelaySeconds = 60L)
                val config = AppConfig(
                    kraken = KrakenCredentials(
                        apiKey = "k",
                        privateKey = "s",
                    ),
                    settings = settings,
                    allocations = listOf(
                        Allocation(
                            symbol = Asset.BTC,
                            targetPercent = 50.0,
                        ),
                        Allocation(
                            symbol = Asset.USD,
                            targetPercent = 50.0,
                        ),
                    ),
                )
                every { configService.getConfig() } returns config

                krakenService.balanceSupplier =
                    { mapOf("XXBT" to 1.0, "ZUSD" to 50000.0) }
                krakenService.pricesSupplier = { pairs ->
                    if (pairs.contains("XXBTZUSD") ||
                        pairs.contains("XBTUSD")
                    ) {
                        mapOf("XXBTZUSD" to 50000.0)
                    } else {
                        emptyMap()
                    }
                }

                portfolioManager.performRebalanceCycle()

                krakenService.getBalancesCallCount shouldBe 1
            }
        }
    }
}
