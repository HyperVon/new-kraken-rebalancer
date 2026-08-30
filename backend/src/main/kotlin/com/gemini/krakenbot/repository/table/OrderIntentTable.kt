package com.gemini.krakenbot.repository.table

import com.gemini.krakenbot.model.OrderIntent
import com.gemini.krakenbot.model.OrderIntentState
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import java.time.Instant

object OrderIntentTable : Table("order_intents") {
    val id = integer("id").autoIncrement()
    val cycleId = varchar("cycle_id", 36).nullable()
    val clientOrderId = varchar("client_order_id", 36).nullable()
    val clientOrderIdAmbiguous = bool("client_order_id_ambiguous").default(false)
    val pair = varchar("pair", 16)
    val symbol = varchar("symbol", 16)
    val side = varchar("side", 4)
    val volume = decimal("volume", 24, 8)
    val usdAmount = decimal("usd_amount", 18, 2)
    val expectedPrice = decimal("expected_price", 24, 8).nullable()
    val createdAt = long("created_at")
    val state = varchar("state", 16)
    val orderTxid = varchar("order_txid", 64).nullable()
    val errorMessage = text("error_message").nullable()
    val resolvedAt = long("resolved_at").nullable()
    val resolutionEvidence = text("resolution_evidence").nullable()
    val localTradeId = integer("local_trade_id").nullable()

    init {
        index("idx_order_intents_state", false, state)
        index("idx_order_intents_created_at", false, createdAt)
        index("idx_order_intents_local_trade_id", false, localTradeId)
        index("ux_order_intents_client_order_id", true, clientOrderId)
    }

    override val primaryKey = PrimaryKey(id)

    fun toModel(row: ResultRow): OrderIntent = OrderIntent(
        id = row[id],
        cycleId = row[cycleId],
        clientOrderId = row[clientOrderId],
        clientOrderIdAmbiguous = row[clientOrderIdAmbiguous],
        pair = row[pair],
        symbol = row[symbol],
        side = row[side],
        volume = row[volume],
        usdAmount = row[usdAmount],
        expectedPrice = row[expectedPrice],
        createdAt = Instant.ofEpochMilli(row[createdAt]),
        state = OrderIntentState.valueOf(row[state]),
        orderTxid = row[orderTxid],
        errorMessage = row[errorMessage],
        resolvedAt = row[resolvedAt]?.let(Instant::ofEpochMilli),
        resolutionEvidence = row[resolutionEvidence],
        localTradeId = row[localTradeId],
    )

    fun applyPending(builder: UpdateBuilder<*>, intent: OrderIntent) {
        builder[cycleId] = intent.cycleId
        builder[clientOrderId] = intent.clientOrderId
        builder[clientOrderIdAmbiguous] = intent.clientOrderIdAmbiguous
        builder[pair] = intent.pair
        builder[symbol] = intent.symbol
        builder[side] = intent.side
        builder[volume] = intent.volume
        builder[usdAmount] = intent.usdAmount
        builder[expectedPrice] = intent.expectedPrice
        builder[createdAt] = intent.createdAt.toEpochMilli()
        builder[state] = OrderIntentState.PENDING.name
        builder[orderTxid] = null
        builder[errorMessage] = null
        builder[resolvedAt] = null
        builder[resolutionEvidence] = null
        builder[localTradeId] = intent.localTradeId
    }
}
