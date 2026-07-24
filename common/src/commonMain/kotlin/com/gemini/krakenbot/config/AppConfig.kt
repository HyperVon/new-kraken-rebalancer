package com.gemini.krakenbot.config

data class AppConfig(val kraken: KrakenCredentials, val settings: Settings, val allocations: List<Allocation>)
