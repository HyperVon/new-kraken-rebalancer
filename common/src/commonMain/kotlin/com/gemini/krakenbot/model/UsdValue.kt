package com.gemini.krakenbot.model

import kotlin.jvm.JvmInline

/**
 * Type-safe value class wrapper for fiat USD currency amounts and portfolio valuations.
 */
@JvmInline
value class UsdValue(val value: Double) {
    override fun toString(): String = "$$value"

    companion object {
        val ZERO = UsdValue(0.0)
    }
}
