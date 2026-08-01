package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
import kotlinx.css.Align
import kotlinx.css.Color
import kotlinx.css.CssBuilder
import kotlinx.css.Cursor
import kotlinx.css.Display
import kotlinx.css.FlexDirection
import kotlinx.css.FontWeight
import kotlinx.css.GridTemplateColumns
import kotlinx.css.Overflow
import kotlinx.css.Padding
import kotlinx.css.Position
import kotlinx.css.QuotedString
import kotlinx.css.TextAlign
import kotlinx.css.WhiteSpace
import kotlinx.css.alignItems
import kotlinx.css.background
import kotlinx.css.backgroundColor
import kotlinx.css.borderColor
import kotlinx.css.borderRadius
import kotlinx.css.color
import kotlinx.css.content
import kotlinx.css.cursor
import kotlinx.css.display
import kotlinx.css.flexDirection
import kotlinx.css.fontFamily
import kotlinx.css.fontSize
import kotlinx.css.fontWeight
import kotlinx.css.gap
import kotlinx.css.gridTemplateColumns
import kotlinx.css.height
import kotlinx.css.marginBottom
import kotlinx.css.marginRight
import kotlinx.css.marginTop
import kotlinx.css.opacity
import kotlinx.css.padding
import kotlinx.css.paddingLeft
import kotlinx.css.pct
import kotlinx.css.position
import kotlinx.css.properties.TextDecoration
import kotlinx.css.px
import kotlinx.css.rem
import kotlinx.css.textAlign
import kotlinx.css.textDecoration
import kotlinx.css.whiteSpace
import kotlinx.css.width

object NavigationStyles {
    fun CssBuilder.applyNavigationStyles() {
        ".${CssClass.Navigation.Bar}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.25.rem
        }

        ".${CssClass.Navigation.Link}" {
            display = Display.inlineFlex
            alignItems = Align.center
            gap = 0.375.rem
            padding = Padding(0.375.rem, 0.875.rem)
            borderRadius = 0.5.rem
            fontSize = 0.875.rem
            fontWeight = FontWeight.w500
            color = CssTheme.colorTextSecondary
            textDecoration = TextDecoration.none
            put("transition", "all 0.2s ease")
            solidBorder(Color.transparent)
        }

        ".${CssClass.Navigation.Link}:hover" {
            color = CssTheme.colorTextPrimary
            background = CssTheme.colorGlassHover.value
            borderColor = CssTheme.colorWhiteFaint
        }

        // Parens are load-bearing: without them `"A" + "B" { }` parses as
        // `"A" + ("B".invoke(...))` and every selector except the last is dropped.
        (
            ".${CssClass.Navigation.Link}:focus-visible, .${CssClass.History.TimeRangeBtn}:focus-visible, " +
                ".${CssClass.History.ViewsBtn}:focus-visible, .${CssClass.History.ZoomBtn}:focus-visible"
            ) {
            put("outline", "none")
            borderColor = CssTheme.colorBluePrimary
            put("box-shadow", CssTheme.focusRingStrong)
        }

        CssClass.Navigation.LinkActive.querySelector {
            color = CssTheme.colorTextPrimary
            background = CssTheme.colorBlueGlassBgHover.value
            borderColor = CssTheme.colorBlueGlassBorderHover
            fontWeight = FontWeight.w600
            put(
                "box-shadow",
                "0 0 18px rgba(59, 130, 246, 0.35), inset 0 1px 0 rgba(255,255,255,0.12)",
            )
        }

        ".${CssClass.History.ToolbarRow}" {
            display = Display.flex
            alignItems = Align.center
            put("flex-wrap", "wrap")
            gap = 0.75.rem
            marginBottom = 1.25.rem
        }

