package com.gemini.krakenbot.repository.impl

import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.table.PortfolioStatsTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.io.IOException
import java.math.BigDecimal

class SqlitePortfolioStatsRepositoryImpl(
    private val database: Database,
    private val objectMapper: ObjectMapper
) : PortfolioStatsRepository {

    private val log =
        LoggerFactory.getLogger(SqlitePortfolioStatsRepositoryImpl::class.java)

    override fun load(): PortfolioStats {
        return try {
            transaction(database) {
                val dbStats = PortfolioStatsTable
                    .selectAll()
                    .firstOrNull()
                    ?.let {
                        PortfolioStats(it[PortfolioStatsTable.allTimeHigh])
                    }
                if (dbStats != null) {
                    dbStats
                } else {
                    val file = File("portfolio-stats.json")
                    if (file.exists()) {
                        try {
                            val fileStats = objectMapper.readValue(file, PortfolioStats::class.java)
                            if (fileStats != null && fileStats.allTimeHigh != null) {
                                log.info("Migrating allTimeHigh from portfolio-stats.json: {}", fileStats.allTimeHigh)
                                PortfolioStatsTable.insert {
                                    it[allTimeHigh] = fileStats.allTimeHigh!!
                                }
                                try {
                                    file.renameTo(File("portfolio-stats.json.bak"))
                                    log.info("Renamed portfolio-stats.json to portfolio-stats.json.bak")
                                } catch (ex: Exception) {
                                    log.warn("Failed to rename portfolio-stats.json", ex)
                                }
                                fileStats
                            } else {
                                PortfolioStats(BigDecimal.ZERO)
                            }
                        } catch (e: Exception) {
                            log.error("Failed to migrate portfolio-stats.json", e)
                            PortfolioStats(BigDecimal.ZERO)
                        }
                    } else {
                        PortfolioStats(BigDecimal.ZERO)
                    }
                }
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
