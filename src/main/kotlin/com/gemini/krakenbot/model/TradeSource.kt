package com.gemini.krakenbot.model

/** Provenance of trade economics — local estimate at order time vs settled Kraken fill. */
enum class TradeSource {
    LOCAL_ESTIMATE,
    API_FILL,
    ;

    companion object {
        fun fromDbValue(value: String?): TradeSource? = when (value) {
            LOCAL_ESTIMATE.name -> LOCAL_ESTIMATE
            API_FILL.name -> API_FILL
            else -> null
        }
    }
}
