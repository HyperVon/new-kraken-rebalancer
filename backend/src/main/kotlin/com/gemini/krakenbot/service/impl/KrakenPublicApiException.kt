package com.gemini.krakenbot.service.impl

/** A Kraken public API error response whose error codes were returned as a string array. */
internal class KrakenPublicApiException(val endpoint: String, val errors: List<String>, message: String) :
    RuntimeException(message)
