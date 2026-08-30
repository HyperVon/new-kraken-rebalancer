package com.gemini.krakenbot.service

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal

class PortfolioManagerZeroAllocationTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private lateinit var fixture: PortfolioManagerTestFixture
    private val krakenService get() = fixture.krakenService
    private val configService get() = fixture.configService
    private val portfolioStatsRepository get() = fixture.portfolioStatsRepository
    private val portfolioManager: PortfolioManagerImpl get() = fixture.portfolioManager

    init {
        beforeTest {
            fixture = createPortfolioManagerTestFixture()
            coEvery {
                portfolioStatsRepository.load()
            } returns PortfolioStats(
                BigDecimal.ZERO,
            )
        }

        "testZeroAllocationToOtherAssetRebalance" {
            runTest {
                val allocA = Allocation("A", 0.0)
                val allocB = Allocation("B", 100.0)
                val allAllocations = listOf(allocA, allocB)

                val mockSettings = TestFixtures.settings(dryRun = false)
                val mockConfig = TestFixtures.config(
                    settings = mockSettings,
                    allocations = allAllocations,
                )
                every { configService.getConfig() } returns mockConfig

                val balances = mapOf(
                    "A" to 10.0,
                    "B" to 0.0,
                    Asset.USD to 100.0,
                )
                krakenService.balanceSupplier = { balances }

                val prices = mapOf(
                    "AUSD" to 100.0,
                    "BUSD" to 50.0,
                )
                krakenService.pricesSupplier = { prices }

                portfolioManager.performRebalanceCycle()

                krakenService.executedOrders.any {
                    it.pair == "AUSD" && it.type == "market" && it.side == "sell"
                } shouldBe true
            }
        }
    }
}
