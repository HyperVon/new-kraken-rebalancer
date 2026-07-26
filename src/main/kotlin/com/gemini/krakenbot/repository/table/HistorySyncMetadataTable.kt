package com.gemini.krakenbot.repository.table

import org.jetbrains.exposed.v1.core.Table

object HistorySyncMetadataTable : Table("history_sync_metadata") {
    val key = varchar("key", 64)
    val value = varchar("value", 64)

    override val primaryKey = PrimaryKey(key)
}
