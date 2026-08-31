package com.gemini.krakenbot.service.impl.history

/** Reconstruction cannot safely value a tracked asset at a required timeline point. */
class HistoricalPriceUnavailableException(message: String) : IllegalStateException(message)
