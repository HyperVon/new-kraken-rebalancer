package com.gemini.krakenbot.service.history

import java.nio.file.Path
import java.sql.Connection

/**
 * Builds a database in the reviewed version-2 History schema so migration tests can prove
 * upgrade behavior against the real prior shape without carrying production DDL dead code.
 */
internal fun openV2HistoryDatabase(databasePath: Path): Connection {
    val connection = java.sql.DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}")
    connection.autoCommit = false
    try {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE history_schema_meta (
                                    id INTEGER PRIMARY KEY CHECK(id = 1),
                                    schema_version INTEGER NOT NULL,
                                    initialized_at_ns INTEGER NOT NULL
                                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE history_observations (
                                    observation_id TEXT PRIMARY KEY,
                                    cycle_id TEXT NOT NULL,
                                    source_key TEXT NOT NULL,
                                    source_mode TEXT NOT NULL CHECK(source_mode IN ('ACCOUNT', 'SIMULATION')),
                                    account_identity_digest TEXT,
                                    simulation_session_id TEXT,
                                    managed_scope_fingerprint TEXT NOT NULL,
                                    managed_scope_symbols TEXT NOT NULL,
                                    wallet_scope TEXT NOT NULL,
                                    balance_source TEXT NOT NULL,
                                    price_source TEXT NOT NULL,
                                    valuation_currency TEXT NOT NULL,
                                    sample_selection TEXT NOT NULL,
                                    observed_at_ns INTEGER NOT NULL,
                                    balance_request_started_at_ns INTEGER NOT NULL,
                                    balance_response_ended_at_ns INTEGER NOT NULL,
                                    price_request_started_at_ns INTEGER NOT NULL,
                                    price_response_ended_at_ns INTEGER NOT NULL,
                                    persisted_at_ns INTEGER NOT NULL,
                                    status TEXT NOT NULL CHECK(status IN ('COMPLETE', 'INCOMPLETE')),
                                    total_usd TEXT,
                                    incomplete_reasons TEXT NOT NULL,
                                    payload_hash TEXT NOT NULL,
                                    UNIQUE(source_key, cycle_id),
                                    CHECK((source_mode = 'ACCOUNT' AND account_identity_digest IS NOT NULL AND
                                           simulation_session_id IS NULL) OR
                                          (source_mode = 'SIMULATION' AND account_identity_digest IS NULL AND
                                           simulation_session_id IS NOT NULL)),
                                    CHECK((status = 'COMPLETE' AND total_usd IS NOT NULL) OR
                                          (status = 'INCOMPLETE' AND total_usd IS NULL))
                                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE history_observation_assets (
                                    observation_id TEXT NOT NULL REFERENCES history_observations(observation_id) ON DELETE RESTRICT,
                                    asset_symbol TEXT NOT NULL,
                                    status TEXT NOT NULL,
                                    raw_balance_keys TEXT NOT NULL,
                                    raw_balance_values TEXT NOT NULL,
                                    quantity TEXT,
                                    requested_pair TEXT,
                                    response_pair TEXT,
                                    candidate_response_pairs TEXT NOT NULL,
                                    candidate_raw_prices TEXT NOT NULL,
                                    raw_price TEXT,
                                    price_usd TEXT,
                                    value_usd TEXT,
                                    reason TEXT,
                                    PRIMARY KEY(observation_id, asset_symbol)
                                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE history_cycle_activity (
                                    source_key TEXT NOT NULL,
                                    cycle_id TEXT NOT NULL,
                                    source_mode TEXT NOT NULL,
                                    account_identity_digest TEXT,
                                    simulation_session_id TEXT,
                                    managed_scope_fingerprint TEXT NOT NULL,
                                    managed_scope_symbols TEXT NOT NULL,
                                    occurred_at_ns INTEGER NOT NULL,
                                    dry_run INTEGER NOT NULL,
                                    actions TEXT NOT NULL,
                                    payload_hash TEXT NOT NULL,
                                    PRIMARY KEY(source_key, cycle_id)
                                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE history_trades (
                                    account_identity_digest TEXT NOT NULL,
                                    trade_id TEXT NOT NULL,
                                    order_txid TEXT,
                                    post_txid TEXT,
                                    pair TEXT,
                                    base_asset TEXT,
                                    quote_currency TEXT,
                                    fee_currency TEXT,
                                    time_ns INTEGER,
                                    raw_time TEXT,
                                    type TEXT,
                                    order_type TEXT,
                                    price TEXT,
                                    cost TEXT,
                                    fee TEXT,
                                    volume TEXT,
                                    margin TEXT,
                                    misc TEXT,
                                    trade_sequence_id TEXT,
                                    maker INTEGER,
                                    ledger_ids TEXT NOT NULL,
                                    raw_record_json TEXT,
                                    has_valid_time INTEGER NOT NULL,
                                    has_valid_price INTEGER NOT NULL,
                                    has_valid_cost INTEGER NOT NULL,
                                    has_valid_fee INTEGER NOT NULL,
                                    has_valid_volume INTEGER NOT NULL,
                                    is_valid_record INTEGER NOT NULL,
                                    payload_hash TEXT NOT NULL,
                                    PRIMARY KEY(account_identity_digest, trade_id)
                                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE history_ledgers (
                                    account_identity_digest TEXT NOT NULL,
                                    ledger_id TEXT NOT NULL,
                                    refid TEXT,
                                    time_ns INTEGER,
                                    raw_time TEXT,
                                    type TEXT,
                                    subtype TEXT,
                                    asset_class TEXT,
                                    asset TEXT,
                                    amount TEXT,
                                    fee TEXT,
                                    balance TEXT,
                                    raw_record_json TEXT,
                                    has_valid_time INTEGER NOT NULL,
                                    has_valid_amount INTEGER NOT NULL,
                                    has_valid_fee INTEGER NOT NULL,
                                    has_valid_balance INTEGER NOT NULL,
                                    is_valid_record INTEGER NOT NULL,
                                    payload_hash TEXT NOT NULL,
                                    PRIMARY KEY(account_identity_digest, ledger_id)
                                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE history_balance_checkpoints (
                                    account_identity_digest TEXT NOT NULL,
                                    ledger_id TEXT NOT NULL,
                                    observed_at_ns INTEGER,
                                    raw_asset_class TEXT,
                                    raw_asset_key TEXT,
                                    raw_amount TEXT,
                                    raw_fee TEXT,
                                    raw_balance TEXT,
                                    quantity TEXT,
                                    status TEXT NOT NULL CHECK(status IN ('VALID', 'INVALID', 'MISSING')),
                                    reason TEXT,
                                    PRIMARY KEY(account_identity_digest, ledger_id),
                                    FOREIGN KEY(account_identity_digest, ledger_id)
                                        REFERENCES history_ledgers(account_identity_digest, ledger_id) ON DELETE RESTRICT
                                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE history_sync_status (
                                    account_identity_digest TEXT NOT NULL,
                                    feed TEXT NOT NULL CHECK(feed IN ('TRADES', 'LEDGERS')),
                                    scan_id TEXT NOT NULL,
                                    state TEXT NOT NULL CHECK(state IN ('NOT_STARTED', 'RUNNING', 'COMPLETE', 'PAUSED', 'FAILED')),
                                    window_start_sec INTEGER NOT NULL,
                                    window_end_sec INTEGER NOT NULL,
                                    next_cursor TEXT,
                                    fetched_count INTEGER NOT NULL,
                                    stored_count INTEGER NOT NULL,
                                    known_total INTEGER,
                                    last_page_at_ns INTEGER,
                                    last_error TEXT,
                                    PRIMARY KEY(account_identity_digest, feed)
                                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE history_sync_windows (
                                    scan_id TEXT PRIMARY KEY,
                                    account_identity_digest TEXT NOT NULL,
                                    feed TEXT NOT NULL,
                                    start_sec INTEGER NOT NULL,
                                    end_sec INTEGER NOT NULL,
                                    state TEXT NOT NULL,
                                    started_at_ns INTEGER NOT NULL,
                                    completed_at_ns INTEGER,
                                    CHECK(start_sec <= end_sec)
                                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE history_sync_seen_cursors (
                                    account_identity_digest TEXT NOT NULL,
                                    feed TEXT NOT NULL CHECK(feed IN ('TRADES', 'LEDGERS')),
                                    scan_id TEXT NOT NULL,
                                    cursor TEXT NOT NULL,
                                    PRIMARY KEY(account_identity_digest, feed, scan_id, cursor),
                                    FOREIGN KEY(scan_id) REFERENCES history_sync_windows(scan_id) ON DELETE CASCADE
                                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE history_daily_prices (
                                    account_identity_digest TEXT NOT NULL,
                                    raw_asset_key TEXT NOT NULL,
                                    candle_open_sec INTEGER NOT NULL,
                                    candle_end_sec INTEGER NOT NULL,
                                    pair TEXT NOT NULL,
                                    close_usd TEXT NOT NULL,
                                    source TEXT NOT NULL,
                                    PRIMARY KEY(account_identity_digest, raw_asset_key, candle_open_sec)
                                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE history_daily_values (
                                    account_identity_digest TEXT NOT NULL,
                                    source_asset_scope TEXT NOT NULL,
                                    day_end_sec INTEGER NOT NULL,
                                    total_usd TEXT,
                                    status TEXT NOT NULL,
                                    source TEXT NOT NULL,
                                    PRIMARY KEY(account_identity_digest, source_asset_scope, day_end_sec)
                                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE history_daily_assets (
                                    account_identity_digest TEXT NOT NULL,
                                    source_asset_scope TEXT NOT NULL,
                                    day_end_sec INTEGER NOT NULL,
                                    partition_key TEXT NOT NULL,
                                    raw_asset_class TEXT,
                                    raw_asset_key TEXT NOT NULL,
                                    quantity TEXT,
                                    price_usd TEXT,
                                    value_usd TEXT,
                                    price_pair TEXT,
                                    price_candle_open_sec INTEGER,
                                    price_source TEXT,
                                    status TEXT NOT NULL,
                                    reason TEXT,
                                    PRIMARY KEY(account_identity_digest, source_asset_scope, day_end_sec, partition_key),
                                    FOREIGN KEY(account_identity_digest, source_asset_scope, day_end_sec)
                                        REFERENCES history_daily_values(account_identity_digest, source_asset_scope, day_end_sec)
                                        ON DELETE RESTRICT
                                )
                """.trimIndent(),
            )
        }
        connection.prepareStatement(
            "INSERT INTO history_schema_meta(id, schema_version, initialized_at_ns) VALUES (1, 2, ?)",
        ).use { statement ->
            statement.setLong(1, System.currentTimeMillis() * 1_000_000L)
            statement.executeUpdate()
        }
        connection.commit()
        connection.autoCommit = true
        return connection
    } catch (error: Exception) {
        connection.rollback()
        connection.close()
        throw error
    }
}
