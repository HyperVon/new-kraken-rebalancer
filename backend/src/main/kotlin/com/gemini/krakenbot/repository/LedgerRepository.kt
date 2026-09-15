package com.gemini.krakenbot.repository

import com.gemini.krakenbot.model.LedgerEvent
import java.math.BigDecimal
import java.time.Instant

interface LedgerRepository {
    /** Persists ledger entries, skipping rows whose (ledger id, timestamp, asset, type) already exist. */
    suspend fun saveLedgers(events: List<LedgerEvent>): Int

    suspend fun getLedgersInRange(from: Instant, to: Instant): List<LedgerEvent>

    /** Retained ledger rows carrying any of [refIds] across the whole stored history. */
    suspend fun getLedgersByRefIds(refIds: Collection<String>): List<LedgerEvent>

    /**
     * Latest authoritative retained balance at or before [atOrBefore] for each of [symbols],
     * keyed by normalized symbol. Symbols without an authoritative retained balance are omitted.
     */
    suspend fun getLatestAuthoritativeBalances(
        symbols: Collection<String>,
        atOrBefore: Instant,
    ): Map<String, BigDecimal>

    /** Latest ledger entry time; null when no entries are stored. */
    suspend fun getLatestLedgerTime(): Instant?

    suspend fun getSyncMetadata(key: String): String?

    suspend fun setSyncMetadata(key: String, value: String)

    suspend fun isLedgersSeeded(): Boolean

    suspend fun setLedgersSeeded(seeded: Boolean)

    suspend fun pruneLedgersOlderThan(cutoff: Instant): Int
}
