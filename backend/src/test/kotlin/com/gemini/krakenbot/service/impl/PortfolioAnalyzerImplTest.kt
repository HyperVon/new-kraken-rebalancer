package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.PortfolioStatsRepository
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
                coVerify { portfolioStatsRepository.save(match { it.allTimeHigh.compareTo(BigDecimal("1000")) == 0 }) }
            }
        }

        "updateAth raises ATH and returns zero drawdown on new high" {
            runTest {
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("500"))

                val drawdown = analyzer.updateAthAndCalculateDrawdown(BigDecimal("1000"))

                drawdown.shouldBeEqualComparingTo(BigDecimal.ZERO)
                coVerify { portfolioStatsRepository.save(match { it.allTimeHigh.compareTo(BigDecimal("1000")) == 0 }) }
            }
        }

        "updateAth keeps ATH and computes positive drawdown below high" {
            runTest {
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("1000"))

                val drawdown = analyzer.updateAthAndCalculateDrawdown(BigDecimal("900"))

                drawdown.shouldBeEqualComparingTo(BigDecimal("10.0000"))
                coVerify { portfolioStatsRepository.save(match { it.allTimeHigh.compareTo(BigDecimal("1000")) == 0 }) }
            }
        }

        "updateAth rethrows a save failure so the cycle aborts instead of planning on an unpersisted ATH" {
            runTest {
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("1000"))
                coEvery { portfolioStatsRepository.save(any()) } throws RuntimeException("boom")

                shouldThrow<RuntimeException> {
                    analyzer.updateAthAndCalculateDrawdown(BigDecimal("900"))
                }
                coVerify { portfolioStatsRepository.save(match { it.allTimeHigh.compareTo(BigDecimal("1000")) == 0 }) }
            }
        }

        "updateAth rethrows CancellationException from save" {
            runTest {
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal.ZERO)
                coEvery { portfolioStatsRepository.save(any()) } throws CancellationException(null)

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
                    portfolioStatsRepository.save(
                        match {
                            it.allTimeHigh.compareTo(BigDecimal("15000.00")) == 0
                        },
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
                    portfolioStatsRepository.save(
                        match {
                            it.allTimeHigh.compareTo(BigDecimal("7500.00")) == 0
                        },
                    )
                }
            }
        }

        "updateAth with unapplied ledger flows initializes watermark when missing" {
            runTest {
                val mockLedgers = mockk<com.gemini.krakenbot.repository.LedgerRepository>(relaxed = true)
                val mockTrades = mockk<com.gemini.krakenbot.repository.TradeRepository>(relaxed = true)
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
                    mockTrades.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns null

                val dd = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("10000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                )

                dd.shouldBeEqualComparingTo(BigDecimal.ZERO)
                coVerify {
                    mockTrades.setSyncMetadata(
                        com.gemini.krakenbot.model.SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                        fixedTime.epochSecond.toString(),
                    )
                }
            }
        }

        "updateAth with unapplied ledger flows aggregates USD and crypto events" {
            runTest {
                val mockLedgers = mockk<com.gemini.krakenbot.repository.LedgerRepository>(relaxed = true)
                val mockTrades = mockk<com.gemini.krakenbot.repository.TradeRepository>(relaxed = true)
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
                    mockTrades.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()

                val usdDeposit = com.gemini.krakenbot.model.LedgerEvent(
                    ledgerId = "L1",
                    time = fixedTime.minusSeconds(1800),
                    type = com.gemini.krakenbot.model.KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "ZUSD",
                    amount = BigDecimal("2000.00"),
                    fee = BigDecimal.ZERO,
                )
                val btcDeposit = com.gemini.krakenbot.model.LedgerEvent(
                    ledgerId = "L2",
                    time = fixedTime.minusSeconds(900),
                    type = com.gemini.krakenbot.model.KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "XXBT",
                    amount = BigDecimal("0.10000000"),
                    fee = BigDecimal.ZERO,
                )
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns listOf(usdDeposit, btcDeposit)
                coEvery { krakenService.getTickerPrices("XXBTZUSD") } returns mapOf(
                    "XXBTZUSD" to BigDecimal("50000.00"),
                )

                // Total net flow = 2000 + 0.1 * 50000 = 2000 + 5000 = 7000.
                // Current total: 17,000. Pre-flow: 10,000. ATH scales from 10,000 to 17,000.
                val dd = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("17000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                )

                dd.shouldBeEqualComparingTo(BigDecimal.ZERO)
                coVerify {
                    mockTrades.setSyncMetadata(
                        com.gemini.krakenbot.model.SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                        fixedTime.epochSecond.toString(),
                    )
                    portfolioStatsRepository.save(
                        match {
                            it.allTimeHigh.compareTo(BigDecimal("17000.00")) == 0
                        },
                    )
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
                    portfolioStatsRepository.save(
                        match {
                            it.allTimeHigh.compareTo(BigDecimal("5000.00")) == 0
                        },
                    )
                }
            }
        }

        "updateAth returns zero flow when tradeRepository is missing or events are empty" {
            runTest {
                val mockLedgers = mockk<com.gemini.krakenbot.repository.LedgerRepository>(relaxed = true)
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

                val mockTrades = mockk<com.gemini.krakenbot.repository.TradeRepository>(relaxed = true)
                coEvery {
                    mockTrades.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
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

        "calculateUnappliedExternalFlow handles USD asset and ticker failure" {
            runTest {
                val mockLedgers = mockk<com.gemini.krakenbot.repository.LedgerRepository>(relaxed = true)
                val mockTrades = mockk<com.gemini.krakenbot.repository.TradeRepository>(relaxed = true)
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
                    mockTrades.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()

                val usdFlow = com.gemini.krakenbot.model.LedgerEvent(
                    ledgerId = "L1",
                    time = fixedTime.minusSeconds(1800),
                    type = com.gemini.krakenbot.model.KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("1000.00"),
                    fee = BigDecimal.ZERO,
                )
                val failedFlow = com.gemini.krakenbot.model.LedgerEvent(
                    ledgerId = "L2",
                    time = fixedTime.minusSeconds(900),
                    type = com.gemini.krakenbot.model.KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "SOL",
                    amount = BigDecimal("10.0"),
                    fee = BigDecimal.ZERO,
                )
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns listOf(usdFlow, failedFlow)
                coEvery { krakenService.getTickerPrices("SOLUSD") } throws RuntimeException("network down")

                val dd = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("11000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                )
                dd.shouldBeEqualComparingTo(BigDecimal.ZERO)
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
    }
}
