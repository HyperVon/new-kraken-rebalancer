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
import java.math.BigDecimal
import org.jetbrains.exposed.sql.transactions.transactionManager
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

    private val db = DatabaseConfig.init(":memory:")
    private val repository = SqlitePortfolioStatsRepositoryImpl(db)

    init {
        "load returns zero when empty" {
            val stats = repository.load()
            stats.allTimeHigh.shouldNotBeNull()
            stats.allTimeHigh!!.shouldBeEqualComparingTo(BigDecimal.ZERO)
        }

        "save and load stats" {
            val stats = PortfolioStats(BigDecimal("12345.67"))
            repository.save(stats)

            val loaded = repository.load()
            loaded.allTimeHigh.shouldNotBeNull()
            loaded.allTimeHigh!!.shouldBeEqualComparingTo(BigDecimal("12345.67"))

            // Update stats
            stats.allTimeHigh = BigDecimal("20000.00")
            repository.save(stats)

            val loadedUpdated = repository.load()
            loadedUpdated.allTimeHigh.shouldNotBeNull()
            loadedUpdated.allTimeHigh!!.shouldBeEqualComparingTo(BigDecimal("20000.00"))
        }

        "load returns default stats when database throws exception" {
            // Create a db and drop the stats table to trigger an exception on load
            val brokenDb = DatabaseConfig.init(":memory:")
            val brokenRepo = SqlitePortfolioStatsRepositoryImpl(brokenDb)

            transaction(brokenDb) {
                exec("DROP TABLE IF EXISTS portfolio_stats")
            }

            val result = brokenRepo.load()
            result.allTimeHigh.shouldNotBeNull()
            result.allTimeHigh!!.shouldBeEqualComparingTo(BigDecimal.ZERO)
        }

        "save wraps non-IOException as IOException" {
            val brokenDb = DatabaseConfig.init(":memory:")
            val brokenRepo = SqlitePortfolioStatsRepositoryImpl(brokenDb)

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
            
            val ioRepo = SqlitePortfolioStatsRepositoryImpl(mockDb)
            val stats = PortfolioStats(BigDecimal("10000.00"))

            val thrown = shouldThrow<IOException> {
                ioRepo.save(stats)
            }
            thrown.message shouldBe "Direct IO failure"

            unmockkStatic("org.jetbrains.exposed.sql.transactions.TransactionApiKt")
        }
    }
}
