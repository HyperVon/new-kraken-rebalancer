package com.gemini.krakenbot.model

/**
 * Snapshot-identity metadata keys: rows referenced by these keys anchor the recorded series
 * (inception, recovered/approved baselines, accepted comparison start). A full series rewrite
 * must preserve them, and ordinary series queries hide a preserved anchor only when a rewrite
 * placed a recorded snapshot at the same instant.
 */
val SNAPSHOT_IDENTITY_METADATA_KEYS: List<String> =
    listOf(
        SyncMetadataKeys.INCEPTION_SNAPSHOT_ID,
        SyncMetadataKeys.INCEPTION_RECOVERY_BASELINE_SNAPSHOT_ID,
        SyncMetadataKeys.INCEPTION_APPROVED_BASELINE_SNAPSHOT_ID,
        SyncMetadataKeys.INCEPTION_COMPARISON_START_SNAPSHOT_ID,
    )
