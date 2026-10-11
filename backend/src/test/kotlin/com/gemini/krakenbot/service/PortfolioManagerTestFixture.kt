package com.gemini.krakenbot.service

import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.PortfolioAnalyzerImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import io.mockk.mockk

/** Current-cycle wiring over the controllable exchange fake and in-memory latest-state holder. */
data class PortfolioManagerTestFixture(
    val krakenService: FakeKrakenService,
    val configService: ConfigService,
    val portfolioAnalyzer: PortfolioAnalyzer,
    val orderExecutor: OrderExecutor,
    val portfolioManager: PortfolioManagerImpl,
    val currentPortfolioState: CurrentPortfolioState,
)

fun createPortfolioManagerTestFixture(): PortfolioManagerTestFixture {
    val krakenService = FakeKrakenService()
    val configService = mockk<ConfigService>(relaxed = true)
    val portfolioAnalyzer = PortfolioAnalyzerImpl(krakenService, configService)
    val orderExecutor = OrderExecutorImpl(krakenService)
    val currentPortfolioState = CurrentPortfolioState()
    val portfolioManager = PortfolioManagerImpl(
        configService = configService,
        portfolioAnalyzer = portfolioAnalyzer,
        orderExecutor = orderExecutor,
        krakenService = krakenService,
        currentPortfolioState = currentPortfolioState,
    )
    return PortfolioManagerTestFixture(
        krakenService = krakenService,
        configService = configService,
        portfolioAnalyzer = portfolioAnalyzer,
        orderExecutor = orderExecutor,
        portfolioManager = portfolioManager,
        currentPortfolioState = currentPortfolioState,
    )
}
