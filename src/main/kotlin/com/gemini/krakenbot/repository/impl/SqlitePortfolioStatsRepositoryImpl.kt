package com.gemini.krakenbot.repository.impl

import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.table.PortfolioStatsTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.io.IOException
import java.math.BigDecimal

class SqlitePortfolioStatsRepositoryImpl(
    private val database: Database
) : PortfolioStatsRepository {

    private val log =
        LoggerFactory.getLogger(SqlitePortfolioStatsRepositoryImpl::class.java)

    override fun load(): PortfolioStats {
        return try {
            transaction(database) {
                PortfolioStatsTable
                    .selectAll()
                    .firstOrNull()
                    ?.let {
                        PortfolioStats(it[PortfolioStatsTable.allTimeHigh])
                    }
                    ?: PortfolioStats(BigDecimal.ZERO)
            }
        } catch (e: Exception) {
            log.error("Failed to load portfolio stats", e)
            PortfolioStats(BigDecimal.ZERO)
        }
    }

    override fun save(stats: PortfolioStats) {
        try {
            transaction(database) {
                val existing = PortfolioStatsTable.selectAll().firstOrNull()
                if (existing != null) {
                    PortfolioStatsTable.update({
                        PortfolioStatsTable.id eq existing[PortfolioStatsTable.id]
                    }) {
                        it[allTimeHigh] = stats.allTimeHigh
                    }
                } else {
                    PortfolioStatsTable.insert {
                        it[allTimeHigh] = stats.allTimeHigh
                    }
                }
            }
        } catch (e: Exception) {
            log.error("Failed to save portfolio stats", e)
            if (e is IOException) throw e
            throw IOException("Database write failed", e)
        }
    }
}
