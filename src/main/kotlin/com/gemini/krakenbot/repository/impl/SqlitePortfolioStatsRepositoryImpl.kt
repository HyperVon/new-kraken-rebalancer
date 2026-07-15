package com.gemini.krakenbot.repository.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.table.PortfolioStatsTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class SqlitePortfolioStatsRepositoryImpl(
    private val database: Database,
    private val objectMapper: ObjectMapper,
    private val statsFilePath: String = "portfolio-stats.json"
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
                    val file = File(statsFilePath)
                    if (file.exists()) {
                        try {
                            val fileStats = objectMapper.readValue(file, PortfolioStats::class.java)
                            if (fileStats != null && fileStats.allTimeHigh != null) {
                                log.info("Migrating allTimeHigh from stats file: {}", fileStats.allTimeHigh)
                                PortfolioStatsTable.insert {
                                    it[allTimeHigh] = fileStats.allTimeHigh!!
                                }
                                try {
                                    val sourcePath = file.toPath()
                                    val targetPath = File("$statsFilePath.bak").toPath()
                                    Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
                                    log.info("Renamed stats file to backup successfully.")
                                } catch (ex: Exception) {
                                    log.warn("Failed to rename stats file to backup", ex)
                                }
                                fileStats
                            } else {
                                PortfolioStats(BigDecimal.ZERO)
                            }
                        } catch (e: Exception) {
                            log.error("Failed to migrate stats file", e)
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
        database.safeTransaction(log, "Failed to save portfolio stats") {
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
    }
}
