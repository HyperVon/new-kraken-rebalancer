package com.gemini.krakenbot.repository.table

import org.jetbrains.exposed.v1.core.Table

/** Holds one row by convention (ATH tracking) — the stats repository updates whichever row exists. */
object PortfolioStatsTable : Table("portfolio_stats") {
    val id = integer("id").autoIncrement()
    val allTimeHigh = decimal("all_time_high", 18, 2).nullable()

    override val primaryKey = PrimaryKey(id)
}
