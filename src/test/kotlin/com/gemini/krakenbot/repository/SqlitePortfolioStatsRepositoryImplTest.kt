package com.gemini.krakenbot.repository

import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.impl.SqlitePortfolioStatsRepositoryImpl
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

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
    }
}
