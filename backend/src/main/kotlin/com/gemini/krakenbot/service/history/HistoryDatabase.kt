package com.gemini.krakenbot.service.history

import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.util.UUID

/**
 * History SQLite database with versioned schema upgrades. Fresh databases start at the
 * current version; an older initialized database is upgraded in one transaction so a failed
 * upgrade leaves the original data recoverable and blocks trading instead of stranding it.
 */
class HistoryDatabase private constructor(private val jdbcUrl: String, private val filePath: Path?) : AutoCloseable {
    constructor(databasePath: Path) : this("jdbc:sqlite:${databasePath.toAbsolutePath()}", databasePath)

    /** One upgrade step per supported source version; applied in order until the current version. */
    private val migrations = mapOf<Int, (Connection) -> Unit>(
        2 to ::migrateV2ToV3,
        3 to ::migrateV3ToV4,
        4 to ::migrateV4ToV5,
    )

    private val inMemoryKeepalive: Connection? = if (filePath == null) openRawConnection() else null
    private val journalModeLock = Any()

    @Volatile
    private var journalModeVerified = filePath == null

    /** Called only by the explicit data-initialization command or in-memory tests. */
    fun initializeFreshSchema() {
        filePath?.toAbsolutePath()?.parent?.let { Files.createDirectories(it) }
        connectRaw().use { connection ->
            ensureWalMode(connection)
            connection.autoCommit = false
            try {
                val tables = connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
                    ).use { resultSet -> buildSet { while (resultSet.next()) add(resultSet.getString(1)) } }
                }
                if ("history_schema_meta" in tables) {
                    ensureCurrentSchema(connection)
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

    /** Opens an existing, explicitly initialized History database, upgrading older schemas. */
    fun connect(): Connection {
        if (filePath != null) {
            check(Files.isRegularFile(filePath)) {
                "Initialized History database is missing at ${filePath.toAbsolutePath()}."
            }
        }
        val connection = connectRaw()
        val initialized = try {
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'history_schema_meta'",
                ).use { resultSet -> resultSet.next() }
            }
        } catch (error: Throwable) {
            runCatching { connection.close() }
            throw error
        }
        if (!initialized) {
            connection.close()
            throw IllegalStateException("History database is not initialized.")
        }
        try {
            ensureWalMode(connection)
            // Ordinary readers must not reserve SQLite's writer just to check the schema.
            // An actual upgrade still reserves it and rechecks the version inside the transaction.
            if (readSchemaVersion(connection) != HISTORY_SCHEMA_VERSION) {
                connection.autoCommit = false
                ensureCurrentSchema(connection)
                connection.commit()
                connection.autoCommit = true
            }
            return connection
        } catch (error: Exception) {
            runCatching { connection.rollback() }
            connection.close()
            throw IllegalStateException("History database schema could not be opened or upgraded.", error)
        }
    }

    /**
     * Brings the open connection to [HISTORY_SCHEMA_VERSION] in its current transaction.
     * Older supported versions upgrade in place, one step at a time; newer or unknown versions
     * fail closed.
     */
    private fun ensureCurrentSchema(connection: Connection) {
        var version = readSchemaVersion(connection)
        while (version < HISTORY_SCHEMA_VERSION) {
            migrateSchema(connection, version)
            version += 1
        }
        if (version > HISTORY_SCHEMA_VERSION) {
            throw IllegalStateException(
                "History database has a newer schema version $version; expected $HISTORY_SCHEMA_VERSION.",
            )
        }
    }

