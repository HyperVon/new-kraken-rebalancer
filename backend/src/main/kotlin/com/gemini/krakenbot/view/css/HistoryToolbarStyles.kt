package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
import kotlinx.css.Align
import kotlinx.css.Color
import kotlinx.css.CssBuilder
import kotlinx.css.Cursor
import kotlinx.css.Display
import kotlinx.css.FontWeight
import kotlinx.css.Padding
import kotlinx.css.alignItems
import kotlinx.css.background
import kotlinx.css.backgroundColor
import kotlinx.css.borderColor
import kotlinx.css.borderRadius
import kotlinx.css.color
import kotlinx.css.cursor
import kotlinx.css.display
import kotlinx.css.fontFamily
import kotlinx.css.fontSize
import kotlinx.css.fontWeight
import kotlinx.css.gap
import kotlinx.css.marginBottom
import kotlinx.css.opacity
import kotlinx.css.padding
import kotlinx.css.paddingLeft
import kotlinx.css.px
import kotlinx.css.rem

object HistoryToolbarStyles {
    fun CssBuilder.applyHistoryToolbarStyles() {
        ".${CssClass.History.ToolbarRow}" {
            display = Display.flex
            alignItems = Align.center
            flexWrapRaw("wrap")
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
            borderRadius = CssTheme.radiusLg
            widthRaw("fit-content")
            maxWidthRaw("100%")
            flexWrapRaw("wrap")
        }

        ".${CssClass.History.TimeRangeBtn}" {
            padding = Padding(0.375.rem, 1.rem)
            borderRadius = CssTheme.radiusMd
            fontSize = 0.8125.rem
            fontWeight = FontWeight.w600
            color = CssTheme.colorTextSecondary
            background = "transparent"
            solidBorder(Color.transparent)
            cursor = Cursor.pointer
            transitionRaw("all 0.2s ease")
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
            boxShadowRaw("0 0 14px rgba(59, 130, 246, 0.3), inset 0 1px 0 rgba(255,255,255,0.1)")
        }

        // Parens are load-bearing here as well (see the rule above): without
        // them only the last concatenated selector would emit.
        (
            "${CssClass.Navigation.LinkActive.querySelector}:focus-visible, " +
                "${CssClass.History.TimeRangeBtnActive.querySelector}:focus-visible"
            ) {
            // Transparent outline stays invisible normally but is system-painted in
            // forced-colors mode, where the box-shadow ring is not rendered.
            outlineRaw("3px solid transparent")
            borderColor = CssTheme.colorBluePrimary
            boxShadowRaw(CssTheme.focusRingStrong)
        }

        ".${CssClass.History.ViewsToolbar}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.5.rem
            padding = Padding(0.25.rem, 0.5.rem)
            background = CssTheme.colorGlass.value
            solidBorder(CssTheme.colorGlassBorder)
            borderRadius = CssTheme.radiusLg
            flexWrapRaw("wrap")
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
            appearanceRaw("none")
            webkitAppearanceRaw("none")
            mozAppearanceRaw("none")
            background = CssTheme.colorSurface2.value
            color = CssTheme.colorTextPrimary
            solidBorder(CssTheme.colorSurface2Border)
            borderRadius = CssTheme.radiusMd
            padding = Padding(0.375.rem, 2.rem, 0.375.rem, 0.75.rem)
            fontSize = 0.8125.rem
            fontFamily = CssTheme.fontSans
            fontWeight = FontWeight.w600
            cursor = Cursor.pointer
            minWidthRaw("11rem")
            put(
                "background-image",
                "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' " +
                    "viewBox='0 0 24 24' fill='none' stroke='%23a8b4c8' stroke-width='2' stroke-linecap='round' " +
                    "stroke-linejoin='round'%3E%3Cpolyline points='6 9 12 15 18 9'/%3E%3C/svg%3E\")",
            )
            backgroundRepeatRaw("no-repeat")
            backgroundPositionRaw("right 0.625rem center")
        }

        ".${CssClass.History.ViewsSelect}:hover" {
            borderColor = CssTheme.colorGlassBorderHover
        }

        ".${CssClass.History.ViewsSelect}:focus" {
            outlineRaw("none")
            borderColor = CssTheme.colorBluePrimary
            boxShadowRaw(CssTheme.focusRingSubtle)
        }

        ".${CssClass.History.ViewsSelect} option" {
            backgroundColor = CssTheme.colorSurface1
            color = CssTheme.colorTextPrimary
        }

        ".${CssClass.History.ViewsActions}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.25.rem
            flexWrapRaw("wrap")
        }

        ".${CssClass.History.ViewsBtn}" {
            appearanceRaw("none")
            webkitAppearanceRaw("none")
            padding = Padding(0.375.rem, 0.625.rem)
            borderRadius = CssTheme.radiusMd
            fontSize = 0.75.rem
            fontWeight = FontWeight.w600
            color = CssTheme.colorTextSecondary
            background = "transparent"
            solidBorder(Color.transparent)
            cursor = Cursor.pointer
            transitionRaw("all 0.2s ease")
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
    }
}
