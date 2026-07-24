package com.gemini.krakenbot.repository.table

import org.jetbrains.exposed.v1.core.Table

/** Exposed table definition for portfolio stats — single-row table for ATH tracking. */
object PortfolioStatsTable : Table("portfolio_stats") {
    val id = integer("id").autoIncrement()
    val allTimeHigh = decimal("all_time_high", 18, 2).nullable()

    override val primaryKey = PrimaryKey(id)
}
