package com.gemini.krakenbot.config

import kotlin.jvm.JvmInline

private const val _REDACTED_ = "***REDACTED***"

@JvmInline
value class ApiKey(val value: String) {
    override fun toString(): String = _REDACTED_
}

@JvmInline
value class PrivateKey(val value: String) {
    override fun toString(): String = _REDACTED_
}

data class KrakenCredentials(
    val apiKey: ApiKey,
    val privateKey: PrivateKey
) {
    fun hasValidCredentials(): Boolean =
        apiKey.value.isNotBlank() && apiKey.value != PLACEHOLDER_API_KEY

    companion object {
        const val PLACEHOLDER_API_KEY = "YOUR_KRAKEN_API_KEY"

        operator fun invoke(
            apiKey: String,
            privateKey: String
        ): KrakenCredentials =
            KrakenCredentials(ApiKey(apiKey), PrivateKey(privateKey))
    }
}
