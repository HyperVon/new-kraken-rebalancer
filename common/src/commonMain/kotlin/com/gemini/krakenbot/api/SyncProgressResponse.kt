package com.gemini.krakenbot.api

import com.gemini.krakenbot.model.SyncMetadataKeys

/**
 * History `/api/history/sync-progress` JSON body.
 * Property names align with [SyncMetadataKeys] API-facing keys (`seeded`, `offset`, `total`).
 */
data class SyncProgressResponse(val seeded: Boolean, val offset: String, val total: String) {
    companion object {
        const val SEEDED_KEY: String = SyncMetadataKeys.IS_SEEDED
        const val OFFSET_KEY: String = SyncMetadataKeys.OFFSET
        const val TOTAL_KEY: String = SyncMetadataKeys.TOTAL
    }
}
