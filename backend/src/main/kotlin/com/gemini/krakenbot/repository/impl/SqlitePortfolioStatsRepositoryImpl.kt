package com.gemini.krakenbot.repository.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.table.PortfolioStatsTable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
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

    override suspend fun load(): PortfolioStats {
        val dbStats =
            database.safeReadTransactionIO(
                log = log,
                logMessage = "Failed to load portfolio stats from database",
            ) {
                PortfolioStatsTable
                    .selectAll()
                    .where { PortfolioStatsTable.id eq 1 }
                    .firstOrNull()
                    ?.let(PortfolioStatsTable::toModel)
            }
        if (dbStats != null) {
            return dbStats
        }

        return try {
            withContext(Dispatchers.IO) {
                val file = File(statsFilePath)
                if (!file.exists()) {
                    return@withContext PortfolioStats(BigDecimal.ZERO)
                }

                val rawStats = objectMapper.readTree(file)
                val rawAth = rawStats.get("allTimeHigh")
                if (rawAth == null || rawAth.isNull) {
                    return@withContext PortfolioStats(BigDecimal.ZERO)
                }
                val fileStats = objectMapper.treeToValue(rawStats, PortfolioStats::class.java)
                val ath = fileStats?.allTimeHigh ?: return@withContext PortfolioStats(BigDecimal.ZERO)

                log.info("Migrating allTimeHigh from stats file: {}", ath)
                database.safeTransactionIO(log, "Failed to migrate portfolio stats from file") {
                    PortfolioStatsTable.insert {
                        it[id] = 1
                        it[allTimeHigh] = ath
                    }
                }

                try {
                    val targetPath = File("$statsFilePath.bak").toPath()
                    Files.move(file.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING)
                    log.info("Renamed stats file to backup successfully.")
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    log.warn("Failed to rename stats file to backup", ex)
                }

                fileStats
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to migrate stats file", e)
            if (e is IOException) throw e
            throw IOException("Failed to migrate portfolio stats", e)
        }
    }

    override suspend fun save(stats: PortfolioStats) {
        database.safeTransactionIO(log, "Failed to save portfolio stats") {
            val updatedRows = PortfolioStatsTable.update({ PortfolioStatsTable.id eq 1 }) {
                PortfolioStatsTable.applyTo(it, stats)
            }
            if (updatedRows == 0) {
                PortfolioStatsTable.insert {
                    it[id] = 1
                    PortfolioStatsTable.applyTo(it, stats)
                }
            }
        }
    }
}
