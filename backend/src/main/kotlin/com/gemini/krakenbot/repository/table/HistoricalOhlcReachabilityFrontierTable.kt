package com.gemini.krakenbot.repository.table

import org.jetbrains.exposed.v1.core.Table

/** One bounded negative provider-reachability observation per pair and OHLC interval. */
object HistoricalOhlcReachabilityFrontierTable : Table("historical_ohlc_reachability_frontiers") {
    val pair = varchar("pair", 16)
    val intervalMinutes = integer("interval_minutes")
    val earliestReachableEpochSecond = long("earliest_reachable_epoch_second")
    val observedAtEpochSecond = long("observed_at_epoch_second")
    val retryAfterEpochSecond = long("retry_after_epoch_second")

    override val primaryKey = PrimaryKey(pair, intervalMinutes)
}
