package com.gemini.krakenbot.repository

import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.impl.SqlitePortfolioStatsRepositoryImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import org.jetbrains.exposed.sql.transactions.transactionManager
import java.io.File
import java.io.IOException

class StatsThrowingTransactionManager(
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
class SqlitePortfolioStatsRepositoryImplTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val objectMapper = jacksonObjectMapper()
    private val db = DatabaseConfig.init(":memory:")
    private val repository = SqlitePortfolioStatsRepositoryImpl(db, objectMapper)

    init {
        "load returns zero when empty" {
            val stats = repository.load()
            stats.allTimeHigh.shouldNotBeNull()
            stats.allTimeHigh.shouldBeEqualComparingTo(BigDecimal.ZERO)
        }

        "save and load stats" {
            val stats = PortfolioStats(BigDecimal("12345.67"))
            repository.save(stats)

            val loaded = repository.load()
            loaded.allTimeHigh.shouldNotBeNull()
            loaded.allTimeHigh.shouldBeEqualComparingTo(BigDecimal("12345.67"))

            // Update stats
            val updated = stats.copy(allTimeHigh = BigDecimal("20000.00"))
            repository.save(updated)

            val loadedUpdated = repository.load()
            loadedUpdated.allTimeHigh.shouldNotBeNull()
            loadedUpdated.allTimeHigh.shouldBeEqualComparingTo(BigDecimal("20000.00"))
        }

        "load returns default stats when database throws exception" {
            // Create a db and drop the stats table to trigger an exception on load
            val brokenDb = DatabaseConfig.init(":memory:")
            val brokenRepo = SqlitePortfolioStatsRepositoryImpl(brokenDb, objectMapper)

            transaction(brokenDb) {
                exec("DROP TABLE IF EXISTS portfolio_stats")
            }

            val result = brokenRepo.load()
            result.allTimeHigh.shouldNotBeNull()
            result.allTimeHigh.shouldBeEqualComparingTo(BigDecimal.ZERO)
        }

        "save wraps non-IOException as IOException" {
            val brokenDb = DatabaseConfig.init(":memory:")
            val brokenRepo = SqlitePortfolioStatsRepositoryImpl(brokenDb, objectMapper)

            transaction(brokenDb) {
                exec("DROP TABLE IF EXISTS portfolio_stats")
            }

            val stats = PortfolioStats(BigDecimal("10000.00"))

            val thrown = shouldThrow<IOException> {
                brokenRepo.save(stats)
            }
            thrown.message shouldBe "Database write failed"
            thrown.cause shouldNotBe null
        }

        "save rethrows IOException directly without wrapping" {
            // Clear current transaction if it exists
            TransactionManager.currentOrNull()?.close()

            val realTxManager = db.transactionManager
            val throwingTxManager = StatsThrowingTransactionManager(realTxManager)

            val mockDb = io.mockk.mockk<Database>(relaxed = true)
            mockkStatic("org.jetbrains.exposed.sql.transactions.TransactionApiKt")
            every { mockDb.transactionManager } returns throwingTxManager

            val ioRepo = SqlitePortfolioStatsRepositoryImpl(mockDb, objectMapper)
            val stats = PortfolioStats(BigDecimal("10000.00"))

            val thrown = shouldThrow<IOException> {
                ioRepo.save(stats)
            }
            thrown.message shouldBe "Direct IO failure"

            unmockkStatic("org.jetbrains.exposed.sql.transactions.TransactionApiKt")
        }

        "load migrates portfolio-stats.json if database is empty" {
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
}
