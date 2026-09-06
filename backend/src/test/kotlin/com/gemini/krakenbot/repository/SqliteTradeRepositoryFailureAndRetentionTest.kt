package com.gemini.krakenbot.repository

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.OrderSubmissionState
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeReconciliationConflictException
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.repository.table.ActionLogTable
import com.gemini.krakenbot.repository.table.AssetSnapshotTable
import com.gemini.krakenbot.repository.table.PortfolioSnapshotTable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
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

                val snapshot = TestFixtures.emptySnapshot(Instant.now(), BigDecimal.ZERO)

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

                val snapshot = TestFixtures.emptySnapshot(Instant.now(), BigDecimal.ZERO)

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
                mockkStatic(TestFixtures.ORG_JETBRAINS_EXPOSED_V1_JDBC_TRANSACTIONS_TRANSACTION_INTERFACE_KT)
                try {
                    every { mockDb.transactionManager } returns throwingTxManager

                    val ioRepo = SqliteTradeRepositoryImpl(mockDb)
                    val snapshot = TestFixtures.emptySnapshot(Instant.now(), BigDecimal.ZERO)

                    val thrown =
                        shouldThrow<IOException> {
                            ioRepo.save(listOf(snapshot))
                        }
                    thrown.message shouldBe "Direct IO failure"
                } finally {
                    unmockkStatic(TestFixtures.ORG_JETBRAINS_EXPOSED_V1_JDBC_TRANSACTIONS_TRANSACTION_INTERFACE_KT)
                }
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
                mockkStatic(TestFixtures.ORG_JETBRAINS_EXPOSED_V1_JDBC_TRANSACTIONS_TRANSACTION_INTERFACE_KT)
                try {
                    every { mockDb.transactionManager } returns throwingTxManager

                    val ioRepo = SqliteTradeRepositoryImpl(mockDb)
                    val snapshot = TestFixtures.emptySnapshot(Instant.now(), BigDecimal.ZERO)

                    val thrown =
                        shouldThrow<IOException> {
                            ioRepo.saveSnapshot(snapshot)
                        }
                    thrown.message shouldBe "Direct IO failure"
                } finally {
                    unmockkStatic(TestFixtures.ORG_JETBRAINS_EXPOSED_V1_JDBC_TRANSACTIONS_TRANSACTION_INTERFACE_KT)
                }
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
                mockkStatic(TestFixtures.ORG_JETBRAINS_EXPOSED_V1_JDBC_TRANSACTIONS_TRANSACTION_INTERFACE_KT)
                try {
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
                } finally {
                    unmockkStatic(TestFixtures.ORG_JETBRAINS_EXPOSED_V1_JDBC_TRANSACTIONS_TRANSACTION_INTERFACE_KT)
                }
            }
        }

        "pruneSnapshotsOlderThan prunes records" {
            runTest {
                val baseTime = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val s1 = TestFixtures.emptySnapshot(baseTime.minus(100, ChronoUnit.DAYS), BigDecimal("1000.00"))
                val s2 = TestFixtures.emptySnapshot(baseTime, BigDecimal("2000.00"))
                repository.saveSnapshot(s1)
                repository.saveSnapshot(s2)

                repository.pruneSnapshotsOlderThan(baseTime.minus(90, ChronoUnit.DAYS)) shouldBe 1

                val loaded = repository.load()
                loaded.size shouldBe 1
                loaded.first().timestamp shouldBe baseTime
            }
        }

        "pruneSnapshotsOlderThan removes child rows with the parent" {
            runTest {
                val baseTime = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val oldSnapshot = TestFixtures.emptySnapshot(
                    timestamp = baseTime.minus(100, ChronoUnit.DAYS),
                    totalValueUSD = BigDecimal("1000.00"),
                ).copy(
                    assets = mapOf(
                        Asset.BTC to TestFixtures.assetSnapshot(
                            symbol = Asset.BTC,
                            balance = BigDecimal("0.1"),
                            price = BigDecimal("10000.00"),
                            valueUSD = BigDecimal("1000.00"),
                            targetPercent = BigDecimal("100.00"),
                        ),
                    ),
                    actions = listOf("old action"),
                )
                val recentSnapshot = oldSnapshot.copy(timestamp = baseTime, actions = listOf("recent action"))
                repository.saveSnapshot(oldSnapshot)
                repository.saveSnapshot(recentSnapshot)

                repository.pruneSnapshotsOlderThan(baseTime.minus(90, ChronoUnit.DAYS)) shouldBe 1

                transaction(db) {
                    PortfolioSnapshotTable.selectAll().count() shouldBe 1
                    AssetSnapshotTable.selectAll().count() shouldBe 1
                    ActionLogTable.selectAll().count() shouldBe 1
                }
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

        "pruneSnapshotsOlderThan retains inception snapshot and prunes routine snapshots older than 90 days" {
            runTest {
                val baseTime = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val preInceptionOld = TestFixtures.emptySnapshot(
                    baseTime.minus(120, ChronoUnit.DAYS),
                    BigDecimal("1000.00"),
                )
                val inceptionOld = TestFixtures.emptySnapshot(
                    baseTime.minus(100, ChronoUnit.DAYS),
                    BigDecimal("1500.00"),
                )
                val postInceptionOld = TestFixtures.emptySnapshot(
                    baseTime.minus(95, ChronoUnit.DAYS),
                    BigDecimal("1600.00"),
                )
                val recent = TestFixtures.emptySnapshot(baseTime, BigDecimal("2000.00"))

                val id1 = repository.saveSnapshot(preInceptionOld)
                val id2 = repository.saveSnapshot(inceptionOld)
                val id3 = repository.saveSnapshot(postInceptionOld)
                val id4 = repository.saveSnapshot(recent)

                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_SNAPSHOT_ID,
                    id2.toString(),
                )
                repository.setSyncMetadata(
                    SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS,
                    inceptionOld.timestamp.toEpochMilli().toString(),
                )

                // Cutoff is 90 days ago.
                // preInceptionOld is 120 days ago -> pruned!
                // postInceptionOld is 95 days ago (routine snapshot) -> pruned to prevent unbounded DB growth!
                // inceptionOld is 100 days ago (exact INCEPTION_SNAPSHOT_ID) -> strictly retained!
                // recent is 0 days ago (>= cutoff) -> retained!
                val pruned = repository.pruneSnapshotsOlderThan(baseTime.minus(90, ChronoUnit.DAYS))
                pruned shouldBe 2

                val loaded = repository.load()
                loaded.size shouldBe 2
                loaded.any { it.timestamp == preInceptionOld.timestamp } shouldBe false
                loaded.any { it.timestamp == inceptionOld.timestamp } shouldBe true
                loaded.any { it.timestamp == postInceptionOld.timestamp } shouldBe false
                loaded.any { it.timestamp == recent.timestamp } shouldBe true
            }
        }

        "exact anchor snapshot ID survives pruning even if taken >5s before nominal inception" {
            runTest {
                val baseTime = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val nominalInception = baseTime.minus(100, ChronoUnit.DAYS)
                // Anchor snapshot was taken 60 seconds before first rebalance trades
                val anchorSnap = TestFixtures.emptySnapshot(
                    nominalInception.minusSeconds(60),
                    BigDecimal("1500.00"),
                )
                val routineOld = TestFixtures.emptySnapshot(
                    baseTime.minus(95, ChronoUnit.DAYS),
                    BigDecimal("1600.00"),
                )
                val recent = TestFixtures.emptySnapshot(baseTime, BigDecimal("2000.00"))

                val anchorId = repository.saveSnapshot(anchorSnap)
                val routineId = repository.saveSnapshot(routineOld)
                val recentId = repository.saveSnapshot(recent)

                repository.setSyncMetadata(
                    SyncMetadataKeys.INCEPTION_SNAPSHOT_ID,
                    anchorId.toString(),
                )
                repository.setSyncMetadata(
                    SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS,
                    nominalInception.toEpochMilli().toString(),
                )

                val pruned = repository.pruneSnapshotsOlderThan(baseTime.minus(90, ChronoUnit.DAYS))
                pruned shouldBe 1

                val loaded = repository.load()
                loaded.size shouldBe 2
                loaded.any { it.timestamp == anchorSnap.timestamp } shouldBe true
                loaded.any { it.timestamp == routineOld.timestamp } shouldBe false
                loaded.any { it.timestamp == recent.timestamp } shouldBe true
            }
        }

        "pruneSnapshotsOlderThan behaves deterministically when no inception metadata exists (migration)" {
            runTest {
                val baseTime = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val old1 = TestFixtures.emptySnapshot(baseTime.minus(100, ChronoUnit.DAYS), BigDecimal("1000.00"))
                val old2 = TestFixtures.emptySnapshot(baseTime.minus(95, ChronoUnit.DAYS), BigDecimal("1200.00"))
                val recent = TestFixtures.emptySnapshot(baseTime, BigDecimal("2000.00"))

                repository.saveSnapshot(old1)
                repository.saveSnapshot(old2)
                repository.saveSnapshot(recent)

                // No INCEPTION_SNAPSHOT_ID or DETECTED_INCEPTION_EPOCH_MS metadata present yet
                val pruned = repository.pruneSnapshotsOlderThan(baseTime.minus(90, ChronoUnit.DAYS))
                pruned shouldBe 2

                val loaded = repository.load()
                loaded.size shouldBe 1
                loaded.single().timestamp shouldBe recent.timestamp
            }
        }

        "pruneSnapshotsOlderThan falls back to timestamp match when INCEPTION_SNAPSHOT_ID is not set" {
            runTest {
                val baseTime = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val nominalInception = baseTime.minus(100, ChronoUnit.DAYS)
                val anchorSnap = TestFixtures.emptySnapshot(nominalInception, BigDecimal("1500.00"))
                val routineOld = TestFixtures.emptySnapshot(baseTime.minus(95, ChronoUnit.DAYS), BigDecimal("1600.00"))
                val recent = TestFixtures.emptySnapshot(baseTime, BigDecimal("2000.00"))

                repository.saveSnapshot(anchorSnap)
                repository.saveSnapshot(routineOld)
                repository.saveSnapshot(recent)

                // Only DETECTED_INCEPTION_EPOCH_MS is set, no INCEPTION_SNAPSHOT_ID
                repository.setSyncMetadata(
                    SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS,
                    nominalInception.toEpochMilli().toString(),
                )

                val pruned = repository.pruneSnapshotsOlderThan(baseTime.minus(90, ChronoUnit.DAYS))
                pruned shouldBe 1

                val loaded = repository.load()
                loaded.size shouldBe 2
                loaded.any { it.timestamp == anchorSnap.timestamp } shouldBe true
                loaded.any { it.timestamp == routineOld.timestamp } shouldBe false
                loaded.any { it.timestamp == recent.timestamp } shouldBe true
            }
        }

        "getSnapshotId returns id when snapshot exists and null when not found" {
            runTest {
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val snapshot = TestFixtures.emptySnapshot(now, BigDecimal("1000.00"))
                val id = repository.saveSnapshot(snapshot)

                repository.getSnapshotId(now) shouldBe id
                repository.getSnapshotId(now.minusSeconds(3600)) shouldBe null
            }
        }

        "pruneTradesOlderThan retains trades at or after inception epoch" {
            runTest {
                val baseTime = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val preInceptionTrade = TestFixtures.tradeRecord(
                    timestamp = baseTime.minus(120, ChronoUnit.DAYS),
                    pair = Asset.BTC_USD_PAIR,
                    side = OrderSide.BUY.name,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.01"),
                    usdAmount = BigDecimal("500.00"),
                )
                val inceptionTrade = preInceptionTrade.copy(
                    timestamp = baseTime.minus(100, ChronoUnit.DAYS),
                )
                val postInceptionTrade = preInceptionTrade.copy(
                    timestamp = baseTime.minus(95, ChronoUnit.DAYS),
                )
                val recentTrade = preInceptionTrade.copy(
                    timestamp = baseTime,
                )

                repository.saveTrade(preInceptionTrade)
                repository.saveTrade(inceptionTrade)
                repository.saveTrade(postInceptionTrade)
                repository.saveTrade(recentTrade)

                repository.setSyncMetadata(
                    SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS,
                    inceptionTrade.timestamp.toEpochMilli().toString(),
                )

                // Cutoff is 90 days ago.
                // preInceptionTrade (< inception - 5s) -> pruned (1)!
                // inceptionTrade (>= inception - 5s) -> retained!
                // postInceptionTrade (>= inception - 5s) -> retained!
                // recentTrade (>= cutoff) -> retained!
                val pruned = repository.pruneTradesOlderThan(baseTime.minus(90, ChronoUnit.DAYS))
                pruned shouldBe 1

                val allTrades = repository.getTradesInRange(
                    baseTime.minus(150, ChronoUnit.DAYS),
                    baseTime.plus(1, ChronoUnit.DAYS),
                )
                allTrades.size shouldBe 3
                allTrades.any { it.timestamp == preInceptionTrade.timestamp } shouldBe false
                allTrades.any { it.timestamp == inceptionTrade.timestamp } shouldBe true
                allTrades.any { it.timestamp == postInceptionTrade.timestamp } shouldBe true
                allTrades.any { it.timestamp == recentTrade.timestamp } shouldBe true
            }
        }

        "pruneTradesOlderThan prunes routine bot trades older than 90 days" {
            runTest {
                val baseTime = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val botTradeOld = TestFixtures.tradeRecord(
                    timestamp = baseTime.minus(95, ChronoUnit.DAYS),
                    pair = Asset.BTC_USD_PAIR,
                    side = OrderSide.BUY.name,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.01"),
                    usdAmount = BigDecimal("500.00"),
                ).copy(cycleId = "cycle-123", clientOrderId = "cl-456")

                val manualTradeOld = TestFixtures.tradeRecord(
                    timestamp = baseTime.minus(95, ChronoUnit.DAYS),
                    pair = Asset.BTC_USD_PAIR,
                    side = OrderSide.BUY.name,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.01"),
                    usdAmount = BigDecimal("500.00"),
                ).copy(cycleId = null, clientOrderId = null)

                repository.saveTrade(botTradeOld)
                repository.saveTrade(manualTradeOld)

                repository.setSyncMetadata(
                    SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS,
                    baseTime.minus(100, ChronoUnit.DAYS).toEpochMilli().toString(),
                )

                // Bot trade older than 90 days is pruned; manual trade at or after inception is retained
                val pruned = repository.pruneTradesOlderThan(baseTime.minus(90, ChronoUnit.DAYS))
                pruned shouldBe 1

                val remaining = repository.getTradesInRange(
                    baseTime.minus(150, ChronoUnit.DAYS),
                    baseTime.plus(1, ChronoUnit.DAYS),
                )
                remaining.size shouldBe 1
                remaining.single().clientOrderId shouldBe null
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
                mockkStatic(TestFixtures.ORG_JETBRAINS_EXPOSED_V1_JDBC_TRANSACTIONS_TRANSACTION_INTERFACE_KT)
                try {
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
                } finally {
                    unmockkStatic(TestFixtures.ORG_JETBRAINS_EXPOSED_V1_JDBC_TRANSACTIONS_TRANSACTION_INTERFACE_KT)
                }
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
                    TestFixtures.emptySnapshot(Instant.ofEpochMilli(12345678L), BigDecimal("10000.0"))
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

        "updateTrade rejects a missing primary key without mutating any row" {
            runTest {
                val oldTrade = TestFixtures.tradeRecord(
                    timestamp = Instant.now().truncatedTo(ChronoUnit.MILLIS),
                    pair = TestFixtures.XBTUSD,
                    side = TestFixtures.BUY,
                    symbol = Asset.BTC,
                    volume = BigDecimal("0.5"),
                    usdAmount = BigDecimal("15000.00"),
                    id = 999_999,
                )

                shouldThrow<TradeReconciliationConflictException> {
                    repository.updateTrade(oldTrade, oldTrade.copy(source = TradeSource.API_FILL))
                }
                repository.getTradesInRange(Instant.EPOCH, Instant.now()).isEmpty() shouldBe true
            }
        }

        "getSnapshotsInRange evenly samples 599 snapshots and preserves both endpoints" {
            runTest {
                val base = Instant.parse("2020-01-01T00:00:00Z")
                repeat(599) { i ->
                    repository.saveSnapshot(
                        TestFixtures.emptySnapshot(base.plusSeconds(i.toLong()), BigDecimal.valueOf(1000L + i)),
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
                        pair = TestFixtures.XXBTZUSD,
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

        "deleteTrade successfully removes an unprotected trade" {
            runTest {
                val tradeId = repository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = Instant.now(),
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("3000.00"),
                    ),
                )
                repository.deleteTrade(tradeId) shouldBe true
                repository.deleteTrade(tradeId) shouldBe false
            }
        }

        "deleteTrade rejects deletion of a protected trade linked to an unresolved intent" {
            runTest {
                val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                val tradeId = repository.saveTrade(
                    TestFixtures.tradeRecord(
                        timestamp = now,
                        pair = TestFixtures.XBTUSD,
                        side = TestFixtures.BUY,
                        symbol = Asset.BTC,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("3000.00"),
                        success = false,
                        submissionState = OrderSubmissionState.PENDING,
                    ),
                )

                val intentRepo = com.gemini.krakenbot.repository.impl.SqliteOrderIntentRepositoryImpl(db)
                intentRepo.savePending(
                    com.gemini.krakenbot.model.OrderIntent(
                        cycleId = "cycle-test",
                        clientOrderId = "cl-protect-test",
                        pair = TestFixtures.XBTUSD,
                        symbol = Asset.BTC,
                        side = TestFixtures.BUY,
                        volume = BigDecimal("0.1"),
                        usdAmount = BigDecimal("3000.00"),
                        expectedPrice = BigDecimal("30000.00"),
                        createdAt = now,
                        state = com.gemini.krakenbot.model.OrderIntentState.PENDING,
                        localTradeId = tradeId,
                    ),
                )

                val ex = shouldThrow<IOException> {
                    repository.deleteTrade(tradeId)
                }
                ex.message shouldBe "Database write failed"
            }
        }
    }
}
