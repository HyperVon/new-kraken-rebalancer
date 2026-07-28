package com.gemini.krakenbot.repository

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.OrderSubmissionState
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.JdbcTransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.transactions.transactionManager
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

class TradeThrowingTransactionManager(private val delegate: JdbcTransactionManager) :
    JdbcTransactionManager by delegate {
    override fun newTransaction(
        isolation: Int,
        readOnly: Boolean,
        outerTransaction: JdbcTransaction?,
    ): JdbcTransaction = throw IOException("Direct IO failure")
}

class SqliteTradeRepositoryImplTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val db = DatabaseConfig.init(TestFixtures.MEMORY_)
    private val repository = SqliteTradeRepositoryImpl(db)

    init {
        "save and load snapshots" {
            runTest {
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val snapshot =
                    PortfolioSnapshot(
                        timestamp = now,
                        totalValueUSD = BigDecimal("1000.50"),
                        assets =
                        mapOf(
                            Asset.BTC to
                                PortfolioSnapshot.AssetSnapshot(
                                    symbol = Asset(Asset.BTC),
                                    balance = BigDecimal("0.5"),
                                    price = BigDecimal("18000.00"),
                                    valueUSD = BigDecimal("900.00"),
                                    targetPercent = BigDecimal("90.0"),
                                    currentPercent = BigDecimal("90.0"),
                                    deviationPercent = BigDecimal("0.0"),
                                    deviationUSD = BigDecimal("0.0"),
                                ),
                            TestFixtures.USD to
                                PortfolioSnapshot.AssetSnapshot(
                                    symbol = Asset(TestFixtures.USD),
                                    balance = BigDecimal("100.50"),
                                    price = BigDecimal.ONE,
                                    valueUSD = BigDecimal("100.50"),
                                    targetPercent = BigDecimal("10.0"),
                                    currentPercent = BigDecimal("10.0"),
                                    deviationPercent = BigDecimal("0.0"),
                                    deviationUSD = BigDecimal("0.0"),
                                ),
                        ),
                        actions = listOf("Action 1", "Action 2"),
                        drawdownPercent = BigDecimal("1.25"),
                        fiatDeploymentPercent = BigDecimal("10.0"),
                        effectiveUsdTargetPercent = BigDecimal("10.0"),
                    )

                repository.saveSnapshot(snapshot)

                val loaded = repository.load()
                loaded.size shouldBe 1
                val first = loaded.first()
                first.timestamp shouldBe now
                first.totalValueUSD.shouldBeEqualComparingTo(BigDecimal("1000.50"))
                first.drawdownPercent.shouldBeEqualComparingTo(BigDecimal("1.25"))
                first.fiatDeploymentPercent.shouldBeEqualComparingTo(BigDecimal("10.0"))
                first.effectiveUsdTargetPercent.shouldBeEqualComparingTo(BigDecimal("10.0"))
                first.actions shouldBe listOf("Action 1", "Action 2")

                val btc = first.assets[Asset.BTC]!!
                btc.symbol.value shouldBe Asset.BTC
                btc.balance.shouldBeEqualComparingTo(BigDecimal("0.5"))
                btc.price.shouldBeEqualComparingTo(BigDecimal("18000.00"))
                btc.valueUSD.shouldBeEqualComparingTo(BigDecimal("900.00"))
            }
        }

        "save trade and queries" {
            runTest {
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val trade1 =
                    TradeRecord(
                        timestamp = now.minusSeconds(10),
                        pair = Asset.BTC_USD_PAIR,
                        side = OrderSide.BUY.name,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("5000.00"),
                        success = true,
                        dryRun = false,
                        fee = BigDecimal("15.50"),
                    )
                val trade2 =
                    TradeRecord(
                        timestamp = now,
                        pair = TestFixtures.ETHUSD,
                        side = OrderSide.SELL.name,
                        symbol = Asset.ETH,
                        volume = BigDecimal("1.0"),
                        usdAmount = BigDecimal("2000.00"),
                        success = true,
                        dryRun = true,
                        fee = BigDecimal("5.25"),
                    )
                val failedTrade =
                    TradeRecord(
                        timestamp = now.plusSeconds(10),
                        pair = TestFixtures.DOGEUSD,
                        side = OrderSide.BUY.name,
                        symbol = Asset.DOGE,
                        volume = BigDecimal("100.0"),
                        usdAmount = BigDecimal("10.00"),
                        success = false,
                        dryRun = false,
                        errorMessage = "API Error",
                        fee = BigDecimal("1.50"),
                    )

                repository.saveTrade(trade1)
                repository.saveTrade(trade2)
                repository.saveTrade(failedTrade)

                val stats = repository.getTradeSummaryStats()
                stats.totalTradesExecuted shouldBe 1L
                stats.totalVolumeTraded.shouldBeEqualComparingTo(BigDecimal("5000.00"))
                stats.totalFeesPaid.shouldBeEqualComparingTo(BigDecimal("15.50"))

                val trades = repository.getTradesInRange(now.minusSeconds(20), now.plusSeconds(20))
                trades.size shouldBe 3
                trades[0].pair shouldBe TestFixtures.DOGEUSD
                trades[0].success shouldBe false
                trades[0].errorMessage shouldBe "API Error"
                trades[1].pair shouldBe TestFixtures.ETHUSD
                trades[1].dryRun shouldBe true
                trades[2].pair shouldBe TestFixtures.XBTUSD
            }
        }

        "getTradeSummaryStats with time range" {
            runTest {
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val trade1 =
                    TradeRecord(
                        timestamp = now.minus(10, ChronoUnit.DAYS),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("5000.00"),
                        success = true,
                        dryRun = false,
                        fee = BigDecimal("15.50"),
                    )
                val trade2 =
                    TradeRecord(
                        timestamp = now.minus(2, ChronoUnit.DAYS),
                        pair = TestFixtures.ETHUSD,
                        side = TestFixtures.SELL,
                        symbol = TestFixtures.ETH,
                        volume = BigDecimal("1.0"),
                        usdAmount = BigDecimal("2000.00"),
                        success = true,
                        dryRun = true,
                        fee = BigDecimal("5.25"),
                    )

                repository.saveTrade(trade1)
                repository.saveTrade(trade2)

                val s1 =
                    PortfolioSnapshot(
                        timestamp = now.minus(2, ChronoUnit.DAYS),
                        totalValueUSD = BigDecimal("15000.00"),
                        assets = emptyMap(),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    )
                repository.saveSnapshot(s1)
                repository.saveSnapshot(
                    s1.copy(
                        timestamp = now.plus(1, ChronoUnit.DAYS),
                        totalValueUSD = BigDecimal("20000.00"),
                    ),
                )

                val rangeStats = repository.getTradeSummaryStats(from = now.minus(3, ChronoUnit.DAYS), to = now)
                rangeStats.totalTradesExecuted shouldBe 0L
                rangeStats.totalVolumeTraded.shouldBeEqualComparingTo(BigDecimal.ZERO)
                rangeStats.totalFeesPaid.shouldBeEqualComparingTo(BigDecimal.ZERO)
                rangeStats.periodHigh?.shouldBeEqualComparingTo(BigDecimal("15000.00"))
                rangeStats.latestSnapshotTime shouldBe s1.timestamp
            }
        }

        "getSnapshotsInRange and boundary times" {
            runTest {
                val baseTime = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val s1 =
                    PortfolioSnapshot(
                        timestamp = baseTime.minusSeconds(10),
                        totalValueUSD = BigDecimal("1000.00"),
                        assets = emptyMap(),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    )
                val s2 =
                    PortfolioSnapshot(
                        timestamp = baseTime,
                        totalValueUSD = BigDecimal("2000.00"),
                        assets = emptyMap(),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    )

                repository.saveSnapshot(s1)
                repository.saveSnapshot(s2)

                repository.getTradeSummaryStats().latestSnapshotTime shouldBe baseTime

                val inRange = repository.getSnapshotsInRange(baseTime.minusSeconds(5), baseTime.plusSeconds(5))
                inRange.size shouldBe 1
                inRange.first().totalValueUSD.shouldBeEqualComparingTo(BigDecimal("2000.00"))
            }
        }

        "legacy save saves snapshots" {
            runTest {
                val snapshot =
                    PortfolioSnapshot(
                        timestamp = Instant.now(),
                        totalValueUSD = BigDecimal.ZERO,
                        assets = emptyMap(),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    )
                repository.save(listOf(snapshot))
                repository.load().size shouldBe 1
            }
        }

        "getLatestTradeTime with empty and populated trades" {
            runTest {
                repository.getLatestTradeTime() shouldBe null

                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val trade1 =
                    TradeRecord(
                        timestamp = now.minusSeconds(10),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("5000.00"),
                        success = true,
                        dryRun = false,
                    )
                val trade2 =
                    TradeRecord(
                        timestamp = now,
                        pair = TestFixtures.ETHUSD,
                        side = TestFixtures.SELL,
                        symbol = TestFixtures.ETH,
                        volume = BigDecimal("1.0"),
                        usdAmount = BigDecimal("2000.00"),
                        success = true,
                        dryRun = false,
                    )
                repository.saveTrade(trade1)
                repository.saveTrade(trade2)

                repository.getLatestTradeTime() shouldBe now
            }
        }

        "getLatestTradeTime ignores newer dry-run rows" {
            runTest {
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val liveTrade =
                    TradeRecord(
                        timestamp = now,
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("5000.00"),
                        success = true,
                        dryRun = false,
                    )
                val dryRunTrade =
                    TradeRecord(
                        timestamp = now.plusSeconds(60),
                        pair = TestFixtures.ETHUSD,
                        side = TestFixtures.SELL,
                        symbol = TestFixtures.ETH,
                        volume = BigDecimal("1.0"),
                        usdAmount = BigDecimal("2000.00"),
                        success = true,
                        dryRun = true,
                    )
                repository.saveTrade(liveTrade)
                repository.saveTrade(dryRunTrade)

                repository.getLatestTradeTime() shouldBe now
            }
        }

        "CQ-10-L1: getLatestTradeTime ignores newer failed live attempts" {
            runTest {
                val fillTime = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                repository.saveTrade(
                    TradeRecord(
                        timestamp = fillTime,
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("5000.00"),
                        success = true,
                        dryRun = false,
                        source = TradeSource.API_FILL,
                    ),
                )
                repository.saveTrade(
                    TradeRecord(
                        timestamp = fillTime.plusSeconds(60),
                        pair = TestFixtures.ETHUSD,
                        side = TestFixtures.SELL,
                        symbol = TestFixtures.ETH,
                        volume = BigDecimal.ONE,
                        usdAmount = BigDecimal("2000.00"),
                        success = false,
                        dryRun = false,
                        errorMessage = "Order rejected",
                        source = TradeSource.LOCAL_ESTIMATE,
                    ),
                )

                repository.getLatestTradeTime() shouldBe fillTime
            }
        }

        "update trade updates record" {
            runTest {
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val oldTrade =
                    TradeRecord(
                        timestamp = now,
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.5"),
                        usdAmount = BigDecimal("15000.00"),
                        success = true,
                        dryRun = false,
                    )
                repository.saveTrade(oldTrade)

                val newTrade =
                    oldTrade.copy(
                        timestamp = now.plusSeconds(3),
                        pair = TestFixtures.XXBTZUSD,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.49980000"),
                        usdAmount = BigDecimal("14980.50"),
                        price = BigDecimal("29972.00"),
                        fee = BigDecimal("38.95"),
                    )
                repository.updateTrade(oldTrade, newTrade)

                val trades = repository.getTradesInRange(now.minusSeconds(10), now.plusSeconds(10))
                trades.size shouldBe 1
                trades.first().timestamp shouldBe now.plusSeconds(3)
                trades.first().pair shouldBe TestFixtures.XXBTZUSD
                trades.first().volume.shouldBeEqualComparingTo(BigDecimal("0.49980000"))
                trades.first().usdAmount.shouldBeEqualComparingTo(BigDecimal("14980.50"))
                trades.first().price.shouldBeEqualComparingTo(BigDecimal("29972.00"))
                trades.first().fee.shouldBeEqualComparingTo(BigDecimal("38.95"))
            }
        }

        "cleanupDuplicateTrades removes a local estimate but preserves a distinct nearby order" {
            runTest {
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val krakenFill =
                    TradeRecord(
                        timestamp = now.minusMillis(500),
                        pair = "TAOUSD",
                        side = TestFixtures.SELL,
                        symbol = "TAO",
                        volume = BigDecimal("0.07708233"),
                        usdAmount = BigDecimal("16.62393026"),
                        success = true,
                        dryRun = false,
                        price = BigDecimal("215.66460511"),
                        fee = BigDecimal("0.0432"),
                        source = TradeSource.API_FILL,
                    )
                val localEstimate =
                    krakenFill.copy(
                        timestamp = now,
                        volume = BigDecimal("0.07708000"),
                        usdAmount = BigDecimal("16.63"),
                        price = BigDecimal("215.6867"),
                        fee = BigDecimal("0.0998"),
                        slippagePercent = BigDecimal.ZERO,
                        source = TradeSource.LOCAL_ESTIMATE,
                    )
                val distinctOrder =
                    krakenFill.copy(
                        timestamp = now.plusSeconds(1),
                        volume = BigDecimal("0.07000000"),
                        usdAmount = BigDecimal("15.10"),
                    )

                repository.saveTrade(krakenFill)
                repository.saveTrade(localEstimate)
                repository.saveTrade(distinctOrder)

                repository.cleanupDuplicateTrades()

                val trades = repository.getTradesInRange(now.minusSeconds(10), now.plusSeconds(10))
                trades.size shouldBe 2
                trades.any { it.timestamp == krakenFill.timestamp } shouldBe true
                trades.any { it.timestamp == localEstimate.timestamp } shouldBe false
                trades.any { it.timestamp == distinctOrder.timestamp } shouldBe true
            }
        }

        "cleanupDuplicateTrades exercises all duplicate scenarios and branches" {
            runTest {
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)

                val t1 =
                    TradeRecord(
                        timestamp = now,
                        pair = "BTCUSD",
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("1.0"),
                        usdAmount = BigDecimal("60000.0"),
                        success = true,
                        dryRun = false,
                        price = BigDecimal("60000.0"),
                        fee = BigDecimal("100.0"),
                    )
                // The outer scan spans five minutes even though estimate/API matching has its own
                // ten-second constraint; this record exercises the sorted-scan cutoff.
                val t2FarFuture =
                    t1.copy(
                        timestamp = now.plusMillis(300_001),
                        volume = BigDecimal("1.0"),
                    )
                repository.saveTrade(t1)
                repository.saveTrade(t2FarFuture)

                val tDifferentSymbol =
                    t1.copy(
                        timestamp = now.plusMillis(100),
                        symbol = TestFixtures.ETH,
                        pair = TestFixtures.ETHUSD,
                    )
                repository.saveTrade(tDifferentSymbol)

                val tDifferentSide =
                    t1.copy(
                        timestamp = now.plusMillis(200),
                        side = TestFixtures.SELL,
                    )
                repository.saveTrade(tDifferentSide)

                val tPairAlias1 =
                    t1.copy(
                        timestamp = now.plusMillis(300),
                        pair = TestFixtures.XBTUSD,
                    )
                val tPairAlias2 =
                    t1.copy(
                        timestamp = now.plusMillis(400),
                        pair = TestFixtures.XXBTZUSD,
                    )
                repository.saveTrade(tPairAlias1)
                repository.saveTrade(tPairAlias2)

                val tVolDiffers =
                    t1.copy(
                        timestamp = now.plusMillis(500),
                        volume = BigDecimal("1.5"),
                        usdAmount = BigDecimal("90000.0"),
                    )
                repository.saveTrade(tVolDiffers)

                val tFeeRate1 =
                    t1.copy(
                        timestamp = now.plusMillis(600),
                        volume = BigDecimal("1.0"),
                        usdAmount = BigDecimal("1000.0"),
                        fee = BigDecimal("1.0"),
                    )
                val tFeeRate2 =
                    t1.copy(
                        timestamp = now.plusMillis(700),
                        volume = BigDecimal("1.0"),
                        usdAmount = BigDecimal("1000.0"),
                        fee = BigDecimal("1.0001"),
                    )
                repository.saveTrade(tFeeRate1)
                repository.saveTrade(tFeeRate2)

                val tZeroVolume1 =
                    t1.copy(
                        timestamp = now.plusMillis(800),
                        volume = BigDecimal.ZERO,
                        usdAmount = BigDecimal.ZERO,
                        fee = BigDecimal.ZERO,
                    )
                val tZeroVolume2 =
                    t1.copy(
                        timestamp = now.plusMillis(900),
                        volume = BigDecimal.ZERO,
                        usdAmount = BigDecimal.ZERO,
                        fee = BigDecimal.ZERO,
                    )
                repository.saveTrade(tZeroVolume1)
                repository.saveTrade(tZeroVolume2)

                repository.cleanupDuplicateTrades()

                val all = repository.getTradesInRange(now.minusSeconds(1), now.plusSeconds(3600))
                all.size shouldBe 8
            }
        }

        "isHistorySeeded and setHistorySeeded" {
            runTest {
                repository.isHistorySeeded() shouldBe false
                repository.setHistorySeeded(true)
                repository.isHistorySeeded() shouldBe true
                repository.setHistorySeeded(false)
                repository.isHistorySeeded() shouldBe false
            }
        }

        "getTradeSummaryStats returns zero/null values when no data exists" {
            runTest {
                val stats = repository.getTradeSummaryStats()
                stats.latestSnapshotTime shouldBe null
                stats.totalVolumeTraded.shouldBeEqualComparingTo(BigDecimal.ZERO)
                stats.totalFeesPaid.shouldBeEqualComparingTo(BigDecimal.ZERO)
                stats.totalTradesExecuted shouldBe 0L
                stats.avgFeeRatePercent.shouldBeEqualComparingTo(BigDecimal.ZERO)
                stats.avgSlippagePercent shouldBe null
                stats.failedTradeCount shouldBe 0L
                stats.dryRunTradeCount shouldBe 0L
            }
        }

        "save trade round trips expectedPrice source and Kraken trade id" {
            runTest {
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val trade =
                    TradeRecord(
                        timestamp = now,
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("5000.00"),
                        success = true,
                        dryRun = false,
                        price = BigDecimal("50000.00"),
                        fee = BigDecimal("13.0000"),
                        slippagePercent = BigDecimal("0.2500"),
                        expectedPrice = BigDecimal("49875.00"),
                        source = TradeSource.LOCAL_ESTIMATE,
                        tradeId = "TRADE-ROUND-TRIP",
                    )
                repository.saveTrade(trade)

                val loaded = repository.getTradesInRange(now.minusSeconds(1), now.plusSeconds(1)).single()
                loaded.expectedPrice!!.shouldBeEqualComparingTo(BigDecimal("49875.00"))
                loaded.source shouldBe TradeSource.LOCAL_ESTIMATE
                loaded.slippagePercent!!.shouldBeEqualComparingTo(BigDecimal("0.2500"))
                loaded.tradeId shouldBe "TRADE-ROUND-TRIP"
            }
        }

        "getTradeSummaryStats aggregates fee rate slippage and status counts" {
            runTest {
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                repository.saveTrade(
                    TradeRecord(
                        timestamp = now,
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal.ONE,
                        usdAmount = BigDecimal("1000.00"),
                        success = true,
                        dryRun = false,
                        fee = BigDecimal("2.6000"),
                        slippagePercent = BigDecimal("0.1000"),
                        source = TradeSource.API_FILL,
                    ),
                )
                repository.saveTrade(
                    TradeRecord(
                        timestamp = now.plusSeconds(1),
                        pair = TestFixtures.ETHUSD,
                        side = TestFixtures.SELL,
                        symbol = Asset.ETH,
                        volume = BigDecimal.ONE,
                        usdAmount = BigDecimal("500.00"),
                        success = true,
                        dryRun = true,
                        fee = BigDecimal("1.3000"),
                        slippagePercent = BigDecimal("0.2000"),
                    ),
                )
                repository.saveTrade(
                    TradeRecord(
                        timestamp = now.plusSeconds(2),
                        pair = TestFixtures.DOGEUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.DOGE,
                        volume = BigDecimal.TEN,
                        usdAmount = BigDecimal("10.00"),
                        success = false,
                        dryRun = false,
                        fee = BigDecimal.ZERO,
                    ),
                )

                val stats = repository.getTradeSummaryStats(now.minusSeconds(5), now.plusSeconds(5))
                stats.totalTradesExecuted shouldBe 1L
                stats.totalVolumeTraded.shouldBeEqualComparingTo(BigDecimal("1000.00"))
                stats.totalFeesPaid.shouldBeEqualComparingTo(BigDecimal("2.6000"))
                stats.avgFeeRatePercent.shouldBeEqualComparingTo(BigDecimal("0.2600"))
                stats.avgSlippagePercent!!.shouldBeEqualComparingTo(BigDecimal("0.1000"))
                stats.failedTradeCount shouldBe 1L
                stats.dryRunTradeCount shouldBe 1L
            }
        }

        "save wraps non-IOException as IOException" {
            runTest {
                val closedDb = DatabaseConfig.init(TestFixtures.MEMORY_)
                val brokenRepo = SqliteTradeRepositoryImpl(closedDb)

                transaction(closedDb) {
                    exec(TestFixtures.DROP_TABLE_IF_EXISTS_PORTFOLIO_SNAPSHOTS)
                }

                val snapshot =
                    PortfolioSnapshot(
                        timestamp = Instant.now(),
                        totalValueUSD = BigDecimal.ZERO,
                        assets = emptyMap(),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    )

                val thrown =
                    shouldThrow<IOException> {
                        brokenRepo.save(listOf(snapshot))
                    }
                thrown.message shouldBe "Database write failed"
            }
        }

        "saveSnapshot wraps non-IOException as IOException" {
            runTest {
                val closedDb = DatabaseConfig.init(TestFixtures.MEMORY_)
                val brokenRepo = SqliteTradeRepositoryImpl(closedDb)

                transaction(closedDb) {
                    exec(TestFixtures.DROP_TABLE_IF_EXISTS_PORTFOLIO_SNAPSHOTS)
                }

                val snapshot =
                    PortfolioSnapshot(
                        timestamp = Instant.now(),
                        totalValueUSD = BigDecimal.ZERO,
                        assets = emptyMap(),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    )

                val thrown =
                    shouldThrow<IOException> {
                        brokenRepo.saveSnapshot(snapshot)
                    }
                thrown.message shouldBe "Database write failed"
            }
        }

        "saveTrade wraps non-IOException as IOException" {
            runTest {
                val closedDb = DatabaseConfig.init(TestFixtures.MEMORY_)
                val brokenRepo = SqliteTradeRepositoryImpl(closedDb)

                transaction(closedDb) {
                    exec("DROP TABLE IF EXISTS trades")
                }

                val trade =
                    TradeRecord(
                        timestamp = Instant.now(),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("5000.00"),
                        success = true,
                        dryRun = false,
                    )

                val thrown =
                    shouldThrow<IOException> {
                        brokenRepo.saveTrade(trade)
                    }
                thrown.message shouldBe "Database write failed"
            }
        }

        "save rethrows IOException directly without wrapping" {
            runTest {
                // Exposed caches the current transaction per thread; close it so the mocked
                // database must ask the throwing manager for a new transaction.
                TransactionManager.currentOrNull()?.close()

                val realTxManager = db.transactionManager
                val throwingTxManager = TradeThrowingTransactionManager(realTxManager)

                val mockDb = mockk<Database>(relaxed = true)
                mockkStatic(TestFixtures.ORG_JETBRAINS_EXPOSED_SQL_TRANSACTIONS_TRANSACTION_API_KT)
                every { mockDb.transactionManager } returns throwingTxManager

                val ioRepo = SqliteTradeRepositoryImpl(mockDb)
                val snapshot =
                    PortfolioSnapshot(
                        timestamp = Instant.now(),
                        totalValueUSD = BigDecimal.ZERO,
                        assets = emptyMap(),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    )

                val thrown =
                    shouldThrow<IOException> {
                        ioRepo.save(listOf(snapshot))
                    }
                thrown.message shouldBe "Direct IO failure"

                unmockkStatic(TestFixtures.ORG_JETBRAINS_EXPOSED_SQL_TRANSACTIONS_TRANSACTION_API_KT)
            }
        }

        "saveSnapshot rethrows IOException directly without wrapping" {
            runTest {
                // Exposed caches the current transaction per thread; close it so the mocked
                // database must ask the throwing manager for a new transaction.
                TransactionManager.currentOrNull()?.close()

                val realTxManager = db.transactionManager
                val throwingTxManager = TradeThrowingTransactionManager(realTxManager)

                val mockDb = mockk<Database>(relaxed = true)
                mockkStatic(TestFixtures.ORG_JETBRAINS_EXPOSED_SQL_TRANSACTIONS_TRANSACTION_API_KT)
                every { mockDb.transactionManager } returns throwingTxManager

                val ioRepo = SqliteTradeRepositoryImpl(mockDb)
                val snapshot =
                    PortfolioSnapshot(
                        timestamp = Instant.now(),
                        totalValueUSD = BigDecimal.ZERO,
                        assets = emptyMap(),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    )

                val thrown =
                    shouldThrow<IOException> {
                        ioRepo.saveSnapshot(snapshot)
                    }
                thrown.message shouldBe "Direct IO failure"

                unmockkStatic(TestFixtures.ORG_JETBRAINS_EXPOSED_SQL_TRANSACTIONS_TRANSACTION_API_KT)
            }
        }

        "saveTrade rethrows IOException directly without wrapping" {
            runTest {
                // Exposed caches the current transaction per thread; close it so the mocked
                // database must ask the throwing manager for a new transaction.
                TransactionManager.currentOrNull()?.close()

                val realTxManager = db.transactionManager
                val throwingTxManager = TradeThrowingTransactionManager(realTxManager)

                val mockDb = mockk<Database>(relaxed = true)
                mockkStatic(TestFixtures.ORG_JETBRAINS_EXPOSED_SQL_TRANSACTIONS_TRANSACTION_API_KT)
                every { mockDb.transactionManager } returns throwingTxManager

                val ioRepo = SqliteTradeRepositoryImpl(mockDb)
                val trade =
                    TradeRecord(
                        timestamp = Instant.now(),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("5000.00"),
                        success = true,
                        dryRun = false,
                    )

                val thrown =
                    shouldThrow<IOException> {
                        ioRepo.saveTrade(trade)
                    }
                thrown.message shouldBe "Direct IO failure"

                unmockkStatic(TestFixtures.ORG_JETBRAINS_EXPOSED_SQL_TRANSACTIONS_TRANSACTION_API_KT)
            }
        }

        "pruneSnapshotsOlderThan prunes records" {
            runTest {
                val baseTime = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val s1 =
                    PortfolioSnapshot(
                        timestamp = baseTime.minus(100, ChronoUnit.DAYS),
                        totalValueUSD = BigDecimal("1000.00"),
                        assets = emptyMap(),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    )
                val s2 =
                    PortfolioSnapshot(
                        timestamp = baseTime,
                        totalValueUSD = BigDecimal("2000.00"),
                        assets = emptyMap(),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    )
                repository.saveSnapshot(s1)
                repository.saveSnapshot(s2)

                repository.pruneSnapshotsOlderThan(baseTime.minus(90, ChronoUnit.DAYS)) shouldBe 1

                val loaded = repository.load()
                loaded.size shouldBe 1
                loaded.first().timestamp shouldBe baseTime
            }
        }

        "pruneTradesOlderThan prunes records" {
            runTest {
                val baseTime = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val old =
                    TradeRecord(
                        timestamp = baseTime.minus(100, ChronoUnit.DAYS),
                        pair = Asset.BTC_USD_PAIR,
                        side = OrderSide.BUY.name,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.01"),
                        usdAmount = BigDecimal("500.00"),
                        success = true,
                        dryRun = false,
                        fee = BigDecimal("1.00"),
                    )
                val recent =
                    old.copy(
                        timestamp = baseTime,
                        volume = BigDecimal("0.02"),
                        usdAmount = BigDecimal("1000.00"),
                    )
                repository.saveTrade(old)
                repository.saveTrade(recent)

                repository.pruneTradesOlderThan(baseTime.minus(90, ChronoUnit.DAYS)) shouldBe 1

                val remaining =
                    repository.getTradesInRange(
                        baseTime.minus(1, ChronoUnit.DAYS),
                        baseTime.plus(1, ChronoUnit.DAYS),
                    )
                remaining.size shouldBe 1
                remaining.single().timestamp shouldBe baseTime
            }
        }

        "unresolved live submissions survive retention pruning" {
            runTest {
                val pending =
                    TradeRecord(
                        timestamp = Instant.now().minus(100, ChronoUnit.DAYS),
                        pair = Asset.BTC_USD_PAIR,
                        side = OrderSide.BUY.name,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.01"),
                        usdAmount = BigDecimal("500.00"),
                        success = false,
                        dryRun = false,
                        clientOrderId = "74cf3df5-fe0c-4bd7-a884-b630701cfcd8",
                        submissionState = OrderSubmissionState.UNCERTAIN,
                    )
                repository.saveTrade(pending)

                repository.hasPendingSubmissions() shouldBe true
                repository.pruneTradesOlderThan(Instant.now().minus(90, ChronoUnit.DAYS)) shouldBe 0
                repository.hasPendingSubmissions() shouldBe true
            }
        }

        "updateTrade wraps non-IOException as IOException" {
            runTest {
                val closedDb = DatabaseConfig.init(TestFixtures.MEMORY_)
                val brokenRepo = SqliteTradeRepositoryImpl(closedDb)

                transaction(closedDb) {
                    exec("DROP TABLE IF EXISTS trades")
                }

                val trade =
                    TradeRecord(
                        timestamp = Instant.now(),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("5000.00"),
                        success = true,
                        dryRun = false,
                    )

                val thrown =
                    shouldThrow<IOException> {
                        brokenRepo.updateTrade(trade, trade)
                    }
                thrown.message shouldBe "Database update failed"
            }
        }

        "updateTrade rethrows IOException directly without wrapping" {
            runTest {
                TransactionManager.currentOrNull()?.close()

                val realTxManager = db.transactionManager
                val throwingTxManager = TradeThrowingTransactionManager(realTxManager)

                val mockDb = mockk<Database>(relaxed = true)
                mockkStatic(TestFixtures.ORG_JETBRAINS_EXPOSED_SQL_TRANSACTIONS_TRANSACTION_API_KT)
                every { mockDb.transactionManager } returns throwingTxManager

                val ioRepo = SqliteTradeRepositoryImpl(mockDb)
                val trade =
                    TradeRecord(
                        timestamp = Instant.now(),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("5000.00"),
                        success = true,
                        dryRun = false,
                    )

                val thrown =
                    shouldThrow<IOException> {
                        ioRepo.updateTrade(trade, trade)
                    }
                thrown.message shouldBe "Direct IO failure"

                unmockkStatic(TestFixtures.ORG_JETBRAINS_EXPOSED_SQL_TRANSACTIONS_TRANSACTION_API_KT)
            }
        }

        "getSnapshotsInRange returns empty list when no snapshots in range" {
            runTest {
                val inRange = repository.getSnapshotsInRange(Instant.EPOCH, Instant.EPOCH)
                inRange.isEmpty() shouldBe true
            }
        }

        "getTradeSummaryStats aggregates metrics correctly" {
            runTest {
                val startStats = repository.getTradeSummaryStats()
                startStats.totalTradesExecuted shouldBe 0L
                startStats.totalVolumeTraded.shouldBeEqualComparingTo(BigDecimal.ZERO)
                startStats.totalFeesPaid.shouldBeEqualComparingTo(BigDecimal.ZERO)
                startStats.latestSnapshotTime shouldBe null

                val trade1 =
                    TradeRecord(
                        timestamp = Instant.now().minusSeconds(100),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("5000.00"),
                        success = true,
                        dryRun = false,
                        price = BigDecimal("50000.0"),
                        fee = BigDecimal("10.0"),
                    )
                repository.saveTrade(trade1)

                val trade2 =
                    TradeRecord(
                        timestamp = Instant.now(),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.SELL,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.05"),
                        usdAmount = BigDecimal("3000.00"),
                        success = true,
                        dryRun = false,
                        price = BigDecimal("60000.0"),
                        fee = BigDecimal("6.00"),
                    )
                repository.saveTrade(trade2)

                val tradeFailed =
                    TradeRecord(
                        timestamp = Instant.now(),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.5"),
                        usdAmount = BigDecimal("30000.00"),
                        success = false,
                        dryRun = false,
                        price = BigDecimal("60000.0"),
                        fee = BigDecimal("60.00"),
                    )
                repository.saveTrade(tradeFailed)

                val snapshot =
                    PortfolioSnapshot(
                        timestamp = Instant.ofEpochMilli(12345678L),
                        totalValueUSD = BigDecimal("10000.0"),
                        assets = emptyMap(),
                        actions = emptyList(),
                        drawdownPercent = BigDecimal.ZERO,
                        fiatDeploymentPercent = BigDecimal.ZERO,
                        effectiveUsdTargetPercent = BigDecimal.ZERO,
                    )
                repository.saveSnapshot(snapshot)

                val stats = repository.getTradeSummaryStats()
                stats.totalTradesExecuted shouldBe 2L
                stats.totalVolumeTraded.shouldBeEqualComparingTo(BigDecimal("8000.00"))
                stats.totalFeesPaid.shouldBeEqualComparingTo(BigDecimal("16.00"))
                stats.latestSnapshotTime shouldBe Instant.ofEpochMilli(12345678L)
            }
        }

        "save and load sync metadata" {
            runTest {
                repository.getSyncMetadata(TestFixtures.SYNC_KEY) shouldBe null
                repository.setSyncMetadata(TestFixtures.SYNC_KEY, TestFixtures.SYNC_VAL)
                repository.getSyncMetadata(TestFixtures.SYNC_KEY) shouldBe TestFixtures.SYNC_VAL

                repository.setSyncMetadata(TestFixtures.SYNC_KEY, TestFixtures.SYNC_VAL_UPDATED)
                repository.getSyncMetadata(TestFixtures.SYNC_KEY) shouldBe TestFixtures.SYNC_VAL_UPDATED
            }
        }

        "updateTrade targets by primary key when id is present" {
            runTest {
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                repository.saveTrade(
                    TradeRecord(
                        timestamp = now,
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.5"),
                        usdAmount = BigDecimal("15000.00"),
                        success = true,
                        dryRun = false,
                    ),
                )
                val loaded = repository.getTradesInRange(now.minusSeconds(1), now.plusSeconds(1)).single()
                loaded.id shouldNotBe null

                repository.updateTrade(
                    loaded,
                    loaded.copy(
                        volume = BigDecimal("0.48000000"),
                        usdAmount = BigDecimal("14400.00"),
                        price = BigDecimal("30000.00"),
                        fee = BigDecimal("10.00"),
                        source = TradeSource.API_FILL,
                    ),
                )

                val updated = repository.getTradesInRange(now.minusSeconds(1), now.plusSeconds(1)).single()
                updated.id shouldBe loaded.id
                updated.volume.shouldBeEqualComparingTo(BigDecimal("0.48000000"))
                updated.usdAmount.shouldBeEqualComparingTo(BigDecimal("14400.00"))
                updated.price.shouldBeEqualComparingTo(BigDecimal("30000.00"))
                updated.source shouldBe TradeSource.API_FILL
            }
        }

        "getSnapshotsInRange evenly samples 599 snapshots and preserves both endpoints" {
            runTest {
                val base = Instant.parse("2020-01-01T00:00:00Z")
                repeat(599) { i ->
                    repository.saveSnapshot(
                        PortfolioSnapshot(
                            timestamp = base.plusSeconds(i.toLong()),
                            totalValueUSD = BigDecimal.valueOf(1000L + i),
                            assets = emptyMap(),
                            actions = emptyList(),
                            drawdownPercent = BigDecimal.ZERO,
                            fiatDeploymentPercent = BigDecimal.ZERO,
                            effectiveUsdTargetPercent = BigDecimal.ZERO,
                        ),
                    )
                }

                val inRange = repository.getSnapshotsInRange(base, base.plusSeconds(598))
                inRange.size shouldBe 300
                inRange.first().timestamp shouldBe base
                inRange.first().totalValueUSD.shouldBeEqualComparingTo(BigDecimal("1000"))
                inRange[1].timestamp shouldBe base.plusSeconds(2)
                inRange[150].timestamp shouldBe base.plusSeconds(300)
                inRange.last().timestamp shouldBe base.plusSeconds(598)
                inRange.last().totalValueUSD.shouldBeEqualComparingTo(BigDecimal("1598"))
            }
        }

        "cleanupDuplicateTrades is a no-op when no duplicates exist" {
            runTest {
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                repository.saveTrade(
                    TradeRecord(
                        timestamp = now,
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("3000.00"),
                        success = true,
                        dryRun = false,
                        price = BigDecimal("30000.00"),
                        fee = BigDecimal("3.00"),
                    ),
                )
                repository.saveTrade(
                    TradeRecord(
                        timestamp = now.plus(1, ChronoUnit.DAYS),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.SELL,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.05"),
                        usdAmount = BigDecimal("1600.00"),
                        success = true,
                        dryRun = false,
                        price = BigDecimal("32000.00"),
                        fee = BigDecimal("1.60"),
                    ),
                )

                repository.cleanupDuplicateTrades()

                val remaining = repository.getTradesInRange(now.minusSeconds(1), now.plus(2, ChronoUnit.DAYS))
                remaining.size shouldBe 2
            }
        }

        "cleanupDuplicateTrades never deletes an unresolved submission" {
            runTest {
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val unresolved =
                    TradeRecord(
                        timestamp = now,
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("3000.00"),
                        success = false,
                        dryRun = false,
                        price = BigDecimal("30000.00"),
                        source = TradeSource.LOCAL_ESTIMATE,
                        clientOrderId = "74cf3df5-fe0c-4bd7-a884-b630701cfcd8",
                        submissionState = OrderSubmissionState.UNCERTAIN,
                    )
                repository.saveTrade(unresolved)
                repository.saveTrade(
                    unresolved.copy(
                        pair = "XXBTZUSD",
                        clientOrderId = null,
                        submissionState = null,
                    ),
                )

                repository.cleanupDuplicateTrades()

                val remaining = repository.getTradesInRange(now.minusSeconds(1), now.plusSeconds(1))
                remaining.size shouldBe 2
                remaining.any { it.submissionState == OrderSubmissionState.UNCERTAIN } shouldBe true
            }
        }
    }
}
