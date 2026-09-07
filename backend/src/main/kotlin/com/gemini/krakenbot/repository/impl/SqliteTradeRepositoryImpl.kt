package com.gemini.krakenbot.repository.impl

import com.gemini.krakenbot.model.InceptionCandidateEvidence
import com.gemini.krakenbot.model.InceptionInferenceEvidence
import com.gemini.krakenbot.model.OrderIntentState
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeReconciliationConflictException
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.repository.TradeSummaryStats
import com.gemini.krakenbot.repository.table.ActionLogTable
import com.gemini.krakenbot.repository.table.AssetSnapshotTable
import com.gemini.krakenbot.repository.table.HistorySyncMetadataTable
import com.gemini.krakenbot.repository.table.InceptionInferenceCandidateTable
import com.gemini.krakenbot.repository.table.InceptionInferenceTable
import com.gemini.krakenbot.repository.table.OrderIntentTable
import com.gemini.krakenbot.repository.table.PortfolioSnapshotTable
import com.gemini.krakenbot.repository.table.TradeTable
import com.gemini.krakenbot.util.PrecisionConstants
import com.gemini.krakenbot.util.TradeDeduplicator
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.avg
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

class SqliteTradeRepositoryImpl(private val database: Database) : TradeRepository {
    private companion object {
        const val MAX_SNAPSHOT_POINTS = 300
        const val SQLITE_IN_CHUNK_SIZE = 500
        const val DELIMITER = ","
    }

    private val log =
        LoggerFactory.getLogger(SqliteTradeRepositoryImpl::class.java)

    override suspend fun save(history: List<PortfolioSnapshot>) {
        database.safeTransactionIO(log, "Failed to save history to database") {
            for (snapshot in history) {
                insertSnapshotWithChildren(snapshot)
            }
        }
    }

    override suspend fun replaceSnapshots(history: List<PortfolioSnapshot>) {
        database.safeTransactionIO(log, "Failed to replace snapshot history") {
            val snapshotIds = PortfolioSnapshotTable.select(PortfolioSnapshotTable.id)
                .map { it[PortfolioSnapshotTable.id] }
            if (snapshotIds.isNotEmpty()) {
                // Children first even with ON DELETE CASCADE — keeps SQLite FK order explicit.
                snapshotIds.chunked(SQLITE_IN_CHUNK_SIZE).forEach { chunk ->
                    AssetSnapshotTable.deleteWhere { snapshotId inList chunk }
                    ActionLogTable.deleteWhere { snapshotId inList chunk }
                    PortfolioSnapshotTable.deleteWhere { id inList chunk }
                }
            }
            for (snapshot in history) {
                insertSnapshotWithChildren(snapshot)
            }
        }
    }

    override suspend fun load(): List<PortfolioSnapshot> = database.readTransactionIO {
        val snapshotRows =
            PortfolioSnapshotTable
                .selectAll()
                .orderBy(PortfolioSnapshotTable.timestamp, SortOrder.DESC)
                .limit(50)
                .toList()

        buildSnapshotsFromRows(snapshotRows)
    }

    override suspend fun getLatestSnapshot(): PortfolioSnapshot? = database.readTransactionIO {
        val latestRow = PortfolioSnapshotTable
            .selectAll()
            .orderBy(PortfolioSnapshotTable.timestamp, SortOrder.DESC)
            .limit(1)
            .toList()
        buildSnapshotsFromRows(latestRow).firstOrNull()
    }

    override suspend fun saveSnapshot(snapshot: PortfolioSnapshot): Int =
        database.safeTransactionIO(log, "Failed to save snapshot to database") {
            insertSnapshotWithChildren(snapshot)
        }

    override suspend fun saveTrade(trade: TradeRecord): Int =
        database.safeTransactionIO(log, "Failed to save trade to database") {
            TradeTable.insert {
                TradeTable.applyTo(it, trade)
            }[TradeTable.id]
        }

