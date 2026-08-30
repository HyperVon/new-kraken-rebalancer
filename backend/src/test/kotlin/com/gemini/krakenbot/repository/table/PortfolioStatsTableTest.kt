package com.gemini.krakenbot.repository.table

import com.gemini.krakenbot.TestFixtures
import com.gemini.krakenbot.config.DatabaseConfig
import com.gemini.krakenbot.model.PortfolioStats
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal

@Suppress("unused")
class PortfolioStatsTableTest : StringSpec() {
    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "applyTo and toModel round-trip portfolio stats fields" {
            val db = DatabaseConfig.init(TestFixtures.MEMORY_)
            val original = PortfolioStats(allTimeHigh = BigDecimal("150000.50"))

            transaction(db) {
                PortfolioStatsTable.insert {
                    PortfolioStatsTable.applyTo(it, original)
                }

                val row = PortfolioStatsTable.selectAll().single()
                val loaded = PortfolioStatsTable.toModel(row)

                loaded.allTimeHigh.shouldBeEqualComparingTo(original.allTimeHigh)
            }
        }
    }
}
