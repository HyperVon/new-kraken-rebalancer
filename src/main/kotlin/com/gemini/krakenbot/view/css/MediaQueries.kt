package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.ViewText
import kotlinx.css.Align
import kotlinx.css.BorderStyle
import kotlinx.css.CssBuilder
import kotlinx.css.Display
import kotlinx.css.FlexDirection
import kotlinx.css.FontWeight
import kotlinx.css.GridTemplateColumns
import kotlinx.css.Overflow
import kotlinx.css.Padding
import kotlinx.css.QuotedString
import kotlinx.css.TextAlign
import kotlinx.css.alignItems
import kotlinx.css.background
import kotlinx.css.borderBottomStyle
import kotlinx.css.borderRadius
import kotlinx.css.color
import kotlinx.css.columnGap
import kotlinx.css.content
import kotlinx.css.display
import kotlinx.css.flexDirection
import kotlinx.css.flexGrow
import kotlinx.css.flexShrink
import kotlinx.css.fontSize
import kotlinx.css.fontWeight
import kotlinx.css.gap
import kotlinx.css.gridTemplateColumns
import kotlinx.css.header
import kotlinx.css.marginBottom
import kotlinx.css.marginLeft
import kotlinx.css.marginRight
import kotlinx.css.minHeight
import kotlinx.css.minWidth
import kotlinx.css.overflowX
import kotlinx.css.padding
import kotlinx.css.paddingBottom
import kotlinx.css.paddingLeft
import kotlinx.css.paddingRight
import kotlinx.css.pct
import kotlinx.css.px
import kotlinx.css.rem
import kotlinx.css.rowGap
import kotlinx.css.textAlign
import kotlinx.css.width

