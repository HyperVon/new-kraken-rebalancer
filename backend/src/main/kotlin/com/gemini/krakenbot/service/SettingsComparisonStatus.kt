package com.gemini.krakenbot.service

import com.gemini.krakenbot.model.ComparisonAvailability
import com.gemini.krakenbot.model.ComparisonUnavailableReason

/**
 * Outcome of the durable automatic Buy & Hold baseline proof, independent of the current
 * comparison's availability. VERIFIED means the persisted inception proof validated against
 * current evidence, or the full evaluation just proved it; null means the automatic baseline
 * is unproven or was not evaluated.
 */
enum class AutomaticBaselineStatus {
    VERIFIED,
}

/**
 * Read-model outcome of the passive Settings comparison evaluation. It exposes the
 * display fields the effective-baseline slot needs alongside the proposal result, so the
 * async fragment renders both from the single passive comparison it already performs.
 *
 * [baselineStatus] and [baselineTimestamp] describe the durable automatic baseline proof;
 * [comparisonAvailability] describes whether the tail-inclusive comparison was evaluated and
 * succeeded. They are deliberately independent: the persisted proof is append-tolerant, so a
 * validated fast-path read returns a VERIFIED baseline with a null [comparisonAvailability] —
 * the current comparison was not evaluated in that request and must never be reported
 * AVAILABLE. A non-null [comparisonAvailability] always reflects an evaluation that actually
 * ran. [baselineTimestamp] is the anchor the proof or the evaluation resolved — never the
 * strategy inception by itself.
 */
data class SettingsComparisonStatus(
    val baselineStatus: AutomaticBaselineStatus? = null,
    val baselineTimestamp: String? = null,
    val comparisonAvailability: ComparisonAvailability? = null,
    val unavailableReason: ComparisonUnavailableReason? = null,
    /** Evidence timestamp the passive comparison failed at, when the reason carries one. */
    val unavailableAt: String? = null,
    val proposal: ComparisonStartProposal? = null,
)
