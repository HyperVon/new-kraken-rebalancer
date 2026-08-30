package com.gemini.krakenbot.config

import com.gemini.krakenbot.repository.table.OrderIntentTable
import com.gemini.krakenbot.repository.table.SchemaMigrationTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.exists
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import java.time.Instant

internal const val CURRENT_SCHEMA_VERSION = 5

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
)

internal fun JdbcTransaction.applySchemaMigrations() {
    val appliedVersion =
        SchemaMigrationTable
            .select(SchemaMigrationTable.version)
            .orderBy(SchemaMigrationTable.version, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(SchemaMigrationTable.version)
            ?: 0

    val now = Instant.now().toEpochMilli()
    SCHEMA_MIGRATIONS
        .filter { it.version > appliedVersion }
        .forEach { migration ->
            migration.action?.invoke(this)
            SchemaMigrationTable.insert {
                it[version] = migration.version
                it[name] = migration.name
                it[appliedAt] = now
            }
        }
}
