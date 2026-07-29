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

class SqliteTradeRepositoryFailureAndRetentionTest : SqliteTradeRepositoryTestBase() {

    init {
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
                    TestFixtures.tradeRecord(
                        timestamp = Instant.now(),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("5000.00"),
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
                    TestFixtures.tradeRecord(
                        timestamp = Instant.now(),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("5000.00"),
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
                    TestFixtures.tradeRecord(
                        timestamp = baseTime.minus(100, ChronoUnit.DAYS),
                        pair = Asset.BTC_USD_PAIR,
                        side = OrderSide.BUY.name,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.01"),
                        usdAmount = BigDecimal("500.00"),
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
                    TestFixtures.tradeRecord(
                        timestamp = Instant.now().minus(100, ChronoUnit.DAYS),
                        pair = Asset.BTC_USD_PAIR,
                        side = OrderSide.BUY.name,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.01"),
                        usdAmount = BigDecimal("500.00"),
                        success = false,
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
                    TestFixtures.tradeRecord(
                        timestamp = Instant.now(),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("5000.00"),
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
                    TestFixtures.tradeRecord(
                        timestamp = Instant.now(),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("5000.00"),
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
                    TestFixtures.tradeRecord(
                        timestamp = Instant.now().minusSeconds(100),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("5000.00"),
                        price = BigDecimal("50000.0"),
                        fee = BigDecimal("10.0"),
                    )
                repository.saveTrade(trade1)

                val trade2 =
                    TestFixtures.tradeRecord(
                        timestamp = Instant.now(),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.SELL,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.05"),
                        usdAmount = BigDecimal("3000.00"),
                        price = BigDecimal("60000.0"),
                        fee = BigDecimal("6.00"),
                    )
                repository.saveTrade(trade2)

                val tradeFailed =
                    TestFixtures.tradeRecord(
                        timestamp = Instant.now(),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.5"),
                        usdAmount = BigDecimal("30000.00"),
                        success = false,
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
                    TestFixtures.tradeRecord(
                        timestamp = now,
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.5"),
                        usdAmount = BigDecimal("15000.00"),
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
                    TestFixtures.tradeRecord(
                        timestamp = now,
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("3000.00"),
                        price = BigDecimal("30000.00"),
                        fee = BigDecimal("3.00"),
                    ),
                )
                repository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = now.plus(1, ChronoUnit.DAYS),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.SELL,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.05"),
                        usdAmount = BigDecimal("1600.00"),
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
                    TestFixtures.tradeRecord(
                        timestamp = now,
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("3000.00"),
                        success = false,
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
