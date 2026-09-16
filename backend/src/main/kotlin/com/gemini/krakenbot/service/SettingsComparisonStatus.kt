package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonUnavailableReason

/**
 * Read-model outcome of the passive Settings comparison evaluation. It exposes the
 * display fields the effective-baseline slot needs alongside the proposal result, so the
 * async fragment renders both from the single passive comparison it already performs.
 *
 * `availability` is null when the proposal gate chain short-circuited before any passive
 * comparison ran (auto-detected inception, insufficient snapshots, stale reconstruction,
 * or an ineligible unavailable reason). `baselineTimestamp` is the anchor the passive
 * comparison actually resolved — never the strategy inception by itself.
 */
data class SettingsComparisonStatus(
    val availability: ComparisonAvailability? = null,
    val baselineTimestamp: String? = null,
    val unavailableReason: ComparisonUnavailableReason? = null,
    val proposal: ComparisonStartProposal? = null,
)
