package com.gemini.krakenbot.repository.table

import org.jetbrains.exposed.v1.core.Table

/** Legacy schema retained for existing databases; current runtime has no stats reporting writer. */
object PortfolioStatsTable : Table("portfolio_stats") {
    val id = integer("id")
    val allTimeHigh = decimal("all_time_high", 18, 2).nullable()
    val lastTrustedDrawdownPct = decimal("last_trusted_drawdown_pct", 10, 4).nullable()

    override val primaryKey = PrimaryKey(id)
}
