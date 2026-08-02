package com.gemini.krakenbot.repository

import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import com.gemini.krakenbot.repository.impl.readTransactionIO
import com.gemini.krakenbot.repository.impl.safeTransactionIO
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.Instant

object ExampleTradeTable : Table("example_trades") {
    val id = integer("id").autoIncrement()
    val timestamp = long("timestamp").index()
    val pair = varchar("pair", 16)
    val side = varchar("side", 4)
    val volume = decimal("volume", 24, 8)
    val usdAmount = decimal("usd_amount", 18, 2)
    val fee = decimal("fee", 18, 4)

    override val primaryKey = PrimaryKey(id)
}

class SqliteExampleRepositoryImpl(
    private val database: Database
) {
    private val log = LoggerFactory.getLogger(SqliteExampleRepositoryImpl::class.java)

    suspend fun saveTrade(trade: TradeRecord): Unit = database.safeTransactionIO(log, "Failed to save trade record") {
        ExampleTradeTable.insert {
            it[timestamp] = trade.timestamp.toEpochMilli()
            it[pair] = trade.pair
            it[side] = trade.side
            it[volume] = trade.volume
            it[usdAmount] = trade.usdAmount
            it[fee] = trade.fee
        }
    }

    suspend fun loadAllTrades(): List<TradeRecord> = database.readTransactionIO {
        ExampleTradeTable.selectAll()
            .orderBy(ExampleTradeTable.timestamp, SortOrder.DESC)
            .map { row ->
                TradeRecord(
                    id = row[ExampleTradeTable.id],
                    timestamp = Instant.ofEpochMilli(row[ExampleTradeTable.timestamp]),
                    pair = row[ExampleTradeTable.pair],
                    side = row[ExampleTradeTable.side],
                    symbol = row[ExampleTradeTable.pair],
                    volume = row[ExampleTradeTable.volume],
                    usdAmount = row[ExampleTradeTable.usdAmount],
                    success = true,
                    dryRun = false,
                    source = TradeSource.API_FILL,
                )
            }
    }

    suspend fun deleteTradeById(tradeId: Int): Boolean = database.safeTransactionIO(log, "Failed to delete trade record") {
        ExampleTradeTable.deleteWhere { ExampleTradeTable.id eq tradeId } > 0
    }
}
