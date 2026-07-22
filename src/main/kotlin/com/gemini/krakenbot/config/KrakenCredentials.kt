package com.gemini.krakenbot.config

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonValue

private const val _REDACTED_ = "***REDACTED***"

@JvmInline
value class ApiKey(@get:JsonValue val value: String) {
    override fun toString(): String = _REDACTED_
}

@JvmInline
value class PrivateKey(@get:JsonValue val value: String) {
    override fun toString(): String = _REDACTED_
}

data class KrakenCredentials(
    val apiKey: ApiKey,
    val privateKey: PrivateKey
) {
    @get:JsonIgnore
    val isConfigured: Boolean
        get() = apiKey.value.isNotBlank() && apiKey.value != PLACEHOLDER_API_KEY

    companion object {
        const val PLACEHOLDER_API_KEY = "YOUR_KRAKEN_API_KEY"

        operator fun invoke(
            apiKey: String,
            privateKey: String
        ): KrakenCredentials =
            KrakenCredentials(ApiKey(apiKey), PrivateKey(privateKey))
    }
}
