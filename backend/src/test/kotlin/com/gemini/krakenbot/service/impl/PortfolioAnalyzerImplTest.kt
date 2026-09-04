package com.gemini.krakenbot.service.impl

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
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
                    mockLedgers.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
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
                    mockLedgers.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()

                val plainUsdDeposit = com.gemini.krakenbot.model.LedgerEvent(
                    ledgerId = "L0",
                    time = fixedTime.minusSeconds(2400),
                    type = com.gemini.krakenbot.model.KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("1000.00"),
                    fee = BigDecimal.ZERO,
                )
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
                    mockTrades.setSyncMetadata(
                        com.gemini.krakenbot.model.SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                        fixedTime.epochSecond.toString(),
                    )
                    portfolioStatsRepository.save(
                        match {
                            it.allTimeHigh.compareTo(BigDecimal("18000.00")) == 0
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
                    mockLedgers.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
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

        "pricing failure on crypto owner capital fails closed without advancing watermark and succeeds on retry" {
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
                    mockLedgers.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()

                val cryptoDeposit = com.gemini.krakenbot.model.LedgerEvent(
                    ledgerId = "L2",
                    time = fixedTime.minusSeconds(900),
                    type = com.gemini.krakenbot.model.KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "SOL",
                    amount = BigDecimal("10.0"),
                    fee = BigDecimal.ZERO,
                )
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns listOf(cryptoDeposit)
                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns emptyList()
                coEvery { krakenService.getTickerPrices("SOLUSD") } throws RuntimeException("network down")

                // Step 1: Pricing fails -> throws IllegalStateException, watermark does not advance, ATH not saved
                shouldThrow<IllegalStateException> {
                    analyzerWithRepos.updateAthAndCalculateDrawdown(
                        totalPortfolioValueUSD = BigDecimal("11000.00"),
                        netExternalFlowUSD = BigDecimal.ZERO,
                    )
                }
                coVerify(exactly = 0) {
                    mockTrades.setSyncMetadata(
                        com.gemini.krakenbot.model.SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                        any(),
                    )
                    portfolioStatsRepository.save(any())
                }

                // Step 2: Retry when ticker succeeds -> ATH scaled once, watermark advances
                coEvery { krakenService.getTickerPrices("SOLUSD") } returns mapOf("SOLUSD" to BigDecimal("100.00"))
                val dd = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("11000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                )
                dd.shouldBeEqualComparingTo(BigDecimal.ZERO)
                coVerify(exactly = 1) {
                    mockTrades.setSyncMetadata(
                        com.gemini.krakenbot.model.SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                        fixedTime.epochSecond.toString(),
                    )
                    portfolioStatsRepository.save(
                        match { it.allTimeHigh.compareTo(BigDecimal("11000.00")) == 0 },
                    )
                }
            }
        }

        "crypto flow pricing uses historical portfolio snapshot price without querying ticker" {
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
                    mockLedgers.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()

                val cryptoDeposit = com.gemini.krakenbot.model.LedgerEvent(
                    ledgerId = "L2",
                    time = fixedTime.minusSeconds(900),
                    type = com.gemini.krakenbot.model.KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "SOL",
                    amount = BigDecimal("10.0"),
                    fee = BigDecimal.ZERO,
                )
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns listOf(cryptoDeposit)

                val snap = PortfolioSnapshot(
                    timestamp = fixedTime.minusSeconds(905),
                    totalValueUSD = BigDecimal("10000.00"),
                    assets = mapOf(
                        "SOL" to com.gemini.krakenbot.TestFixtures.assetSnapshot(
                            symbol = "SOL",
                            balance = BigDecimal("10.0"),
                            price = BigDecimal("150.00"),
                            valueUSD = BigDecimal("1500.00"),
                            targetPercent = BigDecimal("15.0"),
                        ),
                    ),
                    actions = emptyList<String>(),
                    drawdownPercent = BigDecimal.ZERO,
                    fiatDeploymentPercent = BigDecimal.ZERO,
                    effectiveUsdTargetPercent = BigDecimal.ZERO,
                )
                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns listOf(snap)

                val dd = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("11500.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                )
                dd.shouldBeEqualComparingTo(BigDecimal.ZERO)

                coVerify(exactly = 0) { krakenService.getTickerPrices(any()) }
                coVerify(exactly = 1) {
                    portfolioStatsRepository.save(
                        match { it.allTimeHigh.compareTo(BigDecimal("11500.00")) == 0 },
                    )
                }
            }
        }

        // Regression: the removed getSnapshotBefore fallback could have served a snapshot
        // hours or days old, silently corrupting ATH valuations. After the fix, only
        // snapshots within ±180s are accepted; anything older must fall through to ticker.
        "crypto flow pricing rejects snapshot outside ±180s window and falls through to ticker" {
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
                    mockLedgers.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()

                val cryptoDeposit = com.gemini.krakenbot.model.LedgerEvent(
                    ledgerId = "L3",
                    time = fixedTime.minusSeconds(900),
                    type = com.gemini.krakenbot.model.KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
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
                        "SOL" to com.gemini.krakenbot.TestFixtures.assetSnapshot(
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
                // getSnapshotsInRange returns empty for the ±180s window around the event
                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns emptyList()

                // Ticker provides the correct price
                coEvery { krakenService.getTickerPrices("SOLUSD") } returns mapOf("SOLUSD" to BigDecimal("150.00"))

                val dd = analyzerWithRepos.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("11000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                )
                dd.shouldBeEqualComparingTo(BigDecimal.ZERO)
                // Ticker was queried because the stale snapshot was not in range
                coVerify(exactly = 1) { krakenService.getTickerPrices("SOLUSD") }
            }
        }

        "crypto flow pricing fails closed when ticker returns zero price or cancellation occurs" {
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
                    mockLedgers.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()

                val cryptoDeposit = com.gemini.krakenbot.model.LedgerEvent(
                    ledgerId = "L2",
                    time = fixedTime.minusSeconds(900),
                    type = com.gemini.krakenbot.model.KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "SOL",
                    amount = BigDecimal("10.0"),
                    fee = BigDecimal.ZERO,
                )
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns listOf(cryptoDeposit)
                coEvery { mockTrades.getSnapshotsInRange(any(), any()) } returns emptyList()

                coEvery { krakenService.getTickerPrices("SOLUSD") } returns mapOf("SOLUSD" to BigDecimal.ZERO)
                shouldThrow<IllegalStateException> {
                    analyzerWithRepos.updateAthAndCalculateDrawdown(
                        totalPortfolioValueUSD = BigDecimal("10000.00"),
                        netExternalFlowUSD = BigDecimal.ZERO,
                    )
                }

                coEvery { krakenService.getTickerPrices("SOLUSD") } throws
                    kotlinx.coroutines.CancellationException("ticker cancelled")
                shouldThrow<kotlinx.coroutines.CancellationException> {
                    analyzerWithRepos.updateAthAndCalculateDrawdown(
                        totalPortfolioValueUSD = BigDecimal("10000.00"),
                        netExternalFlowUSD = BigDecimal.ZERO,
                    )
                }
            }
        }

        "ledger coverage watermark missing halts ATH flow calculation safely without advancing watermark" {
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
                    mockLedgers.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
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

        "deposit occurring after ledger sync is not skipped when rebalance runs before next ledger sync" {
            runTest {
                val mockLedgers = mockk<com.gemini.krakenbot.repository.LedgerRepository>(relaxed = true)
                val mockTrades = mockk<com.gemini.krakenbot.repository.TradeRepository>(relaxed = true)
                val t0 = Instant.parse("2026-08-01T12:00:00Z")
                var currentTime = t0
                val analyzerWithRepos = PortfolioAnalyzerImpl(
                    krakenService = krakenService,
                    configService = configService,
                    portfolioStatsRepository = portfolioStatsRepository,
                    ledgerRepository = mockLedgers,
                    tradeRepository = mockTrades,
                    nowProvider = { currentTime },
                )
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("10000.00"))

                // Cycle 1 at T=100s, ledger coverage is at T=60s. Initial watermark set to 60s.
                currentTime = t0.plusSeconds(100)
                coEvery {
                    mockLedgers.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns t0.plusSeconds(60).epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns null

                analyzerWithRepos.updateAthAndCalculateDrawdown(BigDecimal("10000.00"), BigDecimal.ZERO)
                coVerify(exactly = 1) {
                    mockTrades.setSyncMetadata(
                        com.gemini.krakenbot.model.SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                        t0.plusSeconds(60).epochSecond.toString(),
                    )
                }

                // Cycle 2 at T=120s. Deposit occurred on Kraken at T=70s, but ledger sync hasn't fetched it yet.
                // Ledger coverage is STILL at T=60s. ATH watermark is at T=60s.
                // Range query (60..60] is empty. Watermark does NOT advance past 60s.
                currentTime = t0.plusSeconds(120)
                coEvery {
                    mockTrades.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns t0.plusSeconds(60).epochSecond.toString()
                coEvery { mockLedgers.getLedgersInRange(any(), any()) } returns emptyList()

                analyzerWithRepos.updateAthAndCalculateDrawdown(BigDecimal("10000.00"), BigDecimal.ZERO)

                // Now ledger sync runs and imports deposit at T=70s ($5000), advancing coverage to T=150s.
                // Cycle 3 at T=160s: ATH watermark is at 60s, coverage is 150s.
                // Query range is (60..150], which includes the deposit at T=70s!
                currentTime = t0.plusSeconds(160)
                coEvery {
                    mockLedgers.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns t0.plusSeconds(150).epochSecond.toString()
                val depositAt70 = com.gemini.krakenbot.model.LedgerEvent(
                    ledgerId = "L70",
                    time = t0.plusSeconds(70),
                    type = com.gemini.krakenbot.model.KrakenApiConstants.LEDGER_TYPE_DEPOSIT,
                    asset = "USD",
                    amount = BigDecimal("5000.00"),
                    fee = BigDecimal.ZERO,
                )
                coEvery {
                    mockLedgers.getLedgersInRange(t0.plusSeconds(60), t0.plusSeconds(150))
                } returns listOf(depositAt70)

                analyzerWithRepos.updateAthAndCalculateDrawdown(BigDecimal("15000.00"), BigDecimal.ZERO)

                // Verified: ATH was scaled for the deposit at 70s, and watermark advanced to 150s!
                coVerify {
                    mockTrades.setSyncMetadata(
                        com.gemini.krakenbot.model.SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                        t0.plusSeconds(150).epochSecond.toString(),
                    )
                    portfolioStatsRepository.save(
                        match { it.allTimeHigh.compareTo(BigDecimal("15000.00")) == 0 },
                    )
                }
            }
        }

        "staking rewards improve portfolio value and reduce drawdown without scaling ATH" {
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
                // ATH = 100,000. Pre-flow portfolio = 80,000 (20% drawdown).
                coEvery { portfolioStatsRepository.load() } returns PortfolioStats(BigDecimal("100000.00"))
                coEvery {
                    mockLedgers.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.minusSeconds(3600).epochSecond.toString()

                // Staking reward of $10,000 USD increases portfolio to 90,000
                val stakingReward = com.gemini.krakenbot.model.LedgerEvent(
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
                    portfolioStatsRepository.save(
                        match { it.allTimeHigh.compareTo(BigDecimal("100000.00")) == 0 },
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
                    portfolioStatsRepository.save(
                        match { it.allTimeHigh.compareTo(BigDecimal("125000.00")) == 0 },
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
                    portfolioStatsRepository.save(
                        match { it.allTimeHigh.compareTo(BigDecimal("75000.00")) == 0 },
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
                val mockLedgers = mockk<com.gemini.krakenbot.repository.LedgerRepository>(relaxed = true)
                val mockTrades = mockk<com.gemini.krakenbot.repository.TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val mockStatsRepo = mockk<com.gemini.krakenbot.repository.PortfolioStatsRepository>()
                coEvery { mockStatsRepo.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery { mockStatsRepo.save(any()) } throws RuntimeException("disk full")
                coEvery {
                    mockLedgers.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
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
                        com.gemini.krakenbot.model.SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                        any(),
                    )
                }
            }
        }

        "updateAth does not initialize watermark when portfolioStatsRepository save throws and watermark was missing" {
            runTest {
                val mockLedgers = mockk<com.gemini.krakenbot.repository.LedgerRepository>(relaxed = true)
                val mockTrades = mockk<com.gemini.krakenbot.repository.TradeRepository>(relaxed = true)
                val fixedTime = Instant.parse("2026-08-01T12:00:00Z")
                val mockStatsRepo = mockk<com.gemini.krakenbot.repository.PortfolioStatsRepository>()
                coEvery { mockStatsRepo.load() } returns PortfolioStats(BigDecimal("10000.00"))
                coEvery { mockStatsRepo.save(any()) } throws RuntimeException("disk full")
                coEvery {
                    mockLedgers.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
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
                        com.gemini.krakenbot.model.SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC,
                        any(),
                    )
                }
            }
        }

        "updateAth does not advance watermark or query ledgers when watermark is already at or ahead of current epoch" {
            runTest {
                val mockLedgers = mockk<com.gemini.krakenbot.repository.LedgerRepository>(relaxed = true)
                val mockTrades = mockk<com.gemini.krakenbot.repository.TradeRepository>(relaxed = true)
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
                    mockLedgers.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC)
                } returns fixedTime.epochSecond.toString()
                coEvery {
                    mockTrades.getSyncMetadata(com.gemini.krakenbot.model.SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC)
                } returns fixedTime.plusSeconds(10).epochSecond.toString()

                val dd = analyzerWithAheadWatermark.updateAthAndCalculateDrawdown(
                    totalPortfolioValueUSD = BigDecimal("10000.00"),
                    netExternalFlowUSD = BigDecimal.ZERO,
                )

                dd.shouldBeEqualComparingTo(BigDecimal.ZERO)
                coVerify(exactly = 0) {
                    mockLedgers.getLedgersInRange(any(), any())
                }
            }
        }
    }
}
