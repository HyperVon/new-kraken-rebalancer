package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
import kotlinx.css.Align
import kotlinx.css.Color
import kotlinx.css.CssBuilder
import kotlinx.css.Display
import kotlinx.css.FontWeight
import kotlinx.css.Padding
import kotlinx.css.alignItems
import kotlinx.css.background
import kotlinx.css.borderColor
import kotlinx.css.borderRadius
import kotlinx.css.color
import kotlinx.css.display
import kotlinx.css.fontSize
import kotlinx.css.fontWeight
import kotlinx.css.gap
import kotlinx.css.padding
import kotlinx.css.properties.TextDecoration
import kotlinx.css.rem
import kotlinx.css.textDecoration

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
            borderRadius = CssTheme.radiusMd
            fontSize = 0.875.rem
            fontWeight = FontWeight.w500
            color = CssTheme.colorTextSecondary
            textDecoration = TextDecoration.none
            transitionRaw("all 0.2s ease")
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
            outlineRaw("none")
            borderColor = CssTheme.colorBluePrimary
            boxShadowRaw(CssTheme.focusRingStrong)
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
    }
}
