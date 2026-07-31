package com.gemini.krakenbot.api

/**
 * History `/api/history/sync-progress` JSON body.
 * Property names align with the API-facing sync metadata keys (`seeded`, `offset`, `total`).
 */
data class SyncProgressResponse(val seeded: Boolean, val offset: String, val total: String)
