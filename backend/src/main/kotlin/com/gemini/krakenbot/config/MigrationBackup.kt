package com.gemini.krakenbot.config

import org.jetbrains.exposed.v1.core.Table
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.time.Instant

private val log = LoggerFactory.getLogger("com.gemini.krakenbot.config.MigrationBackup")

internal fun backupBeforeMigrationIfNeeded(dbPath: String, tables: Array<Table>) {
    val databaseFile = resolveFileBackedDatabase(dbPath) ?: return
    if (!databaseFile.isFile || databaseFile.length() == 0L || !requiresMigrationBackup(databaseFile, tables)) return

    val source = databaseFile.toPath()
    val backup = source.resolveSibling(
        "${source.fileName}.pre-migration-${Instant.now().toEpochMilli()}.bak",
    )
    try {
        DriverManager.getConnection("jdbc:sqlite:${databaseFile.path}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            }
        }
        Files.copy(source, backup, StandardCopyOption.COPY_ATTRIBUTES)
        log.warn("Created pre-migration database backup at {}", backup)
    } catch (e: Exception) {
        throw IllegalStateException("Cannot create pre-migration database backup for $dbPath", e)
    }
}

private fun resolveFileBackedDatabase(dbPath: String): File? {
    val sqlitePath = dbPath.removePrefix("jdbc:sqlite:")
    val querylessPath = sqlitePath.substringBefore('?')
    if (dbPath == ":memory:" ||
        querylessPath == ":memory:" ||
        sqlitePath.contains("mode=memory", ignoreCase = true)
    ) {
        return null
    }

    val filePath = querylessPath.removePrefix("file:")
    return filePath.takeIf { it.isNotBlank() }?.let(::File)
}

private fun requiresMigrationBackup(databaseFile: File, tables: Array<Table>): Boolean = try {
    DriverManager.getConnection("jdbc:sqlite:${databaseFile.path}").use { connection ->
        connection.createStatement().use { statement ->
            fun exists(query: String): Boolean = statement.executeQuery(query).use { resultSet ->
                resultSet.next()
            }

            val schemaMigrationsExist = exists(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'schema_migrations' LIMIT 1",
            )
            val version = if (schemaMigrationsExist) {
                statement.executeQuery("SELECT MAX(version) FROM schema_migrations").use { resultSet ->
                    if (resultSet.next()) resultSet.getInt(1) else 0
                }
            } else {
                0
            }
            val orderIntentTableExists = exists(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'order_intents' LIMIT 1",
            )
            val localTradeIdColumnExists = orderIntentTableExists && statement.executeQuery(
                "PRAGMA table_info(order_intents)",
            ).use { resultSet ->
                generateSequence {
                    if (resultSet.next()) resultSet.getString("name") else null
                }.any { it == "local_trade_id" }
            }
            fun readIndexDefinition(index: ExpectedIndex): Pair<Boolean, List<String>>? {
                val unique = statement.executeQuery(
                    "PRAGMA index_list('${index.tableName}')",
                ).use { resultSet ->
                    var value: Boolean? = null
                    while (resultSet.next()) {
                        if (resultSet.getString("name") == index.name) {
                            value = resultSet.getInt("unique") != 0
                            break
                        }
                    }
                    value
                } ?: return null
                val columns = statement.executeQuery(
                    "PRAGMA index_info('${index.name}')",
                ).use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(resultSet.getString("name") ?: "")
                        }
                    }
                }
                return unique to columns
            }
            val indexesNeedRepair = expectedIndexes.any { expectedIndex ->
                readIndexDefinition(expectedIndex) != (expectedIndex.unique to expectedIndex.columns)
            }
            val columnsNeedRepair = tables.any { table ->
                val tableExists = exists(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '${table.tableName}' LIMIT 1",
                )
                if (!tableExists) {
                    true
                } else {
                    val actualColumns = statement.executeQuery(
                        "PRAGMA table_info('${table.tableName}')",
                    ).use { resultSet ->
                        generateSequence {
                            if (resultSet.next()) resultSet.getString("name") else null
                        }.toSet()
                    }
                    table.columns.any { column -> column.name !in actualColumns }
                }
            }
            if (version < CURRENT_SCHEMA_VERSION || !orderIntentTableExists ||
                !localTradeIdColumnExists || indexesNeedRepair || columnsNeedRepair
            ) {
                true
            } else {
                val needsProvenanceRewrite = exists(
                    """
                    SELECT 1 FROM trades
                    WHERE source IS NULL
                      AND success = 1
                      AND dry_run = 0
                      AND error_message IS NULL
                      AND slippage_percent IS NULL
                    LIMIT 1
                    """.trimIndent(),
                )
                val needsLegacyImport = exists(
                    """
                    SELECT 1
                    FROM trades t
                    WHERE t.dry_run = 0
                      AND t.submission_state IN ('PENDING', 'UNCERTAIN')
                      AND NOT EXISTS (
                          SELECT 1
                          FROM order_intents i
                          WHERE i.local_trade_id = t.id
                      )
                    LIMIT 1
                    """.trimIndent(),
                )
                val needsLegacyIdentityBackfill = exists(
                    """
                    SELECT 1
                    FROM order_intents i
                    JOIN trades t ON (
                        (i.client_order_id IS NOT NULL AND i.client_order_id = t.client_order_id)
                        OR i.client_order_id IS NULL AND t.client_order_id IS NULL
                    )
                    WHERE i.local_trade_id IS NULL
                      AND i.created_at = t.timestamp
                      AND i.pair = t.pair
                      AND i.symbol = t.symbol
                      AND i.side = t.side
                      AND i.volume = t.volume
                      AND i.usd_amount = t.usd_amount
                      AND t.dry_run = 0
                      AND (t.source = 'LOCAL_ESTIMATE' OR t.source IS NULL)
                    LIMIT 1
                    """.trimIndent(),
                )
                val needsTerminalReconciliation = exists(
                    """
                    SELECT 1
                    FROM order_intents i
                    JOIN trades t ON t.id = i.local_trade_id
                    WHERE i.state IN ('CONFIRMED', 'REJECTED')
                      AND t.submission_state IN ('PENDING', 'UNCERTAIN')
                    LIMIT 1
                    """.trimIndent(),
                )
                val needsPendingRecovery = exists(
                    "SELECT 1 FROM order_intents WHERE state = 'PENDING' LIMIT 1",
                )
                needsProvenanceRewrite || needsLegacyImport || needsLegacyIdentityBackfill ||
                    needsTerminalReconciliation || needsPendingRecovery
            }
        }
    }
} catch (e: Exception) {
    // Every schema-absence case above is guarded, so a probe failure here is a real fault
    // (corruption, lock, IO error). Keep the fail-safe backup decision but make it visible.
    log.warn(
        "Pre-migration probe failed for {}; creating a precautionary backup before initializing",
        databaseFile.path,
        e,
    )
    true
}
