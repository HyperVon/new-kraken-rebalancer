package com.gemini.krakenbot

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.repository.impl.SqliteLedgerRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqlitePortfolioStatsRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.impl.DynamicKrakenService
import com.gemini.krakenbot.service.impl.KrakenServiceImpl
import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.PortfolioAnalyzerImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import com.gemini.krakenbot.service.impl.SimulatedKrakenService
import com.gemini.krakenbot.service.impl.history.TradeHistoryServiceImpl
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.time.Instant

/**
 * Evaluation scenarios against [SimulatedKrakenService] (production emulator) with real
 * TradeHistory + in-memory SQLite. Complements [EvaluationScenariosTest] (FakeKraken exact math).
 * Assertions are invariant/tolerance based — emulator prices drift randomly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulationEvaluationScenariosTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private data class SimStack(
        val configService: ConfigService,
        val simulated: SimulatedKrakenService,
        val portfolioManager: PortfolioManagerImpl,
        val tradeHistory: TradeHistoryServiceImpl,
        val repository: SqliteTradeRepositoryImpl,
    )

    private fun createSimStack(forceRebalance: Boolean = false): SimStack {
        val configService = mockk<ConfigService>(relaxed = true)
        val simConfig = TestFixtures.DEFAULT_TEST_CONFIG.copy(
            settings = TestFixtures.DEFAULT_TEST_SETTINGS.copy(
                simulation = true,
                deviationTriggerPercent = if (forceRebalance) {
                    0.0
                } else {
                    TestFixtures.DEFAULT_TEST_SETTINGS.deviationTriggerPercent
                },
                minimumOrderSizeUSD = if (forceRebalance) {
                    0.0
                } else {
                    TestFixtures.DEFAULT_TEST_SETTINGS.minimumOrderSizeUSD
                },
            ),
        )
        every { configService.getConfig() } returns simConfig

        val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
        val db = DatabaseConfig.init(TestFixtures.MEMORY_)
        val repository = SqliteTradeRepositoryImpl(db)
        val statsRepository = SqlitePortfolioStatsRepositoryImpl(db, objectMapper)
        val ledgerRepository = SqliteLedgerRepositoryImpl(db)

        val simulated = SimulatedKrakenService(configService)
        val realService = mockk<KrakenServiceImpl>(relaxed = true)
        val dynamic = DynamicKrakenService(realService, simulated, configService)

        val analyzer =
            PortfolioAnalyzerImpl(
                krakenService = dynamic,
                configService = configService,
                portfolioStatsRepository = statsRepository,
            )
        val tradeHistory =
            TradeHistoryServiceImpl(
                repository = repository,
                portfolioStatsRepository = statsRepository,
                ledgerRepository = ledgerRepository,
                krakenService = dynamic,
                configService = configService,
                objectMapper = objectMapper,
                portfolioAnalyzer = analyzer,
            )
        val orderExecutor = OrderExecutorImpl(dynamic, tradeHistory)
        val portfolioManager =
            PortfolioManagerImpl(
                configService = configService,
                tradeHistoryService = tradeHistory,
                portfolioAnalyzer = analyzer,
                orderExecutor = orderExecutor,
                krakenService = dynamic,
            )

        return SimStack(configService, simulated, portfolioManager, tradeHistory, repository)
    }

    init {
        "sim cold start seeds historical snapshots when DB empty" {
            runTest {
                val stack = createSimStack()
                stack.tradeHistory.init()
                val history = stack.tradeHistory.getHistory()
                history.size shouldBeGreaterThanOrEqual 50
            }
        }

        "sim cold start comparison reconciles seeded fills" {
            runTest {
                val stack = createSimStack()
                stack.tradeHistory.init()
                stack.tradeHistory.syncTradesFromKraken()

                val comparison = stack.tradeHistory.getRebalancerComparison(
                    Instant.now().minusSeconds(30L * 24L * 60L * 60L),
                    Instant.now().plusSeconds(60),
                )

                comparison.availability shouldBe ComparisonAvailability.AVAILABLE
                comparison.points.size shouldBeGreaterThanOrEqual 50
                (comparison.latestDifferenceUSD!!.signum() != 0) shouldBe true
            }
        }

        "sim rebalance cycle persists snapshot and trades with cycleId" {
            runTest {
                val stack = createSimStack(forceRebalance = true)
                val snapshot = stack.portfolioManager.performRebalanceCycle()
                snapshot.shouldNotBeNull()

                val latest = stack.tradeHistory.getLatestSnapshot()
                latest.shouldNotBeNull()
                (latest.totalValueUSD.signum() > 0) shouldBe true

                val trades =
                    stack.repository.getTradesInRange(
                        Instant.EPOCH,
                        Instant.now().plusSeconds(60),
                    )
                trades.shouldNotBeEmpty()
                val cycleTrades = trades.filter { it.cycleId != null }
                cycleTrades.shouldNotBeEmpty()
                cycleTrades.all { it.cycleId!!.isNotBlank() } shouldBe true
                cycleTrades.all { it.orderTxid?.isNotBlank() == true } shouldBe true
            }
        }

        "sim sync imports emulator trade history" {
            runTest {
                val stack = createSimStack()
                stack.simulated.getBalances()
                stack.tradeHistory.syncTradesFromKraken()
                val trades =
                    stack.repository.getTradesInRange(
                        Instant.EPOCH,
                        Instant.now().plusSeconds(60),
                    )
                trades.shouldNotBeEmpty()
                stack.tradeHistory.isHistorySeeded() shouldBe true
            }
        }

        "sim multi-cycle keeps portfolio value positive" {
            runTest {
                val stack = createSimStack()
                repeat(3) {
                    val snap = stack.portfolioManager.performRebalanceCycle()
                    snap.shouldNotBeNull()
                    (snap.totalValueUSD.signum() > 0) shouldBe true
                }
                val trades =
                    stack.repository.getTradesInRange(
                        Instant.EPOCH,
                        Instant.now().plusSeconds(60),
                    )
                trades.filter { it.cycleId != null }.forEach { trade ->
                    trade.cycleId!!.isNotBlank() shouldBe true
                }
            }
        }

        "sim addSnapshot emits on history flow" {
            runTest {
                val stack = createSimStack()
                val collected = mutableListOf<PortfolioSnapshot>()
                val job =
                    launch {
                        stack.tradeHistory.getHistoryFlow().take(1).toList(collected)
                    }
                yield()
                val snap = stack.portfolioManager.performRebalanceCycle()
                snap.shouldNotBeNull()
                advanceUntilIdle()
                job.join()
                collected.shouldNotBeEmpty()
                (collected.first().totalValueUSD.signum() > 0) shouldBe true
            }
        }
    }
}
