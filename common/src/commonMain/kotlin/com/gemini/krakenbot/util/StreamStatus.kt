package com.gemini.krakenbot.util

object StreamStatus {
    fun isStale(timeSinceUpdateSeconds: Long): Boolean =
        timeSinceUpdateSeconds > PrecisionConstants.STALE_THRESHOLD_SECONDS

    fun isStale(timeSinceUpdateSeconds: Double): Boolean =
        timeSinceUpdateSeconds > PrecisionConstants.STALE_THRESHOLD_SECONDS.toDouble()
}
