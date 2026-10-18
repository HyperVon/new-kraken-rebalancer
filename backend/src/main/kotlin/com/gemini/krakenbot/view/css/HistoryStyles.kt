package com.gemini.krakenbot.view.css

import com.gemini.krakenbot.view.util.ChartProps
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlIds
import kotlinx.css.Align
import kotlinx.css.CssBuilder
import kotlinx.css.Display
import kotlinx.css.GridTemplateColumns
import kotlinx.css.Padding
import kotlinx.css.Position
import kotlinx.css.TextAlign
import kotlinx.css.alignItems
import kotlinx.css.background
import kotlinx.css.borderRadius
import kotlinx.css.color
import kotlinx.css.display
import kotlinx.css.fontFamily
import kotlinx.css.fontSize
import kotlinx.css.gap
import kotlinx.css.gridTemplateColumns
import kotlinx.css.height
import kotlinx.css.marginBottom
import kotlinx.css.padding
import kotlinx.css.pct
import kotlinx.css.position
import kotlinx.css.px
import kotlinx.css.rem
import kotlinx.css.textAlign
import kotlinx.css.width

object HistoryStyles {
    fun CssBuilder.applyHistoryStyles() {
        ".${CssClass.History.PageHeading}" {
            marginBottom = 1.rem
        }
        ".${CssClass.History.PageHeading} h1" {
            marginBottom = 0.375.rem
        }
        ".${CssClass.History.PageHeading} p" {
            color = CssTheme.colorTextSecondary
            fontSize = 0.875.rem
            put("margin", "0")
            lineHeightRaw("1.6")
        }
        "#${HtmlIds.HISTORY_SAVED_VIEWS}[hidden]" { display = Display.none }
        "#${HtmlIds.HISTORY_SAVED_VIEWS}" {
            alignItems = Align.end
            padding = Padding(0.rem)
            width = 100.pct
            put("margin", "0")
        }
        ".${CssClass.History.ChartControls} .active" {
            color = CssTheme.colorTextPrimary
            background = CssTheme.colorBlueGlassBgHover.value
            solidBorder(CssTheme.colorBlueAccent)
        }
        ".${CssClass.History.GridLine}" {
            strokeRaw(CssTheme.colorBorderSubtle.value)
            strokeWidthRaw("1")
        }
        ".${CssClass.History.StatsGrid}" {
            display = Display.grid
            gridTemplateColumns = GridTemplateColumns("repeat(2, minmax(0, 1fr))")
            gap = 0.75.rem
            marginBottom = 1.rem
            alignItems = Align.start
        }

        ".${CssClass.History.ToolbarRow}" {
            display = Display.flex
            alignItems = Align.center
            flexWrapRaw("wrap")
            gap = 0.75.rem
            marginBottom = 1.rem
            padding = Padding(0.875.rem)
            background = CssTheme.colorGlass.value
            solidBorder(CssTheme.colorGlassBorder)
            borderRadius = CssTheme.radiusLg
        }
        ".${CssClass.History.TimeRangeSelector}" {
            width = 100.pct
            display = Display.flex
            gap = 0.375.rem
            flexWrapRaw("wrap")
        }
        ".${CssClass.History.TimeRangeBtn}" {
            display = Display.inlineFlex
            alignItems = Align.center
            justifyContentRaw("center")
            minHeightRaw("2.125rem")
            padding = Padding(0.375.rem, 0.75.rem)
            put("text-decoration", "none")
            boxSizingRaw("border-box")
            fontSize = 0.8125.rem
            color = CssTheme.colorTextSecondary
            background = CssTheme.colorGlass.value
            solidBorder(CssTheme.colorGlassBorder)
            borderRadius = CssTheme.radiusMd
            fontFamily = CssTheme.fontSans
        }
        CssClass.History.TimeRangeBtnActive.querySelector {
            color = CssTheme.colorTextPrimary
            background = CssTheme.colorBlueGlassBgHover.value
            solidBorder(CssTheme.colorBlueAccent)
            boxShadowRaw(CssTheme.insetTopHighlight)
        }
        ".${CssClass.History.TimeRangeBtn}:hover, .${CssClass.History.PaginationBtn}:hover" {
            color = CssTheme.colorTextPrimary
            background = CssTheme.colorGlassHover.value
            solidBorder(CssTheme.colorGlassBorderHover)
        }
        ".${CssClass.History.TimeRangeBtn}:focus-visible, .${CssClass.History.PaginationBtn}:focus-visible" {
            boxShadowRaw(CssTheme.focusRingCompact)
        }
        ".${CssClass.History.ChartHeader}" {
            display = Display.flex
            alignItems = Align.center
            justifyContentRaw("space-between")
            flexWrapRaw("wrap")
            gap = 0.75.rem
            marginBottom = 1.rem
        }
        ".${CssClass.History.ChartControls}:not([hidden])" {
            display = Display.flex
            alignItems = Align.center
            flexWrapRaw("wrap")
            gap = 0.375.rem
        }
        ".${CssClass.History.ChartControls} .${CssClass.History.FilterInput}" {
            width = 10.rem
        }
        ".${CssClass.History.ChartHeaderTitle}, .${CssClass.History.TitleNoMargin}" {
            marginBottom = 0.px
        }
        ".${CssClass.History.ChartContainer}" {
            height = 20.rem
            position = Position.relative
            minWidthRaw("0")
            background = CssTheme.colorGlassSurfaceFaint.value
            solidBorder(CssTheme.colorBorderSubtle)
            borderRadius = CssTheme.radiusMd
        }
        ".${CssClass.History.ChartExpanded} .${CssClass.History.ChartContainer}" {
            put("height", "min(65vh, 40rem)")
            minHeightRaw("24rem")
        }
        ".${CssClass.History.ChartContainer} > .${CssClass.History.MutedSmallText}:only-child" {
            display = Display.flex
            alignItems = Align.center
            justifyContentRaw("center")
            textAlign = TextAlign.center
            height = 100.pct
            padding = Padding(1.5.rem)
            put("margin", "0")
            boxSizingRaw("border-box")
            color = CssTheme.colorTextSecondary
            lineHeightRaw("1.6")
        }
        CssClass.History.ChartSvg.querySelector {
            display = Display.block
            width = 100.pct
            height = 100.pct
        }
        CssClass.History.Line.querySelector {
            fillRaw("none")
            strokeRaw(ChartProps.COLOR_BLUE)
            strokeWidthRaw("3")
            strokeLinejoinRaw("round")
            strokeLinecapRaw("round")
        }
        CssClass.History.Point.querySelector {
            fillRaw(ChartProps.COLOR_BLUE)
        }
        CssClass.History.GapMarker.querySelector {
            fillRaw(CssTheme.colorWarning.value)
        }
        CssClass.History.AxisLabel.querySelector {
            fillRaw(CssTheme.colorTextSecondary.value)
            fontSize = 0.6875.rem
            fontFamily = CssTheme.fontMono
        }
        ".${CssClass.History.ChartCaption}, .${CssClass.History.MutedSmallText}" {
            fontSize = 0.75.rem
            color = CssTheme.colorTextSecondary
            lineHeightRaw("1.6")
        }
        ".${CssClass.History.EmptyTableCell}" {
            textAlign = TextAlign.center
            color = CssTheme.colorTextMuted
            padding = Padding(2.rem)
        }
        ".${CssClass.History.TradeLog}" {
            marginBottom = 1.5.rem
        }
        ".${CssClass.History.TradeLogHeader}, .${CssClass.History.FilterRow}, .${CssClass.History.Pagination}" {
            display = Display.flex
            alignItems = Align.center
            flexWrapRaw("wrap")
            gap = 0.75.rem
            marginBottom = 0.75.rem
        }
        ".${CssClass.History.ToolbarRow} > .${CssClass.History.MutedSmallText}" {
            width = 100.pct
            put("margin", "0")
        }
        ".${CssClass.History.ToolbarRow} > .${CssClass.History.FilterRow}" {
            alignItems = Align.end
            gap = 0.625.rem
            put("margin", "0")
            put("flex", "1 1 100%")
        }
        ".${CssClass.History.FilterRow} > label" {
            display = Display.grid
            gap = 0.375.rem
            fontSize = 0.75.rem
            color = CssTheme.colorTextSecondary
            minWidthRaw("0")
            put("flex", "1 1 8rem")
        }
        ".${CssClass.History.FilterRow} label .${CssClass.History.FilterInput}" {
            width = 100.pct
        }
        ".${CssClass.History.FilterInput}" {
            padding = Padding(0.375.rem, 0.625.rem)
            fontSize = 0.8125.rem
            width = 12.rem
            maxWidthRaw("100%")
            minWidthRaw("0")
            minHeightRaw("2.125rem")
            boxSizingRaw("border-box")
            fontFamily = CssTheme.fontSans
            color = CssTheme.colorTextPrimary
            background = CssTheme.colorSurface2.value
            solidBorder(CssTheme.colorSurface2Border)
            borderRadius = CssTheme.radiusMd
        }
        ".${CssClass.History.PaginationBtn}" {
            padding = Padding(0.375.rem, 0.75.rem)
            minHeightRaw("2.125rem")
            fontFamily = CssTheme.fontSans
            whiteSpaceRaw("nowrap")
            put("cursor", "pointer")
            fontSize = 0.75.rem
            color = CssTheme.colorTextSecondary
            background = CssTheme.colorGlass.value
            solidBorder(CssTheme.colorGlassBorder)
            borderRadius = CssTheme.radiusSm
        }
        ".${CssClass.History.SyncBanner}" {
            marginBottom = 1.rem
            padding = Padding(1.rem)
            background = CssTheme.colorGlassSurfaceFaint.value
            boxShadowRaw(CssTheme.insetTopHighlight)
        }
        ".${CssClass.History.SectionNav}" {
            display = Display.flex
            flexWrapRaw("wrap")
            gap = 0.5.rem
            marginBottom = 1.rem
        }
        ".${CssClass.History.SyncHeader}" {
            display = Display.flex
            alignItems = Align.center
            justifyContentRaw("space-between")
            flexWrapRaw("wrap")
            gap = 0.75.rem
            marginBottom = 0.75.rem
        }
        ".${CssClass.History.SyncTitle}" {
            fontSize = 0.875.rem
            put("margin", "0")
        }
        ".${CssClass.History.SyncFeed}" {
            display = Display.inlineFlex
            alignItems = Align.center
            flexWrapRaw("wrap")
            gap = 0.5.rem
            put("margin", "0 1.5rem 0.5rem 0")
            maxWidthRaw("100%")
        }
        ".${CssClass.History.SyncFeed} p" {
            width = 100.pct
            put("margin", "0")
            color = CssTheme.colorWarning
        }
        ".${CssClass.History.SyncBanner} summary" {
            put("cursor", "pointer")
            fontSize = 0.8125.rem
            color = CssTheme.colorTextSecondary
            padding = Padding(0.375.rem, 0.rem)
        }
        ".${CssClass.History.SyncBanner} dl" {
            display = Display.grid
            gridTemplateColumns = GridTemplateColumns("minmax(8rem, auto) minmax(0, 1fr)")
            gap = 0.375.rem
        }
        ".${CssClass.History.SyncBanner} dd" { put("margin", "0") }
        ".${CssClass.History.TradeLog} .${CssClass.Table.Wrapper}" {
            put("max-height", "32rem")
            put("overflow", "auto")
        }
        ".${CssClass.History.TradeLog} thead" {
            position = Position.sticky
            put("top", "0")
            put("z-index", "1")
            background = CssTheme.colorSurface2.value
        }
        (
            "#${HtmlIds.HISTORY_PORTFOLIO_SECTION}, #${HtmlIds.HISTORY_TRADES_SECTION}, " +
                "#${HtmlIds.HISTORY_LEDGERS_SECTION}"
            ) {
            put("scroll-margin-top", "1rem")
        }
        ".${CssClass.History.SyncText}" {
            fontSize = 0.8125.rem
            color = CssTheme.colorTextSecondary
            lineHeightRaw("1.6")
            overflowWrapRaw("anywhere")
        }
        "@media (max-width: 639px)" {
            ".${CssClass.History.StatsGrid} > :first-child" { put("grid-column", "1 / -1") }
            ".${CssClass.History.StatsGrid} .${CssClass.StatusCard.Title}" { fontSize = 0.75.rem }
            ".${CssClass.History.StatsGrid} .${CssClass.StatusCard.Value}" { fontSize = 1.125.rem }
            ".${CssClass.History.PageHeading} h1" { fontSize = 1.5.rem }
            ".${CssClass.History.ToolbarRow}" {
                alignItems = Align.stretch
            }
            ".${CssClass.History.TimeRangeSelector}" {
                width = 100.pct
            }
            ".${CssClass.History.TimeRangeBtn}" {
                put("flex", "1 1 auto")
            }
            ".${CssClass.History.ChartContainer}" {
                height = 16.rem
            }
            ".${CssClass.History.ChartControls}:not([hidden])" {
                width = 100.pct
            }
            ".${CssClass.History.ChartHeaderTitle}" {
                fontSize = 1.rem
            }
        }
    }
}
