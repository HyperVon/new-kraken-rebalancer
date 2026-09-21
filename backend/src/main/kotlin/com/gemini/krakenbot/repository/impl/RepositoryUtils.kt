package com.gemini.krakenbot.repository.impl

import com.gemini.krakenbot.model.OrderIntentReconciliationException
import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.model.TradeReconciliationConflictException
import com.gemini.krakenbot.repository.table.HistorySyncMetadataTable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.slf4j.Logger
import java.io.IOException

inline fun <T> Database.safeTransaction(
    log: Logger,
    logMessage: String,
    exceptionMessage: String = "Database write failed",
    crossinline block: JdbcTransaction.() -> T,
): T {
    try {
        return transaction(this) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: OrderIntentReconciliationException) {
        throw e
    } catch (e: TradeReconciliationConflictException) {
        throw e
    } catch (e: Exception) {
        log.error(logMessage, e)
        if (e is IOException) throw e
        throw IOException(exceptionMessage, e)
    }
}

suspend fun <T> Database.safeTransactionIO(
    log: Logger,
    logMessage: String,
    exceptionMessage: String = "Database write failed",
    block: JdbcTransaction.() -> T,
): T = withContext(Dispatchers.IO) {
    safeTransaction(log, logMessage, exceptionMessage, block)
}

suspend fun <T> Database.readTransactionIO(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) {
    transaction(this@readTransactionIO) { block() }
}

suspend fun Database.readSyncMetadata(key: String): String? = readTransactionIO {
    readSyncMetadataInTransaction(key)
}

fun readSyncMetadataInTransaction(key: String): String? = HistorySyncMetadataTable
    .selectAll()
    .where { HistorySyncMetadataTable.key eq key }
    .firstOrNull()
    ?.get(HistorySyncMetadataTable.value)

/**
 * Advances the shared revision consumed by durable comparison caches. Call this in the same
 * transaction as any write that can change portfolio, trade, ledger, or historical-price
 * evidence, so a cache can never survive a partially committed evidence update.
 */
fun JdbcTransaction.bumpComparisonEvidenceRevision() {
    val current = readSyncMetadataInTransaction(SyncMetadataKeys.COMPARISON_EVIDENCE_REVISION)
        ?.toLongOrNull()
        ?: 0L
    HistorySyncMetadataTable.upsert {
        it[HistorySyncMetadataTable.key] = SyncMetadataKeys.COMPARISON_EVIDENCE_REVISION
        it[HistorySyncMetadataTable.value] = (current + 1L).toString()
    }
}

suspend fun Database.writeSyncMetadata(key: String, value: String, log: Logger, logMessage: String) {
    safeTransactionIO(log, logMessage) {
        HistorySyncMetadataTable.upsert {
            it[HistorySyncMetadataTable.key] = key
            it[HistorySyncMetadataTable.value] = value
        }
    }
}
