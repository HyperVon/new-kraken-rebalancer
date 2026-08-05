package com.gemini.krakenbot.repository.table

import org.jetbrains.exposed.v1.core.Table

object LedgerTable : Table("ledgers") {
    val id = integer("id").autoIncrement()
    val timestamp = long("timestamp")
    val refid = varchar("refid", 64)
    val type = varchar("type", 16)
    val subtype = varchar("subtype", 32).nullable()
    val aclass = varchar("aclass", 16).nullable()
    val asset = varchar("asset", 16)
    val amount = decimal("amount", 24, 8)
    val fee = decimal("fee", 18, 4)
    val balance = decimal("balance", 24, 8)

    init {
        index("idx_ledgers_timestamp", false, timestamp)
        index("idx_ledgers_refid", false, refid)
        index("idx_ledgers_dedupe", true, refid, timestamp, asset, type)
    }

    override val primaryKey = PrimaryKey(id)
}
