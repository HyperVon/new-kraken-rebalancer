package com.gemini.krakenbot.model

import java.time.Instant

/** One behavioral candidate episode, persisted as a bounded evidence summary. */
data class InceptionCandidateEvidence(
    val observedStart: Instant,
    val observedEnd: Instant,
    val windowStart: Instant,
    val windowEnd: Instant,
    val strength: String,
    val reasons: List<String>,
    val contradictions: List<String>,
    val assetCount: Int,
    val assetSymbols: List<String>,
    val orderCount: Int,
    val repeatedEvidenceCount: Int,
    val timescalesSeconds: Set<Long>,
)

/**
 * Display-only historical strategy-start inference evidence for one account scope.
 * Read models must never copy these values into manual inception inputs.
 */
data class InceptionInferenceEvidence(
    val fingerprint: String,
    val evidenceDigest: String,
    val modelVersion: String,
    val coverageStart: Instant?,
    val coverageEnd: Instant?,
    val horizon: Instant?,
    val firstPositive: Instant?,
    val inferredStart: Instant?,
    val inferredWindowStart: Instant?,
    val inferredWindowEnd: Instant?,
    val strongestObservedStart: Instant?,
    val strength: String?,
    val reasons: List<String>,
    val contradictions: List<String>,
    val unsupportedMarketCount: Int,
    val unsupportedMarketSamples: List<String>,
    val competingCandidateCount: Int,
    val candidates: List<InceptionCandidateEvidence> = emptyList(),
)
