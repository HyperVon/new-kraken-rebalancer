package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
import kotlinx.css.Align
import kotlinx.css.BorderStyle
import kotlinx.css.CssBuilder
import kotlinx.css.Display
import kotlinx.css.FlexDirection
import kotlinx.css.FontWeight
import kotlinx.css.Padding
import kotlinx.css.alignItems
import kotlinx.css.background
import kotlinx.css.borderLeftColor
import kotlinx.css.borderLeftStyle
import kotlinx.css.borderLeftWidth
import kotlinx.css.borderRadius
import kotlinx.css.borderTopColor
import kotlinx.css.borderTopStyle
import kotlinx.css.borderTopWidth
import kotlinx.css.color
import kotlinx.css.display
import kotlinx.css.flexDirection
import kotlinx.css.fontFamily
import kotlinx.css.fontSize
import kotlinx.css.fontWeight
import kotlinx.css.gap
import kotlinx.css.marginTop
import kotlinx.css.padding
import kotlinx.css.paddingLeft
import kotlinx.css.properties.TextDecoration
import kotlinx.css.px
import kotlinx.css.rem
import kotlinx.css.textDecoration

object ActivityFeedStyles {
    // DASH-3: cycle-grouped activity feed replaces the flat log dump.
    fun CssBuilder.applyActivityFeedStyles() {
        ".${CssClass.Activity.Feed.value}" {
            display = Display.flex
            flexDirection = FlexDirection.column
            gap = 0.px
            marginTop = 0.5.rem
        }

        ".${CssClass.Activity.Cycle.value}" {
            background = "transparent"
            borderTopWidth = 1.px
            borderTopStyle = BorderStyle.solid
            borderTopColor = CssTheme.colorBorderFaint
        }

        ".${CssClass.Activity.Cycle.value}:first-child" {
            borderTopStyle = BorderStyle.none
        }

        ".${CssClass.Activity.CycleHeader.value}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.75.rem
            padding = Padding(0.75.rem, 0.px)
        }

        ".${CssClass.Activity.CycleTime.value}" {
            fontSize = 0.75.rem
            color = CssTheme.colorTextMuted
            fontFamily = CssTheme.fontMono
            marginLeftRaw("auto")
        }

        ".${CssClass.Activity.CycleMeta.value}" {
            fontSize = 0.75.rem
            color = CssTheme.colorTextSecondary
            fontWeight = FontWeight.w600
        }

        ".${CssClass.Activity.CycleBody.value}" {
            display = Display.flex
            flexDirection = FlexDirection.column
            padding = Padding(0.px, 0.px, 0.5.rem)
        }

        ".${CssClass.Activity.Item.value}" {
            display = Display.flex
            alignItems = Align.center
            gap = 0.625.rem
            padding = Padding(0.375.rem, 0.px)
            borderTopWidth = 1.px
            borderTopStyle = BorderStyle.solid
            borderTopColor = CssTheme.colorBorderFaint
        }

        CssClass.Activity.ItemTrade.querySelector {
            paddingLeft = 0.625.rem
            borderLeftWidth = 3.px
            borderLeftStyle = BorderStyle.solid
            borderLeftColor = CssTheme.colorBlueAccent
            marginLeftRaw("0")
        }

        ".${CssClass.Activity.ItemText.value}" {
            fontSize = 0.8125.rem
            color = CssTheme.colorTextPrimary
            lineHeightRaw("1.35")
        }

        "${CssClass.Activity.Item.querySelector} .${CssClass.Activity.ItemText.value}" {
            color = CssTheme.colorTextSecondary
        }

        "${CssClass.Activity.ItemTrade.querySelector} .${CssClass.Activity.ItemText.value}" {
            color = CssTheme.colorTextPrimary
            fontWeight = FontWeight.w600
        }

        ".${CssClass.Activity.FeedFooter.value}" {
            display = Display.flex
            justifyContentRaw("center")
            marginTop = 0.75.rem
        }

        ".${CssClass.Activity.ViewAll.value}" {
            display = Display.inlineFlex
            alignItems = Align.center
            gap = 0.375.rem
            fontSize = 0.8125.rem
            fontWeight = FontWeight.w600
            color = CssTheme.colorBlueAccent
            textDecoration = TextDecoration.none
            padding = Padding(0.375.rem, 0.875.rem)
            borderRadius = CssTheme.radiusMd
            transitionRaw("all 0.2s ease")
        }

        ".${CssClass.Activity.ViewAll.value}:hover" {
            background = CssTheme.colorBlueGlassBg.value
        }
    }
}
