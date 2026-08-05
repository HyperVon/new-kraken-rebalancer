package com.gemini.krakenbot.repository

import com.gemini.krakenbot.model.LedgerEvent
import java.time.Instant

interface LedgerRepository {
    /** Persists ledger entries, skipping rows whose (refid, timestamp, asset, type) already exist. */
    suspend fun saveLedgers(events: List<LedgerEvent>): Int

    suspend fun getLedgersInRange(from: Instant, to: Instant): List<LedgerEvent>

    /** Latest ledger entry time; null when no entries are stored. */
    suspend fun getLatestLedgerTime(): Instant?

    suspend fun getSyncMetadata(key: String): String?

    suspend fun setSyncMetadata(key: String, value: String)

    suspend fun isLedgersSeeded(): Boolean

    suspend fun setLedgersSeeded(seeded: Boolean)

    suspend fun pruneLedgersOlderThan(cutoff: Instant): Int
}