    override suspend fun updateTrade(oldTrade: TradeRecord, newTrade: TradeRecord) {
        database.safeTransactionIO(log, "Failed to update trade in database", "Database update failed") {
            val oldTradeId = oldTrade.id ?: run {
                val candidateIds = TradeTable.select(TradeTable.id)
                    .where {
                        (TradeTable.timestamp eq oldTrade.timestamp.toEpochMilli()) and
                            (TradeTable.pair eq oldTrade.pair) and
                            (TradeTable.side eq OrderSide.normalize(oldTrade.side)) and
                            (TradeTable.volume eq oldTrade.volume)
                    }
                    .limit(2)
                    .map { it[TradeTable.id] }
                if (candidateIds.size != 1) {
                    throw TradeReconciliationConflictException(
                        "Expected one trade reconciliation candidate, found ${candidateIds.size}.",
                    )
                }
                candidateIds.single()
            }
            val updatedRows = TradeTable.update({ TradeTable.id eq oldTradeId }) {
                TradeTable.applyTo(it, newTrade)
            }
            if (updatedRows != 1) {
                throw TradeReconciliationConflictException(
                    "Expected to update one trade row, but updated $updatedRows for trade ${oldTrade.id}.",
                )
            }
        }
    }

    override suspend fun deleteTrade(id: Int): Boolean =
        database.safeTransactionIO(log, "Failed to delete trade from database") {
            val protectedTradeIds = protectedTradeIds()
            if (id in protectedTradeIds) {
                throw IllegalStateException("Cannot delete protected trade $id linked to unresolved order intent.")
            }
            TradeTable.deleteWhere { TradeTable.id eq id } == 1
        }

    override suspend fun hasPendingSubmissions(): Boolean = database.readTransactionIO {
        TradeTable
            .selectAll()
            .where {
                TradeTable.submissionState.isNotNull() and
                    (TradeTable.dryRun eq false)
            }
            .any()
    }

    override suspend fun getSnapshotsInRange(from: Instant, to: Instant): List<PortfolioSnapshot> =
        database.readTransactionIO {
            val allIds =
                PortfolioSnapshotTable
                    .select(PortfolioSnapshotTable.id)
                    .where {
                        (PortfolioSnapshotTable.timestamp greaterEq from.toEpochMilli()) and
                            (PortfolioSnapshotTable.timestamp lessEq to.toEpochMilli())
                    }.orderBy(PortfolioSnapshotTable.timestamp, SortOrder.ASC)
                    .map { it[PortfolioSnapshotTable.id] }

            if (allIds.isEmpty()) return@readTransactionIO emptyList()

            // Keep both range endpoints while evenly sampling the interior for stable chart payloads.
            val downsampledIds =
                if (allIds.size <= MAX_SNAPSHOT_POINTS) {
                    allIds
                } else {
                    List(MAX_SNAPSHOT_POINTS) { sampleIndex ->
                        val sourceIndex =
                            (
                                sampleIndex.toLong() * allIds.lastIndex.toLong() /
                                    (MAX_SNAPSHOT_POINTS - 1).toLong()
                                ).toInt()
                        allIds[sourceIndex]
                    }
                }

            val snapshotRows =
                PortfolioSnapshotTable
                    .selectAll()
                    .where { PortfolioSnapshotTable.id inList downsampledIds }
                    .orderBy(PortfolioSnapshotTable.timestamp, SortOrder.ASC)
                    .toList()

            buildSnapshotsFromRows(snapshotRows)
        }

    override suspend fun getSnapshotBefore(timestamp: Instant): PortfolioSnapshot? = database.readTransactionIO {
        val rows =
            PortfolioSnapshotTable
                .selectAll()
                .where { PortfolioSnapshotTable.timestamp less timestamp.toEpochMilli() }
                .orderBy(PortfolioSnapshotTable.timestamp, SortOrder.DESC)
                .limit(1)
                .toList()
        buildSnapshotsFromRows(rows).firstOrNull()
    }

    override suspend fun getSnapshotId(timestamp: Instant): Int? = database.readTransactionIO {
        PortfolioSnapshotTable
            .select(PortfolioSnapshotTable.id)
            .where { PortfolioSnapshotTable.timestamp eq timestamp.toEpochMilli() }
            .limit(1)
            .firstOrNull()
            ?.get(PortfolioSnapshotTable.id)
    }

