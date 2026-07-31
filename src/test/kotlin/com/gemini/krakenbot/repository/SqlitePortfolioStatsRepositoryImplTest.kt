package com.gemini.krakenbot.repository

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.impl.SqlitePortfolioStatsRepositoryImpl
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
                mockkStatic(TestFixtures.ORG_JETBRAINS_EXPOSED_SQL_TRANSACTIONS_TRANSACTION_API_KT)
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
                    unmockkStatic(TestFixtures.ORG_JETBRAINS_EXPOSED_SQL_TRANSACTIONS_TRANSACTION_API_KT)
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
                    mockkStatic(TestFixtures.ORG_JETBRAINS_EXPOSED_SQL_TRANSACTIONS_TRANSACTION_API_KT)
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
                        unmockkStatic(TestFixtures.ORG_JETBRAINS_EXPOSED_SQL_TRANSACTIONS_TRANSACTION_API_KT)
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
                    exec("INSERT INTO portfolio_stats (all_time_high) VALUES (NULL)")
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
    }
}
