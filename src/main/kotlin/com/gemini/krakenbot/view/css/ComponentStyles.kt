package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
import kotlinx.css.*
import kotlinx.css.properties.*

object ComponentStyles {
    fun CssBuilder.applyComponentStyles() {
        // Status Badges & Cards
        ".status-badge" {
            padding = Padding(0.125.rem, 0.625.rem)
            borderRadius = CssTheme.radiusPill
            fontSize = 0.75.rem
            fontWeight = FontWeight.w700
            put("letter-spacing", "0.05em")
            put("box-shadow", "0 4px 6px -1px rgba(0, 0, 0, 0.1)")
        }

        ".status-badge.live" {
            backgroundColor = Color("rgba(16, 185, 129, 0.15)")
            color = CssTheme.colorSuccess
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = Color("rgba(16, 185, 129, 0.3)")
            put("animation", "pulse 2s cubic-bezier(0.4, 0, 0.6, 1) infinite")
        }

        ".status-badge.delayed" {
            backgroundColor = Color("rgba(245, 158, 11, 0.15)")
            color = CssTheme.colorWarning
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = Color("rgba(245, 158, 11, 0.3)")
        }

        ".status-badge.offline" {
            backgroundColor = Color("rgba(100, 116, 139, 0.15)")
            color = CssTheme.colorTextSecondary
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = Color("rgba(100, 116, 139, 0.3)")
        }

        "@keyframes pulse" {
            "0%, 100%" {
                opacity = 1
            }
            "50%" {
                opacity = 0.6
            }
        }

        ".status-card" {
            display = Display.flex
            flexDirection = FlexDirection.column
            gap = 0.5.rem
        }

        ".${CssClass.StatusCard.Header}" {
            display = Display.flex
            put("justify-content", "space-between")
            alignItems = Align.center
        }

        ".${CssClass.StatusCard.Title}" {
            fontSize = 0.875.rem
            fontWeight = FontWeight.w600
            color = CssTheme.colorTextSecondary
        }

        ".${CssClass.StatusCard.Icon}" {
            display = Display.flex
            alignItems = Align.center
            put("justify-content", "center")
            width = 2.rem
            height = 2.rem
            borderRadius = 0.5.rem
            background = CssTheme.colorGlassHover.value
            color = CssTheme.colorTextSecondary
        }

        ".${CssClass.StatusCard.Value}" {
            fontSize = 1.75.rem
            fontWeight = FontWeight.w700
            fontFamily = CssTheme.fontHeading
            put("letter-spacing", "-0.02em")
        }

        ".status-card.success .${CssClass.StatusCard.Value}" {
            color = CssTheme.colorSuccess
        }

        ".${CssClass.StatusCard.Sub}" {
            put("margin-top", "auto")
            fontSize = 0.75.rem
            color = CssTheme.colorTextSecondary
        }

        // Allocation Charts
        ".${CssClass.AllocationChart.Container}" {
            display = Display.flex
            flexDirection = FlexDirection.column
            gap = 0.875.rem
            marginTop = 0.5.rem
        }

        ".${CssClass.AllocationChart.BarRow}" {
            display = Display.flex
            alignItems = Align.center
            gap = 1.rem
            put("transition", "transform 0.2s ease")
        }

        ".${CssClass.AllocationChart.BarRow}:hover" {
            transform { translateX(4.px) }
        }

        ".${CssClass.AllocationChart.BarLabel}" {
            width = 3.5.rem
            fontWeight = FontWeight.w700
            color = CssTheme.colorTextPrimary
            fontSize = 0.875.rem
        }

        ".${CssClass.AllocationChart.BarTrack}" {
            flexGrow = 1.0
            height = 0.75.rem
            background = "rgba(255, 255, 255, 0.05)"
            borderRadius = CssTheme.radiusPill
            overflow = Overflow.hidden
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = Color("rgba(255, 255, 255, 0.02)")
        }

        ".${CssClass.AllocationChart.BarFill}" {
            height = 100.pct
            borderRadius = CssTheme.radiusPill
            background = "linear-gradient(90deg, ${CssTheme.colorBluePrimary.value}, ${CssTheme.colorSuccess.value})"
            put("transition", "width 0.8s cubic-bezier(0.4, 0, 0.2, 1)")
        }

        ".${CssClass.AllocationChart.BarValue}" {
            width = 9.5.rem
            textAlign = TextAlign.right
            fontFamily = CssTheme.fontMono
            fontSize = 0.75.rem
            color = CssTheme.colorTextSecondary
        }

        // Scrollbars, Spinners & Toasts
        ".custom-scrollbar" {
            put("scrollbar-width", "thin")
            put("scrollbar-color", "rgba(148, 163, 184, 0.15) transparent")
        }

        ".custom-scrollbar::-webkit-scrollbar" {
            width = 6.px
            height = 6.px
        }

        ".custom-scrollbar::-webkit-scrollbar-track" {
            background = "transparent"
        }

        ".custom-scrollbar::-webkit-scrollbar-thumb" {
            backgroundColor = Color("rgba(148, 163, 184, 0.15)")
            borderRadius = CssTheme.radiusPill
        }

        ".custom-scrollbar::-webkit-scrollbar-thumb:hover" {
            backgroundColor = Color("rgba(148, 163, 184, 0.3)")
        }

        ".max-h-100" {
            maxHeight = 25.rem
        }

        ".${CssClass.Loading.SpinnerContainer}" {
            display = Display.flex
            flexDirection = FlexDirection.column
            alignItems = Align.center
            put("justify-content", "center")
            put("min-height", "calc(100vh - 10rem)")
            gap = 1.rem
        }

        ".${CssClass.Loading.Spinner}" {
            width = 3.rem
            height = 3.rem
            borderWidth = 4.px
            borderStyle = BorderStyle.solid
            borderColor = CssTheme.colorKrakenBlue
            put("border-top-color", "transparent")
            borderRadius = 50.pct
            put("animation", "spin 1s linear infinite")
        }

        "@keyframes spin" {
            "to" {
                transform { rotate(360.deg) }
            }
        }

        ".${CssClass.Activity.EmptyHistoryBox}, .history-empty" {
            display = Display.flex
            flexDirection = FlexDirection.column
            alignItems = Align.center
            put("justify-content", "center")
            padding = Padding(4.rem, 1.rem)
            color = CssTheme.colorTextMuted
            textAlign = TextAlign.center
            gap = 0.5.rem
        }

        ".${CssClass.Activity.EmptyHistoryBox} svg" {
            color = Color("rgba(255, 255, 255, 0.1)")
            marginBottom = 0.5.rem
        }

        ".${CssClass.Activity.EmptyHistoryBox} h3" {
            color = CssTheme.colorTextSecondary
        }

        ".${CssClass.Dashboard.WaitingTitle}" {
            fontSize = 1.25.rem
            fontWeight = FontWeight.w600
            color = CssTheme.colorTextBright
        }

        ".${CssClass.Dashboard.WaitingText}" {
            color = CssTheme.colorTextSecondary
            fontSize = 0.875.rem
            textAlign = TextAlign.center
            maxWidth = 24.rem
        }
    }
}
