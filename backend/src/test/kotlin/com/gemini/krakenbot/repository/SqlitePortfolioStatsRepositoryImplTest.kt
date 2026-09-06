package com.gemini.krakenbot.repository

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.impl.SqlitePortfolioStatsRepositoryImpl
import com.gemini.krakenbot.repository.impl.SqliteTradeRepositoryImpl
import com.gemini.krakenbot.repository.table.PortfolioStatsTable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.JdbcTransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.transactions.transactionManager
import java.io.File
import java.io.IOException
import java.math.BigDecimal

class StatsThrowingTransactionManager(
    private val delegate: JdbcTransactionManager,
    private val failure: Throwable = IOException("Direct IO failure"),
) : JdbcTransactionManager by delegate {
    override fun newTransaction(
        isolation: Int,
        readOnly: Boolean,
        outerTransaction: JdbcTransaction?,
    ): JdbcTransaction = throw failure
}

class SqlitePortfolioStatsRepositoryImplTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val objectMapper = jacksonObjectMapper()
    private val db = DatabaseConfig.init(TestFixtures.MEMORY_)
    private val repository = SqlitePortfolioStatsRepositoryImpl(db, objectMapper)

    init {
        "load returns zero when empty" {
            runTest {
                val stats = repository.load()
                stats.allTimeHigh.shouldNotBeNull()
                stats.allTimeHigh.shouldBeEqualComparingTo(BigDecimal.ZERO)
            }
        }

        "checkpoint persists stats, flow identities, and watermark atomically" {
            runTest {
                val tradeRepository = SqliteTradeRepositoryImpl(db)
                repository.saveAthStateWithFlowCheckpoint(
                    stats = PortfolioStats(BigDecimal("137500.00"), BigDecimal("20.0000")),
                    appliedFlows = listOf(
                        AppliedAthFlow("dep-1", 1000L),
                        AppliedAthFlow("dep-2", 2000L),
                    ),
                    flowWatermarkSec = 500L,
                )

                val loaded = repository.load()
                loaded.allTimeHigh.shouldBeEqualComparingTo(BigDecimal("137500.00"))
                loaded.lastTrustedDrawdownPct!!.shouldBeEqualComparingTo(BigDecimal("20.0000"))
                repository.getAppliedAthFlowIds(listOf("dep-1", "dep-2", "dep-3")) shouldBe
                    setOf("dep-1", "dep-2")
                tradeRepository.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC) shouldBe "500"
                repository.getAppliedAthFlowIds(emptyList()) shouldBe emptySet()

                // Re-checkpointing known identities is a no-op. The journal is
                // a lifetime decision log: advancing the watermark past
                // applied rows must NOT prune them, because the identity scan
                // relies on retained entries to avoid re-applying late-
                // arriving or historical rows.
                repository.saveAthStateWithFlowCheckpoint(
                    stats = loaded,
                    appliedFlows = listOf(
                        AppliedAthFlow("dep-1", 1000L),
                    ),
                    flowWatermarkSec = 2000L,
                )
                repository.getAppliedAthFlowIds(listOf("dep-1", "dep-2")) shouldBe setOf("dep-1", "dep-2")
                repository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("137500.00"))
            }
        }

        "journalPresumedDecidedFlows inserts identities without touching stats or watermark" {
            runTest {
                val tradeRepository = SqliteTradeRepositoryImpl(db)
                repository.saveAthStateWithFlowCheckpoint(
                    stats = PortfolioStats(BigDecimal("100.00")),
                    appliedFlows = emptyList(),
                    flowWatermarkSec = 9000L,
                )
                repository.journalPresumedDecidedFlows(
                    listOf(
                        AppliedAthFlow("legacy-1", 100L),
                        AppliedAthFlow("legacy-2", 200L),
                        AppliedAthFlow("legacy-1", 100L),
                    ),
                )
                repository.getAppliedAthFlowIds(listOf("legacy-1", "legacy-2")) shouldBe setOf("legacy-1", "legacy-2")
                repository.load().allTimeHigh.shouldBeEqualComparingTo(BigDecimal("100.00"))
                tradeRepository.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC) shouldBe "9000"
            }
        }

        "getAppliedAthFlowIds resolves identity sets larger than one query chunk" {
            runTest {
                val many = (1L..1500L).map { AppliedAthFlow("dep-$it", it) }
                repository.saveAthStateWithFlowCheckpoint(
                    stats = PortfolioStats(BigDecimal("1.00")),
                    appliedFlows = many,
                    flowWatermarkSec = null,
                )
                repository.getAppliedAthFlowIds(many.map { it.ledgerId }) shouldBe
                    many.map { it.ledgerId }.toSet()
            }
        }

        "checkpoint persists durable flow semantics and getAppliedAthFlows round-trips them" {
            runTest {
                val semantic = AppliedAthFlow(
                    ledgerId = "dep-sem",
                    eventTimeSec = 3000L,
                    decisionCategory = "OWNER_CAPITAL",
                    asset = "ZUSD",
                    actualBalanceDelta = BigDecimal("1000.00000000"),
                    normalizedGroupId = "card-ref-1",
                    decisionVersion = 1,
                    eventTimeMillis = 3000123L,
                )
                val identityOnly = AppliedAthFlow("dep-legacy", 4000L)
                repository.saveAthStateWithFlowCheckpoint(
                    stats = PortfolioStats(BigDecimal("1.00")),
                    appliedFlows = listOf(semantic, identityOnly),
                    flowWatermarkSec = 5000L,
                )

                val loaded = repository.getAppliedAthFlows(listOf("dep-sem", "dep-legacy", "missing"))
                    .associateBy { it.ledgerId }
                loaded.keys shouldBe setOf("dep-sem", "dep-legacy")
                loaded.getValue("dep-sem").decisionCategory shouldBe "OWNER_CAPITAL"
                loaded.getValue("dep-sem").asset shouldBe "ZUSD"
                loaded.getValue("dep-sem").actualBalanceDelta!!
                    .shouldBeEqualComparingTo(BigDecimal("1000.00000000"))
                loaded.getValue("dep-sem").normalizedGroupId shouldBe "card-ref-1"
                loaded.getValue("dep-sem").decisionVersion shouldBe 1
                loaded.getValue("dep-sem").eventTimeMillis shouldBe 3000123L
                loaded.getValue("dep-legacy").decisionCategory shouldBe null
                loaded.getValue("dep-legacy").actualBalanceDelta shouldBe null
                loaded.getValue("dep-legacy").eventTimeMillis shouldBe null

                // Insert-if-absent: re-checkpointing a semantic identity keeps
                // the originally journaled decision semantics intact.
                repository.saveAthStateWithFlowCheckpoint(
                    stats = PortfolioStats(BigDecimal("2.00")),
                    appliedFlows = listOf(
                        AppliedAthFlow("dep-sem", 9999L),
                    ),
                    flowWatermarkSec = 6000L,
                )
                repository.getAppliedAthFlows(listOf("dep-sem")).single().let {
                    it.eventTimeSec shouldBe 3000L
                    it.eventTimeMillis shouldBe 3000123L
                }
            }
        }

        for (failureStage in listOf("journal", "commit")) {
            "checkpoint rolls back semantics stats and watermark on $failureStage failure and retries safely" {
                runTest {
                    val tradeRepository = SqliteTradeRepositoryImpl(db)
                    val original = PortfolioStats(BigDecimal("10000.00"), BigDecimal("20.00"))
                    repository.saveAthStateWithFlowCheckpoint(original, emptyList(), 100)
                    val flows = listOf("first", "second").map {
                        AppliedAthFlow(it, 200, "OWNER_CAPITAL", "USD", BigDecimal("1000.00"), decisionVersion = 1)
                    }
                    transaction(db) {
                        if (failureStage == "commit") {
                            // A deferred FK violation permits every checkpoint statement, then fails COMMIT.
                            exec("CREATE TABLE checkpoint_parent (id INTEGER PRIMARY KEY)")
                            exec(
                                "CREATE TABLE checkpoint_failure (id INTEGER REFERENCES checkpoint_parent(id) " +
                                    "DEFERRABLE INITIALLY DEFERRED)",
                            )
                            exec(
                                "CREATE TRIGGER fail_checkpoint AFTER INSERT ON ath_applied_flows " +
                                    "BEGIN INSERT INTO checkpoint_failure VALUES (1); END",
                            )
                        } else {
                            exec(
                                "CREATE TRIGGER fail_checkpoint BEFORE INSERT ON ath_applied_flows " +
                                    "WHEN NEW.ledger_id = 'second' BEGIN SELECT RAISE(ABORT, 'checkpoint test failure'); END",
                            )
                        }
                    }
                    val updated = PortfolioStats(BigDecimal("12000.00"), BigDecimal("10.00"))
                    shouldThrow<IOException> {
                        repository.saveAthStateWithFlowCheckpoint(updated, flows, 300)
                    }
                    repository.load().allTimeHigh.shouldBeEqualComparingTo(original.allTimeHigh)
                    repository.load().lastTrustedDrawdownPct!!.shouldBeEqualComparingTo(
                        original.lastTrustedDrawdownPct!!,
                    )
                    repository.getAppliedAthFlows(flows.map { it.ledgerId }) shouldBe emptyList()
                    tradeRepository.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC) shouldBe "100"
                    transaction(db) { exec("DROP TRIGGER fail_checkpoint") }
                    repeat(2) { repository.saveAthStateWithFlowCheckpoint(updated, flows, 300) }
                    repository.load().allTimeHigh.shouldBeEqualComparingTo(updated.allTimeHigh)
                    repository.load().lastTrustedDrawdownPct!!.shouldBeEqualComparingTo(
                        updated.lastTrustedDrawdownPct!!,
                    )
                    val committed = repository.getAppliedAthFlows(flows.map { it.ledgerId })
                    committed.map { it.ledgerId }.toSet() shouldBe setOf("first", "second")
                    committed.forEach {
                        it.decisionCategory shouldBe "OWNER_CAPITAL"
                        it.actualBalanceDelta!!.shouldBeEqualComparingTo(BigDecimal("1000"))
                    }
                    tradeRepository.getSyncMetadata(SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC) shouldBe "300"
                }
            }
        }

        "save and load stats" {
            runTest {
                val stats = PortfolioStats(BigDecimal("12345.67"))
                repository.save(stats)

                val loaded = repository.load()
                loaded.allTimeHigh.shouldNotBeNull()
                loaded.allTimeHigh.shouldBeEqualComparingTo(BigDecimal("12345.67"))

                val updated = stats.copy(allTimeHigh = BigDecimal("20000.00"))
                repository.save(updated)

                val loadedUpdated = repository.load()
                loadedUpdated.allTimeHigh.shouldNotBeNull()
                loadedUpdated.allTimeHigh.shouldBeEqualComparingTo(BigDecimal("20000.00"))
            }
        }

        "load exposes database read failures instead of resetting ATH to zero" {
            runTest {
                val brokenDb = DatabaseConfig.init(TestFixtures.MEMORY_)
                val brokenRepo = SqlitePortfolioStatsRepositoryImpl(brokenDb, objectMapper)

                transaction(brokenDb) {
                    exec(TestFixtures.DROP_TABLE_IF_EXISTS_PORTFOLIO_STATS)
                }

                shouldThrow<IOException> {
                    brokenRepo.load()
                }
            }
        }

        "load exposes corrupt legacy stats migration instead of resetting ATH to zero" {
            runTest {
                val testFile = File("test-portfolio-stats-corrupt.json")
                val testBakFile = File("test-portfolio-stats-corrupt.json.bak")
                val isolatedDb = DatabaseConfig.init(TestFixtures.MEMORY_)
                val testRepo =
                    SqlitePortfolioStatsRepositoryImpl(isolatedDb, objectMapper, testFile.path)
                try {
                    testFile.delete()
                    testBakFile.delete()
                    testFile.writeText("{not-json")

                    shouldThrow<IOException> {
                        testRepo.load()
                    }

                    testFile.exists() shouldBe true
                    testBakFile.exists() shouldBe false
                    transaction(isolatedDb) {
                        PortfolioStatsTable.selectAll().count() shouldBe 0
                    }
                } finally {
                    testFile.delete()
                    testBakFile.delete()
                }
            }
        }

        "save wraps non-IOException as IOException" {
            runTest {
                val brokenDb = DatabaseConfig.init(TestFixtures.MEMORY_)
                val brokenRepo = SqlitePortfolioStatsRepositoryImpl(brokenDb, objectMapper)

                transaction(brokenDb) {
                    exec(TestFixtures.DROP_TABLE_IF_EXISTS_PORTFOLIO_STATS)
                }

                val stats = PortfolioStats(BigDecimal("10000.00"))

                val thrown =
                    shouldThrow<IOException> {
                        brokenRepo.save(stats)
                    }
                thrown.message shouldBe "Database write failed"
                thrown.cause shouldNotBe null
            }
        }

        "save rethrows IOException directly without wrapping" {
            runTest {
                // Exposed caches the current transaction per thread; close it so the mocked
                // database must ask the throwing manager for a new transaction.
                TransactionManager.currentOrNull()?.close()

                val realTxManager = db.transactionManager
                val throwingTxManager = StatsThrowingTransactionManager(realTxManager)

                val mockDb = mockk<Database>(relaxed = true)
                mockkStatic(TestFixtures.ORG_JETBRAINS_EXPOSED_V1_JDBC_TRANSACTIONS_TRANSACTION_INTERFACE_KT)
                try {
                    every { mockDb.transactionManager } returns throwingTxManager

                    val ioRepo = SqlitePortfolioStatsRepositoryImpl(mockDb, objectMapper)
                    val stats = PortfolioStats(BigDecimal("10000.00"))

                    val thrown =
                        shouldThrow<IOException> {
                            ioRepo.save(stats)
                        }
                    thrown.message shouldBe "Direct IO failure"
                } finally {
                    unmockkStatic(TestFixtures.ORG_JETBRAINS_EXPOSED_V1_JDBC_TRANSACTIONS_TRANSACTION_INTERFACE_KT)
                }
            }
        }

        listOf("load", "save").forEach { operation ->
            "$operation propagates transaction cancellation" {
                runTest {
                    TransactionManager.currentOrNull()?.close()
                    val cancellation = CancellationException("cancel stats transaction")
                    val throwingTxManager = StatsThrowingTransactionManager(db.transactionManager, cancellation)
                    val mockDb = mockk<Database>(relaxed = true)
                    mockkStatic(TestFixtures.ORG_JETBRAINS_EXPOSED_V1_JDBC_TRANSACTIONS_TRANSACTION_INTERFACE_KT)
                    every { mockDb.transactionManager } returns throwingTxManager
                    val cancelledRepo = SqlitePortfolioStatsRepositoryImpl(mockDb, objectMapper)

                    try {
                        val thrown = shouldThrow<CancellationException> {
                            if (operation == "load") {
                                cancelledRepo.load()
                            } else {
                                cancelledRepo.save(PortfolioStats(BigDecimal.TEN))
                            }
                        }
                        thrown shouldBe cancellation
                    } finally {
                        unmockkStatic(TestFixtures.ORG_JETBRAINS_EXPOSED_V1_JDBC_TRANSACTIONS_TRANSACTION_INTERFACE_KT)
                    }
                }
            }
        }

        "load migrates portfolio-stats.json if database is empty" {
            runTest {
                val testFile = File("test-portfolio-stats.json")
                val testBakFile = File("test-portfolio-stats.json.bak")
                val testRepo = SqlitePortfolioStatsRepositoryImpl(db, objectMapper, "test-portfolio-stats.json")
                try {
                    testFile.delete()
                    testBakFile.delete()
                    testFile.writeText("""{"allTimeHigh": 18000.0}""")

                    val stats = testRepo.load()
                    stats.allTimeHigh.shouldNotBeNull()
                    stats.allTimeHigh.shouldBeEqualComparingTo(BigDecimal("18000.00"))

                    val loadedFromDb = testRepo.load()
                    loadedFromDb.allTimeHigh.shouldNotBeNull()
                    loadedFromDb.allTimeHigh.shouldBeEqualComparingTo(BigDecimal("18000.00"))

                    testFile.exists() shouldBe false
                    testBakFile.exists() shouldBe true
                } finally {
                    testFile.delete()
                    testBakFile.delete()
                }
            }
        }

        "load treats null allTimeHigh column as zero" {
            runTest {
                val isolatedDb = DatabaseConfig.init(TestFixtures.MEMORY_)
                val isolatedRepo = SqlitePortfolioStatsRepositoryImpl(isolatedDb, objectMapper)
                transaction(isolatedDb) {
                    exec("INSERT INTO portfolio_stats (id, all_time_high) VALUES (1, NULL)")
                }

                val stats = isolatedRepo.load()
                stats.allTimeHigh.shouldBeEqualComparingTo(BigDecimal.ZERO)
            }
        }

        "load migrates stats file with null allTimeHigh as zero without inserting" {
            runTest {
                val testFile = File("test-portfolio-stats-null.json")
                val testBakFile = File("test-portfolio-stats-null.json.bak")
                val isolatedDb = DatabaseConfig.init(TestFixtures.MEMORY_)
                val testRepo =
                    SqlitePortfolioStatsRepositoryImpl(isolatedDb, objectMapper, "test-portfolio-stats-null.json")
                try {
                    testFile.delete()
                    testBakFile.delete()
                    testFile.writeText("""{"allTimeHigh": null}""")

                    val stats = testRepo.load()
                    stats.allTimeHigh.shouldBeEqualComparingTo(BigDecimal.ZERO)

                    // Null ATH short-circuits before migration insert/rename
                    testFile.exists() shouldBe true
                    testBakFile.exists() shouldBe false
                    transaction(isolatedDb) {
                        PortfolioStatsTable.selectAll().count() shouldBe 0
                    }
                } finally {
                    testFile.delete()
                    testBakFile.delete()
                }
            }
        }

        "load treats a stats file without allTimeHigh as zero without inserting" {
            runTest {
                val testFile = File("test-portfolio-stats-missing-ath.json")
                val testBakFile = File("test-portfolio-stats-missing-ath.json.bak")
                val isolatedDb = DatabaseConfig.init(TestFixtures.MEMORY_)
                val testRepo =
                    SqlitePortfolioStatsRepositoryImpl(isolatedDb, objectMapper, testFile.path)
                try {
                    testFile.delete()
                    testBakFile.delete()
                    testFile.writeText("{}")

                    val stats = testRepo.load()
                    stats.allTimeHigh.shouldBeEqualComparingTo(BigDecimal.ZERO)

                    testFile.exists() shouldBe true
                    testBakFile.exists() shouldBe false
                    transaction(isolatedDb) {
                        PortfolioStatsTable.selectAll().count() shouldBe 0
                    }
                } finally {
                    testFile.delete()
                    testBakFile.delete()
                }
            }
        }
    }
}
