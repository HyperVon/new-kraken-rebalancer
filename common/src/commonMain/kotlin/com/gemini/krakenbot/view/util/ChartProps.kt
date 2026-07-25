package com.gemini.krakenbot.view.util

import com.gemini.krakenbot.model.Asset

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
    const val BEGIN_AT_ZERO = "beginAtZero"
    const val USE_POINT_STYLE = "usePointStyle"
    const val POINT_STYLE = "pointStyle"
    const val POINT_STYLE_WIDTH = "pointStyleWidth"

    const val TIME_TYPE = "time"
    const val TIME_FORMAT_DEFAULT = "MMM d, yyyy HH:mm"

    // Default Layout & Dimension Constants
    const val FONT_SIZE_LEGEND = 12
    const val BORDER_WIDTH_TOOLTIP = 1
    const val PADDING_TOOLTIP = 12
    const val CORNER_RADIUS_TOOLTIP = 8
    const val MAX_TICKS_LIMIT_DEFAULT = 8
    const val LEGEND_POINT_STYLE_LINE = "line"
    const val LEGEND_POINT_STYLE_WIDTH = 24

    const val TENSION_CURVED = 0.3
    const val BORDER_WIDTH_PRIMARY = 2.0
    const val BORDER_WIDTH_SECONDARY = 1.5
    const val POINT_RADIUS_PRIMARY = 4
    const val POINT_RADIUS_SECONDARY = 3
    const val POINT_HOVER_RADIUS_PRIMARY = 6
    const val POINT_HOVER_RADIUS_SECONDARY = 5
    const val POINT_HIT_RADIUS_DEFAULT = 10
    const val POINT_RADIUS_HIDDEN = 0

    /** Density thresholds: full radius ≤ FULL_MAX; half until HALF_MAX; then hidden. */
    const val POINT_DENSITY_FULL_MAX = 24
    const val POINT_DENSITY_HALF_MAX = 48

    // chartjs-plugin-zoom option keys
    const val ZOOM = "zoom"
    const val PAN = "pan"
    const val WHEEL = "wheel"
    const val PINCH = "pinch"
    const val DRAG = "drag"
    const val ENABLED = "enabled"
    const val MODE = "mode"
    const val MODE_X = "x"
    const val LIMITS = "limits"
    const val MIN_RANGE = "minRange"
    const val MIN = "min"
    const val MAX = "max"
    const val ORIGINAL = "original"
    const val ON_ZOOM_COMPLETE = "onZoomComplete"

    /** Chart.js animation mode for instant programmatic zoom/pan (no tween). */
    const val TRANSITION_NONE = "none"

    /** Minimum visible x-span after zoom (ms) — prevents collapsing the time axis. */
    const val ZOOM_MIN_RANGE_MS = 3_600_000
    const val ZOOM_FACTOR_IN = 1.2
    const val ZOOM_FACTOR_OUT = 0.8

    /**
     * Sentinel label in visibility maps: when present, datasets whose labels are
     * absent from the map inherit this default (used by “Total only” presets).
     */
    const val DATASET_VISIBILITY_DEFAULT = "*"

    // Fonts & Theme colors
    const val FONT_INTER = "'Inter', sans-serif"
    const val FONT_MONO = "'Roboto Mono', monospace"
    const val COLOR_LEGEND_LABEL = "#94a3b8"
    const val COLOR_TOOLTIP_BG = "rgba(15, 23, 42, 0.9)"
    const val COLOR_TOOLTIP_BORDER = "rgba(255, 255, 255, 0.1)"
    const val COLOR_TOOLTIP_TITLE = "#f8fafc"
    const val COLOR_TOOLTIP_BODY = "#cbd5e1"
    const val COLOR_GRID_LINE = "rgba(51, 65, 85, 0.3)"
    const val COLOR_ZERO_LINE = "rgba(148, 163, 184, 0.65)"
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
    const val COLOR_SLATE = "rgba(148, 163, 184, 1)"

    const val COLOR_BLUE_BG = "rgba(96, 165, 250, 0.08)"
    const val COLOR_GREEN_BG = "rgba(52, 211, 153, 0.08)"

    const val COLOR_BLUE_BG_PALETTE = "rgba(96, 165, 250, 0.1)"
    const val COLOR_EMERALD_BG_PALETTE = "rgba(52, 211, 153, 0.1)"
    const val COLOR_AMBER_BG_PALETTE = "rgba(251, 191, 36, 0.1)"
    const val COLOR_VIOLET_BG_PALETTE = "rgba(167, 139, 250, 0.1)"
    const val COLOR_RED_BG_PALETTE = "rgba(248, 113, 113, 0.1)"
    const val COLOR_TEAL_BG_PALETTE = "rgba(45, 212, 191, 0.1)"
    const val COLOR_ORANGE_BG_PALETTE = "rgba(251, 146, 60, 0.1)"
    const val COLOR_FUCHSIA_BG_PALETTE = "rgba(232, 121, 249, 0.1)"
    const val COLOR_SLATE_BG_PALETTE = "rgba(148, 163, 184, 0.12)"

    /** Solid hex colors for SSR allocation bars (match chart palette). */
    const val SOLID_BTC = "#fbbf24"
    const val SOLID_ETH = "#a78bfa"
    const val SOLID_USD = "#94a3b8"
    const val SOLID_FALLBACK = "#60a5fa"

    val PALETTE_BORDER_COLORS = arrayOf(
        COLOR_BLUE,
        COLOR_EMERALD,
        COLOR_AMBER,
        COLOR_VIOLET,
        COLOR_RED,
        COLOR_TEAL,
        COLOR_ORANGE,
        COLOR_FUCHSIA,
    )

    val PALETTE_BG_COLORS = arrayOf(
        COLOR_BLUE_BG_PALETTE,
        COLOR_EMERALD_BG_PALETTE,
        COLOR_AMBER_BG_PALETTE,
        COLOR_VIOLET_BG_PALETTE,
        COLOR_RED_BG_PALETTE,
        COLOR_TEAL_BG_PALETTE,
        COLOR_ORANGE_BG_PALETTE,
        COLOR_FUCHSIA_BG_PALETTE,
    )

    /** Fixed per-asset colors shared by History charts and Dashboard allocation bars. */
    fun borderColorForSymbol(symbol: String, fallbackIndex: Int = 0): String = when (symbol.uppercase()) {
        Asset.BTC -> COLOR_AMBER
        Asset.ETH -> COLOR_VIOLET
        Asset.USD -> COLOR_SLATE
        else -> PALETTE_BORDER_COLORS[fallbackIndex % PALETTE_BORDER_COLORS.size]
    }

    fun backgroundColorForSymbol(symbol: String, fallbackIndex: Int = 0): String = when (symbol.uppercase()) {
        Asset.BTC -> COLOR_AMBER_BG_PALETTE
        Asset.ETH -> COLOR_VIOLET_BG_PALETTE
        Asset.USD -> COLOR_SLATE_BG_PALETTE
        else -> PALETTE_BG_COLORS[fallbackIndex % PALETTE_BG_COLORS.size]
    }

    fun solidColorForSymbol(symbol: String, fallbackIndex: Int = 0): String = when (symbol.uppercase()) {
        Asset.BTC -> SOLID_BTC
        Asset.ETH -> SOLID_ETH
        Asset.USD -> SOLID_USD
        else -> SOLID_FALLBACK_PALETTE[fallbackIndex % SOLID_FALLBACK_PALETTE.size]
    }

    private val SOLID_FALLBACK_PALETTE =
        arrayOf(
            SOLID_FALLBACK,
            "#34d399",
            SOLID_BTC,
            SOLID_ETH,
            "#f87171",
            "#2dd4bf",
            "#fb923c",
            "#e879f9",
        )
}
