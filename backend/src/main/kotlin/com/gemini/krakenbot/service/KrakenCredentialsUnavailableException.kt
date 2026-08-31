package com.gemini.krakenbot.service

/** A private Kraken query was requested while credentials were not configured. */
class KrakenCredentialsUnavailableException(message: String) : IllegalStateException(message)
