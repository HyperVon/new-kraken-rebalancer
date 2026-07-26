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
    const val COMPLETED = "completed"
    const val IS_SEEDED = "seeded"
    const val OFFSET = "offset"
    const val TOTAL = "total"
}
