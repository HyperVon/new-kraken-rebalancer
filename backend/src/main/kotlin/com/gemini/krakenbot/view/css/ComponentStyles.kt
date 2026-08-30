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
import kotlinx.css.backgroundColor
import kotlinx.css.borderColor
import kotlinx.css.borderRadius
import kotlinx.css.borderStyle
import kotlinx.css.borderWidth
import kotlinx.css.color
import kotlinx.css.display
import kotlinx.css.flexDirection
import kotlinx.css.flexShrink
import kotlinx.css.fontFamily
import kotlinx.css.fontSize
import kotlinx.css.fontWeight
import kotlinx.css.gap
import kotlinx.css.height
import kotlinx.css.marginLeft
import kotlinx.css.opacity
import kotlinx.css.padding
import kotlinx.css.paddingTop
import kotlinx.css.px
import kotlinx.css.rem
import kotlinx.css.width

object ComponentStyles {
    fun CssBuilder.applyComponentStyles() {
        ".${CssClass.StatusCard.Badge}" {
            padding = Padding(0.125.rem, 0.625.rem)
            borderRadius = CssTheme.radiusPill
            fontSize = 0.75.rem
            fontWeight = FontWeight.w700
            letterSpacingRaw("0.05em")
            boxShadowRaw(CssTheme.shadowBadge)
        }

        ".${CssClass.StatusCard.Badge}.live" {
            backgroundColor = CssTheme.colorSuccessMuted
            color = CssTheme.colorSuccess
            solidBorder(CssTheme.colorSuccessBorder)
            animationRaw("pulse 2s cubic-bezier(0.4, 0, 0.6, 1) infinite")
        }

        ".${CssClass.StatusCard.Badge}.delayed" {
            backgroundColor = CssTheme.colorWarningMuted
            color = CssTheme.colorWarning
            solidBorder(CssTheme.colorWarningBorder)
        }

        ".${CssClass.Form.AllocationTotal}" {
            display = Display.inlineFlex
            alignItems = Align.center
            padding = Padding(0.25.rem, 0.75.rem)
            borderRadius = CssTheme.radiusPill
            fontSize = 0.75.rem
            fontWeight = FontWeight.w700
            letterSpacingRaw("0.04em")
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
        }

        ".${CssClass.Form.AllocationTotalOk}" {
            backgroundColor = CssTheme.colorSuccessMuted
            color = CssTheme.colorSuccess
            borderColor = CssTheme.colorSuccessBorder
        }

        ".${CssClass.Form.AllocationTotalBad}" {
            backgroundColor = CssTheme.colorDangerMuted
            color = CssTheme.colorDanger
            borderColor = CssTheme.colorDangerBorder
        }

        "@keyframes pulse" {
            "0%, 100%" {
                opacity = 1
            }
            "50%" {
                opacity = 0.6
            }
        }

        CssClass.StatusCard.Default.querySelector {
            display = Display.flex
            flexDirection = FlexDirection.column
            gap = 0.375.rem
            padding = Padding(0.75.rem, 0.875.rem)
            borderRadius = CssTheme.radiusXl
        }

        ".${CssClass.StatusCard.Header}" {
            display = Display.flex
            justifyContentRaw("space-between")
            alignItems = Align.flexStart
            gap = 0.5.rem
        }

        ".${CssClass.StatusCard.Title}" {
            fontSize = 0.8125.rem
            fontWeight = FontWeight.w600
            color = CssTheme.colorTextSecondary
            paddingTop = 0.px
            lineHeightRaw("1.25")
        }

        ".${CssClass.StatusCard.Icon}" {
            display = Display.flex
            alignItems = Align.center
            justifyContentRaw("center")
            width = 1.75.rem
            height = 1.75.rem
            flexShrink = 0.0
            borderRadius = CssTheme.radiusSm
            background = CssTheme.colorGlassHover.value
            color = CssTheme.colorTextSecondary
            marginLeft = 0.px
        }

        ".${CssClass.StatusCard.Icon} svg" {
            width = 0.9375.rem
            height = 0.9375.rem
        }

        ".${CssClass.StatusCard.Value}" {
            fontSize = 1.375.rem
            fontWeight = FontWeight.w700
            fontFamily = CssTheme.fontHeading
            letterSpacingRaw("-0.02em")
            lineHeightRaw("1.15")
        }
    }
}
