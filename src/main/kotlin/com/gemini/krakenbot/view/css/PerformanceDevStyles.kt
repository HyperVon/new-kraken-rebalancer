package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
import kotlinx.css.Align
import kotlinx.css.CssBuilder
import kotlinx.css.Display
import kotlinx.css.FlexDirection
import kotlinx.css.FontWeight
import kotlinx.css.Padding
import kotlinx.css.QuotedString
import kotlinx.css.alignItems
import kotlinx.css.background
import kotlinx.css.backgroundColor
import kotlinx.css.borderRadius
import kotlinx.css.color
import kotlinx.css.content
import kotlinx.css.display
import kotlinx.css.flexDirection
import kotlinx.css.fontFamily
import kotlinx.css.fontSize
import kotlinx.css.fontWeight
import kotlinx.css.gap
import kotlinx.css.height
import kotlinx.css.marginBottom
import kotlinx.css.marginRight
import kotlinx.css.opacity
import kotlinx.css.pct
import kotlinx.css.px
import kotlinx.css.rem
import kotlinx.css.width

object PerformanceDevStyles {
    fun CssBuilder.applyPerformanceDevStyles() {
        ".${CssClass.Performance.DevContainer}" {
            display = Display.flex
            flexDirection = FlexDirection.column
            lineHeightRaw("1.1")
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
    }
}
