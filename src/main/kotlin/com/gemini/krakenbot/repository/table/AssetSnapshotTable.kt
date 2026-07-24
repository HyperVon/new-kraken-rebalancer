package com.gemini.krakenbot.repository.table

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/** Exposed table definition for asset snapshots — one row per asset per rebalancing cycle. */
object AssetSnapshotTable : Table("asset_snapshots") {
    val id = integer("id").autoIncrement()
    val snapshotId =
        integer("snapshot_id")
            .references(PortfolioSnapshotTable.id, onDelete = ReferenceOption.CASCADE)
    val symbol = varchar("symbol", 16)
    val balance = decimal("balance", 24, 8)
    val price = decimal("price", 24, 8)
    val valueUSD = decimal("value_usd", 18, 2)
    val targetPercent = decimal("target_percent", 10, 4)
    val currentPercent = decimal("current_percent", 10, 4)
    val deviationPercent = decimal("deviation_percent", 10, 4)
    val deviationUSD = decimal("deviation_usd", 18, 2)

    init {
        index("idx_assetsnapshots_snapshot_id", false, snapshotId)
    }

    override val primaryKey = PrimaryKey(id)
}
