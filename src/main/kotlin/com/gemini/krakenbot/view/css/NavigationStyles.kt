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
            background = CssTheme.colorBlueGlassBg.value
            borderColor = CssTheme.colorBlueGlassBorder
            fontWeight = FontWeight.w600
        }

        ".${CssClass.History.TimeRangeSelector}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.375.rem
            marginBottom = 1.25.rem
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
        }

        ".${CssClass.History.ChartContainer}" {
            position = Position.relative
            height = 20.rem
            marginTop = 0.5.rem
        }

        ".${CssClass.History.StatsGrid}" {
            display = Display.grid
            gridTemplateColumns = GridTemplateColumns("1fr")
            gap = 1.rem
            marginBottom = 1.25.rem
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
            alignItems = Align.center
            gap = 0.75.rem
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
