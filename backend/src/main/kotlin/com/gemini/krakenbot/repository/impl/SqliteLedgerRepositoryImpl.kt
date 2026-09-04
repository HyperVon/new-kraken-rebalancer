package com.gemini.krakenbot.repository.impl

import com.gemini.krakenbot.model.LedgerEvent
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.LedgerRepository
import com.gemini.krakenbot.repository.table.LedgerTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory
import java.time.Instant

class SqliteLedgerRepositoryImpl(private val database: Database) : LedgerRepository {
    private val log = LoggerFactory.getLogger(SqliteLedgerRepositoryImpl::class.java)

    override suspend fun saveLedgers(events: List<LedgerEvent>): Int =
        database.safeTransactionIO(log, "Failed to save ledger entries to database") {
            var inserted = 0
            for (event in events) {
                // INSERT OR IGNORE relies on the unique (ledger id, timestamp, asset, type) index.
                inserted += LedgerTable.insertIgnore {
                    LedgerTable.applyTo(it, event)
                }.insertedCount
            }
            inserted
        }

    override suspend fun getLedgersInRange(from: Instant, to: Instant): List<LedgerEvent> = database.readTransactionIO {
        LedgerTable
            .selectAll()
            .where {
                (LedgerTable.timestamp greaterEq from.toEpochMilli()) and
                    (LedgerTable.timestamp lessEq to.toEpochMilli())
            }.orderBy(LedgerTable.timestamp, SortOrder.DESC)
            .map(LedgerTable::toModel)
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
            val inceptionEpochMs = readSyncMetadataInTransaction(
                SyncMetadataKeys.DETECTED_INCEPTION_EPOCH_MS,
            )?.toLongOrNull()
            val cutoffMillis = cutoff.toEpochMilli()
            val effectiveCutoff = if (inceptionEpochMs != null && cutoffMillis > inceptionEpochMs) {
                minOf(cutoffMillis, inceptionEpochMs - 5000L)
            } else {
                cutoffMillis
            }
            LedgerTable.deleteWhere {
                timestamp less effectiveCutoff
            }
        }
}
