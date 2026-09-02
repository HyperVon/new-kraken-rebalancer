package com.gemini.krakenbot.repository.table

import com.gemini.krakenbot.model.PortfolioSnapshot
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import java.time.Instant

object PortfolioSnapshotTable : Table("portfolio_snapshots") {
    val id = integer("id").autoIncrement()
    val timestamp = long("timestamp")
    val totalValueUSD = decimal("total_value_usd", 18, 2)
    val drawdownPercent = decimal("drawdown_percent", 10, 4)
    val fiatDeploymentPercent = decimal("fiat_deployment_percent", 10, 4)
    val effectiveUsdTargetPercent = decimal("effective_usd_target_percent", 10, 4)
    val balancesObservedAt = long("balances_observed_at").nullable()

    init {
        index("idx_snapshots_timestamp", false, timestamp)
    }

    override val primaryKey = PrimaryKey(id)

    fun toModel(
        row: ResultRow,
        assets: Map<String, PortfolioSnapshot.AssetSnapshot>,
        actions: List<String>,
    ): PortfolioSnapshot {
        val observedAtMillis = row.getOrNull(balancesObservedAt)
        val observedAt = if (observedAtMillis != null && observedAtMillis > 0L) {
            Instant.ofEpochMilli(observedAtMillis)
        } else {
            null
        }
        return PortfolioSnapshot(
            timestamp = Instant.ofEpochMilli(row[timestamp]),
            totalValueUSD = row[totalValueUSD],
            assets = assets,
            actions = actions,
            drawdownPercent = row[drawdownPercent],
            fiatDeploymentPercent = row[fiatDeploymentPercent],
            effectiveUsdTargetPercent = row[effectiveUsdTargetPercent],
            balancesObservedAt = observedAt,
        )
    }

    fun applyTo(builder: UpdateBuilder<*>, snapshot: PortfolioSnapshot) {
        builder[timestamp] = snapshot.timestamp.toEpochMilli()
        builder[totalValueUSD] = snapshot.totalValueUSD
        builder[drawdownPercent] = snapshot.drawdownPercent
        builder[fiatDeploymentPercent] = snapshot.fiatDeploymentPercent
        builder[effectiveUsdTargetPercent] = snapshot.effectiveUsdTargetPercent
        builder[balancesObservedAt] = snapshot.balancesObservedAt?.toEpochMilli()
    }
}
