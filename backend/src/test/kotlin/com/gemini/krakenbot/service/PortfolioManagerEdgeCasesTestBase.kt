package com.gemini.krakenbot.service

import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.mockk.every
import kotlinx.coroutines.flow.flowOf

abstract class PortfolioManagerEdgeCasesTestBase : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    protected lateinit var fixture: PortfolioManagerTestFixture
    protected val krakenService get() = fixture.krakenService
    protected val configService get() = fixture.configService
    protected val portfolioAnalyzer get() = fixture.portfolioAnalyzer
    protected val orderExecutor get() = fixture.orderExecutor
    protected val portfolioManager: PortfolioManagerImpl get() = fixture.portfolioManager
    protected val currentPortfolioState get() = fixture.currentPortfolioState

    init {
        beforeTest {
            fixture = createPortfolioManagerTestFixture()
            every { configService.watchConfigChanges() } answers {
                flowOf(configService.getConfig().settings)
            }
        }
    }
}
