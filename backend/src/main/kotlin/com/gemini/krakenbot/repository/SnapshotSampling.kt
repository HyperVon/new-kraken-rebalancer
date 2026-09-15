package com.gemini.krakenbot.repository

/** Upper bound of points returned by sampled snapshot-series reads. */
internal const val MAX_SNAPSHOT_POINTS = 300

/**
 * Samples a timestamp-ordered series down to [maxPoints] while keeping both range endpoints.
 * Callers exclude identity anchors before sampling so a preserved anchor cannot displace the
 * recorded snapshot that shares its instant.
 */
internal fun <T> List<T>.downsampleSnapshots(maxPoints: Int = MAX_SNAPSHOT_POINTS): List<T> {
    if (size <= maxPoints) return this
    return List(maxPoints) { sampleIndex ->
        val sourceIndex =
            (sampleIndex.toLong() * lastIndex.toLong() / (maxPoints - 1).toLong()).toInt()
        this[sourceIndex]
    }
}
