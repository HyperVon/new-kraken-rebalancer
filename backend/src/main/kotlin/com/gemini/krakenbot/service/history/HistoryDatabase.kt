package com.gemini.krakenbot.service.history

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** Fresh History-only SQLite database. Normal opens never create or migrate its schema. */
class HistoryDatabase private constructor(private val jdbcUrl: String, private val filePath: Path?) : AutoCloseable {
    constructor(databasePath: Path) : this("jdbc:sqlite:${databasePath.toAbsolutePath()}", databasePath)

    private val inMemoryKeepalive: Connection? = if (filePath == null) openRawConnection() else null

    /** Called only by the explicit data-initialization command or in-memory tests. */
    fun initializeFreshSchema() {
        filePath?.toAbsolutePath()?.parent?.let { Files.createDirectories(it) }
        connectRaw().use { connection ->
            connection.autoCommit = false
            try {
                val tables = connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
                    ).use { resultSet -> buildSet { while (resultSet.next()) add(resultSet.getString(1)) } }
                }
                if ("history_schema_meta" in tables) {
                    val version = connection.createStatement().use { statement ->
                        statement.executeQuery(
                            "SELECT schema_version FROM history_schema_meta WHERE id = 1",
                        ).use { resultSet ->
                            check(resultSet.next()) { "History schema marker is incomplete." }
                            resultSet.getInt(1)
                        }
                    }
                    check(version == HISTORY_SCHEMA_VERSION) { "Unsupported History schema version $version." }
                    connection.commit()
                    return
                }
                check(tables.isEmpty()) {
                    "History database has tables but no History schema marker; refusing to reuse it."
                }
                createSchema(connection)
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }
    }

    /** Opens only an existing, explicitly initialized History database. */
    fun connect(): Connection {
        if (filePath != null) {
            check(Files.isRegularFile(filePath)) {
                "Initialized History database is missing at ${filePath.toAbsolutePath()}."
            }
        }
        return connectRaw().also { connection ->
            val version = runCatching {
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT schema_version FROM history_schema_meta WHERE id = 1",
                    ).use { resultSet ->
                        check(resultSet.next()) { "History schema marker is missing." }
                        resultSet.getInt(1)
                    }
                }
            }.getOrElse { error ->
                connection.close()
                throw IllegalStateException("History database is not initialized.", error)
            }
            check(version == HISTORY_SCHEMA_VERSION) {
                connection.close()
                "Unsupported History schema version $version."
            }
        }
    }

    /** Validates the complete fresh schema and SQLite health before the trading loop can start. */
    fun verifyInitialized() {
        connect().use { connection ->
            val actualTables = connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
                ).use { resultSet -> buildSet { while (resultSet.next()) add(resultSet.getString(1)) } }
            }
            check(actualTables == REQUIRED_TABLES) { "History database schema is incomplete or unrecognized." }
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA quick_check").use { resultSet ->
                    check(resultSet.next() && resultSet.getString(1) == "ok") {
                        "History database integrity check failed."
                    }
                }
            }
        }
    }

    /** Whether this initialized store contains evidence belonging to a Kraken account. */
    fun hasAccountEvidence(): Boolean {
        if (filePath == null || !Files.exists(filePath)) return false
        return connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    ACCOUNT_EVIDENCE_TABLES.joinToString(" UNION ALL ") { table ->
                        "SELECT EXISTS(SELECT 1 FROM $table WHERE account_identity_digest IS NOT NULL)"
                    },
                ).use { resultSet ->
                    var evidenceFound = false
                    while (resultSet.next()) evidenceFound = evidenceFound || resultSet.getInt(1) != 0
                    evidenceFound
                }
            }
        }
    }

    override fun close() {
        inMemoryKeepalive?.close()
    }

    private fun connectRaw(): Connection = openRawConnection().also { connection ->
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("PRAGMA busy_timeout = 5000")
        }
    }

    private fun openRawConnection(): Connection = DriverManager.getConnection(jdbcUrl)

    private fun createSchema(connection: Connection) {
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

            statement.execute(
                "CREATE INDEX idx_history_observations_source_time " +
                    "ON history_observations(source_key, observed_at_ns)",
            )
            statement.execute(
                "CREATE INDEX idx_history_observation_assets_symbol " +
                    "ON history_observation_assets(asset_symbol, observation_id)",
            )
            statement.execute(
                "CREATE INDEX idx_history_cycle_activity_source_time " +
                    "ON history_cycle_activity(source_key, occurred_at_ns DESC)",
            )
            statement.execute(
                "CREATE INDEX idx_history_trades_account_time " +
                    "ON history_trades(account_identity_digest, time_ns)",
            )
            statement.execute(
                "CREATE INDEX idx_history_trades_pair_time " +
                    "ON history_trades(account_identity_digest, pair, time_ns)",
            )
            statement.execute(
                "CREATE INDEX idx_history_ledgers_account_time " +
                    "ON history_ledgers(account_identity_digest, time_ns)",
            )
            statement.execute(
                "CREATE INDEX idx_history_ledgers_asset_time " +
                    "ON history_ledgers(account_identity_digest, asset, time_ns)",
            )
            statement.execute(
                "CREATE INDEX idx_history_checkpoints_asset_time " +
                    "ON history_balance_checkpoints(account_identity_digest, raw_asset_class, raw_asset_key, observed_at_ns)",
            )
            statement.execute(
                "CREATE INDEX idx_history_sync_windows_coverage " +
                    "ON history_sync_windows(account_identity_digest, feed, state, start_sec, end_sec)",
            )
            statement.execute(
                "CREATE INDEX idx_history_daily_values_scope_day " +
                    "ON history_daily_values(account_identity_digest, source_asset_scope, day_end_sec)",
            )
            statement.execute(
                "CREATE INDEX idx_history_daily_assets_key_day " +
                    "ON history_daily_assets(account_identity_digest, raw_asset_key, day_end_sec)",
            )
        }
        connection.prepareStatement(
            "INSERT INTO history_schema_meta(id, schema_version, initialized_at_ns) VALUES (1, ?, ?)",
        ).use { statement ->
            statement.setInt(1, HISTORY_SCHEMA_VERSION)
            statement.setLong(2, InstantNanos.now())
            statement.executeUpdate()
        }
    }

    companion object {
        private const val HISTORY_SCHEMA_VERSION = 2
        private val REQUIRED_TABLES = setOf(
            "history_schema_meta",
            "history_observations",
            "history_observation_assets",
            "history_cycle_activity",
            "history_trades",
            "history_ledgers",
            "history_balance_checkpoints",
            "history_sync_status",
            "history_sync_windows",
            "history_sync_seen_cursors",
            "history_daily_prices",
            "history_daily_values",
            "history_daily_assets",
        )
        private val ACCOUNT_EVIDENCE_TABLES = listOf(
            "history_observations",
            "history_cycle_activity",
            "history_trades",
            "history_ledgers",
            "history_balance_checkpoints",
            "history_sync_status",
            "history_sync_windows",
            "history_sync_seen_cursors",
            "history_daily_prices",
            "history_daily_values",
            "history_daily_assets",
        )

        fun inMemory(): HistoryDatabase {
            val url = "jdbc:sqlite:file:history-${UUID.randomUUID()}?mode=memory&cache=shared"
            return HistoryDatabase(url, null)
        }
    }
}

private object InstantNanos {
    fun now(): Long = java.time.Instant.now().toEpochNanos()
}

internal fun java.time.Instant.toEpochNanos(): Long = epochSecond * 1_000_000_000L + nano

internal fun Long.toInstantNanos(): java.time.Instant = java.time.Instant.ofEpochSecond(
    this / 1_000_000_000L,
    this % 1_000_000_000L,
)
