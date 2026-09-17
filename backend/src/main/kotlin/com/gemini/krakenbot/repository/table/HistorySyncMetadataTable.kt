package com.gemini.krakenbot.repository.table

import org.jetbrains.exposed.v1.core.Table

object HistorySyncMetadataTable : Table("history_sync_metadata") {
    val key = varchar("key", 64)
    // Full-wallet baseline universes exceed 64 chars; values are opaque strings with no length contract.
    val value = text("value")

    override val primaryKey = PrimaryKey(key)
}
