package com.gemini.krakenbot.repository.table

import org.jetbrains.exposed.v1.core.Table

object InceptionInferenceTable : Table("inception_inference") {
    val fingerprint = varchar("fingerprint", 64)
    val evidenceDigest = varchar("evidence_digest", 64)
    val modelVersion = varchar("model_version", 16)
    val coverageStartEpochMs = long("coverage_start_epoch_ms").nullable()
    val coverageEndEpochMs = long("coverage_end_epoch_ms").nullable()
    val horizonEpochSec = long("horizon_epoch_sec").nullable()
    val firstPositiveEpochMs = long("first_positive_epoch_ms").nullable()
    val inferredStartEpochMs = long("inferred_start_epoch_ms").nullable()
    val inferredWindowStartEpochMs = long("inferred_window_start_epoch_ms").nullable()
    val inferredWindowEndEpochMs = long("inferred_window_end_epoch_ms").nullable()
    val inferredStartStrength = varchar("inferred_start_strength", 16).nullable()
    val inferredStartReasons = text("inferred_start_reasons").nullable()
    val inferredStartContradictions = text("inferred_start_contradictions").nullable()
    val strongestObservedStartEpochMs = long("strongest_observed_start_epoch_ms").nullable()
    val strongestEpisodeStrength = varchar("strongest_episode_strength", 16).nullable()
    val strongestEpisodeReasons = text("strongest_episode_reasons").nullable()
    val strongestEpisodeContradictions = text("strongest_episode_contradictions").nullable()
    val earliestAmbiguousStartEpochMs = long("earliest_ambiguous_start_epoch_ms").nullable()
    val earlierAmbiguousCandidateCount = integer("earlier_ambiguous_candidate_count")
    val unsupportedMarketCount = integer("unsupported_market_count")
    val unsupportedMarketSamples = varchar("unsupported_market_samples", 256).nullable()
    val competingCandidateCount = integer("competing_candidate_count")

    override val primaryKey = PrimaryKey(fingerprint)
}

object InceptionInferenceCandidateTable : Table("inception_inference_candidates") {
    val fingerprint = varchar("fingerprint", 64)
    val position = integer("position")
    val observedStartEpochMs = long("observed_start_epoch_ms")
    val observedEndEpochMs = long("observed_end_epoch_ms")
    val windowStartEpochMs = long("window_start_epoch_ms")
    val windowEndEpochMs = long("window_end_epoch_ms")
    val strength = varchar("strength", 16)
    val reasons = text("reasons")
    val contradictions = text("contradictions")
    val assetCount = integer("asset_count")
    val assetSymbols = varchar("asset_symbols", 256)
    val orderCount = integer("order_count")
    val repeatedEvidenceCount = integer("repeated_evidence_count")
    val timescalesSeconds = varchar("timescales_seconds", 64)

    override val primaryKey = PrimaryKey(fingerprint, position)
}
