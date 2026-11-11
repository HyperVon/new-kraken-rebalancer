package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
import kotlinx.css.CssBuilder
import kotlinx.css.Display
import kotlinx.css.FontWeight
import kotlinx.css.Margin
import kotlinx.css.Overflow
import kotlinx.css.Padding
import kotlinx.css.Position
import kotlinx.css.backgroundColor
import kotlinx.css.borderRadius
import kotlinx.css.borderWidth
import kotlinx.css.color
import kotlinx.css.display
import kotlinx.css.fontWeight
import kotlinx.css.height
import kotlinx.css.margin
import kotlinx.css.marginBottom
import kotlinx.css.overflow
import kotlinx.css.padding
import kotlinx.css.position
import kotlinx.css.px
import kotlinx.css.rem
import kotlinx.css.width

object UtilityStyles {
    fun CssBuilder.applyUtilityStyles() {
        ".${CssClass.Utility.ScreenReaderOnly}" {
            position = Position.absolute
            width = 1.px
            height = 1.px
            padding = Padding(0.px)
            margin = Margin((-1).px)
            overflow = Overflow.hidden
            clipRaw("rect(0, 0, 0, 0)")
            clipPathRaw("inset(50%)")
            whiteSpaceRaw("nowrap")
            borderWidth = 0.px
        }

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
