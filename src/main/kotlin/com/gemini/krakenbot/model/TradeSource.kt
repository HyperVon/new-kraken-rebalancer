package com.gemini.krakenbot.model

/** Provenance of trade economics — local estimate at order time vs settled Kraken fill. */
enum class TradeSource {
    LOCAL_ESTIMATE,
    API_FILL,
    LEGACY_UNKNOWN,
    ;

    companion object {
        fun fromDbValue(value: String?): TradeSource? = when (value) {
            LOCAL_ESTIMATE.name -> LOCAL_ESTIMATE
            API_FILL.name -> API_FILL
            LEGACY_UNKNOWN.name -> LEGACY_UNKNOWN
            else -> null
        }
    }
}
