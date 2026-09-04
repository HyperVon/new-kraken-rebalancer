package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.flow.flowOf
import java.math.BigDecimal

abstract class PortfolioManagerEdgeCasesTestBase : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    protected lateinit var fixture: PortfolioManagerTestFixture
    protected val krakenService get() = fixture.krakenService
    protected val configService get() = fixture.configService
    protected val tradeHistoryService get() = fixture.tradeHistoryService
    protected val portfolioStatsRepository get() = fixture.portfolioStatsRepository
    protected val portfolioAnalyzer get() = fixture.portfolioAnalyzer
    protected val orderExecutor get() = fixture.orderExecutor
    protected val portfolioManager: PortfolioManagerImpl get() = fixture.portfolioManager
    protected val inceptionDiscoveryService get() = fixture.inceptionDiscoveryService

    init {
        beforeTest {
            fixture = createPortfolioManagerTestFixture()
            coEvery { portfolioStatsRepository.load() } returns PortfolioStats(
                BigDecimal.ZERO,
            )
            every { configService.watchConfigChanges() } answers {
                flowOf(configService.getConfig().settings)
            }
        }
    }
}