    override suspend fun getSnapshotById(id: Int): PortfolioSnapshot? = database.readTransactionIO {
        val row = PortfolioSnapshotTable
            .selectAll()
            .where { PortfolioSnapshotTable.id eq id }
            .limit(1)
            .toList()
        buildSnapshotsFromRows(row).firstOrNull()
    }

    override suspend fun saveSnapshotWithMetadata(
        snapshot: PortfolioSnapshot,
        metadata: Map<String, String>,
        snapshotIdMetadataKeys: Set<String>,
    ): Int = database.safeTransactionIO(log, "Failed to persist inception baseline and evidence") {
        val snapshotId = insertSnapshotWithChildren(snapshot)
        val allMetadata = metadata + snapshotIdMetadataKeys.associateWith { snapshotId.toString() }
        allMetadata.forEach { (key, value) ->
            HistorySyncMetadataTable.upsert {
                it[HistorySyncMetadataTable.key] = key
                it[HistorySyncMetadataTable.value] = value
            }
        }
        snapshotId
    }

    override suspend fun setSyncMetadataAtomically(metadata: Map<String, String>) {
        if (metadata.isEmpty()) return
        database.safeTransactionIO(log, "Failed to persist inception inference metadata") {
            metadata.forEach { (key, value) ->
                HistorySyncMetadataTable.upsert {
                    it[HistorySyncMetadataTable.key] = key
                    it[HistorySyncMetadataTable.value] = value
                }
            }
        }
    }

    override suspend fun saveInceptionInferenceEvidence(
        evidence: InceptionInferenceEvidence,
        metadata: Map<String, String>,
    ) {
        database.safeTransactionIO(log, "Failed to persist inception inference evidence") {
            InceptionInferenceTable.upsert {
                it[fingerprint] = evidence.fingerprint
                it[evidenceDigest] = evidence.evidenceDigest
                it[modelVersion] = evidence.modelVersion
                it[coverageStartEpochMs] = evidence.coverageStart?.toEpochMilli()
                it[coverageEndEpochMs] = evidence.coverageEnd?.toEpochMilli()
                it[horizonEpochSec] = evidence.horizon?.epochSecond
                it[firstPositiveEpochMs] = evidence.firstPositive?.toEpochMilli()
                it[inferredStartEpochMs] = evidence.inferredStart?.toEpochMilli()
                it[inferredWindowStartEpochMs] = evidence.inferredWindowStart?.toEpochMilli()
                it[inferredWindowEndEpochMs] = evidence.inferredWindowEnd?.toEpochMilli()
                it[strongestObservedStartEpochMs] = evidence.strongestObservedStart?.toEpochMilli()
                it[strength] = evidence.strength
                it[reasons] = evidence.reasons.joinToString(DELIMITER)
                it[contradictions] = evidence.contradictions.joinToString(DELIMITER)
                it[unsupportedMarketCount] = evidence.unsupportedMarketCount
                it[unsupportedMarketSamples] = evidence.unsupportedMarketSamples.joinToString(DELIMITER)
                it[competingCandidateCount] = evidence.competingCandidateCount
            }
            InceptionInferenceCandidateTable.deleteWhere {
                InceptionInferenceCandidateTable.fingerprint eq evidence.fingerprint
            }
            evidence.candidates.forEachIndexed { position, candidate ->
                InceptionInferenceCandidateTable.insert {
                    it[fingerprint] = evidence.fingerprint
                    it[this.position] = position
                    it[observedStartEpochMs] = candidate.observedStart.toEpochMilli()
                    it[observedEndEpochMs] = candidate.observedEnd.toEpochMilli()
                    it[windowStartEpochMs] = candidate.windowStart.toEpochMilli()
                    it[windowEndEpochMs] = candidate.windowEnd.toEpochMilli()
                    it[strength] = candidate.strength
                    it[reasons] = candidate.reasons.joinToString(DELIMITER)
                    it[contradictions] = candidate.contradictions.joinToString(DELIMITER)
                    it[assetCount] = candidate.assetCount
                    it[assetSymbols] = candidate.assetSymbols.joinToString(DELIMITER)
                    it[orderCount] = candidate.orderCount
                    it[repeatedEvidenceCount] = candidate.repeatedEvidenceCount
                    it[timescalesSeconds] = candidate.timescalesSeconds.joinToString(DELIMITER)
                }
            }
            metadata.forEach { (key, value) ->
                HistorySyncMetadataTable.upsert {
                    it[HistorySyncMetadataTable.key] = key
                    it[HistorySyncMetadataTable.value] = value
                }
            }
        }
    }

