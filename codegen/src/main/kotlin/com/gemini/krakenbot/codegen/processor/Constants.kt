package com.gemini.krakenbot.codegen.processor

import java.math.BigDecimal
import java.time.Instant

internal const val FILE_NAME_ARGUMENT = "fileName"

internal const val CSS_THEME_FILE_NAME = "CssTheme"
internal const val CSS_THEME_VARS_FILE_NAME = "CssThemeVars"
internal const val CHART_PROPS_FILE_NAME = "ChartProps"
internal const val ASSET_IMPORT = "import com.gemini.krakenbot.model.Asset"

internal const val CSS_VARIABLE_PREFIX = "--"
internal const val CSS_RADIUS_PREFIX = "radius"
internal const val CSS_COLOR_PREFIX = "color"

internal const val PIXEL_SUFFIX = "px"
internal const val REM_SUFFIX = "rem"

internal const val CSS_THEME_VARS_IMPORT = "import com.gemini.krakenbot.view.util.CssThemeVars"
internal const val COLOR_IMPORT = "import kotlinx.css.Color"
internal const val CSS_BUILDER_IMPORT = "import kotlinx.css.CssBuilder"
internal const val PX_IMPORT = "import kotlinx.css.px"
internal const val REM_IMPORT = "import kotlinx.css.rem"

internal const val CSS_CLASSES_FILE_NAME = "CssClasses"
internal const val CSS_CLASS_NAME = "CssClass"
internal const val COMPOSITE_CLASS_NAME = "Composite"

internal val LOWER_TO_UPPER_BOUNDARY_REGEX = Regex("([a-z])([A-Z])")
internal val LETTER_TO_DIGIT_BOUNDARY_REGEX = Regex("([a-zA-Z])(\\d)")

internal const val PLACEHOLDER_FORMAT = "$1-$2"

internal const val ASSET_NAME = "com.gemini.krakenbot.model.Asset"

internal const val TO_STRING_DECLARATION = "override fun toString(): String = value"
internal const val QUERY_SELECTOR_DECLARATION = "val querySelector: String"
internal const val QUERY_SELECTOR_GETTER =
    $$"""    get() = value.split(" ").filter { it.isNotBlank() }.joinToString("") { ".$it" }"""
internal const val CSS_CLASS_PLUS_OPERATOR =
    $$"operator fun plus(other: CssClass): CssClass = Composite(\"$value ${other.value}\".trim())"
internal val KOTLIN_IDENTIFIER_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")

internal val BIG_DECIMAL_NAME = BigDecimal::class.qualifiedName!!
internal val INSTANT_NAME = Instant::class.qualifiedName!!
internal val STRING_NAME = String::class.qualifiedName!!
internal val LIST_NAME = List::class.qualifiedName!!
internal val MAP_NAME = Map::class.qualifiedName!!
