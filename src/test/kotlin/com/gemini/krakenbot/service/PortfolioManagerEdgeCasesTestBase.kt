package com.gemini.krakenbot.service

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.config.Settings
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderResult
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import com.gemini.krakenbot.toBigDecimalMap
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.io.IOException
import java.math.BigDecimal
import kotlin.time.Duration.Companion.milliseconds

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
