package com.gemini.krakenbot.config

import org.jetbrains.exposed.v1.jdbc.JdbcTransaction

internal data class ExpectedIndex(
    val tableName: String,
    val name: String,
    val unique: Boolean,
    val columns: List<String>,
)

internal val expectedIndexes = listOf(
    ExpectedIndex("portfolio_snapshots", "idx_snapshots_timestamp", false, listOf("timestamp")),
    ExpectedIndex("ledgers", "idx_ledgers_timestamp", false, listOf("timestamp")),
    ExpectedIndex("ledgers", "idx_ledgers_refid", false, listOf("refid")),
    ExpectedIndex("ledgers", "idx_ledgers_dedupe", true, listOf("ledger_id", "timestamp", "asset", "type")),
    ExpectedIndex("trades", "idx_trades_timestamp", false, listOf("timestamp")),
    ExpectedIndex(
        "trades",
        "idx_trades_pair_side_timestamp",
        false,
        listOf("pair", "side", "timestamp"),
    ),
    ExpectedIndex("trades", "idx_trades_success", false, listOf("success")),
    ExpectedIndex("trades", "idx_trades_cycle_id", false, listOf("cycle_id")),
    ExpectedIndex("trades", "idx_trades_trade_id", false, listOf("trade_id")),
    ExpectedIndex("trades", "idx_trades_submission_state", false, listOf("submission_state")),
    ExpectedIndex("order_intents", "idx_order_intents_state", false, listOf("state")),
    ExpectedIndex("order_intents", "idx_order_intents_created_at", false, listOf("created_at")),
    ExpectedIndex("order_intents", "idx_order_intents_local_trade_id", false, listOf("local_trade_id")),
    ExpectedIndex("order_intents", "ux_order_intents_client_order_id", true, listOf("client_order_id")),
    ExpectedIndex("action_logs", "idx_actionlogs_snapshot_id", false, listOf("snapshot_id")),
    ExpectedIndex("asset_snapshots", "idx_assetsnapshots_snapshot_id", false, listOf("snapshot_id")),
)

internal fun JdbcTransaction.readIndexDefinition(index: ExpectedIndex): Pair<Boolean, List<String>>? {
    val unique = exec("PRAGMA index_list('${index.tableName}')") { resultSet ->
        var value: Boolean? = null
        while (resultSet.next()) {
            if (resultSet.getString("name") == index.name) {
                value = resultSet.getInt("unique") != 0
                break
            }
        }
        value
    } ?: return null
    val columns = exec("PRAGMA index_info('${index.name}')") { resultSet ->
        buildList {
            while (resultSet.next()) {
                add(resultSet.getString("name") ?: "")
            }
        }
    } ?: emptyList()
    return unique to columns
}

internal fun JdbcTransaction.repairInvalidIndexes() {
    expectedIndexes.forEach { expectedIndex ->
        val actualDefinition = readIndexDefinition(expectedIndex)
        if (actualDefinition != null && actualDefinition != (expectedIndex.unique to expectedIndex.columns)) {
            exec("DROP INDEX IF EXISTS ${expectedIndex.name}")
        }
    }
}