        ".${CssClass.History.TimeRangeSelector}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.375.rem
            marginBottom = 0.px
            padding = Padding(0.25.rem)
            background = CssTheme.colorGlass.value
            solidBorder(CssTheme.colorGlassBorder)
            borderRadius = 0.75.rem
            put("width", "fit-content")
        }

        ".${CssClass.History.TimeRangeBtn}" {
            padding = Padding(0.375.rem, 1.rem)
            borderRadius = 0.5.rem
            fontSize = 0.8125.rem
            fontWeight = FontWeight.w600
            color = CssTheme.colorTextSecondary
            background = "transparent"
            solidBorder(Color.transparent)
            cursor = Cursor.pointer
            put("transition", "all 0.2s ease")
            fontFamily = CssTheme.fontSans
        }

        ".${CssClass.History.TimeRangeBtn}:hover" {
            color = CssTheme.colorTextPrimary
            background = CssTheme.colorGlassHover.value
        }

        CssClass.History.TimeRangeBtnActive.querySelector {
            color = CssTheme.colorTextPrimary
            background = CssTheme.colorBlueGlassBgHover.value
            borderColor = CssTheme.colorBlueGlassBorderHover
            put(
                "box-shadow",
                "0 0 14px rgba(59, 130, 246, 0.3), inset 0 1px 0 rgba(255,255,255,0.1)",
            )
        }

        // Parens are load-bearing here as well (see the rule above): without
        // them only the last concatenated selector would emit.
        (
            "${CssClass.Navigation.LinkActive.querySelector}:focus-visible, " +
                "${CssClass.History.TimeRangeBtnActive.querySelector}:focus-visible"
            ) {
            // Transparent outline stays invisible normally but is system-painted in
            // forced-colors mode, where the box-shadow ring is not rendered.
            put("outline", "3px solid transparent")
            borderColor = CssTheme.colorBluePrimary
            put("box-shadow", CssTheme.focusRingStrong)
        }

        ".${CssClass.History.ViewsToolbar}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.5.rem
            padding = Padding(0.25.rem, 0.5.rem)
            background = CssTheme.colorGlass.value
            solidBorder(CssTheme.colorGlassBorder)
            borderRadius = 0.75.rem
            put("flex-wrap", "wrap")
        }

        ".${CssClass.History.ViewsLabel}" {
            fontSize = 0.8125.rem
            fontWeight = FontWeight.w600
            color = CssTheme.colorTextSecondary
            fontFamily = CssTheme.fontSans
            paddingLeft = 0.375.rem
        }

        // HIST-1: fully themed select (no native chrome) with SVG chevron.
        ".${CssClass.History.ViewsSelect}" {
            put("appearance", "none")
            put("-webkit-appearance", "none")
            put("-moz-appearance", "none")
            background = CssTheme.colorSurface2.value
            color = CssTheme.colorTextPrimary
            solidBorder(CssTheme.colorSurface2Border)
            borderRadius = 0.5.rem
            padding = Padding(0.375.rem, 2.rem, 0.375.rem, 0.75.rem)
            fontSize = 0.8125.rem
            fontFamily = CssTheme.fontSans
            fontWeight = FontWeight.w600
            cursor = Cursor.pointer
            put("min-width", "11rem")
            put(
                "background-image",
                "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' " +
                    "viewBox='0 0 24 24' fill='none' stroke='%23a8b4c8' stroke-width='2' stroke-linecap='round' " +
                    "stroke-linejoin='round'%3E%3Cpolyline points='6 9 12 15 18 9'/%3E%3C/svg%3E\")",
            )
            put("background-repeat", "no-repeat")
            put("background-position", "right 0.625rem center")
        }

        ".${CssClass.History.ViewsSelect}:hover" {
            borderColor = CssTheme.colorGlassBorderHover
        }

        ".${CssClass.History.ViewsSelect}:focus" {
            put("outline", "none")
            borderColor = CssTheme.colorBluePrimary
            put("box-shadow", CssTheme.focusRingSubtle)
        }

        ".${CssClass.History.ViewsSelect} option" {
            backgroundColor = CssTheme.colorSurface1
            color = CssTheme.colorTextPrimary
        }

        ".${CssClass.History.ViewsActions}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.25.rem
        }

        ".${CssClass.History.ViewsBtn}" {
            put("appearance", "none")
            put("-webkit-appearance", "none")
            padding = Padding(0.375.rem, 0.625.rem)
            borderRadius = 0.5.rem
            fontSize = 0.75.rem
            fontWeight = FontWeight.w600
            color = CssTheme.colorTextSecondary
            background = "transparent"
            solidBorder(Color.transparent)
            cursor = Cursor.pointer
            put("transition", "all 0.2s ease")
            fontFamily = CssTheme.fontSans
        }

        ".${CssClass.History.ViewsBtn}:hover" {
            color = CssTheme.colorTextPrimary
            background = CssTheme.colorGlassHover.value
        }

        ".${CssClass.History.ViewsBtn}:disabled" {
            opacity = 0.4
            cursor = Cursor.notAllowed
        }

        // HIST-2: single ~44px chart header row (title + compact zoom; caveats go in caption).
        ".${CssClass.History.ChartHeader.value}" {
            display = Display.flex
            alignItems = Align.center
            put("justify-content", "space-between")
            gap = 0.75.rem
            put("flex-wrap", "wrap")
            put("min-height", "2.25rem")
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
            put("justify-content", "flex-end")
        }

        ".${CssClass.History.ZoomBtn}" {
            put("appearance", "none")
            put("-webkit-appearance", "none")
            padding = Padding(0.25.rem, 0.5.rem)
            borderRadius = 0.375.rem
            fontSize = 0.75.rem
            fontWeight = FontWeight.w600
            color = CssTheme.colorTextSecondary
            background = CssTheme.colorGlass.value
            solidBorder(CssTheme.colorGlassBorder)
            cursor = Cursor.pointer
            put("transition", "all 0.2s ease")
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
            put("line-height", "1.4")
            marginTop = 0.5.rem
        }

        ".${CssClass.History.ChartScrubber}" {
            padding = Padding(0.75.rem, 0.5.rem, 0.125.rem)
        }

        ".${CssClass.History.ChartScrubberInput}" {
            put("appearance", "none")
            put("-webkit-appearance", "none")
            width = 100.pct
            height = 0.35.rem
            borderRadius = CssTheme.radiusPill
            background = CssTheme.colorBorderMuted.value
            cursor = Cursor.ewResize
            put("accent-color", CssTheme.colorBlueAccent.value)
        }

        ".${CssClass.History.ChartScrubberInput}:disabled" {
            opacity = 0.3
            cursor = Cursor.default
        }

        ".${CssClass.History.ChartScrubberInput}::-webkit-slider-thumb" {
            put("appearance", "none")
            put("-webkit-appearance", "none")
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

        ".${CssClass.History.StatsGrid}" {
            display = Display.grid
            gridTemplateColumns = GridTemplateColumns("1fr")
            gap = 0.75.rem
            marginBottom = 1.rem
            alignItems = Align.start
        }

        ".${CssClass.History.TradeLogHeader}" {
            display = Display.flex
            put("justify-content", "space-between")
            alignItems = Align.center
            marginBottom = 1.rem
        }

        ".${CssClass.History.TitleNoMargin}" {
            marginBottom = 0.px
        }

        ".${CssClass.History.MutedSmallText}" {
            fontSize = 0.875.rem
            color = CssTheme.colorTextMuted
        }

        ".${CssClass.History.EmptyTableCell}" {
            textAlign = TextAlign.center
            color = CssTheme.colorTextMuted
            padding = Padding(2.rem)
        }

        ".${CssClass.History.SyncBanner}" {
            display = Display.none
            marginBottom = 1.5.rem
            padding = Padding(1.5.rem)
        }

        ".${CssClass.History.SyncHeader}" {
            display = Display.flex
            alignItems = Align.center
            put("justify-content", "space-between")
            marginBottom = 0.75.rem
        }

        ".${CssClass.History.SyncTitle}" {
            fontWeight = FontWeight.w600
            color = CssTheme.colorTextPrimary
            display = Display.flex
            alignItems = Align.center
            gap = 0.5.rem
        }

        ".${CssClass.History.SyncSpinner}" {
            width = 1.rem
            height = 1.rem
            solidBorder(CssTheme.colorKrakenBlue, 2.px)
            put("border-top-color", "transparent")
            borderRadius = 50.pct
            put("animation", "spin 1s linear infinite")
        }

        ".${CssClass.History.SyncText}" {
            fontFamily = CssTheme.fontMono
            fontSize = 0.875.rem
            color = CssTheme.colorTextMuted
        }

        ".${CssClass.History.ProgressTrack}" {
            width = 100.pct
            height = 0.5.rem
            background = CssTheme.colorWhiteMuted.value
            borderRadius = CssTheme.radiusPill
            put("overflow", "hidden")
        }

        ".${CssClass.History.ProgressBar}" {
            width = 0.pct
            height = 100.pct
            background = CssTheme.colorKrakenBlue.value
            put("transition", "width 0.3s ease")
            borderRadius = CssTheme.radiusPill
        }

        ".${CssClass.Performance.DevContainer}" {
            display = Display.flex
            flexDirection = FlexDirection.column
            put("line-height", "1.1")
        }

        ".${CssClass.Performance.DevUsdLabel}" {
            fontSize = 0.675.rem
            opacity = 0.7
            fontFamily = CssTheme.fontMono
        }

        ".${CssClass.Performance.DevLegend}" {
            display = Display.flex
            alignItems = Align.center
            gap = 1.rem
            marginBottom = 0.75.rem
            fontSize = 0.75.rem
            color = CssTheme.colorTextMuted
        }

        ".${CssClass.Performance.DevLegendItem}" {
            display = Display.inlineFlex
            alignItems = Align.center
            gap = 0.35.rem
            fontWeight = FontWeight.w600
        }

        ".${CssClass.Performance.DevLegendItem}:first-child" {
            marginRight = 1.rem
        }

        ".${CssClass.Performance.DevLegendItem}::before" {
            content = QuotedString("")
            width = 0.5.rem
            height = 0.5.rem
            borderRadius = 50.pct
            display = Display.inlineBlock
        }

        ".${CssClass.Performance.DevLegendOver}" {
            color = CssTheme.colorWarning
        }

        ".${CssClass.Performance.DevLegendOver}::before" {
            backgroundColor = CssTheme.colorWarning
        }

        ".${CssClass.Performance.DevLegendUnder}" {
            color = CssTheme.colorBlueAccent
        }

        ".${CssClass.Performance.DevLegendUnder}::before" {
            backgroundColor = CssTheme.colorBlueAccent
        }

        ".${CssClass.Utility.ErrorBanner}" {
            backgroundColor = CssTheme.colorDangerMuted
            solidBorder(CssTheme.colorDangerBorder)
            color = CssTheme.colorDangerLight
            padding = Padding(1.rem)
            borderRadius = 0.5.rem
            marginBottom = 1.5.rem
            fontWeight = FontWeight.w500
        }

        "@media (prefers-reduced-motion: reduce)" {
            "*, *::before, *::after" {
                put("animation-duration", "0.01ms !important")
                put("animation-iteration-count", "1 !important")
                put("scroll-behavior", "auto !important")
                put("transition-duration", "0.01ms !important")
            }
        }

        ".${CssClass.History.ComparisonHeader}" {
            display = Display.flex
            alignItems = Align.center
            put("justify-content", "flex-start")
            gap = 0.75.rem
            put("flex-wrap", "wrap")
        }

        ".${CssClass.History.ComparisonDelta}" {
            fontSize = 0.8125.rem
            fontWeight = FontWeight.w700
            fontFamily = CssTheme.fontMono
            padding = Padding(0.25.rem, 0.625.rem)
            borderRadius = 0.375.rem
            background = CssTheme.colorGlass.value
            solidBorder(CssTheme.colorGlassBorder)
            whiteSpace = WhiteSpace.nowrap
        }

        ".${CssClass.History.ComparisonDelta}.${CssClass.Utility.Positive}" {
            color = CssTheme.colorSuccess
            borderColor = CssTheme.colorSuccessBorder
        }

        ".${CssClass.History.ComparisonDelta}.${CssClass.Utility.Negative}" {
            color = CssTheme.colorDanger
            borderColor = CssTheme.colorDangerBorder
        }

        ".${CssClass.History.ComparisonDelta}.${CssClass.Utility.Neutral}" {
            color = CssTheme.colorTextMuted
        }

        ".${CssClass.History.ComparisonUnavailable}" {
            display = Display.none
            padding = Padding(1.5.rem)
            textAlign = TextAlign.center
            color = CssTheme.colorTextMuted
            fontSize = 0.875.rem
        }

        ".${CssClass.History.ComparisonUnavailable}.${CssClass.Utility.Visible}" {
            display = Display.block
        }

        ".${CssClass.History.ComparisonChartArea}" {
            display = Display.block
        }

        ".${CssClass.History.ComparisonConfidenceBadge}" {
            display = Display.none
            fontSize = 0.75.rem
            color = CssTheme.colorWarning
            padding = Padding(0.25.rem, 0.75.rem)
        }

        ".${CssClass.History.ComparisonConfidenceBadge}.${CssClass.Utility.Visible}" {
            display = Display.inline
        }

        ".${CssClass.History.ComparisonChartArea}.${CssClass.Utility.Hidden}" {
            display = Display.none
        }
    }
}
