package com.gemini.krakenbot.config

import com.gemini.krakenbot.repository.table.OrderIntentTable
import com.gemini.krakenbot.repository.table.SchemaMigrationTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.exists
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import java.time.Instant

internal const val CURRENT_SCHEMA_VERSION = 8

internal data class SchemaMigration(
    val version: Int,
    val name: String,
    val action: (JdbcTransaction.() -> Unit)? = null,
)

internal val SCHEMA_MIGRATIONS = listOf(
    SchemaMigration(1, "baseline"),
    SchemaMigration(2, "order-intent-journal") {
        if (!OrderIntentTable.exists()) {
            currentDialectMetadata.resetCaches()
            SchemaUtils.createStatements(OrderIntentTable).forEach { exec(it) }
            currentDialectMetadata.resetCaches()
        }
    },
    SchemaMigration(3, "legacy-submission-guard-import"),
    SchemaMigration(4, "legacy-trade-identity"),
    SchemaMigration(5, "ambiguous-legacy-client-order-id"),
    SchemaMigration(6, "order-intent-trade-foreign-key") {
        ensureOrderIntentTradeForeignKey()
    },
    SchemaMigration(7, "portfolio-stats-singleton") {
        migratePortfolioStatsToSingleton()
    },
    SchemaMigration(8, "ath-applied-flow-semantics"),
)

internal fun validateSchemaMigrations(migrations: List<SchemaMigration> = SCHEMA_MIGRATIONS) {
    val versions = migrations.map { it.version }
    check(versions == versions.distinct()) { "Schema migration versions must be unique." }
    check(versions == versions.sorted()) { "Schema migration versions must be ordered." }
    check(versions == (1..CURRENT_SCHEMA_VERSION).toList()) {
        "Schema migrations must cover every version through $CURRENT_SCHEMA_VERSION."
    }
}

internal fun JdbcTransaction.applySchemaMigrations() {
    validateSchemaMigrations()
    val appliedVersions =
        SchemaMigrationTable
            .select(SchemaMigrationTable.version)
            .map { it[SchemaMigrationTable.version] }
            .toSet()

    val now = Instant.now().toEpochMilli()
    SCHEMA_MIGRATIONS
        .filter { it.version !in appliedVersions }
        .forEach { migration ->
            migration.action?.invoke(this)
            SchemaMigrationTable.insert {
                it[version] = migration.version
                it[name] = migration.name
                it[appliedAt] = now
            }
        }
}

internal fun JdbcTransaction.rejectUnsupportedSchemaVersion() {
    val tableExists = exec(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'schema_migrations'",
    ) { resultSet -> resultSet.next() } ?: false
    if (!tableExists) return

    val versions = exec("SELECT version FROM schema_migrations ORDER BY version") { resultSet ->
        buildList {
            while (resultSet.next()) add(resultSet.getInt("version"))
        }
    }.orEmpty()
    val maxVersion = versions.maxOrNull() ?: 0
    check(maxVersion <= CURRENT_SCHEMA_VERSION) {
        "Database schema version $maxVersion is newer than this binary supports ($CURRENT_SCHEMA_VERSION)."
    }
}

internal fun JdbcTransaction.ensureOrderIntentTradeForeignKey() {
    val hasForeignKey = exec("PRAGMA foreign_key_list('order_intents')") { resultSet ->
        var found = false
        while (resultSet.next()) {
            if (resultSet.getString("table") == "trades" &&
                resultSet.getString("from") == "local_trade_id" &&
                resultSet.getString("to") == "id" &&
                resultSet.getString("on_delete").equals("RESTRICT", ignoreCase = true)
            ) {
                found = true
            }
        }
        found
    } ?: false
    if (hasForeignKey) return

    exec(
        """
        CREATE TABLE order_intents_with_fk (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            cycle_id VARCHAR(36),
            client_order_id VARCHAR(36),
            client_order_id_ambiguous BOOLEAN NOT NULL DEFAULT 0,
            pair VARCHAR(16) NOT NULL,
            symbol VARCHAR(16) NOT NULL,
            side VARCHAR(4) NOT NULL,
            volume DECIMAL(24, 8) NOT NULL,
            usd_amount DECIMAL(18, 2) NOT NULL,
            expected_price DECIMAL(24, 8),
            created_at INTEGER NOT NULL,
            state VARCHAR(16) NOT NULL,
            order_txid VARCHAR(64),
            error_message TEXT,
            resolved_at INTEGER,
            resolution_evidence TEXT,
            local_trade_id INTEGER REFERENCES trades(id) ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    exec(
        """
        INSERT INTO order_intents_with_fk (
            id, cycle_id, client_order_id, client_order_id_ambiguous, pair, symbol, side,
            volume, usd_amount, expected_price, created_at, state, order_txid, error_message,
            resolved_at, resolution_evidence, local_trade_id
        )
        SELECT id, cycle_id, client_order_id, client_order_id_ambiguous, pair, symbol, side,
            volume, usd_amount, expected_price, created_at, state, order_txid, error_message,
            resolved_at, resolution_evidence,
            CASE WHEN local_trade_id IS NOT NULL AND EXISTS
                (SELECT 1 FROM trades WHERE trades.id = order_intents.local_trade_id)
                THEN local_trade_id ELSE NULL END
        FROM order_intents
        """.trimIndent(),
    )
    exec("DROP TABLE order_intents")
    exec("ALTER TABLE order_intents_with_fk RENAME TO order_intents")
    exec("CREATE INDEX idx_order_intents_state ON order_intents(state)")
    exec("CREATE INDEX idx_order_intents_created_at ON order_intents(created_at)")
    exec("CREATE INDEX idx_order_intents_local_trade_id ON order_intents(local_trade_id)")
    exec("CREATE UNIQUE INDEX ux_order_intents_client_order_id ON order_intents(client_order_id)")
    currentDialectMetadata.resetCaches()
}

private fun JdbcTransaction.migratePortfolioStatsToSingleton() {
    // Legacy databases predate last_trusted_drawdown_pct: carry it only when
    // the existing table already has it, otherwise rebuild without it (a later
    // addMissingColumnsStatements pass adds it as NULL).
    val legacyColumns = exec("PRAGMA table_info(portfolio_stats)") { resultSet ->
        buildSet {
            while (resultSet.next()) add(resultSet.getString("name"))
        }
    } ?: emptySet()
    exec(
        """
        CREATE TABLE portfolio_stats_with_singleton (
            id INTEGER PRIMARY KEY NOT NULL,
            all_time_high DECIMAL(18, 2),
            last_trusted_drawdown_pct DECIMAL(10, 4)
        )
        """.trimIndent(),
    )
    if ("last_trusted_drawdown_pct" in legacyColumns) {
        exec(
            """
            INSERT INTO portfolio_stats_with_singleton (id, all_time_high, last_trusted_drawdown_pct)
            SELECT 1, MAX(all_time_high),
                (SELECT last_trusted_drawdown_pct FROM portfolio_stats
                 ORDER BY all_time_high DESC, id DESC LIMIT 1)
            FROM portfolio_stats HAVING COUNT(*) > 0
            """.trimIndent(),
        )
    } else {
        exec(
            """
            INSERT INTO portfolio_stats_with_singleton (id, all_time_high)
            SELECT 1, MAX(all_time_high) FROM portfolio_stats HAVING COUNT(*) > 0
            """.trimIndent(),
        )
    }
    exec("DROP TABLE portfolio_stats")
    exec("ALTER TABLE portfolio_stats_with_singleton RENAME TO portfolio_stats")
    currentDialectMetadata.resetCaches()
}
