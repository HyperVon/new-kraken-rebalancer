package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
import kotlinx.css.*

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
            borderBottomColor = Color("rgba(51, 65, 85, 0.2)")
        }

        "tr:last-child td" {
            borderBottomStyle = BorderStyle.none
        }

        "tr.hoverable:hover" {
            backgroundColor = Color("rgba(255, 255, 255, 0.02)")
        }

        ".${CssClass.Table.SymbolCol}" {
            fontWeight = FontWeight.w700
            color = CssTheme.colorTextPrimary
        }

        ".${CssClass.Table.MonoCol}" {
            fontFamily = CssTheme.fontMono
        }

        "th.sortable" {
            cursor = Cursor.pointer
            put("user-select", "none")
        }

        "th.sortable:hover" {
            color = CssTheme.colorTextPrimary
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

        // Badges
        ".badge" {
            display = Display.inlineFlex
            alignItems = Align.center
            padding = Padding(0.125.rem, 0.5.rem)
            borderRadius = 0.375.rem
            fontSize = 0.675.rem
            fontWeight = FontWeight.w700
            put("letter-spacing", "0.05em")
            backgroundColor = Color("rgba(255, 255, 255, 0.05)")
            borderWidth = 1.px
            borderStyle = BorderStyle.solid
            borderColor = Color("rgba(255, 255, 255, 0.1)")
            color = CssTheme.colorTextSecondary
        }

        ".badge.badge-buy" {
            backgroundColor = Color("rgba(16, 185, 129, 0.1)")
            borderColor = Color("rgba(16, 185, 129, 0.2)")
            color = CssTheme.colorSuccess
        }

        ".badge.badge-sell" {
            backgroundColor = Color("rgba(239, 68, 68, 0.1)")
            borderColor = Color("rgba(239, 68, 68, 0.2)")
            color = CssTheme.colorDanger
        }

        ".badge.badge-info" {
            backgroundColor = CssTheme.colorBlueGlassBg
            borderColor = CssTheme.colorBlueGlassBorder
            color = CssTheme.colorBlueAccent
        }

        ".text-success" {
            color = CssTheme.colorSuccess
        }

        ".${CssClass.Utility.TextDanger}" {
            color = CssTheme.colorDanger
        }
    }
}
