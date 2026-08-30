package com.gemini.krakenbot.repository.table

import com.gemini.krakenbot.model.OrderSide
import com.gemini.krakenbot.model.OrderSubmissionState
import com.gemini.krakenbot.model.TradeRecord
import com.gemini.krakenbot.model.TradeSource
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import java.time.Instant

object TradeTable : Table("trades") {
    val id = integer("id").autoIncrement()
    val timestamp = long("timestamp")
    val pair = varchar("pair", 16)
    val side = varchar("side", 4)
    val symbol = varchar("symbol", 16)
    val volume = decimal("volume", 24, 8)
    val usdAmount = decimal("usd_amount", 18, 2)
    val success = bool("success")
    val dryRun = bool("dry_run")
    val errorMessage = text("error_message").nullable()
    val price = decimal("price", 24, 8)
    val fee = decimal("fee", 18, 4)
    val slippagePercent = decimal("slippage_percent", 10, 4).nullable()
    val expectedPrice = decimal("expected_price", 24, 8).nullable()
    val tradeSource = varchar("source", 16).nullable()
    val cycleId = varchar("cycle_id", 36).nullable()
    val orderTxid = varchar("order_txid", 64).nullable()
    val tradeId = varchar("trade_id", 64).nullable()
    val clientOrderId = varchar("client_order_id", 36).nullable()
    val submissionState = varchar("submission_state", 16).nullable()

    init {
        index("idx_trades_timestamp", false, timestamp)
        index("idx_trades_pair_side_timestamp", false, pair, side, timestamp)
        index("idx_trades_success", false, success)
        index("idx_trades_cycle_id", false, cycleId)
        index("idx_trades_trade_id", false, tradeId)
        index("idx_trades_submission_state", false, submissionState)
    }

    override val primaryKey = PrimaryKey(id)

    fun toModel(row: ResultRow): TradeRecord = TradeRecord(
        timestamp = Instant.ofEpochMilli(row[timestamp]),
        pair = row[pair],
        side = OrderSide.normalize(row[side]),
        symbol = row[symbol],
        volume = row[volume],
        usdAmount = row[usdAmount],
        success = row[success],
        dryRun = row[dryRun],
        errorMessage = row[errorMessage],
        price = row[price],
        fee = row[fee],
        slippagePercent = row[slippagePercent],
        expectedPrice = row[expectedPrice],
        source = TradeSource.fromDbValue(row[tradeSource]),
        id = row[id],
        cycleId = row[cycleId],
        orderTxid = row[orderTxid],
        tradeId = row[tradeId],
        clientOrderId = row[clientOrderId],
        submissionState = row[submissionState]?.let(OrderSubmissionState::valueOf),
    )

    fun applyTo(builder: UpdateBuilder<*>, trade: TradeRecord) {
        builder[timestamp] = trade.timestamp.toEpochMilli()
        builder[pair] = trade.pair
        builder[side] = OrderSide.normalize(trade.side)
        builder[symbol] = trade.symbol
        builder[volume] = trade.volume
        builder[usdAmount] = trade.usdAmount
        builder[success] = trade.success
        builder[dryRun] = trade.dryRun
        builder[errorMessage] = trade.errorMessage
        builder[price] = trade.price
        builder[fee] = trade.fee
        builder[slippagePercent] = trade.slippagePercent
        builder[expectedPrice] = trade.expectedPrice
        builder[tradeSource] = trade.source?.name
        builder[cycleId] = trade.cycleId
        builder[orderTxid] = trade.orderTxid
        builder[tradeId] = trade.tradeId
        builder[clientOrderId] = trade.clientOrderId
        builder[submissionState] = trade.submissionState?.name
    }
}