    override suspend fun findInceptionInferenceEvidence(fingerprint: String): InceptionInferenceEvidence? =
        database.readTransactionIO {
            val record = InceptionInferenceTable
                .selectAll()
                .where { InceptionInferenceTable.fingerprint eq fingerprint }
                .singleOrNull() ?: return@readTransactionIO null
            val candidates = InceptionInferenceCandidateTable
                .selectAll()
                .where { InceptionInferenceCandidateTable.fingerprint eq fingerprint }
                .orderBy(InceptionInferenceCandidateTable.position, SortOrder.ASC)
                .map(::toCandidateEvidence)
            toInferenceEvidence(record, candidates)
        }

    private fun toCandidateEvidence(row: ResultRow): InceptionCandidateEvidence = InceptionCandidateEvidence(
        observedStart = Instant.ofEpochMilli(row[InceptionInferenceCandidateTable.observedStartEpochMs]),
        observedEnd = Instant.ofEpochMilli(row[InceptionInferenceCandidateTable.observedEndEpochMs]),
        windowStart = Instant.ofEpochMilli(row[InceptionInferenceCandidateTable.windowStartEpochMs]),
        windowEnd = Instant.ofEpochMilli(row[InceptionInferenceCandidateTable.windowEndEpochMs]),
        strength = row[InceptionInferenceCandidateTable.strength],
        reasons = splitList(row[InceptionInferenceCandidateTable.reasons]),
        contradictions = splitList(row[InceptionInferenceCandidateTable.contradictions]),
        assetCount = row[InceptionInferenceCandidateTable.assetCount],
        assetSymbols = splitList(row[InceptionInferenceCandidateTable.assetSymbols]),
        orderCount = row[InceptionInferenceCandidateTable.orderCount],
        repeatedEvidenceCount = row[InceptionInferenceCandidateTable.repeatedEvidenceCount],
        timescalesSeconds = splitList(row[InceptionInferenceCandidateTable.timescalesSeconds])
            .mapNotNull(String::toLongOrNull).toSet(),
    )

    private fun toInferenceEvidence(
        row: ResultRow,
        candidates: List<InceptionCandidateEvidence>,
    ): InceptionInferenceEvidence = InceptionInferenceEvidence(
        fingerprint = row[InceptionInferenceTable.fingerprint],
        evidenceDigest = row[InceptionInferenceTable.evidenceDigest],
        modelVersion = row[InceptionInferenceTable.modelVersion],
        coverageStart = row[InceptionInferenceTable.coverageStartEpochMs]?.let(Instant::ofEpochMilli),
        coverageEnd = row[InceptionInferenceTable.coverageEndEpochMs]?.let(Instant::ofEpochMilli),
        horizon = row[InceptionInferenceTable.horizonEpochSec]?.let(Instant::ofEpochSecond),
        firstPositive = row[InceptionInferenceTable.firstPositiveEpochMs]?.let(Instant::ofEpochMilli),
        inferredStart = row[InceptionInferenceTable.inferredStartEpochMs]?.let(Instant::ofEpochMilli),
        inferredWindowStart = row[InceptionInferenceTable.inferredWindowStartEpochMs]?.let(Instant::ofEpochMilli),
        inferredWindowEnd = row[InceptionInferenceTable.inferredWindowEndEpochMs]?.let(Instant::ofEpochMilli),
        strongestObservedStart = row[InceptionInferenceTable.strongestObservedStartEpochMs]?.let(Instant::ofEpochMilli),
        strength = row[InceptionInferenceTable.strength],
        reasons = splitList(row[InceptionInferenceTable.reasons]),
        contradictions = splitList(row[InceptionInferenceTable.contradictions]),
        unsupportedMarketCount = row[InceptionInferenceTable.unsupportedMarketCount],
        unsupportedMarketSamples = splitList(row[InceptionInferenceTable.unsupportedMarketSamples]),
        competingCandidateCount = row[InceptionInferenceTable.competingCandidateCount],
        candidates = candidates,
    )

