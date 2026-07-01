package com.gemini.krakenbot.repository.impl

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.repository.TradeRepository
import com.gemini.krakenbot.repository.table.ActionLogTable
import com.gemini.krakenbot.repository.table.AssetSnapshotTable
import com.gemini.krakenbot.repository.table.PortfolioSnapshotTable
import com.gemini.krakenbot.repository.table.TradeTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.IOException
import java.math.BigDecimal
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

            snapshotRows.map { row -> buildSnapshotFromRow(row) }
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
                }
            }
        } catch (e: Exception) {
            log.error("Failed to save trade to database", e)
            if (e is IOException) throw e
            throw IOException("Database write failed", e)
        }
    }

    override fun getSnapshotsInRange(
        from: Instant,
        to: Instant
    ): List<PortfolioSnapshot> {
        return transaction(database) {
            val snapshotRows = PortfolioSnapshotTable
                .selectAll()
                .andWhere {
                    PortfolioSnapshotTable.timestamp greaterEq from.toEpochMilli()
                }
                .andWhere {
                    PortfolioSnapshotTable.timestamp lessEq to.toEpochMilli()
                }
                .orderBy(PortfolioSnapshotTable.timestamp, SortOrder.ASC)
                .toList()

            snapshotRows.map { row -> buildSnapshotFromRow(row) }
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

    override fun getFirstSnapshotTime(): Instant? {
        return transaction(database) {
            PortfolioSnapshotTable
                .selectAll()
                .orderBy(PortfolioSnapshotTable.timestamp, SortOrder.ASC)
                .limit(1)
                .firstOrNull()
                ?.let {
                    Instant.ofEpochMilli(it[PortfolioSnapshotTable.timestamp])
                }
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

    private fun buildSnapshotFromRow(row: ResultRow): PortfolioSnapshot {
        val snapshotId = row[PortfolioSnapshotTable.id]

        val assetSnapshots = AssetSnapshotTable
            .selectAll()
            .where { AssetSnapshotTable.snapshotId eq snapshotId }
            .associate { assetRow ->
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

        val actions = ActionLogTable
            .selectAll()
            .where { ActionLogTable.snapshotId eq snapshotId }
            .map { it[ActionLogTable.message] }

        return PortfolioSnapshot(
            timestamp = Instant.ofEpochMilli(row[PortfolioSnapshotTable.timestamp]),
            totalValueUSD = row[PortfolioSnapshotTable.totalValueUSD],
            assets = assetSnapshots,
            actions = actions,
            drawdownPercent = row[PortfolioSnapshotTable.drawdownPercent],
            fiatDeploymentPercent = row[PortfolioSnapshotTable.fiatDeploymentPercent],
            effectiveUsdTargetPercent = row[PortfolioSnapshotTable.effectiveUsdTargetPercent]
        )
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
            errorMessage = row[TradeTable.errorMessage]
        )
    }
}
