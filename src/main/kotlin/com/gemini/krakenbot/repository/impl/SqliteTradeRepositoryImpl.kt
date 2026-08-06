package com.gemini.krakenbot.repository.impl

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.OrderSubmissionState
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.repository.TradeSummaryStats
import com.gemini.krakenbot.repository.table.ActionLogTable
import com.gemini.krakenbot.repository.table.AssetSnapshotTable
import com.gemini.krakenbot.repository.table.HistorySyncMetadataTable
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
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.andWhere
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
    }

    private val log =
        LoggerFactory.getLogger(SqliteTradeRepositoryImpl::class.java)

    private fun UpdateBuilder<*>.applyTradeFields(trade: TradeRecord) {
        this[TradeTable.timestamp] = trade.timestamp.toEpochMilli()
        this[TradeTable.pair] = trade.pair
        this[TradeTable.side] = OrderSide.normalize(trade.side)
        this[TradeTable.symbol] = trade.symbol
        this[TradeTable.volume] = trade.volume
        this[TradeTable.usdAmount] = trade.usdAmount
        this[TradeTable.success] = trade.success
        this[TradeTable.dryRun] = trade.dryRun
        this[TradeTable.errorMessage] = trade.errorMessage
        this[TradeTable.price] = trade.price
        this[TradeTable.fee] = trade.fee
        this[TradeTable.slippagePercent] = trade.slippagePercent
        this[TradeTable.expectedPrice] = trade.expectedPrice
        this[TradeTable.tradeSource] = trade.source?.name
        this[TradeTable.cycleId] = trade.cycleId
        this[TradeTable.orderTxid] = trade.orderTxid
        this[TradeTable.tradeId] = trade.tradeId
        this[TradeTable.clientOrderId] = trade.clientOrderId
        this[TradeTable.submissionState] = trade.submissionState?.name
    }

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
                AssetSnapshotTable.deleteWhere { snapshotId inList snapshotIds }
                ActionLogTable.deleteWhere { snapshotId inList snapshotIds }
                PortfolioSnapshotTable.deleteWhere { id inList snapshotIds }
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

    override suspend fun saveSnapshot(snapshot: PortfolioSnapshot) {
        database.safeTransactionIO(log, "Failed to save snapshot to database") {
            insertSnapshotWithChildren(snapshot)
        }
    }

    override suspend fun saveTrade(trade: TradeRecord): Int =
        database.safeTransactionIO(log, "Failed to save trade to database") {
            TradeTable.insert {
                it.applyTradeFields(trade)
            }[TradeTable.id]
        }

    override suspend fun updateTrade(oldTrade: TradeRecord, newTrade: TradeRecord) {
        database.safeTransactionIO(log, "Failed to update trade in database", "Database update failed") {
            val updatedRows = TradeTable.update({
                // Prefer primary key; multi-column fallback only when the in-memory row has no id.
                if (oldTrade.id != null) {
                    TradeTable.id eq oldTrade.id
                } else {
                    (TradeTable.timestamp eq oldTrade.timestamp.toEpochMilli()) and
                        (TradeTable.pair eq oldTrade.pair) and
                        (TradeTable.side eq OrderSide.normalize(oldTrade.side)) and
                        (TradeTable.volume eq oldTrade.volume)
                }
            }) {
                it.applyTradeFields(newTrade)
            }
            check(updatedRows == 1) {
                "Expected to update one trade row, but updated $updatedRows for trade ${oldTrade.id}"
            }
        }
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
                    .andWhere {
                        PortfolioSnapshotTable.timestamp greaterEq from.toEpochMilli()
                    }.andWhere {
                        PortfolioSnapshotTable.timestamp lessEq to.toEpochMilli()
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

    override suspend fun getTradesInRange(from: Instant, to: Instant): List<TradeRecord> = database.readTransactionIO {
        TradeTable
            .selectAll()
            .andWhere {
                TradeTable.timestamp greaterEq from.toEpochMilli()
            }.andWhere {
                TradeTable.timestamp lessEq to.toEpochMilli()
            }.orderBy(TradeTable.timestamp, SortOrder.DESC)
            .map { row -> buildTradeFromRow(row) }
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

    private fun insertSnapshotWithChildren(snapshot: PortfolioSnapshot) {
        val snapshotId =
            PortfolioSnapshotTable.insert {
                it[timestamp] = snapshot.timestamp.toEpochMilli()
                it[totalValueUSD] = snapshot.totalValueUSD
                it[drawdownPercent] = snapshot.drawdownPercent
                it[fiatDeploymentPercent] = snapshot.fiatDeploymentPercent
                it[effectiveUsdTargetPercent] = snapshot.effectiveUsdTargetPercent
            }[PortfolioSnapshotTable.id]

        for ((_, assetSnapshot) in snapshot.assets) {
            AssetSnapshotTable.insert {
                it[AssetSnapshotTable.snapshotId] = snapshotId
                it[symbol] = assetSnapshot.symbol.value
                it[balance] = assetSnapshot.balance
                it[price] = assetSnapshot.price
                it[valueUSD] = assetSnapshot.valueUSD
                it[targetPercent] = assetSnapshot.targetPercent
                it[currentPercent] = assetSnapshot.currentPercent
                it[deviationPercent] = assetSnapshot.deviationPercent
                it[deviationUSD] = assetSnapshot.deviationUSD
            }
        }

        for (action in snapshot.actions) {
            ActionLogTable.insert {
                it[ActionLogTable.snapshotId] = snapshotId
                it[message] = action
            }
        }
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

            val assetSnapshots =
                assetRows.associate { assetRow ->
                    val symbol = assetRow[AssetSnapshotTable.symbol]
                    symbol to
                        PortfolioSnapshot.AssetSnapshot(
                            symbol = Asset(symbol),
                            balance = assetRow[AssetSnapshotTable.balance],
                            price = assetRow[AssetSnapshotTable.price],
                            valueUSD = assetRow[AssetSnapshotTable.valueUSD],
                            targetPercent = assetRow[AssetSnapshotTable.targetPercent],
                            currentPercent = assetRow[AssetSnapshotTable.currentPercent],
                            deviationPercent = assetRow[AssetSnapshotTable.deviationPercent],
                            deviationUSD = assetRow[AssetSnapshotTable.deviationUSD],
                        )
                }

            val actions = actionRows.map { it[ActionLogTable.message] }

            PortfolioSnapshot(
                timestamp = Instant.ofEpochMilli(row[PortfolioSnapshotTable.timestamp]),
                totalValueUSD = row[PortfolioSnapshotTable.totalValueUSD],
                assets = assetSnapshots,
                actions = actions,
                drawdownPercent = row[PortfolioSnapshotTable.drawdownPercent],
                fiatDeploymentPercent = row[PortfolioSnapshotTable.fiatDeploymentPercent],
                effectiveUsdTargetPercent = row[PortfolioSnapshotTable.effectiveUsdTargetPercent],
            )
        }
    }

    private fun buildTradeFromRow(row: ResultRow): TradeRecord = TradeRecord(
        timestamp = Instant.ofEpochMilli(row[TradeTable.timestamp]),
        pair = row[TradeTable.pair],
        side = OrderSide.normalize(row[TradeTable.side]),
        symbol = row[TradeTable.symbol],
        volume = row[TradeTable.volume],
        usdAmount = row[TradeTable.usdAmount],
        success = row[TradeTable.success],
        dryRun = row[TradeTable.dryRun],
        errorMessage = row[TradeTable.errorMessage],
        price = row[TradeTable.price],
        fee = row[TradeTable.fee],
        slippagePercent = row[TradeTable.slippagePercent],
        expectedPrice = row[TradeTable.expectedPrice],
        source = TradeSource.fromDbValue(row[TradeTable.tradeSource]),
        id = row[TradeTable.id],
        cycleId = row[TradeTable.cycleId],
        orderTxid = row[TradeTable.orderTxid],
        tradeId = row[TradeTable.tradeId],
        clientOrderId = row[TradeTable.clientOrderId],
        submissionState = row[TradeTable.submissionState]?.let(OrderSubmissionState::valueOf),
    )

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

    override suspend fun isHistorySeeded(): Boolean = getSyncMetadata(SyncMetadataKeys.HISTORY_SEEDED) == "true"

    override suspend fun setHistorySeeded(seeded: Boolean) {
        setSyncMetadata(SyncMetadataKeys.HISTORY_SEEDED, seeded.toString())
    }

    override suspend fun getSyncMetadata(key: String): String? = database.readTransactionIO {
        HistorySyncMetadataTable
            .selectAll()
            .where { HistorySyncMetadataTable.key eq key }
            .firstOrNull()
            ?.get(HistorySyncMetadataTable.value)
    }

    override suspend fun setSyncMetadata(key: String, value: String) {
        database.safeTransactionIO(log, "Failed to upsert sync metadata") {
            HistorySyncMetadataTable.upsert {
                it[HistorySyncMetadataTable.key] = key
                it[HistorySyncMetadataTable.value] = value
            }
        }
    }

    override suspend fun pruneSnapshotsOlderThan(cutoff: Instant): Int =
        database.safeTransactionIO(log, "Failed to prune old snapshots") {
            val cutoffMillis = cutoff.toEpochMilli()
            val idsToDelete =
                PortfolioSnapshotTable
                    .select(PortfolioSnapshotTable.id)
                    .where { PortfolioSnapshotTable.timestamp less cutoffMillis }
                    .map { it[PortfolioSnapshotTable.id] }

            if (idsToDelete.isNotEmpty()) {
                // Children first even with ON DELETE CASCADE — keeps SQLite FK order explicit.
                AssetSnapshotTable.deleteWhere { snapshotId inList idsToDelete }
                ActionLogTable.deleteWhere { snapshotId inList idsToDelete }
                PortfolioSnapshotTable.deleteWhere { id inList idsToDelete }
            }
            idsToDelete.size
        }

    override suspend fun pruneTradesOlderThan(cutoff: Instant): Int =
        database.safeTransactionIO(log, "Failed to prune old trades") {
            val cutoffMillis = cutoff.toEpochMilli()
            TradeTable.deleteWhere {
                (timestamp less cutoffMillis) and submissionState.isNull()
            }
        }

    override suspend fun cleanupDuplicateTrades() {
        database.safeTransactionIO(log, "Failed to cleanup duplicate trades") {
            val allTradeRows = TradeTable.selectAll().orderBy(TradeTable.timestamp, SortOrder.ASC).toList()
            val allRecords = allTradeRows.map { buildTradeFromRow(it) }
                .filter { it.submissionState == null }
            val toDelete = TradeDeduplicator.findDuplicateTradeIds(allRecords)

            if (toDelete.isNotEmpty()) {
                log.info("Cleaning up {} duplicate trade rows (pair-alias or estimate/fill match)...", toDelete.size)
                TradeTable.deleteWhere { id inList toDelete }
            }
        }
    }
}
