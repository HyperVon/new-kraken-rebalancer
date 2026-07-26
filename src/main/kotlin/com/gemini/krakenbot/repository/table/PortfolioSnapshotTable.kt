package com.gemini.krakenbot.repository.table

import org.jetbrains.exposed.v1.core.Table

object PortfolioSnapshotTable : Table("portfolio_snapshots") {
    val id = integer("id").autoIncrement()
    val timestamp = long("timestamp")
    val totalValueUSD = decimal("total_value_usd", 18, 2)
    val drawdownPercent = decimal("drawdown_percent", 10, 4)
    val fiatDeploymentPercent = decimal("fiat_deployment_percent", 10, 4)
    val effectiveUsdTargetPercent = decimal("effective_usd_target_percent", 10, 4)

    init {
        index("idx_snapshots_timestamp", false, timestamp)
    }

    override val primaryKey = PrimaryKey(id)
}
