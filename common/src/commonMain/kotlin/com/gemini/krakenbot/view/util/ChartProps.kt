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
    const val RESPONSIVE = "responsive"
    const val MAINTAIN_ASPECT_RATIO = "maintainAspectRatio"
    const val PLUGINS = "plugins"
    const val LEGEND = "legend"
    const val LABELS = "labels"
    const val COLOR = "color"
    const val FONT = "font"
    const val FAMILY = "family"
    const val SIZE = "size"
    const val TOOLTIP = "tooltip"
    const val TITLE_COLOR = "titleColor"
    const val BODY_COLOR = "bodyColor"
    const val BODY_FONT = "bodyFont"
    const val PADDING = "padding"
    const val CORNER_RADIUS = "cornerRadius"
    const val SCALES = "scales"
    const val X = "x"
    const val Y = "y"
    const val TYPE = "type"
    const val TIME = "time"
    const val TOOLTIP_FORMAT = "tooltipFormat"
    const val GRID = "grid"
    const val TICKS = "ticks"
    const val MAX_TICKS_LIMIT = "maxTicksLimit"

    const val TIME_TYPE = "time"
    const val TIME_FORMAT_DEFAULT = "MMM d, yyyy HH:mm"

    // Fonts & Theme colors
    const val FONT_INTER = "'Inter', sans-serif"
    const val FONT_MONO = "'Roboto Mono', monospace"
    const val COLOR_LEGEND_LABEL = "#94a3b8"
    const val COLOR_TOOLTIP_BG = "rgba(15, 23, 42, 0.9)"
    const val COLOR_TOOLTIP_BORDER = "rgba(255, 255, 255, 0.1)"
    const val COLOR_TOOLTIP_TITLE = "#f8fafc"
    const val COLOR_TOOLTIP_BODY = "#cbd5e1"
    const val COLOR_GRID_LINE = "rgba(51, 65, 85, 0.3)"
    const val COLOR_TICK = "#64748b"

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
