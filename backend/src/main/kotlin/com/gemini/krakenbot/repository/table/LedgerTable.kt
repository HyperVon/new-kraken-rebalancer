package com.gemini.krakenbot.repository.table

import com.gemini.krakenbot.model.LedgerEvent
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import java.time.Instant

object LedgerTable : Table("ledgers") {
    val id = integer("id").autoIncrement()
    val timestamp = long("timestamp")
    val ledgerId = varchar("ledger_id", 64)
    val refid = varchar("refid", 64).nullable()
    val type = varchar("type", 16)
    val subtype = varchar("subtype", 32).nullable()
    val aclass = varchar("aclass", 16).nullable()
    val asset = varchar("asset", 16)
    val amount = decimal("amount", 24, 8)

    // Ledger fees are denominated in the ledger asset, unlike trade fees which are USD-scale.
    val fee = decimal("fee", 24, 8)
    val balance = decimal("balance", 24, 8)

    init {
        index("idx_ledgers_timestamp", false, timestamp)
        index("idx_ledgers_refid", false, refid)
        index("idx_ledgers_dedupe", true, ledgerId, timestamp, asset, type)
    }

    override val primaryKey = PrimaryKey(id)

    fun toModel(row: ResultRow): LedgerEvent = LedgerEvent(
        ledgerId = row[ledgerId],
        refid = row[refid],
        time = Instant.ofEpochMilli(row[timestamp]),
        type = row[type],
        subtype = row[subtype],
        aclass = row[aclass],
        asset = row[asset],
        amount = row[amount],
        fee = row[fee],
        balance = row[balance],
        // The legacy schema used zero as the in-memory sentinel for a missing/unparseable balance.
        hasAuthoritativeBalance = row[balance].signum() != 0,
    )

    fun applyTo(builder: UpdateBuilder<*>, event: LedgerEvent) {
        builder[timestamp] = event.time.toEpochMilli()
        builder[ledgerId] = event.ledgerId
        builder[refid] = event.refid
        builder[type] = event.type
        builder[subtype] = event.subtype
        builder[aclass] = event.aclass
        builder[asset] = event.asset
        builder[amount] = event.amount
        builder[fee] = event.fee
        builder[balance] = event.balance
    }
}
