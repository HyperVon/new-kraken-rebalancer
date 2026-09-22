package com.gemini.krakenbot.repository.table

import org.jetbrains.exposed.v1.core.Table

/**
 * A durable proof that one OHLC request covered a pair/interval/range at a known wall-clock time.
 * Keeping the fetch proof separate from candles preserves the distinction between an empty,
 * successfully fetched series and a missing cache row.
 *
 * [sinceEpochSecond] is the ORIGINAL request bound; the proven span is [coverageFromEpochSecond,
 * coverageUntilEpochSecond). The coverage columns are nullable only so pre-existing rows migrate
 * without fabricated bounds — rows with null coverage fail closed in [loadCovered][com.gemini.krakenbot.repository.HistoricalOhlcRepository.loadCovered]
 * and are pruned on the next save of their lineage. Every newly written proof carries bounds.
 */
object HistoricalOhlcFetchTable : Table("historical_ohlc_fetches") {
    val pair = varchar("pair", 16)
    val intervalMinutes = integer("interval_minutes")
    val sinceEpochSecond = long("since_epoch_second")
    val fetchedAtEpochSecond = long("fetched_at_epoch_second")
    val coverageFromEpochSecond = long("coverage_from_epoch_second").nullable()
    val coverageUntilEpochSecond = long("coverage_until_epoch_second").nullable()

    init {
        index("idx_historical_ohlc_fetches_lookup", false, pair, intervalMinutes, sinceEpochSecond)
    }

    override val primaryKey = PrimaryKey(pair, intervalMinutes, sinceEpochSecond, fetchedAtEpochSecond)
}
