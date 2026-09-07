package com.gemini.krakenbot.service

import com.gemini.krakenbot.view.util.ViewText

/**
 * High-level status of the automatic inception detection surfaced on the Settings page.
 */
enum class InceptionDisplayStatus {
    CONFIRMED,
    IN_PROGRESS,
    NOT_DETECTED,
    UNAVAILABLE,
    AMBIGUOUS,
    BASELINE_UNAVAILABLE,
    COMPLETE_NO_BOT_EVIDENCE,
    FAILED,
    VALIDATION_PENDING,
    MANUAL_OVERRIDE,
}

/**
 * Display-only read-model for the auto-detected strategy inception shown on the Settings page.
 *
 * This is a local-DB read snapshot only: it surfaces durable confirmed and inferred inception
 * metadata gated by current account trust and the applicable configuration fingerprint. It never
 * triggers detection, never writes metadata, and never copies into `Settings.inceptionDate` — the
 * Settings input remains the sole write path for the configured date.
 *
 * Only whitelisted automatic sources (`auto`, `auto-recovered`) qualify for [InceptionDisplayStatus.CONFIRMED].
 */
data class InceptionDisplayInfo(
    val status: InceptionDisplayStatus = InceptionDisplayStatus.NOT_DETECTED,
    /** Auto-detected date formatted as YYYY-MM-DD (UTC), or null when nothing auto-detected. */
    val dateText: String? = null,
    /** Detection source (`auto` / `auto-recovered`), null when [dateText] is null. */
    val source: String? = null,
    /** Informative user-facing status message, or null to use the default for [status]. */
    val message: String? = null,
    /** Inferred behavioral start, independent of confirmation and baseline readiness. */
    val inferredStartText: String? = null,
    /** Bounds of the evidence window used for the inferred start, when available. */
    val inferredWindowStartText: String? = null,
    val inferredWindowEndText: String? = null,
    /** First fill with positive local ownership evidence, not necessarily strategy inception. */
    val firstPositiveText: String? = null,
) {
    /** True while the bounded recovery pass reports IN_PROGRESS. */
    val inProgress: Boolean get() = status == InceptionDisplayStatus.IN_PROGRESS

    /** Formats the user-facing text for display on the Settings page. */
    fun toDisplayText(): String = when (status) {
        InceptionDisplayStatus.CONFIRMED ->
            if (dateText != null) {
                "${ViewText.INCEPTION_DETECTED_LABEL}: $dateText. ${message ?: ViewText.INCEPTION_DETECTED_LEAVE_BLANK}"
            } else {
                ViewText.INCEPTION_DETECTED_NOT_STARTED
            }

        InceptionDisplayStatus.IN_PROGRESS ->
            message ?: ViewText.INCEPTION_DETECTED_IN_PROGRESS

        InceptionDisplayStatus.NOT_DETECTED ->
            message ?: ViewText.INCEPTION_DETECTED_NOT_STARTED

        InceptionDisplayStatus.AMBIGUOUS ->
            message ?: ViewText.INCEPTION_DETECTED_AMBIGUOUS

        InceptionDisplayStatus.BASELINE_UNAVAILABLE ->
            message ?: ViewText.INCEPTION_DETECTED_BASELINE_UNAVAILABLE

        InceptionDisplayStatus.COMPLETE_NO_BOT_EVIDENCE ->
            message ?: ViewText.INCEPTION_DETECTED_COMPLETE_NO_BOT_EVIDENCE

        InceptionDisplayStatus.FAILED ->
            message ?: ViewText.INCEPTION_DETECTED_FAILED

        InceptionDisplayStatus.UNAVAILABLE ->
            message ?: ViewText.INCEPTION_DETECTED_UNAVAILABLE

        InceptionDisplayStatus.VALIDATION_PENDING ->
            message ?: ViewText.INCEPTION_DETECTED_VALIDATION_PENDING

        InceptionDisplayStatus.MANUAL_OVERRIDE ->
            message ?: ViewText.INCEPTION_DETECTED_MANUAL_OVERRIDE
    }
}
