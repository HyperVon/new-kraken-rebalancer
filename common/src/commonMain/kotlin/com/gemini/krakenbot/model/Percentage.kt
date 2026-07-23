package com.gemini.krakenbot.model

import kotlin.jvm.JvmInline

/**
 * Type-safe value class wrapper for percentage values (e.g., allocation targets, deviations, drawdowns).
 */
@JvmInline
value class Percentage(val value: Double) {
    override fun toString(): String = "$value%"

    companion object {
        val ZERO = Percentage(0.0)
        val HUNDRED = Percentage(100.0)
    }
}
