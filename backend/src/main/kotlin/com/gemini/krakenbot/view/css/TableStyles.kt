package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlQueries
import kotlinx.css.Align
import kotlinx.css.BorderCollapse
import kotlinx.css.BorderStyle
import kotlinx.css.Color
import kotlinx.css.CssBuilder
import kotlinx.css.Cursor
import kotlinx.css.Display
import kotlinx.css.FontWeight
import kotlinx.css.Overflow
import kotlinx.css.Padding
import kotlinx.css.QuotedString
import kotlinx.css.TextAlign
import kotlinx.css.alignItems
import kotlinx.css.backgroundColor
import kotlinx.css.borderBottomColor
import kotlinx.css.borderBottomStyle
import kotlinx.css.borderBottomWidth
import kotlinx.css.borderCollapse
import kotlinx.css.borderColor
import kotlinx.css.borderRadius
import kotlinx.css.color
import kotlinx.css.content
import kotlinx.css.cursor
import kotlinx.css.display
import kotlinx.css.fontFamily
import kotlinx.css.fontSize
import kotlinx.css.fontWeight
import kotlinx.css.height
import kotlinx.css.marginBottom
import kotlinx.css.marginLeft
import kotlinx.css.marginRight
import kotlinx.css.marginTop
import kotlinx.css.opacity
import kotlinx.css.overflowX
import kotlinx.css.padding
import kotlinx.css.paddingLeft
import kotlinx.css.paddingRight
import kotlinx.css.pct
import kotlinx.css.px
import kotlinx.css.rem
import kotlinx.css.table
import kotlinx.css.td
import kotlinx.css.textAlign
import kotlinx.css.th
import kotlinx.css.thead
import kotlinx.css.width

object TableStyles {
    fun CssBuilder.applyTableStyles() {
        ".${CssClass.Table.Wrapper}" {
            overflowX = Overflow.auto
            marginTop = 0.px
            marginBottom = 0.px
            marginLeft = (-1.5).rem
            marginRight = (-1.5).rem
            paddingLeft = 1.5.rem
            paddingRight = 1.5.rem
        }

        table {
            width = 100.pct
            borderCollapse = BorderCollapse.collapse
            textAlign = TextAlign.left
            fontSize = 0.875.rem
        }

        thead {
            borderBottomWidth = 1.px
            borderBottomStyle = BorderStyle.solid
            borderBottomColor = CssTheme.colorBorderMuted
        }

        th {
            padding = Padding(0.75.rem, 0.5.rem)
            fontWeight = FontWeight.w600
            color = CssTheme.colorTextSecondary
            fontSize = 0.75.rem
            textTransformRaw("uppercase")
            letterSpacingRaw("0.05em")
        }

        td {
            padding = Padding(0.75.rem, 0.5.rem)
            verticalAlignRaw("middle")
            borderBottomWidth = 1.px
            borderBottomStyle = BorderStyle.solid
            borderBottomColor = CssTheme.colorBorderFaint
        }

        "tr:last-child td" {
            borderBottomStyle = BorderStyle.none
        }

        HtmlQueries.HOVERABLE_TR {
            transitionRaw("all 0.2s ease")
        }

        "${HtmlQueries.HOVERABLE_TR}:hover" {
            backgroundColor = CssTheme.colorWhiteSubtle
        }

        ".${CssClass.Table.SymbolCol}" {
            fontWeight = FontWeight.w700
            color = CssTheme.colorTextPrimary
        }

        ".${CssClass.Table.MonoCol}" {
            fontFamily = CssTheme.fontMono
            // Tabular figures keep decimal columns aligned.
            fontVariantNumericRaw("tabular-nums")
        }

        HtmlQueries.SORTABLE_TH {
            cursor = Cursor.pointer
            userSelectRaw("none")
            transitionRaw("all 0.2s ease")
        }

        "${HtmlQueries.SORTABLE_TH}:hover" {
            color = CssTheme.colorTextPrimary
        }

        "${HtmlQueries.SORTABLE_TH}:focus-visible" {
            outlineRaw("none")
            color = CssTheme.colorTextPrimary
            boxShadowRaw(CssTheme.focusRingInsetStrong)
        }

        "${HtmlQueries.SORTABLE_TH}::after" {
            content = QuotedString("")
            marginLeft = 0.25.rem
            fontSize = 0.7.rem
            opacity = 0.4
        }

        "${HtmlQueries.SORTABLE_TH}.${CssClass.Utility.Asc.value}::after" {
            content = QuotedString("▲")
            opacity = 1
        }

        "${HtmlQueries.SORTABLE_TH}.${CssClass.Utility.Desc.value}::after" {
            content = QuotedString("▼")
            opacity = 1
        }

        // Badges — shared outline system for activity actions.
        ".badge" {
            display = Display.inlineFlex
            alignItems = Align.center
            padding = Padding(0.125.rem, 0.5.rem)
            borderRadius = CssTheme.radiusSm
            fontSize = 0.75.rem
            fontWeight = FontWeight.w700
            letterSpacingRaw("0.05em")
            backgroundColor = Color.transparent
            solidBorder(CssTheme.colorWhiteBorder)
            color = CssTheme.colorTextSecondary
        }

        ".badge.badge-buy" {
            backgroundColor = CssTheme.colorSuccessBgSubtle
            borderColor = CssTheme.colorSuccessBorderSubtle
            color = CssTheme.colorSuccess
        }

        ".badge.badge-sell" {
            backgroundColor = CssTheme.colorDangerBgSubtle
            borderColor = CssTheme.colorDangerBorderSubtle
            color = CssTheme.colorDanger
        }

        ".badge.badge-info" {
            backgroundColor = CssTheme.colorBlueGlassBg
            borderColor = CssTheme.colorBlueGlassBorder
            color = CssTheme.colorBlueAccent
        }

        ".${CssClass.Utility.TextDanger}" {
            color = CssTheme.colorDanger
        }

        ".${CssClass.Utility.TextOverweight}" {
            color = CssTheme.colorWarning
        }

        ".${CssClass.Utility.TextUnderweight}" {
            color = CssTheme.colorBlueAccent
        }
    }
}
