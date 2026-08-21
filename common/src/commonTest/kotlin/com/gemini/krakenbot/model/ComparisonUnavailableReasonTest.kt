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
