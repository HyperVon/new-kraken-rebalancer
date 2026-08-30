package com.gemini.krakenbot

import com.gemini.krakenbot.config.Allocation
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.domain.OrderResult
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.impl.SqlitePortfolioStatsRepositoryImpl
import com.gemini.krakenbot.service.ConfigService
import com.gemini.krakenbot.service.FakeKrakenService
import com.gemini.krakenbot.service.TradeHistoryService
import com.gemini.krakenbot.service.impl.OrderExecutorImpl
import com.gemini.krakenbot.service.impl.PortfolioAnalyzerImpl
import com.gemini.krakenbot.service.impl.PortfolioManagerImpl
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal

internal fun EvaluationScenariosTest.registerScenarios29To35() {
    "Scenario 29: Extremely Large Minimum Order Size" {
        runTest {
            val fakeKraken = FakeKrakenService()
            val mockConfig = mockk<ConfigService>(relaxed = true)
            val appConfig =
                TestFixtures.config(
                    settings =
                    TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L, minimumOrderSizeUSD = 100.0),
                    allocations =
                    listOf(
                        Allocation(Asset.BTC, 45.0),
                        Allocation(Asset.ETH, 45.0),
                        Allocation(Asset.USD, 10.0),
                    ),
                )
            every { mockConfig.getConfig() } returns appConfig

            fakeKraken.balanceSupplier = {
                mapOf(
                    Asset.BTC to 0.09,
                    "ETH" to 2.25,
                    Asset.USD to 1200.0,
                )
            }
            fakeKraken.pricesSupplier = { _ ->
                mapOf(
                    TestFixtures.XBTUSD to 50000.0,
                    TestFixtures.ETHUSD to 2000.0,
                )
            }

            val statsRepo = mockk<PortfolioStatsRepository>(relaxed = true)
            val analyzer =
                PortfolioAnalyzerImpl(
                    fakeKraken,
                    mockConfig,
                    statsRepo,
                )
            val executor =
                OrderExecutorImpl(fakeKraken, tradeHistoryService)

            val mockHistory = mockk<TradeHistoryService>(relaxed = true)
            val capturedActions = mutableListOf<String>()
            coEvery { mockHistory.addSnapshot(any()) } answers {
                capturedActions.addAll(firstArg<PortfolioSnapshot>().actions)
            }

            val pm =
                PortfolioManagerImpl(
                    mockConfig,
                    mockHistory,
                    analyzer,
                    executor,
                )
            pm.performRebalanceCycle()

            val btcSkipped = capturedActions.any { it.contains("Skipping dust buy for BTC") }
            val ethSkipped = capturedActions.any { it.contains("Skipping dust buy for ETH") }
            val zeroOrders = fakeKraken.executedOrders.isEmpty()

            val success = btcSkipped && ethSkipped && zeroOrders
            val evidence =
                "Captured actions: $capturedActions\n" +
                    "Executed orders count: ${fakeKraken.executedOrders.size}\n" +
                    "BTC buy skipped: $btcSkipped, ETH buy skipped: $ethSkipped"

            success.shouldBeTrue()
            EvaluationScenariosTest.recordResult(
                "Scenario 29",
                "Extremely Large Minimum Order Size",
                TestFixtures.PASS,
                evidence,
            )
        }
    }

    "Scenario 30: Exponent Curve Calibration for Fiat Deployment" {
        runTest {
            val fakeKraken = FakeKrakenService()
            val mockConfig = mockk<ConfigService>(relaxed = true)
            val f = evaluationTempPath("30-stats")
            val testStatsFile = f.absolutePath
            val db = DatabaseConfig.init(TestFixtures.MEMORY_)
            val statsRepo = SqlitePortfolioStatsRepositoryImpl(db, objectMapper, testStatsFile)

            val appConfig =
                TestFixtures.config(
                    settings =
                    TestFixtures.settings(loopDelaySeconds = 60L, fiatMaxDrawdown = 20.0, fiatDeploymentExponent = 2.0),
                    allocations =
                    listOf(
                        Allocation(Asset.BTC, 80.0),
                        Allocation(Asset.USD, 20.0),
                    ),
                )
            every { mockConfig.getConfig() } returns appConfig

            val analyzer =
                PortfolioAnalyzerImpl(
                    fakeKraken,
                    mockConfig,
                    statsRepo,
                )
            val executor =
                OrderExecutorImpl(fakeKraken, tradeHistoryService)

            val mockHistory = mockk<TradeHistoryService>(relaxed = true)
            val capturedSnapshots = mutableListOf<PortfolioSnapshot>()
            coEvery { mockHistory.addSnapshot(any()) } answers {
                capturedSnapshots.add(firstArg())
            }

            val pm =
                PortfolioManagerImpl(
                    mockConfig,
                    mockHistory,
                    analyzer,
                    executor,
                )

            // Cycle 1 sets the ATH at $10,000 (0.2 BTC); cycle 2 drops to $9,000, a 10% drawdown.
            // With fiatMaxDrawdown 20 and exponent 2 that deploys (10/20)^2 = 25% of the 20% USD
            // sleeve, leaving an effective USD target of 15% and a scaled BTC target of 85%.
            fakeKraken.balanceSupplier = {
                mapOf(
                    Asset.BTC to 0.2,
                    Asset.USD to 0.0,
                )
            }
            fakeKraken.pricesSupplier = { _ -> mapOf(TestFixtures.XBTUSD to 50000.0) }
            pm.performRebalanceCycle()

            fakeKraken.balanceSupplier = {
                mapOf(
                    Asset.BTC to 0.18,
                    Asset.USD to 0.0,
                )
            }
            pm.performRebalanceCycle()

            val lastSnapshot = requireNotNull(capturedSnapshots.lastOrNull())
            val btcSnapshot = requireNotNull(lastSnapshot.assets[Asset.BTC])
            lastSnapshot.drawdownPercent.shouldBeEqualComparingTo(BigDecimal("10.0"))
            lastSnapshot.fiatDeploymentPercent.shouldBeEqualComparingTo(BigDecimal("25.0"))
            lastSnapshot.effectiveUsdTargetPercent.shouldBeEqualComparingTo(BigDecimal("15.0"))
            btcSnapshot.targetPercent.shouldBeEqualComparingTo(BigDecimal("85.0"))

            val evidence =
                "Drawdown: ${lastSnapshot.drawdownPercent}%\n" +
                    "Deployment Pct: ${lastSnapshot.fiatDeploymentPercent}% (Expected: 25.0%)\n" +
                    "Effective USD Target: ${lastSnapshot.effectiveUsdTargetPercent}% (Expected: 15.0%)\n" +
                    "Adjusted BTC Target: ${btcSnapshot.targetPercent}% (Expected: 85.0%)"

            f.delete()
            EvaluationScenariosTest.recordResult(
                "Scenario 30",
                "Exponent Curve Calibration for Fiat Deployment",
                TestFixtures.PASS,
                evidence,
            )
        }
    }

    "Scenario 31: USD Refresh Early-Accept and Fail-Closed Buys" {
        runTest {
            val fakeKraken = FakeKrakenService()
            val mockConfig = mockk<ConfigService>(relaxed = true)
            val executor =
                OrderExecutorImpl(fakeKraken, tradeHistoryService)

            // Opening $100 + sell $100 → projected $200; early-accept threshold = 95% = $190.
            // Sub-case A: first poll returns exactly $190 → stop polling; buy budget 99% = $188.10.
            fakeKraken.balanceSupplier = {
                mapOf(Asset.USD to BigDecimal("190.00"))
            }
            fakeKraken.orderResultFactory = { pair, _, side, volume ->
                OrderResult(
                    success = true,
                    pair = pair,
                    side = side,
                    volume = volume,
                    orderTxid = "FAKE-$pair-$side",
                )
            }

            val settings =
                TestFixtures.settings(dryRun = false)
            every { mockConfig.getConfig() } returns
                TestFixtures.config(
                    settings = settings,
                    allocations =
                    listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(Asset.ETH, 50.0),
                    ),
                )

            executor.executeOrders(
                buyOrders = mapOf(Asset.ETH to BigDecimal("200.00")),
                sellOrders = mapOf(Asset.BTC to BigDecimal("100.00")),
                currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                prices =
                mapOf(
                    Asset.BTC to BigDecimal("1000.00"),
                    Asset.ETH to BigDecimal("1000.00"),
                ),
                settings = settings,
                actionLog = mutableListOf(),
            )

            val earlyAcceptPolls = fakeKraken.getBalancesCallCount == 1
            val earlyAcceptBuy =
                fakeKraken.executedOrders.size == 2 &&
                    fakeKraken.executedOrders[1].side == TestFixtures.BUY &&
                    fakeKraken.executedOrders[1].volume.compareTo(BigDecimal("0.1881")) == 0

            // Sub-case B: fail-closed — no positive USD observed → sells only, no buys.
            // The three polls (throw → empty → zero) exhaust the retry cap without a positive
            // reading, so the buy phase is abandoned rather than sized off stale cash.
            fakeKraken.executedOrders.clear()
            fakeKraken.getBalancesCallCount = 0
            var poll = 0
            fakeKraken.balanceSupplier = {
                poll++
                when (poll) {
                    1 -> error("Temporary balance failure")
                    2 -> emptyMap()
                    else -> mapOf(Asset.USD to BigDecimal.ZERO)
                }
            }

            executor.executeOrders(
                buyOrders = mapOf(Asset.ETH to BigDecimal("100.00")),
                sellOrders = mapOf(Asset.BTC to BigDecimal("100.00")),
                currentValuesUSD = mapOf(Asset.USD to BigDecimal("100.00")),
                prices =
                mapOf(
                    Asset.BTC to BigDecimal("1000.00"),
                    Asset.ETH to BigDecimal("1000.00"),
                ),
                settings = settings,
                actionLog = mutableListOf(),
            )

            val failClosedPolls = fakeKraken.getBalancesCallCount == 3
            val failClosedNoBuys =
                fakeKraken.executedOrders.size == 1 &&
                    fakeKraken.executedOrders.single().side == TestFixtures.SELL

            val success =
                earlyAcceptPolls && earlyAcceptBuy && failClosedPolls && failClosedNoBuys
            val evidence =
                "Sub-case A (early-accept ≥95%): pollsPass=$earlyAcceptPolls buyPass=$earlyAcceptBuy\n" +
                    "Sub-case B (fail-closed abort buys): pollsPass=$failClosedPolls noBuysPass=$failClosedNoBuys"

            success.shouldBeTrue()
            EvaluationScenariosTest.recordResult(
                "Scenario 31",
                "USD Refresh Early-Accept and Fail-Closed Buys",
                TestFixtures.PASS,
                evidence,
            )
        }
    }

    "Scenario 32: Multi-Cycle Convergence with Fill Feedback" {
        runTest {
            val fakeKraken = FakeKrakenService()
            val mockConfig = mockk<ConfigService>(relaxed = true)
            val statsRepo = mockk<PortfolioStatsRepository>(relaxed = true)
            val mockHistory = mockk<TradeHistoryService>(relaxed = true)
            val prices =
                mapOf(
                    Asset.BTC to BigDecimal("50000.00"),
                    Asset.ETH to BigDecimal("2000.00"),
                )
            val balances =
                mutableMapOf(
                    Asset.BTC to BigDecimal("0.18"),
                    Asset.ETH to BigDecimal("0.50"),
                    Asset.USD to BigDecimal.ZERO,
                )
            val appConfig =
                TestFixtures.config(
                    settings =
                    TestFixtures.settings(dryRun = false, loopDelaySeconds = 60L, deviationTriggerPercent = 0.1),
                    allocations =
                    listOf(
                        Allocation(Asset.BTC, 50.0),
                        Allocation(Asset.ETH, 40.0),
                        Allocation(Asset.USD, 10.0),
                    ),
                )
            every { mockConfig.getConfig() } returns appConfig
            fakeKraken.balanceSupplier = { balances.toMap() }
            fakeKraken.pricesSupplier = {
                mapOf(
                    TestFixtures.XBTUSD to prices.getValue(Asset.BTC),
                    TestFixtures.ETHUSD to prices.getValue(Asset.ETH),
                )
            }

            // Fill feedback: buys fill only 99% of the requested volume and every fill is written
            // back into the shared balance map, so each cycle re-analyzes the real post-fill
            // portfolio. Trading at the quoted price with no fees keeps totalValueUSD constant.
            val fillRatio = BigDecimal("0.99")
            fakeKraken.executeOrderAction = { pair, _, side, volume ->
                val symbol =
                    when (pair) {
                        TestFixtures.XBTUSD -> Asset.BTC
                        TestFixtures.ETHUSD -> Asset.ETH
                        else -> error("Unexpected pair: $pair")
                    }
                val price = prices.getValue(symbol)
                val filledVolume = if (side == TestFixtures.BUY) volume.multiply(fillRatio) else volume
                val filledValue = filledVolume.multiply(price)

                if (side == TestFixtures.BUY) {
                    balances[symbol] = balances.getValue(symbol).add(filledVolume)
                    balances[Asset.USD] = balances.getValue(Asset.USD).subtract(filledValue)
                } else {
                    balances[symbol] = balances.getValue(symbol).subtract(filledVolume)
                    balances[Asset.USD] = balances.getValue(Asset.USD).add(filledValue)
                }
            }

            val analyzer = PortfolioAnalyzerImpl(fakeKraken, mockConfig, statsRepo)
            val executor = OrderExecutorImpl(fakeKraken, mockHistory)
            val manager = PortfolioManagerImpl(mockConfig, mockHistory, analyzer, executor)
            val startingValue =
                balances.getValue(Asset.BTC).multiply(prices.getValue(Asset.BTC))
                    .add(balances.getValue(Asset.ETH).multiply(prices.getValue(Asset.ETH)))
                    .add(balances.getValue(Asset.USD))

            val ordersPerCycle = mutableListOf<Int>()
            val snapshots = mutableListOf<PortfolioSnapshot>()
            repeat(3) {
                val orderCountBefore = fakeKraken.executedOrders.size
                snapshots += requireNotNull(manager.performRebalanceCycle())
                ordersPerCycle += fakeKraken.executedOrders.size - orderCountBefore
            }

            val maxDeviations =
                snapshots.map { snapshot ->
                    snapshot.assets.values.maxOf { asset -> asset.deviationPercent.abs() }
                }
            maxDeviations[0].shouldBeEqualComparingTo(BigDecimal("3.00"))
            maxDeviations[1].shouldBeEqualComparingTo(BigDecimal("0.03"))
            (maxDeviations[1] < maxDeviations[0]).shouldBeTrue()
            (maxDeviations[2] <= maxDeviations[1]).shouldBeTrue()

            // Third cycle is silent because the 0.03% residual sits below deviationTriggerPercent.
            ordersPerCycle shouldBe listOf(2, 1, 0)
            snapshots[2].actions.none { it.startsWith("Deviation:") }.shouldBeTrue()
            fakeKraken.executedOrders
                .all { order ->
                    val symbol = if (order.pair == TestFixtures.XBTUSD) Asset.BTC else Asset.ETH
                    order.volume.multiply(prices.getValue(symbol)).signum() > 0
                }.shouldBeTrue()
            snapshots.forEach { snapshot ->
                snapshot.totalValueUSD.shouldBeEqualComparingTo(startingValue)
            }

            val evidence =
                "Start: BTC=0.18 @ $50000, ETH=0.50 @ $2000, USD=$0; targets=50%/40%/10%\n" +
                    "99% partial-buy fills fed back into balances\n" +
                    "Post-cycle max |deviation|: $maxDeviations\n" +
                    "Executed orders per cycle: $ordersPerCycle\n" +
                    "Total value per cycle: ${snapshots.map { it.totalValueUSD }}"

            EvaluationScenariosTest.recordResult(
                "Scenario 32",
                "Multi-Cycle Convergence with Fill Feedback",
                TestFixtures.PASS,
                evidence,
            )
        }
    }

    "Scenario 33: Drawdown Deployment Changes Order Sizes" {
        runTest {
            val prices =
                mapOf(
                    Asset.BTC to BigDecimal("50000.00"),
                    Asset.ETH to BigDecimal("2000.00"),
                )
            val balances =
                mapOf(
                    Asset.BTC to BigDecimal.ZERO,
                    Asset.ETH to BigDecimal.ZERO,
                    Asset.USD to BigDecimal("8000.00"),
                )

            suspend fun runCycle(
                fiatMaxDrawdown: Double,
                ath: BigDecimal,
            ): Pair<PortfolioSnapshot?, FakeKrakenService> {
                val fakeKraken = FakeKrakenService()
                val mockConfig = mockk<ConfigService>(relaxed = true)
                val statsRepo = mockk<PortfolioStatsRepository>(relaxed = true)
                val mockHistory = mockk<TradeHistoryService>(relaxed = true)
                val capturedSnapshots = mutableListOf<PortfolioSnapshot>()
                coEvery { mockHistory.addSnapshot(any()) } answers {
                    capturedSnapshots.add(firstArg())
                }
                coEvery { statsRepo.load() } returns PortfolioStats(allTimeHigh = ath)
                coEvery { statsRepo.save(any()) } returns Unit

                val appConfig =
                    TestFixtures.config(
                        settings =
                        TestFixtures.settings(
                            dryRun = false,
                            loopDelaySeconds = 60L,
                            fiatMaxDrawdown = fiatMaxDrawdown,
                            fiatDeploymentExponent = 2.0,
                        ),
                        allocations =
                        listOf(
                            Allocation(Asset.BTC, 40.0),
                            Allocation(Asset.ETH, 40.0),
                            Allocation(Asset.USD, 20.0),
                        ),
                    )
                every { mockConfig.getConfig() } returns appConfig
                fakeKraken.balanceSupplier = { balances }
                fakeKraken.pricesSupplier = {
                    mapOf(
                        TestFixtures.XBTUSD to prices.getValue(Asset.BTC),
                        TestFixtures.ETHUSD to prices.getValue(Asset.ETH),
                    )
                }

                val analyzer = PortfolioAnalyzerImpl(fakeKraken, mockConfig, statsRepo)
                val executor = OrderExecutorImpl(fakeKraken, mockHistory)
                val manager = PortfolioManagerImpl(mockConfig, mockHistory, analyzer, executor)
                val snapshot = manager.performRebalanceCycle()
                return Pair(snapshot ?: capturedSnapshots.lastOrNull(), fakeKraken)
            }

            val (_, controlKraken) = runCycle(fiatMaxDrawdown = 0.0, ath = BigDecimal("10000.00"))
            val (drawdownSnapshotRaw, drawdownKraken) =
                runCycle(fiatMaxDrawdown = 20.0, ath = BigDecimal("10000.00"))
            val drawdownSnapshot = requireNotNull(drawdownSnapshotRaw)

            val controlBtcBuy =
                controlKraken.executedOrders.single {
                    it.pair == TestFixtures.XBTUSD && it.side == TestFixtures.BUY
                }
            val controlEthBuy =
                controlKraken.executedOrders.single {
                    it.pair == TestFixtures.ETHUSD && it.side == TestFixtures.BUY
                }
            val drawdownBtcBuy =
                drawdownKraken.executedOrders.single {
                    it.pair == TestFixtures.XBTUSD && it.side == TestFixtures.BUY
                }
            val drawdownEthBuy =
                drawdownKraken.executedOrders.single {
                    it.pair == TestFixtures.ETHUSD && it.side == TestFixtures.BUY
                }

            val expectedControlBtc = BigDecimal("0.06400000")
            val expectedControlEth = BigDecimal("1.60000000")
            val expectedDrawdownBtc = BigDecimal("0.08000000")
            val expectedDrawdownEth = BigDecimal("1.96000000")

            controlBtcBuy.volume.shouldBeEqualComparingTo(expectedControlBtc)
            controlEthBuy.volume.shouldBeEqualComparingTo(expectedControlEth)
            drawdownBtcBuy.volume.shouldBeEqualComparingTo(expectedDrawdownBtc)
            drawdownEthBuy.volume.shouldBeEqualComparingTo(expectedDrawdownEth)

            (drawdownBtcBuy.volume > controlBtcBuy.volume).shouldBeTrue()
            (drawdownEthBuy.volume > controlEthBuy.volume).shouldBeTrue()

            drawdownSnapshot.drawdownPercent.shouldBeEqualComparingTo(BigDecimal("20.0"))
            drawdownSnapshot.fiatDeploymentPercent.shouldBeEqualComparingTo(BigDecimal("100.0"))
            drawdownSnapshot.effectiveUsdTargetPercent.shouldBeEqualComparingTo(BigDecimal("0.0"))

            val controlCryptoNotional =
                controlBtcBuy.volume.multiply(prices.getValue(Asset.BTC))
                    .add(controlEthBuy.volume.multiply(prices.getValue(Asset.ETH)))
            val drawdownCryptoNotional =
                drawdownBtcBuy.volume.multiply(prices.getValue(Asset.BTC))
                    .add(drawdownEthBuy.volume.multiply(prices.getValue(Asset.ETH)))
            (drawdownCryptoNotional > controlCryptoNotional).shouldBeTrue()

            val evidence =
                "Portfolio: all-cash USD=$8000, BTC=0, ETH=0; prices BTC=$50000, ETH=$2000; targets 40/40/20\n" +
                    "Control (fiatMaxDrawdown=0): BTC buy=${controlBtcBuy.volume}, ETH buy=${controlEthBuy.volume} " +
                    "(crypto notional=$controlCryptoNotional)\n" +
                    "Drawdown (ATH=$10000, 20% DD, deploy 100%): BTC buy=${drawdownBtcBuy.volume}, " +
                    "ETH buy=${drawdownEthBuy.volume} (crypto notional=$drawdownCryptoNotional)\n" +
                    "Snapshot: drawdown=${drawdownSnapshot.drawdownPercent}%, " +
                    "fiatDeployment=${drawdownSnapshot.fiatDeploymentPercent}%, " +
                    "effectiveUsdTarget=${drawdownSnapshot.effectiveUsdTargetPercent}%"

            EvaluationScenariosTest.recordResult(
                "Scenario 33",
                "Drawdown Deployment Changes Order Sizes",
                TestFixtures.PASS,
                evidence,
            )
        }
    }

    "Scenario 34: Zero-Target Liquidation Never Exceeds Holdings" {
        runTest {
            val fakeKraken = FakeKrakenService()
            val mockConfig = mockk<ConfigService>(relaxed = true)
            val mockHistory = mockk<TradeHistoryService>(relaxed = true)
            val availableBtc = BigDecimal("0.00000001")
            val btcPrice = BigDecimal("500000.00")
            val settings =
                TestFixtures.settings(deviationTriggerPercent = 0.0, minimumOrderSizeUSD = 0.0)
            every { mockConfig.getConfig() } returns
                TestFixtures.config(
                    settings = settings,
                    allocations =
                    listOf(
                        Allocation(Asset.BTC, 0.0),
                        Allocation(Asset.USD, 100.0),
                    ),
                )
            fakeKraken.balanceSupplier = {
                mapOf(
                    "XXBT" to availableBtc,
                    "ZUSD" to BigDecimal.ZERO,
                )
            }
            fakeKraken.pricesSupplier = { mapOf(TestFixtures.XBTUSD to btcPrice) }

            val analyzer =
                PortfolioAnalyzerImpl(
                    fakeKraken,
                    mockConfig,
                    mockk<PortfolioStatsRepository>(relaxed = true),
                )
            val manager =
                PortfolioManagerImpl(
                    mockConfig,
                    mockHistory,
                    analyzer,
                    OrderExecutorImpl(fakeKraken, mockHistory),
                )

            // Raw BTC value is $0.005; portfolio valuation rounds the zero-target sell intent
            // to $0.01, whose ordinary HALF_UP division would request two satoshis.
            manager.performRebalanceCycle()

            val sell = fakeKraken.executedOrders.single()
            sell.side shouldBe TestFixtures.SELL
            sell.volume.shouldBeEqualComparingTo(availableBtc)
            (sell.volume <= availableBtc).shouldBeTrue()

            EvaluationScenariosTest.recordResult(
                "Scenario 34",
                "Zero-Target Liquidation Never Exceeds Holdings",
                TestFixtures.PASS,
                "BTC holding=$availableBtc @ $$btcPrice; rounded liquidation intent=$0.01; " +
                    "submitted sell volume=${sell.volume}",
            )
        }
    }

    "Scenario 35: Complete Liquidation of Zero-Target Position" {
        runTest {
            val fakeKraken = FakeKrakenService()
            val mockConfig = mockk<ConfigService>(relaxed = true)
            val mockHistory = mockk<TradeHistoryService>(relaxed = true)
            val appConfig = TestFixtures.config(
                settings = TestFixtures.settings(
                    dryRun = false,
                    simulation = true,
                    deviationTriggerPercent = 5.0,
                    minimumOrderSizeUSD = 10.0,
                ),
                allocations = listOf(
                    Allocation(Asset.BTC, 0.0), // Zero target allocation
                    Allocation(Asset.ETH, 50.0),
                    Allocation(Asset.USD, 50.0),
                ),
            )
            every { mockConfig.getConfig() } returns appConfig

            val btcBalance = BigDecimal("0.1")
            val btcPrice = BigDecimal("1000.00") // $100 value > $10 minimum order size
            fakeKraken.balanceSupplier = {
                mapOf(
                    "XXBT" to btcBalance,
                    "XETH" to BigDecimal("0.5"),
                    "ZUSD" to BigDecimal("400.00"),
                )
            }
            fakeKraken.pricesSupplier = {
                mapOf(
                    TestFixtures.XBTUSD to btcPrice,
                    TestFixtures.ETHUSD to BigDecimal("1000.00"),
                )
            }

            val analyzer = PortfolioAnalyzerImpl(
                fakeKraken,
                mockConfig,
                mockk<PortfolioStatsRepository>(relaxed = true),
            )
            val manager = PortfolioManagerImpl(
                mockConfig,
                mockHistory,
                analyzer,
                OrderExecutorImpl(fakeKraken, mockHistory),
            )

            manager.performRebalanceCycle()

            // Assert zero-target asset generates a 100% sell order for the full holding
            val sellOrders = fakeKraken.executedOrders.filter { it.side == TestFixtures.SELL }
            sellOrders.size shouldBe 1
            val sell = sellOrders.single()
            sell.pair shouldBe Asset.BTC_USD_PAIR
            sell.volume.shouldBeEqualComparingTo(btcBalance)

            EvaluationScenariosTest.recordResult(
                "Scenario 35",
                "Complete Liquidation of Zero-Target Position",
                TestFixtures.PASS,
                "Zero-target BTC holding=$btcBalance ($100 USD > $10 dust) generates full liquidation sell order",
            )
        }
    }
}
