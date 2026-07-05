package com.gemini.krakenbot.view.util

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Extension functions for common Kotlin idioms and BigDecimal operations.
 * Promotes a more declarative and functional approach to code.
 */

// BigDecimal operations
fun BigDecimal.setScaleHalf(scale: Int): BigDecimal =
    this.setScale(scale, RoundingMode.HALF_UP)

fun BigDecimal.formatPercentage(): String =
    this.setScaleHalf(2).toPlainString()

fun BigDecimal.formatCurrency(): String =
    this.setScaleHalf(2).toPlainString()

fun BigDecimal.isZero(): Boolean =
    this.compareTo(BigDecimal.ZERO) == 0

fun BigDecimal.isPositive(): Boolean =
    this.signum() > 0

fun BigDecimal.isNegative(): Boolean =
    this.signum() < 0

fun BigDecimal.toPercentageString(decimals: Int = 2): String =
    "${this.setScale(decimals, RoundingMode.HALF_UP)}%"

// Collection operations
fun <K, V> Map<K, V>.getOrZero(key: K, default: V): V =
    this[key] ?: default

fun <K, V> MutableMap<K, V>.getOrPutDefault(key: K, defaultValue: V): V =
    this.getOrPut(key) { defaultValue }

fun <K : Comparable<K>, V> Map<K, V>.sortedByKey(): List<Pair<K, V>> =
    this.entries.sortedBy { it.key }.map { it.key to it.value }

fun <K, V : Comparable<V>> Map<K, V>.sortedByValue(): List<Pair<K, V>> =
    this.entries.sortedBy { it.value }.map { it.key to it.value }

// CSS class builder
fun buildCssClass(vararg classes: String): String =
    classes.filter { it.isNotEmpty() }.joinToString(" ")

fun buildCssClass(baseClass: String, conditional: Boolean, conditionalClass: String): String =
    if (conditional) "$baseClass $conditionalClass" else baseClass
