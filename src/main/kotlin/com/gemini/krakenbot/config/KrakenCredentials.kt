package com.gemini.krakenbot.config

import com.fasterxml.jackson.annotation.JsonValue

@JvmInline
value class ApiKey(@get:JsonValue val value: String) {
    override fun toString(): String = value
}

@JvmInline
value class PrivateKey(@get:JsonValue val value: String) {
    override fun toString(): String = value
}

data class KrakenCredentials(
    val apiKey: ApiKey,
    val privateKey: PrivateKey
) {
    companion object {
        operator fun invoke(apiKey: String, privateKey: String): KrakenCredentials =
            KrakenCredentials(ApiKey(apiKey), PrivateKey(privateKey))
    }
}