    private fun splitList(value: String?): List<String> =
        value?.takeIf { it.isNotBlank() }?.split(DELIMITER)?.map(String::trim).orEmpty()

    override suspend fun getTradesInRange(from: Instant, to: Instant): List<TradeRecord> = database.readTransactionIO {
        TradeTable
            .selectAll()
            .where {
                (TradeTable.timestamp greaterEq from.toEpochMilli()) and
                    (TradeTable.timestamp lessEq to.toEpochMilli())
            }.orderBy(TradeTable.timestamp, SortOrder.DESC)
            .map(TradeTable::toModel)
    }

    override suspend fun getTradeSummaryStats(): TradeSummaryStats = getTradeSummaryStats(Instant.EPOCH, Instant.now())

    override suspend fun getTradeSummaryStats(from: Instant, to: Instant): TradeSummaryStats =
        database.readTransactionIO {
            val countCol = TradeTable.id.count()
            val volumeCol = TradeTable.usdAmount.sum()
            val feeCol = TradeTable.fee.sum()

            val fromMillis = from.toEpochMilli()
            val toMillis = to.toEpochMilli()
            val tradeInRange =
                (TradeTable.timestamp greaterEq fromMillis) and
                    (TradeTable.timestamp lessEq toMillis)
            val snapshotInRange =
                (PortfolioSnapshotTable.timestamp greaterEq fromMillis) and
                    (PortfolioSnapshotTable.timestamp lessEq toMillis)
            val executedFilter =
                (TradeTable.success eq true) and
                    (TradeTable.dryRun eq false) and
                    tradeInRange

            val tradeRow =
                TradeTable
                    .select(countCol, volumeCol, feeCol)
                    .where { executedFilter }
                    .firstOrNull()

            val totalTrades = tradeRow?.get(countCol) ?: 0L
            val totalVolume = tradeRow?.get(volumeCol) ?: BigDecimal.ZERO
            val totalFees = tradeRow?.get(feeCol) ?: BigDecimal.ZERO

            val avgFeeRatePercent =
                if (totalVolume.signum() == 0) {
                    BigDecimal.ZERO
                } else {
                    totalFees
                        .divide(totalVolume, PrecisionConstants.SCALE_PERCENT + 2, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(PrecisionConstants.HUNDRED_INT.toLong()))
                        .setScale(PrecisionConstants.SCALE_PERCENT, RoundingMode.HALF_UP)
                }

            val slippageAvgCol = TradeTable.slippagePercent.avg()
            val slippageRow =
                TradeTable
                    .select(slippageAvgCol)
                    .where {
                        executedFilter and TradeTable.slippagePercent.isNotNull()
                    }.firstOrNull()
            val avgSlippagePercent = slippageRow?.get(slippageAvgCol)?.setScale(
                PrecisionConstants.SCALE_PERCENT,
                RoundingMode.HALF_UP,
            )

            val failedCountCol = TradeTable.id.count()
            val failedTradeCount =
                TradeTable
                    .select(failedCountCol)
                    .where {
                        (TradeTable.success eq false) and tradeInRange
                    }.firstOrNull()
                    ?.get(failedCountCol) ?: 0L

            val dryRunCountCol = TradeTable.id.count()
            val dryRunTradeCount =
                TradeTable
                    .select(dryRunCountCol)
                    .where {
                        (TradeTable.dryRun eq true) and tradeInRange
                    }.firstOrNull()
                    ?.get(dryRunCountCol) ?: 0L

            val periodHighCol = PortfolioSnapshotTable.totalValueUSD.max()
            val periodHigh =
                PortfolioSnapshotTable
                    .select(periodHighCol)
                    .where { snapshotInRange }
                    .firstOrNull()
                    ?.get(periodHighCol)

            val latestSnapshotTime =
                PortfolioSnapshotTable
                    .select(PortfolioSnapshotTable.timestamp)
                    .where { snapshotInRange }
                    .orderBy(PortfolioSnapshotTable.timestamp, SortOrder.DESC)
                    .limit(1)
                    .firstOrNull()
                    ?.let { Instant.ofEpochMilli(it[PortfolioSnapshotTable.timestamp]) }

            TradeSummaryStats(
                totalTradesExecuted = totalTrades,
                totalVolumeTraded = totalVolume,
                totalFeesPaid = totalFees,
                latestSnapshotTime = latestSnapshotTime,
                periodHigh = periodHigh,
                avgFeeRatePercent = avgFeeRatePercent,
                avgSlippagePercent = avgSlippagePercent,
                failedTradeCount = failedTradeCount,
                dryRunTradeCount = dryRunTradeCount,
            )
        }

