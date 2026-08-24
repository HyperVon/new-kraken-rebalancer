package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.KrakenService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal

class PortfolioAnalyzerImplTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val configService = mockk<ConfigService>(relaxed = true)
    private val portfolioStatsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
    private val krakenService = mockk<KrakenService>(relaxed = true)

    private val analyzer =
        PortfolioAnalyzerImpl(
            krakenService = krakenService,
            configService = configService,
            portfolioStatsRepository = portfolioStatsRepository,
        )

    init {
        "updateAth sets initial ATH when stored ATH is zero" {
            runTest {
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal.ZERO)

                val drawdown = analyzer.updateAthAndCalculateDrawdown(BigDecimal("1000"))

                drawdown.shouldBeEqualComparingTo(BigDecimal.ZERO)
                coVerify { portfolioStatsRepository.save(match { it.allTimeHigh.compareTo(BigDecimal("1000")) == 0 }) }
            }
        }

        "updateAth raises ATH and returns zero drawdown on new high" {
            runTest {
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("500"))

                val drawdown = analyzer.updateAthAndCalculateDrawdown(BigDecimal("1000"))

                drawdown.shouldBeEqualComparingTo(BigDecimal.ZERO)
                coVerify { portfolioStatsRepository.save(match { it.allTimeHigh.compareTo(BigDecimal("1000")) == 0 }) }
            }
        }

        "updateAth keeps ATH and computes positive drawdown below high" {
            runTest {
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("1000"))

                val drawdown = analyzer.updateAthAndCalculateDrawdown(BigDecimal("900"))

                drawdown.shouldBeEqualComparingTo(BigDecimal("10.0000"))
                coVerify { portfolioStatsRepository.save(match { it.allTimeHigh.compareTo(BigDecimal("1000")) == 0 }) }
            }
        }

        "updateAth rethrows a save failure so the cycle aborts instead of planning on an unpersisted ATH" {
            runTest {
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("1000"))
                coEvery { portfolioStatsRepository.save(any()) } throws RuntimeException("boom")

                shouldThrow<RuntimeException> {
                    analyzer.updateAthAndCalculateDrawdown(BigDecimal("900"))
                }
                coVerify { portfolioStatsRepository.save(match { it.allTimeHigh.compareTo(BigDecimal("1000")) == 0 }) }
            }
        }

        "updateAth rethrows CancellationException from save" {
            runTest {
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal.ZERO)
                coEvery { portfolioStatsRepository.save(any()) } throws CancellationException(null)

                shouldThrow<CancellationException> {
                    analyzer.updateAthAndCalculateDrawdown(BigDecimal("1000"))
                }
            }
        }

        "buildSnapshot uses ONE price for USD and ticker price for crypto" {
            runTest {
                every { configService.getConfig() } returns
                    TestFixtures.config(
                        settings = TestFixtures.settings(),
                        allocations = listOf(
                            Allocation(Asset.BTC, 50.0),
                            Allocation(Asset.USD, 50.0),
                        ),
                    )

                val snapshot =
                    analyzer.buildSnapshot(
                        balances = mapOf("BTC" to BigDecimal("1"), "USD" to BigDecimal("100")),
                        prices = mapOf("BTC" to BigDecimal("20000")),
                        currentValuesUSD = mapOf("BTC" to BigDecimal("20000"), "USD" to BigDecimal("100")),
                        totalPortfolioValueUSD = BigDecimal("20100"),
                        effectiveUsdTarget = BigDecimal("50"),
                        cryptoScaleFactor = BigDecimal("1"),
                        drawdownPct = BigDecimal.ZERO,
                        fiatDeploymentPct = BigDecimal.ZERO,
                        actionLog = emptyList(),
                    )

                snapshot.assets["USD"]!!.price.shouldBeEqualComparingTo(BigDecimal.ONE)
                snapshot.assets["BTC"]!!.price.shouldBeEqualComparingTo(BigDecimal("20000"))
            }
        }

        "buildSnapshot falls back to zero value when asset missing from current values" {
            runTest {
                every { configService.getConfig() } returns
                    TestFixtures.config(
                        settings = TestFixtures.settings(),
                        allocations = listOf(
                            Allocation(Asset.BTC, 50.0),
                            Allocation(Asset.USD, 50.0),
                        ),
                    )

                val snapshot =
                    analyzer.buildSnapshot(
                        balances = mapOf("BTC" to BigDecimal("1"), "USD" to BigDecimal("100")),
                        prices = mapOf("BTC" to BigDecimal("20000")),
                        currentValuesUSD = mapOf("USD" to BigDecimal("100")),
                        totalPortfolioValueUSD = BigDecimal("100"),
                        effectiveUsdTarget = BigDecimal("50"),
                        cryptoScaleFactor = BigDecimal("1"),
                        drawdownPct = BigDecimal.ZERO,
                        fiatDeploymentPct = BigDecimal.ZERO,
                        actionLog = emptyList(),
                    )

                snapshot.assets["BTC"]!!.valueUSD.shouldBeEqualComparingTo(BigDecimal.ZERO)
            }
        }

        "buildSnapshot throws on unresolved price for crypto asset" {
            runTest {
                every { configService.getConfig() } returns
                    TestFixtures.config(
                        settings = TestFixtures.settings(),
                        allocations = listOf(Allocation(Asset.ETH, 100.0)),
                    )

                shouldThrow<IllegalStateException> {
                    analyzer.buildSnapshot(
                        balances = mapOf("ETH" to BigDecimal("1")),
                        prices = emptyMap(),
                        currentValuesUSD = mapOf("ETH" to BigDecimal("1000")),
                        totalPortfolioValueUSD = BigDecimal("1000"),
                        effectiveUsdTarget = BigDecimal("100"),
                        cryptoScaleFactor = BigDecimal("1"),
                        drawdownPct = BigDecimal.ZERO,
                        fiatDeploymentPct = BigDecimal.ZERO,
                        actionLog = emptyList(),
                    )
                }
            }
        }
    }
}
