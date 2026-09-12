package com.gemini.krakenbot.service.impl.history

import com.gemini.krakenbot.model.PortfolioSnapshot
import com.gemini.krakenbot.model.SNAPSHOT_IDENTITY_METADATA_KEYS
import com.gemini.krakenbot.repository.TradeRepository

/**
 * Snapshots referenced by identity metadata (inception, recovered/approved baselines, accepted
 * comparison start). These rows are provenance anchors: a series rewrite preserves them, so a
 * reconstructed snapshot for the same instant can sit next to its anchor. Series-level lookups
 * must prefer the reconstructed row whenever both exist.
 */
internal suspend fun TradeRepository.snapshotIdentityAnchors(): Set<PortfolioSnapshot> = SNAPSHOT_IDENTITY_METADATA_KEYS
    .mapNotNull { key -> getSyncMetadata(key)?.toIntOrNull() }
    .distinct()
    .mapNotNull { id -> getSnapshotById(id) }
    .toSet()
