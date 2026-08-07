package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
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
import kotlinx.css.tr
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
            put("text-transform", "uppercase")
            put("letter-spacing", "0.05em")
        }

        td {
            padding = Padding(0.75.rem, 0.5.rem)
            put("vertical-align", "middle")
            borderBottomWidth = 1.px
            borderBottomStyle = BorderStyle.solid
            borderBottomColor = CssTheme.colorBorderFaint
        }

        "tr:last-child td" {
            borderBottomStyle = BorderStyle.none
        }

        "tr.hoverable:hover" {
            backgroundColor = CssTheme.colorWhiteSubtle
        }

        ".${CssClass.Table.SymbolCol}" {
            fontWeight = FontWeight.w700
            color = CssTheme.colorTextPrimary
        }

        ".${CssClass.Table.MonoCol}" {
            fontFamily = CssTheme.fontMono
            // HIST-3: tabular figures keep decimal columns aligned.
            put("font-variant-numeric", "tabular-nums")
        }

        // HIST-3: quiet success indicator (replaces the always-"SUCCESS" text column).
        ".${CssClass.Table.StatusDot.value}" {
            display = Display.inlineBlock
            width = 0.5.rem
            height = 0.5.rem
            borderRadius = 50.pct
            backgroundColor = CssTheme.colorSuccess
            put("box-shadow", "0 0 0 3px rgba(16, 185, 129, 0.15)")
        }

        "th.sortable" {
            cursor = Cursor.pointer
            put("user-select", "none")
        }

        "th.sortable:hover" {
            color = CssTheme.colorTextPrimary
        }

        "th.sortable:focus-visible" {
            put("outline", "none")
            color = CssTheme.colorTextPrimary
            put("box-shadow", "inset 0 0 0 2px rgba(59, 130, 246, 0.7)")
        }

        "th.sortable::after" {
            content = QuotedString("")
            marginLeft = 0.35.rem
            fontSize = 0.7.rem
            opacity = 0.4
        }

        "th.sortable.asc::after" {
            content = QuotedString("▲")
            opacity = 1
        }

        "th.sortable.desc::after" {
            content = QuotedString("▼")
            opacity = 1
        }

        // Badges — shared outline system for activity + trade SIDE/STATUS
        ".badge" {
            display = Display.inlineFlex
            alignItems = Align.center
            padding = Padding(0.125.rem, 0.5.rem)
            borderRadius = CssTheme.radiusSm
            fontSize = 0.675.rem
            fontWeight = FontWeight.w700
            put("letter-spacing", "0.05em")
            backgroundColor = Color.transparent
            solidBorder(CssTheme.colorWhiteBorder)
            color = CssTheme.colorTextSecondary
        }

        ".badge.badge-buy, .badge.badge-success" {
            backgroundColor = CssTheme.colorSuccessBgSubtle
            borderColor = CssTheme.colorSuccessBorderSubtle
            color = CssTheme.colorSuccess
        }

        ".badge.badge-sell, .badge.badge-failed" {
            backgroundColor = CssTheme.colorDangerBgSubtle
            borderColor = CssTheme.colorDangerBorderSubtle
            color = CssTheme.colorDanger
        }

        ".badge.badge-info" {
            backgroundColor = CssTheme.colorBlueGlassBg
            borderColor = CssTheme.colorBlueGlassBorder
            color = CssTheme.colorBlueAccent
        }

        ".badge.badge-slippage-favorable" {
            backgroundColor = CssTheme.colorSuccessBgSubtle
            borderColor = CssTheme.colorSuccessBorderSubtle
            color = CssTheme.colorSuccess
        }

        ".badge.badge-slippage-adverse" {
            backgroundColor = CssTheme.colorDangerBgSubtle
            borderColor = CssTheme.colorDangerBorderSubtle
            color = CssTheme.colorDanger
        }

        ".badge.badge-slippage-neutral" {
            backgroundColor = Color.transparent
            borderColor = CssTheme.colorWhiteBorder
            color = CssTheme.colorTextMuted
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