    private fun readSchemaVersion(connection: Connection): Int = connection.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT schema_version FROM history_schema_meta WHERE id = 1",
        ).use { resultSet ->
            check(resultSet.next()) { "History schema marker is incomplete." }
            resultSet.getInt(1)
        }
    }

    private fun migrateSchema(connection: Connection, fromVersion: Int) {
        val migration = migrations[fromVersion]
            ?: throw IllegalStateException("No History schema migration is available from version $fromVersion.")
        try {
            migration(connection)
        } catch (error: Exception) {
            throw IllegalStateException("History schema upgrade from version $fromVersion failed.", error)
        }
        connection.prepareStatement(
            "UPDATE history_schema_meta SET schema_version = ? WHERE id = 1",
        ).use { statement ->
            statement.setInt(1, fromVersion + 1)
            check(statement.executeUpdate() == 1) { "History schema marker could not be updated." }
        }
    }

    /**
     * Version 3 to 4: durable verified coverage. The union of the scan windows the old schema
     * already proved is carried over, so an upgraded database keeps the coverage it had earned.
     */
    private fun migrateV3ToV4(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "CREATE TABLE history_sync_coverage(" +
                    "account_identity_digest TEXT NOT NULL, " +
                    "feed TEXT NOT NULL CHECK(feed IN ('TRADES', 'LEDGERS')), " +
                    "start_sec INTEGER NOT NULL, " +
                    "end_sec INTEGER NOT NULL, " +
                    "updated_at_ns INTEGER NOT NULL, " +
                    "PRIMARY KEY(account_identity_digest, feed, start_sec))",
            )
        }
        val windows = mutableListOf<Triple<String, String, LongRange>>()
        connection.prepareStatement(
            "SELECT account_identity_digest, feed, start_sec, end_sec FROM history_sync_windows " +
                "WHERE state = 'COMPLETE'",
        ).use { statement ->
            statement.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    windows += Triple(
                        resultSet.getString("account_identity_digest"),
                        resultSet.getString("feed"),
                        resultSet.getLong("start_sec")..resultSet.getLong("end_sec"),
                    )
                }
            }
        }
        val migratedAtNs = InstantNanos.now()
        windows.groupBy { it.first to it.second }.forEach { (key, group) ->
            val feed = HistoryFeed.valueOf(key.second)
            group.map { it.third }.forEach { range ->
                mergeCoverageSegment(connection, key.first, feed, range.first, range.last, migratedAtNs)
            }
        }
    }

    /** Version 4 to 5 adds account-wide daily balance evidence and durable valuation generations. */
    private fun migrateV4ToV5(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate("ALTER TABLE history_daily_values ADD COLUMN day_label TEXT NOT NULL DEFAULT ''")
            statement.executeUpdate(
                "ALTER TABLE history_daily_values ADD COLUMN generation_id TEXT NOT NULL DEFAULT 'legacy'",
            )
            statement.executeUpdate(
                "ALTER TABLE history_daily_values ADD COLUMN calculation_policy_version TEXT NOT NULL DEFAULT 'legacy'",
            )
            statement.executeUpdate(
                "ALTER TABLE history_daily_values ADD COLUMN source_revision INTEGER NOT NULL DEFAULT 0",
            )
            statement.executeUpdate(
                "ALTER TABLE history_daily_values ADD COLUMN updated_at_ns INTEGER NOT NULL DEFAULT 0",
            )
            statement.executeUpdate(
                "UPDATE history_daily_values SET day_label = date(day_end_sec, 'unixepoch', '-1 day') " +
                    "WHERE day_label = ''",
            )
            createV5Tables(statement)
            statement.executeUpdate(
                "INSERT INTO history_ledger_revisions(account_identity_digest, revision) " +
                    "SELECT DISTINCT account_identity_digest, 1 FROM history_ledgers",
            )
        }
    }

    private fun createV5Tables(statement: Statement) {
        statement.executeUpdate(
            "CREATE TABLE history_daily_balances(" +
                "account_identity_digest TEXT NOT NULL, " +
                "source_asset_scope TEXT NOT NULL, " +
                "day_end_sec INTEGER NOT NULL, " +
                "day_label TEXT NOT NULL, " +
                "partition_key TEXT NOT NULL, " +
                "raw_asset_class TEXT, " +
                "raw_asset_key TEXT NOT NULL, " +
                "quantity TEXT, " +
                "source_ledger_id TEXT, " +
                "source_observed_at_ns INTEGER, " +
                "source_revision INTEGER NOT NULL, " +
                "generation_id TEXT NOT NULL, " +
                "status TEXT NOT NULL CHECK(status IN " +
                "('VALID', 'NO_OPENING_ANCHOR', 'COVERAGE_GAP', 'INVALID_CHECKPOINT', 'AMBIGUOUS_ORDER')), " +
                "reason TEXT, " +
                "PRIMARY KEY(account_identity_digest, source_asset_scope, day_end_sec, partition_key))",
        )
        statement.executeUpdate(
            "CREATE TABLE history_daily_price_legs(" +
                "account_identity_digest TEXT NOT NULL, " +
                "raw_asset_key TEXT NOT NULL, " +
                "candle_open_sec INTEGER NOT NULL, " +
                "leg_index INTEGER NOT NULL, " +
                "pair TEXT NOT NULL, " +
                "close TEXT NOT NULL, " +
                "quote_asset TEXT NOT NULL, " +
                "PRIMARY KEY(account_identity_digest, raw_asset_key, candle_open_sec, leg_index), " +
                "FOREIGN KEY(account_identity_digest, raw_asset_key, candle_open_sec) " +
                "REFERENCES history_daily_prices(account_identity_digest, raw_asset_key, candle_open_sec) " +
                "ON DELETE CASCADE)",
        )
        statement.executeUpdate(
            "CREATE TABLE history_valuation_jobs(" +
                "account_identity_digest TEXT NOT NULL, " +
                "source_asset_scope TEXT NOT NULL, " +
                "generation_id TEXT NOT NULL, " +
                "state TEXT NOT NULL CHECK(state IN ('PENDING', 'RUNNING', 'WAITING', " +
                "'NEEDS_ATTENTION', 'COMPLETE')), " +
                "stage TEXT NOT NULL CHECK(stage IN (" +
                "'ESTABLISH_BALANCES', " +
                "'PRICE_AND_VALUE')), " +
                "target_start_day TEXT NOT NULL, " +
                "target_end_day TEXT NOT NULL, " +
                "next_day TEXT, " +
                "target_days INTEGER NOT NULL, " +
                "balances_processed INTEGER NOT NULL, " +
                "balances_valid INTEGER NOT NULL, " +
                "balances_blocked INTEGER NOT NULL, " +
                "required_price_tasks INTEGER, " +
                "completed_price_tasks INTEGER NOT NULL, " +
                "complete_value_days INTEGER NOT NULL, " +
                "blocked_value_days INTEGER NOT NULL, " +
                "source_revision INTEGER NOT NULL, " +
                "calculation_policy_version TEXT NOT NULL, " +
                "last_progress_at_ns INTEGER, " +
                "next_retry_at_ns INTEGER, " +
                "last_success_at_ns INTEGER, " +
                "last_error TEXT, " +
                "retry_attempt INTEGER NOT NULL DEFAULT 0, " +
                "started_at_ns INTEGER NOT NULL, " +
                "updated_at_ns INTEGER NOT NULL, " +
                "PRIMARY KEY(account_identity_digest, source_asset_scope))",
        )
        statement.executeUpdate(
            "CREATE TABLE history_ledger_revisions(" +
                "account_identity_digest TEXT PRIMARY KEY, revision INTEGER NOT NULL CHECK(revision >= 0))",
        )
        statement.executeUpdate(
            "CREATE INDEX idx_history_daily_balances_scope_day " +
                "ON history_daily_balances(account_identity_digest, source_asset_scope, day_end_sec)",
        )
    }

    /**
     * Version 2 to 3: durable job/progress/revision metadata plus structured cycle outcomes.
     * Existing records are preserved. A recent-refresh horizon is seeded only from proven
     * completed-window metadata; unfinished legacy scans are replayed through the normal
     * deduplicated scans rather than being guessed into a purpose.
     */
    private fun migrateV2ToV3(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "ALTER TABLE history_cycle_activity ADD COLUMN outcome TEXT",
            )
            statement.executeUpdate(
                "CREATE TABLE history_sync_jobs(" +
                    "account_identity_digest TEXT NOT NULL, " +
                    "feed TEXT NOT NULL CHECK(feed IN ('TRADES', 'LEDGERS')), " +
                    "purpose TEXT NOT NULL CHECK(purpose IN ('RECENT', 'BACKFILL', 'REPAIR')), " +
                    "state TEXT NOT NULL CHECK(state IN ('PENDING', 'RUNNING', 'COMPLETE', 'FAILED', 'BLOCKED')), " +
                    "window_start_sec INTEGER, " +
                    "window_end_sec INTEGER, " +
                    "cursor TEXT, " +
                    "completed_through_sec INTEGER, " +
                    "last_error TEXT, " +
                    "next_retry_at_ns INTEGER, " +
                    "last_success_at_ns INTEGER, " +
                    "updated_at_ns INTEGER NOT NULL, " +
                    "PRIMARY KEY(account_identity_digest, feed, purpose))",
            )
            statement.executeUpdate(
                "CREATE TABLE history_data_revision(" +
                    "id INTEGER PRIMARY KEY CHECK(id = 1), " +
                    "revision INTEGER NOT NULL)",
            )
            statement.executeUpdate("INSERT INTO history_data_revision(id, revision) VALUES (1, 1)")
            statement.executeUpdate(
                "INSERT INTO history_sync_jobs(" +
                    "account_identity_digest, feed, purpose, state, window_start_sec, window_end_sec, cursor, " +
                    "completed_through_sec, last_error, next_retry_at_ns, last_success_at_ns, updated_at_ns) " +
                    "SELECT account_identity_digest, feed, 'RECENT', 'COMPLETE', NULL, NULL, NULL, " +
                    "MAX(end_sec), NULL, NULL, MAX(completed_at_ns), " +
                    "CAST(strftime('%s','now') AS INTEGER) * 1000000000 " +
                    "FROM history_sync_windows WHERE state = 'COMPLETE' " +
                    "GROUP BY account_identity_digest, feed",
            )
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

    private fun ensureWalMode(connection: Connection) {
        if (filePath == null || journalModeVerified) return
        synchronized(journalModeLock) {
            if (journalModeVerified) return
            val currentMode = connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA journal_mode").use { resultSet ->
                    check(resultSet.next()) { "History database journal mode could not be read." }
                    resultSet.getString(1)
                }
            }
            val journalMode = if (currentMode.equals("wal", ignoreCase = true)) {
                currentMode
            } else {
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA journal_mode = WAL").use { resultSet ->
                        check(resultSet.next()) { "History database journal mode could not be changed to WAL." }
                        resultSet.getString(1)
                    }
                }
            }
            check(journalMode.equals("wal", ignoreCase = true)) {
                "History database requires WAL mode for concurrent snapshot reads and writes; found '$journalMode'."
            }
            journalModeVerified = true
        }
    }

    // Reserve the writer before transactional writes; deferred lock upgrades can fail immediately
    // when writers overlap. Read-only pagination explicitly uses a deferred snapshot instead.
    private fun openRawConnection(): Connection = DriverManager.getConnection(
        jdbcUrl,
        SQLiteConfig().apply { setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE) }.toProperties(),
    )

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
                    outcome TEXT,
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
                    day_label TEXT NOT NULL,
                    generation_id TEXT NOT NULL,
                    calculation_policy_version TEXT NOT NULL,
                    source_revision INTEGER NOT NULL,
                    updated_at_ns INTEGER NOT NULL,
                    PRIMARY KEY(account_identity_digest, source_asset_scope, day_end_sec)
                )
                """.trimIndent(),
            )

            createV5Tables(statement)
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
            statement.execute(
                """
                CREATE TABLE history_sync_jobs (
                    account_identity_digest TEXT NOT NULL,
                    feed TEXT NOT NULL CHECK(feed IN ('TRADES', 'LEDGERS')),
                    purpose TEXT NOT NULL CHECK(purpose IN ('RECENT', 'BACKFILL', 'REPAIR')),
                    state TEXT NOT NULL CHECK(state IN ('PENDING', 'RUNNING', 'COMPLETE', 'FAILED', 'BLOCKED')),
                    window_start_sec INTEGER,
                    window_end_sec INTEGER,
                    cursor TEXT,
                    completed_through_sec INTEGER,
                    last_error TEXT,
                    next_retry_at_ns INTEGER,
                    last_success_at_ns INTEGER,
                    updated_at_ns INTEGER NOT NULL,
                    PRIMARY KEY(account_identity_digest, feed, purpose)
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE history_data_revision (
                    id INTEGER PRIMARY KEY CHECK(id = 1),
                    revision INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute("INSERT INTO history_data_revision(id, revision) VALUES (1, 1)")
            statement.execute(
                """
                CREATE TABLE history_sync_coverage (
                    account_identity_digest TEXT NOT NULL,
                    feed TEXT NOT NULL CHECK(feed IN ('TRADES', 'LEDGERS')),
                    start_sec INTEGER NOT NULL,
                    end_sec INTEGER NOT NULL,
                    updated_at_ns INTEGER NOT NULL,
                    PRIMARY KEY(account_identity_digest, feed, start_sec)
                )
                """.trimIndent(),
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
        private const val HISTORY_SCHEMA_VERSION = 5
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
            "history_sync_jobs",
            "history_sync_coverage",
            "history_data_revision",
            "history_daily_prices",
            "history_daily_values",
            "history_daily_assets",
            "history_daily_balances",
            "history_daily_price_legs",
            "history_valuation_jobs",
            "history_ledger_revisions",
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
            "history_sync_jobs",
            "history_sync_coverage",
            "history_daily_prices",
            "history_daily_values",
            "history_daily_assets",
            "history_daily_balances",
            "history_daily_price_legs",
            "history_valuation_jobs",
            "history_ledger_revisions",
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
