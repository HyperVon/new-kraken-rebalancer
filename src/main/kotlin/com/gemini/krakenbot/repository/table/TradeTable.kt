package com.gemini.krakenbot.repository.table

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/** Exposed table definition for trades — one row per executed order. */
object TradeTable : Table("trades") {
    val id = integer("id").autoIncrement()
    val snapshotId = integer("snapshot_id")
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

    override val primaryKey = PrimaryKey(id)
}
