package com.gemini.krakenbot.repository.table

import org.jetbrains.exposed.v1.core.Table

object HistoricalOhlcCandleTable : Table("historical_ohlc_candles") {
    val pair = varchar("pair", 16)
    val intervalMinutes = integer("interval_minutes")
    val candleStartEpochSecond = long("candle_start_epoch_second")
    val close = decimal("close", 24, 8)
    val fetchedAtEpochSecond = long("fetched_at_epoch_second")

    init {
        index("idx_historical_ohlc_candles_lookup", false, pair, intervalMinutes, candleStartEpochSecond)
    }

    override val primaryKey = PrimaryKey(pair, intervalMinutes, candleStartEpochSecond)
}
