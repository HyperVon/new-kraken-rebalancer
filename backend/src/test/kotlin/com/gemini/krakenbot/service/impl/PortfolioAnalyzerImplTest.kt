package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.DepositStatusRecord
import com.gemini.krakenbot.model.FundingEvidence
import com.gemini.krakenbot.model.FundingProvenanceResolver
import com.gemini.krakenbot.model.KrakenApiConstants
import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.model.SimpleFundingProvenanceResolver
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.service.AthTrustFailureException
import com.gemini.krakenbot.service.AthTrustFailureReason
import com.gemini.krakenbot.service.AthUpdateResult
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
import java.time.Instant

class PortfolioAnalyzerImplTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val configService = mockk<ConfigService>(relaxed = true)
    private val portfolioStatsRepository = mockk<PortfolioStatsRepository>(relaxed = true)
    private val krakenService = mockk<KrakenService>(relaxed = true)

    private val testProvenanceResolver = FundingProvenanceResolver { event ->
        when (event.type) {
            KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
            KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
            -> FundingEvidence.EXTERNAL

            else -> FundingEvidence.UNRESOLVED
        }
    }

    private fun createAnalyzerWithRepos(
        ledgerRepository: LedgerRepository? = null,
        tradeRepository: TradeRepository? = null,
        nowProvider: () -> Instant = Instant::now,
        provenanceResolver: FundingProvenanceResolver = testProvenanceResolver,
    ): PortfolioAnalyzerImpl = PortfolioAnalyzerImpl(
        krakenService = krakenService,
        configService = configService,
        portfolioStatsRepository = portfolioStatsRepository,
        ledgerRepository = ledgerRepository,
        tradeRepository = tradeRepository,
        nowProvider = nowProvider,
        defaultProvenanceResolver = provenanceResolver,
    )

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
                coVerify {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match {
                            it.allTimeHigh.compareTo(BigDecimal("1000")) ==
                                0
                        },
                        any(),
                        any(),
                    )
                }
            }
        }

        "updateAth raises ATH and returns zero drawdown on new high" {
            runTest {
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("500"))

                val drawdown = analyzer.updateAthAndCalculateDrawdown(BigDecimal("1000"))

                drawdown.shouldBeEqualComparingTo(BigDecimal.ZERO)
                coVerify {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match {
                            it.allTimeHigh.compareTo(BigDecimal("1000")) ==
                                0
                        },
                        any(),
                        any(),
                    )
                }
            }
        }

        "updateAth keeps ATH and computes positive drawdown below high" {
            runTest {
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("1000"))

                val drawdown = analyzer.updateAthAndCalculateDrawdown(BigDecimal("900"))

                drawdown.shouldBeEqualComparingTo(BigDecimal("10.0000"))
                coVerify {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match {
                            it.allTimeHigh.compareTo(BigDecimal("1000")) ==
                                0
                        },
                        any(),
                        any(),
                    )
                }
            }
        }

        "updateAth rethrows a save failure so the cycle aborts instead of planning on an unpersisted ATH" {
            runTest {
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("1000"))
                coEvery { portfolioStatsRepository.saveAthStateWithFlowCheckpoint(any(), any(), any()) } throws
                    RuntimeException("boom")

                shouldThrow<RuntimeException> {
                    analyzer.updateAthAndCalculateDrawdown(BigDecimal("900"))
                }
                coVerify {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match {
                            it.allTimeHigh.compareTo(BigDecimal("1000")) ==
                                0
                        },
                        any(),
                        any(),
                    )
                }
            }
        }

        "updateAth rethrows CancellationException from save" {
            runTest {
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal.ZERO)
                coEvery { portfolioStatsRepository.saveAthStateWithFlowCheckpoint(any(), any(), any()) } throws
                    CancellationException(null)

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

        "fetchObservedBalances captures balances and observation timestamp" {
            runTest {
                coEvery { krakenService.getBalances() } returns mapOf("BTC" to BigDecimal("1.5"))
                val before = Instant.now()
                val (balances, observedAt) = analyzer.fetchObservedBalances()
                val after = Instant.now()

                balances["BTC"] shouldBe BigDecimal("1.5")
                (observedAt >= before) shouldBe true
                (observedAt <= after) shouldBe true
            }
        }

        "fetchObservedBalances captures boundary before getBalances completion" {
            runTest {
                val t0 = Instant.parse("2026-07-03T12:00:00.000Z")
                val t1 = Instant.parse("2026-07-03T12:00:00.500Z")
                var currentTime = t0
                val customAnalyzer = PortfolioAnalyzerImpl(
                    krakenService = krakenService,
                    configService = configService,
                    portfolioStatsRepository = portfolioStatsRepository,
                    nowProvider = { currentTime },
                )
                coEvery { krakenService.getBalances() } coAnswers {
                    currentTime = t1
                    mapOf("BTC" to BigDecimal("1.5"))
                }

                val observed = customAnalyzer.fetchObservedBalances()
                observed.observedAt shouldBe t0
                observed.balances["BTC"] shouldBe BigDecimal("1.5")
            }
        }

        "buildSnapshot preserves explicit balancesObservedAt" {
            runTest {
                every { configService.getConfig() } returns
                    TestFixtures.config(
                        settings = TestFixtures.settings(),
                        allocations = listOf(Allocation(Asset.USD, 100.0)),
                    )
                val observationTime = Instant.parse("2026-07-03T10:00:00Z")
                val snapshot = analyzer.buildSnapshot(
                    balances = mapOf("USD" to BigDecimal("500")),
                    prices = emptyMap(),
                    currentValuesUSD = mapOf("USD" to BigDecimal("500")),
                    totalPortfolioValueUSD = BigDecimal("500"),
                    effectiveUsdTarget = BigDecimal("100"),
                    cryptoScaleFactor = BigDecimal("1"),
                    drawdownPct = BigDecimal.ZERO,
                    fiatDeploymentPct = BigDecimal.ZERO,
                    actionLog = emptyList(),
                    balancesObservedAt = observationTime,
                )

                snapshot.balancesObservedAt shouldBe observationTime
            }
        }

        "fetchBalances returns raw balances from observed balances" {
            runTest {
                coEvery { krakenService.getBalances() } returns mapOf("BTC" to BigDecimal("2.5"))
                val balances = analyzer.fetchBalances()
                balances["BTC"] shouldBe BigDecimal("2.5")
            }
        }

        "updateAth scales ATH proportionally on external deposit" {
            runTest {
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))

                // Stored ATH: 10,000. Current total: 15,000 (after 5,000 deposit). Pre-flow: 10,000.
                // Scale factor: 15,000 / 10,000 = 1.5. Adjusted ATH = 15,000.
                val drawdown = analyzer.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("15000.00"),
                    netExternalFlowUSD = BigDecimal("5000.00"),
                )

                drawdown.shouldBeEqualComparingTo(BigDecimal.ZERO)
                coVerify {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match {
                            it.allTimeHigh.compareTo(BigDecimal("15000.00")) == 0
                        },
                        any(),
                        any(),
                    )
                }
            }
        }

        "updateAth scales ATH proportionally on external withdrawal preserving drawdown" {
            runTest {
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))

                // Stored ATH: 10,000. Value was 8,000 (20% DD). User withdraws 2,000.
                // Current total: 6,000. Pre-flow: 8,000.
                // Scale factor: 6,000 / 8,000 = 0.75. Adjusted ATH = 7,500.
                // Drawdown: (7,500 - 6,000) / 7,500 = 1,500 / 7,500 = 20.0000%.
                val drawdown = analyzer.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("6000.00"),
                    netExternalFlowUSD = BigDecimal("-2000.00"),
                )

                drawdown.shouldBeEqualComparingTo(BigDecimal("20.0000"))
                coVerify {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match {
                            it.allTimeHigh.compareTo(BigDecimal("7500.00")) == 0
                        },
                        any(),
                        any(),
                    )
                }
            }
        }

        "updateAth with unapplied ledger flows initializes watermark when missing" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val analyzerWithRepos = PortfolioAnalyzerImpl(
                    krakenService = krakenService,
                    configService = configService,
                    portfolioStatsRepository = portfolioStatsRepository,
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                    defaultProvenanceResolver = testProvenanceResolver,
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns null

                val dd = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("10000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                )

                dd.shouldBeEqualComparingTo(BigDecimal.ZERO)
                // The watermark is checkpointed atomically with the ATH value
                // inside the stats repository, not via trade metadata.
                coVerify {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        any(),
                        emptyList(),
                        fixedTime.epochSecond,
                    )
                }
                coVerify(exactly = 0) { mockTrades.setSyncMetadata(any(), any()) }
            }
        }

        "updateAth with unapplied ledger flows aggregates USD and crypto events" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val analyzerWithRepos = createAnalyzerWithRepos(
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()

                val plainUsdDeposit = LedgerEvent(
                    ledgerId = "L0",
                    refid = "FT-L0",
                    time = fixedTime.minusSeconds(2400),
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("1000.00"),
                    fee = BigDecimal.ZERO,
                )
                val usdDeposit = LedgerEvent(
                    ledgerId = "L1",
                    refid = "FT-L1",
                    time = fixedTime.minusSeconds(1800),
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "ZUSD",
                    amount = BigDecimal("2000.00"),
                    fee = BigDecimal.ZERO,
                )
                val btcDeposit = LedgerEvent(
                    ledgerId = "L2",
                    refid = "tx-btc-deposit",
                    time = fixedTime.minusSeconds(120),
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "XXBT",
                    amount = BigDecimal("0.10000000"),
                    fee = BigDecimal.ZERO,
                )
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns
                    listOf(plainUsdDeposit, usdDeposit, btcDeposit)
                coEvery { krakenService.getTickerPrices(any()) } returns mapOf(
                    "XBTUSD" to BigDecimal("50000.00"),
                    "XXBTZUSD" to BigDecimal("50000.00"),
                )

                // Total net flow = 1000 + 2000 + 0.1 * 50000 = 1000 + 2000 + 5000 = 8000.
                // Current total: 18,000. Pre-flow: 10,000. ATH scales from 10,000 to 18,000.
                val dd = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("18000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                )

                dd.shouldBeEqualComparingTo(BigDecimal.ZERO)
                coVerify {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match {
                            it.allTimeHigh.compareTo(BigDecimal("18000.00")) == 0
                        },
                        match { applied ->
                            applied.map { it.ledgerId }.toSet() == setOf("L0", "L1", "L2")
                        },
                        fixedTime.epochSecond,
                    )
                }
                coVerify(exactly = 0) { mockTrades.setSyncMetadata(any(), any()) }
            }
        }

        "updateAth defers cash-flow adjustment when balances are newer than ledger coverage" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val coverageTime = Instant.parse("2026-08-01T12:00:00Z")
                val analyzerWithRepos = PortfolioAnalyzerImpl(
                    krakenService = krakenService,
                    configService = configService,
                    portfolioStatsRepository = portfolioStatsRepository,
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { coverageTime },
                )
                // Balances observed after ledger coverage may already include
                // the deposit, so the whole update defers: ATH must NOT
                // ratchet to 12,000, nothing is persisted, and the last
                // trusted drawdown is preserved for display.
                coEvery { portfolioStatsRepository.load() } returns
                    PortfolioStats(BigDecimal("10000.00"), BigDecimal("20.0000"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns coverageTime.epochSecond.toString()
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns
                    listOf(
                        LedgerEvent(
                            ledgerId = "L9",
                            refid = "FT-L9",
                            time = coverageTime.minusSeconds(60),
                            type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                            asset = "USD",
                            amount = BigDecimal("5000.00"),
                            fee = BigDecimal.ZERO,
                        ),
                    )
                val result = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("12000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = coverageTime.plusSeconds(120),
                )

                result shouldBe
                    AthUpdateResult.Deferred(
                        BigDecimal("20.0000"),
                        AthTrustFailureReason.LEDGER_COVERAGE_STALE,
                    )
                coVerify(exactly = 0) { portfolioStatsRepository.saveAthStateWithFlowCheckpoint(any(), any(), any()) }
            }
        }

        "updateAth does not treat a post-flow snapshot save as an absorbed predecessor" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val observation = Instant.parse("2026-08-01T12:00:00Z")
                val flowTime = observation.minusSeconds(600)
                val savedAfterFlow = flowTime.plusSeconds(5)
                val analyzerWithRepos = createAnalyzerWithRepos(
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { observation },
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns observation.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns observation.minusSeconds(3600).epochSecond.toString()
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_JOURNAL_MIGRATED)
                } returns "true"
                val deposit = LedgerEvent(
                    ledgerId = "POST-SAVE-FLOW",
                    refid = "POST-SAVE-FLOW-REF",
                    time = flowTime,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("1000.00"),
                )
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns listOf(deposit)
                coEvery { portfolioStatsRepository.getAppliedAthFlowIds(any()) } returns emptySet()
                coEvery { mockTrades.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns listOf(
                    PortfolioSnapshot(
                        timestamp = savedAfterFlow,
                        totalValueUSD = BigDecimal("10000.00"),
                        assets = mapOf(
                            "USD" to TestFixtures.assetSnapshot(
                                symbol = "USD",
                                balance = BigDecimal("10000.00"),
                                price = BigDecimal.ONE,
                                valueUSD = BigDecimal("10000.00"),
                                targetPercent = BigDecimal("100.0"),
                            ),
                        ),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal("100.0"),
                        balancesObservedAt = flowTime.minusSeconds(5),
                    ),
                )

                val result = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("11000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = observation,
                )

                result shouldBe AthUpdateResult.Deferred(null, AthTrustFailureReason.PRE_FLOW_BASIS_UNCERTAIN)
                coVerify(exactly = 0) {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(any(), any(), any())
                }
            }
        }

        "updateAth replays a balance event after observation when the saved snapshot did not embed it" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val observation = Instant.parse("2026-08-01T12:00:00Z")
                val rewardTime = observation.plusSeconds(1)
                val flowTime = observation.plusSeconds(600)
                val predecessor = PortfolioSnapshot(
                    timestamp = observation.plusSeconds(3),
                    totalValueUSD = BigDecimal("10000.00"),
                    assets = mapOf(
                        "BTC" to TestFixtures.assetSnapshot(
                            symbol = "BTC",
                            balance = BigDecimal("1.0"),
                            price = BigDecimal("10000.00"),
                            valueUSD = BigDecimal("10000.00"),
                            targetPercent = BigDecimal("100.0"),
                        ),
                    ),
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal("100.0"),
                    balancesObservedAt = observation,
                )
                val reward = LedgerEvent(
                    ledgerId = "OBSERVATION-REWARD",
                    time = rewardTime,
                    type = KrakenApiConstants.LEDGER_TYPE_EARN,
                    subtype = "reward",
                    asset = "BTC",
                    amount = BigDecimal("0.1"),
                    balance = BigDecimal("1.1"),
                    hasAuthoritativeBalance = true,
                )
                val deposit = LedgerEvent(
                    ledgerId = "OBSERVATION-DEPOSIT",
                    refid = "OBSERVATION-DEPOSIT-REF",
                    time = flowTime,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("1000.00"),
                )
                val analyzerWithRepos = createAnalyzerWithRepos(
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { flowTime },
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns flowTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns observation.minusSeconds(3600).epochSecond.toString()
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_JOURNAL_MIGRATED)
                } returns "true"
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns listOf(reward, deposit)
                coEvery { portfolioStatsRepository.getAppliedAthFlowIds(any()) } returns emptySet()
                coEvery { mockTrades.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns listOf(predecessor)
                coEvery { krakenService.getTickerPrices("XBTUSD") } returns mapOf(
                    "XBTUSD" to BigDecimal("10000.00"),
                )

                val result = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("10900.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = flowTime,
                )

                // The pre-flow basis is 11,000 (1.1 BTC at 10,000), proving
                // the reward was replayed from the observation boundary even
                // though the predecessor was saved three seconds later.
                coVerify {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match { it.allTimeHigh.compareTo(BigDecimal("10909.09")) == 0 },
                        any(),
                        any(),
                    )
                }
            }
        }

        "updateAth does not double-count a balance event proven embedded in the snapshot" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val observation = Instant.parse("2026-08-01T12:00:00Z")
                val rewardTime = observation.plusSeconds(1)
                val flowTime = observation.plusSeconds(600)
                val predecessor = PortfolioSnapshot(
                    timestamp = observation.plusSeconds(3),
                    totalValueUSD = BigDecimal("11000.00"),
                    assets = mapOf(
                        "BTC" to TestFixtures.assetSnapshot(
                            symbol = "BTC",
                            balance = BigDecimal("1.1"),
                            price = BigDecimal("10000.00"),
                            valueUSD = BigDecimal("11000.00"),
                            targetPercent = BigDecimal("100.0"),
                        ),
                    ),
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal("100.0"),
                    balancesObservedAt = observation,
                )
                val reward = LedgerEvent(
                    ledgerId = "EMBEDDED-REWARD",
                    time = rewardTime,
                    type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                    asset = "BTC",
                    amount = BigDecimal("0.1"),
                    balance = BigDecimal("1.1"),
                    hasAuthoritativeBalance = true,
                )
                val deposit = LedgerEvent(
                    ledgerId = "EMBEDDED-DEPOSIT",
                    refid = "EMBEDDED-DEPOSIT-REF",
                    time = flowTime,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("1000.00"),
                )
                val analyzerWithRepos = createAnalyzerWithRepos(
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { flowTime },
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns flowTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns observation.minusSeconds(3600).epochSecond.toString()
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_JOURNAL_MIGRATED)
                } returns "true"
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns listOf(reward, deposit)
                coEvery { portfolioStatsRepository.getAppliedAthFlowIds(any()) } returns emptySet()
                coEvery { mockTrades.getTradesInRange(any(), any()) } returns emptyList()
                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns listOf(predecessor)
                coEvery { krakenService.getTickerPrices("XBTUSD") } returns mapOf(
                    "XBTUSD" to BigDecimal("10000.00"),
                )

                val result = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("10900.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = flowTime,
                )

                // The same 11,000 pre-flow basis is obtained from the
                // snapshot itself; the reward is not added a second time.
                coVerify {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match { it.allTimeHigh.compareTo(BigDecimal("10909.09")) == 0 },
                        any(),
                        any(),
                    )
                }
            }
        }

        "updateAth skips owner-capital flows for assets outside the configured universe" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val analyzerWithRepos = createAnalyzerWithRepos(
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )
                every { configService.getConfig() } returns TestFixtures.config(
                    settings = TestFixtures.settings(),
                    allocations = listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(Asset.USD, 50.0),
                    ),
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns
                    listOf(
                        LedgerEvent(
                            ledgerId = "LX",
                            refid = "tx-LX",
                            time = fixedTime.minusSeconds(900),
                            type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                            asset = "ETH",
                            amount = BigDecimal("1.00"),
                            fee = BigDecimal.ZERO,
                        ),
                        LedgerEvent(
                            ledgerId = "LB",
                            refid = "tx-LB",
                            time = fixedTime.minusSeconds(600),
                            type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                            asset = "XXBT",
                            amount = BigDecimal("0.10000000"),
                            fee = BigDecimal.ZERO,
                        ),
                    )
                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns
                    listOf(
                        PortfolioSnapshot(
                            timestamp = fixedTime.minusSeconds(600),
                            totalValueUSD = BigDecimal("10000.00"),
                            assets = mapOf(
                                "BTC" to TestFixtures.assetSnapshot(
                                    symbol = "BTC",
                                    balance = BigDecimal("1.0"),
                                    price = BigDecimal("50000.00"),
                                    valueUSD = BigDecimal("50000.00"),
                                    targetPercent = BigDecimal("50.0"),
                                ),
                            ),
                            actions = emptyList(),
                            drawdownPercent = BigDecimal.ZERO,
                            fiatDeploymentPercent = BigDecimal.ZERO,
                            effectiveUsdTargetPercent = BigDecimal.ZERO,
                        ),
                    )

                // ETH is outside the configured universe so only the 0.1 BTC
                // deposit (5,000 at the snapshot price) scales ATH: 10,000 to
                // 15,000. Observed-at equals ledger coverage, so flows apply.
                val result = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("15000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = fixedTime,
                )

                result shouldBe
                    AthUpdateResult.Trusted(BigDecimal.ZERO)
                coVerify {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match {
                            it.allTimeHigh.compareTo(BigDecimal("15000.00")) == 0
                        },
                        any(),
                        any(),
                    )
                }
            }
        }

        "updateAth skips already-checkpointed flows and applies only the remainder" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val analyzerWithRepos = createAnalyzerWithRepos(
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns
                    listOf(
                        LedgerEvent(
                            ledgerId = "L1",
                            refid = "FT-L1",
                            time = fixedTime.minusSeconds(1800),
                            type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                            asset = "USD",
                            amount = BigDecimal("1000.00"),
                            fee = BigDecimal.ZERO,
                        ),
                        LedgerEvent(
                            ledgerId = "L2",
                            refid = "FT-L2",
                            time = fixedTime.minusSeconds(900),
                            type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                            asset = "USD",
                            amount = BigDecimal("2000.00"),
                            fee = BigDecimal.ZERO,
                        ),
                    )
                // L1 was checkpointed by an earlier cycle (watermark held) that
                // had already scaled ATH to 11000: only L2 scales now.
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("11000.00"))
                coEvery { portfolioStatsRepository.getAppliedAthFlowIds(any()) } returns setOf("L1")

                val result = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("13000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = fixedTime,
                )

                // Basis 11000 (13000 - 2000): 11000 * 13000/11000 = 13000.
                (result as AthUpdateResult.Trusted)
                    .drawdownPct.shouldBeEqualComparingTo(BigDecimal.ZERO)
                coVerify {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match { it.allTimeHigh.compareTo(BigDecimal("13000.00")) == 0 },
                        match { applied -> applied.map { it.ledgerId } == listOf("L2") },
                        fixedTime.epochSecond,
                    )
                }
            }
        }

        "updateAth is a no-op when every candidate flow is already checkpointed" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val analyzerWithRepos = PortfolioAnalyzerImpl(
                    krakenService = krakenService,
                    configService = configService,
                    portfolioStatsRepository = portfolioStatsRepository,
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns
                    listOf(
                        LedgerEvent(
                            ledgerId = "L1",
                            refid = "FT-L1",
                            time = fixedTime.minusSeconds(1800),
                            type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                            asset = "USD",
                            amount = BigDecimal("1000.00"),
                            fee = BigDecimal.ZERO,
                        ),
                    )
                coEvery { portfolioStatsRepository.getAppliedAthFlowIds(any()) } returns setOf("L1")

                val result = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("10000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = fixedTime,
                )

                (result as AthUpdateResult.Trusted)
                    .drawdownPct.shouldBeEqualComparingTo(BigDecimal.ZERO)
                coVerify {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match { it.allTimeHigh.compareTo(BigDecimal("10000.00")) == 0 },
                        emptyList(),
                        fixedTime.epochSecond,
                    )
                }
            }
        }

        "updateAth leaves ATH unchanged for a dust flow that rounds to zero" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val analyzerWithRepos = createAnalyzerWithRepos(
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns
                    listOf(
                        LedgerEvent(
                            ledgerId = "LDUST",
                            refid = "FT-LDUST",
                            time = fixedTime.minusSeconds(900),
                            type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                            asset = "USD",
                            amount = BigDecimal("0.001"),
                            fee = BigDecimal.ZERO,
                        ),
                    )

                val result = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("10000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = fixedTime,
                )

                (result as AthUpdateResult.Trusted)
                    .drawdownPct.shouldBeEqualComparingTo(BigDecimal.ZERO)
                coVerify {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match { it.allTimeHigh.compareTo(BigDecimal("10000.00")) == 0 },
                        match { applied -> applied.map { it.ledgerId } == listOf("LDUST") },
                        fixedTime.epochSecond,
                    )
                }
            }
        }

        "updateAth defers on a malformed flow watermark without reading ledgers" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val analyzerWithRepos = PortfolioAnalyzerImpl(
                    krakenService = krakenService,
                    configService = configService,
                    portfolioStatsRepository = portfolioStatsRepository,
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns "bogus"

                val result = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("10000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = fixedTime,
                )

                // A corrupt watermark must not silently advance past unapplied
                // flows: skipped withdrawals would overstate drawdown and
                // over-deploy. Defer with no writes; the operator repairs the
                // key. Ledgers are never even read.
                (result as AthUpdateResult.Deferred)
                    .lastTrustedDrawdownPct shouldBe null
                coVerify(exactly = 0) { mockLedgers.getLedgersInRange(any(), any()) }
                coVerify(exactly = 0) {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(any(), any(), any())
                    mockTrades.setSyncMetadata(any(), any())
                }
            }
        }

        "updateAth decides late-arriving rows by identity, not by the legacy watermark window" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val analyzerWithRepos = createAnalyzerWithRepos(
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_JOURNAL_MIGRATED)
                } returns "true"
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()
                // A backfilled historical row (L-OLD) below the legacy ATH
                // watermark and a fresh row (L-NEW) above it, neither decided
                // yet. Identity scanning reaches both: L-NEW scales 10000 *
                // 11000/10000 = 11000, while L-OLD predates every retained
                // snapshot and is consciously skipped into the journal. The
                // old timestamp window would have silently dropped L-OLD
                // without any decision record.
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns
                    listOf(
                        LedgerEvent(
                            ledgerId = "L-OLD",
                            refid = "FT-L-OLD",
                            time = fixedTime.minusSeconds(7200),
                            type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                            asset = "USD",
                            amount = BigDecimal("99999.00"),
                            fee = BigDecimal.ZERO,
                        ),
                        LedgerEvent(
                            ledgerId = "L-NEW",
                            refid = "FT-L-NEW",
                            time = fixedTime.minusSeconds(900),
                            type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                            asset = "USD",
                            amount = BigDecimal("1000.00"),
                            fee = BigDecimal.ZERO,
                        ),
                    )
                coEvery { portfolioStatsRepository.getAppliedAthFlowIds(any()) } returns emptySet()
                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns
                    listOf(TestFixtures.emptySnapshot(fixedTime.minusSeconds(900), BigDecimal("10000.00")))

                val result = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("11000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = fixedTime,
                )

                (result as AthUpdateResult.Trusted)
                    .drawdownPct.shouldBeEqualComparingTo(BigDecimal.ZERO)
                coVerify {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match { it.allTimeHigh.compareTo(BigDecimal("11000.00")) == 0 },
                        match { applied -> applied.map { it.ledgerId } == listOf("L-OLD", "L-NEW") },
                        fixedTime.epochSecond,
                    )
                }
            }
        }

        "updateAth defers without writes when an owner-capital flow cannot be priced" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val analyzerWithRepos = PortfolioAnalyzerImpl(
                    krakenService = krakenService,
                    configService = configService,
                    portfolioStatsRepository = portfolioStatsRepository,
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                    defaultProvenanceResolver = testProvenanceResolver,
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(86400L * 32).epochSecond.toString()
                // Stale crypto deposit: no snapshot nearby and older than the
                // 24h ticker bound, so pricing defers the whole update (no
                // zeroing, no checkpoint, cycle keeps snapshotting) instead
                // of aborting the cycle. (The watermark must strictly precede
                // the event: an event AT the watermark was already processed.)
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns
                    listOf(
                        LedgerEvent(
                            ledgerId = "LS",
                            refid = "tx-LS",
                            time = fixedTime.minusSeconds(86400L * 31),
                            type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                            asset = "XXBT",
                            amount = BigDecimal("0.10000000"),
                            fee = BigDecimal.ZERO,
                        ),
                    )
                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { portfolioStatsRepository.load() } returns
                    PortfolioStats(BigDecimal("10000.00"), BigDecimal("5.0000"))

                val result = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("10000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = fixedTime,
                )

                result shouldBe
                    AthUpdateResult.Deferred(
                        BigDecimal("5.0000"),
                        AthTrustFailureReason.HISTORICAL_PRICE_UNAVAILABLE,
                    )
                coVerify(exactly = 0) {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(any(), any(), any())
                }
            }
        }

        "calculateCryptoScaleFactor and analyzeDeviations delegate to engine" {
            every { configService.getConfig() } returns TestFixtures.config(
                settings = TestFixtures.settings(),
                allocations = listOf(
                    Allocation(Asset.BTC, 50.0),
                    Allocation(Asset.ETH, 30.0),
                    Allocation(Asset.USD, 20.0),
                ),
            )

            val factor = analyzer.calculateCryptoScaleFactor(BigDecimal("10.0"))
            factor.shouldBeEqualComparingTo(BigDecimal("1.125")) // 90 / 80 = 1.125

            val plan = analyzer.analyzeDeviations(
                totalPortfolioValueUSD = BigDecimal("10000.00"),
                currentValuesUSD = mapOf(
                    "BTC" to BigDecimal("5000.00"),
                    "ETH" to BigDecimal("3000.00"),
                    "USD" to BigDecimal("2000.00"),
                ),
                effectiveUsdTarget = BigDecimal("20.0"),
                cryptoScaleFactor = BigDecimal.ONE,
            )
            plan.buyOrders.isEmpty() shouldBe true
            plan.sellOrders.isEmpty() shouldBe true
        }

        "updateAth with initial zero ATH and external flow sets ATH to total value" {
            runTest {
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal.ZERO)
                val dd = analyzer.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("5000.00"),
                    netExternalFlowUSD = BigDecimal("5000.00"),
                )
                dd.shouldBeEqualComparingTo(BigDecimal.ZERO)
                coVerify {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match {
                            it.allTimeHigh.compareTo(BigDecimal("5000.00")) == 0
                        },
                        any(),
                        any(),
                    )
                }
            }
        }

        "updateAth returns zero flow when tradeRepository is missing or events are empty" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val analyzerNoTrades = PortfolioAnalyzerImpl(
                    krakenService = krakenService,
                    configService = configService,
                    portfolioStatsRepository = portfolioStatsRepository,
                    ledgerRepository = mockLedgers,
                    tradeRepository = null,
                    nowProvider = { fixedTime },
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))

                val dd1 = analyzerNoTrades.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("10000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                )
                dd1.shouldBeEqualComparingTo(BigDecimal.ZERO)

                val mockTrades = mockk<TradeRepository>(relaxed = true)
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(60).epochSecond.toString()
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns emptyList()

                val analyzerWithEmptyEvents = PortfolioAnalyzerImpl(
                    krakenService = krakenService,
                    configService = configService,
                    portfolioStatsRepository = portfolioStatsRepository,
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )
                val dd2 = analyzerWithEmptyEvents.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("10000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                )
                dd2.shouldBeEqualComparingTo(BigDecimal.ZERO)
            }
        }

        "pricing failure on crypto owner capital fails closed without advancing watermark and succeeds on retry" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val analyzerWithRepos = createAnalyzerWithRepos(
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()

                val cryptoDeposit = LedgerEvent(
                    ledgerId = "L2",
                    refid = "tx-L2",
                    time = fixedTime.minusSeconds(120),
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "SOL",
                    amount = BigDecimal("10.0"),
                    fee = BigDecimal.ZERO,
                )
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns listOf(cryptoDeposit)
                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getTickerPrices("SOLUSD") } throws RuntimeException("network down")

                // Step 1: Pricing fails -> Deferred (no trusted drawdown yet),
                // watermark does not advance, ATH not saved
                val deferred = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("11000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = fixedTime,
                )
                deferred shouldBe AthUpdateResult.Deferred(null, AthTrustFailureReason.HISTORICAL_PRICE_UNAVAILABLE)
                coVerify(exactly = 0) {
                    mockTrades.setSyncMetadata(
                        SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                        any(),
                    )
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(any(), any(), any())
                }

                // Step 2: Retry when ticker succeeds -> ATH scaled once, watermark advances
                coEvery { krakenService.getTickerPrices("SOLUSD") } returns mapOf("SOLUSD" to BigDecimal("100.00"))
                val dd = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("11000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                )
                dd.shouldBeEqualComparingTo(BigDecimal.ZERO)
                coVerify(exactly = 1) {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match { it.allTimeHigh.compareTo(BigDecimal("11000.00")) == 0 },
                        match { applied -> applied.map { it.ledgerId } == listOf("L2") },
                        fixedTime.epochSecond,
                    )
                }
                coVerify(exactly = 0) { mockTrades.setSyncMetadata(any(), any()) }
            }
        }

        "two-arg overload throws IllegalStateException on deferral instead of ClassCastException" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val analyzerWithRepos = PortfolioAnalyzerImpl(
                    krakenService = krakenService,
                    configService = configService,
                    portfolioStatsRepository = portfolioStatsRepository,
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()

                val cryptoDeposit = LedgerEvent(
                    ledgerId = "L2",
                    refid = "tx-L2",
                    time = fixedTime.minusSeconds(900),
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "SOL",
                    amount = BigDecimal("10.0"),
                    fee = BigDecimal.ZERO,
                )
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns listOf(cryptoDeposit)
                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getTickerPrices("SOLUSD") } throws RuntimeException("network down")

                // Pricing fails -> the update defers, and the legacy overload
                // surfaces that as IllegalStateException (fail-closed abort),
                // never ClassCastException from a blind Trusted cast.
                shouldThrow<IllegalStateException> {
                    analyzerWithRepos.updateAthAndCalculateDrawdown(
                        totalPortfolioValueUSD = BigDecimal("11000.00"),
                        netExternalFlowUSD = BigDecimal.ZERO,
                    )
                }
            }
        }

        "crypto flow pricing uses historical portfolio snapshot price without querying ticker" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val analyzerWithRepos = createAnalyzerWithRepos(
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()

                val cryptoDeposit = LedgerEvent(
                    ledgerId = "L2",
                    refid = "tx-L2",
                    time = fixedTime.minusSeconds(900),
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "SOL",
                    amount = BigDecimal("10.0"),
                    fee = BigDecimal.ZERO,
                )
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns listOf(cryptoDeposit)

                val snap = PortfolioSnapshot(
                    timestamp = fixedTime.minusSeconds(905),
                    totalValueUSD = BigDecimal("10000.00"),
                    assets = mapOf(
                        "SOL" to TestFixtures.assetSnapshot(
                            symbol = "SOL",
                            balance = BigDecimal("10.0"),
                            price = BigDecimal("150.00"),
                            valueUSD = BigDecimal("1500.00"),
                            targetPercent = BigDecimal("15.0"),
                        ),
                        "USD" to TestFixtures.assetSnapshot(
                            symbol = "USD",
                            balance = BigDecimal("8500.00"),
                            price = BigDecimal.ONE,
                            valueUSD = BigDecimal("8500.00"),
                            targetPercent = BigDecimal("85.0"),
                        ),
                    ),
                    actions = emptyList<String>(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                )
                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns listOf(snap)
                val recentZeroVolumeTrade = TestFixtures.tradeRecord(
                    timestamp = cryptoDeposit.time.minusSeconds(60),
                    pair = "SOLUSD",
                    side = TestFixtures.BUY,
                    symbol = "SOL",
                    volume = BigDecimal.ZERO,
                    usdAmount = BigDecimal.ZERO,
                )
                val recentZeroNotionalTrade = recentZeroVolumeTrade.copy(
                    volume = BigDecimal.ONE,
                )
                coEvery { mockTrades.getTradesInRange(any(), any()) } returnsMany listOf(
                    listOf(recentZeroVolumeTrade),
                    emptyList(),
                    listOf(recentZeroNotionalTrade),
                )

                val dd = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("11500.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                )
                dd.shouldBeEqualComparingTo(BigDecimal.ZERO)

                coVerify(exactly = 0) { krakenService.getTickerPrices(any()) }
                coVerify(exactly = 1) {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match {
                            it.allTimeHigh.compareTo(BigDecimal("11500.00")) == 0
                        },
                        any(),
                        any(),
                    )
                }
            }
        }

        "crypto flow pricing prefers a successful historical trade over OHLC" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val flowTime = fixedTime.minusSeconds(600)
                val analyzerWithRepos = createAnalyzerWithRepos(
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )
                every { configService.getConfig() } returns TestFixtures.config(
                    settings = TestFixtures.settings(),
                    allocations = listOf(Allocation(Asset.SOL, 50.0), Allocation(Asset.USD, 50.0)),
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns flowTime.minusSeconds(3600).epochSecond.toString()
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_JOURNAL_MIGRATED)
                } returns "true"

                val deposit = LedgerEvent(
                    ledgerId = "TRADE-PRICED-DEPOSIT",
                    refid = "TRADE-PRICED-REF",
                    time = flowTime,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "SOL",
                    amount = BigDecimal("5.0"),
                )
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns listOf(deposit)
                coEvery { portfolioStatsRepository.getAppliedAthFlowIds(any()) } returns emptySet()

                val validTrade = TestFixtures.tradeRecord(
                    timestamp = flowTime.minusSeconds(60),
                    pair = "SOLUSD",
                    side = TestFixtures.BUY,
                    symbol = "SOL",
                    volume = BigDecimal.ONE,
                    usdAmount = BigDecimal("100.00"),
                    price = BigDecimal("100.00"),
                )
                coEvery { mockTrades.getTradesInRange(any(), any()) } returns listOf(
                    validTrade.copy(success = false),
                    validTrade.copy(dryRun = true),
                    validTrade.copy(symbol = "BTC"),
                    validTrade,
                )
                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns listOf(
                    PortfolioSnapshot(
                        timestamp = flowTime.minusSeconds(600),
                        totalValueUSD = BigDecimal("10000.00"),
                        assets = mapOf(
                            "SOL" to TestFixtures.assetSnapshot(
                                symbol = "SOL",
                                balance = BigDecimal("10.0"),
                                price = BigDecimal("100.00"),
                                valueUSD = BigDecimal("1000.00"),
                                targetPercent = BigDecimal("10.0"),
                            ),
                            Asset.USD to TestFixtures.assetSnapshot(
                                symbol = Asset.USD,
                                balance = BigDecimal("9000.00"),
                                price = BigDecimal.ONE,
                                valueUSD = BigDecimal("9000.00"),
                                targetPercent = BigDecimal("90.0"),
                            ),
                        ),
                        actions = emptyList<String>(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                        balancesObservedAt = flowTime.minusSeconds(600),
                    ),
                )

                analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("10505.05"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = fixedTime,
                ) shouldBe AthUpdateResult.Trusted(BigDecimal.ZERO)

                coVerify(exactly = 0) { krakenService.getOHLC(any(), any(), any()) }
                coVerify(exactly = 0) { krakenService.getTickerPrices(any()) }
                coVerify {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match {
                            it.allTimeHigh.compareTo(BigDecimal("10505.05")) == 0
                        },
                        any(),
                        any(),
                    )
                }
            }
        }

        "crypto flow pricing uses only completed 15-minute OHLC candles" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val flowTime = fixedTime.minusSeconds(600)
                val analyzerWithRepos = createAnalyzerWithRepos(
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )
                every { configService.getConfig() } returns TestFixtures.config(
                    settings = TestFixtures.settings(),
                    allocations = listOf(Allocation(Asset.SOL, 50.0), Allocation(Asset.USD, 50.0)),
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns flowTime.minusSeconds(3600).epochSecond.toString()
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_JOURNAL_MIGRATED)
                } returns "true"
                val deposit = LedgerEvent(
                    ledgerId = "OHLC-DEPOSIT",
                    refid = "OHLC-DEPOSIT-REF",
                    time = flowTime,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "SOL",
                    amount = BigDecimal("5.0"),
                )
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns listOf(deposit)
                coEvery { portfolioStatsRepository.getAppliedAthFlowIds(any()) } returns emptySet()
                coEvery { mockTrades.getTradesInRange(any(), any()) } returns emptyList()
                val predecessor = PortfolioSnapshot(
                    timestamp = flowTime.minusSeconds(600),
                    totalValueUSD = BigDecimal("10000.00"),
                    assets = mapOf(
                        "SOL" to TestFixtures.assetSnapshot(
                            symbol = "SOL",
                            balance = BigDecimal("10.0"),
                            price = BigDecimal("100.00"),
                            valueUSD = BigDecimal("1000.00"),
                            targetPercent = BigDecimal("10.0"),
                        ),
                        Asset.USD to TestFixtures.assetSnapshot(
                            symbol = Asset.USD,
                            balance = BigDecimal("9000.00"),
                            price = BigDecimal.ONE,
                            valueUSD = BigDecimal("9000.00"),
                            targetPercent = BigDecimal("90.0"),
                        ),
                    ),
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                    balancesObservedAt = flowTime.minusSeconds(600),
                )
                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns listOf(predecessor)
                coEvery {
                    krakenService.getOHLC(
                        pair = "SOLUSD",
                        interval = PortfolioAnalyzerImpl.HISTORICAL_OHLC_INTERVAL_MINUTES,
                        since = flowTime.minusSeconds(86400).epochSecond,
                    )
                } returns listOf(
                    // A candle starting at the event is still open and must be rejected.
                    flowTime.epochSecond to BigDecimal("1.00"),
                    // The completed candle ending exactly at the event is valid.
                    flowTime.minusSeconds(900).epochSecond to BigDecimal("150.00"),
                    // Future candles must never be selected.
                    flowTime.plusSeconds(1).epochSecond to BigDecimal("999.00"),
                )

                val result = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("10750.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = fixedTime,
                )

                result shouldBe AthUpdateResult.Trusted(BigDecimal.ZERO)
                coVerify(exactly = 2) {
                    krakenService.getOHLC(
                        pair = "SOLUSD",
                        interval = PortfolioAnalyzerImpl.HISTORICAL_OHLC_INTERVAL_MINUTES,
                        since = flowTime.minusSeconds(86400).epochSecond,
                    )
                }
                coVerify(exactly = 0) { krakenService.getTickerPrices(any()) }
                coVerify {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match { it.allTimeHigh.compareTo(BigDecimal("10750.00")) == 0 },
                        any(),
                        any(),
                    )
                }
            }
        }

        "updateAth scales ATH using net contribution for confirmed external crypto deposit with fee" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val analyzerWithRepos = createAnalyzerWithRepos(
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()

                val cryptoDeposit = LedgerEvent(
                    ledgerId = "L-FEE-DEP",
                    refid = "tx-L-FEE-DEP",
                    time = fixedTime.minusSeconds(900),
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "SOL",
                    amount = BigDecimal("10.0"),
                    fee = BigDecimal("0.1"),
                )
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns listOf(cryptoDeposit)

                val snap = PortfolioSnapshot(
                    timestamp = fixedTime.minusSeconds(905),
                    totalValueUSD = BigDecimal("10000.00"),
                    assets = mapOf(
                        "SOL" to TestFixtures.assetSnapshot(
                            symbol = "SOL",
                            balance = BigDecimal("10.0"),
                            price = BigDecimal("150.00"),
                            valueUSD = BigDecimal("1500.00"),
                            targetPercent = BigDecimal("15.0"),
                        ),
                        "USD" to TestFixtures.assetSnapshot(
                            symbol = "USD",
                            balance = BigDecimal("8500.00"),
                            price = BigDecimal.ONE,
                            valueUSD = BigDecimal("8500.00"),
                            targetPercent = BigDecimal("85.0"),
                        ),
                    ),
                    actions = emptyList<String>(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                )
                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns listOf(snap)

                val dd = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("11485.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                )
                dd.shouldBeEqualComparingTo(BigDecimal.ZERO)

                coVerify(exactly = 1) {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match {
                            it.allTimeHigh.compareTo(BigDecimal("11485.00")) == 0
                        },
                        any(),
                        any(),
                    )
                }
            }
        }

        "updateAth scales ATH using net contribution for card-funded buy crypto without fee-induced drawdown" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val analyzerWithRepos = createAnalyzerWithRepos(
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()

                val cardRef = "CARD-BUY-2026-08-01T1145Z"
                val cardTime = fixedTime.minusSeconds(900)
                val cardDeposit = LedgerEvent(
                    ledgerId = "L-CARD-DEP",
                    refid = cardRef,
                    time = cardTime,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("5000.00"),
                    fee = BigDecimal.ZERO,
                )
                val cardSpend = LedgerEvent(
                    ledgerId = "L-CARD-SPEND",
                    refid = cardRef,
                    time = cardTime,
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    asset = "USD",
                    amount = BigDecimal("-4980.00"),
                    fee = BigDecimal("20.00"),
                )
                val cardReceive = LedgerEvent(
                    ledgerId = "L-CARD-RCV",
                    refid = cardRef,
                    time = cardTime,
                    type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                    asset = "BTC",
                    amount = BigDecimal("0.0996"),
                    fee = BigDecimal.ZERO,
                )
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns
                    listOf(cardDeposit, cardSpend, cardReceive)

                val snap = PortfolioSnapshot(
                    timestamp = cardTime.minusSeconds(5),
                    totalValueUSD = BigDecimal("10000.00"),
                    assets = mapOf(
                        "BTC" to TestFixtures.assetSnapshot(
                            symbol = "BTC",
                            balance = BigDecimal("0.10"),
                            price = BigDecimal("50000.00"),
                            valueUSD = BigDecimal("5000.00"),
                            targetPercent = BigDecimal("50.0"),
                        ),
                        "USD" to TestFixtures.assetSnapshot(
                            symbol = "USD",
                            balance = BigDecimal("5000.00"),
                            price = BigDecimal.ONE,
                            valueUSD = BigDecimal("5000.00"),
                            targetPercent = BigDecimal("50.0"),
                        ),
                    ),
                    actions = emptyList<String>(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                )
                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns listOf(snap)

                // Current total portfolio: 10,000 baseline + 4,980 net card funding = 14,980.00
                val dd = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("14980.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                )
                dd.shouldBeEqualComparingTo(BigDecimal.ZERO)

                coVerify(exactly = 1) {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match {
                            it.allTimeHigh.compareTo(BigDecimal("14980.00")) == 0
                        },
                        any(),
                        any(),
                    )
                }
            }
        }

        "production ATH path uses the immutable prepared provenance resolver for classification and normalization" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                var prepareCalls = 0
                val preparedResolver = object : FundingProvenanceResolver {
                    override fun resolve(event: LedgerEvent): FundingEvidence = FundingEvidence.EXTERNAL

                    override fun isCardFunding(event: LedgerEvent): Boolean = true
                }
                val productionShapeResolver = object : FundingProvenanceResolver {
                    override fun resolve(event: LedgerEvent): FundingEvidence = FundingEvidence.UNRESOLVED

                    override suspend fun prepare(events: Collection<LedgerEvent>): FundingProvenanceResolver {
                        prepareCalls++
                        return preparedResolver
                    }
                }
                val analyzerWithRepos = createAnalyzerWithRepos(
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )
                every { configService.getConfig() } returns TestFixtures.config(
                    settings = TestFixtures.settings(),
                    allocations = listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(Asset.USD, 50.0),
                    ),
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()

                val cardRef = "CARD-PREPARED-RESOLVER"
                val cardTime = fixedTime.minusSeconds(900)
                val cardDeposit = LedgerEvent(
                    ledgerId = "prepared-deposit",
                    refid = cardRef,
                    time = cardTime,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("5000.00"),
                    fee = BigDecimal.ZERO,
                )
                val cardSpend = LedgerEvent(
                    ledgerId = "prepared-spend",
                    refid = cardRef,
                    time = cardTime,
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    asset = "USD",
                    amount = BigDecimal("-4980.00"),
                    fee = BigDecimal("20.00"),
                )
                val cardReceive = LedgerEvent(
                    ledgerId = "prepared-receive",
                    refid = cardRef,
                    time = cardTime,
                    type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                    asset = "BTC",
                    amount = BigDecimal("0.0996"),
                    fee = BigDecimal.ZERO,
                )
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns
                    listOf(cardDeposit, cardSpend, cardReceive)
                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns listOf(
                    PortfolioSnapshot(
                        timestamp = cardTime.minusSeconds(5),
                        totalValueUSD = BigDecimal("10000.00"),
                        assets = mapOf(
                            "BTC" to TestFixtures.assetSnapshot(
                                symbol = "BTC",
                                balance = BigDecimal("0.10"),
                                price = BigDecimal("50000.00"),
                                valueUSD = BigDecimal("5000.00"),
                                targetPercent = BigDecimal("50.0"),
                            ),
                            "USD" to TestFixtures.assetSnapshot(
                                symbol = "USD",
                                balance = BigDecimal("5000.00"),
                                price = BigDecimal.ONE,
                                valueUSD = BigDecimal("5000.00"),
                                targetPercent = BigDecimal("50.0"),
                            ),
                        ),
                        actions = emptyList<String>(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    ),
                )

                val result = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("14980.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = fixedTime,
                    provenanceResolver = productionShapeResolver,
                )

                result shouldBe AthUpdateResult.Trusted(BigDecimal.ZERO)
                prepareCalls shouldBe 1
            }
        }

        "priceOwnerCapitalFlow preserves passthrough fee direction and representative identity" {
            runTest {
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val analyzerWithRepos = createAnalyzerWithRepos(tradeRepository = mockTrades)
                val deposit = LedgerEvent(
                    ledgerId = "passthrough-deposit",
                    refid = "PASSTHROUGH-DEPOSIT",
                    time = fixedTime,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("1000.00"),
                )
                val spend = LedgerEvent(
                    ledgerId = "passthrough-spend",
                    refid = deposit.refid,
                    time = fixedTime.plusMillis(100),
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    asset = "USD",
                    amount = BigDecimal("-990.00"),
                    fee = BigDecimal("10.00"),
                )
                val depositGroup = listOf(deposit, spend)

                analyzerWithRepos.priceOwnerCapitalFlow(
                    deposit,
                    balancesObservedAt = null,
                    tradesRepo = mockTrades,
                    allRetained = depositGroup,
                ).shouldBeEqualComparingTo(BigDecimal("990.00"))
                analyzerWithRepos.priceOwnerCapitalFlow(
                    spend,
                    balancesObservedAt = null,
                    tradesRepo = mockTrades,
                    allRetained = depositGroup,
                ).shouldBeEqualComparingTo(BigDecimal("-1000.00"))

                val withdrawal = LedgerEvent(
                    ledgerId = "passthrough-withdrawal",
                    refid = "PASSTHROUGH-WITHDRAWAL",
                    time = fixedTime,
                    type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                    asset = "USD",
                    amount = BigDecimal("-1000.00"),
                )
                val receive = LedgerEvent(
                    ledgerId = "passthrough-receive",
                    refid = withdrawal.refid,
                    time = fixedTime.plusMillis(100),
                    type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                    asset = "USD",
                    amount = BigDecimal("990.00"),
                    fee = BigDecimal("10.00"),
                )
                analyzerWithRepos.priceOwnerCapitalFlow(
                    withdrawal,
                    balancesObservedAt = null,
                    tradesRepo = mockTrades,
                    allRetained = listOf(withdrawal, receive),
                ).shouldBeEqualComparingTo(BigDecimal("-990.00"))

                analyzerWithRepos.priceOwnerCapitalFlow(
                    deposit.copy(refid = null),
                    balancesObservedAt = null,
                    tradesRepo = mockTrades,
                ).shouldBeEqualComparingTo(BigDecimal("1000.00"))
                analyzerWithRepos.priceOwnerCapitalFlow(
                    deposit,
                    balancesObservedAt = null,
                    tradesRepo = mockTrades,
                    allRetained = emptyList(),
                ).shouldBeEqualComparingTo(BigDecimal("1000.00"))
                analyzerWithRepos.priceOwnerCapitalFlow(
                    deposit.copy(refid = " "),
                    balancesObservedAt = null,
                    tradesRepo = mockTrades,
                    allRetained = emptyList(),
                ).shouldBeEqualComparingTo(BigDecimal("1000.00"))
                analyzerWithRepos.priceOwnerCapitalFlow(
                    deposit,
                    balancesObservedAt = null,
                    tradesRepo = mockTrades,
                    allRetained = listOf(deposit.copy(refid = "OTHER-REF")),
                ).shouldBeEqualComparingTo(BigDecimal("1000.00"))
                analyzerWithRepos.priceOwnerCapitalFlow(
                    deposit,
                    balancesObservedAt = null,
                    tradesRepo = mockTrades,
                    allRetained = listOf(deposit.copy(refid = null)),
                ).shouldBeEqualComparingTo(BigDecimal("1000.00"))
                analyzerWithRepos.priceOwnerCapitalFlow(
                    spend,
                    balancesObservedAt = null,
                    tradesRepo = mockTrades,
                    allRetained = listOf(spend),
                ).shouldBeEqualComparingTo(BigDecimal("-1000.00"))
            }
        }

        "isLinkedPassthroughLeg requires a linked funding refid" {
            val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
            val analyzerWithRepos = createAnalyzerWithRepos()
            val deposit = LedgerEvent(
                ledgerId = "linked-deposit",
                refid = "LINKED-REF",
                time = fixedTime,
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "USD",
                amount = BigDecimal("1000.00"),
            )
            val spend = LedgerEvent(
                ledgerId = "linked-spend",
                refid = "LINKED-REF",
                time = fixedTime.plusMillis(100),
                type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                asset = "USD",
                amount = BigDecimal("-1000.00"),
            )

            analyzerWithRepos.isLinkedPassthroughLeg(spend, listOf(deposit, spend, spend.copy(refid = null))) shouldBe
                true
            analyzerWithRepos.isLinkedPassthroughLeg(spend, listOf(spend)) shouldBe false
            analyzerWithRepos.isLinkedPassthroughLeg(spend.copy(refid = "  "), listOf(deposit, spend)) shouldBe false
            analyzerWithRepos.isLinkedPassthroughLeg(deposit, listOf(deposit, spend)) shouldBe false
        }

        "crypto flow pricing filters invalid trades and snapshots before fallback" {
            runTest {
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val analyzerWithRepos = createAnalyzerWithRepos(
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )
                val flow = LedgerEvent(
                    ledgerId = "crypto-price-flow",
                    time = fixedTime,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "BTC",
                    amount = BigDecimal("0.10000000"),
                )
                coEvery { mockTrades.getTradesInRange(any(), any()) } returns listOf(
                    TestFixtures.tradeRecord(
                        timestamp = fixedTime.plusSeconds(1),
                        pair = "XXBTZUSD",
                        side = "buy",
                        symbol = "BTC",
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("5000.00"),
                    ),
                    TestFixtures.tradeRecord(
                        timestamp = fixedTime.minusSeconds(30),
                        pair = "XXBTZUSD",
                        side = "buy",
                        symbol = "BTC",
                        volume = BigDecimal.ZERO,
                        usdAmount = BigDecimal("5000.00"),
                        success = false,
                    ),
                    TestFixtures.tradeRecord(
                        timestamp = fixedTime.minusSeconds(30),
                        pair = "XXETHZUSD",
                        side = "buy",
                        symbol = "ETH",
                        volume = BigDecimal("1.0"),
                        usdAmount = BigDecimal("5000.00"),
                        dryRun = true,
                    ),
                    TestFixtures.tradeRecord(
                        timestamp = fixedTime.minusSeconds(181),
                        pair = "XXBTZUSD",
                        side = "buy",
                        symbol = "BTC",
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("5000.00"),
                    ),
                    TestFixtures.tradeRecord(
                        timestamp = fixedTime.minusSeconds(10),
                        pair = "XXBTZUSD",
                        side = "buy",
                        symbol = "BTC",
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("5000.00"),
                    ),
                )
                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns emptyList()

                analyzerWithRepos.priceOwnerCapitalFlow(
                    flow,
                    balancesObservedAt = fixedTime,
                    tradesRepo = mockTrades,
                ).shouldBeEqualComparingTo(BigDecimal("5000.00"))

                coEvery { mockTrades.getTradesInRange(any(), any()) } returns emptyList()
                val invalidSnapshot = TestFixtures.emptySnapshot(fixedTime.minusSeconds(100), BigDecimal.ZERO)
                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns listOf(
                    invalidSnapshot.copy(balancesObservedAt = fixedTime.plusSeconds(1)),
                    invalidSnapshot.copy(timestamp = fixedTime.plusSeconds(1)),
                    invalidSnapshot.copy(
                        timestamp = fixedTime.minusSeconds(90),
                        balancesObservedAt = fixedTime.minusSeconds(90),
                        totalValueUSD = BigDecimal("5000.00"),
                        assets = mapOf(
                            "BTC" to TestFixtures.assetSnapshot(
                                symbol = "BTC",
                                balance = BigDecimal("0.10"),
                                price = BigDecimal("50000.00"),
                                valueUSD = BigDecimal("5000.00"),
                                targetPercent = BigDecimal("100.0"),
                            ),
                        ),
                    ),
                )

                analyzerWithRepos.priceOwnerCapitalFlow(
                    flow,
                    balancesObservedAt = fixedTime,
                    tradesRepo = mockTrades,
                ).shouldBeEqualComparingTo(BigDecimal("5000.00"))

                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns listOf(
                    invalidSnapshot.copy(timestamp = fixedTime.minusSeconds(70)),
                    invalidSnapshot.copy(
                        timestamp = fixedTime.minusSeconds(80),
                        balancesObservedAt = fixedTime.minusSeconds(80),
                        assets = mapOf(
                            "BTC" to TestFixtures.assetSnapshot(
                                symbol = "BTC",
                                balance = BigDecimal("0.10"),
                                price = BigDecimal.ZERO,
                                valueUSD = BigDecimal.ZERO,
                                targetPercent = BigDecimal("100.0"),
                            ),
                        ),
                    ),
                )
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns listOf(
                    fixedTime.minusSeconds(900).epochSecond to BigDecimal("50000.00"),
                )
                analyzerWithRepos.priceOwnerCapitalFlow(
                    flow,
                    balancesObservedAt = fixedTime,
                    tradesRepo = mockTrades,
                ).shouldBeEqualComparingTo(BigDecimal("5000.00"))

                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getOHLC(any(), any(), any()) } returns listOf(
                    fixedTime.minusSeconds(86401).epochSecond to BigDecimal("70000.00"),
                    fixedTime.minusSeconds(60).epochSecond to BigDecimal("60000.00"),
                    fixedTime.minusSeconds(900).epochSecond to BigDecimal.ZERO,
                )
                shouldThrow<AthTrustFailureException> {
                    analyzerWithRepos.priceOwnerCapitalFlow(
                        flow,
                        balancesObservedAt = fixedTime,
                        tradesRepo = mockTrades,
                    )
                }
            }
        }

        "updateAth scales ATH using net contribution for card buy crypto with offset and crypto fee" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val analyzerWithRepos = createAnalyzerWithRepos(
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()

                val cardRef = "CARD-BUY-OFFSET-2026"
                val cardTime = fixedTime.minusSeconds(900)
                val cardDeposit = LedgerEvent(
                    ledgerId = "L-CARD-DEP",
                    refid = cardRef,
                    time = cardTime,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("5000.00"),
                    fee = BigDecimal.ZERO,
                )
                val cardSpend = LedgerEvent(
                    ledgerId = "L-CARD-SPEND",
                    refid = cardRef,
                    time = cardTime.plusMillis(300),
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    asset = "USD",
                    amount = BigDecimal("-4980.00"),
                    fee = BigDecimal("20.00"),
                )
                val cardReceive = LedgerEvent(
                    ledgerId = "L-CARD-RCV",
                    refid = cardRef,
                    time = cardTime.plusSeconds(30),
                    type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                    asset = "BTC",
                    amount = BigDecimal("0.0996"),
                    fee = BigDecimal("0.0001"),
                )
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns
                    listOf(cardDeposit, cardSpend, cardReceive)

                val snap = PortfolioSnapshot(
                    timestamp = cardTime.minusSeconds(5),
                    totalValueUSD = BigDecimal("10000.00"),
                    assets = mapOf(
                        "BTC" to TestFixtures.assetSnapshot(
                            symbol = "BTC",
                            balance = BigDecimal("0.10"),
                            price = BigDecimal("50000.00"),
                            valueUSD = BigDecimal("5000.00"),
                            targetPercent = BigDecimal("50.0"),
                        ),
                        "USD" to TestFixtures.assetSnapshot(
                            symbol = "USD",
                            balance = BigDecimal("5000.00"),
                            price = BigDecimal.ONE,
                            valueUSD = BigDecimal("5000.00"),
                            targetPercent = BigDecimal("50.0"),
                        ),
                    ),
                    actions = emptyList<String>(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                )
                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns listOf(snap)
                coEvery { mockTrades.getTradesInRange(any(), any()) } returns listOf(
                    TestFixtures.tradeRecord(
                        timestamp = cardTime.minusSeconds(4),
                        pair = "USDUSD",
                        side = TestFixtures.BUY,
                        symbol = "USD",
                        volume = BigDecimal.ZERO,
                        usdAmount = BigDecimal.ZERO,
                    ),
                )

                val provenance = SimpleFundingProvenanceResolver(
                    deposits = listOf(
                        DepositStatusRecord(
                            refid = cardRef,
                            asset = "USD",
                            amount = BigDecimal("5000.00"),
                            time = cardTime,
                            status = "Success",
                            method = "Visa",
                        ),
                    ),
                )

                // Gross 5000 - spend fee $20 - crypto fee (0.0001 * 50k = $5) = $4,975 net capital
                // Total portfolio: 10,000 + 4,975 = 14,975.00
                val result = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("14975.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = fixedTime,
                    provenanceResolver = provenance,
                )
                (result as AthUpdateResult.Trusted).drawdownPct.shouldBeEqualComparingTo(BigDecimal.ZERO)

                coVerify(exactly = 1) {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match {
                            it.allTimeHigh.compareTo(BigDecimal("14975.00")) == 0
                        },
                        any(),
                        any(),
                    )
                }
            }
        }

        "updateAth defers with AMBIGUOUS_FUNDING when card buy crypto is missing receive leg" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val analyzerWithRepos = createAnalyzerWithRepos(
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()

                val cardRef = "CARD-INCOMPLETE"
                val cardTime = fixedTime.minusSeconds(900)
                val cardDeposit = LedgerEvent(
                    ledgerId = "L-CARD-DEP",
                    refid = cardRef,
                    time = cardTime,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("5000.00"),
                    fee = BigDecimal.ZERO,
                )
                val cardSpend = LedgerEvent(
                    ledgerId = "L-CARD-SPEND",
                    refid = cardRef,
                    time = cardTime,
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    asset = "USD",
                    amount = BigDecimal("-4980.00"),
                    fee = BigDecimal("20.00"),
                )
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns
                    listOf(cardDeposit, cardSpend)

                val provenance = SimpleFundingProvenanceResolver(
                    deposits = listOf(
                        DepositStatusRecord(
                            refid = cardRef,
                            asset = "USD",
                            amount = BigDecimal("5000.00"),
                            time = cardTime,
                            status = "Success",
                            method = "Visa",
                        ),
                    ),
                )

                val result = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("15000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = fixedTime,
                    provenanceResolver = provenance,
                )
                result shouldBe AthUpdateResult.Deferred(
                    lastTrustedDrawdownPct = null,
                    reason = AthTrustFailureReason.AMBIGUOUS_FUNDING,
                )
            }
        }

        "updateAth defers with HISTORICAL_PRICE_UNAVAILABLE when card crypto fee cannot be priced" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val analyzerWithRepos = createAnalyzerWithRepos(
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()

                val cardRef = "CARD-UNPRICEABLE"
                val cardTime = fixedTime.minusSeconds(900)
                val cardDeposit = LedgerEvent(
                    ledgerId = "L-CARD-DEP",
                    refid = cardRef,
                    time = cardTime,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("5000.00"),
                    fee = BigDecimal.ZERO,
                )
                val cardSpend = LedgerEvent(
                    ledgerId = "L-CARD-SPEND",
                    refid = cardRef,
                    time = cardTime,
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    asset = "USD",
                    amount = BigDecimal("-4980.00"),
                    fee = BigDecimal("20.00"),
                )
                val cardReceive = LedgerEvent(
                    ledgerId = "L-CARD-RCV",
                    refid = cardRef,
                    time = cardTime,
                    type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                    asset = "BTC",
                    amount = BigDecimal("0.0996"),
                    fee = BigDecimal("0.0001"),
                )
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns
                    listOf(cardDeposit, cardSpend, cardReceive)

                // No snapshot returned to price BTC at cardTime
                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getTickerPrices(any()) } throws RuntimeException("Ticker unavailable")

                val provenance = SimpleFundingProvenanceResolver(
                    deposits = listOf(
                        DepositStatusRecord(
                            refid = cardRef,
                            asset = "USD",
                            amount = BigDecimal("5000.00"),
                            time = cardTime,
                            status = "Success",
                            method = "Visa",
                        ),
                    ),
                )

                val result = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("15000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = fixedTime,
                    provenanceResolver = provenance,
                )
                result shouldBe AthUpdateResult.Deferred(
                    lastTrustedDrawdownPct = null,
                    reason = AthTrustFailureReason.HISTORICAL_PRICE_UNAVAILABLE,
                )
            }
        }

        "updateAth scales ATH using net capital for card-funded withdrawal with linked fee" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val analyzerWithRepos = createAnalyzerWithRepos(
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()

                val cardRef = "CARD-WITHDRAW-2026-08-01T1145Z"
                val cardTime = fixedTime.minusSeconds(900)
                val cardWithdrawal = LedgerEvent(
                    ledgerId = "L-CARD-WTH",
                    refid = cardRef,
                    time = cardTime,
                    type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                    asset = "USD",
                    amount = BigDecimal("-1000.00"),
                    fee = BigDecimal.ZERO,
                )
                val cardReceive = LedgerEvent(
                    ledgerId = "L-CARD-RCV",
                    refid = cardRef,
                    time = cardTime,
                    type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                    asset = "USD",
                    amount = BigDecimal("1000.00"),
                    fee = BigDecimal("20.00"),
                )
                val cardSpend = LedgerEvent(
                    ledgerId = "L-CARD-SPEND",
                    refid = cardRef,
                    time = cardTime,
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    asset = "BTC",
                    amount = BigDecimal("-0.02"),
                    fee = BigDecimal.ZERO,
                )
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns
                    listOf(cardWithdrawal, cardReceive, cardSpend)

                val snap = PortfolioSnapshot(
                    timestamp = cardTime.minusSeconds(5),
                    totalValueUSD = BigDecimal("10000.00"),
                    assets = mapOf(
                        "BTC" to TestFixtures.assetSnapshot(
                            symbol = "BTC",
                            balance = BigDecimal("0.10"),
                            price = BigDecimal("50000.00"),
                            valueUSD = BigDecimal("5000.00"),
                            targetPercent = BigDecimal("50.0"),
                        ),
                        "USD" to TestFixtures.assetSnapshot(
                            symbol = "USD",
                            balance = BigDecimal("5000.00"),
                            price = BigDecimal.ONE,
                            valueUSD = BigDecimal("5000.00"),
                            targetPercent = BigDecimal("50.0"),
                        ),
                    ),
                    actions = emptyList<String>(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                )
                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns listOf(snap)

                // -1000 withdrawal + 20 fee = -980 net outflow; portfolio becomes 9,020
                val dd = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("9020.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                )
                dd.shouldBeEqualComparingTo(BigDecimal.ZERO)

                coVerify(exactly = 1) {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match {
                            it.allTimeHigh.compareTo(BigDecimal("9020.00")) == 0
                        },
                        any(),
                        any(),
                    )
                }
            }
        }

        // Regression: the removed getSnapshotBefore fallback could have served a snapshot
        // hours or days old, silently corrupting ATH valuations. After the fix, only
        // snapshots within ±180s are accepted; anything older must fall through to ticker.
        "crypto flow pricing rejects snapshot outside ±180s window and falls through to ticker" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val analyzerWithRepos = createAnalyzerWithRepos(
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()

                val cryptoDeposit = LedgerEvent(
                    ledgerId = "L3",
                    refid = "tx-L3",
                    time = fixedTime.minusSeconds(240),
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "SOL",
                    amount = BigDecimal("5.0"),
                    fee = BigDecimal.ZERO,
                )
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns listOf(cryptoDeposit)
                // The snapshot is 1800 seconds away from the event — outside the 180s window
                val staleSnap = PortfolioSnapshot(
                    timestamp = fixedTime.minusSeconds(1800),
                    totalValueUSD = BigDecimal("9000.00"),
                    assets = mapOf(
                        "SOL" to TestFixtures.assetSnapshot(
                            symbol = "SOL",
                            balance = BigDecimal("10.0"),
                            price = BigDecimal("1.00"), // wrong price - must NOT be used
                            valueUSD = BigDecimal("10.00"),
                            targetPercent = BigDecimal("10.0"),
                        ),
                    ),
                    actions = emptyList<String>(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                )
                // A defensive provider-boundary check: even if a repository implementation returns
                // an out-of-range row, the historical resolver must not use it as a nearby price.
                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns listOf(staleSnap)

                // Ticker provides the correct price
                coEvery { krakenService.getTickerPrices("SOLUSD") } returns mapOf("SOLUSD" to BigDecimal("150.00"))

                val dd = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("11000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                )
                dd.shouldBeEqualComparingTo(BigDecimal.ZERO)
                // Ticker was queried because the stale snapshot was rejected by the resolver's bounds.
                coVerify(exactly = 2) { krakenService.getTickerPrices("SOLUSD") }
            }
        }

        "crypto flow pricing fails closed when ticker returns zero price or cancellation occurs" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val analyzerWithRepos = createAnalyzerWithRepos(
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()

                val cryptoDeposit = LedgerEvent(
                    ledgerId = "L2",
                    refid = "tx-L2",
                    time = fixedTime.minusSeconds(120),
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "SOL",
                    amount = BigDecimal("10.0"),
                    fee = BigDecimal.ZERO,
                )
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns listOf(cryptoDeposit)
                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns emptyList()

                coEvery { krakenService.getTickerPrices("SOLUSD") } returns mapOf("SOLUSD" to BigDecimal.ZERO)
                val zeroPrice = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("10000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = fixedTime,
                )
                zeroPrice shouldBe AthUpdateResult.Deferred(null, AthTrustFailureReason.HISTORICAL_PRICE_UNAVAILABLE)

                coEvery { krakenService.getTickerPrices("SOLUSD") } throws
                    CancellationException("ticker cancelled")
                shouldThrow<CancellationException> {
                    analyzerWithRepos.updateAthAndCalculateDrawdown(
                        totalPortfolioValueUSD = BigDecimal("10000.00"),
                        netExternalFlowUSD = BigDecimal.ZERO,
                    )
                }
            }
        }

        "ledger coverage watermark missing halts ATH flow calculation safely without advancing watermark" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val analyzerWithRepos = PortfolioAnalyzerImpl(
                    krakenService = krakenService,
                    configService = configService,
                    portfolioStatsRepository = portfolioStatsRepository,
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns null

                val dd = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("10000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                )
                dd.shouldBeEqualComparingTo(BigDecimal.ZERO)
                coVerify(exactly = 0) {
                    mockTrades.setSyncMetadata(any(), any())
                    mockLedgers.getLedgersInRange(any(), any())
                }
            }
        }

        "initial ATH defers when dated balances are not covered by ledger history" {
            runTest {
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val unknownLedgers = mockk<LedgerRepository>(relaxed = true)
                val unknownTrades = mockk<TradeRepository>(relaxed = true)
                val unknownCoverageAnalyzer = createAnalyzerWithRepos(
                    ledgerRepository = unknownLedgers,
                    tradeRepository = unknownTrades,
                    nowProvider = { fixedTime },
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal.ZERO)
                coEvery {
                    unknownLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns null

                unknownCoverageAnalyzer.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("10000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = fixedTime,
                ) shouldBe AthUpdateResult.Deferred(null, AthTrustFailureReason.LEDGER_COVERAGE_UNKNOWN)

                val staleLedgers = mockk<LedgerRepository>(relaxed = true)
                val staleTrades = mockk<TradeRepository>(relaxed = true)
                val staleCoverageAnalyzer = createAnalyzerWithRepos(
                    ledgerRepository = staleLedgers,
                    tradeRepository = staleTrades,
                    nowProvider = { fixedTime },
                )
                coEvery {
                    staleLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(1).epochSecond.toString()

                staleCoverageAnalyzer.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("10000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                    balancesObservedAt = fixedTime,
                ) shouldBe AthUpdateResult.Deferred(null, AthTrustFailureReason.LEDGER_COVERAGE_STALE)
            }
        }

        "uncertain balance-observation assignments defer on unsafe boundary evidence" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val observation = Instant.parse("2026-08-01T12:00:00Z")
                val flowTime = observation.plusSeconds(600)
                val analyzerWithRepos = createAnalyzerWithRepos(
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { flowTime },
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns flowTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns observation.minusSeconds(3600).epochSecond.toString()
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_JOURNAL_MIGRATED)
                } returns "true"
                coEvery { portfolioStatsRepository.getAppliedAthFlowIds(any()) } returns emptySet()
                coEvery { mockTrades.getTradesInRange(any(), any()) } returns emptyList()

                val deposit = LedgerEvent(
                    ledgerId = "BOUNDARY-DEPOSIT",
                    refid = "BOUNDARY-DEPOSIT-REF",
                    time = flowTime,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("1000.00"),
                )
                fun predecessor(balance: String = "1.0") = PortfolioSnapshot(
                    timestamp = observation.plusSeconds(3),
                    totalValueUSD = BigDecimal("10000.00"),
                    assets = mapOf(
                        "BTC" to TestFixtures.assetSnapshot(
                            symbol = "BTC",
                            balance = BigDecimal(balance),
                            price = BigDecimal("10000.00"),
                            valueUSD = BigDecimal(balance).multiply(BigDecimal("10000.00")),
                            targetPercent = BigDecimal("100.0"),
                        ),
                    ),
                    actions = emptyList(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal("100.0"),
                    balancesObservedAt = observation,
                )

                suspend fun assertDeferred(rows: List<LedgerEvent>, snapshot: PortfolioSnapshot) {
                    coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns rows + deposit
                    coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns listOf(snapshot)

                    analyzerWithRepos.updateAthAndCalculateDrawdown(
                        totalPortfolioValueUSD = BigDecimal("11000.00"),
                        netExternalFlowUSD = BigDecimal.ZERO,
                        balancesObservedAt = flowTime,
                    ) shouldBe AthUpdateResult.Deferred(
                        null,
                        AthTrustFailureReason.BALANCE_OBSERVATION_UNCERTAIN,
                    )
                }

                assertDeferred(
                    rows = listOf(
                        LedgerEvent(
                            ledgerId = "MISSING-BALANCE",
                            time = observation.plusSeconds(1),
                            type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                            asset = "BTC",
                            amount = BigDecimal("0.1"),
                            balance = BigDecimal("1.1"),
                        ),
                    ),
                    snapshot = predecessor(),
                )
                assertDeferred(
                    rows = listOf(
                        LedgerEvent(
                            ledgerId = "SAME-TIME-1",
                            time = observation.plusSeconds(1),
                            type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                            asset = "BTC",
                            amount = BigDecimal("0.1"),
                            balance = BigDecimal("1.1"),
                            hasAuthoritativeBalance = true,
                        ),
                        LedgerEvent(
                            ledgerId = "SAME-TIME-2",
                            time = observation.plusSeconds(1),
                            type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                            asset = "BTC",
                            amount = BigDecimal("0.1"),
                            balance = BigDecimal("1.2"),
                            hasAuthoritativeBalance = true,
                        ),
                    ),
                    snapshot = predecessor(),
                )
                assertDeferred(
                    rows = listOf(
                        LedgerEvent(
                            ledgerId = "INCONSISTENT-CHAIN",
                            time = observation.plusSeconds(1),
                            type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                            asset = "BTC",
                            amount = BigDecimal("0.1"),
                            balance = BigDecimal("1.3"),
                            hasAuthoritativeBalance = true,
                        ),
                    ),
                    snapshot = predecessor(),
                )
                assertDeferred(
                    rows = listOf(
                        LedgerEvent(
                            ledgerId = "AMBIGUOUS-CUTOFF",
                            time = observation.plusSeconds(1),
                            type = KrakenApiConstants.LEDGER_TYPE_STAKING,
                            asset = "BTC",
                            amount = BigDecimal.ZERO,
                            balance = BigDecimal("1.0"),
                            hasAuthoritativeBalance = true,
                        ),
                    ),
                    snapshot = predecessor(),
                )
            }
        }

        "deposit occurring after ledger sync is not skipped when rebalance runs before next ledger sync" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val t0 = Instant.parse("2026-08-01T12:00:00Z")
                var currentTime = t0
                val analyzerWithRepos = createAnalyzerWithRepos(
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { currentTime },
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))

                // Cycle 1 at T=100s, ledger coverage is at T=60s. Initial watermark set to 60s.
                currentTime = t0.plusSeconds(100)
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns t0.plusSeconds(60).epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns null

                analyzerWithRepos.updateAthAndCalculateDrawdown(BigDecimal("10000.00"), BigDecimal.ZERO)
                coVerify(exactly = 1) {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        any(),
                        emptyList(),
                        t0.plusSeconds(60).epochSecond,
                    )
                }

                // Cycle 2 at T=120s. Deposit occurred on Kraken at T=70s, but ledger sync hasn't fetched it yet.
                // Ledger coverage is STILL at T=60s. ATH watermark is at T=60s.
                // Range query (60..60] is empty. Watermark does NOT advance past 60s.
                currentTime = t0.plusSeconds(120)
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns t0.plusSeconds(60).epochSecond.toString()
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns emptyList()

                analyzerWithRepos.updateAthAndCalculateDrawdown(BigDecimal("10000.00"), BigDecimal.ZERO)

                // Now ledger sync runs and imports deposit at T=70s ($5000), advancing coverage to T=150s.
                // Cycle 3 at T=160s: the identity scan reads every retained row up to coverage
                // (EPOCH..150], not just (watermark..coverage], so the late deposit is decided once.
                currentTime = t0.plusSeconds(160)
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns t0.plusSeconds(150).epochSecond.toString()
                val depositAt70 = LedgerEvent(
                    ledgerId = "L70",
                    refid = "FT-L70",
                    time = t0.plusSeconds(70),
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("5000.00"),
                    fee = BigDecimal.ZERO,
                )
                coEvery {
                    mockLedgers.getLedgersInRange(any(), any())
                } returns listOf(depositAt70)
                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns
                    listOf(TestFixtures.emptySnapshot(t0.plusSeconds(60), BigDecimal("10000.00")))

                analyzerWithRepos.updateAthAndCalculateDrawdown(BigDecimal("15000.00"), BigDecimal.ZERO)

                // Verified: ATH was scaled for the deposit at 70s, the flow
                // identity checkpointed, and watermark advanced to 150s!
                coVerify {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match { it.allTimeHigh.compareTo(BigDecimal("15000.00")) == 0 },
                        match { applied -> applied.map { it.ledgerId } == listOf("L70") },
                        t0.plusSeconds(150).epochSecond,
                    )
                }
                coVerify(exactly = 0) { mockTrades.setSyncMetadata(any(), any()) }
            }
        }

        "staking rewards improve portfolio value and reduce drawdown without scaling ATH" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val analyzerWithRepos = PortfolioAnalyzerImpl(
                    krakenService = krakenService,
                    configService = configService,
                    portfolioStatsRepository = portfolioStatsRepository,
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )
                // ATH = 100,000. Pre-flow portfolio = 80,000 (20% drawdown).
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("100000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()

                // Staking reward of $10,000 USD increases portfolio to 90,000
                val stakingReward = LedgerEvent(
                    ledgerId = "S1",
                    time = fixedTime.minusSeconds(1800),
                    type = "staking",
                    asset = "USD",
                    amount = BigDecimal("10000.00"),
                    fee = BigDecimal.ZERO,
                )
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns listOf(stakingReward)

                // Total portfolio is now 90,000.
                // Because staking is NOT in OWNER_CAPITAL_TYPES, netExternalFlow is 0.
                // ATH is NOT scaled and remains 100,000.
                // Drawdown is (100,000 - 90,000) / 100,000 = 10.0000%!
                val dd = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("90000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                )
                dd.shouldBeEqualComparingTo(BigDecimal("10.0000"))
                coVerify {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match {
                            it.allTimeHigh.compareTo(BigDecimal("100000.00")) == 0
                        },
                        any(),
                        any(),
                    )
                }
            }
        }

        "proportional scaling on deposit and withdrawal preserves economic drawdown percentage" {
            runTest {
                // Scenario A: Deposit of $20,000 into a portfolio at $80,000 with $100,000 ATH (20% DD)
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("100000.00"))
                // New total = 100,000, flow = 20,000 -> preFlow = 80,000
                // scaled ATH = 100,000 * (100,000 / 80,000) = 125,000
                // new DD = (125,000 - 100,000) / 125,000 = 20.0000%
                val ddDeposit = analyzer.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("100000.00"),
                    netExternalFlowUSD = BigDecimal("20000.00"),
                )
                ddDeposit.shouldBeEqualComparingTo(BigDecimal("20.0000"))
                coVerify {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match {
                            it.allTimeHigh.compareTo(BigDecimal("125000.00")) == 0
                        },
                        any(),
                        any(),
                    )
                }

                // Scenario B: Withdrawal of $20,000 from a portfolio at $80,000 with $100,000 ATH (20% DD)
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("100000.00"))
                // New total = 60,000, flow = -20,000 -> preFlow = 80,000
                // scaled ATH = 100,000 * (60,000 / 80,000) = 75,000
                // new DD = (75,000 - 60,000) / 75,000 = 20.0000%
                val ddWithdrawal = analyzer.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("60000.00"),
                    netExternalFlowUSD = BigDecimal("-20000.00"),
                )
                ddWithdrawal.shouldBeEqualComparingTo(BigDecimal("20.0000"))
                coVerify {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match {
                            it.allTimeHigh.compareTo(BigDecimal("75000.00")) == 0
                        },
                        any(),
                        any(),
                    )
                }
            }
        }

        "updateAth leaves ATH unchanged when pre-flow value is non-positive" {
            runTest {
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))

                // Total 5000, flow 6000 -> preFlowValue = -1000 <= 0 -> adjustAthForCashFlow returns 10000.
                val dd = analyzer.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("5000.00"),
                    netExternalFlowUSD = BigDecimal("6000.00"),
                )
                dd.shouldBeEqualComparingTo(BigDecimal("50.0000"))
            }
        }

        "updateAth does not advance watermark when portfolioStatsRepository save throws" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val mockStatsRepo = mockk<PortfolioStatsRepository>()
                coEvery { mockStatsRepo.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery { mockStatsRepo.saveAthStateWithFlowCheckpoint(any(), any(), any()) } throws
                    RuntimeException("disk full")
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()

                val analyzerWithFailingSave = PortfolioAnalyzerImpl(
                    krakenService = krakenService,
                    configService = configService,
                    portfolioStatsRepository = mockStatsRepo,
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )

                io.kotest.assertions.throwables.shouldThrow<RuntimeException> {
                    analyzerWithFailingSave.updateAthAndCalculateDrawdown(
                        totalPortfolioValueUSD = BigDecimal("10000.00"),
                        netExternalFlowUSD = BigDecimal.ZERO,
                    )
                }

                coVerify(exactly = 0) {
                    mockTrades.setSyncMetadata(
                        SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                        any(),
                    )
                }
            }
        }

        "updateAth does not initialize watermark when portfolioStatsRepository save throws and watermark was missing" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val mockStatsRepo = mockk<PortfolioStatsRepository>()
                coEvery { mockStatsRepo.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery { mockStatsRepo.saveAthStateWithFlowCheckpoint(any(), any(), any()) } throws
                    RuntimeException("disk full")
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns null

                val analyzerWithFailingSave = PortfolioAnalyzerImpl(
                    krakenService = krakenService,
                    configService = configService,
                    portfolioStatsRepository = mockStatsRepo,
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )

                io.kotest.assertions.throwables.shouldThrow<RuntimeException> {
                    analyzerWithFailingSave.updateAthAndCalculateDrawdown(
                        totalPortfolioValueUSD = BigDecimal("10000.00"),
                        netExternalFlowUSD = BigDecimal.ZERO,
                    )
                }

                coVerify(exactly = 0) {
                    mockTrades.setSyncMetadata(
                        SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                        any(),
                    )
                }
            }
        }

        "updateAth rescans by identity when the watermark is ahead of coverage without re-applying decided rows" {
            runTest {
                val mockLedgers = mockk<LedgerRepository>(relaxed = true)
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val analyzerWithAheadWatermark = PortfolioAnalyzerImpl(
                    krakenService = krakenService,
                    configService = configService,
                    portfolioStatsRepository = portfolioStatsRepository,
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { fixedTime },
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockLedgers.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_JOURNAL_MIGRATED)
                } returns "true"
                coEvery {
                    mockTrades.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.plusSeconds(10).epochSecond.toString()
                // The watermark is only observability: the scan still reads
                // every retained row, and the decision journal — not the
                // timestamp — is what prevents a second scaling of L-PAST.
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns
                    listOf(
                        LedgerEvent(
                            ledgerId = "L-PAST",
                            refid = "FT-L-PAST",
                            time = fixedTime.minusSeconds(60),
                            type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                            asset = "USD",
                            amount = BigDecimal("5000.00"),
                            fee = BigDecimal.ZERO,
                        ),
                    )
                coEvery { portfolioStatsRepository.getAppliedAthFlowIds(any()) } returns setOf("L-PAST")

                val dd = analyzerWithAheadWatermark.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("10000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                )

                dd.shouldBeEqualComparingTo(BigDecimal.ZERO)
                coVerify { mockLedgers.getLedgersInRange(any(), any()) }
                coVerify(exactly = 1) {
                    portfolioStatsRepository.saveAthStateWithFlowCheckpoint(
                        match { it.allTimeHigh.compareTo(BigDecimal("10000.00")) == 0 },
                        emptyList(),
                        fixedTime.epochSecond,
                    )
                }
            }
        }

        "isLinkedPassthroughLeg correctly identifies spend and receive legs linked to funding" {
            val analyzer = createAnalyzerWithRepos()
            val ref = "CARD-123"
            val deposit = LedgerEvent(
                ledgerId = "L-DEP",
                refid = ref,
                time = Instant.now(),
                type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                asset = "USD",
                amount = BigDecimal("100.00"),
                fee = BigDecimal.ZERO,
            )
            val withdrawal = LedgerEvent(
                ledgerId = "L-WTH",
                refid = "WTH-123",
                time = Instant.now(),
                type = KrakenApiConstants.LEDGER_TYPE_WITHDRAWAL,
                asset = "USD",
                amount = BigDecimal("-100.00"),
                fee = BigDecimal.ZERO,
            )
            val spend = LedgerEvent(
                ledgerId = "L-SPEND",
                refid = ref,
                time = Instant.now(),
                type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                asset = "USD",
                amount = BigDecimal("-100.00"),
                fee = BigDecimal.ZERO,
            )
            val receive = LedgerEvent(
                ledgerId = "L-RCV",
                refid = "WTH-123",
                time = Instant.now(),
                type = KrakenApiConstants.LEDGER_TYPE_RECEIVE,
                asset = "USD",
                amount = BigDecimal("100.00"),
                fee = BigDecimal.ZERO,
            )
            val unlinkedSpend = LedgerEvent(
                ledgerId = "L-UNLINKED",
                refid = "NO-FUNDING",
                time = Instant.now(),
                type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                asset = "USD",
                amount = BigDecimal("-50.00"),
                fee = BigDecimal.ZERO,
            )
            val blankRefidSpend = LedgerEvent(
                ledgerId = "L-BLANK",
                refid = "   ",
                time = Instant.now(),
                type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                asset = "USD",
                amount = BigDecimal("-50.00"),
                fee = BigDecimal.ZERO,
            )

            analyzer.isLinkedPassthroughLeg(deposit, listOf(deposit, spend)) shouldBe false
            analyzer.isLinkedPassthroughLeg(blankRefidSpend, listOf(deposit, spend)) shouldBe false
            analyzer.isLinkedPassthroughLeg(unlinkedSpend, listOf(deposit, spend)) shouldBe false
            analyzer.isLinkedPassthroughLeg(spend, listOf(deposit, spend)) shouldBe true
            analyzer.isLinkedPassthroughLeg(receive, listOf(withdrawal, receive)) shouldBe true
        }

        "priceOwnerCapitalFlow handles ZUSD and non-representative or unlinked card flows" {
            runTest {
                val mockTrades = mockk<TradeRepository>(relaxed = true)
                val analyzer = createAnalyzerWithRepos(tradeRepository = mockTrades)
                val ref = "CARD-456"
                val nowTime = Instant.now()
                val zusdEvent = LedgerEvent(
                    ledgerId = "L-ZUSD",
                    refid = null,
                    time = nowTime,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "ZUSD",
                    amount = BigDecimal("500.00"),
                    fee = BigDecimal.ZERO,
                )
                val unlinkedDeposit = LedgerEvent(
                    ledgerId = "L-UNLINKED-DEP",
                    refid = "PLAIN-REF",
                    time = nowTime,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("250.00"),
                    fee = BigDecimal.ZERO,
                )
                val dep1 = LedgerEvent(
                    ledgerId = "L-DEP-1",
                    refid = ref,
                    time = nowTime,
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("100.00"),
                    fee = BigDecimal.ZERO,
                )
                val dep2 = LedgerEvent(
                    ledgerId = "L-DEP-2",
                    refid = ref,
                    time = nowTime.plusSeconds(1),
                    type = KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("100.00"),
                    fee = BigDecimal.ZERO,
                )
                val spend = LedgerEvent(
                    ledgerId = "L-SPEND",
                    refid = ref,
                    time = nowTime,
                    type = KrakenApiConstants.LEDGER_TYPE_SPEND,
                    asset = "USD",
                    amount = BigDecimal("-100.00"),
                    fee = BigDecimal("5.00"),
                )

                // ZUSD returns delta directly
                analyzer.priceOwnerCapitalFlow(zusdEvent, nowTime, mockTrades, emptyList())
                    .shouldBeEqualComparingTo(BigDecimal("500.00"))

                // USD without passthrough returns delta
                analyzer.priceOwnerCapitalFlow(unlinkedDeposit, nowTime, mockTrades, listOf(unlinkedDeposit))
                    .shouldBeEqualComparingTo(BigDecimal("250.00"))

                // Representative deposit nets fee
                analyzer.priceOwnerCapitalFlow(dep1, nowTime, mockTrades, listOf(dep1, dep2, spend))
                    .shouldBeEqualComparingTo(BigDecimal("95.00"))

                // Non-representative deposit returns un-netted delta
                analyzer.priceOwnerCapitalFlow(dep2, nowTime, mockTrades, listOf(dep1, dep2, spend))
                    .shouldBeEqualComparingTo(BigDecimal("100.00"))
            }
        }
    }
}
