package com.gemini.krakenbot.config

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonValue

@JvmInline
value class ApiKey(@get:JsonValue val value: String) {
    override fun toString(): String = "***REDACTED***"
}

@JvmInline
value class PrivateKey(@get:JsonValue val value: String) {
    override fun toString(): String = "***REDACTED***"
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

