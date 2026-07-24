package com.gemini.krakenbot.repository

import com.gemini.krakenbot.domain.TradeRecord
import com.gemini.krakenbot.util.safeTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.math.BigDecimal

object ExampleTradeTable : LongIdTable("example_trades") {
    val timestamp = long("timestamp").index()
    val pair = varchar("pair", 32)
    val side = varchar("side", 8)
    val volume = decimal("volume", 18, 8)
    val usdAmount = decimal("usd_amount", 12, 2)
}

class SqliteExampleRepositoryImpl(
    private val database: Database
) {
    private val log = LoggerFactory.getLogger(SqliteExampleRepositoryImpl::class.java)

    suspend fun saveTrade(trade: TradeRecord): Unit = withContext(Dispatchers.IO) {
        database.safeTransaction(log, "Failed to save trade record") {
            ExampleTradeTable.insert {
                it[timestamp] = trade.timestamp.toEpochMilli()
                it[pair] = trade.pair
                it[side] = trade.side.name
                it[volume] = trade.volume
                it[usdAmount] = trade.usdAmount
            }
        }
    }

    suspend fun loadAllTrades(): List<TradeRecord> = withContext(Dispatchers.IO) {
        transaction(database) {
            ExampleTradeTable.selectAll()
                .orderBy(ExampleTradeTable.timestamp, SortOrder.DESC)
                .map { row ->
                    TradeRecord(
                        id = row[ExampleTradeTable.id].value,
                        pair = row[ExampleTradeTable.pair],
                        volume = row[ExampleTradeTable.volume],
                        usdAmount = row[ExampleTradeTable.usdAmount]
                    )
                }
        }
    }

    suspend fun deleteTradeById(tradeId: Long): Boolean = withContext(Dispatchers.IO) {
        database.safeTransaction(log, "Failed to delete trade record") {
            ExampleTradeTable.deleteWhere { ExampleTradeTable.id eq tradeId } > 0
        }
    }
}
