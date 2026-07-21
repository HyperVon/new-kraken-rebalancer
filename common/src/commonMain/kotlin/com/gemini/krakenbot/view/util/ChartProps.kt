package com.gemini.krakenbot.view.util

/** Centralized Chart.js option keys and chart styling color constants. */
object ChartProps {
    const val LABEL = "label"
    const val DATA = "data"
    const val BORDER_COLOR = "borderColor"
    const val BACKGROUND_COLOR = "backgroundColor"
    const val FILL = "fill"
    const val TENSION = "tension"
    const val BORDER_WIDTH = "borderWidth"
    const val POINT_RADIUS = "pointRadius"
    const val POINT_HOVER_RADIUS = "pointHoverRadius"
    const val POINT_HIT_RADIUS = "pointHitRadius"
    const val TRANSPARENT = "transparent"

    // Color palette
    const val COLOR_BLUE = "rgba(96, 165, 250, 1)"
    const val COLOR_EMERALD = "rgba(52, 211, 153, 1)"
    const val COLOR_AMBER = "rgba(251, 191, 36, 1)"
    const val COLOR_VIOLET = "rgba(167, 139, 250, 1)"
    const val COLOR_RED = "rgba(248, 113, 113, 1)"
    const val COLOR_TEAL = "rgba(45, 212, 191, 1)"
    const val COLOR_ORANGE = "rgba(251, 146, 60, 1)"
    const val COLOR_FUCHSIA = "rgba(232, 121, 249, 1)"

    const val COLOR_BLUE_BORDER = COLOR_BLUE
    const val COLOR_BLUE_BG = "rgba(96, 165, 250, 0.08)"
    const val COLOR_GREEN_BORDER = COLOR_EMERALD
    const val COLOR_GREEN_BG = "rgba(52, 211, 153, 0.08)"

    val PALETTE_BORDER_COLORS = arrayOf(
        COLOR_BLUE,
        COLOR_EMERALD,
        COLOR_AMBER,
        COLOR_VIOLET,
        COLOR_RED,
        COLOR_TEAL,
        COLOR_ORANGE,
        COLOR_FUCHSIA
    )

    val PALETTE_BG_COLORS = arrayOf(
        "rgba(96, 165, 250, 0.1)",
        "rgba(52, 211, 153, 0.1)",
        "rgba(251, 191, 36, 0.1)",
        "rgba(167, 139, 250, 0.1)",
        "rgba(248, 113, 113, 0.1)",
        "rgba(45, 212, 191, 0.1)",
        "rgba(251, 146, 60, 0.1)",
        "rgba(232, 121, 249, 0.1)"
    )
}
