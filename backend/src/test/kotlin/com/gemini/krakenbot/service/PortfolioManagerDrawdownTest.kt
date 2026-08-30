package com.gemini.krakenbot.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.AppConfig
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.config.KrakenCredentials
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.impl.SqlitePortfolioStatsRepositoryImpl
import com.gemini.krakenbot.repository.table.PortfolioStatsTable
import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.PortfolioAnalyzerImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode

class PortfolioManagerDrawdownTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val krakenService = FakeKrakenService()
    private val configService = mockk<ConfigService>(relaxed = true)
    private val tradeHistoryService = mockk<TradeHistoryService>(relaxed = true)
    private val portfolioStatsRepository =
        mockk<PortfolioStatsRepository>(relaxed = true)
    private lateinit var portfolioManager: PortfolioManagerImpl
    private lateinit var portfolioAnalyzer: PortfolioAnalyzer
    private lateinit var orderExecutor: OrderExecutor

    init {
        beforeTest {
            portfolioAnalyzer = PortfolioAnalyzerImpl(
                krakenService = krakenService,
                configService = configService,
                portfolioStatsRepository = portfolioStatsRepository,
            )
            orderExecutor = OrderExecutorImpl(krakenService, tradeHistoryService)
            portfolioManager = PortfolioManagerImpl(
                configService = configService,
                tradeHistoryService = tradeHistoryService,
                portfolioAnalyzer = portfolioAnalyzer,
                orderExecutor = orderExecutor,
            )

            val settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L, fiatMaxDrawdown = 50.0)
            val appConfig =
                AppConfig(
                    kraken = KrakenCredentials(
                        apiKey = "k",
                        privateKey = "s",
                    ),
                    settings = settings,
                    allocations = emptyList(),
                )
            every { configService.getConfig() } returns appConfig
        }

        "testDrawdownAndFiatDeployment" {
            runTest {
                coEvery {
                    portfolioStatsRepository.load()
                } returns PortfolioStats(
                    BigDecimal("2000.0"),
                )

                val allocs = listOf(
                    Allocation("A", 50.0),
                    Allocation(Asset.USD, 50.0),
                )

                val appConfig = TestFixtures.config(
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L, fiatMaxDrawdown = 50.0),
                    allocations = allocs,
                )
                every { configService.getConfig() } returns appConfig

                val prices = mapOf("AUSD" to 100.0)
                krakenService.pricesSupplier = { prices }

                val balances = mapOf(
                    "A" to 7.5,
                    Asset.USD to 750.0,
                )
                krakenService.balanceSupplier = { balances }

                portfolioManager.performRebalanceCycle()

                krakenService.executedOrders.size shouldBe 1
                val order = krakenService.executedOrders[0]
                order.pair shouldBe "AUSD"
                order.type shouldBe "market"
                order.side shouldBe "buy"
                (
                    order.volume.subtract(BigDecimal.valueOf(3.75))
                        .abs() < BigDecimal("0.01")
                    ).shouldBeTrue()

                val captor = slot<PortfolioSnapshot>()
                coVerify { tradeHistoryService.addSnapshot(capture(captor)) }
                val s = captor.captured

                s.drawdownPercent.shouldBeEqualComparingTo(BigDecimal("25.0"))
                s.fiatDeploymentPercent.shouldBeEqualComparingTo(BigDecimal("50.0"))
                s.effectiveUsdTargetPercent.shouldBeEqualComparingTo(BigDecimal("25.0"))
            }
        }

        "testNewATH" {
            runTest {
                val stats = PortfolioStats(BigDecimal("1000.0"))
                coEvery { portfolioStatsRepository.load() } returns stats

                val allocs = listOf(
                    Allocation(
                        symbol = Asset.USD,
                        targetPercent = 100.0,
                    ),
                )

                val appConfig = TestFixtures.config(
                    settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L, fiatMaxDrawdown = 50.0),
                    allocations = allocs,
                )
                every { configService.getConfig() } returns appConfig
                krakenService.pricesSupplier = { emptyMap() }

                val balances = mapOf(Asset.USD to 1500.0)
                krakenService.balanceSupplier = { balances }

                portfolioManager.performRebalanceCycle()

                val captor = slot<PortfolioStats>()
                coVerify { portfolioStatsRepository.save(capture(captor)) }
                captor.captured.allTimeHigh.shouldNotBeNull()
                captor.captured.allTimeHigh.shouldBeEqualComparingTo(BigDecimal("1500.0"))
            }
        }

        "USD-only drawdown reports zero deployment and keeps the full USD target" {
            runTest {
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("1000.00"))
                every { configService.getConfig() } returns
                    TestFixtures.config(
                        settings =
                        TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L, fiatMaxDrawdown = 50.0),
                        allocations = listOf(Allocation(Asset.USD, 100.0)),
                    )
                krakenService.pricesSupplier = { emptyMap() }
                krakenService.balanceSupplier = { mapOf(Asset.USD to 500.0) }

                portfolioManager.performRebalanceCycle()

                val snapshot = slot<PortfolioSnapshot>()
                coVerify { tradeHistoryService.addSnapshot(capture(snapshot)) }
                snapshot.captured.drawdownPercent.shouldBeEqualComparingTo(BigDecimal("50.0000"))
                snapshot.captured.fiatDeploymentPercent.shouldBeEqualComparingTo(BigDecimal.ZERO)
                snapshot.captured.effectiveUsdTargetPercent.shouldBeEqualComparingTo(BigDecimal("100.0"))
                krakenService.executedOrders.size shouldBe 0
            }
        }

        "corrupt ATH migration aborts analysis before saving a lower ATH or planning orders" {
            runTest {
                val statsFile = File("test-ath-fail-closed-stats.json")
                val statsBackup = File("test-ath-fail-closed-stats.json.bak")
                val isolatedDb = DatabaseConfig.init(TestFixtures.MEMORY_)
                val statsRepository =
                    SqlitePortfolioStatsRepositoryImpl(isolatedDb, jacksonObjectMapper(), statsFile.path)
                val failClosedAnalyzer =
                    PortfolioAnalyzerImpl(
                        krakenService = krakenService,
                        configService = configService,
                        portfolioStatsRepository = statsRepository,
                    )
                val failClosedManager =
                    PortfolioManagerImpl(
                        configService = configService,
                        tradeHistoryService = tradeHistoryService,
                        portfolioAnalyzer = failClosedAnalyzer,
                        orderExecutor = OrderExecutorImpl(krakenService, tradeHistoryService),
                    )
                val config =
                    TestFixtures.config(
                        settings =
                        TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L, fiatMaxDrawdown = 50.0),
                        allocations =
                        listOf(
                            Allocation(Asset.BTC, 50.0),
                            Allocation(Asset.USD, 50.0),
                        ),
                    )
                every { configService.getConfig() } returns config
                krakenService.pricesSupplier = { mapOf(Asset.BTC_USD_PAIR to 100.0) }
                krakenService.balanceSupplier = { mapOf(Asset.BTC to 0.0, Asset.USD to 1000.0) }

                try {
                    statsFile.delete()
                    statsBackup.delete()
                    statsFile.writeText("{not-json")

                    shouldThrow<IOException> {
                        failClosedManager.performRebalanceCycle()
                    }

                    krakenService.executedOrders.size shouldBe 0
                    coVerify(exactly = 0) { tradeHistoryService.addSnapshot(any()) }
                    transaction(isolatedDb) {
                        PortfolioStatsTable.selectAll().count() shouldBe 0
                    }
                    statsFile.exists() shouldBe true
                    statsBackup.exists() shouldBe false
                } finally {
                    statsFile.delete()
                    statsBackup.delete()
                }
            }
        }

        "testCalculateFiatDeployment_AtMaxDrawdownSaturationExponentOne" {
            runTest {
                val settings = TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L, fiatMaxDrawdown = 50.0)
                portfolioAnalyzer.calculateFiatDeployment(
                    BigDecimal("50.0"),
                    settings,
                ).shouldBeEqualComparingTo(BigDecimal("100.0"))
            }
        }

        "testCalculateFiatDeployment_AtMaxDrawdownSaturationExponentTwo" {
            runTest {
                val settings = TestFixtures.settings(
                    dryRun = false,
                    loopDelaySeconds = 60L,
                    fiatMaxDrawdown = 50.0,
                    fiatDeploymentExponent = 2.0,
                )
                portfolioAnalyzer.calculateFiatDeployment(
                    BigDecimal("50.0"),
                    settings,
                ).shouldBeEqualComparingTo(BigDecimal("100.0"))
            }
        }

        "testCalculateFiatDeployment_AboveMaxDrawdownCoercesTo100" {
            runTest {
                val settings = TestFixtures.settings(
                    dryRun = false,
                    loopDelaySeconds = 60L,
                    fiatMaxDrawdown = 50.0,
                    fiatDeploymentExponent = 2.0,
                )
                portfolioAnalyzer.calculateFiatDeployment(
                    BigDecimal("75.0"),
                    settings,
                ).shouldBeEqualComparingTo(BigDecimal("100.0"))
            }
        }

        // CQ-3-18: ALGORITHM.md MaxDD=30% aggressive exponent 0.5 table
        // | DD 1.5% → 22% | 7.5% → 50% | 15% → 71% | 22.5% → 87% | 30% → 100% |
        "testCalculateFiatDeployment_AggressiveExponentHalf_AlgorithmTable" {
            runTest {
                val settings = TestFixtures.settings(
                    dryRun = false,
                    loopDelaySeconds = 60L,
                    fiatMaxDrawdown = 30.0,
                    fiatDeploymentExponent = 0.5,
                )
                // Documented integer Deploy% (ALGORITHM rounds for display).
                val algorithmTable =
                    listOf(
                        BigDecimal("1.5") to BigDecimal("22"),
                        BigDecimal("7.5") to BigDecimal("50"),
                        BigDecimal("15") to BigDecimal("71"),
                        BigDecimal("22.5") to BigDecimal("87"),
                        BigDecimal("30") to BigDecimal("100"),
                    )
                for ((drawdownPct, tableDeployPct) in algorithmTable) {
                    val deploy =
                        portfolioAnalyzer.calculateFiatDeployment(drawdownPct, settings)
                    deploy
                        .setScale(0, RoundingMode.HALF_UP)
                        .shouldBeEqualComparingTo(tableDeployPct)
                }
            }
        }

        // CQ-9-1: ALGORITHM.md MaxDD=30% conservative exponent 2.0 table
        // | DD 1.5% → 0.25% | 7.5% → 6.25% | 15% → 25% | 22.5% → 56% | 30% → 100% |
        "testCalculateFiatDeployment_ConservativeExponentTwo_AlgorithmTable" {
            runTest {
                val settings = TestFixtures.settings(
                    dryRun = false,
                    loopDelaySeconds = 60L,
                    fiatMaxDrawdown = 30.0,
                    fiatDeploymentExponent = 2.0,
                )
                listOf(
                    BigDecimal("1.5") to BigDecimal("0.2500"),
                    BigDecimal("7.5") to BigDecimal("6.2500"),
                    BigDecimal("15") to BigDecimal("25.0000"),
                    BigDecimal("22.5") to BigDecimal("56.2500"),
                    BigDecimal("30") to BigDecimal("100.0000"),
                ).forEach { (drawdownPct, expectedDeployPct) ->
                    portfolioAnalyzer.calculateFiatDeployment(drawdownPct, settings)
                        .shouldBeEqualComparingTo(expectedDeployPct)
                }
                // Documented integer Deploy% (ALGORITHM rounds 56.25% → 56 for display).
                portfolioAnalyzer.calculateFiatDeployment(BigDecimal("22.5"), settings)
                    .setScale(0, RoundingMode.HALF_UP)
                    .shouldBeEqualComparingTo(BigDecimal("56"))
            }
        }

        "testCalculateFiatDeployment_AggressiveExponentHalf_At25PercentOfMaxIs50" {
            runTest {
                // MaxDD 30%, DD 7.5% (25% of max) → (0.25)^0.5 * 100 = 50% exactly
                val settings = TestFixtures.settings(
                    dryRun = false,
                    loopDelaySeconds = 60L,
                    fiatMaxDrawdown = 30.0,
                    fiatDeploymentExponent = 0.5,
                )
                portfolioAnalyzer.calculateFiatDeployment(
                    BigDecimal("7.5"),
                    settings,
                ).shouldBeEqualComparingTo(BigDecimal("50.0"))
            }
        }

        "testCalculateFiatDeployment_AggressiveExponentHalf_AtMaxDrawdownIs100" {
            runTest {
                val settings = TestFixtures.settings(
                    dryRun = false,
                    loopDelaySeconds = 60L,
                    fiatMaxDrawdown = 30.0,
                    fiatDeploymentExponent = 0.5,
                )
                portfolioAnalyzer.calculateFiatDeployment(
                    BigDecimal("30.0"),
                    settings,
                ).shouldBeEqualComparingTo(BigDecimal("100.0"))
            }
        }
    }
}
