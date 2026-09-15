package com.gemini.krakenbot.model

/** Provenance of trade economics: local estimate, settled API fill, explicit manual evidence, or legacy data. */
enum class TradeSource {
    LOCAL_ESTIMATE,
    API_FILL,

    /** Explicitly user/external trade evidence; absence of bot evidence is not enough. */
    MANUAL,
    LEGACY_UNKNOWN,
    ;

    companion object {
        fun fromDbValue(value: String?): TradeSource? = entries.firstOrNull { it.name == value }
    }
}
