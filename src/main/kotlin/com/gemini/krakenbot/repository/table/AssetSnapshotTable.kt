package com.gemini.krakenbot.repository.table

import com.gemini.krakenbot.model.Asset
import com.gemini.krakenbot.model.PortfolioSnapshot
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder

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

    fun toModel(row: ResultRow): Pair<String, PortfolioSnapshot.AssetSnapshot> {
        val sym = row[symbol]
        return sym to PortfolioSnapshot.AssetSnapshot(
            symbol = Asset(sym),
            balance = row[balance],
            price = row[price],
            valueUSD = row[valueUSD],
            targetPercent = row[targetPercent],
            currentPercent = row[currentPercent],
            deviationPercent = row[deviationPercent],
            deviationUSD = row[deviationUSD],
        )
    }

    fun applyTo(builder: UpdateBuilder<*>, snapshotId: Int, assetSnapshot: PortfolioSnapshot.AssetSnapshot) {
        builder[AssetSnapshotTable.snapshotId] = snapshotId
        builder[symbol] = assetSnapshot.symbol.value
        builder[balance] = assetSnapshot.balance
        builder[price] = assetSnapshot.price
        builder[valueUSD] = assetSnapshot.valueUSD
        builder[targetPercent] = assetSnapshot.targetPercent
        builder[currentPercent] = assetSnapshot.currentPercent
        builder[deviationPercent] = assetSnapshot.deviationPercent
        builder[deviationUSD] = assetSnapshot.deviationUSD
    }
}
