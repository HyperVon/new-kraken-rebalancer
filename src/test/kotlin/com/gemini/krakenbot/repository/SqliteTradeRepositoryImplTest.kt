package com.gemini.krakenbot.repository

import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.transactionManager
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

class TradeThrowingTransactionManager(
    private val delegate: TransactionManager
) : TransactionManager by delegate {
    override fun newTransaction(
        isolation: Int,
        readOnly: Boolean,
        outerTransaction: Transaction?
    ): Transaction {
        throw IOException("Direct IO failure")
    }
}

@Suppress("unused")
class SqliteTradeRepositoryImplTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val db = DatabaseConfig.init(":memory:")
    private val repository = SqliteTradeRepositoryImpl(db)

    init {
        "save and load snapshots" {
            val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
            val snapshot = PortfolioSnapshot(
                timestamp = now,
                totalValueUSD = BigDecimal("1000.50"),
                assets = mapOf(
                    "BTC" to PortfolioSnapshot.AssetSnapshot(
                        symbol = Asset("BTC"),
                        balance = BigDecimal("0.5"),
                        price = BigDecimal("18000.00"),
                        valueUSD = BigDecimal("900.00"),
                        targetPercent = BigDecimal("90.0"),
                        currentPercent = BigDecimal("90.0"),
                        deviationPercent = BigDecimal("0.0"),
                        deviationUSD = BigDecimal("0.0")
                    ),
                    "USD" to PortfolioSnapshot.AssetSnapshot(
                        symbol = Asset("USD"),
                        balance = BigDecimal("100.50"),
                        price = BigDecimal.ONE,
                        valueUSD = BigDecimal("100.50"),
                        targetPercent = BigDecimal("10.0"),
                        currentPercent = BigDecimal("10.0"),
                        deviationPercent = BigDecimal("0.0"),
                        deviationUSD = BigDecimal("0.0")
                    )
                ),
                actions = listOf("Action 1", "Action 2"),
                drawdownPercent = BigDecimal("1.25"),
                fiatDeploymentPercent = BigDecimal("10.0"),
                effectiveUsdTargetPercent = BigDecimal("10.0")
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
            
            val btc = first.assets["BTC"]!!
            btc.symbol.value shouldBe "BTC"
            btc.balance.shouldBeEqualComparingTo(BigDecimal("0.5"))
            btc.price.shouldBeEqualComparingTo(BigDecimal("18000.00"))
            btc.valueUSD.shouldBeEqualComparingTo(BigDecimal("900.00"))
        }

        "save trade and queries" {
            val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
            val trade1 = TradeRecord(
                timestamp = now.minusSeconds(10),
                pair = "XBTUSD",
                side = "BUY",
                symbol = "BTC",
                volume = BigDecimal("0.1"),
                usdAmount = BigDecimal("5000.00"),
                success = true,
                dryRun = false,
                fee = BigDecimal("15.50")
            )
            val trade2 = TradeRecord(
                timestamp = now,
                pair = "ETHUSD",
                side = "SELL",
                symbol = "ETH",
                volume = BigDecimal("1.0"),
                usdAmount = BigDecimal("2000.00"),
                success = true,
                dryRun = true,
                fee = BigDecimal("5.25")
            )
            val failedTrade = TradeRecord(
                timestamp = now.plusSeconds(10),
                pair = "DOGEUSD",
                side = "BUY",
                symbol = "DOGE",
                volume = BigDecimal("100.0"),
                usdAmount = BigDecimal("10.00"),
                success = false,
                dryRun = false,
                errorMessage = "API Error",
                fee = BigDecimal("1.50")
            )

            repository.saveTrade(trade1)
            repository.saveTrade(trade2)
            repository.saveTrade(failedTrade)

            repository.getTotalTradeCount() shouldBe 2L // only successful
            repository.getTotalVolumeTraded().shouldBeEqualComparingTo(BigDecimal("7000.00")) // 5000 + 2000
            repository.getTotalFeesPaid().shouldBeEqualComparingTo(BigDecimal("20.75")) // 15.50 + 5.25 (ignores failedTrade)

            val trades = repository.getTradesInRange(now.minusSeconds(20), now.plusSeconds(20))
            trades.size shouldBe 3
            trades[0].pair shouldBe "DOGEUSD" // sorted desc by timestamp
            trades[0].success shouldBe false
            trades[0].errorMessage shouldBe "API Error"
            trades[1].pair shouldBe "ETHUSD"
            trades[1].dryRun shouldBe true
            trades[2].pair shouldBe "XBTUSD"
        }

        "getSnapshotsInRange and boundary times" {
            val baseTime = Instant.now().truncatedTo(ChronoUnit.MILLIS)
            val s1 = PortfolioSnapshot(
                timestamp = baseTime.minusSeconds(10),
                totalValueUSD = BigDecimal("1000.00"),
                assets = emptyMap(),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO
            )
            val s2 = PortfolioSnapshot(
                timestamp = baseTime,
                totalValueUSD = BigDecimal("2000.00"),
                assets = emptyMap(),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO
            )

            repository.saveSnapshot(s1)
            repository.saveSnapshot(s2)

            repository.getLatestSnapshotTime() shouldBe baseTime

            val inRange = repository.getSnapshotsInRange(baseTime.minusSeconds(5), baseTime.plusSeconds(5))
            inRange.size shouldBe 1
            inRange.first().totalValueUSD.shouldBeEqualComparingTo(BigDecimal("2000.00"))
        }

        "legacy save saves snapshots" {
            val snapshot = PortfolioSnapshot(
                timestamp = Instant.now(),
                totalValueUSD = BigDecimal.ZERO,
                assets = emptyMap(),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO
            )
            repository.save(listOf(snapshot))
            repository.load().size shouldBe 1
        }

        "getLatestTradeTime with empty and populated trades" {
            repository.getLatestTradeTime() shouldBe null

            val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
            val trade1 = TradeRecord(
                timestamp = now.minusSeconds(10),
                pair = "XBTUSD",
                side = "BUY",
                symbol = "BTC",
                volume = BigDecimal("0.1"),
                usdAmount = BigDecimal("5000.00"),
                success = true,
                dryRun = false
            )
            val trade2 = TradeRecord(
                timestamp = now,
                pair = "ETHUSD",
                side = "SELL",
                symbol = "ETH",
                volume = BigDecimal("1.0"),
                usdAmount = BigDecimal("2000.00"),
                success = true,
                dryRun = false
            )
            repository.saveTrade(trade1)
            repository.saveTrade(trade2)

            repository.getLatestTradeTime() shouldBe now
        }

        "update trade updates record" {
            val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
            val oldTrade = TradeRecord(
                timestamp = now,
                pair = "XBTUSD",
                side = "BUY",
                symbol = "BTC",
                volume = BigDecimal("0.5"),
                usdAmount = BigDecimal("15000.00"),
                success = true,
                dryRun = false
            )
            repository.saveTrade(oldTrade)

            val newTrade = oldTrade.copy(
                timestamp = now.plusSeconds(3),
                pair = "XXBTZUSD",
                symbol = "BTC",
                volume = BigDecimal("0.49980000"),
                usdAmount = BigDecimal("14980.50"),
                price = BigDecimal("29972.00"),
                fee = BigDecimal("38.95")
            )
            repository.updateTrade(oldTrade, newTrade)

            val trades = repository.getTradesInRange(now.minusSeconds(10), now.plusSeconds(10))
            trades.size shouldBe 1
            trades.first().timestamp shouldBe now.plusSeconds(3)
            trades.first().pair shouldBe "XXBTZUSD"
            trades.first().volume.shouldBeEqualComparingTo(BigDecimal("0.49980000"))
            trades.first().usdAmount.shouldBeEqualComparingTo(BigDecimal("14980.50"))
            trades.first().price.shouldBeEqualComparingTo(BigDecimal("29972.00"))
            trades.first().fee.shouldBeEqualComparingTo(BigDecimal("38.95"))
        }

        "cleanupDuplicateTrades removes a local estimate but preserves a distinct nearby order" {
            val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
            val krakenFill = TradeRecord(
                timestamp = now.minusMillis(500),
                pair = "TAOUSD",
                side = "SELL",
                symbol = "TAO",
                volume = BigDecimal("0.07708233"),
                usdAmount = BigDecimal("16.62393026"),
                success = true,
                dryRun = false,
                price = BigDecimal("215.66460511"),
                fee = BigDecimal("0.0432")
            )
            val localEstimate = krakenFill.copy(
                timestamp = now,
                volume = BigDecimal("0.07708000"),
                usdAmount = BigDecimal("16.63"),
                price = BigDecimal("215.6867"),
                fee = BigDecimal("0.0998")
            )
            val distinctOrder = krakenFill.copy(
                timestamp = now.plusSeconds(1),
                volume = BigDecimal("0.07000000"),
                usdAmount = BigDecimal("15.10")
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

        "cleanupDuplicateTrades exercises all duplicate scenarios and branches" {
            val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)

            // Scenario 1: diff > 300_000 milliseconds (should break early)
            val t1 = TradeRecord(
                timestamp = now,
                pair = "BTCUSD",
                side = "BUY",
                symbol = "BTC",
                volume = BigDecimal("1.0"),
                usdAmount = BigDecimal("60000.0"),
                success = true,
                dryRun = false,
                price = BigDecimal("60000.0"),
                fee = BigDecimal("100.0")
            )
            val t2FarFuture = t1.copy(
                timestamp = now.plusMillis(300_001),
                volume = BigDecimal("1.0")
            )
            repository.saveTrade(t1)
            repository.saveTrade(t2FarFuture)

            // Scenario 2: sameSymbolAndSide is false (different symbol)
            val tDifferentSymbol = t1.copy(
                timestamp = now.plusMillis(100),
                symbol = "ETH",
                pair = "ETHUSD"
            )
            repository.saveTrade(tDifferentSymbol)

            // Scenario 3: sameSymbolAndSide is false (different side)
            val tDifferentSide = t1.copy(
                timestamp = now.plusMillis(200),
                side = "SELL"
            )
            repository.saveTrade(tDifferentSide)

            // Scenario 4: pairAliasDuplicate (same symbol/side, same volume, different pair name)
            val tPairAlias1 = t1.copy(
                timestamp = now.plusMillis(300),
                pair = "XBTUSD"
            )
            val tPairAlias2 = t1.copy(
                timestamp = now.plusMillis(400),
                pair = "XXBTZUSD"
            )
            repository.saveTrade(tPairAlias1)
            repository.saveTrade(tPairAlias2)

            // Scenario 5: localEstimateDuplicate but volume differs by > 1% (should not delete)
            val tVolDiffers = t1.copy(
                timestamp = now.plusMillis(500),
                volume = BigDecimal("1.5"),
                usdAmount = BigDecimal("90000.0")
            )
            repository.saveTrade(tVolDiffers)

            // Scenario 6: localEstimateDuplicate but fee does not differ materially (diff < 0.001)
            // (e.g. both have fee rate = 0.001)
            val tFeeRate1 = t1.copy(
                timestamp = now.plusMillis(600),
                volume = BigDecimal("1.0"),
                usdAmount = BigDecimal("1000.0"),
                fee = BigDecimal("1.0") // rate 0.001
            )
            val tFeeRate2 = t1.copy(
                timestamp = now.plusMillis(700),
                volume = BigDecimal("1.0"),
                usdAmount = BigDecimal("1000.0"),
                fee = BigDecimal("1.0001") // rate 0.0010001 (diff = 0.0000001 < 0.001)
            )
            repository.saveTrade(tFeeRate1)
            repository.saveTrade(tFeeRate2)

            // Scenario 7: isWithinOnePercent and feePercentDiffersMaterially zero checks
            val tZeroVolume1 = t1.copy(
                timestamp = now.plusMillis(800),
                volume = BigDecimal.ZERO,
                usdAmount = BigDecimal.ZERO,
                fee = BigDecimal.ZERO
            )
            val tZeroVolume2 = t1.copy(
                timestamp = now.plusMillis(900),
                volume = BigDecimal.ZERO,
                usdAmount = BigDecimal.ZERO,
                fee = BigDecimal.ZERO
            )
            repository.saveTrade(tZeroVolume1)
            repository.saveTrade(tZeroVolume2)

            repository.cleanupDuplicateTrades()

            val all = repository.getTradesInRange(now.minusSeconds(1), now.plusSeconds(3600))
            all.size shouldBe 6
        }

        "isHistorySeeded and setHistorySeeded" {
            repository.isHistorySeeded() shouldBe false
            repository.setHistorySeeded(true)
            repository.isHistorySeeded() shouldBe true
            repository.setHistorySeeded(false)
            repository.isHistorySeeded() shouldBe false
        }


        "getLatestSnapshotTime returns null when no snapshots exist" {
            repository.getLatestSnapshotTime() shouldBe null
        }

        "getTotalVolumeTraded returns zero when no trades exist" {
            repository.getTotalVolumeTraded().shouldBeEqualComparingTo(BigDecimal.ZERO)
        }

        "getTotalFeesPaid returns zero when no trades exist" {
            repository.getTotalFeesPaid().shouldBeEqualComparingTo(BigDecimal.ZERO)
        }

        "getTotalTradeCount returns zero when no trades exist" {
            repository.getTotalTradeCount() shouldBe 0L
        }

        "save wraps non-IOException as IOException" {
            // Use a closed database to trigger an exception
            val closedDb = DatabaseConfig.init(":memory:")
            val brokenRepo = SqliteTradeRepositoryImpl(closedDb)

            // Drop a required table to trigger a write failure
            transaction(closedDb) {
                exec("DROP TABLE IF EXISTS portfolio_snapshots")
            }

            val snapshot = PortfolioSnapshot(
                timestamp = Instant.now(),
                totalValueUSD = BigDecimal.ZERO,
                assets = emptyMap(),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO
            )

            val thrown = shouldThrow<IOException> {
                brokenRepo.save(listOf(snapshot))
            }
            thrown.message shouldBe "Database write failed"
        }

        "saveSnapshot wraps non-IOException as IOException" {
            val closedDb = DatabaseConfig.init(":memory:")
            val brokenRepo = SqliteTradeRepositoryImpl(closedDb)

            transaction(closedDb) {
                exec("DROP TABLE IF EXISTS portfolio_snapshots")
            }

            val snapshot = PortfolioSnapshot(
                timestamp = Instant.now(),
                totalValueUSD = BigDecimal.ZERO,
                assets = emptyMap(),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO
            )

            val thrown = shouldThrow<IOException> {
                brokenRepo.saveSnapshot(snapshot)
            }
            thrown.message shouldBe "Database write failed"
        }

        "saveTrade wraps non-IOException as IOException" {
            val closedDb = DatabaseConfig.init(":memory:")
            val brokenRepo = SqliteTradeRepositoryImpl(closedDb)

            transaction(closedDb) {
                exec("DROP TABLE IF EXISTS trades")
            }

            val trade = TradeRecord(
                timestamp = Instant.now(),
                pair = "XBTUSD",
                side = "BUY",
                symbol = "BTC",
                volume = BigDecimal("0.1"),
                usdAmount = BigDecimal("5000.00"),
                success = true,
                dryRun = false
            )

            val thrown = shouldThrow<IOException> {
                brokenRepo.saveTrade(trade)
            }
            thrown.message shouldBe "Database write failed"
        }

        "save rethrows IOException directly without wrapping" {
            // Clear current transaction if it exists
            TransactionManager.currentOrNull()?.close()
            
            val realTxManager = db.transactionManager
            val throwingTxManager = TradeThrowingTransactionManager(realTxManager)
            
            val mockDb = mockk<Database>(relaxed = true)
            mockkStatic("org.jetbrains.exposed.sql.transactions.TransactionApiKt")
            every { mockDb.transactionManager } returns throwingTxManager
            
            val ioRepo = SqliteTradeRepositoryImpl(mockDb)
            val snapshot = PortfolioSnapshot(
                timestamp = Instant.now(),
                totalValueUSD = BigDecimal.ZERO,
                assets = emptyMap(),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO
            )

            val thrown = shouldThrow<IOException> {
                ioRepo.save(listOf(snapshot))
            }
            thrown.message shouldBe "Direct IO failure"

            unmockkStatic("org.jetbrains.exposed.sql.transactions.TransactionApiKt")
        }

        "saveSnapshot rethrows IOException directly without wrapping" {
            // Clear current transaction if it exists
            TransactionManager.currentOrNull()?.close()
            
            val realTxManager = db.transactionManager
            val throwingTxManager = TradeThrowingTransactionManager(realTxManager)
            
            val mockDb = mockk<Database>(relaxed = true)
            mockkStatic("org.jetbrains.exposed.sql.transactions.TransactionApiKt")
            every { mockDb.transactionManager } returns throwingTxManager
            
            val ioRepo = SqliteTradeRepositoryImpl(mockDb)
            val snapshot = PortfolioSnapshot(
                timestamp = Instant.now(),
                totalValueUSD = BigDecimal.ZERO,
                assets = emptyMap(),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO
            )

            val thrown = shouldThrow<IOException> {
                ioRepo.saveSnapshot(snapshot)
            }
            thrown.message shouldBe "Direct IO failure"

            unmockkStatic("org.jetbrains.exposed.sql.transactions.TransactionApiKt")
        }

        "saveTrade rethrows IOException directly without wrapping" {
            // Clear current transaction if it exists
            TransactionManager.currentOrNull()?.close()
            
            val realTxManager = db.transactionManager
            val throwingTxManager = TradeThrowingTransactionManager(realTxManager)
            
            val mockDb = mockk<Database>(relaxed = true)
            mockkStatic("org.jetbrains.exposed.sql.transactions.TransactionApiKt")
            every { mockDb.transactionManager } returns throwingTxManager
            
            val ioRepo = SqliteTradeRepositoryImpl(mockDb)
            val trade = TradeRecord(
                timestamp = Instant.now(),
                pair = "XBTUSD",
                side = "BUY",
                symbol = "BTC",
                volume = BigDecimal("0.1"),
                usdAmount = BigDecimal("5000.00"),
                success = true,
                dryRun = false
            )

            val thrown = shouldThrow<IOException> {
                ioRepo.saveTrade(trade)
            }
            thrown.message shouldBe "Direct IO failure"

            unmockkStatic("org.jetbrains.exposed.sql.transactions.TransactionApiKt")
        }

        "pruneSnapshotsOlderThan prunes records" {
            val baseTime = Instant.now().truncatedTo(ChronoUnit.MILLIS)
            val s1 = PortfolioSnapshot(
                timestamp = baseTime.minus(100, ChronoUnit.DAYS),
                totalValueUSD = BigDecimal("1000.00"),
                assets = emptyMap(),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO
            )
            val s2 = PortfolioSnapshot(
                timestamp = baseTime,
                totalValueUSD = BigDecimal("2000.00"),
                assets = emptyMap(),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO
            )
            repository.saveSnapshot(s1)
            repository.saveSnapshot(s2)

            repository.pruneSnapshotsOlderThan(baseTime.minus(90, ChronoUnit.DAYS)) shouldBe 1

            val loaded = repository.load()
            loaded.size shouldBe 1
            loaded.first().timestamp shouldBe baseTime
        }

        "updateTrade wraps non-IOException as IOException" {
            val closedDb = DatabaseConfig.init(":memory:")
            val brokenRepo = SqliteTradeRepositoryImpl(closedDb)

            transaction(closedDb) {
                exec("DROP TABLE IF EXISTS trades")
            }

            val trade = TradeRecord(
                timestamp = Instant.now(),
                pair = "XBTUSD",
                side = "BUY",
                symbol = "BTC",
                volume = BigDecimal("0.1"),
                usdAmount = BigDecimal("5000.00"),
                success = true,
                dryRun = false
            )

            val thrown = shouldThrow<IOException> {
                brokenRepo.updateTrade(trade, trade)
            }
            thrown.message shouldBe "Database update failed"
        }

        "updateTrade rethrows IOException directly without wrapping" {
            TransactionManager.currentOrNull()?.close()
            
            val realTxManager = db.transactionManager
            val throwingTxManager = TradeThrowingTransactionManager(realTxManager)
            
            val mockDb = mockk<Database>(relaxed = true)
            mockkStatic("org.jetbrains.exposed.sql.transactions.TransactionApiKt")
            every { mockDb.transactionManager } returns throwingTxManager
            
            val ioRepo = SqliteTradeRepositoryImpl(mockDb)
            val trade = TradeRecord(
                timestamp = Instant.now(),
                pair = "XBTUSD",
                side = "BUY",
                symbol = "BTC",
                volume = BigDecimal("0.1"),
                usdAmount = BigDecimal("5000.00"),
                success = true,
                dryRun = false
            )

            val thrown = shouldThrow<IOException> {
                ioRepo.updateTrade(trade, trade)
            }
            thrown.message shouldBe "Direct IO failure"

            unmockkStatic("org.jetbrains.exposed.sql.transactions.TransactionApiKt")
        }

        "getSnapshotsInRange returns empty list when no snapshots in range" {
            val inRange = repository.getSnapshotsInRange(Instant.EPOCH, Instant.EPOCH)
            inRange.isEmpty() shouldBe true
        }

        "getTradeSummaryStats aggregates metrics correctly" {
            val startStats = repository.getTradeSummaryStats()
            startStats.totalTradesExecuted shouldBe 0L
            startStats.totalVolumeTraded shouldBe BigDecimal.ZERO
            startStats.totalFeesPaid shouldBe BigDecimal.ZERO
            startStats.latestSnapshotTime shouldBe null

            val trade1 = TradeRecord(
                timestamp = Instant.now().minusSeconds(100),
                pair = "XBTUSD",
                side = "BUY",
                symbol = "BTC",
                volume = BigDecimal("0.1"),
                usdAmount = BigDecimal("5000.00"),
                success = true,
                dryRun = false,
                price = BigDecimal("50000.0"),
                fee = BigDecimal("10.0")
            )
            repository.saveTrade(trade1)

            val trade2 = TradeRecord(
                timestamp = Instant.now(),
                pair = "XBTUSD",
                side = "SELL",
                symbol = "BTC",
                volume = BigDecimal("0.05"),
                usdAmount = BigDecimal("3000.00"),
                success = true,
                dryRun = false,
                price = BigDecimal("60000.0"),
                fee = BigDecimal("6.00")
            )
            repository.saveTrade(trade2)

            // A failed trade should not be included
            val tradeFailed = TradeRecord(
                timestamp = Instant.now(),
                pair = "XBTUSD",
                side = "BUY",
                symbol = "BTC",
                volume = BigDecimal("0.5"),
                usdAmount = BigDecimal("30000.00"),
                success = false,
                dryRun = false,
                price = BigDecimal("60000.0"),
                fee = BigDecimal("60.00")
            )
            repository.saveTrade(tradeFailed)

            val snapshot = PortfolioSnapshot(
                timestamp = Instant.ofEpochMilli(12345678L),
                totalValueUSD = BigDecimal("10000.0"),
                assets = emptyMap(),
                actions = emptyList(),
                drawdownPercent = BigDecimal.ZERO,
                fiatDeploymentPercent = BigDecimal.ZERO,
                effectiveUsdTargetPercent = BigDecimal.ZERO
            )
            repository.saveSnapshot(snapshot)

            val stats = repository.getTradeSummaryStats()
            stats.totalTradesExecuted shouldBe 2L
            stats.totalVolumeTraded.compareTo(BigDecimal("8000.00")) shouldBe 0
            stats.totalFeesPaid.compareTo(BigDecimal("16.00")) shouldBe 0
            stats.latestSnapshotTime shouldBe Instant.ofEpochMilli(12345678L)
        }

        "save and load sync metadata" {
            repository.getSyncMetadata("sync_key") shouldBe null
            repository.setSyncMetadata("sync_key", "sync_val")
            repository.getSyncMetadata("sync_key") shouldBe "sync_val"

            repository.setSyncMetadata("sync_key", "sync_val_updated")
            repository.getSyncMetadata("sync_key") shouldBe "sync_val_updated"
        }
    }
}
