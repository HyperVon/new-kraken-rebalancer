package com.gemini.krakenbot.repository.impl

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.repository.table.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

class SqliteTradeRepositoryImpl(
    private val database: Database
) : TradeRepository {

    private val log =
        LoggerFactory.getLogger(SqliteTradeRepositoryImpl::class.java)

    override fun save(history: List<PortfolioSnapshot>) {
        try {
            transaction(database) {
                for (snapshot in history) {
                    insertSnapshotWithChildren(snapshot)
                }
            }
        } catch (e: Exception) {
            log.error("Failed to save history to database", e)
            if (e is IOException) throw e
            throw IOException("Database write failed", e)
        }
    }

    override fun load(): List<PortfolioSnapshot> {
        return transaction(database) {
            val snapshotRows = PortfolioSnapshotTable
                .selectAll()
                .orderBy(PortfolioSnapshotTable.timestamp, SortOrder.DESC)
                .limit(50)
                .toList()

            buildSnapshotsFromRows(snapshotRows)
        }
    }

    override fun saveSnapshot(snapshot: PortfolioSnapshot) {
        try {
            transaction(database) {
                insertSnapshotWithChildren(snapshot)
            }
        } catch (e: Exception) {
            log.error("Failed to save snapshot to database", e)
            if (e is IOException) throw e
            throw IOException("Database write failed", e)
        }
    }

    override fun saveTrade(trade: TradeRecord) {
        try {
            transaction(database) {
                TradeTable.insert {
                    it[timestamp] = trade.timestamp.toEpochMilli()
                    it[pair] = trade.pair
                    it[side] = trade.side
                    it[symbol] = trade.symbol
                    it[volume] = trade.volume
                    it[usdAmount] = trade.usdAmount
                    it[success] = trade.success
                    it[dryRun] = trade.dryRun
                    it[errorMessage] = trade.errorMessage
                    it[price] = trade.price
                    it[fee] = trade.fee
                    it[slippagePercent] = trade.slippagePercent
                }
            }
        } catch (e: Exception) {
            log.error("Failed to save trade to database", e)
            if (e is IOException) throw e
            throw IOException("Database write failed", e)
        }
    }

    override fun updateTrade(oldTrade: TradeRecord, newTrade: TradeRecord) {
        try {
            transaction(database) {
                TradeTable.update({
                    (TradeTable.timestamp eq oldTrade.timestamp.toEpochMilli()) and
                    (TradeTable.pair eq oldTrade.pair) and
                    (TradeTable.side eq oldTrade.side) and
                    (TradeTable.volume eq oldTrade.volume)
                }) {
                    it[timestamp] = newTrade.timestamp.toEpochMilli()
                    it[pair] = newTrade.pair
                    it[side] = newTrade.side
                    it[symbol] = newTrade.symbol
                    it[volume] = newTrade.volume
                    it[usdAmount] = newTrade.usdAmount
                    it[success] = newTrade.success
                    it[dryRun] = newTrade.dryRun
                    it[errorMessage] = newTrade.errorMessage
                    it[price] = newTrade.price
                    it[fee] = newTrade.fee
                    it[slippagePercent] = newTrade.slippagePercent
                }
            }
        } catch (e: Exception) {
            log.error("Failed to update trade in database", e)
            if (e is IOException) throw e
            throw IOException("Database update failed", e)
        }
    }

    override fun getSnapshotsInRange(
        from: Instant,
        to: Instant
    ): List<PortfolioSnapshot> {
        return transaction(database) {
            val allIds = PortfolioSnapshotTable
                .select(PortfolioSnapshotTable.id)
                .andWhere {
                    PortfolioSnapshotTable.timestamp greaterEq from.toEpochMilli()
                }
                .andWhere {
                    PortfolioSnapshotTable.timestamp lessEq to.toEpochMilli()
                }
                .orderBy(PortfolioSnapshotTable.timestamp, SortOrder.ASC)
                .map { it[PortfolioSnapshotTable.id] }

            if (allIds.isEmpty()) return@transaction emptyList()

            val downsampledIds = if (allIds.size <= 300) {
                allIds
            } else {
                val step = allIds.size / 300
                allIds.filterIndexed { index, _ -> index % step == 0 }
            }

            val snapshotRows = PortfolioSnapshotTable
                .selectAll()
                .where { PortfolioSnapshotTable.id inList downsampledIds }
                .orderBy(PortfolioSnapshotTable.timestamp, SortOrder.ASC)
                .toList()

            buildSnapshotsFromRows(snapshotRows)
        }
    }

    override fun getTradesInRange(
        from: Instant,
        to: Instant
    ): List<TradeRecord> {
        return transaction(database) {
            TradeTable
                .selectAll()
                .andWhere {
                    TradeTable.timestamp greaterEq from.toEpochMilli()
                }
                .andWhere {
                    TradeTable.timestamp lessEq to.toEpochMilli()
                }
                .orderBy(TradeTable.timestamp, SortOrder.DESC)
                .map { row -> buildTradeFromRow(row) }
        }
    }

    override fun getTotalTradeCount(): Long {
        return transaction(database) {
            TradeTable
                .selectAll()
                .where { TradeTable.success eq true }
                .count()
        }
    }

    override fun getTotalVolumeTraded(): BigDecimal {
        return transaction(database) {
            val sumCol = TradeTable.usdAmount.sum()
            TradeTable
                .select(sumCol)
                .where { TradeTable.success eq true }
                .firstOrNull()
                ?.get(sumCol) ?: BigDecimal.ZERO
        }
    }

    override fun getTotalFeesPaid(): BigDecimal {
        return transaction(database) {
            val sumCol = TradeTable.fee.sum()
            TradeTable
                .select(sumCol)
                .where { TradeTable.success eq true }
                .firstOrNull()
                ?.get(sumCol) ?: BigDecimal.ZERO
        }
    }



    override fun getLatestSnapshotTime(): Instant? {
        return transaction(database) {
            PortfolioSnapshotTable
                .selectAll()
                .orderBy(PortfolioSnapshotTable.timestamp, SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.let {
                    Instant.ofEpochMilli(it[PortfolioSnapshotTable.timestamp])
                }
        }
    }

    private fun insertSnapshotWithChildren(snapshot: PortfolioSnapshot) {
        val snapshotId = PortfolioSnapshotTable.insert {
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

        val allAssetSnapshots = AssetSnapshotTable
            .selectAll()
            .where { AssetSnapshotTable.snapshotId inList snapshotIds }
            .groupBy { it[AssetSnapshotTable.snapshotId] }

        val allActionLogs = ActionLogTable
            .selectAll()
            .where { ActionLogTable.snapshotId inList snapshotIds }
            .groupBy { it[ActionLogTable.snapshotId] }

        return rows.map { row ->
            val snapshotId = row[PortfolioSnapshotTable.id]
            val assetRows = allAssetSnapshots[snapshotId] ?: emptyList()
            val actionRows = allActionLogs[snapshotId] ?: emptyList()

            val assetSnapshots = assetRows.associate { assetRow ->
                val symbol = assetRow[AssetSnapshotTable.symbol]
                symbol to PortfolioSnapshot.AssetSnapshot(
                    symbol = Asset(symbol),
                    balance = assetRow[AssetSnapshotTable.balance],
                    price = assetRow[AssetSnapshotTable.price],
                    valueUSD = assetRow[AssetSnapshotTable.valueUSD],
                    targetPercent = assetRow[AssetSnapshotTable.targetPercent],
                    currentPercent = assetRow[AssetSnapshotTable.currentPercent],
                    deviationPercent = assetRow[AssetSnapshotTable.deviationPercent],
                    deviationUSD = assetRow[AssetSnapshotTable.deviationUSD]
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
                effectiveUsdTargetPercent = row[PortfolioSnapshotTable.effectiveUsdTargetPercent]
            )
        }
    }


    private fun buildTradeFromRow(row: ResultRow): TradeRecord {
        return TradeRecord(
            timestamp = Instant.ofEpochMilli(row[TradeTable.timestamp]),
            pair = row[TradeTable.pair],
            side = row[TradeTable.side],
            symbol = row[TradeTable.symbol],
            volume = row[TradeTable.volume],
            usdAmount = row[TradeTable.usdAmount],
            success = row[TradeTable.success],
            dryRun = row[TradeTable.dryRun],
            errorMessage = row[TradeTable.errorMessage],
            price = row[TradeTable.price],
            fee = row[TradeTable.fee],
            slippagePercent = row[TradeTable.slippagePercent]
        )
    }

    override fun getLatestTradeTime(): Instant? {
        return transaction(database) {
            TradeTable
                .selectAll()
                .where { TradeTable.dryRun eq false }
                .orderBy(TradeTable.timestamp, SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.let {
                    Instant.ofEpochMilli(it[TradeTable.timestamp])
                }
        }
    }

    override fun isHistorySeeded(): Boolean {
        return transaction(database) {
            HistorySyncMetadataTable
                .selectAll()
                .where { HistorySyncMetadataTable.key eq "history_seeded" }
                .firstOrNull()
                ?.let { it[HistorySyncMetadataTable.value] == "true" } ?: false
        }
    }

    override fun setHistorySeeded(seeded: Boolean) {
        transaction(database) {
            val existing = HistorySyncMetadataTable
                .selectAll()
                .where { HistorySyncMetadataTable.key eq "history_seeded" }
                .firstOrNull()
            if (existing != null) {
                HistorySyncMetadataTable.update({ HistorySyncMetadataTable.key eq "history_seeded" }) {
                    it[value] = seeded.toString()
                }
            } else {
                HistorySyncMetadataTable.insert {
                    it[key] = "history_seeded"
                    it[value] = seeded.toString()
                }
            }
        }
    }

    override fun getSyncMetadata(key: String): String? {
        return transaction(database) {
            HistorySyncMetadataTable
                .selectAll()
                .where { HistorySyncMetadataTable.key eq key }
                .firstOrNull()
                ?.get(HistorySyncMetadataTable.value)
        }
    }

    override fun setSyncMetadata(key: String, value: String) {
        transaction(database) {
            val existing = HistorySyncMetadataTable
                .selectAll()
                .where { HistorySyncMetadataTable.key eq key }
                .firstOrNull()
            if (existing != null) {
                HistorySyncMetadataTable.update({ HistorySyncMetadataTable.key eq key }) {
                    it[HistorySyncMetadataTable.value] = value
                }
            } else {
                HistorySyncMetadataTable.insert {
                    it[HistorySyncMetadataTable.key] = key
                    it[HistorySyncMetadataTable.value] = value
                }
            }
        }
    }
    override fun pruneSnapshotsOlderThan(cutoff: Instant): Int {
        return transaction(database) {
            val cutoffMillis = cutoff.toEpochMilli()
            val toDeleteRows = PortfolioSnapshotTable
                .selectAll()
                .where { PortfolioSnapshotTable.timestamp less cutoffMillis }
                .toList()

            PortfolioSnapshotTable.deleteWhere {
                timestamp less cutoffMillis
            }
            toDeleteRows.size
        }
    }

    override fun cleanupDuplicateTrades() {
        transaction(database) {
            val toDelete = mutableListOf<Int>()
            val allTrades = TradeTable.selectAll().orderBy(TradeTable.timestamp, SortOrder.ASC).toList()

            for (i in allTrades.indices) {
                val t1 = allTrades[i]
                for (j in i + 1 until allTrades.size) {
                    val t2 = allTrades[j]
                    val diff = t2[TradeTable.timestamp] - t1[TradeTable.timestamp]
                    if (diff > 300_000) break

                    val sameSymbolAndSide =
                        t1[TradeTable.symbol].equals(t2[TradeTable.symbol], ignoreCase = true) &&
                                t1[TradeTable.side].equals(t2[TradeTable.side], ignoreCase = true)
                    val pairAliasDuplicate = sameSymbolAndSide &&
                            t1[TradeTable.volume].compareTo(t2[TradeTable.volume]) == 0 &&
                            t1[TradeTable.pair] != t2[TradeTable.pair]
                    val localEstimateDuplicate = sameSymbolAndSide &&
                            t1[TradeTable.pair].equals(t2[TradeTable.pair], ignoreCase = true) &&
                            diff <= 10_000 &&
                            isWithinOnePercent(t1[TradeTable.volume], t2[TradeTable.volume]) &&
                            isWithinOnePercent(t1[TradeTable.usdAmount], t2[TradeTable.usdAmount]) &&
                            feePercentDiffersMaterially(t1, t2)

                    if (pairAliasDuplicate || localEstimateDuplicate) {
                        // The earlier timestamp is the exchange fill. The later row is the
                        // locally recorded estimate or an alternate Kraken pair spelling.
                        toDelete.add(t2[TradeTable.id])
                    }
                }
            }

            if (toDelete.isNotEmpty()) {
                log.info("Cleaning up {} duplicate local trades due to pair name mismatch...", toDelete.size)
                TradeTable.deleteWhere { id inList toDelete }
            }
        }
    }

    private fun isWithinOnePercent(first: BigDecimal, second: BigDecimal): Boolean {
        val largerAmount = maxOf(first.abs(), second.abs())
        return largerAmount.signum() > 0 &&
                first.subtract(second).abs().divide(largerAmount, 8, RoundingMode.HALF_UP) <= BigDecimal("0.01")
    }

    private fun feePercentDiffersMaterially(first: ResultRow, second: ResultRow): Boolean {
        val firstAmount = first[TradeTable.usdAmount]
        val secondAmount = second[TradeTable.usdAmount]
        if (firstAmount.signum() == 0 || secondAmount.signum() == 0) return false

        val firstFeeRate = first[TradeTable.fee].divide(firstAmount, 8, RoundingMode.HALF_UP)
        val secondFeeRate = second[TradeTable.fee].divide(secondAmount, 8, RoundingMode.HALF_UP)
        return firstFeeRate.subtract(secondFeeRate).abs() >= BigDecimal("0.001")
    }
}