    private fun insertSnapshotWithChildren(snapshot: PortfolioSnapshot): Int {
        val snapshotId =
            PortfolioSnapshotTable.insert {
                PortfolioSnapshotTable.applyTo(it, snapshot)
            }[PortfolioSnapshotTable.id]

        for ((_, assetSnapshot) in snapshot.assets) {
            AssetSnapshotTable.insert {
                AssetSnapshotTable.applyTo(it, snapshotId, assetSnapshot)
            }
        }

        for (action in snapshot.actions) {
            ActionLogTable.insert {
                ActionLogTable.applyTo(it, snapshotId, action)
            }
        }
        return snapshotId
    }

    private fun buildSnapshotsFromRows(rows: List<ResultRow>): List<PortfolioSnapshot> {
        if (rows.isEmpty()) return emptyList()
        val snapshotIds = rows.map { it[PortfolioSnapshotTable.id] }

        val allAssetSnapshots =
            AssetSnapshotTable
                .selectAll()
                .where { AssetSnapshotTable.snapshotId inList snapshotIds }
                .groupBy { it[AssetSnapshotTable.snapshotId] }

        val allActionLogs =
            ActionLogTable
                .selectAll()
                .where { ActionLogTable.snapshotId inList snapshotIds }
                .orderBy(ActionLogTable.id, SortOrder.ASC)
                .groupBy { it[ActionLogTable.snapshotId] }

        return rows.map { row ->
            val snapshotId = row[PortfolioSnapshotTable.id]
            val assetRows = allAssetSnapshots[snapshotId] ?: emptyList()
            val actionRows = allActionLogs[snapshotId] ?: emptyList()

            val assetSnapshots = assetRows.associate(AssetSnapshotTable::toModel)
            val actions = actionRows.map { it[ActionLogTable.message] }

            PortfolioSnapshotTable.toModel(row, assetSnapshots, actions)
        }
    }

