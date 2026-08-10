package com.gemini.krakenbot.repository.table

import org.jetbrains.exposed.v1.core.Table

object OrderIntentTable : Table("order_intents") {
    val id = integer("id").autoIncrement()
    val cycleId = varchar("cycle_id", 36).nullable()
    val clientOrderId = varchar("client_order_id", 36).nullable()
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
}
