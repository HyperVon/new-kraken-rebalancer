package com.gemini.krakenbot.model

import com.gemini.krakenbot.view.util.ViewText

/**
 * Which synthetic benchmark a buy-and-hold comparison was built from.
 *
 * Both methods measure the same Actual portfolio; they differ only in what the strategy's major
 * asset-selection decisions are assumed to have been worth.
 *
 * Lives in `:common` because the wire DTO, the server, and the browser chart label all have to
 * agree on the name. [seriesLabel] is what the chart legend renders, so the served benchmark is
 * always named from the response rather than assumed by the page.
 */
enum class BenchmarkMethod(val seriesLabel: String, val configurationEvidence: ConfigurationEvidence) {
    /** Freezes the approved inception portfolio and holds it forever. Forensic reference. */
    FIXED_INCEPTION_HOLD(ViewText.BUY_AND_HOLD_FIXED_INCEPTION, ConfigurationEvidence.NOT_APPLICABLE),

    /**
     * Follows the same major, persistent allocation changes the strategy made, applying one synthetic
     * portfolio transition per inferred regime change and otherwise holding. This is the primary
     * comparison because it isolates the value of routine rebalancing from asset selection.
     */
    INFERRED_CONFIGURATION_MATCHED_HOLD(ViewText.BUY_AND_HOLD_CONFIG_MATCHED, ConfigurationEvidence.INFERRED),
    ;

    companion object {
        /**
         * Resolves a wire-level method name, falling back to the primary benchmark. A response that
         * omits or misspells the field must not be labelled as the forensic reference.
         */
        fun fromNameOrPrimary(name: String?): BenchmarkMethod =
            entries.firstOrNull { it.name == name } ?: INFERRED_CONFIGURATION_MATCHED_HOLD
    }
}

/** Chart series label for a wire-level benchmark name; see [BenchmarkMethod.fromNameOrPrimary]. */
fun benchmarkSeriesLabel(name: String?): String = BenchmarkMethod.fromNameOrPrimary(name).seriesLabel

/**
 * Provenance of the allocation history a benchmark method relies on.
 *
 * Deliberately separate from `ComparisonConfidence`: a comparison can be fully reconciled
 * arithmetically while its configuration history is only inferred.
 */
enum class ConfigurationEvidence {
    /** No configuration history is used beyond the approved inception holdings. */
    NOT_APPLICABLE,

    /**
     * Allocation changes were inferred from persistent trading and balance behavior because exact
     * historical configuration changes were never retained. Not proven history.
     */
    INFERRED,
}
