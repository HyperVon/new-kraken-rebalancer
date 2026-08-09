package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
import kotlinx.css.Align
import kotlinx.css.CssBuilder
import kotlinx.css.Cursor
import kotlinx.css.Display
import kotlinx.css.FontWeight
import kotlinx.css.GridTemplateColumns
import kotlinx.css.Padding
import kotlinx.css.Position
import kotlinx.css.alignItems
import kotlinx.css.background
import kotlinx.css.borderColor
import kotlinx.css.borderRadius
import kotlinx.css.color
import kotlinx.css.cursor
import kotlinx.css.display
import kotlinx.css.fontFamily
import kotlinx.css.fontSize
import kotlinx.css.fontWeight
import kotlinx.css.gap
import kotlinx.css.gridTemplateColumns
import kotlinx.css.height
import kotlinx.css.marginBottom
import kotlinx.css.marginTop
import kotlinx.css.opacity
import kotlinx.css.padding
import kotlinx.css.pct
import kotlinx.css.position
import kotlinx.css.px
import kotlinx.css.rem
import kotlinx.css.width

object HistoryChartStyles {
    fun CssBuilder.applyHistoryChartStyles() {
        // HIST-2: single ~44px chart header row (title + compact zoom; caveats go in caption).
        ".${CssClass.History.ChartHeader.value}" {
            display = Display.grid
            gridTemplateColumns = GridTemplateColumns("minmax(0, 1fr) auto")
            alignItems = Align.center
            gap = 0.75.rem
            minHeightRaw("2.25rem")
            marginBottom = 0.5.rem
        }

        ".${CssClass.History.ChartHeaderTitle.value}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.5.rem
            marginBottom = 0.px
        }

        ".${CssClass.History.ChartTools}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.25.rem
            justifyContentRaw("flex-end")
            flexWrapRaw("nowrap")
            whiteSpaceRaw("nowrap")
        }

        ".${CssClass.History.ZoomBtn}" {
            appearanceRaw("none")
            webkitAppearanceRaw("none")
            padding = Padding(0.25.rem, 0.5.rem)
            borderRadius = CssTheme.radiusSm
            fontSize = 0.75.rem
            fontWeight = FontWeight.w600
            color = CssTheme.colorTextSecondary
            background = CssTheme.colorGlass.value
            solidBorder(CssTheme.colorGlassBorder)
            cursor = Cursor.pointer
            transitionRaw("all 0.2s ease")
            fontFamily = CssTheme.fontSans
        }

        ".${CssClass.History.ZoomBtn}:hover" {
            color = CssTheme.colorTextPrimary
            background = CssTheme.colorGlassHover.value
            borderColor = CssTheme.colorBlueGlassBorder
        }

        ".${CssClass.History.ChartContainer}" {
            position = Position.relative
            // HIST-2: reclaim vertical space from the collapsed 3-row header (+~64px plot).
            height = 24.rem
            marginTop = 0.px
        }

        ".${CssClass.History.ChartCaption.value}" {
            fontSize = 0.75.rem
            color = CssTheme.colorTextMuted
            lineHeightRaw("1.4")
            marginTop = 0.5.rem
        }

        ".${CssClass.History.ChartScrubber}" {
            padding = Padding(0.75.rem, 0.5.rem, 0.125.rem)
        }

        ".${CssClass.History.ChartScrubber.value}:has(.${CssClass.History.ChartScrubberInput.value}:disabled)" {
            display = Display.none
        }

        ".${CssClass.History.ChartScrubberInput}" {
            appearanceRaw("none")
            webkitAppearanceRaw("none")
            width = 100.pct
            height = 0.35.rem
            borderRadius = CssTheme.radiusPill
            background = CssTheme.colorBorderMuted.value
            cursor = Cursor.ewResize
            accentColorRaw(CssTheme.colorBlueAccent.value)
        }

        ".${CssClass.History.ChartScrubberInput}:disabled" {
            opacity = 0.3
            cursor = Cursor.default
        }

        ".${CssClass.History.ChartScrubberInput}::-webkit-slider-thumb" {
            appearanceRaw("none")
            webkitAppearanceRaw("none")
            width = 1.rem
            height = 1.rem
            borderRadius = 50.pct
            background = CssTheme.colorBlueAccent.value
            solidBorder(CssTheme.colorBg, 2.px)
            cursor = Cursor.grab
        }

        ".${CssClass.History.ChartScrubberInput}::-moz-range-thumb" {
            width = 1.rem
            height = 1.rem
            borderRadius = 50.pct
            background = CssTheme.colorBlueAccent.value
            solidBorder(CssTheme.colorBg, 2.px)
            cursor = Cursor.grab
        }
    }
}
