package com.gemini.krakenbot.model

/**
 * Sync progress and seeding metadata keys shared by persistence, history sync,
 * and API/JSON status payloads. Lives in `:common` model (not view.util) so the
 * repository layer does not depend on view packages.
 */
object SyncMetadataKeys {
    const val SYNC_OFFSET = "sync_offset"
    const val SYNC_TOTAL = "sync_total"
    const val HISTORY_SEEDED = "history_seeded"

    /**
     * Epoch-second watermark written after each successful Kraken sync. Used when
     * [getLatestTradeTime] is null (e.g. only dry-run locals) so subsequent syncs stay
     * incremental instead of re-pulling full history from EPOCH (CQ-8-M2).
     */
    const val SYNC_WATERMARK_EPOCH_SEC = "sync_watermark_epoch_sec"
    const val COMPLETED = "completed"
    const val IS_SEEDED = "seeded"
    const val OFFSET = "offset"
    const val TOTAL = "total"
}
