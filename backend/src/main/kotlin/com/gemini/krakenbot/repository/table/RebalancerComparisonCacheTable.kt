package com.gemini.krakenbot.repository.table

import org.jetbrains.exposed.v1.core.Table

/** One latest durable comparison result per effective reconciled source range. */
object RebalancerComparisonCacheTable : Table("rebalancer_comparison_cache") {
    val fromEpochMillis = long("from_epoch_millis")
    val toEpochMillis = long("to_epoch_millis")
    val inputFingerprint = varchar("input_fingerprint", 64)
    val resultJson = text("result_json")
    val calculatedAtEpochMillis = long("calculated_at_epoch_millis")
    val ohlcDependenciesJson = text("ohlc_dependencies_json").default("[]")

    override val primaryKey = PrimaryKey(fromEpochMillis, toEpochMillis)
}
