package com.gemini.krakenbot.repository.table

import org.jetbrains.exposed.sql.Table

/** Exposed table definition for sync metadata. */
object HistorySyncMetadataTable : Table("history_sync_metadata") {
    val key = varchar("key", 64)
    val value = varchar("value", 64)

    override val primaryKey = PrimaryKey(key)
}
