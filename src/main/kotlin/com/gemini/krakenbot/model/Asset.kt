package com.gemini.krakenbot.model

import com.fasterxml.jackson.annotation.JsonValue

@JvmInline
value class Asset(@get:JsonValue val value: String) {
    override fun toString(): String = value
}
