package com.gemini.krakenbot.repository.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.gemini.krakenbot.model.PortfolioStats
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.AppliedAthFlow
import com.gemini.krakenbot.repository.PortfolioStatsRepository
import com.gemini.krakenbot.repository.table.AthAppliedFlowTable
import com.gemini.krakenbot.repository.table.HistorySyncMetadataTable
import com.gemini.krakenbot.repository.table.PortfolioStatsTable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
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
            database.safeTransactionIO(
                log = log,
                logMessage = "Failed to load portfolio stats from database",
                exceptionMessage = "Database read failed",
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
            upsertStats(stats)
        }
    }

    override suspend fun saveAthStateWithFlowCheckpoint(
        stats: PortfolioStats,
        appliedFlows: List<AppliedAthFlow>,
        flowWatermarkSec: Long?,
    ) {
        database.safeTransactionIO(log, "Failed to save ATH state with flow checkpoint") {
            upsertStats(stats)
            insertJournalIdentities(appliedFlows)
            if (flowWatermarkSec != null) {
                // Observability watermark only: reconciliation is identity-driven
                // (the journal), so journal rows are never pruned by watermark.
                HistorySyncMetadataTable.upsert {
                    it[key] = SyncMetadataKeys.ATH_FLOW_WATERMARK_EPOCH_SEC
                    it[value] = flowWatermarkSec.toString()
                }
            }
        }
    }

    override suspend fun journalPresumedDecidedFlows(flows: List<AppliedAthFlow>) {
        if (flows.isEmpty()) return
        database.safeTransactionIO(log, "Failed to journal presumed-decided ATH flows") {
            insertJournalIdentities(flows)
        }
    }

    /**
     * Insert-if-absent journaling, chunked to stay under SQLite host-variable
     * limits: the identity scan now considers every retained ledger row, so
     * decision batches can exceed the parameter cap of a single `inList`.
     */
    private fun JdbcTransaction.insertJournalIdentities(flows: List<AppliedAthFlow>) {
        for (chunk in flows.distinctBy(AppliedAthFlow::ledgerId).chunked(JOURNAL_QUERY_CHUNK)) {
            val knownIds = AthAppliedFlowTable
                .selectAll()
                .where { AthAppliedFlowTable.ledgerId inList chunk.map(AppliedAthFlow::ledgerId) }
                .map { it[AthAppliedFlowTable.ledgerId] }
                .toSet()
            for (flow in chunk) {
                if (flow.ledgerId !in knownIds) {
                    AthAppliedFlowTable.insertIgnore {
                        it[ledgerId] = flow.ledgerId
                        it[eventTimeSec] = flow.eventTimeSec
                        it[eventTimeMillis] = flow.eventTimeMillis
                        it[decisionCategory] = flow.decisionCategory
                        it[asset] = flow.asset
                        it[actualBalanceDelta] = flow.actualBalanceDelta
                        it[normalizedGroupId] = flow.normalizedGroupId
                        it[decisionVersion] = flow.decisionVersion
                    }
                }
            }
        }
    }

    override suspend fun getAppliedAthFlowIds(ledgerIds: List<String>): Set<String> {
        if (ledgerIds.isEmpty()) return emptySet()
        return database.readTransactionIO {
            ledgerIds.chunked(JOURNAL_QUERY_CHUNK)
                .flatMap { chunk ->
                    AthAppliedFlowTable
                        .selectAll()
                        .where { AthAppliedFlowTable.ledgerId inList chunk }
                        .map { it[AthAppliedFlowTable.ledgerId] }
                }
                .toSet()
        }
    }

    override suspend fun getAppliedAthFlows(ledgerIds: List<String>): List<AppliedAthFlow> =
        database.readTransactionIO {
            ledgerIds.chunked(JOURNAL_QUERY_CHUNK)
                .flatMap { chunk ->
                    AthAppliedFlowTable
                        .selectAll()
                        .where { AthAppliedFlowTable.ledgerId inList chunk }
                        .map { row ->
                            AppliedAthFlow(
                                ledgerId = row[AthAppliedFlowTable.ledgerId],
                                eventTimeSec = row[AthAppliedFlowTable.eventTimeSec],
                                eventTimeMillis = row[AthAppliedFlowTable.eventTimeMillis],
                                decisionCategory = row[AthAppliedFlowTable.decisionCategory],
                                asset = row[AthAppliedFlowTable.asset],
                                actualBalanceDelta = row[AthAppliedFlowTable.actualBalanceDelta],
                                normalizedGroupId = row[AthAppliedFlowTable.normalizedGroupId],
                                decisionVersion = row[AthAppliedFlowTable.decisionVersion],
                            )
                        }
                }
        }

    private fun JdbcTransaction.upsertStats(stats: PortfolioStats) {
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

    private companion object {
        /** Keeps journal `inList` batches under the SQLite host-variable cap. */
        const val JOURNAL_QUERY_CHUNK = 1000
    }
}
