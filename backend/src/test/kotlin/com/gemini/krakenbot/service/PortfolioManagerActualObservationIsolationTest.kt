package com.gemini.krakenbot.service

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.service.actual.ActualObservationDispatcher
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.*
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.math.BigDecimal

class PortfolioManagerActualObservationIsolationTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "observation capture failure after order execution does not fail or undo the cycle" {
            runTest {
                val fixture = createPortfolioManagerTestFixture()
                val observationDispatcher = mockk<ActualObservationDispatcher>()
                val orderExecutor = mockk<OrderExecutor>()
                var plannedBuys: Map<String, BigDecimal>? = null
                val manager = PortfolioManagerImpl(
                    configService = fixture.configService,
                    portfolioAnalyzer = fixture.portfolioAnalyzer,
                    orderExecutor = orderExecutor,
                    krakenService = fixture.krakenService,
                    reportingDispatcher = fixture.reportingDispatcher,
                    actualObservationDispatcher = observationDispatcher,
                )
                val config = TestFixtures.config(
                    settings = TestFixtures.settings(dryRun = true, simulation = false),
                    allocations = listOf(Allocation(TestFixtures.A, 100.0), Allocation(Asset.USD, 0.0)),
                )
                io.mockk.every { fixture.configService.getConfig() } returns config
                fixture.krakenService.pricesSupplier = { mapOf(TestFixtures.AUSD to 100.0) }
                fixture.krakenService.balanceSupplier = { mapOf(TestFixtures.A to 0.0, Asset.USD to 1000.0) }
                coEvery {
                    observationDispatcher.captureAfterCycle(any(), any(), any(), any())
                } throws IOException("isolated Actual storage is unavailable")
                coEvery {
                    orderExecutor.executeOrders(any(), any(), any(), any(), any(), any(), any(), any())
                } coAnswers {
                    plannedBuys = firstArg()
                }

                val snapshot = manager.performRebalanceCycle()

                snapshot shouldNotBe null
                plannedBuys?.get(TestFixtures.A)?.signum() shouldBe 1
                coVerify(exactly = 1) {
                    orderExecutor.executeOrders(any(), any(), any(), any(), any(), any(), any(), any())
                }
                coVerify(exactly = 1) {
                    observationDispatcher.captureAfterCycle(any(), config, any(), any())
                }
            }
        }
    }
}
