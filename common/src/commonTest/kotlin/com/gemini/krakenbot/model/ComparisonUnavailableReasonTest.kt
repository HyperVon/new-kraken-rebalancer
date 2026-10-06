package com.gemini.krakenbot.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ComparisonUnavailableReasonTest {

    @Test
    fun eachEnumEntryHasSpecificDisplayText() {
        assertEquals(
            "Not enough history exists in this range to compare strategies.",
            ComparisonUnavailableReason.INSUFFICIENT_SNAPSHOTS.displayText,
        )
        assertEquals(
            "The comparison needs a positive starting portfolio value.",
            ComparisonUnavailableReason.NON_POSITIVE_BASELINE.displayText,
        )
        assertEquals(
            "Starting holdings do not reconcile with the recorded portfolio value.",
            ComparisonUnavailableReason.BASELINE_MISMATCH.displayText,
        )
        assertEquals(
            "A required historical asset price is missing.",
            ComparisonUnavailableReason.MISSING_PRICE.displayText,
        )
        assertEquals(
            "The configured asset set changed during this range.",
            ComparisonUnavailableReason.ASSET_UNIVERSE_CHANGED.displayText,
        )
        assertEquals(
            "A recorded trade cannot be reconciled safely.",
            ComparisonUnavailableReason.UNSUPPORTED_TRADE.displayText,
        )
        assertEquals(
            "A deposit, withdrawal, transfer, or incomplete trade history may exist.",
            ComparisonUnavailableReason.UNEXPLAINED_BALANCE_CHANGE.displayText,
        )
        assertEquals(
            "Funding provenance could not be retrieved; the comparison cannot classify funding safely.",
            ComparisonUnavailableReason.FUNDING_PROVENANCE_UNAVAILABLE.displayText,
        )
        assertEquals(
            "Historical snapshot coverage is incomplete for part of the strategy period, so the earliest trustworthy comparison start cannot be determined.",
            ComparisonUnavailableReason.HISTORICAL_COVERAGE_GAP.displayText,
        )
        assertEquals(
            "Market price evidence is being refreshed; the comparison continues in the background and will update on the next refresh.",
            ComparisonUnavailableReason.EXTERNAL_EVIDENCE_REFRESHING.displayText,
        )
    }

    @Test
    fun displayTextForMapsKnownReasonStrings() {
        assertEquals(
            "Not enough history exists in this range to compare strategies.",
            ComparisonUnavailableReason.displayTextFor("INSUFFICIENT_SNAPSHOTS"),
        )
        assertEquals(
            "The comparison needs a positive starting portfolio value.",
            ComparisonUnavailableReason.displayTextFor("NON_POSITIVE_BASELINE"),
        )
        assertEquals(
            "Starting holdings do not reconcile with the recorded portfolio value.",
            ComparisonUnavailableReason.displayTextFor("BASELINE_MISMATCH"),
        )
        assertEquals(
            "A required historical asset price is missing.",
            ComparisonUnavailableReason.displayTextFor("MISSING_PRICE"),
        )
        assertEquals(
            "The configured asset set changed during this range.",
            ComparisonUnavailableReason.displayTextFor("ASSET_UNIVERSE_CHANGED"),
        )
        assertEquals(
            "A recorded trade cannot be reconciled safely.",
            ComparisonUnavailableReason.displayTextFor("UNSUPPORTED_TRADE"),
        )
        assertEquals(
            "A deposit, withdrawal, transfer, or incomplete trade history may exist.",
            ComparisonUnavailableReason.displayTextFor("UNEXPLAINED_BALANCE_CHANGE"),
        )
        assertEquals(
            "Funding provenance could not be retrieved; the comparison cannot classify funding safely.",
            ComparisonUnavailableReason.displayTextFor("FUNDING_PROVENANCE_UNAVAILABLE"),
        )
        assertEquals(
            "Historical snapshot coverage is incomplete for part of the strategy period, so the earliest trustworthy comparison start cannot be determined.",
            ComparisonUnavailableReason.displayTextFor("HISTORICAL_COVERAGE_GAP"),
        )
        assertEquals(
            "Market price evidence is being refreshed; the comparison continues in the background and will update on the next refresh.",
            ComparisonUnavailableReason.displayTextFor("EXTERNAL_EVIDENCE_REFRESHING"),
        )
    }

    @Test
    fun configurationTransitionReasonsExplainRetainedEvidenceFailures() {
        val expectedTexts = mapOf(
            "CONFIGURATION_BALANCE_EVIDENCE_MISSING" to
                "Retained historical spot balance evidence is missing at a configuration change.",
            "CONFIGURATION_TRANSITION_INCOMPLETE" to
                "Retained history does not confirm completion of asset additions or removals in the transition window.",
            "CONFIGURATION_TRANSITION_UNSETTLED" to
                "Historical balances for the affected assets were still changing in the transition window.",
            "CONFIGURATION_FUNDING_EVIDENCE_MISSING" to
                "Retained history cannot establish the funding split for assets added at a configuration change.",
        )
        expectedTexts.forEach { (reason, expectedText) ->
            assertEquals(expectedText, ComparisonUnavailableReason.valueOf(reason).displayText)
            assertEquals(expectedText, ComparisonUnavailableReason.displayTextFor(reason))
        }
    }

    @Test
    fun displayTextForDefaultsToInvalidResponseOnUnknownOrNull() {
        assertEquals(
            "Comparison data could not be validated.",
            ComparisonUnavailableReason.displayTextFor("UNKNOWN_REASON"),
        )
        assertEquals(
            "Comparison data could not be validated.",
            ComparisonUnavailableReason.displayTextFor(""),
        )
        assertEquals(
            "Comparison data could not be validated.",
            ComparisonUnavailableReason.displayTextFor(null),
        )
    }
}
