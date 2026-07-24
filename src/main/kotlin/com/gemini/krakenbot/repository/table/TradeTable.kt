package com.gemini.krakenbot.repository.table

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/** Exposed table definition for trades — one row per executed order. */
object TradeTable : Table("trades") {
    val id = integer("id").autoIncrement()

    @Suppress("unused")
    val snapshotId =
        integer("snapshot_id")
            .references(PortfolioSnapshotTable.id, onDelete = ReferenceOption.CASCADE)
            .nullable()
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

    init {
        index("idx_trades_timestamp", false, timestamp)
        index("idx_trades_pair_side_timestamp", false, pair, side, timestamp)
        index("idx_trades_success", false, success)
    }

    override val primaryKey = PrimaryKey(id)
}
