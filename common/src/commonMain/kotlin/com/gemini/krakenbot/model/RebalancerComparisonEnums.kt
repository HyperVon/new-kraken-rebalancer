package com.gemini.krakenbot.model

import com.gemini.krakenbot.view.util.ViewText

enum class ComparisonAvailability {
    AVAILABLE,
    UNAVAILABLE,
}

enum class ComparisonConfidence {
    RECONCILED,
    ESTIMATED,
}

enum class ComparisonUnavailableReason(val displayText: String) {
    INSUFFICIENT_SNAPSHOTS(ViewText.UNAVAILABLE_INSUFFICIENT_SNAPSHOTS),
    NON_POSITIVE_BASELINE(ViewText.UNAVAILABLE_NON_POSITIVE_BASELINE),
    BASELINE_MISMATCH(ViewText.UNAVAILABLE_BASELINE_MISMATCH),
    MISSING_PRICE(ViewText.UNAVAILABLE_MISSING_PRICE),
    ASSET_UNIVERSE_CHANGED(ViewText.UNAVAILABLE_ASSET_UNIVERSE_CHANGED),
    UNSUPPORTED_TRADE(ViewText.UNAVAILABLE_UNSUPPORTED_TRADE),
    AMBIGUOUS_TRADE_OWNERSHIP(ViewText.UNAVAILABLE_AMBIGUOUS_TRADE_OWNERSHIP),
    UNEXPLAINED_BALANCE_CHANGE(ViewText.UNAVAILABLE_UNEXPLAINED_BALANCE_CHANGE),
    ;

    companion object {
        fun displayTextFor(reason: String?): String =
            entries.firstOrNull { it.name == reason }?.displayText ?: ViewText.UNAVAILABLE_INVALID_RESPONSE
    }
}