    override suspend fun getLatestTradeTime(): Instant? = database.readTransactionIO {
        // Only successful non-dry-run rows can advance the exchange-fill cursor. Failed attempts
        // and dry-run estimates never settled, so using either could skip older Kraken history.
        TradeTable
            .selectAll()
            .where {
                (TradeTable.success eq true) and
                    (TradeTable.dryRun eq false)
            }
            .orderBy(TradeTable.timestamp, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.let {
                Instant.ofEpochMilli(it[TradeTable.timestamp])
            }
    }

    override suspend fun hasAnyTradeRows(): Boolean = database.readTransactionIO {
        TradeTable
            .selectAll()
            .limit(1)
            .firstOrNull() != null
    }

    override suspend fun isHistorySeeded(): Boolean = getSyncMetadata(SyncMetadataKeys.HISTORY_SEEDED) == "true"

    override suspend fun setHistorySeeded(seeded: Boolean) {
        setSyncMetadata(SyncMetadataKeys.HISTORY_SEEDED, seeded.toString())
    }

    override suspend fun getSyncMetadata(key: String): String? = database.readSyncMetadata(key)

    override suspend fun setSyncMetadata(key: String, value: String) {
        database.writeSyncMetadata(key, value, log, "Failed to upsert sync metadata")
    }

    override suspend fun pruneSnapshotsOlderThan(cutoff: Instant): Int =
        database.safeTransactionIO(log, "Failed to prune old snapshots") {
            val cutoffMillis = cutoff.toEpochMilli()
            val inceptionSnapshotId = readSyncMetadataInTransaction(
                SyncMetadataKeys.INCEPTION_SNAPSHOT_ID,
            )?.toIntOrNull()
            val inceptionEpochMs = readSyncMetadataInTransaction(
                SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS,
            )?.toLongOrNull()
            val idsToDelete =
                PortfolioSnapshotTable
                    .select(PortfolioSnapshotTable.id, PortfolioSnapshotTable.timestamp)
                    .where { PortfolioSnapshotTable.timestamp less cutoffMillis }
                    .filterNot { row ->
                        val id = row[PortfolioSnapshotTable.id]
                        val ts = row[PortfolioSnapshotTable.timestamp]
                        id == inceptionSnapshotId ||
                            (inceptionSnapshotId == null && inceptionEpochMs != null && ts == inceptionEpochMs)
                    }
                    .map { it[PortfolioSnapshotTable.id] }

            if (idsToDelete.isNotEmpty()) {
                // Children first even with ON DELETE CASCADE — keeps SQLite FK order explicit.
                idsToDelete.chunked(SQLITE_IN_CHUNK_SIZE).forEach { chunk ->
                    AssetSnapshotTable.deleteWhere { snapshotId inList chunk }
                    ActionLogTable.deleteWhere { snapshotId inList chunk }
                    PortfolioSnapshotTable.deleteWhere { id inList chunk }
                }
            }
            idsToDelete.size
        }

    override suspend fun pruneTradesOlderThan(cutoff: Instant): Int =
        database.safeTransactionIO(log, "Failed to prune old trades") {
            val cutoffMillis = cutoff.toEpochMilli()
            val protectedTradeIds = protectedTradeIds()
            val inceptionEpochMs = readSyncMetadataInTransaction(
                SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS,
            )?.toLongOrNull()
            val idsToDelete = TradeTable.select(
                TradeTable.id,
                TradeTable.timestamp,
                TradeTable.clientOrderId,
                TradeTable.cycleId,
                TradeTable.tradeSource,
            ).where {
                (TradeTable.timestamp less cutoffMillis) and
                    TradeTable.submissionState.isNull()
            }.filterNot { row ->
                val id = row[TradeTable.id]
                val ts = row[TradeTable.timestamp]
                val isManualOrExternal = row[TradeTable.clientOrderId].isNullOrBlank() &&
                    row[TradeTable.cycleId].isNullOrBlank() &&
                    row[TradeTable.tradeSource] != TradeSource.LOCAL_ESTIMATE.name
                protectedTradeIds.contains(id) ||
                    (inceptionEpochMs != null && ts >= (inceptionEpochMs - 5000L) && isManualOrExternal)
            }.map { it[TradeTable.id] }
            idsToDelete.chunked(SQLITE_IN_CHUNK_SIZE).sumOf { chunk ->
                TradeTable.deleteWhere { TradeTable.id inList chunk }
            }
        }

    override suspend fun cleanupDuplicateTrades() {
        database.safeTransactionIO(log, "Failed to cleanup duplicate trades") {
            val allTradeRows = TradeTable.selectAll().orderBy(TradeTable.timestamp, SortOrder.ASC).toList()
            val allRecords = allTradeRows.map(TradeTable::toModel)
                .filter { it.submissionState == null }
            val toDelete = TradeDeduplicator.findDuplicateTradeIds(allRecords)

            if (toDelete.isNotEmpty()) {
                log.info("Cleaning up {} duplicate trade rows (pair-alias or estimate/fill match)...", toDelete.size)
                val protectedTradeIds = protectedTradeIds()
                toDelete
                    .filterNot(protectedTradeIds::contains)
                    .chunked(SQLITE_IN_CHUNK_SIZE)
                    .forEach { chunk -> TradeTable.deleteWhere { TradeTable.id inList chunk } }
            }
        }
    }

    private fun protectedTradeIds(): Set<Int> = OrderIntentTable
        .select(OrderIntentTable.localTradeId)
        .where {
            OrderIntentTable.localTradeId.isNotNull() and
                (
                    OrderIntentTable.state inList listOf(
                        OrderIntentState.PENDING.name,
                        OrderIntentState.UNCERTAIN.name,
                    )
                    )
        }
        .mapNotNull { it[OrderIntentTable.localTradeId] }
        .toSet()
}
