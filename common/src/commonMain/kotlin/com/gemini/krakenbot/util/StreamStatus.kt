package com.gemini.krakenbot.util

object StreamStatus {
    fun isStale(timeSinceUpdateSeconds: Long): Boolean =
        timeSinceUpdateSeconds > PrecisionConstants.STALE_THRESHOLD_SECONDS
}
