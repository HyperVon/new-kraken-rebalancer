package com.gemini.krakenbot.model

enum class ComparisonAvailability {
    AVAILABLE,
    UNAVAILABLE,
}

enum class ComparisonConfidence {
    RECONCILED,
    ESTIMATED,
}

enum class ComparisonUnavailableReason {
    INSUFFICIENT_SNAPSHOTS,
    NON_POSITIVE_BASELINE,
    BASELINE_MISMATCH,
    MISSING_PRICE,
    ASSET_UNIVERSE_CHANGED,
    UNSUPPORTED_TRADE,
    UNEXPLAINED_BALANCE_CHANGE,
}
