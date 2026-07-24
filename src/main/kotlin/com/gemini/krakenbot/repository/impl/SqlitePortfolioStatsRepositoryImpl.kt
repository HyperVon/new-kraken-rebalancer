package com.gemini.krakenbot.repository.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.table.PortfolioStatsTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class SqlitePortfolioStatsRepositoryImpl(
    private val database: Database,
    private val objectMapper: ObjectMapper,
    private val statsFilePath: String = "portfolio-stats.json",
) : PortfolioStatsRepository {
    private val log =
        LoggerFactory.getLogger(SqlitePortfolioStatsRepositoryImpl::class.java)

    override suspend fun load(): PortfolioStats = try {
        val dbStats =
            database.readTransactionIO {
                PortfolioStatsTable
                    .selectAll()
                    .firstOrNull()
                    ?.let { PortfolioStats(it[PortfolioStatsTable.allTimeHigh] ?: BigDecimal.ZERO) }
            }
        if (dbStats != null) {
            return dbStats
        }

        val file = File(statsFilePath)
        if (!file.exists()) {
            return PortfolioStats(BigDecimal.ZERO)
        }

        try {
            val fileStats = objectMapper.readValue(file, PortfolioStats::class.java)
            val ath = fileStats?.allTimeHigh ?: return PortfolioStats(BigDecimal.ZERO)

            log.info("Migrating allTimeHigh from stats file: {}", ath)
            database.safeTransactionIO(log, "Failed to migrate portfolio stats from file") {
                PortfolioStatsTable.insert {
                    it[allTimeHigh] = ath
                }
            }

            try {
                val targetPath = File("$statsFilePath.bak").toPath()
                Files.move(file.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING)
                log.info("Renamed stats file to backup successfully.")
            } catch (ex: Exception) {
                log.warn("Failed to rename stats file to backup", ex)
            }

            fileStats
        } catch (e: Exception) {
            log.error("Failed to migrate stats file", e)
            PortfolioStats(BigDecimal.ZERO)
        }
    } catch (e: Exception) {
        log.error("Failed to load portfolio stats", e)
        PortfolioStats(BigDecimal.ZERO)
    }

    override suspend fun save(stats: PortfolioStats) {
        database.safeTransactionIO(log, "Failed to save portfolio stats") {
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
