package com.gemini.krakenbot.service

/**
 * Display-only read-model for the auto-detected strategy inception shown on the Settings page.
 *
 * This is a local-DB read snapshot only: it surfaces the durable
 * `DETECTED_INCEPTION_EPOCH_MS` / `DETECTED_INCEPTION_SOURCE` metadata plus the lightweight
 * recovery [InceptionRecoveryStatus] for the in-progress case. It never triggers detection,
 * never writes metadata, and never copies into `Settings.inceptionDate` — the Settings input
 * remains the sole write path for the configured date.
 *
 * A `CONFIGURED` source is intentionally treated as absent here: the configured date is
 * already visible in the Settings input field, so this model only surfaces `auto` and
 * `auto-recovered` detections.
 */
data class InceptionDisplayInfo(
    /** Auto-detected date formatted as YYYY-MM-DD (UTC), or null when nothing auto-detected. */
    val dateText: String? = null,
    /** Detection source (`auto` / `auto-recovered`), null when [dateText] is null. */
    val source: String? = null,
    /** True while the bounded recovery pass reports IN_PROGRESS and no date is cached yet. */
    val inProgress: Boolean = false,
)
