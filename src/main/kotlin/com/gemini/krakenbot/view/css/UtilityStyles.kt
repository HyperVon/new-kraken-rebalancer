package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
import kotlinx.css.CssBuilder
import kotlinx.css.Display
import kotlinx.css.FontWeight
import kotlinx.css.Padding
import kotlinx.css.backgroundColor
import kotlinx.css.borderRadius
import kotlinx.css.color
import kotlinx.css.display
import kotlinx.css.fontWeight
import kotlinx.css.marginBottom
import kotlinx.css.padding
import kotlinx.css.rem

object UtilityStyles {
    fun CssBuilder.applyUtilityStyles() {
        ".${CssClass.Utility.ErrorBanner}" {
            backgroundColor = CssTheme.colorDangerMuted
            solidBorder(CssTheme.colorDangerBorder)
            color = CssTheme.colorDangerLight
            padding = Padding(1.rem)
            borderRadius = CssTheme.radiusMd
            marginBottom = 1.5.rem
            fontWeight = FontWeight.w500
        }

        "@media (prefers-reduced-motion: reduce)" {
            "*, *::before, *::after" {
                animationDurationRaw("0.01ms !important")
                animationIterationCountRaw("1 !important")
                scrollBehaviorRaw("auto !important")
                transitionDurationRaw("0.01ms !important")
            }
        }

        ".${CssClass.Utility.Hidden}" {
            display = Display.none
        }
    }
}
