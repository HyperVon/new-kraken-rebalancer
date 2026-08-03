package com.gemini.krakenbot.api

fun buildSyncProgressResponse(seeded: Boolean, offset: String?, total: String?): SyncProgressResponse =
    SyncProgressResponse(
        seeded = seeded,
        offset = offset.orEmpty(),
        total = total.orEmpty(),
    )
