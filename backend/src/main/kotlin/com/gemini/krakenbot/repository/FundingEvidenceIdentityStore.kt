package com.gemini.krakenbot.repository

/**
 * Durable identity of the last successfully prepared funding-provenance evidence batch.
 *
 * The in-memory prepared resolver expires after a short TTL and dies with the process, but
 * the fingerprint derived from the evidence CONTENT is deterministic: the same deposits,
 * withdrawals, and internal transfers always hash to the same value. Persisting that
 * fingerprint (plus bounded scope metadata, never raw payloads) lets a comparison cache
 * constructed under one process be recognized as still-valid after the TTL lapses or the
 * application restarts, without trusting stale evidence for classification — resolution
 * always requires a fresh in-memory prepare; the durable record only certifies cache
 * identity, not provenance decisions.
 *
 * Operations are deliberately non-suspend: the fingerprint is consumed from a resolver
 * property getter that cannot suspend, and each call is a bounded (single-row) metadata
 * read or upsert on a local SQLite file executed from worker threads.
 */
interface FundingEvidenceIdentityStore {
    fun load(): FundingEvidenceIdentityRecord?

    fun save(record: FundingEvidenceIdentityRecord)

    /**
     * Removes the durable record. Called when a save cannot be verified so a later request
     * misses the cache (authoritative replay) instead of recognizing evidence under the
     * previous batch's fingerprint. Best-effort: a failed clear keeps the conservative
     * miss-on-next-preparation behavior through the in-memory TTL only.
     */
    fun clear()
}

data class FundingEvidenceIdentityRecord(
    /** Content fingerprint of the prepared evidence (sha256 of normalized records). */
    val fingerprint: String,
    /** Bounded scope metadata: evidence scope, funding families, queried epoch range. */
    val identity: String,
    val updatedAtEpochSeconds: Long,
)
