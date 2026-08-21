package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
import kotlinx.css.Align
import kotlinx.css.CssBuilder
import kotlinx.css.Display
import kotlinx.css.FlexDirection
import kotlinx.css.FontWeight
import kotlinx.css.Padding
import kotlinx.css.TextAlign
import kotlinx.css.alignItems
import kotlinx.css.background
import kotlinx.css.borderRadius
import kotlinx.css.color
import kotlinx.css.display
import kotlinx.css.flexDirection
import kotlinx.css.fontSize
import kotlinx.css.fontWeight
import kotlinx.css.gap
import kotlinx.css.height
import kotlinx.css.marginBottom
import kotlinx.css.maxWidth
import kotlinx.css.padding
import kotlinx.css.pct
import kotlinx.css.properties.deg
import kotlinx.css.properties.rotate
import kotlinx.css.properties.transform
import kotlinx.css.px
import kotlinx.css.rem
import kotlinx.css.textAlign
import kotlinx.css.width

object FeedbackStyles {
    fun CssBuilder.applyFeedbackStyles() {
        ".${CssClass.Loading.SpinnerContainer}" {
            display = Display.flex
            flexDirection = FlexDirection.column
            alignItems = Align.center
            justifyContentRaw("center")
            minHeightRaw("calc(100vh - 10rem)")
            gap = 1.rem
        }

        ".${CssClass.Loading.Spinner}" {
            width = 3.rem
            height = 3.rem
            solidBorder(CssTheme.colorKrakenBlue, 4.px)
            borderTopColorRaw("transparent")
            borderRadius = 50.pct
            animationRaw("spin 1s linear infinite")
        }

        "@keyframes spin" {
            "to" {
                transform { rotate(360.deg) }
            }
        }

        ".${CssClass.Activity.EmptyHistoryBox}" {
            display = Display.flex
            flexDirection = FlexDirection.column
            alignItems = Align.center
            justifyContentRaw("center")
            padding = Padding(4.rem, 1.rem)
            color = CssTheme.colorTextMuted
            textAlign = TextAlign.center
            gap = 0.5.rem
        }

        ".${CssClass.Activity.EmptyHistoryBox} svg" {
            color = CssTheme.colorIconFaint
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
