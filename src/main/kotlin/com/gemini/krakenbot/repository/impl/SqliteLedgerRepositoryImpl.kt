package com.gemini.krakenbot.repository.impl

import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.table.LedgerTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory
import java.time.Instant

class SqliteLedgerRepositoryImpl(private val database: Database) : LedgerRepository {
    private val log = LoggerFactory.getLogger(SqliteLedgerRepositoryImpl::class.java)

    private fun UpdateBuilder<*>.applyLedgerFields(event: LedgerEvent) {
        this[LedgerTable.timestamp] = event.time.toEpochMilli()
        this[LedgerTable.ledgerId] = event.ledgerId
        this[LedgerTable.refid] = event.refid
        this[LedgerTable.type] = event.type
        this[LedgerTable.subtype] = event.subtype
        this[LedgerTable.aclass] = event.aclass
        this[LedgerTable.asset] = event.asset
        this[LedgerTable.amount] = event.amount
        this[LedgerTable.fee] = event.fee
        this[LedgerTable.balance] = event.balance
    }

    override suspend fun saveLedgers(events: List<LedgerEvent>): Int =
        database.safeTransactionIO(log, "Failed to save ledger entries to database") {
            var inserted = 0
            for (event in events) {
                // INSERT OR IGNORE relies on the unique (ledger id, timestamp, asset, type) index.
                inserted += LedgerTable.insertIgnore {
                    it.applyLedgerFields(event)
                }.insertedCount
            }
            inserted
        }

    override suspend fun getLedgersInRange(from: Instant, to: Instant): List<LedgerEvent> = database.readTransactionIO {
        LedgerTable
            .selectAll()
            .andWhere {
                LedgerTable.timestamp greaterEq from.toEpochMilli()
            }.andWhere {
                LedgerTable.timestamp lessEq to.toEpochMilli()
            }.orderBy(LedgerTable.timestamp, SortOrder.DESC)
            .map { row -> buildLedgerFromRow(row) }
    }

    override suspend fun getLatestLedgerTime(): Instant? = database.readTransactionIO {
        LedgerTable
            .selectAll()
            .orderBy(LedgerTable.timestamp, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.let { Instant.ofEpochMilli(it[LedgerTable.timestamp]) }
    }

    override suspend fun getSyncMetadata(key: String): String? = database.readSyncMetadata(key)

    override suspend fun setSyncMetadata(key: String, value: String) {
        database.writeSyncMetadata(key, value, log, "Failed to upsert sync metadata")
    }

    override suspend fun isLedgersSeeded(): Boolean = getSyncMetadata(SyncMetadataKeys.LEDGERS_SEEDED) == "true"

    override suspend fun setLedgersSeeded(seeded: Boolean) {
        setSyncMetadata(SyncMetadataKeys.LEDGERS_SEEDED, seeded.toString())
    }

    override suspend fun pruneLedgersOlderThan(cutoff: Instant): Int =
        database.safeTransactionIO(log, "Failed to prune old ledger entries") {
            LedgerTable.deleteWhere {
                timestamp less cutoff.toEpochMilli()
            }
        }

    private fun buildLedgerFromRow(row: ResultRow): LedgerEvent = LedgerEvent(
        ledgerId = row[LedgerTable.ledgerId],
        refid = row[LedgerTable.refid],
        time = Instant.ofEpochMilli(row[LedgerTable.timestamp]),
        type = row[LedgerTable.type],
        subtype = row[LedgerTable.subtype],
        aclass = row[LedgerTable.aclass],
        asset = row[LedgerTable.asset],
        amount = row[LedgerTable.amount],
        fee = row[LedgerTable.fee],
        balance = row[LedgerTable.balance],
    )
}
