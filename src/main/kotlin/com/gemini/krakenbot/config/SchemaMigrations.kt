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

internal fun JdbcTransaction.applySchemaMigrations() {
    val appliedVersion =
        SchemaMigrationTable
            .select(SchemaMigrationTable.version)
            .orderBy(SchemaMigrationTable.version, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(SchemaMigrationTable.version)
            ?: 0

    if (appliedVersion < 1) {
        SchemaMigrationTable.insert {
            it[version] = 1
            it[name] = "baseline"
            it[appliedAt] = Instant.now().toEpochMilli()
        }
    }

    if (!OrderIntentTable.exists()) {
        currentDialectMetadata.resetCaches()
        SchemaUtils.createStatements(OrderIntentTable).forEach { exec(it) }
        currentDialectMetadata.resetCaches()
    }

    if (appliedVersion < 2) {
        SchemaMigrationTable.insert {
            it[version] = 2
            it[name] = "order-intent-journal"
            it[appliedAt] = Instant.now().toEpochMilli()
        }
    }

    if (appliedVersion < 3) {
        SchemaMigrationTable.insert {
            it[version] = 3
            it[name] = "legacy-submission-guard-import"
            it[appliedAt] = Instant.now().toEpochMilli()
        }
    }

    if (appliedVersion < 4) {
        SchemaMigrationTable.insert {
            it[version] = 4
            it[name] = "legacy-trade-identity"
            it[appliedAt] = Instant.now().toEpochMilli()
        }
    }

    if (appliedVersion < 5) {
        SchemaMigrationTable.insert {
            it[version] = 5
            it[name] = "ambiguous-legacy-client-order-id"
            it[appliedAt] = Instant.now().toEpochMilli()
        }
    }
}
