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

    override val primaryKey = PrimaryKey(id)

    fun toModel(row: ResultRow): PortfolioStats = PortfolioStats(row[allTimeHigh] ?: BigDecimal.ZERO)

    fun applyTo(builder: UpdateBuilder<*>, stats: PortfolioStats) {
        builder[allTimeHigh] = stats.allTimeHigh
    }
}
