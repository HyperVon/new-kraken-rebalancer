package com.gemini.krakenbot.repository.table

import com.gemini.krakenbot.model.PortfolioStats
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import java.math.BigDecimal

/** Exactly one logical row (id=1) stores the portfolio ATH. */
object PortfolioStatsTable : Table("portfolio_stats") {
    val id = integer("id")
    val allTimeHigh = decimal("all_time_high", 18, 2).nullable()
    val lastTrustedDrawdownPct = decimal("last_trusted_drawdown_pct", 10, 4).nullable()

    override val primaryKey = PrimaryKey(id)

    fun toModel(row: ResultRow): PortfolioStats = PortfolioStats(
        allTimeHigh = row[allTimeHigh] ?: BigDecimal.ZERO,
        lastTrustedDrawdownPct = row[lastTrustedDrawdownPct],
    )

    fun applyTo(builder: UpdateBuilder<*>, stats: PortfolioStats) {
        builder[allTimeHigh] = stats.allTimeHigh
        builder[lastTrustedDrawdownPct] = stats.lastTrustedDrawdownPct
    }
}
