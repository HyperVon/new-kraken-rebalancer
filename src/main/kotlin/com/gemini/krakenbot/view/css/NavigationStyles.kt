package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
import kotlinx.css.*
import kotlinx.css.properties.*

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
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = Color.transparent
        }

        ".${CssClass.Navigation.Link}:hover" {
            color = CssTheme.colorTextPrimary
            background = CssTheme.colorGlassHover.value
            borderColor = CssTheme.colorWhiteFaint
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
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = CssTheme.colorGlassBorder
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
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = Color.transparent
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

        ".${CssClass.History.ViewsToolbar}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.5.rem
            padding = Padding(0.25.rem, 0.5.rem)
            background = CssTheme.colorGlass.value
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = CssTheme.colorGlassBorder
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
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = CssTheme.colorSurface2Border
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
            put("box-shadow", "0 0 0 3px rgba(59, 130, 246, 0.2)")
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
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = Color.transparent
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

        // HIST-2: single ~44px chart header row (title · inline legend · compact zoom).
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
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = CssTheme.colorGlassBorder
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
            borderWidth = 2.px
            borderStyle = BorderStyle.solid
            borderColor = CssTheme.colorBg
            cursor = Cursor.grab
        }

        ".${CssClass.History.ChartScrubberInput}::-moz-range-thumb" {
            width = 1.rem
            height = 1.rem
            borderRadius = 50.pct
            background = CssTheme.colorBlueAccent.value
            borderWidth = 2.px
            borderStyle = BorderStyle.solid
            borderColor = CssTheme.colorBg
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
            borderWidth = 2.px
            borderStyle = BorderStyle.solid
            borderColor = CssTheme.colorKrakenBlue
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

        // Recent Activity & Deviations
        ".${CssClass.Activity.EmptyText}" {
            color = CssTheme.colorTextMuted
            fontStyle = FontStyle.italic
            display = Display.flex
            alignItems = Align.center
            gap = 0.5.rem
        }

        ".${CssClass.Activity.DotMarker}" {
            width = 0.375.rem
            height = 0.375.rem
            borderRadius = 50.pct
            backgroundColor = CssTheme.colorTextMuted
        }

        ".${CssClass.Activity.RowContainer}" {
            display = Display.flex
            alignItems = Align.flexStart
            gap = 0.75.rem
        }

        "tr.${CssClass.Activity.RowTrade.value} td" {
            paddingTop = 0.85.rem
            paddingBottom = 0.85.rem
        }

        "tr.${CssClass.Activity.RowInfo.value} td" {
            paddingTop = 0.55.rem
            paddingBottom = 0.55.rem
            opacity = 0.85
        }

        ".${CssClass.Activity.Message}" {
            fontWeight = FontWeight.w600
            color = CssTheme.colorTextPrimary
            put("line-height", "1.35")
        }

        ".${CssClass.Activity.MessageMuted}" {
            fontWeight = FontWeight.w400
            color = CssTheme.colorTextSecondary
            fontSize = 0.8125.rem
            put("line-height", "1.35")
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
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = CssTheme.colorDangerBorder
            color = CssTheme.colorDangerLight
            padding = Padding(1.rem)
            borderRadius = 0.5.rem
            marginBottom = 1.5.rem
            fontWeight = FontWeight.w500
        }

        ".toast" {
            position = Position.fixed
            bottom = 2.rem
            right = 2.rem
            padding = Padding(1.rem, 1.5.rem)
            borderRadius = 0.5.rem
            color = Color.white
            fontWeight = FontWeight.w500
            display = Display.flex
            alignItems = Align.center
            gap = 0.75.rem
            put("box-shadow", "0 10px 15px -3px rgba(0, 0, 0, 0.3)")
            zIndex = 1000
            put("animation", "slideIn 0.3s ease")
        }

        ".toast.success" {
            backgroundColor = CssTheme.colorSuccess
        }

        ".toast.error" {
            backgroundColor = CssTheme.colorDanger
        }

        "@keyframes slideIn" {
            "from" {
                transform { translateY(1.rem) }
                opacity = 0
            }
            "to" {
                transform { translateY(0.rem) }
                opacity = 1
            }
        }
    }
}
