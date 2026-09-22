package com.gemini.krakenbot.repository.impl

import com.gemini.krakenbot.model.SyncMetadataKeys
import com.gemini.krakenbot.repository.FundingEvidenceIdentityRecord
import com.gemini.krakenbot.repository.FundingEvidenceIdentityStore
import com.gemini.krakenbot.repository.table.HistorySyncMetadataTable
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * Persists the durable funding-provenance identity in the shared sync-metadata table. Only
 * the content fingerprint, bounded scope metadata, and update timestamp are stored — never
 * raw Kraken payloads, credentials, or signatures. Read and write failures degrade to "no
 * durable identity" (cache misses, authoritative replay) and never fail a trading path.
 */
class SqliteFundingEvidenceIdentityStoreImpl(private val database: Database) : FundingEvidenceIdentityStore {
    private val log = LoggerFactory.getLogger(SqliteFundingEvidenceIdentityStoreImpl::class.java)

    override fun load(): FundingEvidenceIdentityRecord? = try {
        transaction(database) {
            val fingerprint =
                readSyncMetadataInTransaction(SyncMetadataKeys.FUNDING_PROVENANCE_DURABLE_FINGERPRINT)
                    ?: return@transaction null
            FundingEvidenceIdentityRecord(
                fingerprint = fingerprint,
                identity =
                readSyncMetadataInTransaction(SyncMetadataKeys.FUNDING_PROVENANCE_DURABLE_IDENTITY).orEmpty(),
                updatedAtEpochSeconds = readSyncMetadataInTransaction(
                    SyncMetadataKeys.FUNDING_PROVENANCE_DURABLE_UPDATED_AT_EPOCH_SEC,
                )?.toLongOrNull() ?: 0L,
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Unable to read durable funding evidence identity: {}", e.message)
        null
    }

    override fun save(record: FundingEvidenceIdentityRecord) {
        try {
            transaction(database) {
                writeSyncMetadataInTransaction(
                    SyncMetadataKeys.FUNDING_PROVENANCE_DURABLE_FINGERPRINT,
                    record.fingerprint,
                )
                writeSyncMetadataInTransaction(
                    SyncMetadataKeys.FUNDING_PROVENANCE_DURABLE_IDENTITY,
                    record.identity,
                )
                writeSyncMetadataInTransaction(
                    SyncMetadataKeys.FUNDING_PROVENANCE_DURABLE_UPDATED_AT_EPOCH_SEC,
                    record.updatedAtEpochSeconds.toString(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The in-memory prepared resolver still serves this process; the durable copy is
            // an optimization for later requests and restarts.
            log.warn("Unable to persist durable funding evidence identity: {}", e.message)
        }
    }

    override fun clear() {
        try {
            transaction(database) {
                HistorySyncMetadataTable.deleteWhere {
                    HistorySyncMetadataTable.key inList listOf(
                        SyncMetadataKeys.FUNDING_PROVENANCE_DURABLE_FINGERPRINT,
                        SyncMetadataKeys.FUNDING_PROVENANCE_DURABLE_IDENTITY,
                        SyncMetadataKeys.FUNDING_PROVENANCE_DURABLE_UPDATED_AT_EPOCH_SEC,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Unable to clear durable funding evidence identity: {}", e.message)
        }
    }
}