object MediaQueries {
    fun CssBuilder.applyMediaQueries() {
        "@media (min-width: 640px)" {
            ".${CssClass.Form.AllocationListContainer}" {
                gridTemplateColumns = GridTemplateColumns("repeat(2, 1fr)")
            }
            ".${CssClass.History.StatsGrid}" {
                gridTemplateColumns = GridTemplateColumns("repeat(2, 1fr)")
            }
            ".${CssClass.History.ToolbarRow}" {
                flexWrapRaw("nowrap")
            }
        }

        "@media (min-width: 768px)" {
            ".${CssClass.Layout.Container}" {
                padding = Padding(1.5.rem, 1.5.rem, 4.rem, 1.5.rem)
            }
            header {
                flexDirection = FlexDirection.column
                justifyContentRaw("flex-start")
                alignItems = Align.stretch
                paddingBottom = 1.5.rem
                marginBottom = 1.75.rem
            }
            CssClass.StatusCard.Default.querySelector {
                gap = 0.375.rem
                padding = Padding(0.75.rem, 0.875.rem)
            }
            // DASH-1: hero KPI card (left) + stacked compact tiles (right) on wider viewports.
            ".${CssClass.Layout.HeroGrid}" {
                gridTemplateColumns = GridTemplateColumns("1.6fr 1fr")
            }
            ".${CssClass.History.StatsGrid}" {
                gridTemplateColumns = GridTemplateColumns("repeat(3, 1fr)")
            }
            ".${CssClass.Form.Grid2Col}" {
                gridTemplateColumns = GridTemplateColumns("1fr 1fr")
            }
            // SafetyToggles is display:grid (FormStyles); flexDirection would be a no-op.
            ".${CssClass.Form.SafetyToggles}" {
                gridTemplateColumns = GridTemplateColumns("1fr 1fr")
                gap = 1.5.rem
            }
        }

        "@media (min-width: 1024px)" {
            ".${CssClass.Layout.DetailGrid}" {
                gridTemplateColumns = GridTemplateColumns("1fr 1fr")
            }
            ".${CssClass.Form.AllocationListContainer}" {
                gridTemplateColumns = GridTemplateColumns("repeat(3, 1fr)")
            }
            ".${CssClass.History.StatsGrid}" {
                gridTemplateColumns = GridTemplateColumns("repeat(6, 1fr)")
                gap = 0.625.rem
            }
            ".${CssClass.History.StatsGrid} ${CssClass.StatusCard.Default.querySelector}" {
                padding = Padding(0.75.rem, 0.75.rem)
            }
            ".${CssClass.History.StatsGrid} .${CssClass.StatusCard.Value}" {
                fontSize = 1.2.rem
            }
            ".${CssClass.History.StatsGrid} .${CssClass.StatusCard.Title}" {
                fontSize = 0.75.rem
            }
        }

        "@media (min-width: 768px) and (max-width: 1160px)" {
            ".${CssClass.Layout.HeaderActions}" {
                width = 100.pct
                justifyContentRaw("center")
            }
            ".${CssClass.Layout.HeaderActions} > #${HtmlIds.SAVE_BUTTON}" {
                marginLeftRaw("0")
            }
        }

        // CI-11-UI6: tighten History 9-col trade log at laptop widths (~1280).
        "@media (max-width: 1280px)" {
            header {
                columnGap = 0.5.rem
            }
            ".${CssClass.Layout.HeaderTitleSection}" {
                gap = 0.5.rem
            }
            ".${CssClass.Layout.HeaderActions}" {
                gap = 0.5.rem
            }
            ".${CssClass.Layout.LoopControl}" {
                gap = 0.375.rem
            }
            ".${CssClass.Layout.LoopState}" {
                padding = Padding(0.3125.rem, 0.5.rem)
            }
            ".${CssClass.History.TradeLog} table" {
                fontSize = 0.8125.rem
            }
            ".${CssClass.History.TradeLog} th" {
                padding = Padding(0.45.rem, 0.3.rem)
                fontSize = 0.6875.rem
            }
            ".${CssClass.History.TradeLog} td" {
                padding = Padding(0.45.rem, 0.3.rem)
            }
        }

        "@media (max-width: 767px)" {
            header {
                flexDirection = FlexDirection.column
                alignItems = Align.center
                rowGap = 0.75.rem
            }
            ".${CssClass.Layout.HeaderTitleSection}" {
                width = 100.pct
                flexDirection = FlexDirection.column
                alignItems = Align.center
                gap = 0.5.rem
            }
            ".${CssClass.Layout.HeaderActions}" {
                width = 100.pct
                justifyContentRaw("center")
                gap = 0.5.rem
                rowGap = 0.5.rem
            }
            ".${CssClass.Layout.HeaderActions} > #${HtmlIds.SAVE_BUTTON}" {
                marginLeftRaw("0")
            }
            ".${CssClass.Layout.GlassPanel}" {
                padding = Padding(1.rem)
            }
            ".${CssClass.History.ToolbarRow}" {
                flexDirection = FlexDirection.column
                alignItems = Align.stretch
            }
            ".${CssClass.History.TimeRangeSelector}" {
                width = 100.pct
                justifyContentRaw("center")
            }
            ".${CssClass.History.ViewsToolbar}" {
                width = 100.pct
                justifyContentRaw("center")
            }
            ".${CssClass.History.ViewsActions}" {
                width = 100.pct
                justifyContentRaw("center")
            }
            ".hero-card" {
                flexDirection = FlexDirection.column
                gap = 0.75.rem
            }
            ".hero-card > .hero-card-text" {
                flexShrink = 1.0
            }
            ".${CssClass.Hero.Spark.value}" {
                width = 100.pct
                minWidth = 0.px
                minHeight = 4.rem
                flexGrow = 0.0
            }
            ".${CssClass.Form.AddAssetBox}" {
                flexDirection = FlexDirection.column
            }
            ".${CssClass.Form.AddAssetBox} > .btn" {
                width = 100.pct
                justifyContentRaw("center")
            }
        }

        "@media (max-width: 639px)" {
            // DASH-MOBILE-1: keep the operator's most useful performance columns visible.
            listOf(2, 4, 5).forEach { column ->
                ".${CssClass.Layout.DetailGrid} table th:nth-child($column)" {
                    display = Display.none
                }
                ".${CssClass.Layout.DetailGrid} table td:nth-child($column)" {
                    display = Display.none
                }
            }

            // HIST-MOBILE-1: turn each dense trade row into a contained detail card.
            ".${CssClass.History.TradeLogHeader}" {
                flexDirection = FlexDirection.column
                alignItems = Align.stretch
                gap = 0.75.rem
            }
            ".${CssClass.History.TradeLog} .${CssClass.Form.CheckboxContainer}" {
                justifyContentRaw("flex-start")
            }
            ".${CssClass.History.TradeLog} .${CssClass.Table.Wrapper}" {
                overflowX = Overflow.visible
                marginLeft = 0.px
                marginRight = 0.px
                paddingLeft = 0.px
                paddingRight = 0.px
            }
            ".${CssClass.History.TradeLog} table" {
                display = Display.block
                width = 100.pct
            }
            ".${CssClass.History.TradeLog} thead" {
                display = Display.none
            }
            ".${CssClass.History.TradeLog} tbody" {
                display = Display.grid
                gap = 0.75.rem
            }
            ".${CssClass.History.TradeLog} tr" {
                display = Display.grid
                gridTemplateColumns = GridTemplateColumns("repeat(2, minmax(0, 1fr))")
                rowGap = 0.25.rem
                columnGap = 0.75.rem
                padding = Padding(0.75.rem)
                background = CssTheme.colorSurface2.value
                borderRadius = CssTheme.radiusMd
                solidBorder(CssTheme.colorSurface1Border)
            }
            ".${CssClass.History.TradeLog} td" {
                display = Display.flex
                alignItems = Align.center
                justifyContentRaw("space-between")
                gap = 0.5.rem
                minWidth = 0.px
                padding = Padding(0.25.rem, 0.px)
                borderBottomStyle = BorderStyle.none
                textAlign = TextAlign.right
                fontSize = 0.75.rem
            }
            ".${CssClass.History.TradeLog} td:not(.${CssClass.History.EmptyTableCell})::before" {
                flexShrink = 0.0
                color = CssTheme.colorTextMuted
                fontSize = 0.625.rem
                fontWeight = FontWeight.w600
                letterSpacingRaw("0.05em")
                textTransformRaw("uppercase")
                textAlign = TextAlign.left
            }
            ".${CssClass.History.TradeLog} td:nth-child(1)::before" { content = quoted(ViewText.HEADER_TIME) }
            ".${CssClass.History.TradeLog} td:nth-child(2)::before" { content = quoted(ViewText.HEADER_PAIR) }
            ".${CssClass.History.TradeLog} td:nth-child(3)::before" { content = quoted(ViewText.HEADER_SIDE) }
            ".${CssClass.History.TradeLog} td:nth-child(4)::before" { content = quoted(ViewText.HEADER_VOLUME) }
            ".${CssClass.History.TradeLog} td:nth-child(5)::before" {
                content = quoted(ViewText.HEADER_USD_AMOUNT)
            }
            ".${CssClass.History.TradeLog} td:nth-child(6)::before" { content = quoted(ViewText.HEADER_PRICE) }
            ".${CssClass.History.TradeLog} td:nth-child(7)::before" { content = quoted(ViewText.HEADER_FEE) }
            ".${CssClass.History.TradeLog} td:nth-child(8)::before" { content = quoted(ViewText.HEADER_SLIPPAGE) }
            ".${CssClass.History.TradeLog} td:nth-child(9)::before" { content = quoted(ViewText.HEADER_STATUS) }
            ".${CssClass.History.TradeLog} .${CssClass.History.EmptyTableCell}" {
                put("grid-column", "1 / -1")
            }
        }
    }

    private fun quoted(value: String) = QuotedString(value)
}
