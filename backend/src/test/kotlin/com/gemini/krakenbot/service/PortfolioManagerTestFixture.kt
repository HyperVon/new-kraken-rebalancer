package com.gemini.krakenbot.service

import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.PortfolioAnalyzerImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import io.mockk.mockk

/**
 * Shared PM wiring for unit tests: real analyzer/executor/manager over [FakeKrakenService]
 * (not SimulatedKrakenService) plus relaxed mocks for config/history/stats.
 */
data class PortfolioManagerTestFixture(
    val krakenService: FakeKrakenService,
    val configService: ConfigService,
    val tradeHistoryService: TradeHistoryService,
    val portfolioStatsRepository: PortfolioStatsRepository,
    val portfolioAnalyzer: PortfolioAnalyzer,
    val orderExecutor: OrderExecutor,
    val portfolioManager: PortfolioManagerImpl,
    val reportingDispatcher: ReportingDispatcher,
)

fun createPortfolioManagerTestFixture(): PortfolioManagerTestFixture {
    val krakenService = FakeKrakenService()
    val configService = mockk<ConfigService>(relaxed = true)
    val tradeHistoryService = mockk<TradeHistoryService>(relaxed = true)
    val portfolioStatsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
    val portfolioAnalyzer =
        PortfolioAnalyzerImpl(
            krakenService = krakenService,
            configService = configService,
            portfolioStatsRepository = portfolioStatsRepository,
        )
    val orderExecutor = OrderExecutorImpl(krakenService, tradeHistoryService)
    val reportingDispatcher = ReportingDispatcher(
        historyServiceProvider = { tradeHistoryService },
        projectionServiceProvider = { mockk<TradeProjectionService>(relaxed = true) },
    )
    val portfolioManager =
        PortfolioManagerImpl(
            configService = configService,
            portfolioAnalyzer = portfolioAnalyzer,
            orderExecutor = orderExecutor,
            krakenService = krakenService,
            reportingDispatcher = reportingDispatcher,
        )
    return PortfolioManagerTestFixture(
        krakenService = krakenService,
        configService = configService,
        tradeHistoryService = tradeHistoryService,
        portfolioStatsRepository = portfolioStatsRepository,
        portfolioAnalyzer = portfolioAnalyzer,
        orderExecutor = orderExecutor,
        portfolioManager = portfolioManager,
        reportingDispatcher = reportingDispatcher,
    )
}
