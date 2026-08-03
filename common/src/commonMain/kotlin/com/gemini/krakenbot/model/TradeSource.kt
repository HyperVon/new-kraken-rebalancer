package com.gemini.krakenbot.model

/** Provenance of trade economics: local estimate, settled API fill, or legacy data. */
enum class TradeSource {
    LOCAL_ESTIMATE,
    API_FILL,
    LEGACY_UNKNOWN,
    ;

    companion object {
        fun fromDbValue(value: String?): TradeSource? = entries.firstOrNull { it.name == value }
    }
}
